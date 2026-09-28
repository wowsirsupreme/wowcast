package com.wowsir.wowcast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log

/**
 * Foreground service that captures the screen via MediaProjection and streams
 * each frame to the MS2160/MS9120 dongle over USB, reproducing the original
 * app's pure-Java transfer path.
 *
 * Audio: this service sends video only. The dongle exposes its own USB Audio
 * Class interface, and Android routes system/media sound to it automatically
 * (as on the Samsung), so no audio handling is needed here.
 */
class CaptureService : Service() {

    companion object {
        const val ACTION_START = "com.wowsir.wowcast.START"
        const val ACTION_STOP = "com.wowsir.wowcast.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_USB_DEVICE = "usbDevice"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_VIC = "vic"

        private const val CHANNEL_ID = "wowcast_capture"
        private const val NOTIF_ID = 1001
        private const val BULK_CHUNK = 16384
        private const val TAG = "WOWCast/Service"

        @Volatile var isRunning = false
            private set
    }

    private var link: UsbLink? = null
    private var proto: MsProtocol? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var width = 1280
    private var height = 720
    private var vic = MsProtocol.VIC_1280x720_60

    private var srcBuf: ByteArray? = null
    private var outBuf: ByteArray? = null
    private var frameId = 0
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }

        width = intent.getIntExtra(EXTRA_WIDTH, 1280)
        height = intent.getIntExtra(EXTRA_HEIGHT, 720)
        vic = intent.getIntExtra(EXTRA_VIC, MsProtocol.VIC_1280x720_60)
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData: Intent? = getParcelable(intent, EXTRA_RESULT_DATA, Intent::class.java)
        val device: UsbDevice? = getParcelable(intent, EXTRA_USB_DEVICE, UsbDevice::class.java)

        startForegroundCompat()

        if (device == null || resultData == null || resultCode == 0) {
            Log.e(TAG, "Missing start extras")
            stopEverything()
            return START_NOT_STICKY
        }

        // 1) Open USB and run the chip start-up sequence.
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val l = UsbLink.open(usbManager, device)
        if (l == null) {
            Log.e(TAG, "USB open failed")
            stopEverything()
            return START_NOT_STICKY
        }
        link = l
        proto = MsProtocol(l).also { it.startTransmission(width, height, vic) }

        // 2) Start MediaProjection -> VirtualDisplay -> ImageReader.
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(resultCode, resultData)
        if (mp == null) {
            Log.e(TAG, "getMediaProjection returned null")
            stopEverything()
            return START_NOT_STICKY
        }
        projection = mp

        thread = HandlerThread("wowcast-capture").apply { start() }
        handler = Handler(thread!!.looper)

        // Register a callback (required on Android 14+).
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection stopped")
                stopEverything()
            }
        }, handler)

        val density = resources.displayMetrics.densityDpi
        val reader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ r -> onFrame(r) }, handler)
        imageReader = reader

        virtualDisplay = mp.createVirtualDisplay(
            "wowcast",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler
        )

        started = true
        isRunning = true
        Log.i(TAG, "Mirroring started ${width}x${height} vic=$vic")
        return START_NOT_STICKY
    }

    private fun onFrame(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            Log.w(TAG, "acquireLatestImage: ${e.message}"); null
        } ?: return
        try {
            val p = proto ?: return
            val lnk = link ?: return
            val plane = image.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val w = image.width
            val h = image.height

            var src = srcBuf
            if (src == null || src.size < buffer.remaining()) {
                src = ByteArray(buffer.remaining()); srcBuf = src
            }
            val n = buffer.remaining()
            buffer.get(src, 0, n)

            val outLen = w * h * 3
            var out = outBuf
            if (out == null || out.size < outLen) {
                out = ByteArray(outLen); outBuf = out
            }
            val extraPixels = (rowStride - w * 4) / 4
            FrameConverter.rgbaToChip(src, w, h, if (extraPixels > 0) extraPixels else 0, out)

            // Per-frame control handshake, then bulk the pixels in 16 KB chunks.
            p.frameTransferSwitch(frameId)
            p.xdataWrite(MsProtocol.REG_VPACK_TRANSFER, 1)
            var offset = 0
            while (offset < outLen) {
                val len = minOf(BULK_CHUNK, outLen - offset)
                val sent = lnk.bulk(out, offset, len)
                if (sent < 0) { Log.w(TAG, "bulk failed at $offset"); break }
                offset += len
            }
            frameId = frameId xor 1
        } catch (e: Exception) {
            Log.e(TAG, "onFrame error", e)
        } finally {
            image.close()
        }
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "WOWCast", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("WOWCast")
            .setContentText("Mirroring screen over USB")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun stopEverything() {
        if (!isRunning && !started) {
            stopForegroundCompat(); stopSelf(); return
        }
        try { proto?.stopTransmission() } catch (_: Exception) {}
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        try { link?.close() } catch (_: Exception) {}
        thread?.quitSafely()
        virtualDisplay = null; imageReader = null; projection = null
        link = null; proto = null; thread = null; handler = null
        started = false; isRunning = false
        stopForegroundCompat()
        stopSelf()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    private fun <T> getParcelable(intent: Intent, key: String, clazz: Class<T>): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, clazz)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(key) as? T
        }
    }
}
