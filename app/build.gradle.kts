plugins {
    id("saarthi.android.application")
    id("saarthi.android.compose")
    id("saarthi.hilt")
}

android {
    namespace = "com.saarthi.app"
    defaultConfig {
        applicationId = "com.saarthi.app"
        versionCode = 1
        versionName = "1.0.0"
    }
    // libc++_shared.so must be packaged when using c++_shared STL with JNI
    packaging {
        jniLibs {
            useLegacyPackaging = false
            keepDebugSymbols += "**/*.so"
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
    implementation(project(":feature:feature-money"))
    implementation(project(":feature:feature-kisan"))
    implementation(project(":feature:feature-knowledge"))
    implementation(project(":feature:feature-fieldexpert"))
}
