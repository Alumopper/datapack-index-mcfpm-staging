pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "mcfpm"

include(
    "model",
    "core",
    "repository-maven",
    "source-github",
    "publish-nexus",
    "minecraft-java",
    "publish-central",
    "cli",
    "gradle-plugin",
    "mcfpp-adapter",
    "private-nexus-e2e",
)
