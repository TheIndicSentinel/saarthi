package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.KisanPackPreference
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackUpdateCheckerTest {

    private val installer = mockk<KisanPackInstaller>()
    private val preference = mockk<KisanPackPreference>(relaxed = true)
    private val installed = MutableStateFlow(1)

    private fun checker(
        manifestUrl: String = "https://example.com/manifest.json",
        appVersionCode: Int = 10,
        http: PackHttp,
    ): PackUpdateChecker {
        every { preference.installedVersion } returns installed
        coEvery { preference.recordUpdateCheck(any()) } returns Unit
        val remote = object : KisanPackRemoteConfig {
            override val manifestUrl = manifestUrl
            override val publicKeyB64 = "test-key"
            override val appVersionCode = appVersionCode
        }
        return PackUpdateChecker(installer, preference, remote, http)
    }

    private fun manifestJson(
        packVersion: Int = 2,
        minApp: Int = 0,
        url: String = "https://example.com/pack.json",
    ) = """{"packVersion":$packVersion,"minAppVersionCode":$minApp,"pack":{"url":"$url","sha256":"abc","signature":"sig"}}"""

    @Test
    fun `empty manifest URL is unavailable and does not fetch`() = runBlocking {
        var fetched = false
        val http = object : PackHttp {
            override fun getString(url: String): String { fetched = true; error("should not fetch") }
            override fun getBytes(url: String): ByteArray = error("should not fetch")
        }
        val outcome = checker(manifestUrl = "", http = http).checkAndInstall()
        assertEquals(PackUpdateOutcome.Unavailable, outcome)
        assertTrue(!fetched)
    }

    @Test
    fun `already-installed version is up to date`() = runBlocking {
        installed.value = 2
        val http = object : PackHttp {
            override fun getString(url: String) = manifestJson(packVersion = 2)
            override fun getBytes(url: String) = error("should not download pack")
        }
        val outcome = checker(http = http).checkAndInstall()
        assertEquals(PackUpdateOutcome.UpToDate, outcome)
        coVerify(exactly = 0) { installer.installVerified(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `newer manifest version installs after verify`() = runBlocking {
        installed.value = 1
        val packBytes = byteArrayOf(1, 2, 3)
        val http = object : PackHttp {
            override fun getString(url: String) = manifestJson(packVersion = 3)
            override fun getBytes(url: String) = packBytes
        }
        coEvery {
            installer.installVerified(packBytes, "abc", "sig", "test-key", "manifest:v3")
        } returns 3

        val outcome = checker(http = http).checkAndInstall()
        assertEquals(PackUpdateOutcome.Updated(fromVersion = 1, toVersion = 3), outcome)
    }

    @Test
    fun `verify failure keeps the current pack`() = runBlocking {
        val http = object : PackHttp {
            override fun getString(url: String) = manifestJson(packVersion = 9)
            override fun getBytes(url: String) = byteArrayOf(9)
        }
        coEvery { installer.installVerified(any(), any(), any(), any(), any()) } returns null

        val outcome = checker(http = http).checkAndInstall()
        assertEquals(PackUpdateOutcome.KeptCurrent, outcome)
    }

    @Test
    fun `app too old skips install`() = runBlocking {
        val http = object : PackHttp {
            override fun getString(url: String) = manifestJson(packVersion = 5, minApp = 99)
            override fun getBytes(url: String) = error("should not download")
        }
        val outcome = checker(appVersionCode = 10, http = http).checkAndInstall()
        assertEquals(PackUpdateOutcome.AppTooOld, outcome)
    }

    @Test
    fun `manifest fetch failure is network failed`() = runBlocking {
        val http = object : PackHttp {
            override fun getString(url: String) = error("offline")
            override fun getBytes(url: String) = error("offline")
        }
        val outcome = checker(http = http).checkAndInstall()
        assertEquals(PackUpdateOutcome.NetworkFailed, outcome)
    }

    @Test
    fun `parseManifest reads packVersion and pack fields`() {
        val parsed = PackUpdateChecker.parseManifest(manifestJson(packVersion = 4, minApp = 2))
        assertEquals(4, parsed.packVersion)
        assertEquals(2, parsed.minAppVersionCode)
        assertEquals("abc", parsed.sha256)
        assertEquals("sig", parsed.signature)
    }
}
