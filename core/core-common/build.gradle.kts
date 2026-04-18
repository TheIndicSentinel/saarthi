plugins {
    id("saarthi.android.library")
}

android { namespace = "com.saarthi.core.common" }

dependencies {
    implementation(libs.coroutines.android)
    implementation(libs.timber)
}
