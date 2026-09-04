package xyz.al.gradlelsp.documents

import java.net.URI
import java.security.MessageDigest
import java.util.LinkedHashMap

internal data class ExternalDocument(
    val uri: String,
    val languageId: String,
    val text: String,
)

/** In-memory backing store for read-only source and decompiler documents. */
internal class ExternalDocumentStore(
    private val maximumEntries: Int = 128,
    private val maximumEstimatedTextBytes: Long = 32L * 1024L * 1024L,
) {
    private val documents = LinkedHashMap<String, ExternalDocument>(16, 0.75f, true)
    private var estimatedTextBytes = 0L

    init {
        require(maximumEntries > 0) { "maximumEntries must be positive" }
        require(maximumEstimatedTextBytes > 0) { "maximumEstimatedTextBytes must be positive" }
    }

    @Synchronized
    fun register(
        origin: String,
        displayName: String,
        languageId: String,
        text: String,
    ): ExternalDocument {
        val digest = sha256(origin, text)
        val safeName = displayName.substringAfterLast('/').substringAfterLast('\\')
            .ifBlank { "external-source" }
        val uri = URI("gradle-lsp", "source", "/$digest/$safeName", null).toASCIIString()
        documents[uri]?.let { existing -> return existing }

        val document = ExternalDocument(uri, languageId, text)
        documents[uri] = document
        estimatedTextBytes += estimatedBytes(text)
        evictIfNeeded()
        return document
    }

    @Synchronized
    fun find(uri: String): ExternalDocument? = documents[uri]

    private fun evictIfNeeded() {
        val entries = documents.entries.iterator()
        while (
            entries.hasNext() &&
            (documents.size > maximumEntries || estimatedTextBytes > maximumEstimatedTextBytes) &&
            documents.size > 1
        ) {
            val evicted = entries.next().value
            entries.remove()
            estimatedTextBytes -= estimatedBytes(evicted.text)
        }
    }

    private fun estimatedBytes(text: String): Long = text.length.toLong() * UTF16_BYTES_PER_CODE_UNIT

    private fun sha256(origin: String, text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(origin.toByteArray(Charsets.UTF_8))
        digest.update(0)
        return digest.digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val UTF16_BYTES_PER_CODE_UNIT = 2L
    }
}
