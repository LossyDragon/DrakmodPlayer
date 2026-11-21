package com.lossydragon.modplayer.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.lossydragon.modplayer.model.Module
import com.lossydragon.modplayer.util.DirectoryEntry
import com.lossydragon.modplayer.util.PartialDownload
import com.lossydragon.modplayer.util.createPartialDownload
import com.lossydragon.modplayer.util.discardPartialDownload
import com.lossydragon.modplayer.util.finalizeDownload
import com.lossydragon.modplayer.util.findDownloadedModule
import com.lossydragon.modplayer.util.isAccessible
import com.lossydragon.modplayer.util.openPartialDownload
import com.lossydragon.modplayer.util.queryDirectoryEntries
import com.lossydragon.modplayer.util.resolveDocId
import com.lossydragon.native.Player
import com.lossydragon.native.model.ModInfo
import java.io.OutputStream
import timber.log.Timber

/** Every Storage Access Framework call the app makes, so repositories and ViewModels need no Context. */
class SafDataSource(private val context: Context) {

    private companion object {
        const val MAX_FILE_GRANTS = 16
        const val RW_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }

    private val resolver get() = context.contentResolver

    /* Grants */

    fun hasPersistedGrant(uri: Uri): Boolean =
        resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    /** Persists read/write access to [root]; drops the grant on [previousRoot] if it differs. */
    fun replaceRootGrant(root: Uri, previousRoot: Uri?) {
        resolver.takePersistableUriPermission(root, RW_FLAGS)
        if (previousRoot != null && previousRoot != root) release(previousRoot, RW_FLAGS)
    }

    /**
     * Persists a read grant on a single file (ACTION_VIEW), then releases the oldest file grants
     * beyond [MAX_FILE_GRANTS] so the per-app cap never blocks the root-folder grant.
     */
    fun persistFileGrant(uri: Uri) {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        resolver.persistedUriPermissions
            .filterNot { DocumentsContract.isTreeUri(it.uri) }
            .sortedByDescending { it.persistedTime }
            .drop(MAX_FILE_GRANTS)
            .forEach { release(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private fun release(uri: Uri, flags: Int) {
        try {
            resolver.releasePersistableUriPermission(uri, flags)
        } catch (e: SecurityException) {
            Timber.w(e, "Could not release grant for $uri")
        }
    }

    /* Directory tree */

    fun resolveDocId(uri: Uri): String = uri.resolveDocId(context)

    fun queryDirectoryEntries(treeRoot: Uri, docId: String): List<DirectoryEntry> =
        resolver.queryDirectoryEntries(treeRoot, docId)

    fun isAccessible(uri: Uri): Boolean = uri.isAccessible(context)

    /** Probes [uri] with the native loader; fills [modInfo] on success. Never throws. */
    fun testModule(uri: Uri, modInfo: ModInfo): Boolean = Player.testFromFd(context, uri, modInfo)

    fun deleteDocument(uri: Uri): Boolean = DocumentsContract.deleteDocument(resolver, uri)

    /* Downloads */

    fun findDownloadedModule(rootUri: Uri, artist: String, filename: String): Uri? =
        context.findDownloadedModule(rootUri, artist, filename)

    fun createPartialDownload(rootUri: Uri, module: Module): PartialDownload? =
        context.createPartialDownload(rootUri, module)

    fun openPartialDownload(download: PartialDownload): OutputStream? =
        context.openPartialDownload(download)

    fun finalizeDownload(rootUri: Uri, download: PartialDownload): Uri? =
        context.finalizeDownload(rootUri, download)

    fun discardPartialDownload(download: PartialDownload) = context.discardPartialDownload(download)

    /* Plain documents */

    fun writeText(uri: Uri, text: String) {
        resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: error("Could not open output stream for $uri")
    }

    fun readText(uri: Uri): String =
        resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("Could not open input stream for $uri")
}
