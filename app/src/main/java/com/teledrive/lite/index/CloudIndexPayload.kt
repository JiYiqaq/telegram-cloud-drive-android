package com.teledrive.lite.index

import java.util.Base64
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
data class CloudIndexPayload(
    val schema: String,
    val formatVersion: Int,
    val appVersion: String,
    val revision: Long,
    val currentIndexMessageId: Long,
    val previous: CloudIndexPointer?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val rootFolderId: String,
    val keyDerivation: IndexKeyDerivationMetadata,
    val encryptionParameters: IndexEncryptionParameters,
    val folders: List<IndexFolder>,
    val files: List<IndexFile>,
    val chunks: List<IndexChunk>,
    val pendingOperations: List<IndexPendingOperation>,
) {
    val previousIndexMessageId: Long?
        get() = previous?.messageId

    companion object {
        const val SCHEMA: String = "teledrive.cloud-index"
        const val CURRENT_FORMAT_VERSION: Int = 1
    }
}

@Serializable
data class CloudIndexPointer(
    val revision: Long,
    @SerialName("previousIndexMessageId") val messageId: Long,
    val fileId: String,
)

@Serializable
data class IndexKeyDerivationMetadata(
    val algorithm: String,
    val salt: IndexBytes,
    val iterations: Int,
    val keyLengthBytes: Int,
)

@Serializable
data class IndexEncryptionParameters(
    val index: AesGcmParameters,
    val file: AesGcmParameters,
)

@Serializable
data class AesGcmParameters(
    val algorithm: String,
    val formatVersion: Int,
    val nonceLengthBytes: Int,
    val tagLengthBits: Int,
    val keyLengthBits: Int,
)

@Serializable
data class IndexFolder(
    val id: String,
    val name: String,
    val parentId: String?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Serializable
data class IndexFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val createdAtEpochMillis: Long,
    val modifiedAtEpochMillis: Long,
    val uploadedAtEpochMillis: Long,
    val parentFolderId: String,
    val sha256: String,
    val encryptionFormatVersion: Int,
    val chunkSizeBytes: Int,
    val chunkCount: Int,
    val wrappedDataKey: IndexBytes,
    val status: IndexFileStatus,
    val isCloudIndexed: Boolean,
)

@Serializable
enum class IndexFileStatus {
    @SerialName("available")
    AVAILABLE,

    @SerialName("deleting")
    DELETING,

    @SerialName("partially_deleted")
    PARTIALLY_DELETED,
}

@Serializable
data class IndexChunk(
    val id: String,
    val fileId: String,
    val partIndex: Int,
    val messageId: Long,
    val telegramFileId: String,
    val nonce: IndexBytes,
    val encryptedSizeBytes: Long,
    val uploadStatus: IndexChunkStatus,
)

@Serializable
enum class IndexChunkStatus {
    @SerialName("uploaded")
    UPLOADED,

    @SerialName("deleting")
    DELETING,

    @SerialName("deleted")
    DELETED,

    @SerialName("failed")
    FAILED,
}

@Serializable
data class IndexPendingOperation(
    val id: String,
    val type: IndexPendingOperationType,
    val targetId: String,
    val payload: Map<String, String>?,
    val remainingMessageIds: List<Long>?,
    val baseRevision: Long,
    val candidateRevision: Long?,
    val indexConfirmedAtEpochMillis: Long?,
    val status: IndexPendingOperationStatus,
    val attempt: Int,
    val nextRetryAtEpochMillis: Long?,
    val errorCode: String?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Serializable
enum class IndexPendingOperationType {
    @SerialName("create_folder")
    CREATE_FOLDER,

    @SerialName("rename")
    RENAME,

    @SerialName("move")
    MOVE,

    @SerialName("delete_folder")
    DELETE_FOLDER,

    @SerialName("delete")
    DELETE,

    @SerialName("index_update")
    INDEX_UPDATE,
}

@Serializable
enum class IndexPendingOperationStatus {
    @SerialName("pending")
    PENDING,

    @SerialName("running")
    RUNNING,

    @SerialName("failed")
    FAILED,
}

@Serializable(with = IndexBytesSerializer::class)
class IndexBytes private constructor(bytes: ByteArray) {
    private val value = bytes.copyOf()

    val size: Int
        get() = value.size

    fun toByteArray(): ByteArray = value.copyOf()

    override fun equals(other: Any?): Boolean =
        other is IndexBytes && value.contentEquals(other.value)

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = "IndexBytes(size=$size)"

    companion object {
        fun of(bytes: ByteArray): IndexBytes = IndexBytes(bytes)
    }
}

object IndexBytesSerializer : KSerializer<IndexBytes> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("IndexBytes", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: IndexBytes) {
        encoder.encodeString(encode(value.toByteArray()))
    }

    override fun deserialize(decoder: Decoder): IndexBytes {
        val encoded = decoder.decodeString()
        val decoded = try {
            Base64.getUrlDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            throw SerializationException("Invalid binary field")
        }
        if (encode(decoded) != encoded) {
            decoded.fill(0)
            throw SerializationException("Non-canonical binary field")
        }
        return IndexBytes.of(decoded).also { decoded.fill(0) }
    }

    private fun encode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
