package com.teledrive.lite.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class KeystoreCipher(
    keyAlias: String,
) : SecretCipher {
    private val delegate = AesGcmSecretCipher(AndroidKeystoreKeyProvider(keyAlias))

    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray =
        delegate.encrypt(plaintext, associatedData)

    override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray =
        delegate.decrypt(ciphertext, associatedData)

    override fun deleteKey() {
        delegate.deleteKey()
    }
}

private class AndroidKeystoreKeyProvider(
    private val alias: String,
) : SecretKeyProvider {
    init {
        require(alias.isNotBlank())
    }

    @Synchronized
    override fun getOrCreate(): SecretKey {
        val keyStore = loadKeyStore()
        val existing = keyStore.getKey(alias, null)
        if (existing != null) {
            return existing as? SecretKey
                ?: error("Android Keystore alias does not contain a secret key")
        }
        return KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        ).apply {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
        }.generateKey()
    }

    @Synchronized
    override fun delete() {
        val keyStore = loadKeyStore()
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
        }
    }

    private fun loadKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
