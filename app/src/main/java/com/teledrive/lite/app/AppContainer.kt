package com.teledrive.lite.app

import android.content.Context
import com.teledrive.lite.BuildConfig
import com.teledrive.lite.crypto.CloudIndexEnvelopeCryptor
import com.teledrive.lite.crypto.CryptoEngine
import com.teledrive.lite.crypto.KeyWrapping
import com.teledrive.lite.database.TeleDriveDatabase
import com.teledrive.lite.index.EncryptedIndexCodec
import com.teledrive.lite.deletion.DeletionScheduler
import com.teledrive.lite.deletion.DeletionServices
import com.teledrive.lite.deletion.RepositoryDeletionStore
import com.teledrive.lite.deletion.SafeDeletionCoordinator
import com.teledrive.lite.deletion.TelegramDeletionRemote
import com.teledrive.lite.deletion.VerifiedDeletionIndexPublisher
import com.teledrive.lite.deletion.LocalOrphanCleanupPublisher
import com.teledrive.lite.deletion.OrphanCleanupScheduler
import com.teledrive.lite.deletion.RoomOrphanCleanupStore
import com.teledrive.lite.deletion.FolderDeletionScheduler
import com.teledrive.lite.deletion.FolderDeletionServices
import com.teledrive.lite.download.ContentResolverDownloadDestination
import com.teledrive.lite.download.DownloadCoordinator
import com.teledrive.lite.download.DownloadQueueRepository
import com.teledrive.lite.download.DownloadScheduler
import com.teledrive.lite.download.DownloadServices
import com.teledrive.lite.download.RoomDownloadStore
import com.teledrive.lite.download.TelegramDownloadRemote
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
import com.teledrive.lite.settings.AppPreferences
import com.teledrive.lite.settings.SettingsRepository
import androidx.work.WorkManager
import com.teledrive.lite.telegram.TelegramBotApiClient
import com.teledrive.lite.sync.EncryptedIndexCandidateFactory
import com.teledrive.lite.sync.FileIndexCandidateArtifactStore
import com.teledrive.lite.sync.FileRepositoryIndexCacheReplacer
import com.teledrive.lite.sync.IndexAtomicUpdater
import com.teledrive.lite.sync.IndexUpdateSingleFlight
import com.teledrive.lite.sync.IndexRecoveryService
import com.teledrive.lite.sync.RoomIndexLocalStore
import com.teledrive.lite.sync.RoomIndexSnapshotSource
import com.teledrive.lite.sync.TelegramIndexRemote
import com.teledrive.lite.sync.PinnedIndexSnapshotReader
import com.teledrive.lite.sync.RecoveryContextCommitter
import com.teledrive.lite.telegram.TelegramApiException
import com.teledrive.lite.telegram.TelegramFailure
import java.io.File
import java.util.UUID
import com.teledrive.lite.upload.RoomUploadStore
import com.teledrive.lite.upload.TelegramUploadRemote
import com.teledrive.lite.upload.UploadCoordinator
import com.teledrive.lite.upload.UploadQueueRepository
import com.teledrive.lite.upload.UploadScheduler
import com.teledrive.lite.upload.UploadServices
import com.teledrive.lite.upload.ContentResolverUploadInput
import com.teledrive.lite.upload.CloudIndexUpdateRunner
import com.teledrive.lite.upload.RequiredFileIndexPublisher

/**
 * 进程级依赖容器。后续任务会在这里注册数据库、网络客户端和仓库。
 */
class AppContainer(context: Context) {
    val applicationContext: Context = context.applicationContext

    val database: TeleDriveDatabase = TeleDriveDatabase.create(applicationContext)
    val fileRepository = FileRepository(database)
    val transferRepository = TransferRepository(database)
    val settingsRepository = SettingsRepository(database)
    val appPreferences = AppPreferences(
        applicationContext.getSharedPreferences("teledrive_user_preferences_v1", Context.MODE_PRIVATE),
    )
    val uploadQueueRepository = UploadQueueRepository(database)
    val uploadStore = RoomUploadStore(database)
    val uploadScheduler = UploadScheduler(
        context = applicationContext,
        queueRepository = uploadQueueRepository,
        uploadStore = uploadStore,
    )
    val downloadQueueRepository = DownloadQueueRepository(database)
    val downloadStore = RoomDownloadStore(database)
    val downloadScheduler = DownloadScheduler(
        context = applicationContext,
        queueRepository = downloadQueueRepository,
        downloadStore = downloadStore,
    )
    val deletionScheduler = DeletionScheduler(applicationContext, fileRepository)
    val orphanCleanupScheduler = OrphanCleanupScheduler(applicationContext)
    val folderDeletionScheduler = FolderDeletionScheduler(applicationContext)
    private val indexUpdateSingleFlight = IndexUpdateSingleFlight()

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

    val maintenanceService = AppMaintenanceService(
        database = database,
        fileRepository = fileRepository,
        setupStateStore = setupStateStore,
        workManager = WorkManager.getInstance(applicationContext),
        indexCandidateDirectory = File(applicationContext.filesDir, INDEX_CANDIDATE_DIRECTORY),
    )

    val setupInitializationService: SetupInitializationService = SetupInitializer(
        stateStore = setupStateStore,
    )

    fun isSetupComplete(): Boolean = setupStateStore.isComplete()

    /** Creates an authenticated cloud-index session from the currently committed setup generation. */
    fun createCloudIndexServices(): CloudIndexServices? {
        val config = secureConfigStore.load() ?: return null
        setupStateStore.loadCryptoContext()?.close() ?: return null
        val remote = TelegramIndexRemote(
            gateway = TelegramBotApiClient(config.botToken),
            chatId = config.channelId,
        )
        val encryptedCodec = EncryptedIndexCodec(CloudIndexEnvelopeCryptor(CryptoEngine()))
        val candidateFactory = EncryptedIndexCandidateFactory(
            snapshotSource = RoomIndexSnapshotSource(database),
            cryptoContextProvider = setupStateStore::loadCryptoContext,
            encryptedIndexCodec = encryptedCodec,
            artifactStore = FileIndexCandidateArtifactStore(
                File(applicationContext.filesDir, INDEX_CANDIDATE_DIRECTORY),
            ),
            appVersion = BuildConfig.VERSION_NAME,
        )
        return CloudIndexServices(
            updater = IndexAtomicUpdater(
                remote = remote,
                localStore = RoomIndexLocalStore(database),
                candidateFactory = candidateFactory,
                operationIdFactory = { UUID.randomUUID().toString() },
                singleFlight = indexUpdateSingleFlight,
            ),
            recovery = IndexRecoveryService(
                remote = remote,
                encryptedIndexCodec = encryptedCodec,
                cacheReplacer = FileRepositoryIndexCacheReplacer(fileRepository),
                contextCommitter = RecoveryContextCommitter { parameters, masterKey ->
                    setupStateStore.commit(config, masterKey, parameters)
                },
            ),
        )
    }

    fun createUploadServices(): UploadServices? {
        val config = secureConfigStore.load() ?: return null
        setupStateStore.loadCryptoContext()?.close() ?: return null
        val cloudIndex = createCloudIndexServices() ?: return null
        val cryptoEngine = CryptoEngine()
        val store = uploadStore
        return UploadServices(
            store = store,
            coordinator = UploadCoordinator(
                store = store,
                input = ContentResolverUploadInput(applicationContext.contentResolver),
                remote = TelegramUploadRemote(
                    gateway = TelegramBotApiClient(config.botToken),
                    chatId = config.channelId,
                ),
                indexPublisher = RequiredFileIndexPublisher(
                    runner = CloudIndexUpdateRunner {
                        cloudIndex.updater.resumeOrStart()
                    },
                    maximumAttempts = MAX_INDEX_PUBLICATION_ATTEMPTS,
                ),
                cryptoEngine = cryptoEngine,
                keyWrapping = KeyWrapping(cryptoEngine),
                cryptoContextProvider = setupStateStore::loadCryptoContext,
            ),
        )
    }

    fun createDownloadServices(): DownloadServices? {
        val config = secureConfigStore.load() ?: return null
        setupStateStore.loadCryptoContext()?.close() ?: return null
        val cryptoEngine = CryptoEngine()
        return DownloadServices(
            store = downloadStore,
            coordinator = DownloadCoordinator(
                store = downloadStore,
                remote = TelegramDownloadRemote(TelegramBotApiClient(config.botToken)),
                destination = ContentResolverDownloadDestination(applicationContext.contentResolver),
                cryptoEngine = cryptoEngine,
                keyWrapping = KeyWrapping(cryptoEngine),
                cryptoContextProvider = setupStateStore::loadCryptoContext,
            ),
        )
    }

    fun createDeletionServices(): DeletionServices? {
        val config = secureConfigStore.load() ?: return null
        setupStateStore.loadCryptoContext()?.close() ?: return null
        val cloudIndex = createCloudIndexServices() ?: return null
        val gateway = TelegramBotApiClient(config.botToken)
        val indexRemote = TelegramIndexRemote(gateway, config.channelId)
        val encryptedCodec = EncryptedIndexCodec(CloudIndexEnvelopeCryptor(CryptoEngine()))
        return DeletionServices(
            coordinator = SafeDeletionCoordinator(
                store = RepositoryDeletionStore(fileRepository),
                remote = TelegramDeletionRemote(gateway, config.channelId),
                indexPublisher = VerifiedDeletionIndexPublisher(
                    updater = cloudIndex.updater,
                    snapshotReader = PinnedIndexSnapshotReader(
                        remote = indexRemote,
                        encryptedIndexCodec = encryptedCodec,
                        cryptoContextProvider = setupStateStore::loadCryptoContext,
                    ),
                    repository = fileRepository,
                    maximumAttempts = MAX_INDEX_PUBLICATION_ATTEMPTS,
                ),
                errorCode = { error ->
                    when (error) {
                        is TelegramApiException -> when (val failure = error.failure) {
                            is TelegramFailure.Api -> "DELETE_API_${failure.errorCode ?: failure.httpStatusCode}"
                            is TelegramFailure.Http -> "DELETE_HTTP_${failure.statusCode}"
                            is TelegramFailure.Network -> "DELETE_RESULT_UNKNOWN"
                            TelegramFailure.InvalidResponse -> "DELETE_INVALID_RESPONSE"
                        }
                        else -> "REMOTE_DELETE_FAILED"
                    }
                },
            ),
        )
    }

    fun createOrphanCleanupServices(): DeletionServices? {
        val config = secureConfigStore.load() ?: return null
        val gateway = TelegramBotApiClient(config.botToken)
        val store = RoomOrphanCleanupStore(database)
        return DeletionServices(
            coordinator = SafeDeletionCoordinator(
                store = store,
                remote = TelegramDeletionRemote(gateway, config.channelId),
                indexPublisher = LocalOrphanCleanupPublisher(store),
                errorCode = { error ->
                    if (error is TelegramApiException && error.failure is TelegramFailure.Network) {
                        "ORPHAN_DELETE_RESULT_UNKNOWN"
                    } else {
                        "ORPHAN_DELETE_FAILED"
                    }
                },
            ),
        )
    }

    fun createFolderDeletionServices(): FolderDeletionServices? {
        val deletion = createDeletionServices() ?: return null
        val cloud = createCloudIndexServices() ?: return null
        return FolderDeletionServices(fileRepository, deletion.coordinator, cloud.updater)
    }

    private companion object {
        const val PREFERENCES_NAME = "teledrive_secure_state_v1"
        const val CONFIG_KEY_ALIAS = "teledrive_config_key_v1"
        const val SESSION_KEY_ALIAS = "teledrive_session_key_v1"
        const val INDEX_CANDIDATE_DIRECTORY = "index-candidates-v1"
        const val MAX_INDEX_PUBLICATION_ATTEMPTS = 3
    }
}

data class CloudIndexServices(
    val updater: IndexAtomicUpdater,
    val recovery: IndexRecoveryService,
)
