#!/usr/bin/env python3
"""Generates original (CC0) test ROMs for RetroBox: rb-testrom.gb, rb-testrom.gbc, rb-testrom.sfc.

(The NES one is tools/make_test_rom.py, the GBA one is tools/gba-testrom/build.sh.)

All of them behave the same way so a human or a script can check video, input, saves and geometry:
  * checkerboard background + a bar of N filled cells (N = battery-save counter, 0..20)
  * Start press  -> counter++ (wraps 20 -> 0) and is written to cartridge RAM (SRAM), bar redraws
  * Game Boy / Color: D-pad moves a square sprite; the last of A / B pressed sets the colour state (A also starts a tone)
  * SNES: D-pad scrolls the background; the backdrop colour shows the LAST pressed button (sticky, so a short tap is enough):
          A red, B green, X blue, Y yellow, L cyan, R magenta, initially dark blue
          Select cycles video mode: 256x224 -> 512x224 (BG mode 5) -> 512x448 (mode 5 + interlace)
No copyrighted material is used; there is no Nintendo logo data beyond the 48 bytes the Game Boy header format requires.

Usage: make_test_roms.py [output-dir]
"""
import os
import sys


class Asm:
    """Tiny label-resolving byte assembler shared by the Game Boy and SNES programs."""

    def __init__(self, org):
        self.org, self.b, self.labels, self.fix = org, bytearray(), {}, []

    def pc(self):
        return self.org + len(self.b)

    def label(self, n):
        assert n not in self.labels, n
        self.labels[n] = self.pc()

    def emit(self, *bs):
        self.b += bytes(bs)

    def rel(self, opcode, label):        # 8-bit relative branch
        self.emit(opcode, 0)
        self.fix.append(('rel', len(self.b) - 1, label))

    def abs16(self, opcode, label, *prefix):  # opcode + 16-bit little-endian absolute address
        self.emit(opcode)
        self.fix.append(('abs', len(self.b), label))
        self.emit(0, 0)

    def data_label(self, label):          # raw 16-bit address of a label
        self.fix.append(('abs', len(self.b), label))
        self.emit(0, 0)

    def finish(self):
        for kind, pos, label in self.fix:
            t = self.labels[label]
            if kind == 'rel':
                off = t - (self.org + pos + 1)
                assert -128 <= off <= 127, f'branch to {label} out of range ({off})'
                self.b[pos] = off & 0xFF
            else:
                self.b[pos], self.b[pos + 1] = t & 0xFF, t >> 8
        return bytes(self.b)


# ------------------------------------------------------------------------------------------ Game Boy / Color

GB_LOGO = bytes.fromhex(
    'CEED6666CC0D000B03730083000C000D0008111F8889000EDCCC6EE6DDDDD999BBBB67636E0EECCCDDDC999FBBB9333E')


def gb_program():
    a = Asm(0x150)
    e = a.emit
    X, Y, PREV, CNT, ST, PSTART = 0x80, 0x81, 0x82, 0x83, 0x84, 0x85   # HRAM variables (ldh)

    a.label('start')
    e(0xF3)                                  # di
    e(0x31, 0xFE, 0xFF)                      # ld sp,$FFFE
    a.label('w1')
    e(0xF0, 0x44); e(0xFE, 144); a.rel(0x38, 'w1')   # wait for vblank
    e(0xAF); e(0xE0, 0x40)                   # LCD off
    # tiles 0..2 -> $8000
    e(0x21, 0x00, 0x80)                      # ld hl,$8000
    e(0x11); a.data_label('tiles')           # ld de,tiles
    e(0x06, 48)
    a.label('lt')
    e(0x1A, 0x22, 0x13, 0x05); a.rel(0x20, 'lt')     # ld a,(de) ; ld (hl+),a ; inc de ; dec b ; jr nz
    # clear OAM ($FE00..$FE9F)
    e(0x21, 0x00, 0xFE, 0x06, 160, 0xAF)
    a.label('co')
    e(0x22, 0x05); a.rel(0x20, 'co')
    # BG map $9800..$9BFF = tile 1
    e(0x21, 0x00, 0x98, 0x06, 4, 0x0E, 0, 0x3E, 1)
    a.label('fm')
    e(0x22, 0x0D); a.rel(0x20, 'fm'); e(0x05); a.rel(0x20, 'fm')
    # cartridge RAM: magic $A5 at $A000, counter at $A001
    e(0x3E, 0x0A, 0xEA, 0x00, 0x00)          # enable RAM
    e(0xFA, 0x00, 0xA0, 0xFE, 0xA5); a.rel(0x28, 'have')
    e(0x3E, 0xA5, 0xEA, 0x00, 0xA0, 0xAF, 0xEA, 0x01, 0xA0)
    a.label('have')
    e(0xFA, 0x01, 0xA0, 0xE0, CNT)           # counter -> HRAM
    e(0xAF, 0xEA, 0x00, 0x00)                # disable RAM
    # variables
    e(0x3E, 0x50, 0xE0, X, 0x3E, 0x50, 0xE0, Y)
    e(0xAF, 0xE0, PREV, 0xE0, ST, 0xE0, PSTART)
    # sound on
    e(0x3E, 0x80, 0xE0, 0x26, 0x3E, 0x77, 0xE0, 0x24, 0x3E, 0xFF, 0xE0, 0x25)
    # DMG palettes
    e(0x3E, 0xE4, 0xE0, 0x47, 0xE0, 0x48)
    # CGB object palette 0 (ignored on DMG): colour3 = yellow
    e(0x3E, 0x80, 0xE0, 0x6A, 0x06, 8, 0x21); a.data_label('objpal')
    a.label('op')
    e(0x2A, 0xE0, 0x6B, 0x05); a.rel(0x20, 'op')
    e(0xCD); a.data_label('drawbar')
    e(0x3E, 0x93, 0xE0, 0x40)                # LCDC: on, BG on, OBJ on, tiles @ $8000
    a.label('main')
    e(0xF0, 0x44, 0xFE, 144); a.rel(0x20, 'main')    # wait for LY==144
    # ---- read pad: b = dpad (R L U D), c = buttons (A B Sel Start)
    e(0x3E, 0x20, 0xE0, 0x00, 0xF0, 0x00, 0xF0, 0x00, 0x2F, 0xE6, 0x0F, 0x47)
    e(0x3E, 0x10, 0xE0, 0x00, 0xF0, 0x00, 0xF0, 0x00, 0x2F, 0xE6, 0x0F, 0x4F)
    e(0x3E, 0x30, 0xE0, 0x00)
    # ---- colour state in e, kept in HRAM (e is reused below); sticky: the last of A/B pressed wins
    e(0xF0, ST, 0x5F)
    e(0x79, 0xE6, 0x01); a.rel(0x28, 'nA'); e(0x1E, 1); a.rel(0x18, 'gotS')
    a.label('nA')
    e(0x79, 0xE6, 0x02); a.rel(0x28, 'gotS'); e(0x1E, 2)
    a.label('gotS')
    e(0x7B, 0xE0, ST)
    e(0x16, 0)
    e(0x21); a.data_label('bgp'); e(0x19, 0x7E, 0xE0, 0x47)           # BGP by state
    # CGB BG palette 0 <- cgbpal[state*8 ..]
    e(0x7B, 0x87, 0x87, 0x87, 0x5F, 0x16, 0)
    e(0x21); a.data_label('cgbpal'); e(0x19)
    e(0x3E, 0x80, 0xE0, 0x68, 0x1E, 8)
    a.label('cp')
    e(0x2A, 0xE0, 0x69, 0x1D); a.rel(0x20, 'cp')
    # sound edge on state 1
    e(0xF0, PREV, 0xFE, 1); a.rel(0x28, 'wasA')
    e(0xF0, ST, 0xFE, 1); a.rel(0x20, 'snd_done')
    e(0xAF, 0xE0, 0x10, 0x3E, 0x80, 0xE0, 0x11, 0x3E, 0xF0, 0xE0, 0x12, 0xAF, 0xE0, 0x13, 0x3E, 0x87, 0xE0, 0x14)
    a.rel(0x18, 'snd_done')
    a.label('wasA')
    e(0xF0, ST, 0xFE, 1); a.rel(0x28, 'snd_done')
    e(0xAF, 0xE0, 0x12)                      # DAC off -> silence
    a.label('snd_done')
    e(0xF0, ST, 0xE0, PREV)
    # ---- dpad -> sprite position
    e(0xF0, X)
    e(0xCB, 0x40); a.rel(0x28, 'm1'); e(0x3C)        # bit 0,b ; inc a  (right)
    a.label('m1')
    e(0xCB, 0x48); a.rel(0x28, 'm2'); e(0x3D)        # bit 1,b ; dec a  (left)
    a.label('m2')
    e(0xE0, X, 0xF0, Y)
    e(0xCB, 0x50); a.rel(0x28, 'm3'); e(0x3D)        # bit 2,b (up)
    a.label('m3')
    e(0xCB, 0x58); a.rel(0x28, 'm4'); e(0x3C)        # bit 3,b (down)
    a.label('m4')
    e(0xE0, Y)
    e(0x21, 0x00, 0xFE, 0xF0, Y, 0x22, 0xF0, X, 0x22, 0x3E, 2, 0x22, 0xAF, 0x77)   # OAM[0]
    # ---- Start edge: counter++
    e(0x79, 0xE6, 0x08, 0x57)                # d = start bit
    e(0xF0, PSTART, 0xBA); a.rel(0x28, 'nostart')    # unchanged
    e(0x7A, 0xE0, PSTART)
    e(0xB7); a.rel(0x28, 'nostart')          # released -> nothing
    e(0xF0, CNT, 0x3C, 0xFE, 21); a.rel(0x38, 'cok'); e(0xAF)
    a.label('cok')
    e(0xE0, CNT)
    e(0x4F, 0x3E, 0x0A, 0xEA, 0x00, 0x00, 0x79, 0xEA, 0x01, 0xA0, 0xAF, 0xEA, 0x00, 0x00)   # save to SRAM
    e(0xCD); a.data_label('drawbar')
    a.label('nostart')
    a.label('wn')
    e(0xF0, 0x44, 0xFE, 144); a.rel(0x28, 'wn')
    e(0xC3); a.data_label('main')

    a.label('drawbar')                       # row 8 of the map: first CNT cells solid (tile 2), rest tile 1
    e(0xF0, CNT, 0x47, 0x21, 0x00, 0x99, 0x0E, 20)
    a.label('db')
    e(0x78, 0xB7); a.rel(0x28, 'dbe'); e(0x05, 0x3E, 2); a.rel(0x18, 'dbp')
    a.label('dbe')
    e(0x3E, 1)
    a.label('dbp')
    e(0x22, 0x0D); a.rel(0x20, 'db'); e(0xC9)

    a.label('tiles')
    # tile 0 blank, tile 1 checker (colour 1 / 0), tile 2 solid colour 3
    a.b += bytes(16)
    a.b += bytes([0xF0, 0, 0xF0, 0, 0xF0, 0, 0xF0, 0, 0x0F, 0, 0x0F, 0, 0x0F, 0, 0x0F, 0])
    a.b += bytes([0xFF] * 16)
    a.label('bgp'); a.b += bytes([0xE4, 0x1B, 0x6C])
    a.label('cgbpal')
    def c(v): return v.to_bytes(2, 'little')
    for c0 in (0x4000, 0x001F, 0x03E0):      # backdrop: dark blue / red / green
        a.b += c(c0) + c(0x6318) + c(0x4210) + c(0x7FFF)
    a.label('objpal'); a.b += c(0) + c(0) + c(0) + c(0x03FF)
    return a.finish(), a.labels


def make_gb(out, cgb):
    code, labels = gb_program()
    rom = bytearray(0x8000)
    rom[0x100:0x104] = bytes([0x00, 0xC3, 0x50, 0x01])             # nop ; jp $0150 (start is first)
    assert labels['start'] == 0x150
    rom[0x104:0x134] = GB_LOGO
    title = b'RBTEST'
    rom[0x134:0x134 + len(title)] = title
    rom[0x143] = 0xC0 if cgb else 0x00                              # CGB only / DMG
    rom[0x147] = 0x03                                               # MBC1 + RAM + battery
    rom[0x148] = 0x00                                               # 32 KiB
    rom[0x149] = 0x02                                               # 8 KiB RAM
    rom[0x14A] = 0x01
    rom[0x14B] = 0x33
    x = 0
    for i in range(0x134, 0x14D):
        x = (x - rom[i] - 1) & 0xFF
    rom[0x14D] = x
    rom[0x150:0x150 + len(code)] = code
    s = sum(rom) - rom[0x14E] - rom[0x14F]
    rom[0x14E], rom[0x14F] = (s >> 8) & 0xFF, s & 0xFF
    open(out, 'wb').write(rom)
    print(f'wrote {out}: {len(rom)} bytes, code {len(code)} bytes')


# ------------------------------------------------------------------------------------------ SNES (LoROM, emulation mode)

def snes_program():
    a = Asm(0x8000)
    e = a.emit
    # zero page: $00 X, $01 Y, $03 video mode 0..2, $04 prev Select, $10 counter, $11 tmp, $12 prev Start, $20 JOY1H, $21 JOY1L

    def sta_ppu(addr, val):
        e(0xA9, val, 0x8D, addr & 0xFF, addr >> 8)

    a.label('reset')
    e(0x78, 0xD8, 0xA2, 0xFF, 0x9A)                    # sei ; cld ; ldx #$ff ; txs
    sta_ppu(0x2100, 0x8F)                              # forced blank
    for r in (0x4200, 0x420B, 0x420C, 0x210D, 0x210D, 0x210E, 0x210E):
        sta_ppu(r, 0)
    sta_ppu(0x2105, 0x01)                              # BG mode 1
    sta_ppu(0x2107, 0x00)                              # BG1 map at VRAM word $0000
    sta_ppu(0x210B, 0x01)                              # BG1 chars at word $1000
    sta_ppu(0x212C, 0x01)                              # main screen: BG1
    sta_ppu(0x212D, 0x01)                              # sub screen: BG1 (so hi-res modes look right)
    sta_ppu(0x2115, 0x80)                              # VRAM increment on high-byte write
    sta_ppu(0x2121, 0x00)                              # CGRAM address 0
    e(0xA2, 0)
    a.label('pl'); e(0xBD); a.data_label('pal'); e(0x8D, 0x22, 0x21, 0xE8, 0xE0, 8); a.rel(0xD0, 'pl')
    sta_ppu(0x2116, 0x00); sta_ppu(0x2117, 0x10)       # VRAM word $1000
    e(0xA2, 0)
    a.label('tl'); e(0xBD); a.data_label('tiles'); e(0x8D, 0x18, 0x21, 0xE8, 0xBD); a.data_label('tiles')
    e(0x8D, 0x19, 0x21, 0xE8, 0xE0, 192); a.rel(0xD0, 'tl')
    sta_ppu(0x2116, 0x00); sta_ppu(0x2117, 0x00)       # tile map at word 0, 1024 entries = name 2
    e(0xA0, 4, 0xA2, 0)
    a.label('ml'); e(0xA9, 2, 0x8D, 0x18, 0x21, 0xA9, 0, 0x8D, 0x19, 0x21, 0xCA); a.rel(0xD0, 'ml'); e(0x88); a.rel(0xD0, 'ml')
    # SRAM at $70:0000: magic $A5, counter
    e(0xAF, 0x00, 0x00, 0x70, 0xC9, 0xA5); a.rel(0xF0, 'have')
    e(0xA9, 0xA5, 0x8F, 0x00, 0x00, 0x70, 0xA9, 0x00, 0x8F, 0x01, 0x00, 0x70)
    a.label('have')
    e(0xAF, 0x01, 0x00, 0x70, 0x85, 0x10)
    for z in (0x00, 0x01, 0x03, 0x04, 0x06, 0x12):
        e(0xA9, 0x20 if z == 0 else 0, 0x85, z)
    e(0x20); a.data_label('drawbar')
    sta_ppu(0x4200, 0x01)                              # auto joypad read on
    sta_ppu(0x2100, 0x0F)                              # display on

    a.label('main')
    a.label('w1'); e(0xAD, 0x10, 0x42); a.rel(0x10, 'w1')            # wait for vblank
    a.label('w2'); e(0xAD, 0x12, 0x42, 0x29, 0x01); a.rel(0xD0, 'w2')    # wait for auto-joypad read to finish
    e(0xAD, 0x19, 0x42, 0x85, 0x20, 0xAD, 0x18, 0x42, 0x85, 0x21)
    # ---- d-pad scrolls ($20: B Y Sel Start U D L R)
    e(0xA5, 0x20, 0x29, 0x01); a.rel(0xF0, 'r1'); e(0xE6, 0x00)
    a.label('r1'); e(0xA5, 0x20, 0x29, 0x02); a.rel(0xF0, 'r2'); e(0xC6, 0x00)
    a.label('r2'); e(0xA5, 0x20, 0x29, 0x08); a.rel(0xF0, 'r3'); e(0xC6, 0x01)
    a.label('r3'); e(0xA5, 0x20, 0x29, 0x04); a.rel(0xF0, 'r4'); e(0xE6, 0x01)
    a.label('r4')
    e(0xA5, 0x00, 0x8D, 0x0D, 0x21, 0xA9, 0, 0x8D, 0x0D, 0x21)
    e(0xA5, 0x01, 0x8D, 0x0E, 0x21, 0xA9, 0, 0x8D, 0x0E, 0x21)
    # ---- backdrop colour from the held button: x = 0..6
    e(0xA6, 0x06)                                      # ldx $06: sticky - the last pressed button's colour stays
    e(0xA5, 0x21, 0x29, 0x80); a.rel(0xF0, 'b1'); e(0xA2, 1); e(0x4C); a.data_label('got')
    a.label('b1'); e(0xA5, 0x20, 0x29, 0x80); a.rel(0xF0, 'b2'); e(0xA2, 2); e(0x4C); a.data_label('got')
    a.label('b2'); e(0xA5, 0x21, 0x29, 0x40); a.rel(0xF0, 'b3'); e(0xA2, 3); e(0x4C); a.data_label('got')
    a.label('b3'); e(0xA5, 0x20, 0x29, 0x40); a.rel(0xF0, 'b4'); e(0xA2, 4); e(0x4C); a.data_label('got')
    a.label('b4'); e(0xA5, 0x21, 0x29, 0x20); a.rel(0xF0, 'b5'); e(0xA2, 5); e(0x4C); a.data_label('got')
    a.label('b5'); e(0xA5, 0x21, 0x29, 0x10); a.rel(0xF0, 'got'); e(0xA2, 6)
    a.label('got')
    e(0x86, 0x06)                                      # stx $06
    e(0xA9, 0, 0x8D, 0x21, 0x21)                       # CGRAM address 0
    e(0x8A, 0x0A, 0xAA, 0xBD); a.data_label('coltab'); e(0x8D, 0x22, 0x21, 0xE8, 0xBD); a.data_label('coltab'); e(0x8D, 0x22, 0x21)
    # ---- Select edge: cycle video mode
    e(0xA5, 0x20, 0x29, 0x20, 0xC5, 0x04); a.rel(0xF0, 'sel_done')    # compare with previous
    e(0xA5, 0x20, 0x29, 0x20, 0x85, 0x04); a.rel(0xF0, 'sel_done')    # released -> nothing
    e(0xE6, 0x03, 0xA5, 0x03, 0xC9, 3); a.rel(0x90, 'sel_ok'); e(0xA9, 0, 0x85, 0x03)
    a.label('sel_ok')
    e(0xA6, 0x03, 0xBD); a.data_label('bgm'); e(0x8D, 0x05, 0x21, 0xBD); a.data_label('ini'); e(0x8D, 0x33, 0x21)
    a.label('sel_done')
    e(0xA5, 0x20, 0x29, 0x20, 0x85, 0x04)
    # ---- Start edge: counter++
    e(0xA5, 0x20, 0x29, 0x10, 0xC5, 0x12); a.rel(0xF0, 'start_done')
    e(0xA5, 0x20, 0x29, 0x10, 0x85, 0x12); a.rel(0xF0, 'start_done')
    e(0xE6, 0x10, 0xA5, 0x10, 0xC9, 21); a.rel(0x90, 'c_ok'); e(0xA9, 0, 0x85, 0x10)
    a.label('c_ok')
    e(0xA5, 0x10, 0x8F, 0x01, 0x00, 0x70)              # sta $700001
    e(0x20); a.data_label('drawbar')
    a.label('start_done')
    e(0xA5, 0x20, 0x29, 0x10, 0x85, 0x12)
    e(0x4C); a.data_label('main')

    a.label('drawbar')                                 # map row 8: CNT solid cells (name 4), rest name 2
    sta_ppu(0x2116, 0x00); sta_ppu(0x2117, 0x01)
    e(0xA5, 0x10, 0x85, 0x11, 0xA2, 20)
    a.label('db'); e(0xA5, 0x11); a.rel(0xF0, 'dbe'); e(0xC6, 0x11, 0xA9, 4); a.rel(0xD0, 'dbp')
    a.label('dbe'); e(0xA9, 2)
    a.label('dbp'); e(0x8D, 0x18, 0x21, 0xA9, 0, 0x8D, 0x19, 0x21, 0xCA); a.rel(0xD0, 'db'); e(0x60)

    a.label('pal')                                     # CGRAM 0..3: backdrop (replaced per frame), grey, mid grey, white
    for v in (0x2000, 0x6318, 0x4210, 0x7FFF):
        a.b += v.to_bytes(2, 'little')
    a.label('coltab')
    for v in (0x2000, 0x001F, 0x03E0, 0x7C00, 0x03FF, 0x7FE0, 0x7C1F):
        a.b += v.to_bytes(2, 'little')
    a.label('bgm'); a.b += bytes([1, 5, 5])
    a.label('ini'); a.b += bytes([0, 0, 1])
    a.label('tiles')
    # 4 bpp chars (32 bytes): 0,1 blank (name 0), 2,3 checker (name 2; mode 5 uses chars 2+3), 4,5 solid colour 3 (name 4)
    a.b += bytes(64)
    chk = bytearray()
    for row in range(8):
        chk += bytes([0xF0 if row < 4 else 0x0F, 0x00])
    chk += bytes(16)
    a.b += bytes(chk) * 2
    sol = bytes([0xFF, 0xFF] * 8) + bytes(16)
    a.b += sol * 2
    return a.finish(), a.labels


def make_snes(out):
    code, labels = snes_program()
    rom = bytearray(0x8000)
    rom[:len(code)] = code
    assert len(code) < 0x7F00, len(code)
    h = 0x7FC0
    title = b'RBTEST ROM'.ljust(21)
    rom[h:h + 21] = title
    rom[h + 0x15] = 0x20            # LoROM, slow
    rom[h + 0x16] = 0x02            # ROM + RAM + battery
    rom[h + 0x17] = 0x05            # 32 KiB
    rom[h + 0x18] = 0x03            # 8 KiB SRAM
    rom[h + 0x19] = 0x01            # USA
    rom[h + 0x1A] = 0x33
    rom[h + 0x1B] = 0x00
    reset = labels['reset']
    for v in (0x7FE4, 0x7FE6, 0x7FE8, 0x7FEA, 0x7FEC, 0x7FEE, 0x7FF4, 0x7FF6, 0x7FF8, 0x7FFA, 0x7FFE):
        rom[v:v + 2] = reset.to_bytes(2, 'little')        # every vector -> reset: the program polls and never uses interrupts
    rom[0x7FFC:0x7FFE] = reset.to_bytes(2, 'little')
    rom[h + 0x1C:h + 0x20] = bytes([0, 0, 0, 0])
    s = (sum(rom) + 0x1FE) & 0xFFFF
    rom[h + 0x1C:h + 0x1E] = (s ^ 0xFFFF).to_bytes(2, 'little')
    rom[h + 0x1E:h + 0x20] = s.to_bytes(2, 'little')
    open(out, 'wb').write(rom)
    print(f'wrote {out}: {len(rom)} bytes, code {len(code)} bytes')


def main():
    d = sys.argv[1] if len(sys.argv) > 1 else '.'
    os.makedirs(d, exist_ok=True)
    make_gb(os.path.join(d, 'rb-testrom.gb'), cgb=False)
    make_gb(os.path.join(d, 'rb-testrom.gbc'), cgb=True)
    make_snes(os.path.join(d, 'rb-testrom.sfc'))


main()
