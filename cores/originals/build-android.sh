#!/usr/bin/env bash
# Builds RetroBox Originals for Android: app/src/main/jniLibs/<abi>/libretrobox_originals_libretro_android.so
# (called by cores/build-cores.sh; can also be run alone). Needs the NDK, see cores/build-cores.sh.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -z "$SDK" ] && for d in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do [ -d "$d" ] && SDK="$d"; done
NDK="${ANDROID_NDK_HOME:-$SDK/ndk/27.1.12297006}"
BIN="$NDK/toolchains/llvm/prebuilt/$(ls "$NDK/toolchains/llvm/prebuilt")/bin"
JNILIBS="$HERE/../../app/src/main/jniLibs"
API=21
for pair in armeabi-v7a:armv7a-linux-androideabi arm64-v8a:aarch64-linux-android; do
  abi="${pair%%:*}"; triple="${pair##*:}"
  mkdir -p "$JNILIBS/$abi"
  out="$JNILIBS/$abi/libretrobox_originals_libretro_android.so"
  "$BIN/${triple}${API}-clang" -O2 -fPIC -shared -std=gnu11 -Wall -Wextra -fvisibility=hidden \
    -I"$HERE/../../app/src/main/cpp/third_party/libretro" -Wl,-z,max-page-size=16384 -Wl,--build-id=none \
    -o "$out" "$HERE"/core.c "$HERE"/gfx.c "$HERE"/snd.c "$HERE"/snake.c -lm
  "$BIN/llvm-strip" --strip-unneeded "$out"
  echo "built $out ($(wc -c < "$out") bytes)"
done
