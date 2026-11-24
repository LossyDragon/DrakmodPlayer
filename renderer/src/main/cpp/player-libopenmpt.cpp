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
#include <atomic>
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
// How often the render callback publishes channel states; the visualiser draws no faster.
constexpr int CHANNEL_SNAPSHOT_HZ = 60;

// Fixed scratch for format conversion; larger requests are rendered in chunks so the callback never allocates.
constexpr int32_t SCRATCH_FRAMES = 2048;
static std::array<int16_t, SCRATCH_FRAMES * 2> g_scratch{};
static std::array<float, SCRATCH_FRAMES * 2> g_scratch_float{};

// Published by the render callback after every read; `valid` is false while stopped.
struct OpenMptFrame {
  bool valid = false;
  int order = 0, pattern = 0, row = 0, numRows = 0, speed = 0, bpm = 0;
  int timeMs = 0, totalMs = 0, channels = 0, playingChannels = 0, sequence = 0, loopCount = 0;
};
static DelayLine<OpenMptFrame, SNAPSHOT_SLOTS> g_frame;

// Per-channel play state for the visualiser. Only the render callback may read it from the
// module (libopenmpt is not thread-safe), so it is published the same way as the frame.
struct OpenMptChannels {
  int count = 0;
  std::array<bool, MAX_CHANNELS> ok{};
  std::array<openmpt_module_current_channel_state, MAX_CHANNELS> ch{};
};
static DelayLine<OpenMptChannels, SNAPSHOT_SLOTS> g_channels;
static OpenMptChannels g_channels_scratch; // audio thread only

struct SubsongInfo {
  int ms = 0;
  int speed = 0;
  int bpm = 0;
};

struct OpenMptState {
  // Serialises engine-thread mutations against visualisation readers. Never taken by the audio thread.
  std::mutex mutex;
  bool initialized = false;

  // Read by the render callback without a lock. Every write happens with the callback
  // drained (RenderPause / set_playing(0)); they are atomic so that hand-over is well defined.
  std::atomic<openmpt_module*> mod{nullptr};
  std::atomic<bool> playing{false};
  std::atomic<bool> loop{false};
  std::atomic<int> actual_rate{44100};
  std::atomic<int> selected_subsong{0};
  std::atomic<double> duration{0.0};
  std::atomic<int> restart_order{0}; // where repeat-one resumes, for the selected subsong
  std::atomic<int> restart_row{0};
  // Audio thread only; reset with the callback drained.
  int loop_count = 0; // repeat-one restarts
  int frames_since_channels = 0;

  // Fixed at load, per subsong: readers must not ask the module for anything the renderer changes.
  std::vector<SubsongInfo> subsongs;
  // Our own record of the mutes, so a query never has to touch the module.
  std::array<bool, MAX_CHANNELS> muted{};
  // used to derive pitch bend: period when the note last triggered
  std::array<int, MAX_CHANNELS> base_periods{};
  std::array<int, MAX_CHANNELS> prev_keys{};

  static OpenMptState& instance() {
    static OpenMptState s;
    return s;
  }
};

struct ModuleDeleter {
  void operator()(openmpt_module* mod) const {
    openmpt_module_destroy(mod);
  }
};
using ModulePtr = std::unique_ptr<openmpt_module, ModuleDeleter>;

static std::string openmptString(const char* value) {
  if (!value) return "";
  std::string result(value);
  openmpt_free_string(value);
  return result;
}

static std::string metadata(openmpt_module* mod, const char* key) {
  return openmptString(openmpt_module_get_metadata(mod, key));
}

// libopenmpt does not clip float output.
static int32_t floatToInt32(float v) {
  const double clipped = std::min(1.0, std::max(-1.0, static_cast<double>(v)));
  return static_cast<int32_t>(clipped * 2147483647.0);
}

// Renders up to `count` frames into `audioData` starting at frame `offset`, converting
// libopenmpt's stereo output to the stream's layout. A 32-bit stream is rendered in float so
// it carries more than 16 bits of precision. Returns the frames written; fewer than `count`
// means the module ended.
static int32_t readFrames(openmpt_module* mod, int rate, void* audioData, int32_t offset, int32_t count, int32_t numChannels, int32_t bytesPerSample) {
  if (bytesPerSample == 2 && numChannels == 2) {
    auto* out = static_cast<int16_t*>(audioData) + static_cast<size_t>(offset) * 2;
    return static_cast<int32_t>(openmpt_module_read_interleaved_stereo(mod, rate, count, out));
  }
  int32_t frames = 0;
  while (frames < count) {
    const int32_t want = std::min(count - frames, SCRATCH_FRAMES);
    const size_t at = static_cast<size_t>(offset) + frames;
    int32_t got;
    if (bytesPerSample == 2) {
      got = static_cast<int32_t>(openmpt_module_read_interleaved_stereo(mod, rate, want, g_scratch.data()));
      auto* out = static_cast<int16_t*>(audioData) + at;
      for (int32_t i = 0; i < got; i++) out[i] = static_cast<int16_t>((g_scratch[i * 2] + g_scratch[i * 2 + 1]) / 2);
    } else {
      got = static_cast<int32_t>(openmpt_module_read_interleaved_float_stereo(mod, rate, want, g_scratch_float.data()));
      if (numChannels == 1) {
        auto* out = static_cast<int32_t*>(audioData) + at;
        for (int32_t i = 0; i < got; i++) out[i] = floatToInt32((g_scratch_float[i * 2] + g_scratch_float[i * 2 + 1]) * 0.5f);
      } else {
        auto* out = static_cast<int32_t*>(audioData) + at * 2;
        for (int32_t i = 0; i < got * 2; i++) out[i] = floatToInt32(g_scratch_float[i]);
      }
    }
    frames += got;
    if (got < want) break;
  }
  return frames;
}

static int renderOpenMpt(void* audioData, int32_t numFrames, int32_t numChannels, int32_t bytesPerSample) {
  OpenMptState& state = OpenMptState::instance();
  const size_t frameBytes = static_cast<size_t>(numChannels) * bytesPerSample;
  const size_t numBytes = static_cast<size_t>(numFrames) * frameBytes;

  openmpt_module* mod = state.mod.load(std::memory_order_acquire);
  if (!mod || !state.playing.load(std::memory_order_acquire)) {
    memset(audioData, 0, numBytes);
    return 0;
  }
  const int rate = state.actual_rate.load(std::memory_order_relaxed);
  const bool loop = state.loop.load(std::memory_order_relaxed);

  int32_t frames = readFrames(mod, rate, audioData, 0, numFrames, numChannels, bytesPerSample);
  if (frames < numFrames && loop) {
    // Repeat-one. libopenmpt's own repeat never rewinds its position counter, so the loop is
    // done here: jump to the module's restart point and fill the rest of this buffer from it.
    openmpt_module_set_position_order_row(mod, state.restart_order.load(std::memory_order_relaxed), state.restart_row.load(std::memory_order_relaxed));
    state.loop_count++;
    frames += readFrames(mod, rate, audioData, frames, numFrames - frames, numChannels, bytesPerSample);
  }

  bool ended = false;
  if (frames < numFrames) {
    memset(static_cast<char*>(audioData) + frames * frameBytes, 0, (numFrames - frames) * frameBytes);
    // The caller silences the whole buffer on "ended", so a short read that still holds the
    // last frames of the module is played out; the next read is empty and reports the end.
    ended = !loop && frames == 0;
  }

  OpenMptFrame f;
  f.valid = true;
  f.order = openmpt_module_get_current_order(mod);
  f.pattern = openmpt_module_get_current_pattern(mod);
  f.row = openmpt_module_get_current_row(mod);
  f.numRows = openmpt_module_get_pattern_num_rows(mod, f.pattern);
  f.speed = openmpt_module_get_current_speed(mod);
  f.bpm = static_cast<int>(std::round(openmpt_module_get_current_tempo2(mod)));
  f.timeMs = static_cast<int>(std::round(openmpt_module_get_position_seconds(mod) * 1000.0));
  f.totalMs = static_cast<int>(std::round(state.duration.load(std::memory_order_relaxed) * 1000.0));
  f.channels = openmpt_module_get_num_channels(mod);
  f.playingChannels = openmpt_module_get_current_playing_channels(mod);
  f.sequence = state.selected_subsong.load(std::memory_order_relaxed);
  f.loopCount = state.loop_count;
  state.frames_since_channels += numFrames;
  if (state.frames_since_channels >= rate / CHANNEL_SNAPSHOT_HZ) {
    state.frames_since_channels = 0;
    const int64_t at = audio_render_frame() + numFrames;
    g_frame.push(at, [&](OpenMptFrame& d) { d = f; });
    OpenMptChannels& c = g_channels_scratch;
    c.count = std::min<int>(MAX_CHANNELS, f.channels);
    for (int i = 0; i < c.count; i++) c.ok[i] = openmpt_module_get_current_channel_state(mod, i, &c.ch[i]) != 0;
    g_channels.push(at, [&](OpenMptChannels& d) { d = c; });
  }

  return ended ? -1 : 0;
}

// The loop point of the selected subsong (the start, for most modules).
static void setRestartPoint(OpenMptState& state, openmpt_module* mod) {
  const int subsong = state.selected_subsong;
  state.restart_order = std::max(0, openmpt_module_get_restart_order(mod, subsong));
  state.restart_row = std::max(0, openmpt_module_get_restart_row(mod, subsong));
}

static void resetSnapshots() {
  g_frame.reset();
  g_channels.reset();
}

// Callers hold state.mutex and have the callback drained (RenderPause or set_playing(0)).
static void destroyModule(OpenMptState& state) {
  if (openmpt_module* mod = state.mod.exchange(nullptr, std::memory_order_acq_rel)) openmpt_module_destroy(mod);
  state.playing = false;
  state.duration = 0.0;
  state.selected_subsong = 0;
  state.loop_count = 0;
  state.frames_since_channels = 0;
  state.subsongs.clear();
  state.muted.fill(false);
  state.base_periods.fill(0);
  state.prev_keys.fill(-1);
  resetSnapshots();
  clear_module_ended();
}

// Length and starting speed/tempo of every subsong. Only for a module the renderer does not
// have yet: selecting a subsong moves the play position.
static std::vector<SubsongInfo> measureSubsongs(openmpt_module* mod) {
  const int selected = openmpt_module_get_selected_subsong(mod);
  std::vector<SubsongInfo> subsongs(std::max(1, openmpt_module_get_num_subsongs(mod)));
  for (int i = 0; i < static_cast<int>(subsongs.size()); i++) {
    openmpt_module_select_subsong(mod, i); // on failure the current selection is measured instead
    subsongs[i].ms = static_cast<int>(std::round(openmpt_module_get_duration_seconds(mod) * 1000.0));
    subsongs[i].speed = openmpt_module_get_current_speed(mod);
    subsongs[i].bpm = static_cast<int>(std::round(openmpt_module_get_current_tempo2(mod)));
  }
  openmpt_module_select_subsong(mod, selected);
  return subsongs;
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
  {
    std::lock_guard<std::mutex> lock(state.mutex);
    state.actual_rate = actual_rate;
    state.initialized = true;
  }
  set_render_callback(renderOpenMpt);
  return JNI_TRUE;
}

jboolean openmpt_setSequence(JNIEnv* env, jint seq) {
  OpenMptState& state = OpenMptState::instance();
  RenderPause pause;
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.mod || seq < 0) return JNI_FALSE;
  if (openmpt_module_select_subsong(state.mod, seq) == 0) return JNI_FALSE;
  state.selected_subsong = seq;
  state.duration = openmpt_module_get_duration_seconds(state.mod);
  setRestartPoint(state, state.mod);
  resetSnapshots();
  clear_module_ended();
  return JNI_TRUE;
}

jboolean openmpt_testFd(JNIEnv* env, jint fd, jobject modInfo) {
  UniqueFd owned(fd); // ownership was transferred by ParcelFileDescriptor.detachFd()
  lseek(fd, 0, SEEK_SET);
  std::vector<unsigned char> data = readFd(fd);
  if (data.empty()) return JNI_FALSE;

  const size_t probe_size = std::min(data.size(), openmpt_probe_file_header_get_recommended_size());
  int probe = openmpt_probe_file_header(OPENMPT_PROBE_FILE_HEADER_FLAGS_DEFAULT, data.data(), probe_size, data.size(), nullptr, nullptr, nullptr, nullptr, nullptr, nullptr);
  if (probe == OPENMPT_PROBE_FILE_HEADER_RESULT_FAILURE) return JNI_FALSE;

  // Header-only parse for the metadata (and to settle an indeterminate probe).
  static const openmpt_module_initial_ctl ctls[] = {
    {"load.skip_samples", "1"},
    {"load.skip_patterns", "1"},
    {"load.skip_plugins", "1"},
    {"load.skip_subsongs_init", "1"},
    {nullptr, nullptr},
  };
  int error = OPENMPT_ERROR_OK;
  const char* error_message = nullptr;
  ModulePtr mod(openmpt_module_create_from_memory2(data.data(), data.size(), nullptr, nullptr, nullptr, nullptr, &error, &error_message, ctls));

  if (!mod) {
    openmpt_free_string(error_message);
    return JNI_FALSE;
  }

  std::string title = metadata(mod.get(), "title");
  std::string type_long = metadata(mod.get(), "type_long");

  jstring name = env->NewStringUTF(title.c_str());
  jstring typeStr = env->NewStringUTF(type_long.c_str());
  env->SetObjectField(modInfo, g_modInfo.name, name);
  env->SetObjectField(modInfo, g_modInfo.type, typeStr);
  env->DeleteLocalRef(name);
  env->DeleteLocalRef(typeStr);
  return JNI_TRUE;
}

jint openmpt_deinit(JNIEnv* env) {
  OpenMptState& state = OpenMptState::instance();
  set_playing(0);
  {
    std::lock_guard<std::mutex> lock(state.mutex);
    destroyModule(state);
    state.initialized = false;
  }
  close_audio();
  return 0;
}

jint openmpt_endPlayer(JNIEnv* env) {
  set_playing(0);
  OpenMptState& state = OpenMptState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.playing = false;
  resetSnapshots();
  return 0;
}

jint openmpt_getPatternRows(JNIEnv* env, jint pat) {
  OpenMptState& state = OpenMptState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.mod || pat < 0 || pat >= openmpt_module_get_num_patterns(state.mod)) return 0;
  return openmpt_module_get_pattern_num_rows(state.mod, pat);
}

jint openmpt_getPlayer(JNIEnv* env, jint parm) {
  return 0; // libopenmpt has no libxmp-style player parameters to read back
}

jint openmpt_loadFd(JNIEnv* env, jint fd, jobject modInfo) {
  std::vector<unsigned char> data;
  {
    UniqueFd owned(fd);
    data = readFd(fd);
  }
  if (data.empty()) return -1;

  int error = OPENMPT_ERROR_OK;
  const char* error_message = nullptr;
  ModulePtr loaded(openmpt_module_create_from_memory2(data.data(), data.size(), nullptr, nullptr, nullptr, nullptr, &error, &error_message, nullptr));

  if (!loaded) {
    LOG_WARN("loadModuleFd failed: %d %s", error, error_message ? error_message : "");
    openmpt_free_string(error_message);
    return error != 0 ? error : -2;
  }

  // Everything that can fail or is slow happens before the module is handed to the renderer.
  std::string title = metadata(loaded.get(), "title");
  std::string type = metadata(loaded.get(), "type");
  std::vector<SubsongInfo> subsongs = measureSubsongs(loaded.get());
  std::array<bool, MAX_CHANNELS> muted{}; // a module can come with channels muted
  const int channels = std::min<int>(MAX_CHANNELS, openmpt_module_get_num_channels(loaded.get()));
  for (int i = 0; i < channels; i++) muted[i] = openmpt_module_channel_mute(loaded.get(), i, -1) > 0;

  OpenMptState& state = OpenMptState::instance();
  {
    RenderPause pause;
    pause.stay_stopped(); // Kotlin restarts via startPlayer
    std::lock_guard<std::mutex> lock(state.mutex);
    destroyModule(state);
    openmpt_module* mod = loaded.release();
    state.subsongs = std::move(subsongs);
    state.muted = muted;
    state.selected_subsong = std::max(0, openmpt_module_get_selected_subsong(mod));
    state.duration = openmpt_module_get_duration_seconds(mod);
    setRestartPoint(state, mod);
    state.mod = mod;
  }

  jstring name = env->NewStringUTF(title.c_str());
  jstring typeStr = env->NewStringUTF(type.c_str());
  env->SetObjectField(modInfo, g_modInfo.name, name);
  env->SetObjectField(modInfo, g_modInfo.type, typeStr);
  env->DeleteLocalRef(name);
  env->DeleteLocalRef(typeStr);
  LOG_INFO("loadModuleFd - loaded: %s (%s)", title.c_str(), type.c_str());
  return 0;
}

jint openmpt_mute(JNIEnv* env, jint chn, jint status) {
  OpenMptState& state = OpenMptState::instance();
  if (chn < 0 || chn >= MAX_CHANNELS) return -1;
  if (status < 0) {
    // A query is answered from our record: asking the module means silencing the renderer first.
    std::lock_guard<std::mutex> lock(state.mutex);
    return state.mod ? (state.muted[chn] ? 1 : 0) : -1;
  }
  RenderPause pause;
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.mod) return -1;
  const int previous = openmpt_module_channel_mute(state.mod, chn, status);
  if (previous >= 0) state.muted[chn] = status != 0;
  return previous;
}

jint openmpt_playAudio(JNIEnv* env) {
  return play_audio();
}

jint openmpt_releaseModule(JNIEnv* env) {
  OpenMptState& state = OpenMptState::instance();
  RenderPause pause;
  pause.stay_stopped();
  std::lock_guard<std::mutex> lock(state.mutex);
  destroyModule(state);
  return 0;
}

jint openmpt_seek(JNIEnv* env, jint time) {
  OpenMptState& state = OpenMptState::instance();
  RenderPause pause;
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.mod) return -1;
  openmpt_module_set_position_seconds(state.mod, time / 1000.0);
  resetSnapshots(); // show the new position at once instead of the buffered tail
  return 0;
}

jint openmpt_setPlayer(JNIEnv* env, jint parm, jint value) {
  OpenMptState& state = OpenMptState::instance();
  RenderPause pause;
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.mod) return 0;
  if (parm == 1) openmpt_module_ctl_set_integer(state.mod, "render.stereoseparation", value);
  if (parm == 2) openmpt_module_ctl_set_integer(state.mod, "render.interpolationfilterlength", value);
  if (parm == 7) openmpt_module_ctl_set_integer(state.mod, "render.mastergain.millibel", (value - 100) * 60);
  return 0;
}

jint openmpt_setResampler(JNIEnv* env, jint mode) {
  OpenMptState& state = OpenMptState::instance();
  RenderPause pause;
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
  // Render at the rate the Oboe stream was actually opened with (set by openmpt_init),
  // never the requested one: a mismatch here plays everything at the wrong pitch.
  (void)rate;
  (void)format;
  RenderPause pause;
  pause.stay_stopped();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.mod) {
    LOG_ERROR("startPlayer - no module loaded");
    return -1;
  }
  LOG_INFO("startPlayer: rate=%d loop=%d duration=%.2fs",
    state.actual_rate.load(), state.loop ? 1 : 0, state.duration.load());
  state.playing = true;
  resetSnapshots();
  set_render_callback(renderOpenMpt);
  set_playing(1);
  return 0;
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

void openmpt_getAudioStats(JNIEnv* env, jobject info) {
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
  OpenMptState& state = OpenMptState::instance();

  int channels = 0;
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

  {
    std::lock_guard<std::mutex> lock(state.mutex);
    if (!state.mod) return;
    static thread_local OpenMptChannels snap; // 3.6 KB; keep it off the stack of UI threads
    if (!g_channels.read(audio_presented_frame(), snap)) return;
    channels = snap.count;

    for (int i = 0; i < channels; i++) {
      if (!snap.ok[i]) continue;
      const openmpt_module_current_channel_state& ch = snap.ch[i];
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
      pitchbends[i] = (state.base_periods[i] > 0 && ch.period > 0) ? state.base_periods[i] - ch.period : 0;
      notes[i] = ch.note;
      samples[i] = ch.sample;
    }
  }

  auto set = [&](jfieldID fid, const std::array<int, MAX_CHANNELS>& values) {
    auto arr = (jintArray)env->GetObjectField(channelInfo, fid);
    if (arr) env->SetIntArrayRegion(arr, 0, channels, values.data());
    env->DeleteLocalRef(arr);
  };
  set(g_channelInfo.volumes, volumes);
  set(g_channelInfo.finalVols, finalVols);
  set(g_channelInfo.pans, pans);
  set(g_channelInfo.instruments, instruments);
  set(g_channelInfo.keys, keys);
  set(g_channelInfo.periods, periods);
  set(g_channelInfo.holdVols, volumes); // no volume-column history in libopenmpt; mirror the channel volume
  set(g_channelInfo.positions, positions);
  set(g_channelInfo.pitchbends, pitchbends);
  set(g_channelInfo.notes, notes);
  set(g_channelInfo.samples, samples);
}

void openmpt_getFrameInfo(JNIEnv* env, jobject frameInfo) {
  OpenMptFrame f;
  if (!g_frame.read(audio_presented_frame(), f) || !f.valid) return;
  env->SetIntField(frameInfo, g_frameInfo.pos, f.order);
  env->SetIntField(frameInfo, g_frameInfo.pattern, f.pattern);
  env->SetIntField(frameInfo, g_frameInfo.row, f.row);
  env->SetIntField(frameInfo, g_frameInfo.numRows, f.numRows);
  env->SetIntField(frameInfo, g_frameInfo.frame, 0);
  env->SetIntField(frameInfo, g_frameInfo.speed, f.speed);
  env->SetIntField(frameInfo, g_frameInfo.bpm, f.bpm);
  env->SetIntField(frameInfo, g_frameInfo.time, f.timeMs);
  env->SetIntField(frameInfo, g_frameInfo.totalTime, f.totalMs);
  env->SetIntField(frameInfo, g_frameInfo.frameTime, 0);
  env->SetIntField(frameInfo, g_frameInfo.bufferSize, 0);
  env->SetIntField(frameInfo, g_frameInfo.totalSize, 0);
  env->SetIntField(frameInfo, g_frameInfo.volume, 0);
  env->SetIntField(frameInfo, g_frameInfo.loopCount, f.loopCount);
  env->SetIntField(frameInfo, g_frameInfo.virtChannels, f.channels);
  env->SetIntField(frameInfo, g_frameInfo.virtUsed, f.playingChannels);
  env->SetIntField(frameInfo, g_frameInfo.sequence, f.sequence);
}

void openmpt_getModVars(JNIEnv* env, jobject modVars) {
  OpenMptState& state = OpenMptState::instance();

  std::string title, type, message;
  int channels, subsongs, instruments, samples, patterns, orders, speed, bpm;
  std::vector<SubsongInfo> subsongInfo;
  std::vector<std::string> instrNames;

  {
    std::lock_guard<std::mutex> lock(state.mutex);
    if (!state.mod) return;

    title = metadata(state.mod, "title");
    type = metadata(state.mod, "type");
    message = metadata(state.mod, "message");
    channels = openmpt_module_get_num_channels(state.mod);
    subsongInfo = state.subsongs;
    subsongs = static_cast<int>(subsongInfo.size());
    const int current = std::min(std::max(0, state.selected_subsong.load()), subsongs - 1);
    instruments = openmpt_module_get_num_instruments(state.mod);
    samples = openmpt_module_get_num_samples(state.mod);
    patterns = openmpt_module_get_num_patterns(state.mod);
    orders = openmpt_module_get_num_orders(state.mod);
    speed = subsongs > 0 ? subsongInfo[current].speed : 0;
    bpm = subsongs > 0 ? subsongInfo[current].bpm : 0;

    int displayInstruments = instruments > 0 ? instruments : samples;
    instrNames.reserve(displayInstruments);
    for (int i = 0; i < displayInstruments; i++) {
      std::string raw = instruments > 0 ? openmptString(openmpt_module_get_instrument_name(state.mod, i)) : openmptString(openmpt_module_get_sample_name(state.mod, i));
      std::array<char, 8> index{}; // two hex digits, like libxmp; the UI relies on the width
      snprintf(index.data(), index.size(), "%02X ", i + 1);
      instrNames.push_back(index.data() + raw);
    }
  }

  jstring titleStr = env->NewStringUTF(title.c_str());
  jstring typeStr = env->NewStringUTF(type.c_str());
  jstring messageStr = env->NewStringUTF(message.c_str());
  env->SetObjectField(modVars, g_modVars.name, titleStr);
  env->SetObjectField(modVars, g_modVars.type, typeStr);
  env->SetObjectField(modVars, g_modVars.miComment, messageStr);
  env->SetIntField(modVars, g_modVars.pat, patterns);
  env->SetIntField(modVars, g_modVars.trk, orders);
  env->SetIntField(modVars, g_modVars.chn, channels);
  env->SetIntField(modVars, g_modVars.ins, instruments);
  env->SetIntField(modVars, g_modVars.smp, samples);
  env->SetIntField(modVars, g_modVars.spd, speed);
  env->SetIntField(modVars, g_modVars.bpm, bpm);
  env->SetIntField(modVars, g_modVars.len, orders);
  env->SetIntField(modVars, g_modVars.gvl, 64);
  env->SetIntField(modVars, g_modVars.miNumSequences, subsongs);

  jclass seqCls = env->FindClass("com/lossydragon/native/model/Sequence");
  jobjectArray seqArray = env->NewObjectArray(subsongs, seqCls, nullptr);
  for (int i = 0; i < subsongs; i++) {
    jobject seqObj = env->AllocObject(seqCls);
    env->SetIntField(seqObj, g_sequence.entryPoint, 0);
    env->SetIntField(seqObj, g_sequence.duration, subsongInfo[i].ms);
    env->SetObjectArrayElement(seqArray, i, seqObj);
    env->DeleteLocalRef(seqObj);
  }
  env->SetObjectField(modVars, g_modVars.seqData, seqArray);

  jclass stringCls = env->FindClass("java/lang/String");
  jobjectArray insArray = env->NewObjectArray(static_cast<jsize>(instrNames.size()), stringCls, nullptr);
  for (int i = 0; i < static_cast<int>(instrNames.size()); i++) {
    jstring s = env->NewStringUTF(instrNames[i].c_str());
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
  if (!rowNotes || !rowInstruments || !rowFxType || !rowFxParm || !rowFx2Type || !rowFx2Parm) return;
  OpenMptState& state = OpenMptState::instance();

  int channels = 0;
  std::array<jbyte, MAX_CHANNELS> notes{};
  std::array<jbyte, MAX_CHANNELS> instruments{};
  std::array<jbyte, MAX_CHANNELS> fxType{};
  std::array<jbyte, MAX_CHANNELS> fxParam{};
  std::array<jbyte, MAX_CHANNELS> fx2Type{};
  std::array<jbyte, MAX_CHANNELS> fx2Param{};

  {
    std::lock_guard<std::mutex> lock(state.mutex);
    if (!state.mod) return;
    if (pat < 0 || pat >= openmpt_module_get_num_patterns(state.mod)) return;
    if (row < 0 || row >= openmpt_module_get_pattern_num_rows(state.mod, pat)) return;
    channels = std::min<int>(MAX_CHANNELS, openmpt_module_get_num_channels(state.mod));
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

  [&]() {
    OpenMptState& state = OpenMptState::instance();
    std::lock_guard<std::mutex> lock(state.mutex);
    if (!state.mod || chn < 0 || chn >= MAX_CHANNELS) return;

    static thread_local OpenMptChannels snap;
    if (!g_channels.read(audio_presented_frame(), snap) || chn >= snap.count || !snap.ok[chn]) return;
    const openmpt_module_current_channel_state& channel = snap.ch[chn];
    if (channel.sample <= 0 || channel.increment == 0) return;

    // Sample data and its loop points do not change after load, so this is safe beside the renderer.
    openmpt_module_sample_state sample{};
    if (openmpt_module_get_sample_state(state.mod, channel.sample, &sample) == 0 || sample.length <= 0 || !sample.data) return;

    int64_t pos = static_cast<int64_t>(channel.position) << 32;
    int64_t step = channel.increment;
    if (step < 0) step = -step;
    for (int i = 0; i < width; i++) {
      int64_t wrapped = wrapSamplePosition(pos, sample);
      if (wrapped < 0) break;
      sampleBuffer[i] = readSampleByte(sample, static_cast<int>(wrapped >> 32));
      pos += step;
    }
  }();

  env->SetByteArrayRegion(buffer, 0, width, sampleBuffer.data());
}

void openmpt_setLoopMode(JNIEnv* env, jboolean loop) {
  // The render callback does the looping itself, so this is all it needs.
  OpenMptState::instance().loop = loop == JNI_TRUE;
}

void openmpt_setPlaying(JNIEnv* env, jboolean value) {
  OpenMptState& state = OpenMptState::instance();
  if (value != JNI_TRUE) set_playing(0);
  {
    std::lock_guard<std::mutex> lock(state.mutex);
    state.playing = value == JNI_TRUE;
  }
  if (value == JNI_TRUE) set_playing(1);
}

void openmpt_freeContext(JNIEnv* env) {
  OpenMptState& state = OpenMptState::instance();
  set_playing(0);
  std::lock_guard<std::mutex> lock(state.mutex);
  destroyModule(state);
  state.initialized = false;
}
