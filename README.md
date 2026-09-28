WOWCast
=======

Screen mirroring over USB for MacroSilicon MS2160 / MS9120 display dongles
(the "USB to HDMI/RCA" adapter that the MS9120.apk / MS2160 app came with).

WOWCast is a clean-room reimplementation of that app's video transfer path,
rewritten in pure Kotlin so it runs on every Android CPU architecture,
including 64-bit-only (arm64) tablets where the original app fails to transmit.


Why the original failed on some tablets
----------------------------------------
The original app shipped its USB code as 32-bit-only native libraries
(libusb101.so and libmsusb.so; armeabi and armeabi-v7a only, no arm64-v8a).
On tablets without 32-bit support (many current Xiaomi/Redmi models) those
libraries cannot load, so the app opens but never sends video.

WOWCast uses only Android's built-in USB Host API
(UsbDeviceConnection.controlTransfer / bulkTransfer) and MediaProjection.
There is no native library, so there is no architecture restriction.


About audio
-----------
WOWCast sends VIDEO only. That is not a limitation versus the original app,
which also sent video only. On the dongle, audio is carried by a separate
USB Audio Class interface built into the adapter: when you plug it in,
Android routes system and media sound to it automatically (the same way it
does for USB-C headphones), and the dongle merges that audio into the HDMI
output. If sound does not follow on a given device, check the system output/
sound-routing setting and select the USB audio device.


How it works
------------
1. Finds the dongle by USB id (VID 0x534D, PID 0x6021) and asks for permission.
2. Opens interface 0 and the bulk-OUT endpoint at address 0x04.
3. Runs the chip start-up sequence (power on, set video-in, set video-out,
   frame transfer mode, start, video on) using 8-byte control commands.
4. Captures the screen with MediaProjection into an ImageReader (RGBA_8888).
5. For each frame: converts RGBA to the chip's packed 3-byte pixels, sends a
   per-frame control handshake, then bulk-transfers the pixels in 16 KB chunks.

All command bytes, the register (0xF202), the 16 KB chunk size, the endpoint
(0x04) and the frame handshake were taken directly from the original app's
own pure-Java transfer path, so the protocol is faithful.


Building the APK
----------------
Option A - GitHub Actions (no local setup):
  1. Create a new GitHub repository and push this project to it
     (default branch main or master).
  2. The included workflow (.github/workflows/build-apk.yml) builds
     automatically. Open the Actions tab, wait for the run to finish, and
     download the "wowcast-apk" artifact. Inside is wowcast.apk.
  3. You can also trigger it manually from the Actions tab (Run workflow).

Option B - Android Studio:
  1. Open this folder in Android Studio (Giraffe or newer).
  2. Let it sync, then Build > Build APK(s). The APK lands in
     app/build/outputs/apk/debug/.

Option C - Command line (with Android SDK installed):
  ./gradlew :app:assembleDebug
  Output: app/build/outputs/apk/debug/app-debug.apk


Installing
----------
Copy the APK to the tablet and install it (allow "install from unknown
sources"). Plug in the dongle, open WOWCast, pick a resolution, tap
"Start mirroring", and approve the screen-capture prompt.


Status / notes
--------------
- Default output is 720p (lower USB 2.0 bandwidth, smoother); 1080p is offered
  too but is heavier over USB 2.0.
- Frames are sent as packed RGB (RGB888 path). A YUV422 path (half the data)
  can be added later for higher frame rates.
- The set_video_out colorspace byte defaults to 0 (matches the original's
  default). If output color looks wrong on your specific display, that single
  constant (MsProtocol.OUT_COLORSPACE) is the first thing to adjust.
- This talks to real hardware; the first on-device run is where any remaining
  chip-specific tuning would show up.
