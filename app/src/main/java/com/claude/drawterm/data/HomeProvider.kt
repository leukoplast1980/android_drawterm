package com.claude.drawterm.data

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.claude.drawterm.R
import java.io.File
import java.io.FileNotFoundException

/**
 * Shows the drawterm folder (what servers see as /mnt/term) in the Files
 * app and in every app's file picker, so files move freely between Plan 9
 * and Android apps without any storage permission.
 */
class HomeProvider : DocumentsProvider() {
    private val home by lazy { Storage.home(context!!).canonicalFile }

    override fun onCreate() = true

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: ROOT_COLUMNS).apply {
            newRow()
                .add(Root.COLUMN_ROOT_ID, ROOT)
                .add(Root.COLUMN_DOCUMENT_ID, docId(home))
                .add(Root.COLUMN_TITLE, context!!.getString(R.string.app_name))
                .add(Root.COLUMN_SUMMARY, context!!.getString(R.string.files_summary))
                .add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
                .add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
                .add(Root.COLUMN_AVAILABLE_BYTES, home.freeSpace)
        }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply { addFile(file(documentId)) }

    override fun queryChildDocuments(parentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
            file(parentId).listFiles()?.forEach { addFile(it) }
        }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))

    override fun createDocument(parentId: String, mimeType: String, displayName: String): String {
        val parent = file(parentId)
        var f = child(parent, displayName)
        val base = displayName.substringBeforeLast('.')
        val ext = displayName.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var n = 1
        while (f.exists()) f = File(parent, "$base (${n++})$ext")
        val ok = if (mimeType == Document.MIME_TYPE_DIR) f.mkdir() else f.createNewFile()
        if (!ok) throw FileNotFoundException("cannot create $displayName")
        return docId(f)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val f = file(documentId)
        val to = child(f.parentFile!!, displayName)
        if (!f.renameTo(to)) throw FileNotFoundException("cannot rename to $displayName")
        return docId(to)
    }

    override fun deleteDocument(documentId: String) {
        if (!file(documentId).deleteRecursively()) throw FileNotFoundException("cannot delete $documentId")
    }

    override fun isChildDocument(parentId: String, documentId: String): Boolean =
        file(documentId).path.startsWith(file(parentId).path + File.separator)

    override fun getDocumentType(documentId: String): String = mimeType(file(documentId))

    private fun docId(f: File) = ROOT + ":" + f.toRelativeString(home)

    /** The file for a document id, refusing anything outside home. */
    private fun file(documentId: String): File {
        val f = File(home, documentId.substringAfter(':')).canonicalFile
        if (f != home && !f.path.startsWith(home.path + File.separator)) throw FileNotFoundException(documentId)
        return f
    }

    /** [name] inside [dir]; names that would climb out ("..", "a/b") are refused. */
    private fun child(dir: File, name: String): File {
        if (name.isEmpty() || name == "." || name == ".." || '/' in name || '\u0000' in name) {
            throw FileNotFoundException("bad file name: $name")
        }
        return File(dir, name)
    }

    private fun mimeType(f: File): String = when {
        f.isDirectory -> Document.MIME_TYPE_DIR
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase())
            ?: "application/octet-stream"
    }

    private fun MatrixCursor.addFile(f: File) {
        var flags = Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        flags = flags or if (f.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE
        newRow()
            .add(Document.COLUMN_DOCUMENT_ID, docId(f))
            .add(Document.COLUMN_DISPLAY_NAME, if (f == home) context!!.getString(R.string.app_name) else f.name)
            .add(Document.COLUMN_MIME_TYPE, mimeType(f))
            .add(Document.COLUMN_SIZE, if (f.isDirectory) null else f.length())
            .add(Document.COLUMN_LAST_MODIFIED, f.lastModified())
            .add(Document.COLUMN_FLAGS, if (f == home) Document.FLAG_DIR_SUPPORTS_CREATE else flags)
    }

    companion object {
        private const val ROOT = "home"

        /** This folder for a Files app to open; the authority is the one in the manifest. */
        fun rootUri(ctx: Context): Uri = DocumentsContract.buildRootUri("${ctx.packageName}.home", ROOT)

        private val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY,
            Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_AVAILABLE_BYTES,
        )
        private val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )
    }
}
