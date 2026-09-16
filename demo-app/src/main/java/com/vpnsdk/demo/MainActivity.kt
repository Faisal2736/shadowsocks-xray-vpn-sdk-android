package com.vpnsdk.demo

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.vpnsdk.VpnConnectionState
import com.vpnsdk.demo.databinding.ActivityMainBinding
import com.vpnsdk.model.VpnConfig
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Single-screen reference client for [com.vpnsdk.VpnConnectionManager]: fill in (or accept
 * the pre-filled example) server details, request VPN permission, connect, watch state,
 * disconnect. This is the whole integration flow a consuming app needs to replicate.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: VpnDemoViewModel by viewModels()
    private val handler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            viewModel.connect(currentConfigFromForm())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        prefillExampleConfig()
        observeConnectionState()

        binding.connectButton.setOnClickListener {
            val state = viewModel.connectionState.value
            when (state) {
                is VpnConnectionState.Connected -> viewModel.disconnect()
                is VpnConnectionState.Connecting -> Unit // no-op while connecting
                else -> requestVpnPermissionThenConnect()
            }
        }
    }

    private fun prefillExampleConfig() {
        val example = ExampleServers.placeholder
        binding.addressInput.setText(example.address)
        binding.portInput.setText(example.port.toString())
        binding.methodInput.setText(example.method)
        binding.passwordInput.setText(example.password)
    }

    private fun currentConfigFromForm(): VpnConfig {
        val port = binding.portInput.text?.toString()?.toIntOrNull() ?: ExampleServers.placeholder.port
        return VpnConfig(
            protocol = "shadowsocks",
            address = binding.addressInput.text?.toString().orEmpty(),
            port = port,
            method = binding.methodInput.text?.toString(),
            password = binding.passwordInput.text?.toString(),
            displayName = "Demo connection"
        )
    }

    private fun requestVpnPermissionThenConnect() {
        val config = currentConfigFromForm()
        if (config.address.isBlank()) {
            Toast.makeText(this, getString(R.string.hint_address), Toast.LENGTH_SHORT).show()
            return
        }
        val intent = viewModel.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            viewModel.connect(config)
        }
    }

    private fun observeConnectionState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.connectionState.collectLatest { state -> render(state) }
            }
        }
    }

    private fun render(state: VpnConnectionState) {
        binding.errorText.visibility = android.view.View.GONE
        when (state) {
            is VpnConnectionState.Connecting -> {
                binding.statusText.text = getString(R.string.connecting)
                binding.connectButton.text = getString(R.string.connecting)
                binding.connectButton.isEnabled = false
                binding.serverNameText.visibility = android.view.View.GONE
                binding.connectionTimeText.visibility = android.view.View.GONE
                binding.trafficText.visibility = android.view.View.GONE
                stopTimer()
            }
            is VpnConnectionState.Connected -> {
                binding.statusText.text = getString(R.string.connected)
                binding.connectButton.text = getString(R.string.disconnect)
                binding.connectButton.isEnabled = true
                state.config?.displayName?.let {
                    binding.serverNameText.text = it
                    binding.serverNameText.visibility = android.view.View.VISIBLE
                }
                binding.connectionTimeText.visibility = android.view.View.VISIBLE
                binding.trafficText.visibility = android.view.View.VISIBLE
                startTimer(state.connectedSinceMillis)
                updateTraffic(state.bytesReceived, state.bytesSent)
            }
            is VpnConnectionState.Error -> {
                binding.statusText.text = getString(R.string.disconnected)
                binding.connectButton.text = getString(R.string.connect)
                binding.connectButton.isEnabled = true
                resetIdleUi()
                binding.errorText.text = state.message
                binding.errorText.visibility = android.view.View.VISIBLE
            }
            else -> {
                binding.statusText.text = getString(R.string.disconnected)
                binding.connectButton.text = getString(R.string.connect)
                binding.connectButton.isEnabled = true
                resetIdleUi()
            }
        }
    }

    private fun resetIdleUi() {
        binding.serverNameText.visibility = android.view.View.GONE
        binding.connectionTimeText.visibility = android.view.View.GONE
        binding.trafficText.visibility = android.view.View.GONE
        stopTimer()
    }

    private fun startTimer(connectedSinceMillis: Long) {
        stopTimer()
        timerRunnable = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - connectedSinceMillis
                val seconds = (elapsed / 1000) % 60
                val minutes = (elapsed / 60000) % 60
                val hours = elapsed / 3600000
                binding.connectionTimeText.text = String.format("%02d:%02d:%02d", hours, minutes, seconds)
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(timerRunnable!!)
    }

    private fun stopTimer() {
        timerRunnable?.let { handler.removeCallbacks(it) }
        timerRunnable = null
    }

    private fun updateTraffic(bytesReceived: Long, bytesSent: Long) {
        val receivedMB = bytesReceived / (1024.0 * 1024.0)
        val sentMB = bytesSent / (1024.0 * 1024.0)
        binding.trafficText.text = String.format("↓ %.2f MB ↑ %.2f MB", receivedMB, sentMB)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTimer()
    }
}
