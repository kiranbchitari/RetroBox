/* Proves the stage generator (cores/originals/stagegen.c) makes finishable stages.
 *
 * For many seeds it builds every Adventure stage and searches it with a copy of the game's own movement code (update_player and
 * move_body in blizzard.c): from the start, over every position the player can stand on, it tries walking, drops and jumps
 * (full and short, with and without a run-up, steering, letting go early), and records where each one lands. The stage passes when
 * the checkpoint, the exit (the boss arena for stage 10), the gem and the treasure chest can all be reached without touching spikes
 * or falling in a pit. Moving platforms count as solid ground across their whole beat, lifts as a ledge at each end joined by a ride;
 * monsters, wind and icicles are not simulated (monsters can be shot, the other two are timing).
 * It also checks every thing stands on ground and not inside a wall, and that Arena layouts can be climbed.
 *
 *   cc -O2 -Wall -Wextra -I cores/originals -o /tmp/sg_check tools/originals-levels/sg_check.c cores/originals/stagegen.c -lm
 *   /tmp/sg_check [seeds] [first_seed]           check
 *   /tmp/sg_check --dump seed stage [difficulty]  print one stage
 */
#include "stagegen.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static SgStage S;                 /* the stage under test, with the platforms the search needs added */
static int WW, WH;

static int is_solid(int t) { return t == 1 || t == 4 || t == 5; }
static int is_oneway(int t) { return t == 2 || t == 3 || t == SGT_CRUMBLE; }
static int is_ice(int t) { return t == 3 || t == 4; }
static int tile_at(int c, int r) {
    if (r < 0) return 0;
    if (r >= WH) return 0;
    if (c < 0 || c >= WW) return 1;
    return S.tile[r][c];
}
static int move_body(float *x, float *y, float *vx, float *vy, float hw, float h, int drop) {
    int flags = 0;
    float nx = *x + *vx;
    int r0 = (int)floorf(*y - h + .02f), r1 = (int)floorf(*y - .02f);
    if (*vx > 0.f) {
        int c = (int)floorf(nx + hw);
        for (int r = r0; r <= r1; r++) if (is_solid(tile_at(c, r))) { nx = (float)c - hw - .001f; flags |= 2; break; }
    } else if (*vx < 0.f) {
        int c = (int)floorf(nx - hw);
        for (int r = r0; r <= r1; r++) if (is_solid(tile_at(c, r))) { nx = (float)c + 1.f + hw + .001f; flags |= 2; break; }
    }
    if (nx - hw < 0.f) { nx = hw; flags |= 2; }
    if (nx + hw > (float)WW) { nx = (float)WW - hw; flags |= 2; }
    *x = nx;
    float ny = *y + *vy;
    int c0 = (int)floorf(*x - hw + .02f), c1 = (int)floorf(*x + hw - .02f);
    if (*vy >= 0.f) {
        int done = 0;
        for (int r = (int)ceilf(*y - .001f); r <= (int)floorf(ny) && !done; r++)
            for (int c = c0; c <= c1; c++) {
                int t = tile_at(c, r);
                if (is_solid(t) || (is_oneway(t) && !drop)) { ny = (float)r; *vy = 0.f; flags |= 1; done = 1; break; }
            }
    } else {
        int r = (int)floorf(ny - h);
        for (int c = c0; c <= c1; c++) if (is_solid(tile_at(c, r))) { ny = (float)r + 1.f + h + .001f; *vy = 0.f; flags |= 4; break; }
    }
    *y = ny;
    return flags;
}

typedef struct { float x, y, vx, vy; int ground, coyote, jumpBuf, drop, springT; } P;
typedef struct { int left, right, down, jump, hold; } In;     /* jump: a fresh press this frame; hold: B is down */

/* one frame of update_player (jump / move part) */
static void step(P *p, In in) {
    int onIce = p->ground && is_ice(tile_at((int)floorf(p->x), (int)floorf(p->y + .01f)));
    float target = (float)(in.right - in.left) * PH_VX;
    p->vx += (target - p->vx) * (onIce ? .05f : (p->ground ? .6f : .3f));
    if (target == 0.f && !onIce && fabsf(p->vx) < .01f) p->vx = 0.f;
    if (in.jump) p->jumpBuf = 7;
    if (p->jumpBuf > 0) p->jumpBuf--;
    if (p->ground) p->coyote = 6; else if (p->coyote > 0) p->coyote--;
    if (p->jumpBuf > 0 && p->coyote > 0 && !p->springT) {
        if (in.down && p->ground && is_oneway(tile_at((int)floorf(p->x), (int)floorf(p->y + .01f)))) { p->drop = 14; p->vy = .06f; p->ground = 0; p->coyote = 0; p->jumpBuf = 0; }
        else { p->vy = PH_JUMP_VY; p->ground = 0; p->coyote = 0; p->jumpBuf = 0; }
    }
    if (p->springT > 0) p->springT--;
    else if (!in.hold && p->vy < -.25f) p->vy = -.25f;
    p->vy += p->vy < 0.f ? PH_GUP : PH_GDN; if (p->vy > PH_VMAX) p->vy = PH_VMAX;
    if (p->drop > 0) p->drop--;
    int fl = move_body(&p->x, &p->y, &p->vx, &p->vy, PH_HW, 1.0f, p->drop > 0);
    p->ground = (fl & 1) != 0;
}

/* spikes and springs (adv_update) */
static int apply_things(P *p) {                     /* returns 1 when the player died */
    if (p->y > (float)WH + .5f) return 1;
    for (int k = 0; k < S.nthings; k++) {
        const SgThing *t = &S.things[k];
        float tx = (float)t->x + .5f;
        if (t->kind == SGK_SPIKES && fabsf(p->x - tx) < .72f && p->y > (float)t->y - .55f && p->y - 1.f < (float)t->y) return 1;
        if (t->kind == SGK_SPRING && p->ground && fabsf(p->x - tx) < .55f && fabsf(p->y - (float)t->y) < .01f) { p->vy = PH_SPRING_VY; p->ground = 0; p->springT = 24; p->coyote = 0; p->jumpBuf = 0; }
    }
    return 0;
}

/* ---- search ---- */
#define XQ 4
static uint8_t reach[SG_MAXH][SG_MAXW * XQ + 8];
static int qx[400000], qy[400000], qn, qh;

static int node_of(const P *p, int *x, int *r) {
    if (!p->ground) return 0;
    *x = (int)floorf(p->x * (float)XQ + .5f); *r = (int)floorf(p->y + .01f + .5f * 0) ;
    if (*r < 0 || *r >= WH || *x < 0 || *x >= WW * XQ) return 0;
    return 1;
}
static void visit(const P *p) {
    int x, r;
    if (!node_of(p, &x, &r)) return;
    if (fabsf((float)r - p->y) > .05f) return;
    if (!reach[r][x]) { reach[r][x] = 1; qx[qn] = x; qy[qn] = r; qn++; }
}

/* run a trial from a standing node: inputs for up to 'frames' frames; every standing position on the way is recorded */
static void trial(int x, int r, float vx0, int dir, int doJump, int holdFrames, int steerFrames, int steerDir, int drop) {
    P p = {(float)x / (float)XQ, (float)r, vx0, 0.f, 1, 6, 0, 0, 0};
    int airborne = 0;
    for (int f = 0; f < 150; f++) {
        In in = {0};
        int d = f < steerFrames ? dir : steerDir;
        in.right = d > 0; in.left = d < 0;
        if (doJump) {
            in.jump = f == 0; in.hold = f < holdFrames;
            in.down = drop;
        } else in.down = 0;
        step(&p, in);
        if (apply_things(&p)) return;
        if (!p.ground) airborne = 1;
        if (p.ground && (airborne || !doJump || f > 0)) {
            visit(&p);
            if (airborne && doJump) return;           /* landed: the trial ends */
        }
        if (p.y > (float)WH + 1.f) return;
        if (!doJump && airborne && p.ground) return;
    }
}

static void search(void) {
    memset(reach, 0, sizeof reach); qn = qh = 0;
    P s = {(float)S.startX + .5f, (float)S.startY, 0.f, 0.f, 1, 6, 0, 0, 0};
    visit(&s);
    while (qh < qn) {
        int x = qx[qh], r = qy[qh]; qh++;
        int under = tile_at((int)floorf((float)x / XQ), r);
        for (int dir = -1; dir <= 1; dir += 2) {
            trial(x, r, 0.f, dir, 0, 0, 999, dir, 0);                                         /* walk (and walk off) */
            int run = is_solid(tile_at((int)floorf((float)x / XQ - dir * 1.5f), r)) || is_oneway(tile_at((int)floorf((float)x / XQ - dir * 1.5f), r));
            run = run || is_solid(tile_at((int)floorf((float)x / XQ - dir * 1.5f), r));
            static const int HOLD[5] = {6, 12, 20, 30, 60}, STEER[4] = {999, 28, 18, 8};
            for (int v = 0; v < 2; v++) {
                if (v && !run) continue;
                for (int h = 0; h < 5; h++) for (int st = 0; st < 4; st++) {
                    trial(x, r, v ? dir * PH_VX : 0.f, dir, 1, HOLD[h], STEER[st], 0, 0);
                    trial(x, r, v ? dir * PH_VX : 0.f, dir, 1, HOLD[h], STEER[st], -dir, 0);
                }
            }
        }
        trial(x, r, 0.f, 0, 1, 20, 999, 0, 0);                                                 /* straight up */
        if (is_oneway(under) || is_oneway(tile_at((int)floorf((float)x / XQ + .35f), r)) || is_oneway(tile_at((int)floorf((float)x / XQ - .35f), r)))
            for (int dir = -1; dir <= 1; dir++) trial(x, r, 0.f, dir, 1, 6, 999, dir, 1);       /* drop through */
        /* a lift: the ride joins its two ledges */
        for (int m = 0; m < S.nmovers; m++) {
            const SgMover *mv = &S.movers[m];
            if (mv->ay == 0.f) continue;
            int rowA = (int)floorf(mv->cy + mv->ay + .5f), rowB = (int)floorf(mv->cy - mv->ay + .5f);
            float x0 = mv->cx - 1.5f, x1 = mv->cx + 1.5f, px = (float)x / XQ;
            if (px > x0 - .05f && px < x1 + .05f && (r == rowA || r == rowB)) {
                int tr = r == rowA ? rowB : rowA;
                P q = {px, (float)tr, 0.f, 0.f, 1, 6, 0, 0, 0};
                visit(&q);
            }
        }
    }
}

static int reached_near(float cx, int row, float rx) {
    for (int x = 0; x < WW * XQ; x++) if (reach[row][x] && fabsf((float)x / XQ - cx) < rx) return 1;
    return 0;
}

/* ---- checks ---- */
static int fails;
static void fail(uint32_t seed, int stage, const char *what, int a, int b) {
    if (fails < 40) printf("FAIL seed %u stage %d: %s (%d, %d)\n", seed, stage, what, a, b);
    fails++;
}

static void make_walkable(void) {                 /* moving platforms count as ground over their whole beat */
    for (int m = 0; m < S.nmovers; m++) {
        const SgMover *mv = &S.movers[m];
        if (mv->ay == 0.f) {
            int row = (int)floorf(mv->cy + .5f);
            for (int c = (int)floorf(mv->cx - mv->ax - 1.5f + .01f); c <= (int)floorf(mv->cx + mv->ax + 1.5f - .01f); c++)
                if (c >= 0 && c < WW && !S.tile[row][c]) S.tile[row][c] = 2;
        } else {
            int rowA = (int)floorf(mv->cy + mv->ay + .5f), rowB = (int)floorf(mv->cy - mv->ay + .5f);
            for (int k = 0; k < 3; k++) { int c = (int)floorf(mv->cx - 1.5f + .01f) + k; if (!S.tile[rowA][c]) S.tile[rowA][c] = 2; if (!S.tile[rowB][c]) S.tile[rowB][c] = 2; }
        }
    }
}

static int check_stage(uint32_t seed, int stage, int diff, int verbose) {
    SgParams pr = {seed, stage, diff, stage == 5 || stage == 10, stage == 8};
    sg_adventure(&S, &pr);
    WW = S.w; WH = S.h;
    int before = fails;
    /* standing places */
    for (int i = 0; i < S.nthings; i++) {
        const SgThing *t = &S.things[i];
        int x = t->x, y = t->y;
        int onGround = is_solid(tile_at(x, y)) || is_oneway(tile_at(x, y));
        switch (t->kind) {
        case SGK_SPIKES: case SGK_SPRING: case SGK_LAMP:
            if (!onGround || is_solid(tile_at(x, y - 1))) fail(seed, stage, "thing not standing on open ground", t->kind, x);
            break;
        case SGK_DECO:
            if (!onGround && t->arg != 5) fail(seed, stage, "decoration floating", t->arg, x);
            if (t->arg == 5 && (!onGround || is_solid(tile_at(x, y - 1)))) fail(seed, stage, "chest not on a ledge", x, y);
            break;
        case SGK_ENEMY:
            if (t->arg != 2 && (!onGround || is_solid(tile_at(x, y - 1)) || is_solid(tile_at(x, y - 2)))) fail(seed, stage, "monster not on open ground", t->arg, x);
            if (t->arg == 2 && is_solid(tile_at(x, y))) fail(seed, stage, "flyer inside a wall", x, y);
            break;
        case SGK_COIN: case SGK_GEM:
            if (is_solid(tile_at(x, y))) fail(seed, stage, "coin inside a wall", x, y);
            break;
        case SGK_ICICLE:
            if (!is_solid(tile_at(x, y - 1))) fail(seed, stage, "icicle hangs from nothing", x, y);
            break;
        }
    }
    if (!(is_solid(tile_at(S.startX, S.startY)))) fail(seed, stage, "start not on ground", S.startX, S.startY);
    if (!(is_solid(tile_at(S.cpX, S.cpY)) || is_oneway(tile_at(S.cpX, S.cpY)))) fail(seed, stage, "checkpoint not on ground", S.cpX, S.cpY);
    if (!(is_solid(tile_at(S.exitX, S.exitY)))) fail(seed, stage, "exit not on ground", S.exitX, S.exitY);
    make_walkable();
    search();
    if (!reached_near((float)S.cpX + .5f, S.cpY, .6f)) fail(seed, stage, "checkpoint unreachable", S.cpX, S.cpY);
    if (!reached_near((float)S.exitX + .5f, S.exitY, .8f)) fail(seed, stage, "exit unreachable", S.exitX, S.exitY);
    if (S.arenaX1 && !reached_near((float)S.arenaX0 + 3.f, S.bossY, 1.5f)) fail(seed, stage, "arena unreachable", S.arenaX0, 0);
    int gems = 0, chests = 0;
    for (int i = 0; i < S.nthings; i++) {
        const SgThing *t = &S.things[i];
        if (t->kind == SGK_GEM) { gems++; if (!reached_near((float)t->x + .5f, t->y + 1, .6f)) fail(seed, stage, "gem unreachable", t->x, t->y); }
        if (t->kind == SGK_DECO && t->arg == 5) { chests++; if (!reached_near((float)t->x + .5f, t->y, .6f)) fail(seed, stage, "chest unreachable", t->x, t->y); }
    }
    if (!gems || !chests) fail(seed, stage, "no nook", gems, chests);
    if (verbose) {
        int n = 0; for (int r = 0; r < WH; r++) for (int x = 0; x < WW * XQ; x++) n += reach[r][x];
        printf("stage %d seed %u: w=%d things=%d movers=%d standing nodes=%d\n", stage, seed, S.w, S.nthings, S.nmovers, n);
    }
    return fails == before;
}

static void dump(uint32_t seed, int stage, int diff) {
    SgParams pr = {seed, stage, diff, stage == 5 || stage == 10, stage == 8};
    sg_adventure(&S, &pr);
    char g[SG_MAXH][SG_MAXW + 1];
    for (int r = 0; r < S.h; r++) {
        for (int c = 0; c < S.w; c++) { int k = S.tile[r][c]; g[r][c] = k == 1 ? '#' : k == 2 ? '=' : k == 3 ? '-' : k == 4 ? '~' : k == 5 ? 'c' : k == 6 ? 'x' : '.'; }
        g[r][S.w] = 0;
    }
    for (int i = 0; i < S.nthings; i++) {
        const SgThing *t = &S.things[i]; char ch = '?';
        switch (t->kind) { case SGK_COIN: ch = 'o'; break; case SGK_GEM: ch = 'G'; break; case SGK_SPIKES: ch = '^'; break; case SGK_ENEMY: ch = 'e'; break;
            case SGK_SPRING: ch = 'S'; break; case SGK_DECO: ch = t->arg == 5 ? 'T' : 't'; break; case SGK_ICICLE: ch = 'v'; break; case SGK_WIND: ch = 'w'; break; case SGK_LAMP: ch = 'l'; break; case SGK_PITSPIKES: ch = '_'; break; }
        if (t->kind == SGK_WIND) { for (int c = t->x; c < t->x + t->arg && c < S.w; c++) if (g[0][c] == '.') g[0][c] = t->patrol ? '>' : '<'; continue; }
        if (g[t->y][t->x] == '.') g[t->y][t->x] = ch;
    }
    for (int m = 0; m < S.nmovers; m++) {
        const SgMover *mv = &S.movers[m];
        int row = (int)(mv->cy + .5f);
        if (mv->ay == 0.f) { for (int c = (int)(mv->cx - mv->ax - 1.5f); c <= (int)(mv->cx + mv->ax + 1.5f); c++) if (g[row][c] == '.') g[row][c] = 'm'; }
        else { int top = (int)(mv->cy - mv->ay + .5f), bot = (int)(mv->cy + mv->ay + .5f); for (int r = top; r <= bot; r++) for (int k = 0; k < 3; k++) { int c = (int)(mv->cx - 1.5f + .01f) + k; if (g[r][c] == '.') g[r][c] = ':'; } }
    }
    g[S.startY - 1][S.startX] = 'P'; g[S.cpY - 1][S.cpX] = 'C'; g[S.exitY - 1][S.exitX] = 'D';
    for (int r = 0; r < S.h; r++) printf("%2d %s\n", r, g[r]);
}

static void check_arena(uint32_t seed, int level) {
    uint8_t t[SGA_H][SGA_W];
    sg_arena(t, seed, level);
    /* every platform must be reachable: use the same search on a 32 x 18 board (rows below are solid) */
    memset(&S, 0, sizeof S);
    S.w = SGA_W; S.h = SGA_H; S.startX = 6; S.startY = SGA_H - 1;
    for (int r = 0; r < SGA_H; r++) memcpy(S.tile[r], t[r], SGA_W);
    WW = SGA_W; WH = SGA_H + 1;                  /* the arena's floor is the last row; below it is solid in the game, a pit here is fine */
    search();
    for (int r = 0; r < SGA_H - 1; r++) for (int c = 0; c < SGA_W; c++) {
        int k = t[r][c];
        if (k && !is_solid(k) && is_oneway(k)) {
            int ok = 0;
            for (int x = 0; x < SGA_W * XQ; x++) if (reach[r][x] && x / XQ >= c - 1 && x / XQ <= c + 1) ok = 1;
            if (!ok) { fail(seed, -level, "arena platform unreachable", c, r); return; }
        }
    }
}

int main(int argc, char **argv) {
    if (argc >= 4 && !strcmp(argv[1], "--dump")) { dump((uint32_t)atoi(argv[2]), atoi(argv[3]), argc > 4 ? atoi(argv[4]) : 1); return 0; }
    int n = argc > 1 ? atoi(argv[1]) : 20; uint32_t first = argc > 2 ? (uint32_t)atoi(argv[2]) : 1;
    int total = 0, bad = 0;
    for (uint32_t s = first; s < first + (uint32_t)n; s++)
        for (int st = 1; st <= 10; st++) for (int d = 0; d < 3; d += 2) { total++; if (!check_stage(s * 2654435761u + 12345u, st, d, 0)) bad++; }
    printf("adventure: %d stages, %d failed\n", total, bad);
    int abad = 0, atot = 0;
    for (uint32_t s = first; s < first + (uint32_t)n * 4; s++) for (int lv = 1; lv <= 30; lv++) { if (lv % 5 == 0) continue; atot++; int b = fails; check_arena(s * 40503u + 7u, lv); if (fails != b) abad++; }
    printf("arena: %d layouts, %d failed\n", atot, abad);
    return fails ? 1 : 0;
}
