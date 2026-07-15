package com.teledrive.lite.crypto

import com.teledrive.lite.util.SecureErase
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class KeyDerivationParameters private constructor(
    val algorithm: String,
    salt: ByteArray,
    val iterations: Int,
    val keyLengthBytes: Int,
) {
    private val saltBytes = salt.copyOf()

    val salt: ByteArray
        get() = saltBytes.copyOf()

    companion object {
        fun pbkdf2(
            salt: ByteArray,
            iterations: Int,
            keyLengthBytes: Int = KeyDerivation.MASTER_KEY_BYTES,
        ): KeyDerivationParameters {
            require(salt.isNotEmpty()) { "Salt must not be empty" }
            require(salt.size <= KeyDerivation.MAX_SALT_BYTES) { "Salt is too large" }
            require(iterations in 1..KeyDerivation.MAX_ITERATIONS) {
                "Iterations are outside the supported range"
            }
            require(keyLengthBytes == KeyDerivation.MASTER_KEY_BYTES) {
                "Only 256-bit master keys are supported"
            }
            return KeyDerivationParameters(
                algorithm = KeyDerivation.ALGORITHM,
                salt = salt,
                iterations = iterations,
                keyLengthBytes = keyLengthBytes,
            )
        }
    }
}

object KeyDerivation {
    const val ALGORITHM: String = "PBKDF2-HMAC-SHA256"
    const val MASTER_KEY_BYTES: Int = 32
    const val DEFAULT_ITERATIONS: Int = 600_000
    const val DEFAULT_SALT_BYTES: Int = 16
    const val MAX_ITERATIONS: Int = 2_000_000
    const val MAX_SALT_BYTES: Int = 64

    private const val JCA_ALGORITHM = "PBKDF2WithHmacSHA256"
    private val secureRandom = SecureRandom()

    fun newParameters(): KeyDerivationParameters =
        KeyDerivationParameters.pbkdf2(
            salt = ByteArray(DEFAULT_SALT_BYTES).also(secureRandom::nextBytes),
            iterations = DEFAULT_ITERATIONS,
            keyLengthBytes = MASTER_KEY_BYTES,
        )

    fun derive(
        password: CharArray,
        parameters: KeyDerivationParameters,
    ): ByteArray {
        require(parameters.algorithm == ALGORITHM) { "Unsupported key derivation algorithm" }
        val passwordCopy = password.copyOf()
        val saltCopy = parameters.salt
        val spec = PBEKeySpec(
            passwordCopy,
            saltCopy,
            parameters.iterations,
            parameters.keyLengthBytes * Byte.SIZE_BITS,
        )
        return try {
            SecretKeyFactory.getInstance(JCA_ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            SecureErase.wipe(passwordCopy)
            SecureErase.wipe(saltCopy)
        }
    }
}
