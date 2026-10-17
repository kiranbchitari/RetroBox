/* Interface between the libretro glue (core.c) and the games. Each game fills in one Game table. */
#ifndef RB_GAME_H
#define RB_GAME_H
#include <stddef.h>
#include <stdint.h>

typedef struct { uint8_t up, down, left, right, a, b, start, select; } Pad;

typedef struct Game {
    const char *id;                                   /* the "game=" line of a cartridge */
    int (*setup)(int w, int h);                       /* (re)creates the frame and background for a w x h screen (16:9: 960x540, 1280x720, 1920x1080); 1 = ok */
    void (*release)(void);
    void (*reset)(void);                              /* back to the title screen */
    void (*frame)(const Pad pads[2]);                 /* one 1/60 s step: update, then draw into the frame */
    const uint16_t *(*fb)(void);                      /* RGB565 */
    size_t (*state_size)(void);
    int (*state_save)(void *dst);
    int (*state_load)(const void *src, size_t size);
    uint8_t *(*sram)(size_t *size);                   /* battery-backed data: best scores, last menu choice */
} Game;

extern const Game GAME_SNAKE;
extern const Game GAME_BLIZZARD;

#endif
