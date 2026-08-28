import java.util.Base64
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
        // Embedded read-only HuggingFace token — enables seamless download of the
        // gated Gemma 3n repos (google/gemma-3n-*). The litert-community/* repos
        // are public and need no auth; only the two google/* entries in
        // ModelCatalog actually require a token.
        //   local.properties  →  hf.app.token=hf_xxxxxxxxxxxxxxxxxxxxxxxx
        //   CI secret         →  env HF_APP_TOKEN=hf_xxxxxxxxxxxxxxxxxxxxxxxx
        //
        // SECURITY: do NOT embed the raw token as a plaintext BuildConfig String
        // constant — R8 inlines String constants into the dex, so `strings <apk>`
        // trivially recovers an `hf_...`-prefixed secret. Store a Base64-encoded
        // form and decode at runtime (HuggingFaceTokenManager.embeddedAppToken).
        // This is defense-in-depth against trivial STATIC extraction only; it is
        // not full remediation (a runtime attacker can still recover the token).
        // The complete fix (per-user token / short-lived proxy) is tracked
        // separately. Empty stays empty → no Authorization header is sent.
        val hfAppToken = localProps.getProperty("hf.app.token")
            ?: System.getenv("HF_APP_TOKEN")
            ?: ""
        val hfAppTokenB64 = if (hfAppToken.isEmpty()) {
            ""
        } else {
            Base64.getEncoder().encodeToString(hfAppToken.toByteArray(Charsets.UTF_8))
        }
        buildConfigField("String", "HF_APP_TOKEN_B64", "\"$hfAppTokenB64\"")
    }

    // Debug log → saarthi_debug.log is how we diagnose RAG (path, boost,
    // heading, scores) on a physical phone. It ALWAYS lands app-private by
    // default now (both debug and release) — readable via adb / Android Studio
    // and attachable through the Support screen, but never written to the
    // world-readable public Downloads folder where other apps could read
    // filenames / response previews / device info. A developer who needs the
    // file in public Downloads (e.g. a non-technical beta tester grabbing it
    // with a file manager while onboarding is stuck) opts in explicitly per
    // build with -Psaarthi.publicLog=true.
    buildTypes {
        named("debug") {
            val publicLog = (project.findProperty("saarthi.publicLog") as String?)?.toBoolean() ?: false
            buildConfigField("boolean", "PUBLIC_DEBUG_LOG", "$publicLog")
        }
        named("release") {
            val publicLog = (project.findProperty("saarthi.publicLog") as String?)?.toBoolean() ?: false
            buildConfigField("boolean", "PUBLIC_DEBUG_LOG", "$publicLog")
        }
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
    implementation(project(":core:core-i18n"))
    implementation(libs.timber)
    // FileProvider — DebugLogger.shareableUri() wraps the app-private log
    // file (production builds, and the Android-9 fallback) so it can be
    // attached to the Support screen's "email us" intent.
    implementation(libs.androidx.core.ktx)

    // LiteRT-LM: Google AI Edge inference library (same runtime as AI Edge Gallery)
    implementation(libs.litertlm)

    // WorkManager + OkHttp: reliable background downloads for 2.5 GB+ model files.
    // Replaces DownloadManager which stalls on Samsung OneUI (Doze pauses the queue).
    // OkHttp Range headers enable resumption from the byte offset if the download
    // is interrupted; WorkManager enqueues the job persistently across reboots.
    implementation(libs.workmanager)
    implementation(libs.okhttp)
}
