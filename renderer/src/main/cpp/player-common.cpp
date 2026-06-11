#include "player-common.h"

AudioStatsFields g_audioStats{};
ChannelInfoFields g_channelInfo{};
FrameInfoFields g_frameInfo{};
ModInfoFields g_modInfo{};
ModVarsFields g_modVars{};
SequenceFields g_sequence{};

void initAudioStatsFields(JNIEnv* env) {
  if (g_audioStats.clazz) return;
  GET_CLASS(g_audioStats, "com/lossydragon/native/model/AudioStats");
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
}

void initChannelInfoFields(JNIEnv* env) {
  if (g_channelInfo.clazz) return;
  GET_CLASS(g_channelInfo, "com/lossydragon/native/model/ChannelInfo");
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
}

void initFrameInfoFields(JNIEnv* env) {
  if (g_frameInfo.clazz) return;
  GET_CLASS(g_frameInfo, "com/lossydragon/native/model/FrameInfo");
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
}

void initModInfoFields(JNIEnv* env) {
  if (g_modInfo.clazz) return;
  GET_CLASS(g_modInfo, "com/lossydragon/native/model/ModInfo");
  GET_FIELD(g_modInfo, name, "Ljava/lang/String;");
  GET_FIELD(g_modInfo, type, "Ljava/lang/String;");
}

void initModVarsFields(JNIEnv* env) {
  if (g_modVars.clazz) return;
  GET_CLASS(g_modVars, "com/lossydragon/native/model/ModVars");
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
  GET_FIELD(g_modVars, seqData, "[Lcom/lossydragon/native/model/Sequence;");
  GET_FIELD(g_modVars, instruments, "[Ljava/lang/String;");
  GET_FIELD(g_modVars, renderingEngine, "Ljava/lang/String;");
}

void initSequenceFields(JNIEnv* env) {
  if (g_sequence.clazz) return;
  GET_CLASS(g_sequence, "com/lossydragon/native/model/Sequence");
  GET_FIELD(g_sequence, entryPoint, "I");
  GET_FIELD(g_sequence, duration, "I");
}
