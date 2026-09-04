package xyz.al.gradlelsp.documents

/** Immutable document state whose identity distinguishes close/reopen generations. */
internal class DocumentSnapshot(
    val uri: String,
    val fileName: String,
    val version: Int,
    val text: String,
)
