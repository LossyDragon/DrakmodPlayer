package com.lossydragon.modplayer.player

/**
 * Canonical media IDs used in the Android Auto browse tree.
 */
object AutoMediaId {

    const val ROOT = "root"
    const val FILE_BROWSER = "browser"
    const val HOME = "home"
    const val PLAYLISTS = "playlists"
    const val PLAY_ALL = "play_all"
    const val SHUFFLE_ALL = "shuffle_all"

    private const val DIR_PREFIX = "dir::"
    private const val FILE_PREFIX = "file::"

    /** Encodes a directory's [filePath] into a stable browsable directory ID. */
    fun dir(filePath: String) = DIR_PREFIX + filePath

    /** Returns true if [id] encodes a directory node. */
    fun isDir(id: String) = id.startsWith(DIR_PREFIX)

    /** Extracts the file path from a directory ID produced by [dir]. */
    fun pathFromDir(id: String) = id.removePrefix(DIR_PREFIX)

    /** Encodes a SAF content URI string into a stable playable file ID. */
    fun file(uri: String) = FILE_PREFIX + uri

    /** Returns true if [id] encodes a playable file node. */
    fun isFile(id: String) = id.startsWith(FILE_PREFIX)

    /** Extracts the raw URI string from a file ID produced by [file]. */
    fun uriFromFile(id: String) = id.removePrefix(FILE_PREFIX)
}
