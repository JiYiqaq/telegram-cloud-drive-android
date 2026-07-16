package com.teledrive.lite.sync

import com.teledrive.lite.crypto.CloudIndexEnvelopeCryptor
import com.teledrive.lite.crypto.CryptoEngine
import com.teledrive.lite.crypto.KeyDerivation
import com.teledrive.lite.crypto.KeyDerivationParameters
import com.teledrive.lite.index.AesGcmParameters
import com.teledrive.lite.index.CloudIndexPayload
import com.teledrive.lite.index.EncryptedIndexCodec
import com.teledrive.lite.index.IndexBytes
import com.teledrive.lite.index.IndexEncryptionParameters
import com.teledrive.lite.index.IndexFolder
import com.teledrive.lite.index.IndexKeyDerivationMetadata
import com.teledrive.lite.repository.CloudCacheSnapshot
import com.teledrive.lite.util.SecureErase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

class IndexRecoveryServiceTest {
    @Test
    fun replacesCacheOnlyAfterSecondExactPinnedRead() = runBlocking {
        val fixture = fixture(messageId = 71)
        val remote = FakeRecoveryRemote(fixture.document, fixture.envelope)
        val cache = RecordingCacheReplacer()

        val outcome = service(remote, cache).recover(PASSWORD.copyOf())

        assertEquals(IndexRecoveryOutcome.Recovered(1L, 71L, "remote-index"), outcome)
        assertEquals(2, remote.pinnedReads)
        assertNotNull(cache.snapshot)
        assertEquals(71L, cache.snapshot?.indexState?.currentIndexMessageId)
    }

    @Test
    fun selfMessageMismatchNeverTouchesLocalCache() {
        val fixture = fixture(messageId = 72)
        val remote = FakeRecoveryRemote(
            fixture.document.copy(messageId = 71),
            fixture.envelope,
        )
        val cache = RecordingCacheReplacer()

        val failure = assertThrows(IndexRecoveryException::class.java) {
            runBlocking { service(remote, cache).recover(PASSWORD.copyOf()) }
        }

        assertEquals(IndexRecoveryFailure.PAYLOAD_POINTER_MISMATCH, failure.failure)
        assertEquals(null, cache.snapshot)
    }

    @Test
    fun changedPinOrWrongPasswordNeverTouchesLocalCache() {
        val fixture = fixture(messageId = 71)
        val changed = fixture.document.copy(messageId = 99, fileId = "other")
        val remote = FakeRecoveryRemote(fixture.document, fixture.envelope, changed)
        val cache = RecordingCacheReplacer()
        val changedFailure = assertThrows(IndexRecoveryException::class.java) {
            runBlocking { service(remote, cache).recover(PASSWORD.copyOf()) }
        }
        assertEquals(IndexRecoveryFailure.PIN_CHANGED_DURING_RECOVERY, changedFailure.failure)
        assertEquals(null, cache.snapshot)

        val wrongPasswordFailure = assertThrows(IndexRecoveryException::class.java) {
            runBlocking {
                service(FakeRecoveryRemote(fixture.document, fixture.envelope), cache)
                    .recover("wrong".toCharArray())
            }
        }
        assertEquals(IndexRecoveryFailure.DECRYPTION_FAILED, wrongPasswordFailure.failure)
        assertEquals(null, cache.snapshot)
    }

    @Test
    fun rebindsValidatedRemoteKeyBeforeReplacingCache() = runBlocking {
        val fixture = fixture(messageId = 71)
        val order = mutableListOf<String>()
        val cache = object : IndexCacheReplacer {
            override suspend fun replace(snapshot: CloudCacheSnapshot) {
                order += "cache"
            }
        }
        val committer = RecoveryContextCommitter { parameters, masterKey ->
            assertEquals(KeyDerivation.ALGORITHM, parameters.algorithm)
            assertEquals(KeyDerivation.MASTER_KEY_BYTES, masterKey.size)
            order += "key"
        }

        service(FakeRecoveryRemote(fixture.document, fixture.envelope), cache, committer)
            .recover(PASSWORD.copyOf())

        assertEquals(listOf("key", "cache"), order)
    }

    private fun service(
        remote: IndexRecoveryRemote,
        cache: IndexCacheReplacer,
        committer: RecoveryContextCommitter? = null,
    ) = IndexRecoveryService(
        remote = remote,
        encryptedIndexCodec = EncryptedIndexCodec(CloudIndexEnvelopeCryptor(CryptoEngine())),
        cacheReplacer = cache,
        contextCommitter = committer,
        clock = { 3_000 },
    )

    private fun fixture(messageId: Long): Fixture {
        val parameters = KeyDerivationParameters.pbkdf2(ByteArray(16) { it.toByte() }, 1)
        val payload = CloudIndexPayload(
            schema = CloudIndexPayload.SCHEMA,
            formatVersion = 1,
            appVersion = "0.1.0",
            revision = 1,
            currentIndexMessageId = messageId,
            previous = null,
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 2,
            rootFolderId = "root",
            keyDerivation = IndexKeyDerivationMetadata(
                parameters.algorithm,
                IndexBytes.of(parameters.salt),
                parameters.iterations,
                parameters.keyLengthBytes,
            ),
            encryptionParameters = IndexEncryptionParameters(AES, AES),
            folders = listOf(IndexFolder("root", "TeleDrive", null, 1, 1)),
            files = emptyList(),
            chunks = emptyList(),
            pendingOperations = emptyList(),
        )
        val masterKey = KeyDerivation.derive(PASSWORD, parameters)
        val envelope = try {
            EncryptedIndexCodec(CloudIndexEnvelopeCryptor(CryptoEngine()))
                .encrypt(payload, masterKey, parameters)
        } finally {
            SecureErase.wipe(masterKey)
        }
        return Fixture(
            document = RemoteIndexDocument(
                messageId,
                "remote-index",
                IndexAtomicUpdater.INDEX_FILE_NAME,
                envelope.size.toLong(),
            ),
            envelope = envelope,
        )
    }

    private class FakeRecoveryRemote(
        private val first: RemoteIndexDocument,
        private val bytes: ByteArray,
        private val second: RemoteIndexDocument = first,
    ) : IndexRecoveryRemote {
        var pinnedReads = 0
        override suspend fun getPinned(): RemoteIndexDocument {
            pinnedReads += 1
            return if (pinnedReads == 1) first else second
        }

        override suspend fun download(document: RemoteIndexDocument): ByteArray = bytes.copyOf()
    }

    private class RecordingCacheReplacer : IndexCacheReplacer {
        var snapshot: CloudCacheSnapshot? = null
        override suspend fun replace(snapshot: CloudCacheSnapshot) {
            this.snapshot = snapshot
        }
    }

    private data class Fixture(
        val document: RemoteIndexDocument,
        val envelope: ByteArray,
    )

    private companion object {
        val PASSWORD = "correct horse".toCharArray()
        val AES = AesGcmParameters("AES-256-GCM", 1, 12, 128, 256)
    }
}
