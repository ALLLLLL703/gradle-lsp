package xyz.al.gradlelsp.documents

import xyz.al.gradlelsp.analysis.AnalysisDocument
import xyz.al.gradlelsp.gradle.GradleProjectRootLocator
import java.io.IOException
import java.net.URI
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

internal fun interface WorkspaceDocumentSource {
    fun forEachDocument(origin: AnalysisDocument, consume: (AnalysisDocument) -> Unit)
}

/** Streams Gradle Kotlin DSL documents without retaining a workspace-wide text or PSI index. */
internal class GradleWorkspaceDocumentSource(
    private val openDocuments: DocumentStore,
) : WorkspaceDocumentSource {
    @Volatile
    private var configuredRoots: List<Path> = emptyList()

    fun configureRoots(rootUris: List<String>) {
        configuredRoots = collapseNestedRoots(
            rootUris.mapNotNull(::filePath)
                .map { path ->
                    val normalized = path.toAbsolutePath().normalize()
                    if (Files.isDirectory(normalized)) normalized else normalized.parent
                }
                .filterNotNull()
                .distinct(),
        )
    }

    override fun forEachDocument(origin: AnalysisDocument, consume: (AnalysisDocument) -> Unit) {
        val originPath = filePath(origin.uri) ?: return
        val configured = configuredRoots
        val roots = configured.takeIf { roots ->
            roots.isNotEmpty() && roots.any(originPath::startsWith)
        } ?: listOf(GradleProjectRootLocator.findFor(originPath))
        val openByPath = openDocuments.currentSnapshots()
            .asSequence()
            .filter { snapshot -> snapshot.fileName.endsWith(KOTLIN_GRADLE_SUFFIX) }
            .mapNotNull { snapshot -> filePath(snapshot.uri)?.let { path -> path to snapshot } }
            .filter { (path) -> roots.any(path::startsWith) }
            .associateTo(linkedMapOf()) { (path, snapshot) -> path to snapshot }

        roots.forEach { root ->
            visitRoot(root, openByPath, consume)
        }
        if (!Thread.currentThread().isInterrupted) {
            openByPath.values.sortedBy(DocumentSnapshot::uri).forEach { snapshot ->
                consume(snapshot.analysisDocument())
            }
        }
    }

    private fun visitRoot(
        root: Path,
        openByPath: MutableMap<Path, DocumentSnapshot>,
        consume: (AnalysisDocument) -> Unit,
    ) {
        if (!Files.isDirectory(root)) return
        try {
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(
                        directory: Path,
                        attributes: BasicFileAttributes,
                    ): FileVisitResult {
                        if (Thread.currentThread().isInterrupted) return FileVisitResult.TERMINATE
                        if (directory != root && directory.fileName.toString() in EXCLUDED_DIRECTORIES) {
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                        if (Thread.currentThread().isInterrupted) return FileVisitResult.TERMINATE
                        if (!attributes.isRegularFile || !file.fileName.toString().endsWith(KOTLIN_GRADLE_SUFFIX)) {
                            return FileVisitResult.CONTINUE
                        }
                        val normalized = file.toAbsolutePath().normalize()
                        val snapshot = openByPath.remove(normalized)
                        val document = snapshot?.analysisDocument() ?: try {
                            AnalysisDocument(
                                uri = normalized.toUri().toASCIIString(),
                                fileName = normalized.fileName.toString(),
                                text = Files.readString(normalized),
                            )
                        } catch (_: IOException) {
                            null
                        }
                        document?.let(consume)
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exception: java.io.IOException): FileVisitResult =
                        FileVisitResult.CONTINUE
                },
            )
        } catch (_: IOException) {
            // An unreadable root must not prevent scanning another configured workspace root.
        }
    }

    private fun collapseNestedRoots(roots: List<Path>): List<Path> {
        val retained = mutableListOf<Path>()
        roots.sortedBy { path -> path.nameCount }.forEach { candidate ->
            if (retained.none(candidate::startsWith)) retained.add(candidate)
        }
        return retained
    }

    private fun filePath(uri: String): Path? = try {
        val parsed = URI.create(uri)
        if (parsed.scheme != "file") null else Path.of(parsed).toAbsolutePath().normalize()
    } catch (_: Exception) {
        null
    }

    private fun DocumentSnapshot.analysisDocument(): AnalysisDocument =
        AnalysisDocument(uri, fileName, text)

    private companion object {
        const val KOTLIN_GRADLE_SUFFIX = ".gradle.kts"
        val EXCLUDED_DIRECTORIES = setOf(
            ".git",
            ".gradle",
            ".idea",
            ".kotlin",
            "build",
            "node_modules",
            "out",
            "target",
        )
    }
}
