/* core-harness: run a libretro core headless on the build machine (no Android, no app).
 *
 * Loads a core (.dylib/.so) with dlopen, loads a ROM, runs N frames with scripted RetroPad input and reports what the
 * frontend sees: system/AV info, input descriptors, core options, frame sizes, audio sample count, save RAM. It can dump
 * frames to PNG/PPM and print single pixels. Environment handling follows app/src/main/cpp/host.cpp (legacy
 * SET_VARIABLES options, GET_INPUT_BITMASKS, both ports set to RETRO_DEVICE_JOYPAD after load, SRAM loaded before the
 * first frame), so a ROM that works here exercises the same core paths the app uses.
 *
 * Build: tools/core-harness/build.sh      Usage: see usage() below.            CC0 / public domain.
 */
#include <dlfcn.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <zlib.h>

#include "libretro.h"

#define MAX_EV 256
#define MAX_OPT 128

static const char *BTN_NAMES[16] = {"b", "y", "select", "start", "up", "down", "left", "right",
                                    "a", "x", "l", "r", "l2", "r2", "l3", "r3"};

struct ev { int frame, port; uint16_t mask; };
struct dump { int frame; char path[512]; };
struct probe { int frame, x, y; };
struct opt { char key[128]; char desc[512]; char def[128]; };
struct ovr { char key[128]; char val[128]; };

static struct ev evs[MAX_EV]; static int nev;
static struct dump dumps[64]; static int ndump;
static struct probe probes[64]; static int nprobe;
static struct opt opts[MAX_OPT]; static int nopt;
static struct ovr ovrs[MAX_OPT]; static int novr;
static int devs[2] = {RETRO_DEVICE_JOYPAD, RETRO_DEVICE_JOYPAD};
static int quiet, show_opts;

static enum retro_pixel_format pixfmt = RETRO_PIXEL_FORMAT_0RGB1555;
static uint16_t pad[2];
static unsigned fw, fh;               /* last frame */
static uint8_t *rgb;                  /* last frame, RGB888 */
static size_t rgb_cap;
static long video_frames, dupe_frames, audio_frames;
static int audio_peak;
static unsigned sizes[16][2]; static int nsizes;
static int bitmask_queries, single_queries;
static char sysdir[512] = ".", savedir[512] = ".";

static void die(const char *fmt, ...) {
    va_list ap; va_start(ap, fmt); vfprintf(stderr, fmt, ap); va_end(ap); fputc('\n', stderr); exit(1);
}

static void core_log(enum retro_log_level level, const char *fmt, ...) {
    if (quiet && level < RETRO_LOG_WARN) return;
    static const char *lv[] = {"D", "I", "W", "E"};
    va_list ap; va_start(ap, fmt);
    fprintf(stderr, "  [core %s] ", lv[level & 3]); vfprintf(stderr, fmt, ap);
    va_end(ap);
}

static void parse_variables(const struct retro_variable *v) {
    for (; v && v->key; v++) {
        if (nopt >= MAX_OPT) break;
        struct opt *o = &opts[nopt++];
        snprintf(o->key, sizeof o->key, "%s", v->key);
        snprintf(o->desc, sizeof o->desc, "%s", v->value ? v->value : "");
        const char *semi = v->value ? strstr(v->value, "; ") : NULL;
        o->def[0] = 0;
        if (semi) { /* default = first value, as the app does */
            const char *s = semi + 2, *bar = strchr(s, '|');
            size_t n = bar ? (size_t)(bar - s) : strlen(s);
            if (n >= sizeof o->def) n = sizeof o->def - 1;
            memcpy(o->def, s, n); o->def[n] = 0;
        }
    }
}

static const char *dev_name(unsigned d) {
    switch (d & RETRO_DEVICE_MASK) {
        case RETRO_DEVICE_JOYPAD: return "joypad"; case RETRO_DEVICE_ANALOG: return "analog";
        case RETRO_DEVICE_MOUSE: return "mouse"; case RETRO_DEVICE_LIGHTGUN: return "lightgun";
        case RETRO_DEVICE_KEYBOARD: return "keyboard"; case RETRO_DEVICE_POINTER: return "pointer";
        default: return "none";
    }
}

static bool env(unsigned cmd, void *data) {
    unsigned c = cmd & ~(RETRO_ENVIRONMENT_EXPERIMENTAL | RETRO_ENVIRONMENT_PRIVATE);
    switch (c) {
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
            enum retro_pixel_format f = *(const enum retro_pixel_format *)data;
            if (f > RETRO_PIXEL_FORMAT_RGB565) return false;
            pixfmt = f;
            printf("env: pixel format %s\n", f == RETRO_PIXEL_FORMAT_RGB565 ? "RGB565" : f == RETRO_PIXEL_FORMAT_XRGB8888 ? "XRGB8888" : "0RGB1555");
            return true;
        }
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
        case RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY: *(const char **)data = sysdir; return true;
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY: *(const char **)data = savedir; return true;
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: ((struct retro_log_callback *)data)->log = core_log; return true;
        case RETRO_ENVIRONMENT_GET_CAN_DUPE: *(bool *)data = true; return true;
        case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION: *(unsigned *)data = 0; return true;
        case RETRO_ENVIRONMENT_SET_VARIABLES: parse_variables(data); return true;
        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            struct retro_variable *v = data;
            for (int i = 0; i < novr; i++) if (!strcmp(ovrs[i].key, v->key)) { v->value = ovrs[i].val; return true; }
            for (int i = 0; i < nopt; i++) if (!strcmp(opts[i].key, v->key)) { v->value = opts[i].def; return true; }
            v->value = NULL; return false;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE: *(bool *)data = false; return true;
        case RETRO_ENVIRONMENT_SET_GEOMETRY: {
            const struct retro_game_geometry *g = data;
            printf("env: SET_GEOMETRY %ux%u aspect %.4f\n", g->base_width, g->base_height, g->aspect_ratio);
            return true;
        }
        case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO: {
            const struct retro_system_av_info *a = data;
            printf("env: SET_SYSTEM_AV_INFO %ux%u (max %ux%u) fps %.4f rate %.1f\n", a->geometry.base_width,
                   a->geometry.base_height, a->geometry.max_width, a->geometry.max_height, a->timing.fps, a->timing.sample_rate);
            return true;
        }
        case RETRO_ENVIRONMENT_SET_MESSAGE: {
            const struct retro_message *m = data;
            if (m && m->msg) printf("env: message \"%s\"\n", m->msg);
            return true;
        }
        case RETRO_ENVIRONMENT_GET_LANGUAGE: *(unsigned *)data = RETRO_LANGUAGE_ENGLISH; return true;
        case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS: return true;
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS: {
            const struct retro_input_descriptor *d = data;
            printf("env: input descriptors\n");
            for (; d && d->description; d++)
                printf("  port %u %-7s id %-2u %-7s -> %s\n", d->port, dev_name(d->device), d->id,
                       (d->device & RETRO_DEVICE_MASK) == RETRO_DEVICE_JOYPAD && d->id < 16 ? BTN_NAMES[d->id] : "", d->description);
            return true;
        }
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO: {
            const struct retro_controller_info *ci = data;
            printf("env: controller info\n");
            for (unsigned p = 0; ci && ci[p].types; p++)
                for (unsigned i = 0; i < ci[p].num_types; i++)
                    printf("  port %u: %-28s device %u (0x%x)\n", p, ci[p].types[i].desc, ci[p].types[i].id, ci[p].types[i].id);
            return true;
        }
        case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL:
        case RETRO_ENVIRONMENT_SET_KEYBOARD_CALLBACK:
        case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
        case RETRO_ENVIRONMENT_SET_MEMORY_MAPS:
        case RETRO_ENVIRONMENT_SET_SERIALIZATION_QUIRKS:
        case RETRO_ENVIRONMENT_SET_MINIMUM_AUDIO_LATENCY:
        case RETRO_ENVIRONMENT_SET_CONTENT_INFO_OVERRIDE:
        case RETRO_ENVIRONMENT_SET_SUBSYSTEM_INFO:
            return true;
        default:
            return false;
    }
}

static void video(const void *data, unsigned w, unsigned h, size_t pitch) {
    if (!data) { dupe_frames++; return; }
    video_frames++;
    int known = 0;
    for (int i = 0; i < nsizes; i++) if (sizes[i][0] == w && sizes[i][1] == h) known = 1;
    if (!known && nsizes < 16) { sizes[nsizes][0] = w; sizes[nsizes][1] = h; nsizes++; }
    fw = w; fh = h;
    if (rgb_cap < (size_t)w * h * 3) { rgb_cap = (size_t)w * h * 3; rgb = realloc(rgb, rgb_cap); }
    for (unsigned y = 0; y < h; y++) {
        const uint8_t *row = (const uint8_t *)data + y * pitch;
        uint8_t *o = rgb + (size_t)y * w * 3;
        for (unsigned x = 0; x < w; x++, o += 3) {
            if (pixfmt == RETRO_PIXEL_FORMAT_XRGB8888) {
                uint32_t p = ((const uint32_t *)row)[x];
                o[0] = p >> 16; o[1] = p >> 8; o[2] = p;
            } else if (pixfmt == RETRO_PIXEL_FORMAT_RGB565) {
                uint16_t p = ((const uint16_t *)row)[x];
                o[0] = ((p >> 11) & 31) * 255 / 31; o[1] = ((p >> 5) & 63) * 255 / 63; o[2] = (p & 31) * 255 / 31;
            } else {
                uint16_t p = ((const uint16_t *)row)[x];
                o[0] = ((p >> 10) & 31) * 255 / 31; o[1] = ((p >> 5) & 31) * 255 / 31; o[2] = (p & 31) * 255 / 31;
            }
        }
    }
}

static void peak(int16_t v) { int a = v < 0 ? -v : v; if (a > audio_peak) audio_peak = a; }
static void audio1(int16_t l, int16_t r) { peak(l); peak(r); audio_frames++; }
static size_t audio_batch(const int16_t *d, size_t frames) {
    for (size_t i = 0; i < frames * 2; i++) peak(d[i]);
    audio_frames += frames; return frames;
}
static void input_poll(void) {}
static int16_t input_state(unsigned port, unsigned device, unsigned index, unsigned id) {
    (void)index;
    if (port > 1 || (device & RETRO_DEVICE_MASK) != RETRO_DEVICE_JOYPAD) return 0;
    if (id == RETRO_DEVICE_ID_JOYPAD_MASK) { bitmask_queries++; return (int16_t)pad[port]; }
    single_queries++;
    return id < 16 ? (pad[port] >> id) & 1 : 0;
}

static void put32(uint8_t *p, uint32_t v) { p[0] = v >> 24; p[1] = v >> 16; p[2] = v >> 8; p[3] = v; }

static void png_chunk(FILE *f, const char *type, const uint8_t *d, uint32_t n) {
    uint8_t h[8]; put32(h, n); memcpy(h + 4, type, 4);
    fwrite(h, 1, 8, f); if (n) fwrite(d, 1, n, f);
    uLong crc = crc32(crc32(0, (const Bytef *)type, 4), d, n);
    uint8_t c[4]; put32(c, (uint32_t)crc); fwrite(c, 1, 4, f);
}

static void write_frame(const char *path) {
    if (!rgb) { fprintf(stderr, "no frame to dump to %s\n", path); return; }
    FILE *f = fopen(path, "wb");
    if (!f) die("cannot write %s", path);
    size_t n = strlen(path);
    if (n > 4 && !strcmp(path + n - 4, ".ppm")) {
        fprintf(f, "P6\n%u %u\n255\n", fw, fh); fwrite(rgb, 1, (size_t)fw * fh * 3, f);
    } else {
        size_t raw_n = (size_t)(fw * 3 + 1) * fh;
        uint8_t *raw = malloc(raw_n);
        for (unsigned y = 0; y < fh; y++) { raw[y * (fw * 3 + 1)] = 0; memcpy(raw + y * (fw * 3 + 1) + 1, rgb + (size_t)y * fw * 3, fw * 3); }
        uLongf zn = compressBound(raw_n); uint8_t *z = malloc(zn);
        compress2(z, &zn, raw, raw_n, 6);
        static const uint8_t sig[8] = {0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
        uint8_t ihdr[13]; put32(ihdr, fw); put32(ihdr + 4, fh); ihdr[8] = 8; ihdr[9] = 2; ihdr[10] = ihdr[11] = ihdr[12] = 0;
        fwrite(sig, 1, 8, f); png_chunk(f, "IHDR", ihdr, 13); png_chunk(f, "IDAT", z, (uint32_t)zn); png_chunk(f, "IEND", NULL, 0);
        free(raw); free(z);
    }
    fclose(f);
}

static uint32_t frame_hash(void) {
    uint32_t h = 2166136261u;
    for (size_t i = 0; i < (size_t)fw * fh * 3; i++) h = (h ^ rgb[i]) * 16777619u;
    return h;
}

static uint16_t parse_buttons(const char *s) {
    uint16_t m = 0; char buf[256]; snprintf(buf, sizeof buf, "%s", s);
    for (char *t = strtok(buf, ",+"); t; t = strtok(NULL, ",+")) {
        if (!strcmp(t, "none")) continue;
        int found = 0;
        for (int i = 0; i < 16; i++) if (!strcmp(t, BTN_NAMES[i])) { m |= 1u << i; found = 1; }
        if (!found) die("unknown button '%s' (use: b y select start up down left right a x l r l2 r2 l3 r3 none)", t);
    }
    return m;
}

static void usage(void) {
    fprintf(stderr,
        "usage: core-harness CORE ROM [options]\n"
        "  -n N           frames to run (default 120)\n"
        "  -i F:P:BTNS    from frame F on, port P (0/1) holds RetroPad buttons BTNS (e.g. a+up, none)\n"
        "  -d F:PATH      after frame F, write the frame to PATH (.png or .ppm)\n"
        "  -p F:X,Y       after frame F, print pixel X,Y as RGB\n"
        "  -o KEY=VALUE   core option override (otherwise each option's first value, as the app does)\n"
        "  -c P:DEVICE    retro_set_controller_port_device(P, DEVICE) instead of RETRO_DEVICE_JOYPAD\n"
        "  -s PATH        SRAM file: loaded after retro_load_game if present, written at exit\n"
        "  -S DIR         system + save directory (default .)\n"
        "  -m             list the core's options\n"
        "  -q             only show core warnings/errors\n"
        "Frames are numbered from 1 (frame F = after the F-th retro_run).\n");
    exit(2);
}

#define SYM(name) do { *(void **)&name = dlsym(lib, #name); if (!name) die("missing symbol " #name); } while (0)

int main(int argc, char **argv) {
    if (argc < 3) usage();
    const char *core = argv[1], *rom = argv[2], *sram_path = NULL;
    int frames = 120;
    for (int i = 3; i < argc; i++) {
        const char *a = argv[i];
        if (i + 1 >= argc && strcmp(a, "-m") && strcmp(a, "-q")) usage();
        if (!strcmp(a, "-n")) frames = atoi(argv[++i]);
        else if (!strcmp(a, "-i")) {
            char b[256]; int f, p;
            if (sscanf(argv[++i], "%d:%d:%255s", &f, &p, b) != 3 || nev >= MAX_EV) usage();
            evs[nev++] = (struct ev){f, p, parse_buttons(b)};
        } else if (!strcmp(a, "-d")) {
            const char *s = argv[++i], *col = strchr(s, ':');
            if (!col || ndump >= 64) usage();
            dumps[ndump].frame = atoi(s); snprintf(dumps[ndump].path, sizeof dumps[0].path, "%s", col + 1); ndump++;
        } else if (!strcmp(a, "-p")) {
            if (nprobe >= 64 || sscanf(argv[++i], "%d:%d,%d", &probes[nprobe].frame, &probes[nprobe].x, &probes[nprobe].y) != 3) usage();
            nprobe++;
        } else if (!strcmp(a, "-o")) {
            const char *s = argv[++i], *eq = strchr(s, '=');
            if (!eq || novr >= MAX_OPT) usage();
            snprintf(ovrs[novr].key, sizeof ovrs[0].key, "%.*s", (int)(eq - s), s);
            snprintf(ovrs[novr].val, sizeof ovrs[0].val, "%s", eq + 1); novr++;
        } else if (!strcmp(a, "-c")) {
            int p, d; if (sscanf(argv[++i], "%d:%d", &p, &d) != 2 || p < 0 || p > 1) usage();
            devs[p] = d;
        } else if (!strcmp(a, "-s")) sram_path = argv[++i];
        else if (!strcmp(a, "-S")) { snprintf(sysdir, sizeof sysdir, "%s", argv[++i]); snprintf(savedir, sizeof savedir, "%s", sysdir); }
        else if (!strcmp(a, "-m")) show_opts = 1;
        else if (!strcmp(a, "-q")) quiet = 1;
        else usage();
    }

    void *lib = dlopen(core, RTLD_NOW | RTLD_LOCAL);
    if (!lib) die("dlopen: %s", dlerror());
    void (*retro_set_environment)(retro_environment_t); void (*retro_set_video_refresh)(retro_video_refresh_t);
    void (*retro_set_audio_sample)(retro_audio_sample_t); void (*retro_set_audio_sample_batch)(retro_audio_sample_batch_t);
    void (*retro_set_input_poll)(retro_input_poll_t); void (*retro_set_input_state)(retro_input_state_t);
    void (*retro_init)(void); void (*retro_deinit)(void); void (*retro_run)(void);
    void (*retro_get_system_info)(struct retro_system_info *); void (*retro_get_system_av_info)(struct retro_system_av_info *);
    bool (*retro_load_game)(const struct retro_game_info *); void (*retro_unload_game)(void);
    void (*retro_set_controller_port_device)(unsigned, unsigned);
    void *(*retro_get_memory_data)(unsigned); size_t (*retro_get_memory_size)(unsigned);
    size_t (*retro_serialize_size)(void);
    SYM(retro_set_environment); SYM(retro_set_video_refresh); SYM(retro_set_audio_sample); SYM(retro_set_audio_sample_batch);
    SYM(retro_set_input_poll); SYM(retro_set_input_state); SYM(retro_init); SYM(retro_deinit); SYM(retro_run);
    SYM(retro_get_system_info); SYM(retro_get_system_av_info); SYM(retro_load_game); SYM(retro_unload_game);
    SYM(retro_set_controller_port_device); SYM(retro_get_memory_data); SYM(retro_get_memory_size); SYM(retro_serialize_size);

    retro_set_environment(env);
    retro_set_video_refresh(video); retro_set_audio_sample(audio1); retro_set_audio_sample_batch(audio_batch);
    retro_set_input_poll(input_poll); retro_set_input_state(input_state);
    retro_init();

    struct retro_system_info si = {0};
    retro_get_system_info(&si);
    printf("core: %s %s\n  extensions: %s\n  need_fullpath: %s\n", si.library_name, si.library_version,
           si.valid_extensions ? si.valid_extensions : "", si.need_fullpath ? "true" : "false");
    if (show_opts) {
        printf("options (%d, default = first value):\n", nopt);
        for (int i = 0; i < nopt; i++) printf("  %s = \"%s\"   [%s]\n", opts[i].key, opts[i].def, opts[i].desc);
    }

    struct retro_game_info gi = {.path = rom};
    void *romdata = NULL;
    if (!si.need_fullpath) {
        FILE *f = fopen(rom, "rb"); if (!f) die("cannot open %s", rom);
        fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
        romdata = malloc(n); if (fread(romdata, 1, n, f) != (size_t)n) die("read %s", rom);
        fclose(f); gi.data = romdata; gi.size = n;
    }
    if (!retro_load_game(&gi)) die("retro_load_game failed");
    struct retro_system_av_info av = {0};
    retro_get_system_av_info(&av);
    printf("av: base %ux%u max %ux%u aspect %.4f fps %.4f sample_rate %.1f\n", av.geometry.base_width, av.geometry.base_height,
           av.geometry.max_width, av.geometry.max_height, av.geometry.aspect_ratio, av.timing.fps, av.timing.sample_rate);
    retro_set_controller_port_device(0, devs[0]);
    retro_set_controller_port_device(1, devs[1]);
    printf("ports: %d %d\nserialize_size: %zu\n", devs[0], devs[1], retro_serialize_size());

    size_t sram_n = retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
    uint8_t *sram_p = retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
    printf("sram before run: size %zu, data %s\n", sram_n, sram_p ? "yes" : "NULL");
    if (sram_path && sram_n && sram_p) {
        FILE *f = fopen(sram_path, "rb");
        if (f) { size_t got = fread(sram_p, 1, sram_n, f); fclose(f); printf("sram loaded %zu bytes from %s\n", got, sram_path); }
    }

    clock_t t0 = clock();
    for (int fr = 1; fr <= frames; fr++) {
        for (int i = 0; i < nev; i++) if (evs[i].frame == fr) pad[evs[i].port] = evs[i].mask;
        retro_run();
        for (int i = 0; i < ndump; i++) if (dumps[i].frame == fr) {
            write_frame(dumps[i].path);
            printf("frame %d: %ux%u hash %08x -> %s\n", fr, fw, fh, rgb ? frame_hash() : 0, dumps[i].path);
        }
        for (int i = 0; i < nprobe; i++) if (probes[i].frame == fr) {
            struct probe *p = &probes[i];
            if (rgb && (unsigned)p->x < fw && (unsigned)p->y < fh) {
                uint8_t *o = rgb + ((size_t)p->y * fw + p->x) * 3;
                printf("pixel f%d (%d,%d) = #%02x%02x%02x\n", fr, p->x, p->y, o[0], o[1], o[2]);
            } else printf("pixel f%d (%d,%d) = out of frame\n", fr, p->x, p->y);
        }
    }
    double secs = (double)(clock() - t0) / CLOCKS_PER_SEC;

    printf("run: %d frames, %ld with video, %ld dupes; sizes:", frames, video_frames, dupe_frames);
    for (int i = 0; i < nsizes; i++) printf(" %ux%u", sizes[i][0], sizes[i][1]);
    printf("\naudio: %ld stereo frames total, %.1f per frame -> %.0f Hz at %.3f fps, peak %d\n", audio_frames,
           (double)audio_frames / frames, (double)audio_frames / frames * av.timing.fps, av.timing.fps, audio_peak);
    printf("input queries: %d bitmask, %d single-button\n", bitmask_queries, single_queries);
    printf("host speed: %.0f frames/s (%.1fx realtime, this Mac, not the TV)\n", frames / (secs > 0 ? secs : 1e-9),
           frames / (secs > 0 ? secs : 1e-9) / av.timing.fps);

    sram_n = retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
    sram_p = retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
    printf("sram after run: size %zu", sram_n);
    if (sram_p && sram_n) {
        size_t nz = 0, nff = 0;
        for (size_t i = 0; i < sram_n; i++) { nz += sram_p[i] != 0; nff += sram_p[i] != 0xff; }
        printf(", %zu bytes != 0x00, %zu != 0xFF; first 16:", nz, nff);
        for (size_t i = 0; i < 16 && i < sram_n; i++) printf(" %02x", sram_p[i]);
        if (sram_path) {
            FILE *f = fopen(sram_path, "wb");
            if (f) { fwrite(sram_p, 1, sram_n, f); fclose(f); printf("\nsram written to %s", sram_path); }
        }
    }
    printf("\n");
    retro_unload_game();
    retro_deinit();
    free(romdata);
    return 0;
}
