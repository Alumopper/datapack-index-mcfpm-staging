plugins {
    `java-library`
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    api(project(":model"))
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tomlj:tomlj:1.1.1")

    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

java {
    withSourcesJar()
    withJavadocJar()
}
