package com.dynamictransport.driver.data.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dynamictransport.driver.domain.scan.ScanIssue
import com.dynamictransport.driver.domain.scan.ScanMode
import com.dynamictransport.driver.domain.scan.ScanOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.random.Random

/** Runs the real Android bitmap pipeline (Robolectric native graphics) on synthetic document photos. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BitmapDocumentScannerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scanner = BitmapDocumentScanner(Dispatchers.Unconfined)

    /** Sheet corners in a 3000 x 4000 photo: slightly rotated and in perspective, like a hand-held shot. */
    private val sheet = floatArrayOf(360f, 420f, 2640f, 480f, 2760f, 3600f, 260f, 3540f)

    /** A light page (dark header band at the top, text lines, blank bottom margin) on a dark seat. */
    private fun documentPhoto(width: Int, height: Int, corners: FloatArray): Bitmap {
        val pageWidth = 2100
        val pageHeight = 2970
        val page = Bitmap.createBitmap(pageWidth, pageHeight, Bitmap.Config.ARGB_8888)
        Canvas(page).apply {
            drawColor(Color.rgb(232, 230, 224))
            val ink = Paint().apply { color = Color.rgb(35, 35, 60) }
            drawRect(0f, 150f, pageWidth.toFloat(), 450f, ink)
            val random = Random(5)
            var y = 700f
            while (y < pageHeight - 550f) {
                var x = 150f
                while (x < pageWidth - 400f) {
                    val word = 60f + random.nextInt(220)
                    drawRect(x, y, x + word, y + 28f, ink)
                    x += word + 40f
                }
                y += 70f
            }
        }
        val photo = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pageCorners = floatArrayOf(0f, 0f, pageWidth.toFloat(), 0f, pageWidth.toFloat(), pageHeight.toFloat(), 0f, pageHeight.toFloat())
        val matrix = Matrix().apply { setPolyToPoly(pageCorners, 0, corners, 0, 4) }
        Canvas(photo).apply {
            drawColor(Color.rgb(38, 42, 48))
            drawBitmap(page, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
        page.recycle()
        return photo
    }

    private fun save(bitmap: Bitmap, exifOrientation: Int? = null): File {
        val file = folder.newFile()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bitmap.recycle()
        if (exifOrientation != null) {
            ExifInterface(file.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation.toString())
                saveAttributes()
            }
        }
        return file
    }

    private fun luma(color: Int): Int = (299 * Color.red(color) + 587 * Color.green(color) + 114 * Color.blue(color)) / 1000

    /** Average brightness of the row at [fraction] of the height, over the middle 80 % of the width. */
    private fun rowLuma(bitmap: Bitmap, fraction: Float): Int {
        val y = (bitmap.height * fraction).toInt()
        val from = bitmap.width / 10
        val to = bitmap.width * 9 / 10
        return (from until to).sumOf { luma(bitmap.getPixel(it, y)) } / (to - from)
    }

    private fun assertLooksLikeAScan(result: Bitmap) {
        assertTrue("header band should be at the top", rowLuma(result, 0.10f) < 120)
        assertTrue("bottom margin should be blank paper", rowLuma(result, 0.95f) > 200)
        // Paper right up to the edges: no seat left around the sheet.
        assertTrue(luma(result.getPixel(result.width / 100, result.height * 9 / 10)) > 200)
        assertTrue(luma(result.getPixel(result.width * 99 / 100, result.height * 9 / 10)) > 200)
    }

    @Test
    fun `sheet is found, cut out and straightened like a scan`() = runTest {
        val source = save(documentPhoto(3000, 4000, sheet))
        val target = folder.newFile()

        val outcome = scanner.process(source.path, target.path, ScanMode.DOCUMENT)

        assertEquals(ScanOutcome.Processed(target.path, emptyList()), outcome)
        val result = BitmapFactory.decodeFile(target.path)
        assertTrue("width ${result.width}", result.width in 2400..2600)
        assertTrue("height ${result.height}", result.height in 3000..3250)
        assertLooksLikeAScan(result)
    }

    @Test
    fun `camera rotation stored in EXIF is applied`() = runTest {
        val upright = documentPhoto(3000, 4000, sheet)
        val stored = Bitmap.createBitmap(upright, 0, 0, upright.width, upright.height, Matrix().apply { postRotate(-90f) }, true)
        upright.recycle()
        val source = save(stored, ExifInterface.ORIENTATION_ROTATE_90)
        val target = folder.newFile()

        val outcome = scanner.process(source.path, target.path, ScanMode.DOCUMENT)

        assertEquals(ScanOutcome.Processed(target.path, emptyList()), outcome)
        val result = BitmapFactory.decodeFile(target.path)
        assertTrue(result.height > result.width)
        assertLooksLikeAScan(result)
    }

    @Test
    fun `photo taken from too far away is rejected`() = runTest {
        val small = FloatArray(sheet.size) { sheet[it] * 0.3f }
        val source = save(documentPhoto(900, 1200, small))
        val target = folder.newFile()

        val outcome = scanner.process(source.path, target.path, ScanMode.DOCUMENT) as ScanOutcome.Processed

        assertTrue(outcome.issues.toString(), ScanIssue.LOW_RESOLUTION in outcome.issues)
    }

    @Test
    fun `picture without a sheet is rejected but still shown`() = runTest {
        val plain = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(128, 128, 128)) }
        val source = save(plain)
        val target = folder.newFile()

        val outcome = scanner.process(source.path, target.path, ScanMode.DOCUMENT) as ScanOutcome.Processed

        assertTrue(ScanIssue.NO_DOCUMENT in outcome.issues)
        assertTrue(target.length() > 0)
    }

    @Test
    fun `container photo is kept whole`() = runTest {
        val photo = Bitmap.createBitmap(2400, 1800, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(120, 130, 140)) }
        val source = save(photo)
        val target = folder.newFile()

        val outcome = scanner.process(source.path, target.path, ScanMode.PHOTO)

        assertEquals(ScanOutcome.Processed(target.path, emptyList()), outcome)
        val result = BitmapFactory.decodeFile(target.path)
        assertEquals(2400, result.width)
        assertEquals(1800, result.height)
    }

    @Test
    fun `a file that is not an image is unreadable`() = runTest {
        val source = folder.newFile().apply { writeText("not an image") }

        assertEquals(ScanOutcome.Unreadable, scanner.process(source.path, folder.newFile().path, ScanMode.DOCUMENT))
    }
}
