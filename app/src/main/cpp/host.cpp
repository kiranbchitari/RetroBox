#include "host.h"

#include <android/log.h>
#include <dlfcn.h>
#include <time.h>

#include <atomic>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <map>
#include <mutex>

#include "third_party/libretro/libretro.h"

#define LOG_TAG "RetroBoxHost"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace rb {
namespace {

struct Api {
    void (*set_environment)(retro_environment_t) = nullptr;
    void (*set_video_refresh)(retro_video_refresh_t) = nullptr;
    void (*set_audio_sample)(retro_audio_sample_t) = nullptr;
    void (*set_audio_sample_batch)(retro_audio_sample_batch_t) = nullptr;
    void (*set_input_poll)(retro_input_poll_t) = nullptr;
    void (*set_input_state)(retro_input_state_t) = nullptr;
    void (*init)() = nullptr;
    void (*deinit)() = nullptr;
    unsigned (*api_version)() = nullptr;
    void (*get_system_info)(retro_system_info*) = nullptr;
    void (*get_system_av_info)(retro_system_av_info*) = nullptr;
    void (*set_controller_port_device)(unsigned, unsigned) = nullptr;
    void (*reset)() = nullptr;
    void (*run)() = nullptr;
    size_t (*serialize_size)() = nullptr;
    bool (*serialize)(void*, size_t) = nullptr;
    bool (*unserialize)(const void*, size_t) = nullptr;
    bool (*load_game)(const retro_game_info*) = nullptr;
    void (*unload_game)() = nullptr;
    void* (*get_memory_data)(unsigned) = nullptr;
    size_t (*get_memory_size)(unsigned) = nullptr;
};

struct Frame {
    std::vector<uint16_t> px;  // RGB565, tightly packed
    int w = 0, h = 0;
};

struct State {
    void* lib = nullptr;
    Api api;
    bool gameLoaded = false;
    CoreInfo info;
    AvInfo av;
    std::string systemDir, saveDir, corePath;
    std::vector<uint8_t> romData;
    retro_pixel_format pixelFormat = RETRO_PIXEL_FORMAT_0RGB1555;

    // video hand-off
    std::mutex frameMutex;
    Frame frames[2];
    int back = 0;  // emu thread writes here under the lock; swapped with front by the GL thread
    bool dirty = false, haveFrame = false;
    bool frameProduced = false;

    // audio (same thread as retro_run, no locking)
    std::vector<int16_t> audio;

    // input
    std::atomic<uint32_t> padMask[2]{0, 0};    // buttons currently down (written by the UI thread)
    std::atomic<uint32_t> padLatch[2]{0, 0};   // buttons that went down since the last frame, even if already released again
    uint32_t padFrame[2]{0, 0};                // what the core sees this frame (emulation thread only)
    std::atomic<int> axes[2][4]{{0, 0, 0, 0}, {0, 0, 0, 0}};

    // core options (legacy SET_VARIABLES interface)
    std::mutex optMutex;
    std::map<std::string, std::string> optDefaults, optOverrides;
    bool optDirty = false;

    std::mutex msgMutex;
    std::string messages;

    Stats stats;
    int64_t runUsSum = 0;
};

State g;

int64_t nowUs() {
    timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return int64_t(ts.tv_sec) * 1000000 + ts.tv_nsec / 1000;
}

void addMessage(const std::string& m) {
    std::lock_guard<std::mutex> l(g.msgMutex);
    if (g.messages.size() < 8192) g.messages += m + "\n";
}

// ---- libretro callbacks ----

void logCb(retro_log_level level, const char* fmt, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
    int prio = level == RETRO_LOG_DEBUG ? ANDROID_LOG_DEBUG : level == RETRO_LOG_INFO ? ANDROID_LOG_INFO
             : level == RETRO_LOG_WARN ? ANDROID_LOG_WARN : ANDROID_LOG_ERROR;
    __android_log_print(prio, "RetroBoxCore", "%s", buf);
    if (level >= RETRO_LOG_WARN) {
        size_t n = strlen(buf);
        while (n && (buf[n - 1] == '\n' || buf[n - 1] == '\r')) buf[--n] = 0;
        addMessage(std::string(level == RETRO_LOG_WARN ? "W: " : "E: ") + buf);
    }
}

void parseVariables(const retro_variable* vars) {
    std::lock_guard<std::mutex> l(g.optMutex);
    for (; vars && vars->key; ++vars) {
        std::string v = vars->value ? vars->value : "";
        auto semi = v.find("; ");
        if (semi == std::string::npos) continue;
        std::string rest = v.substr(semi + 2);
        g.optDefaults[vars->key] = rest.substr(0, rest.find('|'));
    }
}

bool environment(unsigned cmd, void* data) {
    const unsigned c = cmd & ~(RETRO_ENVIRONMENT_EXPERIMENTAL | RETRO_ENVIRONMENT_PRIVATE);
    switch (c) {
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
            auto f = *static_cast<const retro_pixel_format*>(data);
            if (f == RETRO_PIXEL_FORMAT_RGB565 || f == RETRO_PIXEL_FORMAT_XRGB8888 || f == RETRO_PIXEL_FORMAT_0RGB1555) {
                g.pixelFormat = f;
                g.stats.pixelFormat = f;
                return true;
            }
            return false;
        }
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
        case RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY:
            *static_cast<const char**>(data) = g.systemDir.c_str();
            return true;
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
            *static_cast<const char**>(data) = g.saveDir.c_str();
            return true;
        case RETRO_ENVIRONMENT_GET_LIBRETRO_PATH:
            *static_cast<const char**>(data) = g.corePath.c_str();
            return true;
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
            static_cast<retro_log_callback*>(data)->log = logCb;
            return true;
        case RETRO_ENVIRONMENT_GET_CAN_DUPE:
            *static_cast<bool*>(data) = true;
            return true;
        case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
            *static_cast<unsigned*>(data) = 0;  // legacy SET_VARIABLES only
            return true;
        case RETRO_ENVIRONMENT_SET_VARIABLES:
            parseVariables(static_cast<const retro_variable*>(data));
            return true;
        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            auto* v = static_cast<retro_variable*>(data);
            std::lock_guard<std::mutex> l(g.optMutex);
            auto it = g.optOverrides.find(v->key);
            if (it != g.optOverrides.end()) { v->value = it->second.c_str(); return true; }
            auto d = g.optDefaults.find(v->key);
            if (d != g.optDefaults.end()) { v->value = d->second.c_str(); return true; }
            v->value = nullptr;
            return false;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE: {
            std::lock_guard<std::mutex> l(g.optMutex);
            *static_cast<bool*>(data) = g.optDirty;
            g.optDirty = false;
            return true;
        }
        case RETRO_ENVIRONMENT_SET_GEOMETRY: {
            auto* geo = static_cast<const retro_game_geometry*>(data);
            g.av.baseW = geo->base_width; g.av.baseH = geo->base_height;
            g.av.aspect = geo->aspect_ratio > 0 ? geo->aspect_ratio : double(geo->base_width) / geo->base_height;
            setSourceAspect(g.av.aspect);
            return true;
        }
        case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO: {
            auto* a = static_cast<const retro_system_av_info*>(data);
            g.av.baseW = a->geometry.base_width; g.av.baseH = a->geometry.base_height;
            g.av.maxW = a->geometry.max_width; g.av.maxH = a->geometry.max_height;
            g.av.aspect = a->geometry.aspect_ratio > 0 ? a->geometry.aspect_ratio : double(g.av.baseW) / g.av.baseH;
            g.av.fps = a->timing.fps; g.av.sampleRate = a->timing.sample_rate;
            setSourceAspect(g.av.aspect);
            return true;
        }
        case RETRO_ENVIRONMENT_SET_MESSAGE: {
            auto* m = static_cast<const retro_message*>(data);
            if (m && m->msg) addMessage(std::string("M: ") + m->msg);
            return true;
        }
        case RETRO_ENVIRONMENT_GET_LANGUAGE:
            *static_cast<unsigned*>(data) = RETRO_LANGUAGE_ENGLISH;
            return true;
        case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
            return true;
        case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL:
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
        case RETRO_ENVIRONMENT_SET_KEYBOARD_CALLBACK:
        case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
        case RETRO_ENVIRONMENT_SET_MEMORY_MAPS:
        case RETRO_ENVIRONMENT_SET_SERIALIZATION_QUIRKS:
        case RETRO_ENVIRONMENT_SET_MINIMUM_AUDIO_LATENCY:
        case RETRO_ENVIRONMENT_SET_CONTENT_INFO_OVERRIDE:
        case RETRO_ENVIRONMENT_SET_SUBSYSTEM_INFO:
            return true;
        default:
            return false;  // unsupported: hw render, VFS, rumble, sensors, camera, disk control, ...
    }
}

void videoRefresh(const void* data, unsigned width, unsigned height, size_t pitch) {
    if (!data) return;  // frame dupe: keep the previous picture
    std::lock_guard<std::mutex> l(g.frameMutex);
    Frame& f = g.frames[g.back];
    f.w = int(width); f.h = int(height);
    g.stats.frameW = f.w; g.stats.frameH = f.h;
    f.px.resize(size_t(width) * height);
    uint16_t* dst = f.px.data();
    switch (g.pixelFormat) {
        case RETRO_PIXEL_FORMAT_RGB565:
            for (unsigned y = 0; y < height; ++y)
                memcpy(dst + size_t(y) * width, static_cast<const uint8_t*>(data) + y * pitch, width * 2);
            break;
        case RETRO_PIXEL_FORMAT_XRGB8888:
            for (unsigned y = 0; y < height; ++y) {
                auto* src = reinterpret_cast<const uint32_t*>(static_cast<const uint8_t*>(data) + y * pitch);
                uint16_t* d = dst + size_t(y) * width;
                for (unsigned x = 0; x < width; ++x) {
                    uint32_t p = src[x];
                    d[x] = uint16_t(((p >> 8) & 0xF800) | ((p >> 5) & 0x07E0) | ((p >> 3) & 0x001F));
                }
            }
            break;
        case RETRO_PIXEL_FORMAT_0RGB1555:
            for (unsigned y = 0; y < height; ++y) {
                auto* src = reinterpret_cast<const uint16_t*>(static_cast<const uint8_t*>(data) + y * pitch);
                uint16_t* d = dst + size_t(y) * width;
                for (unsigned x = 0; x < width; ++x) {
                    uint16_t p = src[x];
                    uint16_t gch = (p >> 5) & 31;
                    d[x] = uint16_t((((p >> 10) & 31) << 11) | (((gch << 1) | (gch >> 4)) << 5) | (p & 31));
                }
            }
            break;
        default: return;
    }
    g.dirty = true;
    g.frameProduced = true;
}

void audioSample(int16_t l, int16_t r) {
    g.audio.push_back(l);
    g.audio.push_back(r);
    g.stats.audioFrames++;
}

size_t audioBatch(const int16_t* data, size_t frames) {
    // Bound memory if Kotlin stops draining (e.g. while paused): keep at most ~1 s.
    if (g.audio.size() < 2 * 96000) g.audio.insert(g.audio.end(), data, data + frames * 2);
    g.stats.audioFrames += frames;
    return frames;
}

void inputPoll() {}

int16_t inputState(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (port > 1) return 0;
    device &= RETRO_DEVICE_MASK;
    if (device == RETRO_DEVICE_JOYPAD) {
        uint32_t m = g.padFrame[port];
        if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return int16_t(m & 0xFFFF);
        return id < 16 ? int16_t((m >> id) & 1) : 0;
    }
    if (device == RETRO_DEVICE_ANALOG && index <= RETRO_DEVICE_INDEX_ANALOG_RIGHT && id <= RETRO_DEVICE_ID_ANALOG_Y)
        return int16_t(g.axes[port][index * 2 + id].load(std::memory_order_relaxed));
    return 0;
}

template <typename T>
bool sym(T& out, const char* name) {
    out = reinterpret_cast<T>(dlsym(g.lib, name));
    return out != nullptr;
}

}  // namespace

std::string loadCore(const std::string& path, const std::string& systemDir, const std::string& saveDir) {
    if (g.lib) unloadCore();
    g.systemDir = systemDir; g.saveDir = saveDir; g.corePath = path;
    g.optDefaults.clear(); g.optOverrides.clear();
    g.pixelFormat = RETRO_PIXEL_FORMAT_0RGB1555;
    g.lib = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!g.lib) return std::string("Cannot load core library: ") + dlerror();

    Api& a = g.api;
    bool ok = sym(a.set_environment, "retro_set_environment") && sym(a.set_video_refresh, "retro_set_video_refresh") &&
              sym(a.set_audio_sample, "retro_set_audio_sample") && sym(a.set_audio_sample_batch, "retro_set_audio_sample_batch") &&
              sym(a.set_input_poll, "retro_set_input_poll") && sym(a.set_input_state, "retro_set_input_state") &&
              sym(a.init, "retro_init") && sym(a.deinit, "retro_deinit") && sym(a.api_version, "retro_api_version") &&
              sym(a.get_system_info, "retro_get_system_info") && sym(a.get_system_av_info, "retro_get_system_av_info") &&
              sym(a.set_controller_port_device, "retro_set_controller_port_device") && sym(a.reset, "retro_reset") &&
              sym(a.run, "retro_run") && sym(a.serialize_size, "retro_serialize_size") && sym(a.serialize, "retro_serialize") &&
              sym(a.unserialize, "retro_unserialize") && sym(a.load_game, "retro_load_game") && sym(a.unload_game, "retro_unload_game") &&
              sym(a.get_memory_data, "retro_get_memory_data") && sym(a.get_memory_size, "retro_get_memory_size");
    if (!ok) { dlclose(g.lib); g.lib = nullptr; return "Core library is missing required libretro symbols"; }
    if (a.api_version() != RETRO_API_VERSION) { dlclose(g.lib); g.lib = nullptr; return "Unsupported libretro API version"; }

    retro_system_info si{};
    a.get_system_info(&si);
    g.info.name = si.library_name ? si.library_name : "?";
    g.info.version = si.library_version ? si.library_version : "?";
    g.info.extensions = si.valid_extensions ? si.valid_extensions : "";
    g.info.needFullpath = si.need_fullpath;

    a.set_environment(environment);
    a.init();
    a.set_video_refresh(videoRefresh);
    a.set_audio_sample(audioSample);
    a.set_audio_sample_batch(audioBatch);
    a.set_input_poll(inputPoll);
    a.set_input_state(inputState);
    LOGI("Loaded core %s %s (fullpath=%d, ext=%s)", g.info.name.c_str(), g.info.version.c_str(), g.info.needFullpath, g.info.extensions.c_str());
    return "";
}

std::string loadGame(const std::string& romPath) {
    if (!g.lib) return "No core loaded";
    if (g.gameLoaded) unloadGame();
    retro_game_info gi{};
    gi.path = romPath.c_str();
    g.romData.clear();
    if (!g.info.needFullpath) {
        std::ifstream in(romPath, std::ios::binary | std::ios::ate);
        if (!in) return "Cannot open ROM file";
        auto size = in.tellg();
        if (size <= 0) return "ROM file is empty";
        g.romData.resize(size_t(size));
        in.seekg(0);
        if (!in.read(reinterpret_cast<char*>(g.romData.data()), size)) return "Cannot read ROM file";
        gi.data = g.romData.data();
        gi.size = g.romData.size();
    }
    {
        std::lock_guard<std::mutex> l(g.frameMutex);
        g.frames[0] = Frame(); g.frames[1] = Frame(); g.dirty = false; g.haveFrame = false;
    }
    g.audio.clear();
    resetStats();
    if (!g.api.load_game(&gi)) {
        g.romData.clear();
        std::string msgs = drainMessages();
        return "The core rejected this ROM (unsupported or corrupt)" + (msgs.empty() ? std::string() : ": " + msgs);
    }
    retro_system_av_info avi{};
    g.api.get_system_av_info(&avi);
    g.av.baseW = avi.geometry.base_width; g.av.baseH = avi.geometry.base_height;
    g.av.maxW = avi.geometry.max_width; g.av.maxH = avi.geometry.max_height;
    g.av.aspect = avi.geometry.aspect_ratio > 0 ? avi.geometry.aspect_ratio : double(g.av.baseW) / (g.av.baseH ? g.av.baseH : 1);
    g.av.fps = avi.timing.fps > 1 ? avi.timing.fps : 60.0;
    g.av.sampleRate = avi.timing.sample_rate > 1000 ? avi.timing.sample_rate : 44100.0;
    setSourceAspect(g.av.aspect);
    g.api.set_controller_port_device(0, RETRO_DEVICE_JOYPAD);
    g.api.set_controller_port_device(1, RETRO_DEVICE_JOYPAD);   // cores with one port ignore this; NES/SNES use it for Player 2
    g.gameLoaded = true;
    LOGI("Game loaded: %ux%u aspect %.3f fps %.3f audio %.0f Hz", g.av.baseW, g.av.baseH, g.av.aspect, g.av.fps, g.av.sampleRate);
    return "";
}

void unloadGame() {
    if (!g.lib || !g.gameLoaded) return;
    g.api.unload_game();
    g.gameLoaded = false;
    g.romData.clear();
    g.romData.shrink_to_fit();
}

void unloadCore() {
    if (!g.lib) return;
    unloadGame();
    g.api.deinit();
    dlclose(g.lib);
    g.lib = nullptr;
    g.api = Api();
    {
        std::lock_guard<std::mutex> l(g.frameMutex);
        g.frames[0] = Frame(); g.frames[1] = Frame(); g.dirty = false; g.haveFrame = false;
    }
    g.audio.clear(); g.audio.shrink_to_fit();
}

bool coreLoaded() { return g.lib != nullptr; }
bool gameLoaded() { return g.gameLoaded; }
const CoreInfo& coreInfo() { return g.info; }
AvInfo avInfo() { return g.av; }

bool runFrame() {
    if (!g.gameLoaded) return false;
    g.frameProduced = false;
    // Snapshot the pad once per frame. A tap shorter than a frame (adb/remote key events, a slow frame) is still
    // seen for one frame because setInput() latches every press until it has been delivered.
    for (int p = 0; p < 2; ++p) g.padFrame[p] = g.padMask[p].load(std::memory_order_relaxed) | g.padLatch[p].exchange(0);
    int64_t t0 = nowUs();
    g.api.run();
    int64_t dt = nowUs() - t0;
    g.stats.frames++;
    g.runUsSum += dt;
    g.stats.avgRunUs = g.runUsSum / g.stats.frames;
    if (dt > g.stats.maxRunUs) g.stats.maxRunUs = dt;
    return g.frameProduced;
}

void reset() { if (g.gameLoaded) g.api.reset(); }

int readAudio(int16_t* out, int maxFrames) {
    int frames = int(g.audio.size() / 2);
    if (frames > maxFrames) frames = maxFrames;
    memcpy(out, g.audio.data(), size_t(frames) * 4);
    g.audio.erase(g.audio.begin(), g.audio.begin() + frames * 2);
    return frames;
}

void setInput(int port, uint32_t mask, int lx, int ly, int rx, int ry) {
    if (port < 0 || port > 1) return;
    g.padMask[port].store(mask, std::memory_order_relaxed);
    g.padLatch[port].fetch_or(mask, std::memory_order_relaxed);
    g.axes[port][0].store(lx); g.axes[port][1].store(ly);
    g.axes[port][2].store(rx); g.axes[port][3].store(ry);
}

void setOption(const std::string& key, const std::string& value) {
    std::lock_guard<std::mutex> l(g.optMutex);
    g.optOverrides[key] = value;
    g.optDirty = true;
}

std::vector<uint8_t> saveState() {
    if (!g.gameLoaded) return {};
    size_t n = g.api.serialize_size();
    if (!n) return {};
    std::vector<uint8_t> buf(n);
    if (!g.api.serialize(buf.data(), n)) return {};
    return buf;
}

bool loadState(const uint8_t* data, size_t size) {
    if (!g.gameLoaded || !size) return false;
    size_t expect = g.api.serialize_size();
    if (expect && size != expect) return false;  // wrong core/version/ROM: refuse rather than corrupt emulation
    return g.api.unserialize(data, size);
}

std::vector<uint8_t> saveRam() {
    if (!g.gameLoaded) return {};
    size_t n = g.api.get_memory_size(RETRO_MEMORY_SAVE_RAM);
    void* p = g.api.get_memory_data(RETRO_MEMORY_SAVE_RAM);
    if (!n || !p) return {};
    return std::vector<uint8_t>(static_cast<uint8_t*>(p), static_cast<uint8_t*>(p) + n);
}

bool loadSaveRam(const uint8_t* data, size_t size) {
    if (!g.gameLoaded) return false;
    size_t n = g.api.get_memory_size(RETRO_MEMORY_SAVE_RAM);
    void* p = g.api.get_memory_data(RETRO_MEMORY_SAVE_RAM);
    if (!n || !p || size == 0) return false;
    memcpy(p, data, size < n ? size : n);
    return true;
}

std::string drainMessages() {
    std::lock_guard<std::mutex> l(g.msgMutex);
    std::string m;
    m.swap(g.messages);
    return m;
}

Stats stats() { return g.stats; }

void resetStats() {
    int64_t pf = g.stats.pixelFormat;
    g.stats = Stats();
    g.stats.pixelFormat = pf;
    g.runUsSum = 0;
}

bool snapshotFrame(std::vector<uint32_t>& out, int* w, int* h) {
    std::lock_guard<std::mutex> l(g.frameMutex);
    // The emulation thread writes frames[back]; if that is a not-yet-consumed new frame it is the newest, else the front buffer is.
    const Frame& f = g.dirty ? g.frames[g.back] : g.frames[g.back ^ 1];
    if (!(g.dirty || g.haveFrame) || f.w <= 0 || f.h <= 0 || f.px.size() < size_t(f.w) * f.h) return false;
    out.resize(size_t(f.w) * f.h);
    for (size_t i = 0; i < out.size(); ++i) {
        uint32_t p = f.px[i];
        uint32_t r = (p >> 11) & 31, gg = (p >> 5) & 63, b = p & 31;
        out[i] = 0xFF000000u | ((r << 3 | r >> 2) << 16) | ((gg << 2 | gg >> 4) << 8) | (b << 3 | b >> 2);
    }
    *w = f.w; *h = f.h;
    return true;
}

bool acquireFrame(const uint16_t** px, int* w, int* h, bool* isNew) {
    std::lock_guard<std::mutex> l(g.frameMutex);
    *isNew = false;
    if (g.dirty) {
        g.back ^= 1;  // the frame just written becomes the front buffer
        g.dirty = false;
        g.haveFrame = true;
        *isNew = true;
    }
    if (!g.haveFrame) return false;
    // After a swap the front buffer is frames[back ^ 1]; the emu thread only touches frames[back].
    const Frame& f = g.frames[g.back ^ 1];
    *px = f.px.data(); *w = f.w; *h = f.h;
    return f.w > 0 && f.h > 0;
}

}  // namespace rb
