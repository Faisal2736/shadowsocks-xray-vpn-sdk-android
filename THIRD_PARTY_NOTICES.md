# Third-Party Notices

This repository's Kotlin/Java source code is original and licensed under
[`LICENSE`](LICENSE) (MIT). It bundles the following **prebuilt native binaries**, which
are third-party material under their own upstream licenses, identified below by
inspecting embedded strings/build paths in the binaries themselves (`strings` on each
`.so`).

## `libxray.so`, `xray_arm64-v8a`, `xray_x86_64`

- **Path**: `vpn-sdk/src/main/jniLibs/{arm64-v8a,x86_64}/libxray.so`,
  `vpn-sdk/src/main/assets/xray_arm64-v8a`, `vpn-sdk/src/main/assets/xray_x86_64`
- **Upstream**: [XTLS/Xray-core](https://github.com/XTLS/Xray-core) — confirmed via
  embedded Go package paths (`github.com/xtls/xray-core/...`) found in the binary.
- **License**: **Mozilla Public License 2.0 (MPL-2.0)**.
- **Why this is safe to bundle**: this repository distributes the compiled Xray-core
  binary **unmodified**, run as a separate subprocess (not statically linked into this
  project's own code). MPL-2.0 is a file-level copyleft license: it requires source
  availability for *modified MPL-licensed files themselves*, but explicitly permits
  combining MPL code with differently-licensed code in a "Larger Work" (MPL-2.0 §3.3)
  without imposing MPL terms on that surrounding code. No Xray-core source was modified
  here, so this repository's own MIT license is unaffected.
- Full license text: <https://github.com/XTLS/Xray-core/blob/main/LICENSE>

## `libtun2socks.so`

- **Path**: `vpn-sdk/src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86,x86_64}/libtun2socks.so`
- **Upstream**: **BadVPN's `tun2socks`**, by Ambroz Bizjak — confirmed via an embedded
  debug build path literally present in the binary's strings:
  `/Volumes/DATA/workspace/shadowsocks-android/core/src/main/jni/badvpn/tun2socks/tun2socks.c`,
  plus the runtime banner string `BadVPN tun2socks 1.999.130`. This shows the binary was
  built from the `badvpn` subdirectory vendored inside
  [shadowsocks/shadowsocks-android](https://github.com/shadowsocks/shadowsocks-android),
  which is itself a copy of upstream [BadVPN](https://github.com/ambrop72/badvpn)
  (mirrored at [shadowsocks/badvpn](https://github.com/shadowsocks/badvpn)).
- **License**: **BSD 3-Clause**, copyright Ambroz Bizjak — **not** GPL. This resolves the
  copyleft concern originally raised here: although shadowsocks-android's *own*
  Kotlin/Java code is GPL-3.0-licensed, the `badvpn/` subdirectory it vendors keeps
  BadVPN's original permissive license, and this repository only bundles a binary built
  from that vendored BSD-licensed `tun2socks.c` — none of shadowsocks-android's own
  GPL-licensed source is compiled into this binary or present in this repository.
- Verbatim upstream license header (from `tun2socks.c`):

  ```
  Copyright (C) Ambroz Bizjak <ambrop7@gmail.com>
  Contributions:
  Transparent DNS: Copyright (C) Kerem Hadimli <kerem.hadimli@gmail.com>

  Redistribution and use in source and binary forms, with or without
  modification, are permitted provided that the following conditions are met:
  1. Redistributions of source code must retain the above copyright
     notice, this list of conditions and the following disclaimer.
  2. Redistributions in binary form must reproduce the above copyright
     notice, this list of conditions and the following disclaimer in the
     documentation and/or other materials provided with the distribution.
  3. Neither the name of the author nor the
     names of its contributors may be used to endorse or promote products
     derived from this software without specific prior written permission.

  THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
  ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
  WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
  DISCLAIMED. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY
  DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
  (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
  LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
  ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
  (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
  SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
  ```

## Status

**Resolved.** Both bundled native binaries are permissively licensed for this repo's
purposes (MPL-2.0 for the unmodified Xray-core subprocess binary; BSD-3-Clause for the
tun2socks binary). Neither imposes copyleft obligations on this repository's own MIT-
licensed source. This repository is not distributing modified versions of either
upstream project, and does not itself need to be GPL-licensed.

If you rebuild or modify either binary from source in the future, re-check this section:
modifying Xray-core source directly would put you back under MPL-2.0's file-level
share-alike requirement for those specific files.

## Design references

No source code was copied from these projects into this repository's own Kotlin/Java
files, but their public architecture and protocol handling informed this SDK's design:

- [shadowsocks-android](https://github.com/shadowsocks/shadowsocks-android) — GPL-3.0
  (its own code; not bundled here — see above for the vendored BSD `badvpn` exception)
- [Xray-core](https://github.com/XTLS/Xray-core) — MPL-2.0
- [Android `VpnService` documentation](https://developer.android.com/reference/android/net/VpnService)

`vpn-sdk` also depends on [BouncyCastle](https://www.bouncycastle.org/) (`bcprov-jdk15on`)
for ChaCha20-Poly1305, distributed under the
[Bouncy Castle License](https://www.bouncycastle.org/licence.html) (an MIT-style
license) — permissive and compatible, no action needed.
