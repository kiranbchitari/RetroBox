#include "snd.h"
#include <math.h>
#include <string.h>

enum { W_SQUARE, W_TRI, W_NOISE };
#define VOICES 12

typedef struct {
    int active, wave;
    int delay;              /* samples before the voice starts */
    int left, total;        /* samples remaining / total */
    float phase, freq, slide, vol, duty;  /* slide: frequency multiplier applied every sample */
    uint32_t noise;
} Voice;

static Voice v[VOICES];
static int rate = 48000;

void snd_init(int sample_rate) { rate = sample_rate > 8000 ? sample_rate : 48000; memset(v, 0, sizeof v); }

static void voice(int wave, float f0, float f1, float ms, float vol, float delay_ms, float duty) {
    int slot = 0;
    for (int i = 0; i < VOICES; i++) { if (!v[i].active) { slot = i; break; } if (v[i].left < v[slot].left) slot = i; }
    Voice *p = &v[slot];
    memset(p, 0, sizeof *p);
    p->active = 1; p->wave = wave;
    p->total = p->left = (int)(ms * rate / 1000.f);
    p->delay = (int)(delay_ms * rate / 1000.f);
    p->freq = f0; p->vol = vol; p->duty = duty; p->noise = 0x1234567u + (uint32_t)slot * 7919u;
    float secs = ms / 1000.f;
    p->slide = secs > 0.f ? powf(f1 / f0, 1.f / (secs * (float)rate)) : 1.f;   /* per-sample frequency multiplier */
}

void snd_play(int fx) {
    switch (fx) {
    case SND_MENU:  voice(W_SQUARE, 880.f, 880.f, 45.f, .16f, 0.f, .25f); break;
    case SND_TURN:  voice(W_TRI, 330.f, 300.f, 25.f, .10f, 0.f, .5f); break;
    case SND_PAUSE: voice(W_SQUARE, 520.f, 390.f, 90.f, .16f, 0.f, .5f); break;
    case SND_START:
        voice(W_SQUARE, 392.f, 392.f, 80.f, .18f, 0.f, .25f);
        voice(W_SQUARE, 523.f, 523.f, 80.f, .18f, 80.f, .25f);
        voice(W_SQUARE, 659.f, 659.f, 80.f, .18f, 160.f, .25f);
        voice(W_SQUARE, 784.f, 784.f, 220.f, .18f, 240.f, .25f);
        break;
    case SND_EAT:
        voice(W_SQUARE, 600.f, 900.f, 70.f, .20f, 0.f, .125f);
        voice(W_SQUARE, 900.f, 1200.f, 90.f, .16f, 55.f, .125f);
        break;
    case SND_BONUS:
        voice(W_SQUARE, 659.f, 659.f, 70.f, .18f, 0.f, .5f);
        voice(W_SQUARE, 784.f, 784.f, 70.f, .18f, 65.f, .5f);
        voice(W_SQUARE, 988.f, 988.f, 70.f, .18f, 130.f, .5f);
        voice(W_SQUARE, 1319.f, 1319.f, 200.f, .18f, 195.f, .5f);
        break;
    case SND_DIE:
        voice(W_TRI, 330.f, 55.f, 650.f, .34f, 0.f, .5f);
        voice(W_NOISE, 900.f, 120.f, 380.f, .20f, 0.f, .5f);
        break;
    }
}

void snd_mix(int16_t *out, int frames) {
    for (int i = 0; i < frames; i++) {
        float mix = 0.f;
        for (int k = 0; k < VOICES; k++) {
            Voice *p = &v[k];
            if (!p->active) continue;
            if (p->delay > 0) { p->delay--; continue; }
            float env = (float)p->left / (float)p->total;             /* linear decay */
            float attack = (float)(p->total - p->left) / (rate * .003f);   /* 3 ms attack: no click */
            if (attack < 1.f) env *= attack;
            float s;
            switch (p->wave) {
            case W_SQUARE: s = p->phase < p->duty ? 1.f : -1.f; break;
            case W_TRI:    s = 4.f * fabsf(p->phase - .5f) - 1.f; break;
            default:
                p->noise ^= p->noise << 13; p->noise ^= p->noise >> 17; p->noise ^= p->noise << 5;
                s = ((p->noise >> 8) & 0xFFFF) / 32768.f - 1.f;
                break;
            }
            mix += s * p->vol * env;
            p->phase += p->freq / (float)rate;
            if (p->phase >= 1.f) p->phase -= floorf(p->phase);
            p->freq *= p->slide;
            if (--p->left <= 0) p->active = 0;
        }
        if (mix > 1.f) mix = 1.f; else if (mix < -1.f) mix = -1.f;
        int16_t sample = (int16_t)(mix * 24000.f);
        out[2 * i] = sample; out[2 * i + 1] = sample;
    }
}
