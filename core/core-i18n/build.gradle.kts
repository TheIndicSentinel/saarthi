plugins {
    id("saarthi.android.library")
    id("saarthi.hilt")
}

android { namespace = "com.saarthi.core.i18n" }

dependencies {
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)
    implementation(libs.androidx.appcompat)
    implementation(project(":core:core-common"))
}
