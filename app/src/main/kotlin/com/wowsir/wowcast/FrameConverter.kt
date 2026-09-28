package com.wowsir.wowcast

/**
 * Converts an RGBA_8888 frame (as delivered by ImageReader, which may include
 * per-row padding) into the packed 3-bytes-per-pixel buffer the MS2160 expects.
 *
 * Byte order matches the original Ms2160Util.rgb8888torgb888: for each pixel it
 * emits src[B], src[G], src[R] (i.e. the source's byte 2,1,0), and skips the
 * row padding between rows.
 */
object FrameConverter {

    /**
     * @param src           raw RGBA bytes from plane 0
     * @param width         frame width in pixels
     * @param height        frame height in pixels
     * @param extraPixels   row padding expressed in pixels ((rowStride - width*4) / 4)
     * @param out           destination buffer, must be at least width*height*3 bytes
     */
    fun rgbaToChip(src: ByteArray, width: Int, height: Int, extraPixels: Int, out: ByteArray) {
        var s = 0
        var d = 0
        val rowSkip = extraPixels * 4
        for (y in 0 until height) {
            for (x in 0 until width) {
                out[d] = src[s + 2]     // B
                out[d + 1] = src[s + 1] // G
                out[d + 2] = src[s]     // R
                d += 3
                s += 4
            }
            s += rowSkip
        }
    }
}
