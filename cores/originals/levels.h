/* Adventure-mode art for Blizzard Buddies: the rich pack's 64 px tiles (levels_data.h) and each stage's painted
 * background (backgrounds_data.h, JPEG), both made by tools/originals-levels/convert_levels.py. Tiles are scaled once to
 * the game's tile size; a background is decoded and scaled to the screen when its stage starts. */
#ifndef RB_LEVELS_H
#define RB_LEVELS_H
#include "gfx.h"
#include "levels_data.h"

/* tile = pixels per game tile, w x h = the picture. Returns 1 when ready. */
int lv_setup(int tile, int w, int h);
void lv_release(void);
/* Decodes stage's background (0-based) for the current picture size; cheap if it is already the current one. */
int lv_background(int stage);
/* The background, slid within its margin: fx, fy from 0 (left / top) to 1 (right / bottom). 0 if none is ready. */
int lv_draw_bg(Surf *s, float fx, float fy);
/* Tile t (an LV_TILES index) with its top-left corner at whole pixel (x, y); see-through parts show what is behind. */
void lv_draw_tile(Surf *s, int t, int x, int y);

#endif
