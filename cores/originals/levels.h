/* Adventure-mode art for Blizzard Buddies: the level pack's 32 px tiles and parallax backgrounds (levels_data.h, made by
 * tools/originals-levels/convert_levels.py), scaled once to the game's tile and picture size, drawn as plain row copies. */
#ifndef RB_LEVELS_H
#define RB_LEVELS_H
#include "gfx.h"
#include "levels_data.h"

/* tile = pixels per game tile, h = picture height. Returns 1 when ready. */
int lv_setup(int tile, int h);
void lv_release(void);
/* Prepares the background of a theme (scaled to the picture height; done when a level starts). */
int lv_theme(int theme);
/* The background, scrolled left by scroll pixels (it repeats sideways); 0 if no theme is ready. */
int lv_draw_bg(Surf *s, int scroll);
/* One tile: art LV_ART_*, top = the cap version (snow, grass, ice crust), at whole pixel (x, y). */
void lv_draw_tile(Surf *s, int art, int top, int x, int y);

#endif
