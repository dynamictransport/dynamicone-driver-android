package com.dynamictransport.dynamicone.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.exifinterface.media.ExifInterface
import com.dynamictransport.dynamicone.core.common.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import kotlin.math.max

/** Draws a downloaded document photo (JPEG, PNG, WebP) for the in-app viewer. */
fun interface DocumentImageDecoder {
    /** Upright and at most [maxEdgePx] on its longer edge; null when the file is not a readable image. */
    suspend fun decode(filePath: String, maxEdgePx: Int): ImageBitmap?
}

/** Decodes only as many pixels as the screen needs: a scanned page stays readable without running out of memory. */
class AndroidDocumentImageDecoder @Inject constructor(
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : DocumentImageDecoder {

    override suspend fun decode(filePath: String, maxEdgePx: Int): ImageBitmap? = withContext(io) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(filePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdgePx) sample *= 2
            val decoded = BitmapFactory.decodeFile(filePath, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return@withContext null
            upright(decoded, rotationOf(filePath)).asImageBitmap()
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    private fun upright(decoded: Bitmap, rotation: Int): Bitmap {
        if (rotation == 0) return decoded
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val result = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (result !== decoded) decoded.recycle()
        return result
    }

    /** EXIF rotation of a JPEG; other formats have none. */
    private fun rotationOf(filePath: String): Int = runCatching {
        when (ExifInterface(filePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSVERSE -> 90
            ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180
            ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSPOSE -> 270
            else -> 0
        }
    }.getOrDefault(0)
}
