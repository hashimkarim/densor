# Densor R1 protocol, version 3

R1 is a software candidate pending the hardware validation described below.
Integers below are unsigned little-endian unless explicitly signed. No C
structure is written to EEPROM. Offsets are in bytes; bounds are exclusive.

## Memory map

| Absolute address | Length | Owner / contents |
| --- | --- | --- |
| 0 | 8 | Firmware format guard, ASCII `DENSOR1!` |
| 8 | 2 | Firmware next-write pointer, LE16 |
| 10 | 2 | Reserved zero; never used for changing metadata |
| 12 | 64 | Firmware capability and immutable session header |
| 76 | 16 | Firmware acknowledgement/status |
| 92 | 32 | Android pending request |
| 124 | 4 | Android commit marker: request ID, zero when incomplete |
| 128 | density − 128 | Combined, padded records |

Read ST25DV system registers at I²C device address 0x57 (HAL address 0xAE):
`MEM_SIZE` at 0x0014–15 is the last RF block number; `BLK_SIZE` at 0x0016 is
block size minus one. Require four-byte blocks and a supported density of 512,
2048 or 8192 bytes. Compare this result to the stored header on every boot and
to Android's RF-reported size. No assumed 8192-byte bound.

Metadata CRC is CRC-16/CCITT-FALSE: polynomial 0x1021, initial 0xFFFF, no
reflection, no final XOR; `123456789` gives 0x29B1. Store CRC LE16. This CRC
protects metadata; **R1 records and the raw pointer have no CRC**.

## Header at 12 (offsets relative to header)

| Offset | Bytes | Meaning |
| --- | --- | --- |
| 0 | 4 | ASCII `DNR1` |
| 4 | 1 | Format version = 3 |
| 5 | 1 | Storage layout = 1 (combined records) |
| 6 | 1 | Header length = 64 |
| 7 | 1 | Time flags = 0; UTC start is not established |
| 8 | 2 | Detected physical memory size |
| 10 | 2 | Log start = 128 |
| 12 | 1 | Session sensor mask |
| 13 | 1 | Session stored stride |
| 14 | 1 | Oscillator: 0 = external crystal, 1 = internal RC |
| 15 | 1 | Supported sensor mask = 0x0F |
| 16 | 1 | Reserved zero |
| 17 | 1 | Sensor presence confirmed when the session was configured |
| 18 | 1 | One-time startup delay, 0–59 minutes; zero disables it |
| 19 | 1 | Reserved zero |
| 20 | 4 | Session ID (APPLY_SETTINGS request ID) |
| 24 | 4 | Accepted configuration ID (same as session ID in R1) |
| 28 | 4 | Common period in seconds |
| 32 | 4 | UTC start = 0; do not interpret as a date |
| 36 | 26 | Reserved zero |
| 62 | 2 | CRC of header bytes 0–61 |

Mask bits are 0 = LIS2DW12 temperature, 1 = TMP119 temperature, 2 = photodiode,
3 = acceleration. Unknown bits are invalid. A newly provisioned device has
mask/stride/period/session/configuration ID zero and is stopped. A configured
session must have a nonzero mask and ID, matching calculated stride, and a
supported period. R1 periods are 1–59 seconds or whole minutes 1–59
(60–3540 seconds).

UTC is deliberately invalid: an NFC request can wait an unknown time for a
wake, so a phone timestamp in that request is not the actual first-sample
time. Display/export sample index and nominal elapsed seconds, not fabricated
wall-clock times. Nominal elapsed times assume uninterrupted normal wakes;
R1 cannot reconstruct missed wakes or power-loss gaps.

## Status at 76

| Offset | Bytes | Meaning |
| --- | --- | --- |
| 0 | 2 | Status revision, incremented on state transitions |
| 2 | 1 | 0 STOPPED, 1 CONFIGURING, 2 RUNNING, 3 ERROR, 4 FULL |
| 3 | 1 | Error code (below) |
| 4 | 1 | Last confirmed sensor presence mask |
| 5 | 1 | Reserved zero |
| 6 | 4 | Last acknowledged request ID |
| 10 | 4 | Session ID; must match header |
| 14 | 2 | CRC over bytes 0–13 |

Errors: 0 none, 1 bus/write/readiness failure, 2 invalid header, 3 invalid
pointer, 4 invalid configuration, 5 missing/failed selected sensor, 6 full,
7 invalid state, 8 acquisition timeout. Presence is last-confirmed information,
not a real-time discovery result while the MCU is off. The analog photodiode
has no digital identity: its presence is a board requirement, not a claim that
an ADC reading can distinguish an absent photodiode from darkness.

## Pending request at 92

| Offset | Bytes | Meaning |
| --- | --- | --- |
| 0 | 4 | ASCII `DCP3` |
| 4 | 4 | Request ID, strictly greater than acknowledged ID; no wrap |
| 8 | 1 | Command: 2 APPLY_SETTINGS; all other values rejected |
| 9 | 1 | Sensor mask |
| 10 | 1 | Oscillator |
| 11 | 1 | Request version = 3 |
| 12 | 4 | Common period in seconds |
| 16 | 4 | Expected current session ID |
| 20 | 1 | One-time startup delay, 0–59 minutes |
| 21 | 9 | Reserved zero |
| 30 | 2 | CRC over bytes 0–29 |

Only one request may be outstanding. Android validates the complete R1 guard,
header, status and actual RF density before any new-format write. It writes a
zero commit marker and verifies it, writes/verifies the pending body, then
writes/verifies the request ID as the marker. Firmware reads marker/body/marker;
both markers must match the body ID and CRC must pass. Incomplete requests are
ignored, never made active. Android shows pending separately from acknowledged
active settings and refreshes on a later tap/wake. CRC is integrity checking,
not authentication or an atomic-write guarantee.

R1 has one operation: **apply settings, reset the log and record on the next
normal wake**. It may replace a running, full or errored recording. A reset uses
the current settings with a new request ID. There are no STOP/START commands or
mandatory export/start workflow. Save the existing recording before applying
settings or resetting; additional samples may arrive until the request is
accepted. The app does not claim that an earlier live snapshot contains those
later samples.

Firmware validates the request and selected sensors before changing the header.
It writes CONFIGURING first, resets the pointer, writes the new immutable
header, then acknowledges RUNNING. With startup delay zero it acquires the first
sample on that wake. With 1–59 minutes it performs no acquisition, schedules that
one-time delay and powers off. The next wake takes the first sample, and subsequent
wakes use the common sampling period. A reset reuses the configured delay.

The durable acknowledgement consumes the one-time request before sleeping;
duplicate requests and ordinary MCU boots do not repeat the delay. No additional
periodic polling wake or EEPROM write is needed. As in the original firmware,
power loss after acknowledgement but before successful RTC power-off can shorten
or skip the delay; R1 does not recover an absolute startup deadline. RUNNING with
zero samples can therefore mean waiting for the first sample. Android displays
the configured delay, without claiming a measured countdown. Nominal elapsed time
starts at the first sample, not at request submission or acknowledgement.
An interrupted initialization is rejected on reboot; R1 does not recover it
atomically. Firmware log/status and Android request regions are disjoint, and
acquisition/initialization execute serially on the MCU. Duplicate acknowledged
requests are ignored. Exhausted request IDs require export and reprovisioning.

Except for this one-time startup delay, every ordinary wake, including full/error
states, uses the configured common
period. Before valid settings exist, the default is 120 seconds. There is no
separate fast command-polling schedule. Requests wait for the next regular wake;
full/error states skip acquisition until new settings/reset are accepted. A
latched EEPROM programming/readiness failure retains the existing fault-halt
behaviour below. Real idle/sampling/fault power remains unmeasured.

Version 3 restores the original optional startup delay without changing record
payloads or addresses. Version 2 had the same layout with zero at header offset
18 and pending offset 20, and no delay. Android reads version-2 recordings but
requires version-3 firmware for configuration; export before flashing and explicit
reprovisioning. Version 1 was the unpublished session-control prototype and is
unsupported. Original legacy recordings remain readable. Older apps reject the
version-3 header.

## Records

Fixed order: supply byte; optional old temperature LE signed16; optional
TMP119 LE signed16; optional photodiode LE unsigned16; optional acceleration
X/Y/Z LE signed16; zero padding to a multiple of four. Session fields never
change while recording. Raw TMP119 bits are preserved (I²C MSB-first bytes
are explicitly converted to LE). Old temperature is `signed16 / 256 + 25` °C;
TMP119 is `signed16 / 128` °C. Acceleration retains the original ±2g scaling
`signed16 / 16384` g. Photodiode is the raw 12-bit ADC code.

Supply codes 0–15 mean 1.8–3.3 V in 0.1 V steps; 0xFF means unavailable.
Other values are invalid. Acquire supply once if temperature or photodiode is
enabled; acceleration-only stores 0xFF. Never OR voltage into either temperature.
Padding must be zero and is not a sample. Strides: one temperature 4; both 8;
one temperature + photodiode + acceleration 12; all four streams 16 bytes.

Check the whole padded record fits before transmission. Split writes at
four-byte page boundaries. After each transmission, bounded ACK polling must
confirm programming completion. Advance RAM pointer only when all pages are
complete; write the two-byte pointer once; confirm its completion before
power-off. An exact fit is valid. Reject pointers below 128, above density,
not four-byte aligned, or not on the session's stride. FULL stops acquisition.

An EEPROM transmission/readiness failure latches a fault for the wake. Firmware
does not deliberately switch power off while programming completion is unknown;
it halts without reset after attempting to report the error. An unresponsive bus
can prevent durable error reporting. Bench recovery and energy measurement must
cover this fault path. Presence changes add a status write; normal unchanged
presence adds no metadata programs. ADC sampling uses 160.5 cycles for the
photodiode/VREFINT pair, with the first pair discarded after calibration.

R1 has no tail scan, checkpoint, record CRC or torn-write recovery. An aligned
but corrupted raw pointer can be undetectable. Power loss can leave an orphan
record, corrupt the pointer, or interrupt metadata. Do not claim a multi-page
record is atomic. An invalid header/status/configuring state requires stopped
recovery/export and explicit reprovisioning, never automatic reinterpretation.

## Legacy detection and migration

New format requires the entire guard and internally consistent header/status,
CRC, bounds, version, reserved bytes and capabilities. Any recognizable new
guard/header with invalid metadata is unsupported, never a legacy fallback.
Legacy is recognized only from the original nine-byte header with permitted
enable bits (0xAF), a positive valid BCD period, valid BCD delay, a pointer in
[9, physical density], and whole records under the original enabled-field
stride. Neither plausibility of temperature nor a spare byte is a discriminator.
Keep the original ten-byte `future1` and two-byte `future2` interpretation.
Legacy reading is read-only in the new app. Unknown layouts are unsupported.

Export the original binary before flashing/provisioning. Ordinary R1 firmware
does not overwrite a legacy or corrupt layout on boot. A separate explicit
SWD provisioning operation invokes the firmware's format operation after
export; it commits the guard last and leaves the device stopped. Android
never initializes a legacy tag, so it cannot accidentally send R1 configuration
to the original firmware. Do not downgrade firmware without export and an
explicit layout reset. Older Android versions must not configure R1 tags.

Every NFC recording read compares the immutable header/status and pointer
before and after the data read; a changed snapshot is rejected and retried by
the user. Both binary and CSV export are available during logging. A running
export is explicitly a **live snapshot**, never a completed session. Full/error
exports contain the stable committed prefix. The binary includes the header;
CSV preserves separate temperatures, disabled/unavailable columns and time
validity. Hardware power-off is required if no samples may arrive between a
live export and the next settings/reset acknowledgement.

## Hardware evidence and release gates

Sources in `NFC_harvester_battery_free/hardware/datasheets/`: ST25DV DS10925 rev 11 pp.60, 64–68,
141; TMP119 SNIS236 pp.7–8, 16–17, 33–39; LIS2DW12 DS11811 rev 8 pp.16, 35–39.
TMP119 unaveraged conversion maximum is 17.5 ms. Use 0x0400 shutdown,
0x0C00 one-shot, ready bit 0x2000 and device ID 0x2117, with finite bus/readiness
timeouts. No ALERT interrupt and no automatic nonvolatile writes. Shutdown
promptly on every wake, including when unselected; include startup energy.
Unaveraged samples do not inherit the accuracy specification for eight averages.

Hardware availability: none confirmed for this implementation. Electrical
operation, fitted density, stack high-water, NFC timing, real sensor logs and
energy at 2.6/2.2/1.8 V remain **unmeasured**. The build does not satisfy those
release gates.
