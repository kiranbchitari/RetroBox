# RetroBox Originals

Games made for RetroBox itself. They are one libretro core (`retrobox_originals`), so the library, controller profiles,
save states, Quick Resume, fast-forward and the pause menu work exactly as they do for NES or SNES. Licensed GPL-2.0 like the app.

**A game is a "cartridge":** a tiny `Snake.rbo` file, `RBO1` on the first line and `game=<id>` on the second. The app writes
the bundled cartridges to `<app roms folder>/RetroBox Originals/` the first time it starts (once per game, a marker next to it
remembers; delete a cartridge and it stays deleted). Today: `snake` and `blizzard` ("Blizzard Buddies"); a game added in a later version is installed at the next start.

## Picture size

The core draws RGB565 frames at **1280x720** (default), **1920x1080** or **960x540**, chosen by the core option
`retrobox_originals_resolution`. The app sets it before the game loads from the screen height (`Originals.resolutionFor`):
1000 px and up gets 1080p (4K screens too: the GPU scales a 1080p frame, a 4K software frame would be too slow),
650-999 px gets 720p (the Mi TV is 1280x720), below that 540p. Everything is laid out from the frame height
(`S = height / 720`), so the same game looks the same, only sharper, at any of the three sizes; the host letterboxes other aspect
ratios. Logic runs on a fixed grid at 60 Hz independent of the resolution.

## Files

| File | What |
|---|---|
| `core.c` | libretro glue: options, input (D-pad, left stick, A/B/Start), audio batch, save RAM (best scores, last menu choice), save states |
| `blizzard.c` | Blizzard Buddies, a one-screen snowball platformer for one or two players (see below) |
| `sprite.c` | Blizzard's character sprites: decodes `sprites_data.h` once per picture size, pre-scales every frame to the game tile with an area filter, alpha blit with mirroring, squash/stretch, lean and a white hit flash |
| `sprites_data.h` | generated, do not edit: the character, boss and effect frames as 255-colour palettes + run-length coded pixels, six palette-swapped buddy skins (about 930 KB of pixel data) |
| `assets/` | `src/`: the character art pack as supplied (strips and its README), `src/bosses/`: the boss and effects pack as supplied; `blizzard_atlas.png` + `blizzard_atlas.json`: the cleaned frames, anchors and animations made from them |
| `snake.c` | the game: grid logic, smooth sliding snake, wrap/walls, bonus fruit, title screen with an autopilot snake, pause, game over, particles |
| `gfx.c` | software renderer: anti-aliased rounded rectangles / circles / rings, dithered gradient, vignette, 5x7 pixel font |
| `snd.c` | sound effects and a small looping chiptune, all synthesised in code (square / triangle / noise voices with pitch slides); no audio files |
| `build-host.sh` | builds a macOS/Linux library for `tools/core-harness` (`EXTRA_CFLAGS=-DRB_SELFTEST` makes the autopilot / a test bot play real games; Blizzard also knows `-DRB_GODMODE`, `-DRB_TRACE`, `-DRB_START_LEVEL=5` and `-DRB_SPRITE_GALLERY`, which shows every sprite frame, skin and animation instead of the title) |
| `build-android.sh` | builds both Android ABIs into `app/src/main/jniLibs` (also run by `cores/build-cores.sh`) |

## Trying it without a TV

```bash
cores/originals/build-host.sh && tools/core-harness/build.sh
printf 'RBO1\ngame=snake\n' > /tmp/Snake.rbo
# 300 frames, press A (start) at frame 70, dump frames 60 and 140 as PNG; -o picks the picture size
tools/core-harness/core-harness cores/originals/out/retrobox_originals_libretro.dylib /tmp/Snake.rbo -n 300 \
  -i 70:0:a -i 73:0:none -d 60:/tmp/title.png -d 140:/tmp/play.png -o retrobox_originals_resolution=1080p
```

## Controls

Title: D-pad up/down chooses MODE / SPEED / PLAY, left/right changes the value, A or Start plays. In game: D-pad (or left stick)
steers, Start pauses (B then returns to the title), after a crash A retries and B goes to the title.
Modes: WALLS (the wall kills) and WRAP (the edges connect). Speeds: EASY, NORMAL, HARD; the snake speeds up as it eats, a gold bonus
fruit appears after every 5th apple. Best scores are kept per mode and speed in the cartridge save.

## Blizzard Buddies

An original game (own name, characters and levels; nothing taken from any other game). The buddy, the five small
monsters and the three bosses are sprites from generated art packs made for the game (`assets/src`); the scenery,
snowballs, ghost and fireballs are drawn in code. Throw snow (A) at the monsters:
three hits bury one in a snowball, walk into the ball to roll it, and it flattens every monster in its way. Move with the D-pad or
stick, jump with B or Up, drop through a platform with Down + jump. Pad 2's Start joins a second player at any time (Start on pad 2
at the title starts a co-op game). Best total score and menu choices are kept in the cartridge save.

* **Monsters:** walkers, hoppers, flyers, spitters (shoot fireballs along their row), chargers (stun themselves on a wall), and a boss every
  fifth level: an ogre (level 5), a frost wizard that hovers, teleports and fires spreads (10), a rage ogre (15), then they repeat.
* **Pace:** levels get more and faster monsters (up to 14); after 45 seconds a ghost that can't be hurt hunts the players down.
* **Score:** kills in quick succession build a combo (x2 to x5), rolling one ball through several monsters doubles each kill, a level
  ends with a time bonus and a no-miss bonus, and every 40000 points after 30000 is an extra life. Potions give speed, range and power.
* **Levels:** ten layouts (some with slippery ice platforms or an ice floor), four colour worlds, every layout drawn in code.
* **Replay:** difficulty (Easy / Normal / Hard changes monster speed and lives) and six buddy colours; gold, violet and panda unlock
  at levels 5, 10 and 15.
* **Feel:** hit-stop and camera shake on hits, knockback, dust, squash on landing, short jumps by letting go of B, a little music.

It is drawn on a 32 x 18 tile board (a tile is height / 18 pixels, so 960x540, 1280x720 and 1920x1080 are filled exactly), at 60 Hz.

### Character sprites

`tools/originals-sprites/build_sprites.py` (Python 3 with Pillow and numpy) turns the art in `assets/src` into
`assets/blizzard_atlas.png`, `assets/blizzard_atlas.json` and `sprites_data.h`; rerun it after changing the art or its
frame lists (`--preview sheet.png` also writes every animation on one sheet). The strips are not a grid, so the script:
cleans the alpha haze around the art, finds each body and splits touching ones at the thinnest column, drops labels, stars,
projectiles and other loose effects, erases the few effects attached to a body, recolours the anti-aliased edge so no
coloured glow shows, scales each character once and anchors all its frames at the feet (the flyer and the effects at
their centre). Characters are drawn 18% bigger than the first sprite version, the bosses 18% bigger than their 2.5-tile
hit box; hit boxes, physics and the feet line did not change. The bosses come from the right half of
`src/bosses/complete_character_and_boss_sheet.png` (the boss strips beside it cut off the ogre's second row) and are
picked by a point on each drawing (`at:x,y`). Their art is small, so it is stored at fewer pixels per tile (scaled up
1.3x at most in the script) and the game scales it the rest of the way once, when the picture size is set. Frames whose effects are painted over the body are left out (listed per character in the JSON's
`omitted`): the walker's slash and smoke frames, the hopper's attack, the spitter's spat balls and a glitchy hit pose,
the yeti's run and charge, which stand in a snow cloud, the ogre's idle (a double exposure) and walk (the club changes
hands between frames), the wizard's walk (drawn smaller; he hovers) and the ice spikes (no move uses them). The game makes the yeti's walk and charge from its idle frames
(a waddle, a forward lean, and the dust puffs drawn in code). The frame lists refer to bodies by their order in the strip;
the script stops if a strip yields a different number of bodies, so a change to the art cannot silently shift them.

In the game: idle / run / jump (picked by vertical speed) / throw / hit then death for the buddies; walk, jump, hit and
death for the walker; crouch (idle), jump, hit, death for the hopper; fly, hit, death for the flyer; walk, spit wind-up
(attack), hit, death for the spitter; idle-based walk and charge, a dizzy pose when stunned, hit and death for the yeti.
Bosses: one drawing per pose. The ogre walks in its idle pose with a waddle and the pack's footstep dust, winds up
before it throws, and swings (with the pack's whoosh) while its fireball leaves; the rage ogre roars (fire aura) before
and punches (fire streak) after; the wizard idles in a frost aura and casts around his shots, which are the pack's ice
balls growing out of his hand. A rolled snowball shows the hit pose with the pack's stars circling for about 0.7 s, a
snow hit a short white flash; death is the knocked-out pose in the pack's snow cloud. The yeti's stun stars and charge
puffs are pack art too. Shots still leave from the same point and time; the poses are timed from the game state (the
fire timer, a just-fired shot), so save states still restore the exact picture. Fireballs, the wizard's aura and the
health bars are drawn in code (the packs have none).

Positions, speeds and hit boxes are unchanged; only the drawing is new. If the sprites cannot be set up (no memory) the
characters are drawn in code as before.

## Adding another game

Add a `xxx.c` that exports a `Game` table like `GAME_SNAKE` / `GAME_BLIZZARD` (`game.h`), list it in `retro_load_game` in `core.c`, add a
`Cartridge` to `Originals.cartridges`, and a test in `OriginalsTest` / `OriginalsDeviceTest`.
