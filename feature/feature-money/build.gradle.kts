plugins {
    id("saarthi.android.feature")
}

android { namespace = "com.saarthi.feature.money" }

dependencies {
    implementation(project(":core:core-inference"))
    implementation(project(":core:core-rag"))
    implementation(libs.accompanist.permissions)
}
