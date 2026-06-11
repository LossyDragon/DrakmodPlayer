#pragma once
#include <jni.h>

#include <string>
#include <cstring>

#define GET_CLASS(a, b) a.clazz = env->FindClass(b)
#define GET_FIELD(a, b, c) a.b = env->GetFieldID(a.clazz, #b, c)

// Text sanitizer for charsets like CP437.
inline std::string sanitizeForJni(const char* src) {
  if (!src) return "";
  std::string out;
  out.reserve(strlen(src));
  for (const auto* p = reinterpret_cast<const unsigned char*>(src); *p; ++p) out += (*p < 0x80) ? static_cast<char>(*p) : '?';
  return out;
}

struct AudioStatsFields {
  jclass clazz = nullptr;
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
  jclass clazz = nullptr;
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
  jclass clazz = nullptr;
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
  jclass clazz = nullptr;
  jfieldID name = nullptr;
  jfieldID type = nullptr;
};

struct ModVarsFields {
  jclass clazz = nullptr;
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
  jclass clazz = nullptr;
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
