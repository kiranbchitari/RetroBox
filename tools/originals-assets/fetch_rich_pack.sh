#!/usr/bin/env bash
# Fetches the "Nano Banana rich platformer" art pack (64 MB, too big for the repo) from the RetroBox GitHub release,
# checks its SHA-256 and unpacks it into cores/originals/assets/src/rich (git-ignored). The build scripts in
# tools/originals-sprites and tools/originals-levels read it from there; their outputs (atlas, sprites_data.h,
# levels_data.h) are committed, so only someone changing the art needs to run this.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
URL="https://github.com/kiranbchitari/RetroBox/releases/download/Snowbros/nano_banana_rich_platformer_assets.zip"
SHA=3159988e1f1f4c2840dbd57e267db90919664475adf37ff40b5e37a3864dfc9e
DEST="$ROOT/cores/originals/assets/src/rich"
if [ -f "$DEST/.sha256" ] && [ "$(cat "$DEST/.sha256")" = "$SHA" ]; then echo "already unpacked: $DEST"; exit 0; fi
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
curl -fsSL -o "$TMP/pack.zip" "$URL"
echo "$SHA  $TMP/pack.zip" | sha256sum -c -
rm -rf "$DEST"; mkdir -p "$DEST"
unzip -q "$TMP/pack.zip" -d "$TMP/x"
mv "$TMP/x/nano_banana_rich_platformer_assets/nano_banana_rich_platformer_assets/"* "$DEST/"
echo "$SHA" > "$DEST/.sha256"
echo "unpacked into $DEST"
