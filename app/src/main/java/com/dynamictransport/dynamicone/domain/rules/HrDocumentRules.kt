package com.dynamictransport.dynamicone.domain.rules

import com.dynamictransport.dynamicone.domain.model.DrawnSignature
import com.dynamictransport.dynamicone.domain.model.HrDocument
import com.dynamictransport.dynamicone.domain.model.HrDocumentDisplayState
import com.dynamictransport.dynamicone.domain.model.HrDocumentLanguage
import com.dynamictransport.dynamicone.domain.model.HrDocumentStatus
import com.dynamictransport.dynamicone.domain.model.LocalSignature
import com.dynamictransport.dynamicone.domain.model.LocalSignatureState
import com.dynamictransport.dynamicone.domain.model.SignaturePoint
import java.time.Instant
import java.time.ZoneId

/** How close the deadline is, in the phone's calendar. */
enum class DeadlineKind {
    NONE,
    LATER,
    TOMORROW,
    TODAY,
    OVERDUE,
}

/** Business rules of personnel document signing (docs/17_HR_DOCUMENT_SIGNING.md, ASSUMPTIONS D1–D12). */
object HrDocumentRules {

    // The server's rules for a drawn signature (the office tablet uses the same, EmployeeSignatureRules.Parse).
    const val MAX_STROKES = 100
    const val MIN_POINTS_PER_STROKE = 2
    const val MAX_POINTS = 5000
    const val MIN_WIDTH = 0.03f
    const val MIN_HEIGHT = 0.015f

    /** Width / height of the signature pad on the phone; sent with the strokes (docs/17 §5.4). */
    const val PAD_ASPECT_RATIO = 2.5f

    /** Points closer than this to the previous one add nothing to the drawing and are dropped. */
    const val MIN_POINT_DISTANCE = 0.004f

    fun deadline(dueAt: Instant?, now: Instant, zone: ZoneId): DeadlineKind {
        if (dueAt == null) return DeadlineKind.NONE
        if (!now.isBefore(dueAt)) return DeadlineKind.OVERDUE
        val today = now.atZone(zone).toLocalDate()
        val dueDay = dueAt.atZone(zone).toLocalDate()
        return when {
            !dueDay.isAfter(today) -> DeadlineKind.TODAY
            dueDay == today.plusDays(1) -> DeadlineKind.TOMORROW
            else -> DeadlineKind.LATER
        }
    }

    /** A signature waiting on the phone decides what is shown until the server has answered. */
    fun displayState(document: HrDocument, local: LocalSignature?, now: Instant, zone: ZoneId): HrDocumentDisplayState = when {
        document.status == HrDocumentStatus.SIGNED -> HrDocumentDisplayState.SIGNED
        document.status == HrDocumentStatus.WITHDRAWN -> HrDocumentDisplayState.WITHDRAWN
        local?.state == LocalSignatureState.PENDING || local?.state == LocalSignatureState.SENDING ->
            HrDocumentDisplayState.WAITING_TO_SEND
        local?.state == LocalSignatureState.FAILED -> HrDocumentDisplayState.SEND_FAILED
        else -> when (deadline(document.dueAt, now, zone)) {
            DeadlineKind.OVERDUE -> HrDocumentDisplayState.OVERDUE
            DeadlineKind.TODAY, DeadlineKind.TOMORROW -> HrDocumentDisplayState.DUE_SOON
            DeadlineKind.LATER, DeadlineKind.NONE -> HrDocumentDisplayState.TO_SIGN
        }
    }

    /** Soonest deadline first, documents without a deadline last, then the oldest sent first. */
    fun sortPending(documents: List<HrDocument>): List<HrDocument> = documents.sortedWith(
        compareBy<HrDocument>({ it.dueAt == null }, { it.dueAt }, { it.sentAt }, { it.id }),
    )

    /**
     * The language version shown first: the first of [preferred] (app language, account language) the document has,
     * then the reference language, then the first version.
     */
    fun pickLanguage(document: HrDocument, preferred: List<String?>): HrDocumentLanguage? {
        val wanted = preferred.mapNotNull { it?.lowercase()?.takeIf(String::isNotBlank) } + listOfNotNull(document.referenceLanguage)
        return wanted.firstNotNullOfOrNull { code -> document.language(code) } ?: document.languages.firstOrNull()
    }

    /** Mirrors the server's check so a signature that would be rejected cannot be sent. */
    fun isValid(signature: DrawnSignature): Boolean = isValid(signature.strokes)

    fun isValid(strokes: List<List<SignaturePoint>>): Boolean {
        if (strokes.isEmpty() || strokes.size > MAX_STROKES) return false
        if (strokes.any { it.size < MIN_POINTS_PER_STROKE }) return false
        val points = strokes.flatten()
        if (points.size > MAX_POINTS) return false
        if (points.any { !it.x.isFinite() || !it.y.isFinite() || it.x !in 0f..1f || it.y !in 0f..1f }) return false
        val width = points.maxOf { it.x } - points.minOf { it.x }
        val height = points.maxOf { it.y } - points.minOf { it.y }
        return width >= MIN_WIDTH && height >= MIN_HEIGHT
    }

    /** Clamped into the pad; null when it is too close to [previous] to matter. */
    fun nextPoint(previous: SignaturePoint?, x: Float, y: Float): SignaturePoint? {
        val point = SignaturePoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
        if (previous == null) return point
        val dx = point.x - previous.x
        val dy = point.y - previous.y
        return point.takeIf { dx * dx + dy * dy >= MIN_POINT_DISTANCE * MIN_POINT_DISTANCE }
    }

    /** Room left for a new stroke with at least the minimum number of points. */
    fun canStartStroke(strokes: List<List<SignaturePoint>>): Boolean =
        strokes.size < MAX_STROKES && strokes.sumOf { it.size } + MIN_POINTS_PER_STROKE <= MAX_POINTS

    fun pointsLeft(strokes: List<List<SignaturePoint>>, current: Int): Int = MAX_POINTS - strokes.sumOf { it.size } - current
}
