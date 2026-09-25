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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts.TakePicture
import androidx.core.content.FileProvider
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textview.MaterialTextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * First screen of the app: lets the user capture the device screen
 * via MediaProjection and shows the saved screenshot.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: MaterialTextView
    private lateinit var captureButton: MaterialButton
    private lateinit var previewCard: MaterialCardView
    private lateinit var screenshotPreview: ImageView
    private lateinit var extractButton: MaterialButton
    private lateinit var extractProgress: LinearProgressIndicator
    private lateinit var resultCard: MaterialCardView
    private lateinit var questionText: TextView
    private lateinit var answersText: TextView

    /** Path of the most recent screenshot or photo, used by the extraction step. */
    private var lastCapturePath: String? = null

    /** Pending photo target (uri + backing path) for the camera flow. */
    private var pendingPhotoUri: Uri? = null
    private var pendingPhotoPath: String? = null

    /**
     * Launches the system camera app to take a photo of a physical question
     * (paper, whiteboard, book page…). The picture is saved via FileProvider
     * and then flows through the same extract pipeline as a screenshot.
     */
    private val takePictureLauncher = registerForActivityResult(TakePicture()) { success ->
        val path = pendingPhotoPath
        if (success && path != null) {
            onCaptureSaved(path)
        } else {
            statusText.setText(R.string.status_idle)
        }
    }

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
        extractButton = findViewById(R.id.extractButton)
        extractProgress = findViewById(R.id.extractProgress)
        resultCard = findViewById(R.id.resultCard)
        questionText = findViewById(R.id.questionText)
        answersText = findViewById(R.id.answersText)

        val photoButton = findViewById<MaterialButton>(R.id.photoButton)
        captureButton.setOnClickListener { onCaptureClicked() }
        photoButton.setOnClickListener { onPhotoClicked() }
        extractButton.setOnClickListener { onExtractClicked() }

        QuestionExtractor.init(applicationContext)
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
            lastCapturePath = path
            screenshotPreview.setImageURI(Uri.fromFile(java.io.File(path)))
            previewCard.visibility = View.VISIBLE
            extractButton.isEnabled = true
        }
        Snackbar.make(captureButton, R.string.status_captured, Snackbar.LENGTH_LONG).show()
    }

    private fun onPhotoClicked() {
        val dir = java.io.File(getFilesDir(), "photos").apply { mkdirs() }
        val file = java.io.File(dir, "photo_${System.currentTimeMillis()}.jpg")
        pendingPhotoPath = file.absolutePath
        pendingPhotoUri = FileProvider.getUriForFile(
            this, "${packageName}.fileprovider", file
        )
        statusText.setText(R.string.status_photo_pending)
        takePictureLauncher.launch(pendingPhotoUri)
    }

    private fun onExtractClicked() {
        val path = lastCapturePath ?: run {
            Toast.makeText(this, R.string.no_capture_yet, Toast.LENGTH_SHORT).show()
            return
        }

        statusText.setText(R.string.status_extracting)
        extractButton.isEnabled = false
        extractProgress.visibility = View.VISIBLE
        resultCard.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val (bytes, mime) = ScreenshotLoader.loadForModel(path)
                val result = QuestionExtractor.extract(bytes, mime)
                showExtractionResult(result)
            } catch (e: Exception) {
                statusText.text = getString(R.string.status_extraction_failed, e.message ?: "unknown")
                Toast.makeText(this@MainActivity, e.message ?: "Extraction failed", Toast.LENGTH_LONG).show()
            } finally {
                extractProgress.visibility = View.GONE
                extractButton.isEnabled = true
            }
        }
    }

    private fun showExtractionResult(result: ExtractedQuestion) {
        statusText.setText(R.string.status_extraction_done)
        questionText.text = result.question
        answersText.text = result.answers
            .mapIndexed { i, a -> "${'A' + i}. $a" }
            .joinToString(separator = "\n")
        resultCard.visibility = View.VISIBLE
    }

    private fun onCaptureFailed(error: String?) {
        statusText.text = getString(R.string.status_failed, error ?: "unknown")
        Snackbar.make(captureButton, R.string.status_failed_short, Snackbar.LENGTH_LONG).show()
    }
}
