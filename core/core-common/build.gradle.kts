plugins {
    id("saarthi.android.library")
    id("saarthi.hilt")
}

android { namespace = "com.saarthi.core.common" }

dependencies {
    implementation(libs.coroutines.android)
    implementation(libs.timber)
}
