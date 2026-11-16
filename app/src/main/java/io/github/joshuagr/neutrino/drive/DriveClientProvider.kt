package io.github.joshuagr.neutrino.drive

import android.content.Context
import android.util.Log
import io.github.joshuagr.neutrino.api.ProtonApi
import io.github.joshuagr.neutrino.auth.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.proton.drive.sdk.ClientHost
import me.proton.drive.sdk.LoggerProvider
import me.proton.drive.sdk.ProtonDriveClient
import me.proton.drive.sdk.ProtonDriveSdk
import me.proton.drive.sdk.entity.ClientCreateRequest
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Creates the Drive SDK client once per signed-in session and tears it down on sign-out. */
class DriveClientProvider(
    private val context: Context,
    private val sessions: SessionManager,
    private val keys: AccountKeys,
    private val transport: SdkTransport,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var client: ProtonDriveClient? = null

    suspend fun get(): ProtonDriveClient = lock.withLock {
        client?.let { return it }
        Log.i(TAG, "Drive client: loading account keys")
        keys.load()
        Log.i(TAG, "Drive client: creating")
        val session = sessions.current() ?: throw SessionManager.NotSignedInException()
        val cache = cacheFile(session.cacheKey)
        deleteCachesExcept(cache)
        val logger = ProtonDriveSdk.loggerProvider { level, category, message ->
            if (level >= LoggerProvider.Level.WARN) Log.w(TAG, "[$category] $message")
        }
        ProtonDriveSdk.protonDriveClientCreate(
            scope,
            ClientHost(checkNotNull(keys.userId), transport, keys, keys),
            ClientCreateRequest(
                baseUrl = "${ProtonApi.API_BASE}/",
                loggerProvider = logger,
                cachePath = cache.path,
                uid = clientUid(),
                cacheEncryptionKey = session.cacheKey,
            ),
        ).also { client = it }
    }

    /** Drops the client and the unlocked keys; the session's cache stays for the next client. */
    suspend fun close() = lock.withLock {
        client?.close()
        client = null
        keys.forget()
    }

    /** Sign-out: close, then delete every cache file. The next session uses a new file name. */
    suspend fun wipe() {
        close()
        deleteCachesExcept(null)
    }

    /**
     * One cache file per session. The SDK keeps SQLite connections for the life of the process, so a
     * file deleted and recreated under the same name reopens without its schema ("no such table").
     */
    private fun cacheFile(cacheKey: ByteArray): File {
        val tag = MessageDigest.getInstance("SHA-256").digest(cacheKey).take(TAG_BYTES).joinToString("") { "%02x".format(it) }
        return File(context.noBackupFilesDir, "$CACHE_PREFIX$tag.db")
    }

    private fun deleteCachesExcept(keep: File?) {
        context.noBackupFilesDir.listFiles { file -> file.name.startsWith(CACHE_PREFIX) }
            ?.filterNot { keep != null && it.name.startsWith(keep.name) }
            ?.forEach { it.delete() }
    }

    /** A stable id for this installation, sent with revisions so Proton can tell clients apart. */
    private fun clientUid(): String {
        val file = File(context.noBackupFilesDir, "client-uid")
        if (!file.exists()) file.writeText("neutrino-${UUID.randomUUID()}")
        return file.readText()
    }

    private companion object {
        const val TAG = "Neutrino"
        const val CACHE_PREFIX = "drive-cache"
        const val TAG_BYTES = 8
    }
}
