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

// Runs on the audio thread: no locks, no allocation, no logging. Return < 0 to signal end of module.
typedef int (*AudioRenderCallback)(void* audioData, int32_t numFrames, int32_t numChannels, int32_t bytesPerSample);

int get_audio_stats(struct AudioStats* stats);

void note_underrun(void); // render callback could not produce audio in time (app-side glitch)

int get_effective_format_flags(void); // XMP_FORMAT_* flags reconciled with the opened stream

int open_audio(int, int, int, int, int, int);

int play_audio(void);

int restart_audio(void);

int stop_audio(void);

void close_audio(void);

int has_audio_disconnected(void); // returns 1 after ErrorDisconnected from Oboe

int has_module_ended(void); // returns 1 once a render callback reported end of module

void clear_module_ended(void);

void set_render_callback(AudioRenderCallback callback); // nullptr = silence

void set_playing(int val); // 0 = silence+drain in-flight callback, 1 = play (clears module_ended)

int is_playing(void);

int pause_render(void); // silence + drain; returns previous playing state for resume_render

void resume_render(int was_playing); // restores playing without touching module_ended

int64_t audio_render_frame(void); // inside the render callback only: stream frame of the buffer's first sample

int64_t audio_presented_frame(void); // stream frame reaching the output now; -1 without a stream

#ifdef __cplusplus
}
#endif

#endif
