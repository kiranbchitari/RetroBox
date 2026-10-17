#!/usr/bin/env bash
# Builds rb-testrom.gba from original sources with the Android NDK's clang/lld (no devkitARM needed).
# Usage: tools/gba-testrom/build.sh [output.gba]
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
NDK="${ANDROID_NDK_HOME:-$SDK/ndk/27.1.12297006}"
BIN="$NDK/toolchains/llvm/prebuilt/$(ls "$NDK/toolchains/llvm/prebuilt")/bin"
OUT="${1:-rb-testrom.gba}"
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
CF="--target=armv4t-none-eabi -marm -mcpu=arm7tdmi -O2 -ffreestanding -fno-builtin -fno-pic -nostdlib -mfloat-abi=soft"
"$BIN/clang" $CF -c "$HERE/crt0.S" -o "$W/crt0.o"
"$BIN/clang" $CF -c "$HERE/main.c" -o "$W/main.o"
"$BIN/ld.lld" -T "$HERE/gba.ld" -o "$W/rb.elf" "$W/crt0.o" "$W/main.o"
"$BIN/llvm-objcopy" -O binary "$W/rb.elf" "$W/rb.bin"
python3 - "$W/rb.bin" "$OUT" <<'PY'
import sys
rom = bytearray(open(sys.argv[1], 'rb').read())
assert rom[3] == 0xEA, 'entry instruction is not an ARM branch'
rom[0xA0:0xAC] = b'RBTEST'.ljust(12, b'\0')
rom[0xAC:0xB0] = b'RBTE'
rom[0xB0:0xB2] = b'00'
rom[0xB2] = 0x96
chk = 0
for b in rom[0xA0:0xBD]:
    chk = (chk - b) & 0xFF
rom[0xBD] = (chk - 0x19) & 0xFF
rom += bytes(-len(rom) % 0x8000)
open(sys.argv[2], 'wb').write(rom)
print(f'wrote {sys.argv[2]}: {len(rom)} bytes')
PY
