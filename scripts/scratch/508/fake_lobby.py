#!/usr/bin/env python3
"""Account-free fake FAF lobby: ask_session, auth, game_host, and the host's GameState Idle.

Usage: fake_lobby.py <port> <log-file>. Prints "listening" once bound. Serves one client.
"""
import base64
import datetime
import hashlib
import json
import socket
import struct
import sys
import threading

GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
port = int(sys.argv[1])
log = open(sys.argv[2], "a", buffering=1)


def note(msg):
    log.write(f"{datetime.datetime.now().isoformat()} {msg}\n")


def recv_exact(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise EOFError
        buf += chunk
    return buf


def read_frame(sock):
    b1, b2 = recv_exact(sock, 2)
    opcode = b1 & 0x0F
    masked = b2 & 0x80
    length = b2 & 0x7F
    if length == 126:
        length = struct.unpack(">H", recv_exact(sock, 2))[0]
    elif length == 127:
        length = struct.unpack(">Q", recv_exact(sock, 8))[0]
    mask = recv_exact(sock, 4) if masked else b"\0\0\0\0"
    data = bytearray(recv_exact(sock, length))
    for i in range(length):
        data[i] ^= mask[i % 4]
    return opcode, bytes(data)


send_lock = threading.Lock()


def send_frame(sock, opcode, payload):
    header = bytes([0x80 | opcode])
    n = len(payload)
    if n < 126:
        header += bytes([n])
    elif n < 65536:
        header += bytes([126]) + struct.pack(">H", n)
    else:
        header += bytes([127]) + struct.pack(">Q", n)
    with send_lock:
        sock.sendall(header + payload)


def send_json(sock, obj):
    note(f"-> {obj}")
    send_frame(sock, 1, (json.dumps(obj) + "\n").encode())


def pinger(sock):
    while True:
        threading.Event().wait(30)
        try:
            send_json(sock, {"command": "ping"})
        except OSError:
            return


def serve(sock):
    request = b""
    while b"\r\n\r\n" not in request:
        request += sock.recv(4096)
    key = [
        line.split(b":", 1)[1].strip()
        for line in request.split(b"\r\n")
        if line.lower().startswith(b"sec-websocket-key:")
    ][0]
    accept = base64.b64encode(hashlib.sha1(key + GUID.encode()).digest()).decode()
    sock.sendall(
        (
            "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
            f"Sec-WebSocket-Accept: {accept}\r\n\r\n"
        ).encode()
    )
    threading.Thread(target=pinger, args=(sock,), daemon=True).start()
    while True:
        try:
            opcode, data = read_frame(sock)
        except (EOFError, OSError):
            note("client gone")
            return
        if opcode == 8:
            note("close frame")
            try:
                send_frame(sock, 8, data[:2])
            except OSError:
                pass
            return
        if opcode == 9:
            send_frame(sock, 10, data)
            continue
        if opcode != 1:
            continue
        msg = json.loads(data.decode())
        note(f"<- {msg}")
        cmd = msg.get("command")
        if cmd == "ask_session":
            send_json(sock, {"command": "session", "session": 4242})
        elif cmd == "auth":
            send_json(sock, {"command": "irc_password", "password": "x"})
            send_json(
                sock,
                {
                    "command": "welcome",
                    "me": {"id": 7982, "login": "test"},
                    "id": 7982,
                    "login": "test",
                    "current_time": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                },
            )
        elif cmd == "game_host":
            send_json(
                sock,
                {
                    "command": "game_launch",
                    "uid": 777001,
                    "mod": "faf",
                    "name": msg.get("title", "rig"),
                    "game_type": "custom",
                    "rating_type": "global",
                    "init_mode": 0,
                    "args": ["/numgames", "1"],
                },
            )
        elif cmd == "GameState" and msg.get("args") == ["Idle"]:
            send_json(
                sock, {"command": "HostGame", "target": "game", "args": [msg.get("mapname", "scmp_007")]}
            )


srv = socket.socket()
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(("127.0.0.1", port))
srv.listen(1)
print("listening", flush=True)
conn, _ = srv.accept()
serve(conn)
