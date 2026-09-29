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

/**
 * Captures the screen and streams frames to the MS2160/MS9120 dongle over USB.
 * Strictly serial: one full frame is converted and sent (two control commands + one
 * bulk transfer) before the next is processed. This keeps the dongle's double-buffer
 * in lockstep so the picture stays stable (no rolling/tearing).
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
        const val EXTRA_COLOR = "color"

        private const val CHANNEL_ID = "wowcast_capture"
        private const val NOTIF_ID = 1001

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
    private var colorspace = MsProtocol.COLORSPACE_RGB888
    private var colorMode = 1   // 1=RGB888, 2=YUV422 YUYV, 3=YUV422 UYVY
    private var bpp = 3

    private var srcBuf: ByteArray? = null
    private var outBuf: ByteArray? = null
    private var frameId = 0
    private var started = false
    private var frameCount = 0
    private var perfLogged = false
    private var perfStart = 0L
    @Volatile private var busy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) { stopEverything(); return START_NOT_STICKY }

        width = intent.getIntExtra(EXTRA_WIDTH, 1280)
        height = intent.getIntExtra(EXTRA_HEIGHT, 720)
        vic = intent.getIntExtra(EXTRA_VIC, MsProtocol.VIC_1280x720_60)
        colorMode = intent.getIntExtra(EXTRA_COLOR, 1)
        colorspace = if (colorMode == 1) MsProtocol.COLORSPACE_RGB888 else MsProtocol.COLORSPACE_YUV422
        bpp = if (colorMode == 1) 3 else 2
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData: Intent? = getParcelable(intent, EXTRA_RESULT_DATA, Intent::class.java)
        val device: UsbDevice? = getParcelable(intent, EXTRA_USB_DEVICE, UsbDevice::class.java)

        try { startForegroundCompat() } catch (e: Exception) {
            AppLog.log("Foreground service failed: ${e.message}"); stopEverything(); return START_NOT_STICKY
        }
        if (isRunning || started) {
            AppLog.log("Already mirroring; ignoring duplicate start.")
            return START_NOT_STICKY
        }
        if (device == null || resultData == null || resultCode == 0) {
            AppLog.log("Service missing start data."); stopEverything(); return START_NOT_STICKY
        }

        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val l = UsbLink.open(usbManager, device)
        if (l == null) { AppLog.log("USB open/claim failed."); stopEverything(); return START_NOT_STICKY }
        link = l
        try {
            proto = MsProtocol(l).also { it.startTransmission(width, height, vic, colorspace) }
            AppLog.log("Dongle initialized (${width}x${height}, color=$colorspace).")
        } catch (e: Exception) {
            AppLog.log("Chip start-up failed: ${e.message}"); stopEverything(); return START_NOT_STICKY
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = try { mpm.getMediaProjection(resultCode, resultData) } catch (e: Exception) {
            AppLog.log("getMediaProjection threw: ${e.message}"); null
        }
        if (mp == null) { AppLog.log("MediaProjection null."); stopEverything(); return START_NOT_STICKY }
        projection = mp

        thread = HandlerThread("wowcast-capture").apply { start() }
        handler = Handler(thread!!.looper)
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { AppLog.log("MediaProjection stopped."); stopEverything() }
        }, handler)

        val density = resources.displayMetrics.densityDpi
        val reader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ r -> onFrame(r) }, handler)
        imageReader = reader

        try {
            virtualDisplay = mp.createVirtualDisplay(
                "wowcast", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, handler
            )
        } catch (e: Exception) {
            AppLog.log("createVirtualDisplay failed: ${e.message}"); stopEverything(); return START_NOT_STICKY
        }

        started = true
        isRunning = true
        perfStart = System.currentTimeMillis()
        AppLog.log("Mirroring started. Waiting for frames...")
        return START_NOT_STICKY
    }

    private fun onFrame(reader: ImageReader) {
        if (busy) { try { reader.acquireLatestImage()?.close() } catch (_: Exception) {}; return }
        val image = try { reader.acquireLatestImage() } catch (e: Exception) { null } ?: return
        busy = true
        try {
            val p = proto ?: return
            val lnk = link ?: return
            val plane = image.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val w = image.width
            val h = image.height

            var src = srcBuf
            val n = buffer.remaining()
            if (src == null || src.size < n) { src = ByteArray(n); srcBuf = src }
            buffer.get(src, 0, n)

            val outLen = w * h * bpp
            var out = outBuf
            if (out == null || out.size < outLen) { out = ByteArray(outLen); outBuf = out }
            val extraPixels = (rowStride - w * 4) / 4
            val extra = if (extraPixels > 0) extraPixels else 0
            when (colorMode) {
                2 -> FrameConverter.rgbaToYuv422(src, w, h, extra, out)
                3 -> FrameConverter.rgbaToUyvy(src, w, h, extra, out)
                else -> FrameConverter.rgbaToChip(src, w, h, extra, out)
            }

            p.frameTransferSwitch(frameId)
            p.xdataWrite(MsProtocol.REG_VPACK_TRANSFER, 1)
            val ok = lnk.sendFrame(out, outLen)
            frameId = frameId xor 1
            frameCount++
            if (frameCount == 1) AppLog.log(if (ok) "First frame sent OK." else "First frame FAILED (<0).")
            if (!perfLogged && frameCount == 60) {
                val secs = (System.currentTimeMillis() - perfStart) / 1000.0
                if (secs > 0) AppLog.log("~%.1f fps over first 60 frames.".format(60.0 / secs))
                perfLogged = true
            }
        } catch (e: Exception) {
            AppLog.log("Frame error: ${e.message}")
        } finally {
            try { image.close() } catch (_: Exception) {}
            busy = false
        }
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "WOWCast", NotificationManager.IMPORTANCE_LOW))
        }
        val notif: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("WOWCast").setContentText("Mirroring screen over USB")
            .setSmallIcon(android.R.drawable.ic_menu_share).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIF_ID, notif)
    }

    private fun stopEverything() {
        try { proto?.stopTransmission() } catch (_: Exception) {}
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        try { link?.close() } catch (_: Exception) {}
        thread?.quitSafely()
        virtualDisplay = null; imageReader = null; projection = null
        link = null; proto = null; thread = null; handler = null
        started = false; isRunning = false; frameCount = 0; perfLogged = false; busy = false
        stopForegroundCompat()
        stopSelf()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    override fun onDestroy() { stopEverything(); super.onDestroy() }

    private fun <T> getParcelable(intent: Intent, key: String, clazz: Class<T>): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(key, clazz)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(key) as? T
    }
}
