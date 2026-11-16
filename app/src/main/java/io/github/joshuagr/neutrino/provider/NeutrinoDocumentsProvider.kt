package io.github.joshuagr.neutrino.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.util.Log
import io.github.joshuagr.neutrino.BuildConfig
import io.github.joshuagr.neutrino.R
import io.github.joshuagr.neutrino.auth.SessionManager
import io.github.joshuagr.neutrino.drive.AccountKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.koin.core.context.GlobalContext
import java.io.File
import java.io.FileNotFoundException

/**
 * Exposes the signed-in account's "My files" through the system file picker. Read-only for now:
 * files are downloaded to a private temporary file for the duration of the open and deleted on close.
 *
 * Providers are created before Application.onCreate, so dependencies are looked up on first use.
 */
class NeutrinoDocumentsProvider : DocumentsProvider() {
    private val sessions by lazy { GlobalContext.get().get<SessionManager>() }
    private val keys by lazy { GlobalContext.get().get<AccountKeys>() }
    private val docs by lazy { GlobalContext.get().get<DriveDocuments>() }
    private val closeHandler by lazy { Handler(HandlerThread("neutrino-close").apply { start() }.looper) }
    private val openDir by lazy { File(requireNotNull(context).noBackupFilesDir, "open").apply { mkdirs() } }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_COLUMNS)
        if (sessions.current() == null) return cursor
        val rootDoc = blocking { docs.rootId() }
        cursor.newRow()
            .add(Root.COLUMN_ROOT_ID, ROOT_ID)
            .add(Root.COLUMN_DOCUMENT_ID, rootDoc)
            .add(Root.COLUMN_TITLE, context?.getString(R.string.app_name))
            .add(Root.COLUMN_SUMMARY, keys.primaryEmail)
            .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD)
            .add(Root.COLUMN_ICON, android.R.drawable.ic_menu_upload)
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).also { addRow(it, blocking { docs.get(documentId) }) }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).also { cursor ->
            blocking { docs.children(parentDocumentId) }.forEach { addRow(cursor, it) }
        }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        blocking { docs.isDescendant(parentDocumentId, documentId) }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Writing is not supported yet")
        val tmp = File.createTempFile("open", ".bin", openDir)
        try {
            blocking { docs.download(documentId, tmp) }
        } catch (e: FileNotFoundException) {
            tmp.delete()
            throw e
        }
        if (BuildConfig.DEBUG) Log.i(TAG, "open r by ${callingPackage ?: "?"}: ${tmp.length()} bytes")
        return ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY, closeHandler) { tmp.delete() }
    }

    private fun addRow(cursor: MatrixCursor, doc: DocMeta) {
        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, doc.id)
            .add(Document.COLUMN_DISPLAY_NAME, doc.name)
            .add(Document.COLUMN_MIME_TYPE, if (doc.isFolder) Document.MIME_TYPE_DIR else doc.mimeType ?: DEFAULT_MIME)
            .add(Document.COLUMN_SIZE, doc.size)
            .add(Document.COLUMN_LAST_MODIFIED, doc.lastModified)
            .add(Document.COLUMN_FLAGS, 0)
    }

    /**
     * Provider calls are synchronous binder calls; the Drive work behind them is suspending. Failures
     * surface as FileNotFoundException, the one exception the framework reports back to the client.
     */
    private fun <T> blocking(block: suspend () -> T): T = runCatching { runBlocking(Dispatchers.IO) { block() } }
        .getOrElse { e ->
            Log.w(TAG, "Provider call failed: ${e::class.simpleName}")
            throw e as? FileNotFoundException ?: FileNotFoundException(e.message)
        }

    companion object {
        const val ROOT_ID = "drive"
        private const val TAG = "Neutrino"
        private const val DEFAULT_MIME = "application/octet-stream"
        val authority: String get() = "${BuildConfig.APPLICATION_ID}.documents"
        private val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY, Root.COLUMN_FLAGS, Root.COLUMN_ICON,
        )
        private val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )
    }
}
