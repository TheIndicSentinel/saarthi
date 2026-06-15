package com.saarthi.feature.assistant.data

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Integrity + authenticity check for a downloaded knowledge pack.
 *
 * The pack data is public Govt content, so this is NOT about secrecy — it is
 * about tamper-proofing: only a pack signed by Saarthi's offline key may be
 * installed, and only if its bytes match the manifest's SHA-256.
 *
 * Scheme: ECDSA P-256 (secp256r1) + SHA-256, via the platform java.security
 * provider. Chosen over Ed25519 because Ed25519 in java.security needs API 33+,
 * while this app supports minSdk 28 — and ECDSA P-256 needs NO extra dependency
 * and works on every supported device. Offline-signable with stock openssl.
 *
 * Pure JVM (no Android types) so it is fully unit-testable without an emulator.
 * Every public function is total: malformed input returns false, never throws.
 */
object PackVerifier {

    /**
     * True when [data] was signed by the private key matching
     * [publicKeySpkiBase64] (an X.509 SubjectPublicKeyInfo, base64). [signatureDerBase64]
     * is the DER ECDSA signature, base64. Any malformed input yields false.
     */
    fun verify(data: ByteArray, signatureDerBase64: String, publicKeySpkiBase64: String): Boolean =
        runCatching {
            val keyBytes = Base64.getDecoder().decode(publicKeySpkiBase64.trim())
            val publicKey = KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(keyBytes))
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey)
                update(data)
                verify(Base64.getDecoder().decode(signatureDerBase64.trim()))
            }
        }.getOrDefault(false)

    /** Lowercase hex SHA-256 of [data]. */
    fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }

    /** True when [data]'s SHA-256 equals [expectedHex] (case-insensitive). */
    fun matchesSha256(data: ByteArray, expectedHex: String): Boolean =
        runCatching { sha256Hex(data).equals(expectedHex.trim(), ignoreCase = true) }
            .getOrDefault(false)
}
