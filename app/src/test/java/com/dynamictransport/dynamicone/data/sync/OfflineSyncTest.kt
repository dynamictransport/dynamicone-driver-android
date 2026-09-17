package com.dynamictransport.dynamicone.data.sync

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dynamictransport.dynamicone.core.sync.SyncRunResult
import com.dynamictransport.dynamicone.domain.model.ActionRejection
import com.dynamictransport.dynamicone.domain.model.ActionResult
import com.dynamictransport.dynamicone.domain.model.BreakState
import com.dynamictransport.dynamicone.domain.model.DocumentSlotState
import com.dynamictransport.dynamicone.domain.model.DocumentType
import com.dynamictransport.dynamicone.domain.model.IncidentCategory
import com.dynamictransport.dynamicone.domain.model.PendingActionState
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.model.ShiftStatus
import com.dynamictransport.dynamicone.domain.model.SyncErrorCode
import com.dynamictransport.dynamicone.domain.model.SyncState
import com.dynamictransport.dynamicone.domain.model.TripActionType
import com.dynamictransport.dynamicone.domain.model.TripState
import com.dynamictransport.dynamicone.domain.repository.TripActionCommand
import com.dynamictransport.dynamicone.domain.statemachine.TripSnapshot
import com.dynamictransport.dynamicone.domain.statemachine.TripStateMachine
import com.dynamictransport.dynamicone.testing.DataHarness
import com.dynamictransport.dynamicone.testing.TEST_NOW
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineSyncTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var harness: DataHarness
    private val newTrip = "trp-261245"

    private fun command(action: TripActionType, tripId: String = newTrip) = TripActionCommand(tripId, action)

    @Before
    fun setUp() {
        harness = DataHarness(ApplicationProvider.getApplicationContext(), tempFolder.newFolder())
        runBlocking {
            harness.signIn()
            assertEquals(RefreshResult.SUCCESS, harness.syncEngine.refresh())
        }
    }

    @After
    fun tearDown() = harness.close()

    @Test
    fun `offline accept is stored, shown as pending and sent once when back online`() = runTest {
        harness.backend.simulateOffline.value = true

        assertEquals(ActionResult.Queued, harness.trips.submitAction(command(TripActionType.ACCEPT_TRIP)))
        val optimistic = harness.trip(newTrip)
        assertEquals(TripState.ACCEPTED, optimistic.state)
        assertEquals(SyncState.PENDING, optimistic.syncState)
        assertEquals(1, harness.scheduler.requests)

        assertEquals(SyncRunResult.RETRY, harness.syncEngine.sync())
        val waiting = harness.pendingDao.getAll().single()
        assertEquals(PendingActionState.FAILED_RETRYABLE, waiting.state)
        assertEquals(SyncErrorCode.OFFLINE, waiting.lastError)
        assertEquals(SyncState.PENDING, harness.trip(newTrip).syncState)

        harness.backend.simulateOffline.value = false
        assertEquals(SyncRunResult.COMPLETED, harness.syncEngine.sync())

        assertEquals(PendingActionState.SYNCED, harness.pendingDao.getAll().single().state)
        val synced = harness.trip(newTrip)
        assertEquals(TripState.ACCEPTED, synced.state)
        assertEquals(SyncState.SYNCED, synced.syncState)
        assertEquals(setOf(waiting.clientActionId), harness.backend.processedActionIds())
        assertEquals(TripState.ACCEPTED, harness.backend.trip(newTrip).state)
    }

    @Test
    fun `resending an action whose response was lost does not duplicate it`() = runTest {
        harness.trips.submitAction(command(TripActionType.ACCEPT_TRIP))
        harness.syncEngine.sync()
        val sent = harness.pendingDao.getAll().single()
        val processed = harness.backend.processedActionIds()

        // The server applied the action but the device never got the answer.
        harness.pendingDao.updateState(sent.id, PendingActionState.PENDING, 0, null, TEST_NOW)
        assertEquals(SyncRunResult.COMPLETED, harness.syncEngine.sync())

        assertEquals(PendingActionState.SYNCED, harness.pendingDao.getByClientActionId(sent.clientActionId)?.state)
        assertEquals(processed, harness.backend.processedActionIds())
        assertEquals(TripState.ACCEPTED, harness.backend.trip(newTrip).state)
    }

    @Test
    fun `double tap creates a single pending action`() = runTest {
        val first = async { harness.trips.submitAction(command(TripActionType.ACCEPT_TRIP)) }
        val second = async { harness.trips.submitAction(command(TripActionType.ACCEPT_TRIP)) }
        val results = listOf(first.await(), second.await())

        assertEquals(1, results.count { it == ActionResult.Queued })
        assertEquals(1, results.count { it == ActionResult.Rejected(ActionRejection.NOT_ALLOWED) })
        assertEquals(1, harness.pendingDao.getAll().size)
    }

    @Test
    fun `a stale stored trip projection cannot let a duplicate action through`() = runTest {
        val beforeAccept = checkNotNull(harness.database.tripDao().get(newTrip))
        assertEquals(ActionResult.Queued, harness.trips.submitAction(command(TripActionType.ACCEPT_TRIP)))

        // A concurrent writer that read the row before the tap stores the pre-action projection.
        harness.database.tripDao().upsert(beforeAccept)

        assertEquals(
            ActionResult.Rejected(ActionRejection.NOT_ALLOWED),
            harness.trips.submitAction(command(TripActionType.ACCEPT_TRIP)),
        )
        assertEquals(1, harness.pendingDao.getAll().size)
    }

    @Test
    fun `a stale stored shift projection cannot start the shift twice`() = runTest {
        val beforeStart = checkNotNull(harness.database.shiftDao().get())
        assertEquals(ActionResult.Queued, harness.shifts.startShift())

        harness.database.shiftDao().upsert(beforeStart)

        assertEquals(ActionResult.Rejected(ActionRejection.NOT_ALLOWED), harness.shifts.startShift())
        assertEquals(1, harness.pendingDao.getAll().size)
    }

    @Test
    fun `sync applying server data keeps an action recorded before it`() = runTest {
        harness.trips.submitAction(command(TripActionType.ACCEPT_TRIP))
        harness.syncEngine.sync()
        harness.shifts.startShift()
        harness.trips.submitAction(command(TripActionType.START_TRIP))

        // Pull-only refresh must re-project with the still unsent START_TRIP.
        assertEquals(RefreshResult.SUCCESS, harness.syncEngine.refresh())

        val trip = harness.trip(newTrip)
        assertEquals(TripState.STARTED, trip.state)
        assertEquals(SyncState.PENDING, trip.syncState)
    }

    @Test
    fun `server conflict is shown as a failure and the server state wins`() = runTest {
        harness.shifts.startShift()
        harness.trips.submitAction(command(TripActionType.ACCEPT_TRIP))
        assertEquals(SyncRunResult.COMPLETED, harness.syncEngine.sync())

        harness.backend.simulateOffline.value = true
        harness.trips.submitAction(command(TripActionType.START_TRIP))
        harness.trips.submitAction(command(TripActionType.ARRIVE_PICKUP))
        assertEquals(TripState.AT_PICKUP, harness.trip(newTrip).state)

        harness.backend.plannerCancelTrip(newTrip)
        harness.backend.simulateOffline.value = false
        harness.syncEngine.sync()

        val failed = harness.pendingDao.getAll().filter {
            it.tripAction == TripActionType.START_TRIP || it.tripAction == TripActionType.ARRIVE_PICKUP
        }
        assertTrue(failed.all { it.state == PendingActionState.FAILED_PERMANENT })
        assertEquals(listOf(SyncErrorCode.CONFLICT, SyncErrorCode.PRECEDING_ACTION_FAILED), failed.map { it.lastError })
        val current = harness.trip(newTrip)
        assertEquals(TripState.CANCELLED, current.state)
        assertEquals(SyncState.FAILED, current.syncState)

        val overview = harness.syncRepository.observeOverview().first()
        assertEquals(2, overview.failedCount)
        assertEquals(0, overview.pendingCount)

        failed.forEach { harness.syncRepository.acknowledgeFailure(it.clientActionId) }
        assertEquals(SyncState.SYNCED, harness.trip(newTrip).syncState)
        assertEquals(2, harness.pendingDao.getAll().count { it.state == PendingActionState.FAILED_PERMANENT })
    }

    @Test
    fun `pull keeps the optimistic state of unsent actions`() = runTest {
        harness.trips.submitAction(command(TripActionType.ACCEPT_TRIP))

        assertEquals(RefreshResult.SUCCESS, harness.syncEngine.refresh())

        val trip = harness.trip(newTrip)
        assertEquals(TripState.ACCEPTED, trip.state)
        assertEquals(SyncState.PENDING, trip.syncState)
        assertEquals(TripState.ASSIGNED, harness.database.tripDao().get(newTrip)?.serverState)
    }

    @Test
    fun `interrupted sends are resent after an app restart`() = runTest {
        harness.trips.submitAction(command(TripActionType.ACCEPT_TRIP))
        val action = harness.pendingDao.getAll().single()
        harness.pendingDao.updateState(action.id, PendingActionState.SENDING, 0, null, TEST_NOW)

        harness.syncEngine.recoverAfterRestart()
        assertEquals(PendingActionState.PENDING, harness.pendingDao.getAll().single().state)

        assertEquals(SyncRunResult.COMPLETED, harness.syncEngine.sync())
        assertEquals(PendingActionState.SYNCED, harness.pendingDao.getAll().single().state)
    }

    @Test
    fun `queued actions survive a full app restart`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val databaseFile = tempFolder.newFile("driver.db").also { it.delete() }
        val backendDirectory = tempFolder.newFolder("backend")

        val beforeRestart = DataHarness(context, tempFolder.newFolder("session-1"), backendDirectory, databaseFile)
        beforeRestart.signIn()
        beforeRestart.syncEngine.refresh()
        beforeRestart.backend.simulateOffline.value = true
        beforeRestart.trips.submitAction(command(TripActionType.ACCEPT_TRIP))
        beforeRestart.close()

        val afterRestart = DataHarness(context, tempFolder.newFolder("session-2"), backendDirectory, databaseFile)
        try {
            afterRestart.signIn()
            afterRestart.syncEngine.recoverAfterRestart()
            assertEquals(1, afterRestart.pendingDao.getAll().size)
            assertEquals(SyncState.PENDING, afterRestart.trip(newTrip).syncState)

            assertEquals(SyncRunResult.COMPLETED, afterRestart.syncEngine.sync())
            assertEquals(TripState.ACCEPTED, afterRestart.backend.trip(newTrip).state)
        } finally {
            afterRestart.close()
        }
    }

    @Test
    fun `nothing is sent without a signed in driver and nothing is deleted`() = runTest {
        harness.trips.submitAction(command(TripActionType.ACCEPT_TRIP))
        harness.sessionPreferences.clearLoggedInUser(expired = true)

        assertEquals(SyncRunResult.COMPLETED, harness.syncEngine.sync())

        assertEquals(PendingActionState.PENDING, harness.pendingDao.getAll().single().state)
        assertTrue(harness.backend.processedActionIds().isEmpty())
    }

    @Test
    fun `offline break is projected and only end break remains possible`() = runTest {
        harness.shifts.startShift()
        harness.trips.submitAction(command(TripActionType.ACCEPT_TRIP))
        harness.trips.submitAction(command(TripActionType.START_TRIP))
        harness.syncEngine.sync()
        harness.backend.simulateOffline.value = true

        assertEquals(ActionResult.Queued, harness.trips.submitAction(command(TripActionType.START_BREAK)))
        val onBreak = harness.trip(newTrip)
        assertEquals(BreakState.ON_BREAK, onBreak.breakState)
        assertEquals(TripState.STARTED, onBreak.state)
        assertEquals(
            setOf(TripActionType.END_BREAK),
            TripStateMachine.allowedActions(TripSnapshot(onBreak.state, onBreak.breakState), onBreak.serverAllowedActions),
        )
        assertEquals(
            ActionResult.Rejected(ActionRejection.NOT_ALLOWED),
            harness.trips.submitAction(command(TripActionType.ARRIVE_PICKUP)),
        )
    }

    @Test
    fun `shift start and incident report are delivered to the backend`() = runTest {
        harness.backend.simulateOffline.value = true
        harness.shifts.startShift()
        harness.incidents.report(newTrip, IncidentCategory.TERMINAL_QUEUE, "Long queue at gate")
        assertEquals(ShiftStatus.ACTIVE, harness.shifts.currentShift().status)
        assertEquals(SyncState.PENDING, harness.shifts.currentShift().syncState)

        harness.backend.simulateOffline.value = false
        assertEquals(SyncRunResult.COMPLETED, harness.syncEngine.sync())

        assertEquals(ShiftStatus.ACTIVE, harness.backend.currentShift().status)
        assertEquals(SyncState.SYNCED, harness.shifts.currentShift().syncState)
        assertEquals(IncidentCategory.TERMINAL_QUEUE, harness.backend.incidents().single().category)
    }

    @Test
    fun `document photo is queued with metadata and uploaded`() = runTest {
        harness.trips.submitAction(command(TripActionType.ACCEPT_TRIP))
        val photo = tempFolder.newFile("seal.jpg").apply { writeBytes(ByteArray(128) { 1 }) }
        val sealSlot = harness.documents.observeSlots(newTrip).first()!!.first { it.type == DocumentType.SEAL_PHOTO }

        assertEquals(ActionResult.Queued, harness.documents.upload(newTrip, sealSlot, photo.absolutePath))
        val waiting = harness.documents.observeSlots(newTrip).first()!!.first { it.type == DocumentType.SEAL_PHOTO }
        assertEquals(SyncState.PENDING, waiting.localSync)
        val stored = harness.database.documentDao().getForTrip(newTrip).single()
        assertEquals("781392", stored.sealNo)

        harness.syncEngine.sync()
        val slots = harness.documents.observeSlots(newTrip).first()!!
        val uploaded = slots.first { it.type == DocumentType.SEAL_PHOTO }
        assertEquals(SyncState.SYNCED, uploaded.localSync)
        // Uploaded evidence waits for the check; it is never shown as approved.
        assertEquals(DocumentSlotState.PENDING_REVIEW, uploaded.state)
        val cmr = slots.first { it.type == DocumentType.CMR }
        assertEquals(DocumentSlotState.MISSING, cmr.state)
        // The demo backend has no requirement checklist: nothing is claimed to be required.
        assertNull(cmr.required)
    }

    @Test
    fun `empty photo files are refused`() = runTest {
        val empty = tempFolder.newFile("empty.jpg")
        val cmrSlot = harness.documents.observeSlots(newTrip).first()!!.first { it.type == DocumentType.CMR }
        assertEquals(
            ActionResult.Rejected(ActionRejection.FILE_ERROR),
            harness.documents.upload(newTrip, cmrSlot, empty.absolutePath),
        )
    }
}
