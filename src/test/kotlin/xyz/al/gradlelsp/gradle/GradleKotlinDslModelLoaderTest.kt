package xyz.al.gradlelsp.gradle

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class GradleKotlinDslModelLoaderTest {
    @Test
    fun `cached model is replaced when direct or indirect Gradle inputs change`() {
        val project = Files.createTempDirectory("gradle-lsp-model-cache")
        try {
            val settings = project.resolve("settings.gradle.kts")
            val script = project.resolve("build.gradle.kts")
            val classDirectory = Files.createDirectories(project.resolve("build/classes"))
            val classFile = classDirectory.resolve("Fixture.class")
            Files.writeString(settings, "rootProject.name = \"fixture\"\n")
            Files.writeString(script, "plugins { java }\n")
            Files.write(classFile, byteArrayOf(1))
            var loads = 0
            val loader = GradleKotlinDslModelLoader { requestedScript, projectRoot ->
                loads += 1
                GradleKotlinDslModel(
                    classPath = listOf(classDirectory),
                    sourcePath = emptyList(),
                    implicitImports = listOf(
                        projectRoot.toString(),
                        Files.readString(requestedScript),
                    ),
                )
            }

            val original = loader.modelFor(script)
            assertEquals(original, loader.modelFor(script))
            assertEquals(1, loads)

            Files.writeString(script, "plugins { application }\n")
            assertEquals("plugins { application }\n", loader.modelFor(script).implicitImports.last())
            assertEquals(2, loads)

            Files.writeString(settings, "rootProject.name = \"changed\"\n")
            loader.modelFor(script)
            assertEquals(3, loads)

            val buildLogicSource = project.resolve("buildSrc/src/main/kotlin/ConventionPlugin.kt")
            Files.createDirectories(buildLogicSource.parent)
            Files.writeString(buildLogicSource, "class ConventionPlugin\n")
            loader.modelFor(script)
            assertEquals(4, loads)

            val customCatalog = project.resolve("gradle/testing.versions.toml")
            Files.createDirectories(customCatalog.parent)
            Files.writeString(customCatalog, "[versions]\nfixture = \"1\"\n")
            loader.modelFor(script)
            assertEquals(5, loads)

            val beforeClassChange = loader.modelFor(script).generation
            Files.write(classFile, byteArrayOf(1, 2))
            val afterClassChange = loader.modelFor(script).generation
            assertNotEquals(beforeClassChange, afterClassChange)
            assertEquals(6, loads)
        } finally {
            project.toFile().deleteRecursively()
        }
    }

    @Test
    fun `model cache evicts least recently used scripts`() {
        val root = Files.createTempDirectory("gradle-lsp-model-lru")
        try {
            val scripts = (1..3).map { index ->
                val project = Files.createDirectories(root.resolve("project-$index"))
                Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"p$index\"\n")
                project.resolve("build.gradle.kts").also { script ->
                    Files.writeString(script, "plugins { java }\n")
                }
            }
            val loads = mutableMapOf<String, Int>()
            val loader = GradleKotlinDslModelLoader(maximumEntries = 2) { script, _ ->
                val key = script.toString()
                loads[key] = loads.getOrDefault(key, 0) + 1
                GradleKotlinDslModel(emptyList(), emptyList(), emptyList())
            }

            scripts.forEach(loader::modelFor)
            loader.modelFor(scripts.first())

            assertEquals(2, loads.getValue(scripts.first().toString()))
            assertEquals(4, loads.values.sum())
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
