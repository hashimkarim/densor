#!/usr/bin/env python3
"""Record an isolated firmware image and its actual physical memory allocation."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
from measure_elf import measure

elf = Path(sys.argv[1]); storage, timing = sys.argv[2:4]
report = measure(elf)
report['rtc_retained_ram_bytes'] = 64
report.update(protocol_version=5, storage=storage, timing=timing,
              software_revision=f'R{2 if storage=="shared" else 3}{"a" if timing=="fsm" else "b"}',
              source_revision=subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
              source_dirty=bool(subprocess.check_output(['git', 'status', '--porcelain', '--', 'Core', 'Makefile', 'STM32L021F4UX_FLASH.ld'], text=True).strip()),
              compiler_options=(elf.parent / 'flags').read_text().splitlines(),
              eeprom_metadata_bytes=168, release_ready=False)
report['checksums'] = {elf.with_suffix(s).name: hashlib.sha256(elf.with_suffix(s).read_bytes()).hexdigest()
                       for s in ('.elf', '.bin', '.hex', '.map')}
core = Path('Core')
report['source_sha256'] = {str(p): hashlib.sha256(p.read_bytes()).hexdigest()
                         for p in sorted(core.rglob('*')) if p.is_file()}
elf.with_name(elf.stem + '-manifest.json').write_text(json.dumps(report, indent=2) + '\n')
print(f'{report["software_revision"]} {storage}/{timing}: Flash {report["flash_load_extent_bytes"]}/16384, static RAM {report["sram_static_bytes"]}/2048, retained RAM 64/256')
