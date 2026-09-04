package xyz.al.gradlelsp.gradle

import java.nio.file.Files
import java.nio.file.Path

internal object GradleProjectRootLocator {
    fun findFor(script: Path): Path {
        val scriptDirectory = script.toAbsolutePath().normalize().parent
            ?: Path.of("").toAbsolutePath().normalize()
        val ancestors = generateSequence(scriptDirectory, Path::getParent).toList()
        return ancestors.firstOrNull(::containsSettingsScript)
            ?: ancestors.firstOrNull { directory -> Files.isRegularFile(directory.resolve("gradlew")) }
            ?: scriptDirectory
    }

    private fun containsSettingsScript(directory: Path): Boolean =
        Files.isRegularFile(directory.resolve("settings.gradle.kts")) ||
            Files.isRegularFile(directory.resolve("settings.gradle"))
}
