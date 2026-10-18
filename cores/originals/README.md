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
| `levels.c` | Adventure mode's tiles and painted backgrounds: the rich pack's 64 px tiles scaled once to the game tile (area filter), each stage's background decoded from JPEG (stb_image) when the stage starts, scaled 8% taller than the screen with a dithered bilinear filter and slid across that margin as the camera moves |
| `levels_data.h` | generated, do not edit: the art side of the ten Adventure stages (names, which tiles each one uses) and the 17 tiles (about 1 MB of source); the stage layouts in it are no longer used, `stagegen.c` builds them |
| `backgrounds_data.h` | generated, do not edit: the ten painted stage backgrounds, 1280 x 714 JPEG, 1.85 MB in all |
| `third_party/stb_image.h` | stb_image v2.30 (MIT / public domain, nothings/stb commit f58f558), only its JPEG decoder is compiled |
| `stagegen.c` / `stagegen.h` | the stage generator: Arena boards and Adventure stages from a seed (see Generated stages below); pure C, shares the jump physics numbers (`PH_*`) with `update_player` |
| `sprite.c` | Blizzard's character sprites: decodes `sprites_data.h` once per picture size, pre-scales every frame to the game tile with an area filter, alpha blit with mirroring, squash/stretch, lean and a white hit flash |
| `sprites_data.h` | generated, do not edit: the character, boss, effect and prop frames as 255-colour palettes + run-length coded pixels, six palette-swapped buddy skins (about 715 KB of pixel data) |
| `assets/` | `src/rich/` (git-ignored, fetched by `tools/originals-assets/fetch_rich_pack.sh`): the rich art pack all the art now comes from; `src/levels/`: the level pack whose ten layouts the stages are converted from; `src/` and `src/bosses/`: the first character and boss packs, kept for reference; `blizzard_atlas.png` + `blizzard_atlas.json`: the cleaned frames, anchors and animations; `adventure_levels.json`: the converted stages |
| `snake.c` | the game: grid logic, smooth sliding snake, wrap/walls, bonus fruit, title screen with an autopilot snake, pause, game over, particles |
| `gfx.c` | software renderer: anti-aliased rounded rectangles / circles / rings, dithered gradient, vignette, 5x7 pixel font |
| `snd.c` | sound effects and a small looping chiptune, all synthesised in code (square / triangle / noise voices with pitch slides); no audio files |
| `build-host.sh` | builds a macOS/Linux library for `tools/core-harness` (`EXTRA_CFLAGS=-DRB_SELFTEST` makes the autopilot / a test bot play real games; Blizzard also knows `-DRB_GODMODE`, `-DRB_TRACE`, `-DRB_SEED=n` (a fixed seed), `-DRB_START_LEVEL=5` (Arena), `-DRB_START_STAGE=3` and `-DRB_START_X=60` (Adventure: begin at that column), and `-DRB_SPRITE_GALLERY`, which shows every sprite frame, skin and animation instead of the title) |
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
monsters, the three bosses, fireballs, ice balls and Adventure's tiles, props and backgrounds come from a generated art
pack made for the game (`assets/src/rich`); the arena scenery, snowballs and ghost are drawn in code. Throw snow (A) at the monsters:
three hits bury one in a snowball, walk into the ball to roll it, and it flattens every monster in its way. Move with the D-pad or
stick, jump with B or Up, drop through a platform with Down + jump. Pad 2's Start joins a second player at any time (Start on pad 2
at the title starts a co-op game). Best total score and menu choices are kept in the cartridge save.

* **Monsters:** walkers, hoppers, flyers, spitters (shoot fireballs along their row), chargers (stun themselves on a wall), and a boss every
  fifth level: an ogre (level 5), a frost wizard that hovers, teleports and fires spreads (10), a rage ogre (15), then they repeat.
* **Pace:** levels get more and faster monsters (up to 14); after 45 seconds a ghost that can't be hurt hunts the players down.
* **Score:** kills in quick succession build a combo (x2 to x5), rolling one ball through several monsters doubles each kill, a level
  ends with a time bonus and a no-miss bonus, and every 40000 points after 30000 is an extra life. Potions give speed, range and power.
* **Levels:** every Arena board is generated from the run's seed (see Generated stages): two to four tiers of ledges of different lengths and kinds, sometimes an ice floor, wind, crumbling ledges, a swarm or elite monsters; every fifth level is a boss board. Four colour worlds, drawn in code.
* **Replay:** difficulty (Easy / Normal / Hard changes monster speed and lives) and six buddy colours; gold, violet and panda unlock
  at levels 5, 10 and 15.
* **Feel:** hit-stop and camera shake on hits, knockback, dust, squash on landing, short jumps by letting go of B, a little music.

It is drawn on a 32 x 18 tile board (a tile is height / 18 pixels, so 960x540, 1280x720 and 1920x1080 are filled exactly), at 60 Hz.

### Character sprites

All the art comes from the rich art pack (64 MB, kept out of git: `tools/originals-assets/fetch_rich_pack.sh`
downloads it from the repository's `Snowbros` release, checks its SHA-256 and unpacks it into `assets/src/rich`). Its
characters, enemies and bosses come as one PNG per pose. `tools/originals-sprites/build_sprites.py` (Python 3 with
Pillow and numpy) turns them into `assets/blizzard_atlas.png`, `assets/blizzard_atlas.json` and `sprites_data.h`; rerun
it after changing the art or its frame lists (`--preview sheet.png` also writes every animation on one sheet). Per
frame it cleans the alpha haze, keeps the largest body (dropping loose sparks; the spitter keeps its ice ball when it is
a quarter of the body), erases the one effect attached to a body (the buddy's snowball in the throw, which the game
draws as the shot), recolours the anti-aliased edge so no glow shows, scales each character once to its height and
anchors all its frames at the feet (the flyer, the effects, coins and gems at their centre). A picture used by several
animations is stored once.

Sizes: the buddy and the small monsters are drawn 1.25 times their hit box height, the bosses 118% of their 2.5-tile
hit box; hit boxes, physics and the feet line are unchanged.

In the game: idle / run / jump (rising, falling) / throw (wind-up, release) / hit then death for the buddies; idle, run,
jump, hit and death for the walker and the hopper (with a blink); flap, hit and death for the flyer; walk, spit, hit and
death for the spitter; walk, charge, a dazed pose (with the pack's stars) when stunned, hit and death for the yeti.
Bosses: the ogre walks with its idle and walk frames, a waddle and the pack's footstep dust, jumps up before it throws
and punches as its fireball leaves; the rage ogre roars before and swings its fire arm after, and charges head down in
its last third of health; the wizard idles in a frost aura, raises his staff before a shot and summons ice spikes as it
leaves, the shot being the pack's ice ball growing out of his hand. Fireballs are the pack's (flickering between two
sizes), a spitter's shot is its ice orb. A rolled snowball shows the hit pose with stars circling; death is the
knocked-out pose in the pack's snow cloud. Poses are timed from the game state (the fire timer, a just-fired shot), so
save states still restore the exact picture. The health bars and the wizard's aura are drawn in code.

If the sprites cannot be set up (no memory) the characters are drawn in code as before.

### Adventure mode (generated stages)

The title's MODE row picks ARENA (the one-screen game above) or ADVENTURE: ten side-scrolling stages drawn with the rich
pack's art. The stage numbers keep their look (snowy forest, ice cave, mountain cliffs, frozen castle, lava cavern, windy
heights, ancient ruins, underground mine, crystal peak, the final castle: painted background, tiles, name) but **the layout of
every stage is generated when a run starts**, so no two runs play alike. The camera shows 32 x 18 game tiles, follows the
players (both stay on screen in co-op), moves in whole pixels so tiles never seam, and stops at the world's edges.

Physics, hit boxes and controls are the arena's. In Adventure: pits (falling in costs a life), spikes, springs, coins (100), gems
(1000), a treasure chest (500) hidden in a nook, a checkpoint flag (passing it moves the respawn there; dying there brings the monsters
beyond it back), the exit door, a time limit per stage (then the hurry-up ghost), monsters that wake as the camera nears them, and the
final stage's boss arena. Arena levels and Adventure stages share the state, save states and save RAM (which remembers the mode and
the best stage reached).

### Generated stages (`stagegen.c`)

A run's **seed** comes from what happens on the title screen (the frame count and timer when a game starts), is shown small under
the stage banner (`SEED 1A2B3C4D`), and is the only thing a save state needs to rebuild a stage (`stage_def()` regenerates it on
demand). The same seed, stage and difficulty always give the same stage; test builds fix it with `-DRB_SEED=n`.

*Adventure:* a stage is 122 to 140 tiles wide and is built left to right out of about eight kinds of stretch (more as the
stage number grows, weighted per stage so each has a signature): flat runs, gaps, steps up and down, stairs, hills, a high road of ledges
over a run, pillars standing in a pit, ledge chains over a pit, **crumbling ledges** (shake for about a second after you land, fall, grow
back), a **moving platform** that slides across a pit, a **lift** up a three-wide shaft, a **spring** that throws you onto a high block,
**wind** zones (gusts push you; never over ice), **icicles** that hang from small ice ledges and fall when you walk under them,
monster pens, spike runs and crate stacks (the mine). Each stage has at least one **nook**: a ledge with the treasure chest and a higher
one with a gem. Monsters, spikes, coins and scenery are scattered over what was built, kept away from the start, checkpoint and exit;
a top-up pass makes sure a stage has the monsters its number calls for (7 to 14, more on Hard). Stage 10 is generated up to a corridor and
ends in the fixed boss arena (rage ogre).

*Arena:* a 32 x 18 board of two to four tiers of ledges (3 to 4 rows apart; every ledge has one below it to jump from), mirrored half
the time, in one-way snow, one-way ice, crumbling or (a few) solid blocks. From level 2 the floor can be ice, from level 3 a wind that
gusts from alternating sides (never on an ice floor), and some levels are a **swarm** (many weak monsters) or **elite** (few spitters,
chargers and flyers). Boss levels keep their fixed boards.

*Finishable by construction and by proof:* every jump the generator asks for (gap width against height difference) comes from the
game's own jump numbers with a margin (`sg_reach`), so a stage can always be finished. `tools/originals-levels/sg_check.c` proves it
again independently: it copies the game's movement code, searches every spot a player can stand on from the start with walking,
drops, and full and short jumps with and without a run-up (moving platforms count as ground over their whole beat, a lift as a ledge at each
end), and fails unless the checkpoint, the exit, the gem and the chest can all be reached without touching a spike or falling.
It also checks that nothing is in a wall or floating and that every Arena ledge can be climbed:

```bash
cc -O2 -I cores/originals -o /tmp/sg_check tools/originals-levels/sg_check.c cores/originals/stagegen.c -lm
/tmp/sg_check 80            # 80 seeds x 10 stages x 2 difficulties, and the Arena boards
/tmp/sg_check --dump 7 4    # print seed 7's stage 4
```

Monsters (shootable), wind and icicles (timing) are not part of the proof. `tools/originals-levels/convert_levels.py` is no longer
needed to build stages; it still produces the art tables in `levels_data.h`.

Art (the rich pack): each stage has its own painted background (snowy forest, ice cave, mountain cliffs, frozen
castle, lava cavern, windy heights, ancient ruins, underground mine, crystal peak, the final castle) and its own pair of 64 px
tiles for surfaces and fill, plus one-way, lava pit and crate tiles where the stage has them. The backgrounds are not
drawn to tile, so instead of repeating layers each is drawn 8% taller than the screen and slid across that margin as
the camera crosses the stage (a slow parallax). Coins spin, gems sparkle, the checkpoint flag turns from red to blue,
the exit is the pack's stone arch (barred until the final boss falls), lanterns glow; springs and spikes are the pack's
too. The generator places scenery from the pack (a sign at the start and, per theme, trees, crystals or rocks on open ground; nothing that looks usable but is not).

Final stage: walking into the arena shuts the gate, locks the camera and calls the rage ogre. Three phases by health
(more fire, faster, shorter pauses), each announced; minions drop in; when it falls the barred door opens.

Cost: the backgrounds add 1.85 MB to the library (JPEG) and the tiles 0.3 MB; the stripped library grew from 1.1 to
3.1 MB per ABI (the sprites shrank a little, 830 to 715 KB). A stage's background is
decoded when the stage starts (2.2 MB in memory at 720p, 4.9 MB at 1080p, replaced by the next stage's; on the test
host the decode and scale take 36 ms at 720p and 69 ms at 1080p, once per stage).

## Adding another game

Add a `xxx.c` that exports a `Game` table like `GAME_SNAKE` / `GAME_BLIZZARD` (`game.h`), list it in `retro_load_game` in `core.c`, add a
`Cartridge` to `Originals.cartridges`, and a test in `OriginalsTest` / `OriginalsDeviceTest`.
