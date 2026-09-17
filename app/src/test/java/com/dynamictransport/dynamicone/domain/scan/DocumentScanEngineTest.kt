package com.dynamictransport.dynamicone.domain.scan

import com.dynamictransport.dynamicone.domain.model.DocumentType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

private const val FRAME_WIDTH = 480
private const val FRAME_HEIGHT = 640

private fun point(x: Number, y: Number) = ScanPoint(x.toFloat(), y.toFloat())

private val GOOD_SHEET = listOf(point(40, 50), point(440, 50), point(440, 590), point(40, 590))

/** Ray casting. */
private fun inside(polygon: List<ScanPoint>, x: Float, y: Float): Boolean {
    var result = false
    var previous = polygon.size - 1
    for (index in polygon.indices) {
        val a = polygon[index]
        val b = polygon[previous]
        if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) result = !result
        previous = index
    }
    return result
}

/** Dark background with a light sheet (corners clockwise from top-left) carrying rows of dark "words". */
private fun sheetFrame(
    corners: List<ScanPoint>,
    paper: Int = 215,
    ink: Int = 50,
    background: Int = 45,
    seed: Int = 7,
): LumaImage {
    val random = Random(seed)
    val words = BooleanArray(64 * 64) { random.nextInt(4) != 0 }
    val margin = 12f
    val pixels = IntArray(FRAME_WIDTH * FRAME_HEIGHT) { index ->
        val x = index % FRAME_WIDTH + 0.5f
        val y = index / FRAME_WIDTH + 0.5f
        val onPaper = inside(corners, x, y)
        val inTextArea = onPaper && inside(corners, x - margin, y - margin) && inside(corners, x + margin, y + margin) &&
            inside(corners, x + margin, y - margin) && inside(corners, x - margin, y + margin)
        val row = y.toInt()
        val column = x.toInt()
        val value = when {
            !onPaper -> background
            inTextArea && row % 14 in 5..7 && column % 11 < 8 && words[(row / 14 % 64) * 64 + column / 11 % 64] -> ink
            else -> paper
        }
        (value + random.nextInt(-4, 5)).coerceIn(0, 255)
    }
    return LumaImage(FRAME_WIDTH, FRAME_HEIGHT, pixels)
}

/** A page of small, dense text (about 12 px high letters), like a straightened CMR at 150 dpi. */
private fun textPage(width: Int = 1240, height: Int = 1754, paper: Int = 225, ink: Int = 40, seed: Int = 3): LumaImage {
    val random = Random(seed)
    val pixels = IntArray(width * height) { paper }
    var line = 60
    while (line + 16 < height - 60) {
        var x = 60
        while (x < width - 80) {
            val wordEnd = min(x + 25 + random.nextInt(70), width - 60)
            var stem = x
            while (stem < wordEnd) {
                val stemWidth = 1 + random.nextInt(2)
                val top = line + random.nextInt(4)
                for (y in top until line + 14) {
                    for (column in stem until min(stem + stemWidth, wordEnd)) pixels[y * width + column] = ink
                }
                stem += stemWidth + 2 + random.nextInt(4)
            }
            x = wordEnd + 10 + random.nextInt(8)
        }
        line += 26
    }
    for (index in pixels.indices) pixels[index] = (pixels[index] + random.nextInt(-4, 5)).coerceIn(0, 255)
    return LumaImage(width, height, pixels)
}

private fun rotatedSheet(centerX: Float, centerY: Float, halfWidth: Float, halfHeight: Float, degrees: Double): List<ScanPoint> {
    val angle = Math.toRadians(degrees)
    val c = cos(angle).toFloat()
    val s = sin(angle).toFloat()
    return listOf(-halfWidth to -halfHeight, halfWidth to -halfHeight, halfWidth to halfHeight, -halfWidth to halfHeight)
        .map { (dx, dy) -> ScanPoint(centerX + dx * c - dy * s, centerY + dx * s + dy * c) }
}

private fun assertCorners(expected: List<ScanPoint>, actual: DocumentQuad?, tolerance: Float) {
    assertNotNull("no sheet found", actual)
    expected.zip(actual!!.corners).forEach { (want, got) ->
        assertTrue("expected $want but was $got", distance(want, got) <= tolerance)
    }
}

class DocumentDetectorTest {

    @Test
    fun `finds the corners of a straight sheet`() {
        assertCorners(GOOD_SHEET, DocumentDetector.detect(sheetFrame(GOOD_SHEET)), tolerance = 4f)
    }

    @Test
    fun `finds a rotated sheet with corners clockwise from top-left`() {
        val sheet = rotatedSheet(240f, 320f, 150f, 200f, degrees = 12.0)
        assertCorners(sheet, DocumentDetector.detect(sheetFrame(sheet)), tolerance = 5f)
    }

    @Test
    fun `a dark band printed across the whole sheet does not cut it in two`() {
        val frame = sheetFrame(GOOD_SHEET)
        for (y in 120 until 160) {
            for (x in 0 until FRAME_WIDTH) {
                if (inside(GOOD_SHEET, x + 0.5f, y + 0.5f)) frame.pixels[y * FRAME_WIDTH + x] = 30
            }
        }
        assertCorners(GOOD_SHEET, DocumentDetector.detect(frame), tolerance = 4f)
    }

    @Test
    fun `no sheet on a plain picture or when paper and background look alike`() {
        val random = Random(1)
        val plain = LumaImage(FRAME_WIDTH, FRAME_HEIGHT, IntArray(FRAME_WIDTH * FRAME_HEIGHT) { 128 + random.nextInt(-4, 5) })
        assertNull(DocumentDetector.detect(plain))
        assertNull(DocumentDetector.detect(sheetFrame(GOOD_SHEET, paper = 160, ink = 150, background = 140)))
    }

    @Test
    fun `paper that fills the whole picture is not taken for a sheet`() {
        val tooClose = listOf(point(-20, -20), point(500, -20), point(500, 660), point(-20, 660))
        assertNull(DocumentDetector.detect(sheetFrame(tooClose)))
    }
}

class ScanQualityPolicyTest {

    private val policy = ScanQualityPolicy()

    private fun live(frame: LumaImage, patch: LumaImage = frame.centerCrop(0.4f)) =
        policy.evaluateFrame(ScanMode.DOCUMENT, frame, patch)

    @Test
    fun `a well framed, sharp and lit sheet passes every live check`() {
        val verdict = live(sheetFrame(GOOD_SHEET))

        assertEquals(emptyList<ScanIssue>(), verdict.issues)
        assertTrue(verdict.acceptable)
        assertCorners(
            GOOD_SHEET.map { ScanPoint(it.x / FRAME_WIDTH, it.y / FRAME_HEIGHT) },
            verdict.quad,
            tolerance = 0.01f,
        )
    }

    @Test
    fun `missing sheet is reported without a quad`() {
        val random = Random(2)
        val empty = LumaImage(FRAME_WIDTH, FRAME_HEIGHT, IntArray(FRAME_WIDTH * FRAME_HEIGHT) { 90 + random.nextInt(-4, 5) })

        assertEquals(FrameVerdict(listOf(ScanIssue.NO_DOCUMENT), null), live(empty))
    }

    @Test
    fun `sheet running out of the frame is cut off`() {
        val verdict = live(sheetFrame(listOf(point(100, 80), point(520, 80), point(520, 560), point(100, 560))))
        assertEquals(ScanIssue.CUT_OFF, verdict.issues.first())
    }

    @Test
    fun `small sheet is too far away`() {
        val verdict = live(sheetFrame(listOf(point(180, 240), point(300, 240), point(300, 400), point(180, 400))))
        assertEquals(listOf(ScanIssue.TOO_FAR), verdict.issues)
    }

    @Test
    fun `sheet photographed at a slant is tilted`() {
        val verdict = live(sheetFrame(listOf(point(220, 120), point(260, 120), point(450, 580), point(30, 580))))
        assertTrue(verdict.issues.toString(), ScanIssue.TILTED in verdict.issues)
    }

    @Test
    fun `dark picture is too dark`() {
        val verdict = live(sheetFrame(GOOD_SHEET, paper = 70, ink = 20, background = 12))
        assertEquals(listOf(ScanIssue.TOO_DARK), verdict.issues)
    }

    @Test
    fun `reflection on the paper is glare`() {
        val frame = sheetFrame(GOOD_SHEET)
        for (y in 240 until 360) for (x in 180 until 300) {
            if ((x - 240) * (x - 240) + (y - 300) * (y - 300) <= 60 * 60) frame.pixels[y * FRAME_WIDTH + x] = 255
        }
        assertEquals(listOf(ScanIssue.GLARE), live(frame).issues)
    }

    @Test
    fun `shaky picture is blurry`() {
        val frame = sheetFrame(GOOD_SHEET)
        assertEquals(listOf(ScanIssue.BLURRY), live(frame, frame.centerCrop(0.4f).boxBlur(3).boxBlur(3)).issues)
    }

    @Test
    fun `sharp, large processed document passes the final check`() {
        assertEquals(emptyList<ScanIssue>(), policy.evaluateResult(ScanMode.DOCUMENT, textPage(), 2480, 3508))
    }

    @Test
    fun `processed document with too few pixels is rejected`() {
        assertEquals(listOf(ScanIssue.LOW_RESOLUTION), policy.evaluateResult(ScanMode.DOCUMENT, textPage(), 1000, 1414))
    }

    @Test
    fun `blurred processed document is rejected`() {
        val blurred = textPage().boxBlur(3).boxBlur(3)
        assertEquals(listOf(ScanIssue.BLURRY), policy.evaluateResult(ScanMode.DOCUMENT, blurred, 2480, 3508))
    }

    @Test
    fun `dark processed document is rejected`() {
        val dark = textPage(paper = 70, ink = 15)
        assertTrue(ScanIssue.TOO_DARK in policy.evaluateResult(ScanMode.DOCUMENT, dark, 2480, 3508))
    }

    @Test
    fun `glare blocks documents but not container photos`() {
        val page = textPage()
        for (y in 300 until 1300) for (x in 200 until 1000) page.pixels[y * page.width + x] = 255

        assertTrue(ScanIssue.GLARE in policy.evaluateResult(ScanMode.DOCUMENT, page, 2480, 3508))
        assertFalse(ScanIssue.GLARE in policy.evaluateResult(ScanMode.PHOTO, page, 2480, 3508))
    }

    @Test
    fun `container photos need a long edge of 1600 pixels`() {
        val photo = textPage(width = 1200, height = 900)
        assertEquals(listOf(ScanIssue.LOW_RESOLUTION), policy.evaluateResult(ScanMode.PHOTO, photo, 1500, 1000))
        assertEquals(emptyList<ScanIssue>(), policy.evaluateResult(ScanMode.PHOTO, photo, 2000, 1500))
    }

    @Test
    fun `paper documents are scanned, container and seal photos are not`() {
        assertEquals(ScanMode.DOCUMENT, DocumentType.CMR.scanMode)
        assertEquals(ScanMode.DOCUMENT, DocumentType.OTHER.scanMode)
        assertEquals(ScanMode.PHOTO, DocumentType.CONTAINER_PHOTO.scanMode)
        assertEquals(ScanMode.PHOTO, DocumentType.SEAL_PHOTO.scanMode)
    }
}

class CaptureStabilizerTest {

    private val sheet = DocumentQuad(point(0.1, 0.1), point(0.9, 0.1), point(0.9, 0.9), point(0.1, 0.9))

    @Test
    fun `automatic photo only after the sheet stayed still long enough`() {
        val stabilizer = CaptureStabilizer(holdNanos = 700)
        val good = FrameVerdict(emptyList(), sheet)

        assertEquals(CaptureReadiness.Steadying, stabilizer.update(good, 0))
        assertEquals(CaptureReadiness.Steadying, stabilizer.update(good, 400))
        assertEquals(CaptureReadiness.Ready, stabilizer.update(good, 700))
    }

    @Test
    fun `movement restarts the wait and a failed check resets it`() {
        val stabilizer = CaptureStabilizer(holdNanos = 700)
        val good = FrameVerdict(emptyList(), sheet)
        val moved = FrameVerdict(emptyList(), sheet.scale(1.05f, 1.05f))

        stabilizer.update(good, 0)
        assertEquals(CaptureReadiness.Steadying, stabilizer.update(moved, 600))
        assertEquals(CaptureReadiness.Steadying, stabilizer.update(moved, 1_000))
        assertEquals(CaptureReadiness.Ready, stabilizer.update(moved, 1_300))

        assertEquals(CaptureReadiness.NotReady(ScanIssue.GLARE), stabilizer.update(FrameVerdict(listOf(ScanIssue.GLARE), moved.quad), 1_400))
        assertEquals(CaptureReadiness.Steadying, stabilizer.update(moved, 2_500))
    }
}

class LumaImageTest {

    private val image = LumaImage(2, 3, intArrayOf(1, 2, 3, 4, 5, 6))

    @Test
    fun `rotation follows camera and EXIF convention (clockwise)`() {
        val quarter = image.rotate(90)
        assertEquals(3, quarter.width)
        assertEquals(2, quarter.height)
        assertArrayEquals(intArrayOf(5, 3, 1, 6, 4, 2), quarter.pixels)
        assertArrayEquals(intArrayOf(2, 4, 6, 1, 3, 5), image.rotate(270).pixels)
        assertArrayEquals(intArrayOf(6, 5, 4, 3, 2, 1), image.rotate(180).pixels)
    }

    @Test
    fun `camera plane is read with row padding, crop and unsigned bytes`() {
        val rowStride = 5
        val buffer = ByteBuffer.wrap(byteArrayOf(10, 20, 30, 40, 99, 50, 60, 70, 80, 99, 1, 2, 200.toByte(), 4))

        val region = LumaImage.fromLumaPlane(buffer, rowStride, left = 1, top = 1, width = 2, height = 2)
        assertArrayEquals(intArrayOf(60, 70, 2, 200), region.pixels)

        val averaged = LumaImage.fromLumaPlane(buffer, rowStride, left = 0, top = 0, width = 4, height = 2, maxEdge = 2)
        assertArrayEquals(intArrayOf(35, 55), averaged.pixels)
    }
}
