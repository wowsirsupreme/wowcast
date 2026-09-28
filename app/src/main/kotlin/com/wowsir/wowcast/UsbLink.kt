package com.wowsir.wowcast

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log

/**
 * Low-level USB link to the MacroSilicon MS2160 / MS9120 dongle.
 *
 * Reconstructed from the original app's pure-Java transfer path
 * (USBDevice.java + CaptureService.BulkSendSplit, usethread == 0), which uses
 * only Android's UsbDeviceConnection.controlTransfer()/bulkTransfer(). Because
 * no native library is involved, this runs on every ABI (arm64-v8a included).
 */
class UsbLink private constructor(
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val bulkOut: UsbEndpoint
) {
    companion object {
        const val VENDOR_ID = 0x534D   // 21325  "SM" (MacroSilicon)
        const val PRODUCT_ID = 0x6021  // 24609
        private const val BULK_ENDPOINT_ADDRESS = 0x04
        private const val TAG = "WOWCast/UsbLink"

        // Control transfer shape used for every chip command:
        // bmRequestType=0x21 (Host->Device, Class, Interface), bRequest=9 (SET_REPORT),
        // wValue=0x0300, wIndex=0.  Read direction uses 0xA1 / bRequest 1.
        const val REQTYPE_WRITE = 0x21
        const val REQTYPE_READ = 0xA1
        const val REQ_SET = 9
        const val REQ_GET = 1
        const val WVALUE = 0x0300

        fun findDongle(manager: UsbManager): UsbDevice? =
            manager.deviceList.values.firstOrNull {
                it.vendorId == VENDOR_ID && it.productId == PRODUCT_ID
            }

        /** Opens the dongle. Permission must already be granted. Returns null on failure. */
        fun open(manager: UsbManager, device: UsbDevice): UsbLink? {
            val connection = manager.openDevice(device) ?: run {
                Log.e(TAG, "openDevice returned null (permission not granted?)")
                return null
            }
            // Locate interface 0 and the bulk-OUT endpoint at address 0x04.
            var chosenIface: UsbInterface? = null
            var chosenEp: UsbEndpoint? = null
            outer@ for (i in 0 until device.interfaceCount) {
                val itf = device.getInterface(i)
                for (e in 0 until itf.endpointCount) {
                    val ep = itf.getEndpoint(e)
                    if (ep.address == BULK_ENDPOINT_ADDRESS) {
                        chosenIface = itf
                        chosenEp = ep
                        break@outer
                    }
                }
            }
            // Fallback: first bulk-OUT endpoint on any interface.
            if (chosenEp == null) {
                outer2@ for (i in 0 until device.interfaceCount) {
                    val itf = device.getInterface(i)
                    for (e in 0 until itf.endpointCount) {
                        val ep = itf.getEndpoint(e)
                        if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                            ep.direction == UsbConstants.USB_DIR_OUT
                        ) {
                            chosenIface = itf
                            chosenEp = ep
                            break@outer2
                        }
                    }
                }
            }
            if (chosenIface == null || chosenEp == null) {
                Log.e(TAG, "No suitable bulk-OUT endpoint found")
                connection.close()
                return null
            }
            if (!connection.claimInterface(chosenIface, true)) {
                Log.e(TAG, "claimInterface failed")
                connection.close()
                return null
            }
            return UsbLink(connection, chosenIface, chosenEp)
        }
    }

    /** Sends an 8-byte command over the control endpoint. Returns bytes transferred or <0. */
    fun controlWrite(data: ByteArray): Int =
        connection.controlTransfer(REQTYPE_WRITE, REQ_SET, WVALUE, 0, data, data.size, 1000)

    /** Write-then-read control exchange (used for register reads). */
    fun controlRead(data: ByteArray): Int {
        connection.controlTransfer(REQTYPE_WRITE, REQ_SET, WVALUE, 0, data, data.size, 1000)
        return connection.controlTransfer(REQTYPE_READ, REQ_GET, WVALUE, 0, data, data.size, 1000)
    }

    /** Bulk-OUT a slice of [buffer]. */
    fun bulk(buffer: ByteArray, offset: Int, length: Int): Int =
        connection.bulkTransfer(bulkOut, buffer, offset, length, 1000)

    fun close() {
        try {
            connection.releaseInterface(iface)
        } catch (_: Exception) {
        }
        connection.close()
    }
}
