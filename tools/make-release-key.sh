#!/usr/bin/env bash
# Creates a release signing key (retrobox-release.jks) and keystore.properties in the repo root, both git-ignored.
# Asks for one password (used for the keystore and the key). Back both files up: updates need the same key.
set -euo pipefail
cd "$(dirname "$0")/.."
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
KEYTOOL="$JAVA_HOME/bin/keytool"
[ -e retrobox-release.jks ] && { echo "retrobox-release.jks already exists; not overwriting it."; exit 1; }
[ -e keystore.properties ] && { echo "keystore.properties already exists; not overwriting it."; exit 1; }
read -r -s -p "New keystore password (6+ characters): " PW; echo
read -r -s -p "Repeat it: " PW2; echo
[ "$PW" = "$PW2" ] || { echo "Passwords differ."; exit 1; }
[ ${#PW} -ge 6 ] || { echo "Too short."; exit 1; }
export RB_PW="$PW"
"$KEYTOOL" -genkeypair -keystore retrobox-release.jks -alias retrobox -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass:env RB_PW -keypass:env RB_PW -dname "CN=RetroBox TV"
umask 077
printf 'storeFile=retrobox-release.jks\nstorePassword=%s\nkeyAlias=retrobox\nkeyPassword=%s\n' "$PW" "$PW" > keystore.properties
echo "Wrote retrobox-release.jks and keystore.properties. ./gradlew assembleRelease now signs app-release.apk."
