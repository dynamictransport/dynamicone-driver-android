package com.dynamictransport.dynamicone.data.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlin.math.max

/** Turns a downloaded photo into the small copy the app keeps. */
fun interface ProfilePhotoImages {
    /** Writes an upright JPEG of at most [maxEdgePx] to [output]; false when [source] is not a readable image. */
    fun reduce(source: File, output: File, maxEdgePx: Int): Boolean
}

/**
 * Decodes only as many pixels as needed, turns the image upright by its EXIF orientation and re-encodes it: the copy on
 * the phone carries no EXIF data (camera, place, time) of the original.
 */
class AndroidProfilePhotoImages @Inject constructor() : ProfilePhotoImages {

    override fun reduce(source: File, output: File, maxEdgePx: Int): Boolean = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            false
        } else {
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdgePx) sample *= 2
            val decoded = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample })
            decoded != null && write(upright(decoded, source, maxEdgePx), output)
        }
    } catch (_: IOException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    } catch (_: OutOfMemoryError) {
        false
    }

    private fun upright(decoded: Bitmap, source: File, maxEdgePx: Int): Bitmap {
        val scale = minOf(1f, maxEdgePx.toFloat() / max(decoded.width, decoded.height))
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postRotate(rotationOf(source).toFloat())
        }
        if (matrix.isIdentity) return decoded
        val result = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (result !== decoded) decoded.recycle()
        return result
    }

    private fun write(bitmap: Bitmap, output: File): Boolean = try {
        output.outputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream) }
    } finally {
        bitmap.recycle()
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

    private companion object {
        const val JPEG_QUALITY = 88
    }
}
