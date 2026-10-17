#!/usr/bin/env python3
"""Generates rb-testrom.md: an original (CC0) Sega Genesis / Mega Drive test program for RetroBox.

Behaves like the other RetroBox test ROMs (tools/make_test_roms.py, tools/gba-testrom/main.c):
  * checkerboard background (plane B), 320x224 (H40)
  * the player 1 D-pad moves a white 16x16 square (sprite), 2 px/frame
  * the player 2 D-pad moves a black 16x16 square
  * the checkerboard colour shows the LAST pressed player 1 button (sticky, so a short tap is enough):
        initially grey, A red, B green, C blue, Start yellow,
        6-button pad only: X cyan, Y magenta, Z orange, Mode purple
    (A also plays a PSG tone; any other state is silent)
  * a white 8x8 cell at the top left (tile column 1, row 1) shows that a 6-button pad was detected on port 1
  * Start: counter++ (0..20, wraps), stored in battery-backed SRAM (header 'RA' $F8 $20, odd bytes $200001-$203FFF):
        byte $200001 = magic $A5, byte $200003 = counter; drawn as a bar of white 8x8 cells on tile row 26
No copyrighted code or data. The only Sega-related bytes are the header's system-type field "SEGA MEGA DRIVE " that the
cartridge header format defines; the program does not do the TMSS "SEGA" register write (emulators without a TMSS BIOS
do not need it; a real TMSS console would lock the VDP).

Usage: make_genesis_test_rom.py [output-file]      (default: tools/genesis-testrom/rb-testrom.md)
The output is a plain big-endian binary (.md / .gen / .bin are the same bytes; not SMD-interleaved).
"""
import os
import struct
import sys

# ---------------------------------------------------------------------------------------------------------------------
# Tiny 68000 assembler: just the instruction forms this program needs. Operands are tuples:
#   D(n) A(n) ind(n)=(An) imm(v) absl(addr) and labels (strings) for branches / lea / immediates.

def D(n): return ('d', n)
def A(n): return ('a', n)
def ind(n): return ('ind', n)
def imm(v): return ('imm', v)
def absl(v): return ('abs', v)
def reg(r): return r[1] if isinstance(r, tuple) else r   # D(n) or plain n where only a register fits

SIZE = {'b': 0, 'w': 1, 'l': 2}           # size field for most ops
MOVE_SIZE = {'b': 1, 'w': 3, 'l': 2}      # MOVE's own size encoding
COND = {'ra': 0, 'sr': 1, 'hi': 2, 'ls': 3, 'cc': 4, 'cs': 5, 'ne': 6, 'eq': 7,
        'pl': 10, 'mi': 11, 'ge': 12, 'lt': 13, 'gt': 14, 'le': 15}


class Asm:
    def __init__(self, org):
        self.org, self.b, self.labels, self.fix = org, bytearray(), {}, []

    def pc(self): return self.org + len(self.b)

    def label(self, n):
        assert n not in self.labels, n
        self.labels[n] = self.pc()

    def w(self, *ws):
        for v in ws: self.b += struct.pack('>H', v & 0xFFFF)

    def l(self, v):
        if isinstance(v, str): self.fix.append(('abs32', len(self.b), v)); v = 0
        self.b += struct.pack('>I', v & 0xFFFFFFFF)

    def ea(self, op):
        """-> (mode, reg, extension-emitter) for an effective address"""
        k, v = op
        if k == 'd': return 0, v, lambda sz: None
        if k == 'a': return 1, v, lambda sz: None
        if k == 'ind': return 2, v, lambda sz: None
        if k == 'abs': return 7, 1, lambda sz: self.l(v)
        if k == 'imm':
            def ext(sz):
                if sz == 'l': self.l(v)
                else: self.w(v if not isinstance(v, str) else 0)
            return 7, 4, ext
        raise ValueError(op)

    def move(self, sz, src, dst):
        sm, sr, sx = self.ea(src); dm, dr, dx = self.ea(dst)
        self.w((MOVE_SIZE[sz] << 12) | (dr << 9) | (dm << 6) | (sm << 3) | sr); sx(sz); dx(sz)

    def moveq(self, v, dn): assert -128 <= v <= 127; self.w(0x7000 | (dn << 9) | (v & 0xFF))

    def lea(self, src, an):
        if isinstance(src, str): self.w(0x41F9 | (an << 9)); self.l(src); return
        m, r, x = self.ea(src); self.w(0x41C0 | (an << 9) | (m << 3) | r); x('l')

    def _imm_op(self, base, sz, v, dst):   # ANDI/ORI/EORI/CMPI/SUBI/ADDI #v,<ea>
        m, r, x = self.ea(dst); self.w(base | (SIZE[sz] << 6) | (m << 3) | r)
        if sz == 'l': self.l(v)
        else: self.w(v)
        x(sz)

    def ori(self, sz, v, d): self._imm_op(0x0000, sz, v, d)
    def andi(self, sz, v, d): self._imm_op(0x0200, sz, v, d)
    def eori(self, sz, v, d): self._imm_op(0x0A00, sz, v, d)
    def cmpi(self, sz, v, d): self._imm_op(0x0C00, sz, v, d)

    def _q(self, base, sz, q, dst):        # ADDQ/SUBQ
        assert 1 <= q <= 8; m, r, x = self.ea(dst)
        self.w(base | ((q & 7) << 9) | (SIZE[sz] << 6) | (m << 3) | r); x(sz)

    def addq(self, sz, q, d): self._q(0x5000, sz, q, d)
    def subq(self, sz, q, d): self._q(0x5100, sz, q, d)

    def _dn_op(self, base, sz, src, dn):   # ADD/SUB/CMP/AND/OR <ea>,Dn
        m, r, x = self.ea(src); self.w(base | (reg(dn) << 9) | (SIZE[sz] << 6) | (m << 3) | r); x(sz)

    def add(self, sz, s, dn): self._dn_op(0xD000, sz, s, dn)
    def sub(self, sz, s, dn): self._dn_op(0x9000, sz, s, dn)
    def cmp(self, sz, s, dn): self._dn_op(0xB000, sz, s, dn)
    def and_(self, sz, s, dn): self._dn_op(0xC000, sz, s, dn)
    def or_(self, sz, s, dn): self._dn_op(0x8000, sz, s, dn)

    def adda_w(self, dn, an): self.w(0xD0C0 | (an << 9) | dn)   # adda.w Dn,An

    def _single(self, base, sz, dst):      # TST/CLR/NOT <ea>
        m, r, x = self.ea(dst); self.w(base | (SIZE[sz] << 6) | (m << 3) | r); x(sz)

    def tst(self, sz, d): self._single(0x4A00, sz, d)
    def clr(self, sz, d): self._single(0x4200, sz, d)
    def not_(self, sz, d): self._single(0x4600, sz, d)

    def btst(self, bit, dst): m, r, x = self.ea(dst); self.w(0x0800 | (m << 3) | r, bit); x('b')

    def lsl(self, sz, q, dn): assert 1 <= q <= 8; self.w(0xE108 | ((q & 7) << 9) | (SIZE[sz] << 6) | reg(dn))
    def lsr(self, sz, q, dn): assert 1 <= q <= 8; self.w(0xE008 | ((q & 7) << 9) | (SIZE[sz] << 6) | reg(dn))

    def br(self, cc, target):               # Bcc / BRA / BSR with a 16-bit displacement
        self.w(0x6000 | (COND[cc] << 8)); self.fix.append(('rel16', len(self.b), target)); self.w(0)

    def dbra(self, dn, target): self.w(0x51C8 | dn); self.fix.append(('rel16', len(self.b), target)); self.w(0)
    def rts(self): self.w(0x4E75)
    def rte(self): self.w(0x4E73)
    def nop(self): self.w(0x4E71)
    def move_sr(self, v): self.w(0x46FC, v)

    def finish(self):
        for kind, pos, label in self.fix:
            t = self.labels[label]
            if kind == 'rel16':
                off = t - (self.org + pos)
                assert -32768 <= off <= 32767, label
                self.b[pos:pos + 2] = struct.pack('>h', off)
            else:
                self.b[pos:pos + 4] = struct.pack('>I', t)
        return bytes(self.b)


# ---------------------------------------------------------------------------------------------------------------------
VDP_DATA, VDP_CTRL = 0xC00000, 0xC00004
PSG = 0xC00011
IO_DATA1, IO_DATA2, IO_CTRL1, IO_CTRL2 = 0xA10003, 0xA10005, 0xA10009, 0xA1000B
SRAM_CTRL, SRAM_MAGIC, SRAM_COUNT = 0xA130F1, 0x200001, 0x200003
RAM = 0xFF0000
SX, SY, S2X, S2Y, STATE, PREVST, CNT, PAD1, PAD2 = (RAM + 2 * i for i in range(9))

PLANE_A, PLANE_B, SPRITES, HSCROLL = 0xC000, 0xE000, 0xF000, 0xFC00
TILE_WHITE, TILE_BLACK = 8, 12                 # 4 consecutive solid tiles each (16x16 sprites use tile..tile+3)
BAR_ROW, BAR_CELLS, MARK_ADDR = 26, 20, PLANE_A + (1 * 64 + 1) * 2


def vram_w(addr): return 0x40000000 | ((addr & 0x3FFF) << 16) | (addr >> 14)
def cram_w(addr): return 0xC0000000 | (addr << 16)
VSRAM_W = 0x40000010


def rgb(r, g, b):                               # 3 bits per channel -> CRAM word 0000BBB0GGG0RRR0
    return (b << 9) | (g << 5) | (r << 1)


# state -> (checker colour 1, checker colour 2). State order: none, A, B, C, Start, X, Y, Z, Mode
STATES = [
    (rgb(2, 2, 2), rgb(3, 3, 3)),   # initial: grey
    (rgb(6, 0, 0), rgb(7, 2, 2)),   # A red
    (rgb(0, 5, 0), rgb(2, 7, 2)),   # B green
    (rgb(0, 0, 6), rgb(2, 2, 7)),   # C blue
    (rgb(6, 6, 0), rgb(7, 7, 2)),   # Start yellow
    (rgb(0, 6, 6), rgb(2, 7, 7)),   # X cyan
    (rgb(6, 0, 6), rgb(7, 2, 7)),   # Y magenta
    (rgb(7, 3, 0), rgb(7, 5, 1)),   # Z orange
    (rgb(3, 0, 5), rgb(4, 2, 6)),   # Mode purple
]
# pad word bits (active high), built by read_pad: 0 U 1 D 2 L 3 R 4 B 5 C 6 A 7 Start 8 Z 9 Y 10 X 11 Mode 15 six-button
PRIORITY = [(6, 1), (4, 2), (5, 3), (7, 4), (10, 5), (9, 6), (8, 7), (11, 8)]   # (pad bit, state)


def program():
    a = Asm(0)
    # --- 68000 vector table: SSP, reset, then every exception -> halt, autovectors -> rte
    a.l(0x00FFFE00); a.l('reset')
    for v in range(2, 64):
        a.l('rte_' if 25 <= v <= 31 else 'halt')
    # --- cartridge header ($100-$1FF)
    hdr = bytearray(b' ' * 256)

    def put(off, s): hdr[off - 0x100:off - 0x100 + len(s)] = s
    put(0x100, b'SEGA MEGA DRIVE ')
    put(0x110, b'(C)RBOX 2026.OCT')
    put(0x120, b'RETROBOX TEST ROM'.ljust(48))
    put(0x150, b'RETROBOX TEST ROM'.ljust(48))
    put(0x180, b'GM 00000000-00')
    put(0x18E, b'\0\0')                                   # checksum, filled in below
    put(0x190, b'J6'.ljust(16))                            # 3-button and 6-button pad
    put(0x1A0, struct.pack('>II', 0, ROM_SIZE - 1))
    put(0x1A8, struct.pack('>II', 0xFF0000, 0xFFFFFF))
    put(0x1B0, b'RA' + bytes([0xF8, 0x20]) + struct.pack('>II', 0x200001, 0x203FFF))   # battery SRAM, odd bytes
    put(0x1BC, b' ' * 12)
    put(0x1C8, b'CC0 RetroBox test program'.ljust(40))
    put(0x1F0, b'JUE'.ljust(16))
    assert len(a.b) == 0x100
    a.b += hdr

    # --- reset
    a.label('reset')
    a.move_sr(0x2700)
    a.move('b', imm(0x40), absl(IO_CTRL1)); a.move('b', imm(0x40), absl(IO_CTRL2))   # TH pins = output
    a.move('b', imm(0x40), absl(IO_DATA1)); a.move('b', imm(0x40), absl(IO_DATA2))
    a.move('w', absl(VDP_CTRL), D(0))                     # clear a pending control-port write
    for reg, val in [(0, 0x04), (1, 0x04), (2, PLANE_A >> 10), (3, 0x2C), (4, PLANE_B >> 13), (5, SPRITES >> 9),
                     (6, 0), (7, 0x00), (10, 0xFF), (11, 0x00), (12, 0x81), (13, HSCROLL >> 10), (15, 2), (16, 0x01),
                     (17, 0), (18, 0)]:
        a.move('w', imm(0x8000 | (reg << 8) | val), absl(VDP_CTRL))
    a.lea(absl(VDP_DATA), 0)
    a.moveq(0, 0)
    # clear VRAM (32768 words), CRAM (64), VSRAM (40)
    for cmd, n in [(vram_w(0), 0x8000), (cram_w(0), 64), (VSRAM_W, 40)]:
        a.move('l', imm(cmd), absl(VDP_CTRL)); a.move('w', imm(n - 1), D(1))
        lbl = f'clr{cmd:x}'; a.label(lbl); a.move('w', D(0), ind(0)); a.dbra(1, lbl)
    # solid tiles: 1 -> colour 1, 2 -> colour 2, 8..11 -> colour 3 (white), 12..15 -> colour 4 (black)
    for tile, count, colour in [(1, 1, 1), (2, 1, 2), (TILE_WHITE, 4, 3), (TILE_BLACK, 4, 4)]:
        a.move('l', imm(vram_w(tile * 32)), absl(VDP_CTRL))
        a.move('l', imm(0x11111111 * colour), D(0)); a.move('w', imm(count * 8 - 1), D(1))
        lbl = f'tile{tile}'; a.label(lbl); a.move('l', D(0), ind(0)); a.dbra(1, lbl)
    # plane B checkerboard (64x32 cells): rows alternate 1,2,1,2... / 2,1,2,1...
    a.move('l', imm(vram_w(PLANE_B)), absl(VDP_CTRL))
    a.move('l', imm(0x00010002), D(2)); a.move('w', imm(31), D(3))
    a.label('cb_row'); a.move('w', imm(31), D(4))
    a.label('cb_col'); a.move('l', D(2), ind(0)); a.dbra(4, 'cb_col')
    a.eori('l', 0x00030003, D(2)); a.dbra(3, 'cb_row')
    # fixed palette entries: 3 white, 4 black
    a.move('l', imm(cram_w(6)), absl(VDP_CTRL)); a.move('w', imm(rgb(7, 7, 7)), ind(0)); a.move('w', imm(0), ind(0))
    # PSG: silence all four channels
    for v in (0x9F, 0xBF, 0xDF, 0xFF):
        a.move('b', imm(v), absl(PSG))
    # SRAM: map it ($A130F1 bit 0), check the magic, load the counter
    a.move('b', imm(1), absl(SRAM_CTRL))
    a.moveq(0, 0); a.move('b', absl(SRAM_MAGIC), D(0)); a.cmpi('w', 0xA5, D(0)); a.br('eq', 'sram_ok')
    a.move('b', imm(0xA5), absl(SRAM_MAGIC)); a.move('b', imm(0), absl(SRAM_COUNT))
    a.label('sram_ok')
    a.moveq(0, 0); a.move('b', absl(SRAM_COUNT), D(0))
    a.cmpi('w', BAR_CELLS, D(0)); a.br('ls', 'cnt_ok'); a.moveq(0, 0)
    a.label('cnt_ok'); a.move('w', D(0), absl(CNT))
    # variables
    a.move('w', imm(152), absl(SX)); a.move('w', imm(104), absl(SY))
    a.move('w', imm(40), absl(S2X)); a.move('w', imm(40), absl(S2Y))
    a.move('w', imm(0xFFFF), absl(STATE)); a.clr('w', absl(PREVST))
    a.br('sr', 'draw_bar')
    a.br('sr', 'update_sprites')
    a.move('w', imm(0x8100 | 0x44), absl(VDP_CTRL))       # display on, mode 5, no interrupts

    # --- main loop, once per frame
    a.label('main')
    a.label('wv1'); a.move('w', absl(VDP_CTRL), D(0)); a.btst(3, D(0)); a.br('ne', 'wv1')   # leave vblank
    a.label('wv2'); a.move('w', absl(VDP_CTRL), D(0)); a.btst(3, D(0)); a.br('eq', 'wv2')   # enter vblank
    a.lea(absl(IO_DATA1), 0); a.br('sr', 'read_pad'); a.move('w', D(0), absl(PAD1))
    a.lea(absl(IO_DATA2), 0); a.br('sr', 'read_pad'); a.move('w', D(0), absl(PAD2))
    a.lea(absl(VDP_DATA), 0)
    # 6-button marker
    a.move('l', imm(vram_w(MARK_ADDR)), absl(VDP_CTRL))
    a.move('w', absl(PAD1), D(0)); a.moveq(0, 1); a.btst(15, D(0)); a.br('eq', 'mark0'); a.moveq(TILE_WHITE, 1)
    a.label('mark0'); a.move('w', D(1), ind(0))
    # new state = first pressed button in PRIORITY, else keep (initial state 0)
    a.move('w', absl(STATE), D(1)); a.br('pl', 'st_have'); a.moveq(0, 1)
    a.label('st_have')
    for bit, st in PRIORITY:
        a.btst(bit, D(0)); a.br('eq', f'nb{bit}'); a.moveq(st, 1); a.br('ra', 'st_done'); a.label(f'nb{bit}')
    a.label('st_done')
    a.cmp('w', absl(STATE), 1); a.br('eq', 'st_same')
    a.move('w', D(1), absl(STATE))
    a.move('l', imm(cram_w(2)), absl(VDP_CTRL))           # palette entries 1, 2
    a.lea('colours', 1); a.move('w', D(1), D(2)); a.lsl('w', 2, D(2)); a.adda_w(2, 1)
    a.move('l', ('ind', 1), ind(0))
    a.cmpi('w', 1, D(1)); a.br('ne', 'quiet')
    a.move('b', imm(0x8E), absl(PSG)); a.move('b', imm(0x0F), absl(PSG)); a.move('b', imm(0x90), absl(PSG))  # ~440 Hz
    a.br('ra', 'st_same')
    a.label('quiet'); a.move('b', imm(0x9F), absl(PSG))
    a.label('st_same')
    # movement
    for pad, x, y in [(PAD1, SX, SY), (PAD2, S2X, S2Y)]:
        a.move('w', absl(pad), D(0)); a.move('w', absl(x), D(2)); a.move('w', absl(y), D(3))
        p = f'm{pad & 0xFF:x}'
        a.btst(3, D(0)); a.br('eq', p + 'r'); a.addq('w', 2, D(2)); a.label(p + 'r')
        a.btst(2, D(0)); a.br('eq', p + 'l'); a.subq('w', 2, D(2)); a.label(p + 'l')
        a.btst(1, D(0)); a.br('eq', p + 'd'); a.addq('w', 2, D(3)); a.label(p + 'd')
        a.btst(0, D(0)); a.br('eq', p + 'u'); a.subq('w', 2, D(3)); a.label(p + 'u')
        for reg, hi in [(2, 304), (3, 208)]:
            a.tst('w', D(reg)); a.br('pl', f'{p}lo{reg}'); a.moveq(0, reg); a.label(f'{p}lo{reg}')
            a.cmpi('w', hi, D(reg)); a.br('le', f'{p}hi{reg}'); a.move('w', imm(hi), D(reg)); a.label(f'{p}hi{reg}')
        a.move('w', D(2), absl(x)); a.move('w', D(3), absl(y))
    # Start (player 1) rising edge: counter++ and save
    a.move('w', absl(PAD1), D(0)); a.andi('w', 0x80, D(0))
    a.move('w', absl(PREVST), D(1)); a.move('w', D(0), absl(PREVST))
    a.tst('w', D(0)); a.br('eq', 'no_start'); a.tst('w', D(1)); a.br('ne', 'no_start')
    a.move('w', absl(CNT), D(0)); a.addq('w', 1, D(0)); a.cmpi('w', BAR_CELLS, D(0)); a.br('ls', 'cnt_w'); a.moveq(0, 0)
    a.label('cnt_w'); a.move('w', D(0), absl(CNT)); a.move('b', D(0), absl(SRAM_COUNT))
    a.br('sr', 'draw_bar')
    a.label('no_start')
    a.br('sr', 'update_sprites')
    a.br('ra', 'main')

    # --- read_pad: a0 = data port. Returns d0 = active-high button word (see PRIORITY comment). Uses d1, d2.
    a.label('read_pad')
    a.move('b', imm(0x40), ind(0)); a.nop(); a.nop()
    a.move('b', ind(0), D(0)); a.andi('w', 0x3F, D(0))              # TH=1: C B R L D U
    a.move('b', imm(0x00), ind(0)); a.nop(); a.nop()
    a.move('b', ind(0), D(1)); a.andi('w', 0x30, D(1)); a.lsl('w', 2, D(1)); a.or_('w', D(1), 0)   # TH=0: Start A
    for th in (0x40, 0x00, 0x40, 0x00):
        a.move('b', imm(th), ind(0)); a.nop(); a.nop()
    a.move('b', ind(0), D(1))                                         # 3rd TH=0: low nibble 0 on a 6-button pad
    a.move('b', imm(0x40), ind(0)); a.nop(); a.nop()
    a.move('b', ind(0), D(2))                                         # 4th TH=1: Mode X Y Z
    a.andi('b', 0x0F, D(1)); a.br('ne', 'pad3')
    a.andi('w', 0x0F, D(2)); a.lsl('w', 8, D(2)); a.or_('w', D(2), 0)
    a.not_('w', D(0)); a.andi('w', 0x0FFF, D(0)); a.ori('w', 0x8000, D(0)); a.rts()
    a.label('pad3')
    a.ori('w', 0x0F00, D(0)); a.not_('w', D(0)); a.andi('w', 0x0FFF, D(0)); a.rts()

    # --- draw_bar: plane A row BAR_ROW, cells 0..19 = white if index < counter
    a.label('draw_bar')
    a.lea(absl(VDP_DATA), 0)
    a.move('l', imm(vram_w(PLANE_A + BAR_ROW * 128)), absl(VDP_CTRL))
    a.move('w', absl(CNT), D(1)); a.moveq(BAR_CELLS - 1, 2); a.moveq(0, 3)
    a.label('bar_loop'); a.moveq(0, 4); a.cmp('w', D(1), 3); a.br('ge', 'bar_put'); a.moveq(TILE_WHITE, 4)
    a.label('bar_put'); a.move('w', D(4), ind(0)); a.addq('w', 1, D(3)); a.dbra(2, 'bar_loop')
    a.rts()

    # --- update_sprites: sprite 0 = white square (link 1), sprite 1 = black square (end)
    a.label('update_sprites')
    a.lea(absl(VDP_DATA), 0)
    a.move('l', imm(vram_w(SPRITES)), absl(VDP_CTRL))
    for y, x, link, tile in [(SY, SX, 1, TILE_WHITE), (S2Y, S2X, 0, TILE_BLACK)]:
        a.move('w', absl(y), D(0)); a.add('w', imm(128), 0); a.move('w', D(0), ind(0))
        a.move('w', imm(0x0500 | link), ind(0)); a.move('w', imm(0x8000 | tile), ind(0))
        a.move('w', absl(x), D(0)); a.add('w', imm(128), 0); a.move('w', D(0), ind(0))
    a.rts()

    a.label('halt'); a.br('ra', 'halt')
    a.label('rte_'); a.rte()
    a.label('colours')
    for c1, c2 in STATES:
        a.w(c1, c2)
    return a.finish()


ROM_SIZE = 0x20000      # 128 KiB, the smallest common cartridge size


def build():
    code = bytearray(program())
    assert len(code) <= ROM_SIZE
    rom = code + b'\xFF' * (ROM_SIZE - len(code))
    csum = sum(struct.unpack('>%dH' % ((ROM_SIZE - 0x200) // 2), bytes(rom[0x200:]))) & 0xFFFF
    rom[0x18E:0x190] = struct.pack('>H', csum)
    return bytes(rom)


if __name__ == '__main__':
    here = os.path.dirname(os.path.abspath(__file__))
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(here, 'genesis-testrom', 'rb-testrom.md')
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    data = build()
    with open(out, 'wb') as f:
        f.write(data)
    print(f'wrote {out} ({len(data)} bytes)')
