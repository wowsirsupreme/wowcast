package com.wowsir.wowcast

/**
 * Converts RGBA_8888 frames (with possible row padding) into the chip's pixel formats.
 * All functions convert only rows [rowStart, rowEnd) so the work can be split across
 * CPU cores. src row stride in bytes = (width + extraPixels) * 4.
 */
object FrameConverter {

    /** RGB888 in the chip's byte order (B,G,R). out >= width*height*3. */
    fun rgbaToChip(src: ByteArray, width: Int, height: Int, extraPixels: Int, out: ByteArray,
                   rowStart: Int = 0, rowEnd: Int = height) {
        val srcStride = (width + extraPixels) * 4
        for (y in rowStart until rowEnd) {
            var s = y * srcStride
            var d = y * width * 3
            var x = 0
            while (x < width) {
                out[d] = src[s + 2]; out[d + 1] = src[s + 1]; out[d + 2] = src[s]
                d += 3; s += 4; x++
            }
        }
    }

    /** Packed YUV422 (YUYV), BT.601. out >= width*height*2. width should be even. */
    fun rgbaToYuv422(src: ByteArray, width: Int, height: Int, extraPixels: Int, out: ByteArray,
                     rowStart: Int = 0, rowEnd: Int = height) {
        val srcStride = (width + extraPixels) * 4
        val pairs = width and 1.inv()
        for (y in rowStart until rowEnd) {
            var s = y * srcStride
            var d = y * width * 2
            var x = 0
            while (x < pairs) {
                val r0 = src[s].toInt() and 0xFF; val g0 = src[s + 1].toInt() and 0xFF; val b0 = src[s + 2].toInt() and 0xFF
                val r1 = src[s + 4].toInt() and 0xFF; val g1 = src[s + 5].toInt() and 0xFF; val b1 = src[s + 6].toInt() and 0xFF
                val y0 = (77 * r0 + 150 * g0 + 29 * b0) shr 8
                val y1 = (77 * r1 + 150 * g1 + 29 * b1) shr 8
                var u = ((-43 * r0 - 84 * g0 + 127 * b0) shr 8) + 128
                var v = ((127 * r0 - 106 * g0 - 21 * b0) shr 8) + 128
                if (u < 0) u = 0 else if (u > 255) u = 255
                if (v < 0) v = 0 else if (v > 255) v = 255
                out[d] = (if (y0 < 0) 0 else if (y0 > 255) 255 else y0).toByte()
                out[d + 1] = u.toByte()
                out[d + 2] = (if (y1 < 0) 0 else if (y1 > 255) 255 else y1).toByte()
                out[d + 3] = v.toByte()
                d += 4; s += 8; x += 2
            }
        }
    }
}
