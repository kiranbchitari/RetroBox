# Building and installing RetroBox TV

Status: built and run on an **Android TV emulator** (API 36, arm64). **Not yet installed on a Xiaomi Mi TV 4A**; nothing below about the physical TV has been tested.

## 1. Build

Requirements: Android SDK platform 36, NDK `27.1.12297006`, CMake `3.22.1` (all via Android Studio SDK Manager), JDK 17+ (Android Studio's bundled JBR works), git.

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # macOS; any JDK 17+ elsewhere
export ANDROID_HOME="$HOME/Library/Android/sdk"

cores/build-cores.sh            # clones pinned libretro cores, builds them with the NDK -> app/src/main/jniLibs (needs network once)
./gradlew testDebugUnitTest     # unit tests
./gradlew assembleDebug         # -> app/build/outputs/apk/debug/app-debug.apk   (package com.retrobox.tv.debug)
./gradlew assembleRelease       # -> app/build/outputs/apk/release/app-release-unsigned.apk (or signed, see below)
tools/run-device-tests.sh       # on-device tests on a connected TV/emulator (§7)
```

Lower/raise the minimum Android API with `-Pretrobox.minSdk=23` (default 21) once you know the TV's API level.

## 2. Signing a release APK

One-time: create a key and `keystore.properties` (both git-ignored; the script asks for a password):

```bash
tools/make-release-key.sh
```

Or write `keystore.properties` yourself from `keystore.properties.example` (`storeFile` is relative to the properties file). A properties file somewhere else works too: `./gradlew assembleRelease -Pretrobox.keystoreProperties=/path/to/keystore.properties` (or the `RETROBOX_KEYSTORE_PROPERTIES` environment variable).

`./gradlew assembleRelease` then produces a signed `app/build/outputs/apk/release/app-release.apk` (R8-minified, about 5 MB). Without a properties file you get `app-release-unsigned.apk`; sign it by hand:

```bash
$ANDROID_HOME/build-tools/36.0.0/apksigner sign --ks retrobox-release.jks --out RetroBox-release.apk app/build/outputs/apk/release/app-release-unsigned.apk
```

**Back up `retrobox-release.jks` and the password.** Every update must be signed with the same key; otherwise Android refuses it, and the only way out is uninstalling, which deletes saves. The debug build is signed automatically with the debug key and is fine for personal use.

**A build you give to anyone else:** Snes9x 2005 (SNES) is licensed for non-commercial use only, and its terms conflict with the GPL. Leave it out with `./gradlew assembleRelease -Pretrobox.excludeCores=snes9x2005`, or at least never sell or bundle the APK commercially. SNES games then show "core not installed in this build". GPL also means you must offer the source (this repo plus the pinned core commits in `cores/build-cores.sh`) to anyone you give the APK to. See THIRD_PARTY_NOTICES.md. This is not legal advice.

## 3. Install over ADB (recommended)

1. TV: Settings → Device Preferences → About → press **Build** 7 times. Then Developer options → enable **USB debugging** (and **Network debugging** / ADB over network if offered).
2. Find the TV's IP (Settings → Network). On your computer:

```bash
adb connect <tv-ip>:5555        # accept the authorization prompt on the TV
adb devices                     # should list the TV
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.retrobox.tv.debug/com.retrobox.tv.MainActivity     # or launch from the TV's app row
```

(The release package is `com.retrobox.tv`; the debug one is `com.retrobox.tv.debug`, so both can be installed together.)

## 4. Sideload from USB (untested)

Copy the APK to a FAT32/exFAT USB stick, plug it into the TV, open a file manager on the TV and open the APK. You may have to allow "Install unknown apps" for that file manager (Settings → Device Preferences → Security & restrictions). Whether the Mi TV's stock file manager does this, I have not verified.

## 5. Putting ROMs on the TV

The simplest place is the app's own folder, which it creates at first launch (folders on a USB drive can be added from **Library → Folders** where the TV has a folder picker; see below):

```
/sdcard/Android/data/com.retrobox.tv/files/roms/           (debug build: com.retrobox.tv.debug)
```

```bash
adb shell am start -n com.retrobox.tv/.MainActivity        # launch once so the app creates the folder
adb push my-game.nes /sdcard/Android/data/com.retrobox.tv/files/roms/
```

Launch the app **before** pushing: a folder first created by `adb push` is owned by the shell user and the app cannot list it. Android 11+ also hides `Android/data` from most on-device file managers, so copying from a USB stick on the TV itself only works through **Folders → Add folder…** (if the TV has a picker).
Supported file extensions: NES `.nes .fds .unf .unif`, SNES `.sfc .smc .swc .fig`, Game Boy `.gb`, Game Boy Color `.gbc`, GBA `.gba`. `.zip` files work too: the first supported ROM inside a zip is the game, and the library shows it under that ROM's name. It is unpacked to the app's cache when you start it, and saves are named after the ROM, so they keep working if you unzip it later. RetroBox Originals cartridges are `.rbo` (the app writes `Snake.rbo` into `roms/RetroBox Originals/` itself the first time it starts; see cores/originals/README.md). Other archive formats (`.7z`, `.rar`) are not supported. No BIOS or firmware file is needed for any of these systems (GBA: gpSP has a built-in BIOS and mGBA emulates it). For GBA the first card in the ROM list switches between **gpSP** (default, fastest on the TV's CPU) and **mGBA** (more accurate); cartridge saves are shared between them, save states are not.

Supply only ROMs you legally own. No ROMs, BIOS files or artwork are included. Tiny original test ROMs (CC0) can be generated to check the plumbing without any commercial game:

```bash
python3 tools/make_test_rom.py out.nes                 # NES
python3 tools/make_test_roms.py out-dir/               # rb-testrom.gb / .gbc / .sfc
tools/gba-testrom/build.sh out-dir/rb-testrom.gba      # GBA (needs the Android NDK that cores/build-cores.sh uses)
```

Keyboard (USB/Bluetooth) defaults while playing, as in RetroArch: arrows = D-pad, Z = B, X = A, A = Y, S = X, Q = L, W = R, Enter = Start, Right-Shift = Select. A remote's OK button acts as A; Back opens the pause menu.

### Library, folders, artwork and controls (Milestone 5)

* On start the app scans its ROM folder and any folders you added (**Folders → Add folder…** uses Android's folder picker; many TV firmwares have none, then just copy ROMs into the app folder). **Library** lists everything: Search, Sort, Favorites-only and System cards, and three views (the three buttons at the top right: small grid, list, and big cards with a full-size cover; the one you pick is remembered, for every system, until you pick another); hold OK on a game for favourites, per-game controls and **Delete game…** (see below).
* Pictures: the game's own last frame is saved when you pause or quit. To use your own cover art put `<rom file name without extension>.png` (or `.jpg`) in `/sdcard/Android/data/<pkg>/files/artwork/<system>/` (`nes`, `snes`, `gb`, `gbc`, `gba`); it wins over the saved frame.
* **Deleting a game:** hold OK on a game → **Delete game…** → a confirmation with focus on **No, keep it** (Back cancels too). **Yes, delete game** removes the ROM file from the app's own ROM folder (`/sdcard/Android/data/<pkg>/files/roms`, or the private `files/roms`), the game's cover art in `artwork/<system>/` (`.png`/`.jpg`/`.jpeg` named after the ROM) and its saved picture, takes it out of the library and Recently played, and puts focus on the neighbouring tile. The middle row, **Save states + saves: Keep / Delete too** (Left/Right/OK switches it), decides about the save-state slots, the Quick Resume session and the cartridge save; they are **kept by default**, so the game's progress is still there if you copy the ROM back. For a `.zip` game the whole zip file is deleted. Games that come from a folder added with **Folders → Add folder…** are never deleted: the dialog only says it can't be done from here (the app has read access there only), so remove those files with a file manager or from the computer. If another game has the same file name (same system, e.g. in two sub-folders), its shared cover art and saves are left alone and the status line says so; if a picture or save file cannot be removed, the status line names it. The game's custom controls (Controls for this Game) are kept. Verified only on the Android TV emulator (see TEST_MATRIX.md); **not tried on the Mi TV**.
* **Controllers → Controls: …** rebinds buttons globally, per system, or per game (select a button, press the key). *Export/Import controls* use `/sdcard/Android/data/<pkg>/files/profiles/retrobox-profiles.json` (`adb pull` / `adb push` it).

## 6. Pairing the Cosmic Byte Stratos Xenon

I have not tested this controller. General procedure: put the controller in Bluetooth pairing mode as described in its manual (many pads have a mode switch/combination; I do not know this model's), then on the TV go to Settings → Remotes & Accessories → Add accessory. Open RetroBox TV → **Controllers**: the pad should appear with its vendor/product id, and pressing buttons lights them up in the live monitor. If the d-pad or sticks show different axes than expected, that screen tells us what Android reports.

## 7. Checking a TV with the device tests

Before playing on a new device, run the on-device tests with the TV connected over ADB (`adb connect <tv-ip>:5555` first):

```bash
tools/run-device-tests.sh
```

They install the debug app plus a test APK (`com.retrobox.tv.debug.test`), then run 34 tests in about a minute on the emulator; a slow TV will take longer. Covered: each core (FCEUmm, Snes9x 2005, Gambatte for GB and GBC, gpSP, mGBA) plays its CC0 test ROM (video, sound samples, buttons, save-state restore, cartridge-save reload); every graphics Look is built and drawn on the TV's own GPU; the library scanner with the real SQLite store; D-pad navigation on Home and Settings and Back through the app; the license texts are packaged. One test class: `tools/run-device-tests.sh com.retrobox.tv.GlLooksDeviceTest`. GPU name and frame sizes go to `adb logcat -s RetroBoxDeviceTest`.

The script does **not** uninstall anything (unlike `./gradlew connectedDebugAndroidTest`, which would delete the debug app's ROMs and saves). The tests use a scratch folder in the app's cache and their own database. One test opens the real Home screen and only moves focus. The test APK can be removed afterwards with `adb uninstall com.retrobox.tv.debug.test`.

## 8. Troubleshooting

See [TROUBLESHOOTING.md](TROUBLESHOOTING.md).
