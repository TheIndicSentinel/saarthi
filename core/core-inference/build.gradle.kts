plugins {
    id("saarthi.android.library")
    id("saarthi.hilt")
}

android { namespace = "com.saarthi.core.inference" }

dependencies {
    implementation(libs.mediapipe.tasks.genai)
    implementation(libs.coroutines.android)
    implementation(libs.datastore.preferences)
    implementation(project(":core:core-common"))
    implementation(libs.timber)
}
