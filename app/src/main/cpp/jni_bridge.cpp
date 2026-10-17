#include <jni.h>

#include <string>
#include <vector>

#include "host.h"

namespace {

std::string toStd(JNIEnv* env, jstring s) {
    if (!s) return "";
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r(c);
    env->ReleaseStringUTFChars(s, c);
    return r;
}

// Returns null on success, otherwise a Java string with the error.
jstring errOrNull(JNIEnv* env, const std::string& e) { return e.empty() ? nullptr : env->NewStringUTF(e.c_str()); }

jbyteArray toJava(JNIEnv* env, const std::vector<uint8_t>& v) {
    if (v.empty()) return nullptr;
    jbyteArray a = env->NewByteArray(jsize(v.size()));
    env->SetByteArrayRegion(a, 0, jsize(v.size()), reinterpret_cast<const jbyte*>(v.data()));
    return a;
}

}  // namespace

#define JNI(ret, name) extern "C" JNIEXPORT ret JNICALL Java_com_retrobox_tv_emulator_NativeHost_##name

JNI(jstring, loadCore)(JNIEnv* env, jobject, jstring path, jstring systemDir, jstring saveDir) {
    return errOrNull(env, rb::loadCore(toStd(env, path), toStd(env, systemDir), toStd(env, saveDir)));
}
JNI(jstring, loadGame)(JNIEnv* env, jobject, jstring path) { return errOrNull(env, rb::loadGame(toStd(env, path))); }
JNI(void, unloadGame)(JNIEnv*, jobject) { rb::unloadGame(); }
JNI(void, unloadCore)(JNIEnv*, jobject) { rb::unloadCore(); }

JNI(jobjectArray, coreInfo)(JNIEnv* env, jobject) {
    const rb::CoreInfo& ci = rb::coreInfo();
    jclass sc = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(4, sc, nullptr);
    env->SetObjectArrayElement(arr, 0, env->NewStringUTF(ci.name.c_str()));
    env->SetObjectArrayElement(arr, 1, env->NewStringUTF(ci.version.c_str()));
    env->SetObjectArrayElement(arr, 2, env->NewStringUTF(ci.extensions.c_str()));
    env->SetObjectArrayElement(arr, 3, env->NewStringUTF(ci.needFullpath ? "1" : "0"));
    return arr;
}

JNI(jdoubleArray, avInfo)(JNIEnv* env, jobject) {
    rb::AvInfo a = rb::avInfo();
    jdouble v[7] = {double(a.baseW), double(a.baseH), a.aspect, a.fps, a.sampleRate, double(a.maxW), double(a.maxH)};
    jdoubleArray r = env->NewDoubleArray(7);
    env->SetDoubleArrayRegion(r, 0, 7, v);
    return r;
}

JNI(jboolean, runFrame)(JNIEnv*, jobject) { return rb::runFrame(); }
JNI(void, reset)(JNIEnv*, jobject) { rb::reset(); }

JNI(jint, readAudio)(JNIEnv* env, jobject, jshortArray out) {
    jsize cap = env->GetArrayLength(out) / 2;
    jshort* p = env->GetShortArrayElements(out, nullptr);
    int n = rb::readAudio(reinterpret_cast<int16_t*>(p), cap);
    env->ReleaseShortArrayElements(out, p, 0);
    return n;
}

JNI(void, setInput)(JNIEnv*, jobject, jint port, jint mask, jint lx, jint ly, jint rx, jint ry) {
    rb::setInput(port, uint32_t(mask), lx, ly, rx, ry);
}
JNI(void, setOption)(JNIEnv* env, jobject, jstring k, jstring v) { rb::setOption(toStd(env, k), toStd(env, v)); }

JNI(jbyteArray, saveState)(JNIEnv* env, jobject) { return toJava(env, rb::saveState()); }
JNI(jboolean, loadState)(JNIEnv* env, jobject, jbyteArray data) {
    jsize n = env->GetArrayLength(data);
    jbyte* p = env->GetByteArrayElements(data, nullptr);
    bool ok = rb::loadState(reinterpret_cast<const uint8_t*>(p), size_t(n));
    env->ReleaseByteArrayElements(data, p, JNI_ABORT);
    return ok;
}
JNI(jbyteArray, saveRam)(JNIEnv* env, jobject) { return toJava(env, rb::saveRam()); }
JNI(jboolean, loadSaveRam)(JNIEnv* env, jobject, jbyteArray data) {
    jsize n = env->GetArrayLength(data);
    jbyte* p = env->GetByteArrayElements(data, nullptr);
    bool ok = rb::loadSaveRam(reinterpret_cast<const uint8_t*>(p), size_t(n));
    env->ReleaseByteArrayElements(data, p, JNI_ABORT);
    return ok;
}

JNI(jstring, drainMessages)(JNIEnv* env, jobject) {
    std::string m = rb::drainMessages();
    return m.empty() ? nullptr : env->NewStringUTF(m.c_str());
}

JNI(jlongArray, stats)(JNIEnv* env, jobject) {
    rb::Stats s = rb::stats();
    jlong v[7] = {s.frames, s.avgRunUs, s.maxRunUs, s.audioFrames, s.pixelFormat, s.frameW, s.frameH};
    jlongArray r = env->NewLongArray(7);
    env->SetLongArrayRegion(r, 0, 7, v);
    return r;
}
// [w, h, argb pixels...] of the newest frame, or null.
JNI(jintArray, snapshot)(JNIEnv* env, jobject) {
    std::vector<uint32_t> px;
    int w = 0, h = 0;
    if (!rb::snapshotFrame(px, &w, &h)) return nullptr;
    jintArray r = env->NewIntArray(jsize(2 + px.size()));
    jint head[2] = {w, h};
    env->SetIntArrayRegion(r, 0, 2, head);
    env->SetIntArrayRegion(r, 2, jsize(px.size()), reinterpret_cast<const jint*>(px.data()));
    return r;
}
JNI(void, resetStats)(JNIEnv*, jobject) { rb::resetStats(); }

JNI(void, glInit)(JNIEnv*, jobject) { rb::glInit(); }
JNI(void, glResize)(JNIEnv*, jobject, jint w, jint h) { rb::glResize(w, h); }
JNI(void, glDraw)(JNIEnv*, jobject) { rb::glDraw(); }
JNI(void, glRelease)(JNIEnv*, jobject) { rb::glRelease(); }
JNI(void, setDisplay)(JNIEnv*, jobject, jint mode, jint filter, jint shader, jint safePermille) { rb::setDisplay(mode, filter, shader, safePermille); }
JNI(void, setRenderTiming)(JNIEnv*, jobject, jboolean on) { rb::setRenderTiming(on); }
JNI(jlongArray, renderStats)(JNIEnv* env, jobject) {
    rb::RenderStats s = rb::renderStats();
    jlong v[4] = {s.count, s.avgUs, s.maxUs, s.avgIntervalUs};
    jlongArray r = env->NewLongArray(4);
    env->SetLongArrayRegion(r, 0, 4, v);
    return r;
}
JNI(jstring, shaderStatus)(JNIEnv* env, jobject) { return env->NewStringUTF(rb::shaderStatus().c_str()); }
JNI(jstring, glInfo)(JNIEnv* env, jobject) { return env->NewStringUTF(rb::glInfo().c_str()); }
