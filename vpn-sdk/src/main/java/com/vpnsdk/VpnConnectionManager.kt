package com.vpnsdk

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService as AndroidVpnService
import android.os.Build
import androidx.core.content.ContextCompat
import com.vpnsdk.model.VpnConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Public entry point of the VPN SDK.
 *
 * A host app talks to the tunnel only through this class: request VPN permission, connect
 * with a [VpnConfig], observe [connectionState], and disconnect. It hides the
 * [android.net.VpnService] lifecycle, the broadcast used internally to report state, and the
 * Shadowsocks/Xray core selection.
 *
 * Typical usage from an Activity/Fragment:
 * ```
 * val manager = VpnConnectionManager(context)
 * manager.prepare(activity)?.let { intent -> launcher.launch(intent); return }
 * manager.connect(config)
 * // ...
 * manager.connectionState.collect { state -> /* update UI */ }
 * ```
 */
class VpnConnectionManager(private val appContext: Context) {

    constructor(activity: Activity) : this(activity.applicationContext)

    private val _connectionState = MutableStateFlow<VpnConnectionState>(VpnConnectionState.Idle)

    /** Current tunnel state. Safe to collect from any coroutine scope. */
    val connectionState: StateFlow<VpnConnectionState> = _connectionState.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != VpnService.ACTION_CONNECTION_STATE_CHANGED) return

            val connected = intent.getBooleanExtra(VpnService.EXTRA_CONNECTED, false)
            val error = intent.getStringExtra(VpnService.EXTRA_ERROR)
            val config = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(VpnService.EXTRA_CONFIG, VpnConfig::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<VpnConfig>(VpnService.EXTRA_CONFIG)
            }
            val bytesReceived = intent.getLongExtra(VpnService.EXTRA_BYTES_RECEIVED, 0L)
            val bytesSent = intent.getLongExtra(VpnService.EXTRA_BYTES_SENT, 0L)
            val connectedTime = intent.getLongExtra(VpnService.EXTRA_CONNECTED_TIME, 0L)

            _connectionState.value = when {
                !connected && error != null -> VpnConnectionState.Error(error)
                !connected -> VpnConnectionState.Disconnected
                else -> VpnConnectionState.Connected(
                    config = config,
                    bytesReceived = bytesReceived,
                    bytesSent = bytesSent,
                    connectedSinceMillis = connectedTime
                )
            }
        }
    }

    private var isRegistered = false

    init {
        registerReceiver()
    }

    private fun registerReceiver() {
        if (isRegistered) return
        val filter = IntentFilter(VpnService.ACTION_CONNECTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, filter)
        }
        isRegistered = true
    }

    /**
     * Returns an [Intent] to launch for the system VPN-permission dialog, or `null` if
     * permission was already granted. Launch it with
     * `registerForActivityResult(ActivityResultContracts.StartActivityForResult())`.
     */
    fun prepare(activity: Activity): Intent? = AndroidVpnService.prepare(activity)

    /** Starts the tunnel. Call [prepare] first and only invoke this once permission is granted. */
    fun connect(config: VpnConfig) {
        _connectionState.value = VpnConnectionState.Connecting
        val intent = Intent(appContext, VpnService::class.java).apply {
            action = VpnService.ACTION_CONNECT
            putExtra(VpnService.EXTRA_CONFIG, config)
        }
        ContextCompat.startForegroundService(appContext, intent)
    }

    /** Stops the tunnel if one is active. */
    fun disconnect() {
        val intent = Intent(appContext, VpnService::class.java).apply {
            action = VpnService.ACTION_DISCONNECT
        }
        appContext.startService(intent)
    }

    /** Unregisters the internal broadcast receiver. Call from `onDestroy`/`onCleared`. */
    fun dispose() {
        if (!isRegistered) return
        runCatching { appContext.unregisterReceiver(receiver) }
        isRegistered = false
    }
}

/** Tunnel lifecycle as observed by [VpnConnectionManager.connectionState]. */
sealed class VpnConnectionState {
    data object Idle : VpnConnectionState()
    data object Connecting : VpnConnectionState()
    data class Connected(
        val config: VpnConfig?,
        val bytesReceived: Long,
        val bytesSent: Long,
        val connectedSinceMillis: Long
    ) : VpnConnectionState()
    data object Disconnected : VpnConnectionState()
    data class Error(val message: String) : VpnConnectionState()
}
