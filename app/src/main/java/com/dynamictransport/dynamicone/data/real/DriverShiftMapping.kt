package com.dynamictransport.dynamicone.data.real

import com.dynamictransport.dynamicone.core.common.toInstantOrNull
import com.dynamictransport.dynamicone.core.network.DriverErrorCodes
import com.dynamictransport.dynamicone.core.network.DriverShiftErrorCodes
import com.dynamictransport.dynamicone.core.network.DriverShiftWireNames
import com.dynamictransport.dynamicone.core.network.DriverTimesResponseDto
import com.dynamictransport.dynamicone.core.network.PlannedVehicleDto
import com.dynamictransport.dynamicone.core.network.RemoteException
import com.dynamictransport.dynamicone.core.network.ShiftDto
import com.dynamictransport.dynamicone.core.network.ShiftStartRequestDto
import com.dynamictransport.dynamicone.core.network.ShiftStateResponseDto
import com.dynamictransport.dynamicone.core.network.VehicleCheckAnswerDto
import com.dynamictransport.dynamicone.core.network.VehicleCheckRequestDto
import com.dynamictransport.dynamicone.core.network.VehicleCheckTemplateDto
import com.dynamictransport.dynamicone.core.network.VehicleTagResponseDto
import com.dynamictransport.dynamicone.domain.model.CheckItem
import com.dynamictransport.dynamicone.domain.model.CheckResult
import com.dynamictransport.dynamicone.domain.model.CheckTemplate
import com.dynamictransport.dynamicone.domain.model.DailyRest
import com.dynamictransport.dynamicone.domain.model.DailyRestState
import com.dynamictransport.dynamicone.domain.model.DriverActivity
import com.dynamictransport.dynamicone.domain.model.DriverTimes
import com.dynamictransport.dynamicone.domain.model.DriverTimesAvailability
import com.dynamictransport.dynamicone.domain.model.ServerShiftState
import com.dynamictransport.dynamicone.domain.model.ShiftCommandFailure
import com.dynamictransport.dynamicone.domain.model.ShiftEndedBy
import com.dynamictransport.dynamicone.domain.model.ShiftRecord
import com.dynamictransport.dynamicone.domain.model.ShiftStartDraft
import com.dynamictransport.dynamicone.domain.model.VehicleCheckSummary
import com.dynamictransport.dynamicone.domain.model.VehicleIdentification
import com.dynamictransport.dynamicone.domain.model.VehicleInfo
import java.time.Duration
import java.time.Instant

/** Driver API daily shift wire models ↔ domain (docs/16_DRIVER_SHIFT_REST_AND_VEHICLE_CHECK.md). */
object DriverShiftMapping {

    private val ITEM_KEY = Regex("^[a-z0-9_]{1,40}$")
    private const val MAX_ITEMS = 30

    /** Null for an unknown status: nothing can be decided from it. */
    fun driverTimes(dto: DriverTimesResponseDto): DriverTimes? {
        val availability = when (dto.status) {
            DriverShiftWireNames.TIMES_AVAILABLE -> DriverTimesAvailability.AVAILABLE
            DriverShiftWireNames.TIMES_NOT_LINKED -> return DriverTimes(DriverTimesAvailability.NOT_LINKED)
            DriverShiftWireNames.TIMES_NO_DATA -> return DriverTimes(DriverTimesAvailability.NO_DATA)
            else -> return null
        }
        return DriverTimes(
            availability = availability,
            referenceTime = dto.referenceTimeUtc?.toInstantOrNull(),
            activity = when (dto.currentActivity) {
                DriverShiftWireNames.ACTIVITY_DRIVING -> DriverActivity.DRIVING
                DriverShiftWireNames.ACTIVITY_OTHER_WORK -> DriverActivity.OTHER_WORK
                DriverShiftWireNames.ACTIVITY_STANDBY -> DriverActivity.STANDBY
                DriverShiftWireNames.ACTIVITY_BREAK -> DriverActivity.BREAK
                else -> DriverActivity.UNKNOWN
            },
            dailyRest = dto.dailyRest?.let { rest ->
                DailyRest(
                    state = when (rest.state) {
                        DriverShiftWireNames.REST_RESTING -> DailyRestState.RESTING
                        DriverShiftWireNames.REST_WORKING -> DailyRestState.WORKING
                        else -> DailyRestState.UNKNOWN
                    },
                    startedAt = rest.startedAtUtc?.toInstantOrNull(),
                    endsAt = rest.endsAtUtc?.toInstantOrNull(),
                    minimumDuration = rest.minimumDurationMinutes?.takeIf { it > 0 }?.let { Duration.ofMinutes(it.toLong()) },
                )
            },
            // Negative values are exceeded limits and stay negative; missing values stay unknown.
            remainingDrivingToday = dto.remainingDrivingTodayMinutes?.let { Duration.ofMinutes(it.toLong()) },
            remainingWorkingToday = dto.remainingWorkingTodayMinutes?.let { Duration.ofMinutes(it.toLong()) },
            nextBreakDueAt = dto.nextBreakDueUtc?.toInstantOrNull(),
            nextBreakMinimum = dto.nextBreakMinimumMinutes?.takeIf { it > 0 }?.let { Duration.ofMinutes(it.toLong()) },
            latestShiftEnd = dto.latestEndCurrentShiftUtc?.toInstantOrNull(),
            hasInfringement = dto.hasInfringement == true,
        )
    }

    fun shiftState(dto: ShiftStateResponseDto): ServerShiftState = ServerShiftState(
        active = dto.active?.let(::shiftRecord)?.takeIf { it.endedAt == null },
        lastEnded = dto.lastEnded?.let(::shiftRecord),
        plannedVehicle = dto.plannedVehicle?.let(::plannedVehicle),
    )

    /** A shift without id or start time cannot be shown and is skipped. */
    fun shiftRecord(dto: ShiftDto): ShiftRecord? {
        val id = dto.id?.takeIf { it.isNotBlank() } ?: return null
        val vehicle = dto.vehicle
        return ShiftRecord(
            id = id,
            commandId = dto.commandId?.takeIf { it.isNotBlank() },
            startedAt = dto.startedAtUtc?.toInstantOrNull() ?: return null,
            endedAt = dto.endedAtUtc?.toInstantOrNull(),
            endedBy = when (dto.endedBy) {
                null -> null
                DriverShiftWireNames.ENDED_BY_DRIVER -> ShiftEndedBy.DRIVER
                DriverShiftWireNames.ENDED_BY_TACHOGRAPH -> ShiftEndedBy.TACHOGRAPH
                DriverShiftWireNames.ENDED_BY_OFFICE -> ShiftEndedBy.OFFICE
                else -> ShiftEndedBy.UNKNOWN
            },
            vehicle = vehicle?.let { VehicleInfo(it.vehicleId, it.registrationNumber?.takeIf(String::isNotBlank)) },
            identifiedBy = when (vehicle?.identifiedBy) {
                DriverShiftWireNames.IDENTIFIED_NFC -> VehicleIdentification.NFC
                DriverShiftWireNames.IDENTIFIED_MANUAL -> VehicleIdentification.MANUAL
                else -> null
            },
            trailerPlate = dto.trailerRegistrationNumber?.takeIf { it.isNotBlank() },
            startedBeforeRestEnd = dto.startedBeforeRestEnd == true,
            vehicleCheck = dto.vehicleCheck?.let { check ->
                VehicleCheckSummary(
                    defectCount = check.defectCount?.coerceAtLeast(0) ?: 0,
                    photosExpected = check.photosExpected?.coerceAtLeast(0) ?: 0,
                    photosReceived = check.photosReceived?.coerceAtLeast(0) ?: 0,
                )
            },
        )
    }

    fun plannedVehicle(dto: PlannedVehicleDto): VehicleInfo? {
        val plate = dto.registrationNumber?.takeIf { it.isNotBlank() } ?: return null
        return VehicleInfo(
            vehicleId = dto.vehicleId?.takeIf { it.isNotBlank() },
            registrationNumber = plate,
            trailerRegistrationNumber = dto.trailerRegistrationNumber?.takeIf { it.isNotBlank() },
        )
    }

    fun vehicleTag(dto: VehicleTagResponseDto): VehicleInfo? {
        val plate = dto.registrationNumber?.takeIf { it.isNotBlank() } ?: return null
        return VehicleInfo(
            vehicleId = dto.vehicleId?.takeIf { it.isNotBlank() },
            registrationNumber = plate,
            displayName = dto.displayName?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * Null when the list is unusable: no items, an invalid or repeated key, or an item without an English title. A
     * partial list is never used, because the server expects an answer for every item.
     */
    fun template(dto: VehicleCheckTemplateDto): CheckTemplate? {
        val items = dto.items?.takeIf { it.isNotEmpty() && it.size <= MAX_ITEMS } ?: return null
        val mapped = items.map { item ->
            val key = item.key?.takeIf { ITEM_KEY.matches(it) } ?: return null
            val titles = item.titles.orEmpty().filterValues { it.isNotBlank() }
            if (titles["en"] == null) return null
            CheckItem(
                key = key,
                titles = titles,
                hints = item.hints.orEmpty().filterValues { it.isNotBlank() },
                photoRequiredOnDefect = item.photoRequiredOnDefect != false,
                notApplicableAllowed = item.notApplicableAllowed == true,
            )
        }
        if (mapped.map { it.key }.toSet().size != mapped.size) return null
        return CheckTemplate(
            templateId = dto.templateId?.takeIf { it.isNotBlank() },
            version = dto.version ?: return null,
            items = mapped,
        )
    }

    /** The start as stored and sent every time; the elapsed time is added per attempt. */
    fun startRequest(draft: ShiftStartDraft, startedAt: Instant, installationId: String) = ShiftStartRequestDto(
        commandId = draft.commandId,
        vehicleTagUid = draft.tagUid,
        manualVehicleId = if (draft.tagUid == null) draft.manualVehicle?.vehicleId else null,
        tagScannedAtUtc = draft.tagScannedAt.toString(),
        startedAtUtc = startedAt.toString(),
        installationId = installationId,
        restWarningAcknowledged = draft.restWarningAcknowledged,
        vehicleCheck = VehicleCheckRequestDto(
            templateId = draft.template.templateId,
            templateVersion = draft.template.version,
            items = draft.answers.map { answer ->
                val defect = answer.result == CheckResult.DEFECT
                VehicleCheckAnswerDto(
                    key = answer.key,
                    result = when (answer.result) {
                        CheckResult.OK -> DriverShiftWireNames.CHECK_OK
                        CheckResult.DEFECT -> DriverShiftWireNames.CHECK_DEFECT
                        CheckResult.NOT_APPLICABLE -> DriverShiftWireNames.CHECK_NOT_APPLICABLE
                    },
                    note = answer.note?.takeIf { defect },
                    photoIds = answer.photos.map { it.photoId }.takeIf { defect && it.isNotEmpty() },
                )
            },
        ),
    )

    /**
     * The server's final answer (400/409, docs/16 §2): the command is shown as not recorded with its reason. Null means
     * it was not processed or its outcome is unknown: the same command id is sent again later.
     */
    fun failure(e: RemoteException): ShiftCommandFailure? = when (e) {
        is RemoteException.Validation -> when (e.problem?.code) {
            DriverShiftErrorCodes.VEHICLE_TAG_UNKNOWN -> ShiftCommandFailure.TAG_UNKNOWN
            DriverShiftErrorCodes.VEHICLE_CHECK_INVALID -> ShiftCommandFailure.CHECK_INVALID
            DriverShiftErrorCodes.SHIFT_TIME_INVALID, DriverShiftErrorCodes.SHIFT_TOO_OLD -> ShiftCommandFailure.TIME_REJECTED
            else -> ShiftCommandFailure.REJECTED
        }
        is RemoteException.Conflict -> when (e.problem?.code) {
            DriverShiftErrorCodes.SHIFT_ALREADY_ACTIVE -> ShiftCommandFailure.ALREADY_ACTIVE
            DriverShiftErrorCodes.SHIFT_NOT_ACTIVE -> ShiftCommandFailure.NOT_ACTIVE
            else -> ShiftCommandFailure.CONFLICT
        }
        is RemoteException.Rejected, is RemoteException.PayloadTooLarge -> ShiftCommandFailure.REJECTED
        // Offline, 5xx, unreadable response, 401 (session), 403 (see hold), 404 (not live or disabled), 429.
        else -> null
    }

    /** `403 driver_access_required` keeps the command waiting with its reason; the password screen comes first. */
    fun hold(e: RemoteException): ShiftCommandFailure? = when {
        e !is RemoteException.Forbidden -> null
        e.problem?.code == DriverErrorCodes.PASSWORD_CHANGE_REQUIRED -> null
        else -> ShiftCommandFailure.ACCESS_REQUIRED
    }
}
