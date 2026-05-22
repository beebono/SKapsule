#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
OPENAL_SRC="${OPENAL_SRC:-$REPO_ROOT/openal-soft}"
OUT_DIR="${OUT_DIR:-$REPO_ROOT/out/openal}"
BUILD_ROOT="${BUILD_ROOT:-$REPO_ROOT/out/openal/build}"

# --- NDK discovery ------------------------------------------------------------
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    NDK_PARENT="${ANDROID_HOME:-$HOME/Android/Sdk}/ndk"
    if [[ -d "$NDK_PARENT" ]]; then
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

GENERATOR=("Unix Makefiles")
JOBS_FLAG="-j$(nproc)"
if command -v ninja >/dev/null 2>&1; then
    GENERATOR=(Ninja)
    JOBS_FLAG=""
fi

echo "openal-soft source: $OPENAL_SRC"
echo "NDK:                $ANDROID_NDK_HOME"
echo "ABIs:               ${ABIS[*]}"
echo "Platform:           $ANDROID_PLATFORM"
echo "Generator:          ${GENERATOR[*]}"
echo

[[ -d "$OPENAL_SRC" ]] || { echo "ERROR: openal-soft source not found at $OPENAL_SRC" >&2; exit 1; }

# --- build per ABI ------------------------------------------------------------
for ABI in "${ABIS[@]}"; do
    BUILD_DIR="$BUILD_ROOT/$ABI"
    OUT_LIB_DIR="$OUT_DIR/jniLibs/$ABI"
    rm -rf "$BUILD_DIR"
    mkdir -p "$BUILD_DIR" "$OUT_LIB_DIR"

    echo "=== configuring $ABI ==="
    cmake -S "$OPENAL_SRC" -B "$BUILD_DIR" \
        -G "${GENERATOR[@]}" \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
        -DANDROID_ABI="$ABI" \
        -DANDROID_PLATFORM="$ANDROID_PLATFORM" \
        -DANDROID_STL=c++_shared \
        -DCMAKE_BUILD_TYPE="$BUILD_TYPE" \
        -DLIBTYPE=SHARED \
        -DALSOFT_UTILS=OFF \
        -DALSOFT_EXAMPLES=OFF \
        -DALSOFT_TESTS=OFF \
        -DALSOFT_INSTALL=OFF \
        -DALSOFT_NO_CONFIG_UTIL=ON \
        -DALSOFT_EAX=OFF \
        -DALSOFT_BACKEND_OPENSL=ON \
        -DALSOFT_BACKEND_WAVE=OFF \
        -DALSOFT_UPDATE_BUILD_VERSION=OFF

    echo "=== building $ABI ==="
    cmake --build "$BUILD_DIR" --config "$BUILD_TYPE" --target OpenAL -- $JOBS_FLAG

    # OpenAL Soft writes libopenal.so to <build>/libopenal.so on Linux/Android.
    SRC_SO=""
    for cand in "$BUILD_DIR/libopenal.so" "$BUILD_DIR/lib/libopenal.so"; do
        [[ -e "$cand" ]] && SRC_SO="$(readlink -f "$cand")" && break
    done
    if [[ -z "$SRC_SO" || ! -f "$SRC_SO" ]]; then
        echo "ERROR: could not locate built libopenal.so for $ABI" >&2
        find "$BUILD_DIR" -maxdepth 2 -name 'libopenal*' 2>/dev/null >&2
        exit 1
    fi

    cp -v "$SRC_SO" "$OUT_LIB_DIR/libopenal.so"

    STRIP="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
    if [[ -x "$STRIP" ]]; then
        "$STRIP" --strip-unneeded "$OUT_LIB_DIR/libopenal.so"
    fi

    if command -v readelf >/dev/null 2>&1; then
        soname=$(readelf -d "$OUT_LIB_DIR/libopenal.so" | awk '/SONAME/ {gsub(/[\[\]]/,"",$NF); print $NF}')
        echo "  SONAME: $soname"
    fi
    echo
done

echo "=== done ==="
for ABI in "${ABIS[@]}"; do
    ls -l "$OUT_DIR/jniLibs/$ABI/libopenal.so" 2>/dev/null || true
done
