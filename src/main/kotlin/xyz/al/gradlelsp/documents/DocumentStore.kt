package xyz.al.gradlelsp.documents

import java.net.URI
import java.nio.file.Path

internal data class DocumentStoreCapture(
    val snapshot: DocumentSnapshot?,
    val revision: Long,
)

internal class DocumentStore {
    private val snapshots = mutableMapOf<String, DocumentSnapshot>()
    private var workspaceRevision = 0L

    @Synchronized
    fun open(uri: String, version: Int, text: String): DocumentSnapshot {
        val snapshot = DocumentSnapshot(uri, fileName(uri), version, text)
        snapshots[uri] = snapshot
        workspaceRevision += 1
        return snapshot
    }

    @Synchronized
    fun replace(uri: String, version: Int, text: String): DocumentSnapshot? {
        val current = snapshots[uri] ?: return null
        if (version <= current.version) return null

        return DocumentSnapshot(uri, current.fileName, version, text).also { replacement ->
            snapshots[uri] = replacement
            workspaceRevision += 1
        }
    }

    @Synchronized
    fun close(uri: String): DocumentSnapshot? = snapshots.remove(uri)?.also {
        workspaceRevision += 1
    }

    @Synchronized
    fun current(uri: String): DocumentSnapshot? = snapshots[uri]

    @Synchronized
    fun capture(uri: String): DocumentStoreCapture =
        DocumentStoreCapture(snapshots[uri], workspaceRevision)

    @Synchronized
    fun currentSnapshots(): List<DocumentSnapshot> = snapshots.values.toList()

    @Synchronized
    fun revision(): Long = workspaceRevision

    @Synchronized
    fun isCurrent(snapshot: DocumentSnapshot): Boolean = snapshots[snapshot.uri] === snapshot

    private fun fileName(uri: String): String =
        runCatching { Path.of(URI.create(uri)).fileName?.toString() }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: "build.gradle.kts"
}
