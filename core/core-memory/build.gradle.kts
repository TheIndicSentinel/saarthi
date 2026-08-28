plugins {
    id("saarthi.android.library")
    id("saarthi.hilt")
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.saarthi.core.memory"
    sourceSets {
        // Room MigrationTestHelper reads exported schema JSON from androidTest assets.
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

ksp {
    // Exports Room schema JSON files to schemas/ so migrations can be written
    // against a known baseline. Check these files into git alongside schema changes.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.sqlcipher.android)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)
    implementation(project(":core:core-common"))

    // Real SQLite engine for Migration tests — see SaarthiDatabaseMigrationTest.
    testImplementation(libs.sqlite.jdbc)

    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
