package com.dynamictransport.dynamicone.core.pdf

import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import com.dynamictransport.dynamicone.core.common.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlin.math.roundToInt

/** The pages of an opened PDF, drawn on demand. */
interface PdfPageSource : Closeable {
    val pageCount: Int

    /** Width divided by height of the page, for its place on screen before it is drawn. */
    fun aspectRatio(index: Int): Float

    /** The page [widthPx] wide on a white background; null when it cannot be drawn (closed, damaged). */
    suspend fun render(index: Int, widthPx: Int): ImageBitmap?
}

fun interface PdfDocumentOpener {
    /** Null when the file is not a PDF this phone can show (damaged, password protected). */
    suspend fun open(filePath: String): PdfPageSource?
}

/** The platform's PDF renderer: no extra library, nothing leaves the app. */
class AndroidPdfDocumentOpener @Inject constructor(
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : PdfDocumentOpener {

    override suspend fun open(filePath: String): PdfPageSource? = withContext(io) {
        val descriptor = try {
            ParcelFileDescriptor.open(File(filePath), ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (_: IOException) {
            return@withContext null
        }
        try {
            val renderer = PdfRenderer(descriptor)
            val ratios = (0 until renderer.pageCount).map { index ->
                val page = renderer.openPage(index)
                try {
                    page.width.toFloat() / page.height.coerceAtLeast(1)
                } finally {
                    page.close()
                }
            }
            AndroidPdfPageSource(renderer, descriptor, ratios, io)
        } catch (_: IOException) {
            descriptor.close()
            null
        } catch (_: SecurityException) {
            // Password protected.
            descriptor.close()
            null
        }
    }
}

private class AndroidPdfPageSource(
    private val renderer: PdfRenderer,
    private val descriptor: ParcelFileDescriptor,
    private val ratios: List<Float>,
    private val io: CoroutineDispatcher,
) : PdfPageSource {

    // The renderer draws one page at a time and must not be closed while drawing.
    private val lock = Any()
    private var closed = false

    override val pageCount: Int = ratios.size

    override fun aspectRatio(index: Int): Float = ratios.getOrNull(index)?.takeIf { it > 0f } ?: A4_RATIO

    override suspend fun render(index: Int, widthPx: Int): ImageBitmap? = withContext(io) {
        synchronized(lock) {
            if (closed || index !in 0 until pageCount) return@synchronized null
            val page = renderer.openPage(index)
            try {
                val width = widthPx.coerceIn(1, MAX_WIDTH_PX)
                val height = (width * page.height / page.width.coerceAtLeast(1).toFloat()).roundToInt().coerceAtLeast(1)
                val bitmap = createBitmap(width, height)
                // Pages are transparent where nothing is printed; documents are read on white.
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap.asImageBitmap()
            } finally {
                page.close()
            }
        }
    }

    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        renderer.close()
        descriptor.close()
    }

    private companion object {
        const val A4_RATIO = 210f / 297f

        /** Sharp enough for zooming in on a phone, small enough to keep a few pages in memory. */
        const val MAX_WIDTH_PX = 2400
    }
}
