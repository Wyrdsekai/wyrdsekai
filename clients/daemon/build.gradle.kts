// Inference Daemon — Android App
// This module is a SEPARATE Gradle project (not included in root settings.gradle.kts)
// because it requires Android SDK (AGP, minSdk 28, Java 17 toolchain) which conflicts
// with the root project's Java 25 toolchain.
//
// Build: cd clients/daemon && ../../gradlew assembleDebug
// (or open in Android Studio as a standalone project)
//
// Shared types (DaemonCapability, InferenceRequest, etc.) are defined independently
// in this module to avoid cross-project dependency. Wire format is JSON-compatible
// with daemon-common and InferenceGossip on the server.

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "org.wyrdsekai.daemon"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.wyrdsekai.daemon"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // JNI: prebuilt llama.cpp .so in jniLibs/
    // Native build via CMake is in llama-jni/ (separate build step)
    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")
}

dependencies {
    // Compose
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // Ktor embedded server (CIO engine — no Netty on Android)
    implementation("io.ktor:ktor-server-core:3.4.0")
    implementation("io.ktor:ktor-server-cio:3.4.0")
    implementation("io.ktor:ktor-server-content-negotiation:3.4.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.4.0")

    // NATS
    implementation("io.nats:jnats:2.25.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
