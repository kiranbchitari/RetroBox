# Troubleshooting RetroBox TV

Everything below was written from what the app does on an **Android TV emulator**. Nothing has been checked on the Xiaomi Mi TV 4A or with a Cosmic Byte Stratos Xenon yet, so treat TV-specific advice as a starting point, not a known fix.

## First: collect facts

| What | How |
|---|---|
| Does each emulator core work on this device at all? | `tools/run-device-tests.sh` with the TV connected over ADB (see INSTALL.md §7). Each core plays its test ROM, every graphics Look is built on the TV's GPU, and the D-pad flow is exercised. A failing line names the core or Look. |
| Device, GPU, memory, cores, recent errors | Home → **Performance**. **Save report to file** writes `Android/data/<package>/files/diagnostics/report.txt`; `adb pull /sdcard/Android/data/com.retrobox.tv/files/diagnostics/report.txt`. |
| Live speed while playing | Settings → **FPS Overlay** on (fps · emulation ms · draw ms). The pause menu's info line has the same numbers. Once a second they also go to `adb logcat -s RetroBoxPerf`. |
| Logs | `adb logcat -s RetroBox RetroBoxHost RetroBoxCore RetroBoxGL RetroBoxPerf` |
| Crash after the fact | `adb logcat -b crash -d`. An unclean exit is also noted as "Previous session ended unexpectedly" under Performance → recent errors. |

`<package>` is `com.retrobox.tv` for a release build and `com.retrobox.tv.debug` for a debug build. They are separate apps with separate games, saves and settings.

## Installing

| Symptom | What to try |
|---|---|
| `adb connect` times out | TV and computer must be on the same network. Turn on Developer options → USB debugging / Network debugging, then check the TV's IP under Settings → Network. Some firmwares only listen on port 5555 after a reboot with debugging on. |
| `adb devices` shows `unauthorized` | Accept the prompt on the TV (tick "Always allow"). If no prompt appears: `adb kill-server`, then reconnect. |
| `INSTALL_FAILED_OLDER_SDK` | The TV's Android is older than `minSdk` (21). Check with `adb shell getprop ro.build.version.sdk`. |
| `INSTALL_FAILED_NO_MATCHING_ABIS` | Should not happen, because both ARM ABIs are packaged. Report `adb shell getprop ro.product.cpu.abilist`. |
| `INSTALL_FAILED_INSUFFICIENT_STORAGE` | Free space on the TV. The APK is about 5 MB (release) or 12 MB (debug), but installing needs some extra room. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | The installed copy was signed with a different key. Either sign with the original key (keep `retrobox-release.jks` and `keystore.properties` backed up) or `adb uninstall <package>`. **Uninstalling deletes that app's saves, settings and the ROMs in its own folder.** |
| `assembleRelease` says `keystore.properties is missing: …` | The properties file is incomplete. Compare it with `keystore.properties.example`, or move it away to get an unsigned APK. |
| Only `app-release-unsigned.apk` is produced | No `keystore.properties` was found. Run `tools/make-release-key.sh`, or sign by hand with `apksigner` (INSTALL.md §2). |
| App not in the TV's launcher row | The LEANBACK launcher entry is declared. Some firmwares list sideloaded apps only under Settings → Apps; open it from there once. |

## Games and the library

| Symptom | What to try |
|---|---|
| "No games found" / a game is missing | ROMs must have a supported extension: NES `.nes .fds .unf .unif`, SNES `.sfc .smc .swc .fig`, Game Boy `.gb`, Game Boy Color `.gbc`, GBA `.gba`, or be inside a `.zip`. A zip counts only when it holds one of those; `.7z`/`.rar` are not supported. If the same game is in the folder both unzipped and zipped, it is listed once, as the unzipped file. Empty files are skipped. Library → Folders → rescan. |
| Pushed ROMs are not listed | Launch the app once *before* `adb push`. A folder created by `adb push` belongs to the shell user, and the app cannot list it. Fix: `adb shell rm -r /sdcard/Android/data/<package>/files/roms`, open the app, then push again. |
| "Add folder…" is missing or does nothing | Many TV firmwares have no system folder picker. The app hides the button when only a stub exists. Copy ROMs into the app's own folder instead. |
| A folder is marked "not available" | Its USB drive is unplugged or its permission was revoked. Its games are kept and marked missing, and they come back after a rescan with the drive plugged in. Removing the folder forgets its games and their favourite marks. |
| "… is not a SNES ROM / may be corrupt" | The header check failed: wrong extension for the system, a truncated download, or a different format. The message names the file. Bad-checksum ROM hacks are accepted on purpose. |
| "Emulator core '…' is not installed in this build" | That core was left out of this APK. Either `cores/build-cores.sh` was not run before building, or the build used `-Pretrobox.excludeCores=…` (for example, a build without Snes9x 2005). |
| "Could not unpack … from ….zip" | The zip is damaged or its ROM is larger than 64 MB unpacked. Re-download it, or unzip it on a computer and copy the ROM instead. |
| Picture is a frozen frame on Home | That is the last frame saved when you paused or quit. Put your own cover at `Android/data/<package>/files/artwork/<system>/<rom name>.png` to replace it. |

## Playing

| Symptom | What to try |
|---|---|
| Black screen or crash when a game starts | Performance → recent errors gives the cause: core missing, ROM rejected, or audio could not start. Then check `adb logcat -s RetroBox RetroBoxHost RetroBoxCore RetroBoxGL`. Run `tools/run-device-tests.sh com.retrobox.tv.CoreDeviceTest` to see whether that core works on this device at all. |
| Game runs slowly / stutters | Turn the FPS Overlay on. If **emulation ms** is close to or above 16.7 ms, the CPU is the limit: for GBA try the other core (pause menu → first card in the ROM list switches gpSP ↔ mGBA; gpSP is the lighter one). If **draw ms** is high, use the Plain preset (Look: Off). Turn the overlay off again, because measuring adds a little cost. No speed on the Mi TV has been measured yet. |
| Game suddenly runs fast / "▶▶" in the corner | Fast-forward is on: R2 (or Space) is held, or it was switched on under pause menu → Speed & Turbo. Switch it off there. If your layout uses R2 for a game button, R2 plays that button and does not fast-forward. |
| Turbo fires on its own / a button flickers | Pause menu → Speed & Turbo → Turbo: Off. Turbo is saved per system. |
| Sound crackles | Settings → Audio Buffer: raise it to 150–300 ms. The pause menu shows `audio underruns / dropped`. A rising **dropped** count means the TV's audio output is not taking samples (HDMI/ARC or Bluetooth audio); try plain HDMI audio. |
| No sound at all | Settings → Sound On and Volume above 0. Check the TV's volume and output. Sound has never been heard on any device so far: if it is silent on the TV, please report the pause-menu audio line. |
| A Look (CRT, scanlines, LCD grid, sharp bilinear) does nothing | The TV's GPU could not build it, and the plain picture is used. The pause menu Graphics page and Performance → shader problems say why. The Mali-450 has no fragment `highp`, so expect differences there. `tools/run-device-tests.sh com.retrobox.tv.GlLooksDeviceTest` checks all Looks at once. |
| Picture cropped at the edges | Settings → Safe Area (1–10 %) until all edges are visible. |
| Wrong shape (stretched / squashed) | Pause menu → Screen Aspect: Original, 4:3, 16:9, Stretch or Integer. The setting is saved for that system. |
| Save state will not load | States are per core: a gpSP state does not load in mGBA, and the reverse is also true. A damaged slot is shown as such in the slot list. Overwriting a slot keeps the previous file as `.bak` in `files/saves/`. Cartridge saves (SRAM) are shared between the two GBA cores. |
| Lost progress after a crash | Cartridge saves are written every 20 seconds while they change, and again on pause, on quit and when the app goes to the background. Quick Resume (auto state) is taken only when you quit to the menu. A crash can lose up to the last 20 seconds of cartridge saves. |

## Controllers and remote

| Symptom | What to try |
|---|---|
| Controller not detected | Re-pair under Settings → Remotes & Accessories, then open **Controllers**: pressing buttons should light them up. Some pads have a mode switch (Android / X-input / D-input); try each. |
| Buttons do the wrong thing | Controllers → Edit (all systems, one system, or one game via a long press in the Library) and rebind. The live monitor on the Controllers screen shows what Android reports for each button and stick. |
| Second controller moves Player 1, or nothing | Only NES and SNES have two players. Pause menu → **Players** shows which pad is which and **swaps** them; the first pad connected is Player 1. Keyboard and remote always play as Player 1. Player 2's buttons: Controllers → Edit → **Player 2** (Same as Player 1 / Custom). |
| Game paused with "controller disconnected" | A pad dropped its Bluetooth connection. Its buttons are released so the character does not keep running. Reconnect it and Resume. |
| Cannot open the pause menu with a pad | Back (remote, or the pad's Back button), the pad's Mode/Home button and Menu open it. So does holding Select + Start together for about 0.6 s, for pads without a Back button. |
| Remote OK does nothing in a game | OK acts as the A button. The remote has no other game buttons, so use a gamepad or keyboard to play. |

## Starting over

* Reset settings only: Settings → Reset all settings (press twice).
* Reset controls: Controllers → Edit → "Reset to system defaults", or delete `files/profiles.json` (debug build: `adb shell run-as com.retrobox.tv.debug rm files/profiles.json`).
* Everything: Android Settings → Apps → RetroBox TV → Clear data. **This deletes saves, states, settings and the ROMs in the app's own folder.** A release build cannot be read with `run-as`, so there is no ADB backup of its saves. Keep copies of your ROMs elsewhere.
