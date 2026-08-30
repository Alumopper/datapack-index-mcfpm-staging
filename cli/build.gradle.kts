plugins {
    application
    kotlin("jvm")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":repository-maven"))
    implementation(project(":source-github"))
    implementation(project(":publish-nexus"))
    implementation(project(":minecraft-java"))
    implementation(project(":publish-central"))
    implementation("info.picocli:picocli:4.7.7")

    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

application {
    mainClass.set("moe.afox.mcfpm.cli.MainKt")
    applicationName = "mcfpm"
}

tasks.withType<Jar>().configureEach {
    manifest.attributes["Implementation-Version"] = project.version.toString()
}

val runtimeImageRoot = layout.buildDirectory.dir("runtime-image")
val runtimeImage = runtimeImageRoot.map { it.dir("image") }

val cleanRuntimeImage by tasks.registering(Delete::class) {
    delete(runtimeImageRoot)
}

val jlinkRuntime by tasks.registering(Exec::class) {
    val javaHome = javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    }.map { it.metadata.installationPath }
    inputs.property("javaHome", javaHome.map { it.asFile.absolutePath })
    outputs.dir(runtimeImageRoot)
    dependsOn(cleanRuntimeImage)
    executable(
        javaHome.get().file(
            "bin/jlink${if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) ".exe" else ""}",
        ).asFile.absolutePath,
    )
    args(
        "--add-modules", "java.base,java.logging,java.net.http,java.xml,jdk.crypto.ec",
        "--strip-debug",
        "--no-header-files",
        "--no-man-pages",
        "--compress=zip-6",
        "--output", runtimeImage.get().asFile,
    )
}

tasks.register<Zip>("runtimeZip") {
    group = "distribution"
    description = "Builds a CLI distribution with a minimized runtime for the current operating system."
    dependsOn(tasks.installDist, jlinkRuntime)
    archiveBaseName.set("mcfpm")
    archiveClassifier.set(
        providers.systemProperty("os.name").map { os ->
            when {
                os.startsWith("Windows", ignoreCase = true) -> "windows"
                os.startsWith("Mac", ignoreCase = true) -> "macos"
                else -> "linux"
            }
        },
    )
    from(tasks.installDist.map { it.destinationDir }) {
        exclude("bin/mcfpm", "bin/mcfpm.bat")
    }
    from("src/runtime/bin/mcfpm") {
        into("bin")
        filePermissions {
            unix("rwxr-xr-x")
        }
    }
    from("src/runtime/bin/mcfpm.bat") {
        into("bin")
    }
    into("runtime") {
        from(runtimeImage) {
            exclude("bin/**")
        }
        from(runtimeImage.map { it.dir("bin") }) {
            into("bin")
            filePermissions {
                unix("rwxr-xr-x")
            }
        }
    }
}
