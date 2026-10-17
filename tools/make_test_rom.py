#!/usr/bin/env python3
"""Generates rb-testrom.nes: a tiny original NROM test program for RetroBox (public domain / CC0).

What it does (so a human or a script can verify video, input and audio):
  * draws a checkerboard background and a movable 8x8 square sprite
  * D-pad moves the sprite 1 px/frame
  * Player 2 (second controller, read from $4017) moves a second, blue square with its D-pad
  * A held   -> background colour red   + pulse-wave tone on APU channel 1
  * B held   -> background colour green
  * Start    -> background colour blue
  * nothing  -> background colour black
Usage: make_test_rom.py [output.nes]
"""
import sys

OPS = {  # mnemonic: {mode: opcode}
    'SEI': {'imp': 0x78}, 'CLD': {'imp': 0xD8}, 'CLC': {'imp': 0x18}, 'SEC': {'imp': 0x38},
    'LDX': {'imm': 0xA2}, 'LDY': {'imm': 0xA0}, 'STX': {'zp': 0x86, 'abs': 0x8E},
    'TXS': {'imp': 0x9A}, 'INX': {'imp': 0xE8}, 'DEX': {'imp': 0xCA}, 'INY': {'imp': 0xC8}, 'DEY': {'imp': 0x88},
    'BIT': {'abs': 0x2C}, 'BPL': {'rel': 0x10}, 'BMI': {'rel': 0x30}, 'BNE': {'rel': 0xD0}, 'BEQ': {'rel': 0xF0},
    'LDA': {'imm': 0xA9, 'zp': 0xA5, 'abs': 0xAD, 'absx': 0xBD}, 'STA': {'zp': 0x85, 'abs': 0x8D, 'absx': 0x9D},
    'TAX': {'imp': 0xAA}, 'TXA': {'imp': 0x8A}, 'PHA': {'imp': 0x48}, 'PLA': {'imp': 0x68},
    'CPX': {'imm': 0xE0}, 'AND': {'imm': 0x29}, 'EOR': {'imm': 0x49}, 'ORA': {'imm': 0x09},
    'LSR': {'acc': 0x4A}, 'ROL': {'zp': 0x26}, 'INC': {'zp': 0xE6}, 'DEC': {'zp': 0xC6},
    'JMP': {'abs': 0x4C}, 'RTI': {'imp': 0x40}, 'NOP': {'imp': 0xEA},
}
SIZE = {'imp': 1, 'acc': 1, 'imm': 2, 'zp': 2, 'rel': 2, 'abs': 3, 'absx': 3}
ORG = 0xC000


def parse(line):
    line = line.split(';')[0].strip()
    if not line:
        return None
    label = None
    if line.endswith(':'):
        return ('label', line[:-1])
    parts = line.split(None, 1)
    mn = parts[0].upper()
    arg = parts[1].strip() if len(parts) > 1 else None
    return ('op', mn, arg)


def num(tok, labels):
    tok = tok.strip()
    if tok.startswith('$'):
        return int(tok[1:], 16)
    if tok.startswith('%'):
        return int(tok[1:], 2)
    if tok in labels:
        return labels[tok]
    return int(tok)


def mode_of(mn, arg):
    if arg is None:
        return 'acc' if 'acc' in OPS[mn] and 'imp' not in OPS[mn] else 'imp'
    a = arg.strip()
    if a.upper() == 'A' and 'acc' in OPS[mn]:
        return 'acc'
    if a.startswith('#'):
        return 'imm'
    if 'rel' in OPS[mn]:
        return 'rel'
    if a.upper().endswith(',X'):
        return 'absx'
    # zero page if value is a literal < 0x100 and zp form exists
    base = a
    if base.startswith('$') and len(base) <= 3 and 'zp' in OPS[mn]:
        return 'zp'
    return 'abs'


def assemble(src):
    items = [p for p in (parse(l) for l in src.splitlines()) if p]
    labels, pc, prog = {}, ORG, []
    for it in items:
        if it[0] == 'label':
            labels[it[1]] = pc
        else:
            _, mn, arg = it
            m = mode_of(mn, arg)
            if m not in OPS[mn]:
                raise SystemExit(f'bad mode {m} for {mn} {arg}')
            prog.append((pc, mn, arg, m))
            pc += SIZE[m]
    out = bytearray()
    for pc, mn, arg, m in prog:
        out.append(OPS[mn][m])
        if m in ('imp', 'acc'):
            continue
        a = arg.strip()
        if m == 'imm':
            out.append(num(a[1:], labels) & 0xFF)
        elif m == 'zp':
            out.append(num(a, labels) & 0xFF)
        elif m == 'rel':
            off = num(a, labels) - (pc + 2)
            assert -128 <= off <= 127, f'branch out of range at {mn} {arg}'
            out.append(off & 0xFF)
        else:
            v = num(a[:-2] if m == 'absx' else a, labels)
            out += bytes([v & 0xFF, v >> 8])
    return bytes(out), labels


PROGRAM = """
reset:
  SEI
  CLD
  LDX #$40
  STX $4017
  LDX #$FF
  TXS
  INX
  STX $2000
  STX $2001
  STX $4010
vb1:
  BIT $2002
  BPL vb1
  LDA #0
  TAX
clr:
  STA $0000,X
  STA $0300,X
  STA $0400,X
  INX
  BNE clr
vb2:
  BIT $2002
  BPL vb2
  LDA $2002
  LDA #$3F
  STA $2006
  LDA #$00
  STA $2006
  LDX #0
pal:
  LDA palette,X
  STA $2007
  INX
  CPX #32
  BNE pal
  LDA $2002
  LDA #$20
  STA $2006
  LDA #$00
  STA $2006
  LDA #1
  LDY #30
row:
  LDX #32
col:
  STA $2007
  EOR #3
  DEX
  BNE col
  EOR #3
  DEY
  BNE row
  LDA #0
  LDX #64
at:
  STA $2007
  DEX
  BNE at
  LDA #1
  STA $4015
  LDA #$78
  STA $00
  LDA #$70
  STA $01
  LDA #$A0
  STA $04
  LDA #$90
  STA $05
  LDA #0
  STA $2005
  STA $2005
  LDA #%10000000
  STA $2000
  LDA #%00011110
  STA $2001
forever:
  JMP forever

nmi:
  PHA
  TXA
  PHA
  LDA #1
  STA $4016
  LDA #0
  STA $4016
  LDX #8
rd:
  LDA $4016
  LSR A
  ROL $02
  DEX
  BNE rd
  LDX #8
rd2:
  LDA $4017
  LSR A
  ROL $06
  DEX
  BNE rd2
  LDA $02
  AND #$08
  BEQ nu
  DEC $01
nu:
  LDA $02
  AND #$04
  BEQ nd
  INC $01
nd:
  LDA $02
  AND #$02
  BEQ nl
  DEC $00
nl:
  LDA $02
  AND #$01
  BEQ nr
  INC $00
nr:
  LDA $01
  STA $0200
  LDA #3
  STA $0201
  LDA #0
  STA $0202
  LDA $00
  STA $0203
  LDA $06
  AND #$08
  BEQ qu
  DEC $05
qu:
  LDA $06
  AND #$04
  BEQ qd
  INC $05
qd:
  LDA $06
  AND #$02
  BEQ ql
  DEC $04
ql:
  LDA $06
  AND #$01
  BEQ qr
  INC $04
qr:
  LDA $05
  STA $0204
  LDA #3
  STA $0205
  LDA #1
  STA $0206
  LDA $04
  STA $0207
  LDA #2
  STA $4014
  LDA $2002
  LDA #$3F
  STA $2006
  LDA #$00
  STA $2006
  LDA $02
  AND #$80
  BEQ notA
  LDA #$16
  JMP setc
notA:
  LDA $02
  AND #$40
  BEQ notB
  LDA #$2A
  JMP setc
notB:
  LDA $02
  AND #$10
  BEQ notS
  LDA #$12
  JMP setc
notS:
  LDA #$0F
setc:
  STA $2007
  LDA #%10000000
  STA $2000
  LDA #0
  STA $2005
  STA $2005
  LDA $02
  AND #$80
  BEQ silent
  LDA $03
  AND #$80
  BNE audiodone
  LDA #$BF
  STA $4000
  LDA #$FD
  STA $4002
  LDA #$01
  STA $4003
  JMP audiodone
silent:
  LDA #$B0
  STA $4000
audiodone:
  LDA $02
  STA $03
  PLA
  TAX
  PLA
  RTI
palette:
"""

# background palettes, then sprite palette 0 (Player 1, orange/red) and 1 (Player 2, blue); 2 and 3 repeat palette 0
PALETTE = bytes([0x0F, 0x30, 0x10, 0x00] * 4 + [0x0F, 0x27, 0x16, 0x30] + [0x0F, 0x21, 0x11, 0x31] + [0x0F, 0x27, 0x16, 0x30] * 2)


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else 'rb-testrom.nes'
    # 'palette' label must point at data appended after code; assemble code first to learn its size.
    code, labels = assemble(PROGRAM)
    labels['palette'] = ORG + len(code)
    code, labels = assemble(PROGRAM)           # second pass with the palette label known
    assert labels['palette'] == ORG + len(code)
    prg = bytearray(code + PALETTE)
    assert len(prg) < 0x4000 - 6
    prg += bytes(0x4000 - len(prg))
    vec = labels['nmi'].to_bytes(2, 'little') + labels['reset'].to_bytes(2, 'little') + labels['reset'].to_bytes(2, 'little')
    prg[0x3FFA:0x4000] = vec
    chr_ = bytearray(0x2000)
    t1 = bytes([0xF0] * 4 + [0x0F] * 4) + bytes(8)     # checker
    t2 = bytes([0x0F] * 4 + [0xF0] * 4) + bytes(8)     # inverse checker
    t3 = bytes([0xFF] * 8) + bytes(8)                  # solid square (sprite)
    chr_[16:32], chr_[32:48], chr_[48:64] = t1, t2, t3
    header = bytes([0x4E, 0x45, 0x53, 0x1A, 1, 1, 0, 0]) + bytes(8)
    open(out, 'wb').write(header + prg + chr_)
    print(f'wrote {out}: {len(header) + len(prg) + len(chr_)} bytes, code {len(code)} bytes')


main()
