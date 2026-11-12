plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "app.synapse.remote"
    compileSdk = 35
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "app.synapse.remote"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
        // the embedded Tailscale node (tailnet/build-android.sh) is built for arm64 only
        ndk { abiFilters += "arm64-v8a" }
    }

    // Release signing comes from the environment, never from a file in the repo:
    //   SYNAPSE_KEYSTORE=~/Documents/vscode/keystores/synapse/synapse.release.jks
    //   SYNAPSE_KEYSTORE_PASSWORD=...   (store and key share it: PKCS12)
    //   SYNAPSE_KEY_ALIAS=synapse       (optional, default "synapse")
    // Without them `assembleRelease` still builds, but unsigned.
    val ksPath = System.getenv("SYNAPSE_KEYSTORE")
    val ksPass = System.getenv("SYNAPSE_KEYSTORE_PASSWORD")
    signingConfigs {
        if (!ksPath.isNullOrBlank() && !ksPass.isNullOrEmpty()) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = ksPass
                keyAlias = System.getenv("SYNAPSE_KEY_ALIAS") ?: "synapse"
                keyPassword = ksPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
