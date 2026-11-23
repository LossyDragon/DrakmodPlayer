package com.lossydragon.modplayer.util

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.lossydragon.modplayer.model.Module
import java.io.OutputStream
import timber.log.Timber

/** A single child entry returned by [queryDirectoryEntries]. */
data class DirectoryEntry(
    val childUri: Uri,
    val docId: String,
    val name: String,
    val size: Long,
    val isDirectory: Boolean
)

/**
 * Returns true if this SAF URI is still accessible.
 * Returns false if the file no longer exists or the app no longer holds permission.
 */
fun Uri.isAccessible(context: Context): Boolean = try {
    context.contentResolver.query(
        this,
        arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
        null,
        null,
        null,
    )?.use { it.count >= 0 } ?: false
} catch (e: Exception) {
    Timber.w(e, "URI accessibility check failed: $this")
    false
}

/**
 * Resolves the correct document ID for this URI, handling both tree and document URI forms.
 */
fun Uri.resolveDocId(context: Context): String = when {
    DocumentsContract.isTreeUri(this) &&
        DocumentsContract.isDocumentUri(context, this) ->
        DocumentsContract.getDocumentId(this)

    DocumentsContract.isTreeUri(this) ->
        DocumentsContract.getTreeDocumentId(this)

    else ->
        DocumentsContract.getDocumentId(this)
}

/**
 * Queries all immediate children of [docId] inside [treeRoot] and returns them as [DirectoryEntry] list.
 */
fun ContentResolver.queryDirectoryEntries(treeRoot: Uri, docId: String): List<DirectoryEntry> {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeRoot, docId)
    val entries = mutableListOf<DirectoryEntry>()
    query(
        childrenUri,
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        ),
        null,
        null,
        null,
    )?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
        val sizeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
        while (cursor.moveToNext()) {
            val childId = cursor.getString(idCol)
            val name = cursor.getString(nameCol) ?: continue
            val mime = cursor.getString(mimeCol) ?: continue
            val size = cursor.getLong(sizeCol)
            entries.add(
                DirectoryEntry(
                    childUri = DocumentsContract.buildDocumentUriUsingTree(treeRoot, childId),
                    docId = childId,
                    name = name,
                    size = size,
                    isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR
                )
            )
        }
    }
    return entries
}

private const val DOWNLOAD_ROOT = "TheModArchive"

/** Document id of the child of [parentDocId] named [name], or null. */
private fun ContentResolver.findChild(
    treeUri: Uri,
    parentDocId: String,
    name: String,
    dirOnly: Boolean = false
): String? {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
    query(
        childrenUri,
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        ),
        null,
        null,
        null,
    )?.use { cursor ->
        while (cursor.moveToNext()) {
            if (cursor.getString(1) != name) continue
            if (dirOnly && cursor.getString(2) != DocumentsContract.Document.MIME_TYPE_DIR) continue
            return cursor.getString(0)
        }
    }
    return null
}

/** Finds or creates the child directory [name]; returns its document id, or null. */
private fun ContentResolver.findOrCreateChildDir(
    treeUri: Uri,
    parentDocId: String,
    name: String
): String? {
    findChild(treeUri, parentDocId, name, dirOnly = true)?.let { return it }
    val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
    val newUri = DocumentsContract.createDocument(
        this,
        parentUri,
        DocumentsContract.Document.MIME_TYPE_DIR,
        name
    )
    return newUri?.let { DocumentsContract.getDocumentId(it) }
}

/** The download directory for [module], `TheModArchive/{artist}/`, created if needed. */
private fun Context.getDownloadDir(
    rootUri: Uri,
    module: Module
): Uri? = try {
    val rootDoc = DocumentsContract.getTreeDocumentId(rootUri)
    val cr = contentResolver
    val tmaDoc = cr.findOrCreateChildDir(rootUri, rootDoc, DOWNLOAD_ROOT) ?: return null
    val artistDoc = cr.findOrCreateChildDir(rootUri, tmaDoc, module.artist) ?: tmaDoc
    DocumentsContract.buildDocumentUriUsingTree(rootUri, artistDoc)
} catch (e: Exception) {
    Timber.e(e)
    null
}

/**
 * Locates a downloaded module file by [filename] under the artist's directory.
 * Returns the file URI, or null if not found.
 */
internal fun Context.findDownloadedModule(
    rootUri: Uri,
    artist: String,
    filename: String
): Uri? = try {
    val rootDocId = DocumentsContract.getTreeDocumentId(rootUri)
    val cr = contentResolver
    val tmaDocId = cr.findChild(rootUri, rootDocId, DOWNLOAD_ROOT, dirOnly = true)
    val dirDocId = tmaDocId?.let { cr.findChild(rootUri, it, artist, dirOnly = true) ?: it }
    dirDocId?.let { cr.findChild(rootUri, it, filename) }
        ?.let { DocumentsContract.buildDocumentUriUsingTree(rootUri, it) }
} catch (e: Exception) {
    Timber.e(e)
    null
}

/** An in-progress download: bytes go to [partUri]; [finalName] is applied by [finalizeDownload]. */
class PartialDownload(
    val dirUri: Uri,
    val partUri: Uri,
    val finalName: String
)

private const val PART_SUFFIX = ".part"

/** Finds a child document of [dirUri] named [name], or null. */
private fun ContentResolver.findChildFile(rootUri: Uri, dirUri: Uri, name: String): Uri? =
    findChild(rootUri, DocumentsContract.getDocumentId(dirUri), name)
        ?.let { DocumentsContract.buildDocumentUriUsingTree(rootUri, it) }

/**
 * Creates a `<filename>.part` document for [module] in its download directory (replacing a stale
 * one), so an interrupted download never leaves a file that looks complete. Open it with
 * [openPartialDownload] once there are bytes to write.
 */
fun Context.createPartialDownload(
    rootUri: Uri,
    module: Module
): PartialDownload? = try {
    val dirUri = getDownloadDir(rootUri, module) ?: return null
    val finalName = module.downloadFilename
    val partName = finalName + PART_SUFFIX
    val cr = contentResolver

    cr.findChildFile(rootUri, dirUri, partName)?.let { DocumentsContract.deleteDocument(cr, it) }
    val partUri = DocumentsContract.createDocument(cr, dirUri, "application/octet-stream", partName)
        ?: return null
    PartialDownload(dirUri, partUri, finalName)
} catch (e: Exception) {
    Timber.e(e)
    null
}

/** Opens the `.part` document for writing. Caller closes it. */
fun Context.openPartialDownload(download: PartialDownload): OutputStream? = try {
    contentResolver.openOutputStream(download.partUri)
} catch (e: Exception) {
    Timber.e(e)
    null
}

/** Replaces any existing final file and renames the `.part` into place. Returns the final URI. */
fun Context.finalizeDownload(rootUri: Uri, download: PartialDownload): Uri? = try {
    val cr = contentResolver
    cr.findChildFile(rootUri, download.dirUri, download.finalName)
        ?.let { DocumentsContract.deleteDocument(cr, it) }
    DocumentsContract.renameDocument(cr, download.partUri, download.finalName)
} catch (e: Exception) {
    Timber.e(e)
    null
}

/** Removes the `.part` file of a failed or cancelled download. */
fun Context.discardPartialDownload(download: PartialDownload) {
    try {
        DocumentsContract.deleteDocument(contentResolver, download.partUri)
    } catch (e: Exception) {
        Timber.w(e, "Could not delete ${download.partUri}")
    }
}
