package com.rescue360.detector

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * Server response model for websocket_v1 /ws/{client_id}.
 */
data class DetectionResponse(
    val type: String? = null,
    val alert: Boolean = false,
    val detections: List<DetectionResult> = emptyList(),
    val latency_ms: Float = 0f,
    val frame_count: Long = 0L,
    val error: String? = null
)

/**
 * Detection result from server.
 */
data class DetectionResult(
    val class_id: Int,
    val class_name: String,
    val confidence: Float,
    val bbox: List<Float>
)

data class RecordingControlResponse(
    val type: String? = null,
    val status: String = "error",
    val scenario_id: String? = null,
    val saved_frames: Int = 0,
    val save_fps: Int = 0,
    val error: String? = null
)

data class RecordingControlResult(
    val scenarioId: String,
    val savedFrames: Int,
    val saveFps: Int = 0
)

class CloudNetworkUnavailableException(message: String) : IOException(message)

/**
 * Low-latency WebSocket client for Rescue360 detection.
 *
 * Protocol:
 * - Android sends one JPEG frame as binary data.
 * - Android sends recording controls as JSON text messages.
 * - Server replies with JSON detection or recording control responses.
 */
class DetectionClient(
    context: Context,
    private val serverUrl: String = BuildConfig.DEFAULT_SERVER_WS_URL
) {
    private val appContext = context.applicationContext
    private val gson = Gson()
    private val connectLock = Any()
    private val pendingDetection = AtomicReference<CompletableDeferred<List<Detection>>?>()
    private val pendingRecordingCommand = AtomicReference<CompletableDeferred<RecordingControlResult>?>()

    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var webSocketClient: OkHttpClient? = null

    @Volatile
    private var cellularNetwork: Network? = null

    @Volatile
    private var cellularNetworkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var isConnected = false

    @Volatile
    private var openingConnection: CompletableDeferred<Unit>? = null

    @Volatile
    private var activeScenarioId: String? = null

    val isRecording: Boolean
        get() = activeScenarioId != null

    /**
     * Send a frame to the server and return detections for that frame.
     */
    suspend fun detectFrame(
        frame: Bitmap,
        cameraId: String = "android_phone",
        cameraName: String = "Android Team"
    ): List<Detection> = withContext(Dispatchers.IO) {
        try {
            ensureConnected(cameraId, cameraName)

            val deferred = CompletableDeferred<List<Detection>>()
            if (!pendingDetection.compareAndSet(null, deferred)) {
                Timber.v("Skipping frame: WebSocket detection already in flight")
                return@withContext emptyList()
            }

            val jpegBytes = bitmapToJpeg(frame)
            val sent = webSocket?.send(jpegBytes.toByteString()) == true
            if (!sent) {
                pendingDetection.compareAndSet(deferred, null)
                isConnected = false
                Timber.w("WebSocket frame send failed")
                return@withContext emptyList()
            }

            try {
                withTimeout(DETECTION_TIMEOUT_MS) {
                    deferred.await()
                }
            } catch (e: TimeoutCancellationException) {
                pendingDetection.compareAndSet(deferred, null)
                Timber.w("WebSocket detection timed out")
                emptyList()
            }
        } catch (e: CloudNetworkUnavailableException) {
            Timber.e(e, "Cloud network is unavailable")
            throw e
        } catch (e: Exception) {
            Timber.e(e, "WebSocket detection failed")
            emptyList()
        }
    }

    suspend fun startRecording(
        cameraId: String = "android_phone",
        cameraName: String = "Android Team",
        saveFps: Int = 5
    ): RecordingControlResult = withContext(Dispatchers.IO) {
        if (activeScenarioId != null) {
            throw IOException("Recording already active: $activeScenarioId")
        }

        ensureConnected(cameraId, cameraName)
        sendRecordingCommand(
            mapOf(
                "type" to "start_recording",
                "save_fps" to saveFps
            )
        ).also { result ->
            activeScenarioId = result.scenarioId
        }
    }

    suspend fun stopRecording(
        cameraId: String = "android_phone",
        cameraName: String = "Android Team"
    ): RecordingControlResult = withContext(Dispatchers.IO) {
        if (activeScenarioId == null) {
            throw IOException("No active recording")
        }

        ensureConnected(cameraId, cameraName)
        sendRecordingCommand(
            mapOf(
                "type" to "stop_recording"
            )
        ).also {
            activeScenarioId = null
        }
    }

    fun close() {
        pendingDetection.getAndSet(null)?.complete(emptyList())
        pendingRecordingCommand.getAndSet(null)?.completeExceptionally(IOException("WebSocket closed"))
        activeScenarioId = null
        isConnected = false
        openingConnection?.cancel()
        openingConnection = null
        webSocket?.close(1000, "Detection stopped")
        webSocket = null
        webSocketClient = null
        cellularNetworkCallback?.let { callback ->
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
        cellularNetworkCallback = null
        cellularNetwork = null
    }

    private suspend fun sendRecordingCommand(payload: Map<String, Any>): RecordingControlResult {
        val deferred = CompletableDeferred<RecordingControlResult>()
        if (!pendingRecordingCommand.compareAndSet(null, deferred)) {
            throw IOException("Recording command already in flight")
        }

        val sent = webSocket?.send(gson.toJson(payload)) == true
        if (!sent) {
            pendingRecordingCommand.compareAndSet(deferred, null)
            isConnected = false
            throw IOException("Failed sending recording command")
        }

        return try {
            withTimeout(RECORDING_COMMAND_TIMEOUT_MS) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            pendingRecordingCommand.compareAndSet(deferred, null)
            throw IOException("Recording command timed out", e)
        }
    }

    private suspend fun ensureConnected(cameraId: String, cameraName: String) {
        val current = webSocket
        if (isConnected && current != null) {
            return
        }

        val cloudClient = buildOkHttpClientForCloud()

        val waiter = synchronized(connectLock) {
            if (isConnected && webSocket != null) {
                return
            }

            openingConnection ?: CompletableDeferred<Unit>().also { deferred ->
                openingConnection = deferred
                val url = buildWebSocketUrl(cameraId, cameraName)
                val request = Request.Builder().url(url).build()
                Timber.i("Opening detection WebSocket: $url")
                webSocketClient = cloudClient
                webSocket = cloudClient.newWebSocket(request, listener)
            }
        }

        withTimeout(CONNECT_TIMEOUT_MS) {
            waiter.await()
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (this@DetectionClient.webSocket != webSocket) {
                webSocket.cancel()
                return
            }

            Timber.i("Detection WebSocket connected")
            isConnected = true
            openingConnection?.complete(Unit)
            openingConnection = null
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val json = gson.fromJson(text, JsonObject::class.java)
                val type = json.get("type")?.asString

                when (type) {
                    "recording_started", "recording_stopped" -> handleRecordingResponse(text)
                    "recording_error", "control_error" -> handleRecordingError(text)
                    else -> handleDetectionResponse(text)
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed parsing WebSocket message")
                pendingDetection.getAndSet(null)?.complete(emptyList())
                pendingRecordingCommand.getAndSet(null)?.completeExceptionally(e)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Timber.i("Detection WebSocket closing: $code $reason")
            if (this@DetectionClient.webSocket == webSocket) {
                isConnected = false
                activeScenarioId = null
            }
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Timber.i("Detection WebSocket closed: $code $reason")
            if (this@DetectionClient.webSocket == webSocket) {
                isConnected = false
                activeScenarioId = null
                this@DetectionClient.webSocket = null
                pendingDetection.getAndSet(null)?.complete(emptyList())
                pendingRecordingCommand.getAndSet(null)?.completeExceptionally(IOException("WebSocket closed: $reason"))
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Timber.e(t, "Detection WebSocket failure")
            webSocket.cancel()

            if (this@DetectionClient.webSocket == webSocket) {
                isConnected = false
                activeScenarioId = null
                this@DetectionClient.webSocket = null
                openingConnection?.completeExceptionally(t)
                openingConnection = null
                pendingDetection.getAndSet(null)?.complete(emptyList())
                pendingRecordingCommand.getAndSet(null)?.completeExceptionally(t)
            }
        }
    }

    private fun handleDetectionResponse(text: String) {
        val response = gson.fromJson(text, DetectionResponse::class.java)
        if (response.error != null) {
            Timber.w("Detection server returned error: ${response.error}")
        }

        val detections = response.detections.map { result ->
            Detection(
                classId = result.class_id,
                className = result.class_name,
                confidence = result.confidence,
                bbox = result.bbox.toFloatArray()
            )
        }

        Timber.d("WebSocket detections=${detections.size}, latency=${response.latency_ms}ms")
        pendingDetection.getAndSet(null)?.complete(detections)
    }

    private fun handleRecordingResponse(text: String) {
        val response = gson.fromJson(text, RecordingControlResponse::class.java)
        if (response.status != "success" || response.scenario_id == null) {
            val error = response.error ?: "Recording command failed"
            pendingRecordingCommand.getAndSet(null)?.completeExceptionally(IOException(error))
            return
        }

        val result = RecordingControlResult(
            scenarioId = response.scenario_id,
            savedFrames = response.saved_frames,
            saveFps = response.save_fps
        )
        Timber.i("Recording response ${response.type}: ${result.scenarioId}, frames=${result.savedFrames}")
        pendingRecordingCommand.getAndSet(null)?.complete(result)
    }

    private fun handleRecordingError(text: String) {
        val response = gson.fromJson(text, RecordingControlResponse::class.java)
        val error = response.error ?: "Recording server error"
        activeScenarioId = null
        Timber.w("Recording server returned error: $error")
        pendingRecordingCommand.getAndSet(null)?.completeExceptionally(IOException(error))
    }

    private fun buildWebSocketUrl(cameraId: String, cameraName: String): String {
        val encodedId = urlEncode(cameraId)
        val encodedName = urlEncode(cameraName)
        return "${serverUrl.trimEnd('/')}/$encodedId?camera_name=$encodedName"
    }

    private suspend fun buildOkHttpClientForCloud(): OkHttpClient {
        val network = getCellularInternetNetwork()
        val builder = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .pingInterval(10, TimeUnit.SECONDS)

        if (network == null) {
            throw CloudNetworkUnavailableException(
                "No cellular internet available. Insert/enable SIM or eSIM while connected to camera WiFi."
            )
        }

        Timber.i("Using cellular network for cloud WebSocket: ${network.networkHandle}")
        builder.socketFactory(network.socketFactory)
        builder.dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                return network.getAllByName(hostname).toList()
            }
        })

        return builder.build()
    }

    private suspend fun getCellularInternetNetwork(): Network? {
        cellularNetwork?.takeIf { network -> network.hasCellularInternet() }?.let {
            return it
        }

        connectivityManager.allNetworks.firstOrNull { network ->
            network.hasCellularInternet()
        }?.let { network ->
            cellularNetwork = network
            return network
        }

        return try {
            withTimeout(CELLULAR_NETWORK_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val request = NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build()

                    val callback = object : ConnectivityManager.NetworkCallback() {
                        private var resumed = false

                        override fun onAvailable(network: Network) {
                            if (resumed) return
                            resumed = true
                            cellularNetwork = network
                            cellularNetworkCallback = this
                            Timber.i("Cellular internet network available: ${network.networkHandle}")
                            continuation.resume(network)
                        }

                        override fun onLost(network: Network) {
                            if (cellularNetwork == network) {
                                Timber.w("Cellular internet network lost: ${network.networkHandle}")
                                cellularNetwork = null
                            }
                        }

                        override fun onUnavailable() {
                            if (resumed) return
                            resumed = true
                            Timber.w("Cellular internet network unavailable")
                            continuation.resume(null)
                        }
                    }

                    cellularNetworkCallback = callback
                    connectivityManager.requestNetwork(request, callback)

                    continuation.invokeOnCancellation {
                        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
                        if (cellularNetworkCallback == callback) {
                            cellularNetworkCallback = null
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Timed out waiting for cellular internet network")
            null
        }
    }

    private fun Network.hasCellularInternet(): Boolean {
        val capabilities = connectivityManager.getNetworkCapabilities(this) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun urlEncode(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name())
    }

    private fun bitmapToJpeg(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        return stream.toByteArray()
    }

    private companion object {
        private const val JPEG_QUALITY = 90
        private const val CONNECT_TIMEOUT_MS = 3_000L
        private const val DETECTION_TIMEOUT_MS = 1_800L
        private const val RECORDING_COMMAND_TIMEOUT_MS = 3_000L
        private const val CELLULAR_NETWORK_TIMEOUT_MS = 5_000L
    }
}
