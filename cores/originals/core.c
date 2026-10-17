/* RetroBox Originals: a libretro core that plays original games made for RetroBox. A "cartridge" is a tiny .rbo file:
 *     RBO1
 *     game=snake
 * so one core can carry many games. Video is RGB565 at 960x540, 1280x720 or 1920x1080 (option, set by the app from the
 * screen), audio is 48 kHz stereo, controls are the RetroPad. Licensed GPL-2.0 like the rest of RetroBox. */
#include "libretro.h"
#include "game.h"
#include "snd.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define RATE 48000
#define FPS 60

static retro_environment_t env_cb;
static retro_video_refresh_t video_cb;
static retro_audio_sample_batch_t audio_cb;
static retro_input_poll_t poll_cb;
static retro_input_state_t input_cb;
static retro_log_printf_t log_cb;

static int width = 1280, height = 720;
static int loaded;
static const Game *cur;

static void logf_(enum retro_log_level lvl, const char *msg) { if (log_cb) log_cb(lvl, "[originals] %s\n", msg); }

void retro_set_environment(retro_environment_t cb) {
    env_cb = cb;
    static const struct retro_variable vars[] = {
        {"retrobox_originals_resolution", "Resolution (applies on the next start); 720p|1080p|540p"},
        {NULL, NULL},
    };
    cb(RETRO_ENVIRONMENT_SET_VARIABLES, (void *)vars);
    struct retro_log_callback lg;
    if (cb(RETRO_ENVIRONMENT_GET_LOG_INTERFACE, &lg)) log_cb = lg.log;
}

void retro_set_video_refresh(retro_video_refresh_t cb) { video_cb = cb; }
void retro_set_audio_sample(retro_audio_sample_t cb) { (void)cb; }
void retro_set_audio_sample_batch(retro_audio_sample_batch_t cb) { audio_cb = cb; }
void retro_set_input_poll(retro_input_poll_t cb) { poll_cb = cb; }
void retro_set_input_state(retro_input_state_t cb) { input_cb = cb; }

unsigned retro_api_version(void) { return RETRO_API_VERSION; }
void retro_init(void) {}
void retro_deinit(void) { if (cur) cur->release(); cur = NULL; loaded = 0; }

void retro_get_system_info(struct retro_system_info *info) {
    memset(info, 0, sizeof *info);
    info->library_name = "RetroBox Originals";
    info->library_version = "1.0";
    info->valid_extensions = "rbo";
    info->need_fullpath = false;
    info->block_extract = true;
}

void retro_get_system_av_info(struct retro_system_av_info *info) {
    memset(info, 0, sizeof *info);
    info->geometry.base_width = (unsigned)width;
    info->geometry.base_height = (unsigned)height;
    info->geometry.max_width = 1920;
    info->geometry.max_height = 1080;
    info->geometry.aspect_ratio = (float)width / (float)height;
    info->timing.fps = FPS;
    info->timing.sample_rate = RATE;
}

void retro_set_controller_port_device(unsigned port, unsigned device) { (void)port; (void)device; }

static void pick_resolution(void) {
    width = 1280; height = 720;
    struct retro_variable v = {"retrobox_originals_resolution", NULL};
    if (env_cb && env_cb(RETRO_ENVIRONMENT_GET_VARIABLE, &v) && v.value) {
        if (!strcmp(v.value, "1080p")) { width = 1920; height = 1080; }
        else if (!strcmp(v.value, "540p")) { width = 960; height = 540; }
    }
}

bool retro_load_game(const struct retro_game_info *game) {
    enum retro_pixel_format fmt = RETRO_PIXEL_FORMAT_RGB565;
    if (!env_cb || !env_cb(RETRO_ENVIRONMENT_SET_PIXEL_FORMAT, &fmt)) { logf_(RETRO_LOG_ERROR, "frontend does not support RGB565"); return false; }
    if (!game || !game->data || game->size < 5 || memcmp(game->data, "RBO1", 4) != 0) { logf_(RETRO_LOG_ERROR, "not a RetroBox Originals cartridge (missing RBO1 header)"); return false; }
    char head[256];
    size_t n = game->size < sizeof head - 1 ? game->size : sizeof head - 1;
    memcpy(head, game->data, n); head[n] = 0;
    static const Game *const games[] = {&GAME_SNAKE, &GAME_BLIZZARD};
    cur = NULL;
    for (size_t i = 0; i < sizeof games / sizeof games[0]; i++) {
        char want[64];
        snprintf(want, sizeof want, "game=%s", games[i]->id);
        if (strstr(head, want)) { cur = games[i]; break; }
    }
    if (!cur) { logf_(RETRO_LOG_ERROR, "unknown game in cartridge"); return false; }

    static const struct retro_input_descriptor desc[] = {
        {0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_UP, "Up"},
        {0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_DOWN, "Down"},
        {0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_LEFT, "Left"},
        {0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_RIGHT, "Right"},
        {0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_A, "Confirm / Shoot"},
        {0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_B, "Back / Jump"},
        {0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_START, "Pause"},
        {1, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_UP, "Up"},
        {1, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_DOWN, "Down"},
        {1, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_LEFT, "Left"},
        {1, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_RIGHT, "Right"},
        {1, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_A, "Confirm / Shoot"},
        {1, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_B, "Back / Jump"},
        {1, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_START, "Join / Pause"},
        {0, 0, 0, 0, NULL},
    };
    env_cb(RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS, (void *)desc);

    pick_resolution();
    if (!cur->setup(width, height)) { logf_(RETRO_LOG_ERROR, "out of memory"); return false; }
    snd_init(RATE);
    cur->reset();
    loaded = 1;
    return true;
}

bool retro_load_game_special(unsigned type, const struct retro_game_info *info, size_t num) { (void)type; (void)info; (void)num; return false; }

void retro_unload_game(void) { if (cur) cur->release(); cur = NULL; loaded = 0; }
void retro_reset(void) { if (loaded) cur->reset(); }
unsigned retro_get_region(void) { return RETRO_REGION_NTSC; }

static int held(unsigned port, unsigned id) { return input_cb(port, RETRO_DEVICE_JOYPAD, 0, id) != 0; }

static Pad read_pad(unsigned port) {
    Pad p = { (uint8_t)held(port, RETRO_DEVICE_ID_JOYPAD_UP), (uint8_t)held(port, RETRO_DEVICE_ID_JOYPAD_DOWN), (uint8_t)held(port, RETRO_DEVICE_ID_JOYPAD_LEFT),
              (uint8_t)held(port, RETRO_DEVICE_ID_JOYPAD_RIGHT), (uint8_t)(held(port, RETRO_DEVICE_ID_JOYPAD_A) || held(port, RETRO_DEVICE_ID_JOYPAD_X)),
              (uint8_t)(held(port, RETRO_DEVICE_ID_JOYPAD_B) || held(port, RETRO_DEVICE_ID_JOYPAD_Y)), (uint8_t)held(port, RETRO_DEVICE_ID_JOYPAD_START),
              (uint8_t)held(port, RETRO_DEVICE_ID_JOYPAD_SELECT) };
    /* the left stick steers too (the dominant axis wins) */
    int ax = input_cb(port, RETRO_DEVICE_ANALOG, RETRO_DEVICE_INDEX_ANALOG_LEFT, RETRO_DEVICE_ID_ANALOG_X);
    int ay = input_cb(port, RETRO_DEVICE_ANALOG, RETRO_DEVICE_INDEX_ANALOG_LEFT, RETRO_DEVICE_ID_ANALOG_Y);
    if (abs(ax) > 20000 || abs(ay) > 20000) {
        if (abs(ax) >= abs(ay)) { if (ax > 0) p.right = 1; else p.left = 1; }
        else { if (ay > 0) p.down = 1; else p.up = 1; }
    }
    return p;
}

void retro_run(void) {
    if (!loaded) return;
    poll_cb();
    Pad pads[2] = { read_pad(0), read_pad(1) };
    cur->frame(pads);

    static int16_t audio[(RATE / FPS) * 2];
    snd_mix(audio, RATE / FPS);
    audio_cb(audio, RATE / FPS);
    video_cb(cur->fb(), (unsigned)width, (unsigned)height, (size_t)width * 2);
}

size_t retro_serialize_size(void) { return loaded ? cur->state_size() : 0; }
bool retro_serialize(void *data, size_t size) { return loaded && size >= cur->state_size() && cur->state_save(data); }
bool retro_unserialize(const void *data, size_t size) { return loaded && cur->state_load(data, size); }

void *retro_get_memory_data(unsigned id) { size_t n; return (cur && id == RETRO_MEMORY_SAVE_RAM) ? cur->sram(&n) : NULL; }
size_t retro_get_memory_size(unsigned id) { size_t n = 0; if (cur && id == RETRO_MEMORY_SAVE_RAM) cur->sram(&n); return n; }

void retro_cheat_reset(void) {}
void retro_cheat_set(unsigned index, bool enabled, const char *code) { (void)index; (void)enabled; (void)code; }
