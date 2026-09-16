package com.vpnsdk.core

import android.util.Log
import java.net.InetAddress

/**
 * Shadowsocks Protocol Handler
 * Implements Shadowsocks protocol format:
 * [Encrypted SOCKS5 Address][Encrypted Payload]
 * 
 * Based on shadowsocks-android protocol specification
 */
object ShadowsocksProtocol {
    private const val TAG = "ShadowsocksProtocol"
    
    /**
     * Encode destination address in SOCKS5 format
     * Format: [ATYP][ADDR][PORT]
     * ATYP: 0x01 (IPv4), 0x03 (Domain), 0x04 (IPv6)
     */
    fun encodeAddress(host: String, port: Int): ByteArray {
        return try {
            // Try to parse as IP address
            val ip = InetAddress.getByName(host)
            when {
                ip.address.size == 4 -> {
                    // IPv4
                    val result = ByteArray(1 + 4 + 2)
                    result[0] = 0x01 // ATYP: IPv4
                    System.arraycopy(ip.address, 0, result, 1, 4)
                    result[5] = (port shr 8).toByte()
                    result[6] = (port and 0xFF).toByte()
                    result
                }
                ip.address.size == 16 -> {
                    // IPv6
                    val result = ByteArray(1 + 16 + 2)
                    result[0] = 0x04 // ATYP: IPv6
                    System.arraycopy(ip.address, 0, result, 1, 16)
                    result[17] = (port shr 8).toByte()
                    result[18] = (port and 0xFF).toByte()
                    result
                }
                else -> {
                    // Domain name
                    val hostBytes = host.toByteArray(Charsets.UTF_8)
                    val result = ByteArray(1 + 1 + hostBytes.size + 2)
                    result[0] = 0x03 // ATYP: Domain
                    result[1] = hostBytes.size.toByte()
                    System.arraycopy(hostBytes, 0, result, 2, hostBytes.size)
                    val offset = 2 + hostBytes.size
                    result[offset] = (port shr 8).toByte()
                    result[offset + 1] = (port and 0xFF).toByte()
                    result
                }
            }
        } catch (e: Exception) {
            // If parsing fails, treat as domain name
            val hostBytes = host.toByteArray(Charsets.UTF_8)
            val result = ByteArray(1 + 1 + hostBytes.size + 2)
            result[0] = 0x03 // ATYP: Domain
            result[1] = hostBytes.size.toByte()
            System.arraycopy(hostBytes, 0, result, 2, hostBytes.size)
            val offset = 2 + hostBytes.size
            result[offset] = (port shr 8).toByte()
            result[offset + 1] = (port and 0xFF).toByte()
            result
        }
    }
    
    /**
     * Build complete Shadowsocks packet
     * Format: [Encrypted SOCKS5 Address + Payload]
     */
    fun buildPacket(destHost: String, destPort: Int, payload: ByteArray): ByteArray {
        val address = encodeAddress(destHost, destPort)
        // Combine address and payload
        val combined = ByteArray(address.size + payload.size)
        System.arraycopy(address, 0, combined, 0, address.size)
        System.arraycopy(payload, 0, combined, address.size, payload.size)
        return combined
    }
}

