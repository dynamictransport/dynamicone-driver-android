package com.dynamictransport.dynamicone.feature.attendance

import com.dynamictransport.dynamicone.core.nfc.NfcAvailability
import com.dynamictransport.dynamicone.domain.model.AttendanceDay
import com.dynamictransport.dynamicone.domain.model.AttendanceDayState
import com.dynamictransport.dynamicone.domain.model.AttendanceDirection
import com.dynamictransport.dynamicone.domain.model.AttendanceMonth
import com.dynamictransport.dynamicone.domain.model.AttendancePunch
import com.dynamictransport.dynamicone.domain.model.AttendancePunchSource
import com.dynamictransport.dynamicone.domain.model.AttendanceStatus
import com.dynamictransport.dynamicone.domain.model.LocalPunch
import com.dynamictransport.dynamicone.domain.model.LocalPunchState
import com.dynamictransport.dynamicone.domain.model.PunchFailure
import com.dynamictransport.dynamicone.domain.model.PunchResult
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.model.SyncState
import com.dynamictransport.dynamicone.testing.FakeAttendanceRepository
import com.dynamictransport.dynamicone.testing.FakeAuthRepository
import com.dynamictransport.dynamicone.testing.FakeNetworkMonitor
import com.dynamictransport.dynamicone.testing.FakeNfcStatusProvider
import com.dynamictransport.dynamicone.testing.MainDispatcherRule
import com.dynamictransport.dynamicone.testing.MutableClock
import com.dynamictransport.dynamicone.testing.TEST_NOW
import com.dynamictransport.dynamicone.testing.keepCollecting
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.TimeZone

private val TAG_ID = byteArrayOf(0x04, 0xA2.toByte(), 0x24, 0xB2.toByte(), 0xC3.toByte(), 0x5E, 0x80.toByte())
private val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")

class AttendanceViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val attendance = FakeAttendanceRepository()
    private val nfc = FakeNfcStatusProvider()
    private val network = FakeNetworkMonitor()
    private lateinit var defaultZone: TimeZone

    @Before
    fun setUp() {
        defaultZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun tearDown() = TimeZone.setDefault(defaultZone)

    private fun viewModel(clock: MutableClock = MutableClock()) =
        AttendanceViewModel(attendance, FakeAuthRepository(), network, nfc, clock)

    private fun checkedInStatus() = AttendanceStatus(
        checkedIn = true,
        nextDirection = AttendanceDirection.OUT,
        workDate = LocalDate.of(2026, 9, 12),
        zone = WARSAW,
        lastPunch = AttendancePunch("p-1", "cmd-1", AttendanceDirection.IN, TEST_NOW.minusSeconds(3_600), null, AttendancePunchSource.MOBILE_NFC, "Entrance"),
    )

    @Test
    fun `starting work waits for a tag and records the scan with the tag identifier`() = runTest {
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)
        viewModel.onResume()

        viewModel.onPrimaryAction()
        assertEquals(AttendanceDirection.IN, viewModel.uiState.value.scanning)

        viewModel.onTagRead(TAG_ID)
        advanceUntilIdle()

        assertEquals(listOf(AttendanceDirection.IN to "04A224B2C35E80"), attendance.recorded)
        val state = viewModel.uiState.value
        assertNull(state.scanning)
        assertTrue(state.outcome is PunchOutcome.Recorded)
        assertEquals(1, attendance.statusRefreshes)
    }

    @Test
    fun `a checked-in account is offered to end work`() = runTest {
        attendance.status.value = checkedInStatus()
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)

        viewModel.onPrimaryAction()

        val state = viewModel.uiState.value
        assertTrue(state.situation!!.checkedIn)
        assertEquals(AttendanceDirection.OUT, state.scanning)
        assertEquals(listOf("p-1"), state.today.map { it.key })
    }

    @Test
    fun `holding the phone on the tag or tapping twice records one punch`() = runTest {
        attendance.punchGate = CompletableDeferred()
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)

        viewModel.onPrimaryAction()
        viewModel.onTagRead(TAG_ID)
        viewModel.onTagRead(TAG_ID)
        viewModel.onPrimaryAction()
        assertTrue(viewModel.uiState.value.submitting)
        attendance.punchGate?.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, attendance.recorded.size)
        assertNull(viewModel.uiState.value.scanning)
    }

    @Test
    fun `a tag read while not waiting for one is ignored`() = runTest {
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)

        viewModel.onTagRead(TAG_ID)
        advanceUntilIdle()

        assertTrue(attendance.recorded.isEmpty())
    }

    @Test
    fun `switched off or missing NFC does not start waiting`() = runTest {
        nfc.value = NfcAvailability.DISABLED
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)

        viewModel.onPrimaryAction()
        assertNull(viewModel.uiState.value.scanning)
        assertEquals(NfcAvailability.DISABLED, viewModel.uiState.value.nfc)

        // Back from the settings with NFC on.
        nfc.value = NfcAvailability.AVAILABLE
        viewModel.onResume()
        viewModel.onPrimaryAction()
        assertEquals(AttendanceDirection.IN, viewModel.uiState.value.scanning)
    }

    @Test
    fun `an unreadable tag is reported and nothing is recorded`() = runTest {
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)

        viewModel.onPrimaryAction()
        viewModel.onTagRead(byteArrayOf(1, 2))

        assertEquals(PunchOutcome.UnreadableTag, viewModel.uiState.value.outcome)
        assertTrue(attendance.recorded.isEmpty())
    }

    @Test
    fun `a rejected or waiting punch is never shown as recorded`() = runTest {
        attendance.punchResult = { PunchResult.Failed(PunchFailure.WRONG_TAG_DIRECTION) }
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)

        viewModel.onPrimaryAction()
        viewModel.onTagRead(TAG_ID)
        advanceUntilIdle()
        assertEquals(PunchOutcome.Failed(AttendanceDirection.IN, PunchFailure.WRONG_TAG_DIRECTION), viewModel.uiState.value.outcome)

        attendance.punchResult = { PunchResult.Saved("cmd-2") }
        viewModel.onPrimaryAction()
        viewModel.onTagRead(TAG_ID)
        advanceUntilIdle()
        assertEquals(PunchOutcome.Saved(AttendanceDirection.IN), viewModel.uiState.value.outcome)
    }

    @Test
    fun `waiting and rejected punches on the phone are listed with their state`() = runTest {
        attendance.local.value = listOf(
            LocalPunch("cmd-2", AttendanceDirection.OUT, TEST_NOW, LocalPunchState.PENDING, null, null, null),
            LocalPunch("cmd-1", AttendanceDirection.IN, TEST_NOW.minusSeconds(60), LocalPunchState.FAILED, PunchFailure.TAG_UNKNOWN, null, null),
        )
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)

        val state = viewModel.uiState.value
        assertEquals(1, state.waitingCount)
        assertEquals(listOf("cmd-1"), state.failed.map { it.commandId })
        assertEquals(listOf(SyncState.PENDING), state.today.map { it.syncState })

        viewModel.onResend(state.failed.single())
        viewModel.onDismiss(state.failed.single())
        advanceUntilIdle()
        assertEquals(listOf("cmd-1"), attendance.resent)
        assertEquals(listOf("cmd-1"), attendance.dismissed)
    }

    @Test
    fun `a punch the server did not take yet is shown with its reason, never as recorded`() = runTest {
        attendance.punchResult = { PunchResult.Saved("cmd-3", PunchFailure.ACCESS_REQUIRED) }
        // Waiting since yesterday: the office has not enabled attendance for this account yet.
        attendance.local.value = listOf(
            LocalPunch(
                "cmd-3", AttendanceDirection.IN, TEST_NOW.minusSeconds(86_400), LocalPunchState.PENDING, PunchFailure.ACCESS_REQUIRED, null, null,
            ),
        )
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)

        viewModel.onPrimaryAction()
        viewModel.onTagRead(TAG_ID)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(PunchOutcome.Saved(AttendanceDirection.IN, PunchFailure.ACCESS_REQUIRED), state.outcome)
        assertEquals(1, state.waitingCount)
        assertEquals(PunchFailure.ACCESS_REQUIRED, state.waitingHold)
        assertTrue(state.failed.isEmpty())
    }

    @Test
    fun `an account whose office access was removed is told so and cannot scan`() = runTest {
        attendance.refreshResult = RefreshResult.ACCESS_REQUIRED
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)
        viewModel.onResume()
        advanceUntilIdle()

        viewModel.onPrimaryAction()

        val state = viewModel.uiState.value
        assertTrue(state.accessRemoved)
        assertFalse(state.unavailable)
        assertNull(state.refreshError)
        assertNull(state.scanning)
    }

    @Test
    fun `at midnight the state is read again and yesterday's check-in does not offer ending work`() = runTest {
        // 23:30 in the office time zone, checked in since the morning.
        val clock = MutableClock(Instant.parse("2026-09-12T21:30:00Z"))
        attendance.status.value = AttendanceStatus(
            checkedIn = true,
            nextDirection = AttendanceDirection.OUT,
            workDate = LocalDate.of(2026, 9, 12),
            zone = WARSAW,
            lastPunch = AttendancePunch(
                "p-1", "cmd-1", AttendanceDirection.IN, Instant.parse("2026-09-12T06:00:00Z"), null, AttendancePunchSource.MOBILE_NFC, null,
            ),
        )
        val viewModel = viewModel(clock)
        keepCollecting(viewModel.uiState)
        viewModel.onResume()
        advanceUntilIdle()
        assertEquals(AttendanceDirection.OUT, viewModel.uiState.value.situation?.nextDirection)

        // 00:05 of the next work day while the screen stayed open.
        clock.now = Instant.parse("2026-09-12T22:05:00Z")
        viewModel.onTimeChanged()
        advanceUntilIdle()

        val situation = viewModel.uiState.value.situation!!
        assertEquals(LocalDate.of(2026, 9, 13), situation.today)
        assertFalse(situation.checkedIn)
        assertEquals(AttendanceDirection.IN, situation.nextDirection)
        assertEquals(2, attendance.statusRefreshes)
    }

    @Test
    fun `a server without attendance shows it as not connected and offers no scan`() = runTest {
        attendance.refreshResult = RefreshResult.NOT_SUPPORTED
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)
        viewModel.onResume()
        advanceUntilIdle()

        viewModel.onPrimaryAction()

        assertTrue(viewModel.uiState.value.unavailable)
        assertNull(viewModel.uiState.value.scanning)
    }

    @Test
    fun `offline the screen still works from what the phone knows`() = runTest {
        attendance.refreshResult = RefreshResult.OFFLINE
        network.online.value = false
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)
        viewModel.onResume()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.loading)
        assertFalse(state.isOnline)
        assertNull(state.refreshError)
        viewModel.onPrimaryAction()
        assertEquals(AttendanceDirection.IN, viewModel.uiState.value.scanning)
    }
}

class AttendanceRecordsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val attendance = FakeAttendanceRepository()
    private val september = YearMonth.of(2026, 9)

    private fun punch(id: String, commandId: String?, direction: AttendanceDirection, at: String) =
        AttendancePunch(id, commandId, direction, Instant.parse(at), null, AttendancePunchSource.MOBILE_NFC, null)

    @Test
    fun `the month starts at the current one and cannot go into the future`() = runTest {
        val viewModel = AttendanceRecordsViewModel(attendance, MutableClock())
        keepCollecting(viewModel.uiState)
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(september, viewModel.uiState.value.month)
        assertFalse(viewModel.uiState.value.canGoForward)
        viewModel.onNextMonth()
        assertEquals(september, viewModel.uiState.value.month)

        viewModel.onPreviousMonth()
        advanceUntilIdle()
        assertEquals(YearMonth.of(2026, 8), viewModel.uiState.value.month)
        assertEquals(listOf(september, YearMonth.of(2026, 8)), attendance.refreshedMonths)
    }

    @Test
    fun `punches the server does not list yet are added to their day with their send state`() {
        val server = AttendanceMonth(
            month = september,
            zone = WARSAW,
            days = listOf(
                AttendanceDay(
                    workDate = LocalDate.of(2026, 9, 10),
                    state = AttendanceDayState.COMPLETE,
                    workedMinutes = 480,
                    punches = listOf(
                        punch("p-1", "cmd-1", AttendanceDirection.IN, "2026-09-10T06:00:00Z"),
                        punch("p-2", "cmd-2", AttendanceDirection.OUT, "2026-09-10T14:00:00Z"),
                    ),
                ),
            ),
        )
        val local = listOf(
            // Already listed by the server.
            LocalPunch("cmd-2", AttendanceDirection.OUT, Instant.parse("2026-09-10T14:00:00Z"), LocalPunchState.SYNCED, null, Instant.parse("2026-09-10T14:00:00Z"), null),
            // Waiting on the phone.
            LocalPunch("cmd-3", AttendanceDirection.IN, Instant.parse("2026-09-11T06:05:00Z"), LocalPunchState.PENDING, null, null, null),
            // Rejected punches belong to the attendance screen.
            LocalPunch("cmd-4", AttendanceDirection.OUT, Instant.parse("2026-09-11T15:00:00Z"), LocalPunchState.FAILED, PunchFailure.TAG_UNKNOWN, null, null),
            // Another month.
            LocalPunch("cmd-5", AttendanceDirection.IN, Instant.parse("2026-08-31T06:00:00Z"), LocalPunchState.PENDING, null, null, null),
        )

        val days = AttendanceRecordsViewModel.mergeDays(september, server, local, WARSAW)

        assertEquals(listOf(LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 10)), days.map { it.date })
        assertNull(days[0].state)
        assertEquals(listOf("cmd-3"), days[0].punches.map { it.key })
        assertEquals(SyncState.PENDING, days[0].punches.single().syncState)
        assertEquals(listOf("p-1", "p-2"), days[1].punches.map { it.key })
        assertEquals(480, days[1].workedMinutes)
    }
}
