package com.dynamictransport.driver.domain.scan

sealed interface CaptureReadiness {
    data class NotReady(val issue: ScanIssue) : CaptureReadiness

    /** Every check passes; the document must stay still a moment longer before the automatic photo. */
    data object Steadying : CaptureReadiness

    data object Ready : CaptureReadiness
}

/**
 * Decides when the automatic photo is taken: every check passes and the document has not moved for [holdNanos].
 * Not thread-safe; the caller serialises access.
 */
class CaptureStabilizer(
    private val holdNanos: Long = 700_000_000L,
    /** Normalised corner movement (fraction of the frame) that restarts the wait. */
    private val maxCornerShift: Float = 0.02f,
) {
    private var lastQuad: DocumentQuad? = null
    private var steadySince: Long? = null

    fun reset() {
        lastQuad = null
        steadySince = null
    }

    fun update(verdict: FrameVerdict, timestampNanos: Long): CaptureReadiness {
        if (!verdict.acceptable) {
            reset()
            return CaptureReadiness.NotReady(verdict.issues.first())
        }
        val previous = lastQuad
        val current = verdict.quad
        lastQuad = current
        val moved = previous != null && current != null && current.maxCornerShift(previous) > maxCornerShift
        val since = steadySince
        if (since == null || moved) {
            steadySince = timestampNanos
            return CaptureReadiness.Steadying
        }
        return if (timestampNanos - since >= holdNanos) CaptureReadiness.Ready else CaptureReadiness.Steadying
    }
}
