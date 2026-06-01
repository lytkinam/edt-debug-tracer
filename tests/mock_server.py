#!/usr/bin/env python3
"""Mock MCP server for Python CI tests."""
from flask import Flask, request, jsonify
import threading, time

app = Flask(__name__)
state = {"active": False, "session_id": None, "steps": 0}

@app.get("/mcp/health")
def health():
    return jsonify({"ok": True, "version": "1.0.0-mock"})

@app.get("/mcp/status")
def status():
    if state["active"]:
        return jsonify({"active": True, "session_id": state["session_id"], "steps": state["steps"]})
    return jsonify({"active": False})

@app.post("/mcp/start")
def start():
    data = request.get_json(force=True, silent=True) or {}
    sid = data.get("session_id", "")
    if not sid:
        return jsonify({"error": "session_id required"}), 400
    if state["active"]:
        return jsonify({"error": "session already active", "session_id": state["session_id"]}), 409
    state.update({"active": True, "session_id": sid, "steps": 0})
    return jsonify({"started": True, "session_id": sid})

@app.post("/mcp/stop")
def stop():
    sid = state["session_id"]
    steps = state["steps"]
    state.update({"active": False, "session_id": None, "steps": 0})
    return jsonify({"stopped": True, "session_id": sid or "", "steps": steps})

@app.post("/mcp/postprocess")
def postprocess():
    data = request.get_json(force=True, silent=True) or {}
    sid = data.get("session_id", "")
    if not sid:
        return jsonify({"error": "session_id required"}), 400
    return jsonify({"ok": True, "raw": 10, "clean": 3})

@app.get("/mcp/trace")
def trace():
    sid = request.args.get("session", "")
    t = request.args.get("type", "clean")
    if not sid:
        return jsonify({"error": "session param required"}), 400
    return jsonify([{"kind": "step", "procedure": "MockProc", "line": 1, "module": "main",
                     "repeat_count": 1, "pattern_len": 1, "ts": 0}])

if __name__ == "__main__":
    app.run(host="127.0.0.1", port=18080)
