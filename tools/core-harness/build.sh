#!/usr/bin/env bash
# Builds the host-side libretro test harness (macOS or Linux): tools/core-harness/core-harness
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cc -O2 -Wall -Wextra -o "$HERE/core-harness" "$HERE/core-harness.c" -I"$HERE/../../app/src/main/cpp/third_party/libretro" -lz -ldl
echo "built $HERE/core-harness"
