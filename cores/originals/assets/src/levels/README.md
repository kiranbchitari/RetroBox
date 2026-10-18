# Platformer Level Assets + 10 Levels

Contains 32x32 PNG tiles, transparent prop PNGs, 512x288 parallax backgrounds, and ten generic tile-based JSON layouts.

## Character sizing
Keep collision/hitbox dimensions unchanged. Increase only visual rendering:
- If current hitbox is 48x48 px, render buddy/enemies around 58–60 px (1.2–1.25x).
- If current hitbox is 40x40 px, render around 48–50 px.
- Anchor sprite feet at collision body's bottom-center; allow transparent padding above and around sprite.
- Bosses: target roughly 2–2.8x normal enemy visual height.
- Do not scale physics, movement, attack ranges, or collision shapes.

## Levels
01 Snowy Forest; 02 Ice Cave; 03 Mountain Cliffs; 04 Frozen Castle; 05 Lava Cavern; 06 Windy Heights; 07 Ancient Ruins; 08 Underground Mine; 09 Crystal Peak; 10 Final Castle with three-phase Rage Ogre.

## Claude Code integration
Inspect the current renderer and level schema first. These JSON layouts use a generic 32px tile-based schema; convert them to the project's actual format instead of blindly replacing it. Map tile names to PNGs, add parallax backgrounds, then validate all ten levels. If the game uses code-drawn platforms, convert the platform coordinates into its existing level representation.
