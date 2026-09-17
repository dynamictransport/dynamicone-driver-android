package com.dynamictransport.dynamicone.domain.scan

import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * 8-bit grayscale image (0 = black, 255 = white) used by the document checks. Pure Kotlin, so the same
 * rules run on live camera frames, on stored photos and in plain JVM tests.
 */
class LumaImage(val width: Int, val height: Int, val pixels: IntArray) {

    init {
        require(width > 0 && height > 0 && pixels.size == width * height) { "Invalid image size ${width}x$height" }
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]

    /** Clockwise rotation by a multiple of 90 degrees (camera frame rotation, EXIF orientation). */
    fun rotate(degrees: Int): LumaImage {
        val turn = Math.floorMod(degrees, 360)
        require(turn % 90 == 0) { "Unsupported rotation $degrees" }
        if (turn == 0) return this
        val outWidth = if (turn == 180) width else height
        val outHeight = if (turn == 180) height else width
        val out = IntArray(outWidth * outHeight)
        for (y in 0 until outHeight) {
            val row = y * outWidth
            for (x in 0 until outWidth) {
                out[row + x] = when (turn) {
                    90 -> this[y, height - 1 - x]
                    180 -> this[width - 1 - x, height - 1 - y]
                    else -> this[width - 1 - y, x]
                }
            }
        }
        return LumaImage(outWidth, outHeight, out)
    }

    /** Area-averaged copy whose longer edge is at most [maxEdge] pixels. */
    fun downscale(maxEdge: Int): LumaImage {
        val factor = (max(width, height) + maxEdge - 1) / maxEdge
        if (factor <= 1 || min(width, height) < factor) return this
        val outWidth = width / factor
        val outHeight = height / factor
        val out = IntArray(outWidth * outHeight)
        val cellArea = factor * factor
        for (y in 0 until outHeight) {
            for (x in 0 until outWidth) {
                var sum = 0
                for (dy in 0 until factor) {
                    val row = (y * factor + dy) * width + x * factor
                    for (dx in 0 until factor) sum += pixels[row + dx]
                }
                out[y * outWidth + x] = sum / cellArea
            }
        }
        return LumaImage(outWidth, outHeight, out)
    }

    /** Copy of a rectangle, clamped to the image. */
    fun crop(left: Int, top: Int, cropWidth: Int, cropHeight: Int): LumaImage {
        val x0 = left.coerceIn(0, width - 1)
        val y0 = top.coerceIn(0, height - 1)
        val w = cropWidth.coerceIn(1, width - x0)
        val h = cropHeight.coerceIn(1, height - y0)
        val out = IntArray(w * h)
        for (y in 0 until h) System.arraycopy(pixels, (y0 + y) * width + x0, out, y * w, w)
        return LumaImage(w, h, out)
    }

    /** Centred crop that keeps [fraction] of each edge. */
    fun centerCrop(fraction: Float): LumaImage {
        val w = max(1, (width * fraction).toInt())
        val h = max(1, (height * fraction).toInt())
        return crop((width - w) / 2, (height - h) / 2, w, h)
    }

    /** Box blur over a (2 * radius + 1) square; at the border only existing pixels are averaged. */
    fun boxBlur(radius: Int): LumaImage {
        if (radius <= 0) return this
        val horizontal = IntArray(pixels.size)
        val prefix = IntArray(max(width, height) + 1)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) prefix[x + 1] = prefix[x] + pixels[row + x]
            for (x in 0 until width) {
                val from = max(0, x - radius)
                val to = min(width - 1, x + radius)
                horizontal[row + x] = (prefix[to + 1] - prefix[from]) / (to - from + 1)
            }
        }
        val out = IntArray(pixels.size)
        for (x in 0 until width) {
            for (y in 0 until height) prefix[y + 1] = prefix[y] + horizontal[y * width + x]
            for (y in 0 until height) {
                val from = max(0, y - radius)
                val to = min(height - 1, y + radius)
                out[y * width + x] = (prefix[to + 1] - prefix[from]) / (to - from + 1)
            }
        }
        return LumaImage(width, height, out)
    }

    /** Grayscale dilation: every pixel becomes the brightest value within a (2 * radius + 1) square. */
    fun maxFilter(radius: Int): LumaImage {
        if (radius <= 0) return this
        val rows = IntArray(pixels.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var brightest = 0
                for (k in max(0, x - radius)..min(width - 1, x + radius)) brightest = max(brightest, pixels[row + k])
                rows[row + x] = brightest
            }
        }
        val out = IntArray(pixels.size)
        for (x in 0 until width) {
            for (y in 0 until height) {
                var brightest = 0
                for (k in max(0, y - radius)..min(height - 1, y + radius)) brightest = max(brightest, rows[k * width + x])
                out[y * width + x] = brightest
            }
        }
        return LumaImage(width, height, out)
    }

    companion object {
        /** Luminance of ARGB pixels (ITU-R BT.601 weights). */
        fun fromArgb(width: Int, height: Int, argb: IntArray): LumaImage =
            LumaImage(width, height, IntArray(argb.size) { index ->
                val color = argb[index]
                (299 * (color shr 16 and 0xFF) + 587 * (color shr 8 and 0xFF) + 114 * (color and 0xFF)) / 1000
            })

        /**
         * The [width] x [height] rectangle at [left], [top] of a camera frame's Y plane (YUV_420_888), area-averaged
         * so its longer edge is at most [maxEdge]. [rowStride] can be larger than the frame width (padded rows).
         */
        fun fromLumaPlane(
            buffer: ByteBuffer,
            rowStride: Int,
            left: Int,
            top: Int,
            width: Int,
            height: Int,
            maxEdge: Int = Int.MAX_VALUE,
        ): LumaImage {
            val longEdge = max(width, height)
            val factor = if (longEdge <= maxEdge) 1 else (longEdge + maxEdge - 1) / maxEdge
            val outWidth = width / factor
            val outHeight = height / factor
            val out = IntArray(outWidth * outHeight)
            val cellArea = factor * factor
            for (y in 0 until outHeight) {
                for (x in 0 until outWidth) {
                    var sum = 0
                    for (dy in 0 until factor) {
                        val row = (top + y * factor + dy) * rowStride + left + x * factor
                        for (dx in 0 until factor) sum += buffer.get(row + dx).toInt() and 0xFF
                    }
                    out[y * outWidth + x] = sum / cellArea
                }
            }
            return LumaImage(outWidth, outHeight, out)
        }
    }
}
