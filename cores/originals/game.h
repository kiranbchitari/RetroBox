/* Interface between the libretro glue (core.c) and the game (snake.c). */
#ifndef RB_GAME_H
#define RB_GAME_H
#include <stddef.h>
#include <stdint.h>

typedef struct { uint8_t up, down, left, right, a, b, start, select; } Pad;

/* (Re)creates the frame and the pre-rendered background for a w x h screen (16:9: 960x540, 1280x720, 1920x1080). 1 = ok. */
int game_setup(int w, int h);
void game_free(void);
void game_reset(void);                 /* back to the title screen */
void game_frame(const Pad *pad);       /* one 1/60 s step: update, then draw into the frame */
const uint16_t *game_fb(void);         /* RGB565 */

size_t game_state_size(void);
int game_state_save(void *dst);
int game_state_load(const void *src, size_t size);

/* Battery-backed data: best scores and the last mode / speed. */
uint8_t *game_sram(size_t *size);

#endif
