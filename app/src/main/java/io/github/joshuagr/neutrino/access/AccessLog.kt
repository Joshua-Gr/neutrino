package io.github.joshuagr.neutrino.access

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One app's use of one file served by Neutrino. Times are epoch milliseconds; null means never. */
data class AccessEntry(
    val documentId: String,
    val fileName: String,
    val packageName: String,
    val lastOpenedAt: Long,
    val lastSavedAt: Long? = null,
    val lastUploadedAt: Long? = null,
) {
    val lastActivityAt: Long get() = maxOf(lastOpenedAt, lastSavedAt ?: 0, lastUploadedAt ?: 0)
}

/**
 * Which apps opened which files through the provider. Kept on disk so the list survives restarts,
 * and cleared on sign-out. Opening through the system picker itself is not recorded.
 */
class AccessLog(context: Context) {
    private val file = File(context.noBackupFilesDir, "access.json")
    private val state = MutableStateFlow(load())
    val entries: StateFlow<List<AccessEntry>> = state.asStateFlow()

    fun opened(documentId: String, fileName: String, packageName: String?) = update(documentId, fileName, packageName) {
        it.copy(fileName = fileName, lastOpenedAt = System.currentTimeMillis())
    }

    fun saved(documentId: String, fileName: String, packageName: String?) = update(documentId, fileName, packageName) {
        it.copy(fileName = fileName, lastSavedAt = System.currentTimeMillis())
    }

    /** An upload reached Drive: every app that uses the file sees it as uploaded. */
    @Synchronized
    fun uploaded(documentId: String) {
        val now = System.currentTimeMillis()
        commit(state.value.map { if (it.documentId == documentId) it.copy(lastUploadedAt = now) else it })
    }

    @Synchronized
    fun clear() {
        file.delete()
        state.value = emptyList()
    }

    @Synchronized
    private fun update(documentId: String, fileName: String, packageName: String?, change: (AccessEntry) -> AccessEntry) {
        if (packageName == null || packageName in IGNORED_CALLERS) return
        val existing = state.value.firstOrNull { it.documentId == documentId && it.packageName == packageName }
            ?: AccessEntry(documentId, fileName, packageName, lastOpenedAt = 0)
        commit(state.value.filterNot { it === existing } + change(existing))
    }

    private fun commit(entries: List<AccessEntry>) {
        val sorted = entries.sortedByDescending { it.lastActivityAt }
        val json = JSONArray(sorted.map(::toJson))
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.toString())
        check(tmp.renameTo(file)) { "Could not record file access" }
        state.value = sorted
    }

    private fun load(): List<AccessEntry> = runCatching {
        val json = JSONArray(file.readText())
        (0 until json.length()).map { fromJson(json.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun toJson(entry: AccessEntry) = JSONObject()
        .put("documentId", entry.documentId)
        .put("fileName", entry.fileName)
        .put("packageName", entry.packageName)
        .put("lastOpenedAt", entry.lastOpenedAt)
        .put("lastSavedAt", entry.lastSavedAt ?: JSONObject.NULL)
        .put("lastUploadedAt", entry.lastUploadedAt ?: JSONObject.NULL)

    private fun fromJson(json: JSONObject) = AccessEntry(
        documentId = json.getString("documentId"),
        fileName = json.getString("fileName"),
        packageName = json.getString("packageName"),
        lastOpenedAt = json.getLong("lastOpenedAt"),
        lastSavedAt = json.optLong("lastSavedAt").takeUnless { json.isNull("lastSavedAt") },
        lastUploadedAt = json.optLong("lastUploadedAt").takeUnless { json.isNull("lastUploadedAt") },
    )

    private companion object {
        /** The system picker browses on behalf of the app that asked; it is not a user of the file. */
        val IGNORED_CALLERS = setOf("com.google.android.documentsui", "com.android.documentsui")
    }
}
