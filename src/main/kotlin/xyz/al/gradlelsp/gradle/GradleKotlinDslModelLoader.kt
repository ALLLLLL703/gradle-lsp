package xyz.al.gradlelsp.gradle

import org.gradle.tooling.GradleConnector
import org.gradle.tooling.model.kotlin.dsl.KotlinDslModelsParameters
import org.gradle.tooling.model.kotlin.dsl.KotlinDslScriptsModel
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

internal data class GradleKotlinDslModel(
    val classPath: List<Path>,
    val sourcePath: List<Path>,
    val implicitImports: List<String>,
)

internal fun interface GradleKotlinDslModelProvider {
    fun modelFor(script: Path): GradleKotlinDslModel
}

internal class GradleKotlinDslModelLoader(
    private val modelFetcher: ((script: Path, projectRoot: Path) -> GradleKotlinDslModel)? = null,
) : GradleKotlinDslModelProvider {
    private val models = ConcurrentHashMap<Path, CachedGradleModel>()

    override fun modelFor(script: Path): GradleKotlinDslModel {
        val normalizedScript = script.toAbsolutePath().normalize()
        val projectRoot = findProjectRoot(normalizedScript)
        val fingerprint = modelInputFingerprint(normalizedScript, projectRoot)
        return models.compute(normalizedScript) { _, cached ->
            if (cached?.fingerprint == fingerprint) {
                cached
            } else {
                CachedGradleModel(
                    fingerprint,
                    modelFetcher?.invoke(normalizedScript, projectRoot)
                        ?: loadModel(normalizedScript, projectRoot),
                )
            }
        }!!.model
    }

    private fun loadModel(script: Path, projectRoot: Path): GradleKotlinDslModel {
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

                return GradleKotlinDslModel(
                    classPath = scriptModel.classPath.map { it.toPath() },
                    sourcePath = scriptModel.sourcePath.map { it.toPath() },
                    implicitImports = scriptModel.implicitImports,
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

    private fun modelInputFingerprint(script: Path, projectRoot: Path): String {
        val inputs = listOf(
            script,
            projectRoot.resolve("build.gradle.kts"),
            projectRoot.resolve("build.gradle"),
            projectRoot.resolve("settings.gradle.kts"),
            projectRoot.resolve("settings.gradle"),
            projectRoot.resolve("gradle.properties"),
            projectRoot.resolve("gradle/libs.versions.toml"),
            projectRoot.resolve("buildSrc/build.gradle.kts"),
            projectRoot.resolve("buildSrc/build.gradle"),
            projectRoot.resolve("buildSrc/settings.gradle.kts"),
            projectRoot.resolve("build-logic/build.gradle.kts"),
            projectRoot.resolve("build-logic/settings.gradle.kts"),
        ).map(Path::toAbsolutePath).map(Path::normalize).distinct().sortedBy(Path::toString)
        val digest = MessageDigest.getInstance("SHA-256")
        inputs.forEach { input ->
            digest.update(input.toString().toByteArray(Charsets.UTF_8))
            digest.update(0)
            if (Files.isRegularFile(input)) {
                Files.newInputStream(input).use { stream ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
            }
            digest.update(0)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun containsSettingsScript(directory: Path): Boolean =
        Files.isRegularFile(directory.resolve("settings.gradle.kts")) ||
            Files.isRegularFile(directory.resolve("settings.gradle"))

    private data class CachedGradleModel(
        val fingerprint: String,
        val model: GradleKotlinDslModel,
    )
}
