#!/usr/bin/env python3
"""Python smoke tests for MCP API. Runs against mock_server.py."""
import requests, sys, time

BASE = "http://localhost:18080/mcp"

def test_health():
    r = requests.get(f"{BASE}/health")
    assert r.status_code == 200
    assert r.json().get("ok") is True
    print("[OK] health")

def test_start_stop():
    r = requests.post(f"{BASE}/start", json={"session_id": "py-test-1"})
    assert r.status_code == 200
    assert r.json()["started"] is True
    r = requests.post(f"{BASE}/stop")
    assert r.status_code == 200
    assert r.json()["stopped"] is True
    print("[OK] start/stop")

def test_double_start():
    requests.post(f"{BASE}/start", json={"session_id": "py-test-2"})
    r = requests.post(f"{BASE}/start", json={"session_id": "py-test-2b"})
    assert r.status_code == 409
    requests.post(f"{BASE}/stop")
    print("[OK] double start -> 409")

def test_postprocess():
    requests.post(f"{BASE}/start", json={"session_id": "py-test-3"})
    requests.post(f"{BASE}/stop")
    r = requests.post(f"{BASE}/postprocess", json={"session_id": "py-test-3"})
    assert r.status_code == 200
    print("[OK] postprocess")

def test_trace():
    r = requests.get(f"{BASE}/trace", params={"session": "py-test-3", "type": "clean"})
    assert r.status_code == 200
    assert isinstance(r.json(), list)
    print("[OK] trace")

if __name__ == "__main__":
    time.sleep(0.5)
    test_health()
    test_start_stop()
    test_double_start()
    test_postprocess()
    test_trace()
    print("\nAll Python tests passed.")
