package com.dynamictransport.dynamicone.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.dynamictransport.dynamicone.BuildConfig
import com.dynamictransport.dynamicone.core.alarm.AndroidRestAlarmNotifier
import com.dynamictransport.dynamicone.core.alarm.AndroidRestAlarmPermissions
import com.dynamictransport.dynamicone.core.alarm.AndroidRestAlarmScheduler
import com.dynamictransport.dynamicone.core.alarm.ApiDriverDaySync
import com.dynamictransport.dynamicone.core.alarm.DataStoreRestAlarmSettings
import com.dynamictransport.dynamicone.core.alarm.RestAlarmDataStore
import com.dynamictransport.dynamicone.core.alarm.RestAlarmNotifier
import com.dynamictransport.dynamicone.core.alarm.RestAlarmPermissions
import com.dynamictransport.dynamicone.core.alarm.RestAlarmScheduler
import com.dynamictransport.dynamicone.core.common.AndroidAppLogger
import com.dynamictransport.dynamicone.core.common.AndroidDeviceUptime
import com.dynamictransport.dynamicone.core.common.AppDirectories
import com.dynamictransport.dynamicone.core.common.AppLogger
import com.dynamictransport.dynamicone.core.common.ApplicationScope
import com.dynamictransport.dynamicone.core.common.BackendMode
import com.dynamictransport.dynamicone.core.common.BackendModeResolver
import com.dynamictransport.dynamicone.core.common.DeviceUptime
import com.dynamictransport.dynamicone.core.common.IdGenerator
import com.dynamictransport.dynamicone.core.common.IoDispatcher
import com.dynamictransport.dynamicone.core.common.UuidIdGenerator
import com.dynamictransport.dynamicone.core.database.AppDatabase
import com.dynamictransport.dynamicone.core.database.AttendancePunchDao
import com.dynamictransport.dynamicone.core.database.CheckPhotoDao
import com.dynamictransport.dynamicone.core.database.DocumentDao
import com.dynamictransport.dynamicone.core.database.DriverCacheDao
import com.dynamictransport.dynamicone.core.database.DriverProfileDao
import com.dynamictransport.dynamicone.core.database.PendingActionDao
import com.dynamictransport.dynamicone.core.database.PendingCommandDao
import com.dynamictransport.dynamicone.core.database.ShiftCommandDao
import com.dynamictransport.dynamicone.core.database.ShiftDao
import com.dynamictransport.dynamicone.core.database.SyncMetaDao
import com.dynamictransport.dynamicone.core.database.TripDao
import com.dynamictransport.dynamicone.core.network.DriverApi
import com.dynamictransport.dynamicone.core.network.DriverAuthApi
import com.dynamictransport.dynamicone.core.network.DriverHttpClients
import com.dynamictransport.dynamicone.core.network.MobileApi
import com.dynamictransport.dynamicone.core.nfc.AndroidNfcStatusProvider
import com.dynamictransport.dynamicone.core.nfc.NfcStatusProvider
import com.dynamictransport.dynamicone.core.push.AndroidMessageNotifier
import com.dynamictransport.dynamicone.core.push.FirebasePushSession
import com.dynamictransport.dynamicone.core.push.MessageNotifier
import com.dynamictransport.dynamicone.core.push.PushRegistrar
import com.dynamictransport.dynamicone.core.push.PushSession
import com.dynamictransport.dynamicone.core.security.CryptoBox
import com.dynamictransport.dynamicone.core.security.EncryptedTokenStore
import com.dynamictransport.dynamicone.core.security.KeystoreCryptoBox
import com.dynamictransport.dynamicone.core.security.SessionDataStore
import com.dynamictransport.dynamicone.core.security.TokenDataStore
import com.dynamictransport.dynamicone.core.security.TokenManager
import com.dynamictransport.dynamicone.core.security.TokenStore
import com.dynamictransport.dynamicone.core.sync.AttendanceSyncScheduler
import com.dynamictransport.dynamicone.core.sync.DriverDaySync
import com.dynamictransport.dynamicone.core.sync.DriverDayWorkScheduler
import com.dynamictransport.dynamicone.core.sync.NoOpDriverDaySync
import com.dynamictransport.dynamicone.core.sync.ShiftSyncScheduler
import com.dynamictransport.dynamicone.core.sync.SyncRunner
import com.dynamictransport.dynamicone.core.sync.SyncScheduler
import com.dynamictransport.dynamicone.core.sync.WorkManagerAttendanceSyncScheduler
import com.dynamictransport.dynamicone.core.sync.WorkManagerDriverDayWorkScheduler
import com.dynamictransport.dynamicone.core.sync.WorkManagerShiftSyncScheduler
import com.dynamictransport.dynamicone.core.sync.WorkManagerSyncScheduler
import com.dynamictransport.dynamicone.data.demo.AppNetworkMonitor
import com.dynamictransport.dynamicone.data.demo.FakeDemoBackendControls
import com.dynamictransport.dynamicone.data.demo.UnavailableAttendanceRepository
import com.dynamictransport.dynamicone.data.demo.UnavailableDailyShiftRepository
import com.dynamictransport.dynamicone.data.demo.UnavailableDemoBackendControls
import com.dynamictransport.dynamicone.data.demo.UnavailableDriverTimesRepository
import com.dynamictransport.dynamicone.data.demo.UnavailableMessageRepository
import com.dynamictransport.dynamicone.data.demo.UnavailablePayrollRepository
import com.dynamictransport.dynamicone.data.demo.UnavailableSignedDocumentRepository
import com.dynamictransport.dynamicone.data.demo.UnavailableTripExecutionRepository
import com.dynamictransport.dynamicone.data.demo.UnavailableVehicleCheckRepository
import com.dynamictransport.dynamicone.data.media.BitmapDocumentScanner
import com.dynamictransport.dynamicone.data.media.FileCheckPhotoStore
import com.dynamictransport.dynamicone.data.media.FileDocumentMediaStore
import com.dynamictransport.dynamicone.data.real.AccountRemote
import com.dynamictransport.dynamicone.data.real.ApiDriverDaySessionData
import com.dynamictransport.dynamicone.data.real.ApiDriverTimesRepository
import com.dynamictransport.dynamicone.data.real.ApiShiftRepository
import com.dynamictransport.dynamicone.data.real.ApiVehicleCheckRepository
import com.dynamictransport.dynamicone.data.real.DriverDaySessionData
import com.dynamictransport.dynamicone.data.real.DriverAccountRemote
import com.dynamictransport.dynamicone.data.real.MobileAccountRemote
import com.dynamictransport.dynamicone.data.real.NoOpSyncRunner
import com.dynamictransport.dynamicone.data.real.RealAttendanceRepository
import com.dynamictransport.dynamicone.data.real.RealAuthRepository
import com.dynamictransport.dynamicone.data.real.RealDocumentRepository
import com.dynamictransport.dynamicone.data.real.RealIncidentRepository
import com.dynamictransport.dynamicone.data.real.RealMessageRepository
import com.dynamictransport.dynamicone.data.real.RealNetworkMonitor
import com.dynamictransport.dynamicone.data.real.RealPayrollRepository
import com.dynamictransport.dynamicone.data.real.RealPushRegistrar
import com.dynamictransport.dynamicone.data.real.RealShiftRepository
import com.dynamictransport.dynamicone.data.real.RealSignedDocumentRepository
import com.dynamictransport.dynamicone.data.real.RealSyncRepository
import com.dynamictransport.dynamicone.data.real.RealTripExecutionRepository
import com.dynamictransport.dynamicone.data.real.RealTripRepository
import com.dynamictransport.dynamicone.data.real.UnavailableDriverRemoteDataSource
import com.dynamictransport.dynamicone.data.remote.DriverRemoteDataSource
import com.dynamictransport.dynamicone.data.remote.fake.FakeDriverBackend
import com.dynamictransport.dynamicone.data.remote.fake.FakeDriverRemoteDataSource
import com.dynamictransport.dynamicone.data.repository.AuthRepositoryImpl
import com.dynamictransport.dynamicone.data.repository.OfflineFirstDocumentRepository
import com.dynamictransport.dynamicone.data.repository.OfflineFirstIncidentRepository
import com.dynamictransport.dynamicone.data.repository.OfflineFirstShiftRepository
import com.dynamictransport.dynamicone.data.repository.OfflineFirstTripRepository
import com.dynamictransport.dynamicone.data.repository.SyncRepositoryImpl
import com.dynamictransport.dynamicone.data.sync.SyncEngine
import com.dynamictransport.dynamicone.domain.repository.AppInfo
import com.dynamictransport.dynamicone.domain.repository.AttendanceRepository
import com.dynamictransport.dynamicone.domain.repository.AuthRepository
import com.dynamictransport.dynamicone.domain.repository.CheckPhotoStore
import com.dynamictransport.dynamicone.domain.repository.DailyShiftRepository
import com.dynamictransport.dynamicone.domain.repository.DemoBackendControls
import com.dynamictransport.dynamicone.domain.repository.DocumentMediaStore
import com.dynamictransport.dynamicone.domain.repository.DocumentRepository
import com.dynamictransport.dynamicone.domain.repository.DocumentScanner
import com.dynamictransport.dynamicone.domain.repository.DriverTimesRepository
import com.dynamictransport.dynamicone.domain.repository.IncidentRepository
import com.dynamictransport.dynamicone.domain.repository.MessageRepository
import com.dynamictransport.dynamicone.domain.repository.NetworkMonitor
import com.dynamictransport.dynamicone.domain.repository.PayrollRepository
import com.dynamictransport.dynamicone.domain.repository.RestAlarmSettingsRepository
import com.dynamictransport.dynamicone.domain.repository.ShiftRepository
import com.dynamictransport.dynamicone.domain.repository.SignedDocumentRepository
import com.dynamictransport.dynamicone.domain.repository.SyncRepository
import com.dynamictransport.dynamicone.domain.repository.TripExecutionRepository
import com.dynamictransport.dynamicone.domain.repository.TripRepository
import com.dynamictransport.dynamicone.domain.repository.VehicleCheckRepository
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

/** Driver API daily shift (docs/16): real backend and `app.driverShift=true` only; the demo keeps its simple shift. */
private fun isDriverShift(): Boolean = BuildConfig.DRIVER_SHIFT_ENABLED && !isDemoMode()

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
        override val isDriverShiftEnabled: Boolean = isDriverShift()
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME).build()

    @Provides
    fun provideTripDao(database: AppDatabase): TripDao = database.tripDao()

    @Provides
    fun provideShiftDao(database: AppDatabase): ShiftDao = database.shiftDao()

    @Provides
    fun provideDriverProfileDao(database: AppDatabase): DriverProfileDao = database.driverProfileDao()

    @Provides
    fun providePendingActionDao(database: AppDatabase): PendingActionDao = database.pendingActionDao()

    @Provides
    fun provideDocumentDao(database: AppDatabase): DocumentDao = database.documentDao()

    @Provides
    fun providePendingCommandDao(database: AppDatabase): PendingCommandDao = database.pendingCommandDao()

    @Provides
    fun provideSyncMetaDao(database: AppDatabase): SyncMetaDao = database.syncMetaDao()

    @Provides
    fun provideAttendancePunchDao(database: AppDatabase): AttendancePunchDao = database.attendancePunchDao()

    @Provides
    fun provideShiftCommandDao(database: AppDatabase): ShiftCommandDao = database.shiftCommandDao()

    @Provides
    fun provideCheckPhotoDao(database: AppDatabase): CheckPhotoDao = database.checkPhotoDao()

    @Provides
    fun provideDriverCacheDao(database: AppDatabase): DriverCacheDao = database.driverCacheDao()
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

    /** The wake-up alarm's settings; no secrets (docs/16). */
    @Provides
    @Singleton
    @RestAlarmDataStore
    fun provideRestAlarmDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile("rest_alarm") })
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

    /**
     * Token refresh runs without the authenticator, so a failed refresh can never recurse. Tokens are refreshed where
     * they were issued: the Mobile API once it is enabled, the Driver API before.
     */
    @Provides
    @Singleton
    fun provideDriverAuthApi(@BaseHttpClient client: OkHttpClient, json: Json): DriverAuthApi =
        retrofit(client, json, accountBaseUrl()).create(DriverAuthApi::class.java)

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
        retrofit(client, json, BuildConfig.API_BASE_URL).create(DriverApi::class.java)

    @Provides
    @Singleton
    fun provideMobileApi(client: OkHttpClient, json: Json): MobileApi =
        retrofit(client, json, BuildConfig.MOBILE_API_BASE_URL).create(MobileApi::class.java)

    /** docs/15_MOBILE_APP_API.md: `app.mobileApi` switches sign-in to the Mobile API once the backend serves it. */
    @Provides
    @Singleton
    fun provideAccountRemote(driverApi: Provider<DriverApi>, mobileApi: Provider<MobileApi>): AccountRemote =
        if (BuildConfig.MOBILE_API_ENABLED) MobileAccountRemote(mobileApi.get()) else DriverAccountRemote(driverApi.get())

    private fun accountBaseUrl(): String =
        if (BuildConfig.MOBILE_API_ENABLED) BuildConfig.MOBILE_API_BASE_URL else BuildConfig.API_BASE_URL

    private fun retrofit(client: OkHttpClient, json: Json, baseUrl: String): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
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
    fun provideShiftRepository(
        demo: Provider<OfflineFirstShiftRepository>,
        real: Provider<RealShiftRepository>,
        daily: Provider<ApiShiftRepository>,
    ): ShiftRepository = when {
        isDemoMode() -> demo.get()
        isDriverShift() -> daily.get()
        else -> real.get()
    }

    @Provides
    fun provideDailyShiftRepository(daily: Provider<ApiShiftRepository>): DailyShiftRepository =
        if (isDriverShift()) daily.get() else UnavailableDailyShiftRepository

    @Provides
    fun provideVehicleCheckRepository(real: Provider<ApiVehicleCheckRepository>): VehicleCheckRepository =
        if (isDriverShift()) real.get() else UnavailableVehicleCheckRepository

    @Provides
    fun provideDriverTimesRepository(real: Provider<ApiDriverTimesRepository>): DriverTimesRepository =
        if (isDriverShift()) real.get() else UnavailableDriverTimesRepository

    @Provides
    fun provideDriverDaySync(real: Provider<ApiDriverDaySync>): DriverDaySync = if (isDriverShift()) real.get() else NoOpDriverDaySync

    @Provides
    fun provideDriverDaySessionData(real: Provider<ApiDriverDaySessionData>): DriverDaySessionData =
        if (isDriverShift()) real.get() else DriverDaySessionData.None

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
    fun provideAttendanceRepository(real: Provider<RealAttendanceRepository>): AttendanceRepository =
        if (isDemoMode()) UnavailableAttendanceRepository else real.get()

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

    @Binds
    abstract fun bindAttendanceSyncScheduler(impl: WorkManagerAttendanceSyncScheduler): AttendanceSyncScheduler

    @Binds
    abstract fun bindDeviceUptime(impl: AndroidDeviceUptime): DeviceUptime

    @Binds
    abstract fun bindNfcStatusProvider(impl: AndroidNfcStatusProvider): NfcStatusProvider

    @Binds
    abstract fun bindCheckPhotoStore(impl: FileCheckPhotoStore): CheckPhotoStore

    @Binds
    abstract fun bindShiftSyncScheduler(impl: WorkManagerShiftSyncScheduler): ShiftSyncScheduler

    @Binds
    abstract fun bindDriverDayWorkScheduler(impl: WorkManagerDriverDayWorkScheduler): DriverDayWorkScheduler

    @Binds
    abstract fun bindRestAlarmScheduler(impl: AndroidRestAlarmScheduler): RestAlarmScheduler

    @Binds
    abstract fun bindRestAlarmNotifier(impl: AndroidRestAlarmNotifier): RestAlarmNotifier

    @Binds
    abstract fun bindRestAlarmPermissions(impl: AndroidRestAlarmPermissions): RestAlarmPermissions

    @Binds
    abstract fun bindRestAlarmSettings(impl: DataStoreRestAlarmSettings): RestAlarmSettingsRepository
}
