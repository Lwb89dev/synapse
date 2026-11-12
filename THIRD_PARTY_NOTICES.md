# Third-party notices

Synapse itself is MIT-licensed (see `LICENSE`). It builds on the following; none of this code is modified.

## Embedded Tailscale node (`tailnet/`)

The desktop sidecar `synapse-net` and the Android library `libsynapsenet.so` are built from
[`tailscale.com`](https://github.com/tailscale/tailscale) (tsnet), pinned in `tailnet/go.mod` / `go.sum`, together with
the Go standard library and ~600 other Go modules that `go list -m all` reports for that module graph (their licenses
are in their own repositories; most are BSD-3-Clause, MIT or Apache-2.0). Run
[`go-licenses`](https://github.com/google/go-licenses) in `tailnet/` for the complete list.
Synapse is an independent project: it is not affiliated with or endorsed by Tailscale Inc.

Tailscale's license:

    BSD 3-Clause License
    
    Copyright (c) 2020 Tailscale Inc & contributors.
    
    Redistribution and use in source and binary forms, with or without
    modification, are permitted provided that the following conditions are met:
    
    1. Redistributions of source code must retain the above copyright notice, this
       list of conditions and the following disclaimer.
    
    2. Redistributions in binary form must reproduce the above copyright notice,
       this list of conditions and the following disclaimer in the documentation
       and/or other materials provided with the distribution.
    
    3. Neither the name of the copyright holder nor the names of its
       contributors may be used to endorse or promote products derived from
       this software without specific prior written permission.
    
    THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
    AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
    IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
    DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
    FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
    DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
    SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
    CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
    OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
    OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

## Android app (`android/`)

Apache License 2.0, all unmodified: AndroidX and Jetpack Compose, Kotlin and kotlinx (coroutines, serialization),
OkHttp and Okio, ZXing and `zxing-android-embedded`. See each project's repository for the license text.

## Artwork

`assets/icons/` holds the Synapse app icon and the notification icon, supplied by the repository owner.
