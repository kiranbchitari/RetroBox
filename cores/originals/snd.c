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

/* ---- music: a lead and a bass line, 32 steps, one step every ~0.17 s ---- */
static const uint8_t LEAD[32] = {72, 76, 79, 76, 81, 79, 76, 79, 77, 81, 84, 81, 79, 76, 74, 76, 72, 76, 79, 76, 81, 79, 76, 72, 74, 77, 81, 79, 76, 74, 72, 0};
static const uint8_t BASS[32] = {48, 0, 55, 0, 45, 0, 52, 0, 41, 0, 48, 0, 43, 0, 50, 0, 48, 0, 55, 0, 45, 0, 52, 0, 50, 0, 57, 0, 43, 0, 50, 0};
static int music_track, music_step;
static long music_left;                      /* samples until the next step */
static float lead_phase, lead_freq, bass_phase, bass_freq, lead_env, bass_env;

static float midi_hz(int n) { return 440.f * powf(2.f, (float)(n - 69) / 12.f); }

void snd_music(int track) {
    if (track == music_track) return;
    music_track = track; music_step = 0; music_left = 0; lead_env = bass_env = 0.f;
}

static void music_tick(void) {
    int n = LEAD[music_step], b = BASS[music_step];
    if (n) { lead_freq = midi_hz(n); lead_env = 1.f; }
    if (b) { bass_freq = midi_hz(b); bass_env = 1.f; }
    music_step = (music_step + 1) & 31;
    music_left = (long)((music_track == MUSIC_TITLE ? 0.24f : 0.17f) * (float)rate);
}

static float music_sample(void) {
    if (!music_track) return 0.f;
    if (music_left <= 0) music_tick();
    music_left--;
    float lead = music_track == MUSIC_TITLE ? 4.f * fabsf(lead_phase - .5f) - 1.f : (lead_phase < .25f ? 1.f : -1.f);
    float bass = 4.f * fabsf(bass_phase - .5f) - 1.f;
    float out = lead * lead_env * (music_track == MUSIC_TITLE ? .07f : .045f) + bass * bass_env * .08f;
    lead_phase += lead_freq / (float)rate; if (lead_phase >= 1.f) lead_phase -= 1.f;
    bass_phase += bass_freq / (float)rate; if (bass_phase >= 1.f) bass_phase -= 1.f;
    lead_env *= .99985f; bass_env *= .9999f;
    return out;
}

void snd_init(int sample_rate) { rate = sample_rate > 8000 ? sample_rate : 48000; memset(v, 0, sizeof v); music_track = MUSIC_OFF; lead_env = bass_env = 0.f; }

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
    case SND_JUMP:  voice(W_SQUARE, 300.f, 620.f, 110.f, .13f, 0.f, .25f); break;
    case SND_THROW: voice(W_NOISE, 1400.f, 500.f, 55.f, .10f, 0.f, .5f); voice(W_TRI, 760.f, 420.f, 70.f, .12f, 0.f, .5f); break;
    case SND_HIT:   voice(W_SQUARE, 240.f, 130.f, 70.f, .15f, 0.f, .5f); break;
    case SND_FREEZE:
        voice(W_SQUARE, 880.f, 880.f, 60.f, .14f, 0.f, .125f); voice(W_SQUARE, 1175.f, 1175.f, 60.f, .14f, 55.f, .125f);
        voice(W_SQUARE, 1568.f, 1568.f, 140.f, .14f, 110.f, .125f); break;
    case SND_SHATTER: voice(W_NOISE, 2400.f, 300.f, 260.f, .26f, 0.f, .5f); voice(W_TRI, 700.f, 140.f, 220.f, .2f, 0.f, .5f); break;
    case SND_ITEM:
        voice(W_SQUARE, 784.f, 784.f, 60.f, .15f, 0.f, .25f); voice(W_SQUARE, 988.f, 988.f, 60.f, .15f, 55.f, .25f);
        voice(W_SQUARE, 1175.f, 1175.f, 60.f, .15f, 110.f, .25f); voice(W_SQUARE, 1568.f, 1568.f, 200.f, .15f, 165.f, .25f); break;
    case SND_HURT:  voice(W_TRI, 520.f, 90.f, 420.f, .32f, 0.f, .5f); voice(W_NOISE, 700.f, 150.f, 200.f, .15f, 0.f, .5f); break;
    case SND_CLEAR: {
        static const float n[6] = {523.f, 659.f, 784.f, 1047.f, 784.f, 1047.f};
        for (int i = 0; i < 6; i++) voice(W_SQUARE, n[i], n[i], i == 5 ? 380.f : 110.f, .16f, i * 110.f, .25f);
        break; }
    case SND_COMBO: voice(W_SQUARE, 700.f, 1400.f, 120.f, .15f, 0.f, .125f); break;
    case SND_BOSS:  voice(W_TRI, 110.f, 70.f, 700.f, .3f, 0.f, .5f); voice(W_NOISE, 400.f, 80.f, 500.f, .12f, 0.f, .5f); break;
    case SND_DIE:
        voice(W_TRI, 330.f, 55.f, 650.f, .34f, 0.f, .5f);
        voice(W_NOISE, 900.f, 120.f, 380.f, .20f, 0.f, .5f);
        break;
    }
}

void snd_mix(int16_t *out, int frames) {
    for (int i = 0; i < frames; i++) {
        float mix = music_sample();
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
