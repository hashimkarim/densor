#!/usr/bin/env python3
"""Reconstruct TEST fixtures from an existing compensated CSV; never touch hardware.

This does not recover an original binary dump. The source CSV was trimmed and
compensated by the historical analysis pipeline. Provenance is saved alongside
every generated fixture. TMP119 values are never invented.
"""
import argparse
import csv
import hashlib
import json
import math
from pathlib import Path
import struct
import subprocess

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parents[1]
SOURCE = ROOT.parent / "data/experiments/labeled_data/2024-04-05-p1-e-1.csv"
INDEX = ROOT.parent / "data/experiments/index.json"


def integer(value, low, high):
    if not math.isfinite(value) or abs(value - round(value)) > 1e-6:
        raise ValueError(f"Inverse compensation did not yield an integer sensor code: {value}")
    result = round(value)
    if not low <= result <= high:
        raise ValueError(f"Sensor code is out of bounds: {result}")
    return result


def generate(output, writer):
    output.mkdir(parents=True, exist_ok=True)
    registers = json.loads(INDEX.read_text())[SOURCE.with_suffix(".bin").name]["densor_registers"]
    assert registers["rtc_interval"] == 1 and registers["startup_delay"] == 0
    assert registers["sensor_states"] == dict(temp=True, pd=True, touch=False, baro=False, accel=True)
    samples = []
    with SOURCE.open() as stream:
        for row in csv.DictReader(stream):
            supply = float(row["m_vdda"])
            code = integer(supply * 10 - 18, 0, 15)
            # Invert data/plot_readings.py, compensate_temp/compensate_pd.
            old = integer((float(row["m_temp"]) + (supply * 10 - 25) * 0.14 - 25) * 16, -2048, 2047) * 16
            pd = integer(float(row["m_pd"]) * 4095 / (supply * 1000), 0, 4095)
            axes = [integer(float(row[f"m_accel_{axis}"]), -32768, 32767) for axis in "xyz"]
            samples.append([code, old, pd, *axes])
    assert len(samples) == 210
    words = output / "historical-reconstruction-words.txt"
    words.write_text("".join(" ".join(map(str, row)) + "\n" for row in samples))
    modern = output / "historical-reconstructed-r1.bin"
    subprocess.run([str(writer), "--replay", str(words), str(modern)], check=True)
    header = bytearray(9)
    header[0] = 0x29 | (0x80 if registers["rc_enabled"] else 0)
    header[1:5] = struct.pack(">I", registers["start_time"])
    header[5] = 1
    header[7:9] = struct.pack("<H", 9 + 10 * len(samples))
    legacy = output / "historical-reconstructed-legacy.bin"
    records = [struct.pack("<HHhhh", (old & 0xfff0) | code, pd, x, y, z)
               for code, old, pd, x, y, z in samples]
    legacy.write_bytes(header + b"".join(records))
    provenance = {
        "kind": "RECONSTRUCTED TEST DATA, not an original NFC dump or an R1 device measurement",
        "source_csv": str(SOURCE.relative_to(REPO)),
        "source_csv_sha256": hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
        "source_index": str(INDEX.relative_to(REPO)),
        "source_index_sha256": hashlib.sha256(INDEX.read_bytes()).hexdigest(),
        "samples": len(samples), "sample_period_seconds": 1,
        "source_index_pointer": registers["memory_pointer"],
        "reconstructed_legacy_pointer": len(legacy.read_bytes()),
        "r1_pointer": len(modern.read_bytes()), "simulated_capacity_bytes": 8192,
        "r1_mask": 13, "tmp119": "absent; no measured TMP119 channel exists in this source",
        "transform": "Inverse documented temperature/photodiode compensation; reject noninteger/out-of-range codes. Temperature low four bits set to zero. C firmware writer generates R1 records and header.",
        "limitations": "CSV contains a trimmed prefix. Source raw binary, original low temperature bits and full tail are unavailable. Source timestamps retained only in reconstructed legacy metadata; R1 uses nominal elapsed time.",
        "sha256": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in (words, legacy, modern)},
    }
    (output / "historical-reconstruction.json").write_text(json.dumps(provenance, indent=2) + "\n")
    return provenance


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "build/tests")
    parser.add_argument("--writer", type=Path, default=ROOT / "build/tests/test_r1")
    args = parser.parse_args()
    print(json.dumps(generate(args.output, args.writer), indent=2))
