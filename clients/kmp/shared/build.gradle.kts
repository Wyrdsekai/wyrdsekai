plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// ONNX Runtime C xcframework for the iOS targets (
// §7): same library Android/desktop use via Maven, distributed for iOS as a
// CocoaPods archive. Downloaded once into the build dir; cinterop binds the
// C API (onnxruntime_c_api.h) — no CocoaPods, no Swift.
val onnxIosVersion = "1.23.0"
val onnxIosDir = layout.buildDirectory.dir("onnxruntime-ios").get().asFile
val onnxXcframework = File(onnxIosDir, "onnxruntime.xcframework")
val downloadOnnxRuntimeIos = tasks.register("downloadOnnxRuntimeIos") {
    outputs.dir(onnxIosDir)
    onlyIf { !onnxXcframework.exists() }
    doLast {
        onnxIosDir.mkdirs()
        val zip = File(onnxIosDir, "pod-archive.zip")
        ant.invokeMethod(
            "get",
            mapOf(
                "src" to "https://download.onnxruntime.ai/pod-archive-onnxruntime-c-$onnxIosVersion.zip",
                "dest" to zip,
            ),
        )
        copy {
            from(zipTree(zip))
            into(onnxIosDir)
        }
        zip.delete()
        // cinterop's staticLibraries matching expects lib<name>.a naming;
        // the xcframework's framework binary is the same archive, unnamed.
        listOf("ios-arm64", "ios-arm64_x86_64-simulator").forEach { slice ->
            val libDir = File(onnxXcframework, "$slice/lib").apply { mkdirs() }
            File(onnxXcframework, "$slice/onnxruntime.framework/onnxruntime")
                .copyTo(File(libDir, "libonnxruntime.a"), overwrite = true)
        }
    }
}

kotlin {
    androidTarget()
    jvm("desktop")

    // iOS targets
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        val slice =
            if (iosTarget.name == "iosArm64") "ios-arm64"
            else "ios-arm64_x86_64-simulator"
        val sliceDir = File(onnxXcframework, slice)
        iosTarget.compilations.getByName("main").cinterops.create("onnxruntime") {
            defFile(project.file("src/nativeInterop/cinterop/onnxruntime.def"))
            packageName = "com.microsoft.onnxruntime.c"
            includeDirs(File(sliceDir, "onnxruntime.framework/Headers"))
            extraOpts("-libraryPath", File(sliceDir, "lib").absolutePath)
            tasks.named(interopProcessingTaskName) { dependsOn(downloadOnnxRuntimeIos) }
        }
        iosTarget.binaries.framework {
            baseName = "shared"
            isStatic = true
        }
        iosTarget.binaries.all {
            linkerOpts("-lc++")
        }
    }

    sourceSets {
        commonMain.dependencies {
            // Compose Multiplatform
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.components.resources)

            // Ktor
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)

            // Kotlinx
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)

            // DI
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }

        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.androidx.security.crypto)
            implementation(libs.rhino)
            implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
            // phone-side NATS request/reply
            // over wss://relay:4443. jnats supports wss:// since 2.16; we
            // match the server's 2.25.2. Android-only because iosMain
            // doesn't have jnats; iOS phones use the RN client.
            implementation("io.nats:jnats:2.25.2")
            // camera QR scanning of wyrdphone:// invites
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.camera.camera2)
            implementation(libs.androidx.camera.lifecycle)
            implementation(libs.androidx.camera.view)
            implementation(libs.mlkit.barcode.scanning)
        }

        val desktopMain by getting {
            dependencies {
                implementation(libs.ktor.client.java)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.rhino)
                implementation("com.microsoft.onnxruntime:onnxruntime:1.23.2")
            }
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            // On-device llama.cpp inference ( item 1):
            // KMP artifact with published ios targets — fills the LlamatikBridge
            // seam from Kotlin (RealLlamatikBridge), no Swift framework linking.
            implementation("com.llamatik:library:1.8.0")
        }
    }
}

// Force kotlinx-datetime version alignment (Compose Material3 pulls 0.7.1 transitively,
// causing compile/runtime mismatch since 0.7.x moved Instant to kotlin.time)
configurations.configureEach {
    resolutionStrategy {
        force("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
        force("org.jetbrains.kotlinx:kotlinx-datetime-jvm:0.7.1")
    }
}

android {
    namespace = "org.wyrdsekai.app.shared"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        ndk {
            // Ship = ARM64 phones only. Emulator/CI builds override from the CLI
            // (no source edit): ./gradlew :androidApp:assembleDebug -PwyrdAbis=x86_64
            // Comma-separated for multi-ABI, e.g. -PwyrdAbis=arm64-v8a,x86_64.
            val wyrdAbis = (findProperty("wyrdAbis") as? String)
                ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            abiFilters += (wyrdAbis ?: listOf("arm64-v8a"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // llama.cpp JNI — only builds if llama.cpp source is present
    val llamaCppDir = file("${projectDir}/../llama.cpp")
    if (llamaCppDir.exists()) {
        externalNativeBuild {
            cmake {
                path = file("src/androidMain/jni/CMakeLists.txt")
                version = "3.22.1+"
            }
        }
        defaultConfig {
            externalNativeBuild {
                cmake {
                    arguments += listOf(
                        "-DLLAMA_CPP_DIR=${llamaCppDir.absolutePath}",
                        "-DANDROID_STL=c++_shared",
                    )
                }
            }
        }
    }
}
