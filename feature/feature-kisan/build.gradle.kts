plugins {
    id("saarthi.android.feature")
}

android { namespace = "com.saarthi.feature.kisan" }

dependencies {
    implementation(project(":core:core-inference"))
    implementation(project(":core:core-rag"))
}
