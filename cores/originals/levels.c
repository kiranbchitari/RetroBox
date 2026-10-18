/* Adventure-mode tiles and backgrounds. See levels.h. */
#include "levels.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>

static uint16_t *tiles[LV_NART][2];       /* [art][top] at cell x cell */
static int cell, ready;
static uint16_t *bgpx;                    /* the current theme, bgw x bgh */
static int bgw, bgh, bgtheme = -1, screenH;

static void unpack(uint16_t c, float *r, float *g, float *b) {
    *r = (float)((c >> 11) & 31) * (255.f / 31.f); *g = (float)((c >> 5) & 63) * (255.f / 63.f); *b = (float)(c & 31) * (255.f / 31.f);
}

static uint16_t pack(float r, float g, float b) {
    int R = (int)(r + .5f), G = (int)(g + .5f), B = (int)(b + .5f);
    if (R > 255) R = 255;
    if (G > 255) G = 255;
    if (B > 255) B = 255;
    return (uint16_t)(((R >> 3) << 11) | ((G >> 2) << 5) | (B >> 3));
}

/* Sharp scaling for pixel art: each output pixel averages the source pixels it covers (area), so pixels stay square and
 * only the seams between them blend; works up and down. src sw x sh, dst dw x dh. */
static void scale_area(const uint16_t *src, int sw, int sh, uint16_t *dst, int dw, int dh) {
    float kx = (float)sw / (float)dw, ky = (float)sh / (float)dh;
    for (int y = 0; y < dh; y++) {
        float y0 = (float)y * ky, y1 = y0 + ky;
        for (int x = 0; x < dw; x++) {
            float x0 = (float)x * kx, x1 = x0 + kx, ar = 0, ag = 0, ab = 0, aw = 0;
            for (int sy = (int)y0; sy < sh && (float)sy < y1; sy++) {
                float wy = fminf(y1, (float)sy + 1.f) - fmaxf(y0, (float)sy);
                if (wy <= 0.f) continue;
                for (int sx = (int)x0; sx < sw && (float)sx < x1; sx++) {
                    float wx = fminf(x1, (float)sx + 1.f) - fmaxf(x0, (float)sx);
                    if (wx <= 0.f) continue;
                    float r, g, b;
                    unpack(src[sy * sw + sx], &r, &g, &b);
                    ar += r * wx * wy; ag += g * wx * wy; ab += b * wx * wy; aw += wx * wy;
                }
            }
            dst[y * dw + x] = aw > 0.f ? pack(ar / aw, ag / aw, ab / aw) : 0;
        }
    }
}

void lv_release(void) {
    for (int a = 0; a < LV_NART; a++) for (int t = 0; t < 2; t++) { free(tiles[a][t]); tiles[a][t] = NULL; }
    free(bgpx); bgpx = NULL; bgtheme = -1; ready = 0;
}

int lv_setup(int tile, int h) {
    lv_release();
    cell = tile; screenH = h;
    for (int a = 0; a < LV_NART; a++)
        for (int t = 0; t < 2; t++) {
            tiles[a][t] = (uint16_t *)malloc((size_t)tile * tile * sizeof(uint16_t));
            if (!tiles[a][t]) { lv_release(); return 0; }
            scale_area(t ? LV_TILE_TOP[a] : LV_TILE_FILL[a], 32, 32, tiles[a][t], tile, tile);
        }
    ready = 1;
    return 1;
}

int lv_theme(int theme) {
    if (!ready || theme < 0 || theme >= LV_NTHEME) return 0;
    if (theme == bgtheme && bgpx) return 1;
    const LvBg *b = &LV_BGS[theme];
    uint16_t *raw = (uint16_t *)malloc((size_t)b->w * b->h * sizeof(uint16_t));
    if (!raw) return 0;
    const uint16_t *d = LV_BG_DATA + b->off;
    for (int y = 0; y < b->h; y++)
        for (int x = 0; x < b->w;) { int n = *d++; uint16_t c = *d++; while (n-- > 0 && x < b->w) raw[y * b->w + x++] = c; }
    int nw = (int)lroundf((float)b->w * (float)screenH / (float)b->h);
    free(bgpx);
    bgpx = (uint16_t *)malloc((size_t)nw * screenH * sizeof(uint16_t));
    if (!bgpx) { free(raw); bgtheme = -1; return 0; }
    scale_area(raw, b->w, b->h, bgpx, nw, screenH);          /* flat shapes: the area filter gives soft, clean edges */
    free(raw);
    bgw = nw; bgh = screenH; bgtheme = theme;
    return 1;
}

int lv_draw_bg(Surf *s, int scroll) {
    if (!bgpx) return 0;
    int off = ((scroll % bgw) + bgw) % bgw;
    for (int y = 0; y < s->h && y < bgh; y++) {
        uint16_t *out = s->px + (size_t)y * s->w;
        const uint16_t *row = bgpx + (size_t)y * bgw;
        int x = 0, sx = off;
        while (x < s->w) {
            int n = bgw - sx;
            if (n > s->w - x) n = s->w - x;
            memcpy(out + x, row + sx, (size_t)n * sizeof(uint16_t));
            x += n; sx = 0;
        }
    }
    return 1;
}

void lv_draw_tile(Surf *s, int art, int top, int x, int y) {
    if (!ready || art < 0 || art >= LV_NART) return;
    const uint16_t *t = tiles[art][top ? 1 : 0];
    int x0 = x < s->cx0 ? s->cx0 : x, x1 = x + cell > s->cx1 ? s->cx1 : x + cell;
    if (x1 <= x0) return;
    for (int j = 0; j < cell; j++) {
        int Y = y + j;
        if (Y < s->cy0 || Y >= s->cy1) continue;
        memcpy(s->px + (size_t)Y * s->w + x0, t + (size_t)j * cell + (x0 - x), (size_t)(x1 - x0) * sizeof(uint16_t));
    }
}
