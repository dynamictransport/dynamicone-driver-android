package com.dynamictransport.dynamicone.domain.scan

import com.dynamictransport.dynamicone.domain.model.DocumentType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Problems that block a document photo, declared in the order they are shown to the driver. */
enum class ScanIssue {
    NO_DOCUMENT,
    CUT_OFF,
    TOO_FAR,
    TILTED,
    TOO_DARK,
    GLARE,
    BLURRY,
    LOW_RESOLUTION,
}

/** Paper documents are framed, straightened and cleaned; container/seal photos are checked for sharpness and light. */
enum class ScanMode { DOCUMENT, PHOTO }

val DocumentType.scanMode: ScanMode
    get() = when (this) {
        DocumentType.CONTAINER_PHOTO, DocumentType.SEAL_PHOTO -> ScanMode.PHOTO
        else -> ScanMode.DOCUMENT
    }

/** Check result of one live camera frame. [quad] is normalised to 0..1 of the upright frame. */
data class FrameVerdict(val issues: List<ScanIssue>, val quad: DocumentQuad?) {
    val acceptable: Boolean get() = issues.isEmpty()
}

sealed interface ScanOutcome {
    /** The processed image was written to [imagePath]; it may be used only when [issues] is empty. */
    data class Processed(val imagePath: String, val issues: List<ScanIssue>) : ScanOutcome

    /** The source is not a readable image. */
    data object Unreadable : ScanOutcome
}

/** Every limit in one place, so they can be tuned with real CMRs and real phones. */
data class ScanThresholds(
    /** Corners closer than this fraction of the frame to its edge count as cut off. */
    val edgeMargin: Float = 0.02f,
    /** Live frame: the document must fill this much of the frame so the photo has enough pixels. */
    val minCoverage: Float = 0.40f,
    val maxCornerAngleDeviation: Double = 20.0,
    val minMeanLuma: Double = 85.0,
    val glareLuma: Int = 250,
    val maxGlareFraction: Double = 0.04,
    /** Blur estimate (0 sharp … 1 blurred) on a full-resolution patch of the live frame. */
    val maxLiveBlur: Double = 0.45,
    /** Same estimate on the processed image scaled to [sharpnessShortEdgePx]. */
    val maxResultBlur: Double = 0.45,
    val sharpnessShortEdgePx: Int = 1240,
    /** About 170 dpi for an A4 sheet. */
    val minDocumentShortEdgePx: Int = 1400,
    val minDocumentLongEdgePx: Int = 2000,
    val minPhotoLongEdgePx: Int = 1600,
)

class ScanQualityPolicy(val thresholds: ScanThresholds = ScanThresholds()) {

    /**
     * Live guidance. [frame] is the upright, downscaled frame; [sharpnessPatch] is a full-resolution crop of the
     * frame centre, because downscaling hides blur.
     */
    fun evaluateFrame(mode: ScanMode, frame: LumaImage, sharpnessPatch: LumaImage): FrameVerdict {
        val issues = sortedSetOf<ScanIssue>()
        var normalizedQuad: DocumentQuad? = null
        val subject = if (mode == ScanMode.DOCUMENT) {
            val quad = DocumentDetector.detect(frame) ?: return FrameVerdict(listOf(ScanIssue.NO_DOCUMENT), null)
            normalizedQuad = quad.scale(1f / frame.width, 1f / frame.height)
            issues += geometryIssues(quad, frame.width, frame.height, checkCoverage = true)
            paperArea(frame, quad)
        } else {
            frame.centerCrop(PHOTO_SUBJECT_FRACTION)
        }
        issues += lightIssues(subject, checkGlare = mode == ScanMode.DOCUMENT)
        val blur = ImageMetrics.blur(sharpnessPatch)
        if (blur != null && blur > thresholds.maxLiveBlur) issues += ScanIssue.BLURRY
        return FrameVerdict(issues.toList(), normalizedQuad)
    }

    /** Framing problems of a document found in an image of [width] x [height] pixels. */
    fun geometryIssues(quad: DocumentQuad, width: Int, height: Int, checkCoverage: Boolean): Set<ScanIssue> {
        val issues = sortedSetOf<ScanIssue>()
        val marginX = thresholds.edgeMargin * width
        val marginY = thresholds.edgeMargin * height
        if (quad.corners.any { it.x < marginX || it.y < marginY || it.x > width - marginX || it.y > height - marginY }) {
            issues += ScanIssue.CUT_OFF
        }
        if (checkCoverage && quad.area() < thresholds.minCoverage * width * height) issues += ScanIssue.TOO_FAR
        if (quad.maxCornerAngleDeviation() > thresholds.maxCornerAngleDeviation) issues += ScanIssue.TILTED
        return issues
    }

    /**
     * Final check of a processed image of [resultWidth] x [resultHeight] pixels. [sample] is that image scaled so
     * its short edge is at most [ScanThresholds.sharpnessShortEdgePx]; for documents it is taken before cleaning.
     */
    fun evaluateResult(mode: ScanMode, sample: LumaImage, resultWidth: Int, resultHeight: Int): List<ScanIssue> {
        val issues = sortedSetOf<ScanIssue>()
        val subject = sample.centerCrop(if (mode == ScanMode.DOCUMENT) DOCUMENT_SUBJECT_FRACTION else PHOTO_SUBJECT_FRACTION)
        issues += lightIssues(subject, checkGlare = mode == ScanMode.DOCUMENT)
        val blur = ImageMetrics.blur(subject)
        if (blur != null && blur > thresholds.maxResultBlur) issues += ScanIssue.BLURRY
        val shortEdge = min(resultWidth, resultHeight)
        val longEdge = max(resultWidth, resultHeight)
        val lowResolution = when (mode) {
            ScanMode.DOCUMENT -> shortEdge < thresholds.minDocumentShortEdgePx || longEdge < thresholds.minDocumentLongEdgePx
            ScanMode.PHOTO -> longEdge < thresholds.minPhotoLongEdgePx
        }
        if (lowResolution) issues += ScanIssue.LOW_RESOLUTION
        return issues.toList()
    }

    /** Glare is not checked on container/seal photos: shiny metal in sunlight is normal there. */
    private fun lightIssues(subject: LumaImage, checkGlare: Boolean): Set<ScanIssue> {
        val issues = sortedSetOf<ScanIssue>()
        if (ImageMetrics.meanLuma(subject) < thresholds.minMeanLuma) issues += ScanIssue.TOO_DARK
        if (checkGlare && ImageMetrics.fractionAtLeast(subject, thresholds.glareLuma) > thresholds.maxGlareFraction) {
            issues += ScanIssue.GLARE
        }
        return issues
    }

    /** Inner part of the document's bounding box; it stays on the paper for moderately rotated sheets. */
    private fun paperArea(frame: LumaImage, quad: DocumentQuad): LumaImage {
        val left = quad.corners.minOf { it.x }
        val right = quad.corners.maxOf { it.x }
        val top = quad.corners.minOf { it.y }
        val bottom = quad.corners.maxOf { it.y }
        val insetX = (right - left) * PAPER_INSET
        val insetY = (bottom - top) * PAPER_INSET
        return frame.crop(
            (left + insetX).toInt(),
            (top + insetY).toInt(),
            (right - left - 2 * insetX).toInt(),
            (bottom - top - 2 * insetY).toInt(),
        )
    }

    private companion object {
        const val DOCUMENT_SUBJECT_FRACTION = 0.9f
        const val PHOTO_SUBJECT_FRACTION = 0.6f
        const val PAPER_INSET = 0.2f
    }
}

object ImageMetrics {

    private const val BLUR_HALF_WINDOW = 4

    /** Below this mean step between neighbouring pixels a region is too plain to judge sharpness. */
    private const val MIN_MEAN_STEP = 1.5

    fun meanLuma(image: LumaImage): Double = image.pixels.average()

    fun fractionAtLeast(image: LumaImage, level: Int): Double = image.pixels.count { it >= level }.toDouble() / image.pixels.size

    /**
     * No-reference blur estimate (Crété-Roffet et al., 2007): how much neighbour-to-neighbour variation is left
     * after an extra 9-tap blur. 0 = sharp … 1 = fully blurred; the worse direction counts. Null when the region
     * is too plain to judge.
     */
    fun blur(image: LumaImage): Double? {
        val alongRows = blurAlong(image, alongRows = true)
        val alongColumns = blurAlong(image, alongRows = false)
        return when {
            alongRows == null -> alongColumns
            alongColumns == null -> alongRows
            else -> max(alongRows, alongColumns)
        }
    }

    private fun blurAlong(image: LumaImage, alongRows: Boolean): Double? {
        val lines = if (alongRows) image.height else image.width
        val length = if (alongRows) image.width else image.height
        if (length <= 2 * BLUR_HALF_WINDOW + 1) return null
        val line = IntArray(length)
        val prefix = IntArray(length + 1)
        var variation = 0L
        var removedByBlur = 0L
        for (lineIndex in 0 until lines) {
            for (i in 0 until length) line[i] = if (alongRows) image[i, lineIndex] else image[lineIndex, i]
            for (i in 0 until length) prefix[i + 1] = prefix[i] + line[i]
            var previousBlurred = 0
            for (i in 0 until length) {
                val from = max(0, i - BLUR_HALF_WINDOW)
                val to = min(length - 1, i + BLUR_HALF_WINDOW)
                // Fixed point (x16) keeps the averaged values precise.
                val blurred = (prefix[to + 1] - prefix[from]) * 16 / (to - from + 1)
                if (i > 0) {
                    val step = abs(line[i] - line[i - 1]) * 16
                    variation += step
                    removedByBlur += max(0, step - abs(blurred - previousBlurred))
                }
                previousBlurred = blurred
            }
        }
        val steps = lines.toLong() * (length - 1)
        if (variation < MIN_MEAN_STEP * 16 * steps) return null
        return (variation - removedByBlur).toDouble() / variation
    }
}
