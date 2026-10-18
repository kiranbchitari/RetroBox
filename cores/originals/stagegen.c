/* Stage generator for Blizzard Buddies, see stagegen.h. */
#include "stagegen.h"
#include <string.h>
#ifdef SG_DEBUG
#include <stdio.h>
#endif

/* ---- random numbers ---- */
typedef struct { uint32_t s; } Rng;
static uint32_t rnd32(Rng *r) { uint32_t x = r->s; x ^= x << 13; x ^= x >> 17; x ^= x << 5; return r->s = x; }
static int rn(Rng *r, int n) { return n <= 1 ? 0 : (int)(rnd32(r) % (uint32_t)n); }
static int rr(Rng *r, int lo, int hi) { return hi <= lo ? lo : lo + rn(r, hi - lo + 1); }
static int pct(Rng *r, int p) { return rn(r, 100) < p; }
static uint32_t mix(uint32_t a, uint32_t b) {
    uint32_t h = a * 2654435761u ^ (b + 0x9E3779B9u + (a << 6) + (a >> 2));
    h ^= h >> 15; h *= 0x2C1B3C6Du; h ^= h >> 12; h *= 0x297A2D39u; h ^= h >> 15;
    return h ? h : 0x2545F491u;
}
static int imin(int a, int b) { return a < b ? a : b; }
static int imax(int a, int b) { return a > b ? a : b; }

/* ---- jump physics ---- */
float sg_reach(int dh) {
    float y = 0.f, vy = PH_JUMP_VY, x = 0.f, target = -(float)dh;
    for (int t = 0; t < 240; t++) {
        float py = y;
        vy += vy < 0.f ? PH_GUP : PH_GDN;
        if (vy > PH_VMAX) vy = PH_VMAX;
        y += vy; x += PH_VX;
        if (vy >= 0.f && py < target && y >= target) {
            float f = (target - py) / (y - py);
            return x - PH_VX * (1.f - f);
        }
    }
    return -1.f;
}

/* the widest gap (in empty columns) a jump up / down by dh rows may ask for: the player leaves the edge with the centre up to
 * half a body past it and lands up to half a body onto the next block (about .74 tile together); 'safe' keeps a margin. */
static int max_gap(int dh, float safe) {
    if (dh > 3) return -1;
    float rch = sg_reach(dh);
    if (rch <= 0.f) return -1;
    return (int)floorf(rch * safe + .74f);
}

/* ---- the builder ---- */
enum { SEG_RUN, SEG_GAP, SEG_UP, SEG_DOWN, SEG_STAIRS, SEG_LEDGES, SEG_CRUMBLE, SEG_MOVER, SEG_LIFT, SEG_SPRING, SEG_WIND,
       SEG_ICICLE, SEG_ENEMY, SEG_SPIKES, SEG_CRATES, SEG_NOOK, SEG_HILL, SEG_HIGHROAD, SEG_STONES, SEG_COUNT };

/*                                    RUN GAP UP DOWN STAIRS LEDGES CRUMBLE MOVER LIFT SPRING WIND ICICLE ENEMY SPIKES CRATES NOOK HILL HIGH STONES */
static const uint8_t WEIGHTS[10][SEG_COUNT] = {
    /* 1 snowy forest  */ {1, 3, 2, 2, 2, 0, 0, 0, 0, 0, 0, 0, 4, 1, 0, 1, 3, 2, 0},
    /* 2 ice cave      */ {1, 2, 1, 1, 1, 3, 0, 0, 0, 2, 0, 10, 3, 3, 0, 1, 2, 2, 3},
    /* 3 cliffs        */ {1, 2, 2, 2, 1, 3, 6, 0, 0, 2, 8, 0, 3, 3, 0, 1, 2, 2, 3},
    /* 4 frozen castle */ {1, 2, 1, 1, 1, 3, 4, 7, 5, 2, 0, 3, 3, 3, 0, 1, 2, 2, 3},
    /* 5 lava cavern   */ {1, 2, 1, 1, 0, 3, 10, 4, 0, 2, 0, 0, 3, 3, 0, 1, 1, 2, 8},
    /* 6 windy heights */ {1, 1, 1, 1, 0, 3, 3, 9, 6, 2, 9, 0, 3, 2, 0, 1, 1, 3, 3},
    /* 7 ancient ruins */ {1, 2, 1, 1, 1, 3, 4, 4, 4, 2, 3, 3, 3, 5, 0, 1, 2, 2, 3},
    /* 8 mine          */ {1, 2, 1, 1, 1, 2, 3, 4, 5, 2, 0, 3, 3, 5, 9, 1, 2, 2, 3},
    /* 9 crystal peak  */ {1, 1, 1, 1, 0, 3, 6, 5, 4, 2, 4, 6, 4, 5, 0, 1, 1, 3, 8},
    /* 10 final castle */ {1, 1, 1, 1, 0, 3, 5, 5, 4, 2, 3, 4, 4, 5, 2, 1, 1, 3, 5},
};
/* scenery by stage: SGK_DECO args */
static const uint8_t DECO_SET[10][3] = {{0, 0, 6}, {4, 6, 6}, {0, 6, 6}, {6, 6, 1}, {6, 6, 6}, {0, 6, 6}, {6, 6, 1}, {6, 1, 6}, {4, 4, 6}, {6, 6, 4}};
static const uint8_t ICY_STAGE[10] = {0, 40, 25, 45, 0, 20, 0, 0, 45, 30};     /* % of ground blocks that are slippery ice */

#define MIN_ROW 9                                  /* ground tops stay between these rows */
#define MAX_ROW 17

typedef struct {
    SgStage *o; Rng r;
    int stage, diff, lava, crates;
    int W, H, x, row, lastLen;
    int cpDone, nooks, ncoin, nenemy, lastSeg, lastSeg2;
    int block0, blockLen;                          /* the block just built: first column and length (for decoration) */
    int noIce;                                     /* wind and ice do not mix: the ground built now is not slippery */
    int limit;                                     /* segments must end before this column (the boss stage keeps its last columns for the arena) */
} G;

static int at(const G *g, int c, int r) { return (c >= 0 && c < g->W && r >= 0 && r < g->H) ? g->o->tile[r][c] : 1; }
static void put(G *g, int c, int r, int k) { if (c >= 0 && c < g->W && r >= 0 && r < g->H) g->o->tile[r][c] = (uint8_t)k; }
static void thing(G *g, int kind, int x, int y, int arg, int patrol) {
    if (g->o->nthings >= SG_MAXTH || x < 0 || x >= g->W || y < 0 || y >= g->H) return;
    if ((kind == SGK_COIN || kind == SGK_GEM) && at(g, x, y)) return;                 /* not inside a wall or a ledge */
    SgThing *t = &g->o->things[g->o->nthings++];
    t->kind = (uint8_t)kind; t->x = (uint8_t)x; t->y = (uint8_t)y; t->arg = (uint8_t)arg; t->patrol = (uint8_t)patrol;
}
static int thing_near(const G *g, int x, int kinds_mask, int dist) {      /* is there a thing of those kinds within dist columns? */
    for (int i = 0; i < g->o->nthings; i++) {
        const SgThing *t = &g->o->things[i];
        if (((1 << t->kind) & kinds_mask) && t->x >= x - dist && t->x <= x + dist) return 1;
    }
    return 0;
}
#define KM(k) (1 << (k))
#define SOLID_THINGS (KM(SGK_SPIKES) | KM(SGK_SPRING) | KM(SGK_ENEMY) | KM(SGK_ICICLE) | KM(SGK_DECO) | KM(SGK_LAMP) | KM(SGK_CRATE))

static void ground(G *g, int x0, int x1, int row) {
    int ice = pct(&g->r, ICY_STAGE[g->stage - 1]) && !g->noIce && x1 - x0 >= 8;                 /* slippery ground only where there is room to stop */
    for (int c = x0; c <= x1; c++) for (int r = row; r < g->H; r++) put(g, c, r, ice ? 4 : 1);
}
static void ledge(G *g, int x0, int x1, int row, int kind) { for (int c = x0; c <= x1; c++) put(g, c, row, kind); }
static void pit_spikes(G *g, int x0, int x1) {
    if (x1 - x0 >= 1) thing(g, SGK_PITSPIKES, (x0 + x1) / 2, g->H - 1, 0, 0);
}

/* A block of ground after a gap of 'gap' empty columns, dh rows higher than the current one (negative: lower), len wide. */
static int block(G *g, int gap, int dh, int len) {
    int x0 = g->x + gap, row = g->row - dh;
    if (x0 >= g->W) return -1;
    if (x0 + len > g->W) len = g->W - x0;
    if (gap >= 2) pit_spikes(g, g->x, x0 - 1);
    ground(g, x0, x0 + len - 1, row);
    g->block0 = x0; g->blockLen = len;
    g->lastLen = (gap == 0 && dh == 0) ? g->lastLen + len : len;
    g->x = x0 + len; g->row = row;
    return x0;
}

/* ---- things that dress a block ---- */
static int diff_pct(const G *g, int base) { return base + (g->diff - 1) * 8; }

static int enemy_type(G *g) {
    static const int unlock[7] = {1, 1, 3, 99, 4, 6, 99};                        /* by monster type: walker hopper flyer - spitter charger */
    static const int pick[8] = {0, 0, 1, 2, 4, 5, 0, 1};
    for (int tries = 0; tries < 12; tries++) {
        int t = pick[rn(&g->r, 8)];
        if (g->stage >= unlock[t]) return t;
    }
    return 0;
}

static void enemies_on(G *g, int x0, int x1, int row, int n) {
    for (int i = 0; i < n && g->nenemy < 60; i++) {
        int t = enemy_type(g);
        int lo = x0 + 2, hi = x1 - 2;
        if (hi < lo) return;
        int x = rr(&g->r, lo, hi);
        if (x < 10 || thing_near(g, x, KM(SGK_ENEMY) | KM(SGK_SPIKES) | KM(SGK_SPRING), 2)) continue;
        int patrol = imin((x1 - x0) / 2 - 1, 5); if (patrol < 1) patrol = 1;
        if (t == 2) { int fy = row - rr(&g->r, 3, 5); if (at(g, x, fy)) continue; thing(g, SGK_ENEMY, x, fy, 2, 0); }          /* flyers hover (y = their height) */
        else thing(g, SGK_ENEMY, x, row, t, patrol);
        g->nenemy++;
    }
}

static void spikes_on(G *g, int x0, int x1, int row, int n) {
    for (int i = 0; i < n; i++) {
        int lo = x0 + 3, hi = x1 - 3;
        if (hi < lo) return;
        int x = rr(&g->r, lo, hi);
        if (x < 12 || thing_near(g, x, SOLID_THINGS, 3)) continue;
        thing(g, SGK_SPIKES, x, row, 0, 0);
        if (g->ncoin < 90) {                                                    /* a coin arc over each, to show the jump */
            thing(g, SGK_COIN, x - 1, row - 2, 0, 0); thing(g, SGK_COIN, x, row - 3, 0, 0); thing(g, SGK_COIN, x + 1, row - 2, 0, 0);
            g->ncoin += 3;
        }
    }
}

static void coins_on(G *g, int x0, int x1, int row) {
    if (g->ncoin > 84 || x1 - x0 < 4) return;
    int n = rr(&g->r, 3, 6), x = rr(&g->r, x0 + 1, imax(x0 + 1, x1 - n)), y = row - (pct(&g->r, 30) ? 3 : 1);
    n = imin(n, x1 - x);
    for (int i = 0; i < n; i++) thing(g, SGK_COIN, x + i, y, 0, 0);
    g->ncoin += n;
}

static void deco_on(G *g, int x0, int x1, int row) {
    if (x1 - x0 < 4) return;
    int x = rr(&g->r, x0 + 1, x1 - 1);
    if (thing_near(g, x, SOLID_THINGS, 1) || at(g, x, row - 1) || at(g, x, row - 2)) return;
    thing(g, SGK_DECO, x, row, DECO_SET[g->stage - 1][rn(&g->r, 3)], 0);
}

/* the usual dressing of a stretch of ground */
static void dress(G *g, int x0, int x1, int row, int calm) {
    int len = x1 - x0 + 1;
    if (len < 5) return;
    if (!calm) {
        if (len >= 6 && pct(&g->r, imin(96, diff_pct(g, 62 + 4 * g->stage)))) enemies_on(g, x0, x1, row, 1 + (len >= 8 && pct(&g->r, 55)) + (g->stage >= 4 && len >= 10 && pct(&g->r, 35)) + (g->stage >= 7 && len >= 12 && pct(&g->r, 30)));
        if (len >= 8 && pct(&g->r, 18 + 5 * g->stage)) spikes_on(g, x0, x1, row, len >= 12 ? 2 : 1);
    }
    if (pct(&g->r, 55)) coins_on(g, x0, x1, row);
    if (pct(&g->r, 45)) deco_on(g, x0, x1, row);
}

/* ---- segments (each starts at g->x / g->row and leaves them at the end of what it built) ---- */
static void run_plain(G *g, int len) {
    int x0 = g->x; ground(g, x0, imin(g->W - 1, x0 + len - 1), g->row);
    g->x = imin(g->W, x0 + len); g->lastLen += g->x - x0;
    g->block0 = x0; g->blockLen = g->x - x0;
}

static int seg_run(G *g, int len) {
    int x0 = g->x; ground(g, x0, imin(g->W - 1, x0 + len - 1), g->row);
    g->x = imin(g->W, x0 + len); g->lastLen += g->x - x0;
    g->block0 = x0; g->blockLen = g->x - x0;
    dress(g, x0, g->x - 1, g->row, 0);
    return 1;
}

static void flyer_over(G *g, int x0, int x1, int row) {            /* a flying monster over a pit, from stage 3 on */
    if (g->stage < 3 || g->nenemy >= 60 || !pct(&g->r, diff_pct(g, 30)) || x1 - x0 < 3) return;
    int fy = row - rr(&g->r, 4, 6);
    if (at(g, (x0 + x1) / 2, fy)) return;
    thing(g, SGK_ENEMY, (x0 + x1) / 2, fy, 2, 0);
    g->nenemy++;
}

static int seg_gap(G *g) {
    int dh = rr(&g->r, -2, 1);
    if (g->row - dh < MIN_ROW || g->row - dh > MAX_ROW) dh = 0;
    int mg = max_gap(dh, .75f); if (mg < 1) return 0;
    int gap = rr(&g->r, imin(g->stage >= 2 ? 2 : 1, mg), mg);
    int px = g->x, row = g->row;
    if (g->lastLen < 4) return 0;
    int x0 = block(g, gap, dh, rr(&g->r, 4, 7));
    if (x0 < 0) return 0;
    flyer_over(g, px, px + gap, row);
    if (g->ncoin < 88) {                                                        /* coins in an arc over the gap */
        int cy = imin(row, g->row) - 2;
        for (int i = 0; i <= gap; i++) thing(g, SGK_COIN, px + i, cy - (i == 0 || i == gap ? 0 : 1), 0, 0);
        g->ncoin += gap + 1;
    }
    dress(g, x0, g->x - 1, g->row, 0);
    return 1;
}

static int seg_up(G *g) {
    int dh = rr(&g->r, 1, 3);
    if (g->row - dh < MIN_ROW) return 0;
    int mg = max_gap(dh, .75f);
    int x0 = block(g, rr(&g->r, 0, imin(1, mg)), dh, rr(&g->r, 4, 7));
    if (x0 < 0) return 0;
    dress(g, x0, g->x - 1, g->row, 0);
    return 1;
}

static int seg_down(G *g) {
    int dh = -rr(&g->r, 1, 4);
    if (g->row - dh > MAX_ROW) dh = -(MAX_ROW - g->row);
    if (dh == 0) return 0;
    int x0 = block(g, rr(&g->r, 0, 2), dh, rr(&g->r, 4, 7));
    if (x0 < 0) return 0;
    dress(g, x0, g->x - 1, g->row, 0);
    return 1;
}

static int seg_stairs(G *g) {
    int up = g->row > 13 ? (pct(&g->r, 65)) : 0;
    int n = rr(&g->r, 3, 5);
    for (int i = 0; i < n; i++) {
        int dh = up ? 1 : -1;
        if (g->row - dh < MIN_ROW || g->row - dh > MAX_ROW) break;
        if (block(g, 0, dh, rr(&g->r, 2, 3)) < 0) break;
    }
    int x0 = g->x; seg_run(g, rr(&g->r, 4, 6));
    return g->x > x0;
}

/* a chain of floating ledges across a pit; 'kind' 2 / 3 one-way snow / ice, 6 crumbling */
static int seg_ledges(G *g, int kind) {
    if (g->lastLen < 4) return 0;
    int span = rr(&g->r, 7, 11), start = g->x, row = g->row, ax = g->x;
    float safe = kind == SGT_CRUMBLE ? .65f : .72f;
    int lrow = row, px = ax;
    int prev_len = g->lastLen;
    while (px - start < span) {
        int dh = kind == SGT_CRUMBLE ? rr(&g->r, -1, 1) : rr(&g->r, -1, 2);
        if (lrow - dh < 7 || lrow - dh > 16) dh = 0;
        if (prev_len < 2) prev_len = 2;
        int mg = max_gap(dh, safe); if (mg < 1) { dh = 0; mg = max_gap(0, safe); }
        int gp = rr(&g->r, imax(1, mg - 1), mg);
        int len = kind == SGT_CRUMBLE ? rr(&g->r, 2, 3) : rr(&g->r, 3, 4);
        int x0 = px + gp;
        if (x0 + len + 8 > g->limit) break;
        int k = kind == 2 && pct(&g->r, ICY_STAGE[g->stage - 1]) ? 3 : kind;
        lrow -= dh;
        ledge(g, x0, x0 + len - 1, lrow, k);
        if (g->ncoin < 88) { for (int i = 0; i < len; i++) thing(g, SGK_COIN, x0 + i, lrow - 2, 0, 0); g->ncoin += len; }
        px = x0 + len; prev_len = len;
    }
    /* land on solid ground again: ledge row -> block row within rules */
    int dh = 0;
    for (int tries = 0; tries < 8; tries++) {
        dh = rr(&g->r, -2, 2);
        int nrow = lrow - dh;
        if (nrow >= MIN_ROW && nrow <= MAX_ROW && max_gap(dh, safe) >= 1) break;
        dh = 99;
    }
    if (dh == 99) { dh = lrow > MAX_ROW ? lrow - MAX_ROW : (lrow < MIN_ROW ? lrow - MIN_ROW : 0); if (max_gap(dh, safe) < 1) dh = 0; }
    int nrow = lrow - dh;
    if (nrow < MIN_ROW) { nrow = MIN_ROW; dh = lrow - nrow; }
    if (nrow > MAX_ROW) { nrow = MAX_ROW; dh = lrow - nrow; }
    int mg = max_gap(dh, safe);
    if (mg < 1) return 1;
    int gp = rr(&g->r, imax(1, mg - 1), mg);
    pit_spikes(g, start, px + gp - 1);
    flyer_over(g, start, px + gp - 1, row);
    g->x = px; g->row = lrow;                                                   /* block() measures from the last ledge */
    int x0 = block(g, gp, dh, rr(&g->r, 4, 6));
    if (x0 >= 0) dress(g, x0, g->x - 1, g->row, 0);
    return 1;
}


static int add_mover(G *g, float cx, float cy, float ax, float ay, int period, int w) {
    SgStage *o = g->o;
    if (o->nmovers >= SG_MAXMOVER) return 0;
    SgMover *m = &o->movers[o->nmovers++];
    m->cx = cx; m->cy = cy; m->ax = ax; m->ay = ay; m->period = period; m->phase = rn(&g->r, period); m->w = w;
    return 1;
}

/* a pit crossed by a plate that slides from one edge to the other */
static int seg_mover(G *g) {
    if (g->lastLen < 4 || g->o->nmovers >= SG_MAXMOVER) return 0;
    int span = rr(&g->r, 8, 11), px = g->x, row = g->row;
    if (px + span + 8 > g->limit) return 0;
    pit_spikes(g, px, px + span - 1);
    flyer_over(g, px, px + span - 1, row);
    int x0 = px + span;
    if (g->ncoin < 88) { thing(g, SGK_COIN, px + span / 2 - 1, row - 2, 0, 0); thing(g, SGK_COIN, px + span / 2 + 1, row - 2, 0, 0); g->ncoin += 2; }
    add_mover(g, ((float)px + 1.5f + (float)x0 - 1.5f) * .5f, (float)row, ((float)x0 - 1.5f - ((float)px + 1.5f)) * .5f, 0.f, rr(&g->r, 300, 380), 3);
    int b = block(g, span, 0, rr(&g->r, 4, 6));
    if (b >= 0) dress(g, b, g->x - 1, g->row, 0);
    return 1;
}

/* a shaft three columns wide: a lift goes up the wall between a low block and a high one */
static int seg_lift(G *g) {
    int dh = rr(&g->r, 5, 8);
    if (g->lastLen < 4 || g->o->nmovers >= SG_MAXMOVER || g->row - dh < MIN_ROW - 1 || g->row > MAX_ROW - 0 || g->x + 3 + 8 > g->limit) return 0;
    int px = g->x, rowA = g->row, rowB = rowA - dh;
    for (int c = px; c < px + 3; c++) for (int r = rowA + 1; r < g->H; r++) put(g, c, r, 1);       /* the shaft's floor, a row below the low block */
    add_mover(g, (float)px + 1.5f, ((float)rowA + (float)rowB) * .5f, 0.f, ((float)rowA - (float)rowB) * .5f, rr(&g->r, 420, 520), 3);
    int b = block(g, 3, dh, rr(&g->r, 4, 6));
    if (b >= 0) dress(g, b, g->x - 1, g->row, 0);
    return 1;
}

/* a spring on the edge of a block throws you up onto a high one */
static int seg_spring(G *g) {
    int dh = rr(&g->r, 4, 5);
    if (g->lastLen < 5 || g->row - dh < MIN_ROW - 1 || g->x + 8 > g->limit) return 0;
    thing(g, SGK_SPRING, g->x - 2, g->row, 0, 0);
    thing(g, SGK_COIN, g->x - 1, g->row - 3, 0, 0); thing(g, SGK_COIN, g->x - 1, g->row - 5, 0, 0); g->ncoin += 2;
    int x0 = block(g, rr(&g->r, 0, 1), dh, rr(&g->r, 4, 6));
    if (x0 >= 0) dress(g, x0, g->x - 1, g->row, 0);
    return 1;
}

/* a long flat run where the wind blows in gusts */
static int seg_wind(G *g) {
    int len = rr(&g->r, 10, 13), x0 = g->x;
    if (x0 + len + 6 > g->limit) return 0;
    g->noIce = 1;
    run_plain(g, len);
    g->noIce = 0;
    thing(g, SGK_WIND, x0 + 1, 0, len - 2, pct(&g->r, 50));
    dress(g, x0, g->x - 1, g->row, 0);
    return 1;
}

/* icicles hang from small ice ledges and fall when you walk under them */
static int seg_icicle(G *g) {
    int len = rr(&g->r, 10, 13), x0 = g->x, row = g->row;
    if (x0 + len + 6 > g->limit || row - 7 < 1) return 0;
    run_plain(g, len);
    int n = rr(&g->r, 2, 4), at_x = x0 + 3;
    for (int i = 0; i < n && at_x < x0 + len - 3; i++) {
        int x = at_x + rn(&g->r, 2);
        if (thing_near(g, x, KM(SGK_SPRING) | KM(SGK_ICICLE), 3)) { at_x += 3; continue; }
        for (int c = x - 1; c <= x + 1; c++) put(g, c, row - 7, 1);
        thing(g, SGK_ICICLE, x, row - 6, 0, 0);
        at_x = x + rr(&g->r, 4, 5);
    }
    dress(g, x0, g->x - 1, row, 1);
    return 1;
}

/* a broad run with a raised ledge and several monsters */
static int seg_enemy(G *g) {
    int len = rr(&g->r, 10, 13), x0 = g->x, row = g->row;
    if (x0 + len + 6 > g->limit) return 0;
    run_plain(g, len);
    int lx = x0 + rr(&g->r, 3, len - 8);
    ledge(g, lx, lx + 4, row - 3, 2);
    enemies_on(g, x0, x0 + len - 1, row, rr(&g->r, 3, 4) + (g->diff > 1) + (g->stage > 5));
    enemies_on(g, lx, lx + 4, row - 3, 1);
    dress(g, x0, x0 + len - 1, row, 1);
    return 1;
}

static int seg_spikes(G *g) {
    int len = rr(&g->r, 10, 13), x0 = g->x, row = g->row;
    if (x0 + len + 6 > g->limit) return 0;
    run_plain(g, len);
    spikes_on(g, x0, x0 + len - 1, row, 2 + (g->stage > 4));
    dress(g, x0, x0 + len - 1, row, 1);
    return 1;
}

/* stacks of crates to climb over */
static int seg_crates(G *g) {
    int len = rr(&g->r, 10, 13), x0 = g->x, row = g->row;
    if (x0 + len + 6 > g->limit) return 0;
    run_plain(g, len);
    int n = rr(&g->r, 2, 3), x = x0 + 4;
    for (int i = 0; i < n && x < x0 + len - 4; i++) {
        if (thing_near(g, x, SOLID_THINGS, 2)) { x += 2; continue; }
        int h = rr(&g->r, 1, 2);
        for (int k = 1; k <= h; k++) put(g, x, row - k, 5);
        if (pct(&g->r, 50)) for (int k = 1; k <= h; k++) put(g, x + 1, row - k, 5);
        x += rr(&g->r, 5, 6);
    }
    dress(g, x0, x0 + len - 1, row, 1);
    return 1;
}

/* a hidden nook above a long run: a ledge with the treasure chest, a second one higher with a gem */
static int seg_nook(G *g) {
    int len = rr(&g->r, 11, 12), x0 = g->x, row = g->row;
    if (row - 7 < 1 || x0 + len + 6 > g->limit) return 0;
    run_plain(g, len);
    int c = x0 + rr(&g->r, 1, len - 8);
    int k1 = pct(&g->r, ICY_STAGE[g->stage - 1]) ? 3 : 2;
    ledge(g, c, c + 2, row - 3, k1);
    ledge(g, c + 4, c + 6, row - 6, k1);                                         /* a gap of one between the ledges */
    thing(g, SGK_DECO, c + 1, row - 3, 5, 0);                                    /* the chest */
    thing(g, SGK_GEM, c + 5, row - 7, 0, 0);
    for (int i = 0; i < 3; i++) thing(g, SGK_COIN, c + i, row - 5, 0, 0);
    g->ncoin += 3; g->nooks++;
    dress(g, x0, x0 + len - 1, row, 1);
    return 1;
}

/* a rest: a plain stretch of ground, with the checkpoint when it is time */
static void checkpoint(G *g) {
    int len = 9, x0 = g->x;
    ground(g, x0, imin(g->W - 1, x0 + len - 1), g->row);
    g->x = imin(g->W, x0 + len); g->lastLen += len;
    SgStage *o = g->o;
    o->cpX = x0 + 4; o->cpY = g->row;
    thing(g, SGK_LAMP, o->cpX + 3, g->row, 0, 0);
    g->cpDone = 1;
}

/* a raised plateau with a monster on it, then back down */
static int seg_hill(G *g) {
    int dh = rr(&g->r, 2, 3);
    if (g->row - dh < MIN_ROW || g->lastLen < 3) return 0;
    int mg = max_gap(dh, .75f);
    int x0 = block(g, rr(&g->r, 0, imin(1, mg)), dh, rr(&g->r, 4, 6));
    if (x0 < 0) return 0;
    dress(g, x0, g->x - 1, g->row, 0);
    int x1 = block(g, rr(&g->r, 0, 2), -dh, rr(&g->r, 4, 7));
    if (x1 >= 0) dress(g, x1, g->x - 1, g->row, 0);
    return 1;
}

/* a high road: a path of ledges over a long run, for coins and a way past what is on the ground */
static int seg_highroad(G *g) {
    int len = rr(&g->r, 12, 14), x0 = g->x, row = g->row;
    if (row - 8 < 2 || x0 + len + 6 > g->limit) return 0;
    run_plain(g, len);
    int lrow = row - 4, px = x0 + 2, n = rr(&g->r, 3, 4);
    for (int i = 0; i < n; i++) {
        int dh = i ? rr(&g->r, -1, 1) : 0, lenL = rr(&g->r, 3, 4);
        if (lrow - dh < row - 7 || lrow - dh > row - 3) dh = 0;
        int mg = max_gap(dh, .72f), gp = i ? rr(&g->r, 1, imax(1, mg - 1)) : rr(&g->r, 0, 2);
        int a = px + gp;
        if (a + lenL + 3 > x0 + len) break;
        lrow -= dh;
        ledge(g, a, a + lenL - 1, lrow, pct(&g->r, ICY_STAGE[g->stage - 1]) ? 3 : 2);
        if (g->ncoin < 88) { for (int c = 0; c < lenL; c++) thing(g, SGK_COIN, a + c, lrow - 2, 0, 0); g->ncoin += lenL; }
        if (i == 1 && pct(&g->r, 60)) enemies_on(g, a, a + lenL - 1, lrow, 1);
        px = a + lenL;
    }
    dress(g, x0, x0 + len - 1, row, 0);
    return 1;
}

/* pillars standing in a pit */
static int seg_stones(G *g) {
    if (g->lastLen < 4 || g->stage < 2) return 0;
    int span = rr(&g->r, 7, 10), start = g->x, px = g->x, prow = g->row, n = 0;
    while (px - start < span && n < 6) {
        int dh = rr(&g->r, -1, 2);
        if (prow - dh < 12 || prow - dh > MAX_ROW) dh = 0;
        int mg = max_gap(dh, .7f); if (mg < 1) { dh = 0; mg = max_gap(0, .7f); }
        int gp = rr(&g->r, imax(1, mg - 1), mg), len = g->stage <= 3 ? rr(&g->r, 3, 4) : rr(&g->r, 2, 3), x0 = px + gp;
        if (x0 + len + 8 > g->limit) break;
        prow -= dh;
        ground(g, x0, x0 + len - 1, prow);
        if (g->ncoin < 88) { thing(g, SGK_COIN, x0, prow - 2, 0, 0); g->ncoin++; }
        px = x0 + len; n++;
    }
    pit_spikes(g, start, px);
    flyer_over(g, start, px, g->row);
    int dh = 0;
    for (int tries = 0; tries < 6; tries++) { dh = rr(&g->r, -2, 2); if (prow - dh >= MIN_ROW && prow - dh <= MAX_ROW) break; dh = 0; }
    int mg = max_gap(dh, .7f); if (mg < 1) { dh = 0; mg = max_gap(0, .7f); }
    g->x = px; g->row = prow; g->lastLen = 2;
    int x0 = block(g, rr(&g->r, imax(1, mg - 1), mg), dh, rr(&g->r, 4, 6));
    if (x0 >= 0) dress(g, x0, g->x - 1, g->row, 0);
    return 1;
}

static int do_segment(G *g, int s) {
    switch (s) {
    case SEG_RUN: return seg_run(g, rr(&g->r, 5, 9));
    case SEG_GAP: return seg_gap(g);
    case SEG_UP: return seg_up(g);
    case SEG_DOWN: return seg_down(g);
    case SEG_STAIRS: return seg_stairs(g);
    case SEG_LEDGES: return seg_ledges(g, 2);
    case SEG_CRUMBLE: return seg_ledges(g, SGT_CRUMBLE);
    case SEG_MOVER: return seg_mover(g);
    case SEG_LIFT: return seg_lift(g);
    case SEG_SPRING: return seg_spring(g);
    case SEG_WIND: return seg_wind(g);
    case SEG_ICICLE: return seg_icicle(g);
    case SEG_ENEMY: return seg_enemy(g);
    case SEG_SPIKES: return seg_spikes(g);
    case SEG_CRATES: return g->crates ? seg_crates(g) : 0;
    case SEG_NOOK: return seg_nook(g);
    case SEG_HILL: return seg_hill(g);
    case SEG_HIGHROAD: return seg_highroad(g);
    case SEG_STONES: return seg_stones(g);
    }
    return 0;
}

static int pick_segment(G *g, int usedIntense) {
    int w[SEG_COUNT], total = 0;
    for (int s = 0; s < SEG_COUNT; s++) {
        w[s] = WEIGHTS[g->stage - 1][s];
        if (s == g->lastSeg || s == g->lastSeg2) w[s] = w[s] > 1 ? w[s] / 2 : 0;       /* not the same thing again and again */
        if (g->row <= 11 && (s == SEG_UP || s == SEG_SPRING || s == SEG_LIFT || s == SEG_HILL)) w[s] = 0;
        if (g->row <= 11 && (s == SEG_DOWN || s == SEG_STAIRS)) w[s] *= 3;
        if (g->row >= 16 && s == SEG_DOWN) w[s] = 0;
        if (g->row >= 16 && s == SEG_UP) w[s] *= 2;
        if (usedIntense && (s == SEG_LEDGES || s == SEG_CRUMBLE || s == SEG_MOVER || s == SEG_LIFT || s == SEG_STONES)) w[s] = 0;   /* a breather after hard jumps */
        if (s == SEG_NOOK && g->nooks >= (g->stage >= 6 ? 2 : 1) + (g->x * 100 / g->W > 60)) w[s] = 0;
        if (s == SEG_CRATES && !g->crates) w[s] = 0;
        total += w[s];
    }
    if (!total) return SEG_RUN;
    int k = rn(&g->r, total);
    for (int s = 0; s < SEG_COUNT; s++) { if (k < w[s]) return s; k -= w[s]; }
    return SEG_RUN;
}

static void descend_to(G *g, int row) {                        /* stairs down to the given row */
    while (g->row < row) {
        int dh = -imin(2, row - g->row);
        if (block(g, 0, dh, 4) < 0) return;
    }
    while (g->row > row) {
        int dh = imin(2, g->row - row);
        if (block(g, 0, dh, 4) < 0) return;
    }
}

/* monsters, spikes and icicles too close to where the player begins, saves or leaves are not fair */
static void clear_around(G *g, int x, int dist) {
    SgStage *o = g->o; int n = 0;
    for (int i = 0; i < o->nthings; i++) {
        SgThing t = o->things[i];
        int hazard = t.kind == SGK_ENEMY || t.kind == SGK_SPIKES || t.kind == SGK_ICICLE;
        if (hazard && t.x >= x - dist && t.x <= x + dist) continue;
        o->things[n++] = t;
    }
    o->nthings = n;
}

/* Flat standing places: columns whose ground is level for 'half' columns each side with open air above. */
static int flat_spot(const G *g, int x, int half, int *row) {
    for (int r = 2; r < g->H; r++) {
        if (!g->o->tile[r][x]) continue;
        if (g->o->tile[r][x] != 1 && g->o->tile[r][x] != 4) return 0;
        for (int c = x - half; c <= x + half; c++) {
            if (c < 0 || c >= g->W) return 0;
            int k = g->o->tile[r][c];
            if ((k != 1 && k != 4) || g->o->tile[r - 1][c] || g->o->tile[r - 2][c]) return 0;
        }
        *row = r; return 1;
    }
    return 0;
}

/* However the segments fell, a stage has the monsters and spikes its number calls for. */
static void top_up(G *g, int last) {
    int want_e = 5 + 2 * g->stage + (g->diff - 1) * 2, want_s = (g->stage + 1) / 2 + (g->diff > 1);
    int ne = 0, ns = 0;
    for (int i = 0; i < g->o->nthings; i++) { if (g->o->things[i].kind == SGK_ENEMY) ne++; if (g->o->things[i].kind == SGK_SPIKES) ns++; }
    for (int tries = 0; tries < 400 && (ne < want_e || ns < want_s) && g->nenemy < 60; tries++) {
        int x = rr(&g->r, 14, last - 3), row;
        int spikeTurn = ns < want_s && (ne >= want_e || tries % 3 == 0);
        if (!spikeTurn && ne < want_e && flat_spot(g, x, 1, &row) && !thing_near(g, x, SOLID_THINGS, 2)) {
            int t = enemy_type(g);
            int fy = row - rr(&g->r, 3, 5);
            if (t == 2 && g->stage >= 3) { if (at(g, x, fy)) continue; thing(g, SGK_ENEMY, x, fy, 2, 0); }
            else if (t == 2) thing(g, SGK_ENEMY, x, row, 0, 2);
            else thing(g, SGK_ENEMY, x, row, t, 2);
            ne++; g->nenemy++;
        } else if (ns < want_s && flat_spot(g, x, 3, &row) && !thing_near(g, x, SOLID_THINGS, 3)) {
            thing(g, SGK_SPIKES, x, row, 0, 0); ns++;
        }
    }
}

/* a nook where it still fits, for a stage whose segments left no room for one */
static void force_nook(G *g, int last) {
    for (int tries = 0; tries < 1200 && !g->nooks; tries++) {
        int x = rr(&g->r, 12, last - 7), row;
        if (!flat_spot(g, x, tries < 300 ? 4 : 3, &row) || row - 7 < 1) continue;
        int clear = 1;
        for (int r = row - 7; r <= row - 3 && clear; r++) for (int c = x - 3; c <= x + 5; c++) if (g->o->tile[r][c]) clear = 0;
        if (!clear || thing_near(g, x, SOLID_THINGS, 4)) continue;
        int c = x - 3, k1 = pct(&g->r, ICY_STAGE[g->stage - 1]) ? 3 : 2;
        ledge(g, c, c + 2, row - 3, k1);
        ledge(g, c + 4, c + 6, row - 6, k1);
        thing(g, SGK_DECO, c + 1, row - 3, 5, 0);
        thing(g, SGK_GEM, c + 5, row - 7, 0, 0);
        g->nooks++;
    }
}

void sg_adventure(SgStage *o, const SgParams *p) {
    memset(o, 0, sizeof *o);
    G g; memset(&g, 0, sizeof g);
    int stage = p->stage < 1 ? 1 : (p->stage > 10 ? 10 : p->stage);
    g.o = o; g.stage = stage; g.diff = p->difficulty; g.lava = p->lava; g.crates = p->crates;
    g.r.s = mix(p->seed, (uint32_t)stage * 7919u + 17u);
    g.W = o->w = stage == 10 ? 140 : 120 + 2 * stage;
    g.limit = stage == 10 ? 94 : g.W;
    g.H = o->h = 20;
    o->seconds = stage == 10 ? 160 : 55 + g.W * 3 / 4;
    int approachEnd = stage == 10 ? 112 : g.W - 12;                       /* the boss stage's arena fills the last 25 columns */
    g.row = MAX_ROW; g.x = 0; g.lastLen = 0;
    ground(&g, 0, 9, g.row); g.x = 10; g.lastLen = 10;
    o->startX = 3; o->startY = g.row;
    {   /* a sign by the start and a lamp, so the first screen is not bare */
        thing(&g, SGK_DECO, 1, g.row, 1, 0);
    }
    int cpAt = g.W * (stage == 10 ? 42 : 46) / 100, intense = 0;
    int guard = 0;
    while (g.x < approachEnd - 14 - (stage == 10 ? 4 * ((MAX_ROW - g.row + 1) / 2) : 0) && guard++ < 200) {      /* the boss stage must come down to the arena floor in time */
        if (!g.cpDone && g.x >= cpAt) { checkpoint(&g); intense = 0; continue; }
        if (g.nooks < (g.x * 100 / g.W > 66 && stage >= 5 ? 2 : (g.x * 100 / g.W > 22 ? 1 : 0)) && seg_nook(&g)) { intense = 0; continue; }   /* hidden nooks, spread out */
        int s = pick_segment(&g, intense), ok = 0;
        int x_before = g.x;
        for (int tries = 0; tries < 6 && !ok; tries++) {
            ok = do_segment(&g, s);
            if (!ok) s = pick_segment(&g, intense);
        }
        if (!ok) { s = SEG_RUN; seg_run(&g, rr(&g.r, 6, 10)); }
        if (g.x == x_before) { seg_run(&g, 6); }
#ifdef SG_DEBUG
        fprintf(stderr, "seg %d from %d to %d row %d things %d\n", s, x_before, g.x, g.row, o->nthings);
#endif
        intense = (s == SEG_LEDGES || s == SEG_CRUMBLE || s == SEG_MOVER || s == SEG_LIFT || s == SEG_STONES);
        g.lastSeg2 = g.lastSeg; g.lastSeg = s;
    }
    if (!g.cpDone) checkpoint(&g);
    if (!g.nooks) {                                                       /* every stage hides at least one nook */
        for (int tries = 0; tries < 4 && !g.nooks && g.x < approachEnd - 22; tries++) seg_nook(&g);
    }
    if (stage == 10) {
        descend_to(&g, MAX_ROW);
        if (g.x < 115) { int x0 = g.x; ground(&g, x0, 114, MAX_ROW); g.x = 115; g.row = MAX_ROW; }
        int a0 = 115;                                                     /* the arena: a floor, three ledges, a far wall */
        ground(&g, a0, g.W - 1, MAX_ROW);
        ledge(&g, a0 + 4, a0 + 8, 13, 2); ledge(&g, a0 + 9, a0 + 14, 10, 2); ledge(&g, a0 + 15, a0 + 19, 13, 2);
        for (int r = 0; r < g.H; r++) put(&g, g.W - 1, r, 1);
        o->arenaX0 = a0; o->arenaX1 = g.W; o->bossX = 128; o->bossY = MAX_ROW; o->phases = 3;
        o->exitX = g.W - 3; o->exitY = MAX_ROW;
        thing(&g, SGK_LAMP, o->exitX - 3, MAX_ROW, 0, 0);
    } else {
        int x0 = g.x;
        ground(&g, x0, g.W - 1, g.row);
        o->exitX = g.W - 5; o->exitY = g.row;
        if (o->exitX - 3 >= x0) thing(&g, SGK_LAMP, o->exitX - 3, g.row, 0, 0);
        dress(&g, x0, g.W - 8, g.row, 1);
    }
    if (!g.nooks) force_nook(&g, stage == 10 ? 108 : g.W - 9);
    top_up(&g, stage == 10 ? 108 : g.W - 9);
    clear_around(&g, o->startX, 8);
    clear_around(&g, o->cpX, 4);
    clear_around(&g, o->exitX, 6);
}

/* ---- Arena layouts ---- */
typedef struct { int x0, x1; } Span;

int sg_arena(uint8_t tile[SGA_H][SGA_W], uint32_t seed, int level) {
    Rng r = {mix(seed, (uint32_t)level * 104729u + 5u)};
    memset(tile, 0, SGA_H * SGA_W);
    int flags = 0;
    if (level >= 2 && pct(&r, 22)) flags |= SGA_ICE_FLOOR;
    if (level >= 3 && pct(&r, 30)) flags = (flags & ~SGA_ICE_FLOOR) | SGA_WIND;      /* wind on ice would sweep the buddies away */
    for (int c = 0; c < SGA_W; c++) tile[SGA_H - 1][c] = (uint8_t)(flags & SGA_ICE_FLOOR ? 4 : 1);
    int tiers = 2 + pct(&r, 70) + (level >= 6 && pct(&r, 28));
    int rows[8], nr = 0;
    rows[nr++] = SGA_H - 1;
    int row = 14 - rn(&r, 2);
    rows[nr++] = row;
    for (int t = 1; t < tiers; t++) { row -= tiers == 4 ? 3 : rr(&r, 3, 4); if (row < 3) break; rows[nr++] = row; }
    Span prev[6]; int nprev = 1; prev[0].x0 = 0; prev[0].x1 = SGA_W - 1;       /* the floor */
    int mirror = pct(&r, 55);
    for (int t = 1; t < nr; t++) {
        Span cur[6]; int nc = 0;
        int want = rr(&r, t == 1 ? 2 : 1, 4);
        for (int i = 0; i < want && nc < 5; i++) {
            int len = rr(&r, 4, 10 - t), x0 = rr(&r, 0, SGA_W - len);
            Span s = {x0, x0 + len - 1};
            int ok = 1;
            for (int k = 0; k < nc; k++) if (s.x0 <= cur[k].x1 + 2 && s.x1 >= cur[k].x0 - 2) ok = 0;
            if (!ok) continue;
            /* reachable: some span one tier down must lie within one column of it (the floor always does) */
            int near = t == 1;
            for (int k = 0; k < nprev && !near; k++) if (s.x0 <= prev[k].x1 + 1 && s.x1 >= prev[k].x0 - 1) near = 1;
            if (!near) { Span q = prev[rn(&r, nprev)]; int mid = (q.x0 + q.x1) / 2; s.x0 = imax(0, mid - len / 2); s.x1 = imin(SGA_W - 1, s.x0 + len - 1); s.x0 = s.x1 - len + 1; if (s.x0 < 0) s.x0 = 0; }
            cur[nc++] = s;
            if (mirror) {
                Span m = {SGA_W - 1 - s.x1, SGA_W - 1 - s.x0};
                int clash = 0;
                for (int k = 0; k < nc; k++) if (m.x0 <= cur[k].x1 + 2 && m.x1 >= cur[k].x0 - 2) clash = 1;
                if (!clash && nc < 5) {
                    int near2 = t == 1;
                    for (int k = 0; k < nprev && !near2; k++) if (m.x0 <= prev[k].x1 + 1 && m.x1 >= prev[k].x0 - 1) near2 = 1;
                    if (near2) cur[nc++] = m;
                }
            }
        }
        if (!nc) { int len = 8, x0 = (SGA_W - len) / 2; cur[nc].x0 = x0; cur[nc].x1 = x0 + len - 1; nc++; }
        for (int k = 0; k < nc; k++) {
            int kind = 2, len = cur[k].x1 - cur[k].x0 + 1;
            if (pct(&r, 14)) kind = 3;
            if (level >= 3 && t >= 1 && pct(&r, 22)) kind = SGT_CRUMBLE;
            if (t == 1 && nc >= 3 && len <= 5 && pct(&r, 25)) kind = pct(&r, 50) ? 1 : 4;       /* a solid block */
            for (int c = cur[k].x0; c <= cur[k].x1; c++) tile[rows[t]][c] = (uint8_t)kind;
        }
        nprev = nc; for (int k = 0; k < nc; k++) prev[k] = cur[k];
    }
    return flags;
}
