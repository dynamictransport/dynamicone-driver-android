package com.dynamictransport.dynamicone.testing

import com.dynamictransport.dynamicone.core.common.AppDirectories
import com.dynamictransport.dynamicone.core.database.PendingCommandEntity
import com.dynamictransport.dynamicone.core.database.PendingCommandKind
import com.dynamictransport.dynamicone.core.network.DriverApi
import com.dynamictransport.dynamicone.core.network.MobileApi
import com.dynamictransport.dynamicone.core.security.SessionPreferences
import com.dynamictransport.dynamicone.core.security.TokenManager
import com.dynamictransport.dynamicone.core.security.TokenPair
import com.dynamictransport.dynamicone.data.media.ProfilePhotoImages
import com.dynamictransport.dynamicone.data.real.AccountRemote
import com.dynamictransport.dynamicone.data.real.ApiActivityTracker
import com.dynamictransport.dynamicone.data.real.ApiVehiclePositionRepository
import com.dynamictransport.dynamicone.data.real.DriverAccountRemote
import com.dynamictransport.dynamicone.data.real.EmployeePhotoStore
import com.dynamictransport.dynamicone.data.real.HrDocumentFileStore
import com.dynamictransport.dynamicone.data.real.MobileAccountRemote
import com.dynamictransport.dynamicone.data.real.PendingCommandStore
import com.dynamictransport.dynamicone.data.real.ProtectedFileDownloader
import com.dynamictransport.dynamicone.data.real.RealAttendanceRepository
import com.dynamictransport.dynamicone.data.real.RealAuthRepository
import com.dynamictransport.dynamicone.data.real.RealDocumentRepository
import com.dynamictransport.dynamicone.data.real.RealHrDocumentRepository
import com.dynamictransport.dynamicone.data.real.RealMyDocumentRepository
import com.dynamictransport.dynamicone.data.real.RealVehicleDocumentRepository
import com.dynamictransport.dynamicone.data.real.RealIncidentRepository
import com.dynamictransport.dynamicone.data.real.RealMessageRepository
import com.dynamictransport.dynamicone.data.real.RealPayrollRepository
import com.dynamictransport.dynamicone.data.real.RealPushRegistrar
import com.dynamictransport.dynamicone.data.real.RealRoadSnapshotRepository
import com.dynamictransport.dynamicone.data.real.RealWakeUpCallRepository
import com.dynamictransport.dynamicone.data.real.RealSessionData
import com.dynamictransport.dynamicone.data.real.RealSignedDocumentRepository
import com.dynamictransport.dynamicone.data.real.RealSyncRepository
import com.dynamictransport.dynamicone.data.real.RealTripExecutionRepository
import com.dynamictransport.dynamicone.data.real.RealTripRepository
import com.dynamictransport.dynamicone.domain.model.AppModule
import com.dynamictransport.dynamicone.domain.model.PendingCommandState
import com.dynamictransport.dynamicone.domain.model.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

/**
 * The real-mode data layer and production HTTP stack against a MockWebServer speaking the Driver API contract.
 * [mobileAccount]: sign-in and token refresh through the Mobile API (`app.mobileApi=true`).
 */
class RealApiHarness(folder: TemporaryFolder, mobileAccount: Boolean = false) {
    val server = MockWebServer()
    val routes = RoutingDispatcher()
    val clock = MutableClock()
    val tokenStore = InMemoryTokenStore()
    val tokenManager = TokenManager(tokenStore)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val commandDao = InMemoryPendingCommandDao()
    val attendanceDao = InMemoryAttendancePunchDao()
    val uptime = FakeDeviceUptime()
    val attendanceScheduler = RecordingAttendanceSyncScheduler()
    val hrDao = InMemoryHrDocumentDao()
    val hrScheduler = RecordingHrDocumentSyncScheduler()
    val freeSpace = FakeFreeSpace()
    val photoImages = FakeProfilePhotoImages()
    val snapshotDao = InMemorySnapshotRequestDao()
    val snapshotScheduler = RecordingSnapshotSyncScheduler()
    val snapshotPreparer = FakeSnapshotPhotoPreparer()
    val deviceProfile = FakeDeviceProfile()
    val wakeUpDao = InMemoryWakeUpCallDao()
    val wakeUpScheduler = RecordingWakeUpSyncScheduler()

    /** In-memory DataStore: the JVM file rename DataStore relies on cannot replace files on Windows. */
    val sessionStore = InMemoryPreferencesDataStore()
    val sessionPreferences = SessionPreferences(sessionStore)
    val directories: AppDirectories = AppDirectories(folder.newFolder("files"), folder.newFolder("cache"))
    val tracker = ApiActivityTracker(clock, NoOpLogger)
    val api: DriverApi
    val mobileApi: MobileApi
    val trips: RealTripRepository
    val store: PendingCommandStore
    val executions: RealTripExecutionRepository
    val downloader: ProtectedFileDownloader
    val documents: RealDocumentRepository
    val payroll: RealPayrollRepository
    val signedDocuments: RealSignedDocumentRepository
    val messages: RealMessageRepository
    val sync: RealSyncRepository
    val incidents: RealIncidentRepository
    val pushRegistrar: RealPushRegistrar
    val attendance: RealAttendanceRepository
    val hrFiles: HrDocumentFileStore
    val hrDocuments: RealHrDocumentRepository
    val vehiclePosition: ApiVehiclePositionRepository
    val snapshots: RealRoadSnapshotRepository
    val wakeUps: RealWakeUpCallRepository
    val myDocuments: RealMyDocumentRepository
    val vehicleDocuments: RealVehicleDocumentRepository
    val photos: EmployeePhotoStore
    val auth: RealAuthRepository
    private val account: AccountRemote
    private val sessionData: RealSessionData

    init {
        server.dispatcher = routes
        server.start()
        val client = DriverApiTestClient(server, tokenManager, clock, if (mobileAccount) "/api/mobile/v1/" else "/api/driver/v1/")
        api = client.api
        mobileApi = client.mobileApi
        trips = RealTripRepository(api, tracker)
        store = PendingCommandStore(commandDao, sessionPreferences, clock)
        executions = RealTripExecutionRepository(api, tracker, trips, store, SequenceIdGenerator("cmd"), clock)
        downloader = ProtectedFileDownloader(tracker, directories, Dispatchers.IO)
        documents = RealDocumentRepository(api, tracker, trips, downloader, directories, Dispatchers.IO)
        payroll = RealPayrollRepository(api, tracker)
        signedDocuments = RealSignedDocumentRepository(api, tracker, downloader)
        messages = RealMessageRepository(api, tracker, store, trips, SequenceIdGenerator("msg"), clock)
        sync = RealSyncRepository(trips, messages, tracker, store, scope)
        incidents = RealIncidentRepository(trips, executions)
        attendance = RealAttendanceRepository(
            mobileApi, tracker, attendanceDao, sessionPreferences, attendanceScheduler, SequenceIdGenerator("punch"), clock, uptime,
        )
        hrFiles = HrDocumentFileStore(directories, freeSpace, Dispatchers.IO)
        hrDocuments = RealHrDocumentRepository(
            mobileApi, tracker, hrDao, hrFiles, downloader, sessionPreferences, hrScheduler, SequenceIdGenerator("sig"), clock, uptime, TEST_JSON,
        )
        vehiclePosition = ApiVehiclePositionRepository(api, tracker, sessionPreferences)
        snapshots = RealRoadSnapshotRepository(api, tracker, snapshotDao, sessionPreferences, snapshotScheduler, snapshotPreparer, directories, clock, Dispatchers.IO)
        wakeUps = RealWakeUpCallRepository(api, tracker, wakeUpDao, sessionPreferences, wakeUpScheduler, clock)
        myDocuments = RealMyDocumentRepository(mobileApi, tracker, downloader, sessionPreferences)
        vehicleDocuments = RealVehicleDocumentRepository(mobileApi, tracker, downloader, sessionPreferences)
        sessionData = RealSessionData(
            trips, executions, documents, payroll, signedDocuments, messages, sync, downloader, store, attendance, vehiclePosition,
            myDocuments, vehicleDocuments,
            hrDocuments = hrDocuments,
        )
        pushRegistrar = RealPushRegistrar(api, tokenManager, sessionPreferences, tracker, FakeAppInfo(), mobileApi, deviceProfile)
        account = if (mobileAccount) MobileAccountRemote(mobileApi) else DriverAccountRemote(api)
        photos = EmployeePhotoStore(account, tracker, directories, photoImages, Dispatchers.IO)
        auth = RealAuthRepository(account, tokenManager, sessionPreferences, tracker, sessionData, pushRegistrar, clock, scope, photos)
    }

    /** The app started again: new repositories over the same stored session and files. */
    fun restartedAuth(): RealAuthRepository = RealAuthRepository(
        account, tokenManager, sessionPreferences, tracker, sessionData, pushRegistrar, clock, scope,
        EmployeePhotoStore(account, tracker, directories, photoImages, Dispatchers.IO),
    )

    /** Stored tokens and the linked driver, as after a successful Driver API sign-in. */
    fun signIn(driverId: String = "driver-1") = runBlocking {
        tokenManager.store(TokenPair("access-1", "refresh-1", clock.now.plusSeconds(900), null))
        sessionPreferences.setLoggedInUser(UserProfile(driverId, "Anna Nowak", null, null, setOf(AppModule.DRIVER), driverId))
    }

    /** Stored tokens and an office account (Mobile API sign-in). */
    fun signInOffice(userId: String = "user-9") = runBlocking {
        tokenManager.store(TokenPair("access-1", "refresh-1", clock.now.plusSeconds(900), null))
        sessionPreferences.setLoggedInUser(UserProfile(userId, "Ayşe Yılmaz", null, null, setOf(AppModule.OFFICE_ATTENDANCE), null))
    }

    fun storedCommand(
        commandId: String,
        driverId: String = "driver-1",
        state: PendingCommandState = PendingCommandState.OUTCOME_UNKNOWN,
        updatedAt: Instant = clock.now,
    ) = PendingCommandEntity(
        commandId = commandId,
        driverId = driverId,
        kind = PendingCommandKind.TRIP_ACTION,
        tripId = "t1",
        payloadJson = """{"tripId":"t1","expectedVersion":"v7","action":"CompleteStop","stopId":"s1","containerId":"c1"}""",
        state = state,
        createdAt = updatedAt,
        updatedAt = updatedAt,
    )

    fun close() {
        server.shutdown()
        scope.cancel()
    }
}

/** Keeps the downloaded bytes as they are (no Android bitmap decoding on the JVM); [readable] = false rejects them. */
class FakeProfilePhotoImages : ProfilePhotoImages {
    var readable = true
    var calls = 0

    override fun reduce(source: File, output: File, maxEdgePx: Int): Boolean {
        calls++
        if (!readable) return false
        source.copyTo(output, overwrite = true)
        return true
    }
}
