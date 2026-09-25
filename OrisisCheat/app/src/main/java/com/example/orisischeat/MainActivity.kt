package com.example.orisischeat

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textview.MaterialTextView

/**
 * First screen of the app: lets the user capture the device screen
 * via MediaProjection and shows the saved screenshot.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: MaterialTextView
    private lateinit var captureButton: MaterialButton
    private lateinit var previewCard: MaterialCardView
    private lateinit var screenshotPreview: ImageView

    /** Launches the system MediaProjection consent dialog. */
    private val mediaProjectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                startCaptureService(result.resultCode, result.data!!)
            } else {
                statusText.setText(R.string.status_permission_denied)
                Snackbar.make(captureButton, R.string.status_permission_denied, Snackbar.LENGTH_SHORT)
                    .show()
            }
        }

    /** Asks for the post-notification runtime permission (API 33+). */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Proceed either way: capture still works, notification may be hidden.
            launchProjectionConsent()
        }

    /** Receiver for the service broadcast when a frame is saved. */
    private val captureResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ScreenCaptureService.ACTION_CAPTURED -> onCaptureSaved(
                    intent.getStringExtra(ScreenCaptureService.EXTRA_PATH)
                )
                ScreenCaptureService.ACTION_CAPTURE_FAILED -> onCaptureFailed(
                    intent.getStringExtra(ScreenCaptureService.EXTRA_ERROR)
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))

        statusText = findViewById(R.id.statusText)
        captureButton = findViewById(R.id.captureButton)
        previewCard = findViewById(R.id.previewCard)
        screenshotPreview = findViewById(R.id.screenshotPreview)

        captureButton.setOnClickListener { onCaptureClicked() }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            captureResultReceiver,
            IntentFilter().apply {
                addAction(ScreenCaptureService.ACTION_CAPTURED)
                addAction(ScreenCaptureService.ACTION_CAPTURE_FAILED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(captureResultReceiver)
    }

    private fun onCaptureClicked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        launchProjectionConsent()
    }

    private fun launchProjectionConsent() {
        statusText.setText(R.string.status_waiting_consent)
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startCaptureService(resultCode: Int, data: Intent) {
        statusText.setText(R.string.status_capturing)
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START_CAPTURE
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun onCaptureSaved(path: String?) {
        statusText.setText(R.string.status_captured)
        if (path != null) {
            screenshotPreview.setImageURI(Uri.parse(path))
            previewCard.visibility = View.VISIBLE
        }
        Snackbar.make(captureButton, R.string.status_captured, Snackbar.LENGTH_LONG).show()
    }

    private fun onCaptureFailed(error: String?) {
        statusText.text = getString(R.string.status_failed, error ?: "unknown")
        Snackbar.make(captureButton, R.string.status_failed_short, Snackbar.LENGTH_LONG).show()
    }
}
