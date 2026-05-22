#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
LAUNCHER="$REPO_ROOT/launcher/app/src/main"

stage() {
    local src="$1" dst="$2"
    if [[ ! -f "$src" ]]; then
        echo "  MISS: $src" >&2
        return 1
    fi
    mkdir -p "$(dirname "$dst")"
    cp -v "$src" "$dst"
}

echo "=== libgl4es.so ==="
stage "$REPO_ROOT/out/gl4es/jniLibs/arm64-v8a/libgl4es.so" \
      "$LAUNCHER/jniLibs/arm64-v8a/libgl4es.so" || \
    echo "  (run ./scripts/build-gl4es-android.sh first)"

echo
echo "=== libopenal.so ==="
stage "$REPO_ROOT/out/openal/jniLibs/arm64-v8a/libopenal.so" \
      "$LAUNCHER/jniLibs/arm64-v8a/libopenal.so" || \
    echo "  (run ./scripts/build-openal-android.sh first)"

echo
echo "=== LWJGL 3.4.1 assets ==="
stage "$REPO_ROOT/out/lwjgl3/lwjgl-3.4.1-android-natives-arm64.zip" \
      "$LAUNCHER/assets/lwjgl/lwjgl-3.4.1-android-natives-arm64.zip" || \
    echo "  (run ./scripts/build-lwjgl3-android.sh first)"
stage "$REPO_ROOT/out/lwjgl3/lwjgl-3.4.1-android-modules.zip" \
      "$LAUNCHER/assets/lwjgl/lwjgl-3.4.1-android-modules.zip" || \
    echo "  (run ./scripts/build-lwjgl3-android.sh first)"

echo
echo "done."
