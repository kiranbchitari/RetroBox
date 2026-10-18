#!/usr/bin/env bash
# Builds RetroBox Originals as a macOS/Linux shared library for tools/core-harness (headless test runs, PNG frame dumps).
# Output: cores/originals/out/retrobox_originals_libretro.dylib (or .so)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
EXT=so; [ "$(uname)" = Darwin ] && EXT=dylib
mkdir -p "$HERE/out"
cc -O2 -Wall -Wextra -fPIC -shared -std=gnu11 ${EXTRA_CFLAGS:-} -I"$HERE/../../app/src/main/cpp/third_party/libretro" \
   -o "$HERE/out/retrobox_originals_libretro.$EXT" "$HERE"/core.c "$HERE"/gfx.c "$HERE"/snd.c "$HERE"/snake.c "$HERE"/blizzard.c "$HERE"/sprite.c "$HERE"/levels.c "$HERE"/stagegen.c -lm
echo "built $HERE/out/retrobox_originals_libretro.$EXT"
