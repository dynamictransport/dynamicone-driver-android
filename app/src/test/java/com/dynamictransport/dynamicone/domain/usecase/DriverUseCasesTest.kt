package com.dynamictransport.dynamicone.domain.usecase

import com.dynamictransport.dynamicone.domain.model.ActionRejection
import com.dynamictransport.dynamicone.domain.model.ActionResult
import com.dynamictransport.dynamicone.domain.model.IncidentCategory
import com.dynamictransport.dynamicone.domain.model.RejectReason
import com.dynamictransport.dynamicone.domain.model.Shift
import com.dynamictransport.dynamicone.domain.model.TripActionType
import com.dynamictransport.dynamicone.domain.model.TripState
import com.dynamictransport.dynamicone.domain.repository.TripActionCommand
import com.dynamictransport.dynamicone.domain.rules.DriverInputRules
import com.dynamictransport.dynamicone.testing.ACTIVE_SHIFT
import com.dynamictransport.dynamicone.testing.FakeIncidentRepository
import com.dynamictransport.dynamicone.testing.FakeShiftRepository
import com.dynamictransport.dynamicone.testing.FakeTripRepository
import com.dynamictransport.dynamicone.testing.testTrip
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriverUseCasesTest {

    private val trips = FakeTripRepository()
    private val shifts = FakeShiftRepository()
    private val submit = SubmitTripActionUseCase(trips, shifts)

    @Test
    fun `reject needs a preset reason`() = runTest {
        assertEquals(ActionResult.Rejected(ActionRejection.REASON_REQUIRED), submit("t", TripActionType.REJECT_TRIP))
        assertTrue(trips.submitted.isEmpty())
    }

    @Test
    fun `reject with OTHER needs a short explanation`() = runTest {
        assertEquals(
            ActionResult.Rejected(ActionRejection.NOTE_REQUIRED),
            submit("t", TripActionType.REJECT_TRIP, RejectReason.OTHER, "   "),
        )
        assertEquals(ActionResult.Queued, submit("t", TripActionType.REJECT_TRIP, RejectReason.OTHER, "  Wrong trailer  "))
        assertEquals(TripActionCommand("t", TripActionType.REJECT_TRIP, RejectReason.OTHER, "Wrong trailer"), trips.submitted.single())
    }

    @Test
    fun `preset reject reasons do not need a note`() = runTest {
        assertEquals(ActionResult.Queued, submit("t", TripActionType.REJECT_TRIP, RejectReason.HEALTH))
        assertEquals(TripActionCommand("t", TripActionType.REJECT_TRIP, RejectReason.HEALTH, null), trips.submitted.single())
    }

    @Test
    fun `reject note longer than the limit is refused`() {
        val tooLong = "x".repeat(DriverInputRules.REJECT_NOTE_MAX_LENGTH + 1)
        assertEquals(ActionRejection.NOTE_TOO_LONG, DriverInputRules.validateReject(RejectReason.OTHER, tooLong))
    }

    @Test
    fun `reason and note are never sent with progress actions`() = runTest {
        shifts.shift.value = ACTIVE_SHIFT
        submit("t", TripActionType.START_TRIP, RejectReason.HEALTH, "note")
        assertEquals(TripActionCommand("t", TripActionType.START_TRIP), trips.submitted.single())
    }

    @Test
    fun `a trip cannot be started without an active shift`() = runTest {
        shifts.shift.value = Shift.NotStarted
        assertEquals(ActionResult.Rejected(ActionRejection.SHIFT_NOT_ACTIVE), submit("t", TripActionType.START_TRIP))
        assertTrue(trips.submitted.isEmpty())
    }

    @Test
    fun `shift cannot end while a trip is in progress`() = runTest {
        trips.trips.value = listOf(testTrip(state = TripState.AT_PICKUP))
        val endShift = EndShiftUseCase(shifts, trips)
        assertEquals(ActionResult.Rejected(ActionRejection.TRIP_IN_PROGRESS), endShift())
        assertEquals(0, shifts.endCalls)

        trips.trips.value = listOf(testTrip(state = TripState.FINISHED))
        assertEquals(ActionResult.Queued, endShift())
        assertEquals(1, shifts.endCalls)
    }

    @Test
    fun `incident needs a category and keeps the note optional`() = runTest {
        val incidents = FakeIncidentRepository()
        val report = ReportIncidentUseCase(incidents)
        assertEquals(ActionResult.Rejected(ActionRejection.REASON_REQUIRED), report("t", null, "note"))
        assertEquals(ActionResult.Queued, report("t", IncidentCategory.TERMINAL_QUEUE, "   "))
        assertEquals(FakeIncidentRepository.Report("t", IncidentCategory.TERMINAL_QUEUE, null), incidents.reports.single())
    }
}
