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

// Closes the fd on every exit path, including an exception while reading it.
struct UniqueFd {
  explicit UniqueFd(int fd) : fd(fd) {}
  ~UniqueFd() {
    if (fd >= 0) close(fd);
  }
  UniqueFd(const UniqueFd&) = delete;
  UniqueFd& operator=(const UniqueFd&) = delete;
  int fd;
};

// Module text is single-byte in the charset of the machine it was written on. libxmp hands
// it over raw, and NewStringUTF needs UTF-8, so decode it: ISO-8859-1 for Amiga formats,
// CP437 (including its glyphs below 0x20, common in titles and sample text) for the rest.
enum class ModuleCharset { CP437, LATIN1 };

// Guessed from libxmp's format name, the only hint it gives.
ModuleCharset charsetForFormat(const char* type);

std::string decodeModuleText(const char* src, ModuleCharset charset);

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

// At most SNAPSHOT_HZ pushes a second, so the history spans ~2 s: more than the largest buffer.
constexpr int SNAPSHOT_HZ = 60;
constexpr size_t SNAPSHOT_SLOTS = 128;

// Single-writer history of snapshots keyed by the stream frame they were rendered at, so a
// reader gets the one being heard now rather than the one just mixed.
template <typename T, size_t N>
struct DelayLine {
  template <typename F>
  void push(int64_t frame, F&& fill) {
    const uint64_t h = head.load(std::memory_order_relaxed);
    Slot& s = slots[h % N];
    s.frame.store(frame, std::memory_order_relaxed);
    fill(s.data);
    head.store(h + 1, std::memory_order_seq_cst);
  }

  // Writer must be drained.
  void reset() {
    head.store(0, std::memory_order_seq_cst);
  }

  // Newest snapshot at or before `frame`; the oldest one when all are later (start, seek).
  bool read(int64_t frame, T& out) const {
    for (int i = 0; i < 8; i++) {
      const uint64_t h = head.load(std::memory_order_seq_cst);
      if (h == 0) return false;
      // The slot at h % N may be mid-write, so it is never a candidate.
      const uint64_t oldest = h >= N ? h - N + 1 : 0;
      uint64_t pick = oldest;
      for (uint64_t j = h; j-- > oldest;) {
        if (slots[j % N].frame.load(std::memory_order_relaxed) <= frame) {
          pick = j;
          break;
        }
      }
      out = slots[pick % N].data;
      std::atomic_thread_fence(std::memory_order_acquire);
      if (head.load(std::memory_order_seq_cst) < pick + N) return true;
    }
    return false;
  }

  struct Slot {
    std::atomic<int64_t> frame{0};
    T data{};
  };
  std::atomic<uint64_t> head{0};
  std::array<Slot, N> slots{};
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
  jfieldID name = nullptr;
  jfieldID type = nullptr;
};

struct ModVarsFields {
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
  jfieldID entryPoint = nullptr;
  jfieldID duration = nullptr;
};

extern AudioStatsFields g_audioStats;
extern ChannelInfoFields g_channelInfo;
extern FrameInfoFields g_frameInfo;
extern ModInfoFields g_modInfo;
extern ModVarsFields g_modVars;
extern SequenceFields g_sequence;

// Resolves every field ID above. Called once from JNI_OnLoad, so no native method can
// observe a half-filled table. False (with a Java exception pending) if a class or field is missing.
bool initFieldIds(JNIEnv* env);

// The whole file, or empty when it cannot be read or is larger than any real module.
std::vector<unsigned char> readFd(int fd);
