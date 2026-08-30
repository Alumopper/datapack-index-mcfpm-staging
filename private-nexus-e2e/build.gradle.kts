import org.gradle.api.publish.maven.MavenPublication

plugins {
    application
    kotlin("jvm")
    `maven-publish`
}

dependencies {
    implementation(project(":core"))
    implementation(project(":repository-maven"))
    implementation(project(":source-github"))
    implementation(project(":publish-nexus"))
    implementation(project(":cli"))
}

application {
    mainClass.set("moe.afox.mcfpm.e2e.PrivateNexusE2EKt")
}

val fixtureVersion = providers.gradleProperty("mcfpm.privateNexusE2EVersion")
    .orElse("0.0.0-e2e-SNAPSHOT")
val fixtureDirectory = layout.buildDirectory.dir("private-nexus-fixtures")

val preparePrivateNexusFixtures by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Download pinned GitHub datapacks and prepare deterministic Maven artifacts."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass)
    args("prepare", fixtureVersion.get(), fixtureDirectory.get().asFile.absolutePath)
    outputs.files(
        fixtureDirectory.map { it.file("lifesteal.mcfpkg") },
        fixtureDirectory.map { it.file("lifesteal-datapack.zip") },
        fixtureDirectory.map { it.file("no-creeper-griefing.mcfpkg") },
        fixtureDirectory.map { it.file("no-creeper-griefing-datapack.zip") },
    )
}

publishing {
    publications {
        create<MavenPublication>("lifesteal") {
            groupId = "io.github.jairussw"
            artifactId = "lifesteal"
            version = fixtureVersion.get()
            artifact(fixtureDirectory.map { it.file("lifesteal.mcfpkg") }) {
                extension = "mcfpkg"
                builtBy(preparePrivateNexusFixtures)
            }
            artifact(fixtureDirectory.map { it.file("lifesteal-datapack.zip") }) {
                classifier = "datapack"
                extension = "zip"
                builtBy(preparePrivateNexusFixtures)
            }
            pom {
                name.set("LifeSteal Mcfpm E2E fixture")
                description.set("Pinned GitHub datapack used only for private Nexus integration tests.")
                url.set("https://github.com/JairusSW/lifesteal")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/license/mit")
                    }
                }
            }
        }
        create<MavenPublication>("noCreeperGriefing") {
            groupId = "io.github.hallettj"
            artifactId = "no-creeper-griefing"
            version = fixtureVersion.get()
            artifact(fixtureDirectory.map { it.file("no-creeper-griefing.mcfpkg") }) {
                extension = "mcfpkg"
                builtBy(preparePrivateNexusFixtures)
            }
            artifact(fixtureDirectory.map { it.file("no-creeper-griefing-datapack.zip") }) {
                classifier = "datapack"
                extension = "zip"
                builtBy(preparePrivateNexusFixtures)
            }
            pom {
                name.set("No Creeper Griefing Mcfpm E2E fixture")
                description.set("Pinned GitHub datapack used only for private Nexus integration tests.")
                url.set("https://github.com/hallettj/no_creeper_griefing")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/license/mit")
                    }
                }
            }
        }
    }
}

val nexusBaseUrl = providers.gradleProperty("nexusBaseUrl").orElse("https://nexus.mcfpp.top")
val nexusSnapshotsRepository = providers.gradleProperty("nexusSnapshotsRepository").orElse("maven-snapshots")
val nexusReleasesRepository = providers.gradleProperty("nexusReleasesRepository").orElse("maven-releases")
val nexusUsernameProvider = providers.gradleProperty("nexusUsername")
    .orElse(providers.environmentVariable("NEXUS_USERNAME"))
val nexusPasswordProvider = providers.gradleProperty("nexusPassword")
    .orElse(providers.environmentVariable("NEXUS_PASSWORD"))
val snapshotRepositoryUrl = providers.provider {
    "${nexusBaseUrl.get().trimEnd('/')}/repository/${nexusSnapshotsRepository.get()}/"
}
val releaseRepositoryUrl = providers.provider {
    "${nexusBaseUrl.get().trimEnd('/')}/repository/${nexusReleasesRepository.get()}/"
}
val requestedLiveTasks = gradle.startParameter.taskNames.map { it.substringAfterLast(':') }.toSet()

val runPrivateNexusE2E by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Publish pinned GitHub datapacks, resolve them from private Nexus, and verify offline reuse."
    notCompatibleWithConfigurationCache("Reads private Nexus credentials only for an explicitly requested live task.")
    dependsOn(
        "publishLifestealPublicationToPrivateNexusRepository",
        "publishNoCreeperGriefingPublicationToPrivateNexusRepository",
    )
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass)
    args("verify", fixtureVersion.get(), snapshotRepositoryUrl.get(), fixtureDirectory.get().asFile.absolutePath)
    if ("runPrivateNexusE2E" in requestedLiveTasks) {
        environment("MCFPM_E2E_NEXUS_USERNAME", nexusUsernameProvider.get())
        environment("MCFPM_E2E_NEXUS_PASSWORD", nexusPasswordProvider.get())
    }
}

val githubImportVersion = providers.gradleProperty("mcfpm.githubImportE2EVersion")
    .orElse("0.0.0-github-import-e2e.1")

tasks.register<JavaExec>("runGitHubImportPrivateNexusE2E") {
    group = "verification"
    description = "Import a real GitHub release asset and commit archive through the Nexus Components API, then verify Maven-only consumption."
    notCompatibleWithConfigurationCache("Reads private Nexus credentials immediately before execution.")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("moe.afox.mcfpm.e2e.GitHubImportPrivateNexusE2E")
    args(
        githubImportVersion.get(),
        releaseRepositoryUrl.get(),
        layout.buildDirectory.dir("github-import-private-nexus").get().asFile.absolutePath,
    )
    if ("runGitHubImportPrivateNexusE2E" in requestedLiveTasks) {
        environment("MCFPM_E2E_NEXUS_USERNAME", nexusUsernameProvider.get())
        environment("MCFPM_E2E_NEXUS_PASSWORD", nexusPasswordProvider.get())
    }
}
