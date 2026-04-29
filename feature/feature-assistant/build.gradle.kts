plugins {
    id("saarthi.android.feature")
}

android { namespace = "com.saarthi.feature.assistant" }

dependencies {
    implementation(project(":core:core-inference"))
    implementation(project(":core:core-rag"))
    implementation(libs.coil.compose)
    implementation(libs.accompanist.permissions)
    implementation(libs.mlkit.text.recognition)
}
