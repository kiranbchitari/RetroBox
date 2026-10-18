#!/usr/bin/env python3
"""Builds the Blizzard Buddies sprites - the buddy, the five monsters, the three bosses, effects and level props - from
the rich art pack (cores/originals/assets/src/rich, fetched by tools/originals-assets/fetch_rich_pack.sh): one
transparent picture per animation frame. This script:

  1. cleans the alpha (haze below ALPHA_LO goes, the body becomes fully opaque above ALPHA_HI),
  2. keeps each frame's body - its biggest blob and any piece at least 12% of it - so labels printed under the feet
     ("IDLE", "HEAVY WALK"), stray bits of neighbouring frames and loose sparks or spat balls are left out (props and
     effects keep every piece),
  3. replaces the coloured halo on the anti-aliased edge with the colour of the outline next to it,
  4. scales each character once (all its frames alike) to VISUAL (1.25) times its hit box height (bosses: 118% of
     theirs), stored at PPT pixels per game tile (art too small for that at fewer), and anchors every frame at the
     feet (bottom centre; the flyer and the effects at their centre), so all frames of a character share one size and
     one anchor,
  5. writes cores/originals/assets/blizzard_atlas.png + blizzard_atlas.json (trimmed frames, anchors, animations, fps),
     a preview sheet, and cores/originals/sprites_data.h (per-character 255-colour palettes, run-length coded indexed
     pixels, six palette-swapped buddy skins) that the core compiles in.

Usage: python3 tools/originals-sprites/build_sprites.py [--preview sheet.png]   (needs Pillow and numpy; the palette
k-means is seeded, so a rerun gives the same result)
"""
import json
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
SRC = os.path.join(ROOT, 'cores', 'originals', 'assets', 'src')
OUT = os.path.join(ROOT, 'cores', 'originals', 'assets')
HEADER = os.path.join(ROOT, 'cores', 'originals', 'sprites_data.h')

ALPHA_LO, ALPHA_HI = 48, 232     # source alpha below LO is haze, above HI is solid body
PPT = 64                         # stored pixels per game tile (1080p draws at 60, so the stored frames are never upscaled in play)
MAX_UPSCALE = 1.3                # art smaller than that needs (the bosses) is stored at most 1.3x its own size, at fewer
                                 # pixels per tile, and the game scales it the rest of the way
VISUAL = 1.25                    # the buddy and the small monsters are drawn 1.25x their hit box height (a 48 px hit box: a
                                 # 60 px character); hit boxes, physics and the feet line are unchanged
HIT_H = dict(buddy=1.0, walker=.85, hopper=.8, flyer=.75, spitter=.85, charger=.85)   # blizzard.c: the player, EH[]
BOSS_H = 2.5 * 1.18              # bosses: 118% of their 2.5-tile hit box, about 2.8x a small monster's height

RICH = 'rich'                    # the rich art pack (tools/originals-assets/fetch_rich_pack.sh unpacks it there)


def frames_of(d, *stems):
    return ['file:%s/%s/%s.png' % (RICH, d, st) for st in stems]


# Per character: its frames ("file:path", one picture each), how tall the idle pose is in game tiles (or 'width'), the
# anchor mode, and the animations as lists of frames with their frames per second (0: picked from the game state).
# Frames are shared between animations where the pack has fewer drawings than the game has poses.
P = 'characters/penguin_buddy/frames_hd'
E = 'enemies/%s/frames_hd'
B = 'bosses/%s/frames_hd'
CHARS = [
    dict(id='buddy', size=('height', VISUAL * HIT_H['buddy']), anchor='feet',
         anims=dict(idle=(frames_of(P, '01_idle_01', '02_idle_02'), 3),
                    run=(frames_of(P, '03_run_01', '04_run_02', '05_run_03', '06_run_04'), 10),
                    jump=(frames_of(P, '07_jump_up', '08_jump_fall'), 0),
                    attack=(frames_of(P, '09_attack_windup', '10_attack_throw'), 12),
                    hit=(frames_of(P, '11_hit_hurt'), 0), death=(frames_of(P, '12_death_ko'), 0)),
         fix={frames_of(P, '10_attack_throw')[0]: dict(erase=[(172, 40, 258, 170)])},    # the snowball leaving the hand
         omitted='the snowball leaving the hand in 10 (the game draws its own shot)'),
    dict(id='walker', size=('height', VISUAL * HIT_H['walker']), anchor='feet',
         anims=dict(idle=(frames_of(E % 'walker_red_monster', '01_idle_01', '02_idle_02'), 3),
                    run=(frames_of(E % 'walker_red_monster', '03_run_01', '04_run_02'), 8),
                    jump=(frames_of(E % 'walker_red_monster', '05_jump_up', '06_leap_forward'), 0),
                    attack=(frames_of(E % 'walker_red_monster', '07_attack_roar'), 0),
                    hit=(frames_of(E % 'walker_red_monster', '08_hit_wince', '09_hit_crumple'), 8),
                    death=(frames_of(E % 'walker_red_monster', '10_death_blob_01', '11_death_blob_02'), 6)),
         fix={}, omitted=''),
    dict(id='hopper', size=('height', VISUAL * HIT_H['hopper']), anchor='feet',
         anims=dict(idle=(frames_of(E % 'hopper_green_monster', '01_idle_01', '02_idle_blink', '01_idle_01', '01_idle_01'), 3),
                    run=(frames_of(E % 'hopper_green_monster', '03_crouch_prep', '04_hop_run'), 8),
                    jump=(frames_of(E % 'hopper_green_monster', '05_leap_stretch', '06_jump_air'), 0),
                    attack=(frames_of(E % 'hopper_green_monster', '07_attack_long_tongue'), 0),
                    hit=(frames_of(E % 'hopper_green_monster', '08_hit_startled'), 0),
                    death=(frames_of(E % 'hopper_green_monster', '09_death_ko'), 0)),
         fix={}, omitted=''),
    # the bat's frame includes its spread wings, about 45% over its body: the body is what matches the hit box
    dict(id='flyer', size=('height', VISUAL * HIT_H['flyer'] * 1.45), anchor='centre',
         anims=dict(idle=(frames_of(E % 'flyer_purple_bat', '01_idle_wings_up'), 0),
                    fly=(frames_of(E % 'flyer_purple_bat', '01_idle_wings_up', '03_fly_flap', '02_fly_wings_down', '03_fly_flap'), 10),
                    attack=(frames_of(E % 'flyer_purple_bat', '04_attack_swoop'), 0),
                    hit=(frames_of(E % 'flyer_purple_bat', '05_hit_hurt'), 0),
                    death=(frames_of(E % 'flyer_purple_bat', '06_death_ko'), 0)),
         fix={}, omitted=''),
    dict(id='spitter', size=('height', VISUAL * HIT_H['spitter']), anchor='feet',
         anims=dict(idle=(frames_of(E % 'spitter_blue_ice_monster', '01_idle'), 0),
                    walk=(frames_of(E % 'spitter_blue_ice_monster', '02_walk_01', '01_idle', '03_walk_02', '01_idle'), 6),
                    attack=(frames_of(E % 'spitter_blue_ice_monster', '04_attack_spit_ice'), 0),
                    hit=(frames_of(E % 'spitter_blue_ice_monster', '05_hit_hurt'), 0),
                    death=(frames_of(E % 'spitter_blue_ice_monster', '06_death_flat'), 0)),
         keep_frac=.25, fix={}, omitted='the ice ball in the spit frame, 21% of the body (the game draws its own shots)'),
    dict(id='charger', size=('height', VISUAL * HIT_H['charger']), anchor='feet',
         anims=dict(idle=(frames_of(E % 'charger_white_yeti', '01_idle'), 0),
                    walk=(frames_of(E % 'charger_white_yeti', '02_walk_heavy_01', '01_idle', '03_walk_heavy_02', '01_idle'), 5),
                    attack=(frames_of(E % 'charger_white_yeti', '04_attack_charge_rush'), 0),
                    hit=(frames_of(E % 'charger_white_yeti', '05_hit_stagger'), 0),
                    death=(frames_of(E % 'charger_white_yeti', '06_death_collapsed'), 0)),
         fix={}, omitted=''),
    # ---- bosses: 118% of their 2.5-tile hit box ----
    dict(id='ogre', size=('height', BOSS_H), anchor='feet',
         anims=dict(idle=(frames_of(B % 'boss_01_ogre', '01_idle', '02_walk'), 0),
                    attack=(frames_of(B % 'boss_01_ogre', '04_jump_slam', '03_attack_punch'), 0),     # wind-up, then the blow
                    hit=(frames_of(B % 'boss_01_ogre', '05_hit_roar'), 0), death=(frames_of(B % 'boss_01_ogre', '06_death_defeated'), 0)),
         fix={}, omitted=''),
    dict(id='wizard', size=('height', BOSS_H), anchor='feet',
         anims=dict(idle=(frames_of(B % 'boss_02_frost_wizard', '01_idle'), 0),
                    cast=(frames_of(B % 'boss_02_frost_wizard', '02_cast_spell', '03_summon_ice_spikes'), 0),
                    hit=(frames_of(B % 'boss_02_frost_wizard', '04_hit_stagger'), 0),
                    death=(frames_of(B % 'boss_02_frost_wizard', '05_death_frozen_block'), 0)),
         fix={}, omitted=''),
    dict(id='rage', size=('height', BOSS_H), anchor='feet',
         anims=dict(idle=(frames_of(B % 'boss_03_rage_ogre', '01_idle_fire_aura'), 0),
                    roar=(frames_of(B % 'boss_03_rage_ogre', '02_rage_roar'), 0),
                    attack=(frames_of(B % 'boss_03_rage_ogre', '03_attack_fire_swing'), 0),
                    hit=(frames_of(B % 'boss_03_rage_ogre', '05_hit_hurt'), 0),
                    death=(frames_of(B % 'boss_03_rage_ogre', '06_death_charred'), 0),
                    charge=(frames_of(B % 'boss_03_rage_ogre', '04_attack_flame_charge'), 0)),
         fix={}, omitted=''),
    # ---- effects (centre-anchored, one scale for all, set by the large ice orb) ----
    dict(id='fx', size=('width', .7), size_ref='file:%s/effects_and_projectiles/01_ice_projectiles_and_shatter/04_ice_orb_large.png' % RICH,
         anchor='centre', keep_all=1,
         anims=dict(iceball=(frames_of('effects_and_projectiles/01_ice_projectiles_and_shatter', '01_ice_spark_small', '03_ice_orb_medium', '04_ice_orb_large'), 15),
                    star=(frames_of('effects_and_projectiles/03_dust_clouds_and_hit_sparks', '06_star_hit_gold_01'), 0),
                    cloud=(frames_of('effects_and_projectiles/03_dust_clouds_and_hit_sparks', '03_dust_puff_medium', '04_dust_puff_large', '05_dust_cloud_billow'), 10),
                    dust=(frames_of('effects_and_projectiles/03_dust_clouds_and_hit_sparks', '02_dust_puff_small', '03_dust_puff_medium', '04_dust_puff_large'), 10),
                    fire=(frames_of('effects_and_projectiles/04_fireballs_flames_and_slashes', '01_fireball_small', '02_fireball_medium'), 12),
                    flash=(frames_of('effects_and_projectiles/03_dust_clouds_and_hit_sparks', '08_critical_flash_burst'), 0),
                    sparkle=(frames_of('effects_and_projectiles/03_dust_clouds_and_hit_sparks', '09_sparkle_pickup_twinkle'), 0)),
         fix={}, omitted='ice spikes, frost mist, flames and slashes: no move in the game uses them'),
] + [dict(id=pid, size=sz, anchor=anc, keep_all=1, anims=dict(idle=(frames_of('props_and_collectibles/hd', *stems), 0)), fix={}, omitted='')
     for pid, stems, sz, anc in (
         ('coin', ('18_gold_coin_front', '19_gold_coin_tilted', '17_gold_coin_star', '19_gold_coin_tilted'), ('height', .72), 'centre'),
         ('gem', ('21_gem_ruby_red', '22_gem_diamond_cyan'), ('height', .68), 'centre'),
         ('flag', ('08_checkpoint_flag_red', '09_checkpoint_flag_blue'), ('height', 1.8), 'feet'),
         ('door', ('12_snowy_stone_arch_doorway',), ('height', 2.3), 'feet'),
         ('spikes', ('16_steel_floor_spikes',), ('width', 1.0), 'feet'),
         ('spring', ('14_bouncy_spring_red',), ('width', .9), 'feet'),
         ('lamp', ('05_glowing_street_lantern',), ('height', 1.9), 'feet'),
         ('ladder', ('01_wooden_ladder',), ('height', 2.0), 'feet'),
         # scenery, placed by tools/originals-levels/convert_levels.py: no effect on play
         ('tree', ('07_snowy_pine_tree',), ('height', 3.0), 'feet'),
         ('sign', ('06_wooden_signpost',), ('height', 1.3), 'feet'),
         ('barrel', ('10_wooden_barrel',), ('height', .95), 'feet'),
         ('cart', ('11_iron_minecart',), ('width', 1.7), 'feet'),
         ('crystal', ('23_crystal_cluster_amethyst', '24_crystal_cluster_ice_blue'), ('height', 1.1), 'feet'),
         ('chest', ('25_treasure_chest_locked',), ('width', 1.1), 'feet'),
         ('rock', ('26_snowy_rock_boulder',), ('width', 1.1), 'feet'))]

# Buddy skins: the art is the blue penguin with a red scarf. A skin moves the blue body hue and the red scarf hue (and
# for the panda turns the body near-black). Order and names follow SKINS in blizzard.c.
SKINS = [  # (body hue degrees or None = keep, body saturation scale, body value scale, scarf hue or None)
    ('BLUE', None, 1.0, 1.0, None),
    ('PINK', 330, 0.85, 1.08, 45),
    ('MINT', 155, 0.9, 1.0, 28),
    ('GOLD', 42, 1.0, 1.15, 0),
    ('VIOLET', 268, 0.95, 1.02, 320),
    ('PANDA', 230, 0.12, 0.42, 120),
]


# ---------------------------------------------------------------- image helpers

def components(mask):
    """8-connected labels of a boolean mask; returns (labels, [(label, x0, y0, x1, y1, area)])."""
    h, w = mask.shape
    lab = np.zeros((h, w), np.int32)
    out = []
    n = 0
    ys, xs = np.nonzero(mask)
    for y0, x0 in zip(ys.tolist(), xs.tolist()):
        if lab[y0, x0]:
            continue
        n += 1
        stack = [(y0, x0)]
        lab[y0, x0] = n
        bx0, by0, bx1, by1, area = x0, y0, x0, y0, 0
        while stack:
            y, x = stack.pop()
            area += 1
            bx0, bx1, by0, by1 = min(bx0, x), max(bx1, x), min(by0, y), max(by1, y)
            for dy in (-1, 0, 1):
                yy = y + dy
                if yy < 0 or yy >= h:
                    continue
                for dx in (-1, 0, 1):
                    xx = x + dx
                    if 0 <= xx < w and mask[yy, xx] and not lab[yy, xx]:
                        lab[yy, xx] = n
                        stack.append((yy, xx))
        out.append((n, bx0, by0, bx1 + 1, by1 + 1, area))
    return lab, out


def dilate(mask, r):
    out = mask.copy()
    for _ in range(r):
        m = out.copy()
        m[1:, :] |= out[:-1, :]
        m[:-1, :] |= out[1:, :]
        m[:, 1:] |= out[:, :-1]
        m[:, :-1] |= out[:, 1:]
        out = m
    return out


def largest(mask, keep_frac=0.12):
    """The biggest blob of a mask plus any blob at least keep_frac of it (detached ears, feet)."""
    lab, comps = components(mask)
    if not comps:
        return mask
    big = max(c[5] for c in comps)
    keep = np.zeros_like(mask)
    for c in comps:
        if c[5] >= big * keep_frac:
            keep |= lab == c[0]
    return keep


def extract(char, ref):
    """One frame (a picture of its own) as straight-alpha float RGBA (0..255): the alpha haze cleaned, the body kept -
    the biggest blob and any piece at least 12% of it - and labels, stray bits and loose sparks dropped (keep_all keeps
    every piece: props and effects are made of several), the anti-aliased edge recoloured to the outline."""
    assert ref.startswith('file:'), ref
    rgba = clean_alpha(np.array(Image.open(os.path.join(SRC, ref[5:])).convert('RGBA')).astype(np.float32))
    core = rgba[..., 3] >= 128
    for ex0, ey0, ex1, ey1 in char.get('fix', {}).get(ref, {}).get('erase', []):
        core[ey0:ey1, ex0:ex1] = False
    if not char.get('keep_all'):
        core = largest(core, char.get('keep_frac', 0.12))
    alpha = rgba[..., 3] * dilate(core, 2)
    alpha[core] = 255.0
    rgba[..., 3] = alpha
    return defringe(rgba, core)


def clean_alpha(a):
    al = (a[..., 3] - ALPHA_LO) / float(ALPHA_HI - ALPHA_LO)
    a = a.copy()
    a[..., 3] = np.clip(al, 0.0, 1.0) * 255.0
    return a


def defringe(rgba, core):
    """Edge pixels outside the solid body carry the generator's coloured glow. Give them the colour of the solid pixels
    next to them (the dark outline), keeping their alpha, so no halo shows on any background."""
    edge = (rgba[..., 3] > 0) & ~core
    if not edge.any():
        return rgba
    rgb = rgba[..., :3]
    acc = np.zeros_like(rgb)
    cnt = np.zeros(core.shape, np.float32)
    src = core.astype(np.float32)
    for r in (1, 2, 3):
        for dy in range(-r, r + 1):
            for dx in range(-r, r + 1):
                if max(abs(dy), abs(dx)) != r:
                    continue
                s = np.roll(np.roll(src, dy, 0), dx, 1)
                c = np.roll(np.roll(rgb, dy, 0), dx, 1)
                take = edge & (cnt == 0) & (s > 0)
                acc[take] += c[take]
                cnt[take] += 1
        # finish ring r before moving outward so the nearest outline wins
        upd = edge & (cnt > 0)
        rgb = rgb.copy()
        rgb[upd] = acc[upd] / cnt[upd][:, None]
        edge &= cnt == 0
        acc[:] = 0
    out = rgba.copy()
    out[..., :3] = rgb
    return out


# ---------------------------------------------------------------- scaling and anchoring

def to_premul(rgba):
    p = rgba.copy()
    p[..., :3] *= rgba[..., 3:4] / 255.0
    return p


def from_premul(p):
    out = p.copy()
    a = p[..., 3:4]
    out[..., :3] = np.where(a > 0, p[..., :3] * 255.0 / np.maximum(a, 1e-6), 0)
    return out


def resample(rgba, s):
    """Scales a straight-alpha float frame by s with Lanczos on premultiplied colour (no dark fringes)."""
    p = to_premul(rgba)
    h, w = p.shape[:2]
    nw, nh = max(1, int(round(w * s))), max(1, int(round(h * s)))
    chans = [np.asarray(Image.fromarray(p[..., c].astype(np.float32), 'F').resize((nw, nh), Image.LANCZOS)) for c in range(4)]
    q = np.clip(np.stack(chans, 2), 0, 255)
    q[..., :3] = np.minimum(q[..., :3], q[..., 3:4])
    return from_premul(q)


def bbox(alpha, t=8):
    ys, xs = np.nonzero(alpha > t)
    return xs.min(), ys.min(), xs.max() + 1, ys.max() + 1


def anchor_of(rgba, mode):
    al = rgba[..., 3]
    x0, y0, x1, y1 = bbox(al, 64)
    w = al / 255.0
    ys, xs = np.mgrid[0:al.shape[0], 0:al.shape[1]]
    cx = float((w * xs).sum() / w.sum())
    if mode == 'centre':
        cy = float((w * ys).sum() / w.sum())
        return cx, cy
    return cx, float(y1)


# ---------------------------------------------------------------- palette (k-means on premultiplied RGBA)

def quantize(pixels, k=255, iters=14, seed=1):
    """pixels: N x 4 premultiplied RGBA (alpha > 0). Returns (palette k x 4 uint8, labels N)."""
    rng = np.random.default_rng(seed)
    px = pixels.astype(np.float32)
    w = np.ones(len(px), np.float32)
    w[px[:, 3] < 250] = 2.0         # edges get more say: they decide how smooth the outline looks
    # init: a spread of samples ordered by luminance
    idx = rng.choice(len(px), size=min(k, len(px)), replace=False)
    cen = px[idx].copy()
    for _ in range(iters):
        lab = assign(px, cen)
        for c in range(len(cen)):
            m = lab == c
            if m.any():
                cen[c] = (px[m] * w[m, None]).sum(0) / w[m].sum()
            else:
                cen[c] = px[rng.integers(len(px))]
    lab = assign(px, cen)
    pal = np.clip(np.round(cen), 0, 255)
    pal[:, :3] = np.minimum(pal[:, :3], pal[:, 3:4])
    return pal.astype(np.uint8), lab


def assign(px, cen):
    out = np.empty(len(px), np.int32)
    cc = (cen ** 2).sum(1)
    for s in range(0, len(px), 8192):
        b = px[s:s + 8192]
        d = cc[None, :] - 2.0 * b @ cen.T
        out[s:s + 8192] = d.argmin(1)
    return out


# ---------------------------------------------------------------- skins (palette swap)

def rgb_to_hsv(c):
    r, g, b = c[..., 0] / 255.0, c[..., 1] / 255.0, c[..., 2] / 255.0
    mx, mn = np.maximum(np.maximum(r, g), b), np.minimum(np.minimum(r, g), b)
    d = mx - mn
    h = np.zeros_like(mx)
    m = d > 1e-6
    rm, gm, bm = m & (mx == r), m & (mx == g) & (mx != r), m & (mx == b) & (mx != r) & (mx != g)
    h[rm] = ((g - b)[rm] / d[rm]) % 6
    h[gm] = (b - r)[gm] / d[gm] + 2
    h[bm] = (r - g)[bm] / d[bm] + 4
    h *= 60.0
    s = np.where(mx > 0, d / np.maximum(mx, 1e-6), 0)
    return h, s, mx


def hsv_to_rgb(h, s, v):
    h = (h % 360.0) / 60.0
    i = np.floor(h).astype(int) % 6
    f = h - np.floor(h)
    p, q, t = v * (1 - s), v * (1 - s * f), v * (1 - s * (1 - f))
    r = np.choose(i, [v, q, p, p, t, v])
    g = np.choose(i, [t, v, v, q, p, p])
    b = np.choose(i, [p, p, t, v, v, q])
    return np.stack([r, g, b], -1) * 255.0


def skin_palette(pal, skin):
    """pal: premultiplied RGBA. Recolours the blue body (hue 195-250) and the red scarf (hue < 20 or > 340)."""
    _, body_h, body_s, body_v, scarf_h = skin
    a = pal[:, 3:4].astype(np.float32)
    straight = np.where(a > 0, pal[:, :3] * 255.0 / np.maximum(a, 1), 0)
    h, s, v = rgb_to_hsv(straight)
    out_h, out_s, out_v = h.copy(), s.copy(), v.copy()
    blue = (h >= 190) & (h <= 255) & (s > 0.18)
    red = ((h <= 22) | (h >= 340)) & (s > 0.35)
    if body_h is not None:
        out_h[blue] = body_h + (h[blue] - 218.0) * 0.5
        out_s[blue] = np.clip(s[blue] * body_s, 0, 1)
        out_v[blue] = np.clip(v[blue] * body_v, 0, 1)
    if scarf_h is not None:
        out_h[red] = scarf_h + np.where(h[red] > 180, h[red] - 360, h[red]) * 0.5
    rgb = hsv_to_rgb(out_h, out_s, out_v)
    res = pal.astype(np.float32).copy()
    res[:, :3] = np.clip(np.round(rgb * a / 255.0), 0, 255)
    return res.astype(np.uint8)


# ---------------------------------------------------------------- main

def build():
    chars_out = []
    for ch in CHARS:
        refs = []
        for name, (frames, fps) in ch['anims'].items():
            for r in frames:
                if r not in refs:
                    refs.append(r)
        if 'size_ref' in ch and ch['size_ref'] not in refs:
            refs.append(ch['size_ref'])
        raw = {r: extract(ch, r) for r in refs}
        # one scale for the whole character, from its first idle frame (or size_ref)
        first = raw[ch.get('size_ref', list(ch['anims'].values())[0][0][0])]
        bx0, by0, bx1, by1 = bbox(first[..., 3], 64)
        kind, tiles = ch['size']
        s = tiles * PPT / float(by1 - by0 if kind == 'height' else bx1 - bx0)
        ppt = float(PPT)
        if s > MAX_UPSCALE:
            ppt, s = PPT * MAX_UPSCALE / s, MAX_UPSCALE
        frames = {}
        for r in refs:
            f = resample(raw[r], s)
            ax, ay = anchor_of(f, ch['anchor'])
            x0, y0, x1, y1 = bbox(f[..., 3], 2)
            f = f[y0:y1, x0:x1]
            frames[r] = dict(img=f, ax=ax - x0, ay=ay - y0)
        print(f"{ch['id']}: {len(refs)} frames, scale {s:.3f}, {ppt:.1f} px per tile", file=sys.stderr)
        chars_out.append(dict(ch=ch, frames=frames, refs=refs, ppt=ppt))
    return chars_out


def pack(chars_out):
    """Shelf-packs every trimmed frame into one atlas. Returns (atlas RGBA uint8, metadata dict)."""
    items = []
    for ci, c in enumerate(chars_out):
        for r in c['refs']:
            items.append((ci, r, c['frames'][r]))
    W = 1024
    x = y = rowh = 0
    pos = {}
    for ci, r, f in sorted(items, key=lambda t: -t[2]['img'].shape[0]):
        h, w = f['img'].shape[:2]
        if x + w + 1 > W:
            x, y, rowh = 0, y + rowh + 1, 0
        pos[(ci, r)] = (x, y)
        x += w + 1
        rowh = max(rowh, h)
    H = y + rowh
    atlas = np.zeros((H, W, 4), np.float32)
    meta = dict(source='cores/originals/assets/src/rich (the rich art pack), built by tools/originals-sprites/build_sprites.py',
                image='blizzard_atlas.png', size=[W, H],
                notes=['frames are trimmed; anchor_x/anchor_y = the feet (or the flyer\'s, wizard\'s fx\'s centre) inside '
                       'the trimmed frame; cell/cell_anchor = the common untrimmed size every frame of the character fits in',
                       'pixels_per_tile: atlas pixels per game tile for that character (the bosses are stored smaller and '
                       'scaled up when drawn)',
                       'frames[].source = the picture the frame was made from (file:path under assets/src)',
                       'fps 0 = the frame is picked from the game state (vertical speed, the spit wind-up timer)',
                       'all art faces right; the game mirrors it'],
                characters={})
    for ci, c in enumerate(chars_out):
        ch = c['ch']
        fr_meta = []
        cell_l = cell_r = cell_t = cell_b = 0.0
        for r in c['refs']:
            f = c['frames'][r]
            cell_l, cell_r = max(cell_l, f['ax']), max(cell_r, f['img'].shape[1] - f['ax'])
            cell_t, cell_b = max(cell_t, f['ay']), max(cell_b, f['img'].shape[0] - f['ay'])
        for r in c['refs']:
            f = c['frames'][r]
            x, y = pos[(ci, r)]
            h, w = f['img'].shape[:2]
            atlas[y:y + h, x:x + w] = f['img']
            fr_meta.append(dict(source=r, x=x, y=y, w=w, h=h, anchor_x=round(f['ax'], 2), anchor_y=round(f['ay'], 2)))
        anims = {}
        for name, (frames, fps) in ch['anims'].items():
            anims[name] = dict(frames=[c['refs'].index(r) for r in frames], fps=fps, loop=name in ('idle', 'run', 'walk', 'fly'))
        meta['characters'][ch['id']] = dict(
            pixels_per_tile=round(c['ppt'], 3),
            anchor='feet (bottom centre)' if ch['anchor'] == 'feet' else 'body centre',
            faces='right', cell=[int(np.ceil(cell_l + cell_r)), int(np.ceil(cell_t + cell_b))],
            cell_anchor=[int(np.ceil(cell_l)), int(np.ceil(cell_t))], frames=fr_meta, animations=anims,
            omitted=ch.get('omitted', ''))
    return np.clip(np.round(atlas), 0, 255).astype(np.uint8), meta


def encode_rle(idx):
    """Rows of (skip, count, count indices) tokens; index 0 = transparent. Returns bytes."""
    out = bytearray()
    h, w = idx.shape
    for y in range(h):
        row = idx[y]
        x = 0
        while x < w:
            skip = 0
            while x < w and row[x] == 0 and skip < 255:
                skip += 1
                x += 1
            n = 0
            while x + n < w and row[x + n] != 0 and n < 255:
                n += 1
            out += bytes((skip, n))
            out += bytes(row[x:x + n].tolist())
            x += n
    return bytes(out)


def write_header(atlas, meta):
    data = bytearray()
    lines = []
    ids = list(meta['characters'].keys())
    frame_rows, anim_rows, pal_rows, char_rows = [], [], [], []
    nframe = 0
    for ci, cid in enumerate(ids):
        cm = meta['characters'][cid]
        pix = []
        for f in cm['frames']:
            pix.append(atlas[f['y']:f['y'] + f['h'], f['x']:f['x'] + f['w']].reshape(-1, 4))
        allpx = np.concatenate(pix).astype(np.float32)
        pm = allpx.copy()
        pm[:, :3] = np.round(pm[:, :3] * pm[:, 3:4] / 255.0)
        vis = pm[:, 3] > 0
        pal, labels = quantize(pm[vis])
        full = np.zeros(len(pm), np.int32)
        full[vis] = labels + 1
        off = 0
        rows = []                                  # each picture's pixels are coded once ...
        for f in cm['frames']:
            n = f['w'] * f['h']
            idx = full[off:off + n].reshape(f['h'], f['w']).astype(np.uint8)
            off += n
            rows.append((len(data), f['w'], f['h'], f['anchor_x'], f['anchor_y']))
            data += encode_rle(idx)
        first_frame = nframe                       # ... and every animation gets its own run of frame records, so an
        for name, an in cm['animations'].items():  # animation is (first, count) even when it reuses another's pictures
            anim_rows.append((cid, name, nframe, len(an['frames']), an['fps']))
            for i in an['frames']:
                frame_rows.append(rows[i])
                nframe += 1
        pals = [pal] if cid != 'buddy' else [skin_palette(pal, sk) for sk in SKINS]
        pal_first = len(pal_rows)
        for p in pals:
            pal_rows.append(np.concatenate([np.zeros((1, 4), np.uint8), p]))
        char_rows.append((cid, first_frame, nframe - first_frame, pal_first, len(pals), cm['pixels_per_tile']))
    L = lines.append
    L('/* GENERATED by tools/originals-sprites/build_sprites.py from cores/originals/assets/src - do not edit. */')
    L('/* Blizzard Buddies character sprites: palette-indexed, run-length coded. */')
    L('#ifndef RB_SPRITES_DATA_H')
    L('#define RB_SPRITES_DATA_H')
    L('#include <stdint.h>')
    L('#define SPR_NFRAMES %d' % len(frame_rows))
    L('#define SPR_NPALS %d' % len(pal_rows))
    L('#define SPR_NCHARS %d' % len(char_rows))
    L('#define SPR_NANIMS %d' % len(anim_rows))
    for ci, r in enumerate(char_rows):
        L('#define SPR_%s %d' % (r[0].upper(), ci))
    for ai, r in enumerate(anim_rows):
        L('#define SPR_%s_%s %d' % (r[0].upper(), r[1].upper(), ai))
    L('typedef struct { uint32_t off; uint16_t w, h; float ax, ay; } SprFrameDef;     /* ax, ay = anchor in frame pixels */')
    L('typedef struct { uint16_t first, count, fps; } SprAnimDef;                     /* frames are global indices */')
    L('typedef struct { uint16_t first, count, pal, npal; float ppt; } SprCharDef;    /* npal > 1: palette per skin; ppt = stored pixels per tile */')
    L('static const SprCharDef SPR_CHARS[SPR_NCHARS] = {')
    for r in char_rows:
        L('    {%d, %d, %d, %d, %.3ff},   /* %s */' % (r[1], r[2], r[3], r[4], r[5], r[0]))
    L('};')
    L('static const SprAnimDef SPR_ANIMS[SPR_NANIMS] = {')
    for r in anim_rows:
        L('    {%d, %d, %d},   /* %s %s */' % (r[2], r[3], r[4], r[0], r[1]))
    L('};')
    L('static const SprFrameDef SPR_FRAMES[SPR_NFRAMES] = {')
    for r in frame_rows:
        L('    {%d, %d, %d, %.2ff, %.2ff},' % r)
    L('};')
    L('/* 256 premultiplied RGBA entries per palette; entry 0 is transparent */')
    L('static const uint8_t SPR_PALS[SPR_NPALS][256][4] = {')
    for p in pal_rows:
        L('  {' + ','.join('{%d,%d,%d,%d}' % tuple(c) for c in p.tolist()) + '},')
    L('};')
    L('/* per frame, per row: (skip, n, n palette indices) until the row is full */')
    L('static const uint8_t SPR_DATA[%d] = {' % len(data))
    for s in range(0, len(data), 40):
        L(','.join(str(b) for b in data[s:s + 40]) + ',')
    L('};')
    L('#endif')
    with open(HEADER, 'w') as fh:
        fh.write('\n'.join(lines) + '\n')
    print(f'header: {len(frame_rows)} frames, {len(pal_rows)} palettes, {len(data)} bytes of pixels', file=sys.stderr)


def preview(atlas, meta):
    """Every animation on a mid-grey and a dark row, at 2x, for checking by eye."""
    rows = []
    for cid, cm in meta['characters'].items():
        for name, an in cm['animations'].items():
            rows.append((cid, name, [cm['frames'][i] for i in an['frames']]))
    cw, chh = 0, 0
    for _, _, fr in rows:
        cw = max(cw, sum(f['w'] + 12 for f in fr))
        chh = max(chh, max(f['h'] for f in fr))
    sheet = Image.new('RGBA', (cw * 2 + 140, len(rows) * (chh * 2 + 10) + 10), (54, 58, 74, 255))
    d = ImageDraw.Draw(sheet)
    img = Image.fromarray(atlas, 'RGBA')
    y = 10
    for cid, name, fr in rows:
        d.text((6, y + chh), f'{cid} {name}', fill=(255, 255, 200, 255))
        x = 140
        base = y + chh * 2
        for f in fr:
            im = img.crop((f['x'], f['y'], f['x'] + f['w'], f['y'] + f['h'])).resize((f['w'] * 2, f['h'] * 2), Image.LANCZOS)
            sheet.alpha_composite(im, (int(x), int(base - f['anchor_y'] * 2)))
            d.line([(x, base), (x + f['w'] * 2, base)], fill=(255, 80, 80, 120))
            x += f['w'] * 2 + 24
        y += chh * 2 + 10
    return sheet


def main():
    chars_out = build()
    atlas, meta = pack(chars_out)
    Image.fromarray(atlas, 'RGBA').save(os.path.join(OUT, 'blizzard_atlas.png'), optimize=True)
    with open(os.path.join(OUT, 'blizzard_atlas.json'), 'w') as fh:
        json.dump(meta, fh, indent=1)
        fh.write('\n')
    if '--preview' in sys.argv:
        preview(atlas, meta).convert('RGB').save(sys.argv[sys.argv.index('--preview') + 1])
    write_header(atlas, meta)


if __name__ == '__main__':
    main()
