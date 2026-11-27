plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "de.gamingdoodz.radiofinder"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.gamingdoodz.hitradar"
        minSdk = 26
        targetSdk = 36
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // Signieren nur mit dem geheimen Upload-Schlüssel. Kein Schlüssel im (öffentlichen) Repo.
    // Debug-Bauten nutzen den automatischen Debug-Schlüssel von Android.
    signingConfigs {
        // Geheimer Upload-Schlüssel für Google Play (aus den GitHub-Secrets)
        val upload = System.getenv("UPLOAD_KEYSTORE_FILE")
        if (upload != null) {
            create("upload") {
                storeFile = file(upload)
                storePassword = System.getenv("UPLOAD_KEYSTORE_PASSWORD")
                keyAlias = "upload"
                keyPassword = System.getenv("UPLOAD_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8: kleiner und schneller (von Google empfohlen)
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Ohne Upload-Schlüssel bleibt die Release-Datei unsigniert (der Workflow bricht dann vorher ab)
            signingConfig = signingConfigs.findByName("upload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // Modell unkomprimiert lassen (schneller laden)
    androidResources { noCompress += "tflite" }

    // Unit-Tests laufen ohne Gerät; Android-Aufrufe liefern dort Standardwerte statt abzustürzen
    testOptions { unitTests.isReturnDefaultValues = true }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    val media3 = "1.8.0"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-cast:$media3")
    implementation("androidx.mediarouter:mediarouter:1.7.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.palette:palette:1.0.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.9.0")
    implementation("com.google.guava:guava:33.3.1-android")
    // Klang-Erkennung (YAMNet) auf dem Handy, Apache 2.0
    implementation("com.google.ai.edge.litert:litert:1.4.0")

    testImplementation("junit:junit:4.13.2")
    // Echtes org.json für Tests (im Android-Stub fehlt es)
    testImplementation("org.json:json:20240303")
}
