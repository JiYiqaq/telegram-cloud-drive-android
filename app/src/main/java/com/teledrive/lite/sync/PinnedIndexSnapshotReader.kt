package com.teledrive.lite.sync

import com.teledrive.lite.index.CloudIndexPayloadMapper
import com.teledrive.lite.index.EncryptedIndexCodec
import com.teledrive.lite.repository.CloudCacheSnapshot
import com.teledrive.lite.settings.SetupCryptoContext
import com.teledrive.lite.util.SecureErase

class PinnedIndexSnapshotReader(
    private val remote: IndexRecoveryRemote,
    private val encryptedIndexCodec: EncryptedIndexCodec,
    private val cryptoContextProvider: () -> SetupCryptoContext?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun readExpected(stableState: StableIndexState): CloudCacheSnapshot {
        require(stableState.revision > 0)
        val pinned = remote.getPinned()
            ?: throw IllegalStateException("Pinned index is missing")
        require(
            pinned.messageId == stableState.messageId &&
                pinned.fileId == stableState.fileId,
        ) { "Pinned index does not match the confirmed local pointer" }
        val envelope = remote.download(pinned)
        try {
            val context = cryptoContextProvider()
                ?: throw IllegalStateException("Crypto context is unavailable")
            val payload = context.use {
                context.withMasterKey { masterKey ->
                    encryptedIndexCodec.decryptWithMasterKey(
                        envelope,
                        masterKey,
                        context.keyDerivation,
                    )
                }
            }
            require(
                payload.revision == stableState.revision &&
                    payload.currentIndexMessageId == pinned.messageId,
            ) { "Pinned index payload pointer mismatch" }
            val snapshot = CloudIndexPayloadMapper.toCloudCacheSnapshot(
                payload = payload,
                currentIndexFileId = pinned.fileId,
                syncedAtEpochMillis = clock(),
            )
            require(remote.getPinned() == pinned) { "Pinned index changed during verification" }
            return snapshot
        } finally {
            SecureErase.wipe(envelope)
        }
    }
}
