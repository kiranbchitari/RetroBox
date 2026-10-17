# Third-party notices

RetroBox TV loads separately built libretro cores with `dlopen`. Each core keeps its own license, and its exact source commit is pinned in `cores/build-cores.sh`. Every APK carries the full license texts in `assets/licenses/`, readable in the app under **Settings → About & licenses**: the app's GPL-2.0, this file, `licenses/*`, and each core's own license file with its repository and commit. `cores/build-cores.sh` copies the core license files into place when it builds the cores.

| Component | License | Notes |
|---|---|---|
| RetroBox TV (this repository) | GPL-2.0 (`LICENSE`) | |
| libretro API header (`app/src/main/cpp/third_party/libretro/libretro.h`) | MIT (`licenses/libretro-h-MIT.txt`) | © RetroArch team |
| FCEUmm (NES) | GPL-2.0-or-later | https://github.com/libretro/libretro-fceumm |
| Gambatte (GB/GBC) | GPL-2.0 | https://github.com/libretro/gambatte-libretro |
| gpSP (GBA) | GPL-2.0 | https://github.com/libretro/gpsp |
| mGBA (GBA) | MPL-2.0 | https://github.com/libretro/mgba |
| Snes9x 2005 (SNES) | Snes9x license (non-commercial) with GPLv2+ parts | https://github.com/libretro/snes9x2005. **Do not publish or sell an APK containing it**; it is fine for personal use. Leave it out with `./gradlew assembleRelease -Pretrobox.excludeCores=snes9x2005` (add `genesis_plus_gx,picodrive` for the Genesis cores). |
| Genesis Plus GX (Genesis) | Non-commercial (MAME-style) + bundled components (Nuked OPN2 LGPL-2.1+, Tremor BSD-3, libchdr BSD-3, …) | https://github.com/libretro/Genesis-Plus-GX commit `aa74d79`. **Redistributions may not be sold or used commercially.** Built from unmodified upstream sources. |
| PicoDrive (Genesis) | Non-commercial (MAME-style) + libchdr BSD-3, emu2413 MIT, dr_libs, Tremor BSD-3 | https://github.com/libretro/picodrive commit `1890c29` (with its pinned submodules). Same non-commercial terms. |
| AndroidX / Jetpack Compose / TV Material | Apache-2.0 (`licenses/Apache-2.0.txt`) | |

If you give the APK to anyone, the GPL requires that you also offer the corresponding source: this repository and the pinned core commits listed in `cores/build-cores.sh`. Test-only libraries (JUnit, AndroidX Test, org.json for JVM tests) are not in the APK.

No game ROMs, BIOS images or artwork are distributed. `tools/make_test_rom.py`, `tools/make_test_roms.py` and `tools/gba-testrom/` generate original test programs (CC0). Copies of them are kept in `app/src/androidTest/assets/testroms/` for the on-device tests and are only in the test APK. This is not legal advice.
