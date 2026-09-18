# R2/R3 byte protocol, version 5

This specification follows
[`densor_multirate.c`](../DentalSensor_StorageProject/Core/Src/densor_multirate.c),
the selected logger/scheduler modules and
[`DensorMultirate.java`](../source_ST25NFCApplication_V3_9/app/src/main/java/com/st/st25nfc/densor/DensorMultirate.java).
See the [operation guide](multirate-implementation.md) for lifecycle and recovery.
Integers are unsigned little-endian unless marked signed. Addresses and lengths
are bytes; region ends are exclusive. Structures are serialized explicitly.

## Identity and memory map

All four variants use `DENSOR2!`, header magic `DNR2` and format version 5.
Storage ID 1 means R3 partitions; ID 2 means R2 shared pool. Timing ID 1 means
FSM (suffix a); ID 2 means RTC time (suffix b). Firmware accepts only its
compiled storage/timing pair. Supported physical EEPROM sizes are 512, 2048
and 8192 bytes, checked against the device density at boot and on Android reads.

| Absolute address | Length | Contents / writer |
| --- | --- | --- |
| 0 | 8 | ASCII `DENSOR2!`; firmware format guard |
| 8 | 4 | Reserved zero; not an R1 pointer |
| 12 | 64 | Immutable session header; firmware |
| 76 | 16 | Status and acknowledgement; firmware |
| 92 | 40 | Pending `DCP5` request; Android |
| 132 | 4 | Request commit marker; Android |
| 136 | 32 | Four pairs of durable pointer slots; firmware |
| 168 | capacity − 168 | Record storage; firmware |

Metadata uses CRC-16/CCITT-FALSE (polynomial `0x1021`, initial `0xFFFF`, no
reflection or final XOR; `123456789` → `0x29B1`), stored LE16. Records and
pointer slots use CRC8 (polynomial `0x07`, initial zero, no reflection or final
XOR; `123456789` → `0xF4`). CRCs are integrity checks, not authentication.

## Header at 12

Offsets in this table are relative to the 64-byte header.

| Offset | Bytes | Meaning |
| --- | --- | --- |
| 0 | 4 | ASCII `DNR2` |
| 4 | 1 | Protocol version 5 |
| 5 | 1 | Storage: 1 partitioned, 2 shared |
| 6 | 1 | Header length 64 |
| 7 | 1 | Timing: 1 FSM, 2 RTC time |
| 8 | 2 | Physical EEPROM capacity |
| 10 | 2 | Log start 168 |
| 12 | 1 | Enabled sensor mask |
| 13 | 1 | Reserved zero; no common record stride |
| 14 | 1 | Oscillator: 0 external crystal, 1 internal RC |
| 15 | 1 | Supported sensor mask `0x0F` |
| 16 | 1 | Reserved zero |
| 17 | 1 | Sensors detected when configured |
| 18 | 1 | Startup delay, 0–59 minutes |
| 19 | 1 | Whole-second lateness tolerance: 0 for base 1, otherwise 1 |
| 20 | 4 | Session ID, equal to accepted Apply request ID |
| 24 | 4 | RTC session epoch, seconds from 2000-01-01; not verified UTC |
| 28 | 2 | Base period, 1–3540 seconds |
| 30 | 2 | LCM of enabled multipliers, 1–65535 |
| 32 | 8 | Four LE16 multipliers in sensor order |
| 40 | 8 | Four LE16 region starts |
| 48 | 8 | Four LE16 region ends |
| 56 | 2 | Checkpoint interval in ticks: `min(LCM, 64)` |
| 58 | 4 | Reserved zero |
| 62 | 2 | CRC16 over header bytes 0–61 |

Sensor order and mask bits are: 0 LIS2DW12 temperature (`0x01`), 1 TMP119
temperature (`0x02`), 2 photodiode (`0x04`), 3 acceleration (`0x08`). Unknown
bits are invalid. Enabled multipliers are 1–65535, disabled multipliers zero;
`base × multiplier` must not exceed 2,592,000 s. The epoch must be at most
`3155759999 − 2592000` so the session fits within the supported calendar.

In shared storage only region 0 is populated: start 168, end capacity.
Regions 1–3 are zero regardless of the enabled sensor mask. In partitioned
storage, enabled regions cover `[168, capacity)` contiguously in sensor order;
disabled regions are zero. Boundaries are four-byte aligned, and each enabled
region holds at least one complete record.

A freshly provisioned tag is STOPPED with mask zero. Header bytes 18–57 are
zero, including epoch, session, schedule and boundaries. No pointer slots or
records are interpreted until configuration succeeds.

## Status at 76

| Relative offset | Bytes | Meaning |
| --- | --- | --- |
| 0 | 2 | Status revision, incremented on writes |
| 2 | 1 | 0 STOPPED, 1 CONFIGURING, 2 RUNNING, 3 ERROR, 4 FULL |
| 3 | 1 | Error code |
| 4 | 1 | Last confirmed sensor presence mask |
| 5 | 1 | Reserved zero |
| 6 | 4 | Last acknowledged request ID |
| 10 | 4 | Session ID, matching header |
| 14 | 2 | CRC16 over status bytes 0–13 |

Error codes: 0 none; 1 I/O/write/readiness failure; 2 bad header; 3 bad pointer;
4 invalid configuration; 5 missing/failed selected sensor; 6 no space;
7 invalid state; 8 acquisition timeout; 9 phase/clock/retained-state loss;
10 ambiguous or invalid recovery state. An I/O failure may prevent the error
from being written. Analog photodiode presence is assumed by board design;
there is no identity register to verify its installation.

## Pending request at 92

| Relative offset | Bytes | Meaning |
| --- | --- | --- |
| 0 | 4 | ASCII `DCP5` |
| 4 | 4 | Request ID, greater than acknowledged ID; no wrap |
| 8 | 1 | Command: 1 Stop/checkpoint, 2 Apply settings/reset |
| 9 | 1 | Requested sensor mask |
| 10 | 1 | Oscillator |
| 11 | 1 | Protocol version 5 |
| 12 | 2 | Base period in seconds |
| 14 | 8 | Four LE16 multipliers |
| 22 | 4 | Expected current session ID |
| 26 | 1 | Startup delay in minutes |
| 27 | 1 | Installed storage ID |
| 28 | 1 | Installed timing ID |
| 29 | 1 | Reserved zero |
| 30 | 8 | Four LE16 allocation counts in four-byte pages |
| 38 | 2 | CRC16 over request bytes 0–37 |

Android clears and verifies the marker at 132, writes/verifies the body in
four-byte chunks, then writes/verifies the request ID as the marker. Firmware
reads marker/body/marker; mismatched IDs, changed markers or a bad CRC leave
the request inactive. IDs at or below the acknowledged ID are ignored.
Only one request may be outstanding.

For Apply, page counts describe the new immutable allocation. R2 uses
`[(capacity − 168) / 4, 0, 0, 0]`; R3 uses the Android allocation described in
the [implementation guide](multirate-implementation.md#storage-and-full-behavior).
The counts must cover the data region exactly and satisfy enabled-stream bounds.
Stop does not apply the proposed schedule or allocation, but still validates
request identity, version, strategies and expected session.

Apply is rejected while RUNNING. After validation, firmware writes CONFIGURING,
fills bytes 136 through the end with `0xFF`, initializes pointer pairs, rereads
the RTC, writes the new header and retained state, then acknowledges RUNNING.
The first-sample epoch includes startup delay. Stop checkpoints the pointers
and acknowledges STOPPED. Incomplete initialization is not automatically retried;
CONFIGURING or inconsistent metadata requires recovery/provisioning attention.

## Durable pointers at 136

Region `i` has two four-byte slots at `136 + 8i` and `140 + 8i`:

| Slot offset | Bytes | Meaning |
| --- | --- | --- |
| 0 | 2 | Absolute next-write address |
| 2 | 1 | Generation, modulo 256 |
| 3 | 1 | CRC8 over bytes 0–2 |

Initialization writes two identical generation-zero pointers to the region
start. Later checkpoints increment the generation and alternate by its low
bit. A CRC-valid pointer must lie within its region and match the record stride
(four-byte alignment for shared storage). Among two valid slots, modular
generation difference 1–127 selects the newer; 128 is ambiguous. Equal
generations must have identical content. Invalid or ambiguous pairs fail closed.
Unused regions have no interpreted checkpoint.

## Records

| Sensor | Payload bytes | Decoding |
| --- | --- | --- |
| LIS2DW12 temperature | 2 signed LE16 | `raw / 256 + 25` °C |
| TMP119 temperature | 2 signed LE16 | `raw / 128` °C |
| Photodiode | 2 LE16 | ADC code 0–4095 |
| Acceleration | 6, three signed LE16 | X/Y/Z, each `raw / 16384` g |

These conversions describe the implemented decoder, not new accuracy claims.
Supply codes 0–15 represent `(18 + code) / 10` volts. Code `0xFF` means absent.
A wake with any temperature or photodiode due requires code 0–15; an
acceleration-only wake requires `0xFF`. On a mixed wake, the same measured supply
code is copied into every partitioned record, including acceleration.

**R2 shared:** `mask:u8 | supply:u8 | payloads in sensor order | crc8 | zero pad`.
CRC covers mask, supply and payloads, excluding padding. Stored length is
`round_up_to_4(3 + sum(payload_lengths))`. A single scalar uses 8 bytes;
acceleration alone uses 12; all channels use 16. Empty ticks write no record.
The mask must match the next nonempty tick implied by the schedule.

**R3 partitioned:** `payload | supply:u8 | crc8`. Temperature/photodiode records
use 4 bytes and acceleration uses 8. CRC covers payload and supply. The general
padding rule adds a zero only when the unpadded length has remainder 3 modulo
4; current record lengths already have remainder zero. Partition identity
supplies the channel; there is no per-record presence mask.

Records contain no timestamp. The `n`th partitioned record belongs to tick
`n × multiplier`; shared records follow successive nonempty scheduled ticks.
Elapsed time is `tick × base`. Independent fixed byte vectors are in
[`multirate-record-fixtures.json`](../tests/multirate-record-fixtures.json).

## Retained RTC state

The first 64 RTC RAM bytes hold two 32-byte copies, separate from EEPROM and
absent from NFC dumps. Board access uses RTC RAM bank zero.

| Copy-relative offset | Bytes | Meaning |
| --- | --- | --- |
| 0 | 2 | ASCII `R5` |
| 2 | 1 | Generation, modulo 256 |
| 3 | 1 | 0 clean, 1 transaction in progress |
| 4 | 4 | Session ID |
| 8 | 4 | Session epoch |
| 12 | 4 | Next base tick |
| 16 | 2 | Phase, `next_tick % LCM` |
| 18 | 8 | Four LE16 working pointers |
| 26 | 4 | Four durable-pointer generation bytes |
| 30 | 2 | CRC16 over bytes 0–29 |

Copies alternate and are read back after writing. Before appending, firmware
persists an in-progress copy; after record writes and any due checkpoint it
advances the schedule and writes a clean copy. Checkpointing occurs when the
next tick is divisible by `min(LCM, 64)` or the LCM, and on closure.

After retained-state loss or an interrupted transaction, recovery scans records
from region starts, validates the durable checkpoint boundary and advances to
the valid tail. It then checkpoints and closes a non-stopped session with an
error. It cannot reconstruct missing measurements or guarantee an all-channel
atomic wake. A stopped session remains stopped; corrupt data before a durable
checkpoint or ambiguous generations can prevent recovery.

## Reading and compatibility

R2/R3 decoding requires a full physical EEPROM dump. The reader validates
header/status, allocations, pointer slots, CRCs, masks, supply and padding,
and scans to the first invalid tail. Corruption before a checkpoint is rejected;
a STOPPED image with a valid uncheckpointed tail is also rejected. Running/full/
error images are labelled snapshots or recovered prefixes. A complete recording
requires STOPPED, error zero and no outstanding request.

R1 v3 uses a different guard, 128-byte metadata and request format; original
legacy and R1 v2 recordings are read-only in the current app. Unsupported
multirate versions are rejected. Export before switching variants and explicitly
reprovision; changing the header's strategy bytes is not a format conversion.
