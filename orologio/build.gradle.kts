// Palina per gli orologi Wear OS (dalla 5, Android 14). I dati li calcola il
// telefono (servizio ServizioOrologio dell'app): l'orologio li riceve e li mostra
// nell'app, nel riquadro (o nel widget, da Wear OS 7), nella complicazione e,
// per la linea seguita, in un Live Update.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "it.palinaroma.orologio"
    compileSdk = 37

    defaultConfig {
        // lo stesso del telefono: Wear OS collega le due app solo se hanno
        // lo stesso nome e la stessa firma
        applicationId = "it.palinaroma"
        minSdk = 34
        targetSdk = 37
        versionCode = 24
        versionName = "0.23"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // la stessa firma del telefono (vedi app/build.gradle.kts)
    val passwordFirma = System.getenv("FIRMA_PASSWORD").orEmpty()
    if (passwordFirma.isNotEmpty()) {
        signingConfigs {
            create("personale") {
                storeFile = file("../app/firma.p12")
                storeType = "pkcs12"
                storePassword = passwordFirma
                keyAlias = "palinaroma"
                keyPassword = passwordFirma
            }
        }
    }

    buildTypes {
        release {
            // R8 toglie il codice e le risorse delle librerie che non si usano:
            // l'APK per l'orologio passa da circa 29 MB a pochi MB
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (passwordFirma.isNotEmpty()) "personale" else "debug")
        }
    }

    // i widget di Wear OS 7 usano le classi della Wear SDK, presenti sull'orologio
    useLibrary("wear-sdk")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    // i costruttori del riquadro usano qualche classe di Android: nei test sul computer bastano i valori vuoti
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":motore"))

    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.wear.compose:compose-foundation:1.7.2")
    implementation("androidx.wear.compose:compose-material3:1.7.2")
    // le schermate della linea e del treno, con lo scorrimento a destra per tornare indietro
    implementation("androidx.wear.compose:compose-navigation:1.7.2")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.core:core-ktx:1.19.1")

    // collegamento con il telefono
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0")

    // riquadro (tile) e complicazione
    implementation("androidx.wear.tiles:tiles:1.6.2")
    implementation("androidx.wear.protolayout:protolayout:1.4.2")
    implementation("androidx.wear.protolayout:protolayout-expression:1.4.2")
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.3.0")
    implementation("androidx.concurrent:concurrent-futures:1.3.0")

    // la linea seguita: Live Update da Wear OS 7, attivita' in corso (Ongoing Activity) prima
    implementation("androidx.wear:wear-ongoing:1.1.0")

    // i widget di Wear OS 7 (Jetpack Glance e Remote Compose, ancora in alpha)
    implementation("androidx.glance.wear:wear:1.0.0-alpha23")
    implementation("androidx.glance.wear:wear-core:1.0.0-alpha23")
    implementation("androidx.compose.remote:remote-creation-compose:1.0.0-alpha24")
    implementation("androidx.compose.remote:remote-core:1.0.0-alpha24")
    implementation("androidx.wear.compose.remote:remote-material3:1.0.0-alpha16")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.4.1")
}
