package com.dynamictransport.dynamicone.domain.usecase

import com.dynamictransport.dynamicone.domain.model.ActionRejection
import com.dynamictransport.dynamicone.domain.model.ActionResult
import com.dynamictransport.dynamicone.domain.model.IncidentCategory
import com.dynamictransport.dynamicone.domain.model.RejectReason
import com.dynamictransport.dynamicone.domain.model.TripActionType
import com.dynamictransport.dynamicone.domain.repository.IncidentRepository
import com.dynamictransport.dynamicone.domain.repository.ShiftRepository
import com.dynamictransport.dynamicone.domain.repository.TripActionCommand
import com.dynamictransport.dynamicone.domain.repository.TripRepository
import com.dynamictransport.dynamicone.domain.rules.DriverInputRules
import com.dynamictransport.dynamicone.domain.statemachine.ShiftStateMachine
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
        // A daily shift ends for the daily rest, also in the middle of a long trip (docs/16 §9).
        if (!shifts.requiresVehicleCheck && !ShiftStateMachine.canEndShift(trips.currentTrips())) {
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
