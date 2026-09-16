package com.vpnsdk.core

import android.util.Log
import com.vpnsdk.model.VpnConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel

/**
 * Shadowsocks Core implementation
 * Based on shadowsocks-android architecture
 * Supports AEAD ciphers: aes-256-gcm, aes-128-gcm, chacha20-ietf-poly1305
 */
class ShadowsocksCore(
    private val socketProtector: ((Socket) -> Boolean)? = null
) : VpnCore {
    private var isActive = false
    private var bytesReceived = 0L
    private var bytesSent = 0L
    private var socketChannel: SocketChannel? = null
    private var serverAddress: InetSocketAddress? = null
    private var encryption: ShadowsocksEncryption? = null
    private var currentConfig: VpnConfig? = null

    companion object {
        private const val TAG = "ShadowsocksCore"
    }

    override suspend fun start(config: VpnConfig): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            Log.d(TAG, "Starting Shadowsocks connection to ${config.address}:${config.port}")
            Log.d(TAG, "Method: ${config.method}, Password: ${if (config.password.isNullOrEmpty()) "empty" else "***"}")

            // Create socket connection to Shadowsocks server
            serverAddress = InetSocketAddress(config.address, config.port)
            socketChannel = SocketChannel.open()
            socketChannel?.configureBlocking(false) // Non-blocking for better performance

            // Exclude transport socket from VPN tunnel to avoid recursive routing.
            socketChannel?.socket()?.let { socket ->
                val protected = socketProtector?.invoke(socket) ?: true
                if (!protected) {
                    throw Exception("Failed to protect Shadowsocks socket from VPN routing")
                }
            }
            
            // Connect with timeout
            val connected = socketChannel?.connect(serverAddress)
            if (connected == false) {
                // Connection in progress, wait for it
                var attempts = 0
                while (socketChannel?.finishConnect() != true && attempts < 50) {
                    delay(100)
                    attempts++
                }
            }

            if (socketChannel?.isConnected != true) {
                throw Exception("Failed to connect to Shadowsocks server at ${config.address}:${config.port}")
            }

            Log.d(TAG, "Socket connected successfully")
            
            // Initialize encryption
            currentConfig = config
            val method = config.method ?: "aes-256-gcm"
            val password = config.password ?: ""
            
            if (password.isNotEmpty()) {
                encryption = ShadowsocksEncryption(method, password)
                Log.d(TAG, "Encryption initialized with method: $method")
            } else {
                Log.w(TAG, "No password provided, encryption disabled")
            }

            isActive = true
            Log.d(TAG, "Shadowsocks connection established")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Shadowsocks: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun stop() {
        withContext(Dispatchers.IO) {
            try {
                socketChannel?.close()
                socketChannel = null
                serverAddress = null
                isActive = false
                bytesReceived = 0L
                bytesSent = 0L
                Log.d(TAG, "Shadowsocks connection stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping Shadowsocks", e)
            }
        }
    }

    override fun isRunning(): Boolean = isActive && socketChannel?.isConnected == true

    override fun getBytesReceived(): Long = bytesReceived

    override fun getBytesSent(): Long = bytesSent

    private fun markTransportDisconnected() {
        isActive = false
        try {
            socketChannel?.close()
        } catch (_: Exception) {
        }
        socketChannel = null
        serverAddress = null
    }

    /**
     * Encrypt and send data to Shadowsocks server
     */
    suspend fun encryptAndSend(data: ByteArray): Result<Int> = withContext(Dispatchers.IO) {
        try {
            if (!isRunning()) {
                return@withContext Result.failure(Exception("Not connected"))
            }

            val encrypted = if (encryption != null) {
                // encrypt() is a suspend function, await it
                encryption?.encrypt(data) ?: run {
                    Log.e(TAG, "Encryption returned null")
                    return@withContext Result.failure(Exception("Encryption failed: returned null"))
                }
            } else {
                // No encryption, send raw (for testing)
                Log.w(TAG, "Sending unencrypted data (no encryption configured)")
                data
            }
            
            if (encrypted.isEmpty()) {
                return@withContext Result.failure(Exception("Encryption failed: empty result"))
            }

            val bytesWritten = socketChannel?.write(ByteBuffer.wrap(encrypted)) ?: 0
            if (bytesWritten > 0) {
                bytesSent += bytesWritten
                Log.d(TAG, "Sent $bytesWritten bytes to Shadowsocks server")
                Result.success(bytesWritten)
            } else {
                Log.w(TAG, "No bytes written to socket")
                Result.failure(Exception("Failed to write to socket"))
            }
        } catch (e: IOException) {
            markTransportDisconnected()
            Log.e(TAG, "Transport socket disconnected while sending", e)
            Result.failure(Exception("Transport disconnected: ${e.message}", e))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt and send", e)
            Result.failure(e)
        }
    }

    /**
     * Receive and decrypt data from Shadowsocks server
     * Returns 0 if no data available (non-blocking socket)
     */
    suspend fun receiveAndDecrypt(buffer: ByteBuffer): Result<Int> = withContext(Dispatchers.IO) {
        try {
            if (!isRunning()) {
                return@withContext Result.failure(Exception("Not connected"))
            }

            val readBuffer = ByteArray(buffer.remaining())
            val bytesRead = socketChannel?.read(ByteBuffer.wrap(readBuffer)) ?: 0
            
            if (bytesRead > 0) {
                Log.d(TAG, "Received $bytesRead bytes from Shadowsocks server")
                val decrypted = if (encryption != null) {
                    // decrypt() is a suspend function, await it
                    encryption?.decrypt(readBuffer.copyOf(bytesRead)) ?: run {
                        Log.e(TAG, "Decryption returned null")
                        return@withContext Result.failure(Exception("Decryption failed: returned null"))
                    }
                } else {
                    // No encryption, return raw
                    readBuffer.copyOf(bytesRead)
                }
                
                if (decrypted != null && decrypted.isNotEmpty()) {
                    buffer.put(decrypted)
                    bytesReceived += decrypted.size
                    Log.d(TAG, "Decrypted ${decrypted.size} bytes")
                    Result.success(decrypted.size)
                } else {
                    Log.w(TAG, "Decryption returned empty data")
                    Result.failure(Exception("Decryption failed: empty result"))
                }
            } else if (bytesRead == 0) {
                // No data available (non-blocking socket) - this is normal
                Result.success(0)
            } else {
                // bytesRead < 0: For non-blocking sockets, -1 means no data available, not an error
                // Only treat as error if socket is actually closed
                if (socketChannel?.isConnected != true) {
                    Result.failure(Exception("Socket disconnected"))
                } else {
                    // No data available yet
                    Result.success(0)
                }
            }
        } catch (e: java.nio.channels.ClosedChannelException) {
            markTransportDisconnected()
            Log.w(TAG, "Socket channel closed")
            Result.failure(Exception("Socket closed"))
        } catch (e: IOException) {
            markTransportDisconnected()
            Log.w(TAG, "Transport socket disconnected while reading: ${e.message}")
            Result.failure(Exception("Socket disconnected"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to receive and decrypt", e)
            Result.failure(e)
        }
    }
    
    /**
     * Get the socket channel for direct access (for packet forwarding)
     */
    fun getSocketChannel(): SocketChannel? = socketChannel
}

