plugins {
    `java-library`
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

java {
    withSourcesJar()
    withJavadocJar()
}
