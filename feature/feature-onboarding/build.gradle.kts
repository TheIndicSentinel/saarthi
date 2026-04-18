plugins {
    id("saarthi.android.feature")
}

android { namespace = "com.saarthi.feature.onboarding" }

dependencies {
    implementation(project(":core:core-inference"))
}
