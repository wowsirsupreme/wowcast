package com.wowsir.wowcast

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

/**
 * Low-level USB link to the MacroSilicon MS2160 / MS9120 dongle.
 * Pure Android USB Host API (no native lib) -> runs on every ABI.
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

        const val REQTYPE_WRITE = 0x21
        const val REQTYPE_READ = 0xA1
        const val REQ_SET = 9
        const val REQ_GET = 1
        const val WVALUE = 0x0300

        fun findDongle(manager: UsbManager): UsbDevice? =
            manager.deviceList.values.firstOrNull {
                it.vendorId == VENDOR_ID && it.productId == PRODUCT_ID
            }

        private fun epType(t: Int) = when (t) {
            UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
            UsbConstants.USB_ENDPOINT_XFER_INT -> "INT"
            UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOC"
            UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CTRL"
            else -> "?"
        }

        fun open(manager: UsbManager, device: UsbDevice): UsbLink? {
            val connection = manager.openDevice(device) ?: run {
                AppLog.log("openDevice returned null (USB permission problem?)."); return null
            }

            // Dump the full topology so we can see the real layout.
            AppLog.log("USB device: ${device.interfaceCount} interface(s).")
            var bulkEp: UsbEndpoint? = null
            var bulkIface: UsbInterface? = null
            var ctrlIface: UsbInterface? = null
            for (i in 0 until device.interfaceCount) {
                val itf = device.getInterface(i)
                AppLog.log("  iface[$i] id=${itf.id} class=${itf.interfaceClass} alt=${itf.alternateSetting} eps=${itf.endpointCount}")
                for (e in 0 until itf.endpointCount) {
                    val ep = itf.getEndpoint(e)
                    val dir = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                    AppLog.log("    ep addr=0x%02X %s %s max=%d".format(ep.address, dir, epType(ep.type), ep.maxPacketSize))
                    if (ep.address == BULK_ENDPOINT_ADDRESS) { bulkEp = ep; bulkIface = itf }
                    if (bulkEp == null && ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        ep.direction == UsbConstants.USB_DIR_OUT) { bulkEp = ep; bulkIface = itf }
                }
                // The control (HID) interface is usually class 3 (HID); remember interface 0 as fallback.
                if (itf.interfaceClass == UsbConstants.USB_CLASS_HID && ctrlIface == null) ctrlIface = itf
            }
            if (ctrlIface == null && device.interfaceCount > 0) ctrlIface = device.getInterface(0)

            if (bulkEp == null || bulkIface == null) {
                AppLog.log("No bulk-OUT endpoint found. Cannot send video.")
                connection.close(); return null
            }
            AppLog.log("Using bulk-OUT ep addr=0x%02X on iface id=%d".format(bulkEp.address, bulkIface.id))

            val toClaim = LinkedHashSet<UsbInterface>()
            ctrlIface?.let { toClaim.add(it) }
            toClaim.add(bulkIface)
            val claimedOk = ArrayList<UsbInterface>()
            for (itf in toClaim) {
                val ok = connection.claimInterface(itf, true)
                AppLog.log("claimInterface id=${itf.id} class=${itf.interfaceClass} -> $ok")
                if (ok) claimedOk.add(itf)
            }
            if (claimedOk.isEmpty()) {
                AppLog.log("Could not claim any interface."); connection.close(); return null
            }
            return UsbLink(connection, claimedOk, bulkEp)
        }
    }

    fun controlWrite(data: ByteArray): Int =
        connection.controlTransfer(REQTYPE_WRITE, REQ_SET, WVALUE, 0, data, data.size, 1000)

    fun controlRead(data: ByteArray): Int {
        connection.controlTransfer(REQTYPE_WRITE, REQ_SET, WVALUE, 0, data, data.size, 1000)
        return connection.controlTransfer(REQTYPE_READ, REQ_GET, WVALUE, 0, data, data.size, 1000)
    }

    fun bulk(buffer: ByteArray, offset: Int, length: Int): Int =
        connection.bulkTransfer(bulkOut, buffer, offset, length, 1000)

    fun close() {
        for (itf in claimed) { try { connection.releaseInterface(itf) } catch (_: Exception) {} }
        connection.close()
    }
}
