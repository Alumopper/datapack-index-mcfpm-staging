plugins {
    `java-gradle-plugin`
    `maven-publish`
    kotlin("jvm")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":repository-maven"))

    testImplementation(kotlin("test-junit5"))
    testImplementation(gradleTestKit())
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

gradlePlugin {
    plugins {
        create("mcfpm") {
            id = "moe.afox.mcfpm"
            implementationClass = "moe.afox.mcfpm.gradle.McfpmPlugin"
            displayName = "Mcfpm"
            description = "Resolve, verify, bundle, and publish Mcfpm packages"
        }
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Mcfpm Gradle Plugin")
            description.set("Gradle integration for the Mcfpm package manager")
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
