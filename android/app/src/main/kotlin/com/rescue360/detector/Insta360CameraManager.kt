package com.rescue360.detector

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import com.arashivision.sdkcamera.camera.InstaCameraManager
import com.arashivision.sdkcamera.camera.callback.ICameraChangedCallback
import com.arashivision.sdkcamera.camera.callback.ICameraOperateCallback
import com.arashivision.sdkcamera.camera.callback.ICaptureSupportConfigCallback
import com.arashivision.sdkcamera.camera.callback.IPreviewStatusListener
import com.arashivision.sdkcamera.camera.model.CaptureMode
import com.arashivision.sdkcamera.camera.model.Latency
import com.arashivision.sdkcamera.camera.model.TemperatureLevel
import com.arashivision.sdkcamera.camera.preview.PreviewParamsBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

/**
 * Insta360 X4 Camera Manager
 *
 * Uses the official Insta360 Camera SDK over Wi-Fi.
 * The old HTTP/MJPEG endpoints such as 192.168.42.1:8080 do not exist on X4.
 */
class Insta360CameraManager(private val context: Context) : ICameraChangedCallback, IPreviewStatusListener {

    private val camera = InstaCameraManager.getInstance()
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager =
        context.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var isConnected = false
    private var isPreviewStreamOpen = false
    private var isDisconnecting = false
    private var frameCallback: ((Bitmap) -> Unit)? = null
    private var errorCallback: ((String) -> Unit)? = null
    private var connectContinuation: Continuation<Boolean>? = null
    private var previewContinuation: Continuation<Boolean>? = null

    suspend fun connect(
        cameraNetwork: Network? = null,
        onFrameReceived: (Bitmap) -> Unit = {},
        onError: (String) -> Unit = {}
    ): Boolean = withContext(Dispatchers.Main.immediate) {
        isDisconnecting = false
        frameCallback = onFrameReceived
        errorCallback = onError

        camera.registerCameraChangedCallback(this@Insta360CameraManager)
        camera.setPreviewStatusChangedListener(this@Insta360CameraManager)
        if (!bindSdkToCurrentWifiNetwork(cameraNetwork)) {
            val message = "Could not bind app process to camera WiFi network"
            Timber.e(message)
            errorCallback?.invoke(message)
            cleanupCallbacks()
            return@withContext false
        }

        val connected = if (
            isConnected &&
            camera.cameraConnectedType == InstaCameraManager.CONNECT_TYPE_WIFI
        ) {
            Timber.d("Insta360 camera already connected over WiFi")
            true
        } else {
            withTimeoutOrNull(CONNECTION_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    Timber.d("Opening Insta360 camera over WiFi via SDK...")
                    connectContinuation = continuation
                    camera.openCamera(InstaCameraManager.CONNECT_TYPE_WIFI)
                }
            } ?: false
        }

        if (!connected) {
            val message = "SDK failed to connect to Insta360 X4 over WiFi"
            Timber.e(message)
            errorCallback?.invoke(message)
            cleanupCallbacks()
            return@withContext false
        }

        if (!prepareCameraForPreview(cameraNetwork)) {
            val message = "Camera connected, but SDK preview preparation failed"
            Timber.e(message)
            errorCallback?.invoke(message)
            return@withContext false
        }

        configureLowLatencyPreview()

        val previewOpened = withTimeoutOrNull(PREVIEW_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                Timber.d("Starting Insta360 RECORD preview stream via SDK with audio disabled...")
                previewContinuation = continuation
                val previewParams = PreviewParamsBuilder()
                    .setPreviewType(InstaCameraManager.PREVIEW_TYPE_RECORD)
                    .setAudioEnabled(false)
                camera.startPreviewStream(previewParams)
            }
        }

        if (previewOpened == false) {
            val message = "Camera connected, but preview stream returned an error"
            Timber.e(message)
            errorCallback?.invoke(message)
            isPreviewStreamOpen = false
            return@withContext false
        }

        if (previewOpened == null) {
            Timber.w("Preview stream did not report opened before timeout; continuing to player setup")
        }

        camera.setStreamEncode()
        isConnected = true
        Timber.i("Insta360 X4 connected over WiFi and preview stream opened")
        true
    }

    suspend fun getCurrentFrame(): Bitmap? = withContext(Dispatchers.IO) {
        null
    }

    suspend fun disconnect() = withContext(Dispatchers.Main.immediate) {
        try {
            Timber.d("Disconnecting Insta360 camera...")
            isDisconnecting = true
            isConnected = false
            isPreviewStreamOpen = false
            connectContinuation = null
            previewContinuation = null
            camera.closePreviewStream()
            camera.closeCamera()
            cleanupCallbacks()
        } catch (e: Exception) {
            Timber.e(e, "Error during camera disconnect")
        } finally {
            isDisconnecting = false
        }
    }

    fun isConnectedToCamera(): Boolean {
        return isConnected && camera.cameraConnectedType == InstaCameraManager.CONNECT_TYPE_WIFI
    }

    fun setFrameCallback(callback: (Bitmap) -> Unit) {
        frameCallback = callback
    }

    fun injectTestFrame(bitmap: Bitmap) {
        frameCallback?.invoke(bitmap)
    }

    override fun onCameraStatusChanged(enabled: Boolean, connectType: Int) {
        Timber.d("Camera status changed: enabled=$enabled connectType=$connectType")
        if (connectType != InstaCameraManager.CONNECT_TYPE_WIFI) return

        if (enabled) {
            isConnected = true
            connectContinuation?.resumeOnce(true)
            connectContinuation = null
        } else {
            isConnected = false
            if (!isDisconnecting) {
                errorCallback?.invoke("Camera WiFi disconnected")
            }
        }
    }

    override fun onCameraConnectError(errorCode: Int) {
        Timber.e("Insta360 SDK camera connect error: $errorCode")
        connectContinuation?.resumeOnce(false)
        connectContinuation = null
        errorCallback?.invoke("Insta360 SDK connect error: $errorCode")
    }

    override fun onOpening() {
        Timber.d("Preview stream opening")
    }

    override fun onOpened() {
        Timber.d("Preview stream opened")
        isPreviewStreamOpen = true
        previewContinuation?.resumeOnce(true)
        previewContinuation = null
    }

    override fun onIdle() {
        Timber.d("Preview stream idle/closed")
        isPreviewStreamOpen = false
    }

    override fun onError() {
        Timber.e("Preview stream error")
        isPreviewStreamOpen = false
        previewContinuation?.resumeOnce(false)
        previewContinuation = null
        errorCallback?.invoke("Preview stream error")
    }

    override fun onCameraPreviewStreamParamsChanged(isPreviewStreamParamsChanged: Boolean) {
        Timber.d("Preview stream params changed: $isPreviewStreamParamsChanged")
    }

    override fun onCameraSDCardStateChanged(enabled: Boolean) = Unit

    override fun onCameraBatteryLow() = Unit

    override fun onCameraBatteryUpdate(batteryLevel: Int, isCharging: Boolean) = Unit

    override fun onCameraStorageChanged(freeSpace: Long, totalSpace: Long) = Unit

    override fun onCameraTemperatureChanged(tempLevel: TemperatureLevel?) = Unit

    private fun bindSdkToCurrentWifiNetwork(cameraNetwork: Network?): Boolean {
        return try {
            val network = cameraNetwork
                ?: connectivityManager.boundNetworkForProcess
                ?: findCurrentWifiNetwork()
                ?: return false

            val bindResult = connectivityManager.bindProcessToNetwork(network)
            camera.setNetIdToCamera(network.networkHandle)
            Timber.d(
                "Binding Insta360 SDK to network=${network.networkHandle}, bindResult=$bindResult"
            )
            bindResult
        } catch (e: Exception) {
            Timber.w(e, "Could not bind SDK to current WiFi network")
            false
        }
    }

    private suspend fun prepareCameraForPreview(cameraNetwork: Network?): Boolean {
        if (!fetchCameraOptions()) {
            Timber.e("fetchCameraOptions failed")
            return false
        }

        if (!ensurePanoramaSensorMode()) {
            Timber.e("ensurePanoramaSensorMode failed")
            return false
        }

        bindSdkToCurrentWifiNetwork(cameraNetwork)
        if (!initCameraSupportConfig()) {
            Timber.e("initCameraSupportConfig failed")
            return false
        }

        return true
    }

    private fun configureLowLatencyPreview() {
        runCatching {
            camera.setFunctionModeToCamera(InstaCameraManager.FUNCTION_MODE_PREVIEW_STREAM)
            Timber.i("Camera function mode set to PREVIEW_STREAM")
        }.onFailure { error ->
            Timber.w(error, "Could not set camera function mode to PREVIEW_STREAM")
        }

        runCatching {
            val supportedLatencies = camera.getSupportLatencyList(CaptureMode.LIVE)
            Timber.i(
                "Supported LIVE latencies: " + supportedLatencies.joinToString { it.nativeValue.toString() }
            )
            if (supportedLatencies.any { it == Latency.LATENCY_NONE }) {
                camera.setLatency(CaptureMode.LIVE, Latency.LATENCY_NONE)
                Timber.i("Camera LIVE latency set to LATENCY_NONE")
            } else {
                Timber.w("Camera does not report LATENCY_NONE support for LIVE mode")
            }
        }.onFailure { error ->
            Timber.w(error, "Could not configure LIVE latency")
        }
    }


    private suspend fun fetchCameraOptions(): Boolean =
        suspendCancellableCoroutine { continuation ->
            Timber.d("Fetching Insta360 camera options")
            camera.fetchCameraOptions(object : ICameraOperateCallback {
                override fun onSuccessful() {
                    continuation.resumeOnce(true)
                }

                override fun onFailed() {
                    continuation.resumeOnce(false)
                }

                override fun onCameraConnectError() {
                    continuation.resumeOnce(false)
                }
            })
        }

    private suspend fun ensurePanoramaSensorMode(): Boolean {
        if (camera.isCameraDualSensorMode) {
            Timber.d("Camera already in panorama/dual-sensor mode")
            return true
        }

        return suspendCancellableCoroutine { continuation ->
            Timber.d("Switching camera to panorama/dual-sensor mode")
            camera.switchPanoramaSensorMode(object : ICameraOperateCallback {
                override fun onSuccessful() {
                    continuation.resumeOnce(true)
                }

                override fun onFailed() {
                    continuation.resumeOnce(false)
                }

                override fun onCameraConnectError() {
                    continuation.resumeOnce(false)
                }
            })
        }
    }

    private suspend fun initCameraSupportConfig(): Boolean =
        suspendCancellableCoroutine { continuation ->
            Timber.d("Initializing Insta360 camera support config")
            camera.initCameraSupportConfig(object : ICaptureSupportConfigCallback {
                override fun onComplete() {
                    continuation.resumeOnce(true)
                }

                override fun onFailed(message: String?) {
                    Timber.e("initCameraSupportConfig failed: $message")
                    continuation.resumeOnce(false)
                }
            })
        }

    private fun findCurrentWifiNetwork(): Network? {
        val currentIp = parseIpAddress(wifiManager.connectionInfo.ipAddress)

        return connectivityManager.allNetworks.firstOrNull { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network)
                ?: return@firstOrNull false
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return@firstOrNull false
            }

            val wifiInfo = capabilities.transportInfo as? WifiInfo
                ?: return@firstOrNull false
            parseIpAddress(wifiInfo.ipAddress) == currentIp
        }
    }

    private fun parseIpAddress(ip: Int): String {
        return "${ip and 0xFF}.${(ip ushr 8) and 0xFF}.${(ip ushr 16) and 0xFF}.${(ip ushr 24) and 0xFF}"
    }

    private fun cleanupCallbacks() {
        camera.unregisterCameraChangedCallback(this)
        camera.setPreviewStatusChangedListener(null)
    }

    private fun Continuation<Boolean>.resumeOnce(value: Boolean) {
        try {
            resume(value)
        } catch (_: IllegalStateException) {
            // Callback arrived after timeout or cancellation.
        }
    }

    companion object {
        private const val CONNECTION_TIMEOUT_MS = 30_000L
        private const val PREVIEW_TIMEOUT_MS = 20_000L
    }
}
