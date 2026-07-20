package com.rescue360.detector

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.arashivision.sdkcamera.camera.InstaCameraManager
import com.arashivision.sdkmedia.player.capture.CaptureParamsBuilderV2
import com.arashivision.sdkmedia.player.capture.InstaCapturePlayerView
import com.arashivision.sdkmedia.player.listener.PlayerViewListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Rescue360 Live Detection App
 *
 * Complete Flow:
 * 1. User selects camera's WiFi network
 * 2. Device connects to that WiFi
 * 3. App connects to Insta360 X4 camera
 * 4. Live frame stream starts
 * 5. Each frame automatically sent to cloud/local server over WebSocket
 * 6. Detections received and drawn on screen in real-time
 * 7. Bounding boxes + class labels + confidence shown live
 */
class MainActivity : AppCompatActivity() {

    // UI Components
    private lateinit var statusText: TextView
    private lateinit var topStatusBar: LinearLayout
    private lateinit var cameraPreview: FrameLayout
    private lateinit var capturePlayerView: InstaCapturePlayerView
    private lateinit var previewImage: ImageView
    private lateinit var detectionOverlay: DetectionOverlayView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var wifiStatusText: TextView
    private lateinit var fpsText: TextView
    private lateinit var loadingSpinner: ProgressBar
    private lateinit var bottomControls: LinearLayout
    private lateinit var helpText: TextView
    private lateinit var recordScenarioButton: Button
    private lateinit var recordingIndicator: TextView

    // Managers
    private var wifiManager: WiFiConnectionManager? = null
    private var cameraManager: Insta360CameraManager? = null
    private var frameProcessor: FrameProcessor? = null
    private var detectionClient: DetectionClient? = null

    // State
    private var isRunning = false
    private var isFrameExtractionActive = false
    private val extractedFrameInFlight = AtomicBoolean(false)
    private var framesProcessed = 0L
    private var lastFrameTime = System.currentTimeMillis()
    private var lastExtractedFrameAt = 0L
    private var hasLoggedPreviewTree = false
    private var lastOverlayUpdateAt = 0L
    private var isRecordingScenario = false
    private var activeScenarioId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_live)

        // Initialize Timber logging
        Timber.plant(Timber.DebugTree())
        Timber.d("App created")

        // Bind views
        bindViews()
        capturePlayerView.setLifecycle(lifecycle)

        // Initialize managers
        wifiManager = WiFiConnectionManager(this)
        cameraManager = Insta360CameraManager(this)
        detectionClient = DetectionClient(this)
        frameProcessor = FrameProcessor(
            context = this,
            onDetection = { detections -> displayDetections(detections) },
            onError = { error -> handleError(error) }
        )
        startStaleDetectionCleaner()

        // Set up button listeners
        startButton.setOnClickListener { startLiveDetection() }
        stopButton.setOnClickListener { stopLiveDetection() }
        recordScenarioButton.setOnClickListener { toggleScenarioRecording() }

        // Request WiFi permissions
        requestWiFiPermissions()

        updateStatus("Ready to connect")
        Timber.i("App initialized")
    }

    /**
     * Bind UI views
     */
    private fun bindViews() {
        topStatusBar = findViewById(R.id.top_status_bar)
        statusText = findViewById(R.id.status_text)
        cameraPreview = findViewById(R.id.camera_preview_container)
        capturePlayerView = findViewById(R.id.capture_player_view)
        previewImage = findViewById(R.id.preview_image)
        detectionOverlay = findViewById(R.id.detection_overlay)
        startButton = findViewById(R.id.btn_start)
        stopButton = findViewById(R.id.btn_stop)
        wifiStatusText = findViewById(R.id.wifi_status)
        fpsText = findViewById(R.id.fps_text)
        loadingSpinner = findViewById(R.id.loading_spinner)
        bottomControls = findViewById(R.id.bottom_controls)
        helpText = findViewById(R.id.help_text)
        recordScenarioButton = findViewById(R.id.btn_record_scenario)
        recordingIndicator = findViewById(R.id.recording_indicator)

        stopButton.isEnabled = false
        recordScenarioButton.isEnabled = false
        recordScenarioButton.visibility = View.GONE
        recordingIndicator.visibility = View.GONE
    }

    private fun startCameraPreviewPlayer() {
        enterLiveFullscreen()
        previewImage.visibility = android.view.View.GONE
        capturePlayerView.visibility = android.view.View.VISIBLE
        capturePlayerView.keepScreenOn = true

        capturePlayerView.setPlayerViewListener(object : PlayerViewListener {
            override fun onFirstFrameRender() {
                Timber.i("Insta360 player first frame rendered")
                loadingSpinner.visibility = android.view.View.GONE
                updateStatus("✓ LIVE - Camera preview active")
            }

            override fun onLoadingFinish() {
                Timber.i("Insta360 player loading finished")
                InstaCameraManager.getInstance().setPipeline(capturePlayerView.pipeline)
                if (ENABLE_CLOUD_DETECTION) {
                    startLiveFrameExtraction()
                } else {
                    detectionOverlay.setDetections(emptyList())
                    detectionOverlay.invalidate()
                    Timber.i("Cloud detection disabled: preview-only mode")
                    updateStatus("✓ LIVE - Preview only")
                }
                loadingSpinner.visibility = android.view.View.GONE
            }

            override fun onReleaseCameraPipeline() {
                Timber.i("Insta360 player released camera pipeline")
                isFrameExtractionActive = false
                InstaCameraManager.getInstance().setPipeline(null)
            }
        })

        capturePlayerView.post {
            val previewWidth = capturePlayerView.width.takeIf { it > 0 }
                ?: cameraPreview.width.takeIf { it > 0 }
                ?: 1280
            val previewHeight = capturePlayerView.height.takeIf { it > 0 }
                ?: cameraPreview.height.takeIf { it > 0 }
                ?: 720

            Timber.d("Preparing Insta360 player: ${previewWidth}x$previewHeight")
            capturePlayerView.prepare(CaptureParamsBuilderV2().apply {
                width = previewWidth
                height = previewHeight
                setScreenRatio(previewWidth, previewHeight)
            })
            capturePlayerView.play()
        }
    }

    private fun startLiveFrameExtraction() {
        if (isFrameExtractionActive) {
            return
        }

        isFrameExtractionActive = true
        detectionOverlay.setSourceFrameSize(DETECTION_FRAME_WIDTH, DETECTION_FRAME_HEIGHT)

        Timber.i(
            "Starting clean preview surface capture ${DETECTION_FRAME_WIDTH}px wide @ ${DETECTION_FPS}fps"
        )

        capturePlayerView.post {
            logPreviewTreeOnce()
        }

        lifecycleScope.launch(Dispatchers.Default) {
            while (isActive && isRunning && isFrameExtractionActive) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastExtractedFrameAt < MIN_DETECTION_FRAME_INTERVAL_MS) {
                    delay(10)
                    continue
                }

                if (!extractedFrameInFlight.compareAndSet(false, true)) {
                    delay(10)
                    continue
                }

                lastExtractedFrameAt = now

                try {
                    val bitmap = captureCleanPreviewBitmap()
                    if (bitmap != null) {
                        withContext(Dispatchers.Main) {
                            detectionOverlay.setSourceFrameSize(bitmap.width, bitmap.height)
                        }
                        processFrame(bitmap)
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed capturing clean preview surface frame")
                } finally {
                    extractedFrameInFlight.set(false)
                }
            }
        }
    }

    private suspend fun captureCleanPreviewBitmap(): Bitmap? = withContext(Dispatchers.Main) {
        val previewWidth = capturePlayerView.width.takeIf { it > 0 } ?: return@withContext null
        val previewHeight = capturePlayerView.height.takeIf { it > 0 } ?: return@withContext null
        val targetWidth = DETECTION_FRAME_WIDTH
        val targetHeight = ((targetWidth.toFloat() * previewHeight.toFloat()) / previewWidth.toFloat())
            .toInt()
            .coerceAtLeast(1)

        findSurfaceView(capturePlayerView)?.let { surfaceView ->
            if (surfaceView.width > 0 && surfaceView.height > 0 && surfaceView.isShown) {
                return@withContext pixelCopySurfaceView(surfaceView, targetWidth, targetHeight)
            }
        }

        findTextureView(capturePlayerView)?.let { textureView ->
            if (textureView.width > 0 && textureView.height > 0 && textureView.isAvailable) {
                return@withContext textureView.getBitmap(targetWidth, targetHeight)
            }
        }

        Timber.w("No clean video SurfaceView/TextureView found inside Insta360 preview")
        null
    }

    private suspend fun pixelCopySurfaceView(
        surfaceView: SurfaceView,
        width: Int,
        height: Int
    ): Bitmap? = suspendCancellableCoroutine { continuation ->
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(
                surfaceView,
                bitmap,
                { result ->
                    if (!continuation.isActive) {
                        return@request
                    }
                    if (result == PixelCopy.SUCCESS) {
                        continuation.resume(bitmap)
                    } else {
                        Timber.w("PixelCopy failed: result=$result")
                        continuation.resume(null)
                    }
                },
                Handler(Looper.getMainLooper())
            )
        } catch (e: Exception) {
            Timber.e(e, "PixelCopy request failed")
            if (continuation.isActive) {
                continuation.resume(null)
            }
        }
    }

    private fun findSurfaceView(root: View): SurfaceView? {
        if (root is SurfaceView) {
            return root
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findSurfaceView(root.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun findTextureView(root: View): TextureView? {
        if (root is TextureView) {
            return root
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findTextureView(root.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun logPreviewTreeOnce() {
        if (hasLoggedPreviewTree) {
            return
        }
        hasLoggedPreviewTree = true
        Timber.i("Insta360 preview tree:\n${describeViewTree(capturePlayerView)}")
    }

    private fun describeViewTree(view: View, depth: Int = 0): String {
        val indent = "  ".repeat(depth)
        val current = "$indent${view.javaClass.name} ${view.width}x${view.height} shown=${view.isShown}"
        if (view !is ViewGroup) {
            return current
        }
        val children = (0 until view.childCount).joinToString("\n") { index ->
            describeViewTree(view.getChildAt(index), depth + 1)
        }
        return if (children.isBlank()) current else "$current\n$children"
    }

    @Suppress("DEPRECATION")
    private fun enterLiveFullscreen() {
        supportActionBar?.hide()
        topStatusBar.visibility = View.GONE
        bottomControls.visibility = View.GONE
        helpText.visibility = View.GONE

        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE

        cameraPreview.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        )
        cameraPreview.requestLayout()
    }

    @Suppress("DEPRECATION")
    private fun exitLiveFullscreen() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        supportActionBar?.show()
        topStatusBar.visibility = View.VISIBLE
        bottomControls.visibility = View.VISIBLE
        helpText.visibility = View.VISIBLE
        cameraPreview.requestLayout()
    }

    /**
     * Start Live Detection Flow
     *
     * Step 1: Show WiFi network selection dialog
     * Step 2: Connect to selected camera WiFi
     * Step 3: Connect to Insta360 X4
     * Step 4: Start frame streaming
     * Step 5: Auto-send each frame to cloud
     * Step 6: Display detections in real-time
     */
    private fun startLiveDetection() {
        updateStatus("🔍 Starting live detection...")
        loadingSpinner.visibility = android.view.View.VISIBLE
        startButton.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Step 1: Show WiFi selection
                withContext(Dispatchers.Main) {
                    updateStatus("📡 Select camera WiFi network...")
                }

                val selectedSSID = wifiManager?.showNetworkSelectionDialog()
                    ?: throw Exception("No WiFi selected")

                Timber.d("Selected WiFi: $selectedSSID")

                val defaultX4Password = DEFAULT_INSTA360_X4_WIFI_PASSWORD.takeIf { it.isNotBlank() }
                val password = if (selectedSSID.startsWith("X4") && defaultX4Password != null) {
                    defaultX4Password
                } else {
                    withContext(Dispatchers.Main) {
                        showPasswordDialog("Enter WiFi Password for: $selectedSSID")
                    }
                }

                if (password == null) {
                    throw Exception("Password required to connect to WiFi")
                }

                withContext(Dispatchers.Main) {
                    updateStatus("📡 Connecting to: $selectedSSID")
                }

                // Step 2: Connect to WiFi
                val wifiConnected = wifiManager?.connectToNetwork(selectedSSID, password)
                    ?: false

                if (!wifiConnected) {
                    throw Exception("Failed to connect to WiFi: $selectedSSID")
                }

                withContext(Dispatchers.Main) {
                    wifiStatusText.text = "✓ WiFi: $selectedSSID"
                    updateStatus("📡 WiFi connected. Connecting to camera...")
                }

                // Step 3: Connect to camera
                val cameraConnected = cameraManager?.connect(
                    cameraNetwork = wifiManager?.cameraNetwork,
                    onFrameReceived = { frame -> processFrame(frame) },
                    onError = { error -> handleCameraError(error) }
                ) ?: false

                if (!cameraConnected) {
                    throw Exception("Failed to connect to Insta360 X4")
                }

                withContext(Dispatchers.Main) {
                    updateStatus("🎥 Camera connected. Starting live detection...")
                    startCameraPreviewPlayer()
                    isRunning = true
                    startButton.isEnabled = false
                    stopButton.isEnabled = true
                    recordScenarioButton.visibility = if (ENABLE_CLOUD_DETECTION) View.VISIBLE else View.GONE
                    recordScenarioButton.isEnabled = ENABLE_CLOUD_DETECTION
                    recordScenarioButton.text = "התחל הקלטת תרחיש"
                    recordingIndicator.visibility = View.GONE
                    loadingSpinner.visibility = android.view.View.GONE

                    Timber.i("Live preview started successfully")
                    updateStatus(if (ENABLE_CLOUD_DETECTION) "✓ LIVE - Detecting in real-time" else "✓ LIVE - Preview only")
                }

            } catch (e: Exception) {
                Timber.e(e, "Error starting detection")
                withContext(Dispatchers.Main) {
                    updateStatus("✗ Error: ${e.message}")
                    handleError(e.message ?: "Unknown error")
                    startButton.isEnabled = true
                    loadingSpinner.visibility = android.view.View.GONE
                }
            }
        }
    }

    /**
     * Process incoming frame from camera
     *
     * Flow:
     * 1. Display frame in preview
     * 2. Send to cloud for detection (async)
     * 3. Receive detections
     * 4. Draw on overlay (happens in displayDetections)
     */
    private fun processFrame(frame: Bitmap) {
        runOnUiThread {
            updateFPS()
        }

        if (!ENABLE_CLOUD_DETECTION) {
            return
        }

        lifecycleScope.launch(Dispatchers.Default) {
            try {
                frameProcessor?.processFrame(
                    frame = frame,
                    cameraId = "android_phone",
                    cameraName = "Team A - Rescue Detector"
                )
            } catch (e: Exception) {
                Timber.e(e, "Frame processing error")
            }
        }
    }

    /**
     * Display detections as overlay on camera frame
     *
     * Shows:
     * - Bounding boxes (colored by class)
     * - Class label + confidence
     * - Detection count
     */
    private fun displayDetections(detections: List<Detection>) {
        val receivedAt = SystemClock.elapsedRealtime()
        runOnUiThread {
            if (!isRunning || receivedAt + MAX_DETECTION_RESULT_AGE_MS < SystemClock.elapsedRealtime()) {
                detectionOverlay.setDetections(emptyList())
                detectionOverlay.invalidate()
            } else {
                lastOverlayUpdateAt = receivedAt
            detectionOverlay.alpha = 1f
            detectionOverlay.bringToFront()

            // Update overlay with detections
            detectionOverlay.setDetections(detections)
            detectionOverlay.invalidate()  // Redraw

            // Update status with detection count
            val message = if (detections.isEmpty()) {
                "No detections"
            } else {
                val summary = detections
                    .groupingBy { it.className }
                    .eachCount()
                    .map { "${it.key}(${it.value})" }
                    .joinToString(" | ")
                "🔍 Detected: $summary"
            }

            updateStatus("✓ LIVE - $message")
            Timber.d("Displayed ${detections.size} detections")
            }
        }
    }

    private fun startStaleDetectionCleaner() {
        lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                if (isRunning && lastOverlayUpdateAt > 0L) {
                    val ageMs = SystemClock.elapsedRealtime() - lastOverlayUpdateAt
                    if (ageMs > STALE_DETECTION_CLEAR_MS) {
                        lastOverlayUpdateAt = 0L
                        detectionOverlay.setDetections(emptyList())
                        detectionOverlay.invalidate()
                    }
                }
                delay(STALE_DETECTION_CHECK_INTERVAL_MS)
            }
        }
    }


    private fun toggleScenarioRecording() {
        if (!isRunning || !ENABLE_CLOUD_DETECTION) {
            Toast.makeText(this, "Start live detection first", Toast.LENGTH_SHORT).show()
            return
        }

        if (isRecordingScenario) {
            stopScenarioRecording()
        } else {
            startScenarioRecording()
        }
    }

    private fun startScenarioRecording() {
        recordScenarioButton.isEnabled = false
        recordScenarioButton.text = "מתחיל הקלטה..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = frameProcessor?.startScenarioRecording(
                    cameraId = "android_phone",
                    cameraName = "Team A - Rescue Detector",
                    saveFps = SCENARIO_SAVE_FPS
                ) ?: throw Exception("Frame processor is not available")

                withContext(Dispatchers.Main) {
                    isRecordingScenario = true
                    activeScenarioId = result.scenarioId
                    recordScenarioButton.text = "עצור הקלטה"
                    recordScenarioButton.isEnabled = true
                    recordingIndicator.text = "REC ${result.scenarioId}"
                    recordingIndicator.visibility = View.VISIBLE
                    updateStatus("● Recording scenario: ${result.scenarioId}")
                    Toast.makeText(
                        this@MainActivity,
                        "Scenario recording started: ${result.scenarioId}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed starting scenario recording")
                withContext(Dispatchers.Main) {
                    resetScenarioRecordingUi()
                    updateStatus("✗ Recording error: ${e.message}")
                    Toast.makeText(
                        this@MainActivity,
                        "Recording error: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun stopScenarioRecording() {
        recordScenarioButton.isEnabled = false
        recordScenarioButton.text = "עוצר הקלטה..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = frameProcessor?.stopScenarioRecording(
                    cameraId = "android_phone",
                    cameraName = "Team A - Rescue Detector"
                ) ?: throw Exception("Frame processor is not available")

                withContext(Dispatchers.Main) {
                    resetScenarioRecordingUi()
                    updateStatus("✓ Scenario saved: ${result.scenarioId}, frames=${result.savedFrames}")
                    Toast.makeText(
                        this@MainActivity,
                        "Scenario saved: ${result.scenarioId} (${result.savedFrames} frames)",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed stopping scenario recording")
                withContext(Dispatchers.Main) {
                    resetScenarioRecordingUi()
                    updateStatus("✗ Recording stopped with error: ${e.message}")
                    Toast.makeText(
                        this@MainActivity,
                        "Recording stopped with error: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private suspend fun stopScenarioRecordingIfNeeded(reason: String) {
        if (!isRecordingScenario) {
            return
        }

        runCatching {
            frameProcessor?.stopScenarioRecording(
                cameraId = "android_phone",
                cameraName = "Team A - Rescue Detector"
            )
        }.onFailure { error ->
            Timber.w(error, "Failed stopping scenario recording during $reason")
        }
    }

    private fun resetScenarioRecordingUi() {
        isRecordingScenario = false
        activeScenarioId = null
        recordScenarioButton.text = "התחל הקלטת תרחיש"
        recordScenarioButton.isEnabled = isRunning && ENABLE_CLOUD_DETECTION
        recordScenarioButton.visibility = if (isRunning && ENABLE_CLOUD_DETECTION) View.VISIBLE else View.GONE
        recordingIndicator.visibility = View.GONE
    }

    /**
     * Stop Live Detection
     *
     * Cleanup:
     * - Stop camera stream
     * - Disconnect from WiFi
     * - Clear UI
     */
    private fun stopLiveDetection() {
        updateStatus("Stopping...")
        loadingSpinner.visibility = android.view.View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                stopScenarioRecordingIfNeeded("live detection stop")
                isRunning = false
                isFrameExtractionActive = false
                extractedFrameInFlight.set(false)
                frameProcessor?.close()
                cameraManager?.disconnect()
                wifiManager?.disconnect()

                withContext(Dispatchers.Main) {
                    InstaCameraManager.getInstance().setPipeline(null)
                    capturePlayerView.destroy()
                    capturePlayerView.keepScreenOn = false
                    exitLiveFullscreen()
                    previewImage.setImageBitmap(null)
                    detectionOverlay.setDetections(emptyList())
                    detectionOverlay.invalidate()

                    wifiStatusText.text = "Disconnected"
                    fpsText.text = "FPS: 0"
                    updateStatus("Ready to connect")

                    startButton.isEnabled = true
                    stopButton.isEnabled = false
                    resetScenarioRecordingUi()
                    loadingSpinner.visibility = android.view.View.GONE

                    Toast.makeText(
                        this@MainActivity,
                        "Detection stopped",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                Timber.i("Live detection stopped")

            } catch (e: Exception) {
                Timber.e(e, "Error stopping detection")
                withContext(Dispatchers.Main) {
                    updateStatus("Error stopping: ${e.message}")
                    loadingSpinner.visibility = android.view.View.GONE
                }
            }
        }
    }

    /**
     * Update FPS counter
     */
    private fun updateFPS() {
        framesProcessed++
        val now = System.currentTimeMillis()
        val elapsed = (now - lastFrameTime) / 1000.0

        if (elapsed >= 1.0) {
            val fps = (framesProcessed / elapsed).toInt()
            fpsText.text = "FPS: $fps"
            framesProcessed = 0L
            lastFrameTime = now
        }
    }

    /**
     * Handle camera errors
     */
    private fun handleCameraError(error: String) {
        Timber.e("Camera error: $error")
        runOnUiThread {
            isRunning = false
            isFrameExtractionActive = false
            extractedFrameInFlight.set(false)
            exitLiveFullscreen()
                updateStatus("✗ Camera error: $error")
            startButton.isEnabled = true
            stopButton.isEnabled = false
            resetScenarioRecordingUi()
            loadingSpinner.visibility = android.view.View.GONE
            Toast.makeText(this, "Camera error: $error", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Handle general errors
     */
    private fun handleError(error: String) {
        Timber.e("Error: $error")
        runOnUiThread {
            updateStatus("✗ Cloud error: $error")
            if (isRecordingScenario) {
                resetScenarioRecordingUi()
            }
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Update status text
     */
    private fun updateStatus(message: String) {
        runOnUiThread {
            statusText.text = message
            Timber.i("Status: $message")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                stopScenarioRecordingIfNeeded("activity destroy")
                isRunning = false
                isFrameExtractionActive = false
                extractedFrameInFlight.set(false)
                frameProcessor?.close()
                cameraManager?.disconnect()
                withContext(Dispatchers.Main) {
                    InstaCameraManager.getInstance().setPipeline(null)
                    capturePlayerView.destroy()
                    capturePlayerView.keepScreenOn = false
                    exitLiveFullscreen()
                }
                wifiManager?.disconnect()
                Timber.i("Cleanup completed")
            } catch (e: Exception) {
                Timber.e(e, "Error during cleanup")
            }
        }
    }

    /**
     * Request WiFi permissions
     */
    private fun requestWiFiPermissions() {
        val requiredPermissions = mutableListOf(
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.CHANGE_NETWORK_STATE
        )

        // Add location permission for Android 6+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            Timber.d("Requesting permissions: $missingPermissions")
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                WIFI_PERMISSION_REQUEST_CODE
            )
        } else {
            Timber.d("All WiFi permissions already granted")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            WIFI_PERMISSION_REQUEST_CODE -> {
                val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                if (allGranted) {
                    Timber.i("All WiFi permissions granted!")
                    updateStatus("✓ Permissions granted. Ready to connect")
                } else {
                    Timber.w("Some WiFi permissions denied")
                    val deniedPermissions = permissions.filterIndexed { index, _ ->
                        grantResults[index] != PackageManager.PERMISSION_GRANTED
                    }
                    updateStatus("✗ Missing permissions: ${deniedPermissions.joinToString()}")
                    Toast.makeText(
                        this,
                        "WiFi permissions required. Grant them in Settings → Apps → Rescue360 → Permissions",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * Show password input dialog
     */
    private suspend fun showPasswordDialog(title: String): String? =
        suspendCancellableCoroutine { continuation ->
            val input = android.widget.EditText(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                hint = "WiFi Password"
            }

            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setView(input)
                .setPositiveButton("Connect") { _, _ ->
                    val password = input.text.toString()
                    Timber.d("Password entered")
                    continuation.resume(password)
                }
                .setNegativeButton("Cancel") { _, _ ->
                    Timber.d("Password dialog cancelled")
                    continuation.resume(null)
                }
                .setOnCancelListener {
                    continuation.resume(null)
                }
                .show()
        }

    companion object {
        private const val WIFI_PERMISSION_REQUEST_CODE = 100
        private val DEFAULT_INSTA360_X4_WIFI_PASSWORD = BuildConfig.DEFAULT_INSTA360_X4_WIFI_PASSWORD
        private const val DETECTION_FRAME_WIDTH = 960
        private const val DETECTION_FRAME_HEIGHT = 540
        private const val DETECTION_FPS = 10
        private const val MIN_DETECTION_FRAME_INTERVAL_MS = 100L
        private const val MAX_DETECTION_RESULT_AGE_MS = 300L
        private const val STALE_DETECTION_CLEAR_MS = 250L
        private const val STALE_DETECTION_CHECK_INTERVAL_MS = 50L
        private const val ENABLE_CLOUD_DETECTION = true
        private const val SCENARIO_SAVE_FPS = 5
    }
}
