package com.dynamictransport.driver.domain.usecase

import com.dynamictransport.driver.domain.model.ActionRejection
import com.dynamictransport.driver.domain.model.ActionResult
import com.dynamictransport.driver.domain.model.IncidentCategory
import com.dynamictransport.driver.domain.model.RejectReason
import com.dynamictransport.driver.domain.model.TripActionType
import com.dynamictransport.driver.domain.repository.IncidentRepository
import com.dynamictransport.driver.domain.repository.ShiftRepository
import com.dynamictransport.driver.domain.repository.TripActionCommand
import com.dynamictransport.driver.domain.repository.TripRepository
import com.dynamictransport.driver.domain.rules.DriverInputRules
import com.dynamictransport.driver.domain.statemachine.ShiftStateMachine
import javax.inject.Inject

class SubmitTripActionUseCase @Inject constructor(
    private val trips: TripRepository,
    private val shifts: ShiftRepository,
) {
    suspend operator fun invoke(
        tripId: String,
        action: TripActionType,
        rejectReason: RejectReason? = null,
        note: String? = null,
    ): ActionResult {
        if (action == TripActionType.REJECT_TRIP) {
            DriverInputRules.validateReject(rejectReason, note)?.let { return ActionResult.Rejected(it) }
        }
        if (action == TripActionType.START_TRIP) {
            val shift = shifts.currentShift()
            if (shift.isTracked && !shift.isActive) return ActionResult.Rejected(ActionRejection.SHIFT_NOT_ACTIVE)
        }
        val isReject = action == TripActionType.REJECT_TRIP
        return trips.submitAction(
            TripActionCommand(
                tripId = tripId,
                action = action,
                rejectReason = rejectReason.takeIf { isReject },
                note = DriverInputRules.normalizeNote(note).takeIf { isReject },
            ),
        )
    }
}

class EndShiftUseCase @Inject constructor(
    private val shifts: ShiftRepository,
    private val trips: TripRepository,
) {
    suspend operator fun invoke(): ActionResult {
        if (!shifts.currentShift().isTracked) return ActionResult.Rejected(ActionRejection.NOT_SUPPORTED)
        if (!ShiftStateMachine.canEndShift(trips.currentTrips())) {
            return ActionResult.Rejected(ActionRejection.TRIP_IN_PROGRESS)
        }
        return shifts.endShift()
    }
}

class ReportIncidentUseCase @Inject constructor(
    private val incidents: IncidentRepository,
) {
    suspend operator fun invoke(tripId: String?, category: IncidentCategory?, note: String?): ActionResult {
        DriverInputRules.validateIncident(category, note)?.let { return ActionResult.Rejected(it) }
        return incidents.report(tripId, requireNotNull(category), DriverInputRules.normalizeNote(note))
    }
}
