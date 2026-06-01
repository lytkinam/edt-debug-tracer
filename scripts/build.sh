#!/bin/bash
# Build edt-debug-tracer plugin without Eclipse PDE.
# Uses javac + jar with EDT plugin jars as classpath.
#
# Usage:
#   ./scripts/build.sh              # compile + package jar
#   ./scripts/build.sh --install    # + copy to EDT dropins
#   ./scripts/build.sh --clean      # remove build artifacts

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
PLUGIN_DIR="$PROJECT_DIR/plugin"
SRC_DIR="$PLUGIN_DIR/src/main/java"
BUILD_DIR="$PROJECT_DIR/build"
DIST_DIR="$PROJECT_DIR/dist"
LIB_DIR="$PLUGIN_DIR/lib"

# ── EDT paths (auto-detect) ──────────────────────────────────────────────
EDT_BASE=$(ls -d /opt/1C/1CE/components/1c-edt-*/ 2>/dev/null | head -1)
if [ -z "$EDT_BASE" ]; then
    echo "ERROR: EDT not found in /opt/1C/1CE/components/"
    exit 1
fi
JDK_DIR=$(ls -d /opt/1C/1CE/components/axiom-jdk-full-* 2>/dev/null | head -1)
JAVAC="${JDK_DIR}/bin/javac"
JAR="${JDK_DIR}/bin/jar"
EDT_PLUGINS="${EDT_BASE}plugins"
DROPINS_DIR="${EDT_BASE}dropins"

BUNDLE_NAME="com.tracer.edt.debugtracer"
BUNDLE_VERSION="1.0.0"
OUTPUT_JAR="${DIST_DIR}/${BUNDLE_NAME}_${BUNDLE_VERSION}.jar"

echo "=== edt-debug-tracer build ==="
echo "EDT:     $EDT_BASE"
echo "JDK:     $JDK_DIR"
echo "Plugin:  $PLUGIN_DIR"
echo ""

# ── Clean ────────────────────────────────────────────────────────────────
if [ "$1" = "--clean" ]; then
    echo "Cleaning..."
    rm -rf "$BUILD_DIR" "$DIST_DIR"
    echo "Done."
    exit 0
fi

# ── Build classpath ──────────────────────────────────────────────────────
CP=""
for j in \
    "$EDT_PLUGINS"/org.eclipse.debug.core_*.jar \
    "$EDT_PLUGINS"/org.eclipse.core.runtime_*.jar \
    "$EDT_PLUGINS"/org.eclipse.osgi_*.jar \
    "$EDT_PLUGINS"/org.eclipse.equinox.common_*.jar \
    "$EDT_PLUGINS"/org.eclipse.core.jobs_*.jar \
    "$EDT_PLUGINS"/org.eclipse.equinox.registry_*.jar \
    "$EDT_PLUGINS"/org.eclipse.equinox.preferences_*.jar \
    "$EDT_PLUGINS"/org.eclipse.core.contenttype_*.jar \
    "$EDT_PLUGINS"/org.eclipse.equinox.app_*.jar \
    "$LIB_DIR"/sqlite-jdbc.jar \
; do
    [ -f "$j" ] && CP="$CP:$j"
done
CP="${CP#:}"  # strip leading colon

# ── Compile ──────────────────────────────────────────────────────────────
echo "=== Compiling ==="
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/classes"

find "$SRC_DIR" -name '*.java' > "$BUILD_DIR/sources.txt"
SOURCE_COUNT=$(wc -l < "$BUILD_DIR/sources.txt")
echo "  Sources: $SOURCE_COUNT files"

"$JAVAC" --release 17 \
    -d "$BUILD_DIR/classes" \
    -cp "$CP" \
    @"$BUILD_DIR/sources.txt" 2>&1

CLASS_COUNT=$(find "$BUILD_DIR/classes" -name '*.class' | wc -l)
echo "  Compiled: $CLASS_COUNT class files"
echo ""

# ── Package ──────────────────────────────────────────────────────────────
echo "=== Packaging ==="
mkdir -p "$DIST_DIR"

# Copy manifest
mkdir -p "$BUILD_DIR/META-INF"
cp "$PLUGIN_DIR/META-INF/MANIFEST.MF" "$BUILD_DIR/META-INF/MANIFEST.MF"

# Include sqlite-jdbc in bundle classpath
cp "$LIB_DIR/sqlite-jdbc.jar" "$BUILD_DIR/classes/lib/sqlite-jdbc.jar" 2>/dev/null || {
    mkdir -p "$BUILD_DIR/classes/lib"
    cp "$LIB_DIR/sqlite-jdbc.jar" "$BUILD_DIR/classes/lib/sqlite-jdbc.jar"
}

cd "$BUILD_DIR/classes"

# Build jar with OSGi manifest
"$JAR" cfm "$OUTPUT_JAR" "$BUILD_DIR/META-INF/MANIFEST.MF" \
    com/ lib/

echo "  Output: $OUTPUT_JAR"
echo "  Size: $(du -h "$OUTPUT_JAR" | cut -f1)"
echo ""

# ── Install (optional) ───────────────────────────────────────────────────
if [ "$1" = "--install" ]; then
    echo "=== Installing ==="
    mkdir -p "$DROPINS_DIR"
    cp "$OUTPUT_JAR" "$DROPINS_DIR/"
    echo "  Installed to: $DROPINS_DIR/"
    echo "  Restart EDT to activate (without -clean flag)"
    echo ""
fi

echo "=== Done ==="
