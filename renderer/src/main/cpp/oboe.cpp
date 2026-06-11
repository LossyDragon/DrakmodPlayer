#include "audio.h"
#include "xmp.h"
#include <android/log.h>
#include <atomic>
#include <cstring>
#include <oboe/Oboe.h>
#include <pthread.h>

#define TAG "DrakPlayer Oboe"
#define LOG_INFO(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOG_WARN(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOG_ERROR(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define lock() pthread_mutex_lock(&mutex)
#define unlock() pthread_mutex_unlock(&mutex)

// xmp context set by init(), cleared before free by deinit()
static xmp_context xmp_ctx = nullptr;
// true = callback calls xmp_play_buffer; false = output silence
static std::atomic<bool> xmp_playing(false);
// true while inside onAudioReady, used to drain in-flight callback safely
static std::atomic<bool> in_callback(false);
// set by callback when xmp_play_buffer returns < 0 (module ended)
static std::atomic<bool> module_ended(false);
// set by onErrorAfterClose when Oboe reports ErrorDisconnected
static std::atomic<bool> audio_disconnected(false);
// passed as the loop arg to xmp_play_buffer: 0 = loop forever, 1 = play once
static std::atomic<int> xmp_loop_flag(1);
// swappable render function; reset_render_callback() points this at render_xmp,
// set_render_callback() lets openmpt (or any future backend) plug in its own renderer
static std::atomic<AudioRenderCallback> render_callback(nullptr);

static std::atomic<int32_t> underrun_count(0);
static std::shared_ptr<oboe::AudioStream> audio_stream;
class XmpAudioCallback;
static XmpAudioCallback* audio_callback = nullptr;
static int actual_channels = 2;
static int effective_format_flags = 0;
static pthread_mutex_t mutex;

// Spin until the current callback invocation finishes (at most one burst period, ~5ms).
static void wait_for_callback() {
  while (in_callback.load(std::memory_order_acquire)) {}
}

class XmpAudioCallback : public oboe::AudioStreamDataCallback,
                         public oboe::AudioStreamErrorCallback {
public:
  oboe::DataCallbackResult onAudioReady(oboe::AudioStream* audioStream, void* audioData, int32_t numFrames) override {
    in_callback.store(true, std::memory_order_release);

    static int callback_count = 0;
    static int32_t last_xrun_count = 0;
    if (++callback_count >= 1000) {
      callback_count = 0;
      if (audioStream->isXRunCountSupported()) {
        auto xRunResult = audioStream->getXRunCount();
        if (xRunResult) {
          int32_t current = xRunResult.value();
          if (current != last_xrun_count) {
            LOG_WARN("XRuns detected: %d (new: %d)", current, current - last_xrun_count);
            last_xrun_count = current;
          }
        }
      }
    }

    const int numChannels = audioStream->getChannelCount();
    const int bytesPerSample = (audioStream->getFormat() == oboe::AudioFormat::I32) ? 4 : 2;
    const int numBytes = numFrames * numChannels * bytesPerSample;

    AudioRenderCallback callback = render_callback.load(std::memory_order_acquire);
    if (xmp_playing.load(std::memory_order_acquire) && callback != nullptr) {
      int ret = callback(audioData, numFrames, numChannels, bytesPerSample);
      if (ret < 0) {
        module_ended.store(true, std::memory_order_release);
        memset(audioData, 0, numBytes);
      }
    } else {
      memset(audioData, 0, numBytes);
    }

    in_callback.store(false, std::memory_order_release);
    return oboe::DataCallbackResult::Continue;
  }

  void onErrorAfterClose(oboe::AudioStream* oboeStream, oboe::Result error) override {
    LOG_ERROR("Stream closed due to error: %s", oboe::convertToText(error));
    if (error == oboe::Result::ErrorDisconnected) {
      LOG_ERROR("Audio device disconnected");
      audio_disconnected.store(true, std::memory_order_release);
    }
  }
};

void set_xmp_context(void* ctx) {
  xmp_ctx = static_cast<xmp_context>(ctx);
}

static int render_xmp(void* audioData, int32_t numFrames, int32_t numChannels, int32_t bytesPerSample) {
  if (xmp_ctx == nullptr) return -1;
  int numBytes = numFrames * numChannels * bytesPerSample;
  return xmp_play_buffer(xmp_ctx, audioData, numBytes, xmp_loop_flag.load(std::memory_order_relaxed));
}

void reset_render_callback() {
  render_callback.store(render_xmp, std::memory_order_release);
}

void set_render_callback(AudioRenderCallback callback) {
  render_callback.store(callback, std::memory_order_release);
}

void set_playing(int val) {
  if (!val) {
    xmp_playing.store(false, std::memory_order_release);
    wait_for_callback();
  } else {
    module_ended.store(false, std::memory_order_release);
    xmp_playing.store(true, std::memory_order_release);
  }
}

int has_module_ended() {
  return module_ended.load(std::memory_order_relaxed) ? 1 : 0;
}

int has_audio_disconnected() {
  return audio_disconnected.load(std::memory_order_acquire) ? 1 : 0;
}

void clear_audio_disconnected() {
  audio_disconnected.store(false, std::memory_order_release);
}

// 0 = loop (repeat-one), 1 = play once
void set_loop_mode(int loop_flag) {
  xmp_loop_flag.store(loop_flag, std::memory_order_relaxed);
}

static int oboe_open(int sample_rate, int performance_mode, int channel_count, int audio_api, int format_flags) {
  oboe::AudioStreamBuilder builder;

  // Guard against a stale callback from a prior disconnect
  if (audio_callback) {
    delete audio_callback;
    audio_callback = nullptr;
  }
  audio_callback = new XmpAudioCallback();

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

  const auto oboeFormat = (format_flags & (1 << 3)) ? oboe::AudioFormat::I32 : oboe::AudioFormat::I16;

  builder.setDirection(oboe::Direction::Output)
    ->setAudioApi(aApi)
    ->setChannelCount(cCount)
    ->setDataCallback(audio_callback)
    ->setErrorCallback(audio_callback)
    ->setFormat(oboeFormat)
    ->setPerformanceMode(pMode)
    ->setSampleRate(sample_rate)
    ->setSharingMode(oboe::SharingMode::Shared);

  oboe::Result result = builder.openStream(audio_stream);
  if (result != oboe::Result::OK) {
    LOG_ERROR("Failed to open stream: %s", oboe::convertToText(result));
    delete audio_callback;
    audio_callback = nullptr;
    return -1;
  }

  audio_disconnected.store(false, std::memory_order_release);

  actual_channels = audio_stream->getChannelCount();

  const char* sharingMode = (audio_stream->getSharingMode() == oboe::SharingMode::Exclusive) ? "Exclusive" : "Shared";

  LOG_INFO("Stream opened: rate=%d, burst=%d, capacity=%d, sharingMode=%s, channels=%d",
    audio_stream->getSampleRate(),
    audio_stream->getFramesPerBurst(),
    audio_stream->getBufferCapacityInFrames(),
    sharingMode,
    actual_channels);

  const char* apiName;
  switch (audio_stream->getAudioApi()) {
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

  return audio_stream->getSampleRate();
}

static void oboe_close() {
  lock();
  if (audio_stream) {
    audio_stream->stop();
    audio_stream->close();
    audio_stream.reset();
  }
  if (audio_callback) {
    delete audio_callback;
    audio_callback = nullptr;
  }
  unlock();
}

void close_audio() {
  oboe_close();
  pthread_mutex_destroy(&mutex);
}

int get_effective_format_flags() {
  return effective_format_flags;
}

int open_audio(int rate, int latency, int performance_mode, int channel_count, int audio_api, int format_flags) {
  (void)latency;

  if (pthread_mutex_init(&mutex, nullptr) != 0) {
    LOG_ERROR("Failed to initialize mutex");
    return -1;
  }

  // OpenSL ES only supports I16 and Float
  if (audio_api == 2 /* OpenSLES */ && (format_flags & (1 << 3))) {
    LOG_WARN("I32 format not supported by OpenSL ES; falling back to I16");
    format_flags &= ~(1 << 3);
  }

  effective_format_flags = format_flags;
  reset_render_callback();

  int assumed_channels = (channel_count == 1) ? 1 : 2;

  int actual_rate = oboe_open(rate, performance_mode, channel_count, audio_api, format_flags);
  if (actual_rate < 0) {
    pthread_mutex_destroy(&mutex);
    return -1;
  }

  if (actual_rate != rate || actual_channels != assumed_channels) {
    LOG_INFO("Buffer mismatch: rate %d->%d, channels %d->%d - reopening", rate, actual_rate, assumed_channels, actual_channels);
    oboe_close();
    int reopen_rate = oboe_open(actual_rate, performance_mode, channel_count, audio_api, format_flags);
    if (reopen_rate < 0) {
      pthread_mutex_destroy(&mutex);
      return -1;
    }
  }

  LOG_INFO("Audio opened: requested_rate=%d, actual_rate=%d, channels=%d", rate, actual_rate, actual_channels);

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
  if (!audio_stream || !stats) return -1;

  stats->sample_rate = audio_stream->getSampleRate();
  stats->frames_per_burst = audio_stream->getFramesPerBurst();
  stats->buffer_capacity = audio_stream->getBufferCapacityInFrames();
  stats->buffer_size = audio_stream->getBufferSizeInFrames();
  stats->underrun_count = underrun_count.load(std::memory_order_relaxed);

  auto xRunResult = audio_stream->getXRunCount();
  stats->xrun_count = (audio_stream->isXRunCountSupported() && xRunResult) ? xRunResult.value() : 0;

  switch (audio_stream->getAudioApi()) {
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

  switch (audio_stream->getSharingMode()) {
    case oboe::SharingMode::Exclusive:
      stats->sharing_mode = "Exclusive";
      break;
    default:
      stats->sharing_mode = "Shared";
      break;
  }

  switch (audio_stream->getPerformanceMode()) {
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

  switch (audio_stream->getFormat()) {
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

  return 0;
}
