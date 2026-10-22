package com.dynamictransport.dynamicone.data.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.max

/** The server's photo rules (docs/24 §6): at most 1600 px on the longest side, at most 600 KiB, upright, no EXIF. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SnapshotPhotoPreparerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val preparer = BitmapSnapshotPhotoPreparer()

    private fun cameraJpeg(width: Int, height: Int, orientation: Int = ExifInterface.ORIENTATION_NORMAL): File {
        val file = folder.newFile("capture.jpg")
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // Noise keeps the JPEG large, like a real road photo.
        val pixels = IntArray(width * height) { i -> Color.rgb((i * 31) % 256, (i * 17) % 256, (i * 7) % 256) }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        bitmap.recycle()
        if (orientation != ExifInterface.ORIENTATION_NORMAL) {
            ExifInterface(file.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
        }
        return file
    }

    @Test
    fun `a large camera photo is scaled down, kept small and stripped of EXIF`() {
        val source = cameraJpeg(4000, 3000)
        val target = File(folder.root, "out/req-1.jpg")

        assertTrue(preparer.prepare(source, target))

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(target.path, bounds)
        assertTrue("${bounds.outWidth}x${bounds.outHeight}", max(bounds.outWidth, bounds.outHeight) <= BitmapSnapshotPhotoPreparer.MAX_EDGE_PX)
        assertTrue("${target.length()} bytes", target.length() in 1..BitmapSnapshotPhotoPreparer.MAX_BYTES)
        // The source is deleted too; not asserted here because Robolectric's native decoder keeps the file open on Windows.
        assertEquals(ExifInterface.ORIENTATION_UNDEFINED, ExifInterface(target.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED))
    }

    @Test
    fun `the EXIF orientation is applied to the pixels`() {
        val source = cameraJpeg(1200, 800, ExifInterface.ORIENTATION_ROTATE_90)
        val target = File(folder.root, "rotated.jpg")

        assertTrue(preparer.prepare(source, target))

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(target.path, bounds)
        assertEquals(800, bounds.outWidth)
        assertEquals(1200, bounds.outHeight)
    }

    @Test
    fun `a file that is no image is refused and nothing is left behind`() {
        val source = folder.newFile("broken.jpg").apply { writeBytes(ByteArray(100)) }
        val target = File(folder.root, "broken-out.jpg")

        assertFalse(preparer.prepare(source, target))

        assertFalse(target.exists())
    }
}
