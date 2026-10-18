/* Stage generator for Blizzard Buddies: Arena layouts and Adventure stages are built from a seed, so no two runs play alike.
 *
 * The generator is plain C with no dependency on the game: blizzard.c asks for a stage (seed + stage number + difficulty) and
 * gets back a tile map, the things on it (coins, spikes, monsters, ...) and the moving platforms. The same seed and stage
 * always give the same result, so a saved state only needs the seed.
 *
 * Every jump the generator asks for is checked against the game's own jump physics (the PH_* numbers below, which
 * blizzard.c's update_player uses too) with a safety margin, so a stage can always be finished; tools/originals-levels/sg_check.c
 * proves it again by searching the stage with the real movement code. */
#ifndef RB_STAGEGEN_H
#define RB_STAGEGEN_H
#include <math.h>
#include <stdint.h>

/* the player's movement (blizzard.c update_player): top speed, jump, gravity while rising / falling, terminal speed, half width */
#define PH_VX 0.13f
#define PH_JUMP_VY (-0.575f)
#define PH_GUP 0.034f
#define PH_GDN 0.045f
#define PH_VMAX 0.62f
#define PH_HW 0.4f
#define PH_SPRING_VY (-0.68f)

#define SG_MAXW 140
#define SG_MAXH 20
#define SG_MAXTH 230
#define SG_MAXMOVER 8
#define SG_MAXCR 24                     /* crumbling tiles that can be falling at once */

/* tile ids (the game's st.tile): 0 empty, 1 solid, 2 one-way, 3 one-way ice, 4 solid ice, 5 crate, 6 crumbling one-way */
#define SGT_CRUMBLE 6

/* thing kinds: 1-10 are levels_data.h's LV_* (the Adventure art pack); 11 and up are new */
enum { SGK_COIN = 1, SGK_GEM = 2, SGK_SPIKES = 3, SGK_PITSPIKES = 4, SGK_LAMP = 6, SGK_CRATE = 7, SGK_ENEMY = 8, SGK_SPRING = 9,
       SGK_DECO = 10, SGK_ICICLE = 11, SGK_WIND = 12 };
/* SGK_DECO's arg: 0 tree, 1 sign, 2 barrel, 3 cart (not used: they would look usable), 4 crystal, 5 treasure chest, 6 rock.
 * SGK_ENEMY: arg = monster type (0 walker 1 hopper 2 flyer 4 spitter 5 charger), patrol = half width of its beat.
 * SGK_ICICLE: x, y = row of its top (it hangs from an ice ledge above). SGK_WIND: x = first column, arg = width, patrol = 1 blows right. */
typedef struct { uint8_t kind, x, y, arg, patrol; } SgThing;

/* A moving platform (a one-way plate) whose position is a pure function of the stage clock: no state to save. */
typedef struct { float cx, cy, ax, ay; int period, phase, w; } SgMover;
static inline void sg_mover_pos(const SgMover *m, int t, float *x, float *y) {
    float s = sinf((float)(t + m->phase) * 6.2831853f / (float)m->period);
    *x = m->cx + m->ax * s; *y = m->cy + m->ay * s;           /* x = centre of the plate, y = its top */
}

typedef struct {
    int w, h;
    uint8_t tile[SG_MAXH][SG_MAXW];
    int startX, startY, cpX, cpY, exitX, exitY, seconds;
    int arenaX0, arenaX1, bossX, bossY, phases;               /* arenaX1 = 0: no boss */
    int nthings; SgThing things[SG_MAXTH];
    int nmovers; SgMover movers[SG_MAXMOVER];
} SgStage;

typedef struct {
    uint32_t seed;
    int stage;                                                /* 1-based */
    int difficulty;                                           /* 0 easy 1 normal 2 hard */
    int lava, crates;                                         /* the stage's art has a lava pit tile / crates */
} SgParams;

/* Builds Adventure stage p->stage (1..10; the last one ends in the boss arena). */
void sg_adventure(SgStage *o, const SgParams *p);

#define SGA_ICE_FLOOR 1
#define SGA_WIND 2
#define SGA_W 32
#define SGA_H 18
/* Builds an Arena layout (32 x 18 tiles; level 5, 10, ... are boss levels with their own fixed boards, built by the game). Returns SGA_* flags. */
int sg_arena(uint8_t tile[SGA_H][SGA_W], uint32_t seed, int level);

/* The farthest the player's centre travels sideways in a full jump from the edge, landing dh rows higher than the take-off
 * (negative: lower); -1 when the jump cannot get up that high. */
float sg_reach(int dh);

#endif
