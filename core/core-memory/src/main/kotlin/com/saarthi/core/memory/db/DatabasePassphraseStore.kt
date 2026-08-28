package com.saarthi.core.memory.db

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device-bound wrap of the SQLCipher passphrase.
 *
 * 32 random bytes are AES-GCM-encrypted with an Android Keystore key and
 * stored under [Context.getNoBackupFilesDir] (Auto Backup is already off).
 * The raw passphrase never leaves the phone and is never logged.
 */
@Singleton
class DatabasePassphraseStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    fun getOrCreateHexPassphrase(): String = SqliteFileFormat.toHex(getOrCreateRaw())

    private fun getOrCreateRaw(): ByteArray {
        val wrapFile = File(context.noBackupFilesDir, WRAP_FILE)
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val secret = secretKey(keyStore)
        if (wrapFile.isFile && wrapFile.length() > IV_BYTES) {
            return unwrap(wrapFile.readBytes(), secret)
        }
        val raw = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        wrapFile.parentFile?.mkdirs()
        wrapFile.writeBytes(wrap(raw, secret))
        return raw
    }

    private fun secretKey(keyStore: KeyStore): SecretKey {
        if (keyStore.containsAlias(ALIAS)) {
            val entry = keyStore.getEntry(ALIAS, null) as KeyStore.SecretKeyEntry
            return entry.secretKey
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun wrap(raw: ByteArray, secret: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secret)
        val iv = cipher.iv
        require(iv.size == IV_BYTES) { "unexpected GCM IV length ${iv.size}" }
        val ciphertext = cipher.doFinal(raw)
        return iv + ciphertext
    }

    private fun unwrap(blob: ByteArray, secret: SecretKey): ByteArray {
        require(blob.size > IV_BYTES) { "wrapped key file is truncated" }
        val iv = blob.copyOfRange(0, IV_BYTES)
        val ciphertext = blob.copyOfRange(IV_BYTES, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "saarthi_sqlcipher_wrap"
        const val WRAP_FILE = "saarthi_db_key.bin"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BYTES = 32
        const val IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
