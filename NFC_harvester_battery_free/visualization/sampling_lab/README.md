# Densor sampling lab

A small local webapp for the existing [synthetic multirate dataset](../../data/synthetic_multirate/README.md).
Set independent rates for acceleration (all three axes together), light,
temperature 1 and temperature 2; compare acquired samples with the 100 Hz
reference; download the selected data as binary files.

Exports include **R1 v3 and R2a/R2b/R3a/R3b v5 EEPROM images** for Android debug
import, plus the original lab-preview format. Each firmware ZIP contains one
full-capacity `.bin` and a JSON sidecar. Lab `.preview.bin` files remain a separate
interchange format and cannot be imported as EEPROM images.

## Run

The launcher requires Bash and Python 3.10+. From the repository root:

```sh
./NFC_harvester_battery_free/visualization/sampling_lab/run.sh
# Or choose a starting port:
./NFC_harvester_battery_free/visualization/sampling_lab/run.sh 9000
```

From this folder, use `./run.sh [port]`. The default is
<http://127.0.0.1:8765>. If the requested or default port is occupied, the server
tries the next port until one is available. It prints the selected URL and
opens it in your default browser; on a headless machine, open the URL manually.
Stop with Ctrl+C. No npm install or build is needed. To serve without opening
a browser, use `python3 serve.py [--port PORT]` from this folder.
Use a modern browser on localhost (Web Crypto, ES modules, canvas and
ResizeObserver). Opening `index.html` directly as a file will not work.
The default server binds only to loopback and serves app assets plus
`samples.csv`, `metadata.json` and `events.csv`; it does not expose the repository.

The dataset remains in `data/synthetic_multirate/dataset/`; there is no duplicate
copy. If missing, follow its README to generate it. The app verifies the samples
SHA-256 against the metadata before use. Nothing is uploaded; sampling and file
creation run in the browser. No network dependencies or external assets.

## Use

1. Pick **Multirate exploration** (25 Hz acceleration, 1 Hz light, 0.1 Hz for
   each temperature), **Original** (all every 120 s), or **Firmware comparison**
   (acceleration every 10 s, light every 30 s, each temperature every 120 s).
2. Drag each enabled sensor's rate slider, in Hz or seconds between samples.
   Its current value appears beside the sensor name. The sliders use a
   logarithmic scale. **Snap periods to GCD** is on by default: moving one
   slider aligns every enabled sensor to multiples of a common whole-second
   step, starting from the current periods' GCD (fractional periods are rounded
   to seconds when computing that step). The step stays fixed during a drag or
   keyboard adjustment. If the schedule exceeds the firmware LCM limit, the
   app increases the step until it fits. All changed values appear immediately.
   R1 aligns every enabled sensor to the moved slider's nearest valid common
   period. Disabled sensors retain their settings.
   Arrow keys move by a snapping step; Home/End select the available bounds.
   Switching units preserves the exact period; snapping applies to periods in
   both Hz and seconds. Turn snapping off for arbitrary fractional periods and
   rates up to the reference rate, including the 25 Hz exploration preset.
   Temperature rates are independent. Firmware exports map temperature 1 to
   LIS2DW12 and temperature 2 to TMP119 as an explicit synthetic convention.
3. Expand **Export interval** (collapsed by default). Use its sliders to choose
   the export interval `[start, end)` and the common phase, measured as
   a delay from interval start. Each sensor first samples at `start + phase`,
   then every configured period. Rates may be fractional but cannot exceed
   the reference rate. Interval sliders advance in 0.01 s steps and the phase
   in 0.001 s steps; their bounds keep the first sample inside the interval.
   Invalid settings disable export.
4. Move the preview window or jump to an event. Preview zoom does **not** change
   the export interval. Switch between **Reference & samples** (reference line,
   acquired dots and optional last-value hold) and **Resulting signal** (only
   sampled values joined by straight lines, without reference, dots or event
   shading). Both views follow the same rate sliders and time window. Resulting
   curves stop at the first and last sample; a lone sample cannot form a curve.
   Connecting lines visually interpolate, without adding measurements or
   changing exports. The 100 Hz reference line preserves per-pixel minima/maxima
   for large views.
5. Select the **Binary format**. Lab preview saves one `.preview.bin` per enabled
   sensor and `manifest.json`. Firmware exports also offer an **EEPROM capacity**
   selector (512, 2048 or 8192 bytes; default 8192), then download one full image such as
   `synthetic-r3a-v5-8192.bin` and a same-named JSON sidecar inside a ZIP. Extract
   the `.bin` and use **Open .bin** in the Android **debug build**. The release
   app has no virtual-tag importer. The ZIP itself is not an importable image.

For a quick compatible export, choose the **Firmware comparison** sampling
preset with R2a/R2b/R3a/R3b, or **Original** with R1. With GCD snapping enabled,
selecting a firmware format or enabling a sensor also aligns the setup before
sampling. With snapping off, arbitrary slider values may produce fractional
periods; firmware exports reject them without rounding. The 25 Hz exploration
preset remains available through lab preview. An inline message explains
unsupported firmware settings, while plots remain usable. Snapping changes the
visible sampling settings, so plots and exported periods always agree.

For scheduled time `t`, the sampler selects the newest reference row with
`time_s <= t`. It does not interpolate, average or anti-alias. The schedule is
computed directly from sample number to avoid accumulating period errors;
rounding within a few floating-point ULPs is corrected at exact grid boundaries.
The recording endpoint is excluded. Events only annotate plots; neither event
labels nor ground-truth curves enter the sampler or the binary payloads.

Observation counts count a three-axis acquisition once; measurement values
count its three axes separately. Reference rows used counts unique source rows
across enabled sensors, out of the full dataset. Display reduction never changes
exported records. Firmware exports validate schedule limits and truncate at
physical EEPROM capacity; lab preview retains all samples. Plots always show
the selected source window, while the firmware export summary shows stored
counts and the first omitted wake. Neither view models measured startup time,
energy or sensor bandwidth. Low rates can miss/alias events.

## Provisional binary format: lab preview v1

Each file is little-endian with a 32-byte header, followed by fixed-size records.
All times are absolute elapsed seconds from the source recording start.

| Offset | Type | Meaning |
| --- | --- | --- |
| 0 | 8 bytes | ASCII `DSMRLAB` followed by NUL |
| 8 | uint16 | Lab format version, 1 |
| 10 | uint8 | Stream ID: accel=0, light=1, temp1=2, temp2=3 |
| 11 | uint8 | Channel count: acceleration=3, others=1 |
| 12 | uint32 | Record count |
| 16 | float64 | Requested sample period, seconds |
| 24 | float64 | First scheduled observation, seconds |

Each record contains `float64 scheduled_time_s`, `float64 source_time_s`,
then one float32 per channel in the manifest's column order. Acceleration
records are 28 bytes; others are 20 bytes. Float32 values use the CSV's physical
units (g, normalized light, °C), with normal float32 rounding. There is no raw
ADC conversion, supply telemetry, EEPROM metadata, record CRC or padding in
this preview format. ZIP entries have standard CRC32 integrity checks.

The manifest includes actual source SHA-256, seed, reference rate, enabled
streams, sample periods, interval, phase, counts, units and the format schema.
It explicitly declares `firmware_compatible: false`. Export is deterministic
for a given dataset and configuration. File size here is not EEPROM usage.

For example, decode a light file using Python's standard library:

```python
import struct
from pathlib import Path

data = Path('light.preview.bin').read_bytes()
magic, version, stream_id, channels, count, period, first = struct.unpack_from('<8sHBBIdd', data)
assert magic == b'DSMRLAB\0' and version == 1 and stream_id == 1 and channels == 1
assert len(data) == 32 + count * 20
records = list(struct.iter_unpack('<ddf', data[32:]))
```

## Firmware EEPROM exports

`sampler.mjs` owns source selection; `exporters.mjs` owns byte encoding.
`EXPORT_ADAPTERS` lists the lab format and five firmware targets. The latter
use `buildFirmwareBundle(dataset, sampledStreams, config, {revision, capacity})`.
The export contract follows the preserved
[multi-rate protocol](../eeprom_lab/public/sources/multirate-protocol.md) and
[R1 protocol](../../software/docs/r1-protocol.md), checked against the Android decoder at
commit `48a8f137aa9dba162e88bdad67b549bfde232d40`.
Revision names follow the later naming correction below: R2 means shared pool,
R3 means partitions, and the suffix selects the scheduler. The pinned decoder
and original golden filenames predate that correction; their wire bytes still
match. The exporter has no format-4 compatibility code.

| Revision | Wire version | Storage | Scheduler |
| --- | --- | --- | --- |
| R1 | 3 | Combined | Common period |
| R2a | 5 | Shared pool | FSM |
| R2b | 5 | Shared pool | RTC timer |
| R3a | 5 | Partitions | FSM |
| R3b | 5 | Partitions | RTC timer |

R2/R3 use `DENSOR2!` / `DNR2`, with no timestamps in records. FSM/RTC pairs
(R2a/R2b and R3a/R3b) with identical settings differ only at byte 19 and header
CRC bytes 74–75. Header boundaries, Android allocation and padding are unchanged.
Disabled multipliers and unused metadata are zero. Record tails stay erased
(`FF`), final pointer copies agree, pending requests/markers are zero, and
every image is a STOPPED/error=0 offline recording. It contains no retained
RAM and cannot resume a device. Capacity truncation is recorded in the sidecar,
not encoded as a firmware FULL error.

R2/R3 periods must be integral seconds, at most 2,592,000 s. The encoder chooses
the largest common divisor no greater than 3540 s as T0, requires multipliers
and their LCM at most 65535, and limits recorded elapsed time to 30 days.
R1 requires a common period of 1–59 s or whole minutes through 3540 s; mixed
rates are rejected by the encoder. With UI snapping enabled, choosing R1 first
sets a common period visibly on all enabled sliders. Source origin is
`start + phase`; firmware elapsed zero refers to that position. Startup delay
is zero and multi-rate epoch is the synthetic value 100000 seconds since 2000.

Firmware field order is temp1, temp2, light, then acceleration. Raw conversions
are `round((temp1-25)*256)`, `round(temp2*128)`, `round(light_norm*4095)` and
`round(accel_g*16384)` per axis. Rounding is JavaScript `Math.round`, including
ties toward positive infinity. Nonfinite values, light outside [0,1], and raw
integer overflow are rejected; there is no saturation or wraparound. This
mapping does not claim physical sensor models or calibrated optical units.

Supply is explicitly assumed to be 2.6 V (code 8) on any wake containing
temperature or light; acceleration-only wakes store `FF`. All due partition
records receive the same wake's supply byte. No voltage bits enter temperature
payloads. Android-proportional partitions are fixed before writing. Serialization
preflights the whole wake and stops before it if any due record cannot fit.

The JSON sidecar records source SHA-256, window/phase/origin, channel mapping,
requested/encoded periods, conversion/rounding/clipping policy, assumed supply,
schedule, allocations, pointers, per-stream counts and first omitted wake with
stop reason `window-end` or `capacity`. It is the synthetic provenance; reserved
EEPROM bytes never contain a synthetic flag.

## Checks

Node.js 18+, Python 3.10+ and a JDK (`javac`/`java`, tested with 11); no third-party
test dependencies. Git must contain the authoritative commit above; the firmware
test reads its four Java decoder sources into the ignored build directory so
concurrent worktree edits cannot change the compatibility contract:

```sh
node --test NFC_harvester_battery_free/visualization/sampling_lab/tests/*.test.mjs
python3 -m unittest discover -s NFC_harvester_battery_free/visualization/sampling_lab/tests -p 'test_*.py'
```

Tests use the real dataset, check preset counts, grid/off-grid and phased
schedules, end exclusion, disabled streams and malformed input; verify a fixed
binary golden vector; and independently unzip/decode every record of a mixed
bundle with Python. Firmware checks match all five canonical golden images
by storage/scheduler byte for byte, exercise all 225 revision/mask/capacity combinations against the
actual pure-Java Android decoder, and compare decoded values, missing fields,
timestamps, counts, pointers and its historical CSV revision labels. Separate
checks verify the current webapp labels, filenames, sidecars and header choices.
They cover exact fits,
whole-wake truncation, empty ticks, unequal temperatures, signed values, CRCs,
padding corruption, invalid schedules, disabled sensors and erased tails.
Generated test artifacts are under `software/build/sampling-lab-export-check/current/`;
earlier artifacts in the parent directory retain their original names.
HTTP checks cover assets, data and the restricted file surface.

Two real-CSV images (partitions + RTC and shared pool + RTC, 2048 bytes) were also opened
through the native **Open .bin** picker in `com.st.st25nfc.dbg` 3.11.0-multirate
on the S21 FE. Both showed complete stopped recordings. Their current names
are R3b and R2b; the preserved phone evidence uses the earlier R3a and R3b names.
See the
[phone evidence](../../../logs/codex/01a0b51a-3652-72d2-a6a2-569deb55c310/qa/eeprom-export-validation.md).
Browser interaction/visual QA and physical NFC/firmware/energy measurements
are not covered by these checks.
