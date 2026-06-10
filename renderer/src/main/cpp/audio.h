#ifndef XMP_JNI_AUDIO_H
#define XMP_JNI_AUDIO_H

#include <cstdint>

#ifdef __cplusplus
extern "C" {
#endif

struct AudioStats {
  int32_t xrun_count;
  int32_t underrun_count;
  int32_t frames_per_burst;
  int32_t buffer_capacity;
  int32_t buffer_size;
  int sample_rate;
  const char* audio_api;
  const char* sharing_mode;
  const char* perf_mode;
  const char* audio_format;
};

typedef int (*AudioRenderCallback)(void* audioData, int32_t numFrames, int32_t numChannels, int32_t bytesPerSample);

int get_audio_stats(struct AudioStats* stats);

int get_effective_format_flags(void);

int open_audio(int, int, int, int, int, int);

int play_audio(void);

int restart_audio(void);

int stop_audio(void);

void close_audio(void);

int has_module_ended(void); // returns 1 when xmp_play_buffer signals XMP_END

void reset_render_callback(void);

void set_render_callback(AudioRenderCallback callback);

void set_loop_mode(int loop_flag); // 0 = loop forever (repeat-one), 1 = play once

void set_playing(int val); // 0 = silence+sync, 1 = play; waits for in-flight callback on stop

void set_xmp_context(void* ctx); // must be called after init(), before startPlayer()

#ifdef __cplusplus
}
#endif

#endif
