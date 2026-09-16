package com.vpnsdk.core

import android.util.Log
import java.nio.ByteBuffer

/**
 * Handles IP packet parsing and reconstruction
 * Extracts payload from IP packets and reconstructs IP headers for responses
 */
object PacketHandler {
    private const val TAG = "PacketHandler"
    
    /**
     * Extract payload from IP packet (skip IP and TCP/UDP headers)
     */
    fun extractPayload(packet: ByteArray): ByteArray? {
        if (packet.size < 20) return null // Minimum IP header size
        
        val version = (packet[0].toInt() shr 4) and 0x0F
        if (version != 4) {
            Log.w(TAG, "Only IPv4 supported, got version: $version")
            return null
        }
        
        // Get IP header length
        val ipHeaderLength = (packet[0].toInt() and 0x0F) * 4
        
        if (packet.size < ipHeaderLength) return null
        
        // Get protocol
        val protocol = packet[9].toInt() and 0xFF
        
        // Get transport header length
        val transportHeaderLength = when (protocol) {
            6 -> { // TCP
                if (packet.size < ipHeaderLength + 20) return null
                ((packet[ipHeaderLength + 12].toInt() shr 4) and 0x0F) * 4
            }
            17 -> { // UDP
                8
            }
            else -> {
                Log.w(TAG, "Unsupported protocol: $protocol")
                return null
            }
        }
        
        val totalHeaderLength = ipHeaderLength + transportHeaderLength
        if (packet.size <= totalHeaderLength) return null
        
        // Extract payload
        return packet.copyOfRange(totalHeaderLength, packet.size)
    }
    
    /**
     * Reconstruct IP packet from payload
     * This is a simplified version - in production, you'd need to properly reconstruct headers
     */
    fun reconstructPacket(originalPacket: ByteArray, payload: ByteArray): ByteArray? {
        if (originalPacket.size < 20) return null
        
        val version = (originalPacket[0].toInt() shr 4) and 0x0F
        if (version != 4) return null
        
        val ipHeaderLength = (originalPacket[0].toInt() and 0x0F) * 4
        val protocol = originalPacket[9].toInt() and 0xFF
        
        val transportHeaderLength = when (protocol) {
            6 -> {
                if (originalPacket.size < ipHeaderLength + 20) return null
                ((originalPacket[ipHeaderLength + 12].toInt() shr 4) and 0x0F) * 4
            }
            17 -> 8
            else -> return null
        }
        
        val totalHeaderLength = ipHeaderLength + transportHeaderLength
        
        // Reconstruct packet with new payload
        val newPacket = ByteArray(totalHeaderLength + payload.size)
        System.arraycopy(originalPacket, 0, newPacket, 0, totalHeaderLength)
        System.arraycopy(payload, 0, newPacket, totalHeaderLength, payload.size)
        
        // Update IP total length
        val totalLength = newPacket.size
        newPacket[2] = (totalLength shr 8).toByte()
        newPacket[3] = (totalLength and 0xFF).toByte()
        
        // Recalculate checksum (simplified - in production, calculate properly)
        newPacket[10] = 0
        newPacket[11] = 0
        
        return newPacket
    }
}

