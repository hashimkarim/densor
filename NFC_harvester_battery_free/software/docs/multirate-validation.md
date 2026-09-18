# R2/R3 validation record

Documentation verification on **2026-09-18**, against source commit
`e7071cc` with documentation edits only. Commands below were run from the
repository root. Tests exercise software and simulated device state; no physical
Densor board, NFC exchange or phone was tested in this documentation update.

## Checks run

| Check | Result |
| --- | --- |
| `make -C NFC_harvester_battery_free/software/DentalSensor_StorageProject -j4 REVISION=<variant>` for R2a/R2b/R3a/R3b | All four targets passed; existing objects were up to date and manifests refreshed |
| `python3 NFC_harvester_battery_free/software/tools/test_multirate.py` | All four logger/scheduler combinations passed under address/undefined-behavior sanitizers |
| Multirate board entry point with fake HAL | 85 scenarios per variant, 340 total, passed |
| C writer → Java decoder | 360 full EEPROM images passed, covering 15 masks, three capacities, two fixture kinds and four variants |
| Java requests → C firmware → Java decoding | 360 allocation requests committed and decoded successfully |
| Fixed record vectors and bundled Android/debug fixtures | Matched firmware writer output |
| `python3 NFC_harvester_battery_free/software/tools/test_r1.py` | R1 storage/configuration/TMP119 tests, 110 board checks, 45 C-produced recordings and golden vectors passed |
| Historical CSV reconstruction | 210 samples agreed between legacy and R1 decoding; TMP119 absent |

Multirate core tests cover masks, mixed and uniform schedules, duplicate/early/
late wakes, delay, phase/sequence rollover, full memory, missing sensors, invalid
allocations and requests, retained-state loss, pointer ambiguity and corruption.
Injected byte-write failures exercise 109 transaction, 726 initialization and
104 Stop boundaries per partitioned variant; shared variants exercise 89,
696 and 89 respectively. These are simulated failure boundaries, not physical
power-cut measurements.

Generated host images and executables are under `software/build/multirate-tests/`;
R1 results are under `software/build/tests/`. The multirate fixture check
does not overwrite checked fixtures unless explicitly given `--update-fixtures`.

## Firmware memory

The variant manifests reported the following allocations for the checked build
outputs. These are binary/linker measurements, not runtime stack measurements.

| Variant | Flash load extent (bytes) | Static SRAM (bytes) | RTC retained RAM (bytes) |
| --- | ---: | ---: | ---: |
| R2a, shared/FSM | 12,348 | 348 | 64 |
| R2b, shared/RTC | 12,356 | 348 | 64 |
| R3a, partitioned/FSM | 12,780 | 348 | 64 |
| R3b, partitioned/RTC | 12,764 | 348 | 64 |

The physical linker limits remain 16,384 bytes Flash and 2,048 bytes SRAM.
Heap/stack reservations remain 512/1,024 bytes. Static SRAM excludes those
reservations; inspect the linker map and compiler `.su` output together before
estimating margin. Runtime stack high-water and interrupt margin are unmeasured.
All variants reserve 168 EEPROM metadata bytes.

## Android evidence and remaining work

The host checks above compile and execute the production Java decoder; they
do not build an APK or exercise Android's NFC transport. The complete
`build_multirate.py` packaging/Gradle workflow was documented from source but
was not rerun for this documentation change.

Existing [R1 phone checks](android-phone-tests.md) apply to the recorded R1 APK.
Later [synthetic EEPROM export/import evidence](../../../logs/codex/01a0b51a-3652-72d2-a6a2-569deb55c310/qa/eeprom-export-validation.md)
records multirate debug-app checks on the S21 FE. That report contains older
R2/R3 labels; identify its cases by the stated storage/timing pair and image
hash, and use the current names in the [overview](../README.md). Neither report
is a fresh phone test of this checkout.

Before treating a candidate as hardware-validated, measure actual sensor
availability, RTC timing/drift, startup delay, acquisition duration, NFC/MCU
contention, power-loss behavior, EEPROM/RTC retention and migration on the
board. Measure energy and stack/heap use, including the voltage cases in the
[run template](../measurements/run-results.csv). TMP119 nonvolatile startup
provisioning and power-cycle readback remain pending. Verify the actual APK
signer and upgrade compatibility, and bind any release to exact source and
artifact hashes. Current build manifests explicitly report `release_ready=false`.
