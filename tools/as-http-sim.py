#!/usr/bin/env python3
"""Minimal HTTP AS sim for RestLink USSD GW greenfield JSON contract."""
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import sys

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8090


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(n) if n else b"{}"
        try:
            req = json.loads(raw.decode("utf-8"))
        except Exception:
            req = {}
        corr = req.get("correlationId", "")
        text = f"Hello {req.get('msisdn', '')} from *{req.get('shortCode', '')}*"
        body = json.dumps({
            "correlationId": corr,
            "requestId": req.get("requestId", corr),
            "generation": req.get("generation", 1),
            "text": text,
            "action": "END",
            "async": False,
        }).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))


if __name__ == "__main__":
    print(f"HTTP AS sim on :{PORT} POST /ussd/pull", flush=True)
    HTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
