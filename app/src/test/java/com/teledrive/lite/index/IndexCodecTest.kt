package com.teledrive.lite.index

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexCodecTest {
    @Test
    fun completePayloadRoundTripsWithoutPlaintextCredentialFields() {
        val payload = validPayload()

        val encoded = IndexCodec.encode(payload)
        val decoded = IndexCodec.decode(encoded)
        val json = encoded.decodeToString()

        assertEquals(payload, decoded)
        assertArrayEquals(encoded, IndexCodec.encode(decoded))
        assertTrue(json.contains("\"wrappedDataKey\":"))
        assertFalse(json.contains("\"password\":"))
        assertFalse(json.contains("\"botToken\":"))
        assertFalse(json.contains("\"masterKey\":"))
        assertFalse(json.contains("\"dataKey\":"))
    }

    @Test
    fun encodingIsDeterministicAndUsesTheCanonicalSchema() {
        val payload = validPayload()
        val reordered = payload.copy(
            folders = payload.folders.reversed(),
            files = payload.files.reversed(),
            chunks = payload.chunks.reversed(),
            pendingOperations = payload.pendingOperations.reversed().map { operation ->
                operation.copy(
                    payload = operation.payload?.entries
                        ?.reversed()
                        ?.associate { it.toPair() },
                    remainingMessageIds = operation.remainingMessageIds?.reversed(),
                )
            },
        )

        assertArrayEquals(IndexCodec.encode(payload), IndexCodec.encode(reordered))
        assertEquals(
            """{"schema":"teledrive.cloud-index","formatVersion":1,"appVersion":"0.1.0-alpha","revision":1,"currentIndexMessageId":40,"previous":null,"createdAtEpochMillis":1,"updatedAtEpochMillis":1,"rootFolderId":"root","keyDerivation":{"algorithm":"PBKDF2-HMAC-SHA256","salt":"AAEC","iterations":600000,"keyLengthBytes":32},"encryptionParameters":{"index":{"algorithm":"AES-256-GCM","formatVersion":1,"nonceLengthBytes":12,"tagLengthBits":128,"keyLengthBits":256},"file":{"algorithm":"AES-256-GCM","formatVersion":1,"nonceLengthBytes":12,"tagLengthBits":128,"keyLengthBits":256}},"folders":[{"id":"root","name":"我的云盘","parentId":null,"createdAtEpochMillis":1,"updatedAtEpochMillis":1}],"files":[],"chunks":[],"pendingOperations":[]}""",
            IndexCodec.encode(minimalPayload()).decodeToString(),
        )
    }

    @Test
    fun decodingRejectsUnknownMalformedAndNonCanonicalJson() {
        val canonical = IndexCodec.encode(minimalPayload()).decodeToString()

        assertFailure(IndexFormatFailure.MALFORMED) {
            IndexCodec.decode("{".encodeToByteArray())
        }
        assertFailure(IndexFormatFailure.MALFORMED) {
            IndexCodec.decode(byteArrayOf(0xc3.toByte(), 0x28))
        }
        assertFailure(IndexFormatFailure.MALFORMED) {
            IndexCodec.decode(canonical.replaceFirst("{", "{\"unknown\":true,").encodeToByteArray())
        }
        assertFailure(IndexFormatFailure.NON_CANONICAL) {
            IndexCodec.decode(" $canonical".encodeToByteArray())
        }
        assertFailure(IndexFormatFailure.MALFORMED) {
            IndexCodec.decode(canonical.replace("\"salt\":\"AAEC\"", "\"salt\":\"AAEC==\"").encodeToByteArray())
        }
    }

    @Test
    fun decodingRejectsUnknownSchemaAndFormatVersion() {
        val canonical = IndexCodec.encode(minimalPayload()).decodeToString()

        assertFailure(IndexFormatFailure.UNSUPPORTED_SCHEMA) {
            IndexCodec.decode(
                canonical.replace(
                    "\"schema\":\"teledrive.cloud-index\"",
                    "\"schema\":\"other.cloud-index\"",
                ).encodeToByteArray(),
            )
        }
        assertFailure(IndexFormatFailure.UNSUPPORTED_VERSION) {
            IndexCodec.decode(
                canonical.replace("\"formatVersion\":1", "\"formatVersion\":99")
                    .encodeToByteArray(),
            )
        }
    }

    @Test
    fun validatorRejectsInvalidRevisionPointerAndKdfMetadata() {
        val valid = validPayload()

        listOf(
            valid.copy(revision = 0),
            valid.copy(appVersion = ""),
            valid.copy(currentIndexMessageId = 0),
            valid.copy(previous = null),
            valid.copy(previous = valid.previous?.copy(revision = 0)),
            valid.copy(previous = valid.previous?.copy(messageId = 0)),
            valid.copy(previous = valid.previous?.copy(messageId = valid.currentIndexMessageId)),
            valid.copy(previous = valid.previous?.copy(fileId = "")),
            valid.copy(keyDerivation = valid.keyDerivation.copy(algorithm = "PBKDF2-HMAC-SHA1")),
            valid.copy(keyDerivation = valid.keyDerivation.copy(salt = IndexBytes.of(byteArrayOf()))),
            valid.copy(keyDerivation = valid.keyDerivation.copy(iterations = 0)),
            valid.copy(keyDerivation = valid.keyDerivation.copy(keyLengthBytes = 16)),
            valid.copy(updatedAtEpochMillis = valid.createdAtEpochMillis - 1),
            valid.copy(
                encryptionParameters = valid.encryptionParameters.copy(
                    index = valid.encryptionParameters.index.copy(nonceLengthBytes = 16),
                ),
            ),
            valid.copy(
                encryptionParameters = valid.encryptionParameters.copy(
                    file = valid.encryptionParameters.file.copy(tagLengthBits = 96),
                ),
            ),
        ).forEach { invalid ->
            assertFailure(IndexFormatFailure.INVALID_DATA) {
                IndexValidator.requireValid(invalid)
            }
        }
    }

    @Test
    fun validatorRejectsBrokenFolderGraphsAndCrossTypeNameConflicts() {
        val valid = validPayload()
        val child = valid.folders.single { it.id == FOLDER_ID }

        listOf(
            valid.copy(folders = valid.folders.filterNot { it.id == ROOT_ID }),
            valid.copy(folders = valid.folders + child),
            valid.copy(folders = valid.folders.map {
                if (it.id == FOLDER_ID) it.copy(parentId = FOLDER_ID) else it
            }),
            valid.copy(folders = valid.folders.map {
                if (it.id == FOLDER_ID) it.copy(name = " bad ") else it
            }),
            valid.copy(files = valid.files.map { it.copy(name = child.name.uppercase()) }),
            valid.copy(folders = valid.folders.map {
                if (it.id == FOLDER_ID) it.copy(updatedAtEpochMillis = 0) else it
            }),
        ).forEach { invalid ->
            assertFailure(IndexFormatFailure.INVALID_DATA) {
                IndexValidator.requireValid(invalid)
            }
        }
    }

    @Test
    fun validatorRejectsInvalidFileAndChunkMetadata() {
        val valid = validPayload()
        val file = valid.files.single()
        val chunk = valid.chunks.single()

        listOf(
            valid.copy(files = listOf(file.copy(id = "not-a-uuid"))),
            valid.copy(files = listOf(file.copy(parentFolderId = MISSING_ID))),
            valid.copy(files = listOf(file.copy(sha256 = "AB".repeat(32)))),
            valid.copy(files = listOf(file.copy(chunkCount = 2))),
            valid.copy(files = listOf(file.copy(wrappedDataKey = IndexBytes.of(ByteArray(1))))),
            valid.copy(chunks = emptyList()),
            valid.copy(chunks = listOf(chunk.copy(partIndex = 1))),
            valid.copy(chunks = listOf(chunk.copy(nonce = IndexBytes.of(ByteArray(11))))),
            valid.copy(chunks = listOf(chunk.copy(messageId = 0))),
            valid.copy(chunks = listOf(chunk.copy(encryptedSizeBytes = 1))),
        ).forEach { invalid ->
            assertFailure(IndexFormatFailure.INVALID_DATA) {
                IndexValidator.requireValid(invalid)
            }
        }
    }

    @Test
    fun validatorRejectsMalformedOrDanglingPendingOperations() {
        val valid = validPayload()
        val operation = valid.pendingOperations.single()

        listOf(
            valid.copy(pendingOperations = valid.pendingOperations + operation),
            valid.copy(pendingOperations = listOf(operation.copy(targetId = MISSING_ID))),
            valid.copy(pendingOperations = listOf(operation.copy(baseRevision = 3))),
            valid.copy(pendingOperations = listOf(operation.copy(candidateRevision = 0))),
            valid.copy(pendingOperations = listOf(operation.copy(payload = null))),
            valid.copy(pendingOperations = listOf(operation.copy(attempt = -1))),
            valid.copy(pendingOperations = listOf(operation.copy(updatedAtEpochMillis = 0))),
        ).forEach { invalid ->
            assertFailure(IndexFormatFailure.INVALID_DATA) {
                IndexValidator.requireValid(invalid)
            }
        }
    }

    @Test
    fun deletionRecoveryMetadataRoundTripsAndMustMatchRemainingChunks() {
        val valid = validPayload()
        val file = valid.files.single().copy(status = IndexFileStatus.PARTIALLY_DELETED)
        val chunk = valid.chunks.single().copy(uploadStatus = IndexChunkStatus.FAILED)
        val operation = valid.pendingOperations.single().copy(
            id = "delete:$FILE_ID",
            type = IndexPendingOperationType.DELETE,
            payload = null,
            remainingMessageIds = listOf(chunk.messageId),
            status = IndexPendingOperationStatus.FAILED,
        )
        val deletion = valid.copy(
            files = listOf(file),
            chunks = listOf(chunk),
            pendingOperations = listOf(operation),
        )

        assertEquals(deletion, IndexCodec.decode(IndexCodec.encode(deletion)))
        assertFailure(IndexFormatFailure.INVALID_DATA) {
            IndexValidator.requireValid(
                deletion.copy(
                    pendingOperations = listOf(operation.copy(remainingMessageIds = emptyList())),
                ),
            )
        }
    }

    @Test
    fun deletedFolderUsesASeparateTombstoneOperationWithoutPretendingToBeAFileDeletion() {
        val operation = IndexPendingOperation(
            id = "00000000-0000-0000-0000-000000000099",
            type = IndexPendingOperationType.DELETE_FOLDER,
            targetId = "00000000-0000-0000-0000-000000000098",
            payload = mapOf("parentId" to ROOT_ID),
            remainingMessageIds = null,
            baseRevision = 0,
            candidateRevision = null,
            indexConfirmedAtEpochMillis = null,
            status = IndexPendingOperationStatus.PENDING,
            attempt = 0,
            nextRetryAtEpochMillis = null,
            errorCode = null,
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 1,
        )
        val payload = minimalPayload().copy(pendingOperations = listOf(operation))

        assertEquals(payload, IndexCodec.decode(IndexCodec.encode(payload)))
        assertFailure(IndexFormatFailure.INVALID_DATA) {
            IndexValidator.requireValid(
                payload.copy(
                    pendingOperations = listOf(
                        operation.copy(payload = mapOf("parentId" to "missing")),
                    ),
                ),
            )
        }
    }

    private fun minimalPayload(): CloudIndexPayload = CloudIndexPayload(
        schema = CloudIndexPayload.SCHEMA,
        formatVersion = CloudIndexPayload.CURRENT_FORMAT_VERSION,
        appVersion = "0.1.0-alpha",
        revision = 1,
        currentIndexMessageId = 40,
        previous = null,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 1,
        rootFolderId = ROOT_ID,
        keyDerivation = IndexKeyDerivationMetadata(
            algorithm = "PBKDF2-HMAC-SHA256",
            salt = IndexBytes.of(byteArrayOf(0, 1, 2)),
            iterations = 600_000,
            keyLengthBytes = 32,
        ),
        encryptionParameters = IndexEncryptionParameters(
            index = AesGcmParameters(
                algorithm = "AES-256-GCM",
                formatVersion = 1,
                nonceLengthBytes = 12,
                tagLengthBits = 128,
                keyLengthBits = 256,
            ),
            file = AesGcmParameters(
                algorithm = "AES-256-GCM",
                formatVersion = 1,
                nonceLengthBytes = 12,
                tagLengthBits = 128,
                keyLengthBits = 256,
            ),
        ),
        folders = listOf(
            IndexFolder(
                id = ROOT_ID,
                name = "我的云盘",
                parentId = null,
                createdAtEpochMillis = 1,
                updatedAtEpochMillis = 1,
            ),
        ),
        files = emptyList(),
        chunks = emptyList(),
        pendingOperations = emptyList(),
    )

    private fun validPayload(): CloudIndexPayload = minimalPayload().copy(
        revision = 2,
        currentIndexMessageId = 43,
        updatedAtEpochMillis = 8,
        previous = CloudIndexPointer(
            revision = 1,
            messageId = 41,
            fileId = "telegram-index-file-id",
        ),
        folders = listOf(
            minimalPayload().folders.single(),
            IndexFolder(
                id = FOLDER_ID,
                name = "报告",
                parentId = ROOT_ID,
                createdAtEpochMillis = 2,
                updatedAtEpochMillis = 3,
            ),
        ),
        files = listOf(
            IndexFile(
                id = FILE_ID,
                name = "季度报告.txt",
                mimeType = "text/plain",
                sizeBytes = 5,
                createdAtEpochMillis = 4,
                modifiedAtEpochMillis = 5,
                uploadedAtEpochMillis = 6,
                parentFolderId = FOLDER_ID,
                sha256 = "ab".repeat(32),
                encryptionFormatVersion = 1,
                chunkSizeBytes = 1024,
                chunkCount = 1,
                wrappedDataKey = IndexBytes.of(ByteArray(66) { (it + 1).toByte() }),
                status = IndexFileStatus.AVAILABLE,
                isCloudIndexed = true,
            ),
        ),
        chunks = listOf(
            IndexChunk(
                id = CHUNK_ID,
                fileId = FILE_ID,
                partIndex = 0,
                messageId = 42,
                telegramFileId = "telegram-chunk-file-id",
                nonce = IndexBytes.of(ByteArray(12) { it.toByte() }),
                encryptedSizeBytes = 39,
                uploadStatus = IndexChunkStatus.UPLOADED,
            ),
        ),
        pendingOperations = listOf(
            IndexPendingOperation(
                id = OPERATION_ID,
                type = IndexPendingOperationType.RENAME,
                targetId = FILE_ID,
                payload = linkedMapOf(
                    "oldName" to "旧报告.txt",
                    "newName" to "季度报告.txt",
                ),
                remainingMessageIds = null,
                baseRevision = 1,
                candidateRevision = 2,
                indexConfirmedAtEpochMillis = null,
                status = IndexPendingOperationStatus.PENDING,
                attempt = 0,
                nextRetryAtEpochMillis = null,
                errorCode = null,
                createdAtEpochMillis = 7,
                updatedAtEpochMillis = 7,
            ),
        ),
    )

    private fun assertFailure(
        expected: IndexFormatFailure,
        block: () -> Unit,
    ) {
        val error = assertThrows(IndexFormatException::class.java, block)
        assertEquals(expected, error.failure)
    }

    private companion object {
        const val ROOT_ID = "root"
        const val FOLDER_ID = "11111111-1111-4111-8111-111111111111"
        const val FILE_ID = "22222222-2222-4222-8222-222222222222"
        const val CHUNK_ID = "33333333-3333-4333-8333-333333333333"
        const val OPERATION_ID = "44444444-4444-4444-8444-444444444444"
        const val MISSING_ID = "55555555-5555-4555-8555-555555555555"
    }
}
