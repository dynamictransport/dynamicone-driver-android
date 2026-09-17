package com.dynamictransport.driver.data.real

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.dynamictransport.driver.domain.model.ActionRejection
import java.io.File
import java.io.IOException
import kotlin.math.max

internal sealed interface PreparedUpload {
    data class Ready(val file: File, val fileName: String, val contentType: String, val temporary: Boolean) : PreparedUpload
    data class Invalid(val reason: ActionRejection) : PreparedUpload
}

/**
 * Client-side checks before a Driver API upload: PDF, JPEG or PNG and at most 10 MB.
 * Photos are re-encoded as JPEG with the EXIF orientation applied to the pixels. This drops every
 * EXIF tag (location, device, time) while the document stays readable.
 */
internal class DocumentUploadPreparer(private val workDirectory: File) {

    fun prepare(source: File): PreparedUpload {
        if (!source.isFile || source.length() == 0L) return PreparedUpload.Invalid(ActionRejection.FILE_ERROR)
        return try {
            when (detect(source)) {
                Kind.PDF -> passThrough(source, "application/pdf", "pdf")
                Kind.PNG -> passThrough(source, "image/png", "png")
                Kind.PHOTO -> reencodeAsJpeg(source)
                Kind.UNSUPPORTED -> PreparedUpload.Invalid(ActionRejection.UNSUPPORTED_FILE)
            }
        } catch (_: IOException) {
            PreparedUpload.Invalid(ActionRejection.FILE_ERROR)
        }
    }

    private enum class Kind { PDF, PNG, PHOTO, UNSUPPORTED }

    /** By content, never by file name. Camera/gallery formats (JPEG, WebP, HEIC) become JPEG. */
    private fun detect(source: File): Kind {
        val header = ByteArray(HEADER_BYTES)
        val read = source.inputStream().use { it.read(header) }
        return when {
            header.hasAt(0, PDF_MAGIC, read) -> Kind.PDF
            header.hasAt(0, PNG_MAGIC, read) -> Kind.PNG
            header.hasAt(0, JPEG_MAGIC, read) ||
                (header.hasAt(0, RIFF, read) && header.hasAt(8, WEBP, read)) ||
                header.hasAt(4, ISO_MEDIA_BOX, read) -> if (isDecodableImage(source)) Kind.PHOTO else Kind.UNSUPPORTED
            else -> Kind.UNSUPPORTED
        }
    }

    private fun passThrough(source: File, contentType: String, extension: String): PreparedUpload =
        if (source.length() > MAX_UPLOAD_BYTES) {
            PreparedUpload.Invalid(ActionRejection.FILE_TOO_LARGE)
        } else {
            PreparedUpload.Ready(source, "${source.nameWithoutExtension}.$extension", contentType, temporary = false)
        }

    private fun isDecodableImage(source: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        return bounds.outWidth > 0 && bounds.outHeight > 0
    }

    private fun reencodeAsJpeg(source: File): PreparedUpload {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_EDGE_PX) sample *= 2
        val decoded = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return PreparedUpload.Invalid(ActionRejection.UNSUPPORTED_FILE)
        val bitmap = upright(decoded, orientationOf(source))
        val target = File(workDirectory.apply { mkdirs() }, "${source.nameWithoutExtension}.jpg")
        try {
            for (quality in JPEG_QUALITIES) {
                target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
                if (target.length() in 1..MAX_UPLOAD_BYTES) {
                    return PreparedUpload.Ready(target, target.name, "image/jpeg", temporary = true)
                }
            }
        } finally {
            bitmap.recycle()
        }
        target.delete()
        return PreparedUpload.Invalid(ActionRejection.FILE_TOO_LARGE)
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
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun ByteArray.hasAt(offset: Int, expected: ByteArray, read: Int): Boolean =
        read >= offset + expected.size && expected.indices.all { this[offset + it] == expected[it] }

    companion object {
        const val MAX_UPLOAD_BYTES = 10L * 1024 * 1024
        private const val HEADER_BYTES = 12
        private const val MAX_IMAGE_EDGE_PX = 4096
        private val JPEG_QUALITIES = intArrayOf(90, 80, 70)
        private val PDF_MAGIC = "%PDF".toByteArray(Charsets.US_ASCII)
        private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        private val RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
        private val WEBP = "WEBP".toByteArray(Charsets.US_ASCII)
        private val ISO_MEDIA_BOX = "ftyp".toByteArray(Charsets.US_ASCII)
    }
}
