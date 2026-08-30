package moe.afox.mcfpm.gradle

import moe.afox.mcfpm.repository.maven.MavenPackageRepository
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.NamedDomainObjectCollection
import org.gradle.api.file.SourceDirectorySet

public class McfpmPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("mcfpm", McfpmExtension::class.java)
        val cacheDirectory = project.layout.buildDirectory.dir("mcfpm/cache")
        val reportsDirectory = project.layout.buildDirectory.dir("reports/mcfpm")

        val resolve = project.tasks.register("mcfpmResolve", McfpmResolveTask::class.java) { task ->
            task.group = TASK_GROUP
            task.description = "Resolves mcfpm.toml and writes the deterministic lockfile"
            task.manifestFile.set(extension.manifest)
            task.consumerProfile.set(extension.consumerProfile)
            task.repositoryUrl.convention(MavenPackageRepository.MAVEN_CENTRAL_URI.toString())
            task.lockfile.set(extension.lockfile)
        }
        val fetch = project.tasks.register("mcfpmFetch", McfpmFetchTask::class.java) { task ->
            task.group = TASK_GROUP
            task.description = "Fetches locked Mcfpm artifacts into the content-addressed cache"
            task.dependsOn(resolve)
            task.manifestFile.set(extension.manifest)
            task.lockfile.set(extension.lockfile)
            task.repositoryUrl.convention(MavenPackageRepository.MAVEN_CENTRAL_URI.toString())
            task.offline.convention(project.providers.gradleProperty("mcfpm.offline").map(String::toBoolean).orElse(false))
            task.cacheDirectory.set(cacheDirectory)
            task.reportFile.set(reportsDirectory.map { it.file("fetch.json") })
        }
        val verify = project.tasks.register("mcfpmVerify", McfpmVerifyTask::class.java) { task ->
            task.group = TASK_GROUP
            task.description = "Verifies locked Mcfpm payload checksums, ZIP safety, and executable trust"
            task.dependsOn(fetch)
            task.lockfile.set(extension.lockfile)
            task.cacheDirectory.set(cacheDirectory)
            task.trustFile.convention(project.layout.projectDirectory.file(".mcfpm/trust.toml"))
            task.reportFile.set(reportsDirectory.map { it.file("verify.json") })
        }
        project.tasks.register("mcfpmBundle", McfpmBundleTask::class.java) { task ->
            task.group = TASK_GROUP
            task.description = "Creates a non-merged Mcfpm consumer bundle"
            task.dependsOn(verify)
            task.lockfile.set(extension.lockfile)
            task.cacheDirectory.set(cacheDirectory)
            task.outputDirectory.set(project.layout.buildDirectory.dir("mcfpm/bundle"))
        }
        val koreBindings = project.tasks.register("mcfpmGenerateKoreBindings", McfpmKoreBindingsTask::class.java) { task ->
            task.group = TASK_GROUP
            task.description = "Generates path-independent KoreDatapacks bindings from mcfpm.lock"
            task.dependsOn(resolve)
            task.lockfile.set(extension.lockfile)
            task.packageName.convention("moe.afox.mcfpm.generated")
            task.aliases.convention(emptyMap())
            task.outputFile.set(
                project.layout.buildDirectory.file("generated/sources/mcfpm/kotlin/KoreDatapacks.kt"),
            )
        }
        project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            addKotlinSourceDirectory(
                project,
                "main",
                koreBindings.flatMap { it.outputFile }.map { it.asFile.parentFile },
            )
        }
        project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            addKotlinSourceDirectory(
                project,
                "commonMain",
                koreBindings.flatMap { it.outputFile }.map { it.asFile.parentFile },
            )
        }
        val pack = project.tasks.register("mcfpmPack") { task ->
            task.group = TASK_GROUP
            task.description = "Packs all configured Mcfpm payload providers"
        }
        val payloadOutputs = project.files()
        extension.payloads.configureEach { payload ->
            val taskName = "mcfpmPack" + payload.name.replaceFirstChar(Char::uppercaseChar)
            val payloadTask = project.tasks.register(taskName, McfpmPackPayloadTask::class.java) { task ->
                task.group = TASK_GROUP
                task.sourceDirectory.set(payload.source)
                task.payloadType.set(payload.type)
                task.classifier.set(payload.classifier.orElse(payload.name))
                task.outputFile.set(
                    project.layout.buildDirectory.file(
                        payload.classifier.orElse(payload.name).map { "mcfpm/payloads/$it.zip" },
                    ),
                )
            }
            payloadOutputs.from(payloadTask.flatMap { it.outputFile })
            pack.configure { it.dependsOn(payloadTask) }
        }
        project.tasks.register("mcfpmPublish", McfpmPublishValidationTask::class.java) { task ->
            task.group = TASK_GROUP
            task.description = "Validates publication inputs; Central release requires the CLI --release gate"
            task.dependsOn(verify, pack)
            task.manifestFile.set(extension.manifest)
            task.lockfile.set(extension.lockfile)
            task.payloadFiles.from(payloadOutputs)
            task.reportFile.set(reportsDirectory.map { it.file("publish.json") })
        }
    }

    private companion object {
        const val TASK_GROUP: String = "mcfpm"
    }
}

private fun addKotlinSourceDirectory(project: Project, sourceSetName: String, source: Any) {
    val kotlinExtension = project.extensions.getByName("kotlin")
    val sourceSetsGetter = kotlinExtension.javaClass.methods.singleOrNull {
        it.name == "getSourceSets" && it.parameterCount == 0
    } ?: error("The applied Kotlin plugin does not expose sourceSets")
    @Suppress("UNCHECKED_CAST")
    val sourceSets = sourceSetsGetter.invoke(kotlinExtension) as NamedDomainObjectCollection<Any>
    val sourceSet = sourceSets.getByName(sourceSetName)
    val kotlinGetter = sourceSet.javaClass.methods.singleOrNull {
        it.name == "getKotlin" && it.parameterCount == 0
    } ?: error("Kotlin source set $sourceSetName does not expose its Kotlin sources")
    val kotlinSources = kotlinGetter.invoke(sourceSet) as SourceDirectorySet
    kotlinSources.srcDir(source)
}
