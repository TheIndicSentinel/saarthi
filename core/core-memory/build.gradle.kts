plugins {
    id("saarthi.android.library")
    id("saarthi.hilt")
    alias(libs.plugins.ksp)
}

android { namespace = "com.saarthi.core.memory" }

dependencies {
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)
    implementation(project(":core:core-common"))
}
