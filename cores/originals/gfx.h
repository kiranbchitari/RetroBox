/* Tiny software renderer for RetroBox Originals: RGB565 surface, anti-aliased rectangles / circles, bitmap text.
 * Everything takes float coordinates, so a game lays itself out for any resolution from one scale factor. */
#ifndef RB_GFX_H
#define RB_GFX_H
#include <stdint.h>

typedef struct {
    uint16_t *px;            /* RGB565, w * h */
    int w, h;
    int cx0, cy0, cx1, cy1;  /* clip rectangle, [cx0,cx1) x [cy0,cy1) */
} Surf;

#define RGB(r, g, b) ((((uint32_t)(r)) << 16) | (((uint32_t)(g)) << 8) | (uint32_t)(b))

void gfx_clip_reset(Surf *s);
void gfx_clip(Surf *s, int x, int y, int w, int h);                 /* new clip = this rectangle, limited to the surface */
uint32_t gfx_mix(uint32_t a, uint32_t b, float t);                  /* t = 0 -> a, 1 -> b */
uint32_t gfx_scale(uint32_t c, float k);                            /* brightness */

/* alpha a is 0..255. r is the corner radius (0 = square). Edges are anti-aliased. */
void gfx_rrect(Surf *s, float x, float y, float w, float h, float r, uint32_t rgb, int a);
void gfx_circle(Surf *s, float cx, float cy, float r, uint32_t rgb, int a);
void gfx_ring(Surf *s, float cx, float cy, float r, float thick, uint32_t rgb, int a);
void gfx_vgrad(Surf *s, uint32_t top, uint32_t bottom);             /* whole surface, dithered */
void gfx_vignette(Surf *s, float strength);                         /* darkens the corners */

/* 5x7 pixel font, upper case only; each font pixel becomes a scale x scale block with softly rounded corners. */
int gfx_text_w(const char *s, int scale);
void gfx_text(Surf *s, float x, float y, int scale, const char *str, uint32_t rgb, int a);
void gfx_text_c(Surf *s, float cx, float y, int scale, const char *str, uint32_t rgb, int a);   /* centred on cx */
void gfx_text_r(Surf *s, float rx, float y, int scale, const char *str, uint32_t rgb, int a);   /* right edge at rx */

#endif
