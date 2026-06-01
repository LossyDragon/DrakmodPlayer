#include "audio.h"
#include <android/log.h>
#include <atomic>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <oboe/Oboe.h>
#include <pthread.h>
#include <stdatomic.h>

#define TAG "libxmp Oboe"
#define LOG_INFO(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOG_WARN(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOG_ERROR(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define BUFFER_TIME 40

#define lock() pthread_mutex_lock(&mutex)
#define unlock() pthread_mutex_unlock(&mutex)

static std::atomic<bool> expect_silence(false);

// Helper to safely load atomic_int
static inline int atomic_load_int(atomic_int *ptr) {
    return atomic_load(ptr);
}

// Helper to safely store atomic_int
static inline void atomic_store_int(atomic_int *ptr, int val) {
    atomic_store(ptr, val);
}

class XmpAudioCallback : public oboe::AudioStreamDataCallback,
                         public oboe::AudioStreamErrorCallback {
public:
    XmpAudioCallback(char *buf, int bufSize, int bufNum, atomic_int *firstFree,
                     atomic_int *lastFree, std::atomic<float> *volScale,
                     std::atomic<int32_t> *underrunCount, int formatFlags)
            : buffer(buf), buffer_size(bufSize), buffer_num(bufNum), first_free(firstFree),
              last_free(lastFree), volume_scale(*volScale), underrun_count_(*underrunCount),
              buffer_position(0), xmp_format_flags(formatFlags),
              oboe_bytes_per_sample((formatFlags & (1 << 3)) ? sizeof(int32_t) : sizeof(int16_t)) {}

    oboe::DataCallbackResult
    onAudioReady(oboe::AudioStream *audioStream, void *audioData, int32_t numFrames) override {
        static int callback_count = 0;
        static int32_t last_xrun_count = 0;

        if (++callback_count >= 1000) {
            callback_count = 0;
            if (audioStream->isXRunCountSupported()) {
                oboe::ResultWithValue<int32_t> xRunResult = audioStream->getXRunCount();
                if (xRunResult) {
                    int32_t current_xruns = xRunResult.value();
                    if (current_xruns != last_xrun_count) {
                        LOG_WARN("XRuns detected: %d (new: %d)", current_xruns,
                                 current_xruns - last_xrun_count);
                        last_xrun_count = current_xruns;
                    }
                }
            }
        }

        const bool is_8bit = (xmp_format_flags & (1 << 0)) != 0;
        const bool is_32bit = (xmp_format_flags & (1 << 3)) != 0;
        const bool is_unsigned = (xmp_format_flags & (1 << 1)) != 0;

        // Source bytes per sample
        const int src_bytes_per_sample = is_8bit ? 1 : is_32bit ? 4 : 2;

        auto *dst16 = static_cast<int16_t *>(audioData);

        const int32_t outputFrames = numFrames * audioStream->getChannelCount();
        const int32_t outputBytes = outputFrames * oboe_bytes_per_sample;

        int32_t outputBytesCopied = 0;

        while (outputBytesCopied < outputBytes) {
            int lf = atomic_load_int(last_free);
            int ff = atomic_load_int(first_free);

            if (lf == ff) {
                int32_t remaining = outputBytes - outputBytesCopied;
                if (!expect_silence.load(std::memory_order_relaxed)) {
                    LOG_WARN("UNDERRUN: filling %d bytes with silence", remaining);
                    underrun_count_.fetch_add(1, std::memory_order_relaxed);
                }
                memset(reinterpret_cast<char *>(dst16) + outputBytesCopied, 0, remaining);
                return oboe::DataCallbackResult::Continue;
            }

            // How many output samples we still need
            int32_t outputSamplesNeeded = (outputBytes - outputBytesCopied) / oboe_bytes_per_sample;

            // How many source samples are available in current buffer
            int32_t srcBytesAvailable = buffer_size - buffer_position;
            int32_t srcSamplesAvailable = srcBytesAvailable / src_bytes_per_sample;

            int32_t samplesToProcess = std::min(outputSamplesNeeded, srcSamplesAvailable);

            char *src = &buffer[lf * buffer_size + buffer_position];
            int16_t *dst = dst16 + (outputBytesCopied / sizeof(int16_t));
            float vol = volume_scale.load(std::memory_order_relaxed);

            if (is_8bit) {
                if (is_unsigned) {
                    // Unsigned 8-bit: 0..255 to -32768..32767
                    auto *src8 = reinterpret_cast<uint8_t *>(src);
                    for (int i = 0; i < samplesToProcess; i++) {
                        auto s = (int16_t) ((src8[i] - 128) << 8);
                        dst[i] = (vol >= 0.99f) ? s : (int16_t) (s * vol);
                    }
                } else {
                    // Signed 8-bit: -128..127 to -32768..32767
                    auto *src8 = reinterpret_cast<int8_t *>(src);
                    for (int i = 0; i < samplesToProcess; i++) {
                        auto s = (int16_t) (src8[i] << 8);
                        dst[i] = (vol >= 0.99f) ? s : (int16_t) (s * vol);
                    }
                }
                buffer_position += samplesToProcess;
                outputBytesCopied += samplesToProcess * sizeof(int16_t);

            } else if (is_32bit) {
                auto *src32 = reinterpret_cast<int32_t *>(src);
                auto *dst32 = reinterpret_cast<int32_t *>(audioData) +
                              (outputBytesCopied / sizeof(int32_t));
                if (vol >= 0.99f) {
                    memcpy(dst32, src32, samplesToProcess * sizeof(int32_t));
                } else {
                    for (int i = 0; i < samplesToProcess; i++) {
                        dst32[i] = (int32_t) (src32[i] * vol);
                    }
                }
                buffer_position += samplesToProcess * sizeof(int32_t);
                outputBytesCopied += samplesToProcess * sizeof(int32_t);
            } else {
                // 16-bit (signed or unsigned)
                if (is_unsigned) {
                    auto *src16u = reinterpret_cast<uint16_t *>(src);
                    for (int i = 0; i < samplesToProcess; i++) {
                        auto s = (int16_t) (src16u[i] - 32768);
                        dst[i] = (vol >= 0.99f) ? s : (int16_t) (s * vol);
                    }
                } else {
                    auto *src16 = reinterpret_cast<int16_t *>(src);
                    if (vol >= 0.99f) {
                        memcpy(dst, src16, samplesToProcess * sizeof(int16_t));
                    } else {
                        for (int i = 0; i < samplesToProcess; i++) {
                            dst[i] = (int16_t) (src16[i] * vol);
                        }
                    }
                }
                buffer_position += samplesToProcess * sizeof(int16_t);
                outputBytesCopied += samplesToProcess * sizeof(int16_t);
            }

            // Consumed entire source buffer — advance to next
            if (buffer_position >= buffer_size) {
                buffer_position = 0;
                atomic_store_int(last_free, (lf + 1) % buffer_num);
            }
        }

        return oboe::DataCallbackResult::Continue;
    }

    void onErrorAfterClose(oboe::AudioStream *oboeStream, oboe::Result error) override {
        LOG_ERROR("Stream closed due to error: %s", oboe::convertToText(error));

        if (error == oboe::Result::ErrorDisconnected) {
            LOG_ERROR("Audio device disconnected");
        }
    }

private:
    atomic_int *first_free;
    atomic_int *last_free;
    char *buffer;
    int buffer_num;
    int buffer_position; // Tracks position within current buffer (in bytes)
    int buffer_size;
    int oboe_bytes_per_sample;
    int xmp_format_flags;
    std::atomic<float> &volume_scale;
    std::atomic<int32_t> &underrun_count_;
};

static XmpAudioCallback *audio_callback = nullptr;
static atomic_int first_free;
static atomic_int last_free;
static char *buffer = nullptr;
static int actual_channels = 2;
static int buffer_num;
static int buffer_size;
static int effective_format_flags = 0;
static pthread_mutex_t mutex;
static std::atomic<float> volume_scale(1.0f);
static std::atomic<int32_t> underrun_count(0);
static std::shared_ptr<oboe::AudioStream> audio_stream;

static int oboe_open(int sample_rate, int num, char *buf, int buf_size,
                     int performance_mode, int channel_count, int audio_api,
                     int format_flags) {
    oboe::AudioStreamBuilder builder;

    // Create callback with the buffer that's already allocated
    audio_callback = new XmpAudioCallback(buf, buf_size, num, &first_free, &last_free,
                                          &volume_scale, &underrun_count, format_flags);

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

    const auto cCount = (channel_count == 1)
                        ? oboe::ChannelCount::Mono
                        : oboe::ChannelCount::Stereo;

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

    const auto oboeFormat = (format_flags & (1 << 3))
                            ? oboe::AudioFormat::I32
                            : oboe::AudioFormat::I16;

    // Configure stream - request sample rate but Oboe may override
    builder.setDirection(oboe::Direction::Output)
            ->setAudioApi(aApi)
            ->setChannelCount(cCount)
            ->setDataCallback(audio_callback)
            ->setErrorCallback(audio_callback)
            ->setFormat(oboeFormat)
            ->setPerformanceMode(pMode)
            ->setSampleRate(sample_rate)
            ->setSharingMode(oboe::SharingMode::Shared);

    // Open stream
    oboe::Result result = builder.openStream(audio_stream);
    if (result != oboe::Result::OK) {
        LOG_ERROR("Failed to open stream: %s", oboe::convertToText(result));
        delete audio_callback;
        audio_callback = nullptr;
        return -1;
    }

    actual_channels = audio_stream->getChannelCount();

    const char *sharingMode = (audio_stream->getSharingMode() == oboe::SharingMode::Exclusive)
                              ? "Exclusive"
                              : "Shared";

    LOG_INFO("Stream opened: rate=%d, burst=%d, capacity=%d, sharingMode=%s, channels=%d",
             audio_stream->getSampleRate(),
             audio_stream->getFramesPerBurst(),
             audio_stream->getBufferCapacityInFrames(),
             sharingMode,
             actual_channels);

    // Log which audio API is being used
    const char *apiName;
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

    // Return the actual sample rate Oboe opened with
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
    if (buffer) {
        free(buffer);
        buffer = nullptr;
    }
    pthread_mutex_destroy(&mutex);
}

int get_effective_format_flags() {
    return effective_format_flags;
}

int open_audio(int rate, int latency, int performance_mode, int channel_count, int audio_api,
               int format_flags) {
    if (pthread_mutex_init(&mutex, nullptr) != 0) {
        LOG_ERROR("Failed to initialize mutex");
        return -1;
    }

    // OpenSL ES only supports I16 and Float — I32 is rejected at stream open time.
    // Guard against it by stripping the 32-bit flag.
    if (audio_api == 2 /* OpenSLES */ && (format_flags & (1 << 3))) {
        LOG_WARN("I32 format not supported by OpenSL ES; falling back to I16");
        format_flags &= ~(1 << 3);
    }
    effective_format_flags = format_flags;

    buffer_num = latency / BUFFER_TIME;

    if (buffer_num < 3) buffer_num = 3;

    // What we intend to request, so we can detect a mismatch after opening.
    int assumed_channels = (channel_count == 1) ? 1 : 2;

    // Determine bytes per sample from xmp format flags
    int bytes_per_sample = sizeof(int16_t); // default 16-bit
    if (format_flags & (1 << 0)) bytes_per_sample = 1; // XMP_FORMAT_8BIT
    else if (format_flags & (1 << 3)) bytes_per_sample = 4; // XMP_FORMAT_32BIT

    // Allocate buffer with requested rate first (we'll resize if needed)
    buffer_size = rate * assumed_channels * bytes_per_sample * BUFFER_TIME / 1000;
    buffer = (char *) malloc(buffer_size * buffer_num);
    if (buffer == nullptr) {
        LOG_ERROR("Failed to allocate audio buffer");
        pthread_mutex_destroy(&mutex);
        return -1;
    }

    // Open Oboe - it will return the actual sample rate
    int actual_rate = oboe_open(rate, buffer_num, buffer, buffer_size,
                                performance_mode, channel_count, audio_api, format_flags);
    if (actual_rate < 0) {
        free(buffer);
        buffer = nullptr;
        pthread_mutex_destroy(&mutex);
        return -1;
    }

    // Reallocate if rate OR channels differ from what we sized for
    if (actual_rate != rate || actual_channels != assumed_channels) {
        LOG_INFO("Buffer mismatch: rate %d->%d, channels %d->%d - reallocating", rate, actual_rate,
                 assumed_channels, actual_channels);

        // Close the stream
        oboe_close();

        // Recalculate buffer size for actual rate
        buffer_size = actual_rate * actual_channels * bytes_per_sample * BUFFER_TIME / 1000;

        // Reallocate buffer
        free(buffer);
        buffer = (char *) malloc(buffer_size * buffer_num);
        if (buffer == nullptr) {
            LOG_ERROR("Failed to reallocate audio buffer");
            pthread_mutex_destroy(&mutex);
            return -1;
        }

        // Reopen with correct buffer size
        int reopen_rate = oboe_open(actual_rate, buffer_num, buffer, buffer_size,
                                    performance_mode, channel_count, audio_api, format_flags);
        if (reopen_rate < 0) {
            free(buffer);
            buffer = nullptr;
            pthread_mutex_destroy(&mutex);
            LOG_ERROR("Failed to reallocate audio buffer with adjusted buffer size");
            return -1;
        }
    }

    // Both start at 0 - empty buffers
    first_free = 0;
    last_free = 0;

    LOG_INFO(
            "Audio opened: requested_rate=%d, actual_rate=%d, channels=%d, latency=%d, buffers=%d, buffer_size=%d",
            rate, actual_rate, actual_channels, latency, buffer_num, buffer_size);

    // Return the actual rate so xmp can use it
    return actual_rate;
}

void flush_audio() {
    lock();

    if (audio_stream) {
        // Poll every 10ms until all buffers are consumed
        struct timespec sleepTime = {.tv_sec = 0, .tv_nsec = 10000 * 1000};

        while (last_free != first_free) {
            unlock();
            nanosleep(&sleepTime, nullptr);
            lock();
        }
    }

    unlock();
}

void drop_audio() {
    lock();

    // Reset buffer positions - empty buffers
    first_free = 0;
    last_free = 0;

    unlock();
}

int play_audio() {
    // Start the stream - the callback will begin pulling data
    // The main thread will fill buffers via fill_buffer() calls
    if (restart_audio() < 0) return -1;

    return 0;
}

void set_expect_silence(int val) {
    expect_silence.store(val != 0, std::memory_order_relaxed);
}

int has_free_buffer() {
    int lf = atomic_load_int(&last_free);
    int ff = atomic_load_int(&first_free);

    // Check if there's space to write a new buffer
    // If first_free + 1 (wrapped) == last_free, we're full
    int next_first = (ff + 1) % buffer_num;
    return next_first != lf;
}

int fill_buffer(int looped) {
    int ret;

    int ff = atomic_load_int(&first_free);
    // int lf = atomic_load_int(&last_free);

    // Fill and enqueue buffer at first_free position
    char *b = &buffer[ff * buffer_size];

    ret = play_buffer(b, buffer_size, looped);

    // Commit only after play_buffer writes to the slot — advancing first_free
    // before the fill would let the callback consume an uninitialized buffer.
    int next_first = (ff + 1) % buffer_num;
    atomic_store_int(&first_free, next_first);

    // LOG_INFO("fill_buffer: filled buffer %d, first_free %d->%d, last_free=%d, ret=%d",
    //      ff, ff, next_first, lf, ret);

    return ret;
}

int restart_audio() {
    lock();

    if (!audio_stream) {
        LOG_ERROR("restart_audio: audio_stream is null");
        unlock();
        return -1;
    }

    // Load atomic values for logging
    int ff = atomic_load_int(&first_free);
    int lf = atomic_load_int(&last_free);
    LOG_INFO("restart_audio: Starting stream, first_free=%d, last_free=%d", ff, lf);

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

    drop_audio();

    return result == oboe::Result::OK ? 0 : -1;
}

int get_volume() {
    // Volume is stored as 0.0-1.0, convert to millibels-like format
    // to maintain compatibility with existing code
    // -vol format where 0 = max volume, higher numbers = quieter
    float vol = volume_scale.load(std::memory_order_relaxed);
    int result = (int) ((1.0f - vol) * 1000.0f);
    return result;
}

int set_volume(int vol) {
    // Convert from -vol format (0 = max, higher = quieter) to 0.0-1.0
    float new_volume = 1.0f - ((float) vol / 1000.0f);

    // Clamp to valid range
    if (new_volume < 0.0f) new_volume = 0.0f;
    if (new_volume > 1.0f) new_volume = 1.0f;

    // Store volume - will be applied in audio callback
    volume_scale.store(new_volume, std::memory_order_relaxed);

    return 0;
}

int get_audio_stats(struct AudioStats *stats) {
    if (!audio_stream || !stats) {
        return -1;
    }

    stats->sample_rate = audio_stream->getSampleRate();
    stats->frames_per_burst = audio_stream->getFramesPerBurst();
    stats->buffer_capacity = audio_stream->getBufferCapacityInFrames();
    stats->buffer_size = audio_stream->getBufferSizeInFrames();
    stats->underrun_count = underrun_count.load(std::memory_order_relaxed);
    auto xRunResult = audio_stream->getXRunCount();
    stats->xrun_count = (audio_stream->isXRunCountSupported() && xRunResult)
                        ? xRunResult.value() : 0;

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
