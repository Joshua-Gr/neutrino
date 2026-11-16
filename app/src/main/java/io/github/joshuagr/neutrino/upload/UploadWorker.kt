package io.github.joshuagr.neutrino.upload

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.joshuagr.neutrino.drive.DriveClientProvider
import io.github.joshuagr.neutrino.provider.DriveDocuments
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import me.proton.drive.sdk.ProtonDriveClient
import me.proton.drive.sdk.entity.FileNode
import me.proton.drive.sdk.entity.FileRevisionUploaderRequest
import me.proton.drive.sdk.entity.FileUploaderRequest
import me.proton.drive.sdk.entity.NodeUid
import me.proton.drive.sdk.entity.RevisionUid
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Uploads one document's pending save as a new revision. A save made against a revision that is no
 * longer active in Drive is uploaded next to it as a conflict copy instead, so no version is lost.
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val pendingStore: PendingStore by inject()
    private val drive: DriveClientProvider by inject()
    private val docs: DriveDocuments by inject()
    private val notifier: UploadNotifier by inject()

    override suspend fun doWork(): Result = coroutineScope {
        val documentId = inputData.getString(KEY_DOCUMENT) ?: return@coroutineScope Result.failure()
        val pending = pendingStore.get(documentId) ?: return@coroutineScope Result.success()
        val name = runCatching { docs.get(documentId).name }.getOrDefault("file")
        runCatching { upload(this, drive.get(), pending, name) }
            .fold(
                onSuccess = { Result.success() },
                onFailure = { error ->
                    Log.w(TAG, "Upload attempt ${runAttemptCount + 1} failed: ${error::class.simpleName}")
                    if (runAttemptCount + 1 >= NOTIFY_AFTER_ATTEMPTS) notifier.failed(documentId, name, reasonFor(error))
                    Result.retry()
                },
            )
    }

    private suspend fun upload(scope: CoroutineScope, client: ProtonDriveClient, pending: Pending, name: String) {
        val node = client.getNode(NodeUid(pending.documentId)) as? FileNode
            ?: error("The file no longer exists in Drive")
        val active = node.activeRevision
        val size = pending.content.length()
        when {
            active.claimedDigests?.sha1?.contentEquals(sha1(pending.content)) == true -> {
                Log.i(TAG, "Upload skipped: content already in Drive")
                pendingStore.complete(pending, active.uid.value)
            }
            active.uid.value != pending.baseRevision -> uploadConflictCopy(scope, client, node, pending, name)
            else -> {
                val request = FileRevisionUploaderRequest(RevisionUid(pending.baseRevision), OffsetDateTime.now(), size)
                val result = client.uploader(request)
                    .use { it.uploadFromStream(scope, FileInputStream(pending.content).channel).awaitCompletion() }
                pendingStore.complete(pending, result.revisionUid.value)
                Log.i(TAG, "Uploaded a new revision ($size bytes)")
            }
        }
        docs.invalidate(pending.documentId)
        notifier.clear(pending.documentId)
    }

    private suspend fun uploadConflictCopy(
        scope: CoroutineScope,
        client: ProtonDriveClient,
        node: FileNode,
        pending: Pending,
        name: String,
    ) {
        val parent = checkNotNull(node.parentUid) { "The file has no parent folder" }
        val stamp = OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm"))
        val dot = name.lastIndexOf('.').takeIf { it > 0 }
        val wanted = if (dot == null) {
            "$name (conflict $stamp)"
        } else {
            "${name.substring(0, dot)} (conflict $stamp)${name.substring(dot)}"
        }
        val copyName = client.getAvailableName(parent, wanted, me.proton.drive.sdk.entity.AvailableNameTarget.FILE)
        val request = FileUploaderRequest(parent, copyName, node.mediaType, pending.content.length(), OffsetDateTime.now(), false)
        client.uploader(request).use { it.uploadFromStream(scope, FileInputStream(pending.content).channel).awaitCompletion() }
        pendingStore.complete(pending, node.activeRevision.uid.value)
        Log.i(TAG, "Uploaded a conflict copy")
        notifier.conflict(pending.documentId, name, copyName)
    }

    private fun reasonFor(error: Throwable): String = when (error) {
        is java.io.IOException -> "no connection to Drive"
        else -> error.message?.lineSequence()?.firstOrNull()?.take(REASON_CHARS) ?: "unknown error"
    }

    private fun sha1(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-1")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }

    companion object {
        private const val TAG = "Neutrino"
        private const val KEY_DOCUMENT = "document"
        private const val NOTIFY_AFTER_ATTEMPTS = 3
        private const val REASON_CHARS = 120
        private const val BUFFER_BYTES = 64 * 1024
        private const val BACKOFF_SECONDS = 15L

        /** Queues the upload for [documentId]; a newer save replaces a queued one, never a running one. */
        fun enqueue(context: Context, documentId: String) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(workDataOf(KEY_DOCUMENT to documentId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("upload-$documentId", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
