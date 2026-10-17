# RetroBox TV — Implementation Plan

Each milestone ends with: build, relevant tests, fix regressions, summary, exact commands.
Status key: ✅ done & verified · 🟡 done, partially verified · ⬜ not started.

Build environment (verified): macOS arm64, Android Studio JBR (JDK 25) as `JAVA_HOME`, Gradle 9.3.1, Android SDK platform 36, NDK 27.1.12297006, CMake 3.22.1.

| # | Milestone | Status |
|---|---|---|
| 1 | Research and architecture (`ARCHITECTURE.md`, this file, core build script) | ✅ |
| 2 | Android TV foundation: project, TV launcher/manifest, Compose home, controller detection + live input monitor, diagnostics, debug APK installed on an emulator | ✅ (emulator only; no physical controller yet) |
| 3 | First playable emulator: NES via fceumm — JNI libretro host, GLES2 video, audio, controller input, pause menu, lifecycle | 🟡 works on Android TV emulator; not tested on the Mi TV, no physical controller, audio output not audible-verified |
| 4 | SNES, GB, GBC, GBA (snes9x2005, gambatte, gpSP + mGBA): ROM validation, video geometry, audio rate, input subsets, SRAM, save states | 🟡 verified on the Android TV emulator with original test ROMs only (no real games, no 32-bit/armeabi-v7a run, no real TV, audio never heard) |
| 5 | Library and profiles: folders (app folder + system-picker folders), scanner, library DB, play history, favourites, search/sort/filter, pictures, controller profiles (global → system → game), import/export, mapping editor | 🟡 implemented and verified on the Android TV emulator; the system folder picker (SAF) path, drive-unplug handling and controller-disconnect handling are written and unit-tested but not run on a device with a real picker/pad |
| 6 | Graphics settings + presets + shaders, audio settings, polish, TV navigation, diagnostics extras | 🟡 implemented and verified on the Android TV emulator (GLES 3 translator); shaders never ran on a Mali-450, presets have no real-TV timings, audio never heard |
| 7 | Packaging: tests, release build/signing, install docs, troubleshooting, test matrix, notices | 🟡 done; on-device tests pass on the Android TV emulator but have not run on the Mi TV |

## Milestone 2 — Android TV foundation
* Gradle project (AGP 8.13.x, Kotlin 2.2.x, Compose BOM, `tv-material`), `retrobox.minSdk` property (default 21), ABI filters `armeabi-v7a`, `arm64-v8a`.
* Manifest: `android.software.leanback` (required=false so it also installs on phones/emulators), `android.hardware.touchscreen` required=false, `LEANBACK_LAUNCHER` + `LAUNCHER` intent filters, banner, no phone-only permissions.
* Screens: Home (systems + actions), Controllers (device list, live buttons/axes), Diagnostics (device, ABI, memory, cores present, recent errors).
* Local error log ring (file) used by Diagnostics.
* Verify: `./gradlew assembleDebug`, install on AVD, navigate with synthetic D-pad key events.

## Milestone 3 — First playable emulator (NES)
* `emulator` Kotlin API + native host (`libretrobox.so`): loader, env callbacks, RGB565 video double buffer, audio ring, input words.
* `GameActivity` with `GLSurfaceView` (GLES2), emulation thread, pause menu (Resume / Reset / Save state / Load state / Quit), Back = pause menu, configurable controller shortcut later.
* Default NES gameplay mapping (editable UI comes in Milestone 5).
* ROM source for this milestone: file in the app's external files `roms/` dir (`adb push`), plus a development launch intent. Test ROM: a freely distributable homebrew/test ROM (no commercial ROMs).
* Verify: ROM loads, frames render, audio device initializes, input via adb `input keyevent` / synthetic gamepad events, pause/resume and clean shutdown, unsupported ROM and core-failure paths produce user-visible errors, lifecycle (home/back/rotate-less TV) does not leak or crash.
* Not claimable at this milestone: real-TV performance or Stratos Xenon behaviour.

## Milestone 4 — Remaining Priority-1 systems (done on the emulator; see TEST_MATRIX.md for exactly what was and was not verified)
Implemented: per-system ROM header validation (SNES LoROM/HiROM/ExHiROM ± copier header with a lenient checksum-or-sane-header rule, Game Boy logo-or-header-checksum, GBA 0x96-or-complement), per-system button subsets (SNES 12 buttons, GBA L/R, GB/GBC none of the extra four), RetroArch-style keyboard defaults, GBA core choice (gpSP default, mGBA alternative, remembered per system, first card in the ROM list), hi-res SNES frame-size reporting (`last frame WxH` in the pause info line and Diagnostics), host-side input latching (a tap is never shorter than one frame), SRAM baseline (no junk saves), gambatte rebuilt with `-DVIDEO_RGB565`. No BIOS or system files are needed: gpSP carries a built-in BIOS (its source also looks for `gba_bios.bin` in the system folder; I did not try a real BIOS) and mGBA uses its HLE BIOS; gambatte and snes9x2005 need nothing.
Status flipped to VERIFIED (Systems.kt) only after a ROM was played on the emulator — in all four cases an original test ROM (`tools/make_test_roms.py`, `tools/gba-testrom/build.sh`).
Left open: playing real commercial games, armeabi-v7a/dynarec runs, audible audio, Game Boy RTC persistence and Super Game Boy borders, per-core SRAM keys, mGBA's 65536 Hz audio cost on the TV.

## Milestone 5 — Library and profiles (see TEST_MATRIX.md for what was and was not verified)
Built:
* `library/`: folders (the app's shared `Android/data/<pkg>/files/roms` always; extra folders through the system picker with persisted tree-URI grants), recursive cancellable scanner with progress (`FileWalker`, `SafWalker`), in-memory search/sort/filter (`LibraryFilter`), `LibraryService` (scan jobs on one background thread, Compose state for the UI), play history file, last-frame pictures + optional user artwork.
* **Storage deviation from the plan:** the platform SQLite (`SqlLibraryStore`, tables `folders`, `games`) instead of Room/KSP, to avoid APK size and start-up cost on a 1 GB TV; browsing is done in memory so Room's query layer was not needed. Play history stays in `history.tsv` (tested, tiny) and favourites live in the `games` table.
* SAF games are copied to `cache/rom-cache/` before a core loads them (cores need a path or memory); the picker is only offered when a real picker exists (Android TV images answer the intent with a stub activity).
* UI: Library (all games or one system) with Search / Sort / Favorites / System cards and a long-press game menu; Folders (add, rescan, cancel, remove, drive-unavailable marking); Home shows Continue playing + Favorites.
* `profile/`: layered controller profiles (global → system → game), `profiles.json`, import/export (`files/profiles/retrobox-profiles.json`), mapping editor with duplicate-binding warning (press the same key again to move it), unbound-button warning, and cancel-on-disconnect while waiting for a key.
Left open: real SAF run, controller-disconnect run, stick/axis remapping, favourites/history management screens, .zip ROMs, per-core SRAM keys, SAF sidecar artwork.

## Milestone 6 — Graphics, audio, polish (see TEST_MATRIX.md for what was and was not verified)
Built:
* `settings/`: one `settings.json` (`SettingsStore`: atomic write, tolerant read — a damaged file falls back to defaults and says so) holding global graphics defaults, per-system exceptions (only the differences are stored), audio, TV safe area and the FPS overlay. This replaces the Milestone-3 `GraphicsProfile` / SharedPreferences pair; per-system choices made before Milestone 6 are not migrated.
* **Graphics:** aspect (Original, 4:3, 16:9, Stretch, Integer), sharp/smooth pixels, five single-pass GLES2 "looks" (off, sharp bilinear, scanlines, CRT, LCD grid) all off by default, four presets (Plain, Smooth pixels, CRT, Handheld LCD). A look that fails to compile/link on a GPU is remembered, reported (pause-menu warning, Performance screen, logcat) and replaced by the plain picture — never fatal. Fragment shaders use `highp` only where `GL_FRAGMENT_PRECISION_HIGH` exists (Mali-400/450 has none).
* **Safe area:** 0–10 % margin per side applied to the game picture, the overlay and every menu screen (one backdrop is painted behind the margin so it is not a visible frame).
* **Audio:** on/off, volume (0–100 %, squared curve), buffer 50–300 ms (changed live by swapping the `AudioTrack` on the emulation thread), 30 ms fade-in after every resume so un-pausing does not click. Audio is cut while paused (the track is paused and flushed), so there is no separate "mute on pause" switch.
* **Overlay (off by default):** fps · emulation ms · draw ms top-left, inside the safe area. While it is on the renderer measures each draw with `glFinish`, so the draw time includes the GPU (and costs a little speed). The same numbers go to `adb logcat -s RetroBoxPerf` once a second.
* **Screens:** Settings (Home tile), pause menu Graphics and Audio pages (changes apply immediately, saved per system for graphics), Performance gains GL context info (renderer, `highp` yes/no), shader problems, render timing, settings in effect and **Save report to file** (`files/diagnostics/report.txt`).
* Tests: JVM suite 137 (was 111); host-side native test of the placement maths (`app/src/test/cpp/run.sh`).
Left open: any run on the Mi TV (Mali-450 shader quality/fill-rate, real preset cost), audible verification, per-game graphics overrides, custom shader files, frame-skip / run-ahead, a safe-area test pattern, instrumented/Compose UI tests.

## Two-player support (added after Milestone 6; see TEST_MATRIX.md)
* NES and SNES are two-player (`SystemAdapter.players`); the host sets both libretro ports to joypad and `setInput(port, …)` feeds each. Game Boy-family/GBA stay one-player (no link cable).
* `PlayerAssigner` / `PlayerInputs` (pure Kotlin, unit-tested): the oldest connected gamepad is Player 1, the next Player 2; a pad connecting later takes the next free slot on its first input; a third pad is ignored; keyboards and remotes always play as Player 1; with one-player systems every pad drives Player 1 as before. Pause menu → **Players** shows who is who and **swaps** them; unplugging a pad releases its buttons and pauses the game with a warning.
* **Controls for Player 2: copy or custom.** In the controls editor (all systems, one system, one game) Player 2 is either *Same as Player 1* (follows Player 1's layout at that level, live) or *Custom* (its own layer, started from a copy). The choice is per level, stored as `player2` in `profiles.json` (absent = copy; old files and exports load unchanged).
* Also: the NES test ROM now has a Player 2 square; Back cancelling a rebind no longer leaves the screen.
Left open: any real controller, real two-player games, SNES port-2 run, keyboard as Player 2, per-controller-model layouts, remembering which physical pad is Player 1 across sessions.

## After Milestone 7: zip ROMs, fast-forward/turbo, Genesis, review fixes (see TEST_MATRIX.md)
* **Zip:** `ZipRom` (keys `zip:<container>!/<entry>`), scanner peeks inside zips (directory read for local files), `RomAccess` unpacks to the one-ROM cache (64 MB cap). Saves/pictures use the inner ROM's name (zip-derived when two zips clash).
* **Fast-forward / turbo:** `EmulationSession.speed` (2×/3×/4×/Max, sound muted) via R2 / Space / analog trigger hold or the pause menu's Speed & Turbo page; per-system turbo buttons pulsed 3 on / 3 off on the emulation thread.
* **Genesis:** built here and later removed again (2026-10-17): cores, `.md .gen .smd .bin` handling, tests and test ROM are gone.
* **Whole-app review:** 18 defects fixed (list in TEST_MATRIX.md). JVM tests 188, on-device tests 30 (then, with Delete game: JVM 206, on-device 31).
Left open: `.7z`, fast-forward with a real controller, every game-screen race reproduced on a device.

## Milestone 7 — Packaging and validation (see TEST_MATRIX.md)
Built:
* **On-device tests** (`app/src/androidTest`, 22 tests, run with `tools/run-device-tests.sh`, which never uninstalls the app): every core plays its CC0 test ROM (frames, frame size, audio samples, A vs B input, exact save-state restore, cartridge-save reload; test ROMs in the test APK's assets), missing-core and garbage-ROM errors, every graphics Look built and drawn on the device GPU in an off-screen EGL pbuffer, library scanner + real SQLite store (rescan keeps favourites, unplug marks missing), D-pad navigation on Home and Settings (Compose UI test with real key events), Home → Settings → Back in the real app, and packaged license texts. Meant to be the first thing run on the Mi TV.
* **Release signing:** `keystore.properties` (or `-Pretrobox.keystoreProperties=` / `RETROBOX_KEYSTORE_PROPERTIES`), validated for missing keys; `tools/make-release-key.sh`; `keystore.properties.example`. A test-signed R8 release APK was installed on the emulator and played the NES test ROM.
* **Distributable build:** `-Pretrobox.excludeCores=snes9x2005` leaves Snes9x 2005 (non-commercial) out of the APK.
* **Licenses:** `LICENSE` (GPL-2.0), `licenses/` (Apache-2.0, libretro.h MIT); `cores/build-cores.sh` copies each core's license with repo + commit; a Gradle task packages them all into `assets/licenses/`; new **Settings → About & licenses** screen reads them (D-pad scrolling).
* Docs: `TROUBLESHOOTING.md` (new), `INSTALL.md` (signing, distributable build, device tests), `README.md` (current state, not implemented, not verified), `THIRD_PARTY_NOTICES.md`.
* Fixed while writing tests: Home lost its initial focus when the remembered tile no longer existed (Compose's `requestFocus()` now returns false instead of throwing); "1 games" wording.
Left open: running the device tests on the Mi TV; a real key for the release build (only throwaway test keys were used); version number is still 0.1.0.

## Commands (current)

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
cores/build-cores.sh                 # build all libretro cores into app/src/main/jniLibs (≈10 s after first clone)
./gradlew assembleDebug              # build the debug APK  -> app/build/outputs/apk/debug/
./gradlew testDebugUnitTest          # JVM unit tests
```
