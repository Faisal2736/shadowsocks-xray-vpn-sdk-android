package com.vpnsdk.core

import android.util.Base64
import android.util.Log
import java.net.URLDecoder

/**
 * Shadowsocks URI Parser and Builder
 * Handles SS URI format: ss://[method]:[password]@[server]:[port]#[name]
 * Also supports base64 encoded format: ss://base64([method]:[password]@[server]:[port])#[name]
 */
object ShadowsocksUri {
    private const val TAG = "ShadowsocksUri"
    
    /**
     * Parse SS URI to extract connection details
     * Format: ss://[method]:[password]@[server]:[port]#[name]
     * Or: ss://base64([method]:[password]@[server]:[port])#[name]
     */
    fun parse(uri: String): ShadowsocksConfig? {
        return try {
            if (!uri.startsWith("ss://")) {
                Log.e(TAG, "Invalid SS URI: does not start with ss://")
                return null
            }
            
            val uriWithoutProtocol = uri.substring(5) // Remove "ss://"
            val parts = uriWithoutProtocol.split("#", limit = 2)
            val name = if (parts.size > 1) URLDecoder.decode(parts[1], "UTF-8") else null
            
            val mainPart = parts[0]
            
            // Split by @ to separate credentials from server:port
            val atIndex = mainPart.lastIndexOf("@")
            if (atIndex == -1) {
                Log.e(TAG, "Invalid SS URI: missing @ separator")
                return null
            }
            
            val credentialsPart = mainPart.substring(0, atIndex)
            val serverPortPart = mainPart.substring(atIndex + 1)
            
            // Parse server:port first (this is always plain text)
            val portColonIndex = serverPortPart.lastIndexOf(":")
            if (portColonIndex == -1) {
                Log.e(TAG, "Invalid SS URI: missing port")
                return null
            }
            
            val server = serverPortPart.substring(0, portColonIndex)
            val port = serverPortPart.substring(portColonIndex + 1).toIntOrNull()
                ?: run {
                    Log.e(TAG, "Invalid SS URI: invalid port")
                    return null
                }
            
            // Parse credentials - could be base64 encoded or plain
            val credentials: String
            if (credentialsPart.contains(":")) {
                // Plain format: method:password
                credentials = credentialsPart
            } else {
                // Base64 encoded format - URL decode first, then base64 decode
                try {
                    val urlDecoded = URLDecoder.decode(credentialsPart, "UTF-8")
                    credentials = String(Base64.decode(urlDecoded, Base64.NO_WRAP or Base64.URL_SAFE))
                    Log.d(TAG, "Decoded base64 credentials: $credentials")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode base64 SS URI credentials: ${e.message}", e)
                    return null
                }
            }
            
            // Parse method:password from credentials
            val colonIndex = credentials.indexOf(":")
            if (colonIndex == -1) {
                Log.e(TAG, "Invalid SS URI: missing : in credentials")
                return null
            }
            
            val method = credentials.substring(0, colonIndex)
            val password = credentials.substring(colonIndex + 1)
            
            Log.d(TAG, "Parsed SS URI - Method: $method, Server: $server, Port: $port")
            
            ShadowsocksConfig(
                method = method,
                password = password,
                server = server,
                port = port,
                name = name
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse SS URI: ${e.message}", e)
            null
        }
    }
    
    /**
     * Build SS URI from components
     * Format: ss://[method]:[password]@[server]:[port]#[name]
     */
    fun build(method: String, password: String, server: String, port: Int, name: String? = null): String {
        val credentials = "$method:$password"
        val address = "$server:$port"
        val uri = "ss://$credentials@$address"
        return if (name != null) {
            "$uri#$name"
        } else {
            uri
        }
    }
    
    /**
     * Build base64 encoded SS URI
     * Format: ss://base64([method]:[password]@[server]:[port])#[name]
     */
    fun buildBase64(method: String, password: String, server: String, port: Int, name: String? = null): String {
        val credentials = "$method:$password"
        val address = "$server:$port"
        val encoded = Base64.encodeToString("$credentials@$address".toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE)
        val uri = "ss://$encoded"
        return if (name != null) {
            "$uri#$name"
        } else {
            uri
        }
    }
    
    /**
     * Parse SS URI and convert to VpnConfig
     */
    fun parseToVpnConfig(uri: String): com.vpnsdk.model.VpnConfig? {
        val config = parse(uri)
        return config?.let {
            com.vpnsdk.model.VpnConfig(
                protocol = "shadowsocks",
                address = it.server,
                port = it.port,
                method = it.method,
                password = it.password,
                encryption = it.method
            )
        }
    }
    
    data class ShadowsocksConfig(
        val method: String,
        val password: String,
        val server: String,
        val port: Int,
        val name: String? = null
    )
}

