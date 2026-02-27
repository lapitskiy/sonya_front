#!/usr/bin/env python3
"""
Читает UART часов и сохраняет в monitor_capture.txt только последние MAX_LINES строк.
Запуск: sg dialout -c 'python3 tools/monitor_capture_live.py' (из sonya_watch/)
Остановка: Ctrl+C
"""
import serial
import sys
import os
import time
import glob

PORT = os.environ.get("MONITOR_PORT", "/dev/ttyACM0")
BAUD = 115200
MAX_LINES = 200
CAPTURE_FILE = os.path.join(os.path.dirname(__file__), "..", "monitor_capture.txt")

def _autodetect_port():
    # Prefer stable symlinks if present
    by_id = sorted(glob.glob("/dev/serial/by-id/*"))
    if by_id:
        return by_id[0]
    for pat in ("/dev/ttyACM*", "/dev/ttyUSB*"):
        devs = sorted(glob.glob(pat))
        if devs:
            return devs[0]
    return None

def main():
    lines = []
    last_flush = 0.0
    last_rx = 0.0
    port = PORT
    if not os.path.exists(port):
        auto = _autodetect_port()
        if auto:
            port = auto
    try:
        s = serial.Serial(port, BAUD, timeout=0.2)
    except Exception as e:
        print("Error opening {}: {}".format(port, e), file=sys.stderr)
        sys.exit(1)

    try:
        # Make it obvious the file is alive (and never truncate to empty).
        lines.append("capture_start port={} baud={} max_lines={}".format(port, BAUD, MAX_LINES))
        last_flush = time.time()
        last_rx = last_flush
        with open(CAPTURE_FILE, "w", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")

        while True:
            line = s.readline()
            if line:
                try:
                    text = line.decode("utf-8", errors="replace").rstrip()
                except Exception:
                    text = line.decode("latin-1", errors="replace").rstrip()
                if not text:
                    continue
                lines.append(text)
                if len(lines) > MAX_LINES:
                    lines.pop(0)
                last_rx = time.time()

            # Periodically flush even if no new data, so the file stays non-empty and "live".
            now = time.time()
            if (now - last_flush) >= 1.0:
                if (now - last_rx) >= 2.0:
                    lines.append("hb no_uart_data_for={}s".format(int(now - last_rx)))
                    if len(lines) > MAX_LINES:
                        lines.pop(0)
                with open(CAPTURE_FILE, "w", encoding="utf-8") as f:
                    f.write("\n".join(lines) + "\n")
                last_flush = now
    except KeyboardInterrupt:
        pass
    finally:
        s.close()

if __name__ == "__main__":
    main()
