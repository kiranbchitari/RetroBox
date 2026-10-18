/* Character sprites: decode, pre-scale, alpha blit into the RGB565 frame. See sprite.h. */
#include "sprite.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>

typedef struct { uint32_t *px; int w, h; float ax, ay, ppt; } Img;     /* premultiplied 0xAARRGGBB; ppt = pixels per tile */

static Img *src;          /* as stored (SPR_CHARS[].ppt pixels per tile), one per frame and palette; kept for the title only */
static Img *play;         /* scaled to the game tile: area-averaged down, or bilinear up for art stored smaller */
static int nimg, base[SPR_NCHARS], ready;

static int char_of(int frame) {
    for (int c = 0; c < SPR_NCHARS; c++)
        if (frame >= SPR_CHARS[c].first && frame < SPR_CHARS[c].first + SPR_CHARS[c].count) return c;
    return -1;
}

/* The title draws the buddies big, idling and throwing: those frames keep their stored size too. */
static int title_frame(int frame) {
    const SprAnimDef *a = &SPR_ANIMS[SPR_BUDDY_IDLE], *b = &SPR_ANIMS[SPR_BUDDY_ATTACK];
    return (frame >= a->first && frame < a->first + a->count) || (frame >= b->first && frame < b->first + b->count);
}

static int img_index(int frame, int skin) {
    int c = char_of(frame);
    if (c < 0) return -1;
    const SprCharDef *d = &SPR_CHARS[c];
    int k = d->npal > 1 ? ((skin % d->npal) + d->npal) % d->npal : 0;
    return base[c] + k * d->count + (frame - d->first);
}

static int decode(Img *im, int frame, int pal, float ppt) {
    const SprFrameDef *f = &SPR_FRAMES[frame];
    im->w = f->w; im->h = f->h; im->ax = f->ax; im->ay = f->ay; im->ppt = ppt;
    im->px = (uint32_t *)calloc((size_t)f->w * f->h, sizeof(uint32_t));
    if (!im->px) return 0;
    const uint8_t (*p)[4] = SPR_PALS[pal];
    const uint8_t *d = SPR_DATA + f->off;
    for (int y = 0; y < f->h; y++) {
        uint32_t *row = im->px + (size_t)y * f->w;
        int x = 0;
        while (x < f->w) {
            x += *d++;
            int n = *d++;
            for (int i = 0; i < n && x < f->w; i++, x++) {
                const uint8_t *c = p[*d++];
                row[x] = ((uint32_t)c[3] << 24) | ((uint32_t)c[0] << 16) | ((uint32_t)c[1] << 8) | c[2];
            }
        }
    }
    return 1;
}

/* Area-averaging downscale by k <= 1 (each output pixel is the mean of the source area it covers). */
static int shrink(Img *dst, const Img *s, float k) {
    int dw = (int)ceilf((float)s->w * k), dh = (int)ceilf((float)s->h * k);
    if (dw < 1) dw = 1;
    if (dh < 1) dh = 1;
    float *tmp = (float *)calloc((size_t)dw * s->h * 4, sizeof(float));
    dst->px = (uint32_t *)calloc((size_t)dw * dh, sizeof(uint32_t));
    if (!tmp || !dst->px) { free(tmp); free(dst->px); dst->px = NULL; return 0; }
    dst->w = dw; dst->h = dh; dst->ax = s->ax * k; dst->ay = s->ay * k; dst->ppt = s->ppt * k;
    float inv = 1.f / k;
    for (int x = 0; x < dw; x++) {                   /* horizontal pass into tmp (dw x s->h) */
        float a0 = (float)x * inv, a1 = a0 + inv;
        for (int sx = (int)a0; sx < s->w && (float)sx < a1; sx++) {
            float wgt = fminf(a1, (float)sx + 1.f) - fmaxf(a0, (float)sx);
            if (wgt <= 0.f) continue;
            for (int y = 0; y < s->h; y++) {
                uint32_t c = s->px[(size_t)y * s->w + sx];
                float *t = tmp + ((size_t)y * dw + x) * 4;
                t[0] += wgt * (float)(c >> 24); t[1] += wgt * (float)((c >> 16) & 255);
                t[2] += wgt * (float)((c >> 8) & 255); t[3] += wgt * (float)(c & 255);
            }
        }
    }
    float norm = k * k;
    for (int y = 0; y < dh; y++) {                   /* vertical pass */
        float b0 = (float)y * inv, b1 = b0 + inv;
        for (int x = 0; x < dw; x++) {
            float acc[4] = {0, 0, 0, 0};
            for (int sy = (int)b0; sy < s->h && (float)sy < b1; sy++) {
                float wgt = fminf(b1, (float)sy + 1.f) - fmaxf(b0, (float)sy);
                if (wgt <= 0.f) continue;
                const float *t = tmp + ((size_t)sy * dw + x) * 4;
                for (int c = 0; c < 4; c++) acc[c] += wgt * t[c];
            }
            uint32_t out = 0;
            for (int c = 0; c < 4; c++) {
                int v = (int)(acc[c] * norm + .5f);
                if (v > 255) v = 255;
                out = (out << 8) | (uint32_t)v;
            }
            dst->px[(size_t)y * dw + x] = out;
        }
    }
    free(tmp);
    return 1;
}

static uint32_t sample(const Img *im, float u, float v);

/* Bilinear upscale by k >= 1, done once here so that drawing art stored smaller than the tile (the bosses) is a plain
 * 1:1 blit like the rest. */
static int grow(Img *dst, const Img *s, float k) {
    int dw = (int)ceilf((float)s->w * k), dh = (int)ceilf((float)s->h * k);
    dst->px = (uint32_t *)malloc((size_t)dw * dh * sizeof(uint32_t));
    if (!dst->px) return 0;
    dst->w = dw; dst->h = dh; dst->ax = s->ax * k; dst->ay = s->ay * k; dst->ppt = s->ppt * k;
    for (int y = 0; y < dh; y++)
        for (int x = 0; x < dw; x++)
            dst->px[(size_t)y * dw + x] = sample(s, ((float)x + .5f) / k - .5f, ((float)y + .5f) / k - .5f);
    return 1;
}

void spr_release(void) {
    for (int i = 0; i < nimg; i++) {
        if (src) free(src[i].px);
        if (play) free(play[i].px);
    }
    free(src); free(play);
    src = play = NULL; nimg = 0; ready = 0;
}

int spr_setup(int tile) {
    spr_release();
    int n = 0;
    for (int c = 0; c < SPR_NCHARS; c++) { base[c] = n; n += SPR_CHARS[c].count * SPR_CHARS[c].npal; }
    src = (Img *)calloc((size_t)n, sizeof(Img));
    play = (Img *)calloc((size_t)n, sizeof(Img));
    if (!src || !play) { spr_release(); return 0; }
    nimg = n;
    for (int c = 0; c < SPR_NCHARS; c++) {
        const SprCharDef *d = &SPR_CHARS[c];
        float k = (float)tile / d->ppt;
        for (int p = 0; p < d->npal; p++)
            for (int f = 0; f < d->count; f++) {
                int i = base[c] + p * d->count + f;
                if (!decode(&src[i], d->first + f, d->pal + p, d->ppt)) { spr_release(); return 0; }
                if (!(k < 1.f ? shrink(&play[i], &src[i], k) : grow(&play[i], &src[i], k))) { spr_release(); return 0; }
                if (!title_frame(d->first + f)) { free(src[i].px); src[i].px = NULL; }      /* only the title draws big */
            }
    }
    ready = 1;
    return 1;
}

int spr_ready(void) { return ready; }

int spr_count(int anim) { return anim >= 0 && anim < SPR_NANIMS ? SPR_ANIMS[anim].count : 0; }

int spr_frame(int anim, int i, int loop) {
    const SprAnimDef *a = &SPR_ANIMS[anim];
    if (i < 0) i = 0;
    i = loop ? i % a->count : (i < a->count ? i : a->count - 1);
    return a->first + i;
}

float spr_ppt(int frame) { int c = char_of(frame); return c >= 0 ? SPR_CHARS[c].ppt : 64.f; }

float spr_top(int frame) { return SPR_FRAMES[frame].ay / spr_ppt(frame); }

static uint32_t sample(const Img *im, float u, float v) {    /* bilinear, premultiplied; (u, v) in pixel centres */
    int x0 = (int)floorf(u), y0 = (int)floorf(v);
    float fx = u - (float)x0, fy = v - (float)y0;
    uint32_t c[4];
    for (int k = 0; k < 4; k++) {
        int x = x0 + (k & 1), y = y0 + (k >> 1);
        c[k] = (x >= 0 && y >= 0 && x < im->w && y < im->h) ? im->px[(size_t)y * im->w + x] : 0;
    }
    if (!(c[0] | c[1] | c[2] | c[3])) return 0;
    float w0 = (1 - fx) * (1 - fy), w1 = fx * (1 - fy), w2 = (1 - fx) * fy, w3 = fx * fy;
    uint32_t out = 0;
    for (int sh = 24; sh >= 0; sh -= 8) {
        float v2 = w0 * (float)((c[0] >> sh) & 255) + w1 * (float)((c[1] >> sh) & 255) + w2 * (float)((c[2] >> sh) & 255) + w3 * (float)((c[3] >> sh) & 255);
        out |= (uint32_t)(int)(v2 + .5f) << sh;
    }
    return out;
}

static void put(uint16_t *dst, uint32_t c, int alpha, int flash) {
    int a = (int)(c >> 24), r = (int)((c >> 16) & 255), g = (int)((c >> 8) & 255), b = (int)(c & 255);
    if (flash) { r += (a - r) * flash / 255; g += (a - g) * flash / 255; b += (a - b) * flash / 255; }
    if (alpha < 255) { a = a * alpha / 255; r = r * alpha / 255; g = g * alpha / 255; b = b * alpha / 255; }
    if (a <= 0) return;
    uint16_t d = *dst;
    int dr = (d >> 11) & 31, dg = (d >> 5) & 63, db = d & 31;
    dr = (dr << 3) | (dr >> 2); dg = (dg << 2) | (dg >> 4); db = (db << 3) | (db >> 2);
    int ia = 255 - a;
    int orr = r + (dr * ia + 127) / 255, og = g + (dg * ia + 127) / 255, ob = b + (db * ia + 127) / 255;
    if (orr > 255) orr = 255;
    if (og > 255) og = 255;
    if (ob > 255) ob = 255;
    *dst = (uint16_t)(((orr >> 3) << 11) | ((og >> 2) << 5) | (ob >> 3));
}

void spr_draw(Surf *s, int frame, float x, float y, const SprDraw *d) {
    if (!ready || frame < 0 || frame >= SPR_NFRAMES) return;
    int idx = img_index(frame, d->skin);
    if (idx < 0) return;
    float big = d->tile * fmaxf(d->sx, d->sy);
    const Img *im = big > play[idx].ppt * 1.1f && src[idx].px ? &src[idx] : &play[idx];     /* far bigger: the stored frame */
    float kx = d->tile / im->ppt * d->sx, ky = d->tile / im->ppt * d->sy;
    if (kx <= 0.f || ky <= 0.f) return;
    float ax = d->flip ? (float)im->w - im->ax : im->ax;
    if (fabsf(kx - 1.f) < .01f && fabsf(ky - 1.f) < .01f && d->lean == 0.f) {      /* 1:1, pixel aligned: crisp */
        int ox = (int)lroundf(x - ax), oy = (int)lroundf(y - im->ay);
        for (int j = 0; j < im->h; j++) {
            int Y = oy + j;
            if (Y < s->cy0 || Y >= s->cy1) continue;
            const uint32_t *row = im->px + (size_t)j * im->w;
            uint16_t *out = s->px + (size_t)Y * s->w;
            for (int i = 0; i < im->w; i++) {
                int X = ox + i;
                if (X < s->cx0 || X >= s->cx1) continue;
                uint32_t c = row[d->flip ? im->w - 1 - i : i];
                if (c) put(out + X, c, d->alpha, d->flash);
            }
        }
        return;
    }
    /* general case: inverse-map every destination pixel, bilinear */
    float top = im->ay * ky, bottom = ((float)im->h - im->ay) * ky;
    float left = ax * kx, right = ((float)im->w - ax) * kx;
    float leanMax = fabsf(d->lean) * top;
    int X0 = (int)floorf(x - left - leanMax) - 1, X1 = (int)ceilf(x + right + leanMax) + 1;
    int Y0 = (int)floorf(y - top) - 1, Y1 = (int)ceilf(y + bottom) + 1;
    if (X0 < s->cx0) X0 = s->cx0;
    if (Y0 < s->cy0) Y0 = s->cy0;
    if (X1 > s->cx1) X1 = s->cx1;
    if (Y1 > s->cy1) Y1 = s->cy1;
    float lean = d->flip ? -d->lean : d->lean;
    for (int Y = Y0; Y < Y1; Y++) {
        float dy = (float)Y + .5f - y;
        float v = im->ay + dy / ky - .5f;
        if (v < -1.f || v > (float)im->h) continue;
        float shift = lean * -dy;                       /* the top leans forward */
        uint16_t *out = s->px + (size_t)Y * s->w;
        for (int X = X0; X < X1; X++) {
            float dx = (float)X + .5f - x - shift;
            float u = dx / kx;
            u = (d->flip ? (float)im->w - ax - u : ax + u) - .5f;
            if (u < -1.f || u > (float)im->w) continue;
            uint32_t c = sample(im, u, v);
            if (c) put(out + X, c, d->alpha, d->flash);
        }
    }
}
