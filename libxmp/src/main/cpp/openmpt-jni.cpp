#include "audio.h"
#include "libopenmpt/libopenmpt.h"
#include <android/log.h>
#include <jni.h>
#include <algorithm>
#include <array>
#include <cerrno>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

constexpr const char* TAG = "OpenMPT JNI";
#define LOG_INFO(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOG_WARN(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOG_ERROR(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define METHOD(type, name) extern "C" JNIEXPORT type JNICALL Java_org_helllabs_libxmp_OpenMpt_##name
#define GET_CLASS(a, b) a.clazz = env->FindClass(b)
#define GET_FIELD(a, b, c) a.b = env->GetFieldID(a.clazz, #b, c)

static struct audio_stats {
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
} audio_stats;

static struct channel_info {
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
} channel_info;

static struct frame_info {
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
} frame_info;

static struct mod_info {
  jclass clazz = nullptr;
  jfieldID name = nullptr;
  jfieldID type = nullptr;
} mod_info;

static struct mod_vars {
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
} mod_vars;

static struct sequence_info {
  jclass clazz = nullptr;
  jfieldID entryPoint = nullptr;
  jfieldID duration = nullptr;
} sequence_info;

namespace {
struct OpenMptState {
  std::mutex mutex;
  bool initialized = false;
  bool mod_is_loaded = false;
  bool playing = false;
  bool loop = false;
  int actual_rate = 44100;
  int selected_subsong = 0;
  double duration = 0.0;
  openmpt_module* mod = nullptr;

  static OpenMptState& instance() {
    static OpenMptState s;
    return s;
  }
};

static void initAudioStatsFields(JNIEnv* env) {
  if (audio_stats.clazz != nullptr) return;
  GET_CLASS(audio_stats, "org/helllabs/libxmp/model/AudioStats");
  GET_FIELD(audio_stats, xrunCount, "I");
  GET_FIELD(audio_stats, underrunCount, "I");
  GET_FIELD(audio_stats, framesPerBurst, "I");
  GET_FIELD(audio_stats, bufferCapacity, "I");
  GET_FIELD(audio_stats, bufferSize, "I");
  GET_FIELD(audio_stats, sampleRate, "I");
  GET_FIELD(audio_stats, audioApi, "Ljava/lang/String;");
  GET_FIELD(audio_stats, sharingMode, "Ljava/lang/String;");
  GET_FIELD(audio_stats, perfMode, "Ljava/lang/String;");
  GET_FIELD(audio_stats, audioFormat, "Ljava/lang/String;");
}

static void initChannelInfoFields(JNIEnv* env) {
  if (channel_info.clazz != nullptr) return;
  GET_CLASS(channel_info, "org/helllabs/libxmp/model/ChannelInfo");
  GET_FIELD(channel_info, volumes, "[I");
  GET_FIELD(channel_info, finalVols, "[I");
  GET_FIELD(channel_info, pans, "[I");
  GET_FIELD(channel_info, instruments, "[I");
  GET_FIELD(channel_info, keys, "[I");
  GET_FIELD(channel_info, periods, "[I");
  GET_FIELD(channel_info, holdVols, "[I");
  GET_FIELD(channel_info, positions, "[I");
  GET_FIELD(channel_info, pitchbends, "[I");
  GET_FIELD(channel_info, notes, "[I");
  GET_FIELD(channel_info, samples, "[I");
}

static void initFrameInfoFields(JNIEnv* env) {
  if (frame_info.clazz != nullptr) return;
  GET_CLASS(frame_info, "org/helllabs/libxmp/model/FrameInfo");
  GET_FIELD(frame_info, pos, "I");
  GET_FIELD(frame_info, pattern, "I");
  GET_FIELD(frame_info, row, "I");
  GET_FIELD(frame_info, numRows, "I");
  GET_FIELD(frame_info, frame, "I");
  GET_FIELD(frame_info, speed, "I");
  GET_FIELD(frame_info, bpm, "I");
  GET_FIELD(frame_info, time, "I");
  GET_FIELD(frame_info, totalTime, "I");
  GET_FIELD(frame_info, frameTime, "I");
  GET_FIELD(frame_info, bufferSize, "I");
  GET_FIELD(frame_info, totalSize, "I");
  GET_FIELD(frame_info, volume, "I");
  GET_FIELD(frame_info, loopCount, "I");
  GET_FIELD(frame_info, virtChannels, "I");
  GET_FIELD(frame_info, virtUsed, "I");
  GET_FIELD(frame_info, sequence, "I");
}

static void initModInfoFields(JNIEnv* env) {
  if (mod_info.clazz != nullptr) return;
  GET_CLASS(mod_info, "org/helllabs/libxmp/model/ModInfo");
  GET_FIELD(mod_info, name, "Ljava/lang/String;");
  GET_FIELD(mod_info, type, "Ljava/lang/String;");
}

static void initSequenceFields(JNIEnv* env) {
  if (sequence_info.clazz != nullptr) return;
  GET_CLASS(sequence_info, "org/helllabs/libxmp/model/Sequence");
  GET_FIELD(sequence_info, entryPoint, "I");
  GET_FIELD(sequence_info, duration, "I");
}

static void initModVarsFields(JNIEnv* env) {
  if (mod_vars.clazz != nullptr) return;
  GET_CLASS(mod_vars, "org/helllabs/libxmp/model/ModVars");
  GET_FIELD(mod_vars, name, "Ljava/lang/String;");
  GET_FIELD(mod_vars, type, "Ljava/lang/String;");
  GET_FIELD(mod_vars, pat, "I");
  GET_FIELD(mod_vars, trk, "I");
  GET_FIELD(mod_vars, chn, "I");
  GET_FIELD(mod_vars, ins, "I");
  GET_FIELD(mod_vars, smp, "I");
  GET_FIELD(mod_vars, spd, "I");
  GET_FIELD(mod_vars, bpm, "I");
  GET_FIELD(mod_vars, len, "I");
  GET_FIELD(mod_vars, rst, "I");
  GET_FIELD(mod_vars, gvl, "I");
  GET_FIELD(mod_vars, miNumSequences, "I");
  GET_FIELD(mod_vars, miComment, "Ljava/lang/String;");
  GET_FIELD(mod_vars, seqData, "[Lorg/helllabs/libxmp/model/Sequence;");
  GET_FIELD(mod_vars, instruments, "[Ljava/lang/String;");
}

std::vector<unsigned char> readFd(int fd) {
  std::vector<unsigned char> data;
  struct stat st {};
  if (fstat(fd, &st) == 0 && st.st_size > 0) data.reserve(static_cast<size_t>(st.st_size));

  std::array<unsigned char, 64 * 1024> buffer {};
  while (true) {
    ssize_t n = read(fd, buffer.data(), buffer.size());
    if (n < 0) {
      if (errno == EINTR) continue;
      data.clear();
      return data;
    }
    if (n == 0) break;
    data.insert(data.end(), buffer.begin(), buffer.begin() + n);
  }
  return data;
}

std::string openmptString(const char* value) {
  if (!value) return "";
  std::string result(value);
  openmpt_free_string(value);
  return result;
}

std::string metadata(openmpt_module* mod, const char* key) {
  return openmptString(openmpt_module_get_metadata(mod, key));
}

int renderOpenMpt(void* audioData, int32_t numFrames, int32_t numChannels, int32_t bytesPerSample) {
  OpenMptState& state = OpenMptState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.mod || !state.playing) return 0;

  size_t frames = 0;
  if (bytesPerSample == 2 && numChannels == 2) {
    frames = openmpt_module_read_interleaved_stereo(state.mod, state.actual_rate, numFrames, static_cast<int16_t*>(audioData));
  } else {
    std::vector<int16_t> stereo(static_cast<size_t>(numFrames) * 2);
    frames = openmpt_module_read_interleaved_stereo(state.mod, state.actual_rate, numFrames, stereo.data());
    if (bytesPerSample == 2 && numChannels == 1) {
      auto* out = static_cast<int16_t*>(audioData);
      for (size_t i = 0; i < frames; i++) out[i] = static_cast<int16_t>((stereo[i * 2] + stereo[i * 2 + 1]) / 2);
    } else if (bytesPerSample == 4) {
      auto* out = static_cast<int32_t*>(audioData);
      for (size_t i = 0; i < frames; i++) {
        int32_t left = stereo[i * 2] << 16;
        int32_t right = stereo[i * 2 + 1] << 16;
        if (numChannels == 1) {
          out[i] = (left + right) / 2;
        } else {
          out[i * 2] = left;
          out[i * 2 + 1] = right;
        }
      }
    }
  }

  if (frames < static_cast<size_t>(numFrames)) {
    if (state.loop) {
      openmpt_module_set_position_seconds(state.mod, 0.0);
    } else {
      return -1;
    }
  }
  return 0;
}

void destroyModule(OpenMptState& state) {
  if (state.mod) {
    openmpt_module_destroy(state.mod);
    state.mod = nullptr;
  }
  state.mod_is_loaded = false;
  state.playing = false;
  state.duration = 0.0;
  state.selected_subsong = 0;
}
}

METHOD(jboolean, init)(JNIEnv* env, jobject, jint rate, jint ms, jint mode, jint channels, jint api, jint flags) {
  OpenMptState& state = OpenMptState::instance();
  int actual_rate = open_audio(rate, ms, mode, channels, api, flags);
  if (actual_rate < 0) return JNI_FALSE;
  initAudioStatsFields(env);
  initChannelInfoFields(env);
  initFrameInfoFields(env);
  initModInfoFields(env);
  initModVarsFields(env);
  initSequenceFields(env);
  state.actual_rate = actual_rate;
  state.initialized = true;
  set_render_callback(renderOpenMpt);
  return JNI_TRUE;
}

METHOD(jint, deinit)(JNIEnv*, jobject) {
  OpenMptState& state = OpenMptState::instance();
  {
    std::lock_guard<std::mutex> lock(state.mutex);
    destroyModule(state);
    state.initialized = false;
  }
  set_playing(0);
  close_audio();
  return 0;
}

METHOD(jint, loadModuleFd)(JNIEnv* env, jobject, jint fd, jobject modInfo) {
  initModInfoFields(env);
  std::vector<unsigned char> data = readFd(fd);
  close(fd);
  if (data.empty()) return -1;

  int error = OPENMPT_ERROR_OK;
  const char* error_message = nullptr;
  openmpt_module* mod = openmpt_module_create_from_memory2(
    data.data(), data.size(), nullptr, nullptr, nullptr, nullptr, &error, &error_message, nullptr);

  if (!mod) {
    LOG_WARN("loadModuleFd failed: %d %s", error, error_message ? error_message : "");
    openmpt_free_string(error_message);
    return error != 0 ? error : -2;
  }

  OpenMptState& state = OpenMptState::instance();
  {
    std::lock_guard<std::mutex> lock(state.mutex);
    destroyModule(state);
    state.mod = mod;
    state.mod_is_loaded = true;
    state.playing = false;
    state.selected_subsong = std::max(0, openmpt_module_get_selected_subsong(mod));
    state.duration = openmpt_module_get_duration_seconds(mod);
  }

  std::string title = metadata(mod, "title");
  std::string type = metadata(mod, "type");
  jstring name = env->NewStringUTF(title.c_str());
  jstring typeStr = env->NewStringUTF(type.c_str());
  env->SetObjectField(modInfo, mod_info.name, name);
  env->SetObjectField(modInfo, mod_info.type, typeStr);
  env->DeleteLocalRef(name);
  env->DeleteLocalRef(typeStr);
  return 0;
}

METHOD(jboolean, testModuleFd)(JNIEnv* env, jobject, jint fd, jobject modInfo) {
  initModInfoFields(env);
  std::vector<unsigned char> data = readFd(fd);
  close(fd);
  if (data.empty()) return JNI_FALSE;

  int error = OPENMPT_ERROR_OK;
  const char* error_message = nullptr;
  openmpt_module* mod = openmpt_module_create_from_memory2(
    data.data(), data.size(), nullptr, nullptr, nullptr, nullptr, &error, &error_message, nullptr);

  if (!mod) {
    openmpt_free_string(error_message);
    return JNI_FALSE;
  }

  std::string title = metadata(mod, "title");
  std::string type = metadata(mod, "type");
  jstring name = env->NewStringUTF(title.c_str());
  jstring typeStr = env->NewStringUTF(type.c_str());
  env->SetObjectField(modInfo, mod_info.name, name);
  env->SetObjectField(modInfo, mod_info.type, typeStr);
  env->DeleteLocalRef(name);
  env->DeleteLocalRef(typeStr);
  openmpt_module_destroy(mod);
  return JNI_TRUE;
}

METHOD(jint, releaseModule)(JNIEnv*, jobject) {
  OpenMptState& state = OpenMptState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  destroyModule(state);
  return 0;
}

METHOD(jint, startPlayer)(JNIEnv*, jobject, jint rate, jint) {
  OpenMptState& state = OpenMptState::instance();
  state.actual_rate = rate > 0 ? rate : state.actual_rate;
  set_render_callback(renderOpenMpt);
  std::lock_guard<std::mutex> lock(state.mutex);
  state.playing = true;
  set_playing(1);
  return 0;
}

METHOD(jint, endPlayer)(JNIEnv*, jobject) {
  set_playing(0);
  OpenMptState& state = OpenMptState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.playing = false;
  return 0;
}

METHOD(jint, playAudio)(JNIEnv*, jobject) {
  return play_audio();
}

METHOD(jboolean, hasModuleEnded)(JNIEnv*, jobject) {
  return has_module_ended() ? JNI_TRUE : JNI_FALSE;
}

METHOD(void, setOpenMptPlaying)(JNIEnv*, jobject, jboolean value) {
  OpenMptState& state = OpenMptState::instance();
  {
    std::lock_guard<std::mutex> lock(state.mutex);
    state.playing = value == JNI_TRUE;
  }
  set_playing(value == JNI_TRUE ? 1 : 0);
}

METHOD(void, setLoopMode)(JNIEnv*, jobject, jboolean loop) {
  OpenMptState& state = OpenMptState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.loop = loop == JNI_TRUE;
}

METHOD(jint, seek)(JNIEnv*, jobject, jint time) {
  OpenMptState& state = OpenMptState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.mod) return -1;
  openmpt_module_set_position_seconds(state.mod, time / 1000.0);
  return 0;
}

METHOD(jboolean, setSequence)(JNIEnv*, jobject, jint seq) {
  OpenMptState& state = OpenMptState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.mod) return JNI_FALSE;
  if (openmpt_module_select_subsong(state.mod, seq) == 0) return JNI_FALSE;
  state.selected_subsong = seq;
  state.duration = openmpt_module_get_duration_seconds(state.mod);
  return JNI_TRUE;
}

METHOD(void, getFrameInfo)(JNIEnv* env, jobject, jobject frameInfo) {
  initFrameInfoFields(env);
  OpenMptState& state = OpenMptState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.mod) return;
  int pattern = openmpt_module_get_current_pattern(state.mod);
  int row = openmpt_module_get_current_row(state.mod);
  env->SetIntField(frameInfo, frame_info.pos, openmpt_module_get_current_order(state.mod));
  env->SetIntField(frameInfo, frame_info.pattern, pattern);
  env->SetIntField(frameInfo, frame_info.row, row);
  env->SetIntField(frameInfo, frame_info.numRows, openmpt_module_get_pattern_num_rows(state.mod, pattern));
  env->SetIntField(frameInfo, frame_info.speed, openmpt_module_get_current_speed(state.mod));
  env->SetIntField(frameInfo, frame_info.bpm, static_cast<int>(std::round(openmpt_module_get_current_tempo2(state.mod))));
  env->SetIntField(frameInfo, frame_info.time, static_cast<int>(std::round(openmpt_module_get_position_seconds(state.mod) * 1000.0)));
  env->SetIntField(frameInfo, frame_info.totalTime, static_cast<int>(std::round(state.duration * 1000.0)));
  env->SetIntField(frameInfo, frame_info.virtChannels, openmpt_module_get_num_channels(state.mod));
  env->SetIntField(frameInfo, frame_info.virtUsed, openmpt_module_get_current_playing_channels(state.mod));
  env->SetIntField(frameInfo, frame_info.sequence, state.selected_subsong);
}

METHOD(void, getModVars)(JNIEnv* env, jobject, jobject modVars) {
  initModVarsFields(env);
  initSequenceFields(env);
  OpenMptState& state = OpenMptState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.mod) return;

  std::string title = metadata(state.mod, "title");
  std::string type = metadata(state.mod, "type");
  std::string message = metadata(state.mod, "message");
  int channels = openmpt_module_get_num_channels(state.mod);
  int subsongs = std::max(1, openmpt_module_get_num_subsongs(state.mod));
  int durationMs = static_cast<int>(std::round(state.duration * 1000.0));

  jstring titleStr = env->NewStringUTF(title.c_str());
  jstring typeStr = env->NewStringUTF(type.c_str());
  jstring messageStr = env->NewStringUTF(message.c_str());
  env->SetObjectField(modVars, mod_vars.name, titleStr);
  env->SetObjectField(modVars, mod_vars.type, typeStr);
  env->SetObjectField(modVars, mod_vars.miComment, messageStr);
  env->SetIntField(modVars, mod_vars.pat, openmpt_module_get_num_patterns(state.mod));
  env->SetIntField(modVars, mod_vars.trk, openmpt_module_get_num_orders(state.mod));
  env->SetIntField(modVars, mod_vars.chn, channels);
  env->SetIntField(modVars, mod_vars.ins, openmpt_module_get_num_instruments(state.mod));
  env->SetIntField(modVars, mod_vars.smp, openmpt_module_get_num_samples(state.mod));
  env->SetIntField(modVars, mod_vars.spd, openmpt_module_get_current_speed(state.mod));
  env->SetIntField(modVars, mod_vars.bpm, static_cast<int>(std::round(openmpt_module_get_current_tempo2(state.mod))));
  env->SetIntField(modVars, mod_vars.len, openmpt_module_get_num_orders(state.mod));
  env->SetIntField(modVars, mod_vars.gvl, 64);
  env->SetIntField(modVars, mod_vars.miNumSequences, subsongs);

  jclass seqCls = env->FindClass("org/helllabs/libxmp/model/Sequence");
  jobjectArray seqArray = env->NewObjectArray(subsongs, seqCls, nullptr);
  for (int i = 0; i < subsongs; i++) {
    jobject seqObj = env->AllocObject(seqCls);
    env->SetIntField(seqObj, sequence_info.entryPoint, 0);
    env->SetIntField(seqObj, sequence_info.duration, durationMs);
    env->SetObjectArrayElement(seqArray, i, seqObj);
    env->DeleteLocalRef(seqObj);
  }
  env->SetObjectField(modVars, mod_vars.seqData, seqArray);

  jclass stringCls = env->FindClass("java/lang/String");
  jobjectArray insArray = env->NewObjectArray(channels, stringCls, nullptr);
  for (int i = 0; i < channels; i++) {
    std::string name = "Channel " + std::to_string(i + 1);
    jstring s = env->NewStringUTF(name.c_str());
    env->SetObjectArrayElement(insArray, i, s);
    env->DeleteLocalRef(s);
  }
  env->SetObjectField(modVars, mod_vars.instruments, insArray);

  env->DeleteLocalRef(titleStr);
  env->DeleteLocalRef(typeStr);
  env->DeleteLocalRef(messageStr);
  env->DeleteLocalRef(seqCls);
  env->DeleteLocalRef(seqArray);
  env->DeleteLocalRef(stringCls);
  env->DeleteLocalRef(insArray);
}

METHOD(jint, getPatternRows)(JNIEnv*, jobject, jint pat) {
  OpenMptState& state = OpenMptState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.mod) return 0;
  return openmpt_module_get_pattern_num_rows(state.mod, pat);
}

METHOD(void, getPatternRow)(JNIEnv* env, jobject, jint pat, jint row, jbyteArray rowNotes, jbyteArray rowInstruments, jbyteArray rowFxType, jbyteArray rowFxParm) {
  OpenMptState& state = OpenMptState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.mod) return;
  int channels = std::min<int>(64, openmpt_module_get_num_channels(state.mod));
  std::array<jbyte, 64> notes {};
  std::array<jbyte, 64> instruments {};
  std::array<jbyte, 64> fxType {};
  std::array<jbyte, 64> fxParam {};
  fxType.fill(-1);
  fxParam.fill(-1);
  for (int ch = 0; ch < channels; ch++) {
    notes[ch] = static_cast<jbyte>(openmpt_module_get_pattern_row_channel_command(state.mod, pat, row, ch, OPENMPT_MODULE_COMMAND_NOTE));
    instruments[ch] = static_cast<jbyte>(openmpt_module_get_pattern_row_channel_command(state.mod, pat, row, ch, OPENMPT_MODULE_COMMAND_INSTRUMENT));
    fxType[ch] = static_cast<jbyte>(openmpt_module_get_pattern_row_channel_command(state.mod, pat, row, ch, OPENMPT_MODULE_COMMAND_EFFECT));
    fxParam[ch] = static_cast<jbyte>(openmpt_module_get_pattern_row_channel_command(state.mod, pat, row, ch, OPENMPT_MODULE_COMMAND_PARAMETER));
  }
  env->SetByteArrayRegion(rowNotes, 0, channels, notes.data());
  env->SetByteArrayRegion(rowInstruments, 0, channels, instruments.data());
  env->SetByteArrayRegion(rowFxType, 0, channels, fxType.data());
  env->SetByteArrayRegion(rowFxParm, 0, channels, fxParam.data());
}

METHOD(void, getChannelData)(JNIEnv* env, jobject, jobject channelInfo) {
  initChannelInfoFields(env);
  OpenMptState& state = OpenMptState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.mod) return;
  int channels = std::min<int>(64, openmpt_module_get_num_channels(state.mod));
  std::array<int, 64> values {};
  for (int i = 0; i < channels; i++) {
    values[i] = static_cast<int>(std::round(openmpt_module_get_current_channel_vu_mono(state.mod, i) * 64.0f));
  }
  auto set = [&](jfieldID fid) {
    auto arr = (jintArray)env->GetObjectField(channelInfo, fid);
    env->SetIntArrayRegion(arr, 0, channels, values.data());
    env->DeleteLocalRef(arr);
  };
  set(channel_info.volumes);
  set(channel_info.finalVols);
}

METHOD(void, getSampleData)(JNIEnv* env, jobject, jboolean, jint, jint, jint, jint, jint width, jbyteArray buffer) {
  if (!buffer) return;
  std::vector<jbyte> zeros(static_cast<size_t>(std::max(0, width)));
  env->SetByteArrayRegion(buffer, 0, width, zeros.data());
}

METHOD(jint, mute)(JNIEnv*, jobject, jint, jint) {
  return 0;
}

METHOD(jint, setPlayer)(JNIEnv*, jobject, jint parm, jint value) {
  OpenMptState& state = OpenMptState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.mod) return 0;
  if (parm == 1) openmpt_module_ctl_set_integer(state.mod, "render.stereoseparation", value);
  if (parm == 2) openmpt_module_ctl_set_integer(state.mod, "render.interpolationfilterlength", value);
  if (parm == 7) openmpt_module_ctl_set_integer(state.mod, "render.mastergain.millibel", (value - 100) * 60);
  return 0;
}

METHOD(jint, setResampler)(JNIEnv*, jobject, jint mode) {
  OpenMptState& state = OpenMptState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.mod) return 0;

  if (mode == 100 || mode == 101) {
    openmpt_module_ctl_set_boolean(state.mod, "render.resampler.emulate_amiga", 1);
    openmpt_module_ctl_set_text(
      state.mod,
      "render.resampler.emulate_amiga_type",
      mode == 100 ? "a500" : "a1200"
    );
    return 0;
  }

  int filterLength = 2;
  if (mode == 0) filterLength = 1;
  if (mode == 1) filterLength = 2;
  if (mode == 2) filterLength = 4;
  openmpt_module_ctl_set_boolean(state.mod, "render.resampler.emulate_amiga", 0);
  openmpt_module_ctl_set_integer(state.mod, "render.interpolationfilterlength", filterLength);
  return 0;
}

METHOD(void, getAudioStats)(JNIEnv* env, jobject, jobject info) {
  initAudioStatsFields(env);
  struct AudioStats stats {};
  get_audio_stats(&stats);
  env->SetIntField(info, audio_stats.xrunCount, stats.xrun_count);
  env->SetIntField(info, audio_stats.underrunCount, stats.underrun_count);
  env->SetIntField(info, audio_stats.framesPerBurst, stats.frames_per_burst);
  env->SetIntField(info, audio_stats.bufferCapacity, stats.buffer_capacity);
  env->SetIntField(info, audio_stats.bufferSize, stats.buffer_size);
  env->SetIntField(info, audio_stats.sampleRate, stats.sample_rate);
  jstring apiStr = env->NewStringUTF(stats.audio_api ? stats.audio_api : "");
  jstring modeStr = env->NewStringUTF(stats.sharing_mode ? stats.sharing_mode : "");
  jstring perfStr = env->NewStringUTF(stats.perf_mode ? stats.perf_mode : "");
  jstring formatStr = env->NewStringUTF(stats.audio_format ? stats.audio_format : "");
  env->SetObjectField(info, audio_stats.audioApi, apiStr);
  env->SetObjectField(info, audio_stats.sharingMode, modeStr);
  env->SetObjectField(info, audio_stats.perfMode, perfStr);
  env->SetObjectField(info, audio_stats.audioFormat, formatStr);
  env->DeleteLocalRef(apiStr);
  env->DeleteLocalRef(modeStr);
  env->DeleteLocalRef(perfStr);
  env->DeleteLocalRef(formatStr);
}

METHOD(jobjectArray, getFormats)(JNIEnv* env, jobject) {
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

METHOD(jstring, getVersion)(JNIEnv* env, jobject) {
  const char* version = openmpt_get_string("library_version");
  jstring result = env->NewStringUTF(version ? version : "");
  openmpt_free_string(version);
  return result;
}
