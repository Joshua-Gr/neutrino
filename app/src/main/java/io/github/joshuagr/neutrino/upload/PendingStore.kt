package io.github.joshuagr.neutrino.upload

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** A local save that has not reached Drive yet. [baseRevision] is the revision its content was derived from. */
data class Pending(
    val documentId: String,
    val baseRevision: String,
    val generation: Long,
    val savedAt: Long,
    val content: File,
)

/**
 * The durable half of the write path: every save lands here first and survives process death
 * until an upload confirms it. One slot per document; a newer save replaces an older one, which is
 * correct because each save is a whole new version of the file.
 */
class PendingStore(context: Context) {
    private val root = File(context.noBackupFilesDir, "pending").apply { mkdirs() }

    @Synchronized
    fun get(documentId: String): Pending? {
        val dir = dirFor(documentId)
        val meta = File(dir, META).takeIf { it.exists() } ?: return null
        val json = runCatching { JSONObject(meta.readText()) }.getOrNull() ?: return null
        return Pending(
            documentId = json.getString("documentId"),
            baseRevision = json.getString("baseRevision"),
            generation = json.getLong("generation"),
            savedAt = json.getLong("savedAt"),
            content = File(dir, CONTENT),
        )
    }

    @Synchronized
    fun all(): List<Pending> = root.listFiles { file -> file.isDirectory }?.mapNotNull { dir ->
        runCatching { JSONObject(File(dir, META).readText()).getString("documentId") }.getOrNull()?.let(::get)
    }.orEmpty()

    /** Moves [working] into the slot for [documentId]. Atomic per file: a crash leaves the old or the new save. */
    @Synchronized
    fun commit(documentId: String, working: File, baseRevision: String): Pending {
        val dir = dirFor(documentId).apply { mkdirs() }
        val generation = (get(documentId)?.generation ?: 0) + 1
        check(working.renameTo(File(dir, CONTENT))) { "Could not stage the save" }
        writeMeta(dir, documentId, baseRevision, generation, System.currentTimeMillis())
        // The writer holds exactly this content now, so its next save derives from this one.
        writeServed(documentId, "$PENDING_MARK$generation")
        return requireNotNull(get(documentId))
    }

    /**
     * Called after [uploaded] reached Drive as [newRevision]. If no newer save arrived, the slot is
     * cleared. If one did and it was derived from the same base, it was derived from the uploaded
     * content, so it is rebased onto the new revision instead of being mistaken for a conflict.
     */
    @Synchronized
    fun complete(uploaded: Pending, newRevision: String) {
        val served = readServed()[uploaded.documentId]
        if (served == "$PENDING_MARK${uploaded.generation}") writeServed(uploaded.documentId, newRevision)
        val current = get(uploaded.documentId) ?: return
        if (current.generation == uploaded.generation) {
            dirFor(uploaded.documentId).deleteRecursively()
        } else if (current.baseRevision == uploaded.baseRevision) {
            writeMeta(dirFor(uploaded.documentId), current.documentId, newRevision, current.generation, current.savedAt)
        }
    }

    /** Records that [documentId] was last read at Drive [revision]. A later save is based on it. */
    @Synchronized
    fun markServed(documentId: String, revision: String) = writeServed(documentId, revision)

    /** Records that [documentId] was last read from its pending save, which derives from that save's base. */
    @Synchronized
    fun markServedPending(documentId: String, pending: Pending) = writeServed(documentId, "$PENDING_MARK${pending.generation}")

    /**
     * The revision a new save of [documentId] derives from: the pending save's base if one exists,
     * else the revision the client last read. Null when this app never served the file.
     */
    @Synchronized
    fun baseFor(documentId: String): String? {
        get(documentId)?.let { return it.baseRevision }
        return readServed()[documentId]?.takeUnless { it.startsWith(PENDING_MARK) }
    }

    fun newWorkingFile(): File = File.createTempFile("save", ".tmp", File(root, "work").apply { mkdirs() })

    @Synchronized
    fun clearAll() {
        root.deleteRecursively()
        root.mkdirs()
    }

    private fun readServed(): Map<String, String> {
        val json = runCatching { JSONObject(servedFile.readText()) }.getOrNull() ?: return emptyMap()
        return json.keys().asSequence().associateWith(json::getString)
    }

    private fun writeServed(documentId: String, value: String) {
        val json = JSONObject(readServed()).put(documentId, value)
        val tmp = File(root, "$SERVED.tmp")
        tmp.writeText(json.toString())
        check(tmp.renameTo(servedFile)) { "Could not record the served revision" }
    }

    private val servedFile get() = File(root, SERVED)

    private fun writeMeta(dir: File, documentId: String, baseRevision: String, generation: Long, savedAt: Long) {
        val tmp = File(dir, "$META.tmp")
        tmp.writeText(
            JSONObject()
                .put("documentId", documentId)
                .put("baseRevision", baseRevision)
                .put("generation", generation)
                .put("savedAt", savedAt)
                .toString(),
        )
        check(tmp.renameTo(File(dir, META))) { "Could not record the save" }
    }

    private fun dirFor(documentId: String): File {
        val name = MessageDigest.getInstance("SHA-256").digest(documentId.toByteArray())
            .take(NAME_BYTES).joinToString("") { "%02x".format(it) }
        return File(root, name)
    }

    private companion object {
        const val META = "meta.json"
        const val CONTENT = "content"
        const val SERVED = "served.json"
        const val PENDING_MARK = "pending:"
        const val NAME_BYTES = 12
    }
}
