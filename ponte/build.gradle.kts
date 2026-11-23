// Il "ponte" tra telefono e orologio: cosa si scambiano (il tabellone delle
// fermate) e come lo scrivono. Lo usano sia l'app del telefono sia quella Wear OS.
plugins {
    id("com.android.library")
}

android {
    namespace = "it.palinaroma.ponte"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // org.json c'e' in Android; nei test sul computer serve la libreria vera
    testImplementation("org.json:json:20240303")
}
