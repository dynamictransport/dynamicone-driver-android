package com.dynamictransport.dynamicone.core.network

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * DynamicONE Mobile API v1, relative to `/api/mobile/v1/` (docs/15_MOBILE_APP_API.md). Sign-in and `me` for every
 * module plus office attendance. Driver screens keep using [DriverApi] with the same tokens. The account is always
 * resolved from the token: no user, employee or driver id is sent.
 */
interface MobileApi {

    /** Same request and token response as the Driver API; any account with at least one module may sign in. */
    @POST("auth/login")
    suspend fun login(@Body body: DriverLoginRequestDto): DriverTokenResponseDto

    @POST("auth/logout")
    suspend fun logout(): Response<Unit>

    @POST("auth/change-password")
    suspend fun changePassword(@Body body: DriverPasswordRequestDto): Response<Unit>

    @GET("me")
    suspend fun me(): MobileProfileResponseDto

    // --- Office attendance ---

    @GET("attendance/status")
    suspend fun attendanceStatus(): AttendanceStatusDto

    /** 201 for a new punch, 200 with the first result when the same `commandId` is sent again. */
    @POST("attendance/punches")
    suspend fun recordPunch(@Body body: AttendancePunchRequestDto): AttendancePunchResultDto

    /** Work dates, both inclusive, at most 31 days. */
    @GET("attendance/days")
    suspend fun attendanceDays(
        @Query("from") from: String,
        @Query("to") to: String,
    ): AttendanceDaysResponseDto
}

@Serializable
data class MobileProfileResponseDto(
    val userId: String? = null,
    val employeeId: String? = null,
    val driverId: String? = null,
    val displayName: String? = null,
    val preferredLanguageCode: String? = null,
    val modules: List<String>? = null,
)

@Serializable
data class AttendancePunchDto(
    val id: String? = null,
    val commandId: String? = null,
    val direction: String? = null,
    val occurredAtUtc: String? = null,
    val receivedAtUtc: String? = null,
    val workDate: String? = null,
    val source: String? = null,
    val pointName: String? = null,
)

@Serializable
data class AttendanceStatusDto(
    val state: String? = null,
    val nextAction: String? = null,
    val workDate: String? = null,
    val timeZoneId: String? = null,
    val lastPunch: AttendancePunchDto? = null,
)

@Serializable
data class AttendancePunchRequestDto(
    val commandId: String,
    val direction: String,
    val tagUid: String,
    val scannedAtUtc: String,
    /** Monotonic time between the scan and this request; null when the phone restarted in between. */
    val elapsedSinceScanMs: Long?,
    val installationId: String,
) {
    // The tag identifier is attendance evidence: keep it out of logs.
    override fun toString(): String = "AttendancePunchRequestDto(commandId=$commandId, direction=$direction)"
}

@Serializable
data class AttendancePunchResultDto(
    val punch: AttendancePunchDto? = null,
    val status: AttendanceStatusDto? = null,
)

@Serializable
data class AttendanceDayDto(
    val workDate: String? = null,
    val state: String? = null,
    val workedMinutes: Int? = null,
    val punches: List<AttendancePunchDto>? = null,
)

@Serializable
data class AttendanceDaysResponseDto(
    val timeZoneId: String? = null,
    val days: List<AttendanceDayDto>? = null,
)

/** Wire names of the attendance contract (case-sensitive text, never numbers). */
object AttendanceWireNames {
    const val IN = "In"
    const val OUT = "Out"
    const val CHECKED_IN = "CheckedIn"
    const val CHECK_IN = "CheckIn"
    const val CHECK_OUT = "CheckOut"
    const val SOURCE_MOBILE_NFC = "MobileNfc"
    const val SOURCE_TERMINAL = "Terminal"
    const val SOURCE_CORRECTION = "Correction"
    const val DAY_COMPLETE = "Complete"
    const val DAY_OPEN = "Open"
    const val DAY_INCOMPLETE = "Incomplete"
    const val DAY_NEEDS_REVIEW = "NeedsReview"
}

/** Problem `code` values of the Mobile API (docs/15_MOBILE_APP_API.md). */
object MobileErrorCodes {
    const val MOBILE_ACCESS_REQUIRED = "mobile_access_required"
    const val ATTENDANCE_ACCESS_REQUIRED = "attendance_access_required"
    const val ATTENDANCE_TAG_UNKNOWN = "attendance_tag_unknown"
    const val ATTENDANCE_TAG_DIRECTION_MISMATCH = "attendance_tag_direction_mismatch"
    const val ATTENDANCE_TIME_INVALID = "attendance_time_invalid"
    const val ATTENDANCE_PUNCH_TOO_OLD = "attendance_punch_too_old"
    const val COMMAND_CONFLICT = "command_conflict"
    const val INVALID_DATE_RANGE = "invalid_date_range"
}
