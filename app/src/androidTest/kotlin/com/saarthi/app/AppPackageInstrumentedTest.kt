package com.saarthi.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Minimal instrumented smoke — proves the app + androidTest APKs wire
 * AndroidJUnitRunner so Firebase Test Lab (and `connectedDebugAndroidTest`)
 * can run. Deliberately does NOT launch MainActivity: onboarding/model
 * download would make a launch-based test slow and flaky on a fresh device.
 *
 * Asserts [BuildConfig.APPLICATION_ID] (Play install id), not [BuildConfig]'s
 * Java/Kotlin namespace (`com.saarthi.app`). Those diverged when applicationId
 * became `com.indicsentinel.saarthi` — see `app/build.gradle.kts`. Hard-coding
 * the old namespace made this smoke fail on every real install (Point 7).
 *
 * To run on Firebase Test Lab (Instrumentation):
 *   1. Build both APKs:
 *        ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
 *   2. Upload:
 *        app    → app/build/outputs/apk/debug/app-debug.apk
 *        test   → app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 */
@RunWith(AndroidJUnit4::class)
class AppPackageInstrumentedTest {

    @Test
    fun app_targetContext_hasExpectedPackageName() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(BuildConfig.APPLICATION_ID, context.packageName)
    }
}
