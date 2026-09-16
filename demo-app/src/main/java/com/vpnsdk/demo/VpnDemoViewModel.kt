package com.vpnsdk.demo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vpnsdk.VpnConnectionManager
import com.vpnsdk.VpnConnectionState
import com.vpnsdk.model.VpnConfig
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Thin wrapper over [VpnConnectionManager] for the demo UI. No repository, no auth, no
 * backend — this is the whole integration surface a consuming app needs.
 */
class VpnDemoViewModel(application: Application) : AndroidViewModel(application) {

    private val connectionManager = VpnConnectionManager(application)

    val connectionState: StateFlow<VpnConnectionState> = connectionManager.connectionState

    fun prepare(activity: android.app.Activity) = connectionManager.prepare(activity)

    fun connect(config: VpnConfig) {
        viewModelScope.launch { connectionManager.connect(config) }
    }

    fun disconnect() {
        viewModelScope.launch { connectionManager.disconnect() }
    }

    override fun onCleared() {
        super.onCleared()
        connectionManager.dispose()
    }
}
