#!/usr/bin/env python3
"""
Lab gRPC-ish AS sim: accepts a length-prefixed JSON frame over a raw TCP socket
on the same port the GW targets, returning JSON AsResponse.

For full gRPC, put a real stub behind ra-grpc-client; this sim validates the
UTF-8 JSON payload contract used by GrpcClientSbb / AsWireCodec.
"""
import json
import socket
import struct
import sys
import threading

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 9090


def handle(conn: socket.socket):
    try:
        # Best-effort: read whatever; if it looks like JSON, reply JSON.
        data = conn.recv(65536)
        if not data:
            return
        text = data.decode("utf-8", errors="ignore")
        start = text.find("{")
        end = text.rfind("}")
        req = {}
        if start >= 0 and end > start:
            try:
                req = json.loads(text[start : end + 1])
            except Exception:
                pass
        corr = req.get("correlationId", "unknown")
        resp = {
            "correlationId": corr,
            "requestId": req.get("requestId", corr),
            "generation": req.get("generation", 1),
            "text": f"gRPC-JSON hello {req.get('msisdn', '')}",
            "action": "END",
            "async": False,
        }
        payload = json.dumps(resp).encode("utf-8")
        # gRPC raw message: 1 byte compressed flag + 4 byte big-endian length
        frame = b"\x00" + struct.pack(">I", len(payload)) + payload
        conn.sendall(frame)
    finally:
        conn.close()


def main():
    print(f"gRPC-JSON AS lab sim listening :{PORT}", flush=True)
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", PORT))
    srv.listen(32)
    while True:
        c, _ = srv.accept()
        threading.Thread(target=handle, args=(c,), daemon=True).start()


if __name__ == "__main__":
    main()
