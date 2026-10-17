#!/usr/bin/env python3
"""Checks the Genesis test ROM on a host build of a libretro core, through tools/core-harness (no Android involved).

Usage: verify.py CORE.dylib|.so [ROM] [--out DIR]
  CORE: a host (macOS/Linux) build of genesis_plus_gx or picodrive; the core is recognised from its file name.
Writes PNGs of the interesting frames to DIR (default: a temp dir) so a human can look at them, and exits non-zero if
any check fails. CC0.
"""
import os
import re
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
HARNESS = os.path.join(HERE, '..', 'core-harness', 'core-harness')

# RetroPad id names (harness syntax) -> Genesis button, per both cores' default layout
PRESS = [('y', 'A', 'red'), ('b', 'B', 'green'), ('a', 'C', 'blue'), ('start', 'Start', 'yellow'),
         ('l', 'X', 'cyan'), ('x', 'Y', 'magenta'), ('r', 'Z', 'orange'), ('select', 'Mode', 'purple')]
# checker colours as 3-bit CRAM values (see make_genesis_test_rom.py STATES)
COLOURS = {'grey': ((2, 2, 2), (3, 3, 3)), 'red': ((6, 0, 0), (7, 2, 2)), 'green': ((0, 5, 0), (2, 7, 2)),
           'blue': ((0, 0, 6), (2, 2, 7)), 'yellow': ((6, 6, 0), (7, 7, 2)), 'cyan': ((0, 6, 6), (2, 7, 7)),
           'magenta': ((6, 0, 6), (7, 2, 7)), 'orange': ((7, 3, 0), (7, 5, 1)), 'purple': ((3, 0, 5), (4, 2, 6))}

results = []


def check(name, ok, detail=''):
    results.append((name, ok))
    print(f"  {'PASS' if ok else 'FAIL'}  {name}" + (f'   ({detail})' if detail else ''))


def read_ppm(path):
    with open(path, 'rb') as f:
        data = f.read()
    m = re.match(rb'P6\s+(\d+)\s+(\d+)\s+255\s', data)
    w, h = int(m.group(1)), int(m.group(2))
    px = data[m.end():]
    return w, h, lambda x, y: tuple(px[(y * w + x) * 3:(y * w + x) * 3 + 3])


def classify(rgb):
    """nearest named checker colour (either shade), by distance in 8-bit RGB"""
    best = None
    for name, shades in COLOURS.items():
        for s in shades:
            ref = tuple(c * 255 // 7 for c in s)
            d = sum((a - b) ** 2 for a, b in zip(rgb, ref))
            if best is None or d < best[0]:
                best = (d, name)
    return best[1] if best[0] < 3 * 45 ** 2 else f'unknown{rgb}'


def bbox(get, w, h, pred):
    xs, ys = [], []
    for y in range(h):
        for x in range(w):
            if pred(get(x, y)):
                xs.append(x); ys.append(y)
    return (min(xs), min(ys), max(xs), max(ys)) if xs else None


def white(p): return min(p) >= 220
def black(p): return max(p) <= 20


def run(core, rom, args, out, tag, frames, dumps=(), sram=None):
    cmd = [HARNESS, core, rom, '-q', '-S', out, '-n', str(frames)] + args
    for f in dumps:
        cmd += ['-d', f'{f}:{os.path.join(out, f"{tag}-f{f}.ppm")}']
    if sram:
        cmd += ['-s', sram]
    p = subprocess.run(cmd, capture_output=True, text=True)
    if p.returncode != 0:
        raise SystemExit(f'harness failed: {" ".join(cmd)}\n{p.stdout}\n{p.stderr}')
    frames_out = {f: read_ppm(os.path.join(out, f'{tag}-f{f}.ppm')) for f in dumps}
    return p.stdout, frames_out


def sram_bytes(stdout):
    m = re.search(r'sram after run: size (\d+).*?first 16:((?: [0-9a-f]{2})*)', stdout)
    return int(m.group(1)), [int(b, 16) for b in m.group(2).split()] if m else (0, [])


def to_png(ppm):
    """convert for viewing (sips is on every Mac; skipped elsewhere)"""
    png = ppm[:-4] + '.png'
    if subprocess.run(['sips', '-s', 'format', 'png', ppm, '--out', png], capture_output=True).returncode == 0:
        os.remove(ppm)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    out = sys.argv[sys.argv.index('--out') + 1] if '--out' in sys.argv else tempfile.mkdtemp(prefix='gen-verify-')
    if '--out' in sys.argv: args.remove(out)
    if not args:
        raise SystemExit(__doc__)
    core = os.path.abspath(args[0])
    rom = os.path.abspath(args[1] if len(args) > 1 else os.path.join(HERE, 'rb-testrom.md'))
    os.makedirs(out, exist_ok=True)
    pico = 'pico' in os.path.basename(core).lower()
    six = ['-o', 'picodrive_input1=6 button pad'] if pico else []          # gpgx: auto -> 6-button (header 'J6')
    three = [] if pico else ['-c', '0:257']                                 # gpgx: RETRO_DEVICE_MDPAD_3B
    print(f'core {core}\nrom  {rom}\nout  {out}')

    print('boot')
    so, fr = run(core, rom, six, out, 'boot', 30, [30])
    w, h, get = fr[30]
    check('frame is 320x224', (w, h) == (320, 224), f'{w}x{h}')
    check('checkerboard grey', classify(get(4, 100)) == 'grey' and classify(get(12, 100)) == 'grey' and get(4, 100) != get(12, 100),
          f'{get(4, 100)} {get(12, 100)}')
    sq = bbox(lambda x, y: get(x + 100, y + 60), 120, 120, white)
    check('P1 square at (152,104)-(167,119)', sq == (52, 44, 67, 59), f'{sq and (sq[0] + 100, sq[1] + 60, sq[2] + 100, sq[3] + 60)}')
    p2 = bbox(get, w, h, black)
    check('P2 square at (40,40)-(55,55)', p2 == (40, 40, 55, 55), str(p2))
    check('6-button marker shown (6-button pad configured)', white(get(12, 12)), str(get(12, 12)))
    check('bar empty on a fresh SRAM', not white(get(4, 212)), str(get(4, 212)))
    m = re.search(r'audio: (\d+) stereo frames.*?-> (\d+) Hz.*?peak (\d+)', so)
    check('audio arrives (~1 s of samples per 60 frames)', int(m.group(2)) > 40000, m.group(0))
    check('silent at boot (no button pressed)', int(m.group(3)) < 2000, f'peak {m.group(3)}')
    to_png(os.path.join(out, 'boot-f30.ppm'))

    print('buttons (tap for 3 frames, colour must stick after release)')
    seen = {}
    for rp, gen, colour in PRESS:
        so, fr = run(core, rom, six + ['-i', f'20:0:{rp}', '-i', '23:0:none'], out, f'btn-{gen}', 40, [40])
        c = classify(fr[40][2](4, 100))
        seen[gen] = c
        check(f'RetroPad {rp:6s} -> {gen:5s} -> {colour}', c == colour, c)
        if gen == 'A':
            peak = int(re.search(r'peak (\d+)', so).group(1))
            check('A plays a tone', peak > 2000, f'peak {peak}')
        to_png(os.path.join(out, f'btn-{gen}-f40.ppm'))
    check('A and B give different pictures', seen['A'] != seen['B'])

    print('3-button pad configured: X/Y/Z/Mode must do nothing')
    so, fr = run(core, rom, three, out, 'three', 30, [30])
    check('no 6-button marker', not white(fr[30][2](12, 12)), str(fr[30][2](12, 12)))
    for rp, gen, _ in PRESS[4:]:
        so, fr = run(core, rom, three + ['-i', f'20:0:{rp}', '-i', '23:0:none'], out, f'three-{gen}', 40, [40])
        c = classify(fr[40][2](4, 100))
        check(f'{gen} ignored on a 3-button pad', c == 'grey', c)
    so, fr = run(core, rom, three + ['-i', '20:0:y', '-i', '23:0:none'], out, 'three-A', 40, [40])
    check('A still works on a 3-button pad', classify(fr[40][2](4, 100)) == 'red')

    print('D-pad')
    so, fr = run(core, rom, six + ['-i', '10:0:right', '-i', '30:0:down', '-i', '40:0:none'], out, 'dpad', 50, [9, 50])
    w, h, get = fr[50]
    sq = bbox(lambda x, y: get(x, y + 30), w, 150, white)
    # 20 frames right, 10 frames down at 2 px/frame (+-1 frame of input latency)
    check('P1 square moved right ~40 px and down ~20 px', bool(sq) and 186 <= sq[0] <= 196 and 120 <= sq[1] + 30 <= 128,
          f'top-left now {sq and (sq[0], sq[1] + 30)}, was (152,104)')
    to_png(os.path.join(out, 'dpad-f50.ppm')); os.remove(os.path.join(out, 'dpad-f9.ppm'))
    so, fr = run(core, rom, six + ['-i', '10:0:left+up', '-i', '200:0:none'], out, 'clamp', 200, [200])
    sq = bbox(fr[200][2], 100, 100, white)
    check('P1 square stops at the top-left corner', sq == (0, 0, 15, 15), str(sq))
    to_png(os.path.join(out, 'clamp-f200.ppm'))

    print('player 2')
    so, fr = run(core, rom, six + ['-i', '10:1:right+down', '-i', '30:1:none'], out, 'p2', 40, [40])
    w, h, get = fr[40]
    p2 = bbox(get, w, h, black)
    check('port 2 D-pad moves the black square (~40,~40 px)', p2 is not None and 74 <= p2[0] <= 84 and 74 <= p2[1] <= 84, str(p2))
    sq = bbox(lambda x, y: get(x + 100, y + 60), 120, 120, white)
    check('P1 square did not move', sq == (52, 44, 67, 59), f'{sq and (sq[0] + 100, sq[1] + 60)}')
    so, fr = run(core, rom, six + ['-i', '10:1:y+b', '-i', '15:1:none'], out, 'p2btn', 30, [30])
    check('port 2 buttons do not change the colour (P1 only)', classify(fr[30][2](4, 100)) == 'grey')
    to_png(os.path.join(out, 'p2-f40.ppm')); os.remove(os.path.join(out, 'p2btn-f30.ppm'))

    print('Start -> battery SRAM')
    sram = os.path.join(out, 'rb-testrom.srm')
    if os.path.exists(sram): os.remove(sram)
    so, fr = run(core, rom, six + ['-i', '20:0:start', '-i', '23:0:none', '-i', '30:0:start', '-i', '33:0:none'],
                 out, 'sram1', 40, [40], sram=sram)
    size, first = sram_bytes(so)
    check('SRAM: magic $A5 at offset 1, counter 2 at offset 3', len(first) >= 4 and first[1] == 0xA5 and first[3] == 2,
          f'size {size}, first bytes {" ".join("%02x" % b for b in first[:8])}')
    get = fr[40][2]
    check('bar shows 2 cells', white(get(4, 212)) and white(get(12, 212)) and not white(get(20, 212)))
    so, fr = run(core, rom, six + ['-i', '20:0:start', '-i', '23:0:none'], out, 'sram2', 40, [10, 40], sram=sram)
    get10, get40 = fr[10][2], fr[40][2]
    check('after reload: bar still shows 2 cells (SRAM persisted)', white(get10(12, 212)) and not white(get10(20, 212)))
    check('one more Start -> 3 cells', white(get40(20, 212)) and not white(get40(28, 212)))
    size, first = sram_bytes(so)
    check('SRAM counter now 3', len(first) >= 4 and first[3] == 3, f'size {size}')
    to_png(os.path.join(out, 'sram1-f40.ppm')); to_png(os.path.join(out, 'sram2-f40.ppm'))
    os.remove(os.path.join(out, 'sram2-f10.ppm'))

    failed = [n for n, ok in results if not ok]
    print(f'\n{len(results) - len(failed)}/{len(results)} checks passed; frames in {out}')
    sys.exit(1 if failed else 0)


if __name__ == '__main__':
    main()
