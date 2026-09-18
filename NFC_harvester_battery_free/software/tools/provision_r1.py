#!/usr/bin/env python3
"""Prepare explicit SWD migration after exporting the original recording.

Default is a dry run. --apply flashes and initializes external EEPROM, destroying
the old layout. Use an independently powered bench board and its actual probe.
"""
import argparse
import hashlib
from pathlib import Path
import re
import subprocess

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--elf", required=True, type=Path)
    parser.add_argument("--export", required=True, type=Path, help="Saved pre-migration binary recording")
    parser.add_argument("--interface", required=True, help="OpenOCD probe config, e.g. interface/stlink.cfg")
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    elf = args.elf.resolve(strict=True)
    saved = args.export.resolve(strict=True).read_bytes()
    if len(saved) < 9:
        parser.error("The pre-migration export is missing or too short")
    if not re.fullmatch(r"[A-Za-z0-9_./-]+", args.interface) or any(c in str(elf) for c in "{}\\\n\r"):
        parser.error("Unsupported probe/path syntax")
    symbols = {}
    for line in subprocess.check_output(["arm-none-eabi-nm", "--defined-only", str(elf)], text=True).splitlines():
        parts = line.split()
        if len(parts) == 3:
            symbols[parts[2]] = int(parts[0], 16)
    for required in ["densor_boot_gate", "densor_provision_request"]:
        if required not in symbols:
            parser.error(f"ELF does not contain the R1 provisioning symbol {required}")
    gate = symbols["densor_boot_gate"] & ~1
    flag = symbols["densor_provision_request"]
    commands = [f"program {{{elf}}} verify", "reset halt", f"bp 0x{gate:x} 2 hw",
                "resume", "wait_halt 5000", f"rbp 0x{gate:x}", f"mww 0x{flag:x} 0x44525031", "resume", "shutdown"]
    argv = ["openocd", "-f", args.interface, "-f", "target/stm32l0.cfg"]
    for command in commands:
        argv += ["-c", command]
    print("Pre-migration export SHA-256:", hashlib.sha256(saved).hexdigest())
    print("External EEPROM will be initialized as an empty, stopped R1 device.")
    if args.apply:
        subprocess.run(argv, check=True)
        print("Flashing/provisioning requested. Read and validate the stopped R1 header in Android before use.")
    else:
        print("Dry run. Re-run with --apply only after checking the saved export and selecting the correct board/probe.")
        print("OpenOCD commands:\n" + "\n".join(commands))

if __name__ == "__main__":
    main()
