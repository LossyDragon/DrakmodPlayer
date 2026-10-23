package com.lossydragon.modplayer.player

/**
 * Canonical media IDs used in the Android Auto browse tree.
 */
object AutoMediaId {

    const val ROOT = "root"
    const val FILE_BROWSER = "browser"
    const val HOME = "home"
    const val PLAYLISTS = "playlists"
    const val PLAYLISTS_EMPTY = "playlists_empty"
    const val PLAY_ALL = "play_all"
    const val SHUFFLE_ALL = "shuffle_all"

    private const val DIR_PREFIX = "dir::"
    private const val FILE_PREFIX = "file::"
    private const val PLAYLIST_PREFIX = "playlist::"
    private const val PLAYLIST_ENTRY_PREFIX = "plentry::"
    private const val SEPARATOR = "::"

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

    /** Encodes a Room playlist id into a browsable playlist node. */
    fun playlist(playlistId: Long) = PLAYLIST_PREFIX + playlistId

    /** Returns true if [id] encodes a playlist node. */
    fun isPlaylist(id: String) = id.startsWith(PLAYLIST_PREFIX)

    /** Extracts the playlist id from an ID produced by [playlist], or null if malformed. */
    fun playlistIdFrom(id: String): Long? = id.removePrefix(PLAYLIST_PREFIX).toLongOrNull()

    /**
     * Encodes one entry of a playlist. Distinct from [file] so that tapping it queues the
     * playlist's entries (not the file's directory siblings).
     */
    fun playlistEntry(playlistId: Long, uri: String) =
        PLAYLIST_ENTRY_PREFIX + playlistId + SEPARATOR + uri

    /** Returns true if [id] encodes a playlist entry. */
    fun isPlaylistEntry(id: String) = id.startsWith(PLAYLIST_ENTRY_PREFIX)

    /** Extracts (playlistId, uri) from an ID produced by [playlistEntry], or null if malformed. */
    fun playlistEntryParts(id: String): Pair<Long, String>? {
        val body = id.removePrefix(PLAYLIST_ENTRY_PREFIX)
        val sep = body.indexOf(SEPARATOR)
        if (sep <= 0) return null
        val playlistId = body.substring(0, sep).toLongOrNull() ?: return null
        return playlistId to body.substring(sep + SEPARATOR.length)
    }
}
