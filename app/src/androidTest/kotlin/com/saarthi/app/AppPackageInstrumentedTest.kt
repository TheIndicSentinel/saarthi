package com.saarthi.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Minimal instrumented test — its real job is to give the androidTest APK at
 * least one runnable test so the build packages AndroidJUnitRunner and Firebase
 * Test Lab accepts the upload. It deliberately does NOT launch MainActivity:
 * the app gates on model download/onboarding, which would make a launch-based
 * test slow and flaky on a fresh Test Lab device. Asserting the target package
 * is enough to prove the app + test APKs are wired and the runner executes.
 *
 * To run on Firebase Test Lab (Instrumentation):
 *   1. Build both APKs (CI uploads them as the "saarthi-firebase-test-apks"
 *      artifact, or locally):
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
        assertEquals("com.saarthi.app", context.packageName)
    }
}
