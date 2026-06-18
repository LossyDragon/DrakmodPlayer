package com.lossydragon.modplayer.db

import android.content.Context
import androidx.compose.ui.graphics.*
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lossydragon.modplayer.ui.theme.seed
import com.lossydragon.native.Player
import com.lossydragon.native.RenderingBackend
import com.materialkolor.PaletteStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import timber.log.Timber

data class QueueState(
    val index: Int,
    val json: String,
    val positionMs: Long,
    val repeat: Int,
    val shuffle: Boolean
)

private val Context.dataStore by preferencesDataStore(
    name = "xmp_prefs",
    corruptionHandler = ReplaceFileCorruptionHandler {
        Timber.e("Preferences corrupted, resetting.")
        emptyPreferences()
    }
)

class AppPreferences(context: Context) {

    private val dataStore: DataStore<Preferences> = context.dataStore

    private fun <T> flow(key: Preferences.Key<T>, default: T): Flow<T> =
        dataStore.data.map { it[key] ?: default }

    private fun <T> flowNullable(key: Preferences.Key<T>): Flow<T?> =
        dataStore.data.map { it[key] }

    private suspend fun <T> get(key: Preferences.Key<T>, default: T): T =
        dataStore.data.map { it[key] ?: default }.firstOrNull() ?: default

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) =
        dataStore.edit { it[key] = value }

    suspend fun resetAll() = dataStore.edit { it.clear() }

    /* Debug */
    private val fileLogging = booleanPreferencesKey("file_logging")
    fun getFileLoggingFlow() = flow(fileLogging, false)
    suspend fun setFileLogging(v: Boolean) = set(fileLogging, v)

    /* Application */
    private val appThemeAmoled = booleanPreferencesKey("app_theme_amoled")
    fun getAppThemeAmoledFlow() = flow(appThemeAmoled, false)
    suspend fun setAppThemeAmoled(v: Boolean) = set(appThemeAmoled, v)

    private val appThemeStyle = intPreferencesKey("app_theme_style")
    fun getAppThemeStyleFlow() = flow(appThemeStyle, PaletteStyle.Vibrant.ordinal)
    suspend fun setAppThemeStyle(v: Int) = set(appThemeStyle, v)

    private val appThemeColor = intPreferencesKey("app_theme_color")
    fun getThemeColorFlow() = flow(appThemeColor, seed.toArgb())
    suspend fun setThemeColor(v: Int) = set(appThemeColor, v)

    /* File Browser */
    private val lastDirectoryUri = stringPreferencesKey("last_directory_uri")
    fun getLastDirectoryFlow() = flowNullable(lastDirectoryUri)
    suspend fun getLastDirectoryUri() = get(lastDirectoryUri, "").ifBlank { null }
    suspend fun setLastDirectoryUri(v: String) = set(lastDirectoryUri, v)

    private val globalShuffle = booleanPreferencesKey("global_shuffle")
    fun getGlobalShuffleFlow() = flow(globalShuffle, false)
    suspend fun setGlobalShuffle(v: Boolean) = set(globalShuffle, v)

    private val globalSubSongs = booleanPreferencesKey("global_subsongs")
    fun getGlobalSubSongsFlow() = flow(globalSubSongs, false)
    suspend fun setGlobalSubSongs(v: Boolean) = set(globalSubSongs, v)

    /* Player */
    private val renderingBackend = intPreferencesKey("rendering_backend")
    fun getRenderingBackendFlow() = flow(renderingBackend, RenderingBackend.INVALID.id)
        .map(RenderingBackend::fromId)

    suspend fun setRenderingBackend(v: RenderingBackend) = set(renderingBackend, v.id)

    private val sampleRate = intPreferencesKey("sample_rate")
    fun getSampleRateFlow() = flow(sampleRate, Player.DEFAULT_SAMPLE_RATE)
    suspend fun setSampleRate(v: Int) = set(sampleRate, v)

    private val playerFlags = intPreferencesKey("player_flags")
    fun getPlayerFlagsFlow() = flow(playerFlags, 0)
    suspend fun setPlayerFlags(v: Int) = set(playerFlags, v)

    private val bufferMs = intPreferencesKey("buffer_ms")
    fun getBufferMsFlow() = flow(bufferMs, Player.DEFAULT_BUFFER_MS)
    suspend fun setBufferMs(v: Int) = set(bufferMs, v)

    private val defaultPan = intPreferencesKey("default_pan")
    fun getDefaultPanFlow() = flow(defaultPan, Player.DEFAULT_PAN_SEPARATION)
    suspend fun setDefaultPan(v: Int) = set(defaultPan, v)

    private val stereoMix = intPreferencesKey("stereo_mix")
    fun getStereoMixFlow() = flow(stereoMix, Player.DEFAULT_STEREO_MIX)
    suspend fun setStereoMix(v: Int) = set(stereoMix, v)

    private val dspEffects = intPreferencesKey("dsp_effects")
    fun getDspEffectFlow() = flow(dspEffects, Player.XMP_DSP_LOWPASS)
    suspend fun setDspEffect(v: Int) = set(dspEffects, v)

    private val interpolationType = intPreferencesKey("interpolation_type")
    fun getInterpolationTypeFlow() = flow(interpolationType, Player.DEFAULT_INTERPOLATION)
    suspend fun setInterpolationType(v: Int) = set(interpolationType, v)

    private val playerVolume = intPreferencesKey("player_volume")
    fun getPlayerVolumeFlow() = flow(playerVolume, Player.DEFAULT_PLAYER_VOLUME)
    suspend fun setPlayerVolume(v: Int) = set(playerVolume, v)

    private val volumeBoost = intPreferencesKey("volume_boost")
    fun getVolumeBoostFlow() = flow(volumeBoost, Player.DEFAULT_VOLUME_BOOST)
    suspend fun setVolumeBoost(v: Int) = set(volumeBoost, v)

    private val playerFormat = intPreferencesKey("player_format")
    fun getPlayerFormatFlow(): Flow<Int> = flow(playerFormat, 0)
    suspend fun setPlayerFormat(v: Int) = set(playerFormat, v)

    private val playerView = intPreferencesKey("player_view")
    fun getPlayerViewFlow(): Flow<Int> = flow(playerView, 0)
    suspend fun setPlayerView(v: Int) = set(playerView, v)

    /* Oboe */
    private val oboePerfMode = intPreferencesKey("oboe_perf_mode")
    fun getOboePerfModeFlow() = flow(oboePerfMode, Player.OBOE_PERFMODE_LOWLATENCY)
    suspend fun setOboePerfMode(v: Int) = set(oboePerfMode, v)

    private val oboeAudioApi = intPreferencesKey("oboe_audio_api")
    fun getOboeAudioApiFlow() = flow(oboeAudioApi, Player.OBOE_AUDIO_API_UNSPECIFIED)
    suspend fun setOboeAudioApi(v: Int) = set(oboeAudioApi, v)

    /* Playback */
    private val autoResume = booleanPreferencesKey("auto_resume")
    fun getAutoResumeFlow() = flow(autoResume, false)
    suspend fun getAutoResume() = get(autoResume, false)
    suspend fun setAutoResume(v: Boolean) = set(autoResume, v)

    private val showRowNumbers = booleanPreferencesKey("show_row_numbers")
    fun getRowNumbersFlow() = flow(showRowNumbers, false)
    suspend fun setRowNumbers(v: Boolean) = set(showRowNumbers, v)

    private val queueJson = stringPreferencesKey("queue_json")
    private val queueIndex = intPreferencesKey("queue_index")
    private val queueShuffle = booleanPreferencesKey("queue_shuffle")
    private val queueRepeat = intPreferencesKey("queue_repeat")
    private val queuePositionMs = longPreferencesKey("queue_position_ms")
    suspend fun saveQueueState(
        json: String,
        index: Int,
        shuffle: Boolean,
        repeat: Int,
        positionMs: Long
    ) = dataStore.edit {
        it[queueJson] = json
        it[queueIndex] = index
        it[queueShuffle] = shuffle
        it[queueRepeat] = repeat
        it[queuePositionMs] = positionMs
    }

    suspend fun getQueueState(): QueueState? {
        val data = dataStore.data.firstOrNull() ?: return null
        val json = data[queueJson]?.takeIf { it.isNotBlank() } ?: return null
        return QueueState(
            json = json,
            index = data[queueIndex] ?: 0,
            shuffle = data[queueShuffle] ?: false,
            repeat = data[queueRepeat] ?: 0,
            positionMs = data[queuePositionMs] ?: 0L,
        )
    }

    suspend fun clearQueueState() = dataStore.edit {
        it.remove(queueJson)
        it.remove(queueIndex)
        it.remove(queueShuffle)
        it.remove(queueRepeat)
        it.remove(queuePositionMs)
    }
}
