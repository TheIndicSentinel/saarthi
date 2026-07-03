import java.util.Properties

plugins {
    id("saarthi.android.application")
    id("saarthi.android.compose")
    id("saarthi.hilt")
}


android {
    namespace = "com.saarthi.app"
    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        applicationId = "com.saarthi.app"
        versionCode = 31
        versionName = "1.0.30"
        // Instrumentation runner for the androidTest APK (Firebase Test Lab /
        // `connectedAndroidTest`). The app convention plugin doesn't set this
        // — only the library one did — so the generated test APK referenced a
        // runner it didn't package, which is the upload error Firebase showed.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Kisan knowledge-pack manifest URL. Defaults to the public
        // saarthi-packs GitHub Releases "latest" manifest, so the signed
        // update channel works in a stock build. Until the first signed
        // release is published the URL simply 404s and PackUpdateWorker
        // backs off (Result.retry) — harmless. Override via:
        //   local.properties → kisan.pack.manifest.url=https://…/manifest.json
        //   CI env           → KISAN_PACK_MANIFEST_URL=https://…/manifest.json
        val kisanManifestUrl = System.getenv("KISAN_PACK_MANIFEST_URL")
            ?: (rootProject.file("local.properties").takeIf { it.exists() }?.let { f ->
                Properties().apply { f.inputStream().use { load(it) } }
                    .getProperty("kisan.pack.manifest.url")
            })
            ?: "https://github.com/TheIndicSentinel/saarthi-packs/releases/latest/download/manifest.json"
        buildConfigField("String", "KISAN_PACK_MANIFEST_URL", "\"$kisanManifestUrl\"")

        // ECDSA P-256 (secp256r1) public key — X.509 SPKI, base64 — used to
        // verify the signature of every downloaded Kisan pack before install.
        // The matching PRIVATE key is held OFFLINE by the maintainer and is
        // never in the repo. Rotating the key = change this value + re-sign.
        buildConfigField(
            "String",
            "KISAN_PACK_PUBLIC_KEY",
            "\"MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAET92OpbPOpjrzlo6eOURyOtsbxfpBPElsQcCo8hce3VBfpKLTkCr7szYyMiifJE/Mko/ZCwqvqFOpk7hfdyia9Q==\"",
        )

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
        // STABLE debug signature. By default Gradle signs debug builds with an
        // auto-generated ~/.android/debug.keystore — on ephemeral CI runners
        // that key is REGENERATED every run, so each CI-built Saarthi.apk had a
        // different signature and Android refused to install it over the
        // previous one ("app doesn't install", forcing an uninstall that loses
        // the 2.5GB downloaded model). Pointing debug signing at the committed
        // ci/debug.keystore (standard debug credentials — not a secret, cannot
        // publish to Play) makes every debug/test build signature-identical,
        // so updates install in place over the previous build.
        getByName("debug") {
            storeFile = rootProject.file("ci/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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
    // WorkManager — used by PackUpdateWorker to periodically refresh
    // the Kisan knowledge pack on UNMETERED Wi-Fi.
    implementation(libs.workmanager)

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

    // Instrumentation tests — packaged into app-debug-androidTest.apk, the
    // "test APK" uploaded to Firebase Test Lab. runner provides
    // AndroidJUnitRunner (the class the manifest references); ext-junit adds
    // AndroidJUnit4 + ActivityScenario; espresso-core is the standard UI-test
    // toolkit for any future on-device tests.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
