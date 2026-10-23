package com.lossydragon.modplayer.player

import android.os.Bundle
import androidx.media3.common.MediaItem
import com.lossydragon.modplayer.db.entity.ModuleEntity
import kotlinx.serialization.json.Json

/**
 * Carries the full [ModuleEntity] inside a [MediaItem]'s metadata extras, so items that
 * round-trip through the session (Android Auto, playback resumption) arrive at
 * [ModPlayer.handleSetMediaItems] with real size / type / name instead of a stub.
 */
internal object MediaItemExtras {
    private const val KEY_MODULE = "com.lossydragon.modplayer.MODULE"

    fun of(entity: ModuleEntity): Bundle = Bundle().apply {
        putString(KEY_MODULE, Json.encodeToString(entity))
    }

    fun moduleFrom(item: MediaItem): ModuleEntity? =
        item.mediaMetadata.extras?.getString(KEY_MODULE)?.let { json ->
            runCatching { Json.decodeFromString<ModuleEntity>(json) }.getOrNull()
        }
}
