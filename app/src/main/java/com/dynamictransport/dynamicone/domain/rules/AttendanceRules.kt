package com.dynamictransport.dynamicone.domain.rules

import com.dynamictransport.dynamicone.domain.model.AttendanceDirection
import com.dynamictransport.dynamicone.domain.model.AttendanceStatus
import com.dynamictransport.dynamicone.domain.model.LocalPunch
import com.dynamictransport.dynamicone.domain.model.LocalPunchState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Where the account stands today and which single action the attendance screen offers. */
data class AttendanceSituation(
    val checkedIn: Boolean,
    /** Time of today's check-in while [checkedIn]. */
    val checkedInSince: Instant?,
    val nextDirection: AttendanceDirection,
    val today: LocalDate,
    val zone: ZoneId,
)

object AttendanceRules {

    /** Tag identifiers are 4–10 bytes (docs/15_MOBILE_APP_API.md §5.1). */
    private val UID_BYTES = 4..10
    private val UID_PATTERN = Regex("^([0-9A-F]{2}){4,10}$")

    /** Upper-case hex without separators, or null for an identifier outside the contract. */
    fun tagUid(bytes: ByteArray?): String? {
        if (bytes == null || bytes.size !in UID_BYTES) return null
        return bytes.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
    }

    /** Accepts `04:a2:24…`, `04 A2 24…` and `04A224…`. */
    fun tagUid(text: String?): String? {
        val compact = text?.replace(Regex("[\\s:-]"), "")?.uppercase() ?: return null
        return compact.takeIf { UID_PATTERN.matches(it) }
    }

    /**
     * The server status decides (`nextAction`), including punches from the office terminal and corrections.
     * Punches still on the phone, or recorded after the status was read, are newer knowledge: then the newest punch
     * of the current work day decides. The rule is the server's: checked in ⇔ today's newest punch is a check-in;
     * a check-in left open on an earlier day does not carry over (overnight shifts are out of scope).
     */
    fun situation(status: AttendanceStatus?, local: List<LocalPunch>, now: Instant, deviceZone: ZoneId): AttendanceSituation {
        val zone = status?.zone ?: deviceZone
        val today = now.atZone(zone).toLocalDate()
        val serverLast = status?.lastPunch
        val newerLocal = local
            .filter { it.state != LocalPunchState.FAILED }
            .filter { it.commandId != serverLast?.commandId }
            .filter { serverLast == null || it.time.isAfter(serverLast.occurredAt) }
            .maxByOrNull { it.time }

        if (newerLocal == null && status != null && status.workDate == today) {
            val since = serverLast?.takeIf { status.checkedIn && it.direction == AttendanceDirection.IN }?.occurredAt
            return AttendanceSituation(status.checkedIn, since, status.nextDirection, today, zone)
        }

        val newest = newerLocal?.let { it.direction to it.time }
            ?: serverLast?.let { it.direction to it.occurredAt }
        val checkedIn = newest != null &&
            newest.first == AttendanceDirection.IN &&
            newest.second.atZone(zone).toLocalDate() == today
        return AttendanceSituation(
            checkedIn = checkedIn,
            checkedInSince = if (checkedIn) newest.second else null,
            nextDirection = if (checkedIn) AttendanceDirection.OUT else AttendanceDirection.IN,
            today = today,
            zone = zone,
        )
    }
}
