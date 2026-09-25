package com.example.orisischeat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream

/**
 * Foreground service that takes a single screenshot using MediaProjection.
 *
 * Flow: [MainActivity] forwards the user's consent (resultCode + data) here.
 * The service promotes itself to foreground (mandatory for mediaProjection
 * FGS type on API 34+), creates a [VirtualDisplay] backed by an [ImageReader],
 * waits for the first frame, saves it as a PNG in app storage and broadcasts
 * the result back to the activity before stopping itself.
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var callbackThread: HandlerThread? = null
    private var stopped = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // User revoked capture from the system cast/notification UI.
            finish(error = getString(R.string.status_failed_short))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action != ACTION_START_CAPTURE) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
        @Suppress("DEPRECATION")
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode == Int.MIN_VALUE || resultData == null) {
            finish(error = "missing projection consent")
            return START_NOT_STICKY
        }

        startInForeground()

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, resultData)?.also {
            it.registerCallback(projectionCallback, Handler(mainLooper))
        }

        if (projection == null) {
            finish(error = "projection unavailable")
            return START_NOT_STICKY
        }

        captureFirstFrame()
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun captureFirstFrame() {
        val metrics: DisplayMetrics = resources.displayMetrics
        val width = Math.max(metrics.widthPixels, 1)
        val height = Math.max(metrics.heightPixels, 1)
        val dpi = metrics.densityDpi

        callbackThread = HandlerThread("ScreenCaptureThread").also { it.start() }
        val handler = Handler(callbackThread!!.looper)

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).also { reader ->
            reader.setOnImageAvailableListener({ r ->
                var image: Image? = null
                try {
                    image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    handleFrame(image)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to read frame", e)
                    finish(error = e.message ?: "frame read error")
                } finally {
                    image?.close()
                }
            }, handler)

            virtualDisplay = projection!!.createVirtualDisplay(
                "OrisisCheatCapture",
                width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler
            )
        }
    }

    /** Converts an RGBA frame to a Bitmap, rotates it to portrait if needed and saves it. */
    private fun handleFrame(image: Image) {
        val plane = image.planes[0]
        val rowPadding = plane.rowStride - image.width * plane.pixelStride
        val bitmapWidth = image.width + rowPadding / plane.pixelStride

        val raw = Bitmap.createBitmap(bitmapWidth, image.height, Bitmap.Config.ARGB_8888).apply {
            copyPixelsFromBuffer(plane.buffer)
        }
        val cropped = if (bitmapWidth > image.width) {
            Bitmap.createBitmap(raw, 0, 0, image.width, image.height).also { raw.recycle() }
        } else {
            raw
        }

        // VirtualDisplay mirrors the natural display orientation; if the device is
        // portrait the frame comes out landscape, so rotate it back.
        val rotated = if (cropped.width > cropped.height) {
            val matrix = android.graphics.Matrix().apply { postRotate(90f) }
            Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, matrix, true).also { cropped.recycle() }
        } else {
            cropped
        }

        val file = newOutputFile()
        FileOutputStream(file).use { out ->
            rotated.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        rotated.recycle()

        finish(path = file.absolutePath)
    }

    private fun newOutputFile(): File {
        val dir = File(getExternalFilesDir(null) ?: filesDir, "captures").apply { mkdirs() }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        return File(dir, "capture_$stamp.png")
    }

    private fun finish(path: String? = null, error: String? = null) {
        if (stopped) return
        stopped = true

        if (path != null) {
            sendBroadcast(Intent(ACTION_CAPTURED).setPackage(packageName).putExtra(EXTRA_PATH, path))
        } else {
            sendBroadcast(Intent(ACTION_CAPTURE_FAILED).setPackage(packageName).putExtra(EXTRA_ERROR, error))
        }

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        callbackThread?.quitSafely()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        finish() // safety net if the system kills us mid-capture
    }

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "screen_capture_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START_CAPTURE = "com.example.orisischeat.action.START_CAPTURE"
        const val ACTION_CAPTURED = "com.example.orisischeat.action.CAPTURED"
        const val ACTION_CAPTURE_FAILED = "com.example.orisischeat.action.CAPTURE_FAILED"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_PATH = "path"
        const val EXTRA_ERROR = "error"
    }
}
