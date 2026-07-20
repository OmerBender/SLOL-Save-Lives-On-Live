package com.rescue360.detector

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Frame Processor
 *
 * Handles:
 * 1. Receive frame from camera
 * 2. Send to server for detection over WebSocket
 * 3. Parse results
 * 4. Callback with detections
 */
class FrameProcessor(
    context: Context,
    private val onDetection: (List<Detection>) -> Unit = {},
    private val onError: (String) -> Unit = {}
) {
    private val client = DetectionClient(context)
    private var lastDetectionTime = System.currentTimeMillis()
    private var lastErrorTime = 0L
    private var lastErrorMessage: String? = null
    private var detectionInterval = 100L  // Process up to ~10 FPS in low-latency mode
    private val requestInFlight = AtomicBoolean(false)

    /**
     * Process incoming frame
     *
     * Throttles requests to avoid overload:
     * - Only sends every 100ms
     * - Async processing (doesn't block)
     */
    suspend fun processFrame(
        frame: Bitmap,
        cameraId: String = "android_phone",
        cameraName: String = "Android Team"
    ) {
        val now = System.currentTimeMillis()

        // Throttle: only send every 100ms
        if (now - lastDetectionTime < detectionInterval) {
            return
        }

        if (!requestInFlight.compareAndSet(false, true)) {
            return
        }

        lastDetectionTime = now

        try {
            Timber.v("Processing frame...")

            // Send to server (blocking, but on IO thread)
            val detections = client.detectFrame(
                frame = frame,
                cameraId = cameraId,
                cameraName = cameraName
            )

            Timber.d("Got ${detections.size} detections")

            // Callback with results
            onDetection(detections)

        } catch (e: Exception) {
            Timber.e(e, "Frame processing error")
            reportError(e.message ?: "Unknown error")
        } finally {
            requestInFlight.set(false)
        }
    }

    private fun reportError(message: String) {
        val now = System.currentTimeMillis()
        if (message == lastErrorMessage && now - lastErrorTime < ERROR_REPEAT_INTERVAL_MS) {
            return
        }

        lastErrorMessage = message
        lastErrorTime = now
        onError(message)
    }


    suspend fun startScenarioRecording(
        cameraId: String = "android_phone",
        cameraName: String = "Android Team",
        saveFps: Int = 5
    ): RecordingControlResult {
        return client.startRecording(
            cameraId = cameraId,
            cameraName = cameraName,
            saveFps = saveFps
        )
    }

    suspend fun stopScenarioRecording(
        cameraId: String = "android_phone",
        cameraName: String = "Android Team"
    ): RecordingControlResult {
        return client.stopRecording(
            cameraId = cameraId,
            cameraName = cameraName
        )
    }

    fun close() {
        requestInFlight.set(false)
        client.close()
    }

    private companion object {
        private const val ERROR_REPEAT_INTERVAL_MS = 5_000L
    }
}
