import argparse
import asyncio
import binascii
import sys
import time
from dataclasses import dataclass
from typing import Optional

from bleak import BleakClient, BleakScanner


SVC_UUID = "f0debc9a-7856-3412-7856-341278563412"
RX_UUID = "f0debc9a-7956-3412-7856-341278563412"
TX_UUID = "f0debc9a-7a56-3412-7856-341278563412"


def _hexdump(data: bytes) -> str:
    return binascii.hexlify(data).decode("ascii")


@dataclass
class Frame:
    type: int
    seq: int
    length: int
    payload: bytes


def parse_frame(data: bytes) -> Optional[Frame]:
    if len(data) < 5:
        return None
    t = data[0]
    seq = data[1] | (data[2] << 8)
    ln = data[3] | (data[4] << 8)
    if len(data) < 5 + ln:
        return None
    payload = data[5 : 5 + ln]
    return Frame(type=t, seq=seq, length=ln, payload=payload)


def describe_frame(f: Frame) -> str:
    if f.type == 0x01:
        return f"EVT_WAKE seq={f.seq}"
    if f.type == 0x02:
        return f"EVT_REC_START seq={f.seq}"
    if f.type == 0x03:
        return f"EVT_REC_END seq={f.seq}"
    if f.type == 0x10:
        return f"AUDIO_CHUNK seq={f.seq} bytes={f.length}"
    if f.type == 0x11:
        try:
            txt = f.payload.decode("utf-8", errors="replace")
        except Exception:
            txt = repr(f.payload)
        return f'EVT_ERROR seq={f.seq} "{txt}"'
    return f"TYPE=0x{f.type:02x} seq={f.seq} len={f.length}"


async def pick_device(name_substr: str, timeout: float) -> str:
    print(f"Scanning for BLE devices (timeout={timeout}s), name contains: {name_substr!r}")
    devices = await BleakScanner.discover(timeout=timeout)
    for d in devices:
        if d.name and name_substr.lower() in d.name.lower():
            print(f"Found: name={d.name!r} address={d.address}")
            return d.address
    raise RuntimeError("Device not found. Make sure SONYA-WATCH is advertising and Bluetooth is enabled.")


async def interactive_loop(client: BleakClient, rx_uuid: str) -> None:
    loop = asyncio.get_running_loop()
    print("Enter commands for RX (e.g. PING / SETREC:2 / REC). Ctrl+C to exit.")

    def _readline() -> str:
        return sys.stdin.readline()

    while True:
        line = await loop.run_in_executor(None, _readline)
        if not line:
            await asyncio.sleep(0.05)
            continue
        cmd = line.strip("\r\n")
        if not cmd:
            continue
        await client.write_gatt_char(rx_uuid, cmd.encode("utf-8"), response=False)
        print(f">> RX: {cmd!r}")


async def run(args: argparse.Namespace) -> int:
    address = args.address
    if not address:
        address = await pick_device(args.name, timeout=args.scan_timeout)

    audio_f = open(args.audio_out, "ab") if args.audio_out else None

    last_rec_bytes = 0
    last_rec_chunks = 0

    def on_notify(_: int, data: bytearray) -> None:
        nonlocal last_rec_bytes, last_rec_chunks
        b = bytes(data)
        f = parse_frame(b)
        ts = time.strftime("%H:%M:%S")
        if f:
            msg = describe_frame(f)
            print(f"[{ts}] << TX {msg}")
            if f.type == 0x10:
                last_rec_bytes += f.length
                last_rec_chunks += 1
                if audio_f:
                    audio_f.write(f.payload)
                    audio_f.flush()
            if f.type == 0x03:
                if last_rec_chunks:
                    print(f"[{ts}]    total AUDIO_CHUNK: chunks={last_rec_chunks} bytes={last_rec_bytes}")
                last_rec_bytes = 0
                last_rec_chunks = 0
        else:
            print(f"[{ts}] << TX raw {len(b)} bytes hex={_hexdump(b)}")

    print(f"Connecting to {address} ...")
    async with BleakClient(address) as client:
        if not client.is_connected:
            raise RuntimeError("Failed to connect.")

        print("Connected.")
        print(f"Using UUIDs: svc={args.svc_uuid} rx={args.rx_uuid} tx={args.tx_uuid}")

        await client.start_notify(args.tx_uuid, on_notify)
        print("TX notifications enabled.")

        # Optional one-shot commands
        for cmd in args.cmd:
            await client.write_gatt_char(args.rx_uuid, cmd.encode("utf-8"), response=False)
            print(f">> RX: {cmd!r}")
            await asyncio.sleep(0.1)

        if args.no_interactive:
            # keep alive to receive notifications
            await asyncio.sleep(args.keepalive)
            return 0

        try:
            await interactive_loop(client, args.rx_uuid)
        finally:
            try:
                await client.stop_notify(args.tx_uuid)
            except Exception:
                pass
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="Sonya Watch BLE client (RX/TX tester)")
    ap.add_argument("--address", help="BLE address (if omitted: scan by name)")
    ap.add_argument("--name", default="SONYA-WATCH", help="Scan filter by name substring")
    ap.add_argument("--scan-timeout", type=float, default=6.0, help="Scan timeout seconds")
    ap.add_argument("--svc-uuid", default=SVC_UUID, help="Service UUID (informational)")
    ap.add_argument("--rx-uuid", default=RX_UUID, help="RX characteristic UUID (write)")
    ap.add_argument("--tx-uuid", default=TX_UUID, help="TX characteristic UUID (notify)")
    ap.add_argument("--cmd", action="append", default=[], help="Send command immediately (repeatable)")
    ap.add_argument("--no-interactive", action="store_true", help="Do not read stdin; just send --cmd and wait")
    ap.add_argument("--keepalive", type=float, default=8.0, help="Seconds to keep connection in no-interactive mode")
    ap.add_argument("--audio-out", help="Append received AUDIO_CHUNK payloads to file (raw 16kHz s16le mono)")

    args = ap.parse_args()
    try:
        return asyncio.run(run(args))
    except KeyboardInterrupt:
        return 0


if __name__ == "__main__":
    raise SystemExit(main())

