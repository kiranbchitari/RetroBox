/* Small sound-effect synthesiser: a handful of voices (square / triangle / noise) with pitch slides and envelopes. */
#ifndef RB_SND_H
#define RB_SND_H
#include <stdint.h>

enum { SND_MENU, SND_START, SND_EAT, SND_BONUS, SND_DIE, SND_PAUSE, SND_TURN,
       SND_JUMP, SND_THROW, SND_HIT, SND_FREEZE, SND_SHATTER, SND_ITEM, SND_HURT, SND_CLEAR, SND_COMBO, SND_BOSS };
enum { MUSIC_OFF, MUSIC_PLAY, MUSIC_TITLE };

void snd_init(int sample_rate);
void snd_play(int effect);
void snd_music(int track);   /* MUSIC_* : a small looping chiptune under the effects; MUSIC_OFF stops it */
/* Writes `frames` interleaved stereo int16 frames. */
void snd_mix(int16_t *out, int frames);

#endif
