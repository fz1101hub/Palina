import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

// Il motore di Palina condiviso tra Android, iPhone e Apple Watch: dati, fonti,
// orario e quello che le schermate devono mostrare. Le schermate restano native
// (Compose su Android, SwiftUI su iPhone e Apple Watch).
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    android {
        namespace = "it.palinaroma.motore"
        compileSdk = 37
        minSdk = 26
    }

    // un solo XCFramework "Motore" per iPhone e Apple Watch (dispositivo e simulatore)
    val motore = XCFramework("Motore")
    listOf(
        iosArm64(), iosSimulatorArm64(),
        watchosArm64(), watchosDeviceArm64(), watchosSimulatorArm64(),
    ).forEach { bersaglio ->
        bersaglio.binaries.framework {
            baseName = "Motore"
            isStatic = true
            linkerOpts("-lsqlite3")
            motore.add(this)
        }
    }

    sourceSets {
        iosMain.dependencies {
            // SQLite di iOS per l'orario incluso nell'app
            implementation("androidx.sqlite:sqlite-framework:2.7.1")
        }
    }
}
