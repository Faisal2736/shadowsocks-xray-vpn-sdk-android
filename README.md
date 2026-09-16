# Shadowsocks/Xray VPN SDK for Android

A reusable Android **library module** for establishing client-side VPN tunnels over
Shadowsocks and Xray-core (VMess/VLESS/Trojan/Shadowsocks), plus a minimal **demo app**
that shows the complete connect → connected → disconnect flow end to end.

This repository is a **public engineering portfolio project**. It demonstrates how a
mobile VPN client integrates with `android.net.VpnService`, drives a local Xray-core
process, and bridges the TUN interface to it — packaged as an SDK another app can depend
on. It is **not** the production VPN application referenced in the author's background
(see [Portfolio positioning](#portfolio-positioning) below).

## Repository structure

```
shadowsocks-xray-vpn-sdk-android/
├── vpn-sdk/     — the reusable library (com.android.library)
└── demo-app/    — a single-screen app that consumes vpn-sdk (com.android.application)
```

## `vpn-sdk`: what it does

- **`VpnConnectionManager`** — the public facade. Three things a host app needs:
  `prepare(activity)` to request VPN permission, `connect(config)` / `disconnect()`,
  and a `connectionState: StateFlow<VpnConnectionState>` to observe.
- **`VpnConfig`** — a protocol-agnostic value object (`protocol`, `address`, `port`,
  `method`/`password` for Shadowsocks, `uuid`/`flow` for VLESS, etc.). Build one
  yourself, parse it from a pasted `ss://` link with `ShadowsocksUri.parseToVpnConfig`,
  or map it from your own server-list API — the SDK has no opinion on where it comes
  from.
- **`VpnService`** — the actual `android.net.VpnService` subclass. Establishes the TUN
  interface, launches `XrayCore` (which generates an Xray `config.json` for whichever
  protocol was requested and runs the bundled `xray` binary as a local SOCKS proxy), and
  bridges TUN traffic to it via `Tun2Socks` (native `tun2socks`, via a Unix domain socket
  FD hand-off — the same pattern production Shadowsocks Android clients use).
- **`ShadowsocksCore`** / `ShadowsocksEncryption` / `ShadowsocksProtocol` — a from-scratch,
  readable reference implementation of Shadowsocks AEAD (AES-GCM, ChaCha20-Poly1305) and
  the SOCKS5-style address encoding, kept in the repo as a teaching artifact even though
  the default runtime path uses Xray-core.

### Quick integration

```kotlin
val vpn = VpnConnectionManager(context)

// 1. Request VPN permission
vpn.prepare(activity)?.let { intent ->
    permissionLauncher.launch(intent)
    return
}

// 2. Connect
val config = VpnConfig(
    protocol = "shadowsocks",
    address = "your-server.example.com",
    port = 8388,
    method = "chacha20-ietf-poly1305",
    password = "your-password"
)
vpn.connect(config)

// 3. Observe state
lifecycleScope.launch {
    vpn.connectionState.collect { state ->
        when (state) {
            is VpnConnectionState.Connecting -> ...
            is VpnConnectionState.Connected -> ...   // bytesReceived, bytesSent, connectedSinceMillis
            is VpnConnectionState.Error -> ...        // state.message
            else -> ...
        }
    }
}

// 4. Disconnect
vpn.disconnect()
```

## `demo-app`: what it shows

A single screen: a pre-filled example server form (address/port/cipher/password),
Connect/Disconnect, live state label, connection timer, and byte counters. **No backend,
no account system, no server-list API** — the example config is a local, static,
obviously-placeholder value (`example.com:8388`) defined in
[`ExampleServers.kt`](demo-app/src/main/java/com/vpnsdk/demo/ExampleServers.kt). Swap it
for a real Shadowsocks/Xray server you control to see a live tunnel; left as-is, it
demonstrates the SDK's error-handling path (the placeholder host won't complete a real
handshake).

### Running it

```bash
./gradlew :demo-app:installDebug
```

Grant the VPN permission prompt, tap Connect. To see a real connection, edit the fields
in-app (or `ExampleServers.kt`) to point at your own server.

## Requirements

- Android Studio, JDK 17+, Android SDK (API 24+ / target 36)
- `vpn-sdk` bundles native binaries for `arm64-v8a` and `x86_64` only (Xray upstream's
  current Android release ABIs)

## Native binaries and licensing

`vpn-sdk` bundles prebuilt native binaries (`libxray.so`, `libtun2socks.so`, and Xray
release assets) needed to run a real tunnel. Their upstream origin was confirmed by
inspecting embedded strings in the binaries themselves: `libxray.so` builds from
[XTLS/Xray-core](https://github.com/XTLS/Xray-core) (MPL-2.0), and `libtun2socks.so`
builds from BadVPN's `tun2socks` as vendored by
[shadowsocks-android](https://github.com/shadowsocks/shadowsocks-android) (BSD-3-Clause,
not the GPL-3.0 that covers shadowsocks-android's own code). Both are permissively usable
here without imposing copyleft terms on this repository's own MIT-licensed source — full
detail and verbatim license text in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
The Kotlin/Java source in this repo is original, written for this project, and licensed
under [`LICENSE`](LICENSE); it does not itself cover the bundled binaries.

Design references (no code copied): [shadowsocks-android](https://github.com/shadowsocks/shadowsocks-android),
[Xray-core](https://github.com/XTLS/Xray-core), [Android VpnService docs](https://developer.android.com/reference/android/net/VpnService).

## Portfolio positioning

This repository is a technical demonstration built to showcase VPN/mobile engineering
skills: `VpnService` internals, Shadowsocks/Xray protocol handling, native-process
bridging, and packaging a connection engine as a reusable SDK. It is written and
maintained independently of, and does not represent, the author's production VPN
application (which has reached 5M+ users) — no code, credentials, or infrastructure from
that production system are included here. Architectural patterns are informed by that
production experience; this demo does not serve production traffic and should not be
treated as one.

## Further reading

A companion article — what a VPN is, how Shadowsocks/Xray work, and a walkthrough of how
this SDK and demo fit together — is in [`docs/medium-article.md`](docs/medium-article.md).

## License

Source code in this repository is licensed under the terms in [`LICENSE`](LICENSE).
Bundled native binaries are covered separately — see
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
