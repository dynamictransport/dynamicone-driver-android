package com.dynamictransport.driver.domain.statemachine

import com.dynamictransport.driver.domain.model.ShiftStatus
import com.dynamictransport.driver.domain.model.Trip

enum class ShiftActionType {
    START_SHIFT,
    END_SHIFT,
}

object ShiftStateMachine {

    fun isLegal(status: ShiftStatus, action: ShiftActionType): Boolean = when (action) {
        ShiftActionType.START_SHIFT -> status != ShiftStatus.ACTIVE && status != ShiftStatus.UNAVAILABLE
        ShiftActionType.END_SHIFT -> status == ShiftStatus.ACTIVE
    }

    fun apply(status: ShiftStatus, action: ShiftActionType): ShiftStatus? {
        if (!isLegal(status, action)) return null
        return when (action) {
            ShiftActionType.START_SHIFT -> ShiftStatus.ACTIVE
            ShiftActionType.END_SHIFT -> ShiftStatus.FINISHED
        }
    }

    /** docs/01_DRIVER_JOURNEY.md G: the shift can only be ended when no trip is in progress. */
    fun canEndShift(trips: List<Trip>): Boolean = trips.none { it.state.isInProgress }
}
