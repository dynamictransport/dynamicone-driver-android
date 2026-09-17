package com.dynamictransport.dynamicone.testing

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.dynamictransport.dynamicone.core.database.AppDatabase
import com.dynamictransport.dynamicone.core.database.PendingActionDao
import com.dynamictransport.dynamicone.core.security.SessionPreferences
import com.dynamictransport.dynamicone.data.remote.fake.FakeDriverBackend
import com.dynamictransport.dynamicone.data.remote.fake.FakeDriverRemoteDataSource
import com.dynamictransport.dynamicone.data.repository.OfflineFirstDocumentRepository
import com.dynamictransport.dynamicone.data.repository.OfflineFirstIncidentRepository
import com.dynamictransport.dynamicone.data.repository.OfflineFirstShiftRepository
import com.dynamictransport.dynamicone.data.repository.OfflineFirstTripRepository
import com.dynamictransport.dynamicone.data.repository.SyncRepositoryImpl
import com.dynamictransport.dynamicone.data.sync.PendingActionWriter
import com.dynamictransport.dynamicone.data.sync.Projector
import com.dynamictransport.dynamicone.data.sync.SyncEngine
import com.dynamictransport.dynamicone.domain.model.Trip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import java.io.File
import java.time.ZoneOffset

/**
 * Real offline-first data layer (Room, queue, projections, sync engine) wired to the in-app fake
 * backend. Only the WorkManager scheduler is replaced so tests decide when sync runs.
 */
class DataHarness(
    context: Context,
    sessionDirectory: File,
    backendDirectory: File = sessionDirectory,
    databaseFile: File? = null,
) {
    val clock = MutableClock()
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    val database: AppDatabase = (
        if (databaseFile == null) {
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        } else {
            Room.databaseBuilder(context, AppDatabase::class.java, databaseFile.absolutePath)
        }
        ).allowMainThreadQueries().build()

    private val dataStoreScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val sessionPreferences = SessionPreferences(
        PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { File(sessionDirectory, "session.preferences_pb") },
        ),
    )

    val backend = FakeDriverBackend(
        clock = clock,
        json = json,
        stateFile = File(backendDirectory, "demo_backend.json"),
        zone = ZoneOffset.UTC,
    )

    val scheduler = RecordingSyncScheduler()
    val ids = SequenceIdGenerator()
    val pendingDao: PendingActionDao get() = database.pendingActionDao()

    val projector = Projector(database.tripDao(), database.shiftDao(), database.documentDao(), database.pendingActionDao())
    private val writer = PendingActionWriter(database.pendingActionDao(), ids, clock)

    val trips = OfflineFirstTripRepository(
        database, database.tripDao(), database.pendingActionDao(), writer, projector, sessionPreferences, scheduler, json,
    )
    val shifts = OfflineFirstShiftRepository(
        database, database.shiftDao(), writer, projector, sessionPreferences, scheduler, clock,
    )
    val incidents = OfflineFirstIncidentRepository(writer, sessionPreferences, scheduler, ids, json)
    val documents = OfflineFirstDocumentRepository(
        database, database.tripDao(), database.documentDao(), writer, sessionPreferences, scheduler, ids, json, clock,
    )

    val syncEngine = SyncEngine(
        database = database,
        tripDao = database.tripDao(),
        shiftDao = database.shiftDao(),
        documentDao = database.documentDao(),
        driverProfileDao = database.driverProfileDao(),
        pendingDao = database.pendingActionDao(),
        syncMetaDao = database.syncMetaDao(),
        remote = FakeDriverRemoteDataSource(backend),
        sessionPreferences = sessionPreferences,
        projector = projector,
        json = json,
        clock = clock,
        logger = NoOpLogger,
    )

    val syncRepository = SyncRepositoryImpl(
        database, database.pendingActionDao(), database.syncMetaDao(), projector, scheduler, syncEngine, clock,
    )

    suspend fun signIn() = sessionPreferences.setLoggedInUser(FakeDriverBackend.DEMO_DRIVER)

    suspend fun trip(id: String): Trip = trips.currentTrips().first { it.id == id }

    fun close() {
        database.close()
        dataStoreScope.cancel()
    }
}
