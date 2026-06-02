#!/bin/bash
# edt-launch.sh — управление EDT 2025.1.5 (production)
#
# Использование:
#   ./scripts/edt-launch.sh start    — запустить EDT
#   ./scripts/edt-launch.sh stop     — остановить EDT
#   ./scripts/edt-launch.sh restart  — перезапустить EDT
#   ./scripts/edt-launch.sh status   — проверить статус
#   ./scripts/edt-launch.sh log      — показать последние логи

set -e

# === Конфигурация ===
EDT_VERSION="2025.1.5+34-x86_64"
EDT_DIR="${EDT_DIR:-/opt/1C/1CE/components/1c-edt-${EDT_VERSION}}"
WORKSPACE="${EDT_WORKSPACE:-/home/ai/workspace-edt2025}"
DISPLAY_NUM="${EDT_DISPLAY:-:1}"
XAUTHORITY="${XAUTHORITY:-/home/ai/.Xauthority}"
GTK_THEME="${GTK_THEME:-Adwaita}"
LOG_FILE="/tmp/edt-launch.log"
LOCK_FILE="$WORKSPACE/.metadata/.lock"

# === Проверка наличия EDT ===
if [ ! -f "$EDT_DIR/1cedt" ]; then
    echo "ERROR: EDT not found at $EDT_DIR/1cedt"
    exit 1
fi

# === Функции ===

get_pid() {
    pgrep -f "1cedt.*-data $WORKSPACE" 2>/dev/null | head -1
}

get_java_pid() {
    pgrep -f "java.*1cedt.*-data $WORKSPACE" 2>/dev/null | head -1
}

is_running() {
    local pid=$(get_pid)
    [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
}

cmd_start() {
    if is_running; then
        echo "EDT already running (PID: $(get_pid))"
        return 0
    fi

    # Clean lock file from previous crash
    if [ -f "$LOCK_FILE" ]; then
        echo "Removing stale lock file..."
        rm -f "$LOCK_FILE"
    fi

    echo "Starting EDT ${EDT_VERSION}..."
    echo "  Display:   $DISPLAY_NUM"
    echo "  Workspace: $WORKSPACE"
    echo "  Log:       $LOG_FILE"

    DISPLAY="$DISPLAY_NUM" \
    XAUTHORITY="$XAUTHORITY" \
    GTK_THEME="$GTK_THEME" \
    nohup "$EDT_DIR/1cedt" \
        -data "$WORKSPACE" \
        > "$LOG_FILE" 2>&1 &

    local pid=$!
    echo "  PID:       $pid"

    # Wait for startup
    echo -n "  Waiting for MCP (port 8765)"
    for i in $(seq 1 40); do
        sleep 3
        if curl -s -o /dev/null -w "%{http_code}" http://localhost:8765/mcp 2>/dev/null | grep -qv "000"; then
            echo ""
            echo "  codepilot1c MCP: http://localhost:8765/mcp ✓"
        fi
        if curl -s -o /dev/null http://localhost:18080/mcp/health 2>/dev/null; then
            echo "  tracer MCP:      http://localhost:18080/mcp/health ✓"
            echo "Started successfully."
            return 0
        fi
        echo -n "."
    done
    echo ""
    echo "  WARNING: Tracer MCP not responding after 120s."
    echo "  codepilot1c may be ready, check: curl -s http://localhost:8765/mcp"
    echo "  Tracer: curl http://localhost:18080/mcp/health"
}

cmd_stop() {
    if ! is_running; then
        echo "EDT is not running."
        return 0
    fi

    local pid=$(get_pid)
    local java_pid=$(get_java_pid)
    echo "Stopping EDT (PID: $pid, Java: $java_pid)..."

    # Kill launcher and java process
    kill "$pid" 2>/dev/null || true
    [ -n "$java_pid" ] && kill "$java_pid" 2>/dev/null || true

    # Wait up to 10 seconds
    for i in $(seq 1 10); do
        sleep 1
        if ! is_running; then
            echo "Stopped."
            rm -f "$LOCK_FILE" 2>/dev/null
            return 0
        fi
    done

    # Force kill
    echo "Force killing..."
    pkill -9 -f "1cedt.*-data $WORKSPACE" 2>/dev/null || true
    sleep 1
    rm -f "$LOCK_FILE" 2>/dev/null
    echo "Stopped (forced)."
}

cmd_restart() {
    cmd_stop
    sleep 2
    cmd_start
}

cmd_status() {
    echo "=== EDT ${EDT_VERSION} Status ==="
    echo "  Directory:  $EDT_DIR"
    echo "  Workspace:  $WORKSPACE"
    echo "  Display:    $DISPLAY_NUM"

    if is_running; then
        local pid=$(get_pid)
        local java_pid=$(get_java_pid)
        echo "  Launcher:   RUNNING (PID: $pid)"
        echo "  Java:       $([ -n "$java_pid" ] && echo "RUNNING (PID: $java_pid)" || echo "not found")"
        echo "  Uptime:     $(ps -o etime= -p "$pid" 2>/dev/null | tr -d ' ')"
    else
        echo "  Process:    STOPPED"
    fi

    echo ""
    echo "  codepilot1c MCP (port 8765):"
    local cp=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8765/mcp 2>/dev/null)
    if [ "$cp" != "000" ]; then
        echo "    HTTP $cp (JSON-RPC 2.0, 86 tools)"
    else
        echo "    not responding"
    fi

    echo ""
    echo "  Tracer MCP (port 18080):"
    local tracer=$(curl -s http://localhost:18080/mcp/health 2>/dev/null)
    if [ -n "$tracer" ]; then
        echo "    $tracer"
    else
        echo "    not responding"
    fi

    echo ""
    echo "  Lock file:"
    if [ -f "$LOCK_FILE" ]; then
        echo "    EXISTS (may indicate crash)"
    else
        echo "    clean"
    fi
}

cmd_log() {
    if [ -f "$LOG_FILE" ]; then
        echo "=== Last 30 lines of $LOG_FILE ==="
        tail -30 "$LOG_FILE"
    else
        echo "Log file not found: $LOG_FILE"
    fi

    echo ""
    echo "=== Tracer entries in EDT log ==="
    grep "\[tracer\]" "$WORKSPACE/.metadata/.log" 2>/dev/null | tail -10 || echo "  No tracer entries"

    echo ""
    echo "=== Last errors in EDT log ==="
    grep -i "error\|exception" "$WORKSPACE/.metadata/.log" 2>/dev/null | tail -5 || echo "  No errors"
}

# === Main ===

case "${1:-}" in
    start)   cmd_start   ;;
    stop)    cmd_stop    ;;
    restart) cmd_restart ;;
    status)  cmd_status  ;;
    log)     cmd_log     ;;
    *)
        echo "Usage: $0 {start|stop|restart|status|log}"
        echo ""
        echo "Commands:"
        echo "  start    — Start EDT on DISPLAY $DISPLAY_NUM"
        echo "  stop     — Stop EDT (graceful, then force)"
        echo "  restart  — Stop then start"
        echo "  status   — Show process, MCP ports, and lock file"
        echo "  log      — Show recent logs and errors"
        echo ""
        echo "Environment variables:"
        echo "  EDT_DIR         EDT installation (default: /opt/1C/1CE/components/1c-edt-${EDT_VERSION})"
        echo "  EDT_WORKSPACE   Workspace path (default: /home/ai/workspace-edt2025)"
        echo "  EDT_DISPLAY     X display (default: :1)"
        echo ""
        echo "IMPORTANT: Never restart EDT with -clean flag!"
        exit 1
        ;;
esac
