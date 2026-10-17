// RetroBox libretro host: loads one libretro core at a time, drives it and exposes frames/audio/state.
// Threading: everything except renderer.cpp's gl*() runs on the single emulation thread;
// frame hand-off to the GL thread goes through acquireFrame() (mutex-protected double buffer).
#pragma once
#include <cstdint>
#include <string>
#include <vector>

namespace rb {

struct AvInfo {
    unsigned baseW = 0, baseH = 0, maxW = 0, maxH = 0;
    double aspect = 0.0, fps = 60.0, sampleRate = 44100.0;
};

struct CoreInfo {
    std::string name, version, extensions;
    bool needFullpath = false;
};

// Return "" on success, otherwise a human-readable error.
std::string loadCore(const std::string& path, const std::string& systemDir, const std::string& saveDir);
std::string loadGame(const std::string& romPath);
void unloadGame();
void unloadCore();
bool coreLoaded();
bool gameLoaded();

const CoreInfo& coreInfo();
AvInfo avInfo();

// Runs one frame. Returns true when the core produced a new video frame.
bool runFrame();
void reset();

// Copies up to maxFrames stereo frames into out (interleaved int16); returns frames copied.
int readAudio(int16_t* out, int maxFrames);

void setInput(int port, uint32_t buttonMask, int lx, int ly, int rx, int ry);
void setOption(const std::string& key, const std::string& value);

std::vector<uint8_t> saveState();
bool loadState(const uint8_t* data, size_t size);
std::vector<uint8_t> saveRam();
bool loadSaveRam(const uint8_t* data, size_t size);

// Accumulated core log lines at WARN or above plus SET_MESSAGE text; cleared by the call.
std::string drainMessages();

struct Stats {
    int64_t frames = 0, avgRunUs = 0, maxRunUs = 0, audioFrames = 0, pixelFormat = 0;
    int64_t frameW = 0, frameH = 0;  // size of the most recent video frame the core delivered (changes at runtime, e.g. SNES hi-res)
};
Stats stats();
void resetStats();

// Copy of the newest frame as ARGB8888 (alpha 255) into out; false if no frame yet. Safe from any thread, does not disturb the GL hand-off.
bool snapshotFrame(std::vector<uint32_t>& out, int* w, int* h);

// --- video hand-off (GL thread) ---
// If a new frame is pending, swaps buffers. Returns true if a frame exists (new or old).
bool acquireFrame(const uint16_t** px, int* w, int* h, bool* isNew);

// --- renderer (renderer.cpp, GL thread only) ---
void glInit();
void glResize(int w, int h);
void glDraw();
void glRelease();
// mode: 0 original aspect, 1 4:3, 2 stretch, 3 integer scale, 4 16:9; filter: 0 nearest, 1 bilinear;
// shader: 0 none, 1 sharp bilinear, 2 scanlines, 3 CRT, 4 LCD grid; safePermille: margin on every side in 1/1000 of the screen.
void setDisplay(int mode, int filter, int shader, int safePermille);
// Aspect ratio the renderer uses for "original" (from core); called when AV info changes.
void setSourceAspect(double aspect);
// Frame timing around glDraw (GL thread). Needs glFinish to include GPU work, so it is only measured while switched on.
void setRenderTiming(bool on);
struct RenderStats { int64_t count = 0, avgUs = 0, maxUs = 0, avgIntervalUs = 0; };
RenderStats renderStats();          // window since the previous call, then resets
// Shaders that could not be built on this GPU (one line each, "" = none failed) and a one-line GL summary.
std::string shaderStatus();
std::string glInfo();

}  // namespace rb
