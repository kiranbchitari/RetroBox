#include "gfx.h"
#include <math.h>
#include <string.h>

static inline int imin(int a, int b) { return a < b ? a : b; }
static inline int imax(int a, int b) { return a > b ? a : b; }
static inline float clamp01(float v) { return v < 0.f ? 0.f : (v > 1.f ? 1.f : v); }
static inline uint16_t pack565(int r, int g, int b) { return (uint16_t)(((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3)); }

/* d = d + (src - d) * a / 256, a in 1..254 */
static inline void blend(uint16_t *d, int sr, int sg, int sb, int a) {
    uint16_t p = *d;
    int dr = (p >> 11) & 31, dg = (p >> 5) & 63, db = p & 31;
    dr = (dr << 3) | (dr >> 2); dg = (dg << 2) | (dg >> 4); db = (db << 3) | (db >> 2);
    dr += ((sr - dr) * a) >> 8; dg += ((sg - dg) * a) >> 8; db += ((sb - db) * a) >> 8;
    *d = pack565(dr, dg, db);
}

void gfx_clip_reset(Surf *s) { s->cx0 = 0; s->cy0 = 0; s->cx1 = s->w; s->cy1 = s->h; }

void gfx_clip(Surf *s, int x, int y, int w, int h) {
    s->cx0 = imax(0, x); s->cy0 = imax(0, y); s->cx1 = imin(s->w, x + w); s->cy1 = imin(s->h, y + h);
}

uint32_t gfx_mix(uint32_t a, uint32_t b, float t) {
    t = clamp01(t);
    int r = (int)(((a >> 16) & 255) + ((int)((b >> 16) & 255) - (int)((a >> 16) & 255)) * t + .5f);
    int g = (int)(((a >> 8) & 255) + ((int)((b >> 8) & 255) - (int)((a >> 8) & 255)) * t + .5f);
    int bl = (int)((a & 255) + ((int)(b & 255) - (int)(a & 255)) * t + .5f);
    return RGB(r, g, bl);
}

uint32_t gfx_scale(uint32_t c, float k) {
    int r = (int)(((c >> 16) & 255) * k), g = (int)(((c >> 8) & 255) * k), b = (int)((c & 255) * k);
    return RGB(imin(255, r), imin(255, g), imin(255, b));
}

void gfx_rrect(Surf *s, float x, float y, float w, float h, float r, uint32_t c, int a) {
    if (w <= 0.f || h <= 0.f || a <= 0) return;
    if (a > 255) a = 255;
    float x1 = x + w, y1 = y + h;
    float mr = (w < h ? w : h) * .5f;
    if (r > mr) r = mr;
    if (r < 0.f) r = 0.f;
    int ix0 = imax((int)floorf(x), s->cx0), iy0 = imax((int)floorf(y), s->cy0);
    int ix1 = imin((int)ceilf(x1), s->cx1), iy1 = imin((int)ceilf(y1), s->cy1);
    if (ix0 >= ix1 || iy0 >= iy1) return;
    int sr = (c >> 16) & 255, sg = (c >> 8) & 255, sb = c & 255;
    uint16_t packed = pack565(sr, sg, sb);
    for (int py = iy0; py < iy1; py++) {
        float cy = fminf(py + 1.f, y1) - fmaxf((float)py, y);
        if (cy <= 0.f) continue;
        float fy = py + .5f;
        int ycorner = r > 0.f && (fy < y + r || fy > y1 - r);
        /* columns [sx0, sx1) are fully covered horizontally and outside the rounded corners */
        int sx0 = (int)ceilf(x), sx1 = (int)floorf(x1);
        if (ycorner) { sx0 = imax(sx0, (int)ceilf(x + r)); sx1 = imin(sx1, (int)floorf(x1 - r)); }
        sx0 = imin(imax(sx0, ix0), ix1);
        sx1 = imax(imin(sx1, ix1), sx0);
        uint16_t *row = s->px + (size_t)py * (size_t)s->w;
        for (int pass = 0; pass < 2; pass++) {            /* edge pixels: left run, then right run */
            int from = pass ? sx1 : ix0, to = pass ? ix1 : sx0;
            for (int px = from; px < to; px++) {
                float fx = px + .5f, cov;
                if (ycorner && (fx < x + r || fx > x1 - r)) {
                    float ccx = fx < x + r ? x + r : x1 - r, ccy = fy < y + r ? y + r : y1 - r;
                    float dx = fx - ccx, dy = fy - ccy;
                    cov = clamp01(.5f - (sqrtf(dx * dx + dy * dy) - r));
                } else {
                    cov = (fminf(px + 1.f, x1) - fmaxf((float)px, x)) * cy;
                }
                int pa = (int)(cov * a + .5f);
                if (pa <= 0) continue;
                if (pa >= 255) row[px] = packed; else blend(&row[px], sr, sg, sb, pa);
            }
            if (!pass) {                                  /* the solid middle */
                int sa = (int)(cy * a + .5f);
                if (sa >= 255) { for (int px = sx0; px < sx1; px++) row[px] = packed; }
                else if (sa > 0) { for (int px = sx0; px < sx1; px++) blend(&row[px], sr, sg, sb, sa); }
            }
        }
    }
}

/* Circle: per row a solid middle span and anti-aliased edge pixels (distance based), so big circles stay cheap. */
void gfx_circle(Surf *s, float cx, float cy, float r, uint32_t c, int a) {
    if (r <= 0.f || a <= 0) return;
    if (a > 255) a = 255;
    int sr = (c >> 16) & 255, sg = (c >> 8) & 255, sb = c & 255;
    uint16_t packed = pack565(sr, sg, sb);
    int y0 = imax((int)floorf(cy - r - .5f), s->cy0), y1 = imin((int)ceilf(cy + r + .5f), s->cy1);
    for (int py = y0; py < y1; py++) {
        float dy = py + .5f - cy, ady = fabsf(dy);
        float o2 = (r + .5f) * (r + .5f) - dy * dy;
        if (o2 <= 0.f) continue;
        float hwo = sqrtf(o2);
        float i2 = r * r - (ady + .5f) * (ady + .5f);
        float xi = i2 > 0.f ? sqrtf(i2) - .5f : -1.f;                    /* pixels whose centre is within xi of cx are fully inside */
        int sL, sR;
        if (xi > 0.f) { sL = (int)ceilf(cx - xi - .5f); sR = (int)floorf(cx + xi - .5f) + 1; if (sR < sL) sR = sL; }
        else { sL = sR = (int)ceilf(cx - .5f); }
        int L0 = (int)floorf(cx - hwo - .5f), R1 = (int)ceilf(cx + hwo + .5f);
        uint16_t *row = s->px + (size_t)py * (size_t)s->w;
        for (int pass = 0; pass < 2; pass++) {
            int from = pass ? sR : L0, to = pass ? R1 : sL;
            from = imax(from, s->cx0); to = imin(to, s->cx1);
            for (int px = from; px < to; px++) {
                float dx = px + .5f - cx;
                int pa = (int)(clamp01(.5f - (sqrtf(dx * dx + dy * dy) - r)) * a + .5f);
                if (pa <= 0) continue;
                if (pa >= 255) row[px] = packed; else blend(&row[px], sr, sg, sb, pa);
            }
        }
        int f = imax(sL, s->cx0), t = imin(sR, s->cx1);
        if (a >= 255) for (int px = f; px < t; px++) row[px] = packed;
        else for (int px = f; px < t; px++) blend(&row[px], sr, sg, sb, a);
    }
}

void gfx_ring(Surf *s, float cx, float cy, float r, float thick, uint32_t c, int a) {
    if (r <= 0.f || a <= 0) return;
    if (a > 255) a = 255;
    float mid = r - thick * .5f, half = thick * .5f;
    int x0 = imax((int)floorf(cx - r - 1.f), s->cx0), x1 = imin((int)ceilf(cx + r + 1.f), s->cx1);
    int y0 = imax((int)floorf(cy - r - 1.f), s->cy0), y1 = imin((int)ceilf(cy + r + 1.f), s->cy1);
    int sr = (c >> 16) & 255, sg = (c >> 8) & 255, sb = c & 255;
    for (int py = y0; py < y1; py++) {
        uint16_t *row = s->px + (size_t)py * (size_t)s->w;
        for (int px = x0; px < x1; px++) {
            float dx = px + .5f - cx, dy = py + .5f - cy;
            float d = sqrtf(dx * dx + dy * dy);
            int pa = (int)(clamp01(half + .5f - fabsf(d - mid)) * a + .5f);
            if (pa > 0) blend(&row[px], sr, sg, sb, pa >= 255 ? 254 : pa);
        }
    }
}

static const uint8_t bayer4[4][4] = {{0, 8, 2, 10}, {12, 4, 14, 6}, {3, 11, 1, 9}, {15, 7, 13, 5}};

void gfx_vgrad(Surf *s, uint32_t top, uint32_t bottom) {
    for (int y = 0; y < s->h; y++) {
        float t = s->h > 1 ? (float)y / (float)(s->h - 1) : 0.f;
        float r = ((top >> 16) & 255) + ((int)((bottom >> 16) & 255) - (int)((top >> 16) & 255)) * t;
        float g = ((top >> 8) & 255) + ((int)((bottom >> 8) & 255) - (int)((top >> 8) & 255)) * t;
        float b = (top & 255) + ((int)(bottom & 255) - (int)(top & 255)) * t;
        uint16_t *row = s->px + (size_t)y * (size_t)s->w;
        for (int x = 0; x < s->w; x++) {   /* ordered dither hides the banding RGB565 would show on a smooth gradient */
            float d = (bayer4[y & 3][x & 3] + .5f) / 16.f;
            int rr = (int)(r + (d - .5f) * 8.f), gg = (int)(g + (d - .5f) * 4.f), bb = (int)(b + (d - .5f) * 8.f);
            row[x] = pack565(imax(0, imin(255, rr)), imax(0, imin(255, gg)), imax(0, imin(255, bb)));
        }
    }
}

void gfx_vignette(Surf *s, float strength) {
    float hw = s->w * .5f, hh = s->h * .5f;
    for (int y = 0; y < s->h; y++) {
        float dy = (y - hh) / hh;
        uint16_t *row = s->px + (size_t)y * (size_t)s->w;
        for (int x = 0; x < s->w; x++) {
            float dx = (x - hw) / hw;
            float k = 1.f - strength * (dx * dx + dy * dy) * .5f;
            if (k >= .999f) continue;
            uint16_t p = row[x];
            int r = (p >> 11) & 31, g = (p >> 5) & 63, b = p & 31;
            row[x] = (uint16_t)((((int)(r * k)) << 11) | (((int)(g * k)) << 5) | (int)(b * k));
        }
    }
}

/* ---- font ---- */
static const char CHARSET[] = " ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789:.-!/<>+%,'()?";
static const uint8_t FONT[][7] = {
    {0, 0, 0, 0, 0, 0, 0},
    {0b01110, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001},  /* A */
    {0b11110, 0b10001, 0b10001, 0b11110, 0b10001, 0b10001, 0b11110},
    {0b01110, 0b10001, 0b10000, 0b10000, 0b10000, 0b10001, 0b01110},
    {0b11110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b11110},
    {0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b11111},
    {0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b10000},
    {0b01110, 0b10001, 0b10000, 0b10111, 0b10001, 0b10001, 0b01111},
    {0b10001, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001},
    {0b01110, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110},
    {0b00111, 0b00010, 0b00010, 0b00010, 0b00010, 0b10010, 0b01100},
    {0b10001, 0b10010, 0b10100, 0b11000, 0b10100, 0b10010, 0b10001},
    {0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b11111},
    {0b10001, 0b11011, 0b10101, 0b10101, 0b10001, 0b10001, 0b10001},
    {0b10001, 0b11001, 0b10101, 0b10011, 0b10001, 0b10001, 0b10001},
    {0b01110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110},
    {0b11110, 0b10001, 0b10001, 0b11110, 0b10000, 0b10000, 0b10000},
    {0b01110, 0b10001, 0b10001, 0b10001, 0b10101, 0b10010, 0b01101},
    {0b11110, 0b10001, 0b10001, 0b11110, 0b10100, 0b10010, 0b10001},
    {0b01111, 0b10000, 0b10000, 0b01110, 0b00001, 0b00001, 0b11110},
    {0b11111, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100},
    {0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110},
    {0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01010, 0b00100},
    {0b10001, 0b10001, 0b10001, 0b10101, 0b10101, 0b10101, 0b01010},
    {0b10001, 0b10001, 0b01010, 0b00100, 0b01010, 0b10001, 0b10001},
    {0b10001, 0b10001, 0b01010, 0b00100, 0b00100, 0b00100, 0b00100},
    {0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b10000, 0b11111},  /* Z */
    {0b01110, 0b10001, 0b10011, 0b10101, 0b11001, 0b10001, 0b01110},  /* 0 */
    {0b00100, 0b01100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110},
    {0b01110, 0b10001, 0b00001, 0b00010, 0b00100, 0b01000, 0b11111},
    {0b11110, 0b00001, 0b00001, 0b01110, 0b00001, 0b00001, 0b11110},
    {0b00010, 0b00110, 0b01010, 0b10010, 0b11111, 0b00010, 0b00010},
    {0b11111, 0b10000, 0b11110, 0b00001, 0b00001, 0b10001, 0b01110},
    {0b00110, 0b01000, 0b10000, 0b11110, 0b10001, 0b10001, 0b01110},
    {0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b01000, 0b01000},
    {0b01110, 0b10001, 0b10001, 0b01110, 0b10001, 0b10001, 0b01110},
    {0b01110, 0b10001, 0b10001, 0b01111, 0b00001, 0b00010, 0b01100},  /* 9 */
    {0b00000, 0b00100, 0b00100, 0b00000, 0b00100, 0b00100, 0b00000},  /* : */
    {0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b01100, 0b01100},  /* . */
    {0b00000, 0b00000, 0b00000, 0b11111, 0b00000, 0b00000, 0b00000},  /* - */
    {0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b00000, 0b00100},  /* ! */
    {0b00001, 0b00010, 0b00010, 0b00100, 0b01000, 0b01000, 0b10000},  /* / */
    {0b00010, 0b00100, 0b01000, 0b10000, 0b01000, 0b00100, 0b00010},  /* < */
    {0b01000, 0b00100, 0b00010, 0b00001, 0b00010, 0b00100, 0b01000},  /* > */
    {0b00000, 0b00100, 0b00100, 0b11111, 0b00100, 0b00100, 0b00000},  /* + */
    {0b11001, 0b11010, 0b00010, 0b00100, 0b01000, 0b01011, 0b10011},  /* % */
    {0b00000, 0b00000, 0b00000, 0b00000, 0b01100, 0b00100, 0b01000},  /* , */
    {0b00100, 0b00100, 0b01000, 0b00000, 0b00000, 0b00000, 0b00000},  /* ' */
    {0b00010, 0b00100, 0b01000, 0b01000, 0b01000, 0b00100, 0b00010},  /* ( */
    {0b01000, 0b00100, 0b00010, 0b00010, 0b00010, 0b00100, 0b01000},  /* ) */
    {0b01110, 0b10001, 0b00001, 0b00010, 0b00100, 0b00000, 0b00100},  /* ? */
};
_Static_assert(sizeof(FONT) / 7 == sizeof(CHARSET) - 1, "font table and charset differ");

static const uint8_t *glyph(char ch) {
    if (ch >= 'a' && ch <= 'z') ch = (char)(ch - 'a' + 'A');
    const char *p = strchr(CHARSET, ch);
    return FONT[p && ch ? (size_t)(p - CHARSET) : 0];
}

int gfx_text_w(const char *str, int scale) {
    int n = (int)strlen(str);
    return n ? (n * 6 - 1) * scale : 0;
}

void gfx_text(Surf *s, float x, float y, int scale, const char *str, uint32_t rgb, int a) {
    float sc = (float)scale;
    for (; *str; str++, x += 6.f * sc) {
        const uint8_t *g = glyph(*str);
        for (int row = 0; row < 7; row++) {
            int bits = g[row], col = 0;
            while (col < 5) {                              /* one rectangle per horizontal run of lit pixels */
                if (!(bits & (16 >> col))) { col++; continue; }
                int run = 1;
                while (col + run < 5 && (bits & (16 >> (col + run)))) run++;
                gfx_rrect(s, x + col * sc, y + row * sc, run * sc, sc, sc * .2f, rgb, a);
                col += run;
            }
        }
    }
}

void gfx_text_c(Surf *s, float cx, float y, int scale, const char *str, uint32_t rgb, int a) {
    gfx_text(s, cx - gfx_text_w(str, scale) * .5f, y, scale, str, rgb, a);
}

void gfx_text_r(Surf *s, float rx, float y, int scale, const char *str, uint32_t rgb, int a) {
    gfx_text(s, rx - (float)gfx_text_w(str, scale), y, scale, str, rgb, a);
}
