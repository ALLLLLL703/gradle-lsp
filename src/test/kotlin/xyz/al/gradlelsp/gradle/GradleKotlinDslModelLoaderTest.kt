package xyz.al.gradlelsp.gradle

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class GradleKotlinDslModelLoaderTest {
    @Test
    fun `cached model is replaced when Gradle inputs change`() {
        val project = Files.createTempDirectory("gradle-lsp-model-cache")
        try {
            val settings = project.resolve("settings.gradle.kts")
            val script = project.resolve("build.gradle.kts")
            Files.writeString(settings, "rootProject.name = \"fixture\"\n")
            Files.writeString(script, "plugins { java }\n")
            var loads = 0
            val loader = GradleKotlinDslModelLoader { requestedScript, projectRoot ->
                loads += 1
                GradleKotlinDslModel(
                    classPath = emptyList(),
                    sourcePath = emptyList(),
                    implicitImports = listOf(
                        projectRoot.toString(),
                        Files.readString(requestedScript),
                    ),
                )
            }

            assertEquals(loader.modelFor(script), loader.modelFor(script))
            assertEquals(1, loads)

            Files.writeString(script, "plugins { application }\n")
            assertEquals("plugins { application }\n", loader.modelFor(script).implicitImports.last())
            assertEquals(2, loads)

            Files.writeString(settings, "rootProject.name = \"changed\"\n")
            loader.modelFor(script)
            assertEquals(3, loads)
        } finally {
            project.toFile().deleteRecursively()
        }
    }
}
