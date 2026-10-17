# RetroBox TV

An Android TV retro-gaming frontend that runs libretro emulator cores (it contains no home-made emulators). Target: Xiaomi Mi TV 4A with a Bluetooth gamepad. Systems: NES, SNES, Game Boy, Game Boy Color, Game Boy Advance (two cores) and Sega Genesis / Mega Drive (two cores). `.zip` ROMs, fast-forward and turbo buttons are supported.

* `INSTALL.md`: build, sign, install (ADB / USB), ROM folders, controller pairing, on-device tests
* `TROUBLESHOOTING.md`: symptoms and what to try
* `TEST_MATRIX.md`: what has actually been verified, and what has not
* `ARCHITECTURE.md`: device facts, integration options compared, design, risks
* `IMPLEMENTATION_PLAN.md`: milestones and status
* `THIRD_PARTY_NOTICES.md`, `LICENSE`, `licenses/`: licenses (also in the app under Settings → About & licenses)

```bash
cores/build-cores.sh && ./gradlew assembleDebug     # details in INSTALL.md
```

## Current state

Milestones 1–7 are built, plus zip ROMs, fast-forward/turbo and Genesis. **Everything was verified on an Android TV emulator (API 36, arm64) only.** Nothing has run on the Mi TV, no physical controller was used, and no one has heard the sound. The SNES, Game Boy, GBC, GBA and Genesis cores have played only this repo's original test ROMs, not real games (NES and SNES also ran user-supplied games). Automated checks: 188 JVM unit tests, a native layout test, and 30 on-device tests (`tools/run-device-tests.sh`), all passing on the emulator.

## Not implemented

* `.7z` and other archive formats (`.zip` is supported); PS1, N64, DS (optional systems, not started).
* Rewind, cheats, netplay, achievements, frame-skip and run-ahead.
* Online cover art or metadata (deliberately none). Pictures are the game's last frame or a cover you supply.
* Per-game graphics settings, custom shader files, and a safe-area test pattern.
* Stick/axis remapping, per-controller-model layouts, and remembering which physical pad is Player 1 between sessions. A keyboard cannot be Player 2. There is no Game Boy link cable (GB/GBC/GBA are one player).
* Screens to manage play history and favourites, beyond toggling a favourite.
* Backup or export of saves. Save files live in the app's private storage, which an uninstall deletes, and a release build cannot be read over ADB.
* Game Boy RTC (real-time clock) persistence, Super Game Boy borders, per-core save-RAM keys, and artwork next to ROMs in picker-added folders.
* In-app updates.

## Built but not verified

* Anything on the Mi TV 4A: installing, speed, the Mali-450 shaders (it has no fragment `highp`), the 32-bit (armeabi-v7a) cores and gpSP's ARM dynarec.
* Any real gamepad, including the Cosmic Byte Stratos Xenon. Two-player was tested only with two virtual gamepads, and SNES port 2 never ran.
* Audible sound. Samples reach Android's audio output; nobody has listened.
* The system folder picker, unplugging a USB drive, and a controller disconnecting, all on a real device.
* Real SNES, Game Boy, Game Boy Color, GBA and Genesis games; Genesis 6-button pads in the app (only checked in a desktop harness); `.smd` files.
* Fast-forward and turbo with a real controller (R2 / trigger) — tested with injected key events and the pause menu only.
* Sideloading from a USB stick on the TV.
