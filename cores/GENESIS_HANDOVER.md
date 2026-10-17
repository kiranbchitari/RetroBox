# Sega Genesis / Mega Drive cores — handover for app integration

Status in one line: **both cores are built for armeabi-v7a and arm64-v8a and installed in `app/src/main/jniLibs`; the test ROM
was verified on macOS (arm64) host builds of the same commits through a headless harness (34/34 checks on each core).
Nothing ran on Android or on an ARM32 CPU. The app does not use these cores yet.**

## Files

| What | Where |
|---|---|
| Android cores | `app/src/main/jniLibs/{armeabi-v7a,arm64-v8a}/libgenesis_plus_gx_libretro_android.so`, `libpicodrive_libretro_android.so` |
| Licenses (go into the APK like the others) | `cores/out/licenses/core-genesis_plus_gx.txt`, `cores/out/licenses/core-picodrive.txt` |
| Build | `cores/build-cores.sh genesis_plus_gx picodrive` (+ `cores/mk/genesis/Application.mk`) |
| Test ROM (CC0) | `tools/genesis-testrom/rb-testrom.md` (128 KiB, sha256 `3ced527a…4156bf49`), generator `tools/make_genesis_test_rom.py` (reproducible, byte-identical output) |
| Host harness | `tools/core-harness/core-harness.c` (+ `build.sh`); checks: `tools/genesis-testrom/verify.py <host core>` |

To use the ROM in instrumented tests, copy it to `app/src/androidTest/assets/testroms/rb-testrom.md` (I did not touch `app/`).

## Cores

| | Genesis Plus GX (primary) | PicoDrive (light alternative) |
|---|---|---|
| core id / .so | `genesis_plus_gx` → `libgenesis_plus_gx_libretro_android.so` | `picodrive` → `libpicodrive_libretro_android.so` |
| upstream, pinned commit | github.com/libretro/Genesis-Plus-GX `aa74d79634cd230f65cce4180e92688e53883d2f` (2026-10-16) | github.com/libretro/picodrive `1890c2932234c9d30f4cd3851d02228baae8f09e` (2026-09-26) + its 5 submodules at the commits it pins |
| `library_name` / version | `Genesis Plus GX` / `v1.7.4` | `PicoDrive` / `2.05-1890c29` |
| `valid_extensions` | `m3u mdx md smd gen bin cue iso chd bms sms gg sg 68k sgd` | `bin gen smd md 32x cue iso chd m3u sms gg sg sc 68k sgd pco vgm vgz` |
| `need_fullpath` | **true** | **true** |
| .so size armeabi-v7a / arm64-v8a | 6,046,044 / 11,116,312 bytes | 1,682,872 / 1,619,048 bytes |
| pixel format | RGB565 | RGB565 |
| frame size (seen) | 320x224 (H40, NTSC); first frame after load is 256x192 (VDP not set up yet), then `SET_GEOMETRY` 320x224 | 320x224 from the first frame |
| frame size (other) | H32 games 256x224; PAL V30 320x240; interlace double-field option 320x448 | H32 256x224; PAL V30 320x240 |
| base / max geometry from `get_system_av_info` | before the first frame: base 256x192, max 348x240 (NTSC) / 348x288 (PAL); max is 320+28 border | base 320x240 = max 320x240 |
| aspect (default options) | `SET_GEOMETRY` aspect 1.3061 for 320x224 ("auto" = NTSC pixel aspect) | 1.4286 for 320x224 (`picodrive_aspect` = "PAR") — use 4:3 in the app if it wants consistency |
| fps NTSC / PAL | 59.9227 / 49.7015 (exact) | 60 / 50 (rounded integers; audio 735 / 882 frames per video frame) |
| audio rate | 44100 Hz (fixed) | 44100 Hz default (`picodrive_sound_rate`: 44100, 16000, 22050, 32000, native) |
| BIOS | none for Genesis (TMSS boot ROM optional, `genesis_plus_gx_bios` = disabled). Mega CD needs `bios_CD_U/E/J.bin` in the system dir | none for Genesis; Mega CD needs BIOS |
| save state size (test ROM) | 1,036,288 bytes | 679,178 bytes |
| NEEDED libs | libc, libm, libdl | liblog, libz, libc, libm, libdl |

Both also run Master System / Game Gear / SG-1000 (`.sms .gg .sg`) and Mega CD (with BIOS); PicoDrive also 32X. Untested.

**Extensions:** `.md .gen .smd` are safe Genesis-only choices. `.bin` is ambiguous: Genesis carts use it, but so do Mega
CD track files (`.cue` + `.bin`) and many other systems (Atari 2600, …), so don't route every `.bin` to Genesis without
looking (a Genesis ROM has `SEGA` at offset 0x100; a `.bin` next to a `.cue` is a CD track). `.smd` (Super Magic Drive
interleaved dumps) is de-interleaved by both cores themselves (GPGX `loadrom.c` `deinterleave_block`, PicoDrive
`cart.c` `DecodeSmd`); not tested with a real .smd. **Zip:** neither core lists `.zip`; PicoDrive's cart loader can open a
zip by path internally, GPGX cannot. Since both need a real file path, the app's zip support should extract to a file
and pass that path.

**`.md` caution:** the test ROM is `rb-testrom.md` (the usual Mega Drive extension). Anything that scans the repo or assets
for Markdown will see it.

## Licenses (read from the actual files)

Both are **non-commercial, MAME-style** licenses, like snes9x2005. Exact main terms (identical wording in both, GPGX's
names "Copyright (c) 1998-2003 Charles MacDonald, Copyright (c) 2007-2026 Eke-Eke, Some portions copyright Nicola
Salmoria and the MAME team"; PicoDrive's COPYING has no copyright line, its sources name Dave, notaz, irixxxx and others
and say "licensed under the terms of MAME license"):

> Redistribution and use of this code or any derivative works are permitted provided that the following conditions are met:
> * Redistributions may not be sold, nor may they be used in a commercial product or activity.
> * Redistributions that are modified from the original source must include the complete source code, including the source code for all components used by a binary built from the modified sources. (…)
> * Redistributions must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.

So: the APK may not be sold or used commercially while it contains either core; we build unmodified upstream sources
(only our own wrapper Application.mk), so the "modified sources" clause does not trigger, but THIRD_PARTY_NOTICES should
say where the source is (commits above). The notice must ship with the app (it does via `cores/out/licenses`).

Bundled components (their texts are inside the license files): GPGX `LICENSE.txt` also covers Nuked OPN2 (LGPL-2.1+),
Tremor (BSD-3), minimp3 (CC0), libchdr (BSD-3) with lzma (public domain) / zstd (BSD) / zlib, and others (1259 lines).
For PicoDrive I concatenated `COPYING` + libchdr `LICENSE.txt` (BSD-3) + emu2413 `LICENSE` (MIT) + dr_libs `LICENSE`
(public domain / MIT-0) + Tremor `COPYING` (BSD-3). PicoDrive's `unzip/unzip.c` has no own header (covered by COPYING);
its in-tree `zlib/` is compiled only in the host build, the Android build links the system libz.

## Controls (both cores identical, from their input descriptors and source)

| RetroPad id | 0 B | 1 Y | 2 Select | 3 Start | 8 A | 9 X | 10 L | 11 R |
|---|---|---|---|---|---|---|---|---|
| Genesis | **B** | **A** | **Mode** | **Start** | **C** | **Y** | **X** | **Z** |

D-pad = D-pad. On a 4-face pad: left face (RetroPad Y) = A, bottom (B) = B, right (A) = C, so the A-B-C row is left→bottom→right;
X/Y/Z = L / top face / R. Ports 0 and 1 = players 1 and 2.

**6-button pad:**
* GPGX: the app's `retro_set_controller_port_device(port, RETRO_DEVICE_JOYPAD)` means "Joypad Auto": 6-button if the ROM
  header's I/O field contains `6`, else 3-button (2-button for Master System). To force: device `513`
  (`RETRO_DEVICE_SUBCLASS(JOYPAD, 1)`, "MD Joypad 6 Button"), or `257` for 3-button. Full list printed by the harness
  (`SET_CONTROLLER_INFO`), e.g. 4-WayPlay 1025/1281, Team Player 1537/1793, mouse 2.
* PicoDrive: `retro_set_controller_port_device` is a no-op. Use the core options `picodrive_input1` /
  `picodrive_input2` = `6 button pad` (default `3 button pad`; also `team player`, `4way play`, `None` for port 1).
* Some older games misread a 6-button pad, which is why both default to 3-button/auto. A per-game toggle is the usual fix.
* Mode, X, Y, Z do nothing on a 3-button pad (verified: the test ROM ignores them with a 3-button pad configured).

## Save RAM

| | Genesis Plus GX | PicoDrive |
|---|---|---|
| `retro_get_memory_size(SAVE_RAM)` before the first `retro_run` | 65536 (whenever SRAM is on) | the cart's SRAM size (16384 for the test ROM) |
| … after frames have run | size up to the last byte != 0xFF (4 for the test ROM: `ff a5 ff 03`); 0 if nothing written | full size, or 0 if every byte is 0 |
| unused bytes | 0xFF | 0x00 |
| SRAM on when | header `RA` at 0x1B0, a built-in game list, or **any ROM ≤ 2 MB without header info** (64 KiB at 0x200000) | header `RA` (or its game database) |
| layout | raw 0x200000-based byte addresses: byte at $200001 is offset 1 | same offsets (offset 1 / 3 for the test ROM) |

So the app's current flow (load the `.srm` after `retro_load_game`, before the first frame; save `get_memory_size` bytes
at quit) works with both: verified in the harness, which does exactly that. Consequences: GPGX writes short, variable
length `.srm` files; and if a game never wrote SRAM, the size is 0, so the app should **not overwrite an existing save
file with an empty one** when size is 0. Cross-core: a GPGX save loaded into PicoDrive and a PicoDrive save loaded into
GPGX both showed the right counter (test ROM only; other games may differ, e.g. EEPROM-save games).

## Core options worth exposing (exact keys; the app's "first value = default" matches upstream defaults)

For the weak TV CPU (untested on the TV — the relative cost is from upstream knowledge, not measured):
* PicoDrive: `picodrive_renderer` = `accurate` (default) | `good` | `fast` (fast is much cheaper, can break mid-frame
  effects); `picodrive_sound_rate` = `22050` or `32000` cuts audio work; `picodrive_frameskip` = `disabled` | `auto` |
  `manual` (+ `picodrive_frameskip_threshold`).
* GPGX: `genesis_plus_gx_frameskip` = `disabled` | `auto` | `manual` (+ `_threshold`); keep `genesis_plus_gx_ym2612` at
  `mame (ym2612)` (default; the `nuked` FM cores are far heavier); leave `genesis_plus_gx_audio_filter`,
  `genesis_plus_gx_blargg_ntsc_filter` disabled (defaults).
* Region: `genesis_plus_gx_region_detect` = `auto|ntsc-u|pal|ntsc-j`; `picodrive_region` = `Auto|Japan NTSC|Japan PAL|US|Europe`.
* Aspect: `genesis_plus_gx_aspect_ratio` = `auto|NTSC PAR|PAL PAR|4:3|Uncorrected`; `picodrive_aspect` = `PAR|4/3|CRT`.

All options: run `tools/core-harness/core-harness <core> <rom> -m -n 1` (GPGX declares 61, PicoDrive 20).

## Build notes

* `build-cores.sh` builds each core from its **unmodified upstream `jni/Android.mk`** with a wrapper
  `cores/mk/genesis/Application.mk` (ABIs armeabi-v7a + arm64-v8a, `android-21`, `APP_SUPPORT_FLEXIBLE_PAGE_SIZES`).
  Checked: 16 KiB-aligned LOAD segments, no TEXTREL, only `retro_*` exported; link used `--no-undefined` against API 21.
* `fetch()` now also runs `git submodule update --init --depth 1` when a core has `.gitmodules` (only PicoDrive does).
* `install_license` now accepts several files per core (other cores unchanged: one file each).
* PicoDrive on Android uses its portable C CPU cores (FAME 68000, CZ80) on both ABIs, as upstream's Android.mk does.
  Its ARM-assembly cores (Cyclone 68000, DrZ80, asm renderer/YM2612) are what make it fast on old ARM32 devices, but
  upstream's Android.mk leaves them off and I could not test ARM32 code here, so I did not enable them. If PicoDrive is
  too slow on the TV, that is the next thing to try (needs Cyclone's generator run on the host and a TV test).
  armeabi-v7a PicoDrive also contains the SH-2 dynarec (32X only).
* GPGX's size is upstream's: ~4 MB `.data` of tables plus 3.8 MB of relocations on arm64, ~19 MB `.bss` (static 16 MB
  cartridge buffer etc., only touched pages use RAM).
* Clean sources are kept under `cores/src/Genesis-Plus-GX` and `cores/src/picodrive`. Host builds (`make -f
  Makefile.libretro`) were done on copies outside the repo.

## What the test ROM does, and what was verified

Checkerboard (plane B) + white P1 square + black P2 square (sprites), H40 320x224. The checkerboard colour = last pressed
P1 button (sticky): grey initially, A red, B green, C blue, Start yellow, X cyan, Y magenta, Z orange, Mode purple; A
also plays a PSG tone. A white cell at the top left = 6-button pad detected. Start = counter+1 (wraps 20→0) in battery
SRAM (header `RA F8 20`, odd bytes $200001-$203FFF; magic $A5 at $200001, counter at $200003), drawn as a bar of white
cells on tile row 26. No TMSS "SEGA" register write (emulators don't need it; a TMSS console would lock up).

`verify.py` result on **macOS arm64 host builds** of both cores (same commits): 34/34 each — 320x224 frame, grey
checkerboard, squares at their start positions, audio at 44.1 kHz and silent at boot, all eight buttons give their own
colour via the RetroPad mapping above, A plays a tone, A ≠ B, 3-button pad ignores X/Y/Z/Mode and hides the marker,
D-pad moves the square (+40/+20 px), clamps at the corner, port 2 moves only the black square and its buttons don't
change the colour, Start writes SRAM (offset 1 = $A5, offset 3 = counter), the bar survives reloading the `.srm`, and one
more Start gives 3. I looked at the dumped frames (boot, every button colour, D-pad, port 2, SRAM bar) and they match.

**Not verified:** anything on Android or in the app; any ARM build (the `.so` files have never been loaded — host builds
are arm64 macOS with different compiler flags, e.g. host PicoDrive `-O3 -flto`, Android `-O2`); speed on the Mi TV's
A53 (the harness's ~2700 frames/s on the Mac says nothing about the TV); real commercial games, `.smd` files, PAL games
beyond the reported timing, Mega CD/32X/SMS/GG; audio quality by ear.
