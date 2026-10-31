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
    }
}

rootProject.name = "notes-android"
include(":app")

// The sync engine, shared with the web app; Gradle builds it from source for notes:sync-engine.
includeBuild("../../shared/sync-engine")
