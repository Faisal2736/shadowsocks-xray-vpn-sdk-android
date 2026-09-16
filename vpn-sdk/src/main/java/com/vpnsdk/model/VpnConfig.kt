package com.vpnsdk.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Protocol-agnostic server configuration consumed by [com.vpnsdk.VpnConnectionManager].
 *
 * This is the only shape the SDK needs to establish a tunnel — it deliberately carries no
 * account/subscription/backend fields, so any app can build one locally, from a pasted
 * `ss://` link (see [com.vpnsdk.core.ShadowsocksUri]), or from its own server-list API.
 */
@Parcelize
data class VpnConfig(
    val protocol: String, // "shadowsocks" | "vmess" | "vless" | "trojan"
    val address: String,
    val port: Int,
    val uuid: String? = null, // For VMess/VLESS
    val password: String? = null,
    val encryption: String = "auto",
    val method: String? = null, // For Shadowsocks, e.g. "aes-256-gcm"
    val network: String = "tcp", // "ws" | "tcp" | "kcp"
    val wsPath: String? = null,
    val host: String? = null,
    val tls: Boolean = false,
    val sni: String? = null,
    val flow: String? = null, // For VLESS
    val security: String? = null, // For VMess
    val displayName: String? = null
) : Parcelable
