package com.rescue360.detector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import kotlin.coroutines.resume

/**
 * WiFi Connection Manager
 *
 * Handles:
 * 1. Scan available WiFi networks (using BroadcastReceiver)
 * 2. Show selection dialog
 * 3. Connect to selected network
 * 4. Disconnect from network
 */
class WiFiConnectionManager(private val context: Context) {

    private val wifiManager =
        context.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    var cameraNetwork: Network? = null
        private set

    private var cameraNetworkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Show WiFi network selection dialog
     *
     * Returns: Selected SSID or null if cancelled
     */
    suspend fun showNetworkSelectionDialog(): String? =
        suspendCancellableCoroutine { continuation ->
            try {
                Timber.d("Scanning WiFi networks with BroadcastReceiver...")

                // Use BroadcastReceiver to wait for scan results
                val scanReceiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                            Timber.d("Scan results available!")

                            try {
                                // Unregister receiver immediately
                                context?.unregisterReceiver(this)
                            } catch (e: Exception) {
                                Timber.e(e, "Error unregistering receiver")
                            }

                            val networks = getAvailableNetworks()

                            if (networks.isEmpty()) {
                                Timber.w("No WiFi networks found after scan")
                                continuation.resume(null)
                                return
                            }

                            Timber.d("Found ${networks.size} networks: $networks")

                            // Show dialog with networks
                            AlertDialog.Builder(context!!)
                                .setTitle("Select Camera WiFi")
                                .setItems(networks.toTypedArray()) { _, which ->
                                    val selected = networks[which]
                                    Timber.d("Selected: $selected")
                                    continuation.resume(selected)
                                }
                                .setOnCancelListener {
                                    Timber.d("WiFi selection cancelled")
                                    continuation.resume(null)
                                }
                                .show()
                        }
                    }
                }

                // Register receiver
                val intentFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(scanReceiver, intentFilter, Context.RECEIVER_EXPORTED)
                } else {
                    @Suppress("DEPRECATION")
                    context.registerReceiver(scanReceiver, intentFilter)
                }

                // Start scan
                val scanStarted = wifiManager.startScan()
                Timber.d("WiFi scan started: $scanStarted")

                if (!scanStarted) {
                    Timber.w("Failed to start WiFi scan")
                    try {
                        context.unregisterReceiver(scanReceiver)
                    } catch (e: Exception) {
                        Timber.e(e, "Error unregistering receiver")
                    }
                    continuation.resume(null)
                }

            } catch (e: Exception) {
                Timber.e(e, "Error in showNetworkSelectionDialog")
                continuation.resume(null)
            }
        }

    /**
     * Get available WiFi networks from scan results
     *
     * Returns: List of SSID strings
     */
    private fun getAvailableNetworks(): List<String> {
        return try {
            val scanResults = wifiManager.scanResults

            if (scanResults == null || scanResults.isEmpty()) {
                Timber.w("No scan results available")
                return emptyList()
            }

            Timber.d("Raw scan results count: ${scanResults.size}")

            val networks = scanResults
                .map { it.SSID }
                .filter { it.isNotEmpty() && it != "<unknown ssid>" }
                .distinct()
                .sorted()

            Timber.d("Processed networks: $networks")
            networks
        } catch (e: Exception) {
            Timber.e(e, "Error getting available networks")
            emptyList()
        }
    }

    /**
     * Connect to WiFi network
     *
     * @param ssid Network SSID
     * @param password Network password (if required)
     * @return true if connected successfully
     */
    suspend fun connectToNetwork(ssid: String, password: String = ""): Boolean =
        suspendCancellableCoroutine { continuation ->
            try {
                Timber.d("Connecting to: $ssid")

                val cleanSSID = ssid.trim('"')

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (systemIsConnected(cleanSSID)) {
                        cameraNetwork = findCurrentWifiNetwork()
                        val bindResult = cameraNetwork?.let { network ->
                            connectivityManager.bindProcessToNetwork(network)
                        } ?: false

                        Timber.d(
                            "Already connected to $cleanSSID, cameraNetwork=${cameraNetwork?.networkHandle}, bindResult=$bindResult"
                        )
                        continuation.resume(bindResult)
                        return@suspendCancellableCoroutine
                    }

                    val specifierBuilder = WifiNetworkSpecifier.Builder()
                        .setSsid(cleanSSID)

                    // Add password if provided
                    if (password.isNotEmpty()) {
                        specifierBuilder.setWpa2Passphrase(password)
                        Timber.d("Using WPA2 password for connection")
                    } else {
                        Timber.d("Attempting open network connection")
                    }

                    val specifier = specifierBuilder.build()

                    val request = NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .setNetworkSpecifier(specifier)
                        .build()

                    cameraNetworkCallback?.let { callback ->
                        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
                    }

                    val callback = object : ConnectivityManager.NetworkCallback() {
                        private var isResumed = false

                        override fun onAvailable(network: Network) {
                            super.onAvailable(network)
                            cameraNetwork = network
                            val bindResult = connectivityManager.bindProcessToNetwork(network)
                            Timber.d(
                                "Connected to $cleanSSID, network=${network.networkHandle}, bindResult=$bindResult"
                            )

                            if (!isResumed) {
                                isResumed = true
                                continuation.resume(bindResult)
                            }
                        }

                        override fun onLost(network: Network) {
                            super.onLost(network)
                            Timber.w("Camera WiFi network lost: ${network.networkHandle}")
                            if (cameraNetwork == network) {
                                cameraNetwork = null
                            }
                        }

                        override fun onUnavailable() {
                            super.onUnavailable()
                            Timber.e("Failed to connect to $cleanSSID")
                            if (!isResumed) {
                                isResumed = true
                                continuation.resume(false)
                            }
                        }
                    }

                    cameraNetworkCallback = callback
                    connectivityManager.requestNetwork(
                        request,
                        callback
                    )
                } else {
                    @Suppress("DEPRECATION")
                    val wifiConfig = android.net.wifi.WifiConfiguration().apply {
                        SSID = "\"$cleanSSID\""
                        if (password.isNotEmpty()) {
                            // WPA2
                            preSharedKey = "\"$password\""
                            allowedKeyManagement.set(
                                android.net.wifi.WifiConfiguration.KeyMgmt.WPA_PSK
                            )
                            Timber.d("Using WPA2 password (legacy)")
                        } else {
                            // Open network
                            allowedKeyManagement.set(
                                android.net.wifi.WifiConfiguration.KeyMgmt.NONE
                            )
                            Timber.d("Using open network (legacy)")
                        }
                    }

                    @Suppress("DEPRECATION")
                    val netId = wifiManager.addNetwork(wifiConfig)

                    if (netId >= 0) {
                        @Suppress("DEPRECATION")
                        wifiManager.disconnect()

                        @Suppress("DEPRECATION")
                        wifiManager.enableNetwork(netId, true)

                        @Suppress("DEPRECATION")
                        wifiManager.reconnect()

                        Timber.d("Connected to $cleanSSID")
                        continuation.resume(true)
                    } else {
                        Timber.e("Failed to add network")
                        continuation.resume(false)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Connection error")
                continuation.resume(false)
            }
        }

    /**
     * Disconnect from WiFi
     */
    suspend fun disconnect() {
        try {
            Timber.d("Disconnecting WiFi")

            connectivityManager.bindProcessToNetwork(null)
            cameraNetworkCallback?.let { callback ->
                runCatching { connectivityManager.unregisterNetworkCallback(callback) }
            }
            cameraNetworkCallback = null
            cameraNetwork = null

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                wifiManager.disconnect()
            }
        } catch (e: Exception) {
            Timber.e(e, "Disconnect error")
        }
    }

    /**
     * Check if connected to WiFi
     */
    fun isConnected(): Boolean {
        return try {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities =
                connectivityManager.getNetworkCapabilities(network) ?: return false

            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (e: Exception) {
            Timber.e(e, "Error checking WiFi status")
            false
        }
    }

    private fun systemIsConnected(ssid: String): Boolean {
        val connectedSsid = wifiManager.connectionInfo?.ssid?.trim('"')
        return connectedSsid == ssid
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
}
