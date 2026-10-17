// GLES 2.0 renderer: one RGB565 texture, one textured quad, optional single-pass fragment "shaders" (look presets).
// Kept to ES 2.0 on purpose (Mali-450 on the target TV). All gl*() functions run on the GL thread; the setters and
// the *Status/renderStats getters may be called from any thread.
#include <GLES2/gl2.h>
#include <android/log.h>

#include <atomic>
#include <chrono>
#include <cmath>
#include <mutex>
#include <string>

#include "host.h"
#include "layout.h"

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "RetroBoxGL", __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "RetroBoxGL", __VA_ARGS__)

namespace rb {
namespace {

enum Shader { kShNone = 0, kShSharp = 1, kShScanlines = 2, kShCrt = 3, kShLcd = 4, kShCount = 5 };

struct Prog {
    GLuint id = 0;
    GLint pos = -1, uv = -1, tex = -1, texSize = -1, scale = -1;
    bool tried = false;
};

Prog progs[kShCount];
GLuint texture = 0;
int surfaceW = 0, surfaceH = 0;
int texW = 0, texH = 0;
int frameW = 0, frameH = 0;
int filterApplied = -1;
std::atomic<int> modeReq{0}, filterReq{0}, shaderReq{0}, safeReq{0};
std::atomic<bool> timingReq{false};
std::atomic<double> sourceAspect{0.0};

std::mutex infoMutex;
std::string shaderErrors;   // one line per shader that failed to build on this GPU
std::string glInfoText;

// Render timing window (reset by renderStats()).
std::atomic<int64_t> tCount{0}, tSumUs{0}, tMaxUs{0}, tIntervalSumUs{0};
int64_t lastDrawEndUs = 0;

int64_t nowUs() {
    return std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

const char* kVs =
    "attribute vec2 aPos; attribute vec2 aUv; varying vec2 vUv;\n"
    "void main() { gl_Position = vec4(aPos, 0.0, 1.0); vUv = aUv; }\n";

// highp is not available in fragment shaders on Mali-400/450; fall back to mediump there (see ARCHITECTURE.md).
const char* kFsHead =
    "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n"
    "varying vec2 vUv; uniform sampler2D uTex; uniform vec2 uTexSize; uniform vec2 uScale;\n";

const char* fsBody(int s) {
    switch (s) {
        case kShSharp:  // "sharp bilinear": crisp texel centres, bilinear only across the edges; needs GL_LINEAR
            return "void main() {\n"
                   "  vec2 texel = vUv * uTexSize; vec2 fl = floor(texel);\n"
                   "  vec2 sc = max(floor(uScale), vec2(1.0));\n"
                   "  vec2 region = 0.5 - 0.5 / sc;\n"
                   "  vec2 cd = fract(texel) - 0.5;\n"
                   "  vec2 f = (cd - clamp(cd, -region, region)) * sc + 0.5;\n"
                   "  gl_FragColor = vec4(texture2D(uTex, (fl + f) / uTexSize).rgb, 1.0);\n"
                   "}\n";
        case kShScanlines:
            return "void main() {\n"
                   "  vec3 c = texture2D(uTex, vUv).rgb;\n"
                   "  float d = abs(fract(vUv.y * uTexSize.y) - 0.5) * 2.0;\n"
                   "  gl_FragColor = vec4(c * (1.0 - 0.45 * d * d) * 1.15, 1.0);\n"
                   "}\n";
        case kShCrt:  // scanlines + RGB stripe mask on output pixels + light vignette
            return "void main() {\n"
                   "  vec3 c = texture2D(uTex, vUv).rgb;\n"
                   "  float d = abs(fract(vUv.y * uTexSize.y) - 0.5) * 2.0;\n"
                   "  float m = mod(floor(gl_FragCoord.x), 3.0);\n"
                   "  vec3 mask = vec3(m < 0.5 ? 1.0 : 0.72, (m > 0.5 && m < 1.5) ? 1.0 : 0.72, m > 1.5 ? 1.0 : 0.72);\n"
                   "  vec2 q = vUv - 0.5;\n"
                   "  float vig = 1.0 - 0.5 * dot(q, q);\n"
                   "  gl_FragColor = vec4(c * mask * (1.0 - 0.38 * d * d) * vig * 1.35, 1.0);\n"
                   "}\n";
        case kShLcd:  // dark gaps between source pixels, like a handheld LCD
            return "void main() {\n"
                   "  vec3 c = texture2D(uTex, vUv).rgb;\n"
                   "  vec2 f = fract(vUv * uTexSize); vec2 e = min(f, 1.0 - f);\n"
                   "  float g = smoothstep(0.0, 0.22, min(e.x, e.y));\n"
                   "  gl_FragColor = vec4(c * mix(0.62, 1.0, g) * 1.1, 1.0);\n"
                   "}\n";
        default:
            return "void main() { gl_FragColor = vec4(texture2D(uTex, vUv).rgb, 1.0); }\n";
    }
}

const char* shaderName(int s) {
    static const char* n[] = {"none", "sharp bilinear", "scanlines", "CRT", "LCD grid"};
    return s >= 0 && s < kShCount ? n[s] : "?";
}

void noteShaderError(int s, const std::string& what) {
    std::lock_guard<std::mutex> l(infoMutex);
    shaderErrors += std::string("shader '") + shaderName(s) + "' unavailable: " + what + "\n";
    LOGE("shader '%s' unavailable: %s", shaderName(s), what.c_str());
}

GLuint compile(GLenum type, const std::string& src, std::string* err) {
    GLuint s = glCreateShader(type);
    const char* c = src.c_str();
    glShaderSource(s, 1, &c, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[512] = {0};
        glGetShaderInfoLog(s, sizeof log, nullptr, log);
        *err = log;
        glDeleteShader(s);
        return 0;
    }
    return s;
}

// Builds shader [s] once; a failure is remembered (and reported) so the plain path is used from then on.
Prog& program(int s) {
    Prog& p = progs[s];
    if (p.tried) return p;
    p.tried = true;
    std::string err;
    GLuint vs = compile(GL_VERTEX_SHADER, kVs, &err);
    GLuint fs = vs ? compile(GL_FRAGMENT_SHADER, std::string(kFsHead) + fsBody(s), &err) : 0;
    if (!vs || !fs) { if (vs) glDeleteShader(vs); noteShaderError(s, "compile failed: " + err); return p; }
    GLuint id = glCreateProgram();
    glAttachShader(id, vs);
    glAttachShader(id, fs);
    glLinkProgram(id);
    GLint ok = 0;
    glGetProgramiv(id, GL_LINK_STATUS, &ok);
    glDeleteShader(vs);
    glDeleteShader(fs);
    if (!ok) { glDeleteProgram(id); noteShaderError(s, "link failed"); return p; }
    p.id = id;
    p.pos = glGetAttribLocation(id, "aPos");
    p.uv = glGetAttribLocation(id, "aUv");
    p.tex = glGetUniformLocation(id, "uTex");
    p.texSize = glGetUniformLocation(id, "uTexSize");
    p.scale = glGetUniformLocation(id, "uScale");
    return p;
}

}  // namespace

void setDisplay(int mode, int filter, int shader, int safePermille) {
    modeReq = mode; filterReq = filter; shaderReq = shader; safeReq = safePermille;
}
void setSourceAspect(double aspect) { sourceAspect = aspect; }
void setRenderTiming(bool on) { timingReq = on; if (on) lastDrawEndUs = 0; }

std::string shaderStatus() {
    std::lock_guard<std::mutex> l(infoMutex);
    return shaderErrors;
}
std::string glInfo() {
    std::lock_guard<std::mutex> l(infoMutex);
    return glInfoText;
}

RenderStats renderStats() {
    RenderStats r;
    r.count = tCount.exchange(0);
    int64_t sum = tSumUs.exchange(0), isum = tIntervalSumUs.exchange(0);
    r.maxUs = tMaxUs.exchange(0);
    r.avgUs = r.count ? sum / r.count : 0;
    r.avgIntervalUs = r.count ? isum / r.count : 0;
    return r;
}

void glInit() {
    for (Prog& p : progs) p = Prog();
    texture = 0; texW = texH = frameW = frameH = 0; filterApplied = -1; lastDrawEndUs = 0;
    {
        std::lock_guard<std::mutex> l(infoMutex);
        shaderErrors.clear();
        GLint range[2] = {0, 0}, prec = 0;
        glGetShaderPrecisionFormat(GL_FRAGMENT_SHADER, GL_HIGH_FLOAT, range, &prec);
        auto s = [](GLenum e) { const GLubyte* v = glGetString(e); return v ? std::string(reinterpret_cast<const char*>(v)) : std::string("?"); };
        glInfoText = s(GL_RENDERER) + " | " + s(GL_VERSION) + " | fragment highp: " + (prec > 0 ? "yes" : "NO (mediump only)");
        LOGI("%s", glInfoText.c_str());
    }
    if (!program(kShNone).id) { LOGE("base program failed; nothing will be drawn"); return; }
    glGenTextures(1, &texture);
    glBindTexture(GL_TEXTURE_2D, texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_BLEND);
    glClearColor(0, 0, 0, 1);
}

void glResize(int w, int h) { surfaceW = w; surfaceH = h; }

void glRelease() {
    if (texture) glDeleteTextures(1, &texture);
    for (Prog& p : progs) { if (p.id) glDeleteProgram(p.id); p = Prog(); }
    texture = 0;
}

void glDraw() {
    glClear(GL_COLOR_BUFFER_BIT);
    if (!progs[kShNone].id || surfaceW <= 0 || surfaceH <= 0) return;

    const uint16_t* px = nullptr;
    int w = 0, h = 0;
    bool isNew = false;
    if (!acquireFrame(&px, &w, &h, &isNew)) return;
    const bool timing = timingReq.load();
    const int64_t t0 = timing ? nowUs() : 0;

    // A shader that failed to build (or an unknown id) silently falls back to the plain path; shaderStatus() says why.
    int shader = shaderReq.load();
    if (shader < 0 || shader >= kShCount) shader = kShNone;
    Prog* p = &program(shader);
    if (!p->id) { shader = kShNone; p = &program(kShNone); }

    glBindTexture(GL_TEXTURE_2D, texture);
    int filter = shader == kShSharp ? 1 : filterReq.load();   // sharp bilinear only makes sense with linear sampling
    if (filter != filterApplied) {
        GLint f = filter ? GL_LINEAR : GL_NEAREST;
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, f);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, f);
        filterApplied = filter;
    }
    if (w != texW || h != texH) {
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, w, h, 0, GL_RGB, GL_UNSIGNED_SHORT_5_6_5, px);
        texW = w; texH = h;
    } else if (isNew) {
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RGB, GL_UNSIGNED_SHORT_5_6_5, px);
    }
    frameW = w; frameH = h;

    const Rect r = computeLayout(modeReq.load(), surfaceW, surfaceH, w, h, sourceAspect.load(), safeReq.load());
    glViewport(r.x, r.y, r.w, r.h);

    static const GLfloat quad[] = {  // x, y, u, v  (triangle strip; v=0 is the first row = top of picture)
        -1.f, 1.f, 0.f, 0.f,   -1.f, -1.f, 0.f, 1.f,   1.f, 1.f, 1.f, 0.f,   1.f, -1.f, 1.f, 1.f,
    };
    glUseProgram(p->id);
    glUniform1i(p->tex, 0);
    if (p->texSize >= 0) glUniform2f(p->texSize, float(w), float(h));
    if (p->scale >= 0) glUniform2f(p->scale, float(scaleFactor(r.w, w)), float(scaleFactor(r.h, h)));
    glEnableVertexAttribArray(p->pos);
    glEnableVertexAttribArray(p->uv);
    glVertexAttribPointer(p->pos, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), quad);
    glVertexAttribPointer(p->uv, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), quad + 2);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisableVertexAttribArray(p->pos);
    glDisableVertexAttribArray(p->uv);
    glViewport(0, 0, surfaceW, surfaceH);

    if (timing) {
        glFinish();   // only while the timing overlay is on: makes the number include the GPU work, at some cost to speed
        const int64_t t1 = nowUs();
        const int64_t dt = t1 - t0;
        tCount++; tSumUs += dt;
        if (dt > tMaxUs.load()) tMaxUs = dt;
        if (lastDrawEndUs) tIntervalSumUs += t1 - lastDrawEndUs;
        lastDrawEndUs = t1;
    }
}

}  // namespace rb
