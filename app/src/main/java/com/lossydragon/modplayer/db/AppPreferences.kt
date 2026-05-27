package com.lossydragon.modplayer.db

import android.content.Context
import androidx.compose.ui.graphics.toArgb
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
import com.lossydragon.modplayer.model.QueueState
import com.lossydragon.modplayer.ui.theme.seed
import com.materialkolor.PaletteStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import org.helllabs.libxmp.Xmp
import timber.log.Timber

private val Context.dataStore by preferencesDataStore(
    name = "xmp_prefs",
    corruptionHandler = ReplaceFileCorruptionHandler {
        Timber.e("Preferences corrupted, resetting.")
        emptyPreferences()
    }
)

class AppPreferences(context: Context) {

    private val dataStore: DataStore<Preferences> = context.dataStore

    private val appThemeStyle = intPreferencesKey("app_theme_style")
    private val appThemeAmoled = booleanPreferencesKey("app_theme_amoled")
    private val appThemeColor = intPreferencesKey("app_theme_color")
    private val lastDirectoryUri = stringPreferencesKey("last_directory_uri")
    private val sampleRate = intPreferencesKey("sample_rate")
    private val bufferMs = intPreferencesKey("buffer_ms")
    private val defaultPan = intPreferencesKey("default_pan")
    private val volumeBoost = intPreferencesKey("volume_boost")
    private val stereoMix = intPreferencesKey("stereo_mix")
    private val dspEffects = intPreferencesKey("dsp_effects")
    private val playerVolume = intPreferencesKey("player_volume")
    private val interpolationType = intPreferencesKey("interpolation_type")
    private val playerFlags = intPreferencesKey("player_flags")
    private val autoResume = booleanPreferencesKey("auto_resume")
    private val queueJson = stringPreferencesKey("queue_json")
    private val queueIndex = intPreferencesKey("queue_index")
    private val queueShuffle = booleanPreferencesKey("queue_shuffle")
    private val queueRepeat = intPreferencesKey("queue_repeat")
    private val queuePositionMs = longPreferencesKey("queue_position_ms")

    private fun <T> flow(key: Preferences.Key<T>, default: T): Flow<T> =
        dataStore.data.map { it[key] ?: default }

    private fun <T> flowNullable(key: Preferences.Key<T>): Flow<T?> =
        dataStore.data.map { it[key] }

    private suspend fun <T> get(key: Preferences.Key<T>, default: T): T =
        dataStore.data.map { it[key] ?: default }.firstOrNull() ?: default

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) =
        dataStore.edit { it[key] = value }

    suspend fun resetAll() = dataStore.edit { it.clear() }

    /* Application */
    fun getAppThemeAmoledFlow() = flow(appThemeAmoled, false)
    suspend fun setAppThemeAmoled(v: Boolean) = set(appThemeAmoled, v)

    fun getAppThemeStyleFlow() = flow(appThemeStyle, PaletteStyle.Vibrant.ordinal)
    suspend fun setAppThemeStyle(v: Int) = set(appThemeStyle, v)

    fun getThemeColorFlow() = flow(appThemeColor, seed.toArgb())
    suspend fun setThemeColor(v: Int) = set(appThemeColor, v)

    /* File Browser */
    fun getLastDirectoryFlow() = flowNullable(lastDirectoryUri)
    suspend fun getLastDirectoryUri() = get(lastDirectoryUri, "").ifBlank { null }
    suspend fun setLastDirectoryUri(v: String) = set(lastDirectoryUri, v)

    /* Player */
    fun getSampleRateFlow() = flow(sampleRate, Xmp.DEFAULT_SAMPLE_RATE)
    suspend fun setSampleRate(v: Int) = set(sampleRate, v)

    fun getPlayerFlagsFlow() = flow(playerFlags, 0)
    suspend fun setPlayerFlags(v: Int) = set(playerFlags, v)

    fun getBufferMsFlow() = flow(bufferMs, Xmp.DEFAULT_BUFFER_MS)
    suspend fun setBufferMs(v: Int) = set(bufferMs, v)

    fun getDefaultPanFlow() = flow(defaultPan, Xmp.DEFAULT_PAN_SEPARATION)
    suspend fun setDefaultPan(v: Int) = set(defaultPan, v)

    fun getStereoMixFlow() = flow(stereoMix, Xmp.DEFAULT_STEREO_MIX)
    suspend fun setStereoMix(v: Int) = set(stereoMix, v)

    fun getDspEffectFlow() = flow(dspEffects, Xmp.XMP_DSP_LOWPASS)
    suspend fun setDspEffect(v: Int) = set(dspEffects, v)

    fun getInterpolationTypeFlow() = flow(interpolationType, Xmp.DEFAULT_INTERPOLATION)
    suspend fun setInterpolationType(v: Int) = set(interpolationType, v)

    fun getPlayerVolumeFlow() = flow(playerVolume, Xmp.DEFAULT_PLAYER_VOLUME)
    suspend fun setPlayerVolume(v: Int) = set(playerVolume, v)

    fun getVolumeBoostFlow() = flow(volumeBoost, Xmp.DEFAULT_VOLUME_BOOST)
    suspend fun setVolumeBoost(v: Int) = set(volumeBoost, v)

    /* Playback */
    fun getAutoResumeFlow() = flow(autoResume, false)
    suspend fun getAutoResume() = get(autoResume, false)
    suspend fun setAutoResume(v: Boolean) = set(autoResume, v)

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
