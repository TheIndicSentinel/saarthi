import java.util.Properties
import java.io.File
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("saarthi.android.library")
    id("saarthi.hilt")
}

// Check whether the NDK is installed before enabling the native build.
// Without NDK the app still compiles — LlamaCppBridge.tryLoad() returns false
// and the stub library is used instead.
val localProps = Properties().also { props ->
    rootProject.file("local.properties")
        .takeIf { it.exists() }
        ?.inputStream()?.use { props.load(it) }
}
// Prefer local.properties; fall back to ANDROID_HOME env var (set by GHA android-runner)
val sdkDir = localProps.getProperty("sdk.dir")
    ?: System.getenv("ANDROID_HOME")
    ?: ""
val ndkExists = sdkDir.isNotEmpty() &&
    (File(sdkDir).resolve("ndk").exists() ||
     File(sdkDir).resolve("ndk-bundle").exists())

android {
    namespace  = "com.saarthi.core.inference"

    buildFeatures {
        buildConfig = true
    }

    if (ndkExists) {
        ndkVersion = "27.0.12077973"
    }

    defaultConfig {
        // Embedded read-only HuggingFace token — enables seamless Gemma model downloads.
        // Users never see or enter this; it is used automatically by ModelDownloadManager.
        //
        // To set it:
        //   local.properties  →  hf.app.token=hf_xxxxxxxxxxxxxxxxxxxxxxxx
        //   CI secret         →  env HF_APP_TOKEN=hf_xxxxxxxxxxxxxxxxxxxxxxxx
        //
        // The token only needs "read" scope. Accept each Gemma model licence once at
        // huggingface.co/{repo} (free, one-click) with the account that owns the token.
        val hfAppToken = localProps.getProperty("hf.app.token")
            ?: System.getenv("HF_APP_TOKEN")
            ?: ""
        buildConfigField("String", "HF_APP_TOKEN", "\"$hfAppToken\"")

        if (ndkExists) {
            externalNativeBuild {
                cmake {
                    cppFlags += "-std=c++17"
                    arguments(
                        "-DANDROID_STL=c++_shared",
                        "-DANDROID_ARM_NEON=TRUE",
                        "-DCMAKE_BUILD_TYPE=Release",
                    )
                }
            }
            ndk {
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
        }
    }

    if (ndkExists) {
        externalNativeBuild {
            cmake {
                path    = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }
}

// litertlm-android:0.10.0 was compiled with Kotlin 2.3.x (metadata version 2.3.0).
// Keep this flag in case a future litertlm release bumps to 2.4.x before we follow.
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

dependencies {
    implementation(libs.coroutines.android)
    implementation(libs.datastore.preferences)
    implementation(project(":core:core-common"))
    implementation(libs.timber)
    
    // LiteRT-LM: Google AI Edge inference library (same runtime as AI Edge Gallery)
    implementation(libs.litertlm)
}
