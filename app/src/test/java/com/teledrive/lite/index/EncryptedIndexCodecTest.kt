package com.teledrive.lite.index

import com.teledrive.lite.crypto.CloudIndexEnvelopeCryptor
import com.teledrive.lite.crypto.CryptoEngine
import com.teledrive.lite.crypto.KeyDerivationParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EncryptedIndexCodecTest {
    private val cryptor = CloudIndexEnvelopeCryptor(CryptoEngine())
    private val codec = EncryptedIndexCodec(cryptor)

    @Test
    fun roundTripRequiresOuterAndInnerKdfMetadataToMatch() {
        val parameters = parameters(1)
        val masterKey = ByteArray(32) { (it + 3).toByte() }
        val payload = payload(parameters)

        val envelope = codec.encrypt(payload, masterKey, parameters)
        val restored = codec.decryptWithMasterKey(envelope, masterKey, parameters)

        assertEquals(payload, restored)
    }

    @Test
    fun encryptionRejectsAKeyDerivationDescriptorDifferentFromPayload() {
        val payloadParameters = parameters(1)
        val localParameters = parameters(2)

        val failure = assertThrows(EncryptedIndexCodecException::class.java) {
            codec.encrypt(payload(payloadParameters), ByteArray(32), localParameters)
        }

        assertEquals(EncryptedIndexCodecFailure.KEY_DERIVATION_MISMATCH, failure.failure)
    }

    @Test
    fun decryptionRejectsAuthenticatedOuterMetadataThatDiffersFromPlaintextMetadata() {
        val outerParameters = parameters(1)
        val innerParameters = parameters(2)
        val masterKey = ByteArray(32) { it.toByte() }
        val envelope = cryptor.encrypt(
            IndexCodec.encode(payload(innerParameters)),
            masterKey,
            outerParameters,
        )

        val failure = assertThrows(EncryptedIndexCodecException::class.java) {
            codec.decryptWithMasterKey(envelope, masterKey, outerParameters)
        }

        assertEquals(EncryptedIndexCodecFailure.KEY_DERIVATION_MISMATCH, failure.failure)
    }

    private fun parameters(seed: Int) = KeyDerivationParameters.pbkdf2(
        salt = ByteArray(16) { (it + seed).toByte() },
        iterations = 600_000,
    )

    private fun payload(parameters: KeyDerivationParameters) = CloudIndexPayload(
        schema = CloudIndexPayload.SCHEMA,
        formatVersion = CloudIndexPayload.CURRENT_FORMAT_VERSION,
        appVersion = "0.1.0",
        revision = 1,
        currentIndexMessageId = 71,
        previous = null,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 2,
        rootFolderId = "root",
        keyDerivation = IndexKeyDerivationMetadata(
            algorithm = parameters.algorithm,
            salt = IndexBytes.of(parameters.salt),
            iterations = parameters.iterations,
            keyLengthBytes = parameters.keyLengthBytes,
        ),
        encryptionParameters = IndexEncryptionParameters(AES, AES),
        folders = listOf(IndexFolder("root", "TeleDrive", null, 1, 1)),
        files = emptyList(),
        chunks = emptyList(),
        pendingOperations = emptyList(),
    )

    private companion object {
        val AES = AesGcmParameters("AES-256-GCM", 1, 12, 128, 256)
    }
}
