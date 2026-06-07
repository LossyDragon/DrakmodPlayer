/*
 * Modernized C++17 JNI interface for libxmp
 * For a full JNI interface, check the libxmp Java API at:
 * https://github.com/cmatsuoka/libxmp-java or https://github.com/TheEssem/libxmp-java
 */

#include "xmp.h"
#include "common.h"
#include "audio.h"
#include <android/log.h>
#include <jni.h>
#include <algorithm>
#include <array>
#include <cstdio>
#include <memory>
#include <mutex>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

constexpr const char* TAG = "Mod Player JNI";
#define LOG_DEBUG(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOG_INFO(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOG_WARN(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOG_ERROR(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define METHOD(type, name) JNIEXPORT type JNICALL Java_org_helllabs_libxmp_Xmp_##name
#define GET_CLASS(a, b) a.clazz = env->FindClass(b)
#define GET_FIELD(a, b, c) a.b = env->GetFieldID(a.clazz, #b, c)

extern "C" {

static struct audio_stats {
  jclass clazz = nullptr;
  jfieldID xrunCount = nullptr;
  jfieldID underrunCount = nullptr;
  jfieldID framesPerBurst = nullptr;
  jfieldID bufferCapacity = nullptr;
  jfieldID bufferSize = nullptr;
  jfieldID sampleRate = nullptr;
  jfieldID audioApi = nullptr;
  jfieldID sharingMode = nullptr;
  jfieldID perfMode = nullptr;
  jfieldID audioFormat = nullptr;
} audio_stats;

static struct mod_info {
  jclass clazz = nullptr;
  jfieldID name = nullptr;
  jfieldID type = nullptr;
} mod_info;

static struct channel_info {
  jclass clazz = nullptr;
  jfieldID volumes = nullptr;
  jfieldID finalVols = nullptr;
  jfieldID pans = nullptr;
  jfieldID instruments = nullptr;
  jfieldID keys = nullptr;
  jfieldID periods = nullptr;
  jfieldID holdVols = nullptr;
} channel_info;

static struct mod_vars {
  jclass clazz = nullptr;
  jfieldID currentSequence = nullptr;
  jfieldID lengthInPatterns = nullptr;
  jfieldID numChannels = nullptr;
  jfieldID numInstruments = nullptr;
  jfieldID numPatterns = nullptr;
  jfieldID numSamples = nullptr;
  jfieldID numSequence = nullptr;
  jfieldID seqDuration = nullptr;
} mod_vars;

static struct frame_info {
  jclass clazz = nullptr;
  jfieldID pos = nullptr;
  jfieldID pattern = nullptr;
  jfieldID row = nullptr;
  jfieldID numRows = nullptr;
  jfieldID frame = nullptr;
  jfieldID speed = nullptr;
  jfieldID bpm = nullptr;
} frame_info;

namespace {
  constexpr int MAX_BUFFER_SIZE = 1024;
  constexpr int PERIOD_BASE = 13696;
  constexpr int BUFFER_TIME_MS = 40;
  constexpr int MIN_BUFFER_NUM = 3;

  inline pid_t get_thread_id() {
    return gettid();
  }

  struct XmpPlayerState {
    static XmpPlayerState& instance() {
      static XmpPlayerState s;
      return s;
    }

    XmpPlayerState(const XmpPlayerState&) = delete;
    XmpPlayerState& operator=(const XmpPlayerState&) = delete;

    std::unique_lock<std::mutex> lock() {
      return std::unique_lock<std::mutex>(mutex);
    }

    void incrementBefore() {
      before = (before + 1) % buffer_num;
    }

    std::mutex mutex;
    bool initialized = false;
    bool mod_is_loaded = false;
    bool playing = false;
    int actual_rate = 0;
    int buffer_num = 0;
    int loop_count = 0;
    int sequence = 0;
    int decay = 4;
    int before = 0;
    int now = 0;

    std::unique_ptr<xmp_frame_info[]> fi;
    xmp_context ctx = nullptr;
    xmp_module_info mi{};

    std::array<int, XMP_MAX_CHANNELS> cur_vol{};
    std::array<int, XMP_MAX_CHANNELS> final_vol{};
    std::array<int, XMP_MAX_CHANNELS> hold_vol{};
    std::array<int, XMP_MAX_CHANNELS> ins{};
    std::array<int, XMP_MAX_CHANNELS> key{};
    std::array<int, XMP_MAX_CHANNELS> last_key{};
    std::array<int, XMP_MAX_CHANNELS> pan{};
    std::array<int, XMP_MAX_CHANNELS> period{};
    std::array<int, XMP_MAX_CHANNELS> pos{};

  private:
    XmpPlayerState() = default;
  };

  void initAudioStatsFields(JNIEnv* env) {
    if (audio_stats.clazz != nullptr) return;
    GET_CLASS(audio_stats, "org/helllabs/libxmp/model/AudioStats");
    GET_FIELD(audio_stats, xrunCount, "I");
    GET_FIELD(audio_stats, underrunCount, "I");
    GET_FIELD(audio_stats, framesPerBurst, "I");
    GET_FIELD(audio_stats, bufferCapacity, "I");
    GET_FIELD(audio_stats, bufferSize, "I");
    GET_FIELD(audio_stats, sampleRate, "I");
    GET_FIELD(audio_stats, audioApi, "Ljava/lang/String;");
    GET_FIELD(audio_stats, sharingMode, "Ljava/lang/String;");
    GET_FIELD(audio_stats, perfMode, "Ljava/lang/String;");
    GET_FIELD(audio_stats, audioFormat, "Ljava/lang/String;");
  }

  void initChannelInfoFields(JNIEnv* env) {
    if (channel_info.clazz != nullptr) return;
    GET_CLASS(channel_info, "org/helllabs/libxmp/model/ChannelInfo");
    GET_FIELD(channel_info, volumes, "[I");
    GET_FIELD(channel_info, finalVols, "[I");
    GET_FIELD(channel_info, pans, "[I");
    GET_FIELD(channel_info, instruments, "[I");
    GET_FIELD(channel_info, keys, "[I");
    GET_FIELD(channel_info, periods, "[I");
    GET_FIELD(channel_info, holdVols, "[I");
  }

  void initFrameInfoFields(JNIEnv* env) {
    if (frame_info.clazz != nullptr) return;
    GET_CLASS(frame_info, "org/helllabs/libxmp/model/FrameInfo");
    GET_FIELD(frame_info, pos, "I");
    GET_FIELD(frame_info, pattern, "I");
    GET_FIELD(frame_info, row, "I");
    GET_FIELD(frame_info, numRows, "I");
    GET_FIELD(frame_info, frame, "I");
    GET_FIELD(frame_info, speed, "I");
    GET_FIELD(frame_info, bpm, "I");
  }

  void initModInfoFields(JNIEnv* env) {
    if (mod_info.clazz != nullptr) return;
    GET_CLASS(mod_info, "org/helllabs/libxmp/model/ModInfo");
    GET_FIELD(mod_info, name, "Ljava/lang/String;");
    GET_FIELD(mod_info, type, "Ljava/lang/String;");
  }

  void initModVarsFields(JNIEnv* env) {
    if (mod_vars.clazz != nullptr) return;
    GET_CLASS(mod_vars, "org/helllabs/libxmp/model/ModVars");
    GET_FIELD(mod_vars, currentSequence, "I");
    GET_FIELD(mod_vars, lengthInPatterns, "I");
    GET_FIELD(mod_vars, numChannels, "I");
    GET_FIELD(mod_vars, numInstruments, "I");
    GET_FIELD(mod_vars, numPatterns, "I");
    GET_FIELD(mod_vars, numSamples, "I");
    GET_FIELD(mod_vars, numSequence, "I");
    GET_FIELD(mod_vars, seqDuration, "I");
  }

  xmp_subinstrument* getSubinstrument(const xmp_module_info& mi, int ins, int key) {
    if (ins < 0 || ins >= mi.mod->ins || key < 0 || key >= XMP_MAX_KEYS) return nullptr;
    if (mi.mod->xxi[ins].map[key].ins == 0xff) return nullptr;

    int mapped = mi.mod->xxi[ins].map[key].ins;
    if (mapped < 0 || mapped >= mi.mod->xxi[ins].nsm) return nullptr;

    return &mi.mod->xxi[ins].sub[mapped];
  }

}

int play_buffer(void* buffer, int size, int looped) {
  XmpPlayerState& state = XmpPlayerState::instance();
  std::unique_lock<std::mutex> lock = state.lock();

  if (!state.playing) return -XMP_END;

  int num_loop = looped ? 0 : state.loop_count + 1;
  int ret = xmp_play_buffer(state.ctx, buffer, size, num_loop);

  xmp_frame_info* fi = state.fi.get();
  xmp_get_frame_info(state.ctx, &fi[state.now]);

  state.incrementBefore();
  state.now = (state.before + state.buffer_num - 1) % state.buffer_num;
  state.loop_count = fi[state.now].loop_count;

  return ret;
}

METHOD(jboolean, init)(JNIEnv* env, jobject obj, jint rate, jint ms, jint mode, jint channels, jint api, jint flags) {
  LOG_INFO("init() called - rate: %d, ms: %d, tid: %d", rate, ms, get_thread_id());

  XmpPlayerState& state = XmpPlayerState::instance();

  xmp_context ctx = xmp_create_context();
  if (!ctx) {
    LOG_ERROR("init() failed - could not create context");
    return JNI_FALSE;
  }

  int actual_rate = open_audio(rate, ms, mode, channels, api, flags);
  if (actual_rate < 0) {
    LOG_ERROR("init() failed - could not open audio");
    xmp_free_context(ctx);
    return JNI_FALSE;
  }

  state.ctx = ctx;
  state.actual_rate = actual_rate;

  int buffer_num = ms / BUFFER_TIME_MS;
  if (buffer_num < MIN_BUFFER_NUM) buffer_num = MIN_BUFFER_NUM;
  state.buffer_num = buffer_num;

  if (actual_rate != rate) {
    LOG_INFO("init() - sample rate adjusted: requested=%d, actual=%d", rate, actual_rate);
  }

  initAudioStatsFields(env);
  initChannelInfoFields(env);
  initFrameInfoFields(env);
  initModInfoFields(env);
  initModVarsFields(env);

  state.initialized = true;
  LOG_INFO("init() completed successfully - actual_rate=%d", actual_rate);

  return JNI_TRUE;
}

METHOD(jint, deinit)(JNIEnv* env, jobject obj) {
  LOG_INFO("deinit() called - tid: %d", get_thread_id());

  XmpPlayerState& state = XmpPlayerState::instance();

  {
    std::unique_lock<std::mutex> lock = state.lock();
    LOG_DEBUG("deinit() - acquired lock");
    state.initialized = false;
    xmp_free_context(state.ctx);
    LOG_DEBUG("deinit() - freed context");
  }
  LOG_DEBUG("deinit() - released lock");

  close_audio();
  LOG_INFO("deinit() completed - tid: %d", get_thread_id());

  return 0;
}

METHOD(jint, loadModuleFd)(JNIEnv* env, jobject obj, jint fd, jobject modInfo) {
  LOG_INFO("loadModuleFd() called - fd: %d, tid: %d", fd, get_thread_id());

  std::unique_ptr<FILE, decltype(&fclose)> file(fdopen(fd, "rb"), fclose);
  if (!file) {
    LOG_ERROR("loadModuleFd() - fdopen failed: %s", strerror(errno));
    return -1;
  }

  xmp_test_info ti{};
  int res = xmp_test_module_from_file(file.get(), &ti);

  if (res != 0) {
    LOG_WARN("loadModuleFd() - test failed: %d", res);
    return -2;
  }

  jstring name = env->NewStringUTF(ti.name);
  jstring type = env->NewStringUTF(ti.type);
  env->SetObjectField(modInfo, mod_info.name, name);
  env->SetObjectField(modInfo, mod_info.type, type);
  env->DeleteLocalRef(name);
  env->DeleteLocalRef(type);

  rewind(file.get());

  struct stat statbuf{};
  if (fstat(fd, &statbuf) != 0) {
    LOG_ERROR("loadModuleFd() - fstat failed: %s", strerror(errno));
    return -3;
  }

  XmpPlayerState& state = XmpPlayerState::instance();
  res = xmp_load_module_from_file(state.ctx, file.get(), static_cast<off_t>(statbuf.st_size));

  if (res == 0) {
    xmp_get_module_info(state.ctx, &state.mi);
    state.pos.fill(0);
    state.sequence = 0;
    state.mod_is_loaded = true;
    LOG_INFO("loadModuleFd() - loaded: %s", ti.name);
  }

  return res;
}

METHOD(jboolean, testModuleFd)(JNIEnv* env, jobject obj, jint fd, jobject modInfo) {
  LOG_INFO("testModuleFd() called - fd: %d, tid: %d", fd, get_thread_id());

  std::unique_ptr<FILE, decltype(&fclose)> file(fdopen(fd, "rb"), fclose);
  if (!file) {
    LOG_ERROR("testModuleFd() - fdopen failed for fd %d: %s", fd, strerror(errno));
    return JNI_FALSE;
  }

  xmp_test_info ti{};
  int res = xmp_test_module_from_file(file.get(), &ti);

  LOG_DEBUG("testModuleFd() - test result: %d", res);

  if (res == 0) {
    LOG_INFO("testModuleFd() - valid module: '%s' (type: %s)", ti.name, ti.type);

    jstring name = env->NewStringUTF(ti.name);
    jstring type = env->NewStringUTF(ti.type);

    if (!name || !type) {
      LOG_ERROR("testModuleFd() - failed to create Java strings");
      env->DeleteLocalRef(name);
      env->DeleteLocalRef(type);
      return JNI_FALSE;
    }

    env->SetObjectField(modInfo, mod_info.name, name);
    env->SetObjectField(modInfo, mod_info.type, type);
    env->DeleteLocalRef(name);
    env->DeleteLocalRef(type);

    LOG_DEBUG("testModuleFd() - successfully populated modInfo");
  } else {
    char path[PATH_MAX];
    ssize_t len = readlink(("/proc/self/fd/" + std::to_string(fd)).c_str(), path, sizeof(path) - 1);
    if (len > 0) path[len] = '\0';
    LOG_WARN("testModuleFd() - not a valid module, error: %d, file: %s", res, len > 0 ? path : "unknown");
  }

  return res == 0 ? JNI_TRUE : JNI_FALSE;
}

METHOD(jint, releaseModule)(JNIEnv* env, jobject obj) {
  XmpPlayerState& state = XmpPlayerState::instance();
  std::unique_lock<std::mutex> lock = state.lock();

  if (state.mod_is_loaded) {
    state.mod_is_loaded = false;
    xmp_release_module(state.ctx);
  }

  return 0;
}

METHOD(jint, startPlayer)(JNIEnv* env, jobject obj, jint rate, jint format) {
  XmpPlayerState& state = XmpPlayerState::instance();
  int use_rate = state.actual_rate > 0 ? state.actual_rate : rate;

  LOG_INFO("startPlayer() - requested: %d, using: %d, tid: %d", rate, use_rate, get_thread_id());

  std::unique_lock<std::mutex> lock = state.lock();
  LOG_DEBUG("startPlayer() - acquired lock");

  state.fi = std::make_unique<xmp_frame_info[]>(state.buffer_num);
  if (!state.fi) {
    LOG_ERROR("startPlayer() - failed to allocate frame info");
    return -101;
  }

  state.key.fill(-1);
  state.last_key.fill(-1);

  state.before = 0;
  state.now = 0;
  state.loop_count = 0;
  state.playing = true;

  int ret = xmp_start_player(state.ctx, use_rate, get_effective_format_flags());

  LOG_INFO("startPlayer() completed - result: %d, tid: %d", ret, get_thread_id());
  return ret;
}

METHOD(jint, endPlayer)(JNIEnv* env, jobject obj) {
  LOG_INFO("endPlayer() called - tid: %d", get_thread_id());

  XmpPlayerState& state = XmpPlayerState::instance();
  std::unique_lock<std::mutex> lock = state.lock();
  LOG_DEBUG("endPlayer() - acquired lock");

  if (state.playing) {
    state.playing = false;
    LOG_DEBUG("endPlayer() - stopping player");
    xmp_end_player(state.ctx);
    state.fi.reset();
    LOG_DEBUG("endPlayer() - freed frame info");
  }

  LOG_INFO("endPlayer() completed - tid: %d", get_thread_id());
  return 0;
}

METHOD(jint, playAudio)(JNIEnv* env, jobject obj) {
  return play_audio();
}

METHOD(void, dropAudio)(JNIEnv* env, jobject obj) {
  drop_audio();
}

METHOD(jboolean, stopAudio)(JNIEnv* env, jobject obj) {
  return stop_audio() == 0 ? JNI_TRUE : JNI_FALSE;
}

METHOD(jboolean, restartAudio)(JNIEnv* env, jobject obj) {
  return restart_audio() == 0 ? JNI_TRUE : JNI_FALSE;
}

METHOD(jboolean, hasFreeBuffer)(JNIEnv* env, jobject obj) {
  return has_free_buffer() ? JNI_TRUE : JNI_FALSE;
}

METHOD(jint, fillBuffer)(JNIEnv* env, jobject obj, jboolean looped) {
  return fill_buffer(looped);
}

METHOD(jint, nextPosition)(JNIEnv* env, jobject obj) {
  XmpPlayerState& state = XmpPlayerState::instance();
  return xmp_next_position(state.ctx);
}

METHOD(jint, prevPosition)(JNIEnv* env, jobject obj) {
  XmpPlayerState& state = XmpPlayerState::instance();
  return xmp_prev_position(state.ctx);
}

METHOD(jint, setPosition)(JNIEnv* env, jobject obj, jint n) {
  XmpPlayerState& state = XmpPlayerState::instance();
  return xmp_set_position(state.ctx, n);
}

METHOD(jint, stopModule)(JNIEnv* env, jobject obj) {
  XmpPlayerState& state = XmpPlayerState::instance();
  xmp_stop_module(state.ctx);
  return 0;
}

METHOD(jint, restartModule)(JNIEnv* env, jobject obj) {
  XmpPlayerState& state = XmpPlayerState::instance();
  xmp_restart_module(state.ctx);
  return 0;
}

METHOD(jint, seek)(JNIEnv* env, jobject obj, jint time) {
  XmpPlayerState& state = XmpPlayerState::instance();
  std::unique_lock<std::mutex> lock = state.lock();

  int ret = xmp_seek_time(state.ctx, time);

  if (state.playing) {
    xmp_frame_info* fi = state.fi.get();
    for (int i = 0; i < state.buffer_num; i++) {
      fi[i].time = time;
    }
  }

  return ret;
}

METHOD(jint, time)(JNIEnv* env, jobject obj) {
  XmpPlayerState& state = XmpPlayerState::instance();
  return state.playing ? state.fi.get()[state.before].time : -1;
}

METHOD(jint, mute)(JNIEnv* env, jobject obj, jint chn, jint status) {
  XmpPlayerState& state = XmpPlayerState::instance();
  return xmp_channel_mute(state.ctx, chn, status);
}

METHOD(void, getInfo)(JNIEnv* env, jobject obj, jobject frameInfo) {
  XmpPlayerState& state = XmpPlayerState::instance();

  if (!state.mod_is_loaded) return;

  std::unique_lock<std::mutex> lock = state.lock();

  if (state.playing) {
    const xmp_frame_info& fi = state.fi.get()[state.before];
    env->SetIntField(frameInfo, frame_info.pos, fi.pos);
    env->SetIntField(frameInfo, frame_info.pattern, fi.pattern);
    env->SetIntField(frameInfo, frame_info.row, fi.row);
    env->SetIntField(frameInfo, frame_info.numRows, fi.num_rows);
    env->SetIntField(frameInfo, frame_info.frame, fi.frame);
    env->SetIntField(frameInfo, frame_info.speed, fi.speed);
    env->SetIntField(frameInfo, frame_info.bpm, fi.bpm);
  }
}

METHOD(jint, setPlayerNative)(JNIEnv* env, jobject obj, jint parm, jint value) {
  XmpPlayerState& state = XmpPlayerState::instance();
  return xmp_set_player(state.ctx, parm, value);
}

METHOD(jint, getPlayer)(JNIEnv* env, jobject obj, jint parm) {
  XmpPlayerState& state = XmpPlayerState::instance();
  return xmp_get_player(state.ctx, parm);
}

METHOD(jint, getLoopCount)(JNIEnv* env, jobject obj) {
  XmpPlayerState& state = XmpPlayerState::instance();
  return state.fi.get()[state.before].loop_count;
}

METHOD(void, getModVars)(JNIEnv* env, jobject obj, jobject modVars) {
  XmpPlayerState& state = XmpPlayerState::instance();
  int tid = get_thread_id();

  if (!state.initialized) {
    LOG_WARN("getModVars() - early exit: not initialized - tid: %d", tid);
    return;
  }

  std::unique_lock<std::mutex> lock = state.lock();

  if (!state.mod_is_loaded) {
    LOG_WARN("getModVars() - module not loaded - tid: %d", tid);
    return;
  }

  const xmp_module_info& mi = state.mi;
  int seq = state.sequence;

  env->SetIntField(modVars, mod_vars.seqDuration, mi.seq_data[seq].duration);
  env->SetIntField(modVars, mod_vars.lengthInPatterns, mi.mod->len);
  env->SetIntField(modVars, mod_vars.numPatterns, mi.mod->pat);
  env->SetIntField(modVars, mod_vars.numChannels, mi.mod->chn);
  env->SetIntField(modVars, mod_vars.numInstruments, mi.mod->ins);
  env->SetIntField(modVars, mod_vars.numSamples, mi.mod->smp);
  env->SetIntField(modVars, mod_vars.numSequence, mi.num_sequences);
  env->SetIntField(modVars, mod_vars.currentSequence, seq);
}

METHOD(jstring, getVersion)(JNIEnv* env, jobject obj) {
  return env->NewStringUTF(xmp_version);
}

METHOD(jobjectArray, getFormats)(JNIEnv* env, jobject obj) {
  const char* const* list = xmp_get_format_list();
  int num = 0;
  while (list && list[num]) num++;

  jclass stringClass = env->FindClass("java/lang/String");
  jobjectArray result = env->NewObjectArray(num, stringClass, nullptr);
  for (int i = 0; i < num; i++) {
    jstring s = env->NewStringUTF(list[i]);
    env->SetObjectArrayElement(result, i, s);
    env->DeleteLocalRef(s);
  }
  env->DeleteLocalRef(stringClass);
  return result;
}

METHOD(jstring, getModName)(JNIEnv* env, jobject obj) {
  XmpPlayerState& state = XmpPlayerState::instance();
  const char* name = state.mod_is_loaded ? state.mi.mod->name : "";
  return env->NewStringUTF(name);
}

METHOD(jstring, getModType)(JNIEnv* env, jobject obj) {
  XmpPlayerState& state = XmpPlayerState::instance();
  const char* type = state.mod_is_loaded ? state.mi.mod->type : "";
  return env->NewStringUTF(type);
}

METHOD(jbyteArray, getComment)(JNIEnv* env, jobject obj) {
  XmpPlayerState& state = XmpPlayerState::instance();
  const xmp_module_info& mi = state.mi;

  if (!mi.comment) return env->NewByteArray(0);

  size_t length = strlen(mi.comment);
  jbyteArray byteArray = env->NewByteArray(static_cast<jsize>(length));
  env->SetByteArrayRegion(byteArray, 0, static_cast<jsize>(length), reinterpret_cast<const jbyte*>(mi.comment));
  return byteArray;
}

METHOD(jobjectArray, getInstruments)(JNIEnv* env, jobject obj) {
  XmpPlayerState& state = XmpPlayerState::instance();

  jclass stringClass = env->FindClass("java/lang/String");
  if (!stringClass) return nullptr;

  if (!state.mod_is_loaded) {
    jobjectArray empty = env->NewObjectArray(0, stringClass, nullptr);
    env->DeleteLocalRef(stringClass);
    return empty;
  }

  const xmp_module_info& mi = state.mi;

  jobjectArray stringArray = env->NewObjectArray(mi.mod->ins, stringClass, nullptr);
  if (!stringArray) {
    jobjectArray empty = env->NewObjectArray(0, stringClass, nullptr);
    env->DeleteLocalRef(stringClass);
    return empty;
  }

  for (int i = 0; i < mi.mod->ins; i++) {
    std::array<char, 64> buf{};
    snprintf(buf.data(), buf.size(), "%02X %s", i + 1, mi.mod->xxi[i].name);
    jstring s = env->NewStringUTF(buf.data());
    env->SetObjectArrayElement(stringArray, i, s);
    env->DeleteLocalRef(s);
  }

  env->DeleteLocalRef(stringClass);
  return stringArray;
}

METHOD(void, getChannelData)(JNIEnv* env, jobject obj, jobject channelInfo) {
  XmpPlayerState& state = XmpPlayerState::instance();
  int tid = get_thread_id();

  if (!state.initialized) {
    LOG_WARN("getChannelData() - early exit: not initialized - tid: %d", tid);
    return;
  }

  std::unique_lock<std::mutex> lock = state.lock();

  if (!state.mod_is_loaded || !state.playing) return;

  const xmp_module_info& mi = state.mi;
  int chn = mi.mod->chn;
  const xmp_frame_info& fi = state.fi.get()[state.before];

  auto& cur_vol = state.cur_vol;
  auto& final_vol = state.final_vol;
  auto& hold_vol = state.hold_vol;
  auto& ins = state.ins;
  auto& key = state.key;
  auto& last_key = state.last_key;
  auto& pan = state.pan;
  auto& period = state.period;

  for (int i = 0; i < chn; i++) {
    const xmp_channel_info& ci = fi.channel_info[i];

    if (ci.event.vol > 0) hold_vol[i] = ci.event.vol * 0x40 / mi.vol_base;

    cur_vol[i] -= state.decay;
    cur_vol[i] = std::max(0, cur_vol[i]);

    if (ci.event.note > 0 && ci.event.note <= 0x80) {
      key[i] = ci.event.note - 1;
      last_key[i] = key[i];
      if (xmp_subinstrument* sub = getSubinstrument(mi, ci.instrument, key[i])) {
        cur_vol[i] = sub->vol * 0x40 / mi.vol_base;
      }
    } else {
      key[i] = -1;
    }

    if (ci.event.vol > 0) {
      key[i] = last_key[i];
      cur_vol[i] = ci.event.vol * 0x40 / mi.vol_base;
    }

    ins[i] = static_cast<int>(static_cast<unsigned char>(ci.instrument));
    final_vol[i] = ci.volume;
    pan[i] = ci.pan;
    period[i] = static_cast<int>(ci.period) >> 8;
  }

  auto vol = (jintArray)env->GetObjectField(channelInfo, channel_info.volumes);
  auto finalVols = (jintArray)env->GetObjectField(channelInfo, channel_info.finalVols);
  auto pans = (jintArray)env->GetObjectField(channelInfo, channel_info.pans);
  auto instruments = (jintArray)env->GetObjectField(channelInfo, channel_info.instruments);
  auto keys = (jintArray)env->GetObjectField(channelInfo, channel_info.keys);
  auto periods = (jintArray)env->GetObjectField(channelInfo, channel_info.periods);
  auto holdVols = (jintArray)env->GetObjectField(channelInfo, channel_info.holdVols);

  env->SetIntArrayRegion(vol, 0, chn, cur_vol.data());
  env->SetIntArrayRegion(finalVols, 0, chn, final_vol.data());
  env->SetIntArrayRegion(pans, 0, chn, pan.data());
  env->SetIntArrayRegion(instruments, 0, chn, ins.data());
  env->SetIntArrayRegion(keys, 0, chn, key.data());
  env->SetIntArrayRegion(periods, 0, chn, period.data());
  env->SetIntArrayRegion(holdVols, 0, chn, hold_vol.data());
}

METHOD(jint, getPatternRows)(JNIEnv* env, jobject obj, jint pat) {
  XmpPlayerState& state = XmpPlayerState::instance();
  if (!state.mod_is_loaded) return 0;

  const xmp_module_info& mi = state.mi;
  if (pat < 0 || pat >= mi.mod->pat) return 0;

  const xmp_pattern* xxp = mi.mod->xxp[pat];
  return xxp ? xxp->rows : 0;
}

METHOD(void, getPatternRow)(JNIEnv* env, jobject obj, jint pat, jint row, jbyteArray rowNotes, jbyteArray rowInstruments, jbyteArray rowFxType, jbyteArray rowFxParm) {
  XmpPlayerState& state = XmpPlayerState::instance();

  if (!state.mod_is_loaded) return;

  const xmp_module_info& mi = state.mi;
  if (pat < 0 || pat >= mi.mod->pat) return;

  const xmp_pattern* xxp = mi.mod->xxp[pat];
  if (!xxp || row >= xxp->rows) return;

  int chn = mi.mod->chn;

  std::array<jbyte, XMP_MAX_CHANNELS> row_note{};
  std::array<jbyte, XMP_MAX_CHANNELS> row_ins{};
  std::array<jbyte, XMP_MAX_CHANNELS> row_fxt{};
  std::array<jbyte, XMP_MAX_CHANNELS> row_fxp{};

  for (int i = 0; i < chn; i++) {
    const xmp_track* xxt = mi.mod->xxt[xxp->index[i]];
    const xmp_event& e = xxt->event[row];

    row_note[i] = static_cast<jbyte>(e.note);
    row_ins[i] = static_cast<jbyte>(e.ins);

    if (e.fxt > 0) { // NOLINT(*-branch-clone)
      row_fxt[i] = static_cast<jbyte>(e.fxt);
      row_fxp[i] = static_cast<jbyte>(e.fxp);
    } else if (e.f2t > 0) {
      row_fxt[i] = static_cast<jbyte>(e.f2t);
      row_fxp[i] = static_cast<jbyte>(e.f2p);
    } else if (e.fxt == 0 && e.fxp > 0) {
      // Likely Arpeggio
      row_fxt[i] = static_cast<jbyte>(e.fxt);
      row_fxp[i] = static_cast<jbyte>(e.fxp);
    } else {
      row_fxt[i] = -1;
      row_fxp[i] = -1;
    }
  }

  env->SetByteArrayRegion(rowNotes, 0, chn, row_note.data());
  env->SetByteArrayRegion(rowInstruments, 0, chn, row_ins.data());
  env->SetByteArrayRegion(rowFxType, 0, chn, row_fxt.data());
  env->SetByteArrayRegion(rowFxParm, 0, chn, row_fxp.data());
}

METHOD(void, getSampleData)(JNIEnv* env, jobject obj, jboolean trigger, jint ins, jint key, jint period, jint chn, jint width, jbyteArray buffer) {
  XmpPlayerState& state = XmpPlayerState::instance();

  // Clamp before any early exit — keeps SetByteArrayRegion in bounds for every path.
  width = std::min(width, MAX_BUFFER_SIZE);

  // Zero-init covers the non-loop tail and all early-return paths without separate fills.
  std::array<jbyte, MAX_BUFFER_SIZE> sample_buffer{};

  // Compute under lock, then write to the Java array outside — JNI array writes
  // must not be made while holding a native mutex (GC pin/unpin may be needed).
  [&]() {
    std::unique_lock<std::mutex> lock = state.lock();

    if (!state.mod_is_loaded) return;
    if (chn < 0 || chn >= XMP_MAX_CHANNELS) return;
    if (period <= 0 || ins < 0 || key < 0 || key >= XMP_MAX_KEYS) return;

    const xmp_module_info& mi = state.mi;
    if (ins >= mi.mod->ins) return;

    xmp_subinstrument* sub = getSubinstrument(mi, ins, key);
    if (!sub || sub->sid < 0 || sub->sid >= mi.mod->smp) return;

    const xmp_sample* xxs = &mi.mod->xxs[sub->sid];
    if (xxs->flg & XMP_SAMPLE_SYNTH || xxs->len == 0 || !xxs->data) return;

    int step = (PERIOD_BASE << 4) / period;
    int lps = xxs->lps << 5;
    int lpe = xxs->lpe << 5;

    int current_pos = state.pos[chn];
    if (trigger == JNI_TRUE || (current_pos >> 5) >= xxs->len) current_pos = 0;

    int transient_size = 0;
    if (step > 0) {
      transient_size = (xxs->flg & XMP_SAMPLE_LOOP) ? (lps - current_pos) / step : ((xxs->len << 5) - current_pos) / step;
    }
    int limit = std::min(width, std::max(0, transient_size));

    if (xxs->flg & XMP_SAMPLE_16BIT) {
      const auto* data = reinterpret_cast<const short*>(xxs->data);
      for (int i = 0; i < limit; i++) {
        sample_buffer[i] = static_cast<jbyte>(data[current_pos >> 5] >> 8);
        current_pos += step;
      }
      if (xxs->flg & XMP_SAMPLE_LOOP) {
        for (int i = limit; i < width; i++) {
          sample_buffer[i] = static_cast<jbyte>(data[current_pos >> 5] >> 8);
          current_pos += step;
          if (current_pos >= lpe) {
            current_pos = lps + current_pos - lpe;
            if (current_pos >= lpe) current_pos = lps;
          }
        }
      }
    } else {
      const auto* data = reinterpret_cast<const jbyte*>(xxs->data);
      for (int i = 0; i < limit; i++) {
        sample_buffer[i] = data[current_pos >> 5];
        current_pos += step;
      }
      if (xxs->flg & XMP_SAMPLE_LOOP) {
        for (int i = limit; i < width; i++) {
          sample_buffer[i] = data[current_pos >> 5];
          current_pos += step;
          if (current_pos >= lpe) {
            current_pos = lps + current_pos - lpe;
            if (current_pos >= lpe) current_pos = lps;
          }
        }
      }
    }

    state.pos[chn] = current_pos;
  }();

  env->SetByteArrayRegion(buffer, 0, width, sample_buffer.data());
}

METHOD(jboolean, setSequence)(JNIEnv* env, jobject obj, jint seq) {
  XmpPlayerState& state = XmpPlayerState::instance();
  const xmp_module_info& mi = state.mi;

  if (seq >= mi.num_sequences) return JNI_FALSE;
  if (mi.seq_data[seq].duration <= 0) return JNI_FALSE;
  if (state.sequence == seq) return JNI_FALSE;

  state.sequence = seq;
  state.loop_count = 0;

  xmp_set_position(state.ctx, mi.seq_data[seq].entry_point);
  xmp_play_buffer(state.ctx, nullptr, 0, 0);

  return JNI_TRUE;
}

METHOD(jint, getMaxSequences)(JNIEnv* env, jobject obj) {
  return MAX_SEQUENCES;
}

METHOD(jintArray, getSeqVars)(JNIEnv* env, jobject obj) {
  XmpPlayerState& state = XmpPlayerState::instance();

  if (!state.mod_is_loaded) return env->NewIntArray(0);

  const xmp_module_info& mi = state.mi;
  int num = mi.num_sequences;

  if (num <= 0) return env->NewIntArray(0);

  jintArray result = env->NewIntArray(num);
  if (!result) return env->NewIntArray(0);

  std::vector<jint> durations(num);
  for (int i = 0; i < num; i++) durations[i] = mi.seq_data[i].duration;
  env->SetIntArrayRegion(result, 0, num, durations.data());

  return result;
}

METHOD(void, setExpectSilence)(JNIEnv* env, jobject obj, jboolean value) {
  set_expect_silence(value == JNI_TRUE ? 1 : 0);
}

METHOD(void, getAudioStats)(JNIEnv* env, jobject obj, jobject info) {
  struct AudioStats stats{};
  get_audio_stats(&stats);

  env->SetIntField(info, audio_stats.xrunCount, stats.xrun_count);
  env->SetIntField(info, audio_stats.underrunCount, stats.underrun_count);
  env->SetIntField(info, audio_stats.framesPerBurst, stats.frames_per_burst);
  env->SetIntField(info, audio_stats.bufferCapacity, stats.buffer_capacity);
  env->SetIntField(info, audio_stats.bufferSize, stats.buffer_size);
  env->SetIntField(info, audio_stats.sampleRate, stats.sample_rate);

  jstring apiStr = env->NewStringUTF(stats.audio_api ? stats.audio_api : "");
  jstring modeStr = env->NewStringUTF(stats.sharing_mode ? stats.sharing_mode : "");
  jstring perfStr = env->NewStringUTF(stats.perf_mode ? stats.perf_mode : "");
  jstring formatStr = env->NewStringUTF(stats.audio_format ? stats.audio_format : "");

  env->SetObjectField(info, audio_stats.audioApi, apiStr);
  env->SetObjectField(info, audio_stats.sharingMode, modeStr);
  env->SetObjectField(info, audio_stats.perfMode, perfStr);
  env->SetObjectField(info, audio_stats.audioFormat, formatStr);

  env->DeleteLocalRef(apiStr);
  env->DeleteLocalRef(modeStr);
  env->DeleteLocalRef(perfStr);
  env->DeleteLocalRef(formatStr);
}

} // extern "C"
