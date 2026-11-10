// Il plugin Android 9 compila il Kotlin da solo ("built-in Kotlin"): il plugin
// org.jetbrains.kotlin.android non serve piu'. Qui si sceglie solo la versione
// di Kotlin, che deve essere la stessa del compilatore Compose.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.5.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
