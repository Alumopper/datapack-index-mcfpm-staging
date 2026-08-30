import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.AbstractArchiveTask

plugins {
    kotlin("jvm") version "2.4.10" apply false
    kotlin("plugin.serialization") version "2.4.10" apply false
}

val mcfpmVersion = providers.gradleProperty("mcfpm.version")
    .orElse(providers.environmentVariable("MCFPM_VERSION"))
    .orElse("0.1.0-SNAPSHOT")

val nexusBaseUrl = providers.gradleProperty("nexusBaseUrl").orElse("https://nexus.mcfpp.top")
val nexusReleasesRepository = providers.gradleProperty("nexusReleasesRepository").orElse("maven-releases")
val nexusSnapshotsRepository = providers.gradleProperty("nexusSnapshotsRepository").orElse("maven-snapshots")
val nexusUsernameProvider = providers.gradleProperty("nexusUsername")
    .orElse(providers.environmentVariable("NEXUS_USERNAME"))
val nexusPasswordProvider = providers.gradleProperty("nexusPassword")
    .orElse(providers.environmentVariable("NEXUS_PASSWORD"))
val nexusRepositoryUrl = provider {
    val repositoryName = if (mcfpmVersion.get().endsWith("-SNAPSHOT")) {
        nexusSnapshotsRepository.get()
    } else {
        nexusReleasesRepository.get()
    }
    "${nexusBaseUrl.get().trimEnd('/')}/repository/$repositoryName/"
}

val validateNexusCredentials = tasks.register("validateNexusCredentials") {
    group = "publishing"
    description = "Validate credentials required by the private Nexus repository."
    doLast {
        if (nexusUsernameProvider.orNull?.isNotBlank() != true) {
            throw GradleException("Missing Nexus username. Set nexusUsername or NEXUS_USERNAME.")
        }
        if (nexusPasswordProvider.orNull?.isNotEmpty() != true) {
            throw GradleException("Missing Nexus password. Set nexusPassword or NEXUS_PASSWORD.")
        }
    }
}

allprojects {
    group = "moe.afox.mcfpm"
    version = mcfpmVersion.get()
}

subprojects {
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    pluginManager.withPlugin("java-library") {
        pluginManager.apply("maven-publish")
        extensions.configure<PublishingExtension> {
            publications.create<MavenPublication>("maven") {
                from(components.getByName("java"))
                pom {
                    name.set("Mcfpm ${project.name}")
                    description.set("Mcfpm independent package manager component: ${project.name}")
                    url.set("https://github.com/Alumopper/Mcfpm")
                    licenses {
                        license {
                            name.set("Apache License 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("Alumopper")
                            name.set("Alumopper")
                        }
                    }
                    scm {
                        url.set("https://github.com/Alumopper/Mcfpm")
                        connection.set("scm:git:https://github.com/Alumopper/Mcfpm.git")
                        developerConnection.set("scm:git:ssh://git@github.com/Alumopper/Mcfpm.git")
                    }
                }
            }
        }
    }

    pluginManager.withPlugin("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories.maven {
                name = "privateNexus"
                url = uri(nexusRepositoryUrl.get())
                isAllowInsecureProtocol = false
                credentials {
                    username = nexusUsernameProvider.orNull
                    password = nexusPasswordProvider.orNull
                }
            }
        }
        tasks.matching { it.name.endsWith("ToPrivateNexusRepository") }.configureEach {
            dependsOn(validateNexusCredentials)
        }
    }

    pluginManager.withPlugin("java") {
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        tasks.withType<JavaCompile>().configureEach {
            options.release.set(17)
        }
    }

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(25)
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
                allWarningsAsErrors.set(true)
                freeCompilerArgs.add("-Xjdk-release=17")
            }
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging {
                events("failed", "skipped")
            }
        }
    }
}
