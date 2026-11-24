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
// l'app Wear OS (il formato del tabellone per l'orologio e' nel motore)
include(":orologio")
// il motore condiviso con l'app per iPhone e Apple Watch (Kotlin Multiplatform)
include(":motore")
