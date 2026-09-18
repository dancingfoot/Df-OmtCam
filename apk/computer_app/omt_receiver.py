#!/usr/bin/env python3
"""
OMT (Open Media Transport) & RTP Test Receiver for PC
Listens for OmtCam broadcasts on UDP port 9998 (or custom port).
Prints real-time stream telemetry and can write received H.264 video to file or stdout for piping to FFplay / VLC / OBS.

Usage:
  python3 omt_receiver.py [port]
  python3 omt_receiver.py 9998 --pipe | ffplay -f h264 -i -
"""

import sys
import socket
import struct
import time

DEFAULT_PORT = 9998
OMT_MAGIC = 0x4F4D # "OM"

def run_receiver(port=DEFAULT_PORT, pipe_mode=False):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4 * 1024 * 1024) # 4MB buffer
    sock.bind(("0.0.0.0", port))

    if not pipe_mode:
        print(f"=================================================")
        print(f"  OmtCam PC Receiver listening on UDP port {port}")
        print(f"  Send streams from OmtCam app to this PC's IP")
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

            # Check if OMT packet
            if len(data) >= 20 and struct.unpack("!H", data[0:2])[0] == OMT_MAGIC:
                version, media_type, seq, flags, pts_us, chunk_idx, total_chunks = struct.unpack("!BBHHQH H", data[2:20])
                payload = data[20:]
                is_keyframe = bool(flags & 0x01)
                is_start = bool(flags & 0x02)
                is_end = bool(flags & 0x04)

                if is_end:
                    frames_count += 1

                if pipe_mode and media_type == 1: # Video payload
                    sys.stdout.buffer.write(payload)
                    sys.stdout.buffer.flush()

            else:
                # Raw RTP / UDP
                if pipe_mode:
                    sys.stdout.buffer.write(data)
                    sys.stdout.buffer.flush()

            now = time.time()
            if not pipe_mode and (now - last_stat_time) >= 1.0:
                elapsed = now - last_stat_time
                bytes_diff = total_bytes - last_bytes_count
                kbps = (bytes_diff * 8) / (elapsed * 1000)
                fps = frames_count / elapsed

                sender_ip = addr[0]
                sys.stdout.write(
                    f"\r[LIVE] From {sender_ip} | Rate: {kbps:6.1f} kbps | "
                    f"FPS: {fps:4.1f} | Packets: {total_packets} | Total: {total_bytes / (1024*1024):5.2f} MB"
                )
                sys.stdout.flush()

                frames_count = 0
                last_stat_time = now
                last_bytes_count = total_bytes

    except KeyboardInterrupt:
        if not pipe_mode:
            print("\nReceiver stopped.")
    finally:
        sock.close()

if __name__ == "__main__":
    port = DEFAULT_PORT
    pipe = False
    args = sys.argv[1:]
    for arg in args:
        if arg == "--pipe":
            pipe = True
        elif arg.isdigit():
            port = int(arg)

    run_receiver(port=port, pipe_mode=pipe)
