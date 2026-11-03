package dev.noir.player

import android.database.MatrixCursor
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** App-private synthetic SAF provider, excluded from release builds. */
class ScanFixtureProvider : DocumentsProvider() {
    private val root get() = File(requireNotNull(context).filesDir, "scan-fixture").apply { mkdirs() }
    override fun onCreate() = true
    override fun queryRoots(projection: Array<out String>?) = MatrixCursor(projection ?: arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID))
    private fun file(id: String): File {
        if (id == "root") return root
        require(id.isNotBlank() && !id.contains('/') && !id.contains('\\') && id != "." && id != "..")
        return File(root, id)
    }
    private fun cursor(projection: Array<out String>?, files: List<File>): MatrixCursor {
        val columns = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        return MatrixCursor(columns).apply {
            files.forEach { file -> addRow(columns.map { column -> when(column) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> if (file == root) "root" else file.name
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> file.name
                DocumentsContract.Document.COLUMN_MIME_TYPE -> if(file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "audio/flac"
                DocumentsContract.Document.COLUMN_SIZE -> file.length()
                DocumentsContract.Document.COLUMN_LAST_MODIFIED -> file.lastModified()
                else -> null
            } }.toTypedArray<Any?>()) }
        }
    }
    override fun queryDocument(documentId: String, projection: Array<out String>?) = cursor(projection,listOf(file(documentId)))
    override fun isChildDocument(parentDocumentId: String, documentId: String) = parentDocumentId == "root" && documentId != "root" && file(documentId).isFile
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): MatrixCursor {
        check(parentDocumentId == "root")
        return cursor(projection,root.listFiles().orEmpty().filter{it.isFile}).apply {
            if (incomplete) extras = Bundle().apply {putBoolean(DocumentsContract.EXTRA_LOADING,true)}
            setNotificationUri(requireNotNull(context).contentResolver, DocumentsContract.buildChildDocumentsUri(AUTHORITY,"root"))
        }
    }
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        check(mode == "r"); reads.incrementAndGet()
        return ParcelFileDescriptor.open(file(documentId),ParcelFileDescriptor.MODE_READ_ONLY)
    }
    companion object {
        const val AUTHORITY="dev.noir.player.scan-fixture"
        val reads=AtomicInteger()
        @Volatile var incomplete=false
    }
}
