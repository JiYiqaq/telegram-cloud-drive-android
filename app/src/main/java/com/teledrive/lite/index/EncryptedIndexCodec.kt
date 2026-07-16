package com.teledrive.lite.index

import com.teledrive.lite.crypto.CloudIndexEnvelopeCryptor
import com.teledrive.lite.crypto.KeyDerivation
import com.teledrive.lite.crypto.KeyDerivationParameters
import com.teledrive.lite.util.SecureErase

enum class EncryptedIndexCodecFailure {
    KEY_DERIVATION_MISMATCH,
}

class EncryptedIndexCodecException(
    val failure: EncryptedIndexCodecFailure,
) : IllegalArgumentException(failure.name)

/** Binds the authenticated envelope KDF header to the same metadata inside the index payload. */
class EncryptedIndexCodec(
    private val envelopeCryptor: CloudIndexEnvelopeCryptor,
) {
    fun encrypt(
        payload: CloudIndexPayload,
        masterKey: ByteArray,
        keyDerivation: KeyDerivationParameters,
    ): ByteArray {
        requireMatching(payload.keyDerivation, keyDerivation)
        val plaintext = IndexCodec.encode(payload)
        return try {
            envelopeCryptor.encrypt(plaintext, masterKey, keyDerivation)
        } finally {
            SecureErase.wipe(plaintext)
        }
    }

    fun decryptWithMasterKey(
        envelope: ByteArray,
        masterKey: ByteArray,
        expectedKeyDerivation: KeyDerivationParameters,
    ): CloudIndexPayload {
        val outerMetadata = envelopeCryptor.inspect(envelope).keyDerivation
        if (!matches(outerMetadata, expectedKeyDerivation)) mismatch()
        val plaintext = envelopeCryptor.decrypt(envelope, masterKey)
        return try {
            IndexCodec.decode(plaintext).also { payload ->
                requireMatching(payload.keyDerivation, outerMetadata)
            }
        } finally {
            SecureErase.wipe(plaintext)
        }
    }

    fun decryptWithPassword(envelope: ByteArray, password: CharArray): CloudIndexPayload {
        val outerMetadata = envelopeCryptor.inspect(envelope).keyDerivation
        val masterKey = KeyDerivation.derive(password, outerMetadata)
        return try {
            decryptWithMasterKey(envelope, masterKey, outerMetadata)
        } finally {
            SecureErase.wipe(masterKey)
        }
    }

    private fun requireMatching(
        payloadMetadata: IndexKeyDerivationMetadata,
        expected: KeyDerivationParameters,
    ) {
        val payloadSalt = payloadMetadata.salt.toByteArray()
        val expectedSalt = expected.salt
        try {
            if (
                payloadMetadata.algorithm != expected.algorithm ||
                !payloadSalt.contentEquals(expectedSalt) ||
                payloadMetadata.iterations != expected.iterations ||
                payloadMetadata.keyLengthBytes != expected.keyLengthBytes
            ) {
                mismatch()
            }
        } finally {
            SecureErase.wipe(payloadSalt)
            SecureErase.wipe(expectedSalt)
        }
    }

    private fun matches(first: KeyDerivationParameters, second: KeyDerivationParameters): Boolean {
        val firstSalt = first.salt
        val secondSalt = second.salt
        return try {
            first.algorithm == second.algorithm &&
                first.iterations == second.iterations &&
                first.keyLengthBytes == second.keyLengthBytes &&
                firstSalt.contentEquals(secondSalt)
        } finally {
            SecureErase.wipe(firstSalt)
            SecureErase.wipe(secondSalt)
        }
    }

    private fun mismatch(): Nothing = throw EncryptedIndexCodecException(
        EncryptedIndexCodecFailure.KEY_DERIVATION_MISMATCH,
    )
}
