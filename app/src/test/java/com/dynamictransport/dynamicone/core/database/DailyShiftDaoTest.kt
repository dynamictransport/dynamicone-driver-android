package com.dynamictransport.dynamicone.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dynamictransport.dynamicone.domain.model.ShiftCommandFailure
import com.dynamictransport.dynamicone.domain.model.ShiftCommandState
import com.dynamictransport.dynamicone.domain.model.ShiftCommandType
import com.dynamictransport.dynamicone.domain.model.VehicleIdentification
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class DailyShiftDaoTest {

    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val commands = database.shiftCommandDao()
    private val photos = database.checkPhotoDao()
    private val cache = database.driverCacheDao()

    @After
    fun tearDown() = database.close()

    private fun command(id: String, at: String, driverId: String = "driver-1", state: ShiftCommandState = ShiftCommandState.PENDING) =
        ShiftCommandEntity(
            commandId = id,
            driverId = driverId,
            type = ShiftCommandType.START,
            payloadJson = """{"commandId":"$id"}""",
            occurredAt = Instant.parse(at),
            occurredElapsedMillis = 1_000,
            bootCount = 3,
            state = state,
            failure = null,
            vehiclePlate = "WX 12345",
            identifiedBy = VehicleIdentification.NFC,
            defectCount = 0,
            startCommandId = null,
            checkId = null,
            createdAt = Instant.parse(at),
            updatedAt = Instant.parse(at),
        )

    private fun photo(id: String, commandId: String, state: ShiftCommandState = ShiftCommandState.PENDING) =
        CheckPhotoEntity(id, commandId, "/files/vehicle_check_media/$id.jpg", state, Instant.parse("2026-09-19T06:00:00Z"))

    @Test
    fun `unsent commands of one driver are sent oldest first`() = runBlocking {
        commands.insert(command("late", "2026-09-19T16:00:00Z"))
        commands.insert(command("early", "2026-09-19T06:00:00Z", state = ShiftCommandState.SENDING))
        commands.insert(command("done", "2026-09-18T06:00:00Z", state = ShiftCommandState.SYNCED))
        commands.insert(command("other", "2026-09-19T05:00:00Z", driverId = "driver-2"))

        assertEquals(listOf("early", "late"), commands.getUnsent("driver-1").map { it.commandId })
        assertEquals(listOf("late", "early", "done"), commands.observe("driver-1").first().map { it.commandId })
    }

    @Test
    fun `states, failures and the server's check id are stored`() = runBlocking {
        commands.insert(command("c1", "2026-09-19T06:00:00Z"))
        commands.updateState("c1", ShiftCommandState.FAILED, ShiftCommandFailure.TAG_UNKNOWN, Instant.parse("2026-09-19T06:01:00Z"))
        assertEquals(ShiftCommandFailure.TAG_UNKNOWN, commands.get("c1")?.failure)

        commands.markSynced("c1", "check-1", Instant.parse("2026-09-19T06:02:00Z"))
        val synced = commands.get("c1")!!
        assertEquals(ShiftCommandState.SYNCED, synced.state)
        assertNull(synced.failure)
        assertEquals("check-1", synced.checkId)
    }

    @Test
    fun `old confirmed commands and other drivers' data are removed, photos without a command are found`() = runBlocking {
        commands.insert(command("old", "2026-09-10T06:00:00Z", state = ShiftCommandState.SYNCED))
        commands.insert(command("waiting", "2026-09-10T07:00:00Z"))
        commands.insert(command("other", "2026-09-19T05:00:00Z", driverId = "driver-2"))
        photos.insertAll(listOf(photo("p1", "old"), photo("p2", "waiting"), photo("p3", "gone")))

        commands.deleteSyncedBefore(Instant.parse("2026-09-16T00:00:00Z"))
        commands.deleteForOtherDrivers("driver-1")

        assertEquals(listOf("waiting"), commands.getAll("driver-1").map { it.commandId })
        assertEquals(emptyList<ShiftCommandEntity>(), commands.getAll("driver-2"))
        assertEquals(listOf("p1", "p3"), photos.getOrphans().map { it.photoId }.sorted())
    }

    @Test
    fun `photos are replaced by id and follow their command`() = runBlocking {
        photos.insertAll(listOf(photo("p1", "c1"), photo("p2", "c1")))
        photos.insertAll(listOf(photo("p1", "c1", ShiftCommandState.SYNCED)))
        photos.updateState("p2", ShiftCommandState.FAILED, Instant.parse("2026-09-19T06:05:00Z"))

        assertEquals(listOf(ShiftCommandState.SYNCED, ShiftCommandState.FAILED), photos.forCommand("c1").map { it.state })
        assertEquals(2, photos.allFilePaths().size)
        photos.deleteForCommand("c1")
        assertEquals(emptyList<CheckPhotoEntity>(), photos.observeAll().first())
    }

    @Test
    fun `the driver cache is kept per driver and key`() = runBlocking {
        cache.upsert(DriverCacheEntity("driver-1", DriverCacheEntity.SHIFT, "{\"a\":1}", Instant.parse("2026-09-19T06:00:00Z")))
        cache.upsert(DriverCacheEntity("driver-1", DriverCacheEntity.SHIFT, "{\"a\":2}", Instant.parse("2026-09-19T06:05:00Z")))
        cache.upsert(DriverCacheEntity("driver-2", DriverCacheEntity.DRIVER_TIMES, "{}", Instant.parse("2026-09-19T06:00:00Z")))

        assertEquals("{\"a\":2}", cache.get("driver-1", DriverCacheEntity.SHIFT)?.json)
        cache.deleteForOtherDrivers("driver-1")
        assertNull(cache.get("driver-2", DriverCacheEntity.DRIVER_TIMES))
        cache.deleteAll()
        assertNull(cache.get("driver-1", DriverCacheEntity.SHIFT))
    }
}
