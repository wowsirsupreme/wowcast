package com.wowsir.wowcast

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import java.nio.ByteBuffer

/**
 * USB link to the MS2160 / MS9120 dongle. Pure Android USB Host API (all ABIs).
 * Video frames are sent with asynchronous double-buffered UsbRequests so two bulk
 * transfers are always in flight, keeping the USB 2.0 pipe saturated.
 */
class UsbLink private constructor(
    private val connection: UsbDeviceConnection,
    private val claimed: List<UsbInterface>,
    private val bulkOut: UsbEndpoint
) {
    companion object {
        const val VENDOR_ID = 0x534D
        const val PRODUCT_ID = 0x6021
        private const val BULK_ENDPOINT_ADDRESS = 0x04
        private const val CHUNK = 262144   // 256 KB, multiple of 512

        const val REQTYPE_WRITE = 0x21
        const val REQ_SET = 9
        const val WVALUE = 0x0300

        fun findDongle(manager: UsbManager): UsbDevice? =
            manager.deviceList.values.firstOrNull { it.vendorId == VENDOR_ID && it.productId == PRODUCT_ID }

        private fun epType(t: Int) = when (t) {
            UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"; UsbConstants.USB_ENDPOINT_XFER_INT -> "INT"
            UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOC"; else -> "?"
        }

        fun open(manager: UsbManager, device: UsbDevice): UsbLink? {
            val connection = manager.openDevice(device) ?: run { AppLog.log("openDevice null."); return null }
            AppLog.log("USB device: ${device.interfaceCount} interface(s).")
            var bulkEp: UsbEndpoint? = null; var bulkIface: UsbInterface? = null; var ctrlIface: UsbInterface? = null
            for (i in 0 until device.interfaceCount) {
                val itf = device.getInterface(i)
                for (e in 0 until itf.endpointCount) {
                    val ep = itf.getEndpoint(e)
                    if (ep.address == BULK_ENDPOINT_ADDRESS) { bulkEp = ep; bulkIface = itf }
                    if (bulkEp == null && ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        ep.direction == UsbConstants.USB_DIR_OUT) { bulkEp = ep; bulkIface = itf }
                }
                if (itf.interfaceClass == UsbConstants.USB_CLASS_HID && ctrlIface == null) ctrlIface = itf
            }
            if (ctrlIface == null && device.interfaceCount > 0) ctrlIface = device.getInterface(0)
            if (bulkEp == null || bulkIface == null) { AppLog.log("No bulk-OUT endpoint."); connection.close(); return null }

            val toClaim = LinkedHashSet<UsbInterface>(); ctrlIface?.let { toClaim.add(it) }; toClaim.add(bulkIface)
            val ok = ArrayList<UsbInterface>()
            for (itf in toClaim) if (connection.claimInterface(itf, true)) ok.add(itf)
            if (ok.isEmpty()) { AppLog.log("Could not claim interface."); connection.close(); return null }
            AppLog.log("USB ready: bulk ep=0x%02X %s, %d cores".format(bulkEp.address, epType(bulkEp.type), Runtime.getRuntime().availableProcessors()))
            return UsbLink(connection, ok, bulkEp)
        }
    }

    // Async double-buffering
    private val reqA = UsbRequest()
    private val reqB = UsbRequest()
    private val bufA: ByteBuffer = ByteBuffer.allocateDirect(CHUNK)
    private val bufB: ByteBuffer = ByteBuffer.allocateDirect(CHUNK)
    private val asyncOk: Boolean = reqA.initialize(connection, bulkOut) && reqB.initialize(connection, bulkOut)

    fun controlWrite(data: ByteArray): Int =
        connection.controlTransfer(REQTYPE_WRITE, REQ_SET, WVALUE, 0, data, data.size, 1000)

    /** Sends a full frame. Uses async double-buffering when available, else sync chunks. */
    fun sendFrame(data: ByteArray, len: Int): Boolean {
        if (!asyncOk) return sendSync(data, len)
        val reqs = arrayOf(reqA, reqB); val bufs = arrayOf(bufA, bufB)
        var off = 0; var pending = 0
        // prime up to 2 transfers
        var i = 0
        while (i < 2 && off < len) {
            val n = if (CHUNK < len - off) CHUNK else len - off
            val b = bufs[i]; b.clear(); b.put(data, off, n); b.flip()
            if (!reqs[i].queue(b)) return sendSync(data, off) // fallback for remainder is rare
            off += n; pending++; i++
        }
        while (pending > 0) {
            val done = connection.requestWait() ?: return false
            pending--
            if (off < len) {
                val slot = if (done === reqA) 0 else 1
                val n = if (CHUNK < len - off) CHUNK else len - off
                val b = bufs[slot]; b.clear(); b.put(data, off, n); b.flip()
                if (!reqs[slot].queue(b)) return false
                off += n; pending++
            }
        }
        return true
    }

    private fun sendSync(data: ByteArray, len: Int): Boolean {
        var off = 0
        while (off < len) {
            val n = if (CHUNK < len - off) CHUNK else len - off
            if (connection.bulkTransfer(bulkOut, data, off, n, 1000) < 0) return false
            off += n
        }
        return true
    }

    fun close() {
        try { reqA.cancel(); reqB.cancel() } catch (_: Exception) {}
        try { reqA.close(); reqB.close() } catch (_: Exception) {}
        for (itf in claimed) { try { connection.releaseInterface(itf) } catch (_: Exception) {} }
        connection.close()
    }
}
