package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures are real: generated with stock openssl (EC prime256v1) over the exact
 * [MESSAGE] bytes, so this proves the verifier against the same toolchain the
 * offline pack signer will use:
 *
 *   openssl ecparam -name prime256v1 -genkey -noout -out pk.pem
 *   openssl ec -in pk.pem -pubout -outform DER | base64           # PUBLIC_KEY
 *   printf '%s' '<MESSAGE>' | openssl dgst -sha256 -sign pk.pem | base64   # SIGNATURE
 */
class PackVerifierTest {

    private val MESSAGE = "{\"packId\":\"kisan\",\"packVersion\":8}"

    private val PUBLIC_KEY =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEgWp+v9mlkNQHG/j9Hsi04/KyOt7BR6syIM7" +
            "RL2mnl2QwDGL6dUa7kfgaoooHfsVkLhJuHjhuFeY8ds7m9kJLSw=="

    private val SIGNATURE =
        "MEUCIDlBVHB71DDH9rUP+7X1hxgN+FWT2JfbbnVfk5GdT6aIAiEA/tAZO71vR/f86mL3Oya4" +
            "536Y/nAkb+wDO+kqlvYAHtI="

    private val SHA256 = "a49910850064305c910c98a4a8d68ade8e1c08d5231bc60722c34f5b5a734676"

    @Test
    fun `valid signature verifies`() {
        assertTrue(PackVerifier.verify(MESSAGE.toByteArray(), SIGNATURE, PUBLIC_KEY))
    }

    @Test
    fun `tampered data is rejected`() {
        assertFalse(PackVerifier.verify("$MESSAGE ".toByteArray(), SIGNATURE, PUBLIC_KEY))
    }

    @Test
    fun `wrong public key is rejected`() {
        // A genuinely different P-256 key (also openssl-generated) must not
        // validate a signature made by the first key.
        val otherKey =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEjXhI8AAdM/bkC70a9mVN02ih+1sRwbIwT" +
                "sNETCcwwBiD4mh4+GeKX7ia/ZUCttDQ/R5uX8tBMeKQJ6u7FRanRg=="
        assertFalse(PackVerifier.verify(MESSAGE.toByteArray(), SIGNATURE, otherKey))
    }

    @Test
    fun `malformed inputs return false instead of throwing`() {
        assertFalse(PackVerifier.verify(MESSAGE.toByteArray(), "not-base64!!", PUBLIC_KEY))
        assertFalse(PackVerifier.verify(MESSAGE.toByteArray(), SIGNATURE, "not-a-key"))
        assertFalse(PackVerifier.matchesSha256(MESSAGE.toByteArray(), "xyz"))
    }

    @Test
    fun `sha256 matches the openssl digest`() {
        assertEquals(SHA256, PackVerifier.sha256Hex(MESSAGE.toByteArray()))
        assertTrue(PackVerifier.matchesSha256(MESSAGE.toByteArray(), SHA256))
        assertTrue(PackVerifier.matchesSha256(MESSAGE.toByteArray(), SHA256.uppercase()))
    }
}
