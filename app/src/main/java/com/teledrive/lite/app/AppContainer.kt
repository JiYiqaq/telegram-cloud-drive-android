package com.teledrive.lite.app

import android.content.Context
import com.teledrive.lite.database.TeleDriveDatabase
import com.teledrive.lite.repository.ConnectionRepository
import com.teledrive.lite.repository.FileRepository
import com.teledrive.lite.repository.SetupConnectionService
import com.teledrive.lite.repository.TransferRepository
import com.teledrive.lite.settings.AtomicSetupStateStore
import com.teledrive.lite.settings.KdfParametersStore
import com.teledrive.lite.settings.KeystoreCipher
import com.teledrive.lite.settings.SecureConfigStore
import com.teledrive.lite.settings.SessionKeyStore
import com.teledrive.lite.settings.SetupInitializationService
import com.teledrive.lite.settings.SetupInitializer
import com.teledrive.lite.settings.SharedPreferencesStringValueStore
import com.teledrive.lite.telegram.TelegramBotApiClient

/**
 * 进程级依赖容器。后续任务会在这里注册数据库、网络客户端和仓库。
 */
class AppContainer(context: Context) {
    val applicationContext: Context = context.applicationContext

    val database: TeleDriveDatabase = TeleDriveDatabase.create(applicationContext)
    val fileRepository = FileRepository(database)
    val transferRepository = TransferRepository(database)

    private val secureValues = SharedPreferencesStringValueStore(
        applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )
    private val configCipher = KeystoreCipher(CONFIG_KEY_ALIAS)
    private val sessionCipher = KeystoreCipher(SESSION_KEY_ALIAS)

    val secureConfigStore = SecureConfigStore(
        values = secureValues,
        cipher = configCipher,
    )

    val sessionKeyStore = SessionKeyStore(
        values = secureValues,
        cipher = sessionCipher,
    )

    val kdfParametersStore = KdfParametersStore(secureValues)

    val setupConnectionService: SetupConnectionService = ConnectionRepository { token ->
        TelegramBotApiClient(token)
    }

    private val setupStateStore = AtomicSetupStateStore(
        values = secureValues,
        configCipher = configCipher,
        sessionCipher = sessionCipher,
    )

    val setupInitializationService: SetupInitializationService = SetupInitializer(
        stateStore = setupStateStore,
    )

    fun isSetupComplete(): Boolean = setupStateStore.isComplete()

    private companion object {
        const val PREFERENCES_NAME = "teledrive_secure_state_v1"
        const val CONFIG_KEY_ALIAS = "teledrive_config_key_v1"
        const val SESSION_KEY_ALIAS = "teledrive_session_key_v1"
    }
}
