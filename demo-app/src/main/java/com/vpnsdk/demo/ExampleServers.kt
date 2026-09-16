package com.vpnsdk.demo

import com.vpnsdk.model.VpnConfig

/**
 * Local, static example configuration for this demo — deliberately not fetched from any
 * server-list API or backend. Swap [placeholder] for your own Shadowsocks/Xray server
 * details to see a real connection.
 */
object ExampleServers {

    /**
     * A syntactically valid but non-functional placeholder. `example.com` will not accept a
     * real Shadowsocks handshake, so connecting with this config is expected to surface the
     * SDK's error-handling path rather than a live tunnel.
     */
    val placeholder = VpnConfig(
        protocol = "shadowsocks",
        address = "example.com",
        port = 8388,
        method = "chacha20-ietf-poly1305",
        password = "replace-with-your-server-password",
        displayName = "Example Server (replace me)"
    )
}
