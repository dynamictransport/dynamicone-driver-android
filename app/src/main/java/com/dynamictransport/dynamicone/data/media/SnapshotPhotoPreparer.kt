package com.dynamictransport.dynamicone.data.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlin.math.max

/**
 * Turns the camera's JPEG into what the server accepts (docs/24 §6): longest side at most 1600 px, at most
 * 600 KiB, orientation applied to the pixels and no EXIF at all. [prepare] writes [target] and deletes [source].
 */
fun interface SnapshotPhotoPreparer {
    fun prepare(source: File, target: File): Boolean
}

class BitmapSnapshotPhotoPreparer @Inject constructor() : SnapshotPhotoPreparer {

    override fun prepare(source: File, target: File): Boolean = try {
        val ready = reencode(source, target)
        if (!ready) target.delete()
        ready
    } catch (_: IOException) {
        target.delete()
        false
    } finally {
        source.delete()
    }

    private fun reencode(source: File, target: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE_PX) sample *= 2
        val decoded = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
        val upright = upright(decoded, orientationOf(source))
        val scaled = scaleToEdge(upright, MAX_EDGE_PX)
        try {
            target.parentFile?.mkdirs()
            for (quality in JPEG_QUALITIES) {
                // Bitmap.compress writes a plain JPEG: no EXIF, so no location, device or time leaves the tablet.
                target.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, quality, it) }
                if (target.length() in 1..MAX_BYTES) return true
            }
        } finally {
            scaled.recycle()
        }
        return false
    }

    private fun scaleToEdge(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val edge = max(bitmap.width, bitmap.height)
        if (edge <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / edge
        val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun orientationOf(source: File): Int =
        runCatching { ExifInterface(source.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun upright(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    companion object {
        const val MAX_EDGE_PX = 1600
        const val MAX_BYTES = 600L * 1024
        private val JPEG_QUALITIES = intArrayOf(88, 80, 70, 60, 50, 40)
    }
}
