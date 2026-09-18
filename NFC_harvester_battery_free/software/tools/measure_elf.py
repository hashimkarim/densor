#!/usr/bin/env python3
"""Measure ELF32 load allocation without treating stack reservations as usage."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import subprocess

def measure(path):
    raw = path.read_bytes()
    if raw[:7] != b"\x7fELF\x01\x01\x01":
        raise ValueError("Expected a little-endian ELF32 image")
    e = struct.unpack_from("<16sHHIIIIIHHHHHH", raw)
    _, _, machine, _, _, phoff, shoff, _, _, phsize, phnum, shsize, shnum, names_index = e
    if machine != 40:
        raise ValueError("Expected ARM ELF")
    segments = [struct.unpack_from("<IIIIIIII", raw, phoff + i * phsize) for i in range(phnum)]
    flash = [(p[3], p[3] + p[4]) for p in segments if p[0] == 1 and p[4] and 0x08000000 <= p[3] < 0x08004000]
    for p in segments:
        if p[0] == 1 and p[4] and 0x08000000 <= p[3] < 0x10000000 and p[3] + p[4] > 0x08004000:
            raise ValueError("Flash load exceeds physical 16384-byte device")
    sections = [struct.unpack_from("<IIIIIIIIII", raw, shoff + i * shsize) for i in range(shnum)]
    name_section = sections[names_index]
    names = raw[name_section[4]:name_section[4] + name_section[5]]
    ram = []
    for s in sections:
        if s[2] & 2 and 0x20000000 <= s[3] < 0x20010000 and s[5]:
            name = names[s[0]:].split(b"\0", 1)[0].decode()
            ram.append({"name": name, "address": s[3], "bytes": s[5]})
    flash_extent = max((end for _, end in flash), default=0x08000000) - 0x08000000
    ram_extent = max((s["address"] + s["bytes"] for s in ram), default=0x20000000) - 0x20000000
    symbols = {}
    for line in subprocess.check_output(["arm-none-eabi-nm", "-P", str(path)], text=True).splitlines():
        fields = line.split()
        if len(fields) >= 3:
            symbols[fields[0]] = int(fields[2], 16)
    heap = symbols["_Min_Heap_Size"]
    stack = symbols["_Min_Stack_Size"]
    reservations = heap + stack
    static = sum(s["bytes"] for s in ram if s["name"] != "._user_heap_stack")
    if ram_extent > 2048:
        raise ValueError("SRAM extent exceeds physical 2048-byte device")
    return {"elf": str(path), "sha256": hashlib.sha256(raw).hexdigest(),
        "flash_limit_bytes": 16384, "flash_load_extent_bytes": flash_extent,
        "flash_remaining_bytes": 16384 - flash_extent, "sram_limit_bytes": 2048,
        "sram_static_bytes": static, "sram_heap_stack_reservation_bytes": reservations,
        "heap_reservation_bytes": heap, "stack_reservation_bytes": stack,
        "sram_alignment_padding_bytes": ram_extent - static - reservations,
        "sram_linked_extent_bytes": ram_extent, "sram_unreserved_bytes": 2048 - ram_extent,
        "sram_sections": ram, "peak_stack_bytes": None, "peak_heap_bytes": None,
        "runtime_stack_margin_bytes": None, "hardware_stack_validation": "unmeasured",
        "rtc_retained_ram_bytes": 0,
        "compiler": subprocess.check_output(["arm-none-eabi-gcc", "--version"], text=True).splitlines()[0]}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("elf", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = json.dumps(measure(args.elf), indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(result)
    print(result, end="")

if __name__ == "__main__":
    main()
