#!/usr/bin/env bash
# Builds libsynapsenet.so (embedded tailnet node + JNI bridge) for the Android app, arm64 only.
# Needs Go (>= the go.mod version; `mise exec go@latest -- ./build-android.sh` works) and the Android NDK.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
ndk="${ANDROID_NDK_HOME:-$(ls -d "$sdk"/ndk/* 2>/dev/null | sort -V | head -1)}"
[[ -d "$ndk" ]] || { echo "Android NDK not found (install it from the SDK manager)" >&2; exit 1; }
api=29
cc="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android${api}-clang"
out="$here/../android/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$out"
cd "$here"
# Pixel 10 and other 16 kB-page devices refuse (or warn about) 4 kB-aligned ELF segments
GOOS=android GOARCH=arm64 CGO_ENABLED=1 CC="$cc" \
  go build -buildmode=c-shared -trimpath -ldflags="-s -w '-extldflags=-Wl,-z,max-page-size=16384'" -o "$out/libsynapsenet.so" ./jni
rm -f "$out/libsynapsenet.h"
ls -la "$out/libsynapsenet.so"
