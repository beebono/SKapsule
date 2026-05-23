#!/usr/bin/env bash

# Builds frenchpress (the com.threerings.froth.* Steam-login shim) from the
# frenchpress submodule into out/frenchpress/. It re-implements froth-foamy on top
# of JavaSteam and is placed BEFORE projectx-pcode.jar on SK's classpath at runtime
# so its classes shadow froth-foamy's — see the Steam-login notes. Output is a single
# shaded ("fat") jar bundling JavaSteam + BouncyCastle + Kotlin/ktor deps (~35M);
# staged onto SK's classpath by stage-launcher-assets.sh.
#
# IMPORTANT: the pom sets maven.compiler.release=25, so it MUST be built with a JDK 25
# to match the JRE 25 we ship. Gradle's JDK-21 pin does not apply here. Override the
# build JDK with FRENCHPRESS_JAVA_HOME if needed.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
FRENCHPRESS_SRC="${FRENCHPRESS_SRC:-$REPO_ROOT/frenchpress}"
OUT_DIR="${OUT_DIR:-$REPO_ROOT/out/frenchpress}"
FRENCHPRESS_VERSION="0.1-SNAPSHOT"

[[ -f "$FRENCHPRESS_SRC/pom.xml" ]] || {
    echo "ERROR: frenchpress source not found at $FRENCHPRESS_SRC" >&2
    echo "       Did you 'git submodule update --init frenchpress'?" >&2
    exit 1
}

if [[ -n "${FRENCHPRESS_JAVA_HOME:-}" ]]; then
    export JAVA_HOME="$FRENCHPRESS_JAVA_HOME"
fi
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
command -v mvn >/dev/null 2>&1 || { echo "ERROR: 'mvn' (Maven) not on PATH" >&2; exit 1; }

# Verify the build JDK is 25+ since the pom compiles with --release 25.
JAVA_MAJOR="$("$JAVA_BIN" -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')"
if [[ -z "$JAVA_MAJOR" || "$JAVA_MAJOR" -lt 25 ]]; then
    echo "ERROR: frenchpress needs JDK 25 to build (found '${JAVA_MAJOR:-?}')." >&2
    echo "       Point FRENCHPRESS_JAVA_HOME at a JDK 25 (the running JRE we ship is 25)." >&2
    exit 1
fi
echo "Building frenchpress with JDK $JAVA_MAJOR (${JAVA_HOME:-system default})"

( cd "$FRENCHPRESS_SRC" && mvn -B -DskipTests package )

mkdir -p "$OUT_DIR"
jar="$FRENCHPRESS_SRC/target/frenchpress-$FRENCHPRESS_VERSION.jar"
[[ -f "$jar" ]] || { echo "ERROR: expected build output missing: $jar" >&2; exit 1; }

cp -v "$jar" "$OUT_DIR/frenchpress.jar"

echo
echo "=== done ==="
echo "Outputs:"
ls -lh "$OUT_DIR/frenchpress.jar"
