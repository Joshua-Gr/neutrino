package io.github.joshuagr.neutrino.provider

import io.github.joshuagr.neutrino.drive.DriveClientProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.toList
import me.proton.drive.sdk.entity.AvailableNameTarget
import me.proton.drive.sdk.entity.FileDownloaderRequest
import me.proton.drive.sdk.entity.FileNode
import me.proton.drive.sdk.entity.FileUploaderRequest
import me.proton.drive.sdk.entity.FolderNode
import me.proton.drive.sdk.entity.Node
import me.proton.drive.sdk.entity.NodeUid
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.Channels
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap

/** What the provider needs to describe one Drive node without going back to the network. */
data class DocMeta(
    val id: String,
    val parentId: String?,
    val name: String,
    val mimeType: String?,
    val size: Long?,
    val lastModified: Long?,
    val isFolder: Boolean,
    val activeRevision: String?,
)

/**
 * Drive as the provider sees it. Metadata is kept in memory because clients query it in bursts
 * (KeePassDX issues 10-15 queryDocument calls per action), so only misses reach the network.
 */
class DriveDocuments(private val drive: DriveClientProvider) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val meta = ConcurrentHashMap<String, DocMeta>()
    @Volatile private var rootId: String? = null

    suspend fun rootId(): String = rootId ?: remember(drive.get().getMyFilesFolder()).id.also { rootId = it }

    suspend fun get(id: String): DocMeta = meta[id] ?: run {
        val node = drive.get().getNode(NodeUid(id)) ?: throw java.io.FileNotFoundException("No such document")
        remember(node)
    }

    suspend fun children(folderId: String): List<DocMeta> {
        val client = drive.get()
        val uids = client.enumerateFolderChildrenNodeUids(NodeUid(folderId)).toList()
        if (uids.isEmpty()) return emptyList()
        return client.enumerateNodes(uids).toList().filter { it.trashTime == null }.map(::remember)
    }

    /** Whether [id] sits anywhere below [ancestorId], following parents through the cache. */
    suspend fun isDescendant(ancestorId: String, id: String): Boolean {
        var current: String? = id
        repeat(MAX_DEPTH) {
            val parent = current?.let { get(it).parentId } ?: return false
            if (parent == ancestorId) return true
            current = parent
        }
        return false
    }

    /** Downloads the active revision of [id] to [dest] and returns that revision. */
    suspend fun download(id: String, dest: File): String {
        val revision = (drive.get().getNode(NodeUid(id)) as? FileNode)?.also(::remember)?.activeRevision
            ?: throw java.io.FileNotFoundException("Not a file")
        FileOutputStream(dest).use { out ->
            drive.get().downloader(FileDownloaderRequest(revision.uid)).use { downloader ->
                downloader.downloadToStream(scope, Channels.newChannel(out)).awaitCompletion()
            }
        }
        return revision.uid.value
    }

    /** Drops cached metadata for [id] so the next query reads the current revision from Drive. */
    fun invalidate(id: String) {
        meta.remove(id)
    }

    /** The revision a save will be based on: the file's active revision, read fresh from Drive. */
    suspend fun activeRevision(id: String): String {
        val node = drive.get().getNode(NodeUid(id)) as? FileNode ?: throw java.io.FileNotFoundException("Not a file")
        return remember(node).activeRevision ?: throw java.io.FileNotFoundException("No active revision")
    }

    /** Creates an empty file so the new document has a stable Drive id from the start. */
    suspend fun createFile(parentId: String, displayName: String, mimeType: String): DocMeta {
        val client = drive.get()
        val parent = NodeUid(parentId)
        val name = client.getAvailableName(parent, displayName, AvailableNameTarget.FILE)
        val request = FileUploaderRequest(parent, name, mimeType, 0, OffsetDateTime.now(), false)
        val result = client.uploader(request).use {
            it.uploadFromStream(scope, Channels.newChannel(ByteArrayInputStream(ByteArray(0)))).awaitCompletion()
        }
        meta.remove(result.nodeUid.value)
        return get(result.nodeUid.value)
    }

    fun forget() {
        meta.clear()
        rootId = null
    }

    private fun remember(node: Node): DocMeta {
        val file = node as? FileNode
        val doc = DocMeta(
            id = node.uid.value,
            parentId = node.parentUid?.value,
            name = node.name.getOrNull() ?: UNREADABLE_NAME,
            mimeType = file?.mediaType,
            size = file?.activeRevision?.let { it.claimedSize ?: it.storageSize },
            lastModified = file?.activeRevision?.let { (it.claimedModificationTime?.toInstant() ?: it.creationTime).toEpochMilli() }
                ?: node.creationTime.toEpochMilli(),
            isFolder = node is FolderNode,
            activeRevision = file?.activeRevision?.uid?.value,
        )
        meta[doc.id] = doc
        return doc
    }

    private companion object {
        const val MAX_DEPTH = 64
        const val UNREADABLE_NAME = "(name could not be decrypted)"
    }
}
