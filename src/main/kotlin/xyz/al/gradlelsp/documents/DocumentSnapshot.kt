package xyz.al.gradlelsp.documents

internal data class DocumentSnapshot(
    val uri: String,
    val fileName: String,
    val version: Int,
    val text: String,
)
