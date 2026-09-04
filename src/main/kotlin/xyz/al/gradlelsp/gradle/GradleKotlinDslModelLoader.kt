package xyz.al.gradlelsp.gradle

import org.gradle.tooling.GradleConnector
import org.gradle.tooling.model.gradle.GradleBuild
import org.gradle.tooling.model.kotlin.dsl.KotlinDslModelsParameters
import org.gradle.tooling.model.kotlin.dsl.KotlinDslScriptsModel
import java.io.OutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit

internal data class GradleKotlinDslModel(
    val classPath: List<Path>,
    val sourcePath: List<Path>,
    val implicitImports: List<String>,
    val generation: String = "",
)

internal fun interface GradleKotlinDslModelProvider {
    fun modelFor(script: Path): GradleKotlinDslModel
}

internal class GradleKotlinDslModelLoader(
    private val maximumEntries: Int = 16,
    private val modelFetcher: ((script: Path, projectRoot: Path) -> GradleKotlinDslModel)? = null,
) : GradleKotlinDslModelProvider {
    private val models = LinkedHashMap<Path, CachedGradleModel>(16, 0.75f, true)

    init {
        require(maximumEntries > 0) { "maximumEntries must be positive" }
    }

    @Synchronized
    override fun modelFor(script: Path): GradleKotlinDslModel {
        val normalizedScript = script.toAbsolutePath().normalize()
        val projectRoot = findProjectRoot(normalizedScript)
        val cached = models[normalizedScript]
        val fingerprint = modelInputFingerprint(
            normalizedScript,
            projectRoot,
            cached?.watchedBuildRoots.orEmpty(),
            cached?.model?.classPath.orEmpty(),
        )
        if (cached?.fingerprint == fingerprint) return cached.model

        val loaded = modelFetcher?.let { fetcher ->
            LoadedGradleModel(fetcher(normalizedScript, projectRoot), emptySet())
        } ?: loadModel(normalizedScript, projectRoot)
        val watchedRoots = loaded.watchedBuildRoots
            .map(Path::toAbsolutePath)
            .map(Path::normalize)
            .filterNot { root -> root == projectRoot }
            .toSet()
        val generation = modelInputFingerprint(
            normalizedScript,
            projectRoot,
            watchedRoots,
            loaded.model.classPath,
        )
        val model = loaded.model.copy(generation = generation)
        models[normalizedScript] = CachedGradleModel(generation, watchedRoots, model)
        if (models.size > maximumEntries) {
            models.remove(models.entries.iterator().next().key)
        }
        return model
    }

    private fun loadModel(script: Path, projectRoot: Path): LoadedGradleModel {
        val arguments = arrayOf(
            KotlinDslModelsParameters.CLASSPATH_MODE_SYSTEM_PROPERTY_DECLARATION,
            "-P${KotlinDslScriptsModel.SCRIPTS_GRADLE_PROPERTY_NAME}=$script",
        )
        val discardedOutput = OutputStream.nullOutputStream()

        GradleConnector.newConnector()
            .forProjectDirectory(projectRoot.toFile())
            .connect()
            .use { connection ->
                connection.newBuild()
                    .forTasks(KotlinDslModelsParameters.PREPARATION_TASK_NAME)
                    .withArguments(*arguments)
                    .setStandardOutput(discardedOutput)
                    .setStandardError(discardedOutput)
                    .run()

                val scriptsModel = connection.model(KotlinDslScriptsModel::class.java)
                    .withArguments(*arguments)
                    .setStandardOutput(discardedOutput)
                    .setStandardError(discardedOutput)
                    .get()
                val scriptModel = scriptsModel.scriptModels.entries
                    .firstOrNull { (path) -> path.toPath().toAbsolutePath().normalize() == script }
                    ?.value
                    ?: error("Gradle did not return a Kotlin DSL model for $script")
                val buildRoots = runCatching {
                    val build = connection.getModel(GradleBuild::class.java)
                    build.editableBuilds
                        .map { editable -> editable.buildIdentifier.rootDir.toPath() }
                        .toSet()
                }.getOrDefault(emptySet())

                return LoadedGradleModel(
                    model = GradleKotlinDslModel(
                        classPath = scriptModel.classPath.map { it.toPath() },
                        sourcePath = scriptModel.sourcePath.map { it.toPath() },
                        implicitImports = scriptModel.implicitImports,
                    ),
                    watchedBuildRoots = buildRoots,
                )
            }
    }

    private fun findProjectRoot(script: Path): Path {
        val scriptDirectory = script.parent ?: Path.of("").toAbsolutePath()
        val ancestors = generateSequence(scriptDirectory, Path::getParent).toList()
        return ancestors.firstOrNull(::containsSettingsScript)
            ?: ancestors.firstOrNull { Files.isRegularFile(it.resolve("gradlew")) }
            ?: scriptDirectory
    }

    private fun modelInputFingerprint(
        script: Path,
        projectRoot: Path,
        watchedBuildRoots: Set<Path>,
        classPath: List<Path>,
    ): String {
        val inputs = linkedMapOf<Path, Boolean>()
        fun include(path: Path, hashContent: Boolean) {
            val normalized = path.toAbsolutePath().normalize()
            inputs[normalized] = inputs[normalized] == true || hashContent
        }

        include(script, true)
        collectInputs(projectRoot, ScanMode.PROJECT).forEach { input ->
            include(input.path, input.hashContent)
        }
        watchedBuildRoots.forEach { root ->
            collectInputs(root, ScanMode.INCLUDED_BUILD).forEach { input ->
                include(input.path, input.hashContent)
            }
        }
        classPath.forEach { entry ->
            if (Files.isDirectory(entry)) {
                collectInputs(entry, ScanMode.CLASSPATH).forEach { input ->
                    include(input.path, input.hashContent)
                }
            } else {
                include(entry, false)
            }
        }

        val digest = MessageDigest.getInstance("SHA-256")
        inputs.entries.sortedBy { (path) -> path.toString() }.forEach { (path, hashContent) ->
            updateFingerprint(digest, path, hashContent)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun collectInputs(root: Path, mode: ScanMode): List<ModelInput> {
        if (!Files.isDirectory(root)) return emptyList()
        val normalizedRoot = root.toAbsolutePath().normalize()
        val inputs = mutableListOf<ModelInput>()
        runCatching {
            Files.walkFileTree(
                normalizedRoot,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(
                        directory: Path,
                        attributes: BasicFileAttributes,
                    ): FileVisitResult {
                        if (directory == normalizedRoot) return FileVisitResult.CONTINUE
                        val relative = normalizedRoot.relativize(directory)
                        val name = directory.fileName.toString()
                        if (name in EXCLUDED_DIRECTORIES) return FileVisitResult.SKIP_SUBTREE
                        if (mode == ScanMode.PROJECT && name == "src" && !relative.isBuildLogicPath()) {
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                        if (!attributes.isRegularFile) return FileVisitResult.CONTINUE
                        val relative = normalizedRoot.relativize(file)
                        when (mode) {
                            ScanMode.PROJECT -> if (relative.isBuildLogicPath()) {
                                inputs += ModelInput(file, hashContent = false)
                            } else if (file.isGradleConfigurationFile()) {
                                inputs += ModelInput(file, hashContent = true)
                            }
                            ScanMode.INCLUDED_BUILD -> inputs += ModelInput(
                                file,
                                hashContent = file.isGradleConfigurationFile(),
                            )
                            ScanMode.CLASSPATH -> if (file.fileName.toString().endsWith(".class")) {
                                inputs += ModelInput(file, hashContent = false)
                            }
                        }
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }
        return inputs
    }

    private fun updateFingerprint(
        digest: MessageDigest,
        path: Path,
        hashContent: Boolean,
    ) {
        digest.update(path.toString().toByteArray(Charsets.UTF_8))
        digest.update(0)
        val attributes = runCatching {
            Files.readAttributes(path, BasicFileAttributes::class.java)
        }.getOrNull()
        if (attributes == null) {
            digest.update("missing".toByteArray(Charsets.UTF_8))
        } else {
            digest.update(attributes.size().toString().toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(
                attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS).toString().toByteArray(Charsets.UTF_8),
            )
            digest.update(0)
            digest.update(attributes.fileKey()?.toString().orEmpty().toByteArray(Charsets.UTF_8))
            if (hashContent && attributes.isRegularFile) {
                Files.newInputStream(path).use { stream ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
            }
        }
        digest.update(0)
    }

    private fun Path.isBuildLogicPath(): Boolean {
        val first = firstOrNull()?.toString() ?: return false
        return first == "buildSrc" || first == "build-logic"
    }

    private fun Path.isGradleConfigurationFile(): Boolean {
        val name = fileName.toString()
        return name.endsWith(".gradle") ||
            name.endsWith(".gradle.kts") ||
            name.endsWith(".toml") ||
            name == "gradle.properties" ||
            name == "gradle-wrapper.properties"
    }

    private fun containsSettingsScript(directory: Path): Boolean =
        Files.isRegularFile(directory.resolve("settings.gradle.kts")) ||
            Files.isRegularFile(directory.resolve("settings.gradle"))

    private data class CachedGradleModel(
        val fingerprint: String,
        val watchedBuildRoots: Set<Path>,
        val model: GradleKotlinDslModel,
    )

    private data class LoadedGradleModel(
        val model: GradleKotlinDslModel,
        val watchedBuildRoots: Set<Path>,
    )

    private data class ModelInput(
        val path: Path,
        val hashContent: Boolean,
    )

    private enum class ScanMode {
        PROJECT,
        INCLUDED_BUILD,
        CLASSPATH,
    }

    private companion object {
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
