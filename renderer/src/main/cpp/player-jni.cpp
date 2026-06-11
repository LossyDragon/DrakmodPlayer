//
// Created by Lossy on 6/10/2026.
//
#include <android/log.h>
#include <stdatomic.h>
#include <jni.h>
#include "player.h"

constexpr const char* TAG = "Drakplayer JNI";
#define LOG_DEBUG(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOG_INFO(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOG_WARN(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOG_ERROR(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define METHOD(type, name) JNIEXPORT type JNICALL Java_com_lossydragon_native_Player_##name
#define GET_CLASS(a, b) a.clazz = env->FindClass(b)
#define GET_FIELD(a, b, c) a.b = env->GetFieldID(a.clazz, #b, c)

#define BACKEND_OPENMPT 0
#define BACKEND_LIBXMP 1
#define BACKEND_INVALID -1

static _Atomic int g_backend = BACKEND_INVALID;

static jboolean checkBackend(JNIEnv* env) {
  if (g_backend != BACKEND_INVALID) return JNI_TRUE;
  jclass cls = env->FindClass("java/lang/IllegalStateException");
  env->ThrowNew(cls, "no backend selected");
  return JNI_FALSE;
}

extern "C" {

METHOD(void, setBackend)(JNIEnv* env, jobject obj, jint backend) {
  if (backend != BACKEND_OPENMPT && backend != BACKEND_LIBXMP) {
    jclass cls = env->FindClass("java/lang/IllegalArgumentException");
    env->ThrowNew(cls, "unknown backend id");
    return;
  }
  LOG_INFO("setBackend() - switching to %s", backend == BACKEND_OPENMPT ? "openmpt" : "libxmp");
  g_backend = backend;
}

METHOD(void, deinitInactive)(JNIEnv* env, jobject obj) {
  if (!checkBackend(env)) {
    LOG_ERROR("deinitInactive() - invalid backend");
    return;
  }
  if (g_backend == BACKEND_OPENMPT) {
    LOG_INFO("deinitInactive() - freeing libxmp context");
    xmp_freeContext(env);
  } else if (g_backend == BACKEND_LIBXMP) {
    LOG_INFO("deinitInactive() - freeing openmpt context");
    openmpt_freeContext(env);
  }
}

METHOD(jboolean, init)(JNIEnv* env, jobject obj, jint rate, jint ms, jint mode, jint channels, jint api, jint flags) {
  if (!checkBackend(env)) {
    LOG_ERROR("init() - invalid backend");
    return JNI_FALSE;
  }
  return g_backend == BACKEND_OPENMPT ? openmpt_init(env, rate, ms, mode, channels, api, flags) : xmp_init(env, rate, ms, mode, channels, api, flags);
}

METHOD(jint, deinit)(JNIEnv* env, jobject obj) {
  if (!checkBackend(env)) {
    LOG_ERROR("deinit() - invalid backend");
    return JNI_FALSE;
  }
  return g_backend == BACKEND_OPENMPT ? openmpt_deinit(env) : xmp_deinit(env);
}

METHOD(jint, loadModuleFd)(JNIEnv* env, jobject obj, jint fd, jobject modInfo) {
  if (!checkBackend(env)) {
    LOG_ERROR("loadModuleFd() - invalid backend");
    return JNI_FALSE;
  }
  return g_backend == BACKEND_OPENMPT ? openmpt_loadFd(env, fd, modInfo) : xmp_loadFd(env, fd, modInfo);
}

METHOD(jboolean, testModuleFd)(JNIEnv* env, jobject obj, jint fd, jobject modInfo) {
  if (!checkBackend(env)) {
    LOG_ERROR("testModuleFd() - invalid backend");
    return JNI_FALSE;
  }
  return g_backend == BACKEND_OPENMPT ? openmpt_testFd(env, fd, modInfo) : xmp_testFd(env, fd, modInfo);
}

METHOD(jint, startPlayer)(JNIEnv* env, jobject obj, jint rate, jint format) {
  if (!checkBackend(env)) {
    LOG_ERROR("startPlayer() - invalid backend");
    return JNI_FALSE;
  }
  return g_backend == BACKEND_OPENMPT ? openmpt_startPlayer(env, rate, format) : xmp_startPlayer(env, rate, format);
}

METHOD(jint, endPlayer)(JNIEnv* env, jobject obj) {
  if (!checkBackend(env)) {
    LOG_ERROR("endPlayer() - invalid backend");
    return JNI_FALSE;
  }
  return g_backend == BACKEND_OPENMPT ? openmpt_endPlayer(env) : xmp_endPlayer(env);
}

METHOD(jint, playAudio)(JNIEnv* env, jobject obj) {
  if (!checkBackend(env)) {
    LOG_ERROR("playAudio() - invalid backend");
    return JNI_FALSE;
  }
  return g_backend == BACKEND_OPENMPT ? openmpt_playAudio(env) : xmp_playAudio(env);
}

METHOD(jint, releaseModule)(JNIEnv* env, jobject obj) {
  if (!checkBackend(env)) {
    LOG_ERROR("releaseModule() - invalid backend");
    return JNI_FALSE;
  }
  return g_backend == BACKEND_OPENMPT ? openmpt_releaseModule(env) : xmp_releaseModule(env);
}

METHOD(jboolean, stopAudio)(JNIEnv* env, jobject obj) {
  if (!checkBackend(env)) {
    LOG_ERROR("stopAudio() - invalid backend");
    return JNI_FALSE;
  }
  return g_backend == BACKEND_OPENMPT ? openmpt_stopAudio(env) : xmp_stopAudio(env);
}

METHOD(jboolean, restartAudio)(JNIEnv* env, jobject obj) {
  if (!checkBackend(env)) {
    LOG_ERROR("restartAudio() - invalid backend");
    return JNI_FALSE;
  }
  return g_backend == BACKEND_OPENMPT ? openmpt_restartAudio(env) : xmp_restartAudio(env);
}

METHOD(jboolean, hasModuleEnded)(JNIEnv* env, jobject obj) {
  if (!checkBackend(env)) {
    LOG_ERROR("hasModuleEnded() - invalid backend");
    return JNI_FALSE;
  }
  return g_backend == BACKEND_OPENMPT ? openmpt_hasEnded(env) : xmp_hasEnded(env);
}

METHOD(void, setPlaying)(JNIEnv* env, jobject obj, jboolean value) {
  if (!checkBackend(env)) {
    LOG_ERROR("setXmpPlaying() - invalid backend");
    return;
  }
  g_backend == BACKEND_OPENMPT ? openmpt_setPlaying(env, value) : xmp_setPlaying(env, value);
}

METHOD(void, setLoopMode)(JNIEnv* env, jobject obj, jboolean loop) {
  if (!checkBackend(env)) {
    LOG_ERROR("setLoopMode() - invalid backend");
    return;
  }
  g_backend == BACKEND_OPENMPT ? openmpt_setLoopMode(env, loop) : xmp_setLoopMode(env, loop);
}

METHOD(jint, nextPosition)(JNIEnv* env, jobject obj) {
  if (!checkBackend(env)) {
    LOG_ERROR("nextPosition() - invalid backend");
    return JNI_FALSE;
  }
  return g_backend == BACKEND_OPENMPT ? openmpt_nextPosition(env) : xmp_nextPosition(env);
}

METHOD(jint, prevPosition)(JNIEnv* env, jobject obj) {
  if (!checkBackend(env)) {
    LOG_ERROR("prevPosition() - invalid backend");
    return JNI_FALSE;
  }
  return g_backend == BACKEND_OPENMPT ? openmpt_prevPosition(env) : xmp_prevPosition(env);
}

METHOD(jint, setPosition)(JNIEnv* env, jobject obj, jint pos) {
  if (!checkBackend(env)) {
    LOG_ERROR("setPosition() - invalid backend");
    return JNI_FALSE;
  }
  return g_backend == BACKEND_OPENMPT ? openmpt_setPosition(env, pos) : xmp_setPosition(env, pos);
}

METHOD(jint, stopModule)(JNIEnv* env, jobject obj) {
  if (!checkBackend(env)) {
    LOG_ERROR("stopModule() - invalid backend");
    return JNI_FALSE;
  }
  return g_backend == BACKEND_OPENMPT ? openmpt_stopModule(env) : xmp_stopModule(env);
}

METHOD(jint, restartModule)(JNIEnv* env, jobject obj) {
  if (!checkBackend(env)) {
    LOG_ERROR("restartModule() - invalid backend");
    return JNI_FALSE;
  }
  return g_backend == BACKEND_OPENMPT ? openmpt_restartModule(env) : xmp_restartModule(env);
}

METHOD(jint, seek)(JNIEnv* env, jobject obj, jint time) {
  if (!checkBackend(env)) {
    LOG_ERROR("seek() - invalid backend");
    return JNI_FALSE;
  }
  return g_backend == BACKEND_OPENMPT ? openmpt_seek(env, time) : xmp_seek(env, time);
}

METHOD(jint, time)(JNIEnv* env, jobject obj) {
  if (!checkBackend(env)) {
    LOG_ERROR("time() - invalid backend");
    return JNI_FALSE;
  }
  return g_backend == BACKEND_OPENMPT ? openmpt_time(env) : xmp_time(env);
}

METHOD(jint, mute)(JNIEnv* env, jobject obj, jint chn, jint status) {
  if (!checkBackend(env)) {
    LOG_ERROR("mute() - invalid backend");
    return JNI_FALSE;
  }
  return g_backend == BACKEND_OPENMPT ? openmpt_mute(env, chn, status) : xmp_mute(env, chn, status);
}

METHOD(void, getFrameInfo)(JNIEnv* env, jobject obj, jobject frameInfo) {
  if (!checkBackend(env)) {
    LOG_ERROR("getFrameInfo() - invalid backend");
    return;
  }
  g_backend == BACKEND_OPENMPT ? openmpt_getFrameInfo(env, frameInfo) : xmp_getFrameInfo(env, frameInfo);
}

METHOD(jint, setPlayer)(JNIEnv* env, jobject obj, jint parm, jint value) {
  if (!checkBackend(env)) {
    LOG_ERROR("setPlayer() - invalid backend");
    return JNI_FALSE;
  }
  return g_backend == BACKEND_OPENMPT ? openmpt_setPlayer(env, parm, value) : xmp_setPlayer(env, parm, value);
}

METHOD(jint, getPlayer)(JNIEnv* env, jobject obj, jint parm) {
  if (!checkBackend(env)) {
    LOG_ERROR("getPlayer() - invalid backend");
    return JNI_FALSE;
  }
  return g_backend == BACKEND_OPENMPT ? openmpt_getPlayer(env, parm) : xmp_getPlayer(env, parm);
}

METHOD(void, getModVars)(JNIEnv* env, jobject obj, jobject modVars) {
  if (!checkBackend(env)) {
    LOG_ERROR("getModVars() - invalid backend");
    return;
  }
  g_backend == BACKEND_OPENMPT ? openmpt_getModVars(env, modVars) : xmp_getModVars(env, modVars);
}

METHOD(jstring, getVersion)(JNIEnv* env, jobject obj) {
  if (!checkBackend(env)) {
    LOG_ERROR("getVersion() - invalid backend");
    return JNI_FALSE;
  }
  return g_backend == BACKEND_OPENMPT ? openmpt_getVersion(env) : xmp_getVersion(env);
}

METHOD(jobjectArray, getFormats)(JNIEnv* env, jobject obj) {
  if (!checkBackend(env)) {
    LOG_ERROR("getFormats() - invalid backend");
    return JNI_FALSE;
  }
  return g_backend == BACKEND_OPENMPT ? openmpt_getFormats(env) : xmp_getFormats(env);
}

METHOD(void, getChannelData)(JNIEnv* env, jobject obj, jobject channelInfo) {
  if (!checkBackend(env)) {
    LOG_ERROR("getChannelData() - invalid backend");
    return;
  }
  g_backend == BACKEND_OPENMPT ? openmpt_getChannelData(env, channelInfo) : xmp_getChannelData(env, channelInfo);
}

METHOD(jint, getPatternRows)(JNIEnv* env, jobject obj, jint pat) {
  if (!checkBackend(env)) {
    LOG_ERROR("getPatternRows() - invalid backend");
    return JNI_FALSE;
  }
  return g_backend == BACKEND_OPENMPT ? openmpt_getPatternRows(env, pat) : xmp_getPatternRows(env, pat);
}

METHOD(void, getPatternRow)(JNIEnv* env, jobject obj, jint pat, jint row, jbyteArray rowNotes, jbyteArray rowInstruments, jbyteArray rowFxType, jbyteArray rowFxParm) {
  if (!checkBackend(env)) {
    LOG_ERROR("getPatternRow() - invalid backend");
    return;
  }
  g_backend == BACKEND_OPENMPT ? openmpt_getPatternRow(env, pat, row, rowNotes, rowInstruments, rowFxType, rowFxParm)
                               : xmp_getPatternRow(env, pat, row, rowNotes, rowInstruments, rowFxType, rowFxParm);
}

METHOD(void, getSampleData)(JNIEnv* env, jobject obj, jboolean trigger, jint ins, jint key, jint period, jint chn, jint width, jbyteArray buffer) {
  if (!checkBackend(env)) {
    LOG_ERROR("getSampleData() - invalid backend");
    return;
  }
  g_backend == BACKEND_OPENMPT ? openmpt_getSampleData(env, trigger, ins, key, period, chn, width, buffer) : xmp_getSampleData(env, trigger, ins, key, period, chn, width, buffer);
}

METHOD(jboolean, setSequence)(JNIEnv* env, jobject obj, jint seq) {
  if (!checkBackend(env)) {
    LOG_ERROR("setSequence() - invalid backend");
    return JNI_FALSE;
  }
  return g_backend == BACKEND_OPENMPT ? openmpt_setSequence(env, seq) : xmp_setSequence(env, seq);
}

METHOD(void, getAudioStats)(JNIEnv* env, jobject obj, jobject info) {
  if (!checkBackend(env)) {
    LOG_ERROR("getAudioStats() - invalid backend");
    return;
  }
  g_backend == BACKEND_OPENMPT ? openmpt_getAudioStats(env, info) : xmp_getAudioStats(env, info);
}

METHOD(jint, setResampler)(JNIEnv* env, jobject, jint mode) {
  if (!checkBackend(env)) {
    LOG_ERROR("setResampler() - invalid backend");
    return JNI_FALSE;
  }
  if (g_backend == BACKEND_LIBXMP) {
    LOG_WARN("setResampler() - libxmp backend does not support resampler");
    return JNI_FALSE;
  }
  return g_backend == BACKEND_OPENMPT ?: openmpt_setResampler(env, mode);
}
}
