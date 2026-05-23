#!/usr/bin/env bash

# Builds caciocavallo (cacio-shared + cacio-tta) from the AngelAuraMC
# caciocavallo17 submodule (java-25 branch) into out/cacio/. These provide a
# non-X11 AWT toolkit (CTCToolkit/CTCGraphicsEnvironment) so SK's AWT calls
# (cursors, dialogs, clipboard) work headless on Android — see the AWT bridge
# notes. Output jars are pure-JVM (bytecode 17), staged onto SK's bootclasspath
# by stage-launcher-assets.sh.
#
# IMPORTANT: the java-25 branch resolves JDK-internal APIs against the building
# JDK (maven-compiler uses -XDignore.symbol.file=true), so it MUST be built with
# a JDK 25 to match the FCL-Team JRE 25 we ship. Gradle's JDK-21 pin does not
# apply here. Override the build JDK with CACIO_JAVA_HOME if needed.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CACIO_SRC="${CACIO_SRC:-$REPO_ROOT/caciocavallo17}"
OUT_DIR="${OUT_DIR:-$REPO_ROOT/out/cacio}"
CACIO_VERSION="1.18-SNAPSHOT"

[[ -f "$CACIO_SRC/pom.xml" ]] || {
    echo "ERROR: caciocavallo17 source not found at $CACIO_SRC" >&2
    echo "       Did you 'git submodule update --init caciocavallo17'?" >&2
    exit 1
}

# Pick the build JDK: CACIO_JAVA_HOME > JAVA_HOME > whatever 'mvn' defaults to.
if [[ -n "${CACIO_JAVA_HOME:-}" ]]; then
    export JAVA_HOME="$CACIO_JAVA_HOME"
fi
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
command -v mvn >/dev/null 2>&1 || { echo "ERROR: 'mvn' (Maven) not on PATH" >&2; exit 1; }

# Verify the build JDK is 25+; the java-25 branch adapts to JDK 25 internals
# (e.g. removed SurfaceManager) and won't compile/run correctly on older JDKs.
JAVA_MAJOR="$("$JAVA_BIN" -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')"
if [[ -z "$JAVA_MAJOR" || "$JAVA_MAJOR" -lt 25 ]]; then
    echo "ERROR: cacio java-25 branch needs JDK 25 to build (found '${JAVA_MAJOR:-?}')." >&2
    echo "       Point CACIO_JAVA_HOME at a JDK 25 (the running JRE we ship is 25)." >&2
    exit 1
fi
echo "Building cacio with JDK $JAVA_MAJOR (${JAVA_HOME:-system default})"

# Build both modules. Skip tests (they spin up the toolkit + Robot) and the
# source/javadoc jars (the parent pom attaches them on 'package'; javadoc over
# internal sun.* APIs is slow and brittle and we don't need it).
( cd "$CACIO_SRC" && mvn -B -DskipTests \
    -Dmaven.javadoc.skip=true -Dmaven.source.skip=true \
    package )

mkdir -p "$OUT_DIR"
for mod in cacio-shared cacio-tta; do
    jar="$CACIO_SRC/$mod/target/$mod-$CACIO_VERSION.jar"
    [[ -f "$jar" ]] || { echo "ERROR: expected build output missing: $jar" >&2; exit 1; }
    # Stable unversioned names so staging/runtime paths don't track the version.
    cp -v "$jar" "$OUT_DIR/$mod.jar"
done

echo
echo "=== done ==="
echo "Outputs:"
ls -lh "$OUT_DIR/cacio-shared.jar" "$OUT_DIR/cacio-tta.jar"
