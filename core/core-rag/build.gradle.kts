plugins {
    id("saarthi.android.library")
    id("saarthi.hilt")
}

android { namespace = "com.saarthi.core.rag" }

dependencies {
    implementation(libs.coroutines.android)
    implementation(project(":core:core-common"))
    implementation(project(":core:core-inference"))
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")
    implementation(libs.timber)
}
