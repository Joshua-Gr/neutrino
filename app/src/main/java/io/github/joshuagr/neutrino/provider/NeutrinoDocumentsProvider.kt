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
import io.github.joshuagr.neutrino.access.AccessLog
import io.github.joshuagr.neutrino.auth.SessionManager
import io.github.joshuagr.neutrino.drive.AccountKeys
import io.github.joshuagr.neutrino.upload.PendingStore
import io.github.joshuagr.neutrino.upload.UploadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import io.github.joshuagr.neutrino.Dependencies
import java.io.File
import java.io.FileNotFoundException

/**
 * Exposes the signed-in account's "My files" through the system file picker.
 *
 * Reads serve the newest version: a save still waiting to upload, or else the active revision,
 * downloaded to a private temporary file deleted on close. Writes go to a private working file;
 * on close it becomes the pending save for that document and an upload is queued. The client's
 * close returns at once; the upload runs in the background and survives process death.
 *
 * Providers are created before Application.onCreate, so dependencies are started on first use.
 */
class NeutrinoDocumentsProvider : DocumentsProvider() {
    private val koin by lazy { Dependencies.start(requireNotNull(context)) }
    private val sessions by lazy { koin.get<SessionManager>() }
    private val keys by lazy { koin.get<AccountKeys>() }
    private val docs by lazy { koin.get<DriveDocuments>() }
    private val pending by lazy { koin.get<PendingStore>() }
    private val access by lazy { koin.get<AccessLog>() }
    private val offline by lazy { koin.get<OfflineCache>() }
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
            .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_SUPPORTS_CREATE)
            .add(Root.COLUMN_ICON, R.drawable.ic_picker_root)
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

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        if (mimeType == Document.MIME_TYPE_DIR) throw UnsupportedOperationException("Creating folders is not supported")
        return blocking { docs.createFile(parentDocumentId, displayName, mimeType) }.id
            .also { log("create by ${callingPackage ?: "?"}") }
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val caller = callingPackage
        val descriptor = if (mode == "r") openForRead(documentId) else openForWrite(documentId, mode, caller)
        runCatching {
            val doc = blocking { docs.get(documentId) }
            access.opened(documentId, doc.name, caller, doc.lastModified)
        }
        return descriptor
    }

    private fun openForRead(documentId: String): ParcelFileDescriptor {
        pending.get(documentId)?.let { local ->
            pending.markServedPending(documentId, local)
            log("open r by ${callingPackage ?: "?"}: pending save, ${local.content.length()} bytes")
            return ParcelFileDescriptor.open(local.content, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        val tmp = File.createTempFile("open", ".bin", openDir)
        val revision = try {
            val fetched = blocking { docs.download(documentId, tmp) }
            if (fetched.fromOfflineCopy) {
                log("open r by ${callingPackage ?: "?"}: offline copy is current")
            } else {
                runCatching { offline.put(blocking { docs.get(documentId) }, fetched.revision, tmp) }
            }
            fetched.revision
        } catch (e: MissingDocumentException) {
            tmp.delete()
            throw e
        } catch (e: FileNotFoundException) {
            // Drive unreachable: serve the last version read here, if there is one.
            val cached = offline.decryptTo(documentId, tmp)
            if (cached == null) {
                tmp.delete()
                throw e
            }
            log("open r by ${callingPackage ?: "?"}: offline copy")
            cached
        }
        pending.markServed(documentId, revision)
        log("open r by ${callingPackage ?: "?"}: ${tmp.length()} bytes")
        return ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY, closeHandler) { tmp.delete() }
    }

    private fun openForWrite(documentId: String, mode: String, caller: String?): ParcelFileDescriptor {
        val local = pending.get(documentId)
        // The base is what the client last read, not what Drive holds now: a change made elsewhere
        // since that read must surface as a conflict, never be overwritten.
        val base = pending.baseFor(documentId)
            ?: runCatching { blocking { docs.activeRevision(documentId) } }.getOrElse { e ->
                if (e is MissingDocumentException) throw e
                offline.revision(documentId) ?: throw e
            }
        val working = pending.newWorkingFile()
        if (!mode.contains('t') && mode != "w") {
            // A read-write open without truncation edits the current content in place.
            if (local != null) {
                local.content.copyTo(working, overwrite = true)
            } else {
                pending.markServed(documentId, blocking { docs.download(documentId, working) }.revision)
            }
        }
        log("open $mode by ${callingPackage ?: "?"}")
        val context = requireNotNull(context)
        return ParcelFileDescriptor.open(working, ParcelFileDescriptor.parseMode(mode), closeHandler) { error ->
            if (error != null) {
                Log.w(TAG, "Write closed with an error; the save is discarded: ${error::class.simpleName}")
                working.delete()
                return@open
            }
            pending.commit(documentId, working, base)
            runCatching { access.saved(documentId, blocking { docs.get(documentId) }.name, caller) }
            docs.invalidate(documentId)
            UploadWorker.enqueue(context, documentId)
            log("save staged: ${pending.get(documentId)?.content?.length()} bytes, upload queued")
        }
    }

    private fun addRow(cursor: MatrixCursor, doc: DocMeta) {
        val local = if (doc.isFolder) null else pending.get(doc.id)
        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, doc.id)
            .add(Document.COLUMN_DISPLAY_NAME, doc.name)
            .add(Document.COLUMN_MIME_TYPE, if (doc.isFolder) Document.MIME_TYPE_DIR else doc.mimeType ?: DEFAULT_MIME)
            .add(Document.COLUMN_SIZE, local?.content?.length() ?: doc.size)
            .add(Document.COLUMN_LAST_MODIFIED, local?.savedAt ?: doc.lastModified)
            .add(Document.COLUMN_FLAGS, if (doc.isFolder) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE)
    }

    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.i(TAG, message)
    }

    /**
     * Provider calls are synchronous binder calls; the Drive work behind them is suspending. Failures
     * surface as FileNotFoundException, the one exception the framework reports back to the client.
     */
    private fun <T> blocking(block: suspend () -> T): T = runCatching { runBlocking(Dispatchers.IO) { block() } }
        .getOrElse { e ->
            Log.w(TAG, "Provider call failed: ${e::class.simpleName}")
            throw e as? FileNotFoundException ?: FileNotFoundException(e.message).apply { initCause(e) }
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
