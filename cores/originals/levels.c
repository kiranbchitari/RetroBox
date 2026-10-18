/* Adventure-mode tiles and backgrounds. See levels.h. */
#include "levels.h"
#include "backgrounds_data.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>

/* stb_image (public domain / MIT, third_party/stb_image.h v2.30): JPEG only, from memory, no stdio */
#define STBI_ONLY_JPEG
#define STBI_NO_STDIO
#define STBI_NO_HDR
#define STBI_NO_LINEAR
#define STBI_NO_FAILURE_STRINGS
#define STB_IMAGE_STATIC
#define STB_IMAGE_IMPLEMENTATION
#if defined(__aarch64__)
#define STBI_NEON
#endif
#if defined(__GNUC__)
#pragma GCC diagnostic ignored "-Wunused-function"   /* for the whole file: the unused static stb_image functions are reported at its end */
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wsign-compare"
#pragma GCC diagnostic ignored "-Wunused-parameter"
#endif
#include "third_party/stb_image.h"
#if defined(__GNUC__)
#pragma GCC diagnostic pop
#endif

#define BG_ZOOM 1.08f                      /* the background is drawn 8% taller than the screen: room to slide (parallax) */

typedef struct { uint16_t *rgb; uint8_t *a; uint8_t *opaqueRow; } Tile;   /* cell x cell; rgb premultiplied 565 */
static Tile tiles[LV_NTILES];
static int cell, ready, screenW, screenH;
static uint16_t *bgpx;                     /* the current stage's background, bgw x bgh */
static int bgw, bgh, bgstage = -1;

static uint16_t pack565(int r, int g, int b) {
    if (r > 255) r = 255;
    if (g > 255) g = 255;
    if (b > 255) b = 255;
    if (r < 0) r = 0;
    if (g < 0) g = 0;
    if (b < 0) b = 0;
    return (uint16_t)(((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3));
}

/* Area-averaging scale of a premultiplied RGBA tile (64 x 64) to cell x cell. */
static int make_tile(Tile *t, const uint8_t *src) {
    t->rgb = (uint16_t *)malloc((size_t)cell * cell * sizeof(uint16_t));
    t->a = (uint8_t *)malloc((size_t)cell * cell);
    t->opaqueRow = (uint8_t *)malloc((size_t)cell);
    if (!t->rgb || !t->a || !t->opaqueRow) return 0;
    float k = 64.f / (float)cell;
    for (int y = 0; y < cell; y++) {
        float y0 = (float)y * k, y1 = y0 + k;
        int opaque = 1;
        for (int x = 0; x < cell; x++) {
            float x0 = (float)x * k, x1 = x0 + k, acc[4] = {0, 0, 0, 0}, aw = 0.f;
            for (int sy = (int)y0; sy < 64 && (float)sy < y1; sy++) {
                float wy = fminf(y1, (float)sy + 1.f) - fmaxf(y0, (float)sy);
                if (wy <= 0.f) continue;
                for (int sx = (int)x0; sx < 64 && (float)sx < x1; sx++) {
                    float wx = fminf(x1, (float)sx + 1.f) - fmaxf(x0, (float)sx);
                    if (wx <= 0.f) continue;
                    const uint8_t *p = src + ((size_t)sy * 64 + sx) * 4;
                    for (int c = 0; c < 4; c++) acc[c] += wx * wy * (float)p[c];
                    aw += wx * wy;
                }
            }
            int a = (int)(acc[3] / aw + .5f);
            if (a > 250) a = 255;                           /* the edge of an opaque tile stays opaque */
            t->a[y * cell + x] = (uint8_t)a;
            t->rgb[y * cell + x] = pack565((int)(acc[0] / aw + .5f), (int)(acc[1] / aw + .5f), (int)(acc[2] / aw + .5f));
            if (a != 255) opaque = 0;
        }
        t->opaqueRow[y] = (uint8_t)opaque;
    }
    return 1;
}

void lv_release(void) {
    for (int i = 0; i < LV_NTILES; i++) { free(tiles[i].rgb); free(tiles[i].a); free(tiles[i].opaqueRow); memset(&tiles[i], 0, sizeof tiles[i]); }
    free(bgpx); bgpx = NULL; bgstage = -1; ready = 0;
}

int lv_setup(int tile, int w, int h) {
    lv_release();
    cell = tile; screenW = w; screenH = h;
    for (int i = 0; i < LV_NTILES; i++) if (!make_tile(&tiles[i], LV_TILES[i])) { lv_release(); return 0; }
    ready = 1;
    return 1;
}

/* Bilinear scale of RGB888 to RGB565 with a 4 x 4 ordered dither, so the painted skies do not band. */
static void scale_bg(const uint8_t *src, int sw, int sh, uint16_t *dst, int dw, int dh) {
    static const int8_t BAYER[4][4] = {{-8, 0, -6, 2}, {4, -4, 6, -2}, {-5, 3, -7, 1}, {7, -1, 5, -3}};
    float kx = (float)sw / (float)dw, ky = (float)sh / (float)dh;
    for (int y = 0; y < dh; y++) {
        float v = ((float)y + .5f) * ky - .5f;
        int y0 = (int)floorf(v);
        float fy = v - (float)y0;
        if (y0 < 0) { y0 = 0; fy = 0.f; }
        int y1 = y0 + 1 < sh ? y0 + 1 : sh - 1;
        const uint8_t *r0 = src + (size_t)y0 * sw * 3, *r1 = src + (size_t)y1 * sw * 3;
        for (int x = 0; x < dw; x++) {
            float u = ((float)x + .5f) * kx - .5f;
            int x0 = (int)floorf(u);
            float fx = u - (float)x0;
            if (x0 < 0) { x0 = 0; fx = 0.f; }
            int x1 = x0 + 1 < sw ? x0 + 1 : sw - 1;
            int c[3];
            for (int k = 0; k < 3; k++) {
                float top = (float)r0[x0 * 3 + k] * (1.f - fx) + (float)r0[x1 * 3 + k] * fx;
                float bot = (float)r1[x0 * 3 + k] * (1.f - fx) + (float)r1[x1 * 3 + k] * fx;
                c[k] = (int)(top * (1.f - fy) + bot * fy + .5f);
            }
            int d = BAYER[y & 3][x & 3];
            dst[(size_t)y * dw + x] = pack565(c[0] + d, c[1] + d / 2, c[2] + d);
        }
    }
}

int lv_background(int stage) {
    if (!ready || stage < 0 || stage >= LV_COUNT) return 0;
    if (stage == bgstage && bgpx) return 1;
    int sw, sh, n;
    uint8_t *rgb = stbi_load_from_memory(LV_BG_JPEG[stage], (int)LV_BG_SIZE[stage], &sw, &sh, &n, 3);
    if (!rgb) return 0;
    int dh = (int)ceilf((float)screenH * BG_ZOOM), dw = (int)ceilf((float)dh * (float)sw / (float)sh);
    if (dw < screenW) { dw = screenW; dh = (int)ceilf((float)dw * (float)sh / (float)sw); }
    free(bgpx);
    bgpx = (uint16_t *)malloc((size_t)dw * dh * sizeof(uint16_t));
    if (!bgpx) { stbi_image_free(rgb); bgstage = -1; return 0; }
    scale_bg(rgb, sw, sh, bgpx, dw, dh);
    stbi_image_free(rgb);
    bgw = dw; bgh = dh; bgstage = stage;
    return 1;
}

int lv_draw_bg(Surf *s, float fx, float fy) {
    if (!bgpx) return 0;
    fx = fx < 0.f ? 0.f : (fx > 1.f ? 1.f : fx);
    fy = fy < 0.f ? 0.f : (fy > 1.f ? 1.f : fy);
    int ox = (int)((float)(bgw - s->w) * fx), oy = (int)((float)(bgh - s->h) * fy);
    for (int y = 0; y < s->h; y++)
        memcpy(s->px + (size_t)y * s->w, bgpx + (size_t)(y + oy) * bgw + ox, (size_t)s->w * sizeof(uint16_t));
    return 1;
}

void lv_draw_tile(Surf *s, int t, int x, int y) {
    if (!ready || t < 0 || t >= LV_NTILES) return;
    const Tile *T = &tiles[t];
    int x0 = x < s->cx0 ? s->cx0 : x, x1 = x + cell > s->cx1 ? s->cx1 : x + cell;
    if (x1 <= x0) return;
    for (int j = 0; j < cell; j++) {
        int Y = y + j;
        if (Y < s->cy0 || Y >= s->cy1) continue;
        uint16_t *out = s->px + (size_t)Y * s->w;
        const uint16_t *src = T->rgb + (size_t)j * cell;
        if (T->opaqueRow[j]) { memcpy(out + x0, src + (x0 - x), (size_t)(x1 - x0) * sizeof(uint16_t)); continue; }
        const uint8_t *al = T->a + (size_t)j * cell;
        for (int X = x0; X < x1; X++) {
            int a = al[X - x];
            if (a == 255) { out[X] = src[X - x]; continue; }
            if (a == 0) continue;
            uint16_t c = src[X - x], d = out[X];
            int ia = 255 - a;
            int r = (((c >> 11) & 31) << 3) + ((((d >> 11) & 31) << 3) * ia + 127) / 255;
            int g = (((c >> 5) & 63) << 2) + ((((d >> 5) & 63) << 2) * ia + 127) / 255;
            int b = ((c & 31) << 3) + (((d & 31) << 3) * ia + 127) / 255;
            out[X] = pack565(r, g, b);
        }
    }
}
