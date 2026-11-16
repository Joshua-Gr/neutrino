package io.github.joshuagr.neutrino.provider

import android.content.Context
import io.github.joshuagr.neutrino.auth.KeystoreCipher
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * The last version of each file read through Neutrino, so it can still be opened without a
 * network. Content is encrypted with a Keystore key; a decrypted copy exists only while a client
 * holds the file open. Large files are not kept.
 */
class OfflineCache(context: Context) {
    private val root = File(context.noBackupFilesDir, "offline").apply { mkdirs() }
    private val cipher = KeystoreCipher("neutrino-offline")

    /** Keeps [plain] as the offline copy of [doc] at [revision]. */
    @Synchronized
    fun put(doc: DocMeta, revision: String, plain: File) {
        if (doc.isFolder || plain.length() > MAX_BYTES) return
        val dir = dirFor(doc.id).apply { mkdirs() }
        val content = File(dir, "$CONTENT.tmp")
        content.writeBytes(cipher.encrypt(plain.readBytes()))
        check(content.renameTo(File(dir, CONTENT))) { "Could not store the offline copy" }
        val meta = File(dir, "$META.tmp")
        meta.writeText(toJson(doc, revision).toString())
        check(meta.renameTo(File(dir, META))) { "Could not store the offline copy" }
    }

    /** Metadata of the offline copy, or null when there is none. */
    @Synchronized
    fun meta(id: String): DocMeta? = readMeta(id)?.let(::fromJson)

    /** The Drive revision the offline copy holds. */
    @Synchronized
    fun revision(id: String): String? = readMeta(id)?.optString("revision")?.takeIf { it.isNotEmpty() }

    /** Decrypts the offline copy of [id] into [dest] and returns its revision, or null when there is none. */
    @Synchronized
    fun decryptTo(id: String, dest: File): String? {
        val revision = revision(id) ?: return null
        val content = File(dirFor(id), CONTENT).takeIf { it.exists() } ?: return null
        dest.writeBytes(cipher.decrypt(content.readBytes()))
        return revision
    }

    @Synchronized
    fun clear() {
        root.deleteRecursively()
        root.mkdirs()
        cipher.deleteKey()
    }

    private fun readMeta(id: String): JSONObject? =
        runCatching { JSONObject(File(dirFor(id), META).readText()) }.getOrNull()

    private fun toJson(doc: DocMeta, revision: String) = JSONObject()
        .put("id", doc.id)
        .put("parentId", doc.parentId ?: JSONObject.NULL)
        .put("name", doc.name)
        .put("mimeType", doc.mimeType ?: JSONObject.NULL)
        .put("size", doc.size ?: JSONObject.NULL)
        .put("lastModified", doc.lastModified ?: JSONObject.NULL)
        .put("revision", revision)

    private fun fromJson(json: JSONObject) = DocMeta(
        id = json.getString("id"),
        parentId = json.optString("parentId").takeUnless { json.isNull("parentId") },
        name = json.getString("name"),
        mimeType = json.optString("mimeType").takeUnless { json.isNull("mimeType") },
        size = json.optLong("size").takeUnless { json.isNull("size") },
        lastModified = json.optLong("lastModified").takeUnless { json.isNull("lastModified") },
        isFolder = false,
        activeRevision = json.getString("revision"),
    )

    private fun dirFor(id: String): File {
        val name = MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
            .take(NAME_BYTES).joinToString("") { "%02x".format(it) }
        return File(root, name)
    }

    private companion object {
        const val META = "meta.json"
        const val CONTENT = "content.enc"
        const val NAME_BYTES = 12
        const val MAX_BYTES = 25L * 1024 * 1024
    }
}
