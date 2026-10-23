package com.lossydragon.modplayer.player

import android.content.Context
import com.lossydragon.modplayer.db.AppPreferences
import com.lossydragon.modplayer.db.PlaybackState
import com.lossydragon.modplayer.db.QueueState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Keeps [ModPlayer]'s persistence in memory so tests never touch DataStore files. */
class FakeAppPreferences(context: Context) : AppPreferences(context) {
    val subSongs = MutableStateFlow(false)
    var autoResume = false
    var savedQueueJson: String? = null
    var savedState: PlaybackState? = null
    var clearCount = 0

    override fun getGlobalSubSongsFlow(): Flow<Boolean> = subSongs

    override suspend fun getAutoResume(): Boolean = autoResume

    override suspend fun saveQueueList(json: String) {
        savedQueueJson = json
    }

    override suspend fun savePlaybackState(state: PlaybackState) {
        savedState = state
    }

    override suspend fun getQueueState(): QueueState? {
        val json = savedQueueJson ?: return null
        val state = savedState ?: PlaybackState(0, 0L, 0, false)
        return QueueState(state.index, json, state.positionMs, state.repeat, state.shuffle)
    }

    override suspend fun clearQueueState() {
        clearCount++
        savedQueueJson = null
        savedState = null
    }
}
