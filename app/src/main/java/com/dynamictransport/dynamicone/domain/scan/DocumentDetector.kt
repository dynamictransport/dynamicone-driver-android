package com.dynamictransport.dynamicone.domain.scan

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class ScanPoint(val x: Float, val y: Float)

/** Corners of a document, clockwise from top-left, in the coordinates of the image they were found in. */
data class DocumentQuad(
    val topLeft: ScanPoint,
    val topRight: ScanPoint,
    val bottomRight: ScanPoint,
    val bottomLeft: ScanPoint,
) {
    val corners: List<ScanPoint> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)

    /** Straightened size that keeps the most detail: the longer edge of each opposite pair. */
    val width: Float get() = max(distance(topLeft, topRight), distance(bottomLeft, bottomRight))
    val height: Float get() = max(distance(topLeft, bottomLeft), distance(topRight, bottomRight))

    fun area(): Float = polygonArea(corners)

    fun scale(scaleX: Float, scaleY: Float): DocumentQuad {
        fun ScanPoint.scaled() = ScanPoint(x * scaleX, y * scaleY)
        return DocumentQuad(topLeft.scaled(), topRight.scaled(), bottomRight.scaled(), bottomLeft.scaled())
    }

    /** Largest difference between a corner angle and 90 degrees; it grows when the phone is held at a slant. */
    fun maxCornerAngleDeviation(): Double {
        val points = corners
        return points.indices.maxOf { index ->
            val corner = points[index]
            val previous = points[(index + 3) % 4]
            val next = points[(index + 1) % 4]
            val ax = previous.x - corner.x
            val ay = previous.y - corner.y
            val bx = next.x - corner.x
            val by = next.y - corner.y
            val lengths = hypot(ax, ay) * hypot(bx, by)
            if (lengths == 0f) {
                90.0
            } else {
                val cosine = ((ax * bx + ay * by) / lengths).coerceIn(-1f, 1f)
                abs(Math.toDegrees(acos(cosine.toDouble())) - 90.0)
            }
        }
    }

    /** Largest corner movement compared with [other] (same coordinate system). */
    fun maxCornerShift(other: DocumentQuad): Float = corners.zip(other.corners).maxOf { (a, b) -> distance(a, b) }
}

/**
 * Finds a sheet of paper that is brighter than what it lies on (a CMR on a seat, a dashboard, a dark table).
 *
 * Otsu threshold → closing (fills the printed text) → largest bright region → convex hull → best four-corner
 * fit. Returns null when the picture has no clearly separated, four-cornered sheet, including when the bright
 * region reaches three or more image edges (the "sheet" is the background, or the phone is far too close).
 */
object DocumentDetector {

    private const val MIN_SEPARABILITY = 0.45
    private const val MIN_CLASS_CONTRAST = 40.0
    private const val MIN_AREA_FRACTION = 0.05
    private const val MIN_SOLIDITY = 0.80
    private const val MIN_QUAD_OF_HULL = 0.85
    private const val MAX_HULL_VERTICES = 12
    private const val MAX_TOUCHED_BORDERS = 2

    /** Bright parts smaller than this fraction of the picture are never joined to the sheet. */
    private const val MIN_PART_FRACTION = 0.01

    /** A joined part must share this much of the sheet's width (or height) … */
    private const val MIN_ALIGNED_OVERLAP = 0.8

    /** … and be separated from it by at most this fraction of the picture. */
    private const val MAX_JOIN_GAP = 0.15

    private const val AREA = 0
    private const val MIN_X = 1
    private const val MAX_X = 2
    private const val MIN_Y = 3
    private const val MAX_Y = 4

    fun detect(image: LumaImage): DocumentQuad? {
        val width = image.width
        val height = image.height
        val smooth = image.boxBlur(1)
        val threshold = otsuThreshold(smooth) ?: return null
        val mask = BooleanArray(smooth.pixels.size) { smooth.pixels[it] > threshold }
        val radius = max(1, max(width, height) / 160)
        val closed = morph(morph(mask, width, height, radius, dilate = true), width, height, radius, dilate = false)
        val region = sheetRegion(closed, width, height) ?: return null
        if (region.area < MIN_AREA_FRACTION * width * height || region.touchedBorders > MAX_TOUCHED_BORDERS) return null

        val hull = convexHull(region.outline)
        if (hull.size < 4) return null
        val hullArea = polygonArea(hull)
        if (region.area < MIN_SOLIDITY * hullArea) return null
        val quad = bestQuad(simplify(hull, MAX_HULL_VERTICES))
        if (polygonArea(quad) < MIN_QUAD_OF_HULL * hullArea) return null
        return orderClockwise(quad)
    }

    /** Otsu's threshold, or null when the picture is not clearly split into a bright sheet and a darker background. */
    private fun otsuThreshold(image: LumaImage): Int? {
        val histogram = IntArray(256)
        for (value in image.pixels) histogram[value.coerceIn(0, 255)]++
        val total = image.pixels.size.toDouble()
        var sumAll = 0.0
        for (level in 0..255) sumAll += level.toDouble() * histogram[level]
        val mean = sumAll / total
        var variance = 0.0
        for (level in 0..255) variance += histogram[level] * (level - mean) * (level - mean)
        variance /= total
        if (variance < 1.0) return null

        var weightDark = 0.0
        var sumDark = 0.0
        var bestBetween = -1.0
        var bestLevel = 0
        var bestContrast = 0.0
        for (level in 0 until 255) {
            weightDark += histogram[level]
            if (weightDark == 0.0) continue
            val weightBright = total - weightDark
            if (weightBright == 0.0) break
            sumDark += level.toDouble() * histogram[level]
            val meanDark = sumDark / weightDark
            val meanBright = (sumAll - sumDark) / weightBright
            val between = weightDark * weightBright * (meanBright - meanDark) * (meanBright - meanDark) / (total * total)
            if (between > bestBetween) {
                bestBetween = between
                bestLevel = level
                bestContrast = meanBright - meanDark
            }
        }
        return bestLevel.takeIf { bestBetween / variance >= MIN_SEPARABILITY && bestContrast >= MIN_CLASS_CONTRAST }
    }

    /** Square dilation or erosion, done as a row pass and a column pass over running counts. */
    private fun morph(mask: BooleanArray, width: Int, height: Int, radius: Int, dilate: Boolean): BooleanArray {
        val prefix = IntArray(max(width, height) + 1)
        val rows = BooleanArray(mask.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) prefix[x + 1] = prefix[x] + if (mask[row + x]) 1 else 0
            for (x in 0 until width) {
                val from = max(0, x - radius)
                val to = min(width - 1, x + radius)
                val count = prefix[to + 1] - prefix[from]
                rows[row + x] = if (dilate) count > 0 else count == to - from + 1
            }
        }
        val out = BooleanArray(mask.size)
        for (x in 0 until width) {
            for (y in 0 until height) prefix[y + 1] = prefix[y] + if (rows[y * width + x]) 1 else 0
            for (y in 0 until height) {
                val from = max(0, y - radius)
                val to = min(height - 1, y + radius)
                val count = prefix[to + 1] - prefix[from]
                out[y * width + x] = if (dilate) count > 0 else count == to - from + 1
            }
        }
        return out
    }

    private class Region(val area: Int, val outline: List<ScanPoint>, val touchedBorders: Int)

    /**
     * The largest 4-connected bright region, joined with bright parts that line up with it across a narrow dark
     * gap (a dark band printed across the sheet, a fold shadow), so such a sheet is not cut in two. Returns the
     * joined area and its pixel-edge outline (row extremes).
     */
    private fun sheetRegion(mask: BooleanArray, width: Int, height: Int): Region? {
        val labels = IntArray(mask.size)
        val stack = IntArray(mask.size)
        // Per label (label - 1): area, min x, max x, min y, max y.
        val parts = ArrayList<IntArray>()
        for (start in mask.indices) {
            if (!mask[start] || labels[start] != 0) continue
            val label = parts.size + 1
            val part = intArrayOf(0, width, -1, height, -1)
            var top = 0
            labels[start] = label
            stack[top++] = start
            while (top > 0) {
                val index = stack[--top]
                val x = index % width
                val y = index / width
                part[AREA]++
                part[MIN_X] = min(part[MIN_X], x)
                part[MAX_X] = max(part[MAX_X], x)
                part[MIN_Y] = min(part[MIN_Y], y)
                part[MAX_Y] = max(part[MAX_Y], y)
                if (x > 0 && mask[index - 1] && labels[index - 1] == 0) {
                    labels[index - 1] = label
                    stack[top++] = index - 1
                }
                if (x < width - 1 && mask[index + 1] && labels[index + 1] == 0) {
                    labels[index + 1] = label
                    stack[top++] = index + 1
                }
                if (y > 0 && mask[index - width] && labels[index - width] == 0) {
                    labels[index - width] = label
                    stack[top++] = index - width
                }
                if (y < height - 1 && mask[index + width] && labels[index + width] == 0) {
                    labels[index + width] = label
                    stack[top++] = index + width
                }
            }
            parts += part
        }
        if (parts.isEmpty()) return null

        val inSheet = BooleanArray(parts.size + 1)
        val largest = parts.indices.maxBy { parts[it][AREA] }
        inSheet[largest + 1] = true
        val sheet = parts[largest].copyOf()
        val minPartArea = MIN_PART_FRACTION * width * height
        var grown = true
        while (grown) {
            grown = false
            parts.forEachIndexed { index, part ->
                if (!inSheet[index + 1] && part[AREA] >= minPartArea && continuesSheet(part, sheet, width, height)) {
                    inSheet[index + 1] = true
                    sheet[AREA] += part[AREA]
                    sheet[MIN_X] = min(sheet[MIN_X], part[MIN_X])
                    sheet[MAX_X] = max(sheet[MAX_X], part[MAX_X])
                    sheet[MIN_Y] = min(sheet[MIN_Y], part[MIN_Y])
                    sheet[MAX_Y] = max(sheet[MAX_Y], part[MAX_Y])
                    grown = true
                }
            }
        }

        val outline = ArrayList<ScanPoint>()
        var touchesLeft = false
        var touchesRight = false
        var touchesTop = false
        var touchesBottom = false
        for (y in 0 until height) {
            val row = y * width
            var minX = -1
            var maxX = -1
            for (x in 0 until width) {
                if (inSheet[labels[row + x]]) {
                    if (minX < 0) minX = x
                    maxX = x
                }
            }
            if (minX < 0) continue
            if (y == 0) touchesTop = true
            if (y == height - 1) touchesBottom = true
            if (minX == 0) touchesLeft = true
            if (maxX == width - 1) touchesRight = true
            outline += ScanPoint(minX.toFloat(), y.toFloat())
            outline += ScanPoint(minX.toFloat(), y + 1f)
            outline += ScanPoint(maxX + 1f, y.toFloat())
            outline += ScanPoint(maxX + 1f, y + 1f)
        }
        val touched = listOf(touchesLeft, touchesRight, touchesTop, touchesBottom).count { it }
        return Region(sheet[AREA], outline, touched)
    }

    /** A part continues the sheet when it spans nearly the same width (or height) just across a narrow gap. */
    private fun continuesSheet(part: IntArray, sheet: IntArray, width: Int, height: Int): Boolean {
        val overlapX = min(part[MAX_X], sheet[MAX_X]) - max(part[MIN_X], sheet[MIN_X]) + 1
        val overlapY = min(part[MAX_Y], sheet[MAX_Y]) - max(part[MIN_Y], sheet[MIN_Y]) + 1
        val gapX = max(part[MIN_X] - sheet[MAX_X], sheet[MIN_X] - part[MAX_X]) - 1
        val gapY = max(part[MIN_Y] - sheet[MAX_Y], sheet[MIN_Y] - part[MAX_Y]) - 1
        val stacked = overlapX >= MIN_ALIGNED_OVERLAP * (sheet[MAX_X] - sheet[MIN_X] + 1) &&
            gapY in 0..(MAX_JOIN_GAP * height).toInt()
        val sideBySide = overlapY >= MIN_ALIGNED_OVERLAP * (sheet[MAX_Y] - sheet[MIN_Y] + 1) &&
            gapX in 0..(MAX_JOIN_GAP * width).toInt()
        return stacked || sideBySide
    }

    /** Andrew's monotone chain. */
    private fun convexHull(points: List<ScanPoint>): List<ScanPoint> {
        val sorted = points.distinct().sortedWith(compareBy<ScanPoint> { it.x }.thenBy { it.y })
        if (sorted.size < 3) return sorted
        val hull = ArrayList<ScanPoint>(sorted.size * 2)
        for (point in sorted) {
            while (hull.size >= 2 && cross(hull[hull.size - 2], hull[hull.size - 1], point) <= 0) hull.removeAt(hull.size - 1)
            hull += point
        }
        val lowerSize = hull.size + 1
        for (index in sorted.size - 2 downTo 0) {
            val point = sorted[index]
            while (hull.size >= lowerSize && cross(hull[hull.size - 2], hull[hull.size - 1], point) <= 0) hull.removeAt(hull.size - 1)
            hull += point
        }
        hull.removeAt(hull.size - 1)
        return hull
    }

    /** Drops the vertex that contributes the least area until at most [maxVertices] remain. */
    private fun simplify(polygon: List<ScanPoint>, maxVertices: Int): List<ScanPoint> {
        val points = polygon.toMutableList()
        while (points.size > maxVertices) {
            val weakest = points.indices.minBy { index ->
                abs(cross(points[(index + points.size - 1) % points.size], points[index], points[(index + 1) % points.size]))
            }
            points.removeAt(weakest)
        }
        return points
    }

    /** Four hull vertices (kept in hull order, so the result is convex) that enclose the largest area. */
    private fun bestQuad(polygon: List<ScanPoint>): List<ScanPoint> {
        if (polygon.size <= 4) return polygon
        var best = polygon.take(4)
        var bestArea = -1f
        val n = polygon.size
        for (a in 0 until n - 3) for (b in a + 1 until n - 2) for (c in b + 1 until n - 1) for (d in c + 1 until n) {
            val candidate = listOf(polygon[a], polygon[b], polygon[c], polygon[d])
            val area = polygonArea(candidate)
            if (area > bestArea) {
                bestArea = area
                best = candidate
            }
        }
        return best
    }

    /** Image y grows downwards, so increasing angle around the centre is clockwise on screen. */
    private fun orderClockwise(points: List<ScanPoint>): DocumentQuad? {
        if (points.size != 4) return null
        val centerX = points.sumOf { it.x.toDouble() } / 4
        val centerY = points.sumOf { it.y.toDouble() } / 4
        val clockwise = points.sortedBy { atan2(it.y - centerY, it.x - centerX) }
        val start = clockwise.indices.minBy { clockwise[it].x + clockwise[it].y }
        val ordered = List(4) { clockwise[(start + it) % 4] }
        return DocumentQuad(ordered[0], ordered[1], ordered[2], ordered[3])
    }

    private fun cross(origin: ScanPoint, a: ScanPoint, b: ScanPoint): Float =
        (a.x - origin.x) * (b.y - origin.y) - (a.y - origin.y) * (b.x - origin.x)
}

internal fun distance(a: ScanPoint, b: ScanPoint): Float = hypot(a.x - b.x, a.y - b.y)

internal fun polygonArea(points: List<ScanPoint>): Float {
    var twiceArea = 0f
    for (index in points.indices) {
        val current = points[index]
        val next = points[(index + 1) % points.size]
        twiceArea += current.x * next.y - next.x * current.y
    }
    return abs(twiceArea) / 2f
}
