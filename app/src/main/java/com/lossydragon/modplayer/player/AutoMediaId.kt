package com.lossydragon.modplayer.player

import android.net.Uri
import androidx.core.net.toUri
import com.lossydragon.modplayer.player.AutoMediaId.dir
import com.lossydragon.modplayer.player.AutoMediaId.file

/**
 * Canonical media IDs used in the Android Auto browse tree.
 */
object AutoMediaId {

    const val ROOT = "root"
    const val FILE_BROWSER = "browser"
    const val PLAYLISTS = "playlists"
    const val PLAY_ALL = "play_all"
    const val SHUFFLE_ALL = "shuffle_all"

    /** Encodes [treeUri] and [docId] into a stable browsable directory ID. */
    fun dir(treeUri: Uri, docId: String) = "dir::$treeUri||$docId"

    /** Returns true if [id] encodes a directory node. */
    fun isDir(id: String) = id.startsWith("dir::")

    /** Extracts the SAF tree [Uri] from a directory ID produced by [dir]. */
    fun treeUriFromDir(id: String) = id.removePrefix("dir::").substringBefore("||").toUri()

    /** Extracts the document ID from a directory ID produced by [dir]. */
    fun docIdFromDir(id: String) = id.removePrefix("dir::").substringAfter("||")

    /** Encodes a SAF content URI string into a stable playable file ID. */
    fun file(uri: String) = "file::$uri"

    /** Returns true if [id] encodes a playable file node. */
    fun isFile(id: String) = id.startsWith("file::")

    /** Extracts the raw URI string from a file ID produced by [file]. */
    fun uriFromFile(id: String) = id.removePrefix("file::")
}
