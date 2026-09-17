package com.dynamictransport.dynamicone.data.real

import com.dynamictransport.dynamicone.core.common.toInstantOrNull
import com.dynamictransport.dynamicone.core.network.AttendanceDaysResponseDto
import com.dynamictransport.dynamicone.core.network.AttendancePunchDto
import com.dynamictransport.dynamicone.core.network.AttendanceStatusDto
import com.dynamictransport.dynamicone.core.network.AttendanceWireNames
import com.dynamictransport.dynamicone.core.network.DriverErrorCodes
import com.dynamictransport.dynamicone.core.network.MobileErrorCodes
import com.dynamictransport.dynamicone.core.network.RemoteException
import com.dynamictransport.dynamicone.domain.model.AttendanceDay
import com.dynamictransport.dynamicone.domain.model.AttendanceDayState
import com.dynamictransport.dynamicone.domain.model.AttendanceDirection
import com.dynamictransport.dynamicone.domain.model.AttendanceMonth
import com.dynamictransport.dynamicone.domain.model.AttendancePunch
import com.dynamictransport.dynamicone.domain.model.AttendancePunchSource
import com.dynamictransport.dynamicone.domain.model.AttendanceStatus
import com.dynamictransport.dynamicone.domain.model.PunchFailure
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Mobile API attendance wire models → domain (docs/15_MOBILE_APP_API.md §5). */
object AttendanceMapping {

    fun wire(direction: AttendanceDirection): String = when (direction) {
        AttendanceDirection.IN -> AttendanceWireNames.IN
        AttendanceDirection.OUT -> AttendanceWireNames.OUT
    }

    fun direction(wire: String?): AttendanceDirection? = when (wire) {
        AttendanceWireNames.IN -> AttendanceDirection.IN
        AttendanceWireNames.OUT -> AttendanceDirection.OUT
        else -> null
    }

    /** A punch without id, known direction or time cannot be shown and is skipped. */
    fun punch(dto: AttendancePunchDto): AttendancePunch? {
        val id = dto.id?.takeIf { it.isNotBlank() } ?: return null
        return AttendancePunch(
            id = id,
            commandId = dto.commandId?.takeIf { it.isNotBlank() },
            direction = direction(dto.direction) ?: return null,
            occurredAt = dto.occurredAtUtc?.toInstantOrNull() ?: return null,
            workDate = date(dto.workDate),
            source = when (dto.source) {
                AttendanceWireNames.SOURCE_MOBILE_NFC -> AttendancePunchSource.MOBILE_NFC
                AttendanceWireNames.SOURCE_TERMINAL -> AttendancePunchSource.TERMINAL
                AttendanceWireNames.SOURCE_CORRECTION -> AttendancePunchSource.CORRECTION
                else -> AttendancePunchSource.UNKNOWN
            },
            pointName = dto.pointName?.takeIf { it.isNotBlank() },
        )
    }

    /** Null when the work date or time zone is missing: without them the screen cannot decide anything. */
    fun status(dto: AttendanceStatusDto): AttendanceStatus? {
        val checkedIn = dto.state == AttendanceWireNames.CHECKED_IN
        return AttendanceStatus(
            checkedIn = checkedIn,
            nextDirection = when (dto.nextAction) {
                AttendanceWireNames.CHECK_IN -> AttendanceDirection.IN
                AttendanceWireNames.CHECK_OUT -> AttendanceDirection.OUT
                else -> if (checkedIn) AttendanceDirection.OUT else AttendanceDirection.IN
            },
            workDate = date(dto.workDate) ?: return null,
            zone = zone(dto.timeZoneId) ?: return null,
            lastPunch = dto.lastPunch?.let(::punch),
        )
    }

    fun month(month: YearMonth, dto: AttendanceDaysResponseDto): AttendanceMonth? {
        val zone = zone(dto.timeZoneId) ?: return null
        val days = dto.days.orEmpty().mapNotNull { day ->
            AttendanceDay(
                workDate = date(day.workDate) ?: return@mapNotNull null,
                state = when (day.state) {
                    AttendanceWireNames.DAY_COMPLETE -> AttendanceDayState.COMPLETE
                    AttendanceWireNames.DAY_OPEN -> AttendanceDayState.OPEN
                    AttendanceWireNames.DAY_INCOMPLETE -> AttendanceDayState.INCOMPLETE
                    AttendanceWireNames.DAY_NEEDS_REVIEW -> AttendanceDayState.NEEDS_REVIEW
                    else -> AttendanceDayState.UNKNOWN
                },
                workedMinutes = day.workedMinutes?.takeIf { it >= 0 },
                punches = day.punches.orEmpty().mapNotNull(::punch).sortedBy { it.occurredAt },
            )
        }
        return AttendanceMonth(month, zone, days.sortedByDescending { it.workDate })
    }

    /** Null: not processed or outcome unknown, send the same punch again later. Otherwise the server's final answer. */
    fun failure(e: RemoteException): PunchFailure? = when (e) {
        is RemoteException.Validation -> when (e.problem?.code) {
            MobileErrorCodes.ATTENDANCE_TAG_UNKNOWN -> PunchFailure.TAG_UNKNOWN
            MobileErrorCodes.ATTENDANCE_TAG_DIRECTION_MISMATCH -> PunchFailure.WRONG_TAG_DIRECTION
            MobileErrorCodes.ATTENDANCE_TIME_INVALID, MobileErrorCodes.ATTENDANCE_PUNCH_TOO_OLD -> PunchFailure.TIME_REJECTED
            else -> PunchFailure.REJECTED
        }
        is RemoteException.Conflict -> PunchFailure.CONFLICT
        // The password change screen opens; the punch is sent after it.
        is RemoteException.Forbidden ->
            if (e.problem?.code == DriverErrorCodes.PASSWORD_CHANGE_REQUIRED) null else PunchFailure.ACCESS_REQUIRED
        is RemoteException.Rejected, is RemoteException.PayloadTooLarge -> PunchFailure.REJECTED
        // Offline, 5xx, unreadable response, 401 (session), 404 (API not live or disabled), 429.
        else -> null
    }

    private fun date(value: String?): LocalDate? = value?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    private fun zone(value: String?): ZoneId? = value?.takeIf { it.isNotBlank() }?.let { runCatching { ZoneId.of(it) }.getOrNull() }
}
