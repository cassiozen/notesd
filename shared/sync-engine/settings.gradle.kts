pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // androidx.sqlite is only on Google's.
        google()
        mavenCentral()
    }
}

rootProject.name = "sync-engine"
