/* Small sound-effect synthesiser: a handful of voices (square / triangle / noise) with pitch slides and envelopes. */
#ifndef RB_SND_H
#define RB_SND_H
#include <stdint.h>

enum { SND_MENU, SND_START, SND_EAT, SND_BONUS, SND_DIE, SND_PAUSE, SND_TURN };

void snd_init(int sample_rate);
void snd_play(int effect);
/* Writes `frames` interleaved stereo int16 frames. */
void snd_mix(int16_t *out, int frames);

#endif
