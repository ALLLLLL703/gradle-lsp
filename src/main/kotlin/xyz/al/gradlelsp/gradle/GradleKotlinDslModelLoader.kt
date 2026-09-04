package xyz.al.gradlelsp.gradle

import org.gradle.tooling.GradleConnector
import org.gradle.tooling.model.kotlin.dsl.KotlinDslModelsParameters
import org.gradle.tooling.model.kotlin.dsl.KotlinDslScriptsModel
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

internal data class GradleKotlinDslModel(
    val classPath: List<Path>,
    val sourcePath: List<Path>,
    val implicitImports: List<String>,
)

internal fun interface GradleKotlinDslModelProvider {
    fun modelFor(script: Path): GradleKotlinDslModel
}

internal class GradleKotlinDslModelLoader : GradleKotlinDslModelProvider {
    private val models = ConcurrentHashMap<Path, GradleKotlinDslModel>()

    override fun modelFor(script: Path): GradleKotlinDslModel {
        val normalizedScript = script.toAbsolutePath().normalize()
        return models.computeIfAbsent(normalizedScript, ::loadModel)
    }

    private fun loadModel(script: Path): GradleKotlinDslModel {
        val projectRoot = findProjectRoot(script)
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

    private fun containsSettingsScript(directory: Path): Boolean =
        Files.isRegularFile(directory.resolve("settings.gradle.kts")) ||
            Files.isRegularFile(directory.resolve("settings.gradle"))
}
