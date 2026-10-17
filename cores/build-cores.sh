#!/usr/bin/env bash
# Builds libretro cores from pinned upstream commits with the Android NDK and installs them as
# app/src/main/jniLibs/<abi>/lib<core>_libretro_android.so (Android only allows dlopen from the
# APK-extracted native library dir, so cores must be named lib*.so).
#
# Usage: cores/build-cores.sh [core ...]     (default: all cores listed in CORES)
# Needs: ANDROID_HOME (or ~/Library/Android/sdk, ~/Android/Sdk), NDK 27.1.12297006, CMake 3.22.1, git.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -z "$SDK" ] && for d in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do [ -d "$d" ] && SDK="$d"; done
NDK="${ANDROID_NDK_HOME:-$SDK/ndk/27.1.12297006}"
CMAKE_BIN="$SDK/cmake/3.22.1/bin"
HOST_TAG="$(ls "$NDK/toolchains/llvm/prebuilt")"
STRIP="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin/llvm-strip"
ABIS="armeabi-v7a arm64-v8a"
API=21
JNILIBS="$HERE/../app/src/main/jniLibs"
SRC="$HERE/src"; OUT="$HERE/out"
JOBS="$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)"

# name | repo | pinned commit
CORES_TABLE='
fceumm|https://github.com/libretro/libretro-fceumm.git|ac0d00a7fc01166f84c7bac01d1084ebfd2b865e
snes9x2005|https://github.com/libretro/snes9x2005.git|a79dfe9047e7fec58808aefe48ad2bf499c7af11
gambatte|https://github.com/libretro/gambatte-libretro.git|d9d6cd06382d1ced30de34d56d3609452323dab1
gpsp|https://github.com/libretro/gpsp.git|5819380c2ffb0900219d700a382ee68c464ebb99
mgba|https://github.com/libretro/mgba.git|7a12d6d4b9acb14c0ae62c9166b6a2f3d08007f6
genesis_plus_gx|https://github.com/libretro/Genesis-Plus-GX.git|aa74d79634cd230f65cce4180e92688e53883d2f
picodrive|https://github.com/libretro/picodrive.git|1890c2932234c9d30f4cd3851d02228baae8f09e
'
license_for() { case "$1" in fceumm) echo Copying;; gambatte|gpsp) echo COPYING;; mgba) echo LICENSE;; snes9x2005) echo copyright;;
  genesis_plus_gx) echo LICENSE.txt;;  # covers the bundled components (Nuked OPN2, tremor, libchdr, ...) too
  picodrive) echo COPYING pico/cd/libchdr/LICENSE.txt pico/sound/emu2413/LICENSE platform/common/dr_libs/LICENSE platform/common/tremor/COPYING;;
esac; }

# Each core's license text, with its source and commit, goes into the APK (assets/licenses/, see app/build.gradle.kts).
install_license() { # name repo sha
  local dir="$SRC/$(dirname_for "$1")"
  mkdir -p "$OUT/licenses"
  { echo "$1 — source: $2 (commit $3)"; local first=1 f
    for f in $(license_for "$1"); do [ $first = 1 ] || printf '\n\n==== %s ====\n' "$f"; first=0; echo; cat "$dir/$f"; done
  } > "$OUT/licenses/core-$1.txt"
}

dirname_for() { case "$1" in gambatte) echo gambatte-libretro;; fceumm) echo libretro-fceumm;; genesis_plus_gx) echo Genesis-Plus-GX;; *) echo "$1";; esac; }

fetch() { # name repo sha
  local dir="$SRC/$(dirname_for "$1")"
  if [ ! -d "$dir/.git" ]; then mkdir -p "$dir"; git -C "$dir" init -q; git -C "$dir" remote add origin "$2"; fi
  if [ "$(git -C "$dir" rev-parse HEAD 2>/dev/null || true)" != "$3" ]; then
    git -C "$dir" fetch -q --depth 1 origin "$3"; git -C "$dir" checkout -q FETCH_HEAD
  fi
  # picodrive keeps libchdr, emu2413, dr_libs (and the unused cyclone/libpicofe) in submodules pinned by its commit.
  [ -f "$dir/.gitmodules" ] && git -C "$dir" submodule update -q --init --depth 1
  return 0
}

install_so() { # core abi file
  mkdir -p "$JNILIBS/$2"
  cp "$3" "$JNILIBS/$2/lib$1_libretro_android.so"
  "$STRIP" --strip-unneeded "$JNILIBS/$2/lib$1_libretro_android.so"
}

ndk_build_jni() { # core srcdir-with-jni  (cores that ship their own jni/Android.mk)
  ( cd "$2/jni" && "$NDK/ndk-build" -j"$JOBS" APP_ABI="$ABIS" APP_PLATFORM=android-$API APP_SUPPORT_FLEXIBLE_PAGE_SIZES=true >/dev/null )
  for abi in $ABIS; do install_so "$1" "$abi" "$2/libs/$abi/libretro.so"; done
}

build_core() {
  local name="$1" dir="$SRC/$(dirname_for "$1")"
  echo "==> $name @ $(git -C "$dir" rev-parse --short HEAD)"
  case "$name" in
    fceumm|snes9x2005|gpsp) ndk_build_jni "$name" "$dir" ;;
    genesis_plus_gx|picodrive)
      # Upstream jni/Android.mk (APP_ABI := all) through a wrapper Application.mk that pins our ABIs/API/page size.
      # picodrive uses its portable C CPU cores (FAME 68k, CZ80) on both ABIs, as upstream's Android.mk does;
      # the ARM-asm Cyclone/DrZ80 path is not enabled (untestable here, see cores/GENESIS_HANDOVER.md).
      local jni="$dir/jni"; [ "$name" = genesis_plus_gx ] && jni="$dir/libretro/jni"
      ( cd "$jni" && "$NDK/ndk-build" -j"$JOBS" NDK_PROJECT_PATH="$OUT/$name" NDK_OUT="$OUT/$name/obj" NDK_LIBS_OUT="$OUT/$name/libs" \
          APP_BUILD_SCRIPT="$jni/Android.mk" NDK_APPLICATION_MK="$HERE/mk/genesis/Application.mk" >/dev/null )
      for abi in $ABIS; do install_so "$name" "$abi" "$OUT/$name/libs/$abi/libretro.so"; done ;;
    gambatte)
      "$NDK/ndk-build" -j"$JOBS" NDK_PROJECT_PATH="$OUT/gambatte" NDK_OUT="$OUT/gambatte/obj" NDK_LIBS_OUT="$OUT/gambatte/libs" \
        APP_BUILD_SCRIPT="$HERE/mk/gambatte/Android.mk" NDK_APPLICATION_MK="$HERE/mk/gambatte/Application.mk" GAMBATTE_SRC="$dir" >/dev/null
      for abi in $ABIS; do install_so gambatte "$abi" "$OUT/gambatte/libs/$abi/libretro.so"; done ;;
    mgba)
      for abi in $ABIS; do
        local b="$OUT/mgba/build-$abi"
        "$CMAKE_BIN/cmake" -S "$dir" -B "$b" -G Ninja -DCMAKE_MAKE_PROGRAM="$CMAKE_BIN/ninja" \
          -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-$API \
          -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON -DCMAKE_BUILD_TYPE=Release -DCMAKE_C_FLAGS="-DIOAPI_NO_64" \
          -DBUILD_LIBRETRO=ON -DSKIP_LIBRARY=ON -DBUILD_QT=OFF -DBUILD_SDL=OFF -DBUILD_GL=OFF -DBUILD_GLES2=OFF -DBUILD_GLES3=OFF \
          -DUSE_EPOXY=OFF -DBUILD_TEST=OFF -DBUILD_EXAMPLE=OFF -DUSE_FFMPEG=OFF -DUSE_LIBZIP=OFF -DUSE_MINIZIP=OFF -DUSE_ZLIB=ON \
          -DUSE_PNG=OFF -DUSE_LZMA=OFF -DUSE_SQLITE3=OFF -DUSE_DISCORD_RPC=OFF -DUSE_EDEV=OFF -DENABLE_SCRIPTING=OFF \
          -DBUILD_SHARED=OFF -DBUILD_STATIC=OFF >/dev/null
        "$CMAKE_BIN/cmake" --build "$b" -j"$JOBS" >/dev/null
        install_so mgba "$abi" "$b/mgba_libretro.so"
      done ;;
    *) echo "unknown core $name" >&2; exit 1 ;;
  esac
}

WANT="${*:-fceumm snes9x2005 gambatte gpsp mgba}"
echo "$CORES_TABLE" | while IFS='|' read -r name repo sha; do
  [ -z "$name" ] && continue
  case " $WANT " in *" $name "*) fetch "$name" "$repo" "$sha"; build_core "$name"; install_license "$name" "$repo" "$sha";; esac
done
echo "Done. Cores installed under $JNILIBS"
