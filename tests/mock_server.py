"""Lightweight mock of the MCP HTTP server for CI testing.
Mimics the real McpHttpServer.java behaviour without Eclipse.
"""
from http.server import BaseHTTPRequestHandler, HTTPServer
import json, threading

state = {"tracing": False, "entries": [], "session_id": None}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a): pass  # silence

    def _respond(self, code, data):
        body = json.dumps(data).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", len(body))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/mcp/health":
            self._respond(200, {"status": "ok", "version": "1.0.0"})
        elif self.path == "/mcp/status":
            self._respond(200, {"tracing": state["tracing"],
                                "entries_count": len(state["entries"]),
                                "session_id": state["session_id"]})
        else:
            self._respond(404, {"error": "not_found"})

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length) or b"{}")
        if self.path == "/mcp/start":
            if state["tracing"]:
                self._respond(409, {"error": "already_running", "message": "Tracing session is already active"})
            else:
                state["tracing"] = True
                state["entries"] = []
                state["session_id"] = body.get("session_id")
                self._respond(200, {"started": True, "session_id": state["session_id"]})
        elif self.path == "/mcp/stop":
            if not state["tracing"]:
                self._respond(409, {"error": "not_running", "message": "No active tracing session"})
            else:
                state["tracing"] = False
                resp = {"stopped": True, "session_id": state["session_id"], "entries": list(state["entries"])}
                state["entries"] = []
                state["session_id"] = None
                self._respond(200, resp)
        else:
            self._respond(404, {"error": "not_found"})


if __name__ == "__main__":
    srv = HTTPServer(("localhost", 18080), Handler)
    print("Mock MCP server listening on http://localhost:18080")
    srv.serve_forever()
