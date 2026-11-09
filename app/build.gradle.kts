plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "it.palinaroma"
    compileSdk = 35

    defaultConfig {
        applicationId = "it.palinaroma"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "0.11"
    }

    // La chiave di firma (firma.p12) e' nel repository ma cifrata: la password
    // sta solo nel segreto FIRMA_PASSWORD di GitHub. Serve perche' ogni APK
    // compilato si installi sopra il precedente. Senza il segreto l'APK e' una
    // versione di prova firmata con la chiave temporanea di GitHub: per
    // aggiornarla bisogna disinstallare quella vecchia.
    val passwordFirma = System.getenv("FIRMA_PASSWORD").orEmpty()
    if (passwordFirma.isNotEmpty()) {
        signingConfigs {
            create("personale") {
                storeFile = file("firma.p12")
                storeType = "pkcs12"
                storePassword = passwordFirma
                keyAlias = "palinaroma"
                keyPassword = passwordFirma
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (passwordFirma.isNotEmpty()) "personale" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    // posizione rapida (Wi-Fi e rete) dai servizi di localizzazione di Google
    implementation("com.google.android.gms:play-services-location:21.3.0")

    testImplementation("junit:junit:4.13.2")
}
