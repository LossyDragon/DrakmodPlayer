//
// Created by Zeo on 6/10/2026.
// Refactored by Lossy on 6/10/2026.
//

#include "audio.h"
#include "libopenmpt/libopenmpt.h"
#include "player-common.h"
#include "player.h"
#include <algorithm>
#include <android/log.h>
#include <array>
#include <cerrno>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <jni.h>
#include <memory>
#include <mutex>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

constexpr const char* TAG = "Drakplayer (libopenmpt) JNI";
#define LOG_DEBUG(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOG_INFO(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOG_WARN(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOG_ERROR(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

constexpr int MAX_CHANNELS = 64;
constexpr int MAX_BUFFER_SIZE = 1024;
constexpr int OPENMPT_SAMPLE_16BIT = 0x01;
constexpr int OPENMPT_SAMPLE_LOOP = 0x02;
constexpr int OPENMPT_SAMPLE_PINGPONG = 0x04;
constexpr int OPENMPT_SAMPLE_STEREO = 0x40;

struct OpenMptState {
  std::mutex mutex;
  bool initialized = false;
  bool mod_is_loaded = false;
  bool playing = false;
  bool loop = false;
  int actual_rate = 44100;
  int selected_subsong = 0;
  double duration = 0.0;
  openmpt_module* mod = nullptr;
  std::array<int, MAX_CHANNELS> current_sample{};
  // used to derive pitch bend: period when the note last triggered
  std::array<int, MAX_CHANNELS> base_periods{};
  std::array<int, MAX_CHANNELS> prev_keys{};

  static OpenMptState& instance() {
    static OpenMptState s;
    return s;
  }
};

std::string openmptString(const char* value) {
  if (!value) return "";
  std::string result(value);
  openmpt_free_string(value);
  return result;
}

std::string metadata(openmpt_module* mod, const char* key) {
  return openmptString(openmpt_module_get_metadata(mod, key));
}

int renderOpenMpt(void* audioData, int32_t numFrames, int32_t numChannels, int32_t bytesPerSample) {
  OpenMptState& state = OpenMptState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.mod || !state.playing) return 0;

  static int render_count = 0;
  if (++render_count % 5000 == 0) {
    double pos = openmpt_module_get_position_seconds(state.mod);
    LOG_DEBUG("renderOpenMpt: count=%d pos=%.2fs / %.2fs loop=%d repeat_count=%d",
      render_count, pos, state.duration, state.loop ? 1 : 0,
      openmpt_module_get_repeat_count(state.mod));
  }

  size_t frames = 0;
  if (bytesPerSample == 2 && numChannels == 2) {
    frames = openmpt_module_read_interleaved_stereo(state.mod, state.actual_rate, numFrames, static_cast<int16_t*>(audioData));
  } else {
    std::vector<int16_t> stereo(static_cast<size_t>(numFrames) * 2);
    frames = openmpt_module_read_interleaved_stereo(state.mod, state.actual_rate, numFrames, stereo.data());
    if (bytesPerSample == 2 && numChannels == 1) {
      auto* out = static_cast<int16_t*>(audioData);
      for (size_t i = 0; i < frames; i++) out[i] = static_cast<int16_t>((stereo[i * 2] + stereo[i * 2 + 1]) / 2);
    } else if (bytesPerSample == 4) {
      auto* out = static_cast<int32_t*>(audioData);
      for (size_t i = 0; i < frames; i++) {
        int32_t left = stereo[i * 2] << 16;
        int32_t right = stereo[i * 2 + 1] << 16;
        if (numChannels == 1) {
          out[i] = (left + right) / 2;
        } else {
          out[i * 2] = left;
          out[i * 2 + 1] = right;
        }
      }
    }
  }

  if (frames < static_cast<size_t>(numFrames)) {
    LOG_INFO("renderOpenMpt: short read frames=%zu numFrames=%d loop=%d repeat_count=%d",
      frames, numFrames, state.loop ? 1 : 0,
      openmpt_module_get_repeat_count(state.mod));
    if (state.loop) {
      openmpt_module_set_position_seconds(state.mod, 0.0);
    } else {
      LOG_INFO("renderOpenMpt: signalling module_ended");
      return -1;
    }
  }
  return 0;
}

void destroyModule(OpenMptState& state) {
  if (state.mod) {
    openmpt_module_destroy(state.mod);
    state.mod = nullptr;
  }
  state.mod_is_loaded = false;
  state.playing = false;
  state.duration = 0.0;
  state.selected_subsong = 0;
  state.current_sample.fill(0);
  state.base_periods.fill(0);
  state.prev_keys.fill(-1);
}

static jbyte readSampleByte(const openmpt_module_sample_state& sample, int frame) {
  if (!sample.data || frame < 0 || frame >= sample.length) return 0;
  int sampleChannels = std::max(1, sample.channels);
  int index = frame * sampleChannels;
  if ((sample.flags & OPENMPT_SAMPLE_STEREO) != 0 && sampleChannels >= 2) {
    if (sample.bits_per_sample == 16) {
      const auto* data = static_cast<const int16_t*>(sample.data);
      return static_cast<jbyte>(((static_cast<int>(data[index]) + static_cast<int>(data[index + 1])) / 2) >> 8);
    }
    const auto* data = static_cast<const int8_t*>(sample.data);
    return static_cast<jbyte>((static_cast<int>(data[index]) + static_cast<int>(data[index + 1])) / 2);
  }
  if (sample.bits_per_sample == 16) {
    const auto* data = static_cast<const int16_t*>(sample.data);
    return static_cast<jbyte>(data[index] >> 8);
  }
  const auto* data = static_cast<const int8_t*>(sample.data);
  return static_cast<jbyte>(data[index]);
}

static int64_t wrapSamplePosition(int64_t pos, const openmpt_module_sample_state& sample) {
  const int64_t loopStart = static_cast<int64_t>(sample.loop_start) << 32;
  const int64_t loopEnd = static_cast<int64_t>(sample.loop_end) << 32;
  const int64_t sampleEnd = static_cast<int64_t>(sample.length) << 32;
  if ((sample.flags & OPENMPT_SAMPLE_LOOP) != 0 && loopEnd > loopStart) {
    const int64_t loopLength = loopEnd - loopStart;
    while (pos >= loopEnd) pos = loopStart + ((pos - loopEnd) % loopLength);
    while (pos < loopStart) pos = loopEnd - ((loopStart - pos) % loopLength);
    return pos;
  }
  return (pos >= 0 && pos < sampleEnd) ? pos : -1;
}

jboolean openmpt_hasEnded(JNIEnv* env) {
  return has_module_ended() ? JNI_TRUE : JNI_FALSE;
}

jboolean openmpt_init(JNIEnv* env, jint rate, jint ms, jint mode, jint channels, jint api, jint flags) {
  OpenMptState& state = OpenMptState::instance();
  int actual_rate = open_audio(rate, ms, mode, channels, api, flags);
  if (actual_rate < 0) return JNI_FALSE;
  initAudioStatsFields(env);
  initChannelInfoFields(env);
  initFrameInfoFields(env);
  initModInfoFields(env);
  initModVarsFields(env);
  initSequenceFields(env);
  state.actual_rate = actual_rate;
  state.initialized = true;
  set_render_callback(renderOpenMpt);
  return JNI_TRUE;
}

jboolean openmpt_restartAudio(JNIEnv* env) {
  return JNI_FALSE;
}

jboolean openmpt_setSequence(JNIEnv* env, jint seq) {
  OpenMptState& state = OpenMptState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.mod) return JNI_FALSE;
  if (openmpt_module_select_subsong(state.mod, seq) == 0) return JNI_FALSE;
  state.selected_subsong = seq;
  state.duration = openmpt_module_get_duration_seconds(state.mod);
  set_playing(1); // reset module_ended
  return JNI_TRUE;
}

jboolean openmpt_stopAudio(JNIEnv* env) {
  return JNI_FALSE;
}

jboolean openmpt_testFd(JNIEnv* env, jint fd, jobject modInfo) {
  initModInfoFields(env);
  lseek(fd, 0, SEEK_SET);
  std::vector<unsigned char> data = readFd(fd);
  if (data.empty()) return JNI_FALSE;

  int error = OPENMPT_ERROR_OK;
  const char* error_message = nullptr;
  openmpt_module* mod = openmpt_module_create_from_memory2(data.data(), data.size(), nullptr, nullptr, nullptr, nullptr, &error, &error_message, nullptr);

  if (!mod) {
    openmpt_free_string(error_message);
    return JNI_FALSE;
  }

  std::string title = metadata(mod, "title");
  std::string type_long = metadata(mod, "type_long");
  jstring name = env->NewStringUTF(title.c_str());
  jstring typeStr = env->NewStringUTF(type_long.c_str());
  env->SetObjectField(modInfo, g_modInfo.name, name);
  env->SetObjectField(modInfo, g_modInfo.type, typeStr);
  env->DeleteLocalRef(name);
  env->DeleteLocalRef(typeStr);
  openmpt_module_destroy(mod);
  return JNI_TRUE;
}

jint openmpt_deinit(JNIEnv* env) {
  OpenMptState& state = OpenMptState::instance();
  {
    std::lock_guard<std::mutex> lock(state.mutex);
    destroyModule(state);
    state.initialized = false;
  }
  set_playing(0);
  close_audio();
  return 0;
}

jint openmpt_endPlayer(JNIEnv* env) {
  set_playing(0);
  OpenMptState& state = OpenMptState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.playing = false;
  return 0;
}

jint openmpt_getPatternRows(JNIEnv* env, jint pat) {
  OpenMptState& state = OpenMptState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.mod) return 0;
  return openmpt_module_get_pattern_num_rows(state.mod, pat);
}

jint openmpt_getPlayer(JNIEnv* env, jint parm) {
  return JNI_FALSE;
}

jint openmpt_loadFd(JNIEnv* env, jint fd, jobject modInfo) {
  initModInfoFields(env);
  std::vector<unsigned char> data = readFd(fd);
  close(fd);
  if (data.empty()) return -1;

  int error = OPENMPT_ERROR_OK;
  const char* error_message = nullptr;
  openmpt_module* mod = openmpt_module_create_from_memory2(data.data(), data.size(), nullptr, nullptr, nullptr, nullptr, &error, &error_message, nullptr);

  if (!mod) {
    LOG_WARN("loadModuleFd failed: %d %s", error, error_message ? error_message : "");
    openmpt_free_string(error_message);
    return error != 0 ? error : -2;
  }

  // play once by default; setLoopMode overrides to -1 for repeat-one
  int rc_result = openmpt_module_set_repeat_count(mod, 0);
  LOG_INFO("loadFd: set repeat_count=0 result=%d actual=%d", rc_result, openmpt_module_get_repeat_count(mod));

  OpenMptState& state = OpenMptState::instance();
  {
    std::lock_guard<std::mutex> lock(state.mutex);
    destroyModule(state);
    state.mod = mod;
    state.mod_is_loaded = true;
    state.playing = false;
    state.selected_subsong = std::max(0, openmpt_module_get_selected_subsong(mod));
    state.duration = openmpt_module_get_duration_seconds(mod);
  }

  std::string title = metadata(mod, "title");
  std::string type = metadata(mod, "type");
  jstring name = env->NewStringUTF(title.c_str());
  jstring typeStr = env->NewStringUTF(type.c_str());
  env->SetObjectField(modInfo, g_modInfo.name, name);
  env->SetObjectField(modInfo, g_modInfo.type, typeStr);
  env->DeleteLocalRef(name);
  env->DeleteLocalRef(typeStr);
  return 0;
}

jint openmpt_mute(JNIEnv* env, jint chn, jint status) {
  OpenMptState& state = OpenMptState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.mod) return -1;
  return openmpt_module_channel_mute(state.mod, chn, status);
}

jint openmpt_nextPosition(JNIEnv* env) {
  return JNI_FALSE;
}

jint openmpt_playAudio(JNIEnv* env) {
  return play_audio();
}

jint openmpt_prevPosition(JNIEnv* env) {
  return JNI_FALSE;
}

jint openmpt_releaseModule(JNIEnv* env) {
  OpenMptState& state = OpenMptState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  destroyModule(state);
  return 0;
}

jint openmpt_restartModule(JNIEnv* env) {
  return JNI_FALSE;
}

jint openmpt_seek(JNIEnv* env, jint time) {
  OpenMptState& state = OpenMptState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.mod) return -1;
  openmpt_module_set_position_seconds(state.mod, time / 1000.0);
  return 0;
}

jint openmpt_setPlayer(JNIEnv* env, jint parm, jint value) {
  OpenMptState& state = OpenMptState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.mod) return 0;
  if (parm == 1) openmpt_module_ctl_set_integer(state.mod, "render.stereoseparation", value);
  if (parm == 2) openmpt_module_ctl_set_integer(state.mod, "render.interpolationfilterlength", value);
  if (parm == 7) openmpt_module_ctl_set_integer(state.mod, "render.mastergain.millibel", (value - 100) * 60);
  return 0;
}

jint openmpt_setPosition(JNIEnv* env, jint n) {
  return JNI_FALSE;
}

jint openmpt_setResampler(JNIEnv* env, jint mode) {
  OpenMptState& state = OpenMptState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.mod) return 0;

  if (mode == 100 || mode == 101) {
    openmpt_module_ctl_set_boolean(state.mod, "render.resampler.emulate_amiga", 1);
    openmpt_module_ctl_set_text(state.mod, "render.resampler.emulate_amiga_type", mode == 100 ? "a500" : "a1200");
  } else {
    // XMP_INTERP_NEAREST=0, LINEAR=1, SPLINE=2 → mpt filter lengths 1, 2, 4
    static const int kFilterLen[] = {1, 2, 4};
    int filter_len = (mode >= 0 && mode <= 2) ? kFilterLen[mode] : 2;
    openmpt_module_ctl_set_boolean(state.mod, "render.resampler.emulate_amiga", 0);
    openmpt_module_ctl_set_integer(state.mod, "render.interpolationfilterlength", filter_len);
  }
  return 0;
}

jint openmpt_startPlayer(JNIEnv* env, jint rate, jint format) {
  OpenMptState& state = OpenMptState::instance();
  state.actual_rate = rate > 0 ? rate : state.actual_rate;
  set_render_callback(renderOpenMpt);
  std::lock_guard<std::mutex> lock(state.mutex);
  state.playing = true;
  if (state.mod) {
    LOG_INFO("startPlayer: rate=%d loop=%d repeat_count=%d duration=%.2fs",
      state.actual_rate, state.loop ? 1 : 0,
      openmpt_module_get_repeat_count(state.mod), state.duration);
  }
  set_playing(1);
  return 0;
}

jint openmpt_stopModule(JNIEnv* env) {
  return JNI_FALSE;
}

jint openmpt_time(JNIEnv* env) {
  return JNI_FALSE;
}

jobjectArray openmpt_getFormats(JNIEnv* env) {
  std::string extensions = openmptString(openmpt_get_supported_extensions());
  std::vector<std::string> items;
  size_t start = 0;
  while (start <= extensions.size()) {
    size_t end = extensions.find(';', start);
    std::string item = extensions.substr(start, end == std::string::npos ? std::string::npos : end - start);
    if (!item.empty()) items.push_back(item);
    if (end == std::string::npos) break;
    start = end + 1;
  }
  jclass stringClass = env->FindClass("java/lang/String");
  jobjectArray result = env->NewObjectArray(static_cast<jsize>(items.size()), stringClass, nullptr);
  for (int i = 0; i < static_cast<int>(items.size()); i++) {
    jstring s = env->NewStringUTF(items[i].c_str());
    env->SetObjectArrayElement(result, i, s);
    env->DeleteLocalRef(s);
  }
  env->DeleteLocalRef(stringClass);
  return result;
}

jstring openmpt_getVersion(JNIEnv* env) {
  const char* version = openmpt_get_string("library_version");
  jstring result = env->NewStringUTF(version ? version : "");
  openmpt_free_string(version);
  return result;
}

void openmpt_getAudioStats(JNIEnv* env, jobject info) {
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

void openmpt_getChannelData(JNIEnv* env, jobject channelInfo) {
  initChannelInfoFields(env);
  OpenMptState& state = OpenMptState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.mod) return;
  int channels = std::min<int>(MAX_CHANNELS, openmpt_module_get_num_channels(state.mod));
  std::array<int, MAX_CHANNELS> volumes{};
  std::array<int, MAX_CHANNELS> finalVols{};
  std::array<int, MAX_CHANNELS> pans{};
  std::array<int, MAX_CHANNELS> instruments{};
  std::array<int, MAX_CHANNELS> keys{};
  std::array<int, MAX_CHANNELS> periods{};
  std::array<int, MAX_CHANNELS> positions{};
  std::array<int, MAX_CHANNELS> pitchbends{};
  std::array<int, MAX_CHANNELS> notes{};
  std::array<int, MAX_CHANNELS> samples{};
  instruments.fill(-1);
  keys.fill(-1);

  for (int i = 0; i < channels; i++) {
    openmpt_module_current_channel_state ch{};
    if (openmpt_module_get_current_channel_state(state.mod, i, &ch) == 0) continue;
    volumes[i] = ch.volume;
    finalVols[i] = ch.final_volume;
    pans[i] = ch.pan;
    instruments[i] = ch.instrument;
    keys[i] = ch.key;
    periods[i] = ch.period;
    positions[i] = ch.position;
    // ch.pitchbend is MIDI-only (GetMIDIPitchBend / microTuning), always 8192 for MOD/XM/S3M/IT.
    // Derive pitch bend from period deviation since the note triggered instead.
    if (ch.key != state.prev_keys[i]) {
      state.base_periods[i] = ch.period;
      state.prev_keys[i] = ch.key;
    }
    // period is inverse to pitch: lower period = higher frequency, so negate the diff
    pitchbends[i] = (state.base_periods[i] > 0 && ch.period > 0)
      ? state.base_periods[i] - ch.period
      : 0;
    notes[i] = ch.note;
    samples[i] = ch.sample;
    state.current_sample[i] = ch.sample;
  }
  auto set = [&](jfieldID fid, const std::array<int, MAX_CHANNELS>& values) {
    auto arr = (jintArray)env->GetObjectField(channelInfo, fid);
    env->SetIntArrayRegion(arr, 0, channels, values.data());
    env->DeleteLocalRef(arr);
  };
  set(g_channelInfo.volumes, volumes);
  set(g_channelInfo.finalVols, finalVols);
  set(g_channelInfo.pans, pans);
  set(g_channelInfo.instruments, instruments);
  set(g_channelInfo.keys, keys);
  set(g_channelInfo.periods, periods);
  set(g_channelInfo.positions, positions);
  set(g_channelInfo.pitchbends, pitchbends);
  set(g_channelInfo.notes, notes);
  set(g_channelInfo.samples, samples);
}

void openmpt_getFrameInfo(JNIEnv* env, jobject frameInfo) {
  initFrameInfoFields(env);
  OpenMptState& state = OpenMptState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.mod) return;
  int pattern = openmpt_module_get_current_pattern(state.mod);
  int row = openmpt_module_get_current_row(state.mod);
  env->SetIntField(frameInfo, g_frameInfo.pos, openmpt_module_get_current_order(state.mod));
  env->SetIntField(frameInfo, g_frameInfo.pattern, pattern);
  env->SetIntField(frameInfo, g_frameInfo.row, row);
  env->SetIntField(frameInfo, g_frameInfo.numRows, openmpt_module_get_pattern_num_rows(state.mod, pattern));
  env->SetIntField(frameInfo, g_frameInfo.speed, openmpt_module_get_current_speed(state.mod));
  env->SetIntField(frameInfo, g_frameInfo.bpm, static_cast<int>(std::round(openmpt_module_get_current_tempo2(state.mod))));
  env->SetIntField(frameInfo, g_frameInfo.time, static_cast<int>(std::round(openmpt_module_get_position_seconds(state.mod) * 1000.0)));
  env->SetIntField(frameInfo, g_frameInfo.totalTime, static_cast<int>(std::round(state.duration * 1000.0)));
  env->SetIntField(frameInfo, g_frameInfo.virtChannels, openmpt_module_get_num_channels(state.mod));
  env->SetIntField(frameInfo, g_frameInfo.virtUsed, openmpt_module_get_current_playing_channels(state.mod));
  env->SetIntField(frameInfo, g_frameInfo.sequence, state.selected_subsong);
}

void openmpt_getModVars(JNIEnv* env, jobject modVars) {
  initModVarsFields(env);
  initSequenceFields(env);
  OpenMptState& state = OpenMptState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.mod) return;

  std::string title = metadata(state.mod, "title");
  std::string type = metadata(state.mod, "type");
  std::string message = metadata(state.mod, "message");
  int channels = openmpt_module_get_num_channels(state.mod);
  int subsongs = std::max(1, openmpt_module_get_num_subsongs(state.mod));
  int durationMs = static_cast<int>(std::round(state.duration * 1000.0));
  int instruments = openmpt_module_get_num_instruments(state.mod);
  int samples = openmpt_module_get_num_samples(state.mod);
  int displayInstruments = instruments > 0 ? instruments : samples;

  jstring titleStr = env->NewStringUTF(title.c_str());
  jstring typeStr = env->NewStringUTF(type.c_str());
  jstring messageStr = env->NewStringUTF(message.c_str());
  env->SetObjectField(modVars, g_modVars.name, titleStr);
  env->SetObjectField(modVars, g_modVars.type, typeStr);
  env->SetObjectField(modVars, g_modVars.miComment, messageStr);
  env->SetIntField(modVars, g_modVars.pat, openmpt_module_get_num_patterns(state.mod));
  env->SetIntField(modVars, g_modVars.trk, openmpt_module_get_num_orders(state.mod));
  env->SetIntField(modVars, g_modVars.chn, channels);
  env->SetIntField(modVars, g_modVars.ins, instruments);
  env->SetIntField(modVars, g_modVars.smp, samples);
  env->SetIntField(modVars, g_modVars.spd, openmpt_module_get_current_speed(state.mod));
  env->SetIntField(modVars, g_modVars.bpm, static_cast<int>(std::round(openmpt_module_get_current_tempo2(state.mod))));
  env->SetIntField(modVars, g_modVars.len, openmpt_module_get_num_orders(state.mod));
  env->SetIntField(modVars, g_modVars.gvl, 64);
  env->SetIntField(modVars, g_modVars.miNumSequences, subsongs);

  jclass seqCls = env->FindClass("com/lossydragon/native/model/Sequence");
  jobjectArray seqArray = env->NewObjectArray(subsongs, seqCls, nullptr);
  for (int i = 0; i < subsongs; i++) {
    jobject seqObj = env->AllocObject(seqCls);
    env->SetIntField(seqObj, g_sequence.entryPoint, 0);
    env->SetIntField(seqObj, g_sequence.duration, durationMs);
    env->SetObjectArrayElement(seqArray, i, seqObj);
    env->DeleteLocalRef(seqObj);
  }
  env->SetObjectField(modVars, g_modVars.seqData, seqArray);

  jclass stringCls = env->FindClass("java/lang/String");
  jobjectArray insArray = env->NewObjectArray(displayInstruments, stringCls, nullptr);
  for (int i = 0; i < displayInstruments; i++) {
    std::string raw = instruments > 0 ? openmptString(openmpt_module_get_instrument_name(state.mod, i)) : openmptString(openmpt_module_get_sample_name(state.mod, i));
    std::string name = (i + 1 < 16 ? "0" : "") + std::to_string(i + 1) + " " + raw;
    jstring s = env->NewStringUTF(name.c_str());
    env->SetObjectArrayElement(insArray, i, s);
    env->DeleteLocalRef(s);
  }
  env->SetObjectField(modVars, g_modVars.instruments, insArray);

  jstring renderingStr = env->NewStringUTF("libopenmpt");
  env->SetObjectField(modVars, g_modVars.renderingEngine, renderingStr);

  env->DeleteLocalRef(titleStr);
  env->DeleteLocalRef(typeStr);
  env->DeleteLocalRef(messageStr);
  env->DeleteLocalRef(seqCls);
  env->DeleteLocalRef(seqArray);
  env->DeleteLocalRef(stringCls);
  env->DeleteLocalRef(insArray);
  env->DeleteLocalRef(renderingStr);
}

void openmpt_getPatternRow(JNIEnv* env, jint pat, jint row, jbyteArray rowNotes, jbyteArray rowInstruments, jbyteArray rowFxType, jbyteArray rowFxParm, jbyteArray rowFx2Type, jbyteArray rowFx2Parm) {
  OpenMptState& state = OpenMptState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.mod) return;
  int channels = std::min<int>(64, openmpt_module_get_num_channels(state.mod));
  std::array<jbyte, 64> notes{};
  std::array<jbyte, 64> instruments{};
  std::array<jbyte, 64> fxType{};
  std::array<jbyte, 64> fxParam{};
  std::array<jbyte, 64> fx2Type{};
  std::array<jbyte, 64> fx2Param{};
  for (int ch = 0; ch < channels; ch++) {
    notes[ch] = static_cast<jbyte>(openmpt_module_get_pattern_row_channel_command(state.mod, pat, row, ch, OPENMPT_MODULE_COMMAND_NOTE));
    instruments[ch] = static_cast<jbyte>(openmpt_module_get_pattern_row_channel_command(state.mod, pat, row, ch, OPENMPT_MODULE_COMMAND_INSTRUMENT));
    // Internal command 0 is CMD_NONE / VOLCMD_NONE, so type 0 means an empty slot.
    int effect = openmpt_module_get_pattern_row_channel_command(state.mod, pat, row, ch, OPENMPT_MODULE_COMMAND_EFFECT);
    fxType[ch] = static_cast<jbyte>(effect > 0 ? effect : -1);
    fxParam[ch] = effect > 0 ? static_cast<jbyte>(openmpt_module_get_pattern_row_channel_command(state.mod, pat, row, ch, OPENMPT_MODULE_COMMAND_PARAMETER)) : -1;
    int volEffect = openmpt_module_get_pattern_row_channel_command(state.mod, pat, row, ch, OPENMPT_MODULE_COMMAND_VOLUMEEFFECT);
    fx2Type[ch] = static_cast<jbyte>(volEffect > 0 ? volEffect : -1);
    fx2Param[ch] = volEffect > 0 ? static_cast<jbyte>(openmpt_module_get_pattern_row_channel_command(state.mod, pat, row, ch, OPENMPT_MODULE_COMMAND_VOLUME)) : -1;
  }
  env->SetByteArrayRegion(rowNotes, 0, channels, notes.data());
  env->SetByteArrayRegion(rowInstruments, 0, channels, instruments.data());
  env->SetByteArrayRegion(rowFxType, 0, channels, fxType.data());
  env->SetByteArrayRegion(rowFxParm, 0, channels, fxParam.data());
  env->SetByteArrayRegion(rowFx2Type, 0, channels, fx2Type.data());
  env->SetByteArrayRegion(rowFx2Parm, 0, channels, fx2Param.data());
}

void openmpt_getSampleData(JNIEnv* env, jboolean trigger, jint ins, jint key, jint period, jint chn, jint width, jbyteArray buffer) {
  if (!buffer) return;
  width = std::min(width, MAX_BUFFER_SIZE);
  if (width <= 0) return;
  std::array<jbyte, MAX_BUFFER_SIZE> sampleBuffer{};

  {
    OpenMptState& state = OpenMptState::instance();
    std::lock_guard<std::mutex> lock(state.mutex);
    if (!state.mod || chn < 0 || chn >= MAX_CHANNELS) {
      env->SetByteArrayRegion(buffer, 0, width, sampleBuffer.data());
      return;
    }

    openmpt_module_current_channel_state channel{};
    if (openmpt_module_get_current_channel_state(state.mod, chn, &channel) == 0 || channel.sample <= 0 || channel.increment == 0) {
      env->SetByteArrayRegion(buffer, 0, width, sampleBuffer.data());
      return;
    }

    openmpt_module_sample_state sample{};
    if (openmpt_module_get_sample_state(state.mod, channel.sample, &sample) == 0 || sample.length <= 0 || !sample.data) {
      env->SetByteArrayRegion(buffer, 0, width, sampleBuffer.data());
      return;
    }

    int64_t pos = static_cast<int64_t>(channel.position) << 32;
    int64_t step = channel.increment;
    if (step < 0) step = -step;
    for (int i = 0; i < width; i++) {
      int64_t wrapped = wrapSamplePosition(pos, sample);
      if (wrapped < 0) break;
      sampleBuffer[i] = readSampleByte(sample, static_cast<int>(wrapped >> 32));
      pos += step;
    }
  }

  env->SetByteArrayRegion(buffer, 0, width, sampleBuffer.data());
}

void openmpt_setLoopMode(JNIEnv* env, jboolean loop) {
  OpenMptState& state = OpenMptState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.loop = loop == JNI_TRUE;
  LOG_INFO("setLoopMode: loop=%d", state.loop ? 1 : 0);
  if (state.mod) {
    int32_t count = state.loop ? -1 : 0;
    int rc_result = openmpt_module_set_repeat_count(state.mod, count);
    LOG_INFO("setLoopMode: set repeat_count=%d result=%d actual=%d",
      count, rc_result, openmpt_module_get_repeat_count(state.mod));
  }
}

void openmpt_setPlaying(JNIEnv* env, jboolean value) {
  OpenMptState& state = OpenMptState::instance();
  {
    std::lock_guard<std::mutex> lock(state.mutex);
    state.playing = value == JNI_TRUE;
  }
  set_playing(value == JNI_TRUE ? 1 : 0);
}

void openmpt_freeContext(JNIEnv* env) {
  LOG_INFO("openmpt_freeContext() - stub, not yet implemented");
}
