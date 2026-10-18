# Retro Platformer Character Asset Pack

This ZIP contains a generated character sprite sheet and per-character PNG strips with transparent backgrounds.

## Files
- `all_characters_sprite_sheet.png` — full six-character sheet.
- `penguin_buddy_sprite_strip.png`
- `walker_sprite_strip.png`
- `hopper_sprite_strip.png`
- `flyer_sprite_strip.png`
- `spitter_sprite_strip.png`
- `charger_sprite_strip.png`

## Important integration notes
- PNGs use transparency where the background was separable from the generated image.
- The character strips are visual sprite strips, not guaranteed uniform-grid sprite sheets. Some frames, labels, and effects may be irregularly spaced; inspect and crop individual frames before runtime animation.
- These are generated visual assets, not pixel-perfect production atlases. Check each image at actual game scale, remove any remaining labels/effects, normalize frame dimensions, and create a proper atlas/metadata file for the project's engine.
- Do not replace game logic or collision boxes when swapping art. Use the existing rendering harness to verify scale, alignment, and animation timing.
- Generated assets may contain minor artifacts; clean up before shipping.
