#include "player-common.h"

// Far above any real module; stops a stray huge file from exhausting memory.
constexpr int64_t MAX_MODULE_BYTES = 256LL * 1024 * 1024;

std::vector<unsigned char> readFd(int fd) {
  std::vector<unsigned char> data;
  struct stat st{};
  if (fstat(fd, &st) == 0 && st.st_size > 0) {
    if (static_cast<int64_t>(st.st_size) > MAX_MODULE_BYTES) return data;
    data.reserve(static_cast<size_t>(st.st_size));
  }
  std::array<unsigned char, 64 * 1024> buf{};
  while (true) {
    ssize_t n = read(fd, buf.data(), buf.size());
    if (n < 0) {
      if (errno == EINTR) continue;
      data.clear();
      return data;
    }
    if (n == 0) break;
    // The size above is only a hint (pipes report 0), so the cap is enforced here too.
    if (static_cast<int64_t>(data.size()) + n > MAX_MODULE_BYTES) {
      data.clear();
      return data;
    }
    data.insert(data.end(), buf.begin(), buf.begin() + n);
  }
  return data;
}

// CP437 0x01-0x1F: glyphs, not control codes, when they appear in text.
static constexpr char16_t CP437_LOW[32] = {
  0x0020, 0x263A, 0x263B, 0x2665, 0x2666, 0x2663, 0x2660, 0x2022, 0x25D8, 0x25CB, 0x25D9, 0x2642, 0x2640, 0x266A, 0x266B, 0x263C,
  0x25BA, 0x25C4, 0x2195, 0x203C, 0x00B6, 0x00A7, 0x25AC, 0x21A8, 0x2191, 0x2193, 0x2192, 0x2190, 0x221F, 0x2194, 0x25B2, 0x25BC,
};

// CP437 0x80-0xFF.
static constexpr char16_t CP437_HIGH[128] = {
  0x00C7, 0x00FC, 0x00E9, 0x00E2, 0x00E4, 0x00E0, 0x00E5, 0x00E7, 0x00EA, 0x00EB, 0x00E8, 0x00EF, 0x00EE, 0x00EC, 0x00C4, 0x00C5,
  0x00C9, 0x00E6, 0x00C6, 0x00F4, 0x00F6, 0x00F2, 0x00FB, 0x00F9, 0x00FF, 0x00D6, 0x00DC, 0x00A2, 0x00A3, 0x00A5, 0x20A7, 0x0192,
  0x00E1, 0x00ED, 0x00F3, 0x00FA, 0x00F1, 0x00D1, 0x00AA, 0x00BA, 0x00BF, 0x2310, 0x00AC, 0x00BD, 0x00BC, 0x00A1, 0x00AB, 0x00BB,
  0x2591, 0x2592, 0x2593, 0x2502, 0x2524, 0x2561, 0x2562, 0x2556, 0x2555, 0x2563, 0x2551, 0x2557, 0x255D, 0x255C, 0x255B, 0x2510,
  0x2514, 0x2534, 0x252C, 0x251C, 0x2500, 0x253C, 0x255E, 0x255F, 0x255A, 0x2554, 0x2569, 0x2566, 0x2560, 0x2550, 0x256C, 0x2567,
  0x2568, 0x2564, 0x2565, 0x2559, 0x2558, 0x2552, 0x2553, 0x256B, 0x256A, 0x2518, 0x250C, 0x2588, 0x2584, 0x258C, 0x2590, 0x2580,
  0x03B1, 0x00DF, 0x0393, 0x03C0, 0x03A3, 0x03C3, 0x00B5, 0x03C4, 0x03A6, 0x0398, 0x03A9, 0x03B4, 0x221E, 0x03C6, 0x03B5, 0x2229,
  0x2261, 0x00B1, 0x2265, 0x2264, 0x2320, 0x2321, 0x00F7, 0x2248, 0x00B0, 0x2219, 0x00B7, 0x221A, 0x207F, 0x00B2, 0x25A0, 0x00A0,
};

// Everything here is in the BMP and never U+0000, where UTF-8 and JNI's modified UTF-8 agree.
static void appendUtf8(std::string& out, char16_t c) {
  if (c < 0x80) {
    out += static_cast<char>(c);
  } else if (c < 0x800) {
    out += static_cast<char>(0xC0 | (c >> 6));
    out += static_cast<char>(0x80 | (c & 0x3F));
  } else {
    out += static_cast<char>(0xE0 | (c >> 12));
    out += static_cast<char>(0x80 | ((c >> 6) & 0x3F));
    out += static_cast<char>(0x80 | (c & 0x3F));
  }
}

ModuleCharset charsetForFormat(const char* type) {
  if (!type) return ModuleCharset::CP437;
  static constexpr const char* amiga[] = {
    "Amiga", "Protracker", "ProTracker", "Noisetracker", "NoiseTracker", "Soundtracker", "SoundTracker", "Startrekker", "StarTrekker",
    "MED", "Oktalyzer", "DIGI Booster", "DigiBooster", "Ultimate Soundtracker", "Soundfx", "SoundFX", "Future Composer",
  };
  for (const char* name : amiga) {
    if (strstr(type, name)) return ModuleCharset::LATIN1;
  }
  return ModuleCharset::CP437;
}

std::string decodeModuleText(const char* src, ModuleCharset charset) {
  std::string out;
  if (!src) return out;
  out.reserve(strlen(src));
  for (const auto* p = reinterpret_cast<const unsigned char*>(src); *p; ++p) {
    const unsigned char b = *p;
    if (b == '\t' || b == '\n' || b == '\r') {
      out += static_cast<char>(b); // real line structure, in comments
    } else if (b < 0x20) {
      appendUtf8(out, charset == ModuleCharset::CP437 ? CP437_LOW[b] : u' ');
    } else if (b < 0x7F) {
      out += static_cast<char>(b);
    } else if (charset == ModuleCharset::CP437) {
      appendUtf8(out, b == 0x7F ? char16_t{0x2302} : CP437_HIGH[b - 0x80]);
    } else {
      appendUtf8(out, b < 0xA0 ? u' ' : static_cast<char16_t>(b)); // 0x7F-0x9F are controls in Latin-1
    }
  }
  return out;
}

AudioStatsFields g_audioStats{};
ChannelInfoFields g_channelInfo{};
FrameInfoFields g_frameInfo{};
ModInfoFields g_modInfo{};
ModVarsFields g_modVars{};
SequenceFields g_sequence{};

// A failed lookup leaves a Java exception pending, and no further JNI call is allowed
// until it is handled, so each one bails out immediately.
#define GET_CLASS(name)                 \
  jclass clazz = env->FindClass(name);  \
  if (!clazz) return false
#define GET_FIELD(a, b, c) \
  if (!(a.b = env->GetFieldID(clazz, #b, c))) return false

static bool initAudioStatsFields(JNIEnv* env) {
  GET_CLASS("com/lossydragon/renderer/model/AudioStats");
  GET_FIELD(g_audioStats, xrunCount, "I");
  GET_FIELD(g_audioStats, underrunCount, "I");
  GET_FIELD(g_audioStats, framesPerBurst, "I");
  GET_FIELD(g_audioStats, bufferCapacity, "I");
  GET_FIELD(g_audioStats, bufferSize, "I");
  GET_FIELD(g_audioStats, sampleRate, "I");
  GET_FIELD(g_audioStats, audioApi, "Ljava/lang/String;");
  GET_FIELD(g_audioStats, sharingMode, "Ljava/lang/String;");
  GET_FIELD(g_audioStats, perfMode, "Ljava/lang/String;");
  GET_FIELD(g_audioStats, audioFormat, "Ljava/lang/String;");
  return true;
}

static bool initChannelInfoFields(JNIEnv* env) {
  GET_CLASS("com/lossydragon/renderer/model/ChannelInfo");
  GET_FIELD(g_channelInfo, volumes, "[I");
  GET_FIELD(g_channelInfo, finalVols, "[I");
  GET_FIELD(g_channelInfo, pans, "[I");
  GET_FIELD(g_channelInfo, instruments, "[I");
  GET_FIELD(g_channelInfo, keys, "[I");
  GET_FIELD(g_channelInfo, periods, "[I");
  GET_FIELD(g_channelInfo, holdVols, "[I");
  GET_FIELD(g_channelInfo, positions, "[I");
  GET_FIELD(g_channelInfo, pitchbends, "[I");
  GET_FIELD(g_channelInfo, notes, "[I");
  GET_FIELD(g_channelInfo, samples, "[I");
  return true;
}

static bool initFrameInfoFields(JNIEnv* env) {
  GET_CLASS("com/lossydragon/renderer/model/FrameInfo");
  GET_FIELD(g_frameInfo, pos, "I");
  GET_FIELD(g_frameInfo, pattern, "I");
  GET_FIELD(g_frameInfo, row, "I");
  GET_FIELD(g_frameInfo, numRows, "I");
  GET_FIELD(g_frameInfo, frame, "I");
  GET_FIELD(g_frameInfo, speed, "I");
  GET_FIELD(g_frameInfo, bpm, "I");
  GET_FIELD(g_frameInfo, time, "I");
  GET_FIELD(g_frameInfo, totalTime, "I");
  GET_FIELD(g_frameInfo, frameTime, "I");
  GET_FIELD(g_frameInfo, bufferSize, "I");
  GET_FIELD(g_frameInfo, totalSize, "I");
  GET_FIELD(g_frameInfo, volume, "I");
  GET_FIELD(g_frameInfo, loopCount, "I");
  GET_FIELD(g_frameInfo, virtChannels, "I");
  GET_FIELD(g_frameInfo, virtUsed, "I");
  GET_FIELD(g_frameInfo, sequence, "I");
  return true;
}

static bool initModInfoFields(JNIEnv* env) {
  GET_CLASS("com/lossydragon/renderer/model/ModInfo");
  GET_FIELD(g_modInfo, name, "Ljava/lang/String;");
  GET_FIELD(g_modInfo, type, "Ljava/lang/String;");
  return true;
}

static bool initModVarsFields(JNIEnv* env) {
  GET_CLASS("com/lossydragon/renderer/model/ModVars");
  GET_FIELD(g_modVars, name, "Ljava/lang/String;");
  GET_FIELD(g_modVars, type, "Ljava/lang/String;");
  GET_FIELD(g_modVars, pat, "I");
  GET_FIELD(g_modVars, trk, "I");
  GET_FIELD(g_modVars, chn, "I");
  GET_FIELD(g_modVars, ins, "I");
  GET_FIELD(g_modVars, smp, "I");
  GET_FIELD(g_modVars, spd, "I");
  GET_FIELD(g_modVars, bpm, "I");
  GET_FIELD(g_modVars, len, "I");
  GET_FIELD(g_modVars, rst, "I");
  GET_FIELD(g_modVars, gvl, "I");
  GET_FIELD(g_modVars, miNumSequences, "I");
  GET_FIELD(g_modVars, miComment, "Ljava/lang/String;");
  GET_FIELD(g_modVars, seqData, "[Lcom/lossydragon/renderer/model/Sequence;");
  GET_FIELD(g_modVars, instruments, "[Ljava/lang/String;");
  GET_FIELD(g_modVars, renderingEngine, "Ljava/lang/String;");
  return true;
}

static bool initSequenceFields(JNIEnv* env) {
  GET_CLASS("com/lossydragon/renderer/model/Sequence");
  GET_FIELD(g_sequence, entryPoint, "I");
  GET_FIELD(g_sequence, duration, "I");
  return true;
}

bool initFieldIds(JNIEnv* env) {
  return initAudioStatsFields(env) && initChannelInfoFields(env) && initFrameInfoFields(env) && initModInfoFields(env) &&
         initModVarsFields(env) && initSequenceFields(env);
}
