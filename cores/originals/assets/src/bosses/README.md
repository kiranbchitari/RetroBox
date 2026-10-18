# Retro Platformer Boss Art Pack

Includes generated boss animation-strip references for:
- Ogre boss
- Frost Wizard boss
- Rage Ogre boss
- Boss effects (ice projectiles, ice spikes, hit stars, explosions, dust)

Also includes the complete generated character-and-boss sheet.

## Integration notes
These are usable PNG image assets as visual references, but they are not guaranteed clean, production-ready sprite atlases. The generated sheet includes panel borders, labels, effects, and some background artifacts. Before using in-game:
1. Crop individual animation frames.
2. Remove labels, borders, and unwanted artifacts.
3. Create transparent, consistently sized frames and atlas metadata.
4. Map animations to the project's existing state machine.
5. Keep collision boxes and gameplay logic unchanged.

## Suggested sizing
Render the new character sprites at approximately 115–120% of the hitbox dimensions for visual presence, while keeping collision/hitbox dimensions unchanged. Ensure feet remain aligned to the same ground baseline and verify no UI clipping at 720p and 1080p.

## Style and originality
Use this generated artwork as an original visual direction. Inspect assets and clean them up before shipping.
