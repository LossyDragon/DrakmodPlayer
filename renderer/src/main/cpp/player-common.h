#pragma once
#include <jni.h>

#include <array>
#include <atomic>
#include <cerrno>
#include <string>
#include <cstring>
#include <sched.h>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

#include "audio.h"

// FindClass returns a local ref that dies with the JNI call, so it is only kept
// on the stack while the (stable) field IDs are resolved.
#define GET_CLASS(a, b)                       \
  jclass jniClazz_ = env->FindClass(b);       \
  a.initialized = (jniClazz_ != nullptr)
#define GET_FIELD(a, b, c) a.b = env->GetFieldID(jniClazz_, #b, c)

// Text sanitizer for charsets like CP437.
inline std::string sanitizeForJni(const char* src) {
  if (!src) return "";
  std::string out;
  out.reserve(strlen(src));
  for (const auto* p = reinterpret_cast<const unsigned char*>(src); *p; ++p) out += (*p < 0x80) ? static_cast<char>(*p) : '?';
  return out;
}

// Single-writer snapshot: the audio thread publishes without blocking, readers retry on a torn copy.
template <typename T>
struct SeqLock {
  template <typename F>
  void update(F&& fill) {
    uint32_t s = seq.load(std::memory_order_relaxed);
    seq.store(s + 1, std::memory_order_relaxed);
    std::atomic_thread_fence(std::memory_order_seq_cst);
    fill(data);
    seq.store(s + 2, std::memory_order_release);
  }

  void reset() {
    update([](T& d) { d = T{}; });
  }

  bool read(T& out) const {
    for (int i = 0; i < 64; i++) {
      uint32_t s1 = seq.load(std::memory_order_acquire);
      if (s1 & 1) {
        sched_yield();
        continue;
      }
      out = data;
      std::atomic_thread_fence(std::memory_order_acquire);
      if (seq.load(std::memory_order_relaxed) == s1) return true;
    }
    return false;
  }

  std::atomic<uint32_t> seq{0};
  T data{};
};

// Silences the callback and drains an in-flight render so engine state can be mutated.
// Must be taken BEFORE any mutex the render callback can block on, or the drain never completes.
struct RenderPause {
  RenderPause() : was_playing(pause_render()) {}
  ~RenderPause() { resume_render(was_playing); }
  RenderPause(const RenderPause&) = delete;
  RenderPause& operator=(const RenderPause&) = delete;
  void stay_stopped() { was_playing = 0; }
  int was_playing;
};

struct AudioStatsFields {
  bool initialized = false;
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
};

struct ChannelInfoFields {
  bool initialized = false;
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
};

struct FrameInfoFields {
  bool initialized = false;
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
};

struct ModInfoFields {
  bool initialized = false;
  jfieldID name = nullptr;
  jfieldID type = nullptr;
};

struct ModVarsFields {
  bool initialized = false;
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
  jfieldID renderingEngine = nullptr;
};

struct SequenceFields {
  bool initialized = false;
  jfieldID entryPoint = nullptr;
  jfieldID duration = nullptr;
};

extern AudioStatsFields g_audioStats;
extern ChannelInfoFields g_channelInfo;
extern FrameInfoFields g_frameInfo;
extern ModInfoFields g_modInfo;
extern ModVarsFields g_modVars;
extern SequenceFields g_sequence;

void initAudioStatsFields(JNIEnv* env);
void initChannelInfoFields(JNIEnv* env);
void initFrameInfoFields(JNIEnv* env);
void initModInfoFields(JNIEnv* env);
void initModVarsFields(JNIEnv* env);
void initSequenceFields(JNIEnv* env);

std::vector<unsigned char> readFd(int fd);
