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
) {
    private val documents = object : LinkedHashMap<String, ExternalDocument>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, ExternalDocument>,
        ): Boolean = size > maximumEntries
    }

    init {
        require(maximumEntries > 0) { "maximumEntries must be positive" }
    }

    @Synchronized
    fun register(
        origin: String,
        displayName: String,
        languageId: String,
        text: String,
    ): ExternalDocument {
        val digest = sha256("$origin\u0000$text")
        val safeName = displayName.substringAfterLast('/').substringAfterLast('\\')
            .ifBlank { "external-source" }
        val uri = URI("gradle-lsp", "source", "/$digest/$safeName", null).toASCIIString()
        return documents.getOrPut(uri) { ExternalDocument(uri, languageId, text) }
    }

    @Synchronized
    fun find(uri: String): ExternalDocument? = documents[uri]

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
}
