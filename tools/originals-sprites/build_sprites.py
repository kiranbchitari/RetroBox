#!/usr/bin/env python3
"""Builds the Blizzard Buddies character sprites from the raw strips in cores/originals/assets/src.

The strips are generated art, not a grid: frames are irregularly spaced, some touch each other, labels, stars, motion
streaks, projectiles and dust are baked in, and the background carries a faint alpha haze. This script:

  1. cleans the alpha (haze below ALPHA_LO goes, the body becomes fully opaque above ALPHA_HI),
  2. finds every frame as a connected body, splits touching frames at the thinnest column, drops labels and loose effects
     (stars, sparkles, projectiles), erases the effects listed per frame below and leaves out frames whose effects
     are painted over the body (slash streaks, smoke, the yeti's snow cloud),
  3. replaces the coloured halo on the anti-aliased edge with the colour of the outline next to it,
  4. scales each character once (all its frames alike) to PPT pixels per game tile and anchors every frame at the feet
     (bottom centre; the flyer at its centre), so all frames of a character share one size and one anchor,
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
MIN_BODY = 600                   # connected pixels (alpha >= 128) for a blob to count as a frame; smaller ones are effects / text

# Per character: the strip, how tall the idle pose is in game tiles (or 'width' for the flyer), the anchor mode, how
# merged blobs split, and the animations as lists of frames. A frame is a blob index (blobs sorted left to right) or
# "i/k/n" = piece k of blob i cut into n frames. FIX lists per-frame erase rectangles in strip pixels (x0, y0, x1, y1).
# Frames whose effects cannot be removed without damaging the body are left out (see the comments per character).
CHARS = [
    dict(id='buddy', strip='penguin_buddy_sprite_strip.png', size=('height', 1.12), anchor='feet', blobs=25,
         anims=dict(idle=([0, 1, 2, 3], 6), run=([4, 5, 6, 7, 8], 12), jump=([9, 10, 11, 12], 0),
                    attack=([13, 14, 15, 16], 20), hit=([17, 18, 19], 10), death=([20, 21, 22, 23, 24], 8)),
         fix={16: dict(erase=[(1160, 0, 1300, 144)])}),
    dict(id='walker', strip='walker_sprite_strip.png', size=('height', 1.08), anchor='feet', blobs=25,
         anims=dict(idle=([0, 1, 2, 3], 6), run=([4, 5, 6, 7, 8], 10), jump=([9, 10, 11, 12], 0),
                    attack=([13, 15], 8), hit=([17, 18, 19], 12), death=([20, 21, 22, 24], 8)),
         fix={}, omitted='attack 14 and 16: slash streaks painted over the body; death 23: a smoke puff over the body'),
    dict(id='hopper', strip='hopper_sprite_strip.png', size=('height', 1.08), anchor='feet', blobs=22,
         anims=dict(idle=([0, 1, 2, 3], 6), run=([4, 5, 6, '7/0/2', '7/1/2', 8], 12), jump=([9, 10, 11, 12], 0),
                    hit=([15, 16, 17], 12), death=([18, 19, 20, 21], 8)),
         fix={}, omitted='attack 13 and 14: speed lines over the body, and a tongue cut off by the next frame'),
    dict(id='flyer', strip='flyer_sprite_strip.png', size=('width', 1.45), anchor='centre', blobs=18,
         anims=dict(idle=([0, 1, 2], 6), fly=([3, 4, 5, 6, 7, 8], 12), attack=([9, 10, 11], 10),
                    hit=([12, 13], 10), death=([14, 15, 16, 17], 8)),
         fix={9: dict(erase=[(815, 40, 852, 110)])}),
    dict(id='spitter', strip='spitter_sprite_strip.png', size=('height', 1.06), anchor='feet', blobs=21,
         anims=dict(idle=([0, 1, 2, 3], 6), walk=([4, 5, 6, 7, 8], 8), attack=([9, 10, 11], 0),
                    hit=([14, 15], 12), death=([17, 18, 19, 20], 8)),
         fix={11: dict(erase=[(972, 0, 1030, 142)])},
         omitted='12 and 13: the spat ice balls (the game draws its own shots); hit 16: a shrunken pose that reads as a glitch'),
    dict(id='charger', strip='charger_sprite_strip.png', size=('height', 1.0), anchor='feet', blobs=11,
         anims=dict(idle=([0, '1/0/3', '1/1/3', '1/2/3'], 6), hit=([6, '7/0/2', '7/1/2'], 8),
                    death=(['8/0/2', '8/1/2', 9, 10], 8)),
         fix={}, omitted='run and attack (2-5): a snow cloud woven through the legs; the game builds the walk and the '
                         'charge from the idle frames (draw_enemy_sprite in blizzard.c)'),
]

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


def split_columns(colcount, n):
    """n-1 cut columns at the thinnest column near each equal division of a merged blob."""
    w = len(colcount)
    cuts = []
    for k in range(1, n):
        c = w * k // n
        lo, hi = max(1, c - w // (2 * n)), min(w - 1, c + w // (2 * n))
        cuts.append(lo + int(np.argmin(colcount[lo:hi])))
    return [0] + cuts + [w]


# ---------------------------------------------------------------- frame extraction

def extract(char, ref, a, lab, blobs):
    """One cleaned frame as straight-alpha float RGBA (0..255), cropped to its body plus a small margin."""
    fix = char.get('fix', {}).get(ref, {})
    if isinstance(ref, str):
        bi, k, n = (int(v) for v in ref.split('/'))
    else:
        bi, k, n = ref, 0, 1
    lid, x0, y0, x1, y1, _ = blobs[bi]
    pad = 3
    X0, Y0, X1, Y1 = max(0, x0 - pad), max(0, y0 - pad), min(a.shape[1], x1 + pad), min(a.shape[0], y1 + pad)
    core = lab[Y0:Y1, X0:X1] == lid
    if n > 1:
        # count columns over the upper three quarters only: dust and shadows along the ground bridge touching frames
        rows = np.nonzero(core.any(1))[0]
        upper = core[:rows.min() + int((rows.max() - rows.min()) * 0.75)]
        cuts = split_columns(upper.sum(0), n)
        cx0, cx1 = cuts[k], cuts[k + 1]
        colmask = np.zeros(core.shape[1], bool)
        colmask[cx0:cx1] = True
        core &= colmask[None, :]
    rgba = a[Y0:Y1, X0:X1].copy()
    for ex0, ey0, ex1, ey1 in fix.get('erase', []):
        core[max(0, ey0 - Y0):max(0, ey1 - Y0), max(0, ex0 - X0):max(0, ex1 - X0)] = False
    core = largest(core)
    # soft edge: the cleaned alpha, but only right around this body (no haze, no neighbouring frame, no erased effect)
    region = dilate(core, 2)
    if n > 1:
        region &= colmask[None, :]
    for ex0, ey0, ex1, ey1 in fix.get('erase', []):
        region[max(0, ey0 - Y0):max(0, ey1 - Y0), max(0, ex0 - X0):max(0, ex1 - X0)] = False
    alpha = rgba[..., 3] * region
    alpha[core] = 255.0                      # the body (cleaned alpha >= 128) is solid
    rgba[..., 3] = alpha
    rgba = defringe(rgba, core)
    return rgba


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
        a = np.array(Image.open(os.path.join(SRC, ch['strip'])).convert('RGBA')).astype(np.float32)
        a = clean_alpha(a)
        lab, comps = components(a[..., 3] >= 128)
        blobs = sorted([c for c in comps if c[5] >= MIN_BODY], key=lambda c: c[1])
        assert len(blobs) == ch['blobs'], f"{ch['id']}: {len(blobs)} bodies, expected {ch['blobs']}: frame indices would shift"
        refs = []
        for name, (frames, fps) in ch['anims'].items():
            for r in frames:
                if r not in refs:
                    refs.append(r)
        raw = {r: extract(ch, r, a, lab, blobs) for r in refs}
        # one scale for the whole character, from its first idle frame
        first = raw[list(ch['anims'].values())[0][0][0]]
        bx0, by0, bx1, by1 = bbox(first[..., 3], 64)
        kind, tiles = ch['size']
        s = tiles * PPT / float(by1 - by0 if kind == 'height' else bx1 - bx0)
        frames = {}
        for r in refs:
            f = resample(raw[r], s)
            ax, ay = anchor_of(f, ch['anchor'])
            x0, y0, x1, y1 = bbox(f[..., 3], 2)
            f = f[y0:y1, x0:x1]
            frames[r] = dict(img=f, ax=ax - x0, ay=ay - y0)
        print(f"{ch['id']}: {len(blobs)} bodies, {len(refs)} frames, scale {s:.3f}", file=sys.stderr)
        chars_out.append(dict(ch=ch, frames=frames, refs=refs))
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
    meta = dict(source='cores/originals/assets/src (generated art pack), built by tools/originals-sprites/build_sprites.py',
                pixels_per_tile=PPT, image='blizzard_atlas.png', size=[W, H],
                notes=['frames are trimmed; anchor_x/anchor_y = the feet (or the flyer\'s body centre) inside the trimmed '
                       'frame; cell/cell_anchor = the common untrimmed size every frame of the character fits in',
                       'frames[].source = the body in the strip, left to right from 0, or "body/piece/pieces" for frames '
                       'cut out of touching ones',
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
            strip='src/' + ch['strip'], anchor='feet (bottom centre)' if ch['anchor'] == 'feet' else 'body centre',
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
        first_frame = nframe
        for f in cm['frames']:
            n = f['w'] * f['h']
            idx = full[off:off + n].reshape(f['h'], f['w']).astype(np.uint8)
            off += n
            enc = encode_rle(idx)
            frame_rows.append((len(data), f['w'], f['h'], f['anchor_x'], f['anchor_y']))
            data += enc
            nframe += 1
        pals = [pal] if cid != 'buddy' else [skin_palette(pal, sk) for sk in SKINS]
        pal_first = len(pal_rows)
        for p in pals:
            pal_rows.append(np.concatenate([np.zeros((1, 4), np.uint8), p]))
        anim_first = len(anim_rows)
        for name, an in cm['animations'].items():
            anim_rows.append((cid, name, first_frame + an['frames'][0], len(an['frames']), an['fps']))
            assert an['frames'] == list(range(an['frames'][0], an['frames'][0] + len(an['frames']))), 'animation frames must be consecutive'
        char_rows.append((cid, first_frame, len(cm['frames']), pal_first, len(pals), anim_first, len(cm['animations'])))
    L = lines.append
    L('/* GENERATED by tools/originals-sprites/build_sprites.py from cores/originals/assets/src - do not edit. */')
    L('/* Blizzard Buddies character sprites: palette-indexed, run-length coded, stored at %d pixels per tile. */' % PPT)
    L('#ifndef RB_SPRITES_DATA_H')
    L('#define RB_SPRITES_DATA_H')
    L('#include <stdint.h>')
    L('#define SPR_PPT %d' % PPT)
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
    L('typedef struct { uint16_t first, count, pal, npal; } SprCharDef;               /* npal > 1: palette per skin */')
    L('static const SprCharDef SPR_CHARS[SPR_NCHARS] = {')
    for r in char_rows:
        L('    {%d, %d, %d, %d},   /* %s */' % (r[1], r[2], r[3], r[4], r[0]))
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
