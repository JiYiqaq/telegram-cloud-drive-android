package com.teledrive.lite.crypto

class CryptoAuthenticationException : SecurityException(
    "Encrypted data authentication failed",
)

class CryptoFormatException : IllegalArgumentException(
    "Encrypted data format is invalid or unsupported",
)

class CryptoOperationException : IllegalStateException(
    "Cryptographic operation failed",
)
