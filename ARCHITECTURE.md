# RetroBox TV — Architecture

Status: Milestone 1 (research + architecture). Facts below are marked **verified** (done in this
repo/environment), **reported** (from web sources, not confirmed on your hardware) or **unknown**.

## 1. Target device

| Item | Value | Confidence |
|---|---|---|
| SoC | Amlogic quad-core Cortex-A53, up to ~1.5 GHz | reported (Xiaomi India spec page, Fonearena) |
| GPU | Mali-450 MP3 (**OpenGL ES 2.0 only**) | reported |
| RAM | 1 GB (some regions/variants may differ) | reported, variant-dependent |
| Storage | 4–8 GB eMMC, shared with the OS | reported, conflicting sources |
| OS | "Android TV built in" per you; early 4A units ran MIUI TV/PatchWall on Android | **unknown** — run the checklist |
| Userspace ABI | Probably **32-bit (`armeabi-v7a`)** even though the A53 is 64-bit capable; TV firmwares are frequently 32-bit userspace | **unknown** — run the checklist |

Design consequences (all conservative on purpose):

* **GLES 2.0 renderer only.** No GLES 3 features, no float textures, `highp` not assumed in fragment shaders.
* Ship **both `armeabi-v7a` and `arm64-v8a`**. v7a is the target; arm64 lets us test on Apple-silicon emulators and on 64-bit TV firmwares.
* `minSdk` is a Gradle property (`retrobox.minSdk`, default **21**, Android 5.0). Raise it once the checklist tells us the real API level.
* Memory budget: assume ~300–400 MB usable by apps. Cores run in the same process as the UI (see §6), so the UI must stay lean.
* APK budget: all five Priority-1 cores are ~5 MB (v7a) / ~9 MB (arm64) stripped; the app should stay well under 30 MB universal.

### Diagnostic checklist (run on the TV, over ADB)

```bash
adb connect <tv-ip>:5555                      # TV: Settings > Device Preferences > About > tap Build 7x, then Developer options > USB/Network debugging
adb shell getprop ro.build.version.release    # Android version
adb shell getprop ro.build.version.sdk        # API level  -> sets retrobox.minSdk ceiling
adb shell getprop ro.product.cpu.abilist      # e.g. armeabi-v7a,armeabi  vs  arm64-v8a,armeabi-v7a,armeabi
adb shell getprop ro.product.model; adb shell getprop ro.product.device
adb shell getprop ro.hardware; adb shell getprop ro.board.platform
adb shell cat /proc/cpuinfo | head -30        # cores, ARM features (neon / vfpv4)
adb shell cat /proc/meminfo | head -5         # MemTotal / MemAvailable
adb shell dumpsys SurfaceFlinger | grep -i -E "GLES|GL_RENDERER|GL_VERSION"   # Mali-450 / ES 2.0 confirmation
adb shell df -h /data /sdcard                 # free internal storage
adb shell wm size; adb shell wm density       # panel resolution (4A 32" is 1366x768)
adb shell getconf PAGESIZE                    # 4096 expected
adb shell pm list features | grep -E "leanback|gamepad|bluetooth"   # leanback must be present for the TV launcher row
adb shell dumpsys input | grep -A4 -i "Cosmic\|Xenon\|Stratos"        # controller as Android sees it
```

The app's Diagnostics screen reports the same data from inside the TV (Milestone 2).

## 2. Emulator integration options

| Option | Verdict | Why |
|---|---|---|
| **A. libretro cores + our own thin host (JNI)** | **Chosen** | The libretro API is a small C ABI (`retro_*`), cores are independent `.so` files, so adding a system = adding a `.so` + an adapter descriptor. We control video path (GLES2, RGB565), audio, input mapping, save paths. |
| B. Embed RetroArch | Rejected | GPL-3.0, large, its own menu/input system fights our TV UI and per-system mapping requirements, harder to keep within 1 GB RAM. |
| C. LibretroDroid library (GPL-3.0, used by Lemuroid) | Rejected for now | Saves host code, but couples us to its GL/threading model and GPL-3.0; I did not verify its GLES-2 support or min API, and a Mali-450 target makes that a real risk. Still a viable fallback if our host is blocked. |
| D. Standalone emulator apps via Intents | Rejected | Not a single integrated frontend; no control over mapping/graphics/saves. |
| E. Write emulators ourselves | Excluded by requirements | — |
| F. Desktop emulator libraries copied into the APK | Rejected | Not Android-buildable as-is (the brief warns about this too). |

### Core provenance: build from source, pinned

Prebuilt Android cores exist on `buildbot.libretro.com` (nightly), but they are unpinned binaries.
Instead `cores/build-cores.sh` fetches **pinned commits** of the upstream repos and builds them with
the NDK. This gives reproducible builds, a matching-source answer for GPL, and ABI/page-size control
(`-z max-page-size=16384`, so they also load on 16 KB-page devices such as the installed emulator image).

**Verified in this environment** (NDK 27.1.12297006, macOS arm64, 2026-10-17): all of the following
compile and link for `armeabi-v7a` and `arm64-v8a` using `cores/build-cores.sh` (≈10 s):

| Core | System | Upstream / pinned commit | Build method | License (verify before redistribution) | stripped size v7a / arm64 |
|---|---|---|---|---|---|
| fceumm | NES/Famicom | libretro/libretro-fceumm `ac0d00a` | upstream `jni/Android.mk` | GPL-2.0-or-later (header in `src/fceu.c`) | 1.5 / 2.9 MB |
| snes9x2005 | SNES | libretro/snes9x2005 `a79dfe9` | upstream `jni/Android.mk` | Snes9x license (**non-commercial**) + GPLv2+ parts; GitHub reports "NOASSERTION" | 0.35 / 0.78 MB |
| gambatte | GB / GBC | libretro/gambatte-libretro `d9d6cd0` | our overlay `cores/mk/gambatte/Android.mk` (upstream has none; it must define `-DVIDEO_RGB565` like upstream's Makefile.libretro, otherwise the picture is ~10x too dark) | GPL-2.0 | 1.2 / 2.2 MB |
| mGBA | GBA (and GB) | libretro/mgba `7a12d6d` | CMake + NDK toolchain, `-DIOAPI_NO_64` workaround for bundled minizip | MPL-2.0 | 1.4 / 2.5 MB |
| gpSP | GBA (fast, ARM dynarec) | libretro/gpsp `5819380` | upstream `jni/Android.mk` | GPL-2.0 | 0.6 / 0.7 MB |

"Builds" is not "runs correctly": nothing here counts as playable until it has been loaded with a ROM
on a device (see TEST_MATRIX.md). Planned (not built yet): pcsx_rearmed (GPL-2.0), mupen64plus-next / parallel_n64, melonDS/DeSmuME.

Default core choices (revisit with measurements on the real TV):

* NES → fceumm (fast, broad mapper support). Nestopia is an alternative (more accurate, heavier).
* SNES → snes9x2005 (fastest on A53; weaker compatibility — no SuperFX/SA-1-class coprocessor support). Full `snes9x` is a heavier alternative to evaluate.
* GB/GBC → gambatte.
* GBA → gpSP by default on the 1 GB TV (dynarec), mGBA as the accuracy fallback. Both built; the choice is a per-system setting.

### License posture

* App code: **GPL-2.0** (`LICENSE`, added in Milestone 7), so it can sit next to GPLv2-only cores such as gambatte (MPL-2.0 mGBA is compatible with GPLv2). This is a working decision, not legal advice, and I did not audit every upstream file header.
* Cores are loaded with `dlopen` as separate `.so` files and each retains its own license; the APK carries `assets/licenses/` (Milestone 7): the app's license, `THIRD_PARTY_NOTICES.md`, and each core's license text with its repository and pinned commit, shown under Settings → About & licenses.
* **RetroBox Originals** (`cores/originals`, our own GPL-2.0 code, not fetched): games written for RetroBox as one libretro core, started by a tiny `.rbo` cartridge. The core renders RGB565 at 960x540, 1280x720 or 1920x1080; the app picks the size from the screen height before the game loads (`Originals.resolutionFor`) and defaults the system to plain, smoothed, original-aspect output instead of the global CRT looks. Details in `cores/originals/README.md`.
* **snes9x2005's non-commercial terms conflict with GPL redistribution.** It is fine for personal sideloading on your own TV; do not publish an APK containing it. A distributable build leaves it out with `-Pretrobox.excludeCores=snes9x2005`.
* No ROMs, BIOS, or artwork are bundled. No firmware is required for the Priority-1 systems (gpSP ships a built-in GBA BIOS, mGBA uses HLE); firmware a future core needs would be user-supplied into the app's `system` directory.

## 3. UI technology

**Jetpack Compose + `androidx.tv:tv-material` (1.0.1) for the menu/launcher UI; `GLSurfaceView` for gameplay with the pause menu as a Compose overlay.** (Compose BOM 2025.09.00 / AGP 8.13.2 / Kotlin 2.2.21 / Gradle 9.3.1; the newest Compose needs AGP 9.1+ and compileSdk 37.) UI is laid out for ~960x540 dp, which is what both a 1080p panel at xhdpi and the Mi TV's 1366x768 panel work out to.

* Compose works from API 21 and `tv-material` gives focus-aware components. The risk is RAM/startup on a 1 GB A53 TV with a Mali-450, which I cannot measure here. Mitigation: the UI talks to the rest of the app only through repository interfaces, `R8` minification/resource shrinking is on for release, images are decoded lazily at thumbnail size, and the gameplay screen does not use Compose at all. If measurements on the real TV are bad, the UI layer can be replaced without touching emulation code.
* Leanback is deprecated; we still declare the Leanback launcher category and `android.software.leanback` feature as the manifest requires.

## 4. Module layout

Adapted to the real integration — modules are created only when they hold code, not to match a list:

```
app/               Application, Compose UI (screens, navigation, focus), manifest, GameActivity, DI-free wiring
core-common/       (later) Result/logging helpers if they outgrow `app`
emulator/          Kotlin API: EmulatorCore, EmulatorCoreFactory, SystemAdapter registry (+ pure-JVM tests)
  └ native/        C++ libretro host (JNI): core loader, env callbacks, video/audio ring buffers, GLES2 renderer
cores/             build-cores.sh, Android.mk overlays, pinned sources (gitignored) -> app/src/main/jniLibs
```

Milestones 2–3 deliberately keep everything in one `app` module (packages `emulator`, `game`, `save`, `input`, `diag`, `ui`, `util`) plus `app/src/main/cpp` for the native host, to avoid empty modules. Later splits
(game-library with Room, rom-storage/SAF, controller-profiles) happen when Milestone 5 lands and each has
real code and tests. Dependency injection: **none** (manual wiring through a single `AppContainer`);
Hilt/Koin would add startup cost and APK size with no real benefit at this size.

### Key interfaces (Kotlin)

`EmulatorCore` (lifecycle: load / run / pause / reset / save state / unload), `EmulatorCoreFactory`
(creates a core for a `SystemAdapter` + ROM, or returns a typed failure), `SystemAdapter` (system id,
extensions, core candidates, input button set, capabilities, status = TESTED/EXPERIMENTAL/PLANNED),
`GameMetadata`, `RomRepository`, `ControllerMappingRepository`, `SettingsStore` (graphics/audio/safe area),
`SaveStateRepository`. Only the ones needed by the current milestone get an implementation.

## 5. Native host design (libretro frontend)

* **Core loading:** `dlopen(<nativeLibraryDir>/lib<core>_libretro_android.so)`. Android 10+ forbids executing code from app data dirs, so cores must be packaged as `lib*.so` and extracted (`useLegacyPackaging = true`). Resolve all `retro_*` symbols with `dlsym`; fail with a typed error if any is missing.
* **Threads:** UI thread (Compose/Activity), **emulation thread** (calls `retro_run`, owns the core), **GL thread** (GLSurfaceView renderer), **audio** (drained by the emulation thread into `AudioTrack`; blocking write provides frame pacing, with a clock fallback when audio is disabled). Menus never touch the emulation thread except through a command queue (pause/resume/save/load/stop).
* **Video:** the host accepts RGB565, XRGB8888 and 0RGB1555 from the core and always stores/uploads RGB565 (half the bandwidth of XRGB8888, which matters for Mali-450). fceumm picks RGB565 itself when the frontend supports it (verified in its log). The `video_refresh` callback copies into a double buffer (no allocation per frame); the GL thread uploads with `glTexSubImage2D` into one reusable texture. Aspect ratio comes from `retro_get_system_av_info` and may change at runtime (`SET_GEOMETRY`).
* **Scaling/filtering:** GLES2; nearest or bilinear; the destination rectangle (original / 4:3 / 16:9 / stretch / integer, minus the safe-area margin) is computed on the CPU in `layout.h` (unit-tested on the host) and applied as the viewport. Milestone 6 adds single-pass fragment "looks" (sharp bilinear, scanlines, CRT, LCD grid): each program is built the first time it is chosen, off by default, and a compile/link failure is remembered, reported and replaced by the plain program. Fragment `highp` is used only under `GL_FRAGMENT_PRECISION_HIGH`; Mali-400/450 has none, so texture-coordinate maths in those shaders runs at fp16 there (not yet tried on that GPU).
* **Audio:** `retro_audio_sample_batch` writes into a lock-free ring buffer; `AudioTrack` in `MODE_STREAM` at the core's reported sample rate (default 44100/48000 per core), buffer size configurable, underrun counter exposed to Diagnostics. Milestone 6: the buffer is 50–300 ms from Settings and is swapped live (a new `AudioTrack` replaces the old one on the emulation thread); volume/mute/fade-in are applied in software by `GainStage` before the write. `settings.json` (`SettingsStore`) holds graphics defaults, per-system exceptions, audio, safe area and the overlay flag.
* **Players:** `PlayerAssigner` (device id → player slot: oldest gamepad first, lazily for later pads, release on unplug, swap) and `PlayerInputs` (one `GameInput` per player, routed by `KeyEvent.deviceId`/source) feed `setInput(port, …)`; Player 2's key map is resolved from `ProfileSet.player2` per level (copy of Player 1 unless that level is custom). Both libretro ports are set to RETRO_DEVICE_JOYPAD at game load.
* **Input:** Kotlin maps `KeyEvent`/`MotionEvent` → libretro joypad bitmask per system profile → atomic words in the host; `retro_input_state` reads a per-frame snapshot (no JNI call per poll). The snapshot is `held | latched`, where every press is latched until one frame has seen it, so a tap shorter than a frame (remote key, slow frame, `adb input`) is never lost. Analog sticks and D-pad hats are separate sources.
* **Environment callbacks implemented first:** `SET_PIXEL_FORMAT`, `GET_SYSTEM_DIRECTORY`, `GET_SAVE_DIRECTORY`, `GET_CORE_ASSETS_DIRECTORY`, `GET_LOG_INTERFACE`, `GET_VARIABLE`/`SET_VARIABLES`/`GET_VARIABLE_UPDATE`, `SET_INPUT_DESCRIPTORS`, `GET_CAN_DUPE`, `SET_GEOMETRY`, `SET_SYSTEM_AV_INFO`, `GET_LANGUAGE`, `GET_RUMBLE_INTERFACE` (declines), and returns `false` for anything unrecognised. Core options use the legacy `SET_VARIABLES` interface first; the v1/v2 option interfaces come with the per-system settings UI.
* **Saves:** SRAM through `retro_get_memory_data/size(RETRO_MEMORY_SAVE_RAM)`, persisted on unload and periodically; save states through `retro_serialize/unserialize` with a slot file naming scheme and metadata sidecar. No silent overwrite: the UI confirms before replacing a slot.
* **Crash handling:** a native crash in a core kills the process (same process as UI). Recovery: a `session.lock` marker written at game start and deleted on clean exit; on next launch a marker present means "unclean exit", surfaced in Diagnostics, and SRAM is flushed periodically so progress loss is bounded. Running the emulator in a separate `:emu` process was considered; rejected for Milestone 3 because the second process costs tens of MB on a 1 GB device and forces cross-process settings plumbing. Revisit if crashes are common.

## 6. Storage and library (Milestone 5 — built; see IMPLEMENTATION_PLAN.md for the SQLite-instead-of-Room note)

* ROM folders via **Storage Access Framework** `ACTION_OPEN_DOCUMENT_TREE` with `takePersistableUriPermission`. Many Android TV firmwares ship **without** a working system document picker; the app must detect that (`resolveActivity == null`) and show a fallback (app-private ROM folder reachable over ADB/USB: `/sdcard/Android/data/<pkg>/files/roms`). The first run must work with that fallback, which is also how Milestone 3 is tested.
* Cores read ROMs from file paths (`retro_load_game` with a path) or from memory (`need_fullpath=false`). SAF `content://` URIs are not paths, so SAF ROMs are streamed into memory or copied to cache before loading — implemented per core `need_fullpath` flag.
* Scanning runs on one background thread, cancellable, with progress; results go to the platform SQLite (`SqlLibraryStore`; Room/KSP and DataStore were not adopted to keep the TV build lean). Settings stay in SharedPreferences for now.
* **Verified:** the TV emulator answers `ACTION_OPEN_DOCUMENT_TREE` with `com.android.tv.frameworkpackagestubs` (a stub), so "an activity resolves" does not mean a picker exists; `SafWalker.pickerAvailable` rejects such stubs.

## 7. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Mali-450 only GLES 2.0 | Shader presets limited | GLES2-only renderer, shader compile failure reported not fatal |
| 1 GB RAM + Compose | Slow/killed UI | Lean UI, measure on device, native fallback possible |
| 32-bit ARM, A53 @ ≤1.5 GHz | SNES/GBA slower games may drop frames | Fast cores by default (snes9x2005, gpSP); measure and report, no speed promises |
| TV lacks SAF picker | Cannot add folders | App-private ROM dir + ADB instructions + in-app USB path fallback |
| Cosmic Byte Stratos Xenon quirks (mode switch, d-pad reported as hat vs keys) | Wrong default mapping | Live input monitor + manual mapping are Milestone 2 features; no assumptions baked in |
| Native crash kills UI | Lost session | session marker, periodic SRAM flush, diagnostics |
| License (snes9x2005, GPL mixing) | Cannot publish | personal use; documented; core list is build-time selectable |
| Cannot test on the real TV from here | Unknown real-world perf | Tests on an arm64 AVD only; real-device test plan in docs, no claims until run |

## 8. What I could and could not verify here

Verified: toolchain present (SDK 36/37, NDK 27.1, CMake 3.22.1, Android Studio JBR JDK 25, Gradle 9.3.1 cached);
five cores build for both ABIs. Not verifiable here: the Mi TV's real Android version/ABI/RAM, a
physical Stratos Xenon, real-TV frame rates. The only available emulator image is a phone-type
arm64 image (`Pixel_10_Pro_XL`, API 37, 16 KB pages) — useful for lifecycle/UI/emulation smoke tests, not for performance numbers.
