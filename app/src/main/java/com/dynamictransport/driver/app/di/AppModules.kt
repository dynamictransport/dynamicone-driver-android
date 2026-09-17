package com.dynamictransport.driver.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.dynamictransport.driver.BuildConfig
import com.dynamictransport.driver.core.common.AndroidAppLogger
import com.dynamictransport.driver.core.common.AppDirectories
import com.dynamictransport.driver.core.common.AppLogger
import com.dynamictransport.driver.core.common.ApplicationScope
import com.dynamictransport.driver.core.common.BackendMode
import com.dynamictransport.driver.core.common.BackendModeResolver
import com.dynamictransport.driver.core.common.IdGenerator
import com.dynamictransport.driver.core.common.IoDispatcher
import com.dynamictransport.driver.core.common.UuidIdGenerator
import com.dynamictransport.driver.core.database.DocumentDao
import com.dynamictransport.driver.core.database.DriverDatabase
import com.dynamictransport.driver.core.database.DriverProfileDao
import com.dynamictransport.driver.core.database.PendingActionDao
import com.dynamictransport.driver.core.database.PendingCommandDao
import com.dynamictransport.driver.core.database.ShiftDao
import com.dynamictransport.driver.core.database.SyncMetaDao
import com.dynamictransport.driver.core.database.TripDao
import com.dynamictransport.driver.core.network.DriverApi
import com.dynamictransport.driver.core.network.DriverAuthApi
import com.dynamictransport.driver.core.network.DriverHttpClients
import com.dynamictransport.driver.core.security.CryptoBox
import com.dynamictransport.driver.core.security.EncryptedTokenStore
import com.dynamictransport.driver.core.security.KeystoreCryptoBox
import com.dynamictransport.driver.core.security.SessionDataStore
import com.dynamictransport.driver.core.security.TokenDataStore
import com.dynamictransport.driver.core.security.TokenManager
import com.dynamictransport.driver.core.security.TokenStore
import com.dynamictransport.driver.core.sync.SyncRunner
import com.dynamictransport.driver.core.sync.SyncScheduler
import com.dynamictransport.driver.core.push.AndroidMessageNotifier
import com.dynamictransport.driver.core.push.FirebasePushSession
import com.dynamictransport.driver.core.push.MessageNotifier
import com.dynamictransport.driver.data.real.RealPushRegistrar
import com.dynamictransport.driver.core.push.PushRegistrar
import com.dynamictransport.driver.core.push.PushSession
import com.dynamictransport.driver.core.sync.WorkManagerSyncScheduler
import com.dynamictransport.driver.data.demo.AppNetworkMonitor
import com.dynamictransport.driver.data.demo.FakeDemoBackendControls
import com.dynamictransport.driver.data.demo.UnavailableDemoBackendControls
import com.dynamictransport.driver.data.demo.UnavailableMessageRepository
import com.dynamictransport.driver.data.demo.UnavailablePayrollRepository
import com.dynamictransport.driver.data.demo.UnavailableSignedDocumentRepository
import com.dynamictransport.driver.data.demo.UnavailableTripExecutionRepository
import com.dynamictransport.driver.data.media.BitmapDocumentScanner
import com.dynamictransport.driver.data.media.FileDocumentMediaStore
import com.dynamictransport.driver.data.real.NoOpSyncRunner
import com.dynamictransport.driver.data.real.RealAuthRepository
import com.dynamictransport.driver.data.real.RealDocumentRepository
import com.dynamictransport.driver.data.real.RealIncidentRepository
import com.dynamictransport.driver.data.real.RealMessageRepository
import com.dynamictransport.driver.data.real.RealNetworkMonitor
import com.dynamictransport.driver.data.real.RealPayrollRepository
import com.dynamictransport.driver.data.real.RealShiftRepository
import com.dynamictransport.driver.data.real.RealSignedDocumentRepository
import com.dynamictransport.driver.data.real.RealSyncRepository
import com.dynamictransport.driver.data.real.RealTripExecutionRepository
import com.dynamictransport.driver.data.real.RealTripRepository
import com.dynamictransport.driver.data.real.UnavailableDriverRemoteDataSource
import com.dynamictransport.driver.data.remote.DriverRemoteDataSource
import com.dynamictransport.driver.data.remote.fake.FakeDriverBackend
import com.dynamictransport.driver.data.remote.fake.FakeDriverRemoteDataSource
import com.dynamictransport.driver.data.repository.AuthRepositoryImpl
import com.dynamictransport.driver.data.repository.OfflineFirstDocumentRepository
import com.dynamictransport.driver.data.repository.OfflineFirstIncidentRepository
import com.dynamictransport.driver.data.repository.OfflineFirstShiftRepository
import com.dynamictransport.driver.data.repository.OfflineFirstTripRepository
import com.dynamictransport.driver.data.repository.SyncRepositoryImpl
import com.dynamictransport.driver.data.sync.SyncEngine
import com.dynamictransport.driver.domain.repository.AppInfo
import com.dynamictransport.driver.domain.repository.AuthRepository
import com.dynamictransport.driver.domain.repository.DemoBackendControls
import com.dynamictransport.driver.domain.repository.DocumentMediaStore
import com.dynamictransport.driver.domain.repository.DocumentRepository
import com.dynamictransport.driver.domain.repository.DocumentScanner
import com.dynamictransport.driver.domain.repository.IncidentRepository
import com.dynamictransport.driver.domain.repository.MessageRepository
import com.dynamictransport.driver.domain.repository.NetworkMonitor
import com.dynamictransport.driver.domain.repository.PayrollRepository
import com.dynamictransport.driver.domain.repository.ShiftRepository
import com.dynamictransport.driver.domain.repository.SignedDocumentRepository
import com.dynamictransport.driver.domain.repository.SyncRepository
import com.dynamictransport.driver.domain.repository.TripExecutionRepository
import com.dynamictransport.driver.domain.repository.TripRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.time.Clock
import javax.inject.Provider
import javax.inject.Qualifier
import javax.inject.Singleton

/** Demo data is reachable only in debug builds that select it explicitly; release always uses the real API. */
private fun isDemoMode(): Boolean = BackendModeResolver.current == BackendMode.DEMO

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemUTC()

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    fun provideAppDirectories(@ApplicationContext context: Context): AppDirectories =
        AppDirectories(filesDir = context.filesDir, cacheDir = context.cacheDir)

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun provideLogger(): AppLogger = AndroidAppLogger(enabled = BuildConfig.DEBUG)

    @Provides
    @Singleton
    fun provideAppInfo(): AppInfo = object : AppInfo {
        override val isDemoMode: Boolean = isDemoMode()
        override val versionName: String = BuildConfig.VERSION_NAME
        override val isPushConfigured: Boolean = BuildConfig.PUSH_CONFIGURED
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): DriverDatabase =
        Room.databaseBuilder(context, DriverDatabase::class.java, DriverDatabase.NAME).build()

    @Provides
    fun provideTripDao(database: DriverDatabase): TripDao = database.tripDao()

    @Provides
    fun provideShiftDao(database: DriverDatabase): ShiftDao = database.shiftDao()

    @Provides
    fun provideDriverProfileDao(database: DriverDatabase): DriverProfileDao = database.driverProfileDao()

    @Provides
    fun providePendingActionDao(database: DriverDatabase): PendingActionDao = database.pendingActionDao()

    @Provides
    fun provideDocumentDao(database: DriverDatabase): DocumentDao = database.documentDao()

    @Provides
    fun providePendingCommandDao(database: DriverDatabase): PendingCommandDao = database.pendingCommandDao()

    @Provides
    fun provideSyncMetaDao(database: DriverDatabase): SyncMetaDao = database.syncMetaDao()
}

@Module
@InstallIn(SingletonComponent::class)
object SecurityModule {

    @Provides
    @Singleton
    @SessionDataStore
    fun provideSessionDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile("session") })

    @Provides
    @Singleton
    @TokenDataStore
    fun provideTokenDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile("secure_tokens") })

    @Provides
    @Singleton
    fun provideCryptoBox(): CryptoBox = KeystoreCryptoBox()

    @Provides
    @Singleton
    fun provideTokenStore(@TokenDataStore dataStore: DataStore<Preferences>, cryptoBox: CryptoBox): TokenStore =
        EncryptedTokenStore(dataStore, cryptoBox)
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BaseHttpClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    @BaseHttpClient
    fun provideBaseHttpClient(): OkHttpClient =
        DriverHttpClients.base(appVersion = BuildConfig.VERSION_NAME, logRequests = BuildConfig.DEBUG)

    /** Token refresh runs without the authenticator, so a failed refresh can never recurse. */
    @Provides
    @Singleton
    fun provideDriverAuthApi(@BaseHttpClient client: OkHttpClient, json: Json): DriverAuthApi =
        retrofit(client, json).create(DriverAuthApi::class.java)

    @Provides
    @Singleton
    fun provideApiHttpClient(
        @BaseHttpClient baseClient: OkHttpClient,
        tokenManager: TokenManager,
        authApi: DriverAuthApi,
        clock: Clock,
    ): OkHttpClient = DriverHttpClients.authenticated(baseClient, tokenManager, authApi, clock)

    @Provides
    @Singleton
    fun provideDriverApi(client: OkHttpClient, json: Json): DriverApi =
        retrofit(client, json).create(DriverApi::class.java)

    private fun retrofit(client: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.API_BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
}

@Module
@InstallIn(SingletonComponent::class)
object RemoteModule {

    @Provides
    @Singleton
    fun provideFakeDriverBackend(@ApplicationContext context: Context, clock: Clock, json: Json): FakeDriverBackend =
        FakeDriverBackend(
            clock = clock,
            json = json,
            stateFile = File(context.filesDir, "demo_backend.json"),
            latencyMillis = 350,
        )

    /** Used by the demo data layer only; in real mode it never returns data. */
    @Provides
    @Singleton
    fun provideDriverRemoteDataSource(fake: Provider<FakeDriverRemoteDataSource>): DriverRemoteDataSource =
        if (isDemoMode()) fake.get() else UnavailableDriverRemoteDataSource

    @Provides
    @Singleton
    fun provideDemoBackendControls(fake: Provider<FakeDemoBackendControls>): DemoBackendControls =
        if (isDemoMode()) fake.get() else UnavailableDemoBackendControls
}

/** Screens depend on the domain interfaces only; the implementation is chosen once per process. */
@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    fun provideAuthRepository(demo: Provider<AuthRepositoryImpl>, real: Provider<RealAuthRepository>): AuthRepository =
        if (isDemoMode()) demo.get() else real.get()

    @Provides
    fun provideShiftRepository(demo: Provider<OfflineFirstShiftRepository>, real: Provider<RealShiftRepository>): ShiftRepository =
        if (isDemoMode()) demo.get() else real.get()

    @Provides
    fun provideTripRepository(demo: Provider<OfflineFirstTripRepository>, real: Provider<RealTripRepository>): TripRepository =
        if (isDemoMode()) demo.get() else real.get()

    @Provides
    fun provideDocumentRepository(
        demo: Provider<OfflineFirstDocumentRepository>,
        real: Provider<RealDocumentRepository>,
    ): DocumentRepository = if (isDemoMode()) demo.get() else real.get()

    @Provides
    fun provideIncidentRepository(
        demo: Provider<OfflineFirstIncidentRepository>,
        real: Provider<RealIncidentRepository>,
    ): IncidentRepository = if (isDemoMode()) demo.get() else real.get()

    @Provides
    fun provideTripExecutionRepository(real: Provider<RealTripExecutionRepository>): TripExecutionRepository =
        if (isDemoMode()) UnavailableTripExecutionRepository else real.get()

    @Provides
    fun providePayrollRepository(real: Provider<RealPayrollRepository>): PayrollRepository =
        if (isDemoMode()) UnavailablePayrollRepository else real.get()

    @Provides
    fun provideSignedDocumentRepository(real: Provider<RealSignedDocumentRepository>): SignedDocumentRepository =
        if (isDemoMode()) UnavailableSignedDocumentRepository else real.get()

    @Provides
    fun provideMessageRepository(real: Provider<RealMessageRepository>): MessageRepository =
        if (isDemoMode()) UnavailableMessageRepository else real.get()

    @Provides
    fun provideSyncRepository(demo: Provider<SyncRepositoryImpl>, real: Provider<RealSyncRepository>): SyncRepository =
        if (isDemoMode()) demo.get() else real.get()

    @Provides
    fun provideNetworkMonitor(demo: Provider<AppNetworkMonitor>, real: Provider<RealNetworkMonitor>): NetworkMonitor =
        if (isDemoMode()) demo.get() else real.get()

    @Provides
    fun provideSyncRunner(demo: Provider<SyncEngine>): SyncRunner = if (isDemoMode()) demo.get() else NoOpSyncRunner
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {

    @Binds
    abstract fun bindIdGenerator(impl: UuidIdGenerator): IdGenerator

    @Binds
    abstract fun bindDocumentMediaStore(impl: FileDocumentMediaStore): DocumentMediaStore

    @Binds
    abstract fun bindDocumentScanner(impl: BitmapDocumentScanner): DocumentScanner

    @Binds
    abstract fun bindSyncScheduler(impl: WorkManagerSyncScheduler): SyncScheduler

    @Binds
    abstract fun bindMessageNotifier(impl: AndroidMessageNotifier): MessageNotifier

    @Binds
    abstract fun bindPushRegistrar(impl: RealPushRegistrar): PushRegistrar

    @Binds
    abstract fun bindPushSession(impl: FirebasePushSession): PushSession
}
