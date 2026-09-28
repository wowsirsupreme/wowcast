package com.wowsir.wowcast

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.wowsir.wowcast.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var usbManager: UsbManager
    private lateinit var mpm: MediaProjectionManager

    private var device: UsbDevice? = null

    private data class Res(val label: String, val w: Int, val h: Int, val vic: Int)
    private val resolutions = listOf(
        Res("1280 x 720 (720p)", 1280, 720, MsProtocol.VIC_1280x720_60),
        Res("1920 x 1080 (1080p)", 1920, 1080, MsProtocol.VIC_1920x1080_60)
    )

    private data class ColorMode(val label: String, val value: Int)
    private val colorModes = listOf(
        ColorMode("RGB (compatible)", MsProtocol.COLORSPACE_RGB888),
        ColorMode("YUV422 (faster)", MsProtocol.COLORSPACE_YUV422)
    )

    private val usbPermissionAction get() = "$packageName.USB_PERMISSION"

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            AppLog.log("Screen-capture consent returned: resultCode=${result.resultCode} " +
                "(OK=${Activity.RESULT_OK}, CANCELED=${Activity.RESULT_CANCELED}), data=${result.data != null}")
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                startMirroring(result.resultCode, result.data!!)
            } else {
                AppLog.log("Screen capture not granted. If no dialog appeared, HyperOS/Knox may be blocking it.")
            }
        }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                usbPermissionAction -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) {
                        AppLog.log("USB permission granted. Tap 'Start mirroring' again to continue.")
                    } else {
                        AppLog.log("USB permission denied.")
                    }
                    refreshStatus()
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> detectDevice()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> { device = null; refreshStatus() }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.logView.movementMethod = ScrollingMovementMethod()
        b.logView.text = AppLog.dump()
        AppLog.listener = { text -> runOnUiThread { b.logView.text = text } }

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        b.resSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, resolutions.map { it.label }
        )
        b.colorSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, colorModes.map { it.label }
        )

        b.startBtn.setOnClickListener { onStartClicked() }
        b.stopBtn.setOnClickListener {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
            AppLog.log("Stop requested.")
            setRunningUi(false)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val filter = IntentFilter().apply {
            addAction(usbPermissionAction)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        registerReceiverCompat(filter)

        AppLog.log("WOWCast 1.1 started.")
        detectDevice()
    }

    override fun onResume() {
        super.onResume()
        setRunningUi(CaptureService.isRunning)
        detectDevice()
    }

    override fun onDestroy() {
        AppLog.listener = null
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun onStartClicked() {
        val dev = device
        if (dev == null) { AppLog.log("No dongle detected. Plug in the USB adapter."); return }
        if (usbManager.hasPermission(dev)) {
            requestProjection()
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                android.app.PendingIntent.FLAG_MUTABLE else 0
            val pi = android.app.PendingIntent.getBroadcast(
                this, 0, Intent(usbPermissionAction).setPackage(packageName), flags
            )
            AppLog.log("Requesting USB permission for the dongle...")
            usbManager.requestPermission(dev, pi)
        }
    }

    private fun requestProjection() {
        try {
            AppLog.log("Launching screen-capture consent dialog...")
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        } catch (e: Exception) {
            AppLog.log("Could not launch screen-capture consent: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun startMirroring(resultCode: Int, data: Intent) {
        val dev = device ?: return
        val res = resolutions[b.resSpinner.selectedItemPosition]
        val svc = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_START
            putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureService.EXTRA_RESULT_DATA, data)
            putExtra(CaptureService.EXTRA_USB_DEVICE, dev)
            putExtra(CaptureService.EXTRA_WIDTH, res.w)
            putExtra(CaptureService.EXTRA_HEIGHT, res.h)
            putExtra(CaptureService.EXTRA_VIC, res.vic)
            putExtra(CaptureService.EXTRA_COLOR, colorModes[b.colorSpinner.selectedItemPosition].value)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc)
        else startService(svc)
        AppLog.log("Screen capture granted. Starting at ${res.label}, ${colorModes[b.colorSpinner.selectedItemPosition].label}...")
        setRunningUi(true)
    }

    private fun detectDevice() {
        val found = UsbLink.findDongle(usbManager)
        val attached: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else @Suppress("DEPRECATION") intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        device = found ?: attached
        refreshStatus()
    }

    private fun refreshStatus() {
        val d = device
        b.deviceStatus.text = if (d != null)
            "Dongle: connected (VID 0x%04X PID 0x%04X)".format(d.vendorId, d.productId)
        else "Dongle: not connected"
        b.startBtn.isEnabled = d != null && !CaptureService.isRunning
    }

    private fun setRunningUi(running: Boolean) {
        b.startBtn.isEnabled = !running && device != null
        b.stopBtn.isEnabled = running
    }

    private fun registerReceiverCompat(filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(usbReceiver, filter)
        }
    }
}
