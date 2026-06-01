"""Integration tests for MCP HTTP API.

Run after starting the Eclipse Application with edt-debug-tracer plugin:
    python test_mcp_api.py

Requires: requests
    pip install requests
"""
import requests
import sys

BASE = "http://localhost:18080/mcp"


def ok(msg): print(f"  ✓ {msg}")
def fail(msg): print(f"  ✗ {msg}"); sys.exit(1)


def test_health():
    print("test_health")
    r = requests.get(f"{BASE}/health", timeout=3)
    assert r.status_code == 200, f"Expected 200, got {r.status_code}"
    data = r.json()
    assert data["status"] == "ok", f"Unexpected body: {data}"
    ok(f"GET /health → {data}")


def test_status_idle():
    print("test_status_idle")
    r = requests.get(f"{BASE}/status", timeout=3)
    assert r.status_code == 200
    data = r.json()
    assert data["tracing"] == False
    assert data["entries_count"] == 0
    ok(f"GET /status (idle) → {data}")


def test_start_stop_empty():
    print("test_start_stop_empty")
    # start
    r = requests.post(f"{BASE}/start", json={"session_id": "test-1"}, timeout=3)
    assert r.status_code == 200
    data = r.json()
    assert data["started"] == True
    assert data["session_id"] == "test-1"
    ok(f"POST /start → {data}")

    # status while recording
    r = requests.get(f"{BASE}/status", timeout=3)
    assert r.status_code == 200
    assert r.json()["tracing"] == True
    ok("GET /status (recording) → tracing=true")

    # stop
    r = requests.post(f"{BASE}/stop", timeout=3)
    assert r.status_code == 200
    data = r.json()
    assert data["stopped"] == True
    assert isinstance(data["entries"], list)
    ok(f"POST /stop → {len(data['entries'])} entries")


def test_double_start():
    print("test_double_start")
    requests.post(f"{BASE}/start", timeout=3)
    r = requests.post(f"{BASE}/start", timeout=3)
    assert r.status_code == 409
    assert r.json()["error"] == "already_running"
    ok(f"double start → 409 already_running")
    requests.post(f"{BASE}/stop", timeout=3)  # cleanup


def test_stop_without_start():
    print("test_stop_without_start")
    # ensure not running
    requests.post(f"{BASE}/stop", timeout=3)
    r = requests.post(f"{BASE}/stop", timeout=3)
    assert r.status_code == 409
    assert r.json()["error"] == "not_running"
    ok(f"stop without start → 409 not_running")


if __name__ == "__main__":
    print(f"Running MCP API tests against {BASE}\n")
    tests = [test_health, test_status_idle, test_start_stop_empty,
             test_double_start, test_stop_without_start]
    for t in tests:
        try:
            t()
        except Exception as e:
            fail(str(e))
        print()
    print("All tests passed.")
