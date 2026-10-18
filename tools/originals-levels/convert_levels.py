#!/usr/bin/env python3
"""Converts the supplied platformer level pack (cores/originals/assets/src/levels) into Blizzard Buddies' Adventure mode.

The pack's JSON is a generic side-scroller schema (32 px tiles, 85 x 20 tile worlds, one row per platform). This script
maps it onto the game's own representation - the same tile kinds the arena levels use (empty, solid, one-way, ice) on
a wider board, plus a list of placed things - and fixes what the pack gets wrong, printing every fix:

  * exits (and level 10's boss arena) placed past the end of the world are moved onto the last platform, and level 10
    gets the arena it describes built after its last platform (a corridor, a walled arena floor, the exit inside it);
  * checkpoints and exits are given on the ground row while everything else stands one row above it: all things are
    put on the surface below them;
  * "spikes" sit at row 18 of 20, often inside the ground: over a platform they become spikes on its surface (a hazard
    to jump), over a pit they line the pit's bottom (a pit is deadly anyway);
  * ladders lead nowhere (and Up is jump in this game): they are scenery standing on the surface;
  * level 2 asks for a 5-row climb (a full jump is 4.86 rows): a spring (the pack's spring art) is added below it;
  * the pack's unused art gets a place: one gem per level on a high spot, lamps by checkpoints and exits, crates as
    obstacles in the mine, cloud platforms for Windy Heights.

Then it checks every level with the game's own movement code ported line for line (update_player and move_body in
blizzard.c): running, jumping with full and released jump, falling, landing, one-way platforms and ice. It searches
every surface position reachable from the start and fails if the exit, the checkpoint, a coin or the gem cannot be
reached, if a thing is buried in a wall, if spikes cannot be passed, or if the camera cannot show something.

Output: cores/originals/levels_data.h (tile grids, things, 32 px tile art, parallax backgrounds), the converted levels
as cores/originals/assets/adventure_levels.json, and a report.

Usage: python3 tools/originals-levels/convert_levels.py [--preview DIR]      (Python 3, Pillow, numpy)
"""
import collections
import json
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
PACK = os.path.join(ROOT, 'cores', 'originals', 'assets', 'src', 'levels')
HEADER = os.path.join(ROOT, 'cores', 'originals', 'levels_data.h')
OUT_JSON = os.path.join(ROOT, 'cores', 'originals', 'assets', 'adventure_levels.json')

SCREEN_W, SCREEN_H = 32, 18          # the camera shows 32 x 18 tiles at every picture size
HUD_ROWS = 1.8                       # the score panels cover the top 1.8 tiles of the screen

# tile kinds, as in blizzard.c: 0 empty, 1 solid, 2 one-way, 3 one-way ice, 4 solid ice, 5 solid crate
EMPTY, SOLID, ONEWAY, ONEWAY_ICE, SOLID_ICE, CRATE = 0, 1, 2, 3, 4, 5
TILE_ART = ['snow_ground', 'stone', 'lava_rock', 'forest_ground', 'ice', 'snow_platform', 'cloud_platform', 'crate']
THEMES = ['snow', 'cave', 'lava', 'forest', 'crystal']
THING = dict(coin=1, gem=2, spikes=3, pitspikes=4, ladder=5, lamp=6, crate=7, enemy=8, spring=9)
SPRING_VY = -.68                     # a spring's launch speed (blizzard.c SPRING_VY): about 6.8 rows high
# springs the levels need: the pack's level 2 asks for a 5-row climb (x 27 row 16 to x 30 row 11); a full jump is 4.86
SPRINGS = {2: [27]}
ENEMY = dict(walker=0, hopper=1, flyer=2, spitter=4, charger=5)          # E_WALK.. in blizzard.c

# per level: how platforms are built ('column' = solid ground down to the bottom, 'float' = one-row one-way platforms)
STYLE = {6: 'float'}
FLOAT_ART = {6: 'cloud_platform'}


# ------------------------------------------------------------------ the game's movement code (blizzard.c), ported

class World:
    def __init__(self, grid):
        self.g = grid
        self.h, self.w = grid.shape

    def tile(self, c, r):
        if r < 0:
            return EMPTY
        if r >= self.h:
            return EMPTY                      # adventure: below the world is a pit
        if c < 0 or c >= self.w:
            return SOLID
        return int(self.g[r, c])


def is_solid(t): return t in (SOLID, SOLID_ICE, CRATE)
def is_oneway(t): return t in (ONEWAY, ONEWAY_ICE)
def is_ice(t): return t in (ONEWAY_ICE, SOLID_ICE)


def move_body(W, x, y, vx, vy, hw, h, drop):
    f32 = np.float32
    flags = 0
    nx = f32(x + vx)
    r0, r1 = math.floor(y - h + .02), math.floor(y - .02)
    if vx > 0:
        c = math.floor(nx + hw)
        for r in range(r0, r1 + 1):
            if is_solid(W.tile(c, r)):
                nx = f32(c - hw - .001); flags |= 2; break
    elif vx < 0:
        c = math.floor(nx - hw)
        for r in range(r0, r1 + 1):
            if is_solid(W.tile(c, r)):
                nx = f32(c + 1 + hw + .001); flags |= 2; break
    if nx - hw < 0:
        nx = f32(hw); flags |= 2
    if nx + hw > W.w:
        nx = f32(W.w - hw); flags |= 2
    x = nx
    ny = f32(y + vy)
    c0, c1 = math.floor(x - hw + .02), math.floor(x + hw - .02)
    if vy >= 0:
        done = False
        for r in range(math.ceil(y - .001), math.floor(ny) + 1):
            for c in range(c0, c1 + 1):
                t = W.tile(c, r)
                if is_solid(t) or (is_oneway(t) and not drop):
                    ny = f32(r); vy = 0.0; flags |= 1; done = True; break
            if done:
                break
    else:
        r = math.floor(ny - h)
        for c in range(c0, c1 + 1):
            if is_solid(W.tile(c, r)):
                ny = f32(r + 1 + h + .001); vy = 0.0; flags |= 4; break
    return float(x), float(ny), vx, vy, flags


def on_spring(x, y, springs):
    return any(abs(x - sx) < .55 and abs(y - sy) < .01 for sx, sy in springs)


def simulate(W, x, y, vx, plan, hazards, frames=150, springs=(), runup=False):
    """One move from standing at (x, y) with speed vx. plan = (jump, holdJump, dir1, dir2, switchFrame). Returns
    (landing x, y, path) or None if it dies (pit, spikes) or never lands. Mirrors update_player: acceleration toward
    the stick, jump buffer/coyote, early release, rise and fall gravity, the speed cap."""
    jump, hold, d1, d2, k = plan
    vy = 0.0
    ground = True
    spring_t = 0
    if runup:
        # the run-up, as the test bot (and a player) does it: from standing 0.95 tiles back, run, and go on the first
        # frame past the take-off point - on ice the speed builds slowly, and the take-off lands a little late
        x0, x, vx = x, x - d1 * .95, 0.0
        for _ in range(120):
            if (x - x0) * d1 >= 0:
                break
            on_ice = is_ice(W.tile(math.floor(x), math.floor(y + .01)))
            vx += (d1 * .13 - vx) * (.05 if on_ice else .6)
            vy += .045
            x, y, vx, vy, fl = move_body(W, x, y, vx, vy, .4, 1.0, False)
            if not fl & 1 or hits_hazard(x, y, hazards):
                return None
        else:
            return None
    path = []
    left_ground = False
    landed = False
    for t in range(frames):
        d = 0 if landed else (d1 if t < k else d2)          # after landing the stick is let go: the player slides to a stop
        on_ice = ground and is_ice(W.tile(math.floor(x), math.floor(y + .01)))
        target = d * .13
        vx += (target - vx) * (.05 if on_ice else (.6 if ground else .3))
        if target == 0 and not on_ice and abs(vx) < .01:
            vx = 0.0
        if t == 0 and ground and on_spring(x, y, springs):
            vy = SPRING_VY; ground = False; spring_t = 24
        elif jump and t == 0 and ground:
            vy = -.575; ground = False
        if spring_t > 0:
            spring_t -= 1
        elif not ((hold and t < 40) or (jump and t == 0)) and vy < -.25:      # the press frame counts as held
            vy = -.25
        vy += .034 if vy < 0 else .045
        if vy > .62:
            vy = .62
        x, y, vx, vy, fl = move_body(W, x, y, vx, vy, .4, 1.0, False)
        ground = (fl & 1) != 0
        if ground and on_spring(x, y, springs) and t > 0:
            vy = SPRING_VY; ground = False; spring_t = 24; fl &= ~1
        path.append((x, y))
        if not ground:
            left_ground = True
        if y > W.h + 1:
            return None
        for (hx, hy) in hazards:
            if abs(x - hx) < .4 + .32 and y > hy + .45 and y - 1.0 < hy + 1:
                return None
        if ground and left_ground and t > 0:
            landed = True
        if landed and ground and vx == 0.0:
            return x, y, path
        if ground and not jump and t > 25:
            return x, y, path
    return None


PLAN_K = (0, 6, 12, 20)


def plan_code(plan, runup):
    """A move as one byte for the test bot: jump, hold, dir1+1, dir2+1, the switch frame's index, run-up."""
    jump, hold, d1, d2, k = plan
    return jump | hold << 1 | (d1 + 1) << 2 | (d2 + 1) << 4 | PLAN_K.index(k) << 6, int(runup)


def explore(W, start, hazards, springs=(), parents=None):
    """Every standing spot reachable from the start (x snapped to quarter tiles) and every point any move passes.
    With parents (a dict), records how each spot was first reached: (previous spot, plan, run-up) or a walk."""
    seen = set()
    todo = collections.deque([start])
    if parents is not None:
        parents[(round(start[0] * 4), round(start[1] * 4))] = None
    points = []
    plans = []
    for jump in (1, 0):
        for d1 in (-1, 0, 1):
            for d2 in (-1, 0, 1):
                for k in ((0,) if d1 == d2 else (6, 12, 20)):
                    plans.append((jump, 1, d1, d2, k))
        plans.append((1, 0, 1, 1, 0)); plans.append((1, 0, -1, -1, 0))      # short hops
    while todo:
        x, y = todo.popleft()
        key = (round(x * 4), round(y * 4))
        if key in seen:
            continue
        seen.add(key)
        points.append((x, y))
        # jumps only from spots with footing to spare on both sides: nobody stands to a sixteenth of a tile
        robust = all(is_solid_or_oneway(W, x + e, y) and not hits_hazard(x + e, y, hazards) for e in (-.12, .12))
        for plan in plans if robust else []:
            d = plan[2]
            runup = d != 0 and all(is_solid_or_oneway(W, x - d * .95 + e, y) and not hits_hazard(x - d * .95 + e, y, hazards)
                                   for e in (-.12, .12)) and not is_solid(W.tile(math.floor(x - d * 1.4), round(y) - 1))
            for ru in (False, True) if runup else (False,):
                r = simulate(W, x, y, 0.0, plan, hazards, springs=springs, runup=ru)
                if r is None:
                    continue
                lx, ly, path = r
                points.extend(path[::2])
                nk = (round(lx * 4), round(ly * 4))
                if nk not in seen:
                    todo.append((round(lx * 4) / 4, ly))
                    if parents is not None and nk not in parents:
                        parents[nk] = (key, plan, ru, path)
        # walking along the surface: neighbours a quarter tile away
        for dx in (-.25, .25):
            nx = x + dx
            if 0.4 <= nx <= W.w - .4 and is_solid_or_oneway(W, nx, y) and not hits_hazard(nx, y, hazards):
                nk = (round(nx * 4), round(y * 4))
                if nk not in seen:
                    todo.append((nx, y))
                    if parents is not None and nk not in parents:
                        parents[nk] = (key, None, False, [(nx, y)])
    return seen, points


def is_solid_or_oneway(W, x, y):
    r = round(y)
    for c in (math.floor(x - .38), math.floor(x + .38)):
        t = W.tile(c, r)
        if is_solid(t) or is_oneway(t):
            if not is_solid(W.tile(math.floor(x), r - 1)):
                return True
    return False


def hits_hazard(x, y, hazards):
    return any(abs(x - hx) < .72 and y > hy + .45 and y - 1.0 < hy + 1 for hx, hy in hazards)


def touches(points, cx, cy, rx=.6, up=.5, down=.5):
    """A player at any of points overlaps the thing centred at (cx, cy) (the game's pick-up test)."""
    return any(abs(px - cx) < rx and py > cy - down and py - 1.0 < cy + up for px, py in points)


# ------------------------------------------------------------------ conversion

def surface_below(grid, x, y):
    """Feet row of the first ground at or below row y in column x (None over a pit)."""
    h = grid.shape[0]
    y = max(0, min(y, h - 1))
    if grid[y, x] != EMPTY:                    # given inside the ground: climb to the top of it
        while y > 0 and grid[y - 1, x] != EMPTY:
            y -= 1
        return y
    for r in range(y, h):
        if grid[r, x] != EMPTY:
            return r
    return None


def convert(L, fixes):
    lid = L['id']
    ww, wh = L['world_tiles']['width'], L['world_tiles']['height']
    style = STYLE.get(lid, 'column')
    plats = L['platforms']
    boss = L.get('boss')
    if boss:                                   # the arena lies past the end of the world: make room for it
        ww2 = max(boss['x'] + 12, L['exit']['x'] + 3)
        fixes.append(f"world widened from {ww} to {ww2} tiles for the boss arena (x {boss['arena_start_x']}..{ww2 - 1}), "
                     f"which the pack places past the end of its {ww}-tile world")
        ww = ww2
    grid = np.zeros((wh, ww), np.uint8)
    art = {p['tile'] for p in plats}
    assert len(art) == 1, f'level {lid}: one tile art per level expected'
    art = art.pop()
    kind = SOLID_ICE if art == 'ice' else SOLID
    for p in plats:
        x0, x1, y = p['x'], min(ww, p['x'] + p['width']), p['y']
        if style == 'column':
            grid[y:, x0:x1] = kind
        else:
            grid[y, x0:x1] = ONEWAY_ICE if art == 'ice' else ONEWAY
    things = []
    arena = None
    if boss:
        last = max(p['x'] + p['width'] for p in plats)
        floor = 17
        grid[floor:, last:ww] = kind                                     # corridor and arena floor
        a0 = boss['arena_start_x']
        grid[:floor, ww - 1] = SOLID                                     # the arena's far wall
        for x0, x1, row in ((a0 + 4, a0 + 9, 13), (ww - 10, ww - 5, 13), (a0 + 9, ww - 10, 10)):
            grid[row, x0:x1] = ONEWAY                                    # platforms to dodge the fire on
        fixes.append(f"built the corridor (x {last}..{a0 - 1}) and the boss arena (floor row {floor}, wall at x {ww - 1}, "
                     f"three one-way ledges); the gate at x {a0 - 1} closes when the arena starts")
        bx = boss['x']
        arena = dict(x0=a0, x1=ww, boss_x=bx, boss_y=floor, phases=boss.get('phases', 3))

    def put(x, y, what):
        s = surface_below(grid, x, y)
        if s is None:
            # find the nearest column with ground
            for d in range(1, 12):
                for xx in (x - d, x + d):
                    if 0 <= xx < ww and surface_below(grid, xx, y) is not None:
                        fixes.append(f"{what} at x {x} stood over a pit: moved to x {xx}")
                        return xx, surface_below(grid, xx, y)
            raise SystemExit(f'level {lid}: nowhere to put {what}')
        return x, s

    sx, sy = put(L['player_start']['x'], L['player_start']['y'], 'start')
    ex, ey = L['exit']['x'], L['exit']['y']
    if ex >= ww or (not boss and ex > max(p['x'] + p['width'] for p in plats) - 1):
        lastp = max(plats, key=lambda p: p['x'] + p['width'])
        nx = lastp['x'] + lastp['width'] - 3
        fixes.append(f"exit at x {ex} is past the end of the {ww}-tile world: moved onto the last platform at x {nx}")
        ex = nx
    ex, ey = put(ex, ey - 1, 'exit')
    cx, cy = put(L['checkpoint']['x'], L['checkpoint']['y'] - 1, 'checkpoint')
    if (L['checkpoint']['y'], L['exit']['y']) != (cy, ey) and not any('ground row' in f for f in fixes):
        fixes.append('checkpoint and exit are given on the ground row: stood on top of it')

    for c in L['collectibles']:
        x, y = c['x'], c['y']
        while y > 0 and grid[y, x] != EMPTY:
            y -= 1
        things.append(dict(kind='coin' if c['type'] == 'coin' else 'gem', x=x, y=y))
    for hz in L['hazards']:
        assert hz['type'] == 'spikes'
        x = hz['x']
        s = surface_below(grid, x, 0)
        if s is None:
            things.append(dict(kind='pitspikes', x=x, y=wh - 1))
            fixes.append(f"spikes at ({x}, {hz['y']}) hang in a pit: they line the pit's bottom")
        else:
            # keep spikes 3 tiles clear of the platform's ends: a fair run-up and landing on both sides
            x0 = x
            while x0 > 0 and grid[s, x0 - 1] != EMPTY and grid[s - 1, x0 - 1] == EMPTY:
                x0 -= 1
            x1 = x
            while x1 < ww - 1 and grid[s, x1 + 1] != EMPTY and grid[s - 1, x1 + 1] == EMPTY:
                x1 += 1
            # ... and 3 tiles from the start and the checkpoint (a respawn must not land on them)
            ok = [c for c in range(x0 + 3, x1 - 2) if abs(c - cx) >= 3 and abs(c - sx) >= 3]
            if not ok:
                fixes.append(f"spikes at ({x}, {hz['y']}): no fair place on that platform, left out")
                continue
            nx = min(ok, key=lambda c: abs(c - x))
            things.append(dict(kind='spikes', x=nx, y=s))
            fixes.append(f"spikes at ({x}, {hz['y']}) are inside the ground: moved onto its surface (row {s})" +
                         (f", at x {nx} to keep 3 tiles from its ends, the start and the checkpoint" if nx != x else ''))
    for pr in L['props']:
        assert pr['type'] == 'ladder'
        x, s = put(pr['x'], pr['y'], 'ladder')
        things.append(dict(kind='ladder', x=x, y=s, arg=max(2, min(4, s - pr['y']))))
    if L['props']:
        fixes.append('ladders are scenery: none leads anywhere a jump cannot reach, and Up is jump in this game')
    for en in L['enemies']:
        t = en['type']
        x, y = en['x'], en['y'] + 1
        if t != 'flyer':
            x, y = put(x, en['y'], t)
        things.append(dict(kind='enemy', x=x, y=y, arg=ENEMY[t], patrol=en.get('patrol_tiles', 3)))
    # the pack's unused art: a gem on a high spot, lamps by the checkpoint and exit, crates in the mine
    tops = [(p['y'], p['x'] + p['width'] // 2) for p in plats[1:-1]]
    gy, gx = min(tops)
    things.append(dict(kind='gem', x=gx, y=gy - 3))
    fixes.append(f"added a gem (the pack's gem art) 3 rows over the highest platform, x {gx}")
    if lid == 8:
        for p in (plats[3], plats[5]):
            x = p['x'] + p['width'] - 3
            grid[p['y'] - 1, x] = CRATE
        fixes.append('added two crates (the pack\'s crate art) as solid obstacles on mine platforms')
    if L['theme'] in ('cave', 'lava', 'forest', 'crystal'):
        for x, y in ((cx - 2, cy), (ex + 2, ey)):
            if 0 <= x < ww and grid[y, x] != EMPTY and grid[y - 1, x] == EMPTY:
                things.append(dict(kind='lamp', x=x, y=y))
    for x in SPRINGS.get(lid, []):
        x, y = put(x, 0, 'spring')
        things.append(dict(kind='spring', x=x, y=y))
        fixes.append(f'added a spring (the pack\'s spring art) at x {x}: the pack asks for a climb higher than a full jump there')
    tile_art = FLOAT_ART.get(lid, art)
    return dict(id=lid, name=L['name'], theme=L['theme'], art=tile_art, w=ww, h=wh, grid=grid, start=(sx, sy),
                checkpoint=(cx, cy), exit=(ex, ey), time=L['time_limit_seconds'], things=things, arena=arena, style=style)


def find_route(W, start, hazards, springs, goal):
    """The moves (take-off x, y, plan code, run-up) of a shortest way from start to a spot where goal(x, y) holds."""
    parents = {}
    seen, _ = explore(W, start, hazards, springs, parents)
    order = sorted(seen, key=lambda k: abs(k[0] / 4 - start[0]))
    target = None
    for k, v in parents.items():
        if v is None:
            continue
        if any(goal(px, py) for px, py in v[3]):
            target = k
            break
    if target is None:
        return None
    moves = []
    k = target
    while parents[k] is not None:
        prev, plan, runup, _ = parents[k]
        if plan is not None:
            code, ru = plan_code(plan, runup)
            moves.append((prev[0], prev[1], code, ru))
        k = prev
    moves.reverse()
    return moves


def validate(lv):
    """Raises on anything a player could not do; returns notes."""
    notes = []
    grid = lv['grid'].copy()
    W = World(grid)
    lid = lv['id']
    assert lv['w'] >= SCREEN_W and lv['h'] >= SCREEN_H, f'level {lid}: world smaller than the screen'
    for t in lv['things'] + [dict(kind='start', x=lv['start'][0], y=lv['start'][1]),
                             dict(kind='exit', x=lv['exit'][0], y=lv['exit'][1]),
                             dict(kind='checkpoint', x=lv['checkpoint'][0], y=lv['checkpoint'][1])]:
        assert 0 <= t['x'] < lv['w'] and 0 <= t['y'] < lv['h'], f"level {lid}: {t['kind']} outside the world"
        if t['kind'] in ('coin', 'gem', 'start', 'exit', 'checkpoint', 'spikes', 'lamp', 'ladder') or \
                (t['kind'] == 'enemy' and t['arg'] != ENEMY['flyer']):
            row = t['y'] if t['kind'] in ('coin', 'gem') else t['y'] - 1
            assert grid[row, t['x']] == EMPTY, f"level {lid}: {t['kind']} at ({t['x']}, {row}) is inside a wall"
        if t['kind'] in ('coin', 'gem') and t['y'] - (lv['h'] - SCREEN_H) < HUD_ROWS:
            raise AssertionError(f"level {lid}: {t['kind']} under the score panels")
    hazards = [(t['x'] + .5, t['y'] - 1) for t in lv['things'] if t['kind'] == 'spikes']
    springs = [(t['x'] + .5, float(t['y'])) for t in lv['things'] if t['kind'] == 'spring']
    sx, sy = lv['start']
    seen, points = explore(W, (sx + .5, float(sy)), hazards, springs)
    ex, ey = lv['exit']
    assert touches(points, ex + .5, ey - .8, rx=.8, up=.8, down=.8), f'level {lid}: the exit cannot be reached'
    cx, cy = lv['checkpoint']
    assert touches(points, cx + .5, cy - .8, rx=.8, up=.8, down=.8), f'level {lid}: the checkpoint cannot be reached'
    for t in lv['things']:
        if t['kind'] in ('coin', 'gem'):
            assert touches(points, t['x'] + .5, t['y'] + .5), f"level {lid}: {t['kind']} at ({t['x']}, {t['y']}) cannot be reached"
    # from the checkpoint the exit must be reachable too (respawn there)
    _, p2 = explore(W, (cx + .5, float(cy)), hazards, springs)
    assert touches(p2, ex + .5, ey - .8, rx=.8, up=.8, down=.8), f'level {lid}: the exit cannot be reached from the checkpoint'
    if lv['arena']:                            # inside the arena with its gate shut: the exit is still reachable
        a = lv['arena']
        assert a['x1'] - a['x0'] <= SCREEN_W, 'the arena must fit on one screen'
        g2 = grid.copy()
        g2[:17, a['x0'] - 1] = SOLID
        _, p3 = explore(World(g2), (a['x0'] + .5, 17.0), hazards, springs)
        assert touches(p3, ex + .5, ey - .8, rx=.8, up=.8, down=.8), f'level {lid}: the exit cannot be reached in the arena'
        assert touches(p3, a['x0'] + 5.5, 12.5) and touches(p3, a['x0'] + 12, 9.5), f'level {lid}: arena ledges unreachable'
    gx, gy = ex + .5, ey - .8
    lv['route'] = find_route(W, (sx + .5, float(sy)), hazards, springs,
                             lambda px, py: abs(px - gx) < .8 and py > gy - .8 and py - 1 < gy + .8)
    lv['route3'] = find_route(W, (cx + .5, float(cy)), hazards, springs,
                              lambda px, py: abs(px - gx) < .8 and py > gy - .8 and py - 1 < gy + .8)
    if lv['arena']:
        a = lv['arena']
        g2 = grid.copy()
        g2[:17, a['x0'] - 1] = SOLID
        lv['route'] = find_route(W, (sx + .5, float(sy)), hazards, springs, lambda px, py: px > a['x0'] + 2.5)
        lv['route3'] = find_route(W, (cx + .5, float(cy)), hazards, springs, lambda px, py: px > a['x0'] + 2.5)
        lv['route2'] = find_route(World(g2), (a['x0'] + 3.0, 17.0), hazards, springs,
                                  lambda px, py: abs(px - gx) < .8 and py > gy - .8 and py - 1 < gy + .8)
    notes.append(f'{len(seen)} standing spots reachable, exit / checkpoint / {sum(t["kind"] in ("coin", "gem") for t in lv["things"])} '
                 f'collectibles reachable, spikes passable, everything inside the camera\'s world')
    return notes


# ------------------------------------------------------------------ art

def rgb565(a):
    a = a.astype(np.uint32)
    return ((a[..., 0] >> 3) << 11) | ((a[..., 1] >> 2) << 5) | (a[..., 2] >> 3)


def tile_images():
    """32 x 32 RGB565 per tile art, plus a 'fill' version for ground below the top row (the cap - snow, grass, ice
    crust - replaced by the body texture), alpha flattened (the pack's ice and cloud tiles are 86% opaque)."""
    tops, fills = [], []
    for name in TILE_ART:
        im = np.array(Image.open(os.path.join(PACK, 'tiles', name + '.png')).convert('RGBA')).astype(np.float32)
        bgc = np.array([24, 32, 48], np.float32)
        a = im[..., 3:4] / 255.0
        rgb = im[..., :3] * a + bgc * (1 - a)
        fill = rgb.copy()
        cap = 9 if name in ('snow_ground', 'snow_platform', 'forest_ground', 'cloud_platform', 'ice') else 0   # 7 rows of cap + a 2-row highlight
        if cap:
            fill[1:1 + cap] = rgb[1 + cap:1 + 2 * cap]          # repeat the body under the outline row
        tops.append(rgb565(np.clip(rgb + .5, 0, 255)))
        fills.append(rgb565(np.clip(fill + .5, 0, 255)))
    return tops, fills


def background(name):
    """The theme picture cropped to a whole number of its repeating period, so it can scroll forever."""
    im = np.array(Image.open(os.path.join(PACK, 'backgrounds', name + '.png')).convert('RGB')).astype(np.int32)
    w = im.shape[1]
    best, period = None, w
    for p in range(40, w // 2):
        d = np.abs(im[:, p:] - im[:, :-p]).mean()
        if best is None or d < best - 1e-6:
            best, period = d, p
    n = w // period
    crop = im[:, :n * period]
    return crop, period, float(best)


def rle_rows(img565):
    """Per row: (run length, colour) pairs, run length 1..255."""
    out = []
    for row in img565:
        runs = []
        x = 0
        while x < len(row):
            c = row[x]
            n = 1
            while x + n < len(row) and row[x + n] == c and n < 255:
                n += 1
            runs.append((n, int(c)))
            x += n
        out.append(runs)
    return out


# ------------------------------------------------------------------ output

def write_header(levels, tops, fills, bgs):
    L = []
    A = L.append
    A('/* GENERATED by tools/originals-levels/convert_levels.py from cores/originals/assets/src/levels - do not edit. */')
    A('/* Blizzard Buddies Adventure mode: ten levels, their tile art and parallax backgrounds. */')
    A('#ifndef RB_LEVELS_DATA_H')
    A('#define RB_LEVELS_DATA_H')
    A('#include <stdint.h>')
    assert all(len(l['things']) <= 32 for l in levels), 'the game keeps 32 collected-bits per level'
    A('#define LV_COUNT %d' % len(levels))
    A('#define LV_MAXW %d' % max(l['w'] for l in levels))
    A('#define LV_MAXH %d' % max(l['h'] for l in levels))
    A('#define LV_NART %d' % len(TILE_ART))
    A('#define LV_NTHEME %d' % len(THEMES))
    for i, n in enumerate(TILE_ART):
        A('#define LV_ART_%s %d' % (n.upper(), i))
    for k, v in THING.items():
        A('#define LV_%s %d' % (k.upper(), v))
    A('typedef struct { uint8_t kind, x, y, arg, patrol; } LvThing;           /* y = feet row (coins: the row they float in) */')
    A('typedef struct {')
    A('    const char *name; uint8_t theme, art, w, h;')
    A('    uint8_t startX, startY, cpX, cpY, exitX, exitY; uint16_t seconds;')
    A('    uint8_t arenaX0, arenaX1, bossX, bossY, phases;                        /* arenaX1 = 0: no boss */')
    A('    const char *rows;                                                      /* h rows of w chars: . # = - ~ c */')
    A('    uint8_t nthings; const LvThing *things;')
    A('} LvLevel;')
    sym = {EMPTY: '.', SOLID: '#', ONEWAY: '=', ONEWAY_ICE: '-', SOLID_ICE: '~', CRATE: 'c'}
    for lv in levels:
        A('static const LvThing LV_THINGS_%d[] = {' % lv['id'])
        for t in lv['things']:
            A('    {LV_%s, %d, %d, %d, %d},' % (t['kind'].upper(), t['x'], t['y'], t.get('arg', 0), t.get('patrol', 0)))
        A('};')
    A('static const LvLevel LV_LEVELS[LV_COUNT] = {')
    for lv in levels:
        rows = ''.join(''.join(sym[int(v)] for v in row) for row in lv['grid'])
        a = lv['arena'] or dict(x0=0, x1=0, boss_x=0, boss_y=0, phases=0)
        A('    {"%s", %d, LV_ART_%s, %d, %d, %d, %d, %d, %d, %d, %d, %d, %d, %d, %d, %d, %d,' % (
            lv['name'].upper(), THEMES.index(lv['theme']), lv['art'].upper(), lv['w'], lv['h'], lv['start'][0], lv['start'][1],
            lv['checkpoint'][0], lv['checkpoint'][1], lv['exit'][0], lv['exit'][1], lv['time'], a['x0'], a['x1'], a['boss_x'], a['boss_y'], a['phases']))
        for r in range(lv['h']):
            A('     "%s"' % rows[r * lv['w']:(r + 1) * lv['w']])
        A('     , %d, LV_THINGS_%d},' % (len(lv['things']), lv['id']))
    A('};')
    A('#ifdef RB_SELFTEST')
    A('/* test builds: the ways the level checker found - from the start to the door (level 10: to the arena), from the')
    A(' * arena entrance to the door, from the checkpoint on - as take-off points and moves, for the route bot (blizzard.c) */')
    A('typedef struct { uint16_t x4, y4; uint8_t plan, runup; } LvMove;    /* x4, y4: position x 4; plan: see convert_levels.py */')
    for lv in levels:
        for nm in ('route', 'route2', 'route3'):
            r = lv.get(nm) or []
            A('static const LvMove LV_%s_%d[] = {%s};' % (nm.upper(), lv['id'], ', '.join('{%d, %d, %d, %d}' % m for m in r) or '{0, 0, 0, 0}'))
    A('static const LvMove *const LV_ROUTES[LV_COUNT][3] = {' + ', '.join('{LV_ROUTE_%d, LV_ROUTE2_%d, LV_ROUTE3_%d}' % (l['id'], l['id'], l['id']) for l in levels) + '};')
    A('static const uint8_t LV_ROUTE_LEN[LV_COUNT][3] = {' + ', '.join('{%d, %d, %d}' % tuple(len(l.get(n) or []) for n in ('route', 'route2', 'route3')) for l in levels) + '};')
    A('#endif')
    A('/* 32 x 32 RGB565 tile art: the top of a column, and the ground below it */')
    for nm, arr in (('LV_TILE_TOP', tops), ('LV_TILE_FILL', fills)):
        A('static const uint16_t %s[LV_NART][32 * 32] = {' % nm)
        for t in arr:
            A('  {' + ','.join(str(int(v)) for v in t.reshape(-1)) + '},')
        A('};')
    A('/* parallax backgrounds, 288 rows each, run-length coded RGB565: per row, (count, colour) pairs */')
    A('typedef struct { uint16_t w, h; uint32_t off; } LvBg;       /* w = one repeating period wide */')
    data = []
    offs = []
    for name in THEMES:
        img, w, h = bgs[name]
        offs.append((w, h, len(data)))
        for runs in rle_rows(img):
            for n, c in runs:
                data.append(n); data.append(c)
    A('static const LvBg LV_BGS[LV_NTHEME] = {' + ', '.join('{%d, %d, %d}' % o for o in offs) + '};')
    A('static const uint16_t LV_BG_DATA[%d] = {' % len(data))
    for s in range(0, len(data), 32):
        A(','.join(str(v) for v in data[s:s + 32]) + ',')
    A('};')
    A('#endif')
    with open(HEADER, 'w') as f:
        f.write('\n'.join(L) + '\n')


def preview(lv, tops, fills, path):
    """The whole level at 8 px per tile, things marked."""
    s = 8
    img = Image.new('RGB', (lv['w'] * s, lv['h'] * s), (30, 40, 70))
    d = ImageDraw.Draw(img)
    ai = TILE_ART.index(lv['art'])
    for r in range(lv['h']):
        for c in range(lv['w']):
            k = lv['grid'][r, c]
            if k:
                above = r > 0 and lv['grid'][r - 1, c] != EMPTY
                t = tops[TILE_ART.index('crate')] if k == CRATE else (fills if above else tops)[ai]
                rgb = np.stack([((t >> 11) & 31) * 255 // 31, ((t >> 5) & 63) * 255 // 63, (t & 31) * 255 // 31], -1).astype(np.uint8)
                img.paste(Image.fromarray(rgb).resize((s, s)), (c * s, r * s))
    col = dict(coin=(255, 220, 0), gem=(255, 60, 160), spikes=(220, 240, 255), pitspikes=(160, 180, 200), ladder=(150, 100, 50),
               lamp=(255, 200, 80), crate=(160, 110, 50), enemy=(255, 60, 60), spring=(230, 60, 60))
    for t in lv['things']:
        if t['kind'] == 'crate':
            continue
        x, y = t['x'] * s, t['y'] * s
        d.rectangle([x + 1, y - s + 1 if t['kind'] not in ('coin', 'gem') else y + 1, x + s - 2, y - 1 if t['kind'] not in ('coin', 'gem') else y + s - 2], fill=col[t['kind']])
    for (x, y), c in ((lv['start'], (80, 160, 255)), (lv['checkpoint'], (80, 255, 120)), (lv['exit'], (255, 255, 255))):
        d.rectangle([x * s, (y - 2) * s, x * s + s - 1, y * s - 1], outline=c, width=2)
    img.save(path)


def main():
    levels, report, failed = [], [], []
    for i in range(1, 11):
        L = json.load(open(os.path.join(PACK, 'levels', 'level_%02d.json' % i)))
        fixes = []
        lv = convert(L, fixes)
        try:
            notes = validate(lv)
        except AssertionError as e:
            failed.append(str(e)); notes = ['FAILED: ' + str(e)]
        levels.append(lv)
        report.append(dict(id=lv['id'], name=lv['name'], fixes=fixes, checks=notes))
        print(f"level {lv['id']:2d} {lv['name']:16s} {lv['w']}x{lv['h']}  fixes: {len(fixes)}  {notes[0]}", file=sys.stderr)
        for f in fixes:
            print('     - ' + f, file=sys.stderr)
    if failed:
        raise SystemExit('validation failed:\n  ' + '\n  '.join(failed))
    tops, fills = tile_images()
    bgs = {}
    for name in THEMES:
        crop, period, err = background(name)
        bgs[name] = (rgb565(crop), crop.shape[1], crop.shape[0])
        print(f'background {name}: repeats every {period} px (mean step {err:.2f}), {crop.shape[1]} px kept', file=sys.stderr)
    write_header(levels, tops, fills, bgs)
    out = [dict(id=l['id'], name=l['name'], theme=l['theme'], tile_art=l['art'], style=l['style'], width=l['w'], height=l['h'],
                rows=[''.join('.#=-~c'[int(v)] for v in row) for row in l['grid']],
                start=l['start'], checkpoint=l['checkpoint'], exit=l['exit'], time_limit_seconds=l['time'],
                things=l['things'], boss_arena=l['arena'], fixes=r['fixes'], checks=r['checks'])
           for l, r in zip(levels, report)]
    with open(OUT_JSON, 'w') as f:
        json.dump(dict(format='Blizzard Buddies adventure levels: rows use . empty, # solid, = one-way, - one-way ice, ~ solid ice, c crate; '
                              'y of a thing = the row its feet stand on (coins and gems: the row they float in)',
                       levels=out), f, indent=1)
        f.write('\n')
    if '--preview' in sys.argv:
        d = sys.argv[sys.argv.index('--preview') + 1]
        os.makedirs(d, exist_ok=True)
        for lv in levels:
            preview(lv, tops, fills, os.path.join(d, 'level_%02d.png' % lv['id']))


if __name__ == '__main__':
    main()
