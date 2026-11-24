//
// Created by Lossy on 6/10/2026.
//
#include <android/log.h>
#include <atomic>
#include <exception>
#include <unistd.h>
#include <jni.h>
#include "audio.h"
#include "player-common.h"
#include "player.h"

constexpr const char* TAG = "Drakplayer JNI";
#define LOG_DEBUG(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOG_INFO(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOG_WARN(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOG_ERROR(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define METHOD(type, name) JNIEXPORT type JNICALL Java_com_lossydragon_renderer_Player_##name

#define BACKEND_OPENMPT 0
#define BACKEND_LIBXMP 1
#define BACKEND_INVALID -1

static std::atomic<int> g_backend(BACKEND_INVALID);

static bool isOpenMpt() {
  return g_backend == BACKEND_OPENMPT;
}

static bool checkBackend(JNIEnv* env, const char* method) {
  if (g_backend != BACKEND_INVALID) return true;
  LOG_ERROR("%s() - invalid backend", method);
  jclass cls = env->FindClass("java/lang/IllegalStateException");
  if (cls) env->ThrowNew(cls, "no backend selected");
  return false;
}

static void throwBadBackend(JNIEnv* env) {
  jclass cls = env->FindClass("java/lang/IllegalArgumentException");
  if (cls) env->ThrowNew(cls, "unknown backend id");
}

// A C++ exception (std::bad_alloc on a huge file, say) must never unwind into the VM: that
// aborts the process. Every entry point runs through here, which logs it and reports failure.
template <typename R, typename F>
static R guarded(const char* method, R fallback, F&& body) {
  try {
    return body();
  } catch (const std::exception& e) {
    LOG_ERROR("%s() - native exception: %s", method, e.what());
  } catch (...) {
    LOG_ERROR("%s() - unknown native exception", method);
  }
  return fallback;
}

template <typename F>
static void guarded(const char* method, F&& body) {
  guarded(method, 0, [&]() {
    body();
    return 0;
  });
}

extern "C" {

// Runs on the thread that loads the library, before any native method can be called.
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  JNIEnv* env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
  if (!initFieldIds(env)) {
    LOG_ERROR("JNI_OnLoad() - a model class or field is missing");
    return JNI_ERR;
  }
  return JNI_VERSION_1_6;
}

METHOD(void, setBackend)(JNIEnv* env, jobject, jint backend) {
  if (backend != BACKEND_OPENMPT && backend != BACKEND_LIBXMP) {
    throwBadBackend(env);
    return;
  }
  LOG_INFO("setBackend() - switching to %s", backend == BACKEND_OPENMPT ? "openmpt" : "libxmp");
  g_backend = backend;
}

METHOD(void, deinitInactive)(JNIEnv* env, jobject) {
  guarded("deinitInactive", [&]() {
    if (!checkBackend(env, "deinitInactive")) return;
    if (isOpenMpt()) {
      LOG_INFO("deinitInactive() - freeing libxmp context");
      xmp_freeContext(env);
    } else {
      LOG_INFO("deinitInactive() - freeing openmpt context");
      openmpt_freeContext(env);
    }
  });
}

METHOD(jboolean, init)(JNIEnv* env, jobject, jint rate, jint ms, jint mode, jint channels, jint api, jint flags) {
  return guarded("init", JNI_FALSE, [&]() -> jboolean {
    if (!checkBackend(env, "init")) return JNI_FALSE;
    return isOpenMpt() ? openmpt_init(env, rate, ms, mode, channels, api, flags) : xmp_init(env, rate, ms, mode, channels, api, flags);
  });
}

METHOD(jint, deinit)(JNIEnv* env, jobject) {
  return guarded("deinit", -1, [&]() -> jint {
    if (!checkBackend(env, "deinit")) return -1;
    return isOpenMpt() ? openmpt_deinit(env) : xmp_deinit(env);
  });
}

METHOD(jint, loadModuleFd)(JNIEnv* env, jobject, jint fd, jobject modInfo) {
  return guarded("loadModuleFd", -1, [&]() -> jint {
    if (!checkBackend(env, "loadModuleFd")) {
      close(fd); // ownership was transferred by ParcelFileDescriptor.detachFd()
      return -1;
    }
    return isOpenMpt() ? openmpt_loadFd(env, fd, modInfo) : xmp_loadFd(env, fd, modInfo);
  });
}

METHOD(jboolean, testModuleFd)(JNIEnv* env, jobject, jint fd, jobject modInfo) {
  // Either is safe to call regardless of chosen backend.
  return guarded("testModuleFd", JNI_FALSE, [&]() -> jboolean {
    return isOpenMpt() ? openmpt_testFd(env, fd, modInfo) : xmp_testFd(env, fd, modInfo);
  });
}

METHOD(jint, startPlayer)(JNIEnv* env, jobject, jint rate, jint format) {
  return guarded("startPlayer", -1, [&]() -> jint {
    if (!checkBackend(env, "startPlayer")) return -1;
    return isOpenMpt() ? openmpt_startPlayer(env, rate, format) : xmp_startPlayer(env, rate, format);
  });
}

METHOD(jint, endPlayer)(JNIEnv* env, jobject) {
  return guarded("endPlayer", -1, [&]() -> jint {
    if (!checkBackend(env, "endPlayer")) return -1;
    return isOpenMpt() ? openmpt_endPlayer(env) : xmp_endPlayer(env);
  });
}

METHOD(jint, playAudio)(JNIEnv* env, jobject) {
  return guarded("playAudio", -1, [&]() -> jint {
    if (!checkBackend(env, "playAudio")) return -1;
    return isOpenMpt() ? openmpt_playAudio(env) : xmp_playAudio(env);
  });
}

METHOD(jint, releaseModule)(JNIEnv* env, jobject) {
  return guarded("releaseModule", -1, [&]() -> jint {
    if (!checkBackend(env, "releaseModule")) return -1;
    return isOpenMpt() ? openmpt_releaseModule(env) : xmp_releaseModule(env);
  });
}

METHOD(jboolean, hasAudioDisconnected)(JNIEnv*, jobject) {
  return has_audio_disconnected() ? JNI_TRUE : JNI_FALSE;
}

METHOD(jboolean, hasModuleEnded)(JNIEnv* env, jobject) {
  return guarded("hasModuleEnded", JNI_FALSE, [&]() -> jboolean {
    if (!checkBackend(env, "hasModuleEnded")) return JNI_FALSE;
    return isOpenMpt() ? openmpt_hasEnded(env) : xmp_hasEnded(env);
  });
}

METHOD(void, setPlaying)(JNIEnv* env, jobject, jboolean value) {
  guarded("setPlaying", [&]() {
    if (!checkBackend(env, "setPlaying")) return;
    isOpenMpt() ? openmpt_setPlaying(env, value) : xmp_setPlaying(env, value);
  });
}

METHOD(void, setLoopMode)(JNIEnv* env, jobject, jboolean loop) {
  guarded("setLoopMode", [&]() {
    if (!checkBackend(env, "setLoopMode")) return;
    isOpenMpt() ? openmpt_setLoopMode(env, loop) : xmp_setLoopMode(env, loop);
  });
}

METHOD(jint, seek)(JNIEnv* env, jobject, jint time) {
  return guarded("seek", -1, [&]() -> jint {
    if (!checkBackend(env, "seek")) return -1;
    return isOpenMpt() ? openmpt_seek(env, time) : xmp_seek(env, time);
  });
}

METHOD(jint, mute)(JNIEnv* env, jobject, jint chn, jint status) {
  return guarded("mute", -1, [&]() -> jint {
    if (!checkBackend(env, "mute")) return -1;
    return isOpenMpt() ? openmpt_mute(env, chn, status) : xmp_mute(env, chn, status);
  });
}

METHOD(void, getFrameInfo)(JNIEnv* env, jobject, jobject frameInfo) {
  guarded("getFrameInfo", [&]() {
    if (!checkBackend(env, "getFrameInfo")) return;
    isOpenMpt() ? openmpt_getFrameInfo(env, frameInfo) : xmp_getFrameInfo(env, frameInfo);
  });
}

METHOD(jint, setPlayer)(JNIEnv* env, jobject, jint parm, jint value) {
  return guarded("setPlayer", -1, [&]() -> jint {
    if (!checkBackend(env, "setPlayer")) return -1;
    return isOpenMpt() ? openmpt_setPlayer(env, parm, value) : xmp_setPlayer(env, parm, value);
  });
}

METHOD(jint, getPlayer)(JNIEnv* env, jobject, jint parm) {
  return guarded("getPlayer", 0, [&]() -> jint {
    if (!checkBackend(env, "getPlayer")) return 0;
    return isOpenMpt() ? openmpt_getPlayer(env, parm) : xmp_getPlayer(env, parm);
  });
}

METHOD(void, getModVars)(JNIEnv* env, jobject, jobject modVars) {
  guarded("getModVars", [&]() {
    if (!checkBackend(env, "getModVars")) return;
    isOpenMpt() ? openmpt_getModVars(env, modVars) : xmp_getModVars(env, modVars);
  });
}

// Both format lists are static library data, so this works for either backend at any time,
// independent of (and without touching) the one currently selected.
METHOD(jobjectArray, getFormats)(JNIEnv* env, jobject, jint backend) {
  return guarded("getFormats", static_cast<jobjectArray>(nullptr), [&]() -> jobjectArray {
    if (backend != BACKEND_OPENMPT && backend != BACKEND_LIBXMP) {
      throwBadBackend(env);
      return nullptr;
    }
    return backend == BACKEND_OPENMPT ? openmpt_getFormats(env) : xmp_getFormats(env);
  });
}

METHOD(void, getChannelData)(JNIEnv* env, jobject, jobject channelInfo) {
  guarded("getChannelData", [&]() {
    if (!checkBackend(env, "getChannelData")) return;
    isOpenMpt() ? openmpt_getChannelData(env, channelInfo) : xmp_getChannelData(env, channelInfo);
  });
}

METHOD(jint, getPatternRows)(JNIEnv* env, jobject, jint pat) {
  return guarded("getPatternRows", 0, [&]() -> jint {
    if (!checkBackend(env, "getPatternRows")) return 0;
    return isOpenMpt() ? openmpt_getPatternRows(env, pat) : xmp_getPatternRows(env, pat);
  });
}

METHOD(void, getPatternRow)(JNIEnv* env, jobject, jint pat, jint row, jbyteArray rowNotes, jbyteArray rowInstruments, jbyteArray rowFxType, jbyteArray rowFxParm, jbyteArray rowFx2Type, jbyteArray rowFx2Parm) {
  guarded("getPatternRow", [&]() {
    if (!checkBackend(env, "getPatternRow")) return;
    isOpenMpt() ? openmpt_getPatternRow(env, pat, row, rowNotes, rowInstruments, rowFxType, rowFxParm, rowFx2Type, rowFx2Parm)
                : xmp_getPatternRow(env, pat, row, rowNotes, rowInstruments, rowFxType, rowFxParm, rowFx2Type, rowFx2Parm);
  });
}

METHOD(void, getSampleData)(JNIEnv* env, jobject, jboolean trigger, jint ins, jint key, jint period, jint chn, jint width, jbyteArray buffer) {
  guarded("getSampleData", [&]() {
    if (!checkBackend(env, "getSampleData")) return;
    isOpenMpt() ? openmpt_getSampleData(env, trigger, ins, key, period, chn, width, buffer) : xmp_getSampleData(env, trigger, ins, key, period, chn, width, buffer);
  });
}

METHOD(jboolean, setSequence)(JNIEnv* env, jobject, jint seq) {
  return guarded("setSequence", JNI_FALSE, [&]() -> jboolean {
    if (!checkBackend(env, "setSequence")) return JNI_FALSE;
    return isOpenMpt() ? openmpt_setSequence(env, seq) : xmp_setSequence(env, seq);
  });
}

METHOD(void, getAudioStats)(JNIEnv* env, jobject, jobject info) {
  guarded("getAudioStats", [&]() {
    if (!checkBackend(env, "getAudioStats")) return;
    isOpenMpt() ? openmpt_getAudioStats(env, info) : xmp_getAudioStats(env, info);
  });
}

METHOD(jint, setResampler)(JNIEnv* env, jobject, jint mode) {
  return guarded("setResampler", 0, [&]() -> jint {
    if (!checkBackend(env, "setResampler")) return 0;
    if (!isOpenMpt()) {
      LOG_WARN("setResampler() - libxmp backend does not support resampler");
      return 0;
    }
    return openmpt_setResampler(env, mode);
  });
}
}
