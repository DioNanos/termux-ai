#!/usr/bin/env python3
"""Stub of the Termux AI unix socket, for the CLI shell tests.

usage: stub_socket_server.py SOCKET_PATH LOG_PATH MODE [ARG]

Every request line is appended to LOG_PATH (raw). MODE:
  reply FILE    answer with the contents of FILE and close
  routes FILE   answer each command with the first matching line (CMD SP JSON);
                a command with no line answers ok:false with error_name NO_ROUTE
  echo          answer ok:true with the received prompt as data.text (ASCII-escaped JSON)
  echo-raw      same, with raw UTF-8 in the JSON string
  drop          read the request, then close without answering (broken transport)
  hang          a generation never answers (it stays open 30 s); litert.cancel is answered cancelled:true
"""
import json
import os
import socket
import sys
import threading

sock_path, log_path, mode = sys.argv[1], sys.argv[2], sys.argv[3]
arg = sys.argv[4] if len(sys.argv) > 4 else None

if os.path.exists(sock_path):
    os.unlink(sock_path)
server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
server.bind(sock_path)
server.listen(8)
log_lock = threading.Lock()


def handle(conn):
    try:
        data = b""
        while b"\n" not in data:
            chunk = conn.recv(65536)
            if not chunk:
                break
            data += chunk
        line = data.split(b"\n", 1)[0]
        with log_lock, open(log_path, "ab") as log:
            log.write(line + b"\n")
        if mode == "drop":
            return
        if mode == "hang":
            if json.loads(line.decode("utf-8")).get("cmd") == "litert.cancel":
                conn.sendall(b'{"ok":true,"data":{"cancelled":true}}\n')
            else:
                import time
                time.sleep(30)
            return
        if mode == "reply":
            with open(arg, "rb") as handle_file:
                conn.sendall(handle_file.read())
            return
        if mode == "routes":
            request = json.loads(line.decode("utf-8"))
            with open(arg, encoding="utf-8") as table:
                for table_line in table:
                    table_line = table_line.rstrip("\n")
                    if not table_line:
                        continue
                    prefix, payload = table_line.split(" ", 1)
                    if prefix == request.get("cmd"):
                        conn.sendall(payload.encode("utf-8") + b"\n")
                        return
            conn.sendall(b'{"ok":false,"error_name":"NO_ROUTE","error":"no route for this command"}\n')
            return
        request = json.loads(line.decode("utf-8"))
        prompt = request["args"]["prompt"]
        body = {"ok": True, "data": {"text": prompt, "finish_reason": "stop"}}
        raw = json.dumps(body, ensure_ascii=(mode != "echo-raw")).encode("utf-8")
        conn.sendall(raw + b"\n")
    except Exception as exc:  # the stub must never take the test down
        with log_lock, open(log_path, "ab") as log:
            log.write(("STUB-ERROR " + repr(exc) + "\n").encode("utf-8"))
    finally:
        conn.close()


while True:
    connection, _ = server.accept()
    threading.Thread(target=handle, args=(connection,), daemon=True).start()
