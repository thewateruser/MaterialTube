pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io") // NewPipeExtractor
        maven(url = "https://repo.clojars.org") // a NewPipeExtractor transitive dependency
    }
}

rootProject.name = "YouTube-Plus"
include(":app")
