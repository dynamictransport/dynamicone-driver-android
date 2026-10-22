package com.dynamictransport.dynamicone.core.tar

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dynamictransport.dynamicone.core.database.AppDatabase
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.model.StopActivity
import com.dynamictransport.dynamicone.domain.model.StopStatus
import com.dynamictransport.dynamicone.domain.model.TarWarning
import com.dynamictransport.dynamicone.domain.model.TerminalAppointment
import com.dynamictransport.dynamicone.domain.model.Trip
import com.dynamictransport.dynamicone.domain.model.TripState
import com.dynamictransport.dynamicone.domain.model.TripStop
import com.dynamictransport.dynamicone.testing.FakeServerClock
import com.dynamictransport.dynamicone.testing.FakeTripRepository
import com.dynamictransport.dynamicone.testing.MutableClock
import com.dynamictransport.dynamicone.testing.TEST_NOW
import com.dynamictransport.dynamicone.testing.testStop
import com.dynamictransport.dynamicone.testing.testTrip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Collections

/** TAR warnings on the device alarm manager (docs/26 §6). */
@RunWith(AndroidJUnit4::class)
class TarWarningControllerTest {

    private class RecordingScheduler : TarWarningScheduler {
        val armed: MutableMap<String, Instant> = Collections.synchronizedMap(linkedMapOf())
        val cancelled: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun schedule(key: String, triggerAt: Instant) {
            armed[key] = triggerAt
        }

        override fun cancel(key: String) {
            cancelled += key
            armed.remove(key)
        }
    }

    private class RecordingNotifier : TarWarningNotifier {
        val shown: MutableList<TarWarning> = Collections.synchronizedList(mutableListOf())
        var cancelledAll = 0
        override fun ensureChannel() = Unit
        override fun notify(warning: TarWarning) {
            shown += warning
        }

        override fun cancelAll() {
            cancelledAll++
        }
    }

    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val dao = database.tarWarningDao()
    private val trips = FakeTripRepository()
    private val scheduler = RecordingScheduler()
    private val notifier = RecordingNotifier()
    private val clock = MutableClock()
    private val serverClock = FakeServerClock(clock)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val controller = TarWarningController(trips, dao, scheduler, notifier, serverClock, clock, scope)
    private val amsterdam = ZoneId.of("Europe/Amsterdam")

    /** A window that ends two hours after [TEST_NOW]. */
    private val end: Instant = TEST_NOW.plusSeconds(2 * 3_600)
    private val tar = TerminalAppointment("001234", TEST_NOW, end, amsterdam)

    private fun stop(status: StopStatus = StopStatus.PLANNED, tar: TerminalAppointment? = this.tar): TripStop =
        testStop("s1", 1, StopActivity.PICKUP, "ECT Delta", status = status).copy(tar = tar)

    private fun trip(stops: List<TripStop> = listOf(stop()), state: TripState = TripState.STARTED): Trip =
        testTrip(state = state, stops = stops)

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
    }

    @Test
    fun `a TAR on an open stop arms the four warnings on the device clock`() = runBlocking {
        trips.trips.value = listOf(trip())
        serverClock.offset.value = Duration.ofSeconds(30) // the server is 30 s ahead of the device
        controller.reconcile()

        assertEquals(listOf("trip-1/s1/60", "trip-1/s1/30", "trip-1/s1/15", "trip-1/s1/5"), scheduler.armed.keys.toList())
        // Server trigger time minus the offset: the device alarm goes off when the server reaches end - 60 min.
        assertEquals(end.minusSeconds(3_600 + 30), scheduler.armed["trip-1/s1/60"])
        assertEquals(4, dao.all().size)

        // The same trip read again changes nothing.
        trips.trips.value = listOf(trip().copy(instructions = "same window"))
        controller.reconcile()
        assertEquals(4, scheduler.armed.size)
        assertTrue(scheduler.cancelled.isEmpty())
    }

    @Test
    fun `started, the controller follows every trip change`() = runBlocking {
        trips.trips.value = listOf(trip())
        controller.start()
        withTimeout(5_000) { while (scheduler.armed.size < 4) delay(20) }

        trips.trips.value = listOf(trip(listOf(stop(status = StopStatus.COMPLETED))))
        withTimeout(5_000) { while (scheduler.armed.isNotEmpty()) delay(20) }
        assertTrue(dao.all().isEmpty())
    }

    @Test
    fun `a moved window re-arms, a removed TAR or a completed stop cancels`() = runBlocking {
        trips.trips.value = listOf(trip())
        controller.reconcile()

        val later = tar.copy(endAt = end.plusSeconds(1_800))
        trips.trips.value = listOf(trip(listOf(stop(tar = later))))
        controller.reconcile()
        assertEquals(later.endAt.minusSeconds(3_600), scheduler.armed["trip-1/s1/60"])
        assertEquals(later.endAt, dao.get("trip-1/s1/60")?.endAt)

        trips.trips.value = listOf(trip(listOf(stop(status = StopStatus.COMPLETED, tar = later))))
        controller.reconcile()
        assertTrue(scheduler.armed.isEmpty())
        assertTrue(dao.all().isEmpty())

        trips.trips.value = listOf(trip(listOf(stop(tar = later))))
        controller.reconcile()
        assertEquals(4, scheduler.armed.size)
        trips.trips.value = listOf(trip(listOf(stop(tar = null))))
        controller.reconcile()
        assertTrue(scheduler.armed.isEmpty())
    }

    @Test
    fun `an unread trip list or a trip without stops keeps the stored warnings, a list without the trip drops them`() = runBlocking {
        trips.trips.value = listOf(trip())
        controller.reconcile()
        assertEquals(4, scheduler.armed.size)

        trips.trips.value = emptyList()
        controller.reconcile()
        assertEquals(4, scheduler.armed.size)

        trips.trips.value = listOf(trip(stops = emptyList()))
        controller.reconcile()
        assertEquals(4, scheduler.armed.size)

        trips.trips.value = listOf(testTrip(id = "trip-2", state = TripState.STARTED))
        controller.reconcile()
        assertTrue(scheduler.armed.isEmpty())
        assertTrue(dao.all().isEmpty())
    }

    @Test
    fun `a warning that went off rings only while the stop still has that window`() = runBlocking {
        trips.trips.value = listOf(trip())
        controller.reconcile()

        clock.now = end.minusSeconds(3_600)
        controller.onFired("trip-1/s1/60")
        assertEquals(listOf(60L), notifier.shown.map { it.leadMinutes })
        assertEquals("001234", notifier.shown.single().code)
        assertNotNull(dao.get("trip-1/s1/60")?.firedAt)

        // The same alarm delivered twice rings once.
        controller.onFired("trip-1/s1/60")
        assertEquals(1, notifier.shown.size)

        // A rung warning is not armed again while the window is unchanged.
        scheduler.armed.clear()
        trips.trips.value = listOf(trip().copy(instructions = "again"))
        controller.reconcile()
        assertNull(scheduler.armed["trip-1/s1/60"])

        // The planner moved the window after the alarm was set: no ring for the old one.
        trips.trips.value = listOf(trip(listOf(stop(tar = tar.copy(endAt = end.plusSeconds(600))))))
        controller.reconcile()
        dao.upsert(checkNotNull(dao.get("trip-1/s1/30")).copy(endAt = end))
        controller.onFired("trip-1/s1/30")
        assertEquals(1, notifier.shown.size)

        // After the window nothing rings (the screen shows the expired state).
        clock.now = end.plusSeconds(1_200)
        controller.onFired("trip-1/s1/5")
        assertEquals(1, notifier.shown.size)
    }

    @Test
    fun `after a restart an unknown trip is read first and rings from the stored data only when the server is unreachable`() =
        runBlocking {
            trips.trips.value = listOf(trip())
            controller.reconcile()
            trips.trips.value = emptyList()
            controller.reconcile()
            clock.now = end.minusSeconds(1_800)

            // Read succeeds, trip gone: nothing.
            trips.refreshResult = RefreshResult.SUCCESS
            controller.onFired("trip-1/s1/30")
            assertTrue(notifier.shown.isEmpty())
            assertEquals(listOf("trip-1"), trips.refreshedTrips)

            // Offline: best effort from the stored data.
            trips.refreshResult = RefreshResult.OFFLINE
            controller.onFired("trip-1/s1/15")
            assertEquals(listOf(15L), notifier.shown.map { it.leadMinutes })

            // Rearm after a restart sets only the warnings still ahead and not yet rung.
            scheduler.armed.clear()
            controller.rearm()
            assertEquals(listOf("trip-1/s1/5"), scheduler.armed.keys.toList())
        }

    @Test
    fun `the end of the session cancels every warning`() = runBlocking {
        trips.trips.value = listOf(trip())
        controller.reconcile()

        controller.clear()
        assertTrue(scheduler.armed.isEmpty())
        assertTrue(dao.all().isEmpty())
        assertEquals(1, notifier.cancelledAll)
    }
}
