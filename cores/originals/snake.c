/* RetroBox Originals - Snake. Logic runs on a fixed 36 x 18 grid at 60 Hz; drawing is resolution independent
 * (everything is laid out from the screen height), with the snake sliding smoothly between cells. */
#include "game.h"
#include "gfx.h"
#include "snd.h"
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define COLS 36
#define ROWS 18
#define RING 1024                 /* power of two, > COLS * ROWS */
#define NPART 48
#define STATE_MAGIC 0x314B4E53u   /* "SNK1" */
#define SAVE_MAGIC 0x31564152u

enum { S_TITLE, S_PLAY, S_PAUSE, S_DEAD, S_OVER };
enum { UP, RIGHT, DOWN, LEFT };
static const int DX[4] = {0, 1, 0, -1}, DY[4] = {-1, 0, 1, 0};

static const char *MODE_NAME[2] = {"WALLS", "WRAP"};
static const char *SPEED_NAME[3] = {"EASY", "NORMAL", "HARD"};
static const int BASE_FRAMES[3] = {9, 7, 5};     /* frames per cell at the start */
static const int MIN_FRAMES[3] = {5, 4, 3};      /* the fastest it gets */
static const int SCORE_MUL[3] = {1, 2, 3};
#define DEMO_FRAMES 6
#define BONUS_FRAMES 420

#define C_TEXT RGB(232, 246, 243)
#define C_DIM RGB(111, 163, 160)
#define C_ACCENT RGB(61, 220, 151)
#define C_FOOD RGB(255, 77, 109)
#define C_GOLD RGB(255, 209, 102)
#define C_HEAD RGB(150, 255, 196)
#define C_TAIL RGB(22, 150, 106)
#define C_PANEL RGB(11, 33, 45)
#define C_EDGE RGB(36, 88, 104)

typedef struct { float x, y, vx, vy; int32_t life, max; uint32_t color; } Particle;

typedef struct {
    uint32_t magic, rng;
    int32_t screen, frame, timer;          /* timer: frames on this screen */
    int32_t row, mode, speed;              /* title menu */
    int32_t demo;                          /* 1: the title's self-playing snake */
    int32_t len, head;                     /* snake: cell i is ring[(head + i) & (RING - 1)], 0 = head */
    int16_t rx[RING], ry[RING];
    int32_t tailX, tailY;                  /* the cell the tail just left (for the sliding tail) */
    int32_t dir, lastDir, nq, queue[2];
    int32_t elapsed, grow, foods, score, won, newBest;
    int32_t foodX, foodY, bonusX, bonusY, bonusLeft;
    Particle parts[NPART];
} State;

typedef struct { uint32_t magic; uint32_t hi[2][3]; int32_t mode, speed; uint8_t reserved[28]; } Save;
_Static_assert(sizeof(Save) == 64, "save layout");

static State st;
static Save save;
static Pad prev;
static int synced;

/* ---- screen layout (set by game_setup) ---- */
static Surf fb;
static uint16_t *bg;
static float S;                            /* screen height / 720 */
static int cell, bx0, by0, bw, bh;         /* board: origin and size in pixels */
static float hudY, hudH, sideW, hudGap;

static float u(float k) { return k * S; }
static int fs(float k) { int v = (int)(k * S + .5f); return v < 1 ? 1 : v; }   /* a font scale */

/* ---- helpers ---- */
static uint32_t rnd(void) { st.rng ^= st.rng << 13; st.rng ^= st.rng >> 17; st.rng ^= st.rng << 5; return st.rng; }
static float frnd(void) { return (float)(rnd() & 0xFFFF) / 65535.f; }
static int cx(int i) { return st.rx[(st.head + i) & (RING - 1)]; }
static int cy(int i) { return st.ry[(st.head + i) & (RING - 1)]; }
static int wd(int d, int n) { if (d > n / 2) d -= n; else if (d < -n / 2) d += n; return d; }

static void store_settings(void) { save.mode = st.mode; save.speed = st.speed; }

static void sanitize_save(void) {
    if (save.magic != SAVE_MAGIC) { memset(&save, 0, sizeof save); save.magic = SAVE_MAGIC; save.speed = 1; }
    if (save.mode < 0 || save.mode > 1) save.mode = 0;
    if (save.speed < 0 || save.speed > 2) save.speed = 1;
}

static int step_frames(void) {
    if (st.demo) return DEMO_FRAMES;
    int f = BASE_FRAMES[st.speed] - st.foods / 6;
    return f < MIN_FRAMES[st.speed] ? MIN_FRAMES[st.speed] : f;
}

static int occupied(int x, int y) {
    for (int i = 0; i < st.len; i++) if (cx(i) == x && cy(i) == y) return 1;
    return 0;
}

/* A random empty cell that is not the food or the bonus. 0 when the board is full. */
static int free_cell(int *ox, int *oy) {
    for (int tries = 0; tries < 400; tries++) {
        int x = (int)(rnd() % COLS), y = (int)(rnd() % ROWS);
        if (!occupied(x, y) && !(x == st.foodX && y == st.foodY) && !(st.bonusLeft > 0 && x == st.bonusX && y == st.bonusY)) { *ox = x; *oy = y; return 1; }
    }
    for (int y = 0; y < ROWS; y++) for (int x = 0; x < COLS; x++)
        if (!occupied(x, y) && !(x == st.foodX && y == st.foodY) && !(st.bonusLeft > 0 && x == st.bonusX && y == st.bonusY)) { *ox = x; *oy = y; return 1; }
    return 0;
}

static void new_round(int demo) {
    st.demo = demo;
    st.len = 4; st.head = 0;
    for (int i = 0; i < 4; i++) { st.rx[i] = (int16_t)(COLS / 2 - i); st.ry[i] = (int16_t)(ROWS / 2); }
    st.tailX = cx(3); st.tailY = cy(3);
    st.dir = st.lastDir = RIGHT; st.nq = 0;
    st.elapsed = 0; st.grow = 0; st.foods = 0; st.score = 0; st.won = 0; st.newBest = 0;
    st.bonusLeft = 0; st.foodX = -1; st.foodY = -1;
    int x, y;
    if (free_cell(&x, &y)) { st.foodX = x; st.foodY = y; }
}

static void burst(float x, float y, uint32_t color, int n) {
    for (int i = 0; i < NPART && n > 0; i++) {
        Particle *p = &st.parts[i];
        if (p->life > 0) continue;
        float ang = frnd() * 6.2831853f, sp = .05f + frnd() * .16f;
        p->x = x; p->y = y; p->vx = cosf(ang) * sp; p->vy = sinf(ang) * sp;
        p->life = p->max = 22 + (int32_t)(frnd() * 20.f); p->color = color;
        n--;
    }
}

static void enqueue(int d) {
    int last = st.nq ? st.queue[st.nq - 1] : st.dir;
    if (d == last || d == ((last + 2) & 3)) return;
    if (st.nq < 2) { st.queue[st.nq++] = d; if (!st.demo) snd_play(SND_TURN); }
}

/* Moves the snake one cell. 0 = moved, 1 = ate the food, 2 = ate the bonus, -1 = died. */
static int do_step(void) {
    if (st.nq) { int d = st.queue[0]; st.queue[0] = st.queue[1]; st.nq--; if (d != ((st.dir + 2) & 3)) st.dir = d; }
    int nx = cx(0) + DX[st.dir], ny = cy(0) + DY[st.dir];
    if (st.mode == 1) { nx = (nx + COLS) % COLS; ny = (ny + ROWS) % ROWS; }
    else if (nx < 0 || ny < 0 || nx >= COLS || ny >= ROWS) return -1;
    int growing = st.grow > 0;
    int check = st.len - (growing ? 0 : 1);               /* the tail cell is free again unless the snake is growing */
    for (int i = 0; i < check; i++) if (cx(i) == nx && cy(i) == ny) return -1;
    int tx = cx(st.len - 1), ty = cy(st.len - 1);
    st.head = (st.head - 1) & (RING - 1);
    st.rx[st.head] = (int16_t)nx; st.ry[st.head] = (int16_t)ny;
    st.lastDir = st.dir;
    st.tailX = tx; st.tailY = ty;
    if (growing) { st.len++; st.grow--; }
    int mul = SCORE_MUL[st.speed], result = 0;
    if (nx == st.foodX && ny == st.foodY) {
        result = 1;
        st.foods++; st.grow += 2;
        if (!st.demo) st.score += 10 * mul;
        burst(nx + .5f, ny + .5f, C_FOOD, 14);
        st.foodX = st.foodY = -1;
        int x, y;
        if (free_cell(&x, &y)) { st.foodX = x; st.foodY = y; } else st.won = 1;
        if (!st.demo && st.foods % 5 == 0 && free_cell(&x, &y)) { st.bonusX = x; st.bonusY = y; st.bonusLeft = BONUS_FRAMES; }
        if (!st.demo) snd_play(SND_EAT);
    } else if (st.bonusLeft > 0 && nx == st.bonusX && ny == st.bonusY) {
        result = 2;
        st.bonusLeft = 0; st.grow += 3;
        if (!st.demo) st.score += 50 * mul;
        burst(nx + .5f, ny + .5f, C_GOLD, 24);
        snd_play(SND_BONUS);
    }
    return result;
}

/* Title-screen autopilot: of straight / left / right take the safe one that gets closest to the food. */
static void steer(void) {
    int best = st.dir, bestD = 1 << 30;
    for (int k = 0; k < 3; k++) {
        int d = (st.dir + (k == 0 ? 0 : (k == 1 ? 3 : 1))) & 3;
        int nx = cx(0) + DX[d], ny = cy(0) + DY[d];
        if (st.mode == 1) { nx = (nx + COLS) % COLS; ny = (ny + ROWS) % ROWS; }
        else if (nx < 0 || ny < 0 || nx >= COLS || ny >= ROWS) continue;
        int blocked = 0;
        for (int i = 0; i < st.len - 1; i++) if (cx(i) == nx && cy(i) == ny) { blocked = 1; break; }
        if (blocked) continue;
        int dist = abs(wd(st.foodX - nx, COLS)) + abs(wd(st.foodY - ny, ROWS));
        if (st.mode == 0) dist = abs(st.foodX - nx) + abs(st.foodY - ny);
        if (dist < bestD) { bestD = dist; best = d; }
    }
    st.dir = best; st.nq = 0;
}

static void start_play(void) {
    new_round(0);
    st.screen = S_PLAY; st.timer = 0;
    store_settings();
    snd_play(SND_START);
}

static void to_title(void) {
    new_round(1);
    st.screen = S_TITLE; st.timer = 0;
}

static void die(void) {
    st.screen = S_DEAD; st.timer = 0;
    uint32_t *hi = &save.hi[st.mode][st.speed];
    if ((uint32_t)st.score > *hi) { *hi = (uint32_t)st.score; st.newBest = 1; }
    snd_play(SND_DIE);
}

/* ---- per-frame update ---- */
static void advance(void) {
    st.elapsed++;
    if (st.elapsed >= step_frames()) {
        st.elapsed = 0;
#ifdef RB_SELFTEST            /* test builds: the autopilot also plays real games (see tools/ notes in cores/originals/README.md) */
        steer();
#else
        if (st.demo) steer();
#endif
        int r = do_step();
        if (r < 0) { if (st.demo) new_round(1); else die(); }
        else if (!st.demo && st.won) { st.screen = S_DEAD; st.timer = 0; if ((uint32_t)st.score > save.hi[st.mode][st.speed]) { save.hi[st.mode][st.speed] = (uint32_t)st.score; st.newBest = 1; } snd_play(SND_BONUS); }
    }
    if (!st.demo && st.bonusLeft > 0) st.bonusLeft--;
}

static void update(const Pad *p) {
    Pad e = { (uint8_t)(p->up && !prev.up), (uint8_t)(p->down && !prev.down), (uint8_t)(p->left && !prev.left), (uint8_t)(p->right && !prev.right),
              (uint8_t)(p->a && !prev.a), (uint8_t)(p->b && !prev.b), (uint8_t)(p->start && !prev.start), (uint8_t)(p->select && !prev.select) };
    st.frame++; st.timer++;
    for (int i = 0; i < NPART; i++) {
        Particle *q = &st.parts[i];
        if (q->life <= 0) continue;
        q->x += q->vx; q->y += q->vy; q->vx *= .92f; q->vy *= .92f; q->life--;
    }
    switch (st.screen) {
    case S_TITLE:
        if (e.up) { st.row = (st.row + 2) % 3; snd_play(SND_MENU); }
        if (e.down) { st.row = (st.row + 1) % 3; snd_play(SND_MENU); }
        if (st.row == 0 && (e.left || e.right)) { st.mode ^= 1; store_settings(); snd_play(SND_MENU); to_title(); }
        if (st.row == 1 && (e.left || e.right)) { st.speed = (st.speed + (e.right ? 1 : 2)) % 3; store_settings(); snd_play(SND_MENU); }
        if (e.a || e.start) { start_play(); break; }
        advance();
        break;
    case S_PLAY:
        if (e.up) enqueue(UP);
        if (e.right) enqueue(RIGHT);
        if (e.down) enqueue(DOWN);
        if (e.left) enqueue(LEFT);
        if (e.start) { st.screen = S_PAUSE; st.timer = 0; snd_play(SND_PAUSE); break; }
        advance();
        break;
    case S_PAUSE:
        if (e.start) { st.screen = S_PLAY; st.timer = 0; snd_play(SND_PAUSE); }
        else if (e.b) { to_title(); snd_play(SND_MENU); }
        break;
    case S_DEAD:
        if (st.timer >= 55) { st.screen = S_OVER; st.timer = 0; }
        break;
    case S_OVER:
        if (st.timer > 25 && (e.a || e.start)) start_play();
        else if (st.timer > 10 && e.b) { to_title(); snd_play(SND_MENU); }
        break;
    }
    prev = *p;
}

/* ---- drawing ---- */
static void panel(float x, float y, float w, float h, float r) {
    gfx_rrect(&fb, x, y + u(6), w, h, r, RGB(0, 0, 0), 70);          /* soft shadow */
    gfx_rrect(&fb, x, y, w, h, r, C_EDGE, 255);
    float b = fmaxf(1.f, u(2));
    gfx_rrect(&fb, x + b, y + b, w - 2 * b, h - 2 * b, r - b, C_PANEL, 255);
}

static void render_bg(void) {
    Surf *s = &fb;
    uint16_t *keep = s->px;
    s->px = bg; gfx_clip_reset(s);
    gfx_vgrad(s, RGB(12, 32, 52), RGB(3, 12, 20));
    gfx_vignette(s, .9f);
    /* glow + board */
    for (int i = 7; i >= 1; i--) gfx_rrect(s, bx0 - u(i * 3.f), by0 - u(i * 3.f), bw + u(i * 6.f), bh + u(i * 6.f), u(10.f + i * 3.f), C_ACCENT, 7);
    gfx_rrect(s, bx0 - u(4), by0 - u(4), bw + u(8), bh + u(8), u(6), C_EDGE, 255);
    for (int y = 0; y < ROWS; y++) for (int x = 0; x < COLS; x++)
        gfx_rrect(s, (float)(bx0 + x * cell), (float)(by0 + y * cell), (float)cell, (float)cell, 0, ((x + y) & 1) ? RGB(17, 50, 60) : RGB(13, 40, 49), 255);
    /* HUD panels */
    float cw = bw - 2 * sideW - 2 * hudGap;
    panel((float)bx0, hudY, sideW, hudH, u(12));
    panel(bx0 + sideW + hudGap, hudY, cw, hudH, u(12));
    panel(bx0 + sideW + hudGap + cw + hudGap, hudY, sideW, hudH, u(12));
    gfx_text(s, bx0 + u(22), hudY + u(10), fs(2), "SCORE", C_DIM, 255);
    gfx_text(s, bx0 + sideW + hudGap + cw + hudGap + u(22), hudY + u(10), fs(2), "BEST", C_DIM, 255);
    float colw = cw / 3.f, cx0 = bx0 + sideW + hudGap;
    const char *labels[3] = {"MODE", "SPEED", "LENGTH"};
    for (int i = 0; i < 3; i++) gfx_text_c(s, cx0 + colw * (i + .5f), hudY + u(10), fs(2), labels[i], C_DIM, 255);
    s->px = keep; gfx_clip_reset(s);
}

static void hud(void) {
    char b[32];
    float cw = bw - 2 * sideW - 2 * hudGap, colw = cw / 3.f, cx0 = bx0 + sideW + hudGap, vy = hudY + u(32);
    int sc = fs(4);
    snprintf(b, sizeof b, "%05d", (int)st.score);
    gfx_text(&fb, bx0 + u(22), vy, sc, b, st.demo ? C_DIM : C_ACCENT, 255);
    uint32_t best = save.hi[st.mode][st.speed];
    if (!st.demo && (uint32_t)st.score > best) best = (uint32_t)st.score;     /* the best score follows a record run live */
    snprintf(b, sizeof b, "%05u", (unsigned)best);
    gfx_text(&fb, bx0 + sideW + hudGap + cw + hudGap + u(22), vy, sc, b, C_GOLD, 255);
    int sc2 = fs(3.2f), vy2 = (int)(vy + (7 * sc - 7 * sc2) * .5f);
    gfx_text_c(&fb, cx0 + colw * .5f, (float)vy2, sc2, MODE_NAME[st.mode], C_TEXT, 255);
    gfx_text_c(&fb, cx0 + colw * 1.5f, (float)vy2, sc2, SPEED_NAME[st.speed], C_TEXT, 255);
    snprintf(b, sizeof b, "%d", (int)st.len);
    gfx_text_c(&fb, cx0 + colw * 2.5f, vy, sc, b, C_TEXT, 255);
}

static float PX[RING + 2], PY[RING + 2];

/* Draws the snake as a thick path through the cell centres, sliding by t (0..1) towards its newest cell. */
static void draw_snake(float t, float shx, float shy, float flash) {
    int len = st.len;
    int ix = cx(0), iy = cy(0);
    static float L[2][RING + 2];
    float tx = (float)st.tailX, ty = (float)st.tailY;
    for (int i = 0; i < len; i++) {
        if (i) { ix += wd(cx(i) - cx(i - 1), COLS); iy += wd(cy(i) - cy(i - 1), ROWS); }
        PX[i] = ix + .5f; PY[i] = iy + .5f;
    }
    int n = len + 1;
    L[0][0] = PX[1] + (PX[0] - PX[1]) * t; L[1][0] = PY[1] + (PY[0] - PY[1]) * t;
    for (int i = 1; i < len; i++) { L[0][i] = PX[i]; L[1][i] = PY[i]; }
    float ex = PX[len - 1] + (float)wd((int)tx - cx(len - 1), COLS), ey = PY[len - 1] + (float)wd((int)ty - cy(len - 1), ROWS);
    L[0][len] = PX[len - 1] + (ex - PX[len - 1]) * (1.f - t); L[1][len] = PY[len - 1] + (ey - PY[len - 1]) * (1.f - t);

    float minx = 1e9f, maxx = -1e9f, miny = 1e9f, maxy = -1e9f;
    for (int i = 0; i < n; i++) { minx = fminf(minx, L[0][i]); maxx = fmaxf(maxx, L[0][i]); miny = fminf(miny, L[1][i]); maxy = fmaxf(maxy, L[1][i]); }

    float T = cell * .78f, H = cell * .94f;
    uint32_t red = RGB(255, 90, 90);
    for (int oy = -1; oy <= 1; oy++) for (int ox = -1; ox <= 1; ox++) {
        if (st.mode == 0 && (ox || oy)) continue;
        float offx = (float)(ox * COLS), offy = (float)(oy * ROWS);
        if (minx + offx > COLS + 1 || maxx + offx < -1 || miny + offy > ROWS + 1 || maxy + offy < -1) continue;
        int prevHoriz = -1;
        for (int k = 0; k < n - 1; k++) {
            float ax = bx0 + (L[0][k] + offx) * cell + shx, ay = by0 + (L[1][k] + offy) * cell + shy;
            float bx = bx0 + (L[0][k + 1] + offx) * cell + shx, by = by0 + (L[1][k + 1] + offy) * cell + shy;
            uint32_t col = gfx_mix(C_HEAD, C_TAIL, (float)k / (float)(n - 1));
            if (k & 1) col = gfx_scale(col, .93f);
            if (flash > 0.f) col = gfx_mix(col, red, flash);
            int horiz;
            if (fabsf(ay - by) < .01f && fabsf(ax - bx) < .01f) horiz = -1;
            else horiz = fabsf(ay - by) < .01f;
            if (horiz == 1) gfx_rrect(&fb, fminf(ax, bx) - 1.f, ay - T * .5f, fabsf(bx - ax) + 2.f, T, 0, col, 255);
            else if (horiz == 0) gfx_rrect(&fb, ax - T * .5f, fminf(ay, by) - 1.f, T, fabsf(by - ay) + 2.f, 0, col, 255);
            if (horiz >= 0) {
                if (prevHoriz >= 0 && horiz != prevHoriz) gfx_circle(&fb, ax, ay, T * .5f, col, 255);   /* round corner */
                prevHoriz = horiz;
            }
        }
        float tex = bx0 + (L[0][n - 1] + offx) * cell + shx, tey = by0 + (L[1][n - 1] + offy) * cell + shy;
        uint32_t tc = flash > 0.f ? gfx_mix(C_TAIL, red, flash) : C_TAIL;
        gfx_circle(&fb, tex, tey, T * .5f, tc, 255);
        /* head */
        float hx = bx0 + (L[0][0] + offx) * cell + shx, hy = by0 + (L[1][0] + offy) * cell + shy;
        uint32_t hc = flash > 0.f ? gfx_mix(C_HEAD, red, flash) : C_HEAD;
        gfx_rrect(&fb, hx - H * .5f, hy - H * .5f, H, H, H * .42f, hc, 255);
        float fx = (float)DX[st.lastDir], fy = (float)DY[st.lastDir], sx = -fy, sy = fx;
        for (int side = -1; side <= 1; side += 2) {
            float ex2 = hx + fx * cell * .16f + sx * side * cell * .22f, ey2 = hy + fy * cell * .16f + sy * side * cell * .22f;
            gfx_circle(&fb, ex2, ey2, cell * .14f, RGB(255, 255, 255), 255);
            gfx_circle(&fb, ex2 + fx * cell * .05f, ey2 + fy * cell * .05f, cell * .075f, RGB(10, 20, 30), 255);
        }
    }
}

static void draw_food(float shx, float shy) {
    if (st.foodX >= 0) {
        float pulse = 1.f + .07f * sinf(st.frame * .12f);
        float x = bx0 + (st.foodX + .5f) * cell + shx, y = by0 + (st.foodY + .5f) * cell + shy, r = cell * .34f * pulse;
        gfx_circle(&fb, x, y, r * 1.7f, C_FOOD, 26);
        gfx_circle(&fb, x, y + r * .08f, r, C_FOOD, 255);
        gfx_circle(&fb, x - r * .35f, y - r * .35f, r * .28f, RGB(255, 200, 210), 255);
        gfx_rrect(&fb, x + r * .05f, y - r * 1.35f, r * .6f, r * .38f, r * .19f, RGB(90, 220, 120), 255);   /* leaf */
        gfx_rrect(&fb, x - r * .08f, y - r * 1.05f, r * .16f, r * .45f, r * .05f, RGB(120, 80, 60), 255);   /* stem */
    }
    if (st.bonusLeft > 0) {
        float left = (float)st.bonusLeft / BONUS_FRAMES;
        int blink = st.bonusLeft < 120 && ((st.frame / 5) & 1);
        float x = bx0 + (st.bonusX + .5f) * cell + shx, y = by0 + (st.bonusY + .5f) * cell + shy, r = cell * .36f * (1.f + .1f * sinf(st.frame * .2f));
        if (!blink) {
            gfx_circle(&fb, x, y, r * 1.8f, C_GOLD, 30);
            gfx_circle(&fb, x, y, r, C_GOLD, 255);
            gfx_circle(&fb, x, y, r * .55f, RGB(255, 240, 190), 255);
        }
        gfx_ring(&fb, x, y, cell * .62f, fmaxf(1.f, u(3)) , C_GOLD, (int)(60 + 140 * left));
    }
}

static void draw_particles(float shx, float shy) {
    for (int i = 0; i < NPART; i++) {
        Particle *p = &st.parts[i];
        if (p->life <= 0) continue;
        float k = (float)p->life / (float)p->max;
        gfx_circle(&fb, bx0 + p->x * cell + shx, by0 + p->y * cell + shy, cell * (.05f + .15f * k), p->color, (int)(255 * k));
    }
}

static void dim_board(int a) { gfx_rrect(&fb, (float)bx0, (float)by0, (float)bw, (float)bh, 0, RGB(2, 8, 14), a); }

static void draw_title(void) {
    dim_board(150);
    float pw = u(640), ph = u(440), px = bx0 + (bw - pw) * .5f, py = by0 + (bh - ph) * .5f;
    panel(px, py, pw, ph, u(18));
    int ls = fs(11);
    float totalw = (float)gfx_text_w("SNAKE", ls), lx = px + (pw - totalw) * .5f;
    const char *letters = "SNAKE";
    for (int i = 0; i < 5; i++) {
        char one[2] = {letters[i], 0};
        float bob = sinf(st.frame * .07f + i * .75f) * u(4), x = lx + i * 6.f * ls, y = py + u(34) + bob;
        gfx_text(&fb, x + ls * .5f, y + ls * .6f, ls, one, RGB(0, 0, 0), 110);
        gfx_text(&fb, x, y, ls, one, gfx_mix(C_HEAD, C_ACCENT, i / 4.f), 255);
    }
    gfx_text_c(&fb, px + pw * .5f, py + u(138), fs(2), "A RETROBOX ORIGINAL", C_DIM, 255);
    float rowH = u(60), y0 = py + u(186);
    int sc = fs(3);
    for (int i = 0; i < 3; i++) {
        float y = y0 + i * rowH;
        int sel = st.row == i;
        if (sel) gfx_rrect(&fb, px + u(36), y, pw - u(72), rowH - u(10), u(12), C_ACCENT, 38 + (int)(14 * sinf(st.frame * .1f)));
        float ty = y + (rowH - u(10) - 7 * sc) * .5f;
        uint32_t col = sel ? C_TEXT : C_DIM;
        if (i < 2) {
            gfx_text(&fb, px + u(70), ty, sc, i == 0 ? "MODE" : "SPEED", col, 255);
            const char *v = i == 0 ? MODE_NAME[st.mode] : SPEED_NAME[st.speed];
            float vx = px + pw - u(210);
            gfx_text_c(&fb, vx, ty, sc, v, sel ? C_ACCENT : C_TEXT, 255);
            if (sel) { gfx_text(&fb, vx - u(112), ty, sc, "<", C_TEXT, 255); gfx_text(&fb, vx + u(112) - 5 * sc, ty, sc, ">", C_TEXT, 255); }
        } else {
            gfx_text_c(&fb, px + pw * .5f, ty, sc, "PLAY", sel ? C_ACCENT : C_DIM, 255);
        }
    }
    gfx_text_c(&fb, px + pw * .5f, py + ph - u(46), fs(2), "D-PAD CHOOSE     A PLAY", C_DIM, 255);
}

static void draw_pause(void) {
    dim_board(150);
    float pw = u(520), ph = u(210), px = bx0 + (bw - pw) * .5f, py = by0 + (bh - ph) * .5f;
    panel(px, py, pw, ph, u(18));
    gfx_text_c(&fb, px + pw * .5f, py + u(36), fs(9), "PAUSED", C_TEXT, 255);
    gfx_text_c(&fb, px + pw * .5f, py + ph - u(58), fs(3), "START RESUME   B MENU", C_DIM, 255);
}

static void draw_over(void) {
    dim_board(140);
    float pw = u(600), ph = u(340), px = bx0 + (bw - pw) * .5f, py = by0 + (bh - ph) * .5f;
    panel(px, py, pw, ph, u(18));
    gfx_text_c(&fb, px + pw * .5f, py + u(30), fs(st.won ? 9 : 8), st.won ? "PERFECT!" : "GAME OVER", st.won ? C_GOLD : C_FOOD, 255);
    char b[48];
    snprintf(b, sizeof b, "SCORE %d", (int)st.score);
    gfx_text_c(&fb, px + pw * .5f, py + u(125), fs(5), b, C_TEXT, 255);
    if (st.newBest) {
        if ((st.timer / 12) & 1) gfx_text_c(&fb, px + pw * .5f, py + u(190), fs(3), "NEW BEST!", C_GOLD, 255);
        else gfx_text_c(&fb, px + pw * .5f, py + u(190), fs(3), "NEW BEST!", C_ACCENT, 255);
    } else {
        snprintf(b, sizeof b, "BEST %u", (unsigned)save.hi[st.mode][st.speed]);
        gfx_text_c(&fb, px + pw * .5f, py + u(190), fs(3), b, C_DIM, 255);
    }
    gfx_text_c(&fb, px + pw * .5f, py + ph - u(58), fs(3), "A RETRY     B MENU", C_ACCENT, 255);
}

static void draw(void) {
    memcpy(fb.px, bg, (size_t)fb.w * fb.h * sizeof(uint16_t));
    hud();
    gfx_clip(&fb, bx0, by0, bw, bh);
    float shx = 0, shy = 0, flash = 0;
    if (st.screen == S_DEAD && !st.won) {
        float amp = u(7) * (1.f - st.timer / 55.f);
        shx = sinf(st.timer * 2.9f) * amp; shy = cosf(st.timer * 3.7f) * amp;
        flash = st.timer < 20 ? 1.f - st.timer / 20.f : 0.f;
    }
    float t = (st.screen == S_DEAD || st.screen == S_OVER) ? 1.f : fminf(1.f, (float)st.elapsed / (float)step_frames());
    draw_food(shx, shy);
    draw_snake(t, shx, shy, .6f * flash);
    draw_particles(shx, shy);
    if (st.screen == S_DEAD && !st.won && st.timer < 12) gfx_rrect(&fb, (float)bx0, (float)by0, (float)bw, (float)bh, 0, RGB(255, 60, 60), (int)(90 * (1.f - st.timer / 12.f)));
    gfx_clip_reset(&fb);
    switch (st.screen) {
    case S_TITLE: draw_title(); break;
    case S_PAUSE: draw_pause(); break;
    case S_OVER: draw_over(); break;
    default: break;
    }
}

/* ---- public ---- */
static void snake_release(void) { free(fb.px); free(bg); fb.px = NULL; bg = NULL; }

static int snake_setup(int w, int h) {
    snake_release();
    fb.w = w; fb.h = h;
    fb.px = (uint16_t *)calloc((size_t)w * h, sizeof(uint16_t));
    bg = (uint16_t *)calloc((size_t)w * h, sizeof(uint16_t));
    if (!fb.px || !bg) { snake_release(); return 0; }
    gfx_clip_reset(&fb);
    S = (float)h / 720.f;
    cell = (int)(34.f * S); if (cell < 8) cell = 8;
    bw = COLS * cell; bh = ROWS * cell;
    bx0 = (w - bw) / 2; by0 = h - (int)(16.f * S + .5f) - bh; if (by0 < 0) by0 = 0;
    hudY = u(14); hudH = by0 - u(14) - hudY;
    sideW = bw * .27f; hudGap = bw * .018f;
    render_bg();
    return 1;
}

static void snake_reset(void) {
    if (!st.rng) st.rng = 0x9E3779B9u;
    sanitize_save();
    st.magic = STATE_MAGIC;
    st.mode = save.mode; st.speed = save.speed;
    to_title();
    memset(st.parts, 0, sizeof st.parts);
    st.row = 2;
}

static void snake_frame(const Pad pads[2]) {
    const Pad *pad = &pads[0];
    if (!synced) { sanitize_save(); st.mode = save.mode; st.speed = save.speed; synced = 1; }
    update(pad);
    draw();
}

static const uint16_t *snake_fb(void) { return fb.px; }

static size_t snake_state_size(void) { return sizeof(State); }

static int snake_state_save(void *dst) { memcpy(dst, &st, sizeof st); return 1; }

static int snake_state_load(const void *src, size_t size) {
    if (size != sizeof(State)) return 0;
    State n; memcpy(&n, src, sizeof n);
    if (n.magic != STATE_MAGIC || n.len < 2 || n.len > COLS * ROWS || n.head < 0 || n.head >= RING || n.screen < 0 || n.screen > S_OVER) return 0;
    if (n.mode < 0 || n.mode > 1 || n.speed < 0 || n.speed > 2 || n.dir < 0 || n.dir > 3 || n.lastDir < 0 || n.lastDir > 3 || n.nq < 0 || n.nq > 2 || n.row < 0 || n.row > 2) return 0;
    for (int i = 0; i < n.len; i++) {
        int x = n.rx[(n.head + i) & (RING - 1)], y = n.ry[(n.head + i) & (RING - 1)];
        if (x < 0 || x >= COLS || y < 0 || y >= ROWS) return 0;
    }
    for (int i = 0; i < n.nq; i++) if (n.queue[i] < 0 || n.queue[i] > 3) return 0;
    if (n.foodX < -1 || n.foodX >= COLS || n.foodY < -1 || n.foodY >= ROWS || n.bonusX < 0 || n.bonusX >= COLS || n.bonusY < 0 || n.bonusY >= ROWS || n.bonusLeft < 0 || n.bonusLeft > BONUS_FRAMES) return 0;
    if (n.elapsed < 0 || n.elapsed > 60 || n.grow < 0 || n.grow > 1000) return 0;
    if (n.tailX < 0 || n.tailX >= COLS || n.tailY < 0 || n.tailY >= ROWS) return 0;
    for (int i = 0; i < NPART; i++) if (n.parts[i].life > n.parts[i].max || n.parts[i].max < 0 || n.parts[i].max > 200) memset(&n.parts[i], 0, sizeof(Particle));
    if (!n.rng) n.rng = 0x9E3779B9u;
    st = n;
    return 1;
}

static uint8_t *snake_sram(size_t *size) { *size = sizeof save; return (uint8_t *)&save; }

const Game GAME_SNAKE = {"snake", snake_setup, snake_release, snake_reset, snake_frame, snake_fb, snake_state_size, snake_state_save, snake_state_load, snake_sram};
