package com.vpnsdk.core

import android.content.Context
import android.os.Build
import android.util.Log
import com.vpnsdk.model.VpnConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Xray process wrapper.
 *
 * This provides a local SOCKS endpoint consumed by tun2socks.
 */
class XrayCore(private val context: Context) : VpnCore {
    private var isActive = false
    private var bytesReceived = 0L
    private var bytesSent = 0L
    private var xrayProcess: Process? = null
    private var configFile: File? = null
    private var processLogThread: Thread? = null
    private val recentProcessLogs = ArrayDeque<String>()

    companion object {
        private const val TAG = "XrayCore"
        private const val XRAY_NATIVE_BINARY_NAME = "libxray.so"
        private const val XRAY_FALLBACK_BINARY_PREFIX = "xray_"
        private const val CONFIG_FILE_NAME = "xray_config.json"
        private const val LOCAL_SOCKS_HOST = "127.0.0.1"
        private const val LOCAL_SOCKS_PORT = 10808
        private const val LOCAL_DNS_PORT = 1053
        private val ASSET_BY_ABI = mapOf(
            "arm64-v8a" to "xray_arm64-v8a",
            "x86_64" to "xray_x86_64"
        )

        fun preflight(context: Context): Result<String> {
            return try {
                val abi = Build.SUPPORTED_ABIS.firstOrNull()
                    ?: return Result.failure(IllegalStateException("No device ABI reported by system"))
                val nativeBinary = File(context.applicationInfo.nativeLibraryDir, XRAY_NATIVE_BINARY_NAME)
                if (nativeBinary.exists() && nativeBinary.canExecute()) {
                    return Result.success("native:${nativeBinary.absolutePath}")
                }
                val asset = ASSET_BY_ABI[abi]
                    ?: return Result.failure(
                        IllegalStateException(
                            "Unsupported ABI for bundled Xray: $abi. Supported ABIs: ${ASSET_BY_ABI.keys.joinToString()}"
                        )
                    )

                context.assets.open(asset).use { input ->
                    if (input.available() <= 0) {
                        return Result.failure(
                            IllegalStateException("Xray asset exists but is empty: $asset")
                        )
                    }
                }
                Result.success("asset:$asset")
            } catch (e: Exception) {
                Result.failure(IllegalStateException("Xray preflight failed: ${e.message}", e))
            }
        }
    }

    override suspend fun start(config: VpnConfig): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            stopInternal()

            val configJson = generateXrayConfig(config)
            configFile = File(context.filesDir, CONFIG_FILE_NAME).apply { writeText(configJson) }

            val xrayBinary = resolveXrayBinary()
            if (!xrayBinary.exists() || !xrayBinary.canExecute()) {
                return@withContext Result.failure(
                    IllegalStateException("Xray binary not found/executable for ABI ${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}")
                )
            }

            xrayProcess = ProcessBuilder(
                xrayBinary.absolutePath,
                "run",
                "-c",
                configFile!!.absolutePath
            ).directory(context.filesDir)
                .redirectErrorStream(true)
                .start()

            val process = xrayProcess ?: return@withContext Result.failure(IllegalStateException("Failed to start xray process"))
            startProcessLogReader(process)

            val ready = waitUntilReady(process, LOCAL_SOCKS_HOST, LOCAL_SOCKS_PORT)
            if (!ready) {
                val output = getRecentProcessLogs()
                stopInternal()
                return@withContext Result.failure(
                    IllegalStateException(
                        if (output.isNotBlank()) "Xray failed to become ready: $output"
                        else "Xray process started but SOCKS endpoint is not reachable"
                    )
                )
            }

            isActive = true
            Log.d(TAG, "Xray started and SOCKS is ready at $LOCAL_SOCKS_HOST:$LOCAL_SOCKS_PORT")
            Result.success(Unit)
        } catch (e: IOException) {
            stopInternal()
            val message = e.message.orEmpty()
            val hint = if (message.contains("Permission denied", ignoreCase = true)) {
                "Permission denied executing Xray binary. Ensure libxray.so is bundled in jniLibs and native extraction is enabled."
            } else {
                "I/O error while starting Xray: $message"
            }
            Log.e(TAG, hint, e)
            Result.failure(IllegalStateException(hint, e))
        } catch (e: Exception) {
            stopInternal()
            Log.e(TAG, "Failed to start Xray", e)
            Result.failure(e)
        }
    }

    override suspend fun stop() {
        withContext(Dispatchers.IO) {
            stopInternal()
        }
    }

    override fun isRunning(): Boolean = isActive && (xrayProcess?.isAlive == true)

    override fun getBytesReceived(): Long = bytesReceived

    override fun getBytesSent(): Long = bytesSent

    override fun getLocalSocksHost(): String = LOCAL_SOCKS_HOST

    override fun getLocalSocksPort(): Int = LOCAL_SOCKS_PORT

    override fun getLocalDnsPort(): Int = LOCAL_DNS_PORT

    private fun stopInternal() {
        isActive = false

        processLogThread?.interrupt()
        processLogThread = null
        synchronized(recentProcessLogs) {
            recentProcessLogs.clear()
        }

        xrayProcess?.let { process ->
            runCatching { process.destroy() }
            runCatching { process.destroyForcibly() }
        }
        xrayProcess = null

        runCatching { configFile?.delete() }
        configFile = null

        bytesReceived = 0L
        bytesSent = 0L
    }

    private suspend fun waitUntilReady(process: Process, host: String, port: Int): Boolean {
        repeat(30) {
            if (!process.isAlive) return false
            if (isPortOpen(host, port, 400)) return true
            delay(100)
        }
        return false
    }

    private fun startProcessLogReader(process: Process) {
        processLogThread?.interrupt()
        processLogThread = Thread {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(recentProcessLogs) {
                            recentProcessLogs.addLast(line)
                            while (recentProcessLogs.size > 40) recentProcessLogs.removeFirst()
                        }
                        Log.w(TAG, "xray> $line")
                    }
                }
            } catch (_: Exception) {
                // Reader thread exits when process stops/stream closes.
            }
        }.apply {
            name = "XrayProcessLogReader"
            isDaemon = true
            start()
        }
    }

    private fun getRecentProcessLogs(): String {
        synchronized(recentProcessLogs) {
            return recentProcessLogs.joinToString(" | ")
        }
    }

    private fun isPortOpen(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun generateXrayConfig(config: VpnConfig): String {
        val root = JSONObject()
        root.put("log", JSONObject().put("loglevel", "warning"))

        val inbounds = JSONArray()
        inbounds.put(
            JSONObject()
                .put("listen", LOCAL_SOCKS_HOST)
                .put("port", LOCAL_SOCKS_PORT)
                .put("protocol", "socks")
                .put("settings", JSONObject().put("udp", true))
                .put(
                    "sniffing",
                    JSONObject()
                        .put("enabled", true)
                        .put("destOverride", JSONArray().put("http").put("tls"))
                )
        )
        root.put("inbounds", inbounds)

        root.put("outbounds", JSONArray().put(buildProxyOutbound(config).put("tag", "proxy")))
        root.put("routing", JSONObject().put("domainStrategy", "AsIs"))
        return root.toString()
    }

    private fun buildProxyOutbound(config: VpnConfig): JSONObject {
        return when (config.protocol.lowercase()) {
            "vmess" -> buildVmessOutbound(config)
            "vless" -> buildVlessOutbound(config)
            "trojan" -> buildTrojanOutbound(config)
            "shadowsocks" -> buildShadowsocksOutbound(config)
            else -> throw IllegalArgumentException("Unsupported protocol: ${config.protocol}")
        }
    }

    private fun buildVmessOutbound(config: VpnConfig): JSONObject {
        val user = JSONObject()
            .put("id", config.uuid ?: "")
            .put("alterId", 0)
            .put("security", config.security ?: "auto")

        val vnext = JSONObject()
            .put("address", config.address)
            .put("port", config.port)
            .put("users", JSONArray().put(user))

        return JSONObject()
            .put("protocol", "vmess")
            .put("settings", JSONObject().put("vnext", JSONArray().put(vnext)))
            .put("streamSettings", buildStreamSettings(config))
    }

    private fun buildVlessOutbound(config: VpnConfig): JSONObject {
        val encryption = if (config.encryption.equals("auto", ignoreCase = true)) "none" else config.encryption
        val user = JSONObject()
            .put("id", config.uuid ?: "")
            .put("encryption", encryption.ifBlank { "none" })
        config.flow?.takeIf { it.isNotBlank() }?.let { user.put("flow", it) }

        val vnext = JSONObject()
            .put("address", config.address)
            .put("port", config.port)
            .put("users", JSONArray().put(user))

        return JSONObject()
            .put("protocol", "vless")
            .put("settings", JSONObject().put("vnext", JSONArray().put(vnext)))
            .put("streamSettings", buildStreamSettings(config))
    }

    private fun buildTrojanOutbound(config: VpnConfig): JSONObject {
        val server = JSONObject()
            .put("address", config.address)
            .put("port", config.port)
            .put("password", config.password ?: "")

        return JSONObject()
            .put("protocol", "trojan")
            .put("settings", JSONObject().put("servers", JSONArray().put(server)))
            .put("streamSettings", buildStreamSettings(config))
    }

    private fun buildShadowsocksOutbound(config: VpnConfig): JSONObject {
        val server = JSONObject()
            .put("address", config.address)
            .put("port", config.port)
            .put("method", config.method ?: "aes-256-gcm")
            .put("password", config.password ?: "")

        return JSONObject()
            .put("protocol", "shadowsocks")
            .put("settings", JSONObject().put("servers", JSONArray().put(server)))
    }

    private fun buildStreamSettings(config: VpnConfig): JSONObject {
        val stream = JSONObject().put("network", config.network.lowercase())

        if (config.tls) {
            stream.put("security", "tls")
            stream.put(
                "tlsSettings",
                JSONObject()
                    .put("serverName", config.sni ?: config.host ?: config.address)
                    .put("allowInsecure", false)
            )
        }

        if (config.network.equals("ws", ignoreCase = true)) {
            stream.put(
                "wsSettings",
                JSONObject()
                    .put("path", config.wsPath ?: "/")
                    .put(
                        "headers",
                        JSONObject().put("Host", config.host ?: config.address)
                    )
            )
        }

        return stream
    }

    private fun resolveXrayBinary(): File {
        val nativeBinary = File(context.applicationInfo.nativeLibraryDir, XRAY_NATIVE_BINARY_NAME)
        if (nativeBinary.exists() && nativeBinary.canExecute()) {
            return nativeBinary
        }

        // Fallback for environments where native extraction is unavailable.
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        val assetName = ASSET_BY_ABI[abi]
            ?: throw IllegalStateException(
                "Unsupported ABI for bundled Xray: $abi. Supported ABIs: ${ASSET_BY_ABI.keys.joinToString()}"
            )
        val xrayFile = File(context.noBackupFilesDir, "$XRAY_FALLBACK_BINARY_PREFIX$abi")
        if (!xrayFile.exists() || xrayFile.length() <= 0L) {
            context.assets.open(assetName).use { input ->
                FileOutputStream(xrayFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
        xrayFile.setExecutable(true, false)
        xrayFile.setReadable(true, false)
        if (!xrayFile.canExecute()) {
            throw IllegalStateException("Fallback Xray binary is not executable: ${xrayFile.absolutePath}")
        }
        Log.w(TAG, "Using fallback Xray binary from app storage: ${xrayFile.absolutePath}")
        return xrayFile
    }
}

