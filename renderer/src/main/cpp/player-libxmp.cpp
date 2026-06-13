//
// Created by Lossy on 6/10/2026.
//

#include "audio.h"
#include "common.h"
#include "player-common.h"
#include "player.h"
#include "xmp.h"
#include <algorithm>
#include <android/log.h>
#include <array>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <jni.h>
#include <memory>
#include <mutex>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

constexpr const char* TAG = "Drakplayer (libxmp) JNI";
#define LOG_DEBUG(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOG_INFO(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOG_WARN(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOG_ERROR(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

constexpr int MAX_BUFFER_SIZE = 1024;
constexpr int PERIOD_BASE = 13696;

xmp_subinstrument* getSubinstrument(const xmp_module_info& mi, int ins, int key) {
  if (ins < 0 || ins >= mi.mod->ins || key < 0 || key >= XMP_MAX_KEYS) return nullptr;
  if (mi.mod->xxi[ins].map[key].ins == 0xff) return nullptr;

  int mapped = mi.mod->xxi[ins].map[key].ins;
  if (mapped < 0 || mapped >= mi.mod->xxi[ins].nsm) return nullptr;

  return &mi.mod->xxi[ins].sub[mapped];
}

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

jboolean xmp_hasEnded(JNIEnv* env) {
  return has_module_ended() ? JNI_TRUE : JNI_FALSE;
}

jboolean xmp_init(JNIEnv* env, jint rate, jint ms, jint mode, jint channels, jint api, jint flags) {
  LOG_INFO("init() called - rate: %d, ms: %d, tid: %d", rate, ms, gettid());

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

jboolean xmp_restartAudio(JNIEnv* env) {
  return restart_audio() == 0 ? JNI_TRUE : JNI_FALSE;
}

jboolean xmp_setSequence(JNIEnv* env, jint seq) {
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

jboolean xmp_stopAudio(JNIEnv* env) {
  return stop_audio() == 0 ? JNI_TRUE : JNI_FALSE;
}

jboolean xmp_testFd(JNIEnv* env, jint fd, jobject modInfo) {
  LOG_INFO("testModuleFd() called - fd: %d, tid: %d", fd, gettid());
  initModInfoFields(env);

  lseek(fd, 0, SEEK_SET);
  std::vector<unsigned char> data = readFd(fd);
  if (data.empty()) {
    LOG_ERROR("testModuleFd() - failed to read fd %d", fd);
    return JNI_FALSE;
  }

  xmp_test_info ti{};
  int res = xmp_test_module_from_memory(data.data(), static_cast<long>(data.size()), &ti);

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

    env->SetObjectField(modInfo, g_modInfo.name, name);
    env->SetObjectField(modInfo, g_modInfo.type, type);
    env->DeleteLocalRef(name);
    env->DeleteLocalRef(type);

    LOG_DEBUG("testModuleFd() - successfully populated modInfo");
  } else {
    LOG_WARN("testModuleFd() - not a valid module, error: %d, fd: %d", res, fd);
  }

  return res == 0 ? JNI_TRUE : JNI_FALSE;
}

jint xmp_deinit(JNIEnv* env) {
  LOG_INFO("deinit() called - tid: %d", gettid());

  XmpPlayerState& state = XmpPlayerState::instance();

  {
    std::unique_lock<std::mutex> lock = state.lock();
    LOG_DEBUG("deinit() - acquired lock");
    state.initialized = false;
    state.mod_is_loaded = false;
    set_playing(0); // silence callback and drain any in-flight invocation
    set_xmp_context(nullptr);
    xmp_free_context(state.ctx);
    state.ctx = nullptr;
    LOG_DEBUG("deinit() - freed context");
  }
  LOG_DEBUG("deinit() - released lock");

  close_audio();
  LOG_INFO("deinit() completed - tid: %d", gettid());

  return 0;
}

jint xmp_endPlayer(JNIEnv* env) {
  LOG_INFO("endPlayer() called - tid: %d", gettid());

  XmpPlayerState& state = XmpPlayerState::instance();
  std::unique_lock<std::mutex> lock = state.lock();
  LOG_DEBUG("endPlayer() - acquired lock");

  if (state.playing) {
    set_playing(0); // silence callback and wait for in-flight invocation to finish
    state.playing = false;
    LOG_DEBUG("endPlayer() - stopping player");
    xmp_end_player(state.ctx);
  }

  LOG_INFO("endPlayer() completed - tid: %d", gettid());
  return 0;
}

jint xmp_getPatternRows(JNIEnv* env, jint pat) {
  XmpPlayerState& state = XmpPlayerState::instance();
  if (!state.mod_is_loaded) return 0;

  const xmp_module_info& mi = state.mi;
  if (pat < 0 || pat >= mi.mod->pat) return 0;

  const xmp_pattern* xxp = mi.mod->xxp[pat];
  return xxp ? xxp->rows : 0;
}

jint xmp_getPlayer(JNIEnv* env, jint parm) {
  XmpPlayerState& state = XmpPlayerState::instance();
  return xmp_get_player(state.ctx, parm);
}

jint xmp_loadFd(JNIEnv* env, jint fd, jobject modInfo) {
  LOG_INFO("loadModuleFd() called - fd: %d, tid: %d", fd, gettid());
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
  env->SetObjectField(modInfo, g_modInfo.name, name);
  env->SetObjectField(modInfo, g_modInfo.type, type);
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

jint xmp_mute(JNIEnv* env, jint chn, jint status) {
  XmpPlayerState& state = XmpPlayerState::instance();
  return xmp_channel_mute(state.ctx, chn, status);
}

jint xmp_nextPosition(JNIEnv* env) {
  XmpPlayerState& state = XmpPlayerState::instance();
  return xmp_next_position(state.ctx);
}

jint xmp_playAudio(JNIEnv* env) {
  return play_audio();
}

jint xmp_prevPosition(JNIEnv* env) {
  XmpPlayerState& state = XmpPlayerState::instance();
  return xmp_prev_position(state.ctx);
}

jint xmp_releaseModule(JNIEnv* env) {
  XmpPlayerState& state = XmpPlayerState::instance();
  std::unique_lock<std::mutex> lock = state.lock();

  if (state.mod_is_loaded) {
    state.mod_is_loaded = false;
    xmp_release_module(state.ctx);
  }

  return 0;
}

jint xmp_restartModule(JNIEnv* env) {
  XmpPlayerState& state = XmpPlayerState::instance();
  xmp_restart_module(state.ctx);
  return 0;
}

jint xmp_seek(JNIEnv* env, jint time) {
  XmpPlayerState& state = XmpPlayerState::instance();
  std::unique_lock<std::mutex> lock = state.lock();
  return xmp_seek_time(state.ctx, time);
}

jint xmp_setPlayer(JNIEnv* env, jint parm, jint value) {
  XmpPlayerState& state = XmpPlayerState::instance();
  auto res = xmp_set_player(state.ctx, parm, value);
  if (res != 0) LOG_WARN("xmp_play_buffer: %s (%d)", xmpErrorString(res), res);
  return res;
}

jint xmp_setPosition(JNIEnv* env, jint n) {
  XmpPlayerState& state = XmpPlayerState::instance();
  return xmp_set_position(state.ctx, n);
}

jint xmp_startPlayer(JNIEnv* env, jint rate, jint format) {
  XmpPlayerState& state = XmpPlayerState::instance();
  int use_rate = state.actual_rate > 0 ? state.actual_rate : rate;

  LOG_INFO("startPlayer() - requested: %d, using: %d, tid: %d", rate, use_rate, gettid());

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

  LOG_INFO("startPlayer() completed - result: %d, tid: %d", ret, gettid());
  return ret;
}

jint xmp_stopModule(JNIEnv* env) {
  XmpPlayerState& state = XmpPlayerState::instance();
  xmp_stop_module(state.ctx);
  return 0;
}

jint xmp_time(JNIEnv* env) {
  XmpPlayerState& state = XmpPlayerState::instance();
  if (!state.playing) return -1;
  xmp_frame_info fi = {};
  xmp_get_frame_info(state.ctx, &fi);
  return fi.time;
}

jobjectArray xmp_getFormats(JNIEnv* env) {
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

jstring xmp_getVersion(JNIEnv* env) {
  return env->NewStringUTF(xmp_version);
}

void xmp_getAudioStats(JNIEnv* env, jobject info) {
  initAudioStatsFields(env);

  struct AudioStats stats{};
  get_audio_stats(&stats);

  env->SetIntField(info, g_audioStats.xrunCount, stats.xrun_count);
  env->SetIntField(info, g_audioStats.underrunCount, stats.underrun_count);
  env->SetIntField(info, g_audioStats.framesPerBurst, stats.frames_per_burst);
  env->SetIntField(info, g_audioStats.bufferCapacity, stats.buffer_capacity);
  env->SetIntField(info, g_audioStats.bufferSize, stats.buffer_size);
  env->SetIntField(info, g_audioStats.sampleRate, stats.sample_rate);

  jstring apiStr = env->NewStringUTF(stats.audio_api ? stats.audio_api : "");
  jstring modeStr = env->NewStringUTF(stats.sharing_mode ? stats.sharing_mode : "");
  jstring perfStr = env->NewStringUTF(stats.perf_mode ? stats.perf_mode : "");
  jstring formatStr = env->NewStringUTF(stats.audio_format ? stats.audio_format : "");

  env->SetObjectField(info, g_audioStats.audioApi, apiStr);
  env->SetObjectField(info, g_audioStats.sharingMode, modeStr);
  env->SetObjectField(info, g_audioStats.perfMode, perfStr);
  env->SetObjectField(info, g_audioStats.audioFormat, formatStr);

  env->DeleteLocalRef(apiStr);
  env->DeleteLocalRef(modeStr);
  env->DeleteLocalRef(perfStr);
  env->DeleteLocalRef(formatStr);
}

void xmp_getChannelData(JNIEnv* env, jobject channelInfo) {
  XmpPlayerState& state = XmpPlayerState::instance();

  if (!state.initialized) {
    LOG_WARN("getChannelData() - early exit: not initialized - tid: %d", gettid());
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

  set(g_channelInfo.volumes, lVol);
  set(g_channelInfo.finalVols, lFinalVol);
  set(g_channelInfo.pans, lPan);
  set(g_channelInfo.instruments, lIns);
  set(g_channelInfo.keys, lKey);
  set(g_channelInfo.periods, lPeriod);
  set(g_channelInfo.holdVols, lHoldVol);
  set(g_channelInfo.positions, lPosition);
  set(g_channelInfo.pitchbends, lPitchbend);
  set(g_channelInfo.notes, lNote);
  set(g_channelInfo.samples, lSample);
}

void xmp_getFrameInfo(JNIEnv* env, jobject frameInfo) {
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

  env->SetIntField(frameInfo, g_frameInfo.pos, s.pos);
  env->SetIntField(frameInfo, g_frameInfo.pattern, s.pattern);
  env->SetIntField(frameInfo, g_frameInfo.row, s.row);
  env->SetIntField(frameInfo, g_frameInfo.numRows, s.numRows);
  env->SetIntField(frameInfo, g_frameInfo.frame, s.frame);
  env->SetIntField(frameInfo, g_frameInfo.speed, s.speed);
  env->SetIntField(frameInfo, g_frameInfo.bpm, s.bpm);
  env->SetIntField(frameInfo, g_frameInfo.time, s.time);
  env->SetIntField(frameInfo, g_frameInfo.totalTime, s.totalTime);
  env->SetIntField(frameInfo, g_frameInfo.frameTime, s.frameTime);
  env->SetIntField(frameInfo, g_frameInfo.bufferSize, s.bufferSize);
  env->SetIntField(frameInfo, g_frameInfo.totalSize, s.totalSize);
  env->SetIntField(frameInfo, g_frameInfo.volume, s.volume);
  env->SetIntField(frameInfo, g_frameInfo.loopCount, s.loopCount);
  env->SetIntField(frameInfo, g_frameInfo.virtChannels, s.virtChannels);
  env->SetIntField(frameInfo, g_frameInfo.virtUsed, s.virtUsed);
  env->SetIntField(frameInfo, g_frameInfo.sequence, s.sequence);
}

void xmp_getModVars(JNIEnv* env, jobject modVars) {
  XmpPlayerState& state = XmpPlayerState::instance();

  if (!state.initialized) {
    LOG_WARN("getModVars() - early exit: not initialized - tid: %d", gettid());
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
      LOG_WARN("getModVars() - module not loaded - tid: %d", gettid());
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

  env->SetObjectField(modVars, g_modVars.name, nameStr);
  env->SetObjectField(modVars, g_modVars.type, typeStr);
  env->SetIntField(modVars, g_modVars.pat, pat);
  env->SetIntField(modVars, g_modVars.trk, trk);
  env->SetIntField(modVars, g_modVars.chn, chn);
  env->SetIntField(modVars, g_modVars.ins, ins);
  env->SetIntField(modVars, g_modVars.smp, smp);
  env->SetIntField(modVars, g_modVars.spd, spd);
  env->SetIntField(modVars, g_modVars.bpm, bpm);
  env->SetIntField(modVars, g_modVars.len, len);
  env->SetIntField(modVars, g_modVars.rst, rst);
  env->SetIntField(modVars, g_modVars.gvl, gvl);
  env->SetIntField(modVars, g_modVars.miNumSequences, numSeq);
  env->SetObjectField(modVars, g_modVars.miComment, commentStr);

  jclass seqCls = env->FindClass("com/lossydragon/native/model/Sequence");
  jobjectArray seqArray = env->NewObjectArray((jsize)seqs.size(), seqCls, nullptr);
  for (int i = 0; i < (int)seqs.size(); i++) {
    jobject seqObj = env->AllocObject(seqCls);
    env->SetIntField(seqObj, g_sequence.entryPoint, seqs[i].entryPoint);
    env->SetIntField(seqObj, g_sequence.duration, seqs[i].duration);
    env->SetObjectArrayElement(seqArray, i, seqObj);
    env->DeleteLocalRef(seqObj);
  }
  env->SetObjectField(modVars, g_modVars.seqData, seqArray);

  jclass stringClass = env->FindClass("java/lang/String");
  jobjectArray insArray = env->NewObjectArray(ins, stringClass, nullptr);
  for (int i = 0; i < ins; i++) {
    jstring s = env->NewStringUTF(instrNames[i].c_str());
    env->SetObjectArrayElement(insArray, i, s);
    env->DeleteLocalRef(s);
  }
  env->SetObjectField(modVars, g_modVars.instruments, insArray);

  jstring renderingStr = env->NewStringUTF("libxmp");
  env->SetObjectField(modVars, g_modVars.renderingEngine, renderingStr);

  env->DeleteLocalRef(nameStr);
  env->DeleteLocalRef(typeStr);
  env->DeleteLocalRef(commentStr);
  env->DeleteLocalRef(seqArray);
  env->DeleteLocalRef(seqCls);
  env->DeleteLocalRef(insArray);
  env->DeleteLocalRef(stringClass);
  env->DeleteLocalRef(renderingStr);
}

// An effect slot is empty only when both type and parameter are zero: type 0 is
// arpeggio (FX_ARPEGGIO shares the 0 encoding), which is a real effect whenever
// its parameter is nonzero. Empty slots are reported as -1/-1.
static void packEffect(uint8_t type, uint8_t parm, jbyte& outType, jbyte& outParm) {
  if (type == 0 && parm == 0) {
    outType = -1;
    outParm = -1;
  } else {
    outType = static_cast<jbyte>(type);
    outParm = static_cast<jbyte>(parm);
  }
}

void xmp_getPatternRow(JNIEnv* env, jint pat, jint row, jbyteArray rowNotes, jbyteArray rowInstruments, jbyteArray rowFxType, jbyteArray rowFxParm, jbyteArray rowFx2Type, jbyteArray rowFx2Parm) {
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
  std::array<jbyte, XMP_MAX_CHANNELS> row_f2t{};
  std::array<jbyte, XMP_MAX_CHANNELS> row_f2p{};

  for (int i = 0; i < chn; i++) {
    const xmp_track* xxt = mi.mod->xxt[xxp->index[i]];
    const xmp_event& e = xxt->event[row];

    row_note[i] = static_cast<jbyte>(e.note);
    row_ins[i] = static_cast<jbyte>(e.ins);
    packEffect(e.fxt, e.fxp, row_fxt[i], row_fxp[i]);
    packEffect(e.f2t, e.f2p, row_f2t[i], row_f2p[i]);
  }

  env->SetByteArrayRegion(rowNotes, 0, chn, row_note.data());
  env->SetByteArrayRegion(rowInstruments, 0, chn, row_ins.data());
  env->SetByteArrayRegion(rowFxType, 0, chn, row_fxt.data());
  env->SetByteArrayRegion(rowFxParm, 0, chn, row_fxp.data());
  env->SetByteArrayRegion(rowFx2Type, 0, chn, row_f2t.data());
  env->SetByteArrayRegion(rowFx2Parm, 0, chn, row_f2p.data());
}

void xmp_getSampleData(JNIEnv* env, jboolean trigger, jint ins, jint key, jint period, jint chn, jint width, jbyteArray buffer) {
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

void xmp_setLoopMode(JNIEnv* env, jboolean loop) {
  // loop=true  → xmp_play_buffer loop=0 (loop forever, repeat-one)
  // loop=false → xmp_play_buffer loop=1 (play once, signal end)
  set_loop_mode(loop == JNI_TRUE ? 0 : 1);
}

void xmp_setPlaying(JNIEnv* env, jboolean value) {
  set_playing(value == JNI_TRUE ? 1 : 0);
}

void xmp_freeContext(JNIEnv* env) {
  XmpPlayerState& state = XmpPlayerState::instance();
  std::unique_lock<std::mutex> lock = state.lock();

  set_playing(0);
  set_xmp_context(nullptr);

  if (state.mod_is_loaded) {
    xmp_release_module(state.ctx);
    state.mod_is_loaded = false;
  }

  if (state.ctx) {
    xmp_free_context(state.ctx);
    state.ctx = nullptr;
  }

  state.initialized = false;
}
