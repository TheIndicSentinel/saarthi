plugins {
    id("saarthi.android.library")
    id("saarthi.android.compose")
}

android { namespace = "com.saarthi.core.ui" }

dependencies {
    implementation(libs.compose.material.icons)
    implementation(libs.accompanist.permissions)
}
