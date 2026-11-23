//
// Created by Lossy on 6/10/2026.
//

#include "audio.h"
#include "player-common.h"
#include "player.h"
#include "xmp.h"
#include <algorithm>
#include <android/log.h>
#include <array>
#include <atomic>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
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

// Audio-thread view of the context: set by init, nulled (with the callback drained) before free.
static std::atomic<xmp_context> g_render_ctx(nullptr);
// passed as the loop arg to xmp_play_buffer: 0 = loop forever (repeat-one), 1 = play once
static std::atomic<int> g_loop_flag(1);

// Published by the render callback after every xmp_play_buffer; `valid` is false while stopped.
struct FrameSnapshot {
  bool valid = false;
  xmp_frame_info fi{};
};
static SeqLock<FrameSnapshot> g_frame;

static xmp_subinstrument* getSubinstrument(const xmp_module_info& mi, int ins, int key) {
  if (!mi.mod || ins < 0 || ins >= mi.mod->ins || key < 0 || key >= XMP_MAX_KEYS) return nullptr;
  if (mi.mod->xxi[ins].map[key].ins == 0xff) return nullptr;

  int mapped = mi.mod->xxi[ins].map[key].ins;
  if (mapped < 0 || mapped >= mi.mod->xxi[ins].nsm) return nullptr;

  return &mi.mod->xxi[ins].sub[mapped];
}

static const char* xmpErrorString(int code) {
  static constexpr std::array<const char*, 9> table = {
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

  // Serialises engine-thread mutations against visualisation readers. Never taken by the audio thread.
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

static int render_xmp(void* audioData, int32_t numFrames, int32_t numChannels, int32_t bytesPerSample) {
  const int numBytes = numFrames * numChannels * bytesPerSample;
  xmp_context ctx = g_render_ctx.load(std::memory_order_acquire);
  if (ctx == nullptr) {
    memset(audioData, 0, numBytes);
    return 0;
  }
  int ret = xmp_play_buffer(ctx, audioData, numBytes, g_loop_flag.load(std::memory_order_relaxed));
  g_frame.update([&](FrameSnapshot& s) {
    s.valid = true;
    xmp_get_frame_info(ctx, &s.fi);
  });
  // libxmp reports end of module as -XMP_END; any error also ends playback
  return ret < 0 ? -1 : 0;
}

// Callers hold state.mutex and have the callback drained (RenderPause or set_playing(0)).
static void drop_module(XmpPlayerState& state) {
  if (state.mod_is_loaded && state.ctx) xmp_release_module(state.ctx);
  state.mod_is_loaded = false;
  state.playing = false;
  state.mi = {};
  g_frame.reset();
  clear_module_ended();
}

static void destroy_context(XmpPlayerState& state) {
  set_playing(0);
  g_render_ctx.store(nullptr, std::memory_order_release);
  drop_module(state);
  if (state.ctx) {
    xmp_free_context(state.ctx);
    state.ctx = nullptr;
  }
  state.initialized = false;
}

jboolean xmp_hasEnded(JNIEnv* env) {
  return has_module_ended() ? JNI_TRUE : JNI_FALSE;
}

jboolean xmp_init(JNIEnv* env, jint rate, jint ms, jint mode, jint channels, jint api, jint flags) {
  LOG_INFO("init() called - rate: %d, ms: %d, tid: %d", rate, ms, gettid());

  XmpPlayerState& state = XmpPlayerState::instance();
  std::unique_lock<std::mutex> lock = state.lock();

  if (state.ctx) {
    LOG_WARN("init() - context already exists, destroying it first");
    destroy_context(state);
  }

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
  g_render_ctx.store(ctx, std::memory_order_release);
  set_render_callback(render_xmp);

  if (actual_rate != rate) {
    LOG_INFO("init() - sample rate adjusted: requested=%d, actual=%d", rate, actual_rate);
  }


  state.initialized = true;
  LOG_INFO("init() completed successfully - actual_rate=%d", actual_rate);

  return JNI_TRUE;
}

jboolean xmp_setSequence(JNIEnv* env, jint seq) {
  XmpPlayerState& state = XmpPlayerState::instance();
  RenderPause pause;
  std::unique_lock<std::mutex> lock = state.lock();
  const xmp_module_info& mi = state.mi;

  if (!state.ctx || !state.mod_is_loaded || !mi.seq_data) return JNI_FALSE;
  if (seq < 0 || seq >= mi.num_sequences) return JNI_FALSE;
  if (mi.seq_data[seq].duration <= 0) return JNI_FALSE;
  if (state.sequence == seq) return JNI_FALSE;

  state.sequence = seq;
  xmp_set_position(state.ctx, mi.seq_data[seq].entry_point);
  xmp_play_buffer(state.ctx, nullptr, 0, 0);
  clear_module_ended();

  return JNI_TRUE;
}

jboolean xmp_testFd(JNIEnv* env, jint fd, jobject modInfo) {
  LOG_INFO("testModuleFd() called - fd: %d, tid: %d", fd, gettid());

  UniqueFd owned(fd); // ownership was transferred by ParcelFileDescriptor.detachFd()
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

    const ModuleCharset charset = charsetForFormat(ti.type);
    jstring name = env->NewStringUTF(decodeModuleText(ti.name, charset).c_str());
    jstring type = env->NewStringUTF(decodeModuleText(ti.type, charset).c_str());

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
    destroy_context(state);
  }

  close_audio();
  LOG_INFO("deinit() completed - tid: %d", gettid());

  return 0;
}

jint xmp_endPlayer(JNIEnv* env) {
  LOG_INFO("endPlayer() called - tid: %d", gettid());

  XmpPlayerState& state = XmpPlayerState::instance();
  std::unique_lock<std::mutex> lock = state.lock();

  if (state.playing) {
    set_playing(0);
    state.playing = false;
    if (state.ctx) xmp_end_player(state.ctx);
    g_frame.reset();
  }

  LOG_INFO("endPlayer() completed - tid: %d", gettid());
  return 0;
}

jint xmp_getPatternRows(JNIEnv* env, jint pat) {
  XmpPlayerState& state = XmpPlayerState::instance();
  std::unique_lock<std::mutex> lock = state.lock();
  if (!state.mod_is_loaded || !state.mi.mod) return 0;

  const xmp_module_info& mi = state.mi;
  if (pat < 0 || pat >= mi.mod->pat) return 0;

  const xmp_pattern* xxp = mi.mod->xxp[pat];
  return xxp ? xxp->rows : 0;
}

jint xmp_getPlayer(JNIEnv* env, jint parm) {
  XmpPlayerState& state = XmpPlayerState::instance();
  std::unique_lock<std::mutex> lock = state.lock();
  if (!state.ctx) return 0;
  return xmp_get_player(state.ctx, parm);
}

jint xmp_loadFd(JNIEnv* env, jint fd, jobject modInfo) {
  LOG_INFO("loadModuleFd() called - fd: %d, tid: %d", fd, gettid());
  std::unique_ptr<FILE, decltype(&fclose)> file(fdopen(fd, "rb"), fclose);
  if (!file) {
    LOG_ERROR("loadModuleFd() - fdopen failed: %s", strerror(errno));
    close(fd);
    return -1;
  }

  xmp_test_info ti{};
  int res = xmp_test_module_from_file(file.get(), &ti);

  if (res != 0) {
    LOG_WARN("loadModuleFd() - test failed: %d", res);
    return -2;
  }

  const ModuleCharset charset = charsetForFormat(ti.type);
  jstring name = env->NewStringUTF(decodeModuleText(ti.name, charset).c_str());
  jstring type = env->NewStringUTF(decodeModuleText(ti.type, charset).c_str());
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
  RenderPause pause;
  pause.stay_stopped(); // loading ends the player; Kotlin restarts it via startPlayer
  std::unique_lock<std::mutex> lock = state.lock();
  if (!state.ctx) {
    LOG_ERROR("loadModuleFd() - no context");
    return -4;
  }

  drop_module(state);
  res = xmp_load_module_from_file(state.ctx, file.get(), static_cast<off_t>(statbuf.st_size));

  if (res == 0) {
    xmp_get_module_info(state.ctx, &state.mi);
    state.pos.fill(0);
    state.sequence = 0;
    state.mod_is_loaded = true;
    LOG_INFO("loadModuleFd() - loaded: %s", ti.name);
  } else {
    state.mi = {};
    LOG_WARN("loadModuleFd() - load failed: %s (%d)", xmpErrorString(res), res);
  }

  return res;
}

jint xmp_mute(JNIEnv* env, jint chn, jint status) {
  XmpPlayerState& state = XmpPlayerState::instance();
  if (status < 0) {
    // A query only reads a flag the renderer never writes: no need to silence the callback.
    std::unique_lock<std::mutex> lock = state.lock();
    if (!state.ctx) return -1;
    return xmp_channel_mute(state.ctx, chn, status);
  }
  RenderPause pause;
  std::unique_lock<std::mutex> lock = state.lock();
  if (!state.ctx) return -1;
  return xmp_channel_mute(state.ctx, chn, status);
}

jint xmp_playAudio(JNIEnv* env) {
  return play_audio();
}

jint xmp_releaseModule(JNIEnv* env) {
  XmpPlayerState& state = XmpPlayerState::instance();
  RenderPause pause;
  pause.stay_stopped();
  std::unique_lock<std::mutex> lock = state.lock();
  drop_module(state);
  return 0;
}

jint xmp_seek(JNIEnv* env, jint time) {
  XmpPlayerState& state = XmpPlayerState::instance();
  RenderPause pause;
  std::unique_lock<std::mutex> lock = state.lock();
  if (!state.ctx) return -1;
  return xmp_seek_time(state.ctx, time);
}

jint xmp_setPlayer(JNIEnv* env, jint parm, jint value) {
  XmpPlayerState& state = XmpPlayerState::instance();
  RenderPause pause;
  std::unique_lock<std::mutex> lock = state.lock();
  if (!state.ctx) return -1;
  auto res = xmp_set_player(state.ctx, parm, value);
  if (res != 0) LOG_WARN("xmp_set_player(%d, %d): %s (%d)", parm, value, xmpErrorString(res), res);
  return res;
}

jint xmp_startPlayer(JNIEnv* env, jint rate, jint format) {
  XmpPlayerState& state = XmpPlayerState::instance();
  RenderPause pause;
  pause.stay_stopped(); // set_playing(1) below decides, and also clears module_ended
  std::unique_lock<std::mutex> lock = state.lock();

  if (!state.ctx || !state.mod_is_loaded) {
    LOG_ERROR("startPlayer() - no module loaded");
    return -XMP_ERROR_STATE;
  }

  int use_rate = state.actual_rate > 0 ? state.actual_rate : rate;
  LOG_INFO("startPlayer() - requested: %d, using: %d, tid: %d", rate, use_rate, gettid());

  state.last_key.fill(-1);
  state.cur_vol.fill(0);
  state.hold_vol.fill(0);
  g_frame.reset();

  set_render_callback(render_xmp);
  int ret = xmp_start_player(state.ctx, use_rate, get_effective_format_flags());
  if (ret == 0) {
    state.playing = true;
    set_playing(1);
  } else {
    state.playing = false;
    LOG_WARN("startPlayer() - xmp_start_player: %s (%d)", xmpErrorString(ret), ret);
  }

  LOG_INFO("startPlayer() completed - result: %d, tid: %d", ret, gettid());
  return ret;
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

void xmp_getAudioStats(JNIEnv* env, jobject info) {

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

  int chn = 0;
  std::array<int, XMP_MAX_CHANNELS> lVol{}, lFinalVol{}, lPan{}, lIns{};
  std::array<int, XMP_MAX_CHANNELS> lKey{}, lPeriod{}, lHoldVol{};
  std::array<int, XMP_MAX_CHANNELS> lPosition{}, lPitchbend{}, lNote{}, lSample{};

  {
    std::unique_lock<std::mutex> lock = state.lock();
    if (!state.initialized || !state.mod_is_loaded || !state.playing || !state.mi.mod) return;

    static thread_local FrameSnapshot snap; // 1.6 KB; keep it off the stack of UI threads
    if (!g_frame.read(snap) || !snap.valid) return;

    const xmp_module_info& mi = state.mi;
    const xmp_frame_info& fi = snap.fi;
    chn = std::min<int>(mi.mod->chn, XMP_MAX_CHANNELS);
    const int vol_base = mi.vol_base > 0 ? mi.vol_base : 0x40;

    for (int i = 0; i < chn; i++) {
      const xmp_channel_info& ci = fi.channel_info[i];

      if (ci.event.vol > 0) state.hold_vol[i] = ci.event.vol * 0x40 / vol_base;

      state.cur_vol[i] = std::max(0, state.cur_vol[i] - state.decay);

      int key = -1;
      if (ci.event.note > 0 && ci.event.note <= 0x80) {
        key = ci.event.note - 1;
        state.last_key[i] = key;
        if (xmp_subinstrument* sub = getSubinstrument(mi, ci.instrument, key)) state.cur_vol[i] = sub->vol * 0x40 / vol_base;
      }
      if (ci.event.vol > 0) {
        key = state.last_key[i];
        state.cur_vol[i] = ci.event.vol * 0x40 / vol_base;
      }

      lVol[i] = state.cur_vol[i];
      lHoldVol[i] = state.hold_vol[i];
      lKey[i] = key;
      lIns[i] = (ci.instrument == 0xff) ? -1 : ci.instrument; // -1 = no instrument, same as openmpt
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
    if (obj) env->SetIntArrayRegion(obj, 0, chn, arr.data());
    env->DeleteLocalRef(obj);
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
  static thread_local FrameSnapshot snap;
  if (!g_frame.read(snap) || !snap.valid) return;
  const xmp_frame_info& fi = snap.fi;

  env->SetIntField(frameInfo, g_frameInfo.pos, fi.pos);
  env->SetIntField(frameInfo, g_frameInfo.pattern, fi.pattern);
  env->SetIntField(frameInfo, g_frameInfo.row, fi.row);
  env->SetIntField(frameInfo, g_frameInfo.numRows, fi.num_rows);
  env->SetIntField(frameInfo, g_frameInfo.frame, fi.frame);
  env->SetIntField(frameInfo, g_frameInfo.speed, fi.speed);
  env->SetIntField(frameInfo, g_frameInfo.bpm, fi.bpm);
  env->SetIntField(frameInfo, g_frameInfo.time, fi.time);
  env->SetIntField(frameInfo, g_frameInfo.totalTime, fi.total_time);
  env->SetIntField(frameInfo, g_frameInfo.frameTime, fi.frame_time);
  env->SetIntField(frameInfo, g_frameInfo.bufferSize, fi.buffer_size);
  env->SetIntField(frameInfo, g_frameInfo.totalSize, fi.total_size);
  env->SetIntField(frameInfo, g_frameInfo.volume, fi.volume);
  env->SetIntField(frameInfo, g_frameInfo.loopCount, fi.loop_count);
  env->SetIntField(frameInfo, g_frameInfo.virtChannels, fi.virt_channels);
  env->SetIntField(frameInfo, g_frameInfo.virtUsed, fi.virt_used);
  env->SetIntField(frameInfo, g_frameInfo.sequence, fi.sequence);
}

void xmp_getModVars(JNIEnv* env, jobject modVars) {
  XmpPlayerState& state = XmpPlayerState::instance();

  std::string modName, modType, modComment;
  int pat, trk, chn, ins, smp, spd, bpm, len, rst, gvl, numSeq;
  struct SeqEntry {
    int entryPoint, duration;
  };
  std::vector<SeqEntry> seqs;
  std::vector<std::string> instrNames;

  {
    std::unique_lock<std::mutex> lock = state.lock();
    if (!state.initialized || !state.mod_is_loaded || !state.mi.mod) {
      LOG_WARN("getModVars() - module not loaded - tid: %d", gettid());
      return;
    }
    const xmp_module_info& mi = state.mi;
    const ModuleCharset charset = charsetForFormat(mi.mod->type);
    modName = decodeModuleText(mi.mod->name, charset);
    modType = decodeModuleText(mi.mod->type, charset);
    modComment = decodeModuleText(mi.comment, charset);
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
      std::array<char, 8> index{};
      snprintf(index.data(), index.size(), "%02X ", i + 1);
      instrNames.push_back(index.data() + decodeModuleText(mi.mod->xxi[i].name, charset));
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
  if (!rowNotes || !rowInstruments || !rowFxType || !rowFxParm || !rowFx2Type || !rowFx2Parm) return;
  XmpPlayerState& state = XmpPlayerState::instance();

  int chn = 0;
  std::array<jbyte, XMP_MAX_CHANNELS> row_note{};
  std::array<jbyte, XMP_MAX_CHANNELS> row_ins{};
  std::array<jbyte, XMP_MAX_CHANNELS> row_fxt{};
  std::array<jbyte, XMP_MAX_CHANNELS> row_fxp{};
  std::array<jbyte, XMP_MAX_CHANNELS> row_f2t{};
  std::array<jbyte, XMP_MAX_CHANNELS> row_f2p{};

  {
    std::unique_lock<std::mutex> lock = state.lock();
    if (!state.mod_is_loaded || !state.mi.mod) return;

    const xmp_module_info& mi = state.mi;
    if (pat < 0 || pat >= mi.mod->pat || row < 0) return;

    const xmp_pattern* xxp = mi.mod->xxp[pat];
    if (!xxp || row >= xxp->rows) return;

    chn = std::min<int>(mi.mod->chn, XMP_MAX_CHANNELS);

    for (int i = 0; i < chn; i++) {
      const xmp_track* xxt = mi.mod->xxt[xxp->index[i]];
      if (!xxt || row >= xxt->rows) continue;
      const xmp_event& e = xxt->event[row];

      row_note[i] = static_cast<jbyte>(e.note);
      row_ins[i] = static_cast<jbyte>(e.ins);
      packEffect(e.fxt, e.fxp, row_fxt[i], row_fxp[i]);
      packEffect(e.f2t, e.f2p, row_f2t[i], row_f2p[i]);
    }
  }

  env->SetByteArrayRegion(rowNotes, 0, chn, row_note.data());
  env->SetByteArrayRegion(rowInstruments, 0, chn, row_ins.data());
  env->SetByteArrayRegion(rowFxType, 0, chn, row_fxt.data());
  env->SetByteArrayRegion(rowFxParm, 0, chn, row_fxp.data());
  env->SetByteArrayRegion(rowFx2Type, 0, chn, row_f2t.data());
  env->SetByteArrayRegion(rowFx2Parm, 0, chn, row_f2p.data());
}

void xmp_getSampleData(JNIEnv* env, jboolean trigger, jint ins, jint key, jint period, jint chn, jint width, jbyteArray buffer) {
  if (!buffer) return;
  width = std::min(width, MAX_BUFFER_SIZE);
  if (width <= 0) return;

  XmpPlayerState& state = XmpPlayerState::instance();
  std::array<jbyte, MAX_BUFFER_SIZE> sample_buffer{};

  [&]() {
    std::unique_lock<std::mutex> lock = state.lock();

    if (!state.mod_is_loaded || !state.mi.mod) return;
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
  g_loop_flag.store(loop == JNI_TRUE ? 0 : 1, std::memory_order_relaxed);
}

void xmp_setPlaying(JNIEnv* env, jboolean value) {
  set_playing(value == JNI_TRUE ? 1 : 0);
}

void xmp_freeContext(JNIEnv* env) {
  XmpPlayerState& state = XmpPlayerState::instance();
  std::unique_lock<std::mutex> lock = state.lock();
  destroy_context(state);
}
