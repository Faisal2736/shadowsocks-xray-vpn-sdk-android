package com.vpnsdk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.vpnsdk.core.Tun2Socks
import com.vpnsdk.core.VpnCore
import com.vpnsdk.core.XrayCore
import com.vpnsdk.model.VpnConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Android [VpnService] that owns the TUN interface and the Shadowsocks/Xray transport.
 *
 * Apps do not talk to this class directly — use [VpnConnectionManager], which starts/stops
 * it via intents and exposes connection state as a [kotlinx.coroutines.flow.StateFlow].
 */
class VpnService : VpnService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var vpnInterface: ParcelFileDescriptor? = null
    private var currentConfig: VpnConfig? = null
    private var isRunning = false
    private var connectedTime = 0L
    private var vpnCore: VpnCore? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var tun2SocksBridge: Tun2Socks? = null
    private var healthMonitorJob: Job? = null

    companion object {
        private const val TAG = "VpnSdkService"
        // Align TUN client/router subnet with tun2socks defaults.
        // This avoids DNS/data-path mismatches where tunnel appears connected but traffic stalls.
        private const val VPN_ADDRESS = "172.19.0.1"
        private const val VPN_ROUTE = "0.0.0.0"
        private const val VPN_DNS_PRIMARY = "172.19.0.2"
        private const val DEFAULT_EXTERNAL_DNS = "8.8.8.8"
        private const val VPN_MTU = 1400
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "vpn_sdk_service_channel"

        const val ACTION_CONNECT = "com.vpnsdk.action.CONNECT"
        const val ACTION_DISCONNECT = "com.vpnsdk.action.DISCONNECT"
        const val ACTION_CONNECTION_STATE_CHANGED = "com.vpnsdk.action.CONNECTION_STATE_CHANGED"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_CONNECTED = "connected"
        const val EXTRA_ERROR = "error"
        const val EXTRA_BYTES_RECEIVED = "bytes_received"
        const val EXTRA_BYTES_SENT = "bytes_sent"
        const val EXTRA_CONNECTED_TIME = "connected_time"

        /** Optional hook so a host app can brand the persistent connection notification. */
        var notificationContentTitle: String = "VPN"
        var notificationSmallIconResId: Int? = null
    }

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        createNotificationChannel()
        registerNetworkCallback()
        Log.d(TAG, "VPN SDK service created")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VPN Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows VPN connection status"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(config: VpnConfig?): Notification {
        val disconnectIntent = Intent(this, VpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this, 0, disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(notificationContentTitle)
            .setContentText(config?.displayName ?: config?.address ?: "Connected")
            .setSmallIcon(notificationSmallIconResId ?: android.R.drawable.ic_dialog_info)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", disconnectPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun registerNetworkCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    Log.d(TAG, "Network available (isRunning=$isRunning)")
                }

                override fun onLost(network: Network) {
                    super.onLost(network)
                    Log.d(TAG, "Network lost")
                }
            }

            connectivityManager?.registerNetworkCallback(networkRequest, networkCallback!!)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val config = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_CONFIG, VpnConfig::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<VpnConfig>(EXTRA_CONFIG)
                }
                if (config != null) {
                    Log.d(TAG, "Received connect action for ${config.address}:${config.port}")
                    connect(config)
                } else {
                    Log.e(TAG, "No config provided in connect intent")
                    broadcastConnectionState(false, "No server configuration provided")
                }
            }
            ACTION_DISCONNECT -> {
                Log.d(TAG, "Received disconnect action")
                disconnect()
            }
        }
        return START_STICKY
    }

    private fun connect(config: VpnConfig) {
        if (isRunning) {
            Log.d(TAG, "Already connected, disconnecting first...")
            disconnect()
            serviceScope.launch {
                delay(500)
                connectInternal(config)
            }
        } else {
            connectInternal(config)
        }
    }

    private fun connectInternal(config: VpnConfig) {
        Log.d(TAG, "Starting connection to ${config.address}:${config.port} (${config.protocol})")

        currentConfig = config
        startForeground(NOTIFICATION_ID, createNotification(config))

        serviceScope.launch {
            try {
                establishVpn(config)
                isRunning = true
                connectedTime = System.currentTimeMillis()

                Log.d(TAG, "VPN connection established successfully")
                broadcastConnectionState(true, null)

                startTrafficMonitoring()
                startHealthMonitoring()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect: ${e.message}", e)
                healthMonitorJob?.cancel()
                healthMonitorJob = null
                runCatching { tun2SocksBridge?.stop() }
                tun2SocksBridge = null
                runCatching { vpnCore?.stop() }
                vpnCore = null
                runCatching { vpnInterface?.close() }
                vpnInterface = null
                isRunning = false
                currentConfig = null
                broadcastConnectionState(false, e.message ?: "Unknown error occurred")
                stopForeground(true)
                stopSelf()
            }
        }
    }

    private suspend fun establishVpn(config: VpnConfig): ParcelFileDescriptor {
        Log.d(TAG, "Establishing VPN with protocol: ${config.protocol}")
        runRuntimePreflightChecks()

        if (config.address.isBlank()) {
            throw IllegalArgumentException("Server address is empty")
        }
        if (config.port <= 0 || config.port > 65535) {
            throw IllegalArgumentException("Invalid server port: ${config.port}")
        }
        if (config.protocol.lowercase() == "shadowsocks" && (config.method.isNullOrBlank() || config.password.isNullOrBlank())) {
            throw IllegalArgumentException("Shadowsocks requires method and password")
        }

        // Use a real local proxy core for every protocol, then bridge TUN via native tun2socks.
        vpnCore = XrayCore(this)

        val result = vpnCore?.start(config)
        if (result?.isFailure == true) {
            val errorMsg = result.exceptionOrNull()?.message ?: "Failed to start VPN core"
            throw Exception(errorMsg)
        }

        val builder = Builder()
        builder.setSession(config.displayName ?: "VPN SDK")
        builder.addAddress(VPN_ADDRESS, 30)
        builder.addRoute(VPN_ROUTE, 0)
        builder.addDnsServer(VPN_DNS_PRIMARY)
        builder.setMtu(VPN_MTU)
        builder.addDisallowedApplication(packageName)

        vpnInterface = builder.establish()
        if (vpnInterface == null) {
            throw IllegalStateException("Cannot establish VPN interface")
        }

        val core = vpnCore ?: throw IllegalStateException("VPN core unavailable")
        val socksHost = core.getLocalSocksHost() ?: "127.0.0.1"
        val socksPort = core.getLocalSocksPort()
            ?: throw IllegalStateException("VPN core did not expose local SOCKS port")

        tun2SocksBridge = Tun2Socks(this)
        val tunStart = tun2SocksBridge?.start(
            vpnFd = vpnInterface!!.fileDescriptor,
            socksHost = socksHost,
            socksPort = socksPort,
            dnsGatewayHost = DEFAULT_EXTERNAL_DNS,
            dnsPort = 53
        )
        if (tunStart?.isFailure == true) {
            throw IllegalStateException(tunStart.exceptionOrNull()?.message ?: "Failed to start tun2socks bridge")
        }

        Log.d(TAG, "tun2socks bridge started: TUN -> $socksHost:$socksPort")
        return vpnInterface!!
    }

    private fun runRuntimePreflightChecks() {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        Log.i(TAG, "VPN runtime preflight started (abi=$abi)")

        val xrayAsset = XrayCore.preflight(this).getOrElse { error ->
            throw IllegalStateException(error.message ?: "Xray preflight failed", error)
        }
        Log.i(TAG, "Xray preflight passed (asset=$xrayAsset)")

        val tun2socksBinary = Tun2Socks.preflight(this).getOrElse { error ->
            throw IllegalStateException(error.message ?: "tun2socks preflight failed", error)
        }
        Log.i(TAG, "tun2socks preflight passed (binary=${tun2socksBinary.absolutePath})")
    }

    private fun startTrafficMonitoring() {
        serviceScope.launch {
            while (isRunning) {
                delay(5000)
                val bytesReceived = vpnCore?.getBytesReceived() ?: 0L
                val bytesSent = vpnCore?.getBytesSent() ?: 0L
                val elapsed = System.currentTimeMillis() - this@VpnService.connectedTime
                broadcastTrafficUpdate(bytesReceived, bytesSent, elapsed)
            }
        }
    }

    private fun startHealthMonitoring() {
        healthMonitorJob?.cancel()
        healthMonitorJob = serviceScope.launch {
            delay(2000)
            while (isRunning) {
                val interfaceAlive = vpnInterface != null
                val coreAlive = vpnCore?.isRunning() == true
                val bridgeAlive = tun2SocksBridge?.isRunning() == true

                if (!interfaceAlive || !coreAlive || !bridgeAlive) {
                    val reason = when {
                        !interfaceAlive -> "VPN interface closed unexpectedly"
                        !coreAlive -> "VPN core process terminated unexpectedly"
                        !bridgeAlive -> "tun2socks process terminated unexpectedly"
                        else -> "VPN transport became unhealthy"
                    }
                    Log.e(TAG, "VPN health check failed: $reason")
                    disconnect(reason)
                    return@launch
                }
                delay(1000)
            }
        }
    }

    private fun broadcastTrafficUpdate(bytesReceived: Long, bytesSent: Long, connectedTime: Long) {
        val intent = Intent(ACTION_CONNECTION_STATE_CHANGED).apply {
            setPackage(packageName)
            putExtra(EXTRA_CONNECTED, true)
            putExtra(EXTRA_BYTES_RECEIVED, bytesReceived)
            putExtra(EXTRA_BYTES_SENT, bytesSent)
            putExtra(EXTRA_CONNECTED_TIME, connectedTime)
            currentConfig?.let { putExtra(EXTRA_CONFIG, it) }
        }
        sendBroadcast(intent)
    }

    private fun disconnect(error: String? = null) {
        if (!isRunning && vpnInterface == null) {
            return
        }
        isRunning = false
        healthMonitorJob?.cancel()
        healthMonitorJob = null
        val bridge = tun2SocksBridge
        val core = vpnCore
        tun2SocksBridge = null
        vpnCore = null
        serviceScope.launch {
            runCatching { bridge?.stop() }
            runCatching { core?.stop() }
        }
        vpnInterface?.close()
        vpnInterface = null
        currentConfig = null
        connectedTime = 0L
        broadcastConnectionState(false, error)
        stopForeground(true)
        stopSelf()
    }

    private fun broadcastConnectionState(connected: Boolean, error: String?) {
        val intent = Intent(ACTION_CONNECTION_STATE_CHANGED).apply {
            setPackage(packageName)
            putExtra(EXTRA_CONNECTED, connected)
            putExtra(EXTRA_ERROR, error)
            currentConfig?.let { putExtra(EXTRA_CONFIG, it) }
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        disconnect()
    }
}
