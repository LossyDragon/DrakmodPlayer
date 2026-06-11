#pragma once
#include <jni.h>

// libxmp

jboolean xmp_hasEnded(JNIEnv* env);
jboolean xmp_init(JNIEnv* env, jint rate, jint ms, jint mode, jint channels, jint api, jint flags);
jboolean xmp_restartAudio(JNIEnv* env);
jboolean xmp_setSequence(JNIEnv* env, jint seq);
jboolean xmp_stopAudio(JNIEnv* env);
jboolean xmp_testFd(JNIEnv* env, jint fd, jobject modInfo);
jint xmp_deinit(JNIEnv* env);
jint xmp_endPlayer(JNIEnv* env);
jint xmp_getPatternRows(JNIEnv* env, jint pat);
jint xmp_getPlayer(JNIEnv* env, jint parm);
jint xmp_loadFd(JNIEnv* env, jint fd, jobject modInfo);
jint xmp_mute(JNIEnv* env, jint chn, jint status);
jint xmp_nextPosition(JNIEnv* env);
jint xmp_playAudio(JNIEnv* env);
jint xmp_prevPosition(JNIEnv* env);
jint xmp_releaseModule(JNIEnv* env);
jint xmp_restartModule(JNIEnv* env);
jint xmp_seek(JNIEnv* env, jint time);
jint xmp_setPlayer(JNIEnv* env, jint parm, jint value);
jint xmp_setPosition(JNIEnv* env, jint n);
jint xmp_startPlayer(JNIEnv* env, jint rate, jint format);
jint xmp_stopModule(JNIEnv* env);
jint xmp_time(JNIEnv* env);
jobjectArray xmp_getFormats(JNIEnv* env);
jstring xmp_getVersion(JNIEnv* env);
void xmp_getAudioStats(JNIEnv* env, jobject info);
void xmp_getChannelData(JNIEnv* env, jobject channelInfo);
void xmp_getFrameInfo(JNIEnv* env, jobject frameInfo);
void xmp_getModVars(JNIEnv* env, jobject modVars);
void xmp_getPatternRow(JNIEnv* env, jint pat, jint row, jbyteArray notes, jbyteArray instruments, jbyteArray fxType, jbyteArray fxParm);
void xmp_getSampleData(JNIEnv* env, jboolean trigger, jint ins, jint key, jint period, jint chn, jint width, jbyteArray buffer);
void xmp_setLoopMode(JNIEnv* env, jboolean loop);
void xmp_setPlaying(JNIEnv* env, jboolean value);
void xmp_freeContext(JNIEnv* env);

// libopenmpt

jboolean openmpt_hasEnded(JNIEnv* env);
jboolean openmpt_init(JNIEnv* env, jint rate, jint ms, jint mode, jint channels, jint api, jint flags);
jboolean openmpt_restartAudio(JNIEnv* env);
jboolean openmpt_setSequence(JNIEnv* env, jint seq);
jboolean openmpt_stopAudio(JNIEnv* env);
jboolean openmpt_testFd(JNIEnv* env, jint fd, jobject modInfo);
jint openmpt_deinit(JNIEnv* env);
jint openmpt_endPlayer(JNIEnv* env);
jint openmpt_getPatternRows(JNIEnv* env, jint pat);
jint openmpt_getPlayer(JNIEnv* env, jint parm);
jint openmpt_loadFd(JNIEnv* env, jint fd, jobject modInfo);
jint openmpt_mute(JNIEnv* env, jint chn, jint status);
jint openmpt_nextPosition(JNIEnv* env);
jint openmpt_playAudio(JNIEnv* env);
jint openmpt_prevPosition(JNIEnv* env);
jint openmpt_releaseModule(JNIEnv* env);
jint openmpt_restartModule(JNIEnv* env);
jint openmpt_seek(JNIEnv* env, jint time);
jint openmpt_setPlayer(JNIEnv* env, jint parm, jint value);
jint openmpt_setPosition(JNIEnv* env, jint n);
jint openmpt_setResampler(JNIEnv* env, jint mode);
jint openmpt_startPlayer(JNIEnv* env, jint rate, jint format);
jint openmpt_stopModule(JNIEnv* env);
jint openmpt_time(JNIEnv* env);
jobjectArray openmpt_getFormats(JNIEnv* env);
jstring openmpt_getVersion(JNIEnv* env);
void openmpt_getAudioStats(JNIEnv* env, jobject info);
void openmpt_getChannelData(JNIEnv* env, jobject channelInfo);
void openmpt_getFrameInfo(JNIEnv* env, jobject frameInfo);
void openmpt_getModVars(JNIEnv* env, jobject modVars);
void openmpt_getPatternRow(JNIEnv* env, jint pat, jint row, jbyteArray notes, jbyteArray instruments, jbyteArray fxType, jbyteArray fxParm);
void openmpt_getSampleData(JNIEnv* env, jboolean trigger, jint ins, jint key, jint period, jint chn, jint width, jbyteArray buffer);
void openmpt_setLoopMode(JNIEnv* env, jboolean loop);
void openmpt_setPlaying(JNIEnv* env, jboolean value);
void openmpt_freeContext(JNIEnv* env);
