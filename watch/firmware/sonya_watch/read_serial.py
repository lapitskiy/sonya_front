import serial
import time
import sys


def _usage() -> None:
    print("Usage: read_serial.py [PORT] [BAUD] [DURATION_SEC] [--no-reset]")
    print("  PORT:         e.g. COM7 (default: COM7)")
    print("  BAUD:         e.g. 115200 (default: 115200)")
    print("  DURATION_SEC: seconds to read; 0 = forever (default: 120)")
    print("  --no-reset:   don't toggle RTS/DTR")


def _parse_args(argv: list[str]):
    port = "COM7"
    baud = 115200
    duration = 120.0
    do_reset = True

    args = list(argv[1:])
    if "--help" in args or "-h" in args:
        _usage()
        sys.exit(0)
    if "--no-reset" in args:
        do_reset = False
        args = [a for a in args if a != "--no-reset"]

    if len(args) >= 1:
        port = args[0]
    if len(args) >= 2:
        baud = int(args[1])
    if len(args) >= 3:
        duration = float(args[2])
    return port, baud, duration, do_reset


def main() -> int:
    port, baud, duration, do_reset = _parse_args(sys.argv)
    try:
        s = serial.Serial(port, baud, timeout=0.1)
        print(f"Connected to {port} @ {baud}")

        if do_reset:
            # Proper reset for ESP32-S3 (USB-Serial/JTAG)
            s.setDTR(False)
            s.setRTS(True)
            time.sleep(0.1)
            s.setRTS(False)
            print("Reset device")

        start = time.time()
        while True:
            if duration > 0 and (time.time() - start) >= duration:
                break
            line = s.readline()
            if line:
                sys.stdout.write(line.decode("utf-8", errors="ignore"))
                sys.stdout.flush()

        s.close()
        return 0
    except KeyboardInterrupt:
        return 0
    except Exception as e:
        print(f"Error: {e}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
