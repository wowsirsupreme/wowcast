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
 *
 * Two-stage overlap (the ONLY concurrency): the capture thread converts each frame
 * (single-threaded) into a pooled buffer and hands the newest to a dedicated sender
 * thread, which pushes it over USB (synchronous 64 KB chunks) while the capture thread
 * converts the next one. Stale frames are dropped to keep latency low.
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
    private var bpp = 3

    private var srcBuf: ByteArray? = null
    private var frameId = 0
    private var started = false

    // Two-stage overlap
    private val poolLock = Object()
    private val freeBuffers = ArrayDeque<ByteArray>()
    private val readyLock = Object()
    private var readyBuf: ByteArray? = null
    private var readyLen = 0
    @Volatile private var sending = false
    private var senderThread: Thread? = null
    private var frameCount = 0
    private var perfLogged = false
    private var perfStart = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) { stopEverything(); return START_NOT_STICKY }

        width = intent.getIntExtra(EXTRA_WIDTH, 1280)
        height = intent.getIntExtra(EXTRA_HEIGHT, 720)
        vic = intent.getIntExtra(EXTRA_VIC, MsProtocol.VIC_1280x720_60)
        colorspace = intent.getIntExtra(EXTRA_COLOR, MsProtocol.COLORSPACE_RGB888)
        bpp = if (colorspace == MsProtocol.COLORSPACE_YUV422) 2 else 3
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData: Intent? = getParcelable(intent, EXTRA_RESULT_DATA, Intent::class.java)
        val device: UsbDevice? = getParcelable(intent, EXTRA_USB_DEVICE, UsbDevice::class.java)

        try { startForegroundCompat() } catch (e: Exception) {
            AppLog.log("Foreground service failed: ${e.message}"); stopEverything(); return START_NOT_STICKY
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

        val outLen = width * height * bpp
        synchronized(poolLock) { freeBuffers.clear(); repeat(3) { freeBuffers.addLast(ByteArray(outLen)) } }
        startSender()

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

    /** Capture thread: convert (single-threaded) into a pooled buffer, hand newest to sender. */
    private fun onFrame(reader: ImageReader) {
        val image = try { reader.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val w = image.width
            val h = image.height

            var src = srcBuf
            val n = buffer.remaining()
            if (src == null || src.size < n) { src = ByteArray(n); srcBuf = src }
            buffer.get(src, 0, n)
            image.close()

            val buf = synchronized(poolLock) { if (freeBuffers.isEmpty()) null else freeBuffers.removeLast() }
            if (buf == null) return  // sender still busy; drop this frame

            val outLen = w * h * bpp
            val extraPixels = (rowStride - w * 4) / 4
            val extra = if (extraPixels > 0) extraPixels else 0
            if (colorspace == MsProtocol.COLORSPACE_YUV422)
                FrameConverter.rgbaToYuv422(src, w, h, extra, buf)
            else
                FrameConverter.rgbaToChip(src, w, h, extra, buf)

            synchronized(readyLock) {
                readyBuf?.let { old -> synchronized(poolLock) { freeBuffers.addLast(old) } }
                readyBuf = buf; readyLen = outLen
                readyLock.notifyAll()
            }
            return
        } catch (e: Exception) {
            AppLog.log("Frame convert error: ${e.message}")
        } finally {
            try { image.close() } catch (_: Exception) {}
        }
    }

    /** Sender thread: push newest ready buffer over USB while capture prepares the next. */
    private fun startSender() {
        sending = true
        senderThread = Thread {
            while (sending) {
                var buf: ByteArray? = null; var len = 0
                synchronized(readyLock) {
                    while (sending && readyBuf == null) readyLock.wait()
                    if (!sending) return@Thread
                    buf = readyBuf; len = readyLen; readyBuf = null
                }
                val b = buf ?: continue
                val p = proto; val lnk = link
                if (p == null || lnk == null) { returnBuffer(b); continue }
                try {
                    p.frameTransferSwitch(frameId)
                    p.xdataWrite(MsProtocol.REG_VPACK_TRANSFER, 1)
                    val ok = lnk.sendFrame(b, len)
                    frameId = frameId xor 1
                    frameCount++
                    if (frameCount == 1) AppLog.log(if (ok) "First frame sent OK." else "First frame FAILED (<0).")
                    if (!perfLogged && frameCount == 60) {
                        val secs = (System.currentTimeMillis() - perfStart) / 1000.0
                        if (secs > 0) AppLog.log("~%.1f fps over first 60 frames.".format(60.0 / secs))
                        perfLogged = true
                    }
                } catch (e: Exception) {
                    AppLog.log("Send error: ${e.message}")
                } finally {
                    returnBuffer(b)
                }
            }
        }.also { it.start() }
    }

    private fun returnBuffer(b: ByteArray) { synchronized(poolLock) { freeBuffers.addLast(b) } }

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
        sending = false
        synchronized(readyLock) { readyLock.notifyAll() }
        try { senderThread?.join(500) } catch (_: Exception) {}
        senderThread = null
        try { proto?.stopTransmission() } catch (_: Exception) {}
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        try { link?.close() } catch (_: Exception) {}
        thread?.quitSafely()
        synchronized(poolLock) { freeBuffers.clear() }
        synchronized(readyLock) { readyBuf = null }
        virtualDisplay = null; imageReader = null; projection = null
        link = null; proto = null; thread = null; handler = null
        started = false; isRunning = false; frameCount = 0; perfLogged = false
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
