package com.lossydragon.modplayer.util

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.lossydragon.modplayer.model.Module
import java.io.OutputStream
import timber.log.Timber

data class DirectoryEntry(
    val childUri: Uri,
    val name: String,
    val mime: String,
    val size: Long,
    val isDirectory: Boolean
)

fun Uri.resolveDocId(context: Context): String = when {
    DocumentsContract.isTreeUri(this) &&
        DocumentsContract.isDocumentUri(context, this) ->
        DocumentsContract.getDocumentId(this)

    DocumentsContract.isTreeUri(this) ->
        DocumentsContract.getTreeDocumentId(this)

    else ->
        DocumentsContract.getDocumentId(this)
}

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
                    DocumentsContract.buildDocumentUriUsingTree(treeRoot, childId),
                    name,
                    mime,
                    size,
                    mime == DocumentsContract.Document.MIME_TYPE_DIR
                )
            )
        }
    }
    return entries
}

/**
 * Finds a child directory by [name] under [parentDocId] in the given [treeUri].
 * Returns the document ID of the found directory, or null if not found.
 */
fun ContentResolver.findChildDir(
    treeUri: Uri,
    parentDocId: String,
    name: String
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
            if (cursor.getString(1) == name &&
                cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
            ) {
                return cursor.getString(0)
            }
        }
    }
    return null
}

/**
 * Finds or creates a child directory by [name] under [parentDocId].
 * Returns the document ID, or null if creation fails.
 */
fun ContentResolver.findOrCreateChildDir(
    treeUri: Uri,
    parentDocId: String,
    name: String
): String? {
    findChildDir(treeUri, parentDocId, name)?.let { return it }
    val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
    val newUri = DocumentsContract.createDocument(
        this,
        parentUri,
        DocumentsContract.Document.MIME_TYPE_DIR,
        name
    )
    return newUri?.let { DocumentsContract.getDocumentId(it) }
}

/**
 * Returns the URI of the download directory for [module] under
 * `TheModArchive/{artist}/`, creating it if needed.
 */
fun Context.getDownloadDir(
    rootUri: Uri,
    module: Module
): Uri? = try {
    val rootDoc = DocumentsContract.getTreeDocumentId(rootUri)
    val cr = contentResolver
    val tmaDoc = cr.findOrCreateChildDir(rootUri, rootDoc, "TheModArchive") ?: return null
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
fun Context.findDownloadedModule(
    rootUri: Uri,
    artist: String,
    filename: String
): Uri? = try {
    val rootDocId = DocumentsContract.getTreeDocumentId(rootUri)
    val cr = contentResolver
    val tmaDocId = cr.findChildDir(rootUri, rootDocId, "TheModArchive") ?: return null
    val artistDocId = cr.findChildDir(rootUri, tmaDocId, artist) ?: tmaDocId
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, artistDocId)

    cr.query(
        childrenUri,
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        ),
        null,
        null,
        null,
    )?.use { cursor ->
        while (cursor.moveToNext()) {
            if (cursor.getString(1) == filename) {
                return DocumentsContract.buildDocumentUriUsingTree(rootUri, cursor.getString(0))
            }
        }
    }
    null
} catch (e: Exception) {
    Timber.e(e)
    null
}

/**
 * Returns an [OutputStream] for [module]'s file in the download directory,
 * creating the file if it does not exist. Returns null on failure.
 */
fun Context.getOrCreateOutputFile(
    rootUri: Uri,
    module: Module
): OutputStream? = try {
    val dirUri = getDownloadDir(rootUri, module) ?: return null
    val dirDocId = DocumentsContract.getDocumentId(dirUri)
    val filename = module.url.substringAfterLast('#')
    val cr = contentResolver

    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, dirDocId)
    var fileUri: Uri? = null
    cr.query(
        childrenUri,
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        ),
        null,
        null,
        null,
    )?.use { cursor ->
        while (cursor.moveToNext()) {
            if (cursor.getString(1) == filename) {
                fileUri = DocumentsContract
                    .buildDocumentUriUsingTree(rootUri, cursor.getString(0))
                break
            }
        }
    }

    if (fileUri == null) {
        fileUri = DocumentsContract
            .createDocument(cr, dirUri, "application/octet-stream", filename)
    }

    fileUri?.let { cr.openOutputStream(it) }
} catch (e: Exception) {
    Timber.e(e)
    null
}
