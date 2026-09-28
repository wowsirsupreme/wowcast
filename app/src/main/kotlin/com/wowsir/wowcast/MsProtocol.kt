package com.wowsir.wowcast

/**
 * MS2160 / MS9120 command set, reproduced verbatim from the original
 * app's USBDevice.java. Every command is an 8-byte payload sent as a
 * control transfer (see UsbLink). Byte values match the decompiled source
 * exactly (0xA6 == the "special" HID command prefix).
 */
class MsProtocol(private val link: UsbLink) {

    companion object {
        // Colorspaces (Util.DataType.COLORSPACE)
        const val COLORSPACE_RGB888 = 1
        const val COLORSPACE_YUV422 = 2

        // Transfer mode (Util.DataType.TRANSFER_MODE)
        const val TRANSFER_MODE_FRAME = 0

        // Output video timing codes (_E_AS7160_VIDEO_FORMAT_)
        const val VIC_720x480P_60 = 2
        const val VIC_720x576P_50 = 17
        const val VIC_1280x720_60 = 79
        const val VIC_1920x1080_60 = 129

        // Register (RegisterMap.REG_VPACK_TRANSFER)
        const val REG_VPACK_TRANSFER = 61954  // 0xF202

        // Output colorspace fed to set_video_out. The original used a display
        // colorspace field that defaults to 0; expose it so it can be tuned.
        const val OUT_COLORSPACE = 0
    }

    private fun b(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    fun setPowerOn(on: Boolean): Int =
        link.controlWrite(b(0xA6, 0x07, if (on) 1 else 0, if (on) 2 else 0, 0, 0, 0, 0))

    fun setVideoIn(width: Int, height: Int, colorByte: Int, colSel: Int): Int =
        link.controlWrite(
            b(0xA6, 0x01, (width shr 8) and 0xFF, width and 0xFF,
                (height shr 8) and 0xFF, height and 0xFF, colorByte and 0xFF, colSel and 0xFF)
        )

    fun setVideoOut(vic: Int, colorspace: Int, width: Int, height: Int): Int =
        link.controlWrite(
            b(0xA6, 0x02, vic and 0xFF, colorspace and 0xFF,
                (width shr 8) and 0xFF, width and 0xFF, (height shr 8) and 0xFF, height and 0xFF)
        )

    fun setTransferModeFrame(): Int =
        link.controlWrite(b(0xA6, 0x03, TRANSFER_MODE_FRAME, 0, 0, 0, 0, 0))

    fun setStartTrans(start: Boolean): Int =
        link.controlWrite(b(0xA6, 0x04, if (start) 1 else 0, 0, 0, 0, 0, 0))

    fun setVideoOn(on: Boolean): Int =
        link.controlWrite(b(0xA6, 0x05, if (on) 1 else 0, 0, 0, 0, 0, 0))

    /** Alternates the double-buffer frame index (0/1) between frames. */
    fun frameTransferSwitch(frameId: Int): Int =
        link.controlWrite(b(0x12, 0xF2, 0x02, 0x00, frameId and 0xFF, 0, 0, 0))

    /** Writes one XDATA register. Mirrors xdata_write_switch: addr 0xF202/0xF203 sets flag byte. */
    fun xdataWrite(addr: Int, value: Int): Int {
        val flag = if (addr == 61954 || addr == 61955) 1 else 0
        return link.controlWrite(
            b(0xB6, (addr shr 8) and 0xFF, addr and 0xFF, value and 0xFF, flag, 0, 0, 0)
        )
    }

    /**
     * Full start-up sequence, matching CaptureService.start_transaction() plus
     * the surrounding power/video-on calls. Input resolution == output resolution.
     */
    fun startTransmission(width: Int, height: Int, vic: Int) {
        val alignedW = (width + 3) and 3.inv()
        // color byte = (memColorspace << 4) | inColorspace ; both RGB888 -> 0x11
        val colorByte = (COLORSPACE_RGB888 shl 4) or COLORSPACE_RGB888
        setPowerOn(true)
        setTransferModeFrame()
        setVideoIn(alignedW, height, colorByte, 0)
        setVideoOut(vic, OUT_COLORSPACE, width, height)
        setStartTrans(true)
        setVideoOn(true)
    }

    fun stopTransmission() {
        setStartTrans(false)
        setVideoOn(false)
        setPowerOn(false)
    }
}
