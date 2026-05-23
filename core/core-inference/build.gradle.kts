import java.util.Properties
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("saarthi.android.library")
    id("saarthi.hilt")
}

// Inference runs entirely on litertlm-android (Google AI Edge). The previous
// llama.cpp native bridge had no Kotlin caller and was deleted in v1.0.19 —
// no NDK / CMake / Vulkan-headers setup is required any more.

val localProps = Properties().also { props ->
    rootProject.file("local.properties")
        .takeIf { it.exists() }
        ?.inputStream()?.use { props.load(it) }
}

android {
    namespace  = "com.saarthi.core.inference"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        // Embedded read-only HuggingFace token — enables seamless Gemma model downloads.
        // Users never see or enter this; ModelDownloadManager uses it for gated repos.
        //   local.properties  →  hf.app.token=hf_xxxxxxxxxxxxxxxxxxxxxxxx
        //   CI secret         →  env HF_APP_TOKEN=hf_xxxxxxxxxxxxxxxxxxxxxxxx
        val hfAppToken = localProps.getProperty("hf.app.token")
            ?: System.getenv("HF_APP_TOKEN")
            ?: ""
        buildConfigField("String", "HF_APP_TOKEN", "\"$hfAppToken\"")
    }
}

// litertlm-android is compiled with a specific Kotlin metadata version. This
// flag keeps us from being forced into lock-step Kotlin upgrades when its
// metadata is one minor ahead of ours.
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

    // WorkManager + OkHttp: reliable background downloads for 2.5 GB+ model files.
    // Replaces DownloadManager which stalls on Samsung OneUI (Doze pauses the queue).
    // OkHttp Range headers enable resumption from the byte offset if the download
    // is interrupted; WorkManager enqueues the job persistently across reboots.
    implementation(libs.workmanager)
    implementation(libs.okhttp)
}
