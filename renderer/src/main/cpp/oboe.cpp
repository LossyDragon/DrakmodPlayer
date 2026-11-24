#include "audio.h"
#include <android/log.h>
#include <atomic>
#include <cstring>
#include <memory>
#include <oboe/Oboe.h>
#include <pthread.h>
#include <sched.h>
#include <time.h>

#define TAG "DrakPlayer Oboe"
#define LOG_INFO(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOG_WARN(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOG_ERROR(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define lock() pthread_mutex_lock(&mutex)
#define unlock() pthread_mutex_unlock(&mutex)

// mirror XMP_FORMAT_MONO / XMP_FORMAT_32BIT without pulling xmp.h into the audio layer
constexpr int FORMAT_MONO = 1 << 2;
constexpr int FORMAT_32BIT = 1 << 3;

// true = callback calls the render callback; false = output silence
static std::atomic<bool> playing(false);
// true while inside onAudioReady; paired with `playing` via seq_cst so a drain can never miss a running render
static std::atomic<bool> in_callback(false);
// set by callback when the render callback returns < 0 (module ended)
static std::atomic<bool> module_ended(false);
// set by onErrorAfterClose when Oboe reports ErrorDisconnected
static std::atomic<bool> audio_disconnected(false);
// installed by the active backend (render_xmp / renderOpenMpt); nullptr = silence
static std::atomic<AudioRenderCallback> render_callback(nullptr);
static std::atomic<int32_t> underrun_count(0);

// Never destroyed: callers lock it outside the open/close window.
static pthread_mutex_t mutex = PTHREAD_MUTEX_INITIALIZER;
static std::shared_ptr<oboe::AudioStream> audio_stream; // written under mutex
static int actual_channels = 2;
static int effective_format_flags = 0;
static int requested_buffer_ms = 0;

// audio thread only
static int64_t render_frame = 0;

// Output position anchor, extrapolated between refreshes; under mutex.
constexpr int64_t ANCHOR_REFRESH_NS = 50'000'000;
static int64_t anchor_frame = -1;
static int64_t anchor_ns = 0;
static int64_t anchor_taken_ns = 0;

static int64_t now_ns() {
  timespec t{};
  clock_gettime(CLOCK_MONOTONIC, &t);
  return t.tv_sec * 1000000000LL + t.tv_nsec;
}

void note_underrun() {
  underrun_count.fetch_add(1, std::memory_order_relaxed);
}

// Asks Oboe for roughly `ms` of buffering. Oboe clamps to the device's capacity range.
static void apply_buffer_size(const std::shared_ptr<oboe::AudioStream>& stream, int ms) {
  if (!stream || ms <= 0) return;
  const int32_t frames = static_cast<int32_t>(static_cast<int64_t>(stream->getSampleRate()) * ms / 1000);
  auto result = stream->setBufferSizeInFrames(frames);
  if (result) {
    LOG_INFO("Buffer size: requested %d ms (%d frames), got %d frames (capacity %d)",
      ms, frames, result.value(), stream->getBufferCapacityInFrames());
  } else {
    LOG_WARN("setBufferSizeInFrames(%d) failed: %s", frames, oboe::convertToText(result.error()));
  }
}

// Spin until the current callback invocation finishes (at most one burst period, ~5ms).
static void wait_for_callback() {
  while (in_callback.load(std::memory_order_seq_cst)) sched_yield();
}

class AudioCallback : public oboe::AudioStreamDataCallback,
                      public oboe::AudioStreamErrorCallback {
public:
  oboe::DataCallbackResult onAudioReady(oboe::AudioStream* audioStream, void* audioData, int32_t numFrames) override {
    in_callback.store(true, std::memory_order_seq_cst);

    const int numChannels = audioStream->getChannelCount();
    const int bytesPerSample = (audioStream->getFormat() == oboe::AudioFormat::I32) ? 4 : 2;
    const int numBytes = numFrames * numChannels * bytesPerSample;

    AudioRenderCallback callback = render_callback.load(std::memory_order_acquire);
    if (playing.load(std::memory_order_seq_cst) && callback != nullptr) {
      render_frame = audioStream->getFramesWritten();
      timespec t0{};
      clock_gettime(CLOCK_MONOTONIC, &t0);
      int ret = callback(audioData, numFrames, numChannels, bytesPerSample);
      timespec t1{};
      clock_gettime(CLOCK_MONOTONIC, &t1);
      // Rendering that takes longer than the audio it produced can't keep up: an app-side glitch.
      const int64_t spent_ns = (t1.tv_sec - t0.tv_sec) * 1000000000LL + (t1.tv_nsec - t0.tv_nsec);
      const int64_t budget_ns = static_cast<int64_t>(numFrames) * 1000000000LL / audioStream->getSampleRate();
      if (spent_ns > budget_ns) note_underrun();
      if (ret < 0) {
        module_ended.store(true, std::memory_order_release);
        memset(audioData, 0, numBytes);
      }
    } else {
      memset(audioData, 0, numBytes);
    }

    in_callback.store(false, std::memory_order_seq_cst);
    return oboe::DataCallbackResult::Continue;
  }

  // Runs on Oboe's error thread, not the audio thread; touches only atomics.
  void onErrorAfterClose(oboe::AudioStream* oboeStream, oboe::Result error) override {
    LOG_ERROR("Stream closed due to error: %s", oboe::convertToText(error));
    if (error == oboe::Result::ErrorDisconnected) audio_disconnected.store(true, std::memory_order_release);
  }
};

// Stateless and never deleted: Oboe may still call into it from its error thread after close().
static AudioCallback audio_callback;

void set_render_callback(AudioRenderCallback callback) {
  render_callback.store(callback, std::memory_order_release);
}

void set_playing(int val) {
  if (!val) {
    playing.store(false, std::memory_order_seq_cst);
    wait_for_callback();
  } else {
    module_ended.store(false, std::memory_order_release);
    playing.store(true, std::memory_order_seq_cst);
  }
}

int is_playing() {
  return playing.load(std::memory_order_seq_cst) ? 1 : 0;
}

int pause_render() {
  bool was = playing.exchange(false, std::memory_order_seq_cst);
  wait_for_callback();
  return was ? 1 : 0;
}

void resume_render(int was_playing) {
  if (was_playing) playing.store(true, std::memory_order_seq_cst);
}

int64_t audio_render_frame() {
  return render_frame;
}

int64_t audio_presented_frame() {
  const int64_t now = now_ns();
  lock();
  if (!audio_stream) {
    unlock();
    return -1;
  }
  const int64_t written = audio_stream->getFramesWritten();
  if (anchor_frame < 0 || now - anchor_taken_ns > ANCHOR_REFRESH_NS) {
    int64_t frame = 0, time = 0;
    if (audio_stream->getTimestamp(CLOCK_MONOTONIC, &frame, &time) == oboe::Result::OK) {
      anchor_frame = frame;
      anchor_ns = time;
    } else {
      // No timestamps (OpenSL ES, or just started): assume a full buffer ahead of the output.
      anchor_frame = written - audio_stream->getBufferSizeInFrames();
      if (anchor_frame < 0) anchor_frame = 0;
      anchor_ns = now;
    }
    anchor_taken_ns = now;
  }
  int64_t frame = anchor_frame + (now - anchor_ns) * audio_stream->getSampleRate() / 1000000000LL;
  unlock();
  if (frame > written) frame = written;
  return frame < 0 ? 0 : frame;
}

int has_module_ended() {
  return module_ended.load(std::memory_order_acquire) ? 1 : 0;
}

void clear_module_ended() {
  module_ended.store(false, std::memory_order_release);
}

int has_audio_disconnected() {
  return audio_disconnected.load(std::memory_order_acquire) ? 1 : 0;
}

static void oboe_close() {
  lock();
  if (audio_stream) {
    audio_stream->stop();
    audio_stream->close();
    audio_stream.reset();
  }
  unlock();
}

static int oboe_open(int sample_rate, int performance_mode, int channel_count, int audio_api, int format_flags) {
  oboe_close(); // a previous stream (e.g. after disconnect) must be fully closed before reusing the callback

  oboe::AudioStreamBuilder builder;

  const auto pMode = [&]() -> oboe::PerformanceMode {
    switch (performance_mode) {
      case 1:
        return oboe::PerformanceMode::None;
      case 2:
        return oboe::PerformanceMode::PowerSaving;
      default:
        return oboe::PerformanceMode::LowLatency;
    }
  }();

  const auto cCount = (channel_count == 1) ? oboe::ChannelCount::Mono : oboe::ChannelCount::Stereo;

  const auto aApi = [&]() -> oboe::AudioApi {
    switch (audio_api) {
      case 1:
        return oboe::AudioApi::AAudio;
      case 2:
        return oboe::AudioApi::OpenSLES;
      default:
        return oboe::AudioApi::Unspecified;
    }
  }();

  const auto oboeFormat = (format_flags & FORMAT_32BIT) ? oboe::AudioFormat::I32 : oboe::AudioFormat::I16;

  builder.setDirection(oboe::Direction::Output)
    ->setAudioApi(aApi)
    ->setChannelCount(cCount)
    ->setDataCallback(&audio_callback)
    ->setErrorCallback(&audio_callback)
    ->setFormat(oboeFormat)
    ->setPerformanceMode(pMode)
    ->setSampleRate(sample_rate)
    ->setSharingMode(oboe::SharingMode::Shared);

  // The capacity is an upper bound for setBufferSizeInFrames(); request enough to
  // honour the user's buffer preference (the device may still hand back less).
  if (requested_buffer_ms > 0 && sample_rate > 0) {
    builder.setBufferCapacityInFrames(static_cast<int32_t>(static_cast<int64_t>(sample_rate) * requested_buffer_ms / 1000));
  }

  std::shared_ptr<oboe::AudioStream> stream;
  oboe::Result result = builder.openStream(stream);
  if (result != oboe::Result::OK || !stream) {
    LOG_ERROR("Failed to open stream: %s", oboe::convertToText(result));
    return -1;
  }

  audio_disconnected.store(false, std::memory_order_release);
  underrun_count.store(0, std::memory_order_relaxed);
  actual_channels = stream->getChannelCount();
  apply_buffer_size(stream, requested_buffer_ms);

  lock();
  audio_stream = stream;
  anchor_frame = -1;
  unlock();

  const char* sharingMode = (stream->getSharingMode() == oboe::SharingMode::Exclusive) ? "Exclusive" : "Shared";

  LOG_INFO("Stream opened: rate=%d, burst=%d, capacity=%d, sharingMode=%s, channels=%d",
    stream->getSampleRate(),
    stream->getFramesPerBurst(),
    stream->getBufferCapacityInFrames(),
    sharingMode,
    actual_channels);

  const char* apiName;
  switch (stream->getAudioApi()) {
    case oboe::AudioApi::AAudio:
      apiName = "AAudio";
      break;
    case oboe::AudioApi::OpenSLES:
      apiName = "OpenSL ES";
      break;
    default:
      apiName = "Unknown";
      break;
  }
  LOG_INFO("Audio API: %s", apiName);

  return stream->getSampleRate();
}

void close_audio() {
  set_playing(0);
  oboe_close();
}

int get_effective_format_flags() {
  return effective_format_flags;
}

int open_audio(int rate, int buffer_ms, int performance_mode, int channel_count, int audio_api, int format_flags) {
  requested_buffer_ms = buffer_ms;

  // OpenSL ES only supports I16 and Float
  if (audio_api == 2 /* OpenSLES */ && (format_flags & FORMAT_32BIT)) {
    LOG_WARN("I32 format not supported by OpenSL ES; falling back to I16");
    format_flags &= ~FORMAT_32BIT;
  }

  set_playing(0);
  set_render_callback(nullptr); // silence until the backend installs its renderer

  int assumed_channels = (channel_count == 1) ? 1 : 2;

  int actual_rate = oboe_open(rate, performance_mode, channel_count, audio_api, format_flags);
  if (actual_rate < 0) return -1;

  if (actual_rate != rate || actual_channels != assumed_channels) {
    LOG_INFO("Buffer mismatch: rate %d->%d, channels %d->%d - reopening", rate, actual_rate, assumed_channels, actual_channels);
    actual_rate = oboe_open(actual_rate, performance_mode, channel_count, audio_api, format_flags);
    if (actual_rate < 0) return -1;
  }

  // The mixer must match what the stream actually delivers, not what was asked for.
  lock();
  const bool is_i32 = audio_stream && audio_stream->getFormat() == oboe::AudioFormat::I32;
  unlock();
  int flags = format_flags;
  flags = (actual_channels == 1) ? (flags | FORMAT_MONO) : (flags & ~FORMAT_MONO);
  flags = is_i32 ? (flags | FORMAT_32BIT) : (flags & ~FORMAT_32BIT);
  effective_format_flags = flags;

  LOG_INFO("Audio opened: requested_rate=%d, actual_rate=%d, channels=%d, format_flags=0x%x", rate, actual_rate, actual_channels, flags);

  return actual_rate;
}

int play_audio() {
  return restart_audio();
}

int restart_audio() {
  lock();

  if (!audio_stream) {
    LOG_ERROR("restart_audio: audio_stream is null");
    unlock();
    return -1;
  }

  if (audio_stream->getState() == oboe::StreamState::Started) {
    LOG_INFO("restart_audio: stream already started");
    unlock();
    return 0;
  }

  LOG_INFO("restart_audio: starting stream");
  oboe::Result result = audio_stream->requestStart();
  unlock();

  if (result != oboe::Result::OK) {
    LOG_ERROR("Failed to start stream: %s", oboe::convertToText(result));
    return -1;
  }

  LOG_INFO("Stream started successfully");
  return 0;
}

int stop_audio() {
  lock();

  if (!audio_stream) {
    unlock();
    return -1;
  }

  oboe::Result result = audio_stream->requestStop();
  unlock();

  return result == oboe::Result::OK ? 0 : -1;
}

int get_audio_stats(struct AudioStats* stats) {
  if (!stats) return -1;

  lock();
  std::shared_ptr<oboe::AudioStream> stream = audio_stream;
  if (!stream) {
    unlock();
    return -1;
  }

  stats->sample_rate = stream->getSampleRate();
  stats->frames_per_burst = stream->getFramesPerBurst();
  stats->buffer_capacity = stream->getBufferCapacityInFrames();
  stats->buffer_size = stream->getBufferSizeInFrames();
  stats->underrun_count = underrun_count.load(std::memory_order_relaxed);

  auto xRunResult = stream->getXRunCount();
  stats->xrun_count = (stream->isXRunCountSupported() && xRunResult) ? xRunResult.value() : 0;

  switch (stream->getAudioApi()) {
    case oboe::AudioApi::AAudio:
      stats->audio_api = "AAudio";
      break;
    case oboe::AudioApi::OpenSLES:
      stats->audio_api = "OpenSL ES";
      break;
    default:
      stats->audio_api = "Unknown";
      break;
  }

  switch (stream->getSharingMode()) {
    case oboe::SharingMode::Exclusive:
      stats->sharing_mode = "Exclusive";
      break;
    default:
      stats->sharing_mode = "Shared";
      break;
  }

  switch (stream->getPerformanceMode()) {
    case oboe::PerformanceMode::None:
      stats->perf_mode = "None";
      break;
    case oboe::PerformanceMode::PowerSaving:
      stats->perf_mode = "PowerSaving";
      break;
    default:
      stats->perf_mode = "LowLatency";
      break;
  }

  switch (stream->getFormat()) {
    case oboe::AudioFormat::I16:
      stats->audio_format = "I16";
      break;
    case oboe::AudioFormat::I32:
      stats->audio_format = "I32";
      break;
    default:
      stats->audio_format = "Unknown";
      break;
  }

  unlock();
  return 0;
}
