package com.lossydragon.modplayer.db

import android.content.Context
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
import com.lossydragon.modplayer.BuildConfig
import com.lossydragon.modplayer.db.entity.ModuleEntity
import com.lossydragon.renderer.Player
import com.lossydragon.renderer.RenderingBackend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import timber.log.Timber

/** The cheap, frequently-updated half of the persisted queue. */
data class PlaybackState(
    val index: Int,
    val positionMs: Long,
    val repeat: Int,
    val shuffle: Boolean
)

data class QueueState(val json: String, val playback: PlaybackState) {
    fun decodeModules(): List<ModuleEntity> = Json.decodeFromString(json)
}

// On-disk identifier: renaming it resets every setting and forgets the music folder unless migrated.
private val Context.dataStore by preferencesDataStore(
    name = "xmp_prefs",
    corruptionHandler = ReplaceFileCorruptionHandler {
        Timber.e("Preferences corrupted, resetting.")
        emptyPreferences()
    }
)

/** Own file: an edit rewrites its whole store, and playback state is saved every few seconds. */
private val Context.queueStore by preferencesDataStore(
    name = "queue",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

/** materialkolor PaletteStyle names in the enum order older builds persisted as an ordinal. */
private val LEGACY_PALETTE_STYLES = listOf(
    "TonalSpot", "Neutral", "Vibrant", "Expressive", "Rainbow",
    "FruitSalad", "Monochrome", "Fidelity", "Content",
)

/** Maps a legacy PaletteStyle ordinal to its name, or [AppPreferences.DEFAULT_THEME_STYLE]. */
fun legacyPaletteStyleName(ordinal: Int?): String =
    ordinal?.let { LEGACY_PALETTE_STYLES.getOrNull(it) } ?: AppPreferences.DEFAULT_THEME_STYLE

// Open so tests can substitute in-memory behaviour for the methods [ModPlayer] depends on.
open class AppPreferences(context: Context) {

    companion object {
        const val DEFAULT_THEME_STYLE = "Rainbow" // Accents UI element with gray-ish background
        const val DEFAULT_THEME_COLOR: Int = 0xFF660099.toInt() // Purple

        const val THEME_MODE_DARK = "dark"
        const val THEME_MODE_LIGHT = "light"
        const val THEME_MODE_AUTO = "auto"
        const val DEFAULT_THEME_MODE = THEME_MODE_DARK
    }

    private val dataStore: DataStore<Preferences> = context.dataStore
    private val queueStore: DataStore<Preferences> = context.queueStore

    private fun <T> flow(key: Preferences.Key<T>, default: T): Flow<T> =
        dataStore.data.map { it[key] ?: default }.distinctUntilChanged()

    private fun <T> flowNullable(key: Preferences.Key<T>): Flow<T?> =
        dataStore.data.map { it[key] }.distinctUntilChanged()

    private suspend fun <T> get(key: Preferences.Key<T>, default: T): T =
        dataStore.data.first()[key] ?: default

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) =
        dataStore.edit { it[key] = value }

    suspend fun resetAll() {
        dataStore.edit { it.clear() }
        queueStore.edit { it.clear() }
    }

    /* Debug */
    private val fileLogging = booleanPreferencesKey("file_logging")
    fun getFileLoggingFlow() = flow(fileLogging, false)
    suspend fun setFileLogging(v: Boolean) = set(fileLogging, v)

    /* Application */
    private val appThemeMode = stringPreferencesKey("app_theme_mode")
    fun getAppThemeModeFlow() = flow(appThemeMode, DEFAULT_THEME_MODE)
    suspend fun setAppThemeMode(v: String) = set(appThemeMode, v)

    private val appThemeAmoled = booleanPreferencesKey("app_theme_amoled")
    fun getAppThemeAmoledFlow() = flow(appThemeAmoled, false)
    suspend fun setAppThemeAmoled(v: Boolean) = set(appThemeAmoled, v)

    private val legacyAppThemeStyle = intPreferencesKey("app_theme_style")
    private val appThemeStyle = stringPreferencesKey("app_theme_style_name")

    /** PaletteStyle name; falls back to the ordinal older builds stored. */
    fun getAppThemeStyleFlow(): Flow<String> = dataStore.data
        .map { it[appThemeStyle] ?: legacyPaletteStyleName(it[legacyAppThemeStyle]) }
        .distinctUntilChanged()

    suspend fun setAppThemeStyle(name: String) = set(appThemeStyle, name)

    private val appThemeColor = intPreferencesKey("app_theme_color")
    fun getThemeColorFlow(): Flow<Int> = flow(appThemeColor, DEFAULT_THEME_COLOR)
    suspend fun setThemeColor(argb: Int) = set(appThemeColor, argb)

    /* File Browser */
    private val lastDirectoryUri = stringPreferencesKey("last_directory_uri")
    fun getLastDirectoryFlow() = flowNullable(lastDirectoryUri)
    suspend fun getLastDirectoryUri() = get(lastDirectoryUri, "").ifBlank { null }
    suspend fun setLastDirectoryUri(v: String) = set(lastDirectoryUri, v)

    private val lastIndexedAt = longPreferencesKey("last_indexed_at")
    suspend fun getLastIndexedAt() = get(lastIndexedAt, 0L)
    suspend fun setLastIndexedAt(v: Long) = set(lastIndexedAt, v)

    private val globalShuffle = booleanPreferencesKey("global_shuffle")
    fun getGlobalShuffleFlow() = flow(globalShuffle, false)
    suspend fun setGlobalShuffle(v: Boolean) = set(globalShuffle, v)

    private val globalSubSongs = booleanPreferencesKey("global_subsongs")
    open fun getGlobalSubSongsFlow(): Flow<Boolean> = flow(globalSubSongs, false)
    suspend fun setGlobalSubSongs(v: Boolean) = set(globalSubSongs, v)

    /* Downloads */
    private val modArchiveApiKey = stringPreferencesKey("mod_archive_api_key")

    /** The key the user typed in, blank when unset. */
    fun getUserApiKeyFlow(): Flow<String> = flow(modArchiveApiKey, "")

    /** The key requests should use: the user's own, else the one compiled in (blank if none). */
    fun getApiKeyFlow(): Flow<String> =
        getUserApiKeyFlow().map { it.ifBlank { BuildConfig.API_KEY } }.distinctUntilChanged()

    suspend fun getApiKey(): String = getApiKeyFlow().first()

    suspend fun setUserApiKey(v: String) = set(modArchiveApiKey, v.trim())

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
    open suspend fun getAutoResume(): Boolean = get(autoResume, false)
    suspend fun setAutoResume(v: Boolean) = set(autoResume, v)

    private val showRowNumbers = booleanPreferencesKey("show_row_numbers")
    fun getRowNumbersFlow() = flow(showRowNumbers, false)
    suspend fun setRowNumbers(v: Boolean) = set(showRowNumbers, v)

    private val queueJson = stringPreferencesKey("queue_json")
    private val queueIndex = intPreferencesKey("queue_index")
    private val queueShuffle = booleanPreferencesKey("queue_shuffle")
    private val queueRepeat = intPreferencesKey("queue_repeat")
    private val queuePositionMs = longPreferencesKey("queue_position_ms")

    /** Stores the serialized queue contents. Call only when the queue itself changes. */
    open suspend fun saveQueueList(json: String) {
        queueStore.edit { it[queueJson] = json }
        // Drop the copy older builds kept in the main store.
        if (queueJson in dataStore.data.first()) dataStore.edit { it.remove(queueJson) }
    }

    /** Stores index / position / modes; small, so fine to call periodically. */
    open suspend fun savePlaybackState(state: PlaybackState) {
        dataStore.edit {
            it[queueIndex] = state.index
            it[queueShuffle] = state.shuffle
            it[queueRepeat] = state.repeat
            it[queuePositionMs] = state.positionMs
        }
    }

    open suspend fun getQueueState(): QueueState? {
        val data = dataStore.data.first()
        // Older builds kept the JSON in the main store.
        val json = (queueStore.data.first()[queueJson] ?: data[queueJson])
            ?.takeIf { it.isNotBlank() } ?: return null
        return QueueState(
            json,
            PlaybackState(
                index = data[queueIndex] ?: 0,
                positionMs = data[queuePositionMs] ?: 0L,
                repeat = data[queueRepeat] ?: 0,
                shuffle = data[queueShuffle] ?: false,
            ),
        )
    }

    open suspend fun clearQueueState() {
        queueStore.edit { it.remove(queueJson) }
        dataStore.edit {
            it.remove(queueJson)
            it.remove(queueIndex)
            it.remove(queueShuffle)
            it.remove(queueRepeat)
            it.remove(queuePositionMs)
        }
    }
}
