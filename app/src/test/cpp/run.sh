#!/bin/sh
# Compiles and runs the host-side native tests (no Android needed).
set -e
here="$(cd "$(dirname "$0")" && pwd)"
out="${TMPDIR:-/tmp}/retrobox-layout-test"
${CXX:-c++} -std=c++17 -Wall -Wextra -I"$here/../../main/cpp" "$here/layout_test.cpp" -o "$out"
"$out"
