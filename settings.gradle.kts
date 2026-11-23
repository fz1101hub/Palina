pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PalinaRoma"
include(":app")
// il tabellone per l'orologio (formato comune) e l'app Wear OS
include(":ponte")
include(":orologio")
