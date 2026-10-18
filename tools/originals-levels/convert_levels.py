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
  * ladders lead nowhere (and Up is jump in this game): they are left out;
  * level 2 asks for a 5-row climb (a full jump is 4.86 rows): a spring (the pack's spring art) is added below it;
  * the pack's unused art gets a place: one gem per level on a high spot, lamps by checkpoints and exits, crates as
    obstacles in the mine, cloud platforms for Windy Heights.

Then it checks every level with the game's own movement code ported line for line (update_player and move_body in
blizzard.c): running, jumping with full and released jump, falling, landing, one-way platforms and ice. It searches
every surface position reachable from the start and fails if the exit, the checkpoint, a coin or the gem cannot be
reached, if a thing is buried in a wall, if spikes cannot be passed, or if the camera cannot show something.

The art comes from the rich pack (cores/originals/assets/src/rich, tools/originals-assets/fetch_rich_pack.sh): each
stage's 64 px tiles (STAGE_TILES), its painted background, and scenery placed along the platforms (scenery()).

Output: cores/originals/levels_data.h (tile grids, things, the tiles), backgrounds_data.h (the JPEGs), the converted levels
as cores/originals/assets/adventure_levels.json, and a report.

Usage: python3 tools/originals-levels/convert_levels.py [--preview DIR]      (Python 3, Pillow, numpy)
"""
import collections
import io
import json
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
PACK = os.path.join(ROOT, 'cores', 'originals', 'assets', 'src', 'levels')
HEADER = os.path.join(ROOT, 'cores', 'originals', 'levels_data.h')
BG_HEADER = os.path.join(ROOT, 'cores', 'originals', 'backgrounds_data.h')
RICH = os.path.join(ROOT, 'cores', 'originals', 'assets', 'src', 'rich')     # tools/originals-assets/fetch_rich_pack.sh
OUT_JSON = os.path.join(ROOT, 'cores', 'originals', 'assets', 'adventure_levels.json')

SCREEN_W, SCREEN_H = 32, 18          # the camera shows 32 x 18 tiles at every picture size
HUD_ROWS = 1.8                       # the score panels cover the top 1.8 tiles of the screen

# tile kinds, as in blizzard.c: 0 empty, 1 solid, 2 one-way, 3 one-way ice, 4 solid ice, 5 solid crate
EMPTY, SOLID, ONEWAY, ONEWAY_ICE, SOLID_ICE, CRATE = 0, 1, 2, 3, 4, 5
THING = dict(coin=1, gem=2, spikes=3, pitspikes=4, ladder=5, lamp=6, crate=7, enemy=8, spring=9, deco=10)
# scenery (LV_DECO, arg = which): drawn behind the characters, no effect on play; names are sprites in build_sprites.py
DECO = ['tree', 'sign', 'barrel', 'cart', 'crystal', 'chest', 'rock']

# The rich pack's 64 x 64 tiles, per stage: the top of the ground, the ground below it, one-way platforms, crates and
# what lines a pit's bottom (lava stages). S = snow_and_ice, F = forest_mine_and_lava, then the tile's number.
STAGE_TILES = {
    1: dict(top='S01', fill='S29'),                 # Snowy Forest: deep snow over dark frozen rock
    2: dict(top='S17', fill='S20'),                 # Ice Cave: snow-capped ice over the ice cave wall
    3: dict(top='S02', fill='S28'),                 # Mountain Cliffs: snowy stone over dark frozen rock
    4: dict(top='S02', fill='F03'),                 # Frozen Castle: snowy stone over castle bricks
    5: dict(top='F15', fill='F26', pit='F21'),      # Lava Cavern: volcanic rock over magma bricks, lava in the pits
    6: dict(top='S04', fill='S02', oneway='S04'),   # Windy Heights: floating snow ledges
    7: dict(top='F01', fill='F06'),                 # Ancient Ruins: grass over mossy ruin walls
    8: dict(top='F08', fill='F25'),                 # Underground Mine: dark ore over timber-framed rock
    9: dict(top='S17', fill='S15'),                 # Crystal Peak: snow-capped ice over cracked ice
    10: dict(top='F15', fill='F26', oneway='F11', pit='F21'),   # Final Castle: as the cavern, plank ledges in the arena
}
CRATE_TILE = 'F10'
BG_SIZE, BG_QUALITY = (1280, 714), 82       # the painted backgrounds: about the Mi TV's 1280 x 720, JPEG quality 82
SPRING_VY = -.68                     # a spring's launch speed (blizzard.c SPRING_VY): about 6.8 rows high
# springs the levels need: the pack's level 2 asks for a 5-row climb (x 27 row 16 to x 30 row 11); a full jump is 4.86
SPRINGS = {2: [27]}
ENEMY = dict(walker=0, hopper=1, flyer=2, spitter=4, charger=5)          # E_WALK.. in blizzard.c

# per level: how platforms are built ('column' = solid ground down to the bottom, 'float' = one-row one-way platforms)
STYLE = {6: 'float'}


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
    if L['props']:
        fixes.append('ladders left out: none leads anywhere a jump cannot reach, and Up is jump in this game')
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
    for x, y in ((cx - 2, cy), (ex + 2, ey)):                  # lanterns by the checkpoint and the door
        if 0 <= x < ww and grid[y, x] != EMPTY and grid[y - 1, x] == EMPTY:
            things.append(dict(kind='lamp', x=x, y=y))
    for x in SPRINGS.get(lid, []):
        x, y = put(x, 0, 'spring')
        things.append(dict(kind='spring', x=x, y=y))
        fixes.append(f'added a spring (the pack\'s spring art) at x {x}: the pack asks for a climb higher than a full jump there')
    things += scenery(lid, L['theme'], grid, things, (sx, sy), (cx, cy), (ex, ey), arena)
    return dict(id=lid, name=L['name'], theme=L['theme'], tiles=STAGE_TILES[lid], w=ww, h=wh, grid=grid, start=(sx, sy),
                checkpoint=(cx, cy), exit=(ex, ey), time=L['time_limit_seconds'], things=things, arena=arena, style=style)


def scenery(lid, theme, grid, things, start, cp, ex, arena):
    """Decorations from the rich pack, placed on open ground away from everything that plays: a signpost by the start,
    a chest by the door (a real pickup, see blizzard.c), and per theme pine trees (snow), crystals (cave, crystal) or
    boulders (ruins, lava) along the platforms; the mine's barrels and minecart are left out (they do nothing)."""
    h, w = grid.shape
    busy = {t['x'] for t in things if t['kind'] != 'coin' and t['kind'] != 'gem'} | {start[0], cp[0], ex[0]}
    busy |= {x + d for x in (cp[0], ex[0]) for d in (-1, 1, 2, -2)}
    out = []

    def ground(x):                                     # the surface row at column x, if it is open ground to stand on
        for r in range(1, h):
            if grid[r, x] != EMPTY:
                return r if grid[r - 1, x] == EMPTY and grid[r, x] in (SOLID, SOLID_ICE) else None
        return None

    def place(x, which):
        if 0 < x < w - 1 and x not in busy and (arena is None or x < arena['x0'] - 1):
            r = ground(x)
            if r is not None and ground(x - 1) == r and ground(x + 1) == r:     # not on an edge
                out.append(dict(kind='deco', x=x, y=r, arg=DECO.index(which)))
                busy.update((x - 1, x, x + 1))
                return True
        return False

    place(max(1, start[0] - 1), 'sign') or place(start[0] + 2, 'sign')
    place(ex[0] - 3, 'chest')
    kind = {'snow': ['tree', 'tree', 'rock'], 'cave': ['crystal', 'rock'], 'crystal': ['crystal', 'crystal'],
            'lava': ['rock'], 'forest': ['rock', 'tree']}.get(theme, ['rock'])
    if lid == 8:
        kind = ['barrel', 'cart', 'barrel']
    if lid == 6:
        kind = []                                      # floating ledges: nothing stands on them
    if lid == 7:
        kind = ['rock', 'barrel']
    n = 0
    for x in range(4, w - 4, 7):
        for dx in (0, 1, -1, 2):
            if kind and place(x + dx, kind[n % len(kind)]):
                n += 1
                break
    return [o for o in out if DECO[o['arg']] not in ('barrel', 'cart')]       # they look usable and are not: left out


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
        if t['kind'] in ('coin', 'gem', 'start', 'exit', 'checkpoint', 'spikes', 'lamp', 'ladder', 'deco') or \
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


def tile_file(code):
    d = os.path.join(RICH, 'tiles', 'snow_and_ice' if code[0] == 'S' else 'forest_mine_and_lava', '64x64')
    return os.path.join(d, [f for f in sorted(os.listdir(d)) if f.startswith(code[1:3] + '_')][0])


def tile_set(levels):
    """The rich tiles the stages use, as 64 x 64 premultiplied RGBA (the snow caps and rounded corners are see-through)."""
    codes = sorted({c for lv in levels for c in lv['tiles'].values()} | {CRATE_TILE})
    imgs = []
    for c in codes:
        a = np.array(Image.open(tile_file(c)).convert('RGBA').resize((64, 64), Image.LANCZOS)).astype(np.float32)
        a[..., :3] = a[..., :3] * a[..., 3:4] / 255.0
        imgs.append(np.clip(np.round(a), 0, 255).astype(np.uint8))
    return codes, imgs


def backgrounds():
    """Each stage's painted background as a JPEG (decoded by stb_image in levels.c)."""
    files = sorted(os.listdir(os.path.join(RICH, 'backgrounds')))
    out = []
    for f in files:
        im = Image.open(os.path.join(RICH, 'backgrounds', f)).convert('RGB').resize(BG_SIZE, Image.LANCZOS)
        b = io.BytesIO()
        im.save(b, 'JPEG', quality=BG_QUALITY, optimize=True, progressive=False, subsampling=2)
        out.append((f, b.getvalue()))
    assert len(out) == 10, files
    return out


# ------------------------------------------------------------------ output

def write_header(levels, codes, tiles, bgs):
    L = []
    A = L.append
    A('/* GENERATED by tools/originals-levels/convert_levels.py from cores/originals/assets/src/levels and src/rich - do not edit. */')
    A('/* Blizzard Buddies Adventure mode: ten levels and their tiles (the backgrounds are in backgrounds_data.h). */')
    A('#ifndef RB_LEVELS_DATA_H')
    A('#define RB_LEVELS_DATA_H')
    A('#include <stdint.h>')
    for l in levels:                                   # coins and gems first: the game keeps 32 collected-bits
        l['things'].sort(key=lambda t: t['kind'] not in ('coin', 'gem'))
        assert sum(t['kind'] in ('coin', 'gem') for t in l['things']) <= 32 and len(l['things']) < 256
    A('#define LV_COUNT %d' % len(levels))
    A('#define LV_MAXW %d' % max(l['w'] for l in levels))
    A('#define LV_MAXH %d' % max(l['h'] for l in levels))
    A('#define LV_NTILES %d' % len(codes))
    A('#define LV_TILE_NONE 255')
    for k, v in THING.items():
        A('#define LV_%s %d' % (k.upper(), v))
    for i, n in enumerate(DECO):
        A('#define LV_DECO_%s %d' % (n.upper(), i))
    A('typedef struct { uint8_t kind, x, y, arg, patrol; } LvThing;           /* y = feet row (coins: the row they float in) */')
    A('typedef struct {')
    A('    const char *name; uint8_t tTop, tFill, tOneway, tPit, tCrate, w, h;     /* tiles: indices into LV_TILES */')
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
        T = lv['tiles']
        ti = [codes.index(T[k]) if k in T else 255 for k in ('top', 'fill', 'oneway', 'pit')] + [codes.index(CRATE_TILE)]
        A('    {"%s", %d, %d, %d, %d, %d, %d, %d, %d, %d, %d, %d, %d, %d, %d, %d, %d, %d, %d, %d,' % ((
            lv['name'].upper(),) + tuple(ti) + (lv['w'], lv['h'], lv['start'][0], lv['start'][1],
            lv['checkpoint'][0], lv['checkpoint'][1], lv['exit'][0], lv['exit'][1], lv['time'], a['x0'], a['x1'], a['boss_x'], a['boss_y'], a['phases'])))
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
    A('/* the rich pack\'s tiles used, 64 x 64 premultiplied RGBA: %s */' % ' '.join(codes))
    A('static const uint8_t LV_TILES[LV_NTILES][64 * 64 * 4] = {')
    for t in tiles:
        A('  {' + ','.join(str(int(v)) for v in t.reshape(-1)) + '},')
    A('};')
    A('#endif')
    with open(HEADER, 'w') as f:
        f.write('\n'.join(L) + '\n')
    B = ['/* GENERATED by tools/originals-levels/convert_levels.py from cores/originals/assets/src/rich/backgrounds - do not edit. */',
         '/* The ten stages\' painted backgrounds, %d x %d JPEG (quality %d), decoded by stb_image in levels.c. */' % (BG_SIZE + (BG_QUALITY,)),
         '#ifndef RB_BACKGROUNDS_DATA_H', '#define RB_BACKGROUNDS_DATA_H', '#include <stdint.h>']
    for i, (name, data) in enumerate(bgs):
        B.append('static const uint8_t LV_BG_%d[%d] = {   /* %s */' % (i + 1, len(data), name))
        for k in range(0, len(data), 40):
            B.append(','.join(str(v) for v in data[k:k + 40]) + ',')
        B.append('};')
    B.append('static const uint8_t *const LV_BG_JPEG[%d] = {%s};' % (len(bgs), ', '.join('LV_BG_%d' % (i + 1) for i in range(len(bgs)))))
    B.append('static const uint32_t LV_BG_SIZE[%d] = {%s};' % (len(bgs), ', '.join(str(len(d)) for _, d in bgs)))
    B.append('#endif')
    with open(BG_HEADER, 'w') as f:
        f.write('\n'.join(B) + '\n')


def preview(lv, codes, tiles, path):
    """The whole level at 16 px per tile with its tiles, things marked."""
    s = 16
    img = Image.new('RGB', (lv['w'] * s, lv['h'] * s), (30, 40, 70))
    d = ImageDraw.Draw(img)
    T = lv['tiles']
    small = {c: Image.fromarray(t, 'RGBA').resize((s, s), Image.LANCZOS) for c, t in zip(codes, tiles)}
    for r in range(lv['h']):
        for c in range(lv['w']):
            k = lv['grid'][r, c]
            if k:
                top = r == 0 or lv['grid'][r - 1, c] == EMPTY or k in (ONEWAY, ONEWAY_ICE)
                code = CRATE_TILE if k == CRATE else (T.get('oneway', T['top']) if k in (ONEWAY, ONEWAY_ICE) else T['top' if top else 'fill'])
                img.paste(small[code], (c * s, r * s), small[code])
    col = dict(coin=(255, 220, 0), gem=(255, 60, 160), spikes=(220, 240, 255), pitspikes=(160, 180, 200), ladder=(150, 100, 50),
               lamp=(255, 200, 80), enemy=(255, 60, 60), spring=(230, 60, 60), deco=(90, 200, 120))
    for t in lv['things']:
        x, y = t['x'] * s, t['y'] * s
        d.rectangle([x + 3, y - s + 3 if t['kind'] not in ('coin', 'gem') else y + 3, x + s - 4, y - 3 if t['kind'] not in ('coin', 'gem') else y + s - 4], fill=col[t['kind']])
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
    codes, tiles = tile_set(levels)
    bgs = backgrounds()
    print(f'{len(codes)} tiles, backgrounds {sum(len(d) for _, d in bgs) / 1e6:.2f} MB of JPEG', file=sys.stderr)
    write_header(levels, codes, tiles, bgs)
    out = [dict(id=l['id'], name=l['name'], theme=l['theme'], tiles=l['tiles'], style=l['style'], width=l['w'], height=l['h'],
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
            preview(lv, codes, tiles, os.path.join(d, 'level_%02d.png' % lv['id']))


if __name__ == '__main__':
    main()
