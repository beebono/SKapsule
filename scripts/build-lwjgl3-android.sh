#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
LWJGL_SRC="${LWJGL_SRC:-$REPO_ROOT/lwjgl3}"
OUT_DIR="${OUT_DIR:-$REPO_ROOT/out/lwjgl3}"

ARCH="${LWJGL_BUILD_ARCH:-arm64}"
case "$ARCH" in
    arm64) ABI=arm64-v8a ;;
    arm32) ABI=armeabi-v7a ;;
    x86)   ABI=x86 ;;
    x64)   ABI=x86_64 ;;
    *) echo "ERROR: unsupported LWJGL_BUILD_ARCH=$ARCH" >&2; exit 1 ;;
esac

[[ -d "$LWJGL_SRC" ]] || { echo "ERROR: lwjgl3 source not found at $LWJGL_SRC" >&2; exit 1; }
[[ -f "$LWJGL_SRC/ci_build_android.bash" ]] || { echo "ERROR: $LWJGL_SRC/ci_build_android.bash missing" >&2; exit 1; }

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    NDK_PARENT="${ANDROID_HOME:-$HOME/Android/Sdk}/ndk"
    if [[ -d "$NDK_PARENT" ]]; then
        latest="$(ls -1 "$NDK_PARENT" | sort -V | tail -1)"
        export ANDROID_NDK_HOME="$NDK_PARENT/$latest"
    fi
fi
[[ -d "${ANDROID_NDK_HOME:-}" ]] || { echo "ERROR: set ANDROID_NDK_HOME" >&2; exit 1; }

# LWJGL auto-detects the running JDK (via <detectJDKVersion/> in
# config/build-definitions.xml) and sets the `jdk11` property whenever
# Java >= 11. The release target then compiles a Java 8 base layer for
# the multi-release jar, which uses `--boot-class-path "$JAVA8_HOME/jre/lib/rt.jar"`.
# That path only exists in an actual JDK 8 (JDK 9+ removed rt.jar), so a
# real JDK 8 is required even when the rest of the build runs on a newer
# JDK. Point JAVA8_HOME at one (e.g. /usr/lib/jvm/java-8-openjdk-amd64).
if [[ -z "${JAVA8_HOME:-}" || ! -f "${JAVA8_HOME:-}/jre/lib/rt.jar" ]]; then
    echo "ERROR: JAVA8_HOME must point at a JDK 8 install (with jre/lib/rt.jar)." >&2
    echo "       LWJGL's release target boot-classpaths against rt.jar to build" >&2
    echo "       the Java 8 layer of its multi-release jars." >&2
    exit 1
fi

echo "lwjgl3 source:     $LWJGL_SRC"
echo "NDK:               $ANDROID_NDK_HOME"
echo "Arch / ABI:        $ARCH / $ABI"
echo "Output dir:        $OUT_DIR"
echo

# Drop the -Djdk25=true line from a working copy of ci_build_android.bash
# without mutating the submodule. We run the patched script from inside
# $LWJGL_SRC so its relative paths (bin/, libffi/, ...) still resolve.
PATCHED="$LWJGL_SRC/ci_build_android.skarm.bash"
trap 'rm -f "$PATCHED"' EXIT
sed '/-Djdk25=true \\$/d' "$LWJGL_SRC/ci_build_android.bash" > "$PATCHED"
chmod +x "$PATCHED"

if ! grep -q 'jdk25' "$PATCHED"; then
    echo "patched ci_build_android: -Djdk25 omitted"
else
    echo "ERROR: failed to strip -Djdk25=true from ci_build_android.bash" >&2
    exit 1
fi

echo
echo "=== running LWJGL Android build ==="
( cd "$LWJGL_SRC" && LWJGL_BUILD_ARCH="$ARCH" bash "$PATCHED" )

# --- package natives ----------------------------------------------------------
NATIVES_SRC="$LWJGL_SRC/bin/out"
RELEASE_SRC="$LWJGL_SRC/bin/RELEASE"
mkdir -p "$OUT_DIR"

NATIVES_ZIP="$OUT_DIR/lwjgl-3.4.1-android-natives-$ARCH.zip"
MODULES_ZIP="$OUT_DIR/lwjgl-3.4.1-android-modules.zip"

echo
echo "=== packaging natives -> $NATIVES_ZIP ==="
[[ -d "$NATIVES_SRC" ]] || { echo "ERROR: $NATIVES_SRC missing (build did not produce natives)" >&2; exit 1; }
rm -f "$NATIVES_ZIP"
( cd "$NATIVES_SRC" && zip -j "$NATIVES_ZIP" ./*.so )

echo
echo "=== packaging modules -> $MODULES_ZIP ==="
[[ -d "$RELEASE_SRC" ]] || { echo "ERROR: $RELEASE_SRC missing (ant release did not run)" >&2; exit 1; }
rm -f "$MODULES_ZIP"
# Mirror the upstream zip layout: <module-dir>/<file>, no top-level prefix.
( cd "$RELEASE_SRC" && zip -r "$MODULES_ZIP" . \
    -i '*.jar' '*license*' 'LICENSE' 'build.txt' \
    -x '*-natives-*' '*-sources.jar' )

echo
echo "=== done ==="
ls -lh "$NATIVES_ZIP" "$MODULES_ZIP"
echo
echo "Next: ./scripts/stage-launcher-assets.sh   # copies into launcher assets/"
