# Sampling lab EEPROM export validation

Date: 2026-09-18. All existing thread logs were preserved; this report and the
phone screenshots are additional evidence.

## Host checks

- 19 Node tests passed, including the unchanged lab-preview checks and nine
  firmware test groups. [Final output](eeprom-host-tests-authoritative.txt).
- All five complete canonical golden images matched their supplied bytes and
  SHA-256 values. Standard CRC8/CRC16 vectors passed.
- 225 combinations (R1/R2a/R2b/R3a/R3b × all 15 masks × 512/2048/8192 bytes)
  decoded successfully using actual Java source from authoritative commit
  `48a8f137aa9dba162e88bdad67b549bfde232d40`.
- Decoded source values after quantization, missing fields, supply, timestamps,
  counts, pointers, CSV revision labels and complete-session status matched.
- Tests cover fractional source origin, unequal temperature periods, empty
  ticks, exact fits, stopping the whole wake before capacity overflow, erased
  tails, negative values, rounding ties, signed limits, invalid periods/LCM,
  disabled sensors and missing due values. CRC/metadata/pointer/padding damage
  is rejected. Python independently extracts a firmware ZIP and its sidecar.
- Both Python HTTP tests and JavaScript syntax checks passed. The local app
  returned HTTP 200 at `http://127.0.0.1:8766/`.

The test compiles a read-only copy of the four Java sources obtained with
`git show` into `software/build/sampling-lab-export-check/`. Another thread
changed the working-tree Android CSV labels during validation; the initial
[working-tree run](eeprom-host-tests.txt) retained here records that label
mismatch. Pinning the oracle to the specified commit restored reproducible
contract testing without reverting those changes. Generated binaries, JSON
sidecars and `matrix-summary.json` are in that build directory.

## Android debug import

Device: S21 FE, SM-G990B, USB serial `RFCT10E2EYM`.
Used the ADB coordinator with an exclusive claim and released it after testing.
Installed the existing `com.st.st25nfc.dbg` debug APK, version
3.11.0-multirate / code 29, with `install -r`; no uninstall or app-data clearing.
The release package was not changed.

Both inputs came from the real CSV, using source window `[0,1800)`, phase 0,
periods `[120,60,30,10]` in firmware order, and 2048-byte capacity. Source SHA-256:
`45f69ea6012272d07618c5e81aa3c703b421e86ef5b9b844750170d319fabd0f`.

| Imported image | Counts: temp1, temp2, light, accel | Last elapsed | Stop reason |
| --- | --- | --- | --- |
| sampling-lab-r3a-v5-2048.bin | 15, 30, 60, 180 | 1790 s | window-end |
| sampling-lab-r3b-v5-2048.bin | 13, 25, 50, 148 | 1470 s | capacity; next wake 1480 s omitted |

- R3a image SHA-256:
  `2abb2b2dff96006077943468dd952b3ee417549301c3e479136dc2976a088b46`.
  Final pointers `[228,352,596,2040]`.
- R3b image SHA-256:
  `488367dfe577864919e17e5c8e5fc2c891f5243e0d1a16953f9f216490f011f6`.
  Final pooled pointer `2044`.

Opened each image through **Debug mode → Open .bin → Android document picker**,
then inspected **Data**. Both reported **Loaded 2048 B**, the correct storage
and RTC revision, **Stopped, acknowledged checkpoint**, and **Complete recording**.
Separate temperature values and 2.6 V supply were shown.

[Partitioned R3a screenshot](eeprom-r3a-import.png) ·
[Shared R3b screenshot](eeprom-r3b-import.png).

The phone was left in the debug Data screen with the shared synthetic image;
the two import files remain in Downloads. No live NFC, firmware execution,
hardware sensor, power or energy claim follows from these checks. Web browser
interaction/visual QA was unavailable in this session.
