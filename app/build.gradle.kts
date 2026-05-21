plugins {
    id("saarthi.android.application")
    id("saarthi.android.compose")
    id("saarthi.hilt")
}

// Apply Firebase plugins ONLY when google-services.json is provided. Drop the
// file into app/ and Crashlytics auto-activates on the next build.
val firebaseConfigured = file("google-services.json").exists()
if (firebaseConfigured) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
    logger.lifecycle("[Saarthi] Firebase Crashlytics ENABLED")
} else {
    logger.lifecycle("[Saarthi] Firebase Crashlytics DISABLED (no app/google-services.json)")
}

android {
    namespace = "com.saarthi.app"
    defaultConfig {
        applicationId = "com.saarthi.app"
        versionCode = 25
        versionName = "1.0.24"

        // Ship only arm64-v8a. Every Android 7.0+ device that can run a
        // 1B+ on-device LLM has a 64-bit ARM CPU — keeping armeabi-v7a /
        // x86 / x86_64 in the APK adds JNI overhead with zero real users.
        // Saves ~30% of the JNI section, which on litertlm-android is the
        // largest non-model component of the APK.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH")
            val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
            val keyAlias = System.getenv("KEY_ALIAS")
            val keyPassword = System.getenv("KEY_PASSWORD")
            if (keystorePath != null && keystorePassword != null && keyAlias != null && keyPassword != null) {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            // R8 shrinking + obfuscation. Hilt, Compose, Coil, Room and
            // Timber all ship consumer-rules.pro that handle their own
            // keep rules — we only need our own proguard-rules.pro for
            // litertlm JNI surfaces and Kotlin reflection on data classes.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName(
                if (System.getenv("KEYSTORE_PATH") != null) "release" else "debug"
            )
        }
        debug {
            // Keep debug fast — no R8, no obfuscation. Same APK we ship to
            // testers via debug-apk artifact.
            isMinifyEnabled = false
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
            // Drop debug symbols from .so files — we don't symbolicate
            // native crashes for the litertlm runtime; Google does that
            // upstream. Saves several MB on the litertlm shared library.
            keepDebugSymbols.clear()
        }
        resources {
            // De-duplicate META-INF entries that cause merger warnings on
            // assembleRelease and add nothing to runtime behaviour.
            excludes += listOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/*.kotlin_module",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.navigation.compose)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.timber)

    // Core modules
    implementation(project(":core:core-common"))
    implementation(project(":core:core-ui"))
    implementation(project(":core:core-inference"))
    implementation(project(":core:core-memory"))
    implementation(project(":core:core-i18n"))
    implementation(project(":core:core-rag"))

    // Feature modules
    implementation(project(":feature:feature-onboarding"))
    implementation(project(":feature:feature-assistant"))

    // Firebase Crashlytics — only declared when google-services.json is present.
    // Listing them outside this guard would force every contributor to provide
    // a Firebase config or the build would fail at the AAR resolution step.
    if (firebaseConfigured) {
        implementation(platform(libs.firebase.bom))
        implementation(libs.firebase.crashlytics)
        implementation(libs.firebase.analytics)
    }
}
