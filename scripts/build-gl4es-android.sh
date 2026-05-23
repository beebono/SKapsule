#!/usr/bin/env bash

set -euo pipefail

# --- paths --------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
GL4ES_SRC="${GL4ES_SRC:-$REPO_ROOT/gl4es}"
OUT_DIR="${OUT_DIR:-$REPO_ROOT/out/gl4es}"
BUILD_ROOT="${BUILD_ROOT:-$REPO_ROOT/out/gl4es/build}"

# --- NDK discovery ------------------------------------------------------------
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    NDK_PARENT="${ANDROID_HOME:-$HOME/Android/Sdk}/ndk"
    if [[ -d "$NDK_PARENT" ]]; then
        # pick the highest version available
        ANDROID_NDK_HOME="$(ls -1 "$NDK_PARENT" | sort -V | tail -1)"
        ANDROID_NDK_HOME="$NDK_PARENT/$ANDROID_NDK_HOME"
    fi
fi
if [[ ! -d "${ANDROID_NDK_HOME:-}" ]]; then
    echo "ERROR: set ANDROID_NDK_HOME (or install an NDK under \$ANDROID_HOME/ndk)" >&2
    exit 1
fi

TOOLCHAIN="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake"
[[ -f "$TOOLCHAIN" ]] || { echo "ERROR: missing $TOOLCHAIN" >&2; exit 1; }

# --- config -------------------------------------------------------------------
ABIS=(${ABIS:-arm64-v8a})
ANDROID_PLATFORM="${ANDROID_PLATFORM:-android-21}"
BUILD_TYPE="${BUILD_TYPE:-Release}"
GL4ES_DEBUG="${GL4ES_DEBUG:-0}"

EXTRA_CMAKE_C_FLAGS=()
if [[ "$GL4ES_DEBUG" == "1" ]]; then
    EXTRA_CMAKE_C_FLAGS+=( -DCMAKE_C_FLAGS="-DDEBUG -DDEBUG_FEATURE -g -O0" )
    echo "GL4ES_DEBUG=1: enabling gl4es DBG() printf tracing"
fi

GENERATOR=("Unix Makefiles")
JOBS_FLAG="-j$(nproc)"
if command -v ninja >/dev/null 2>&1; then
    GENERATOR=(Ninja)
    JOBS_FLAG=""
fi

echo "gl4es source:  $GL4ES_SRC"
echo "NDK:           $ANDROID_NDK_HOME"
echo "ABIs:          ${ABIS[*]}"
echo "Platform:      $ANDROID_PLATFORM"
echo "Generator:     ${GENERATOR[*]}"
echo

[[ -d "$GL4ES_SRC" ]] || { echo "ERROR: gl4es source not found at $GL4ES_SRC" >&2; exit 1; }

# --- build per ABI ------------------------------------------------------------
for ABI in "${ABIS[@]}"; do
    BUILD_DIR="$BUILD_ROOT/$ABI"
    OUT_LIB_DIR="$OUT_DIR/jniLibs/$ABI"
    mkdir -p "$BUILD_DIR" "$OUT_LIB_DIR"

    echo "=== configuring $ABI ==="
    cmake -S "$GL4ES_SRC" -B "$BUILD_DIR" \
        -G "${GENERATOR[@]}" \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
        -DANDROID_ABI="$ABI" \
        -DANDROID_PLATFORM="$ANDROID_PLATFORM" \
        -DCMAKE_BUILD_TYPE="$BUILD_TYPE" \
        -DANDROID=ON \
        -DNOX11=ON \
        -DDEFAULT_ES=2 \
        -DSTATICLIB=OFF \
        -DNO_INIT_CONSTRUCTOR=ON \
        -DUSE_ANDROID_LOG=ON \
        -DGBM=OFF \
        -DCMAKE_PROJECT_gl4es_INCLUDE="$SCRIPT_DIR/gl4es-overrides.cmake" \
        "${EXTRA_CMAKE_C_FLAGS[@]}"

    echo "=== building $ABI ==="
    cmake --build "$BUILD_DIR" --config "$BUILD_TYPE" -- $JOBS_FLAG

    SRC_SO=""
    for cand in \
        "$GL4ES_SRC/lib/libGL.so" \
        "$GL4ES_SRC/lib/libGL.so.1" \
        "$BUILD_DIR/libGL.so" \
        "$BUILD_DIR/libGL.so.1"
    do
        [[ -e "$cand" ]] && SRC_SO="$(readlink -f "$cand")" && break
    done
    if [[ -z "$SRC_SO" || ! -f "$SRC_SO" ]]; then
        echo "ERROR: could not locate built libGL.so for $ABI" >&2
        find "$BUILD_DIR" "$GL4ES_SRC/lib" -maxdepth 3 -name 'libGL*' 2>/dev/null >&2
        exit 1
    fi

    cp -v "$SRC_SO" "$OUT_LIB_DIR/libgl4es.so"
    echo
done

echo "=== done ==="
echo "Outputs:"
for ABI in "${ABIS[@]}"; do
    ls -lh "$OUT_DIR/jniLibs/$ABI/libgl4es.so" 2>/dev/null || true
done
