plugins {
    id("com.android.application")
    // il Kotlin lo compila il plugin Android; resta solo il compilatore di Compose
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "it.palinaroma"
    compileSdk = 37

    defaultConfig {
        applicationId = "it.palinaroma"
        minSdk = 26
        targetSdk = 37
        versionCode = 14
        versionName = "0.14"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    // Java 17 per il codice dell'app (il Kotlin segue questa scelta da solo)
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    // L'orario di Roma Mobilita' preparato dalla compilazione su GitHub
    // (strumenti/crea_orario.py) finisce in src/main/assets: se c'e', l'app
    // funziona dal primo avvio senza scaricare nulla.
}

// la generazione dell'orario incluso legge tutto l'orario di Roma
tasks.withType<Test>().configureEach {
    maxHeapSize = "2g"
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    // posizione rapida (Wi-Fi e rete) dai servizi di localizzazione di Google
    implementation("com.google.android.gms:play-services-location:21.4.0")

    testImplementation("junit:junit:4.13.2")

    // prova sul telefono simulato (emulatore) nella compilazione su GitHub
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.4.1")
}
