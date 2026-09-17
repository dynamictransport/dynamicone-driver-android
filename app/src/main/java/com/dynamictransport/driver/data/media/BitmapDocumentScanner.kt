package com.dynamictransport.driver.data.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import com.dynamictransport.driver.core.common.IoDispatcher
import com.dynamictransport.driver.domain.repository.DocumentScanner
import com.dynamictransport.driver.domain.scan.DocumentDetector
import com.dynamictransport.driver.domain.scan.DocumentQuad
import com.dynamictransport.driver.domain.scan.LumaImage
import com.dynamictransport.driver.domain.scan.ScanIssue
import com.dynamictransport.driver.domain.scan.ScanMode
import com.dynamictransport.driver.domain.scan.ScanOutcome
import com.dynamictransport.driver.domain.scan.ScanPoint
import com.dynamictransport.driver.domain.scan.ScanQualityPolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Turns a camera or gallery photo into what a flatbed scanner would deliver: the sheet is found again in the
 * stored photo, cut out, straightened, and uneven light and shadows are evened out. The same rules as the live
 * camera then decide whether the image may be used. Only the document's area is decoded at full resolution.
 */
class BitmapDocumentScanner @Inject constructor(
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : DocumentScanner {

    private val policy = ScanQualityPolicy()

    override suspend fun process(sourcePath: String, outputPath: String, mode: ScanMode): ScanOutcome = withContext(io) {
        try {
            scan(File(sourcePath), File(outputPath), mode)
        } catch (_: IOException) {
            ScanOutcome.Unreadable
        } catch (_: IllegalArgumentException) {
            ScanOutcome.Unreadable
        } catch (_: OutOfMemoryError) {
            // An extremely large gallery image; the driver can still use the in-app camera.
            ScanOutcome.Unreadable
        }
    }

    private class ImageSize(val width: Int, val height: Int)

    private fun scan(source: File, output: File, mode: ScanMode): ScanOutcome {
        val size = imageSize(source) ?: return ScanOutcome.Unreadable
        val rotation = rotationOf(source)
        val issues = sortedSetOf<ScanIssue>()
        var straightened = false
        val result = if (mode == ScanMode.DOCUMENT) {
            val detection = detectionImage(source, size, rotation) ?: return ScanOutcome.Unreadable
            val quad = DocumentDetector.detect(detection)
            val document = quad?.let { straighten(source, size, rotation, it, detection.width, detection.height) }
            if (quad != null) issues += policy.geometryIssues(quad, detection.width, detection.height, checkCoverage = false)
            if (document != null) {
                straightened = true
                document
            } else {
                issues += ScanIssue.NO_DOCUMENT
                uprightImage(source, size, rotation)
            }
        } else {
            uprightImage(source, size, rotation)
        } ?: return ScanOutcome.Unreadable

        try {
            val sampleScale = min(1f, policy.thresholds.sharpnessShortEdgePx.toFloat() / min(result.width, result.height))
            issues += policy.evaluateResult(mode, luma(result, sampleScale), result.width, result.height)
            if (straightened) evenOutLight(result)
            output.outputStream().use { stream ->
                if (!result.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)) throw IOException("JPEG encoding failed")
            }
        } finally {
            result.recycle()
        }
        return ScanOutcome.Processed(output.path, issues.toList())
    }

    private fun imageSize(source: File): ImageSize? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        return if (bounds.outWidth > 0 && bounds.outHeight > 0) ImageSize(bounds.outWidth, bounds.outHeight) else null
    }

    /** EXIF rotation; mirrored orientations are treated as their rotation. */
    private fun rotationOf(source: File): Int = runCatching {
        when (ExifInterface(source.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSVERSE -> 90
            ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180
            ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSPOSE -> 270
            else -> 0
        }
    }.getOrDefault(0)

    /** Small upright grayscale copy for finding the sheet. */
    private fun detectionImage(source: File, size: ImageSize, rotation: Int): LumaImage? {
        var sample = 1
        while (max(size.width, size.height) / (sample * 2) >= DETECTION_EDGE_PX) sample *= 2
        val bitmap = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return try {
            luma(bitmap, min(1f, DETECTION_EDGE_PX.toFloat() / max(bitmap.width, bitmap.height))).rotate(rotation)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Cuts the sheet out of the stored photo and maps its corners onto an upright rectangle (perspective
     * correction). [quad] is in the upright detection image of [detectionWidth] x [detectionHeight].
     */
    private fun straighten(
        source: File,
        size: ImageSize,
        rotation: Int,
        quad: DocumentQuad,
        detectionWidth: Int,
        detectionHeight: Int,
    ): Bitmap? {
        val quarterTurn = rotation % 180 != 0
        val uprightWidth = if (quarterTurn) size.height else size.width
        val uprightHeight = if (quarterTurn) size.width else size.height
        val corners = quad.scale(uprightWidth.toFloat() / detectionWidth, uprightHeight.toFloat() / detectionHeight)
        val fit = min(1f, MAX_OUTPUT_EDGE_PX / max(corners.width, corners.height))
        val outWidth = max(1, (corners.width * fit).roundToInt())
        val outHeight = max(1, (corners.height * fit).roundToInt())

        val sourceCorners = corners.corners.map { toStoredPixels(it, rotation, size) }
        val region = Rect(
            floor(sourceCorners.minOf { it.x }).toInt().coerceIn(0, size.width - 1),
            floor(sourceCorners.minOf { it.y }).toInt().coerceIn(0, size.height - 1),
            ceil(sourceCorners.maxOf { it.x }).toInt().coerceIn(1, size.width),
            ceil(sourceCorners.maxOf { it.y }).toInt().coerceIn(1, size.height),
        )
        if (region.width() < 2 || region.height() < 2) return null
        var sample = 1
        while (max(region.width(), region.height()) / (sample * 2) >= max(outWidth, outHeight)) sample *= 2
        val part = decodeRegion(source, region, sample) ?: return null

        try {
            val scaleX = part.width.toFloat() / region.width()
            val scaleY = part.height.toFloat() / region.height()
            val from = FloatArray(8)
            sourceCorners.forEachIndexed { index, point ->
                from[index * 2] = (point.x - region.left) * scaleX
                from[index * 2 + 1] = (point.y - region.top) * scaleY
            }
            // The corners are mapped slightly (0.6 %) outside the output, so the few pixels of seat or table that
            // the corner estimate can include along the edges are trimmed off, like a scanner's edge trim.
            val trim = max(2f, max(outWidth, outHeight) * 0.006f)
            val to = floatArrayOf(-trim, -trim, outWidth + trim, -trim, outWidth + trim, outHeight + trim, -trim, outHeight + trim)
            val matrix = Matrix()
            if (!matrix.setPolyToPoly(from, 0, to, 0, 4)) return null
            val document = createBitmap(outWidth, outHeight)
            Canvas(document).apply {
                drawColor(Color.WHITE)
                drawBitmap(part, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
            }
            return document
        } finally {
            part.recycle()
        }
    }

    private fun decodeRegion(source: File, region: Rect, sample: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoder = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BitmapRegionDecoder.newInstance(source.path)
            } else {
                @Suppress("DEPRECATION")
                BitmapRegionDecoder.newInstance(source.path, false)
            }
        } catch (_: IOException) {
            null
        }
        if (decoder != null) {
            try {
                decoder.decodeRegion(region, options)?.let { return it }
            } finally {
                decoder.recycle()
            }
        }
        // Formats without region decoding: decode the whole image and crop.
        val whole = BitmapFactory.decodeFile(source.path, options) ?: return null
        val left = region.left / sample
        val top = region.top / sample
        val width = min(region.width() / sample, whole.width - left)
        val height = min(region.height() / sample, whole.height - top)
        if (width < 1 || height < 1) {
            whole.recycle()
            return null
        }
        val cropped = Bitmap.createBitmap(whole, left, top, width, height)
        if (cropped !== whole) whole.recycle()
        return cropped
    }

    /** Upright image, at most [MAX_OUTPUT_EDGE_PX] on the long edge (photos, or a sheet that was not found). */
    private fun uprightImage(source: File, size: ImageSize, rotation: Int): Bitmap? {
        var sample = 1
        while (max(size.width, size.height) / (sample * 2) >= MAX_OUTPUT_EDGE_PX) sample *= 2
        val bitmap = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val fit = min(1f, MAX_OUTPUT_EDGE_PX / max(bitmap.width, bitmap.height))
        if (rotation == 0 && fit == 1f && bitmap.isMutable) return bitmap
        val matrix = Matrix().apply {
            postRotate(rotation.toFloat())
            postScale(fit, fit)
        }
        val upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (upright !== bitmap) bitmap.recycle()
        return upright
    }

    /** Upright coordinates back to the stored, unrotated pixels. */
    private fun toStoredPixels(point: ScanPoint, rotation: Int, size: ImageSize): ScanPoint = when (rotation) {
        90 -> ScanPoint(point.y, size.height - point.x)
        180 -> ScanPoint(size.width - point.x, size.height - point.y)
        270 -> ScanPoint(size.width - point.y, point.x)
        else -> point
    }

    private fun luma(bitmap: Bitmap, scale: Float): LumaImage {
        val width = max(1, (bitmap.width * scale).roundToInt())
        val height = max(1, (bitmap.height * scale).roundToInt())
        val scaled = if (width == bitmap.width && height == bitmap.height) bitmap else bitmap.scale(width, height)
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled !== bitmap) scaled.recycle()
        return LumaImage.fromArgb(width, height, pixels)
    }

    /**
     * Flatbed-scanner look: estimates how bright the paper is around every point (the brightest nearby cells,
     * smoothed) and lifts it to white. Ink, stamps and signatures keep their colour; hand or phone shadows fade.
     */
    private fun evenOutLight(bitmap: Bitmap) {
        val width = bitmap.width
        val height = bitmap.height
        val sample = luma(bitmap, min(1f, LIGHT_SAMPLE_EDGE_PX.toFloat() / max(width, height)))
        val paper = sample.downscale(LIGHT_MAP_EDGE_PX).maxFilter(2).boxBlur(2)

        val mapX0 = IntArray(width)
        val mapX1 = IntArray(width)
        val weightX = FloatArray(width)
        for (x in 0 until width) {
            val position = ((x + 0.5f) * paper.width / width - 0.5f).coerceIn(0f, paper.width - 1f)
            mapX0[x] = position.toInt()
            mapX1[x] = min(mapX0[x] + 1, paper.width - 1)
            weightX[x] = position - mapX0[x]
        }
        val row = IntArray(width)
        for (y in 0 until height) {
            val position = ((y + 0.5f) * paper.height / height - 0.5f).coerceIn(0f, paper.height - 1f)
            val y0 = position.toInt()
            val y1 = min(y0 + 1, paper.height - 1)
            val weightY = position - y0
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                val top = paper[mapX0[x], y0] + (paper[mapX1[x], y0] - paper[mapX0[x], y0]) * weightX[x]
                val bottom = paper[mapX0[x], y1] + (paper[mapX1[x], y1] - paper[mapX0[x], y1]) * weightX[x]
                val background = top + (bottom - top) * weightY
                val gain = (PAPER_WHITE / max(background, MIN_BACKGROUND)).coerceIn(1f, MAX_GAIN)
                val color = row[x]
                row[x] = (color and ALPHA_MASK) or
                    (level(color shr 16 and 0xFF, gain) shl 16) or
                    (level(color shr 8 and 0xFF, gain) shl 8) or
                    level(color and 0xFF, gain)
            }
            bitmap.setPixels(row, 0, width, 0, y, width, 1)
        }
    }

    private fun level(channel: Int, gain: Float): Int =
        ((channel * gain - BLACK_POINT) * 255f / (255f - BLACK_POINT)).toInt().coerceIn(0, 255)

    private companion object {
        const val DETECTION_EDGE_PX = 640
        const val MAX_OUTPUT_EDGE_PX = 4096f
        const val JPEG_QUALITY = 92
        const val LIGHT_SAMPLE_EDGE_PX = 640
        const val LIGHT_MAP_EDGE_PX = 160
        const val PAPER_WHITE = 250f
        const val MIN_BACKGROUND = 60f
        const val MAX_GAIN = 2.5f
        const val BLACK_POINT = 16f
        const val ALPHA_MASK = -0x1000000
    }
}
