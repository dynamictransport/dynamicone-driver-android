package com.dynamictransport.dynamicone.core.network

import kotlinx.serialization.Serializable

// Driver daily shift, tachograph summary and vehicle check (docs/16_DRIVER_SHIFT_REST_AND_VEHICLE_CHECK.md).
// Every response field is optional: a missing field never crashes the app, the mapping decides what is usable.

@Serializable
data class DriverTimesDailyRestDto(
    val state: String? = null,
    val startedAtUtc: String? = null,
    val endsAtUtc: String? = null,
    val minimumDurationMinutes: Int? = null,
)

@Serializable
data class DriverTimesResponseDto(
    val status: String? = null,
    val referenceTimeUtc: String? = null,
    val receivedAtUtc: String? = null,
    val currentActivity: String? = null,
    val dailyRest: DriverTimesDailyRestDto? = null,
    val remainingDrivingTodayMinutes: Int? = null,
    val remainingWorkingTodayMinutes: Int? = null,
    val nextBreakDueUtc: String? = null,
    val nextBreakMinimumMinutes: Int? = null,
    val latestEndCurrentShiftUtc: String? = null,
    val hasInfringement: Boolean? = null,
)

@Serializable
data class ShiftVehicleDto(
    val vehicleId: String? = null,
    val registrationNumber: String? = null,
    val identifiedBy: String? = null,
)

@Serializable
data class ShiftVehicleCheckDto(
    val id: String? = null,
    val result: String? = null,
    val defectCount: Int? = null,
    val photosExpected: Int? = null,
    val photosReceived: Int? = null,
)

@Serializable
data class ShiftDto(
    val id: String? = null,
    val commandId: String? = null,
    val startedAtUtc: String? = null,
    val endedAtUtc: String? = null,
    val endedBy: String? = null,
    val vehicle: ShiftVehicleDto? = null,
    val trailerRegistrationNumber: String? = null,
    val startedBeforeRestEnd: Boolean? = null,
    val vehicleCheck: ShiftVehicleCheckDto? = null,
)

@Serializable
data class PlannedVehicleDto(
    val vehicleId: String? = null,
    val registrationNumber: String? = null,
    val trailerRegistrationNumber: String? = null,
)

@Serializable
data class ShiftStateResponseDto(
    val active: ShiftDto? = null,
    val lastEnded: ShiftDto? = null,
    val plannedVehicle: PlannedVehicleDto? = null,
)

@Serializable
data class VehicleTagResponseDto(
    val vehicleId: String? = null,
    val registrationNumber: String? = null,
    val displayName: String? = null,
    val isPlannedVehicle: Boolean? = null,
)

@Serializable
data class VehicleCheckItemDto(
    val key: String? = null,
    val titles: Map<String, String>? = null,
    val hints: Map<String, String>? = null,
    val photoRequiredOnDefect: Boolean? = null,
    val notApplicableAllowed: Boolean? = null,
)

@Serializable
data class VehicleCheckTemplateDto(
    val templateId: String? = null,
    val version: Int? = null,
    val items: List<VehicleCheckItemDto>? = null,
)

@Serializable
data class VehicleCheckAnswerDto(
    val key: String,
    val result: String,
    val note: String? = null,
    val photoIds: List<String>? = null,
)

@Serializable
data class VehicleCheckRequestDto(
    val templateId: String?,
    val templateVersion: Int,
    val items: List<VehicleCheckAnswerDto>,
)

/**
 * `POST shift/start`. Everything except [elapsedSinceStartMs] is the command's identity: it is stored when the driver
 * confirms and resent unchanged (docs/16 §2).
 */
@Serializable
data class ShiftStartRequestDto(
    val commandId: String,
    val vehicleTagUid: String?,
    val manualVehicleId: String?,
    val tagScannedAtUtc: String,
    val startedAtUtc: String,
    val elapsedSinceStartMs: Long? = null,
    val installationId: String,
    val restWarningAcknowledged: Boolean,
    val vehicleCheck: VehicleCheckRequestDto,
) {
    // The tag identifier and the driver's notes are evidence: keep them out of logs.
    override fun toString(): String = "ShiftStartRequestDto(commandId=$commandId)"
}

@Serializable
data class ShiftStartCheckResultDto(
    val id: String? = null,
    val pendingPhotoIds: List<String>? = null,
)

@Serializable
data class ShiftStartResponseDto(
    val shift: ShiftDto? = null,
    val vehicleCheck: ShiftStartCheckResultDto? = null,
)

/** `POST shift/end`; [elapsedSinceEndMs] is the only field that changes between attempts. */
@Serializable
data class ShiftEndRequestDto(
    val commandId: String,
    val shiftId: String?,
    val startCommandId: String?,
    val endedAtUtc: String,
    val elapsedSinceEndMs: Long? = null,
    val installationId: String,
)

@Serializable
data class ShiftEndResponseDto(
    val shift: ShiftDto? = null,
)

@Serializable
data class CheckPhotoResponseDto(
    val photoId: String? = null,
    val receivedAtUtc: String? = null,
)

/** Wire names of docs/16 (case-sensitive text, never numbers). */
object DriverShiftWireNames {
    const val TIMES_AVAILABLE = "Available"
    const val TIMES_NOT_LINKED = "NotLinked"
    const val TIMES_NO_DATA = "NoData"

    const val ACTIVITY_DRIVING = "Driving"
    const val ACTIVITY_OTHER_WORK = "OtherWork"
    const val ACTIVITY_STANDBY = "Standby"
    const val ACTIVITY_BREAK = "Break"

    const val REST_RESTING = "Resting"
    const val REST_WORKING = "Working"

    const val IDENTIFIED_NFC = "Nfc"
    const val IDENTIFIED_MANUAL = "Manual"

    const val ENDED_BY_DRIVER = "Driver"
    const val ENDED_BY_TACHOGRAPH = "Tachograph"
    const val ENDED_BY_OFFICE = "Office"

    const val CHECK_OK = "Ok"
    const val CHECK_DEFECT = "Defect"
    const val CHECK_NOT_APPLICABLE = "NotApplicable"
}

/** Problem `code` values of docs/16. */
object DriverShiftErrorCodes {
    const val VEHICLE_TAG_UNKNOWN = "vehicle_tag_unknown"
    const val VEHICLE_CHECK_INVALID = "vehicle_check_invalid"
    const val SHIFT_TIME_INVALID = "shift_time_invalid"
    const val SHIFT_TOO_OLD = "shift_too_old"
    const val SHIFT_ALREADY_ACTIVE = "shift_already_active"
    const val SHIFT_NOT_ACTIVE = "shift_not_active"
    const val COMMAND_CONFLICT = "command_conflict"
    const val PHOTO_UNEXPECTED = "photo_unexpected"
}
