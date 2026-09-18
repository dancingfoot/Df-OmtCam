#!/usr/bin/env python3
"""
OMT (Open Media Transport) Multi-Protocol Test Receiver for PC
Supports both TCP (standard OMT receiver/client) and UDP/RTP streaming modes.

Usage:
  # Standard OMT TCP Mode (connects to phone or listens on port 6400):
  python3 omt_receiver.py --tcp 6400
  python3 omt_receiver.py --connect <PHONE_IP> 6400

  # UDP Mode:
  python3 omt_receiver.py --udp 9998

  # Pipe directly into FFplay for live display:
  python3 omt_receiver.py --tcp 6400 --pipe | ffplay -f h264 -probesize 32 -flags low_delay -i -
"""

import sys
import socket
import struct
import time

DEFAULT_TCP_PORT = 6400
DEFAULT_UDP_PORT = 9998
OMT_MAGIC = 0x4F4D # "OM"

def run_tcp_server(port=DEFAULT_TCP_PORT, pipe_mode=False):
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("0.0.0.0", port))
    server.listen(5)

    if not pipe_mode:
        print("=========================================================")
        print(f"  OMT TCP Receiver Server listening on port {port}")
        print(f"  Configure DF-OmtCamera to 'OMT Unicast TCP Client'")
        print(f"  Destination IP: <This PC IP>, Port: {port}")
        print("=========================================================")

    try:
        while True:
            client, addr = server.accept()
            if not pipe_mode:
                print(f"\n[CONNECTED] DF-OmtCamera connected from {addr[0]}:{addr[1]}")
            handle_tcp_stream(client, addr, pipe_mode)
    except KeyboardInterrupt:
        pass
    finally:
        server.close()

def run_tcp_client(host, port, pipe_mode=False):
    if not pipe_mode:
        print(f"Connecting to DF-OmtCamera TCP Server at {host}:{port}...")
    client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    client.connect((host, port))
    if not pipe_mode:
        print(f"[CONNECTED] Connected to {host}:{port}")
    handle_tcp_stream(client, (host, port), pipe_mode)

def handle_tcp_stream(client_sock, addr, pipe_mode):
    total_packets = 0
    total_bytes = 0
    frames_count = 0
    last_stat_time = time.time()
    last_bytes_count = 0

    def recv_exact(n):
        buf = bytearray()
        while len(buf) < n:
            chunk = client_sock.recv(n - len(buf))
            if not chunk:
                return None
            buf.extend(chunk)
        return bytes(buf)

    try:
        while True:
            # Read 4-byte total length
            len_bytes = recv_exact(4)
            if not len_bytes:
                break
            total_len = struct.unpack("!I", len_bytes)[0]
            if total_len < 18 or total_len > 10 * 1024 * 1024:
                continue

            frame_bytes = recv_exact(total_len)
            if not frame_bytes:
                break

            total_packets += 1
            total_bytes += 4 + total_len
            frames_count += 1

            magic = struct.unpack("!H", frame_bytes[0:2])[0]
            if magic == OMT_MAGIC:
                version = frame_bytes[2]
                media_type = frame_bytes[3]
                flags = struct.unpack("!H", frame_bytes[4:6])[0]
                pts_us = struct.unpack("!Q", frame_bytes[6:14])[0]
                payload = frame_bytes[14:]

                if pipe_mode and media_type == 1: # Video
                    sys.stdout.buffer.write(payload)
                    sys.stdout.buffer.flush()

            now = time.time()
            if not pipe_mode and (now - last_stat_time) >= 1.0:
                elapsed = now - last_stat_time
                bytes_diff = total_bytes - last_bytes_count
                kbps = (bytes_diff * 8) / (elapsed * 1000)
                fps = frames_count / elapsed

                sys.stdout.write(
                    f"\r[OMT TCP LIVE] From {addr[0]} | Rate: {kbps:6.1f} kbps | "
                    f"FPS: {fps:4.1f} | Frames: {total_packets} | Total: {total_bytes / (1024*1024):5.2f} MB"
                )
                sys.stdout.flush()

                frames_count = 0
                last_stat_time = now
                last_bytes_count = total_bytes

    except Exception as e:
        if not pipe_mode:
            print(f"\n[STREAM CLOSED] {e}")
    finally:
        client_sock.close()

def run_udp_receiver(port=DEFAULT_UDP_PORT, pipe_mode=False):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4 * 1024 * 1024)
    sock.bind(("0.0.0.0", port))

    if not pipe_mode:
        print(f"=================================================")
        print(f"  OMT UDP Receiver listening on port {port}")
        print(f"=================================================")

    total_packets = 0
    total_bytes = 0
    frames_count = 0
    last_stat_time = time.time()
    last_bytes_count = 0

    try:
        while True:
            data, addr = sock.recvfrom(65535)
            if not data:
                continue

            total_packets += 1
            total_bytes += len(data)

            if len(data) >= 20 and struct.unpack("!H", data[0:2])[0] == OMT_MAGIC:
                version, media_type, seq, flags, pts_us, chunk_idx, total_chunks = struct.unpack("!BBHHQH H", data[2:20])
                payload = data[20:]
                is_end = bool(flags & 0x04)
                if is_end:
                    frames_count += 1
                if pipe_mode and media_type == 1:
                    sys.stdout.buffer.write(payload)
                    sys.stdout.buffer.flush()
            else:
                if pipe_mode:
                    sys.stdout.buffer.write(data)
                    sys.stdout.buffer.flush()

            now = time.time()
            if not pipe_mode and (now - last_stat_time) >= 1.0:
                elapsed = now - last_stat_time
                bytes_diff = total_bytes - last_bytes_count
                kbps = (bytes_diff * 8) / (elapsed * 1000)
                fps = frames_count / elapsed

                sys.stdout.write(
                    f"\r[OMT UDP LIVE] From {addr[0]} | Rate: {kbps:6.1f} kbps | "
                    f"FPS: {fps:4.1f} | Packets: {total_packets} | Total: {total_bytes / (1024*1024):5.2f} MB"
                )
                sys.stdout.flush()

                frames_count = 0
                last_stat_time = now
                last_bytes_count = total_bytes
    except KeyboardInterrupt:
        pass
    finally:
        sock.close()

if __name__ == "__main__":
    mode = "tcp"
    port = DEFAULT_TCP_PORT
    connect_host = None
    pipe = False

    i = 1
    while i < len(sys.argv):
        arg = sys.argv[i]
        if arg == "--pipe":
            pipe = True
        elif arg == "--udp":
            mode = "udp"
            if i + 1 < len(sys.argv) and sys.argv[i+1].isdigit():
                port = int(sys.argv[i+1])
                i += 1
            else:
                port = DEFAULT_UDP_PORT
        elif arg == "--tcp":
            mode = "tcp"
            if i + 1 < len(sys.argv) and sys.argv[i+1].isdigit():
                port = int(sys.argv[i+1])
                i += 1
            else:
                port = DEFAULT_TCP_PORT
        elif arg == "--connect":
            mode = "connect"
            if i + 1 < len(sys.argv):
                connect_host = sys.argv[i+1]
                i += 1
            if i + 1 < len(sys.argv) and sys.argv[i+1].isdigit():
                port = int(sys.argv[i+1])
                i += 1
        elif arg.isdigit():
            port = int(arg)
        i += 1

    if mode == "connect" and connect_host:
        run_tcp_client(connect_host, port, pipe_mode=pipe)
    elif mode == "udp":
        run_udp_receiver(port=port, pipe_mode=pipe)
    else:
        run_tcp_server(port=port, pipe_mode=pipe)
