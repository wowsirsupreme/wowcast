package com.wowsir.wowcast

/**
 * MS2160 / MS9120 command set, reproduced from the original app's USBDevice.java.
 * Each command is an 8-byte control-transfer payload (0xA6 = "special" prefix).
 */
class MsProtocol(private val link: UsbLink) {

    companion object {
        const val COLORSPACE_RGB888 = 1
        const val COLORSPACE_YUV422 = 2
        const val TRANSFER_MODE_FRAME = 0
        const val VIC_720x480P_60 = 2
        const val VIC_720x576P_50 = 17
        const val VIC_1280x720_60 = 79
        const val VIC_1920x1080_60 = 129
        const val REG_VPACK_TRANSFER = 61954  // 0xF202
        const val OUT_COLORSPACE = 0
    }

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    fun setPowerOn(on: Boolean) =
        link.controlWrite(bytes(0xA6, 0x07, if (on) 1 else 0, if (on) 2 else 0, 0, 0, 0, 0))

    fun setVideoIn(width: Int, height: Int, colorByte: Int, colSel: Int) =
        link.controlWrite(bytes(0xA6, 0x01, (width shr 8) and 0xFF, width and 0xFF,
            (height shr 8) and 0xFF, height and 0xFF, colorByte and 0xFF, colSel and 0xFF))

    fun setVideoOut(vic: Int, colorspace: Int, width: Int, height: Int) =
        link.controlWrite(bytes(0xA6, 0x02, vic and 0xFF, colorspace and 0xFF,
            (width shr 8) and 0xFF, width and 0xFF, (height shr 8) and 0xFF, height and 0xFF))

    fun setTransferModeFrame() = link.controlWrite(bytes(0xA6, 0x03, TRANSFER_MODE_FRAME, 0, 0, 0, 0, 0))
    fun setStartTrans(start: Boolean) = link.controlWrite(bytes(0xA6, 0x04, if (start) 1 else 0, 0, 0, 0, 0, 0))
    fun setVideoOn(on: Boolean) = link.controlWrite(bytes(0xA6, 0x05, if (on) 1 else 0, 0, 0, 0, 0, 0))

    fun frameTransferSwitch(frameId: Int) =
        link.controlWrite(bytes(0x12, 0xF2, 0x02, 0x00, frameId and 0xFF, 0, 0, 0))

    fun xdataWrite(addr: Int, value: Int): Int {
        val flag = if (addr == 61954 || addr == 61955) 1 else 0
        return link.controlWrite(bytes(0xB6, (addr shr 8) and 0xFF, addr and 0xFF, value and 0xFF, flag, 0, 0, 0))
    }

    fun startTransmission(width: Int, height: Int, vic: Int, colorspace: Int = COLORSPACE_RGB888) {
        val alignedW = (width + 3) and 3.inv()
        val colorByte = (colorspace shl 4) or colorspace
        val r1 = setPowerOn(true)
        val r2 = setTransferModeFrame()
        val r3 = setVideoIn(alignedW, height, colorByte, 0)
        val r4 = setVideoOut(vic, OUT_COLORSPACE, width, height)
        val r5 = setStartTrans(true)
        val r6 = setVideoOn(true)
        AppLog.log("Control returns (want 8 each): powerOn=$r1 mode=$r2 videoIn=$r3 videoOut=$r4 startTrans=$r5 videoOn=$r6")
        if (listOf(r1, r2, r3, r4, r5, r6).any { it < 0 })
            AppLog.log("WARNING: a control command returned <0 -> chip did NOT accept setup. Bulk will fail.")
    }

    fun stopTransmission() {
        setStartTrans(false); setVideoOn(false); setPowerOn(false)
    }
}
