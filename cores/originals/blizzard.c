/* RetroBox Originals - Blizzard Buddies: a one-screen snowball platformer for one or two players. An original game:
 * throw snow at the monsters, bury them in snowballs, roll the balls into the rest. Logic runs on a 32 x 18 tile board at
 * 60 Hz; drawing is resolution independent (a tile is height / 18 pixels, so 960x540, 1280x720 and 1920x1080 all fill the
 * screen exactly). The buddies, the monsters and the bosses are sprites (sprite.c, art in assets/); the scenery, the
 * snowballs, the ghost and the fireballs are drawn in code, and so are the characters if the sprites cannot be set up. */
#include "game.h"
#include "gfx.h"
#include "snd.h"
#include "sprite.h"
#include "levels.h"
#include "stagegen.h"
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define TW 32                              /* the arena board, and the screen in tiles */
#define TH 18
#define WMAX LV_MAXW                       /* the widest adventure world */
#define HMAX LV_MAXH
#define MAXE 16
#define MAXS 20
#define MAXI 8
#define MAXPT 140
#define MAXPOP 12
#define MAXIC 24                           /* icicles in one stage */
#define MAXQ 24
#define STATE_MAGIC 0x33425A42u    /* "BZB3": generated stages and boards, new state fields */
#define SAVE_MAGIC 0x31425A53u

enum { S_TITLE, S_PLAY, S_PAUSE, S_OVER };
enum { M_ARENA, M_ADVENTURE };
enum { PH_INTRO, PH_ACTION, PH_CLEAR };
enum { E_WALK, E_HOP, E_FLY, E_BOSS, E_SHOOT, E_CHARGE, E_GHOST, E_TYPES };
enum { ES_NORMAL, ES_FROZEN, ES_ROLL, ES_DYING };
enum { IT_SPEED, IT_RANGE, IT_POWER, IT_FRUIT, IT_TYPES };

typedef struct { float x, y, vx, vy, size; int32_t life, max; uint32_t color; } Part;
typedef struct { float x, y; int32_t value, life; } Pop;
typedef struct {
    int32_t active, lives, score, dir, ground, invuln, dead, shootCd, throwAnim, dropTimer, coyote, jumpBuf, anim;
    float x, y, vx, vy;
    int32_t speedUp, rangeUp, powerUp;
    int32_t land, nextLife, iceSlide;
    int32_t springT;                       /* frames left of a spring launch (jump release does not cut it) */
} Player;
typedef struct {
    int32_t used, type, state, dir, snow, snowTimer, timer, hp, hitFlash, ground, bounces, owner, combo, anim;
    int32_t kind, cd;
    float x, y, vx, vy, by, kb;
    float hx, pr;                          /* adventure: home x and patrol half-width (0 = free) */
} Enemy;
typedef struct { int32_t used, life, owner, power; float x, y, vx, vy; } Shot;    /* owner 0/1 = player, 2 = monster fire (power: 1 fire, 2 the wizard's ice, 3 a spitter's ice; drawing only) */
typedef struct { int32_t used, type, life; float x, y, vy; } Item;
typedef struct { int32_t c, r, mode, t; } Crumb;                                    /* a crumbling tile: mode 0 free, 1 shaking, 2 gone */
typedef struct { int32_t mode, t; float y, vy; } Icicle;                            /* mode 0 hangs, 1 shakes, 2 falls, 3 broken */

typedef struct {
    uint32_t magic, rng;
    int32_t screen, phase, frame, timer, level, hold;
    int32_t qn, qi, spawnTimer, minionTimer;
    uint8_t queue[MAXQ];
    uint8_t tile[HMAX][WMAX];              /* 0 empty, 1 solid, 2 one-way, 3 one-way ice, 4 solid ice, 5 crate; ww x wh used */
    Player pl[2];
    Enemy en[MAXE];
    Shot sh[MAXS];
    Item it[MAXI];
    Part pt[MAXPT];
    Pop pop[MAXPOP];
    int32_t newBest, won;
    int32_t difficulty, hitstop, shake, comboCount, comboTimer, levelFrames, hurry, missed, menuRow, tallyTime, tallyNoMiss, bannerTimer;
    int32_t skin[2];
    /* adventure mode (level = stage 1..LV_COUNT) */
    int32_t mode, ww, wh;
    float camx, camy;                      /* top-left of the view, in tiles */
    uint32_t taken[8];                     /* collected coins / gems / chests, by thing index */
    uint32_t spawned[8];                   /* monsters already brought into the stage, by thing index (they wake as the camera nears) */
    int32_t cpReached, exitOpen, arenaOn, bossPhase;   /* arenaOn: 0 not yet, 1 fight, 2 won */
    uint32_t seed;                         /* this run's stages and boards are built from it (stagegen.c) */
    int32_t mods, mtime;                   /* arena board flags (SGA_*); the clock of moving platforms and wind */
    Crumb cr[SG_MAXCR];
    Icicle ic[MAXIC];
} State;

typedef struct { uint32_t magic; uint32_t hi, bestLevel, skin, diff, mode, advBest; uint8_t reserved[36]; } Save;
_Static_assert(sizeof(Save) == 64, "save layout");

static State st;
static Save save;
static Pad prev[2];
static int synced;

/* ---- layout (set by setup) ---- */
static Surf fb;
static uint16_t *bg;
static int W, H, cell, ox;
static float S;
static int bgLevel = -1;
static int bgTitle = -1;
static uint32_t bgSeed;

static float u(float k) { return k * S; }
static int fs(float k) { int v = (int)(k * S + .5f); return v < 1 ? 1 : v; }
static float shx, shy;                      /* camera shake, in pixels, applied to everything on the board */
static int camPx, camPy;                    /* the camera in whole pixels (adventure), so tiles never seam */
static float PX(float x) { return (float)(ox - camPx) + x * cell + shx; }
static float PY(float y) { return (float)(-camPy) + y * cell + shy; }

/* ---- themes ---- */
typedef struct { uint32_t top, bot, hill1, hill2, ice, iceHi, iceDark; int stars, aurora; } Theme;
static const Theme THEMES[4] = {
    {RGB(27, 42, 92), RGB(112, 96, 176), RGB(78, 76, 150), RGB(56, 54, 124), RGB(116, 176, 236), RGB(196, 228, 252), RGB(58, 98, 168), 40, 0},
    {RGB(5, 16, 40), RGB(14, 72, 84), RGB(22, 70, 96), RGB(12, 48, 74), RGB(94, 194, 214), RGB(184, 242, 246), RGB(36, 108, 140), 90, 1},
    {RGB(58, 28, 92), RGB(238, 124, 112), RGB(128, 64, 124), RGB(90, 42, 104), RGB(156, 176, 234), RGB(224, 232, 252), RGB(92, 104, 172), 20, 0},
    {RGB(70, 140, 214), RGB(206, 232, 248), RGB(154, 194, 232), RGB(122, 168, 218), RGB(132, 202, 242), RGB(228, 246, 255), RGB(70, 140, 202), 0, 0},
};

/* ---- level data: floor on the last row, then platforms {row, x0, x1, kind (1 solid, 2 one-way)} ---- */
typedef struct { int8_t row, x0, x1, kind; } Seg;
static const Seg LB[] = {{13, 3, 9, 2}, {13, 22, 28, 2}, {9, 12, 19, 2}, {5, 3, 8, 2}, {5, 23, 28, 2}, {0, 0, 0, 0}};
static const Seg LB1[] = {{14, 0, 6, 2}, {14, 25, 31, 2}, {10, 6, 25, 2}, {6, 0, 5, 2}, {6, 26, 31, 2}, {0, 0, 0, 0}};

static int is_boss_level(int level) { return level % 5 == 0; }
static int adventure(void);
/* The stage being played: the art pack's stage of that number gives the look (tiles, name), stagegen.c the layout. Rebuilt on
 * demand from the seed, so a saved state needs no more than the seed. */
static SgStage GS;
static LvLevel GL;
static uint32_t glKey;
_Static_assert(sizeof(SgThing) == sizeof(LvThing), "SgThing is LvThing");
_Static_assert(SGK_COIN == LV_COIN && SGK_GEM == LV_GEM && SGK_SPIKES == LV_SPIKES && SGK_PITSPIKES == LV_PITSPIKES && SGK_LAMP == LV_LAMP &&
               SGK_CRATE == LV_CRATE && SGK_ENEMY == LV_ENEMY && SGK_SPRING == LV_SPRING && SGK_DECO == LV_DECO, "thing kinds");
static const LvLevel *stage_def(void) {
    int i = st.level - 1; if (i < 0) i = 0; if (i >= LV_COUNT) i = LV_COUNT - 1;
    uint32_t key = st.seed * 2654435761u + (uint32_t)(i + 1) * 40503u + (uint32_t)st.difficulty * 977u + 1u;
    if (key != glKey || !GS.w) {
        const LvLevel *base = &LV_LEVELS[i];
        SgParams sp = {st.seed, i + 1, st.difficulty, base->tPit != LV_TILE_NONE, i + 1 == 8};
        sg_adventure(&GS, &sp);
        GL = *base;
        GL.w = (uint8_t)GS.w; GL.h = (uint8_t)GS.h; GL.rows = NULL;
        GL.startX = (uint8_t)GS.startX; GL.startY = (uint8_t)GS.startY; GL.cpX = (uint8_t)GS.cpX; GL.cpY = (uint8_t)GS.cpY;
        GL.exitX = (uint8_t)GS.exitX; GL.exitY = (uint8_t)GS.exitY; GL.seconds = (uint16_t)GS.seconds;
        GL.arenaX0 = (uint8_t)GS.arenaX0; GL.arenaX1 = (uint8_t)GS.arenaX1; GL.bossX = (uint8_t)GS.bossX; GL.bossY = (uint8_t)GS.bossY; GL.phases = (uint8_t)GS.phases;
        GL.nthings = (uint8_t)GS.nthings; GL.things = (const LvThing *)GS.things;
        glKey = key;
    }
    return &GL;
}
static int taken_get(int k) { return (st.taken[k >> 5] >> (k & 31)) & 1u; }
static void taken_set(int k) { st.taken[k >> 5] |= 1u << (k & 31); }
static int spawned_get(int k) { return (st.spawned[k >> 5] >> (k & 31)) & 1u; }
static void spawned_set(int k) { st.spawned[k >> 5] |= 1u << (k & 31); }
static void spawned_clear(int k) { st.spawned[k >> 5] &= ~(1u << (k & 31)); }
/* A boss is (or is about to be) fought: arena boss levels, or the adventure arena once entered. */
static int boss_fight(void) { return adventure() ? st.arenaOn == 1 : is_boss_level(st.level); }
static int boss_kind(int level) { return ((level / 5) - 1) % 3; }       /* 0 ogre, 1 frost wizard, 2 rage ogre */
static int boss_max(int kind, int level) { return (kind == 0 ? 12 : (kind == 1 ? 14 : 18)) + 2 * (level / 5); }
static int is_solid(int t) { return t == 1 || t == 4 || t == 5; }
static int is_oneway(int t) { return t == 2 || t == 3 || t == SGT_CRUMBLE; }
static int is_ice(int t) { return t == 3 || t == 4; }

/* ---- buddies: six looks, the last three are earned by reaching levels ---- */
#define NSKINS 6
static const int SKIN_UNLOCK[NSKINS] = {0, 0, 0, 5, 10, 15};
static const char *const SKIN_NAME[NSKINS] = {"BLUE", "PINK", "MINT", "GOLD", "VIOLET", "PANDA"};
static const char *const DIFF_NAME[3] = {"EASY", "NORMAL", "HARD"};
static const float DIFF_SPEED[3] = {.85f, 1.f, 1.2f};
static const int DIFF_LIVES[3] = {4, 3, 2};
static int theme_of(int level) { return ((level - 1) / 2) % 4; }

/* ---- helpers ---- */
static uint32_t rnd(void) { st.rng ^= st.rng << 13; st.rng ^= st.rng >> 17; st.rng ^= st.rng << 5; return st.rng; }
static float frnd(void) { return (float)(rnd() & 0xFFFF) / 65535.f; }
static int irnd(int n) { return (int)(rnd() % (uint32_t)n); }
static float fabs_(float v) { return v < 0 ? -v : v; }

static int adventure(void) { return st.mode == M_ADVENTURE; }

static int tile_at(int c, int r) {
    if (r < 0) return 0;
    if (r >= st.wh) return adventure() ? 0 : 1;            /* adventure: below the world is a pit */
    if (c < 0 || c >= st.ww) return 1;
    return st.tile[r][c];
}

/* Moves a box (x centre, y feet) by its velocity against the tiles. Returns 1 landed, 2 hit a wall, 4 hit a ceiling. */
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
    if (nx + hw > (float)st.ww) { nx = (float)st.ww - hw; flags |= 2; }
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

static void sanitize_save(void) {
    if (save.magic != SAVE_MAGIC) { memset(&save, 0, sizeof save); save.magic = SAVE_MAGIC; save.diff = 1; }
    if (save.mode > M_ADVENTURE) save.mode = M_ARENA;
    if (save.advBest > LV_COUNT + 1) save.advBest = 0;
}

static void burst(float x, float y, uint32_t color, int n, float speed, float size) {
    for (int i = 0; i < MAXPT && n > 0; i++) {
        Part *p = &st.pt[i];
        if (p->life > 0) continue;
        float ang = frnd() * 6.2831853f, sp = speed * (.3f + frnd() * .7f);
        p->x = x; p->y = y; p->vx = cosf(ang) * sp; p->vy = sinf(ang) * sp - speed * .4f; p->size = size * (.6f + frnd() * .6f);
        p->life = p->max = 20 + irnd(22); p->color = color;
        n--;
    }
}

static void popup(float x, float y, int value) {
    int slot = 0;
    for (int i = 0; i < MAXPOP; i++) { if (st.pop[i].life <= 0) { slot = i; break; } if (st.pop[i].life < st.pop[slot].life) slot = i; }
    st.pop[slot].x = x; st.pop[slot].y = y; st.pop[slot].value = value; st.pop[slot].life = 50;
}

static void give_score(int p, int pts, float x, float y) {
    if (p < 0 || p > 1) return;
    Player *pl = &st.pl[p];
    pl->score += pts;
    if (pts >= 100) popup(x, y, pts);
    while (pl->score >= pl->nextLife && pl->nextLife > 0) {                /* an extra life every 40000 after 30000 */
        if (pl->lives < 9) pl->lives++;
        pl->nextLife += 40000;
        snd_play(SND_ITEM); popup(pl->x, pl->y - 1.5f, 0);
    }
}

static void drop_item(float x, float y, int forceFruit) {
    int slot = -1;
    for (int i = 0; i < MAXI; i++) if (!st.it[i].used) { slot = i; break; }
    if (slot < 0) return;
    int type = forceFruit ? IT_FRUIT : (irnd(100) < 55 ? IT_FRUIT : irnd(3));
    Item *it = &st.it[slot];
    it->used = 1; it->type = type; it->x = x; it->y = y - .5f; it->vy = -.3f; it->life = 700;
}

static Enemy *new_enemy(int type, float x, float y) {
    for (int i = 0; i < MAXE; i++) {
        Enemy *e = &st.en[i];
        if (e->used) continue;
        memset(e, 0, sizeof *e);
        e->used = 1; e->type = type; e->x = x; e->y = y; e->dir = irnd(2) ? 1 : -1; e->timer = 30 + irnd(60);
        e->by = 3.f + (float)irnd(9);
        e->kind = type == E_BOSS ? (adventure() ? 2 : boss_kind(st.level)) : 0;      /* adventure: the rage ogre */
        e->hp = type == E_BOSS ? boss_max(e->kind, st.level) : (type == E_GHOST ? 999 : 1);
        e->owner = -1;
        return e;
    }
    return NULL;
}

_Static_assert(SGA_W == TW && SGA_H == TH, "the generated arena board is the game board");
#define MOD_SWARM 4                        /* st.mods: arena waves of many weak monsters */
#define MOD_ELITE 8                        /* ... of a few strong ones */
static void build_tiles(int level) {
    memset(st.tile, 0, sizeof st.tile);
    st.ww = TW; st.wh = TH; st.mods = 0;
    if (is_boss_level(level)) {
        const Seg *seg = boss_kind(level) == 1 ? LB1 : LB;
        for (int c = 0; c < TW; c++) st.tile[TH - 1][c] = 1;
        for (; seg->kind; seg++) for (int c = seg->x0; c <= seg->x1; c++) st.tile[seg->row][c] = (uint8_t)seg->kind;
    } else {
        uint8_t board[SGA_H][SGA_W];
        st.mods = sg_arena(board, st.seed, level);
        for (int r = 0; r < TH; r++) memcpy(st.tile[r], board[r], TW);
        uint32_t h = (st.seed ^ (uint32_t)level * 2246822519u) * 2654435761u;
        h ^= h >> 15;
        if (level >= 3 && (h >> 4) % 100 < 20) st.mods |= MOD_SWARM;
        else if (level >= 4 && (h >> 12) % 100 < 20) st.mods |= MOD_ELITE;
    }
}

static void snap_camera(void);
static void adv_retry_reset(void);

static void spawn_player(int i) {
    Player *p = &st.pl[i];
    int respawn = p->dead != 0;
    p->x = i == 0 ? 6.f : 26.f; p->y = (float)(TH - 1); p->vx = p->vy = 0; p->dir = i == 0 ? 1 : -1;
    if (adventure()) {                                   /* at the checkpoint once reached, else the start; P2 a step behind */
        const LvLevel *L = stage_def();
        float x = st.cpReached ? (float)L->cpX + .5f : (float)L->startX + .5f, y = st.cpReached ? (float)L->cpY : (float)L->startY;
        if (st.arenaOn == 1) { x = (float)L->arenaX0 + 1.5f; y = (float)L->bossY; }
        p->x = x - (i ? .9f : 0.f); p->y = y; p->dir = 1;
        const Player *o = &st.pl[1 - i];                 /* co-op: back in next to a partner who stands on the ground */
        if (st.phase == PH_ACTION && o->active && !o->dead && o->lives > 0 && o->ground) { p->x = o->x; p->y = o->y; }
        if (p->x < .5f) p->x = .5f;
        p->springT = 0; p->dead = 0;
        if (respawn && st.phase == PH_ACTION && st.arenaOn == 0 && !(o->active && !o->dead && o->lives > 0 && o->ground)) adv_retry_reset();
        if (st.phase == PH_ACTION) snap_camera();
    }
    p->ground = 1; p->invuln = 150; p->dead = 0; p->shootCd = 0; p->throwAnim = 0; p->dropTimer = 0; p->coyote = 0; p->jumpBuf = 0;
    p->springT = 0;
}

/* ---- adventure stages (the level pack, converted by tools/originals-levels/convert_levels.py) ---- */
static void adv_build(void) {
    const LvLevel *L = stage_def();
    memcpy(st.tile, GS.tile, sizeof st.tile);
    st.ww = L->w; st.wh = L->h;
    memset(st.taken, 0, sizeof st.taken); memset(st.spawned, 0, sizeof st.spawned);
    memset(st.cr, 0, sizeof st.cr); memset(st.ic, 0, sizeof st.ic);
    st.cpReached = 0; st.arenaOn = 0; st.bossPhase = 1; st.mtime = 0;
    st.exitOpen = L->arenaX1 == 0;                       /* the boss stage's exit opens when the boss falls */
}

/* Monsters are brought in as the camera nears them (the stages are longer than the 16 monsters that can be alive at once). */
static void adv_spawn(void) {
    const LvLevel *L = stage_def();
    float cx = st.camx + TW * .5f;
    for (int k = 0; k < L->nthings; k++) {
        const LvThing *t = &L->things[k];
        if (t->kind != LV_ENEMY || spawned_get(k) || fabs_((float)t->x - cx) > 30.f) continue;
        Enemy *e = new_enemy(t->arg, (float)t->x + .5f, (float)t->y);
        if (!e) return;
        e->hx = e->x; e->pr = (float)t->patrol + .5f;
        if (t->arg == E_FLY) { e->by = (float)t->y - .2f; e->y = e->by; }
        e->timer = 20 + irnd(40);
        spawned_set(k);
    }
}

/* After a death at the checkpoint: the monsters beyond it are back, icicles hang again. */
static void adv_retry_reset(void) {
    const LvLevel *L = stage_def();
    float cx = st.cpReached ? (float)L->cpX : 0.f;
    int ic = 0;
    for (int k = 0; k < L->nthings; k++) {
        const LvThing *t = &L->things[k];
        if (t->kind == LV_ENEMY && (float)t->x >= cx) spawned_clear(k);
        if (t->kind == SGK_ICICLE) { if (ic < MAXIC && (float)t->x >= cx) st.ic[ic].mode = 0; ic++; }
    }
    for (int i = 0; i < MAXE; i++) { Enemy *e = &st.en[i]; if (e->used && e->type != E_BOSS && e->type != E_GHOST && e->x >= cx) e->used = 0; }
    memset(st.sh, 0, sizeof st.sh);
}

static void start_level(int level) {
    st.level = level;
#ifdef RB_TRACE
    fprintf(stderr, "LEVEL %d at frame %d, score %d\n", level, st.frame, st.pl[0].score);
#endif
    memset(st.en, 0, sizeof st.en); memset(st.sh, 0, sizeof st.sh); memset(st.it, 0, sizeof st.it);
    st.qn = st.qi = 0; st.spawnTimer = 40; st.minionTimer = 600;
    st.levelFrames = 0; st.missed = 0; st.hurry = 0; st.comboCount = 0; st.comboTimer = 0; st.bannerTimer = 0;
    if (adventure()) {
        adv_build();
        lv_background(st.level - 1);
        for (int i = 0; i < 2; i++) if (st.pl[i].active && st.pl[i].lives > 0) spawn_player(i);
#ifdef RB_START_X                                         /* test builds: begin at this column, on the first ground below the top (screenshots of one spot) */
        for (int r = 0; r < st.wh; r++) if (is_solid(st.tile[r][RB_START_X]) || is_oneway(st.tile[r][RB_START_X])) { st.pl[0].x = (float)RB_START_X + .5f; st.pl[0].y = (float)r; break; }
#endif
        snap_camera();
        st.phase = PH_INTRO; st.timer = 0;
        snd_music(MUSIC_PLAY);
        return;
    }
    build_tiles(level);
    st.camx = st.camy = 0.f;
    if (is_boss_level(level)) {
        st.queue[st.qn++] = E_BOSS;
    } else {
        int n = 4 + level * 3 / 4; if (n > 14) n = 14;
        if (st.mods & MOD_SWARM) n = n * 3 / 2 + 2;
        if (st.mods & MOD_ELITE) n = n * 2 / 3 + 1;
        if (n > MAXQ - 2) n = MAXQ - 2;
        for (int i = 0; i < n; i++) {
            int t = E_WALK, r = irnd(100);
            if (st.mods & MOD_SWARM) t = (level >= 2 && r < 40) ? E_HOP : E_WALK;
            else if (st.mods & MOD_ELITE) t = r < 30 ? E_SHOOT : (r < 60 ? E_CHARGE : (r < 80 ? E_FLY : E_HOP));
            else if (level >= 2 && r < 25) t = E_HOP;
            else if (level >= 3 && r < 45) t = E_FLY;
            else if (level >= 4 && r < 65) t = E_SHOOT;
            else if (level >= 6 && r < 85) t = E_CHARGE;
            st.queue[st.qn++] = (uint8_t)t;
        }
    }
    for (int i = 0; i < 2; i++) if (st.pl[i].active && st.pl[i].lives > 0) spawn_player(i);
    st.phase = PH_INTRO; st.timer = 0;
    snd_music(MUSIC_PLAY);
}

/* ---- game rules ---- */
static const float EHW[E_TYPES] = {.38f, .38f, .38f, 1.2f, .38f, .44f, .36f};
static const float EH[E_TYPES] = {.85f, .8f, .75f, 2.5f, .85f, .85f, .8f};

static int total_score(void) { return st.pl[0].score + st.pl[1].score; }
static float dmul(void) { return DIFF_SPEED[st.difficulty]; }
static void do_shake(int n) { if (n > st.shake) st.shake = n; }
static void do_stop(int n) { if (n > st.hitstop) st.hitstop = n; }

/* hitFlash only drives drawing: 7 after a snow hit (a white flash), BOSS_HIT_POSE after a boss takes a rolled snowball
 * (the hit pose with dizzy stars while it counts down past 7, a flash at its start). */
#define BOSS_HIT_POSE 40
static int flash_on(const Enemy *e) { return (e->hitFlash > 4 && e->hitFlash <= 7) || e->hitFlash > BOSS_HIT_POSE - 3; }

static void kill_player(int i) {
    Player *p = &st.pl[i];
    p->dead = 1; p->lives--; p->vx = 0.f; p->vy = -.45f; p->speedUp = p->rangeUp = p->powerUp = 0;
    st.missed = 1; st.comboCount = 0; st.comboTimer = 0;
    do_stop(10); do_shake(14);
    snd_play(SND_HURT);
    burst(p->x, p->y - .5f, RGB(255, 255, 255), 16, .15f, .12f);
}

static void kill_enemy(Enemy *e, int owner, int combo) {
    if (e->state == ES_DYING || e->type == E_GHOST) return;
    e->state = ES_DYING; e->timer = 0; e->vx = (e->dir >= 0 ? 1.f : -1.f) * .12f; e->vy = -.36f;
    if (st.comboTimer > 0) st.comboCount++; else st.comboCount = 1;
    st.comboTimer = 150;
    int mult = st.comboCount > 5 ? 5 : st.comboCount;
    int pts = 1000;
    for (int k = 1; k < combo && pts < 8000; k++) pts *= 2;
    if (e->type == E_BOSS) pts = 6000;
    pts *= mult;
    give_score(owner, pts, e->x, e->y - 1.f);
    burst(e->x, e->y - .5f, RGB(255, 230, 120), e->type == E_BOSS ? 44 : 14, .17f, .13f);
    do_stop(e->type == E_BOSS ? 18 : 6); do_shake(e->type == E_BOSS ? 34 : 9);
    snd_play(st.comboCount > 1 || combo > 1 ? SND_COMBO : SND_SHATTER);
    if (e->type == E_BOSS) { for (int k = 0; k < 4; k++) drop_item(e->x + (float)k - 1.5f, e->y - .5f, k == 3); }
    else if (irnd(100) < 40) drop_item(e->x, e->y, 0);
}

static void freeze_enemy(Enemy *e, int owner) {
    e->snow = 3; e->state = ES_FROZEN; e->timer = 520; e->vx = 0.f; e->owner = owner; e->combo = 0;
    snd_play(SND_FREEZE);
    do_stop(3);
    give_score(owner, 50, e->x, e->y - 1.f);
    burst(e->x, e->y - .5f, RGB(190, 230, 255), 14, .13f, .11f);
}

static void start_roll(Enemy *e, int dir, int owner) {
    e->state = ES_ROLL; e->dir = dir; e->vx = (float)dir * .28f; e->timer = 0; e->bounces = 0; e->owner = owner; e->combo = 0;
    snd_play(SND_HIT);
}

static void shatter_ball(Enemy *e) {
    snd_play(SND_SHATTER);
    do_shake(6);
    burst(e->x, e->y - .5f, RGB(220, 240, 255), 24, .18f, .14f);
    give_score(e->owner, 100, e->x, e->y - 1.f);
    if (irnd(100) < 35) drop_item(e->x, e->y, 0);
    e->used = 0;
}

static void hit_enemy_with_snow(Enemy *e, const Shot *s) {
    if (e->state == ES_NORMAL) {
        if (e->hitFlash < 7) e->hitFlash = 7;              /* drawing only; does not cut a boss's longer hit pose short */
        burst(s->x, s->y, RGB(255, 255, 255), 7, .1f, .09f);
        give_score(s->owner, 10, 0, 0);
        snd_play(SND_HIT);
        do_stop(1);
        if (e->type == E_BOSS) {
            e->hp -= s->power; do_shake(5);
            if (e->hp <= 0) kill_enemy(e, s->owner, 1);
        } else {
            e->kb = (s->vx >= 0.f ? 1.f : -1.f) * .12f;
            e->snow += s->power; e->snowTimer = 240;
            if (e->snow >= 3) freeze_enemy(e, s->owner);
        }
    } else if (e->state == ES_FROZEN) {
        start_roll(e, s->vx >= 0.f ? 1 : -1, s->owner);
    }
}

static int nearest_player(float x, float y, float *px, float *py) {
    int best = -1; float bd = 1e9f;
    for (int i = 0; i < 2; i++) {
        Player *p = &st.pl[i];
        if (!p->active || p->dead || p->lives <= 0) continue;
        float d = fabs_(p->x - x) + fabs_(p->y - y) * .5f;
        if (d < bd) { bd = d; best = i; *px = p->x; *py = p->y; }
    }
    return best;
}

static void add_shot(float x, float y, float vx, float vy, int life, int owner, int power) {
    for (int i = 0; i < MAXS; i++) {
        Shot *s = &st.sh[i];
        if (s->used) continue;
        s->used = 1; s->x = x; s->y = y; s->vx = vx; s->vy = vy; s->life = life; s->owner = owner; s->power = power;
        return;
    }
}

/* ---- moving platforms, wind, crumbling tiles, icicles (stagegen.c lays them out) ---- */
static float arena_gust(float *dir) {                        /* arena wind: a gust every ten seconds, the direction turning each time */
    *dir = (st.mtime / 600) & 1 ? -1.f : 1.f;
    int cyc = st.mtime % 600;
    return cyc < 420 ? sinf(3.14159265f * (float)cyc / 420.f) : 0.f;
}
static float zone_gust(int k) {                              /* adventure wind zone number k (a thing index) */
    int ph = (st.mtime + k * 53) % 300;
    return ph < 210 ? sinf(3.14159265f * (float)ph / 210.f) : 0.f;
}
static float wind_force(float x) {
    if (!adventure()) {
        float d, g;
        if (!(st.mods & SGA_WIND)) return 0.f;
        g = arena_gust(&d);
        return d * .014f * g;
    }
    const LvLevel *L = stage_def();
    for (int k = 0; k < L->nthings; k++) {
        const LvThing *t = &L->things[k];
        if (t->kind != SGK_WIND || x < (float)t->x || x > (float)(t->x + t->arg)) continue;
        return (t->patrol ? 1.f : -1.f) * .016f * zone_gust(k);
    }
    return 0.f;
}

static int tile_support(const Player *p) {                   /* is something solid or a ledge under the feet? */
    int r = (int)floorf(p->y + .01f);
    for (int c = (int)floorf(p->x - .38f); c <= (int)floorf(p->x + .38f); c++) { int k = tile_at(c, r); if (is_solid(k) || is_oneway(k)) return 1; }
    return 0;
}
static void mover_carry(Player *p) {                         /* standing on a moving platform: ride it */
    if (!adventure() || !GS.nmovers || tile_support(p)) return;
    for (int m = 0; m < GS.nmovers; m++) {
        const SgMover *mv = &GS.movers[m];
        float x0, y0, x1, y1;
        sg_mover_pos(mv, st.mtime - 1, &x0, &y0); sg_mover_pos(mv, st.mtime, &x1, &y1);
        if (fabs_(p->y - y0) < .12f && fabs_(p->x - x0) < (float)mv->w * .5f + .3f) { p->x += x1 - x0; p->y = y1; return; }
    }
}
static int mover_land(Player *p, float yBefore) {            /* falling onto a moving platform */
    if (!adventure()) return 0;
    for (int m = 0; m < GS.nmovers; m++) {
        const SgMover *mv = &GS.movers[m];
        float x1, y1;
        sg_mover_pos(mv, st.mtime, &x1, &y1);
        if (fabs_(p->x - x1) < (float)mv->w * .5f + .3f && yBefore <= y1 + .08f && p->y >= y1 - .001f) { p->y = y1; p->vy = 0.f; return 1; }
    }
    return 0;
}

static void crumble_touch(int c, int r) {
    if (r < 0 || r >= st.wh || c < 0 || c >= st.ww || st.tile[r][c] != SGT_CRUMBLE) return;
    int slot = -1;
    for (int i = 0; i < SG_MAXCR; i++) {
        if (st.cr[i].mode && st.cr[i].c == c && st.cr[i].r == r) return;
        if (!st.cr[i].mode && slot < 0) slot = i;
    }
    if (slot < 0) return;
    st.cr[slot].c = c; st.cr[slot].r = r; st.cr[slot].mode = 1; st.cr[slot].t = 52;
}
static void update_crumble(void) {
    for (int i = 0; i < 2; i++) {
        const Player *p = &st.pl[i];
        if (!p->active || p->dead || !p->ground) continue;
        int r = (int)floorf(p->y + .01f);
        for (int c = (int)floorf(p->x - .38f); c <= (int)floorf(p->x + .38f); c++) crumble_touch(c, r);
    }
    for (int i = 0; i < SG_MAXCR; i++) {
        Crumb *q = &st.cr[i];
        if (!q->mode) continue;
        if (q->c < 0 || q->c >= st.ww || q->r < 0 || q->r >= st.wh) { q->mode = 0; continue; }
        if (q->mode == 1 && --q->t <= 0) {
            st.tile[q->r][q->c] = 0; q->mode = 2; q->t = 260;
            burst((float)q->c + .5f, (float)q->r + .3f, RGB(210, 235, 255), 8, .08f, .1f);
            snd_play(SND_HIT);
        } else if (q->mode == 2 && --q->t <= 0) {
            if (st.tile[q->r][q->c] == 0) st.tile[q->r][q->c] = SGT_CRUMBLE;
            q->mode = 0;
        }
    }
}
static int crumble_shake(int c, int r) {                    /* frames left of the tile's shaking, or -1 */
    for (int i = 0; i < SG_MAXCR; i++) if (st.cr[i].mode == 1 && st.cr[i].c == c && st.cr[i].r == r) return st.cr[i].t;
    return -1;
}

static void update_icicles(void) {
    const LvLevel *L = stage_def();
    int j = 0;
    for (int k = 0; k < L->nthings && j < MAXIC; k++) {
        const LvThing *t = &L->things[k];
        if (t->kind != SGK_ICICLE) continue;
        Icicle *ic = &st.ic[j++];
        float x = (float)t->x + .5f, top = (float)t->y;
        if (ic->mode == 0) {
            for (int i = 0; i < 2; i++) {
                const Player *p = &st.pl[i];
                if (p->active && !p->dead && fabs_(p->x - x) < 1.2f && p->y > top + 1.6f && p->y < top + 11.f) { ic->mode = 1; ic->t = 24; snd_play(SND_HIT); break; }
            }
        } else if (ic->mode == 1) {
            if (--ic->t <= 0) { ic->mode = 2; ic->y = top + 1.4f; ic->vy = 0.f; }
        } else if (ic->mode == 2) {
            ic->vy += .028f; if (ic->vy > .45f) ic->vy = .45f;
            ic->y += ic->vy;
            int hit = 0;
            for (int i = 0; i < 2; i++) {
                Player *p = &st.pl[i];
                if (!p->active || p->dead || p->invuln > 0) continue;
                if (fabs_(p->x - x) < .5f && ic->y > p->y - 1.f && ic->y - 1.2f < p->y) { kill_player(i); hit = 1; }
            }
            int tl = tile_at((int)x, (int)floorf(ic->y));
            if (hit || is_solid(tl) || is_oneway(tl) || ic->y > (float)st.wh) {
                ic->mode = 3;
                burst(x, ic->y - .2f, RGB(215, 240, 255), 14, .12f, .1f); snd_play(SND_HIT); do_shake(4);
            }
        }
    }
}

static void update_player(int i, const Pad *pad, const Pad *pv) {
    Player *p = &st.pl[i];
    if (!p->active) return;
    p->anim++;
    if (p->dead) {
        if (p->dead < 5000) p->dead++;
        p->vy += .03f; if (p->vy > .5f) p->vy = .5f;
        p->y += p->vy; p->x += p->vx;
        if (p->dead > 110 && p->dead < 5000) { if (p->lives > 0) spawn_player(i); else p->dead = 5000; }
        return;
    }
    if (p->invuln > 0) p->invuln--;
#ifdef RB_GODMODE
    p->invuln = 96;                                         /* test builds: never hit, and drawn (96 / 4 is even: the blink shows it) */
#endif
    int left = pad->left, right = pad->right, down = pad->down;
    int jumpPress = (pad->b && !pv->b) || (pad->up && !pv->up);
    int jumpHeld = pad->b || pad->up;
    int onIce = p->ground && is_ice(tile_at((int)floorf(p->x), (int)floorf(p->y + .01f)));
    float maxv = PH_VX + (p->speedUp ? .04f : 0.f);
    float target = (float)(right - left) * maxv;
    p->vx += (target - p->vx) * (onIce ? .05f : (p->ground ? .6f : .3f));
    if (target == 0.f && !onIce && fabs_(p->vx) < .01f) p->vx = 0.f;
    p->vx += wind_force(p->x);
    if (p->land > 0) p->land--;
    if (p->ground && fabs_(p->vx) > .08f && p->anim % 7 == 0) burst(p->x - (float)p->dir * .3f, p->y - .05f, RGB(235, 244, 255), 1, .03f, .07f);
    if (left && !right) p->dir = -1; else if (right && !left) p->dir = 1;
    if (jumpPress) p->jumpBuf = 7;
    if (p->jumpBuf > 0) p->jumpBuf--;
    if (p->ground) p->coyote = 6; else if (p->coyote > 0) p->coyote--;
    if (p->jumpBuf > 0 && p->coyote > 0 && !p->springT) {
        if (down && p->ground && is_oneway(tile_at((int)floorf(p->x), (int)floorf(p->y + .01f)))) { p->dropTimer = 14; p->vy = .06f; p->ground = 0; p->coyote = 0; p->jumpBuf = 0; }
        else { p->vy = PH_JUMP_VY; p->ground = 0; p->coyote = 0; p->jumpBuf = 0; snd_play(SND_JUMP); burst(p->x, p->y - .05f, RGB(235, 244, 255), 3, .05f, .08f); }
    }
    if (p->springT > 0) p->springT--;                           /* a spring launch flies its full height */
    else if (!jumpHeld && p->vy < -.25f) p->vy = -.25f;         /* let go early: a short hop */
    p->vy += p->vy < 0.f ? PH_GUP : PH_GDN; if (p->vy > PH_VMAX) p->vy = PH_VMAX;
    if (p->dropTimer > 0) p->dropTimer--;
    float vyBefore = p->vy; int wasGround = p->ground;
    if (p->ground) mover_carry(p);
    float yBefore = p->y;
    int fl = move_body(&p->x, &p->y, &p->vx, &p->vy, .4f, 1.0f, p->dropTimer > 0);
    if (!(fl & 1) && vyBefore >= 0.f && p->dropTimer <= 0 && mover_land(p, yBefore)) fl |= 1;
    p->ground = (fl & 1) != 0;
    if (p->ground && !wasGround && vyBefore > .2f) { p->land = 7; burst(p->x, p->y - .05f, RGB(235, 244, 255), 4, .06f, .09f); }
    if (p->shootCd > 0) p->shootCd--;
    if (p->throwAnim > 0) p->throwAnim--;
    if (pad->a && p->shootCd <= 0) {
        add_shot(p->x + (float)p->dir * .55f, p->y - .65f, (float)p->dir * .36f + p->vx * .3f, -.015f, 17 + (p->rangeUp ? 11 : 0), i, 1 + (p->powerUp ? 1 : 0));
        p->shootCd = 10; p->throwAnim = 10;
        snd_play(SND_THROW);
    }
}

static void update_shots(void) {
    for (int i = 0; i < MAXS; i++) {
        Shot *s = &st.sh[i];
        if (!s->used) continue;
        s->x += s->vx; s->y += s->vy; s->life--;
        if (s->owner < 2) s->vy += .004f;
        int solid = is_solid(tile_at((int)floorf(s->x), (int)floorf(s->y)));
        int far = adventure() && fabs_(s->x - (st.camx + TW * .5f)) > TW * .5f + 4.f;
        if (s->life <= 0 || solid || far || s->x < 0.f || s->x > (float)st.ww || s->y > (float)st.wh || s->y < -3.f) {
            if (s->owner < 2) burst(s->x, s->y, RGB(255, 255, 255), 4, .07f, .08f);
            s->used = 0; continue;
        }
        if (s->owner < 2) {
            for (int k = 0; k < MAXE; k++) {
                Enemy *e = &st.en[k];
                if (!e->used || e->state == ES_DYING || e->state == ES_ROLL || e->type == E_GHOST) continue;
                if (fabs_(s->x - e->x) < EHW[e->type] + .15f && s->y > e->y - EH[e->type] - .15f && s->y < e->y + .15f) {
                    hit_enemy_with_snow(e, s); s->used = 0; break;
                }
            }
        } else {
            for (int k = 0; k < 2; k++) {
                Player *p = &st.pl[k];
                if (!p->active || p->dead || p->invuln > 0) continue;
                if (fabs_(s->x - p->x) < .5f && s->y > p->y - 1.05f && s->y < p->y + .1f) { kill_player(k); s->used = 0; break; }
            }
        }
    }
}

static void boss_fire(Enemy *e, float px, int n, float spread) {
    float dir = px > e->x ? 1.f : -1.f;
    e->dir = dir > 0.f ? 1 : -1;
    for (int k = 0; k < n; k++) {
        float vy = n == 1 ? 0.f : (float)(k - (n - 1) / 2) * spread;
        add_shot(e->x + dir * 1.3f, e->y - 1.2f, dir * .12f * dmul(), vy, 130, 2, e->kind == 1 ? 2 : 1);   /* power 2: drawn as ice */
    }
    snd_play(SND_BOSS);
}

static void update_enemy(Enemy *e) {
    e->anim++;
    if (e->hitFlash > 0) e->hitFlash--;
    float hw = EHW[e->type], h = EH[e->type];
    float px = e->x, py = e->y;
    int target = nearest_player(e->x, e->y, &px, &py);
    int level = st.level < 15 ? st.level : 15;
    float speed = (.055f + (float)level * .004f) * dmul();
    switch (e->state) {
    case ES_DYING:
        e->vy += .03f; e->x += e->vx; e->y += e->vy; e->timer++;
        if (e->timer > 50 || e->y > st.wh + 3) e->used = 0;
        return;
    case ES_FROZEN: {
        e->vy += .03f; if (e->vy > .5f) e->vy = .5f;
        e->vx = 0.f;
        move_body(&e->x, &e->y, &e->vx, &e->vy, .5f, .95f, 0);
        if (--e->timer <= 0) { e->state = ES_NORMAL; e->snow = 1; e->snowTimer = 140; e->dir = irnd(2) ? 1 : -1; }
        for (int i = 0; i < 2; i++) {                      /* walking into a snowball pushes it */
            Player *p = &st.pl[i];
            if (!p->active || p->dead) continue;
            if (fabs_(p->x - e->x) < .9f && p->y > e->y - 1.f && p->y - 1.f < e->y && fabs_(p->vx) > .03f && (e->x - p->x) * p->vx > 0.f) {
                start_roll(e, p->vx > 0.f ? 1 : -1, i); break;
            }
        }
        return;
    }
    case ES_ROLL: {
        e->vy += .03f; if (e->vy > .5f) e->vy = .5f;
        e->vx = (float)e->dir * .28f;
        int fl = move_body(&e->x, &e->y, &e->vx, &e->vy, .5f, .95f, 0);
        e->timer++;
        if (fl & 2) { e->dir = -e->dir; e->bounces++; snd_play(SND_HIT); do_shake(3); burst(e->x, e->y - .4f, RGB(220, 240, 255), 6, .09f, .1f); }
        for (int k = 0; k < MAXE; k++) {
            Enemy *o = &st.en[k];
            if (o == e || !o->used || o->state == ES_DYING || o->state == ES_ROLL || o->type == E_GHOST) continue;
            if (fabs_(o->x - e->x) < .5f + EHW[o->type] && o->y > e->y - .95f - .1f && o->y - EH[o->type] < e->y) {
                if (o->type == E_BOSS) { o->hp -= 4; o->hitFlash = BOSS_HIT_POSE; do_shake(8); burst(o->x, o->y - 1.f, RGB(255, 255, 255), 12, .13f, .12f); if (o->hp <= 0) kill_enemy(o, e->owner, 1); shatter_ball(e); return; }
                if (o->state == ES_FROZEN) { start_roll(o, e->dir, e->owner); continue; }
                e->combo++;
                kill_enemy(o, e->owner, e->combo);
            }
        }
        if (e->bounces >= 4 || e->timer > 420) shatter_ball(e);
        return;
    }
    default: break;
    }
    /* ---- ES_NORMAL ---- */
    if (e->snow > 0 && --e->snowTimer <= 0) { e->snow--; e->snowTimer = 110; }
    float slow = 1.f - .22f * (float)e->snow;
    float kb = e->kb; e->kb *= .8f; if (fabs_(e->kb) < .005f) e->kb = 0.f;
    switch (e->type) {
    case E_WALK: case E_SHOOT: {
        float sp = e->type == E_SHOOT ? speed * .6f : speed;
        e->vx = (float)e->dir * sp * slow + kb;
        e->vy += .03f; if (e->vy > .5f) e->vy = .5f;
        int fl = move_body(&e->x, &e->y, &e->vx, &e->vy, hw, h, 0);
        e->ground = (fl & 1) != 0;
        if (fl & 2) e->dir = -e->dir;
        else if (e->ground) {
            int ahead = tile_at((int)floorf(e->x + (float)e->dir * (hw + .15f)), (int)floorf(e->y + .05f));
            if (ahead == 0 && irnd(100) < 70) e->dir = -e->dir;
        }
        if (--e->timer <= 0) {
            e->timer = 45 + irnd(70);
            if (e->ground && target >= 0 && py < e->y - 1.5f) { e->vy = -.58f; e->dir = px > e->x ? 1 : -1; }
            else if (e->ground && irnd(100) < 25) e->dir = -e->dir;
        }
        if (e->type == E_SHOOT && --e->cd <= 0 && target >= 0 && fabs_(py - e->y) < 1.6f && fabs_(px - e->x) < 13.f && e->snow == 0) {
            e->dir = px > e->x ? 1 : -1;
            add_shot(e->x + (float)e->dir * .6f, e->y - .5f, (float)e->dir * .13f * dmul(), 0.f, 100, 2, 3);   /* power 3: drawn as the spitter's ice */
            e->cd = 95 + irnd(50);
        }
        break; }
    case E_CHARGE: {
        e->vy += .03f; if (e->vy > .5f) e->vy = .5f;
        if (e->timer > 0) { e->timer--; e->vx = kb; }                       /* stunned after hitting a wall */
        else if (e->cd > 0) { e->cd--; e->vx = (float)e->dir * .16f * dmul() * slow + kb; }
        else e->vx = (float)e->dir * speed * .55f * slow + kb;
        int fl = move_body(&e->x, &e->y, &e->vx, &e->vy, hw, h, 0);
        e->ground = (fl & 1) != 0;
        if (fl & 2) {
            if (e->cd > 0) { e->cd = 0; e->timer = 70; snd_play(SND_HIT); do_shake(4); burst(e->x, e->y - .6f, RGB(255, 255, 255), 6, .08f, .1f); }
            e->dir = -e->dir;
        } else if (e->ground && e->cd == 0) {
            int ahead = tile_at((int)floorf(e->x + (float)e->dir * (hw + .15f)), (int)floorf(e->y + .05f));
            if (ahead == 0) e->dir = -e->dir;
        }
        if (e->timer <= 0 && e->cd == 0 && e->ground && target >= 0 && fabs_(py - e->y) < 1.2f && fabs_(px - e->x) < 11.f && (px > e->x) == (e->dir > 0) && e->snow == 0) e->cd = 80;
        break; }
    case E_HOP: {
        e->vy += .03f; if (e->vy > .5f) e->vy = .5f;
        if (e->ground) e->vx = 0.f;
        int fl = move_body(&e->x, &e->y, &e->vx, &e->vy, hw, h, 0);
        e->ground = (fl & 1) != 0;
        if (fl & 2) e->vx = -e->vx;
        if (e->ground && --e->timer <= 0) {
            e->timer = 32 + irnd(34);
            e->dir = target >= 0 ? (px > e->x ? 1 : -1) : (irnd(2) ? 1 : -1);
            if (adventure() && e->pr > 0.f && fabs_(e->x + (float)e->dir * 1.6f - e->hx) > e->pr) e->dir = e->x < e->hx ? 1 : -1;
            e->vy = (target >= 0 && py < e->y - 1.5f) ? -.62f : -.44f;
            e->vx = (float)e->dir * .10f * dmul() * slow;
        }
        break; }
    case E_FLY: {
        if (e->y < e->by) e->y += .06f;
        else e->y = e->by + sinf((float)e->anim * .07f) * .9f;
        e->x += (float)e->dir * (.03f + (float)level * .002f) * dmul() * slow;
        if (e->x < 1.f) { e->x = 1.f; e->dir = 1; }
        if (e->x > st.ww - 1.f) { e->x = st.ww - 1.f; e->dir = -1; }
        if (--e->timer <= 0) {
            e->timer = 90 + irnd(90); e->by = target >= 0 ? (py - 1.f) : 3.f + (float)irnd(9);
            if (e->by < 3.f) e->by = 3.f;
            if (e->by > st.wh - 4.f) e->by = st.wh - 4.f;
            if (target >= 0) e->dir = px > e->x ? 1 : -1;
        }
        break; }
    case E_GHOST: {                                         /* "hurry up": passes through everything, cannot be hurt */
        if (target >= 0) {
            float dx = px - e->x, dy = (py - .5f) - e->y, d = sqrtf(dx * dx + dy * dy) + .001f;
            e->x += dx / d * .048f * dmul(); e->y += dy / d * .048f * dmul() + sinf((float)e->anim * .1f) * .01f;
            e->dir = dx >= 0.f ? 1 : -1;
        }
        break; }
    case E_BOSS: {
        int half = e->hp * 2 < boss_max(e->kind, st.level);
        if (e->kind == 1) {                                  /* frost wizard: hovers, teleports, fires spreads */
            if (e->y < 6.5f) e->y += .05f; else e->y = 6.5f + sinf((float)e->anim * .05f) * .8f;
            e->x += (float)e->dir * .03f * dmul();
            if (e->x < 4.f) { e->x = 4.f; e->dir = 1; }
            if (e->x > st.ww - 4.f) { e->x = st.ww - 4.f; e->dir = -1; }
            if (--e->timer <= 0) {
                e->timer = half ? 70 : 100;
                if (target >= 0) boss_fire(e, px, half ? 5 : 3, .05f);
                if (irnd(100) < 45) { burst(e->x, e->y - 1.2f, RGB(150, 200, 255), 18, .15f, .12f); e->x = 5.f + (float)irnd(22); burst(e->x, e->y - 1.2f, RGB(150, 200, 255), 18, .15f, .12f); snd_play(SND_FREEZE); }
            }
            break;
        }
        int phase = 1;
        if (adventure()) {                                   /* the final stage's rage ogre: three phases by health */
            int mx = boss_max(e->kind, st.level);
            phase = e->hp * 3 > mx * 2 ? 1 : (e->hp * 3 > mx ? 2 : 3);
            if (phase > st.bossPhase) {
                st.bossPhase = phase; st.bannerTimer = 100; e->timer = 12;
                do_shake(22); snd_play(SND_BOSS); burst(e->x, e->y - 1.5f, RGB(255, 120, 60), 30, .2f, .14f);
            }
        }
        float bs = (e->kind == 2 ? .05f : .035f) * (adventure() ? (phase == 1 ? 1.f : (phase == 2 ? 1.35f : 1.7f)) : (half ? 1.5f : 1.f)) * dmul();
        e->vx = (float)e->dir * bs;
        e->vy += .03f; if (e->vy > .5f) e->vy = .5f;
        int fl = move_body(&e->x, &e->y, &e->vx, &e->vy, hw, h, 0);
        e->ground = (fl & 1) != 0;
        if (fl & 2) e->dir = -e->dir;
        if (--e->timer <= 0) {
            e->timer = adventure() ? (phase == 1 ? 60 : (phase == 2 ? 46 : 34)) + irnd(40) : (e->kind == 2 ? 60 : 85) + irnd(50);
            if (target >= 0) e->dir = px > e->x ? 1 : -1;
            if (e->ground && irnd(100) < (phase == 3 ? 50 : 40)) e->vy = -.64f;
            else boss_fire(e, px, adventure() ? phase + 1 : (e->kind == 2 ? (half ? 3 : 2) : 1), .045f);
        }
        break; }
    }
    /* adventure: keep to the patrol (the pack's patrol_tiles); a monster that falls in a pit is gone */
    if (adventure() && e->pr > 0.f && e->type != E_FLY && e->type != E_BOSS && fabs_(e->x - e->hx) > e->pr && (e->x - e->hx) * (float)e->dir > 0.f) e->dir = -e->dir;
    if (e->y > st.wh + 2 && e->type != E_GHOST) { if (adventure()) e->used = 0; else e->y = -1.f; }   /* arena: back in at the top */
}

static void update_items(void) {
    for (int i = 0; i < MAXI; i++) {
        Item *it = &st.it[i];
        if (!it->used) continue;
        it->vy += .03f; if (it->vy > .4f) it->vy = .4f;
        float vx = 0.f;
        move_body(&it->x, &it->y, &vx, &it->vy, .3f, .5f, 0);
        if (--it->life <= 0) { it->used = 0; continue; }
        for (int k = 0; k < 2; k++) {
            Player *p = &st.pl[k];
            if (!p->active || p->dead) continue;
            if (fabs_(p->x - it->x) < .6f && p->y > it->y - .5f && p->y - 1.f < it->y) {
                switch (it->type) {
                case IT_SPEED: p->speedUp = 1; break;
                case IT_RANGE: p->rangeUp = 1; break;
                case IT_POWER: p->powerUp = 1; break;
                default: give_score(k, 500, it->x, it->y - 1.f); break;
                }
                if (it->type != IT_FRUIT) popup(it->x, it->y - 1.f, 0);
                snd_play(SND_ITEM);
                burst(it->x, it->y - .3f, RGB(255, 230, 120), 10, .1f, .1f);
                it->used = 0; break;
            }
        }
    }
}

static void game_over(void) {
    st.screen = S_OVER; st.timer = 0;
    int t = total_score();
    if ((uint32_t)t > save.hi) { save.hi = (uint32_t)t; st.newBest = 1; }
    if (adventure()) { uint32_t reached = (uint32_t)st.level + (st.won ? 1u : 0u); if (reached > save.advBest) save.advBest = reached; }
    else if ((uint32_t)st.level > save.bestLevel) save.bestLevel = (uint32_t)st.level;
    snd_music(MUSIC_OFF);
    snd_play(st.won ? SND_CLEAR : SND_DIE);
}

static int skin_unlocked(int s) { return s >= 0 && s < NSKINS && (int)save.bestLevel >= SKIN_UNLOCK[s]; }

static void apply_menu_choices(void) {
    if (!skin_unlocked(st.skin[0])) st.skin[0] = 0;
    st.skin[1] = st.skin[0] == 1 ? 0 : 1;
    save.skin = (uint32_t)st.skin[0]; save.diff = (uint32_t)st.difficulty;
}

static void start_game(int two) {
    st.mode = save.mode == M_ADVENTURE ? M_ADVENTURE : M_ARENA;
    memset(st.pl, 0, sizeof st.pl);
    for (int i = 0; i < 2; i++) { st.pl[i].lives = DIFF_LIVES[st.difficulty]; st.pl[i].dir = i == 0 ? 1 : -1; st.pl[i].nextLife = 30000; }
    st.pl[0].active = 1; st.pl[1].active = two ? 1 : 0;
    st.hold = two; st.newBest = 0; st.won = 0; st.hitstop = 0; st.shake = 0;
    apply_menu_choices();
    memset(st.pt, 0, sizeof st.pt); memset(st.pop, 0, sizeof st.pop);
    st.seed = rnd() ^ ((uint32_t)st.frame * 2654435761u) ^ ((uint32_t)st.timer << 13);     /* what you did on the title makes the run */
#ifdef RB_SEED
    st.seed = RB_SEED;                                       /* test builds: a fixed seed */
#endif
    if (!st.seed) st.seed = 1u;
    memset(st.cr, 0, sizeof st.cr); memset(st.ic, 0, sizeof st.ic); st.mtime = 0;
#if defined(RB_START_STAGE)
    st.mode = M_ADVENTURE; start_level(RB_START_STAGE);        /* test builds: straight into an adventure stage */
#elif defined(RB_START_LEVEL)
    st.mode = M_ARENA; start_level(RB_START_LEVEL);
#else
    start_level(1);
#endif
    st.screen = S_PLAY;
    snd_play(SND_START);
}

static void to_title(void) {
    st.screen = S_TITLE; st.timer = 0;
    if (st.skin[0] < 0 || st.skin[0] >= NSKINS) st.skin[0] = 0;
    memset(st.pl, 0, sizeof st.pl);
    memset(st.en, 0, sizeof st.en); memset(st.sh, 0, sizeof st.sh); memset(st.it, 0, sizeof st.it);
    st.level = 1; st.mode = M_ARENA; build_tiles(1);
    st.camx = st.camy = 0.f;
    memset(st.tile, 0, sizeof st.tile);                      /* the title stands on the floor only */
    for (int c = 0; c < TW; c++) st.tile[TH - 1][c] = 1;
    st.hold = st.hold ? 1 : 0;
    st.hitstop = 0; st.shake = 0;
    apply_menu_choices();
    snd_music(MUSIC_TITLE);
}

#ifdef RB_SELFTEST
/* Test builds: a simple bot plays player 1 (walks to the nearest monster, jumps up to it, throws snow, pushes snowballs). */
static Pad bot_pad(int i) {
    Pad p = {0};
    Player *pl = &st.pl[i];
    if (!pl->active || pl->dead) return p;
    Enemy *best = NULL; float bd = 1e9f; int frozen = 0;
    for (int k = 0; k < MAXE; k++) {
        Enemy *e = &st.en[k];
        if (!e->used || e->state == ES_DYING || e->state == ES_ROLL) continue;
        float d = fabs_(e->x - pl->x) + fabs_(e->y - pl->y) * 1.5f - (e->state == ES_FROZEN ? 6.f : 0.f);
        if (d < bd) { bd = d; best = e; frozen = e->state == ES_FROZEN; }
    }
    if (!best) return p;
    float dx = best->x - pl->x, dy = best->y - pl->y;
    if (frozen) { if (dx > .3f) p.right = 1; else if (dx < -.3f) p.left = 1; }
    else {
        if (fabs_(dx) > 3.5f) { if (dx > 0) p.right = 1; else p.left = 1; }
        else if (fabs_(dx) < 1.0f) { if (st.frame & 32) p.left = 1; else p.right = 1; }
        if (dx > 0.2f && pl->dir < 0 && fabs_(dx) <= 3.5f) p.right = 1;
        if (dx < -0.2f && pl->dir > 0 && fabs_(dx) <= 3.5f) p.left = 1;
        if (fabs_(dy) < 1.4f) p.a = 1;
    }
    /* hop all the time: platforms are four tiles apart and a hop reaches the next one when the bot happens to stand under it */
    if (pl->ground && (st.frame % 34) < 2) p.b = 1;
    if (!p.b && !pl->ground && (st.frame % 34) < 20) p.b = 1;           /* hold jump while rising: full height */
    if (dy > 1.5f && pl->ground && (st.frame % 70) < 3) { p.down = 1; p.b = 1; }
    return p;
}

/* Adventure test bot: heads for the door, jumps walls, gaps and spikes, shoots what stands in the way, pushes snowballs,
 * and fights the boss with the arena bot. Its memory is outside the game state (test builds only). */
static float botLastX[2];
static int botStuck[2], botJumpHold[2], botIdle[2], botRush[2];
static float botBest[2];
static Pad adv_bot_pad(int i) {
    Pad p = {0};
    Player *pl = &st.pl[i];
    if (!pl->active || pl->dead) { botStuck[i] = 0; return p; }
    if (st.arenaOn == 1) return bot_pad(i);
    const LvLevel *L = stage_def();
    float goal = (float)L->exitX + .5f;
    int dir = goal > pl->x ? 1 : -1;
    if (fabs_(goal - pl->x) < .3f) dir = 0;
    /* monsters ahead (level with us or up to five rows above): hold back and shoot them, jumping to reach the high ones;
     * frozen ones on our level are walked into, which rolls them away */
    int hop = 0;
    if (pl->x > botBest[i] + .5f || st.levelFrames < 5) { botBest[i] = pl->x; botIdle[i] = 0; } else if (++botIdle[i] > 420) { botRush[i] = 240; botIdle[i] = 0; }   /* no progress: ignore the monsters for a while */
    if (botRush[i] > 0) botRush[i]--;
    {   /* on or next to crumbling ledges: no time to fight */
        int fy = (int)floorf(pl->y + .01f), cx = (int)floorf(pl->x);
        if (tile_at(cx, fy) == SGT_CRUMBLE || tile_at(cx + 1, fy) == SGT_CRUMBLE || tile_at(cx + 2, fy) == SGT_CRUMBLE || tile_at(cx + 3, fy) == SGT_CRUMBLE) botRush[i] = 30;
    }
    for (int k = 0; k < MAXE && !botRush[i]; k++) {
        const Enemy *e = &st.en[k];
        if (!e->used || e->state == ES_DYING || e->state == ES_ROLL || e->type == E_GHOST) continue;
        float dx = e->x - pl->x, dy = e->y - pl->y;
        if (dx * (float)(dir ? dir : 1) < -1.f || fabs_(dx) > 7.f || dy > 1.6f || dy < -5.f) continue;
        if (e->state == ES_FROZEN) { if (fabs_(dy) > 1.2f) hop = 1; continue; }
        int face = dx > 0.f ? 1 : -1;
        if (fabs_(dx) < 2.6f) dir = -face;                                         /* too close: back off */
        else dir = pl->dir == face ? 0 : face;                                     /* hold here, facing it */
        if (fabs_(e->y - .5f - (pl->y - .65f)) < .9f) p.a = (st.frame & 1);         /* at its height: throw */
        if (dy < -1.f) hop = 1;                                                    /* above us: jump to get level with it */
        break;
    }
    if (dir > 0) p.right = 1; else if (dir < 0) p.left = 1;
    int fx = (int)floorf(pl->x), feet = (int)floorf(pl->y + .01f), d = dir ? dir : pl->dir;
    int want = 0, plate = 0, board = 0;
    if (pl->ground && !tile_support(pl)) {                                                                   /* on a moving platform: wait for a level block ahead */
        plate = 1; dir = 0; p.right = p.left = 0;
        for (int m = 0; m < GS.nmovers; m++) {
            float mx, my; sg_mover_pos(&GS.movers[m], st.mtime, &mx, &my);
            float hw = (float)GS.movers[m].w * .5f;
            if (fabs_(my - pl->y) < .2f && fabs_(mx - pl->x) < hw + .4f && is_solid(tile_at((int)floorf(mx + hw + .5f), feet))) { dir = 1; p.right = 1; }
        }
    }
    if (pl->ground && dir && !plate) {
        int ahead = (int)floorf(pl->x + (float)d * .9f);
        if (is_solid(tile_at(ahead, feet - 1)) || is_solid(tile_at(ahead, feet - 2))) want = 1;           /* a wall or a step up */
        int edge = (int)floorf(pl->x + (float)d * .3f);                                                       /* jump as late as the feet allow: the farthest jump */
        for (int m = 0; m < GS.nmovers && d > 0; m++) {                                                       /* a moving platform at our level: step on when it is here */
            float mx, my; sg_mover_pos(&GS.movers[m], st.mtime, &mx, &my);
            float left = mx - (float)GS.movers[m].w * .5f;
            if (fabs_(my - pl->y) < .7f && left > pl->x - 1.f && left < pl->x + 15.f && tile_at(edge, feet) == 0) { board = 1; if (left - pl->x > 1.1f) { dir = 0; p.right = 0; } }
        }
        if (tile_at(edge, feet) == 0 && !board) {                                                             /* the ground ends */
            int below = 0;
            for (int r = feet; r < feet + 3 && r < st.wh; r++) if (tile_at(edge, r)) below = 1;
            if (!below) want = 1;
        }
        for (int k = 0; k < L->nthings; k++) {                                                                /* spikes ahead */
            const LvThing *t = &L->things[k];
            if (t->kind == LV_SPIKES && t->y == feet && ((float)t->x + .5f - pl->x) * (float)d > .6f && ((float)t->x + .5f - pl->x) * (float)d < 1.9f) want = 1;
        }
        (void)fx;
    }
    if (fabs_(pl->x - botLastX[i]) < .02f && pl->ground && !hop && dir) botStuck[i]++; else botStuck[i] = 0;
    botLastX[i] = pl->x;
    if (botStuck[i] > 40 && !plate && !board) { want = 1; botStuck[i] = 0; }
    if (hop && pl->ground && (st.frame % 24) == 0) want = 1;
    if (want && pl->ground) botJumpHold[i] = 30;
    if (botJumpHold[i] > 0) { p.b = 1; botJumpHold[i]--; }
    if (!pl->ground && pl->vy < 0.f) p.b = 1;                                                                 /* full height */
    if (pl->ground && prev[i].b) p.b = 0;                                                                     /* a jump needs a fresh press */
    return p;
}
#endif

/* ---- per-frame update ---- */
static int enemies_left(void) {
    int n = 0;
    for (int i = 0; i < MAXE; i++) if (st.en[i].used && st.en[i].type != E_GHOST) n++;
    return n;
}

static void update_world(void) {
    for (int i = 0; i < MAXPT; i++) {
        Part *q = &st.pt[i];
        if (q->life <= 0) continue;
        q->x += q->vx; q->y += q->vy; q->vy += .004f; q->vx *= .97f; q->life--;
    }
    for (int i = 0; i < MAXPOP; i++) if (st.pop[i].life > 0) st.pop[i].life--;
    if (st.shake > 0) st.shake--;
    if (st.bannerTimer > 0) st.bannerTimer--;
}

/* ---- adventure: camera, things, the boss arena ---- */
#define SPRING_VY PH_SPRING_VY               /* tools/originals-levels/sg_check.c checks the stages with this */

static void update_camera(int snap) {
    float sx = 0.f, sy = 0.f; int n = 0;
    for (int i = 0; i < 2; i++) {
        const Player *p = &st.pl[i];
        if (!p->active || p->lives <= 0 || p->dead) continue;
        sx += p->x; sy += p->y; n++;
    }
    if (!n) return;
    float tx = sx / (float)n - 13.f, ty = sy / (float)n - 11.f;          /* the players a little left of centre */
    if (st.arenaOn == 1) tx = (float)(st.ww - TW);                       /* the boss arena: the camera stands still */
    if (tx < 0.f) tx = 0.f;
    if (tx > (float)(st.ww - TW)) tx = (float)(st.ww - TW);
    if (ty < 0.f) ty = 0.f;
    if (ty > (float)(st.wh - TH)) ty = (float)(st.wh - TH);
    if (snap) { st.camx = tx; st.camy = ty; return; }
    st.camx += (tx - st.camx) * .12f;
    st.camy += (ty - st.camy) * .08f;
}

static void snap_camera(void) { update_camera(1); }

static void victory(void) { st.won = 1; game_over(); }

static int near_thing(const Player *p, float cx, float feet, float rx, float h) {
    return fabs_(p->x - cx) < rx && p->y > feet - h && p->y - 1.f < feet;
}

#define CHEST_ARG 5                            /* LV_DECO arg of the treasure chest (levels_data.h) */

static void adv_update(void) {
    const LvLevel *L = stage_def();
    update_icicles();
    for (int i = 0; i < 2; i++) {
        Player *p = &st.pl[i];
        if (!p->active || p->dead || p->lives <= 0) continue;
        if (p->y > st.wh + .5f) { p->invuln = 0; kill_player(i); continue; }          /* fell in a pit */
        for (int k = 0; k < L->nthings; k++) {
            const LvThing *t = &L->things[k];
            float tx = (float)t->x + .5f;
            if ((t->kind == LV_COIN || t->kind == LV_GEM) && !taken_get(k) && near_thing(p, tx, (float)t->y + 1.f, .6f, 1.f)) {
                taken_set(k);
                give_score(i, t->kind == LV_GEM ? 1000 : 100, tx, (float)t->y);
                burst(tx, (float)t->y + .5f, t->kind == LV_GEM ? RGB(255, 90, 180) : RGB(255, 220, 80), 10, .1f, .09f);
                snd_play(SND_ITEM);
            } else if (t->kind == LV_DECO && t->arg == CHEST_ARG && !taken_get(k) && near_thing(p, tx, (float)t->y, .6f, 1.f)) {
                taken_set(k);                                       /* the treasure chest: a bonus, then it is empty */
                give_score(i, 500, tx, (float)t->y - 1.f);
                burst(tx, (float)t->y - .5f, RGB(255, 210, 90), 24, .16f, .12f);
                snd_play(SND_ITEM);
            } else if (t->kind == LV_SPIKES && p->invuln <= 0 && fabs_(p->x - tx) < .72f && p->y > (float)t->y - .55f && p->y - 1.f < (float)t->y) {
                kill_player(i); break;
            } else if (t->kind == LV_SPRING && p->ground && fabs_(p->x - tx) < .55f && fabs_(p->y - (float)t->y) < .01f) {
                p->vy = SPRING_VY; p->ground = 0; p->springT = 24; p->coyote = 0; p->jumpBuf = 0;
                snd_play(SND_JUMP); burst(tx, (float)t->y - .2f, RGB(255, 120, 120), 5, .07f, .08f);
            }
        }
        if (p->dead) continue;
        if (!st.cpReached && p->x > (float)L->cpX && p->y > (float)L->cpY - 5.f && p->y < (float)L->cpY + 1.f) {   /* passed, at any jump height */
            st.cpReached = 1; snd_play(SND_ITEM);
            burst((float)L->cpX + .5f, (float)L->cpY - 1.2f, RGB(120, 255, 160), 16, .12f, .1f);
        }
        if (st.exitOpen && st.phase == PH_ACTION && near_thing(p, (float)L->exitX + .5f, (float)L->exitY, .8f, 1.7f)) {
            st.phase = PH_CLEAR; st.timer = 0;
            int secs = ((int)L->seconds * 60 - st.levelFrames) / 60; if (secs < 0) secs = 0;
            st.tallyTime = secs * 100; st.tallyNoMiss = st.missed ? 0 : 3000;
            for (int j = 0; j < 2; j++) if (st.pl[j].active && st.pl[j].lives > 0 && !st.pl[j].dead) give_score(j, st.tallyTime + st.tallyNoMiss, st.pl[j].x, st.pl[j].y - 1.5f);
            for (int j = 0; j < MAXE; j++) if (st.en[j].used && st.en[j].type == E_GHOST) st.en[j].used = 0;
            memset(st.sh, 0, sizeof st.sh);
            snd_play(SND_CLEAR);
            return;
        }
    }
    /* the boss arena: entering it shuts the gate behind the players and calls the rage ogre */
    if (L->arenaX1 && st.arenaOn == 0) {
        int in = 0;
        for (int i = 0; i < 2; i++) if (st.pl[i].active && !st.pl[i].dead && st.pl[i].lives > 0 && st.pl[i].x > (float)L->arenaX0 + 1.5f) in = 1;
        if (in) {
            st.arenaOn = 1;
            for (int r = 0; r < L->bossY; r++) st.tile[r][L->arenaX0 - 1] = 1;
            for (int i = 0; i < 2; i++) if (st.pl[i].active && st.pl[i].x < (float)L->arenaX0 + .5f) st.pl[i].x = (float)L->arenaX0 + .6f;
            Enemy *b = new_enemy(E_BOSS, (float)L->bossX + .5f, -3.f);
            if (b) { b->kind = 2; b->hp = boss_max(2, st.level); b->dir = -1; }
            st.minionTimer = 600; st.bossPhase = 1; st.bannerTimer = 100;
            do_shake(16); snd_play(SND_BOSS);
        }
    }
    if (st.arenaOn == 1) {
        int boss = 0;
        for (int i = 0; i < MAXE; i++) if (st.en[i].used && st.en[i].type == E_BOSS) boss = 1;
        if (!boss) {                                      /* the rage ogre is down: the exit opens */
            st.arenaOn = 2; st.exitOpen = 1;
            burst((float)L->exitX + .5f, (float)L->exitY - 1.f, RGB(255, 255, 200), 24, .15f, .12f);
            snd_play(SND_CLEAR);
        }
    }
}

static void update_play(const Pad pads[2]) {
    /* the second player can jump in at any time */
    if (!st.pl[1].active && pads[1].start && !prev[1].start) {
        st.pl[1].active = 1; st.pl[1].lives = DIFF_LIVES[st.difficulty]; st.pl[1].score = 0; st.pl[1].nextLife = 30000; spawn_player(1); snd_play(SND_START);
    }
    for (int i = 0; i < 2; i++) {
        if (st.pl[i].active && pads[i].start && !prev[i].start) { st.screen = S_PAUSE; st.timer = 0; snd_play(SND_PAUSE); return; }
    }
    if (st.hitstop > 0) { st.hitstop--; return; }           /* a hit freezes the game for a moment: it makes hits feel heavy */
    st.mtime++;
    if (st.comboTimer > 0 && --st.comboTimer == 0) st.comboCount = 0;
    for (int i = 0; i < 2; i++) update_player(i, &pads[i], &prev[i]);
    if (adventure()) {
        update_camera(0);
        for (int i = 0; i < 2; i++) {                    /* both players stay on the screen */
            Player *p = &st.pl[i];
            if (!p->active || p->dead) continue;
            float lo = st.camx + .4f, hi = st.camx + (float)TW - .4f;
            if (p->x < lo) { p->x = lo; if (p->vx < 0.f) p->vx = 0.f; }
            if (p->x > hi) { p->x = hi; if (p->vx > 0.f) p->vx = 0.f; }
        }
    }
#ifdef RB_TRACE
    if (st.frame % 4 == 0) fprintf(stderr, "f%d p0 x=%.2f y=%.2f vy=%.3f ground=%d phase=%d\n", st.frame, st.pl[0].x, st.pl[0].y, st.pl[0].vy, st.pl[0].ground, st.phase);
    if (st.frame % 200 == 0) for (int k = 0; k < MAXE; k++) if (st.en[k].used) fprintf(stderr, "  enemy type %d state %d at %.1f,%.1f\n", st.en[k].type, st.en[k].state, st.en[k].x, st.en[k].y);
#endif
    update_crumble();
    if (adventure()) adv_spawn();
    update_shots();
    for (int i = 0; i < MAXE; i++) {
        Enemy *e = &st.en[i];
        if (!e->used) continue;
        if (adventure() && e->state == ES_NORMAL && e->type != E_BOSS && e->type != E_GHOST && e->x < st.camx - 30.f) { e->used = 0; continue; }   /* far behind: gone for good */
        if (adventure() && e->state == ES_NORMAL && e->type != E_BOSS && e->type != E_GHOST && fabs_(e->x - (st.camx + TW * .5f)) > TW * .5f + 6.f) continue;   /* asleep off screen */
        if (st.phase == PH_ACTION || e->state == ES_DYING) update_enemy(e);
    }
    update_items();
    if (st.phase == PH_INTRO) {
        if (++st.timer > 110) { st.phase = PH_ACTION; st.timer = 0; if (!adventure() && is_boss_level(st.level)) snd_play(SND_BOSS); }
        return;
    }
    if (st.phase == PH_CLEAR) {
        if (++st.timer >= 230) { if (adventure() && st.level >= LV_COUNT) victory(); else start_level(st.level + 1); }
        return;
    }
    st.levelFrames++;
    /* monsters drop in from the top, one after another */
    if (st.qi < st.qn && --st.spawnTimer <= 0) {
        int type = st.queue[st.qi++];
        Enemy *e = new_enemy(type, type == E_BOSS ? 16.f : 3.f + (float)irnd(26), type == E_BOSS ? -3.f : -1.f);
        if (e && type == E_FLY) e->y = -.5f;
        if (e && type == E_BOSS && e->kind == 1) { e->y = -2.f; e->by = 6.5f; }
        int gap = 40 - st.level; if (gap < 14) gap = 14;
        st.spawnTimer = gap + irnd(24);
    }
    if (boss_fight() && st.qi >= st.qn && --st.minionTimer <= 0) {
        st.minionTimer = 600;
        int alive = 0, boss = 0;
        for (int i = 0; i < MAXE; i++) if (st.en[i].used) { alive++; if (st.en[i].type == E_BOSS && st.en[i].state != ES_DYING) boss = 1; }
        float mx = adventure() ? (float)stage_def()->arenaX0 + 2.f + (float)irnd(stage_def()->arenaX1 - stage_def()->arenaX0 - 5) : 2.f + (float)irnd(28);
        if (boss && alive < 4) new_enemy(irnd(2) ? E_WALK : (st.level >= 10 ? E_SHOOT : E_HOP), mx, -1.f);
    }
    /* too slow: a ghost that can't be hurt hunts the players down */
    int limit = adventure() ? (int)stage_def()->seconds * 60 : 2700;   /* adventure: the stage's time limit */
    if (!st.hurry && !boss_fight() && st.levelFrames > limit) {
        st.hurry = 1; st.bannerTimer = 110; snd_play(SND_BOSS);
        float cx = st.camx + TW * .5f, tx, ty; int tg = nearest_player(cx, 9.f, &tx, &ty);
        Enemy *g = new_enemy(E_GHOST, tg >= 0 && tx < cx ? st.camx + 30.f : st.camx + 2.f, st.camy + 2.f);
        if (g) g->dir = 1;
    }
    /* contact with a monster hurts */
    for (int i = 0; i < 2; i++) {
        Player *p = &st.pl[i];
        if (!p->active || p->dead || p->invuln > 0) continue;
        for (int k = 0; k < MAXE; k++) {
            Enemy *e = &st.en[k];
            if (!e->used || e->state != ES_NORMAL) continue;
            float hw = EHW[e->type] - .06f, h = EH[e->type] - .12f;
            float ey = e->y;
            if (fabs_(p->x - e->x) < hw + .26f && p->y > ey - h && p->y - .95f < ey - .05f) { kill_player(i); break; }
        }
    }
    if (adventure()) adv_update();
    else if (st.qi >= st.qn && enemies_left() == 0) {
        st.phase = PH_CLEAR; st.timer = 0;
        int secs = (3600 - st.levelFrames) / 60; if (secs < 0) secs = 0;
        st.tallyTime = secs * 100; st.tallyNoMiss = st.missed ? 0 : 3000;
        for (int i = 0; i < 2; i++) if (st.pl[i].active && st.pl[i].lives > 0 && !st.pl[i].dead) give_score(i, st.tallyTime + st.tallyNoMiss, st.pl[i].x, st.pl[i].y - 1.5f);
        for (int i = 0; i < MAXE; i++) if (st.en[i].used && st.en[i].type == E_GHOST) st.en[i].used = 0;
        memset(st.sh, 0, sizeof st.sh);
        snd_play(SND_CLEAR);
        return;
    }
    int anyLeft = 0;
    for (int i = 0; i < 2; i++) if (st.pl[i].active && st.pl[i].lives > 0) anyLeft = 1;
    if (!anyLeft) {
        int allOut = 1;
        for (int i = 0; i < 2; i++) if (st.pl[i].active && st.pl[i].dead < 100) allOut = 0;
        if (allOut) game_over();
    }
}

static void change_skin(int dir) {
    for (int k = 0; k < NSKINS; k++) {
        st.skin[0] = ((st.skin[0] + dir) % NSKINS + NSKINS) % NSKINS;
        if (skin_unlocked(st.skin[0])) break;
    }
    apply_menu_choices();
}

static void update(const Pad pads[2]) {
    Pad in[2] = {pads[0], pads[1]};
#ifdef RB_SELFTEST
    in[0] = adventure() ? adv_bot_pad(0) : bot_pad(0);
    if (st.screen == S_TITLE) { in[0].a = (st.timer > 30); }
    if (st.screen == S_OVER) { in[0].a = 1; }
#endif
    st.frame++;
    update_world();
    switch (st.screen) {
    case S_TITLE:
        st.timer++;
        if (st.timer > 8) {
            for (int i = 0; i < 2; i++) {
                if (in[i].up && !prev[i].up) { st.menuRow = (st.menuRow + 2) % 3; snd_play(SND_MENU); }
                if (in[i].down && !prev[i].down) { st.menuRow = (st.menuRow + 1) % 3; snd_play(SND_MENU); }
                int dir = (in[i].right && !prev[i].right) - (in[i].left && !prev[i].left);
                if (dir) {
                    if (st.menuRow == 0) save.mode = save.mode == M_ADVENTURE ? M_ARENA : M_ADVENTURE;
                    else if (st.menuRow == 1) { st.difficulty = (st.difficulty + dir + 3) % 3; apply_menu_choices(); }
                    else change_skin(dir);
                    snd_play(SND_MENU);
                }
            }
        }
        if (st.timer > 15 && ((in[1].a && !prev[1].a) || (in[1].start && !prev[1].start))) start_game(1);
        else if (st.timer > 15 && ((in[0].a && !prev[0].a) || (in[0].start && !prev[0].start))) start_game(0);
        break;
    case S_PLAY:
        update_play(in);
        break;
    case S_PAUSE:
        st.timer++;
        for (int i = 0; i < 2; i++) {
            if (in[i].start && !prev[i].start && st.pl[i].active) { st.screen = S_PLAY; snd_play(SND_PAUSE); }
            else if (in[i].b && !prev[i].b && st.timer > 10) { to_title(); snd_play(SND_MENU); }
        }
        break;
    case S_OVER:
        st.timer++;
        for (int i = 0; i < 2; i++) {
            if (st.timer > 45 && ((in[i].a && !prev[i].a) || (in[i].start && !prev[i].start))) { start_game(st.hold); break; }
            if (st.timer > 20 && in[i].b && !prev[i].b) { to_title(); snd_play(SND_MENU); break; }
        }
        break;
    }
    prev[0] = in[0]; prev[1] = in[1];
}

/* ---- drawing ---- */
#define C_TEXT RGB(236, 246, 255)
#define C_DIM RGB(150, 182, 214)
#define C_GOLD RGB(255, 210, 90)
#define C_PANEL RGB(12, 28, 52)
#define C_EDGE RGB(70, 120, 176)
#define C_ACCENT RGB(120, 220, 255)

typedef struct { uint32_t body, dark, belly, scarf, hat; } Skin;
static const Skin SKINS[NSKINS] = {
    {RGB(54, 122, 246), RGB(34, 84, 190), RGB(240, 246, 255), RGB(255, 86, 86), RGB(255, 86, 86)},
    {RGB(255, 112, 172), RGB(206, 70, 128), RGB(255, 244, 250), RGB(255, 216, 84), RGB(66, 202, 172)},
    {RGB(60, 204, 152), RGB(30, 150, 110), RGB(240, 255, 248), RGB(255, 150, 50), RGB(90, 130, 255)},
    {RGB(255, 198, 62), RGB(214, 150, 30), RGB(255, 247, 214), RGB(200, 60, 60), RGB(60, 62, 76)},
    {RGB(152, 102, 242), RGB(112, 70, 192), RGB(246, 240, 255), RGB(255, 120, 200), RGB(255, 230, 100)},
    {RGB(64, 64, 76), RGB(34, 34, 44), RGB(246, 246, 246), RGB(90, 200, 90), RGB(246, 246, 246)},
};

static void panel(float x, float y, float w, float h, float r) {
    gfx_rrect(&fb, x, y + u(5), w, h, r, RGB(0, 0, 0), 60);
    gfx_rrect(&fb, x, y, w, h, r, C_EDGE, 255);
    float b = fmaxf(1.f, u(2));
    gfx_rrect(&fb, x + b, y + b, w - 2 * b, h - 2 * b, r - b, C_PANEL, 255);
}

static float hashf(int i, int k) { float v = sinf((float)i * 12.9898f + (float)k * 78.233f) * 43758.5453f; return v - floorf(v); }

static void render_bg(int level) {
    const Theme *t = &THEMES[theme_of(level)];
    Surf *s = &fb;
    uint16_t *keep = s->px;
    float keepX = shx, keepY = shy; shx = shy = 0.f;
    s->px = bg; gfx_clip_reset(s);
    gfx_vgrad(s, t->top, t->bot);
    for (int i = 0; i < t->stars; i++)
        gfx_circle(s, hashf(i, 1) * W, hashf(i, 2) * H * .55f, u(.8f + hashf(i, 3) * 1.4f), RGB(255, 255, 255), 90 + (int)(hashf(i, 4) * 150));
    if (t->aurora) {
        gfx_circle(s, W * .3f, H * .05f, H * .55f, RGB(60, 230, 170), 16);
        gfx_circle(s, W * .62f, H * .0f, H * .5f, RGB(150, 110, 255), 16);
        gfx_circle(s, W * .85f, H * .12f, H * .4f, RGB(60, 200, 240), 14);
    }
    gfx_circle(s, W * .55f, H * 1.0f, H * .46f, gfx_mix(t->hill1, t->bot, .45f), 255);
    gfx_circle(s, W * .22f, H * 1.08f, H * .6f, t->hill1, 255);
    gfx_circle(s, W * .80f, H * 1.14f, H * .68f, t->hill2, 255);
    gfx_vignette(s, .55f);
    float cs = (float)cell;
    for (int r = 0; r < TH; r++) for (int c = 0; c < TW; c++) {
        int k = st.tile[r][c];
        if (!k || k == SGT_CRUMBLE) continue;                      /* crumbling ledges are drawn every frame (draw_arena_dynamic) */
        float x = PX((float)c), y = PY((float)r);
        uint32_t body = is_ice(k) ? gfx_mix(t->ice, RGB(255, 255, 255), .5f) : t->ice;
        uint32_t edge = is_ice(k) ? gfx_mix(t->iceDark, RGB(120, 220, 255), .5f) : t->iceDark;
        if (is_solid(k)) {
            gfx_rrect(s, x, y, cs, cs, 0, edge, 255);
            gfx_rrect(s, x + .05f * cs, y + .05f * cs, .9f * cs, .9f * cs, .12f * cs, body, 255);
            gfx_rrect(s, x + .14f * cs, y + .13f * cs, .5f * cs, .09f * cs, .045f * cs, t->iceHi, 170);
        } else {
            gfx_rrect(s, x, y, cs, .46f * cs, .16f * cs, edge, 255);
            gfx_rrect(s, x + .04f * cs, y + .04f * cs, cs - .08f * cs, .32f * cs, .12f * cs, body, 255);
            gfx_rrect(s, x + .12f * cs, y + .09f * cs, .46f * cs, .07f * cs, .035f * cs, t->iceHi, 170);
        }
        if (is_ice(k)) {                                           /* slippery: glassy shine instead of snow */
            gfx_rrect(s, x + .1f * cs, y - .02f * cs, .8f * cs, .08f * cs, .04f * cs, RGB(255, 255, 255), 200);
        } else if (tile_at(c, r - 1) == 0 && r > 0) {                     /* snow on top */
            gfx_rrect(s, x - .03f * cs, y - .13f * cs, cs + .06f * cs, .3f * cs, .14f * cs, RGB(250, 253, 255), 255);
            gfx_circle(s, x + .27f * cs, y - .1f * cs, .16f * cs, RGB(250, 253, 255), 255);
            gfx_circle(s, x + .72f * cs, y - .08f * cs, .13f * cs, RGB(250, 253, 255), 255);
        }
    }
    gfx_rrect(s, 0, 0, .12f * cs, (float)H, 0, t->iceDark, 110);
    gfx_rrect(s, (float)W - .12f * cs, 0, .12f * cs, (float)H, 0, t->iceDark, 110);
    s->px = keep; gfx_clip_reset(s);
    shx = keepX; shy = keepY;
    bgLevel = level; bgTitle = st.screen == S_TITLE; bgSeed = st.seed;
}

/* A buddy drawn in code (penguin with a scarf and a beanie): the fallback when there are no sprites.
 * (fx, fy) = centre of the feet in pixels, uu = one tile in pixels. */
static void draw_buddy_code(int skin, float fx, float fy, float uu, int dir, int anim, int moving, int air, float vy, int throwing, int dying, int land) {
    const Skin *k = &SKINS[((skin % NSKINS) + NSKINS) % NSKINS];
    float f = (float)dir;
    float run = (moving && !air) ? sinf((float)anim * .4f) : 0.f;
    float bob = (moving && !air) ? fabs_(run) * .07f * uu : 0.f;
    float stretch = air ? (vy < 0.f ? .10f : -.05f) : (land > 0 ? -.16f * (float)land / 7.f : 0.f);
    float sx = 1.f - stretch * .6f, sy = 1.f + stretch;
    /* feet */
    uint32_t orange = RGB(255, 170, 40);
    gfx_rrect(&fb, fx - .36f * uu + (run > 0 ? -.04f : .04f) * uu, fy - .16f - (run > .3f ? .09f * uu : 0.f), .3f * uu, .16f * uu, .07f * uu, orange, 255);
    gfx_rrect(&fb, fx + .06f * uu + (run > 0 ? .04f : -.04f) * uu, fy - .16f - (run < -.3f ? .09f * uu : 0.f), .3f * uu, .16f * uu, .07f * uu, orange, 255);
    float bw = .9f * uu * sx, bh = .92f * uu * sy;
    float bx = fx - bw * .5f, by = fy - .1f * uu - bh - bob;
    gfx_rrect(&fb, bx, by, bw, bh, bw * .48f, k->body, 255);
    gfx_rrect(&fb, bx + bw * .08f, by + bh * .55f, bw * .84f, bh * .45f, bw * .4f, k->dark, 70);          /* shading */
    float mid = by + bh * .55f;
    float swing = sinf((float)anim * .4f) * .06f * uu;
    gfx_circle(&fb, fx - f * .47f * uu, mid + swing, .13f * uu, k->dark, 255);                               /* back flipper */
    gfx_rrect(&fb, fx - .27f * uu + f * .05f * uu, by + bh * .42f, .54f * uu, bh * .55f, .27f * uu, k->belly, 255);
    float eyeY = by + bh * .34f;
    for (int side = -1; side <= 1; side += 2) {
        float ex = fx + f * .1f * uu + (float)side * .18f * uu;
        gfx_circle(&fb, ex, eyeY, .13f * uu, RGB(255, 255, 255), 255);
        if (dying) { gfx_rrect(&fb, ex - .07f * uu, eyeY - .02f * uu, .14f * uu, .04f * uu, .02f * uu, RGB(20, 30, 50), 255); }
        else gfx_circle(&fb, ex + f * .04f * uu, eyeY + .01f * uu, .068f * uu, RGB(20, 30, 50), 255);
    }
    float beakX = f > 0 ? fx + .26f * uu : fx - .26f * uu - .22f * uu;
    gfx_rrect(&fb, beakX, eyeY + .1f * uu, .22f * uu, .13f * uu, .06f * uu, orange, 255);
    float scarfY = eyeY + .27f * uu;
    gfx_rrect(&fb, fx - bw * .47f, scarfY, bw * .94f, .15f * uu, .07f * uu, k->scarf, 255);
    gfx_rrect(&fb, fx - f * .38f * uu - .07f * uu, scarfY + .06f * uu + sinf((float)anim * .2f) * .03f * uu, .15f * uu, .32f * uu, .06f * uu, k->scarf, 255);
    gfx_rrect(&fb, fx - bw * .36f, by - .13f * uu, bw * .72f, .34f * uu, .15f * uu, k->hat, 255);          /* beanie */
    gfx_rrect(&fb, fx - bw * .4f, by + .1f * uu, bw * .8f, .11f * uu, .05f * uu, k->belly, 255);
    gfx_circle(&fb, fx, by - .15f * uu, .095f * uu, RGB(255, 255, 255), 255);
    if (throwing) gfx_circle(&fb, fx + f * .56f * uu, fy - .74f * uu, .14f * uu, k->dark, 255);              /* throwing flipper */
    else gfx_circle(&fb, fx + f * .46f * uu, mid - swing, .13f * uu, k->dark, 255);
}

/* A buddy. throwT counts down from 10 after a throw, deadT counts up from 1 after a hit (0 = alive). */
static void draw_buddy(int skin, float fx, float fy, float uu, int dir, int anim, int moving, int air, float vy, int throwT, int deadT, int land) {
    if (!spr_ready()) {
        draw_buddy_code(skin, fx, fy, uu, deadT ? ((deadT / 6) & 1 ? 1 : -1) : dir, anim, moving, air, vy, throwT > 0, deadT > 0, land);
        return;
    }
    int fr;
    float sx = 1.f, sy = 1.f;
    if (deadT) fr = deadT < 24 ? spr_frame(SPR_BUDDY_HIT, deadT / 8, 0) : spr_frame(SPR_BUDDY_DEATH, (deadT - 24) / 7, 0);
    else if (throwT > 0) fr = spr_frame(SPR_BUDDY_ATTACK, (10 - throwT) * spr_count(SPR_BUDDY_ATTACK) / 10, 0);
    else if (air) fr = spr_frame(SPR_BUDDY_JUMP, vy < 0.f ? 0 : 1, 0);              /* rising, falling */
    else if (moving) fr = spr_frame(SPR_BUDDY_RUN, anim / 5, 1);
    else fr = spr_frame(SPR_BUDDY_IDLE, anim / 10, 1);
    if (!deadT && land > 0) { sy = 1.f - .12f * (float)land / 7.f; sx = 1.f + .08f * (float)land / 7.f; }   /* squash on landing */
    SprDraw d = {uu, sx, sy, 0.f, dir < 0, skin, 255, 0};
    spr_draw(&fb, fr, fx, fy, &d);
}

static void snow_cap(float px, float top, float uu, int layers) {
    uint32_t w = RGB(250, 253, 255);
    float r = (.16f + .1f * (float)layers) * uu;
    gfx_circle(&fb, px, top + r * .4f, r, w, 255);
    if (layers >= 2) { gfx_circle(&fb, px - .3f * uu, top + .28f * uu, .18f * uu, w, 255); gfx_circle(&fb, px + .3f * uu, top + .28f * uu, .18f * uu, w, 255); }
}

static void draw_ball(const Enemy *e, float px, float py, float uu) {
    float r = .54f * uu, cy = py - .52f * uu;
    gfx_circle(&fb, px, cy + .03f * uu, r, RGB(126, 170, 220), 255);
    gfx_circle(&fb, px - .02f * uu, cy - .02f * uu, r * .93f, RGB(222, 238, 255), 255);
    gfx_circle(&fb, px - .2f * uu, cy - .22f * uu, .15f * uu, RGB(255, 255, 255), 230);
    if (e->state == ES_ROLL) {
        float ang = (float)e->anim * .3f * (float)e->dir;
        for (int k = 0; k < 3; k++) {
            float a = ang + (float)k * 2.094f;
            if (cosf(a) > -.2f) gfx_circle(&fb, px + sinf(a) * .3f * uu, cy + cosf(a) * .22f * uu, .075f * uu, RGB(150, 190, 232), 220);
        }
    } else {
        gfx_circle(&fb, px - .14f * uu, cy + .1f * uu, .055f * uu, RGB(60, 80, 120), 255);
        gfx_circle(&fb, px + .14f * uu, cy + .1f * uu, .055f * uu, RGB(60, 80, 120), 255);
    }
}

/* Stars circling a dazed head (the art pack's hit star): centre (cx, cy), radius r, star size in tile pixels. */
static void dizzy_stars(float cx, float cy, float r, int t, float size) {
    for (int k = 0; k < 3; k++) {
        float a = (float)t * .2f + (float)k * 2.09f, depth = sinf(a);      /* smaller and dimmer behind the head */
        SprDraw d = {size * (.8f + .2f * depth), 1.f, 1.f, 0.f, 0, 0, 200 + (int)(55.f * depth), 0};
        spr_draw(&fb, spr_frame(SPR_FX_STAR, 0, 0), cx + cosf(a) * r, cy + depth * r * .25f, &d);
    }
}

/* A boss shot starts with 130 frames to live and a spitter's with 100, so a monster shot above 115 left the boss in the
 * last 15 frames: show the swing / cast. Read from the game state, so a loaded save state draws the same picture. */
static int boss_fired_recently(void) {
    for (int i = 0; i < MAXS; i++) if (st.sh[i].used && st.sh[i].owner == 2 && st.sh[i].life > 115) return 1;
    return 0;
}

/* The bosses as sprites. The art has one drawing per pose, so the motion between poses is the game's: the ogres'
 * waddle, lean and footstep dust (the pack's dust), the wizard's hover, the death cloud (the pack's explosion). The
 * shots still leave from the same point (boss_fire); the swing and cast poses are timed around them. */
static void draw_boss_sprite(const Enemy *e, float px, float py, float uu) {
    int dying = e->state == ES_DYING, t = e->anim, wizard = e->kind == 1;
    int dazed = !dying && e->hitFlash > 7;
    int fired = !dying && !dazed && boss_fired_recently(), windup = !dying && !dazed && !fired && e->timer < 18;
    SprDraw d = {uu, 1.f, 1.f, 0.f, e->dir < 0, 0, 255, !dying && flash_on(e) ? 100 : 0};   /* softer on a big sprite */
    if (dying && e->timer > 40) d.alpha = (50 - e->timer) * 25;
    float ax = px, ay = py;
    int fr, idle;
    if (wizard) {
        idle = spr_frame(SPR_WIZARD_IDLE, 0, 0);
        if (!dying) {
            ay -= sinf((float)t * .08f) * .06f * uu;
            gfx_circle(&fb, px, ay - 1.4f * uu, 1.8f * uu, RGB(120, 190, 255), 26);    /* frost aura: no such art */
        }
        if (dying) fr = spr_frame(SPR_WIZARD_DEATH, 0, 0);
        else if (dazed) fr = spr_frame(SPR_WIZARD_HIT, 0, 0);
        else if (fired) fr = spr_frame(SPR_WIZARD_CAST, 1, 0);                      /* the summon */
        else if (windup) fr = spr_frame(SPR_WIZARD_CAST, 0, 0);
        else fr = spr_frame(SPR_WIZARD_IDLE, t / 12, 1);
    } else {
        int rage = e->kind == 2;
        idle = rage ? spr_frame(SPR_RAGE_IDLE, 0, 0) : spr_frame(SPR_OGRE_IDLE, 0, 0);
        if (dying) fr = rage ? spr_frame(SPR_RAGE_DEATH, 0, 0) : spr_frame(SPR_OGRE_DEATH, 0, 0);
        else if (dazed) fr = rage ? spr_frame(SPR_RAGE_HIT, 0, 0) : spr_frame(SPR_OGRE_HIT, 0, 0);
        else if (fired) fr = rage ? spr_frame(SPR_RAGE_ATTACK, 0, 0) : spr_frame(SPR_OGRE_ATTACK, 1, 0);
        else if (windup && e->ground) fr = rage ? spr_frame(SPR_RAGE_ROAR, 0, 0) : spr_frame(SPR_OGRE_ATTACK, 0, 0);
        else if (rage && e->hp * 3 <= boss_max(e->kind, st.level) && e->ground && fabs_(e->vx) > .05f)
            fr = spr_frame(SPR_RAGE_CHARGE, 0, 0);               /* the last third: head down, charging */
        else {
            fr = rage ? idle : spr_frame(SPR_OGRE_IDLE, t / 15, 1);      /* the ogre's idle and walk frames */
            if (e->ground) {                                     /* a heavy waddle: bob and footstep dust */
                float ph = (float)t * (rage ? .2f : .15f);
                ay -= fabs_(sinf(ph)) * .08f * uu;
                float since = fmodf(ph, 3.14159265f) / (rage ? .2f : .15f);          /* frames since a foot landed */
                if (since < 9.f) {
                    SprDraw dd = {uu * 1.3f, 1.f, 1.f, 0.f, e->dir > 0, 0, 220 - (int)(since * 20.f), 0};
                    spr_draw(&fb, spr_frame(SPR_FX_DUST, (int)since / 3, 0), px - (float)e->dir * .9f * uu, py - .25f * uu, &dd);
                }
            } else if (e->vy < 0.f) { d.sy = 1.05f; d.sx = .97f; }
        }
    }
    spr_draw(&fb, fr, ax, ay, &d);
    if (dying) {
        if (e->timer < 27) {                                     /* the pack's explosion cloud */
            SprDraw cd = {uu * 2.4f, 1.f, 1.f, 0.f, 0, 0, 255 - e->timer * 6, 0};
            spr_draw(&fb, spr_frame(SPR_FX_CLOUD, e->timer / 9, 0), px, py - 1.1f * uu, &cd);
        }
        return;
    }
    float top = ay - spr_top(idle) * uu;
    if (dazed) dizzy_stars(px, top + .2f * uu, .8f * uu, t, uu);
    float frac = (float)e->hp / (float)boss_max(e->kind, st.level);       /* health bar above the head */
    if (frac < 0.f) frac = 0.f;
    float by = top - .4f * uu;
    gfx_rrect(&fb, px - 1.2f * uu, by, 2.4f * uu, .16f * uu, .08f * uu, RGB(20, 24, 40), 220);
    gfx_rrect(&fb, px - 1.2f * uu + .02f * uu, by + .02f * uu, (2.4f * uu - .04f * uu) * frac, .12f * uu, .06f * uu, wizard ? RGB(120, 200, 255) : RGB(255, 90, 90), 255);
}

/* The five small monsters as sprites, one pose per game state; the charger's dust and dizzy stars are the pack's
 * effects. Returns 0 for the ghost (drawn in code) or without sprites. */
static int draw_enemy_sprite(const Enemy *e, float px, float py, float uu) {
    if (!spr_ready() || e->type == E_GHOST) return 0;
    if (e->type == E_BOSS) { draw_boss_sprite(e, px, py, uu); return 1; }
    int dying = e->state == ES_DYING, t = e->anim;
    int hit = e->hitFlash > 0 && !dying, hitI = (7 - e->hitFlash) * 3 / 7;
    SprDraw d = {uu, 1.f, 1.f, 0.f, e->dir < 0, 0, 255, flash_on(e) && !dying ? 170 : 0};
    if (dying && e->timer > 40) d.alpha = (50 - e->timer) * 25;
    float ax = px, ay = py;
    int fr;
    float vy = e->vy;
    int jumpI = vy < 0.f ? 0 : 1;                                /* rising, falling */
    switch (e->type) {
    case E_WALK:
        if (dying) fr = spr_frame(SPR_WALKER_DEATH, e->timer / 7, 0);
        else if (hit) fr = spr_frame(SPR_WALKER_HIT, hitI * 2 / 3, 0);
        else if (!e->ground) fr = spr_frame(SPR_WALKER_JUMP, jumpI, 0);
        else fr = spr_frame(SPR_WALKER_RUN, t / (7 + 3 * e->snow), 1);
        break;
    case E_HOP:
        if (dying) fr = spr_frame(SPR_HOPPER_DEATH, e->timer / 8, 0);
        else if (hit) fr = spr_frame(SPR_HOPPER_HIT, hitI, 0);
        else if (!e->ground) fr = spr_frame(SPR_HOPPER_JUMP, jumpI, 0);
        else fr = spr_frame(SPR_HOPPER_IDLE, t / 9, 1);
        break;
    case E_FLY:
        ay = py - .42f * uu;                                     /* the flyer is anchored at its body centre */
        if (dying) fr = spr_frame(SPR_FLYER_DEATH, e->timer / 8, 0);
        else if (hit) fr = spr_frame(SPR_FLYER_HIT, hitI, 0);
        else fr = spr_frame(SPR_FLYER_FLY, t / (5 + 2 * e->snow), 1);
        break;
    case E_SHOOT:
        if (dying) fr = spr_frame(SPR_SPITTER_DEATH, e->timer / 8, 0);
        else if (hit) fr = spr_frame(SPR_SPITTER_HIT, hitI, 0);
        else if (e->cd < 22 && e->cd > 0 && e->snow == 0) fr = spr_frame(SPR_SPITTER_ATTACK, 0, 0);
        else if (!e->ground) fr = spr_frame(SPR_SPITTER_IDLE, 0, 0);
        else fr = spr_frame(SPR_SPITTER_WALK, t / (8 + 3 * e->snow), 1);
        break;
    case E_CHARGE: {
        int charging = e->cd > 0, stunned = e->timer > 0;
        if (dying) fr = spr_frame(SPR_CHARGER_DEATH, e->timer / 8, 0);
        else if (hit) fr = spr_frame(SPR_CHARGER_HIT, 0, 0);
        else if (stunned) fr = spr_frame(SPR_CHARGER_HIT, 0, 0);
        else if (charging) {
            fr = spr_frame(SPR_CHARGER_ATTACK, 0, 0);
            ax += sinf((float)t * 1.6f) * .03f * uu;
            ay -= fabs_(sinf((float)t * .5f)) * .06f * uu;
            for (int k = 1; k <= 3; k++) {                                     /* snow puffs kicked up behind */
                SprDraw pd = {uu * (.42f + .1f * (float)k), 1.f, 1.f, 0.f, e->dir > 0, 0, 230 - k * 50, 0};
                spr_draw(&fb, spr_frame(SPR_FX_CLOUD, 0, 0), px - (float)e->dir * (.45f + (float)k * .32f) * uu, py - .2f * uu, &pd);
            }
        } else {
            fr = e->ground ? spr_frame(SPR_CHARGER_WALK, t / (8 + 3 * e->snow), 1) : spr_frame(SPR_CHARGER_IDLE, 0, 0);
        }
        break; }
    default: return 0;
    }
    spr_draw(&fb, fr, ax, ay, &d);
    if (dying) return 1;
    float top = ay - spr_top(fr) * uu;
    if (e->type == E_CHARGE && e->timer > 0) dizzy_stars(px, top - .1f * uu, .4f * uu, e->anim, uu * .7f);
    if (e->snow > 0) snow_cap(px, top + .1f * uu, uu, e->snow);
    return 1;
}

static void draw_enemy(const Enemy *e) {
    float uu = (float)cell;
    float px = PX(e->x), py = PY(e->y);
    if (e->state == ES_FROZEN || e->state == ES_ROLL) {
        if (e->state == ES_FROZEN && e->timer < 130) px += sinf((float)e->anim * 1.6f) * .05f * uu;
        draw_ball(e, px, py, uu);
        return;
    }
    if (draw_enemy_sprite(e, px, py, uu)) return;
    int dying = e->state == ES_DYING;
    float spin = dying ? sinf((float)e->timer * .5f) : 1.f;      /* squash left-right = a cheap spin */
    uint32_t flash = RGB(255, 255, 255);
    switch (e->type) {
    case E_WALK: {
        float bobv = e->ground ? fabs_(sinf((float)e->anim * .3f)) * .05f * uu : 0.f;
        float w = .86f * uu * (dying ? fmaxf(.3f, fabs_(spin)) : 1.f);
        uint32_t body = RGB(236, 74, 62);
        gfx_rrect(&fb, px - .32f * uu, py - .16f * uu, .26f * uu, .16f * uu, .07f * uu, RGB(120, 40, 50), 255);
        gfx_rrect(&fb, px + .06f * uu, py - .16f * uu, .26f * uu, .16f * uu, .07f * uu, RGB(120, 40, 50), 255);
        gfx_rrect(&fb, px - w * .5f, py - .14f * uu - .8f * uu - bobv, w, .8f * uu, w * .46f, body, 255);
        gfx_circle(&fb, px - .22f * uu, py - .96f * uu - bobv, .1f * uu, RGB(255, 236, 190), 255);
        gfx_circle(&fb, px + .22f * uu, py - .96f * uu - bobv, .1f * uu, RGB(255, 236, 190), 255);
        gfx_rrect(&fb, px - .22f * uu, py - .5f * uu - bobv, .44f * uu, .34f * uu, .17f * uu, RGB(255, 190, 150), 255);
        for (int s = -1; s <= 1; s += 2) {
            float ex = px + (float)s * .2f * uu, ey = py - .66f * uu - bobv;
            gfx_circle(&fb, ex, ey, .13f * uu, RGB(255, 255, 255), 255);
            gfx_circle(&fb, ex + (float)e->dir * .04f * uu, ey + .02f * uu, .065f * uu, RGB(30, 20, 30), 255);
            gfx_rrect(&fb, ex - .15f * uu, ey - .16f * uu, .3f * uu, .12f * uu, .04f * uu, body, 255);     /* angry lid */
        }
        gfx_rrect(&fb, px - .12f * uu, py - .36f * uu - bobv, .24f * uu, .07f * uu, .03f * uu, RGB(80, 20, 30), 255);
        if (e->snow > 0) snow_cap(px, py - .98f * uu - bobv, uu, e->snow);
        if (e->hitFlash > 0) gfx_circle(&fb, px, py - .55f * uu, .45f * uu, flash, 110);
        break; }
    case E_HOP: {
        float s = e->ground ? 0.f : (e->vy < 0.f ? .2f : -.1f);
        float w = .9f * uu * (1.f - s * .5f) * (dying ? fmaxf(.3f, fabs_(spin)) : 1.f), h = .8f * uu * (1.f + s);
        gfx_rrect(&fb, px - w * .5f, py - h - .02f * uu, w, h, w * .48f, RGB(70, 200, 110), 255);
        gfx_rrect(&fb, px - w * .32f, py - h * .52f, w * .64f, h * .4f, w * .22f, RGB(170, 245, 190), 255);
        for (int sd = -1; sd <= 1; sd += 2) {
            float ex = px + (float)sd * .2f * uu, ey = py - h * .66f;
            gfx_circle(&fb, ex, ey, .15f * uu, RGB(255, 255, 255), 255);
            gfx_circle(&fb, ex + (float)e->dir * .05f * uu, ey, .08f * uu, RGB(20, 40, 30), 255);
        }
        if (e->snow > 0) snow_cap(px, py - h - .02f * uu, uu, e->snow);
        if (e->hitFlash > 0) gfx_circle(&fb, px, py - h * .5f, .45f * uu, flash, 110);
        break; }
    case E_FLY: {
        float flap = sinf((float)e->anim * .5f) * .14f * uu;
        gfx_circle(&fb, px - .36f * uu, py - .5f * uu - flap, .22f * uu, RGB(150, 100, 210), 255);
        gfx_circle(&fb, px + .36f * uu, py - .5f * uu - flap, .22f * uu, RGB(150, 100, 210), 255);
        gfx_circle(&fb, px, py - .4f * uu, .36f * uu * (dying ? fmaxf(.4f, fabs_(spin)) : 1.f), RGB(112, 70, 178), 255);
        gfx_circle(&fb, px - .2f * uu, py - .62f * uu, .08f * uu, RGB(180, 130, 230), 255);
        gfx_circle(&fb, px + .2f * uu, py - .62f * uu, .08f * uu, RGB(180, 130, 230), 255);
        for (int sd = -1; sd <= 1; sd += 2) {
            float ex = px + (float)sd * .14f * uu, ey = py - .42f * uu;
            gfx_circle(&fb, ex, ey, .11f * uu, RGB(255, 240, 130), 255);
            gfx_circle(&fb, ex + (float)e->dir * .03f * uu, ey, .055f * uu, RGB(40, 20, 50), 255);
        }
        if (e->snow > 0) snow_cap(px, py - .72f * uu, uu, e->snow);
        if (e->hitFlash > 0) gfx_circle(&fb, px, py - .4f * uu, .4f * uu, flash, 110);
        break; }
    case E_SHOOT: {
        float bobv = e->ground ? fabs_(sinf((float)e->anim * .25f)) * .04f * uu : 0.f;
        float w = .88f * uu * (dying ? fmaxf(.3f, fabs_(spin)) : 1.f);
        uint32_t body = RGB(246, 196, 52);
        gfx_rrect(&fb, px - .3f * uu, py - .16f * uu, .24f * uu, .16f * uu, .07f * uu, RGB(150, 100, 30), 255);
        gfx_rrect(&fb, px + .06f * uu, py - .16f * uu, .24f * uu, .16f * uu, .07f * uu, RGB(150, 100, 30), 255);
        for (int t2 = -1; t2 <= 1; t2++) gfx_circle(&fb, px + (float)t2 * .25f * uu, py - .9f * uu - bobv, .1f * uu, RGB(255, 140, 40), 255);
        gfx_rrect(&fb, px - w * .5f, py - .14f * uu - .8f * uu - bobv, w, .8f * uu, w * .46f, body, 255);
        gfx_rrect(&fb, px - .3f * uu, py - .42f * uu - bobv, .6f * uu, .26f * uu, .13f * uu, RGB(255, 226, 150), 255);
        float ey = py - .6f * uu - bobv;
        gfx_circle(&fb, px, ey, .24f * uu, RGB(255, 255, 255), 255);
        gfx_circle(&fb, px + (float)e->dir * .08f * uu, ey, .12f * uu, RGB(200, 40, 50), 255);
        gfx_circle(&fb, px + (float)e->dir * .08f * uu, ey, .06f * uu, RGB(30, 10, 20), 255);
        if (e->cd < 22 && e->cd > 0 && e->snow == 0) gfx_circle(&fb, px + (float)e->dir * .5f * uu, py - .45f * uu, .13f * uu * (1.f + (float)(22 - e->cd) * .03f), RGB(255, 140, 40), 220);   /* about to spit */
        if (e->snow > 0) snow_cap(px, py - .98f * uu - bobv, uu, e->snow);
        if (e->hitFlash > 0) gfx_circle(&fb, px, py - .55f * uu, .45f * uu, flash, 110);
        break; }
    case E_CHARGE: {
        int charging = e->cd > 0, stunned = e->timer > 0;
        float shake = charging ? sinf((float)e->anim * 1.6f) * .03f * uu : 0.f;
        float w = 1.0f * uu * (dying ? fmaxf(.3f, fabs_(spin)) : 1.f);
        uint32_t body = RGB(72, 124, 232);
        if (charging) for (int k = 1; k <= 3; k++) gfx_circle(&fb, px - (float)e->dir * (.5f + (float)k * .25f) * uu, py - .12f * uu, (.1f + (float)k * .03f) * uu, RGB(235, 244, 255), 150 - k * 35);
        gfx_rrect(&fb, px - .36f * uu + shake, py - .18f * uu, .28f * uu, .18f * uu, .07f * uu, RGB(30, 50, 110), 255);
        gfx_rrect(&fb, px + .08f * uu + shake, py - .18f * uu, .28f * uu, .18f * uu, .07f * uu, RGB(30, 50, 110), 255);
        gfx_rrect(&fb, px - w * .5f + shake, py - .16f * uu - .82f * uu, w, .82f * uu, w * .42f, body, 255);
        gfx_rrect(&fb, px - .22f * uu + (float)e->dir * .1f * uu, py - .42f * uu, .44f * uu, .26f * uu, .13f * uu, RGB(190, 214, 255), 255);
        gfx_circle(&fb, px + (float)e->dir * .28f * uu, py - .3f * uu, .06f * uu, C_GOLD, 255);                      /* nose ring */
        for (int sd = -1; sd <= 1; sd += 2) {
            gfx_rrect(&fb, px + (float)sd * .34f * uu - .07f * uu + shake, py - 1.12f * uu, .14f * uu, .3f * uu, .06f * uu, RGB(255, 240, 200), 255);       /* horns */
            float ex = px + (float)sd * .22f * uu + shake, ey = py - .68f * uu;
            gfx_circle(&fb, ex, ey, .12f * uu, RGB(255, 255, 255), 255);
            gfx_circle(&fb, ex + (float)e->dir * .04f * uu, ey, .06f * uu, charging ? RGB(255, 40, 40) : RGB(20, 20, 40), 255);
            gfx_rrect(&fb, ex - .14f * uu, ey - .16f * uu, .28f * uu, .12f * uu, .04f * uu, body, 255);
        }
        if (stunned) for (int k = 0; k < 3; k++) { float a = (float)e->anim * .2f + (float)k * 2.09f; gfx_circle(&fb, px + cosf(a) * .4f * uu, py - 1.25f * uu + sinf(a) * .1f * uu, .07f * uu, C_GOLD, 255); }
        if (e->snow > 0) snow_cap(px, py - .98f * uu, uu, e->snow);
        if (e->hitFlash > 0) gfx_circle(&fb, px, py - .55f * uu, .5f * uu, flash, 110);
        break; }
    case E_GHOST: {
        float wob = sinf((float)e->anim * .15f) * .06f * uu;
        gfx_circle(&fb, px, py - .45f * uu, .7f * uu, RGB(190, 220, 255), 34);
        gfx_circle(&fb, px, py - .5f * uu + wob, .42f * uu, RGB(235, 244, 255), 190);
        for (int t2 = -1; t2 <= 1; t2++) gfx_circle(&fb, px + (float)t2 * .26f * uu, py - .2f * uu + sinf((float)e->anim * .2f + (float)t2) * .06f * uu, .17f * uu, RGB(235, 244, 255), 170);
        for (int sd = -1; sd <= 1; sd += 2) {
            float ex = px + (float)sd * .16f * uu, ey = py - .56f * uu + wob;
            gfx_circle(&fb, ex, ey, .12f * uu, RGB(255, 80, 90), 255);
            gfx_circle(&fb, ex + (float)e->dir * .03f * uu, ey, .06f * uu, RGB(30, 0, 10), 255);
        }
        break; }
    case E_BOSS: {
        if (e->kind == 1) {                              /* frost wizard */
            float bobv = sinf((float)e->anim * .08f) * .06f * uu;
            gfx_circle(&fb, px, py - 1.2f * uu, 1.6f * uu, RGB(120, 190, 255), 26);
            gfx_rrect(&fb, px - .9f * uu, py - 1.7f * uu - bobv, 1.8f * uu, 1.7f * uu, .7f * uu, RGB(70, 100, 214), 255);
            for (int k = -2; k <= 2; k++) gfx_circle(&fb, px + (float)k * .34f * uu, py - .1f * uu - bobv + sinf((float)e->anim * .15f + (float)k) * .05f * uu, .2f * uu, RGB(70, 100, 214), 255);
            gfx_rrect(&fb, px - .5f * uu, py - 1.1f * uu - bobv, 1.0f * uu, .8f * uu, .3f * uu, RGB(110, 150, 240), 255);
            gfx_circle(&fb, px, py - 1.75f * uu - bobv, .5f * uu, RGB(255, 222, 192), 255);
            gfx_rrect(&fb, px - .38f * uu, py - 1.5f * uu - bobv, .76f * uu, .5f * uu, .24f * uu, RGB(245, 248, 255), 255);        /* beard */
            for (int sd = -1; sd <= 1; sd += 2) {
                gfx_circle(&fb, px + (float)sd * .18f * uu, py - 1.8f * uu - bobv, .1f * uu, RGB(255, 255, 255), 255);
                gfx_circle(&fb, px + (float)sd * .18f * uu + (float)e->dir * .03f * uu, py - 1.8f * uu - bobv, .05f * uu, RGB(20, 30, 80), 255);
            }
            gfx_rrect(&fb, px - .6f * uu, py - 2.2f * uu - bobv, 1.2f * uu, .24f * uu, .1f * uu, RGB(40, 56, 150), 255);
            gfx_rrect(&fb, px - .36f * uu, py - 3.1f * uu - bobv, .72f * uu, 1.0f * uu, .3f * uu, RGB(40, 56, 150), 255);
            gfx_circle(&fb, px, py - 2.65f * uu - bobv, .12f * uu, C_GOLD, 255);
            gfx_rrect(&fb, px + (float)e->dir * 1.0f * uu - .04f * uu, py - 2.0f * uu - bobv, .08f * uu, 1.8f * uu, .03f * uu, RGB(160, 110, 70), 255);   /* staff */
            gfx_circle(&fb, px + (float)e->dir * 1.0f * uu, py - 2.05f * uu - bobv, .2f * uu + sinf((float)e->anim * .2f) * .02f * uu, RGB(150, 230, 255), 255);
            if (flash_on(e)) gfx_circle(&fb, px, py - 1.4f * uu, 1.0f * uu, flash, 120);
            float frac = (float)e->hp / (float)boss_max(e->kind, st.level);
            if (frac < 0.f) frac = 0.f;
            gfx_rrect(&fb, px - 1.2f * uu, py - 3.4f * uu, 2.4f * uu, .16f * uu, .08f * uu, RGB(20, 24, 40), 220);
            gfx_rrect(&fb, px - 1.2f * uu + .02f * uu, py - 3.38f * uu, (2.4f * uu - .04f * uu) * frac, .12f * uu, .06f * uu, RGB(120, 200, 255), 255);
            break;
        }
        float bw = 2.3f * uu, bh = 2.1f * uu, bobv = e->ground ? fabs_(sinf((float)e->anim * .12f)) * .05f * uu : 0.f;
        uint32_t body = e->kind == 2 ? RGB(208, 74, 62) : RGB(118, 72, 176);
        gfx_rrect(&fb, px - 1.0f * uu, py - .3f * uu, .8f * uu, .3f * uu, .12f * uu, RGB(60, 36, 90), 255);
        gfx_rrect(&fb, px + .2f * uu, py - .3f * uu, .8f * uu, .3f * uu, .12f * uu, RGB(60, 36, 90), 255);
        gfx_circle(&fb, px - 1.2f * uu, py - 1.1f * uu - bobv, .38f * uu, RGB(98, 56, 156), 255);
        gfx_circle(&fb, px + 1.2f * uu, py - 1.1f * uu - bobv, .38f * uu, RGB(98, 56, 156), 255);
        gfx_rrect(&fb, px - bw * .5f, py - .22f * uu - bh - bobv, bw, bh, .8f * uu, body, 255);
        gfx_rrect(&fb, px - .7f * uu, py - 1.35f * uu - bobv, 1.4f * uu, 1.0f * uu, .5f * uu, (e->kind == 2 ? RGB(255, 176, 140) : RGB(176, 134, 224)), 255);
        gfx_circle(&fb, px - .78f * uu, py - 2.4f * uu - bobv, .2f * uu, RGB(255, 236, 190), 255);
        gfx_circle(&fb, px + .78f * uu, py - 2.4f * uu - bobv, .2f * uu, RGB(255, 236, 190), 255);
        gfx_rrect(&fb, px - .6f * uu, py - 2.45f * uu - bobv, 1.2f * uu, .34f * uu, .08f * uu, C_GOLD, 255);       /* crown */
        for (int c = -1; c <= 1; c++) gfx_circle(&fb, px + (float)c * .45f * uu, py - 2.5f * uu - bobv, .12f * uu, C_GOLD, 255);
        for (int sd = -1; sd <= 1; sd += 2) {
            float ex = px + (float)sd * .46f * uu, ey = py - 1.75f * uu - bobv;
            gfx_circle(&fb, ex, ey, .26f * uu, RGB(255, 255, 255), 255);
            gfx_circle(&fb, ex + (float)e->dir * .08f * uu, ey + .02f * uu, .13f * uu, RGB(40, 20, 40), 255);
            gfx_rrect(&fb, ex - .3f * uu, ey - .32f * uu, .6f * uu, .2f * uu, .05f * uu, body, 255);
        }
        gfx_rrect(&fb, px - .4f * uu, py - 1.02f * uu - bobv, .8f * uu, .26f * uu, .1f * uu, RGB(60, 20, 50), 255);
        for (int t2 = -1; t2 <= 1; t2++) gfx_rrect(&fb, px + (float)t2 * .22f * uu - .06f * uu, py - 1.02f * uu - bobv, .12f * uu, .12f * uu, .03f * uu, RGB(255, 255, 255), 255);
        if (flash_on(e)) gfx_rrect(&fb, px - bw * .5f, py - .22f * uu - bh - bobv, bw, bh, .8f * uu, flash, 120);
        float frac = (float)e->hp / (float)boss_max(e->kind, st.level);
        if (frac < 0.f) frac = 0.f;
        gfx_rrect(&fb, px - 1.2f * uu, py - 2.95f * uu, 2.4f * uu, .16f * uu, .08f * uu, RGB(20, 24, 40), 220);
        gfx_rrect(&fb, px - 1.2f * uu + .02f * uu, py - 2.93f * uu, (2.4f * uu - .04f * uu) * frac, .12f * uu, .06f * uu, RGB(255, 90, 90), 255);
        break; }
    }
}

static void draw_shot(const Shot *s) {
    float uu = (float)cell, x = PX(s->x), y = PY(s->y);
    if (s->owner < 2) {
        gfx_circle(&fb, x - s->vx * uu * 1.4f, y - s->vy * uu, .1f * uu, RGB(220, 240, 255), 110);
        gfx_circle(&fb, x, y, .19f * uu, RGB(246, 252, 255), 255);
        gfx_circle(&fb, x - .04f * uu, y - .04f * uu, .1f * uu, RGB(255, 255, 255), 255);
    } else if (s->power == 2 && spr_ready()) {                     /* the frost wizard's ice ball grows out of his hand */
        int age = 130 - s->life;
        SprDraw d = {uu, 1.f, 1.f, 0.f, s->vx < 0.f, 0, 255, 0};
        spr_draw(&fb, spr_frame(SPR_FX_ICEBALL, age / 3, 0), x, y, &d);
    } else if (s->power == 3 && spr_ready()) {                     /* the spitter's ice ball */
        SprDraw d = {uu * .8f, 1.f, 1.f, 0.f, s->vx < 0.f, 0, 255, 0};
        spr_draw(&fb, spr_frame(SPR_FX_ICEBALL, 1, 0), x, y, &d);
    } else if (spr_ready()) {                                      /* the ogres' fireballs */
        SprDraw d = {uu, 1.f, 1.f, 0.f, s->vx < 0.f, 0, 255, 0};
        spr_draw(&fb, spr_frame(SPR_FX_FIRE, (130 - s->life) / 5, 1), x, y, &d);
    } else {
        gfx_circle(&fb, x - s->vx * uu * 2.f, y, .16f * uu, RGB(255, 140, 40), 130);
        gfx_circle(&fb, x, y, .26f * uu, RGB(255, 120, 40), 255);
        gfx_circle(&fb, x, y, .14f * uu, RGB(255, 226, 120), 255);
    }
}

static void draw_item(const Item *it) {
    if (it->life < 120 && ((st.frame / 5) & 1)) return;
    float uu = (float)cell, x = PX(it->x), y = PY(it->y) + sinf((float)st.frame * .1f) * .05f * uu;
    gfx_circle(&fb, x, y - .25f * uu, .5f * uu, RGB(255, 255, 200), 28);
    if (it->type == IT_FRUIT) {
        uint32_t c = ((int)(it->x * 3.f) & 1) ? RGB(255, 150, 40) : RGB(235, 60, 80);
        gfx_circle(&fb, x, y - .22f * uu, .24f * uu, c, 255);
        gfx_circle(&fb, x - .08f * uu, y - .3f * uu, .07f * uu, RGB(255, 255, 255), 200);
        gfx_rrect(&fb, x - .02f * uu, y - .52f * uu, .05f * uu, .12f * uu, .02f * uu, RGB(110, 80, 50), 255);
        gfx_rrect(&fb, x + .02f * uu, y - .55f * uu, .16f * uu, .09f * uu, .04f * uu, RGB(90, 210, 110), 255);
    } else {
        uint32_t c = it->type == IT_SPEED ? RGB(70, 150, 255) : (it->type == IT_RANGE ? RGB(255, 210, 70) : RGB(255, 80, 90));
        gfx_rrect(&fb, x - .17f * uu, y - .34f * uu, .34f * uu, .34f * uu, .15f * uu, c, 255);
        gfx_rrect(&fb, x - .07f * uu, y - .5f * uu, .14f * uu, .2f * uu, .04f * uu, RGB(220, 236, 250), 255);
        gfx_rrect(&fb, x - .09f * uu, y - .56f * uu, .18f * uu, .09f * uu, .04f * uu, RGB(160, 110, 70), 255);
        gfx_circle(&fb, x - .06f * uu, y - .25f * uu, .04f * uu, RGB(255, 255, 255), 220);
    }
}

static void draw_snowfall(void) {
    for (int i = 0; i < 46; i++) {
        float fx = fmodf(hashf(i, 1) * W + sinf((float)st.frame * .02f + (float)i) * u(14) - (float)camPx * .5f, (float)W);   /* half the camera's speed */
        if (fx < 0.f) fx += (float)W;
        float fy = fmodf(hashf(i, 2) * H + (float)st.frame * (.6f + (float)(i % 5) * .28f) * S, (float)H);
        gfx_circle(&fb, fx, fy, u(1.2f + (float)(i % 3) * .8f), RGB(255, 255, 255), 150);
    }
}

/* ---- adventure: the level's tiles and things ---- */
static void prop(int id, float x, float feet, float tile, float sy, int alpha, int flip) {
    SprDraw d = {tile, 1.f, sy, 0.f, flip, 0, alpha, 0};
    spr_draw(&fb, spr_frame(id, 0, 0), x, feet, &d);
}

/* cracks over a crumbling ledge tile (x, y = its top-left pixel) */
static void crumble_cracks(float x, float y, float cs) {
    gfx_rrect(&fb, x + .30f * cs, y + .04f * cs, .06f * cs, .30f * cs, .02f * cs, RGB(40, 70, 120), 210);
    gfx_rrect(&fb, x + .30f * cs, y + .16f * cs, .30f * cs, .05f * cs, .02f * cs, RGB(40, 70, 120), 210);
    gfx_rrect(&fb, x + .62f * cs, y + .10f * cs, .06f * cs, .26f * cs, .02f * cs, RGB(40, 70, 120), 210);
    gfx_rrect(&fb, x, y, cs, .42f * cs, .14f * cs, RGB(235, 248, 255), 46);
}

static void draw_arena_dynamic(void) {
    const Theme *th = &THEMES[theme_of(st.level)];
    float cs = (float)cell;
    for (int r = 0; r < TH; r++) for (int c = 0; c < TW; c++) {
        if (st.tile[r][c] != SGT_CRUMBLE) continue;
        float x = PX((float)c), y = PY((float)r);
        int sh = crumble_shake(c, r);
        if (sh >= 0) x += (float)((sh / 2) & 1 ? 1 : -1) * cs * .05f;
        gfx_rrect(&fb, x, y, cs, .46f * cs, .16f * cs, th->iceDark, 255);
        gfx_rrect(&fb, x + .04f * cs, y + .04f * cs, cs - .08f * cs, .32f * cs, .12f * cs, gfx_mix(th->ice, RGB(255, 255, 255), .35f), 255);
        gfx_rrect(&fb, x + .12f * cs, y + .09f * cs, .46f * cs, .07f * cs, .035f * cs, th->iceHi, 170);
        crumble_cracks(x, y, cs);
    }
    if (st.mods & SGA_WIND) {
        float d, g = arena_gust(&d);
        if (g > .02f) {
            for (int i = 0; i < 14; i++) {
                float ph = fmodf(hashf(i, 31) * 100.f + (float)st.mtime * (.25f + hashf(i, 33) * .2f) * d * 1.f, (float)TW + 4.f);
                if (ph < 0.f) ph += (float)TW + 4.f;
                gfx_rrect(&fb, PX(ph - 2.f), PY(hashf(i, 35) * (float)(TH - 3) + 1.f), cs * (1.f + hashf(i, 37)), cs * .05f, cs * .025f, RGB(240, 248, 255), (int)(120.f * g));
            }
        }
    }
}

static void draw_icicle(const LvThing *t, const Icicle *ic) {
    if (ic->mode == 3) return;
    float cs = (float)cell, x = PX((float)t->x + .5f);
    float top = ic->mode == 2 ? ic->y - 1.4f : (float)t->y;
    float jx = ic->mode == 1 ? (float)((ic->t / 2) & 1 ? 1 : -1) * cs * .06f : 0.f, y0 = PY(top);
    gfx_rrect(&fb, x + jx - .26f * cs, y0, .52f * cs, .55f * cs, .12f * cs, RGB(176, 224, 252), 255);
    gfx_rrect(&fb, x + jx - .18f * cs, y0 + .42f * cs, .36f * cs, .55f * cs, .1f * cs, RGB(150, 208, 248), 255);
    gfx_rrect(&fb, x + jx - .10f * cs, y0 + .82f * cs, .20f * cs, .6f * cs, .08f * cs, RGB(205, 238, 255), 255);
    gfx_rrect(&fb, x + jx - .17f * cs, y0 + .06f * cs, .08f * cs, .5f * cs, .04f * cs, RGB(255, 255, 255), 190);
}

static void draw_wind_zone(int k, const LvThing *t) {
    float g = zone_gust(k);
    if (g < .02f || (float)(t->x + t->arg) < st.camx - 1.f || (float)t->x > st.camx + TW + 1.f) return;
    float cs = (float)cell, range = (float)t->arg, dir = t->patrol ? 1.f : -1.f;
    for (int i = 0; i < 12; i++) {
        float ph = fmodf(hashf(i, k + 41) * 100.f + (float)st.mtime * (.25f + hashf(i, 43) * .2f) * dir, range + 3.f);
        if (ph < 0.f) ph += range + 3.f;
        gfx_rrect(&fb, PX((float)t->x - 1.5f + ph), PY(st.camy + 1.f + hashf(i, k + 45) * (float)(TH - 3)), cs * (1.f + hashf(i, 47)), cs * .05f, cs * .025f, RGB(240, 248, 255), (int)(130.f * g));
    }
}

static void draw_adv_world(void) {
    const LvLevel *L = stage_def();
    float uu = (float)cell;
    int c0 = camPx / cell - 1, r0 = camPy / cell - 1;
    for (int r = r0 < 0 ? 0 : r0; r < st.wh && r <= r0 + TH + 2; r++)
        for (int c = c0 < 0 ? 0 : c0; c < st.ww && c <= c0 + TW + 2; c++) {
            int k = st.tile[r][c], t;
            int X = ox + c * cell - camPx + (int)shx, Y = r * cell - camPy + (int)shy;
            if (!k) {                                          /* a lava pit's bottom (lava stages): the stage's lava tile */
                if (r == st.wh - 1 && L->tPit != LV_TILE_NONE) lv_draw_tile(&fb, L->tPit, X, Y);
                continue;
            }
            if (k == 5) t = L->tCrate;
            else if (k == SGT_CRUMBLE) {                       /* a crumbling ledge: shakes, cracks, falls */
                int sh = crumble_shake(c, r);
                if (sh >= 0) X += ((sh / 2) & 1 ? 1 : -1) * (cell / 18 + 1);
                lv_draw_tile(&fb, L->tOneway != LV_TILE_NONE ? L->tOneway : L->tTop, X, Y);
                crumble_cracks((float)X, (float)Y, (float)cell);
                continue;
            }
            else if (is_oneway(k)) t = L->tOneway != LV_TILE_NONE ? L->tOneway : L->tTop;
            else t = (r == 0 || st.tile[r - 1][c] == 0 || st.tile[r - 1][c] == 5 || is_oneway(st.tile[r - 1][c])) ? L->tTop : L->tFill;
            lv_draw_tile(&fb, t, X, Y);
        }
    for (int m = 0; m < GS.nmovers; m++) {                    /* moving platforms */
        const SgMover *mv = &GS.movers[m];
        float mx, my;
        sg_mover_pos(mv, st.mtime, &mx, &my);
        int t0 = L->tOneway != LV_TILE_NONE ? L->tOneway : L->tTop;
        float px = PX(mx - (float)mv->w * .5f), py = PY(my);
        for (int i = 0; i < mv->w; i++) lv_draw_tile(&fb, t0, (int)floorf(px + (float)(i * cell) + .5f), (int)floorf(py + .5f));
        gfx_rrect(&fb, px, py + .62f * uu, (float)(mv->w * cell), .1f * uu, .05f * uu, RGB(40, 90, 150), 120);
    }
    for (int k = 0; k < L->nthings; k++) if (L->things[k].kind == SGK_WIND) draw_wind_zone(k, &L->things[k]);
    if (!spr_ready()) return;
    /* scenery behind everything that plays: decorations, lanterns */
    static const int DECO_SPR[7] = {SPR_TREE_IDLE, SPR_SIGN_IDLE, SPR_BARREL_IDLE, SPR_CART_IDLE, SPR_CRYSTAL_IDLE, SPR_CHEST_IDLE, SPR_ROCK_IDLE};
    for (int k = 0; k < L->nthings; k++) {
        const LvThing *t = &L->things[k];
        float x = PX((float)t->x + .5f), y = PY((float)t->y);
        if (t->kind == LV_DECO && t->arg < 7 && !(t->arg == CHEST_ARG && taken_get(k))) {      /* an opened chest is gone */
            SprDraw d = {uu, 1.f, 1.f, 0.f, (t->x * 7 + t->y) & 1, 0, 255, 0};          /* every other one mirrored */
            spr_draw(&fb, spr_frame(DECO_SPR[t->arg], t->x + st.level, 1), x, y, &d);
        } else if (t->kind == LV_LADDER) prop(SPR_LADDER_IDLE, x, y, uu, (float)t->arg * .5f, 255, 0);
        else if (t->kind == LV_LAMP) {
            gfx_circle(&fb, x, y - 1.55f * uu, (.8f + .05f * sinf((float)st.frame * .1f + (float)k)) * uu, RGB(255, 200, 110), 38);   /* the lantern's glow */
            prop(SPR_LAMP_IDLE, x, y, uu, 1.f, 255, 0);
        }
    }
    float dx = PX((float)L->exitX + .5f), dy = PY((float)L->exitY);
    if (st.exitOpen) gfx_circle(&fb, dx, dy - 1.1f * uu, (1.2f + .08f * sinf((float)st.frame * .12f)) * uu, RGB(140, 220, 255), 40);
    prop(SPR_DOOR_IDLE, dx, dy, uu, 1.f, st.exitOpen ? 255 : 170, 0);
    if (!st.exitOpen)                                          /* barred until the boss falls */
        for (int b = 0; b < 2; b++) gfx_rrect(&fb, dx - .7f * uu, dy - (1.35f - .6f * (float)b) * uu, 1.4f * uu, .16f * uu, .06f * uu, RGB(70, 50, 40), 255);
    float fx = PX((float)L->cpX + .5f), fy = PY((float)L->cpY);
    if (st.cpReached) gfx_circle(&fb, fx, fy - 1.f * uu, .9f * uu, RGB(140, 200, 255), 30);
    { SprDraw d = {uu, st.cpReached ? 1.f + .04f * sinf((float)st.frame * .15f) : 1.f, 1.f, 0.f, 0, 0, 255, 0};
      spr_draw(&fb, spr_frame(SPR_FLAG_IDLE, st.cpReached, 0), fx, fy, &d); }          /* red flag, blue once passed */
    for (int k = 0; k < L->nthings; k++) {
        const LvThing *t = &L->things[k];
        float x = PX((float)t->x + .5f), y = PY((float)t->y);
        switch (t->kind) {
        case LV_SPIKES: prop(SPR_SPIKES_IDLE, x, y, uu, 1.f, 255, 0); break;
        case LV_PITSPIKES:                                     /* the bottom of a pit, all the way across (lava stages: lava) */
            if (L->tPit != LV_TILE_NONE) break;
            for (int c = t->x; c >= 0 && tile_at(c, st.wh - 1) == 0; c--) prop(SPR_SPIKES_IDLE, PX((float)c + .5f), PY((float)st.wh), uu, 1.f, 220, 0);
            for (int c = t->x + 1; c < st.ww && tile_at(c, st.wh - 1) == 0; c++) prop(SPR_SPIKES_IDLE, PX((float)c + .5f), PY((float)st.wh), uu, 1.f, 220, 0);
            break;
        case LV_SPRING: prop(SPR_SPRING_IDLE, x, y, uu, 1.f, 255, 0); break;
        case SGK_ICICLE: { int j = 0; for (int q = 0; q < k; q++) j += L->things[q].kind == SGK_ICICLE; if (j < MAXIC) draw_icicle(t, &st.ic[j]); break; }
        case LV_COIN: case LV_GEM:
            if (taken_get(k)) break;
            {
                float bob = sinf((float)st.frame * .08f + (float)k) * .06f * uu, cy = PY((float)t->y + .5f) + bob;
                SprDraw d = {uu, 1.f, 1.f, 0.f, 0, 0, 255, 0};
                if (t->kind == LV_GEM) {
                    gfx_circle(&fb, x, cy, .55f * uu, k & 1 ? RGB(120, 230, 255) : RGB(255, 110, 140), 40);
                    spr_draw(&fb, spr_frame(SPR_GEM_IDLE, k & 1, 0), x, cy, &d);                  /* ruby or diamond */
                } else spr_draw(&fb, spr_frame(SPR_COIN_IDLE, st.frame / 8 + k, 1), x, cy, &d);     /* spinning */
            }
            break;
        default: break;
        }
    }
}

static void draw_hud(void) {
    char b[48];
    int sc = fs(3);
    float pw = u(300), ph = u(60), py = u(10);
    for (int i = 0; i < 2; i++) {
        const Player *p = &st.pl[i];
        float x = i == 0 ? u(14) : (float)W - u(14) - pw;
        panel(x, py, pw, ph, u(12));
        draw_buddy(st.skin[i], x + u(32), py + u(52), u(32), i == 0 ? 1 : -1, st.frame, 0, 0, 0, 0, 0, 0);   /* fits the panel */
        if (!p->active) {
            if ((st.frame / 30) & 1) gfx_text(&fb, x + u(66), py + u(24), fs(2), "P2 PRESS START", C_DIM, 255);
            else gfx_text(&fb, x + u(66), py + u(24), fs(2), "CO-OP JOIN", C_DIM, 160);
            continue;
        }
        snprintf(b, sizeof b, "%06d", p->score);
        gfx_text(&fb, x + u(66), py + u(10), sc, b, C_TEXT, 255);
        for (int l = 0; l < p->lives && l < 6; l++) {
            float hx = x + u(72) + (float)l * u(22), hy = py + u(46);
            gfx_circle(&fb, hx, hy, u(7), SKINS[st.skin[i]].body, 255);
            gfx_circle(&fb, hx, hy + u(2), u(4.5f), SKINS[st.skin[i]].belly, 255);
        }
        if (p->speedUp) gfx_circle(&fb, x + pw - u(60), py + u(44), u(6), RGB(70, 150, 255), 255);
        if (p->rangeUp) gfx_circle(&fb, x + pw - u(42), py + u(44), u(6), RGB(255, 210, 70), 255);
        if (p->powerUp) gfx_circle(&fb, x + pw - u(24), py + u(44), u(6), RGB(255, 80, 90), 255);
    }
    float cw = u(250), cx = (float)W * .5f - cw * .5f;
    panel(cx, py, cw, ph, u(12));
    if (adventure()) {                                    /* stage and time left */
        snprintf(b, sizeof b, "STAGE %d", st.level);
        gfx_text_c(&fb, cx + cw * .5f, py + u(9), fs(2.6f), b, boss_fight() ? RGB(255, 120, 120) : C_ACCENT, 255);
        int left = ((int)stage_def()->seconds * 60 - st.levelFrames + 59) / 60; if (left < 0) left = 0;
        snprintf(b, sizeof b, "TIME %d", left);
        gfx_text_c(&fb, cx + cw * .5f, py + u(34), fs(2), b, left <= 10 && ((st.frame / 15) & 1) ? RGB(255, 110, 110) : C_GOLD, 255);
    } else {
        snprintf(b, sizeof b, "LEVEL %d", st.level);
        gfx_text_c(&fb, cx + cw * .5f, py + u(9), fs(2.6f), b, is_boss_level(st.level) ? RGB(255, 120, 120) : C_ACCENT, 255);
        uint32_t hi = save.hi; if ((uint32_t)total_score() > hi) hi = (uint32_t)total_score();
        snprintf(b, sizeof b, "HI %06u", (unsigned)hi);
        gfx_text_c(&fb, cx + cw * .5f, py + u(34), fs(2), b, C_GOLD, 255);
    }
    if (st.comboCount >= 2 && st.comboTimer > 0) {
        snprintf(b, sizeof b, "COMBO X%d", st.comboCount > 5 ? 5 : st.comboCount);
        float pulse = 1.f + (st.comboTimer > 140 ? (float)(st.comboTimer - 140) * .02f : 0.f);
        gfx_text_c(&fb, cx + cw * .5f + u(2), py + ph + u(10) + u(2), fs(3.4f * pulse), b, RGB(0, 0, 0), 120);
        gfx_text_c(&fb, cx + cw * .5f, py + ph + u(10), fs(3.4f * pulse), b, C_GOLD, 255);
        float bw = u(190) * (float)st.comboTimer / 150.f;
        gfx_rrect(&fb, cx + cw * .5f - u(95), py + ph + u(48), u(190), u(7), u(3.5f), RGB(10, 20, 40), 180);
        gfx_rrect(&fb, cx + cw * .5f - u(95), py + ph + u(48), bw, u(7), u(3.5f), C_GOLD, 255);
    }
}

static void shadow_text(float cxp, float y, int scale, const char *t, uint32_t col) {
    gfx_text_c(&fb, cxp + scale * .6f, y + scale * .7f, scale, t, RGB(0, 0, 0), 120);
    gfx_text_c(&fb, cxp, y, scale, t, col, 255);
}

static void dim(int a) { gfx_rrect(&fb, 0, 0, (float)W, (float)H, 0, RGB(2, 8, 20), a); }

static void draw_title(void) {
    dim(70);
    float cxp = (float)W * .5f;
    int ls = fs(15);
    const char *word = "BLIZZARD";
    float total = (float)gfx_text_w(word, ls), lx = cxp - total * .5f;
    for (int i = 0; i < 8; i++) {
        char one[2] = {word[i], 0};
        float bobv = sinf((float)st.frame * .06f + (float)i * .6f) * u(5), x = lx + (float)i * 6.f * (float)ls, y = u(78) + bobv;
        gfx_text(&fb, x + ls * .5f, y + ls * .6f, ls, one, RGB(10, 30, 70), 130);
        gfx_text(&fb, x, y, ls, one, gfx_mix(RGB(255, 255, 255), RGB(120, 200, 255), (float)i / 7.f), 255);
    }
    shadow_text(cxp, u(78) + 7.f * (float)ls + u(14), fs(9), "BUDDIES", C_GOLD);
    float floorY = PY((float)(TH - 1));
    int t0 = st.frame % 100, t1 = (st.frame + 50) % 100;                     /* a throw every 100 frames each */
    draw_buddy(st.skin[0], cxp - u(330), floorY, u(110), 1, st.frame, 0, 0, 0, t0 < 10 ? 10 - t0 : 0, 0, 0);
    draw_buddy(st.skin[1], cxp + u(330), floorY, u(110), -1, st.frame, 0, 0, 0, t1 < 10 ? 10 - t1 : 0, 0, 0);
    for (int row = 0; row < 3; row++) {
        float y = u(284) + (float)row * u(40);
        int sel = st.menuRow == row;
        if (sel) gfx_rrect(&fb, cxp - u(300), y - u(5), u(600), u(36), u(11), C_ACCENT, 36 + (int)(14 * sinf((float)st.frame * .1f)));
        gfx_text(&fb, cxp - u(270), y + u(4), fs(3), row == 0 ? "MODE" : (row == 1 ? "DIFFICULTY" : "BUDDY"), sel ? C_TEXT : C_DIM, 255);
        const char *v = row == 0 ? (save.mode == M_ADVENTURE ? "ADVENTURE" : "ARENA") : (row == 1 ? DIFF_NAME[st.difficulty] : SKIN_NAME[st.skin[0]]);
        float vx = cxp + u(130);
        gfx_text_c(&fb, vx, y + u(4), fs(3), v, sel ? C_GOLD : C_TEXT, 255);
        if (sel) { gfx_text(&fb, vx - u(110), y + u(4), fs(3), "<", C_TEXT, 255); gfx_text(&fb, vx + u(110) - 5.f * (float)fs(3), y + u(4), fs(3), ">", C_TEXT, 255); }
    }
    for (int k = 0; k < NSKINS; k++) if (!skin_unlocked(k)) {
        char hint[64];
        snprintf(hint, sizeof hint, "UNLOCK %s: REACH LEVEL %d (BEST %d)", SKIN_NAME[k], SKIN_UNLOCK[k], (int)save.bestLevel);
        gfx_text_c(&fb, cxp, u(406), fs(2), hint, C_DIM, 255);
        break;
    }
    if ((st.frame / 30) & 1) shadow_text(cxp, u(434), fs(4), "PRESS A TO PLAY", C_TEXT);
    else shadow_text(cxp, u(434), fs(4), "PRESS A TO PLAY", C_DIM);
    gfx_text_c(&fb, cxp, u(484), fs(2.4f), "START ON PAD 2 = TWO PLAYERS CO-OP", C_ACCENT, 255);
    gfx_text_c(&fb, cxp, u(518), fs(2), "D-PAD MOVE    B JUMP    A THROW SNOW    START PAUSE", C_DIM, 255);
    char b[40];
    snprintf(b, sizeof b, "HI SCORE %06u", (unsigned)save.hi);
    gfx_text_c(&fb, cxp, u(556), fs(2.4f), b, C_GOLD, 255);
}

static void draw_banner(void) {
    float cxp = (float)W * .5f;
    char b[48];
    if (st.phase == PH_INTRO) {
        int a = st.timer < 12 ? st.timer * 21 : (st.timer > 90 ? (110 - st.timer) * 12 : 255);
        a = a > 255 ? 255 : (a < 0 ? 0 : a);
        dim(a / 3);
        if (adventure()) {
            snprintf(b, sizeof b, "STAGE %d", st.level);
            gfx_text_c(&fb, cxp + u(4), u(200) + u(4), fs(11), b, RGB(0, 0, 0), a / 2);
            gfx_text_c(&fb, cxp, u(200), fs(11), b, C_TEXT, a);
            gfx_text_c(&fb, cxp, u(300), fs(5), stage_def()->name, C_ACCENT, a);
            gfx_text_c(&fb, cxp, u(370), fs(2.6f), st.level == LV_COUNT ? "THE RAGE OGRE WAITS AT THE END" : "REACH THE DOOR", C_DIM, a);
            gfx_text_c(&fb, cxp, u(420), fs(4), "GET READY!", C_GOLD, a);
            snprintf(b, sizeof b, "SEED %08X", (unsigned)st.seed);
            gfx_text_c(&fb, cxp, u(500), fs(2), b, C_DIM, a / 2);
            return;
        }
        if (is_boss_level(st.level)) snprintf(b, sizeof b, "BOSS FIGHT!"); else snprintf(b, sizeof b, "LEVEL %d", st.level);
        gfx_text_c(&fb, cxp + u(4), u(226) + u(4), fs(13), b, RGB(0, 0, 0), a / 2);
        gfx_text_c(&fb, cxp, u(226), fs(13), b, is_boss_level(st.level) ? RGB(255, 120, 120) : C_TEXT, a);
        {
            char l1[48] = "", l2[48] = "";
            int ice = 0, crumble = 0;
            for (int r = 0; r < TH; r++) for (int c = 0; c < TW; c++) { if (is_ice(st.tile[r][c])) ice = 1; if (st.tile[r][c] == SGT_CRUMBLE) crumble = 1; }
            if (!is_boss_level(st.level)) {
                if (is_ice(st.tile[TH - 1][0])) snprintf(l1, sizeof l1, "SLIPPERY ICE FLOOR");
                else if (ice) snprintf(l1, sizeof l1, "SLIPPERY ICE");
                if (st.mods & SGA_WIND) snprintf(l1 + strlen(l1), sizeof l1 - strlen(l1), "%sWINDY", l1[0] ? "  -  " : "");
                if (crumble) snprintf(l2, sizeof l2, "CRUMBLING LEDGES");
                if (st.mods & MOD_SWARM) snprintf(l2 + strlen(l2), sizeof l2 - strlen(l2), "%sSWARM", l2[0] ? "  -  " : "");
                if (st.mods & MOD_ELITE) snprintf(l2 + strlen(l2), sizeof l2 - strlen(l2), "%sELITE MONSTERS", l2[0] ? "  -  " : "");
            }
            if (l1[0]) gfx_text_c(&fb, cxp, u(344), fs(3.2f), l1, C_ACCENT, a);
            if (l2[0]) gfx_text_c(&fb, cxp, u(378), fs(3.2f), l2, RGB(255, 190, 120), a);
        }
        gfx_text_c(&fb, cxp, u(424), fs(4), "GET READY!", C_GOLD, a);
        snprintf(b, sizeof b, "SEED %08X", (unsigned)st.seed);
        gfx_text_c(&fb, cxp, u(500), fs(2), b, C_DIM, a / 2);
    } else if (st.phase == PH_CLEAR) {
        int a = st.timer < 12 ? st.timer * 21 : 255;
        const char *clr = adventure() ? "STAGE CLEAR!" : "LEVEL CLEAR!";
        gfx_text_c(&fb, cxp + u(4), u(150) + u(4), fs(11), clr, RGB(0, 0, 0), a / 2);
        gfx_text_c(&fb, cxp, u(150), fs(11), clr, C_GOLD, a);
        if (st.timer > 25) { snprintf(b, sizeof b, "TIME BONUS  +%d", st.tallyTime); gfx_text_c(&fb, cxp, u(290), fs(3.4f), b, C_TEXT, 255); }
        if (st.timer > 55) {
            if (st.tallyNoMiss) { snprintf(b, sizeof b, "NO MISS BONUS  +%d", st.tallyNoMiss); gfx_text_c(&fb, cxp, u(340), fs(3.4f), b, C_GOLD, 255); }
            else gfx_text_c(&fb, cxp, u(340), fs(3), "A LIFE WAS LOST: NO MISS BONUS", C_DIM, 255);
        }
        if (st.timer > 85 && adventure()) {
            if (st.level >= LV_COUNT) gfx_text_c(&fb, cxp, u(420), fs(3.6f), "THE ADVENTURE IS DONE!", C_GOLD, 255);
            else { snprintf(b, sizeof b, "NEXT: STAGE %d  %s", st.level + 1, LV_LEVELS[st.level].name); gfx_text_c(&fb, cxp, u(420), fs(3.2f), b, C_ACCENT, 255); }
        } else if (st.timer > 85) {
            int next = st.level + 1;
            if (is_boss_level(next)) gfx_text_c(&fb, cxp, u(420), fs(3.6f), "NEXT: BOSS FIGHT!", RGB(255, 120, 120), 255);
            else { snprintf(b, sizeof b, "NEXT: LEVEL %d", next); gfx_text_c(&fb, cxp, u(420), fs(3.2f), b, C_ACCENT, 255); }
        }
    } else if (st.bannerTimer > 0 && adventure() && st.arenaOn == 1) {     /* the final boss: its arrival, then each phase */
        int a = st.bannerTimer > 80 ? (100 - st.bannerTimer) * 12 : (st.bannerTimer < 20 ? st.bannerTimer * 12 : 255);
        a = a > 255 ? 255 : (a < 0 ? 0 : a);
        if (st.bossPhase <= 1) snprintf(b, sizeof b, "BOSS FIGHT!"); else snprintf(b, sizeof b, "RAGE PHASE %d!", st.bossPhase);
        gfx_text_c(&fb, cxp + u(4), u(220) + u(4), fs(12), b, RGB(0, 0, 0), a / 2);
        gfx_text_c(&fb, cxp, u(220), fs(12), b, RGB(255, 110, 90), a);
        if (st.bossPhase >= 3) gfx_text_c(&fb, cxp, u(340), fs(3.2f), "FASTER, MORE FIRE: HANG ON!", C_TEXT, a);
    } else if (st.bannerTimer > 0 && st.hurry) {
        int a = st.bannerTimer > 90 ? (110 - st.bannerTimer) * 12 : (st.bannerTimer < 20 ? st.bannerTimer * 12 : 255);
        a = a > 255 ? 255 : (a < 0 ? 0 : a);
        gfx_text_c(&fb, cxp + u(4), u(220) + u(4), fs(12), "HURRY UP!", RGB(0, 0, 0), a / 2);
        gfx_text_c(&fb, cxp, u(220), fs(12), "HURRY UP!", ((st.bannerTimer / 6) & 1) ? RGB(255, 100, 100) : C_TEXT, a);
        gfx_text_c(&fb, cxp, u(350), fs(3.2f), "A GHOST IS COMING FOR YOU", C_DIM, a);
    }
}

static void draw_pause(void) {
    dim(150);
    float pw = u(560), ph = u(250), px = (float)W * .5f - pw * .5f, py = (float)H * .5f - ph * .5f;
    panel(px, py, pw, ph, u(18));
    gfx_text_c(&fb, px + pw * .5f, py + u(40), fs(10), "PAUSED", C_TEXT, 255);
    gfx_text_c(&fb, px + pw * .5f, py + ph - u(70), fs(3), "START RESUME   B MENU", C_DIM, 255);
}

static void draw_over(void) {
    dim(150);
    float pw = u(640), ph = u(380), px = (float)W * .5f - pw * .5f, py = (float)H * .5f - ph * .5f;
    panel(px, py, pw, ph, u(18));
    if (st.won) gfx_text_c(&fb, px + pw * .5f, py + u(44), fs(6), "ADVENTURE COMPLETE!", C_GOLD, 255);
    else gfx_text_c(&fb, px + pw * .5f, py + u(36), fs(9), "GAME OVER", RGB(255, 100, 110), 255);
    char b[64];
    snprintf(b, sizeof b, "SCORE %d", total_score());
    gfx_text_c(&fb, px + pw * .5f, py + u(140), fs(5), b, C_TEXT, 255);
    if (adventure()) snprintf(b, sizeof b, st.won ? "ALL %d STAGES CLEARED" : "REACHED STAGE %d  %s", st.won ? LV_COUNT : st.level, st.won ? "" : stage_def()->name);
    else snprintf(b, sizeof b, "REACHED LEVEL %d", st.level);
    gfx_text_c(&fb, px + pw * .5f, py + u(206), fs(3), b, C_ACCENT, 255);
    if (st.newBest) gfx_text_c(&fb, px + pw * .5f, py + u(250), fs(3), "NEW HIGH SCORE!", ((st.timer / 12) & 1) ? C_GOLD : C_TEXT, 255);
    else { snprintf(b, sizeof b, "HI SCORE %u", (unsigned)save.hi); gfx_text_c(&fb, px + pw * .5f, py + u(250), fs(3), b, C_DIM, 255); }
    gfx_text_c(&fb, px + pw * .5f, py + ph - u(60), fs(3), "A RETRY     B MENU", C_ACCENT, 255);
}

static void blit_bg(int dx, int dy) {
    size_t row = (size_t)fb.w * sizeof(uint16_t);
    memcpy(fb.px, bg, (size_t)fb.h * row);
    if (!dx && !dy) return;
    int y0 = dy > 0 ? dy : 0, y1 = dy < 0 ? fb.h + dy : fb.h;
    int n = fb.w - (dx < 0 ? -dx : dx);
    if (n <= 0) return;
    for (int y = y0; y < y1; y++)                          /* the same picture, slid by the shake */
        memcpy(fb.px + (size_t)y * fb.w + (dx > 0 ? dx : 0), bg + (size_t)(y - dy) * fb.w + (dx < 0 ? -dx : 0), (size_t)n * sizeof(uint16_t));
}

#ifdef RB_SPRITE_GALLERY
/* Test builds: every sprite frame at play size, one row per character, plus each animation playing, for checking the
 * art at each resolution with tools/core-harness. Three pages, two seconds each: the small characters (and the buddy
 * skins), the bosses and the effects, then the level props. */
static void draw_gallery(void) {
    static const char *const NAMES[SPR_NCHARS] = {"BUDDY", "WALKER", "HOPPER", "FLYER", "SPITTER", "CHARGER", "OGRE", "WIZARD", "RAGE", "FX",
        "COIN", "GEM", "FLAG", "DOOR", "SPIKES", "SPRING", "LAMP", "LADDER", "TREE", "SIGN", "BARREL", "CART", "CRYSTAL", "CHEST", "ROCK"};
    static const char *const TITLES[3] = {"SPRITE GALLERY 1/3", "SPRITE GALLERY 2/3", "SPRITE GALLERY 3/3"};
    float uu = (float)cell, y = 1.2f * uu;
    int page = (st.frame / 120) % 3;
    gfx_text(&fb, u(8), u(6), fs(2), TITLES[page], C_GOLD, 255);
    if (page == 2) {                                             /* the props side by side, two rows, feet on a line */
        float x = 1.f * uu;
        y = 5.f * uu;
        for (int c = SPR_COIN; c < SPR_NCHARS; c++) {
            const SprCharDef *cd = &SPR_CHARS[c];
            float w = 0.f;
            for (int f = 0; f < cd->count; f++) w += (float)SPR_FRAMES[cd->first + f].w / cd->ppt + .15f;
            if (x + w * uu > (float)W - .5f * uu) { x = 1.f * uu; y += 6.f * uu; }
            gfx_text(&fb, x, y + .6f * uu, fs(1.4f), NAMES[c], C_TEXT, 255);
            gfx_rrect(&fb, x, y, w * uu, 1.f, 0, RGB(255, 90, 90), 120);
            for (int f = 0; f < cd->count; f++) {
                int fr = cd->first + f;
                SprDraw d = {uu, 1.f, 1.f, 0.f, 0, 0, 255, 0};
                spr_draw(&fb, fr, x + SPR_FRAMES[fr].ax / cd->ppt * uu, y, &d);
                x += ((float)SPR_FRAMES[fr].w / cd->ppt + .15f) * uu;
            }
            x += .8f * uu;
        }
        return;
    }
    int c0 = page ? SPR_OGRE : 0, c1 = page ? SPR_COIN : SPR_OGRE;
    for (int c = c0; c < c1; c++) {
        const SprCharDef *cd = &SPR_CHARS[c];
        float rowH = 0.f;
        for (int f = 0; f < cd->count; f++) rowH = fmaxf(rowH, (float)SPR_FRAMES[cd->first + f].h / cd->ppt);
        int centred = c == SPR_FLYER || c == SPR_FX;
        y += rowH * uu;
        float x = 3.2f * uu;
        gfx_text(&fb, u(8), y - uu * .8f, fs(1.6f), NAMES[c], C_TEXT, 255);
        for (int f = 0; f < cd->count; f++) {
            int fr = cd->first + f;
            SprDraw d = {uu, 1.f, 1.f, 0.f, 0, 0, 255, 0};
            float ay = centred ? y - rowH * .5f * uu : y;
            spr_draw(&fb, fr, x + SPR_FRAMES[fr].ax / cd->ppt * uu, ay, &d);
            x += (float)SPR_FRAMES[fr].w / cd->ppt * uu + .15f * uu;
        }
        gfx_rrect(&fb, 3.2f * uu, y, x - 3.2f * uu, 1.f, 0, RGB(255, 90, 90), 120);          /* the feet line */
        y += .35f * uu;
    }
    float x = 3.2f * uu;
    if (!page) {
        y += 1.2f * uu;
        gfx_text(&fb, u(8), y - uu * .8f, fs(1.6f), "SKINS", C_TEXT, 255);
        for (int k = 0; k < SPR_CHARS[SPR_BUDDY].npal; k++) {                  /* the buddy skins, facing both ways */
            SprDraw d = {uu, 1.f, 1.f, 0.f, k & 1, k, 255, 0};
            spr_draw(&fb, spr_frame(SPR_BUDDY_IDLE, st.frame / 10, 1), x + .6f * uu, y, &d);
            x += 1.5f * uu;
        }
        y += .4f * uu;
    }
    /* the animations playing, the way the game times them */
    int a0 = page ? SPR_OGRE_IDLE : 0, a1 = page ? SPR_COIN_IDLE : SPR_OGRE_IDLE;
    float step = page ? 2.4f : 1.5f, rowH = page ? 1.8f : 1.5f, scale = page ? .5f : 1.f;   /* the bosses at half size */
    x = 3.2f * uu; y += rowH * uu;
    for (int a = a0; a < a1; a++) {
        int fps = SPR_ANIMS[a].fps ? SPR_ANIMS[a].fps : 3;
        int fr = spr_frame(a, st.frame * fps / 60, 1);
        int centred = (a >= SPR_FLYER_IDLE && a <= SPR_FLYER_DEATH) || a >= SPR_FX_ICEBALL;
        SprDraw d = {uu * scale, 1.f, 1.f, 0.f, 0, 0, 255, 0};
        spr_draw(&fb, fr, x + step * .5f * uu, centred ? y - .6f * uu * scale : y, &d);
        x += step * uu;
        if (x > (float)W - step * uu) { x = 3.2f * uu; y += rowH * uu; }
    }
}
#endif

static void draw(void) {
    int adv = adventure() && st.screen != S_TITLE;
    camPx = adv ? (int)floorf(st.camx * (float)cell) : 0;       /* whole pixels: tiles and characters share one grid */
    camPy = adv ? (int)floorf(st.camy * (float)cell) : 0;
    if (!adv && (bgLevel != st.level || bgSeed != st.seed || bgTitle != (st.screen == S_TITLE))) render_bg(st.level);
    shx = shy = 0.f;
    if (st.shake > 0 && st.screen == S_PLAY) {
        float a = (float)(st.shake > 14 ? 14 : st.shake) * u(.6f);
        shx = (float)(int)(sinf((float)st.frame * 2.3f) * a); shy = (float)(int)(cosf((float)st.frame * 3.1f) * a);
    }
    if (adv) {                     /* parallax: the painting slides within its 8% margin across the whole stage, snow at 0.5 */
        float fx = st.ww > TW ? st.camx / (float)(st.ww - TW) : 0.f, fy = st.wh > TH ? st.camy / (float)(st.wh - TH) : 0.f;
        if (!lv_draw_bg(&fb, fx, fy)) gfx_vgrad(&fb, RGB(20, 30, 70), RGB(60, 70, 130));
        draw_snowfall(); draw_adv_world();
    }
    else { blit_bg((int)shx, (int)shy); draw_snowfall(); if (st.screen != S_TITLE) draw_arena_dynamic(); }
    float uu = (float)cell;
    for (int i = 0; i < MAXI; i++) if (st.it[i].used) draw_item(&st.it[i]);
    for (int i = 0; i < MAXE; i++) if (st.en[i].used && st.en[i].type != E_BOSS) draw_enemy(&st.en[i]);
    for (int i = 0; i < MAXE; i++) if (st.en[i].used && st.en[i].type == E_BOSS) draw_enemy(&st.en[i]);
    for (int i = 0; i < MAXS; i++) if (st.sh[i].used) draw_shot(&st.sh[i]);
    if (st.screen != S_TITLE)
        for (int i = 0; i < 2; i++) {
            const Player *p = &st.pl[i];
            if (!p->active || p->dead >= 5000) continue;
            if (p->invuln > 0 && !p->dead && ((p->invuln / 4) & 1)) continue;
            draw_buddy(st.skin[i], PX(p->x), PY(p->y), uu, p->dir, p->anim, fabs_(p->vx) > .03f, !p->ground, p->vy, p->throwAnim, p->dead, p->land);
        }
    for (int i = 0; i < MAXPT; i++) {
        const Part *q = &st.pt[i];
        if (q->life <= 0) continue;
        float k = (float)q->life / (float)q->max;
        gfx_circle(&fb, PX(q->x), PY(q->y), q->size * uu * (.4f + .6f * k), q->color, (int)(255 * k));
    }
    for (int i = 0; i < MAXPOP; i++) {
        const Pop *p = &st.pop[i];
        if (p->life <= 0) continue;
        char b[16];
        if (p->value > 0) snprintf(b, sizeof b, "+%d", p->value); else snprintf(b, sizeof b, "UP!");
        float y = PY(p->y) - (float)(50 - p->life) * u(.7f);
        gfx_text_c(&fb, PX(p->x) + u(2), y + u(2), fs(2.2f), b, RGB(0, 0, 0), p->life > 14 ? 140 : p->life * 10);
        gfx_text_c(&fb, PX(p->x), y, fs(2.2f), b, p->value >= 1000 ? C_GOLD : C_TEXT, p->life > 14 ? 255 : p->life * 18);
    }
#ifdef RB_SPRITE_GALLERY
    if (st.screen == S_TITLE) { dim(150); draw_gallery(); return; }
#endif
    if (st.screen == S_TITLE) { draw_title(); return; }
    draw_hud();
    if (st.screen == S_PLAY) draw_banner();
    if (st.screen == S_PAUSE) draw_pause();
    if (st.screen == S_OVER) draw_over();
}

/* ---- public ---- */
static void bz_release(void) { free(fb.px); free(bg); fb.px = NULL; bg = NULL; bgLevel = -1; spr_release(); lv_release(); }

static int bz_setup(int w, int h) {
    bz_release();
    W = w; H = h;
    fb.w = w; fb.h = h;
    fb.px = (uint16_t *)calloc((size_t)w * h, sizeof(uint16_t));
    bg = (uint16_t *)calloc((size_t)w * h, sizeof(uint16_t));
    if (!fb.px || !bg) { bz_release(); return 0; }
    gfx_clip_reset(&fb);
    S = (float)h / 720.f;
    cell = h / TH; if (cell < 8) cell = 8;
    ox = (w - TW * cell) / 2;
    bgLevel = -1; bgTitle = -1;
    spr_setup(cell);                                       /* without memory for them the characters are drawn in code */
    lv_setup(cell, w, h);
    if (adventure()) lv_background(st.level - 1);
    return 1;
}

static void bz_reset(void) {
    if (!st.rng) st.rng = 0x2545F491u;
    sanitize_save();
    st.magic = STATE_MAGIC;
    memset(prev, 0, sizeof prev);
    st.difficulty = save.diff > 2 ? 1 : (int)save.diff;
    st.skin[0] = (int)save.skin < NSKINS ? (int)save.skin : 0;
    to_title();
}

static void bz_frame(const Pad pads[2]) {
    if (!synced) {
        sanitize_save(); synced = 1;
        st.difficulty = save.diff > 2 ? 1 : (int)save.diff;
        st.skin[0] = (int)save.skin < NSKINS ? (int)save.skin : 0;
        apply_menu_choices();
    }
    update(pads);
    draw();
}

static const uint16_t *bz_fb(void) { return fb.px; }
static size_t bz_state_size(void) { return sizeof(State); }
static int bz_state_save(void *dst) { memcpy(dst, &st, sizeof st); return 1; }

static int finite_ok(float v, float lo, float hi) { return isfinite(v) && v >= lo && v <= hi; }

static int bz_state_load(const void *src, size_t size) {
    if (size != sizeof(State)) return 0;
    State n; memcpy(&n, src, sizeof n);
    if (n.magic != STATE_MAGIC || n.screen < 0 || n.screen > S_OVER || n.phase < 0 || n.phase > PH_CLEAR || n.level < 1 || n.level > 9999) return 0;
    if (n.qn < 0 || n.qn > MAXQ || n.qi < 0 || n.qi > n.qn) return 0;
    if (n.difficulty < 0 || n.difficulty > 2 || n.skin[0] < 0 || n.skin[0] >= NSKINS || n.skin[1] < 0 || n.skin[1] >= NSKINS || n.menuRow < 0 || n.menuRow > 2) return 0;
    if (n.mode != M_ARENA && n.mode != M_ADVENTURE) return 0;
    if (n.mode == M_ADVENTURE && (n.level < 1 || n.level > LV_COUNT || n.ww < TW || n.ww > LV_MAXW || n.wh != LV_MAXH)) return 0;
    if (n.mode == M_ARENA && (n.ww != TW || n.wh != TH)) return 0;
    if (!finite_ok(n.camx, 0.f, (float)(n.ww - TW)) || !finite_ok(n.camy, 0.f, (float)(n.wh - TH)) || n.arenaOn < 0 || n.arenaOn > 2 || n.bossPhase < 0 || n.bossPhase > 3) return 0;
    float WX = (float)n.ww, WY = (float)n.wh;
    if (n.comboCount < 0 || n.comboCount > 999 || n.hitstop < 0 || n.hitstop > 60 || n.shake < 0 || n.shake > 60) return 0;
    for (int i = 0; i < 2; i++) {
        const Player *p = &n.pl[i];
        if (p->lives < 0 || p->lives > 99 || !finite_ok(p->x, -5.f, WX + 5.f) || !finite_ok(p->y, -10.f, WY + 10.f) || !finite_ok(p->vx, -2.f, 2.f) || !finite_ok(p->vy, -2.f, 2.f) || (p->dir != 1 && p->dir != -1)) return 0;
    }
    for (int i = 0; i < MAXE; i++) {
        const Enemy *e = &n.en[i];
        if (!e->used) continue;
        if (e->type < 0 || e->type >= E_TYPES || e->kind < 0 || e->kind > 2 || !finite_ok(e->kb, -2.f, 2.f) || e->state < 0 || e->state > ES_DYING || (e->dir != 1 && e->dir != -1) || !finite_ok(e->x, -5.f, WX + 5.f) || !finite_ok(e->y, -10.f, WY + 10.f) || !finite_ok(e->vx, -2.f, 2.f) || !finite_ok(e->vy, -2.f, 2.f) || !finite_ok(e->by, -10.f, 30.f)) return 0;
    }
    for (int i = 0; i < MAXS; i++) { const Shot *s = &n.sh[i]; if (s->used && (s->owner < 0 || s->owner > 2 || !finite_ok(s->x, -5.f, WX + 5.f) || !finite_ok(s->y, -10.f, WY + 10.f) || !finite_ok(s->vx, -2.f, 2.f) || !finite_ok(s->vy, -2.f, 2.f))) return 0; }
    for (int i = 0; i < MAXI; i++) { const Item *t = &n.it[i]; if (t->used && (t->type < 0 || t->type >= IT_TYPES || !finite_ok(t->x, -5.f, WX + 5.f) || !finite_ok(t->y, -10.f, WY + 10.f) || !finite_ok(t->vy, -2.f, 2.f))) return 0; }
    for (int i = 0; i < MAXPT; i++) { const Part *q = &n.pt[i]; if (q->life > 0 && (q->max <= 0 || q->max > 200 || q->life > q->max || !finite_ok(q->x, -50.f, WX + 50.f) || !finite_ok(q->y, -50.f, WY + 50.f))) memset(&n.pt[i], 0, sizeof(Part)); }
    for (int i = 0; i < MAXQ; i++) if (n.queue[i] >= E_TYPES) return 0;
    for (int r = 0; r < HMAX; r++) for (int c = 0; c < WMAX; c++) if (n.tile[r][c] > SGT_CRUMBLE) return 0;   /* 3, 4: ice; 5: crate; 6: crumbling */
    if (n.mtime < 0 || n.mods < 0 || n.mods > 15) return 0;
    for (int i = 0; i < SG_MAXCR; i++) { Crumb *q = &n.cr[i]; if (q->mode < 0 || q->mode > 2 || q->c < 0 || q->c >= WMAX || q->r < 0 || q->r >= HMAX || q->t < 0 || q->t > 600) memset(q, 0, sizeof *q); }
    for (int i = 0; i < MAXIC; i++) { Icicle *q = &n.ic[i]; if (q->mode < 0 || q->mode > 3 || !finite_ok(q->y, -10.f, 40.f) || !finite_ok(q->vy, -2.f, 2.f) || q->t < 0 || q->t > 100) memset(q, 0, sizeof *q); }
    if (!n.rng) n.rng = 0x2545F491u;
    st = n;
    bgLevel = -1;
    glKey = 0;                                               /* the stage's things come back from the seed */
    if (adventure()) { (void)stage_def(); lv_background(st.level - 1); }
    snd_music(n.screen == S_TITLE ? MUSIC_TITLE : (n.screen == S_OVER ? MUSIC_OFF : MUSIC_PLAY));
    return 1;
}

static uint8_t *bz_sram(size_t *size) { *size = sizeof save; return (uint8_t *)&save; }

const Game GAME_BLIZZARD = {"blizzard", bz_setup, bz_release, bz_reset, bz_frame, bz_fb, bz_state_size, bz_state_save, bz_state_load, bz_sram};
