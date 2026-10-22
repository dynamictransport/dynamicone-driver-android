package com.dynamictransport.dynamicone

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dynamictransport.dynamicone.data.media.AndroidProfilePhotoImages
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The copy of the HR photo kept on the phone (docs/21): small, upright, and without the original's EXIF data. */
@RunWith(AndroidJUnit4::class)
class ProfilePhotoImagesDeviceTest {

    private val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "photo-test").apply { mkdirs() }
    private val images = AndroidProfilePhotoImages()

    @After
    fun tearDown() {
        folder.deleteRecursively()
    }

    /** A landscape camera photo whose EXIF says "rotate 90°" and that carries a GPS position. */
    private fun cameraPhoto(): File {
        val file = File(folder, "camera.jpg")
        val bitmap = Bitmap.createBitmap(2000, 1000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(40, 120, 200)) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setLatLong(52.2297, 21.0122)
            saveAttributes()
        }
        return file
    }

    @Test
    fun reducesTurnsUprightAndDropsExif() {
        val output = File(folder, "reduced.jpg")

        assertTrue(images.reduce(cameraPhoto(), output, 512))

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(output.path, bounds)
        assertEquals(256, bounds.outWidth)
        assertEquals(512, bounds.outHeight)
        val exif = ExifInterface(output.path)
        assertTrue(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED) in
            setOf(ExifInterface.ORIENTATION_UNDEFINED, ExifInterface.ORIENTATION_NORMAL))
        assertNull(exif.latLong)
    }

    @Test
    fun aSmallPhotoIsNotEnlarged() {
        val source = File(folder, "small.png")
        Bitmap.createBitmap(120, 160, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.GRAY) }
            .let { bitmap -> source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        val output = File(folder, "reduced.jpg")

        assertTrue(images.reduce(source, output, 512))

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(output.path, bounds)
        assertEquals(120, bounds.outWidth)
        assertEquals(160, bounds.outHeight)
    }

    @Test
    fun somethingThatIsNotAnImageIsRejected() {
        val source = File(folder, "not-an-image.jpg").apply { writeText("<html>error</html>") }

        assertFalse(images.reduce(source, File(folder, "reduced.jpg"), 512))
    }
}
