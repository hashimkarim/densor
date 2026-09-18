#!/usr/bin/env python3
"""Build and run fault tests, then decode C-produced EEPROM images in Java."""
import argparse
import csv
import os
from pathlib import Path
import shutil
import subprocess
from replay_csv_fixture import generate as generate_historical_fixture

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "build" / "tests"
CORE = ROOT / "DentalSensor_StorageProject" / "Core"
JAVA = ROOT / "source_ST25NFCApplication_V3_9/app/src/main/java/com/st/st25nfc/densor"

def run(args):
    return subprocess.run([str(x) for x in args], check=True, text=True, capture_output=True)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--update-fixtures", action="store_true", help="Explicitly regenerate checked Android fixtures from the C writer")
    args = parser.parse_args()
    BUILD.mkdir(parents=True, exist_ok=True)
    cc = os.environ.get("CC", "clang" if shutil.which("clang") else "cc")
    flags = ["-std=c11", "-Wall", "-Wextra", "-Werror", "-g"]
    # Explicitly opt out on hosts without sanitizer runtimes; report that choice.
    sanitize = os.environ.get("DENSOR_SANITIZE", "1") == "1"
    if sanitize:
        flags.append("-fsanitize=address,undefined")
    exe = BUILD / "test_r1"
    run([cc, *flags, "-I" + str(CORE / "Inc"), ROOT / "tests/test_r1.c",
         CORE / "Src/densor_r1.c", CORE / "Src/tmp119.c", "-o", exe])
    print(run([exe]).stdout.strip())
    board = BUILD / "test_board_power"
    run([cc, *flags, "-I" + str(ROOT / "tests/board_hal"), "-I" + str(CORE / "Inc"),
         ROOT / "tests/test_board_power.c", CORE / "Src/densor_board.c",
         CORE / "Src/densor_r1.c", CORE / "Src/tmp119.c", "-o", board])
    scenarios = ("startup-1", "startup-59", "running", "full", "error", "unconfigured", "provision",
                 "bad-header", "bad-pointer", "bad-density", "configuring", "read-fail",
                 "pending-settings", "pending-reset", "running-last", "already-full",
                 "missing-sensor", "tmp-bus", "clock-invalid", "write-fail", "ready-fail", "rtc-write-fail")
    for scenario in scenarios:
        for period in (5, 60, 120, 600, 3540):
            run([board, scenario, period])
    print(f"Board power policy: {len(scenarios) * 5} RTC/state/fault checks passed")
    fixtures = ROOT / "tests/record-fixtures.csv"
    with fixtures.open() as stream:
        for row in csv.DictReader(stream):
            assert row["record_hex"], row
            fields = ["mask", "supply", "old_raw", "tmp119_raw", "pd_raw", "accel_x", "accel_y", "accel_z"]
            actual = run([exe, *[row[key] or "0" for key in fields]]).stdout.strip()
            assert actual == row["record_hex"], (row["name"], actual, row["record_hex"])
    for capacity in (512, 2048, 8192):
        for mask in range(1, 16):
            run([exe, "--dump", BUILD / f"c-{capacity}-{mask}.bin", mask, capacity])
    phone_assets = ROOT / "source_ST25NFCApplication_V3_9/app/src/androidTest/assets"
    for mask in (1, 2, 3, 15):
        if args.update_fixtures:
            shutil.copy2(BUILD / f"c-512-{mask}.bin", phone_assets / f"r1-mask-{mask}.bin")
        assert (phone_assets / f"r1-mask-{mask}.bin").read_bytes() == (BUILD / f"c-512-{mask}.bin").read_bytes(), "Phone fixture differs from firmware output"
    generate_historical_fixture(BUILD, exe)
    for name in ("historical-reconstructed-legacy.bin", "historical-reconstructed-r1.bin"):
        if args.update_fixtures:
            shutil.copy2(BUILD / name, phone_assets / name)
        assert (phone_assets / name).read_bytes() == (BUILD / name).read_bytes(), "Historical phone fixture differs from reconstruction"
    debug_assets = phone_assets.parent.parent / "debug/assets/densor-debug"
    for name in ("r1-mask-3.bin", "historical-reconstructed-legacy.bin", "historical-reconstructed-r1.bin"):
        if args.update_fixtures:
            shutil.copy2(phone_assets / name, debug_assets / name)
        assert (debug_assets / name).read_bytes() == (phone_assets / name).read_bytes(), "Debug fixture differs from checked phone fixture"
    run(["javac", "-d", BUILD, JAVA / "DensorProtocol.java", JAVA / "DensorMultirate.java", JAVA / "data/DensorDataSample.java",
         JAVA / "data/DensorDataSet.java", ROOT / "tests/ProtocolTest.java"])
    print(run(["java", "-ea", "-cp", BUILD, "ProtocolTest", BUILD, fixtures]).stdout.strip())
    print(f"Golden firmware bytes match; sanitizers={'address,undefined' if sanitize else 'disabled explicitly'}")

if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        print(error.stdout, error.stderr)
        raise
