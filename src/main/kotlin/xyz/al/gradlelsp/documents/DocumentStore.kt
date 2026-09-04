package xyz.al.gradlelsp.documents

import java.net.URI
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal class DocumentStore {
    private val snapshots = ConcurrentHashMap<String, DocumentSnapshot>()
    private val revision = AtomicLong()

    fun open(uri: String, version: Int, text: String): DocumentSnapshot {
        val snapshot = DocumentSnapshot(uri, fileName(uri), version, text)
        snapshots[uri] = snapshot
        revision.incrementAndGet()
        return snapshot
    }

    fun replace(uri: String, version: Int, text: String): DocumentSnapshot? {
        var accepted: DocumentSnapshot? = null
        snapshots.computeIfPresent(uri) { _, current ->
            if (version <= current.version) {
                current
            } else {
                DocumentSnapshot(uri, current.fileName, version, text).also {
                    accepted = it
                    revision.incrementAndGet()
                }
            }
        }
        return accepted
    }

    fun close(uri: String): DocumentSnapshot? = snapshots.remove(uri)?.also {
        revision.incrementAndGet()
    }

    fun current(uri: String): DocumentSnapshot? = snapshots[uri]

    fun currentSnapshots(): List<DocumentSnapshot> = snapshots.values.toList()

    fun revision(): Long = revision.get()

    fun isCurrent(snapshot: DocumentSnapshot): Boolean = snapshots[snapshot.uri] === snapshot

    private fun fileName(uri: String): String =
        runCatching { Path.of(URI.create(uri)).fileName?.toString() }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: "build.gradle.kts"
}
