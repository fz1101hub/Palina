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
// il motore condiviso con l'app per iPhone e Apple Watch (Kotlin Multiplatform)
include(":motore")
