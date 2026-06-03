#!/bin/bash
# eclipse-debug-launch.sh — управление Eclipse 2026-03 (debug/test instance)
#
# Третий Eclipse instance для тестирования UI плагинов.
# Свой workspace, свой порт трейсера (18040), JDWP отладка (порт 5005).
#
# Использование:
#   ./scripts/eclipse-debug-launch.sh start    — запустить debug Eclipse
#   ./scripts/eclipse-debug-launch.sh stop     — остановить
#   ./scripts/eclipse-debug-launch.sh restart  — перезапустить
#   ./scripts/eclipse-debug-launch.sh status   — проверить статус
#   ./scripts/eclipse-debug-launch.sh log      — показать последние логи

set -e

# === Конфигурация ===
ECLIPSE_DIR="${ECLIPSE_DIR:-/opt/eclipse-latest}"
WORKSPACE="${ECLIPSE_DEBUG_WORKSPACE:-/home/ai/workspace-eclipse-debug}"
DISPLAY_NUM="${ECLIPSE_DISPLAY:-:1}"
XAUTHORITY="/home/ai/.Xauthority"
GTK_THEME="${GTK_THEME:-Adwaita}"
LOG_FILE="/tmp/eclipse-debug.log"
SERVICE_NAME="eclipse-debug"
TRACER_PORT=18040
JDWP_PORT=5005

# === Проверка наличия Eclipse ===
if [ ! -f "$ECLIPSE_DIR/eclipse" ]; then
    echo "ERROR: Eclipse not found at $ECLIPSE_DIR/eclipse"
    exit 1
fi

# === Функции ===

get_pid() {
    pgrep -f "eclipse -data $WORKSPACE" 2>/dev/null | head -1
}

is_running() {
    local pid=$(get_pid)
    [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
}

cmd_start() {
    if is_running; then
        echo "Debug Eclipse already running (PID: $(get_pid))"
        return 0
    fi

    echo "Starting Eclipse 2026-03 (debug instance)..."
    echo "  Display:   $DISPLAY_NUM"
    echo "  Workspace: $WORKSPACE"
    echo "  Tracer:    port $TRACER_PORT"
    echo "  JDWP:      port $JDWP_PORT"
    echo "  Log:       $LOG_FILE"

    DISPLAY="$DISPLAY_NUM" \
    XAUTHORITY="$XAUTHORITY" \
    GTK_THEME="$GTK_THEME" \
    nohup "$ECLIPSE_DIR/eclipse" \
        -data "$WORKSPACE" \
        -vmargs \
        -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=$JDWP_PORT \
        > "$LOG_FILE" 2>&1 &

    local pid=$!
    echo "  PID:       $pid"

    # Wait for startup
    echo -n "  Waiting for MCP (port $TRACER_PORT)"
    for i in $(seq 1 30); do
        sleep 2
        if curl -s -o /dev/null http://localhost:$TRACER_PORT/mcp/health 2>/dev/null; then
            echo ""
            echo "  MCP:       http://localhost:$TRACER_PORT/mcp/health"
            echo "  JDWP:      localhost:$JDWP_PORT"
            echo "Started successfully."
            return 0
        fi
        echo -n "."
    done
    echo ""
    echo "  WARNING: MCP not responding after 60s. Eclipse may still be starting."
    echo "  Check: curl http://localhost:$TRACER_PORT/mcp/health"
}

cmd_stop() {
    if ! is_running; then
        echo "Debug Eclipse is not running."
        return 0
    fi

    local pid=$(get_pid)
    echo "Stopping Debug Eclipse (PID: $pid)..."

    # Graceful stop first
    kill "$pid" 2>/dev/null || true

    # Wait up to 10 seconds
    for i in $(seq 1 10); do
        sleep 1
        if ! kill -0 "$pid" 2>/dev/null; then
            echo "Stopped."
            rm -f "$WORKSPACE/.metadata/.lock" 2>/dev/null
            return 0
        fi
    done

    # Force kill
    echo "Force killing..."
    kill -9 "$pid" 2>/dev/null || true
    sleep 1
    rm -f "$WORKSPACE/.metadata/.lock" 2>/dev/null
    echo "Stopped (forced)."
}

cmd_restart() {
    cmd_stop
    sleep 2
    cmd_start
}

cmd_status() {
    echo "=== Debug Eclipse 2026-03 Status ==="
    echo "  Directory:  $ECLIPSE_DIR"
    echo "  Workspace:  $WORKSPACE"
    echo "  Display:    $DISPLAY_NUM"
    echo "  Tracer:     port $TRACER_PORT"
    echo "  JDWP:       port $JDWP_PORT"

    if is_running; then
        local pid=$(get_pid)
        echo "  Process:    RUNNING (PID: $pid)"
        echo "  Uptime:     $(ps -o etime= -p "$pid" 2>/dev/null | tr -d ' ')"
    else
        echo "  Process:    STOPPED"
    fi

    echo ""
    echo "  MCP port $TRACER_PORT:"
    local health=$(curl -s http://localhost:$TRACER_PORT/mcp/health 2>/dev/null)
    if [ -n "$health" ]; then
        echo "    $health"
    else
        echo "    not responding"
    fi

    echo ""
    echo "  JDWP port $JDWP_PORT:"
    if ss -tlnp 2>/dev/null | grep -q ":$JDWP_PORT "; then
        echo "    listening"
    else
        echo "    not listening"
    fi

    echo ""
    echo "  Systemd service:"
    systemctl --user is-active "$SERVICE_NAME.service" 2>/dev/null || echo "    not managed by systemd"
}

cmd_log() {
    if [ -f "$LOG_FILE" ]; then
        echo "=== Last 30 lines of $LOG_FILE ==="
        tail -30 "$LOG_FILE"
    else
        echo "Log file not found: $LOG_FILE"
    fi

    echo ""
    echo "=== Tracer entries in Eclipse log ==="
    grep "\[tracer\]" "$WORKSPACE/.metadata/.log" 2>/dev/null | tail -10 || echo "  No tracer entries"
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
        echo "  start    — Start debug Eclipse on DISPLAY $DISPLAY_NUM"
        echo "  stop     — Stop Eclipse (graceful, then force)"
        echo "  restart  — Stop then start"
        echo "  status   — Show process, MCP, JDWP status"
        echo "  log      — Show recent logs"
        echo ""
        echo "Environment variables:"
        echo "  ECLIPSE_DIR              Eclipse installation (default: /opt/eclipse-latest)"
        echo "  ECLIPSE_DEBUG_WORKSPACE  Workspace path (default: /home/ai/workspace-eclipse-debug)"
        echo "  ECLIPSE_DISPLAY          X display (default: :1)"
        exit 1
        ;;
esac
