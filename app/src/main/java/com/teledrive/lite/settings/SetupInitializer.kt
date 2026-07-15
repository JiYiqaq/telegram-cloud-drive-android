package com.teledrive.lite.settings

import com.teledrive.lite.crypto.KeyDerivation
import com.teledrive.lite.crypto.KeyDerivationParameters
import com.teledrive.lite.util.SecureErase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

fun interface SetupInitializationService {
    suspend fun initialize(config: ValidatedConnectionConfig, password: CharArray)
}

class SetupInitializer(
    private val stateStore: SetupStatePersistence,
    private val parametersFactory: () -> KeyDerivationParameters = KeyDerivation::newParameters,
    private val masterKeyDeriver: (CharArray, KeyDerivationParameters) -> ByteArray =
        KeyDerivation::derive,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val initializationMutex: Mutex = Mutex(),
) : SetupInitializationService {
    override suspend fun initialize(
        config: ValidatedConnectionConfig,
        password: CharArray,
    ) {
        try {
            initializationMutex.withLock {
                withContext(workDispatcher) {
                    var masterKey: ByteArray? = null
                    try {
                        val parameters = parametersFactory()
                        masterKey = masterKeyDeriver(password, parameters)
                        require(masterKey.size == KeyDerivation.MASTER_KEY_BYTES)
                        currentCoroutineContext().ensureActive()
                        stateStore.commit(config, masterKey, parameters)
                    } finally {
                        masterKey?.let(SecureErase::wipe)
                    }
                }
            }
        } finally {
            SecureErase.wipe(password)
        }
    }
}
