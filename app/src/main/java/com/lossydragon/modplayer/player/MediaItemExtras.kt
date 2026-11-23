package com.lossydragon.modplayer.player

import android.os.Bundle
import androidx.media3.common.MediaItem
import com.lossydragon.modplayer.db.entity.ModuleEntity

/**
 * Carries the full [ModuleEntity] inside a [MediaItem]'s metadata extras, so items that
 * round-trip through the session (Android Auto, playback resumption) arrive at
 * [ModPlayer.handleSetMediaItems] with real size / type / name instead of a stub.
 */
internal object MediaItemExtras {
    private const val PREFIX = "com.lossydragon.modplayer.MODULE."
    private const val ID = PREFIX + "id"
    private const val FILENAME = PREFIX + "filename"
    private const val EXTENSION = PREFIX + "fileExtension"
    private const val PATH = PREFIX + "filePath"
    private const val PARENT = PREFIX + "parentPath"
    private const val SIZE = PREFIX + "fileSize"
    private const val DIRECTORY = PREFIX + "isDirectory"
    private const val VALID = PREFIX + "isValidModule"
    private const val NAME = PREFIX + "moduleName"
    private const val TYPE = PREFIX + "moduleType"

    fun of(entity: ModuleEntity): Bundle = Bundle().apply {
        putInt(ID, entity.id)
        putString(FILENAME, entity.filename)
        putString(EXTENSION, entity.fileExtension)
        putString(PATH, entity.filePath)
        putString(PARENT, entity.parentPath)
        putLong(SIZE, entity.fileSize)
        putBoolean(DIRECTORY, entity.isDirectory)
        putBoolean(VALID, entity.isValidModule)
        putString(NAME, entity.moduleName)
        putString(TYPE, entity.moduleType)
    }

    fun moduleFrom(item: MediaItem): ModuleEntity? {
        val extras = item.mediaMetadata.extras ?: return null
        val path = extras.getString(PATH) ?: return null
        return ModuleEntity(
            id = extras.getInt(ID),
            filename = extras.getString(FILENAME).orEmpty(),
            fileExtension = extras.getString(EXTENSION).orEmpty(),
            filePath = path,
            parentPath = extras.getString(PARENT).orEmpty(),
            fileSize = extras.getLong(SIZE),
            isDirectory = extras.getBoolean(DIRECTORY),
            isValidModule = extras.getBoolean(VALID),
            moduleName = extras.getString(NAME).orEmpty(),
            moduleType = extras.getString(TYPE).orEmpty(),
        )
    }
}
