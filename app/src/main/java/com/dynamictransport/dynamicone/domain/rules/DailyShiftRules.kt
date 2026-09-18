package com.dynamictransport.dynamicone.domain.rules

import com.dynamictransport.dynamicone.domain.model.ActionRejection
import com.dynamictransport.dynamicone.domain.model.CheckAnswer
import com.dynamictransport.dynamicone.domain.model.CheckItem
import com.dynamictransport.dynamicone.domain.model.CheckResult
import com.dynamictransport.dynamicone.domain.model.CheckTemplate
import com.dynamictransport.dynamicone.domain.model.DriverTimes
import com.dynamictransport.dynamicone.domain.model.LocalShiftCommand
import com.dynamictransport.dynamicone.domain.model.ServerShiftState
import com.dynamictransport.dynamicone.domain.model.Shift
import com.dynamictransport.dynamicone.domain.model.ShiftCommandType
import com.dynamictransport.dynamicone.domain.model.ShiftEndedBy
import com.dynamictransport.dynamicone.domain.model.ShiftRecord
import com.dynamictransport.dynamicone.domain.model.ShiftStartDraft
import com.dynamictransport.dynamicone.domain.model.ShiftStatus
import com.dynamictransport.dynamicone.domain.model.SyncState
import com.dynamictransport.dynamicone.domain.model.VehicleCheckSummary
import java.time.Duration
import java.time.Instant

/** The driver's daily shift from the server state, the commands still on the phone and the tachograph (docs/16). */
object DailyShiftRules {

    /**
     * The server state is the base; starts and ends the server has not confirmed yet are newer knowledge and apply on
     * top, oldest first. A running shift ends when the tachograph shows a daily rest that began after it (§9.1).
     */
    fun resolve(server: ServerShiftState?, commands: List<LocalShiftCommand>, times: DriverTimes?): Shift {
        var shift = when {
            server?.active != null -> server.active.toShift(ShiftStatus.ACTIVE)
            server?.lastEnded != null -> server.lastEnded.toShift(ShiftStatus.FINISHED)
            else -> Shift.NotStarted.copy(requiresVehicleCheck = true)
        }
        commands.filter { it.isUnsent }.sortedBy { it.occurredAt }.forEach { command ->
            shift = when (command.type) {
                ShiftCommandType.START ->
                    if (shift.isActive && shift.startCommandId == command.commandId) shift else command.toStartedShift()
                ShiftCommandType.END ->
                    if (shift.isActive) shift.copy(status = ShiftStatus.FINISHED, endedAt = command.occurredAt, syncState = SyncState.PENDING) else shift
            }
        }
        val rest = times?.restingRest
        val restStart = rest?.startedAt
        val shiftStart = shift.startedAt
        if (shift.isActive && restStart != null && shiftStart != null && restStart.isAfter(shiftStart)) {
            shift = shift.copy(status = ShiftStatus.FINISHED, endedAt = restStart, endedByTachograph = true)
        }
        return shift
    }

    /** The tachograph rest is not over yet: starting now needs the driver's confirmation (docs/16 §1). */
    fun restNotOver(times: DriverTimes?, now: Instant): Duration? {
        val end = times?.restingRest?.endsAt ?: return null
        return Duration.between(now, end).takeIf { !it.isNegative && !it.isZero }
    }

    private fun ShiftRecord.toShift(status: ShiftStatus) = Shift(
        id = id,
        status = status,
        startedAt = startedAt,
        endedAt = endedAt,
        vehiclePlate = vehicle?.registrationNumber,
        trailerPlate = trailerPlate,
        requiresVehicleCheck = true,
        startCommandId = commandId,
        identifiedBy = identifiedBy,
        vehicleCheck = vehicleCheck,
        startedBeforeRestEnd = startedBeforeRestEnd,
        endedByTachograph = endedBy == ShiftEndedBy.TACHOGRAPH,
    )

    private fun LocalShiftCommand.toStartedShift() = Shift(
        id = null,
        status = ShiftStatus.ACTIVE,
        startedAt = occurredAt,
        endedAt = null,
        vehiclePlate = vehiclePlate,
        trailerPlate = null,
        syncState = SyncState.PENDING,
        requiresVehicleCheck = true,
        startCommandId = commandId,
        identifiedBy = identifiedBy,
        vehicleCheck = VehicleCheckSummary(defectCount = defectCount, photosExpected = photosWaiting, photosReceived = 0),
    )
}

/** Completeness of the daily vehicle check before it is sent (docs/16 §7). */
object VehicleCheckRules {
    const val NOTE_MAX_LENGTH = 500
    const val MAX_PHOTOS_PER_ITEM = 3

    /** Null when every item has an allowed answer and every defect has the photos it needs. */
    fun validate(template: CheckTemplate, answers: List<CheckAnswer>): ActionRejection? {
        val byKey = answers.associateBy { it.key }
        if (answers.size != byKey.size || byKey.keys != template.items.map { it.key }.toSet()) return ActionRejection.NOT_ALLOWED
        for (item in template.items) {
            val answer = byKey.getValue(item.key)
            when (answer.result) {
                CheckResult.OK -> if (answer.photos.isNotEmpty() || answer.note != null) return ActionRejection.NOT_ALLOWED
                CheckResult.NOT_APPLICABLE -> if (!item.notApplicableAllowed) return ActionRejection.NOT_ALLOWED
                CheckResult.DEFECT -> {
                    if (item.photoRequiredOnDefect && answer.photos.isEmpty()) return ActionRejection.PHOTO_REQUIRED
                    if (answer.photos.size > MAX_PHOTOS_PER_ITEM) return ActionRejection.NOT_ALLOWED
                    if ((answer.note?.length ?: 0) > NOTE_MAX_LENGTH) return ActionRejection.NOTE_TOO_LONG
                }
            }
        }
        return null
    }

    /** The item may move on: answered, and a defect has its required photo. */
    fun isItemComplete(item: CheckItem, answer: CheckAnswer?): Boolean {
        if (answer == null || answer.key != item.key) return false
        return when (answer.result) {
            CheckResult.OK -> true
            CheckResult.NOT_APPLICABLE -> item.notApplicableAllowed
            CheckResult.DEFECT -> !item.photoRequiredOnDefect || answer.photos.isNotEmpty()
        }
    }

    /** A start the server can take: one way of naming the vehicle and a complete check. */
    fun validate(draft: ShiftStartDraft): ActionRejection? {
        if ((draft.tagUid == null) == (draft.manualVehicle?.vehicleId == null)) return ActionRejection.NOT_ALLOWED
        return validate(draft.template, draft.answers)
    }

    /** Trims the note; blank becomes null. */
    fun normalizeNote(note: String?): String? = note?.trim()?.takeIf { it.isNotEmpty() }?.take(NOTE_MAX_LENGTH)
}
