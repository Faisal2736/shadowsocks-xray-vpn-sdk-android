package com.vpnsdk.core

import com.vpnsdk.model.VpnConfig

/**
 * VPN Core interface
 * Implementations handle protocol-specific connection logic
 */
interface VpnCore {
    suspend fun start(config: VpnConfig): Result<Unit>
    suspend fun stop()
    fun isRunning(): Boolean
    fun getBytesReceived(): Long
    fun getBytesSent(): Long
    fun getLocalSocksHost(): String? = null
    fun getLocalSocksPort(): Int? = null
    fun getLocalDnsPort(): Int? = null
}

