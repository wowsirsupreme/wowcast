package com.wowsir.wowcast

/** Converts an RGBA_8888 frame (with possible row padding) into the chip's pixel formats. */
object FrameConverter {

    /** RGB888 in the chip's byte order (B,G,R per pixel). out >= w*h*3. */
    fun rgbaToChip(src: ByteArray, width: Int, height: Int, extraPixels: Int, out: ByteArray) {
        var s = 0; var d = 0
        val rowSkip = extraPixels * 4
        for (y in 0 until height) {
            for (x in 0 until width) {
                out[d] = src[s + 2]; out[d + 1] = src[s + 1]; out[d + 2] = src[s]
                d += 3; s += 4
            }
            s += rowSkip
        }
    }

    /** Packed YUV422 (YUYV), BT.601. Halves the data vs RGB888. out >= w*h*2. width should be even. */
    fun rgbaToYuv422(src: ByteArray, width: Int, height: Int, extraPixels: Int, out: ByteArray) {
        var s = 0; var d = 0
        val rowSkip = extraPixels * 4
        val pairs = width and 1.inv()
        for (y in 0 until height) {
            var x = 0
            while (x < pairs) {
                val r0 = src[s].toInt() and 0xFF; val g0 = src[s + 1].toInt() and 0xFF; val b0 = src[s + 2].toInt() and 0xFF
                val r1 = src[s + 4].toInt() and 0xFF; val g1 = src[s + 5].toInt() and 0xFF; val b1 = src[s + 6].toInt() and 0xFF
                val y0 = (77 * r0 + 150 * g0 + 29 * b0) shr 8
                val y1 = (77 * r1 + 150 * g1 + 29 * b1) shr 8
                val u = ((-43 * r0 - 84 * g0 + 127 * b0) shr 8) + 128
                val v = ((127 * r0 - 106 * g0 - 21 * b0) shr 8) + 128
                out[d] = y0.coerceIn(0, 255).toByte()
                out[d + 1] = u.coerceIn(0, 255).toByte()
                out[d + 2] = y1.coerceIn(0, 255).toByte()
                out[d + 3] = v.coerceIn(0, 255).toByte()
                d += 4; s += 8; x += 2
            }
            s += rowSkip
        }
    }
}
