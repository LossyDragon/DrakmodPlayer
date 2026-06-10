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

// Text sanitizer for charsets like CP437.
inline std::string sanitizeForJni(const char* src) {
  if (!src) return "";
  std::string out;
  out.reserve(strlen(src));
  for (const auto* p = reinterpret_cast<const unsigned char*>(src); *p; ++p) {
    out += (*p < 0x80) ? static_cast<char>(*p) : '?';
  }
  return out;
}

extern "C" {

constexpr const char* xmpErrorString(int code) {
  constexpr std::array<const char*, 9> table = {
    "",                     // 0 unused
    "End of module",        // XMP_END = 1
    "Internal error",       // XMP_ERROR_INTERNAL = 2
    "Unsupported format",   // XMP_ERROR_FORMAT = 3
    "Error loading file",   // XMP_ERROR_LOAD = 4
    "Error depacking file", // XMP_ERROR_DEPACK = 5
    "System error",         // XMP_ERROR_SYSTEM = 6
    "Invalid parameter",    // XMP_ERROR_INVALID = 7
    "Invalid player state", // XMP_ERROR_STATE = 8
  };
  int idx = std::abs(code);
  if (idx >= static_cast<int>(table.size())) return "Unknown error";
  return table[idx];
}

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
  jfieldID positions = nullptr;
  jfieldID pitchbends = nullptr;
  jfieldID notes = nullptr;
  jfieldID samples = nullptr;
} channel_info;

static struct mod_vars {
  jclass clazz = nullptr;
  jfieldID name = nullptr;
  jfieldID type = nullptr;
  jfieldID pat = nullptr;
  jfieldID trk = nullptr;
  jfieldID chn = nullptr;
  jfieldID ins = nullptr;
  jfieldID smp = nullptr;
  jfieldID spd = nullptr;
  jfieldID bpm = nullptr;
  jfieldID len = nullptr;
  jfieldID rst = nullptr;
  jfieldID gvl = nullptr;
  jfieldID miNumSequences = nullptr;
  jfieldID miComment = nullptr;
  jfieldID seqData = nullptr;
  jfieldID instruments = nullptr;
} mod_vars;

static struct sequence_info {
  jclass clazz = nullptr;
  jfieldID entryPoint = nullptr;
  jfieldID duration = nullptr;
} sequence_info;

static struct frame_info {
  jclass clazz = nullptr;
  jfieldID pos = nullptr;
  jfieldID pattern = nullptr;
  jfieldID row = nullptr;
  jfieldID numRows = nullptr;
  jfieldID frame = nullptr;
  jfieldID speed = nullptr;
  jfieldID bpm = nullptr;
  jfieldID time = nullptr;
  jfieldID totalTime = nullptr;
  jfieldID frameTime = nullptr;
  jfieldID bufferSize = nullptr;
  jfieldID totalSize = nullptr;
  jfieldID volume = nullptr;
  jfieldID loopCount = nullptr;
  jfieldID virtChannels = nullptr;
  jfieldID virtUsed = nullptr;
  jfieldID sequence = nullptr;
} frame_info;

namespace {
  constexpr int MAX_BUFFER_SIZE = 1024;
  constexpr int PERIOD_BASE = 13696;

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

    std::mutex mutex;
    bool initialized = false;
    bool mod_is_loaded = false;
    bool playing = false;
    int actual_rate = 0;
    int sequence = 0;
    int decay = 4;

    xmp_context ctx = nullptr;
    xmp_module_info mi{};

    std::array<int, XMP_MAX_CHANNELS> cur_vol{};
    std::array<int, XMP_MAX_CHANNELS> hold_vol{};
    std::array<int, XMP_MAX_CHANNELS> last_key{};
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
    GET_FIELD(channel_info, positions, "[I");
    GET_FIELD(channel_info, pitchbends, "[I");
    GET_FIELD(channel_info, notes, "[I");
    GET_FIELD(channel_info, samples, "[I");
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
    GET_FIELD(frame_info, time, "I");
    GET_FIELD(frame_info, totalTime, "I");
    GET_FIELD(frame_info, frameTime, "I");
    GET_FIELD(frame_info, bufferSize, "I");
    GET_FIELD(frame_info, totalSize, "I");
    GET_FIELD(frame_info, volume, "I");
    GET_FIELD(frame_info, loopCount, "I");
    GET_FIELD(frame_info, virtChannels, "I");
    GET_FIELD(frame_info, virtUsed, "I");
    GET_FIELD(frame_info, sequence, "I");
  }

  void initModInfoFields(JNIEnv* env) {
    if (mod_info.clazz != nullptr) return;
    GET_CLASS(mod_info, "org/helllabs/libxmp/model/ModInfo");
    GET_FIELD(mod_info, name, "Ljava/lang/String;");
    GET_FIELD(mod_info, type, "Ljava/lang/String;");
  }

  void initSequenceFields(JNIEnv* env) {
    if (sequence_info.clazz != nullptr) return;
    GET_CLASS(sequence_info, "org/helllabs/libxmp/model/Sequence");
    GET_FIELD(sequence_info, entryPoint, "I");
    GET_FIELD(sequence_info, duration, "I");
  }

  void initModVarsFields(JNIEnv* env) {
    if (mod_vars.clazz != nullptr) return;
    GET_CLASS(mod_vars, "org/helllabs/libxmp/model/ModVars");
    GET_FIELD(mod_vars, name, "Ljava/lang/String;");
    GET_FIELD(mod_vars, type, "Ljava/lang/String;");
    GET_FIELD(mod_vars, pat, "I");
    GET_FIELD(mod_vars, trk, "I");
    GET_FIELD(mod_vars, chn, "I");
    GET_FIELD(mod_vars, ins, "I");
    GET_FIELD(mod_vars, smp, "I");
    GET_FIELD(mod_vars, spd, "I");
    GET_FIELD(mod_vars, bpm, "I");
    GET_FIELD(mod_vars, len, "I");
    GET_FIELD(mod_vars, rst, "I");
    GET_FIELD(mod_vars, gvl, "I");
    GET_FIELD(mod_vars, miNumSequences, "I");
    GET_FIELD(mod_vars, miComment, "Ljava/lang/String;");
    GET_FIELD(mod_vars, seqData, "[Lorg/helllabs/libxmp/model/Sequence;");
    GET_FIELD(mod_vars, instruments, "[Ljava/lang/String;");
  }

  xmp_subinstrument* getSubinstrument(const xmp_module_info& mi, int ins, int key) {
    if (ins < 0 || ins >= mi.mod->ins || key < 0 || key >= XMP_MAX_KEYS) return nullptr;
    if (mi.mod->xxi[ins].map[key].ins == 0xff) return nullptr;

    int mapped = mi.mod->xxi[ins].map[key].ins;
    if (mapped < 0 || mapped >= mi.mod->xxi[ins].nsm) return nullptr;

    return &mi.mod->xxi[ins].sub[mapped];
  }

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
  set_xmp_context(ctx);

  if (actual_rate != rate) {
    LOG_INFO("init() - sample rate adjusted: requested=%d, actual=%d", rate, actual_rate);
  }

  initAudioStatsFields(env);
  initChannelInfoFields(env);
  initFrameInfoFields(env);
  initModVarsFields(env);
  initSequenceFields(env);

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
    set_playing(0); // silence callback and drain any in-flight invocation
    set_xmp_context(nullptr);
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
  initModInfoFields(env);
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

  jstring name = env->NewStringUTF(sanitizeForJni(ti.name).c_str());
  jstring type = env->NewStringUTF(sanitizeForJni(ti.type).c_str());
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
  initModInfoFields(env);

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

    jstring name = env->NewStringUTF(sanitizeForJni(ti.name).c_str());
    jstring type = env->NewStringUTF(sanitizeForJni(ti.type).c_str());

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

  state.last_key.fill(-1);
  state.playing = true;

  reset_render_callback();
  int ret = xmp_start_player(state.ctx, use_rate, get_effective_format_flags());
  if (ret == 0) {
    set_playing(1); // tell callback to start calling xmp_play_buffer
  } else {
    state.playing = false;
  }

  LOG_INFO("startPlayer() completed - result: %d, tid: %d", ret, get_thread_id());
  return ret;
}

METHOD(jint, endPlayer)(JNIEnv* env, jobject obj) {
  LOG_INFO("endPlayer() called - tid: %d", get_thread_id());

  XmpPlayerState& state = XmpPlayerState::instance();
  std::unique_lock<std::mutex> lock = state.lock();
  LOG_DEBUG("endPlayer() - acquired lock");

  if (state.playing) {
    set_playing(0); // silence callback and wait for in-flight invocation to finish
    state.playing = false;
    LOG_DEBUG("endPlayer() - stopping player");
    xmp_end_player(state.ctx);
  }

  LOG_INFO("endPlayer() completed - tid: %d", get_thread_id());
  return 0;
}

METHOD(jint, playAudio)(JNIEnv* env, jobject obj) {
  return play_audio();
}

METHOD(jboolean, stopAudio)(JNIEnv* env, jobject obj) {
  return stop_audio() == 0 ? JNI_TRUE : JNI_FALSE;
}

METHOD(jboolean, restartAudio)(JNIEnv* env, jobject obj) {
  return restart_audio() == 0 ? JNI_TRUE : JNI_FALSE;
}

METHOD(jboolean, hasModuleEnded)(JNIEnv* env, jobject obj) {
  return has_module_ended() ? JNI_TRUE : JNI_FALSE;
}

METHOD(void, setXmpPlaying)(JNIEnv* env, jobject obj, jboolean value) {
  set_playing(value == JNI_TRUE ? 1 : 0);
}

METHOD(void, setLoopMode)(JNIEnv* env, jobject obj, jboolean loop) {
  // loop=true  → xmp_play_buffer loop=0 (loop forever, repeat-one)
  // loop=false → xmp_play_buffer loop=1 (play once, signal end)
  set_loop_mode(loop == JNI_TRUE ? 0 : 1);
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
  return xmp_seek_time(state.ctx, time);
}

METHOD(jint, time)(JNIEnv* env, jobject obj) {
  XmpPlayerState& state = XmpPlayerState::instance();
  if (!state.playing) return -1;
  xmp_frame_info fi = {};
  xmp_get_frame_info(state.ctx, &fi);
  return fi.time;
}

METHOD(jint, mute)(JNIEnv* env, jobject obj, jint chn, jint status) {
  XmpPlayerState& state = XmpPlayerState::instance();
  return xmp_channel_mute(state.ctx, chn, status);
}

METHOD(void, getFrameInfo)(JNIEnv* env, jobject obj, jobject frameInfo) {
  XmpPlayerState& state = XmpPlayerState::instance();

  if (!state.initialized) return;

  struct Snap {
    int pos, pattern, row, numRows, frame, speed, bpm, time, totalTime;
    int frameTime, bufferSize, totalSize, volume, loopCount, virtChannels, virtUsed, sequence;
  } s{};

  {
    std::unique_lock<std::mutex> lock = state.lock();
    if (!state.mod_is_loaded || !state.playing) return;
    xmp_frame_info fi = {};
    xmp_get_frame_info(state.ctx, &fi);
    s = {fi.pos,
      fi.pattern,
      fi.row,
      fi.num_rows,
      fi.frame,
      fi.speed,
      fi.bpm,
      fi.time,
      fi.total_time,
      fi.frame_time,
      fi.buffer_size,
      fi.total_size,
      fi.volume,
      fi.loop_count,
      fi.virt_channels,
      fi.virt_used,
      fi.sequence};
  }

  env->SetIntField(frameInfo, frame_info.pos, s.pos);
  env->SetIntField(frameInfo, frame_info.pattern, s.pattern);
  env->SetIntField(frameInfo, frame_info.row, s.row);
  env->SetIntField(frameInfo, frame_info.numRows, s.numRows);
  env->SetIntField(frameInfo, frame_info.frame, s.frame);
  env->SetIntField(frameInfo, frame_info.speed, s.speed);
  env->SetIntField(frameInfo, frame_info.bpm, s.bpm);
  env->SetIntField(frameInfo, frame_info.time, s.time);
  env->SetIntField(frameInfo, frame_info.totalTime, s.totalTime);
  env->SetIntField(frameInfo, frame_info.frameTime, s.frameTime);
  env->SetIntField(frameInfo, frame_info.bufferSize, s.bufferSize);
  env->SetIntField(frameInfo, frame_info.totalSize, s.totalSize);
  env->SetIntField(frameInfo, frame_info.volume, s.volume);
  env->SetIntField(frameInfo, frame_info.loopCount, s.loopCount);
  env->SetIntField(frameInfo, frame_info.virtChannels, s.virtChannels);
  env->SetIntField(frameInfo, frame_info.virtUsed, s.virtUsed);
  env->SetIntField(frameInfo, frame_info.sequence, s.sequence);
}

METHOD(jint, setPlayer)(JNIEnv* env, jobject obj, jint parm, jint value) {
  XmpPlayerState& state = XmpPlayerState::instance();
  auto res = xmp_set_player(state.ctx, parm, value);
  if (res != 0) LOG_WARN("xmp_play_buffer: %s (%d)", xmpErrorString(res), res);
  return res;
}

METHOD(jint, getPlayer)(JNIEnv* env, jobject obj, jint parm) {
  XmpPlayerState& state = XmpPlayerState::instance();
  return xmp_get_player(state.ctx, parm);
}

METHOD(void, getModVars)(JNIEnv* env, jobject obj, jobject modVars) {
  XmpPlayerState& state = XmpPlayerState::instance();

  if (!state.initialized) {
    LOG_WARN("getModVars() - early exit: not initialized - tid: %d", get_thread_id());
    return;
  }

  std::string modName, modType, modComment;
  int pat, trk, chn, ins, smp, spd, bpm, len, rst, gvl, numSeq;
  struct SeqEntry {
    int entryPoint, duration;
  };
  std::vector<SeqEntry> seqs;
  std::vector<std::string> instrNames;

  {
    std::unique_lock<std::mutex> lock = state.lock();
    if (!state.mod_is_loaded) {
      LOG_WARN("getModVars() - module not loaded - tid: %d", get_thread_id());
      return;
    }
    const xmp_module_info& mi = state.mi;
    modName = sanitizeForJni(mi.mod->name);
    modType = sanitizeForJni(mi.mod->type);
    modComment = sanitizeForJni(mi.comment);
    pat = mi.mod->pat;
    trk = mi.mod->trk;
    chn = mi.mod->chn;
    ins = mi.mod->ins;
    smp = mi.mod->smp;
    spd = mi.mod->spd;
    bpm = mi.mod->bpm;
    len = mi.mod->len;
    rst = mi.mod->rst;
    gvl = mi.mod->gvl;
    numSeq = mi.num_sequences;
    if (mi.seq_data) {
      seqs.reserve(numSeq);
      for (int i = 0; i < numSeq; i++) seqs.push_back({mi.seq_data[i].entry_point, mi.seq_data[i].duration});
    }
    instrNames.reserve(ins);
    for (int i = 0; i < ins; i++) {
      std::array<char, 64> buf{};
      snprintf(buf.data(), buf.size(), "%02X %s", i + 1, sanitizeForJni(mi.mod->xxi[i].name).c_str());
      instrNames.emplace_back(buf.data());
    }
  }

  jstring nameStr = env->NewStringUTF(modName.c_str());
  jstring typeStr = env->NewStringUTF(modType.c_str());
  jstring commentStr = env->NewStringUTF(modComment.c_str());

  env->SetObjectField(modVars, mod_vars.name, nameStr);
  env->SetObjectField(modVars, mod_vars.type, typeStr);
  env->SetIntField(modVars, mod_vars.pat, pat);
  env->SetIntField(modVars, mod_vars.trk, trk);
  env->SetIntField(modVars, mod_vars.chn, chn);
  env->SetIntField(modVars, mod_vars.ins, ins);
  env->SetIntField(modVars, mod_vars.smp, smp);
  env->SetIntField(modVars, mod_vars.spd, spd);
  env->SetIntField(modVars, mod_vars.bpm, bpm);
  env->SetIntField(modVars, mod_vars.len, len);
  env->SetIntField(modVars, mod_vars.rst, rst);
  env->SetIntField(modVars, mod_vars.gvl, gvl);
  env->SetIntField(modVars, mod_vars.miNumSequences, numSeq);
  env->SetObjectField(modVars, mod_vars.miComment, commentStr);

  jclass seqCls = env->FindClass("org/helllabs/libxmp/model/Sequence");
  jobjectArray seqArray = env->NewObjectArray((jsize)seqs.size(), seqCls, nullptr);
  for (int i = 0; i < (int)seqs.size(); i++) {
    jobject seqObj = env->AllocObject(seqCls);
    env->SetIntField(seqObj, sequence_info.entryPoint, seqs[i].entryPoint);
    env->SetIntField(seqObj, sequence_info.duration, seqs[i].duration);
    env->SetObjectArrayElement(seqArray, i, seqObj);
    env->DeleteLocalRef(seqObj);
  }
  env->SetObjectField(modVars, mod_vars.seqData, seqArray);

  jclass stringClass = env->FindClass("java/lang/String");
  jobjectArray insArray = env->NewObjectArray(ins, stringClass, nullptr);
  for (int i = 0; i < ins; i++) {
    jstring s = env->NewStringUTF(instrNames[i].c_str());
    env->SetObjectArrayElement(insArray, i, s);
    env->DeleteLocalRef(s);
  }
  env->SetObjectField(modVars, mod_vars.instruments, insArray);

  env->DeleteLocalRef(nameStr);
  env->DeleteLocalRef(typeStr);
  env->DeleteLocalRef(commentStr);
  env->DeleteLocalRef(seqArray);
  env->DeleteLocalRef(seqCls);
  env->DeleteLocalRef(insArray);
  env->DeleteLocalRef(stringClass);
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

METHOD(void, getChannelData)(JNIEnv* env, jobject obj, jobject channelInfo) {
  XmpPlayerState& state = XmpPlayerState::instance();

  if (!state.initialized) {
    LOG_WARN("getChannelData() - early exit: not initialized - tid: %d", get_thread_id());
    return;
  }

  int chn = 0;
  std::array<int, XMP_MAX_CHANNELS> lVol{}, lFinalVol{}, lPan{}, lIns{};
  std::array<int, XMP_MAX_CHANNELS> lKey{}, lPeriod{}, lHoldVol{};
  std::array<int, XMP_MAX_CHANNELS> lPosition{}, lPitchbend{}, lNote{}, lSample{};

  {
    std::unique_lock<std::mutex> lock = state.lock();
    if (!state.mod_is_loaded || !state.playing) return;

    const xmp_module_info& mi = state.mi;
    chn = mi.mod->chn;
    xmp_frame_info fi = {};
    xmp_get_frame_info(state.ctx, &fi);

    for (int i = 0; i < chn; i++) {
      const xmp_channel_info& ci = fi.channel_info[i];

      if (ci.event.vol > 0) state.hold_vol[i] = ci.event.vol * 0x40 / mi.vol_base;

      state.cur_vol[i] = std::max(0, state.cur_vol[i] - state.decay);

      int key = -1;
      if (ci.event.note > 0 && ci.event.note <= 0x80) {
        key = ci.event.note - 1;
        state.last_key[i] = key;
        if (xmp_subinstrument* sub = getSubinstrument(mi, ci.instrument, key)) state.cur_vol[i] = sub->vol * 0x40 / mi.vol_base;
      }
      if (ci.event.vol > 0) {
        key = state.last_key[i];
        state.cur_vol[i] = ci.event.vol * 0x40 / mi.vol_base;
      }

      lVol[i] = state.cur_vol[i];
      lHoldVol[i] = state.hold_vol[i];
      lKey[i] = key;
      lIns[i] = static_cast<unsigned char>(ci.instrument);
      lFinalVol[i] = ci.volume;
      lPan[i] = ci.pan;
      lPeriod[i] = static_cast<int>(ci.period) >> 8;
      lPosition[i] = static_cast<int>(ci.position);
      lPitchbend[i] = static_cast<int>(ci.pitchbend);
      lNote[i] = static_cast<int>(ci.note);
      lSample[i] = static_cast<int>(ci.sample);
    }
  }

  auto set = [&](jfieldID fid, const std::array<int, XMP_MAX_CHANNELS>& arr) {
    auto obj = (jintArray)env->GetObjectField(channelInfo, fid);
    env->SetIntArrayRegion(obj, 0, chn, arr.data());
  };

  set(channel_info.volumes, lVol);
  set(channel_info.finalVols, lFinalVol);
  set(channel_info.pans, lPan);
  set(channel_info.instruments, lIns);
  set(channel_info.keys, lKey);
  set(channel_info.periods, lPeriod);
  set(channel_info.holdVols, lHoldVol);
  set(channel_info.positions, lPosition);
  set(channel_info.pitchbends, lPitchbend);
  set(channel_info.notes, lNote);
  set(channel_info.samples, lSample);
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

  width = std::min(width, MAX_BUFFER_SIZE);

  std::array<jbyte, MAX_BUFFER_SIZE> sample_buffer{};

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

  set_playing(0);
  state.sequence = seq;
  xmp_set_position(state.ctx, mi.seq_data[seq].entry_point);
  xmp_play_buffer(state.ctx, nullptr, 0, 0);
  set_playing(1);

  return JNI_TRUE;
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
