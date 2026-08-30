package moe.afox.mcfpm.gradle

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome

class McfpmPluginTest {
    @Test
    fun `Kotlin JVM provider payload supports configuration cache and build cache`() {
        val project = testProject("jvm")
        Files.writeString(
            project.resolve("build.gradle.kts"),
            """
            plugins {
                kotlin("jvm") version "2.4.10"
                id("moe.afox.mcfpm")
            }

            val generatePayload by tasks.registering(Sync::class) {
                from(layout.projectDirectory.dir("payload-source"))
                into(layout.buildDirectory.dir("generated-payload"))
            }

            mcfpm {
                consumerProfile.set("minecraft.datapack")
                payloads.create("main") {
                    source.set(layout.dir(generatePayload.map { it.destinationDir }))
                    type.set("minecraft.datapack")
                    classifier.set("datapack")
                }
            }
            """.trimIndent(),
        )

        val first = runner(project, "mcfpmPackMain", "--configuration-cache", "--build-cache").build()
        assertEquals(TaskOutcome.SUCCESS, first.task(":mcfpmPackMain")?.outcome)
        val second = runner(project, "mcfpmPackMain", "--configuration-cache", "--build-cache").build()
        assertTrue(second.output.contains("Configuration cache entry reused"))
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":mcfpmPackMain")?.outcome)

        Files.delete(project.resolve("build/mcfpm/payloads/datapack.zip"))
        val cached = runner(project, "mcfpmPackMain", "--configuration-cache", "--build-cache").build()
        assertEquals(TaskOutcome.FROM_CACHE, cached.task(":mcfpmPackMain")?.outcome)
    }

    @Test
    fun `Kotlin multiplatform project registers the complete task contract`() {
        val project = testProject("kmp")
        Files.writeString(
            project.resolve("build.gradle.kts"),
            """
            plugins {
                kotlin("multiplatform") version "2.4.10"
                id("moe.afox.mcfpm")
            }

            kotlin { jvm() }
            """.trimIndent(),
        )

        val result = runner(project, "tasks", "--group", "mcfpm", "--configuration-cache").build()

        listOf("mcfpmResolve", "mcfpmFetch", "mcfpmVerify", "mcfpmBundle", "mcfpmPack", "mcfpmPublish", "mcfpmGenerateKoreBindings")
            .forEach { task -> assertTrue(result.output.contains(task), "Missing task $task") }
    }

    private fun testProject(name: String): Path {
        val project = createTempDirectory("mcfpm-gradle-$name")
        Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"test-$name\"\n")
        val payload = Files.createDirectories(project.resolve("payload-source"))
        Files.writeString(payload.resolve("pack.mcmeta"), """{"pack":{"pack_format":48,"description":"test"}}""")
        Files.createDirectories(payload.resolve("data/test/functions"))
        Files.writeString(payload.resolve("data/test/functions/main.mcfunction"), "say test\n")
        return project
    }

    private fun runner(project: Path, vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(project.toFile())
            .withTestKitDir(project.resolve(".gradle-test-kit").toFile())
            .withPluginClasspath()
            .withArguments(*arguments, "--stacktrace")
            .forwardOutput()
}
