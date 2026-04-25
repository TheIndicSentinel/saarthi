plugins {
    id("saarthi.android.library")
    id("saarthi.hilt")
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.saarthi.core.memory"
}

ksp {
    // Exports Room schema JSON files to schemas/ so migrations can be written
    // against a known baseline. Check these files into git alongside schema changes.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)
    implementation(project(":core:core-common"))
}
