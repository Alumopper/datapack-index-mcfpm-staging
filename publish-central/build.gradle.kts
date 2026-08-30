plugins {
    `java-library`
    kotlin("jvm")
}

dependencies {
    api(project(":core"))

    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

java {
    withSourcesJar()
    withJavadocJar()
}
