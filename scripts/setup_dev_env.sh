#!/bin/bash
# setup_dev_env.sh - prepares Eclipse PDE development environment for EDT Debug Tracer

set -e

EDT_VERSION="2026.1"
EDT_INSTALL_DIR="${EDT_INSTALL_DIR:-$HOME/edt}"
DB_DIR="$HOME/.edt-debug-tracer"
LIB_DIR="$(dirname "$0")/../plugin/lib"
LIB_TESTS_DIR="$(dirname "$0")/../plugin.tests/lib"
SQLITE_JDBC_URL="https://github.com/xerial/sqlite-jdbc/releases/download/3.53.1.0/sqlite-jdbc-3.53.1.0.jar"
SQLITE_JAR="$LIB_DIR/sqlite-jdbc.jar"

echo "=== EDT Debug Tracer: Dev Environment Setup ==="

# 1. Create DB directory
mkdir -p "$DB_DIR"
echo "[OK] DB dir: $DB_DIR"

# 2. Download sqlite-jdbc
mkdir -p "$LIB_DIR" "$LIB_TESTS_DIR"
if [ ! -f "$SQLITE_JAR" ]; then
  echo "Downloading sqlite-jdbc..."
  curl -L "$SQLITE_JDBC_URL" -o "$SQLITE_JAR"
  echo "[OK] Downloaded sqlite-jdbc.jar"
else
  echo "[SKIP] sqlite-jdbc.jar already exists"
fi
cp "$SQLITE_JAR" "$LIB_TESTS_DIR/sqlite-jdbc.jar"
echo "[OK] Copied to plugin.tests/lib/"

# 3. Check Java
if ! java -version 2>&1 | grep -q '17\|21\|22\|23'; then
  echo "[WARN] Java 17+ recommended for EDT 2026.1"
else
  echo "[OK] Java OK"
fi

echo ""
echo "Next steps:"
echo "  1. Open Eclipse IDE for RCP Developers"
echo "  2. Window -> Preferences -> Plug-in Development -> Target Platform"
echo "  3. Add $EDT_INSTALL_DIR/plugins as Target Platform location"
echo "  4. Import plugin/ and plugin.tests/ as Plug-in Projects"
echo "  5. Project -> Clean -> Build All"
echo "  6. Run As -> JUnit Plug-in Test (plugin.tests)"
