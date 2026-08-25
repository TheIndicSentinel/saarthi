package com.saarthi.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented smokes — prove the debug APK + androidTest APK wire
 * AndroidJUnitRunner so Firebase Test Lab (and `connectedDebugAndroidTest`)
 * can run. Deliberately does NOT launch MainActivity: onboarding/model
 * download would make a launch-based test slow and flaky on a fresh device.
 *
 * Asserts [BuildConfig.APPLICATION_ID] (Play install id), not [BuildConfig]'s
 * Java/Kotlin namespace (`com.saarthi.app`). Those diverged when applicationId
 * became `com.indicsentinel.saarthi` — see `app/build.gradle.kts`.
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

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun app_targetContext_hasExpectedPackageName() {
        assertEquals(BuildConfig.APPLICATION_ID, context.packageName)
    }

    @Test
    fun application_class_is_saarthiApp() {
        assertTrue(
            "android:name must install SaarthiApp (Hilt @HiltAndroidApp)",
            context.applicationContext is SaarthiApp,
        )
    }

    @Test
    fun fileProvider_authority_is_registered() {
        val authority = "${context.packageName}.fileprovider"
        val provider = context.packageManager.resolveContentProvider(authority, 0)
        assertNotNull(
            "Settings export / Support log share need $authority",
            provider,
        )
        assertEquals("androidx.core.content.FileProvider", provider!!.name)
        assertFalse("FileProvider must stay unexported", provider.exported)
    }

    @Test
    fun leftover_broad_permissions_are_absent() {
        @Suppress("DEPRECATION")
        val requested = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.toSet()
            .orEmpty()

        val forbidden = listOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
        )
        val present = forbidden.filter { it in requested }
        assertTrue(
            "Merged manifest still declares leftover permissions: $present",
            present.isEmpty(),
        )
    }

    @Test
    fun attach_demo_document_penalty_question_retrieves_a_hit() {
        val hits = com.saarthi.core.rag.Bm25Retriever.rank(
            listOf(com.saarthi.feature.assistant.data.DemoDocument.TEXT),
            com.saarthi.feature.assistant.data.DemoDocument.SUGGESTED_QUESTIONS.first(),
            3,
        )
        assertTrue("demo DPDP sample must BM25-match its suggested penalty question", hits.isNotEmpty())
        assertTrue(hits.first().score > 0.0)
        assertTrue(
            com.saarthi.feature.assistant.data.DemoDocument.TEXT.contains("250"),
        )
    }
}
