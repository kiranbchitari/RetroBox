/* Character sprites for RetroBox Originals (Blizzard Buddies). The pictures are compiled in (sprites_data.h, made by
 * tools/originals-sprites/build_sprites.py from cores/originals/assets). spr_setup decodes them once per screen size and
 * pre-scales every frame to the game tile size (area filter down, bilinear up), so drawing at play size is a plain alpha blit. */
#ifndef RB_SPRITE_H
#define RB_SPRITE_H
#include "gfx.h"
#include "sprites_data.h"

/* tile = pixels per game tile at this resolution. Returns 1 when the sprites are ready; on 0 the game draws its
 * characters in code. */
int spr_setup(int tile);
void spr_release(void);
int spr_ready(void);

/* Frame `i` of animation `anim` (SPR_<CHAR>_<ANIM>), clamped or looped. */
int spr_frame(int anim, int i, int loop);
int spr_count(int anim);

typedef struct {
    float tile;      /* pixels per game tile to draw at (the game tile = 1:1 with the cache, crisp) */
    float sx, sy;    /* squash and stretch around the anchor, 1 = none */
    float lean;      /* horizontal shift of the top of the sprite per pixel of height (leaning into a run) */
    int flip;        /* 1 = face left (the art faces right) */
    int skin;        /* palette for characters with several (the buddy), else 0 */
    int alpha;       /* 0..255 */
    int flash;       /* 0..255 toward white (hit flash) */
} SprDraw;

/* Draws a frame with its anchor (feet, or the body centre for the flyer) at (x, y). */
void spr_draw(Surf *s, int frame, float x, float y, const SprDraw *d);

/* Height of a frame above its anchor, in tiles (for things drawn on top, like snow caps). */
float spr_top(int frame);
/* Stored pixels per game tile of a frame's character (to lay frames out by their size). */
float spr_ppt(int frame);

#endif
