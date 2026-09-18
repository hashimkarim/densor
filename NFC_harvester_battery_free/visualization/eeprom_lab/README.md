# Densor EEPROM Lab

A local interactive web app focused on **page crossings avoided** by the new
firmware. It compares the baseline, R1, R2a/b (shared pool), and R3a/b
(partitions). Suffix **a = retained FSM**, **b = RTC timer**. Default EEPROM
capacity is **8192 bytes**, with four-byte pages.

Payload lengths and extra sensors are configurable for packing experiments.
The default four-sensor configuration matches the checked firmware fixtures;
edited layouts are explicitly labelled **exploratory**.

## Run

Bash, Node.js 22.18+ and npm. From the repository root:

```sh
./NFC_harvester_battery_free/visualization/eeprom_lab/run.sh
# Or choose a starting port:
./NFC_harvester_battery_free/visualization/eeprom_lab/run.sh 9000
```

From this folder, use `./run.sh [port]`. The default is
<http://127.0.0.1:8767>. If the requested or default port is occupied, the server
tries the next port until one is available. It prints the selected URL and
opens it in your default browser; on a headless machine, open the URL manually.
Stop with Ctrl+C. The launcher runs `npm ci` if dependencies are missing, so
the first run requires network access. You can also install explicitly with
`npm ci` and serve without opening a browser using `npm run dev`.

Calculations run in the browser. No EEPROM writes, connected hardware, uploads,
external data services or database are needed. This is a Sites-compatible
Vinext/React project; the current deliverable is local because the Sites hosting
connector was unavailable. For a different deployment origin, set `SITE_URL`
before building to generate the correct social-preview URLs.

## What to explore

- Set **payload bytes** (1–64) beside each sensor's period. **Add sensor** adds
  a named, enabled stream with a 5 B payload, useful for demonstrating smart
  padding. Up to eight sensors fit the model's one-byte mask. Additional sensors
  can be renamed, disabled or removed; the original four can be disabled.
  Presets adjust schedules while preserving payloads and additional sensors;
  **Reset** restores the original four sensors and payloads.
- **Alignment overhead** (default): crossings avoidable by aligning each write.
  Large counters show how many each new layout avoids relative to the baseline.
- **Payload boundaries**: boundaries inside each sensor payload, excluding
  supply, mask, CRC, padding and pointers. The default XYZ payload is counted
  as one six-byte block. Extra payload boundaries are those beyond the minimum
  `ceil(payload_bytes / 4) - 1`.
- **All boundaries**: every page boundary inside each logical record/pointer write.
  A 16-byte aligned record still has three boundaries; they are necessary.
- **Cross-page writes**: I²C write commands that straddle at least one page boundary.
  The original firmware sends an unsplit record and unsplit two-byte pointer.
  R1/R2/R3 split commands at page boundaries. This count is commands, not boundaries.
- Scrub elapsed time, enter exact seconds, play the scenario, or step to the next
  sample to watch totals grow. Time zero includes the first sample.
- **Matched workload** uses the same original three sensors at 10 seconds in all
  revisions. Other presets show the additional effect of multirate logging.
  TMP119 is excluded from the historical baseline. Rates and record formats can
  change total traffic independently of alignment.
- Page ribbons show the latest stored wake for each revision. Every box is a
  physical four-byte page, arrows mark boundaries, and a red final box is an
  extra page caused by an unaligned starting address. Pointer examples show why
  moving bytes 7–8 to 8–9 eliminates a page crossing on each update.
- Memory maps and the byte inspector expose padding, fields, CRCs, metadata,
  per-stream allocation and simulated hot pointers. Click a tile, select a byte,
  jump to an address or select a partition. Metadata and unused bytes are
  symbolic; the app does not manufacture a complete EEPROM dump.
- JSON export records settings, time, counts, page metrics, allocation, pointers
  and capacity limits, including sensor definitions and packing comparisons.
  It is a simulation summary, not a firmware image.

## Packing and payload savings

**What page packing saves** replays exactly the stored records through the
cursor with padding removed. It preserves sensor fields, supply, CRC, sample
times and region starts. It compares record boundaries and payload boundaries
separately and excludes pointers. The unpadded comparison does not add extra
records when the smaller records leave space. Negative savings are shown as
added crossings. Payload boundaries are a subset of record boundaries; do not
add these two counts together.

The **R3 partitions** table shows each stream's sample count, raw and stored
stride, payload boundaries, extra payload boundaries, and smart-padding savings
for both whole records and their payloads. Baseline comparisons are separate:
they can reflect different sample counts, rates and field order. TMP119 has no
baseline counterpart.

R3 uses `smartPad(payload + 2)`: one supply byte and one CRC byte, plus a zero
only when that total has remainder three modulo four. Default 2/2/2/6 B
payloads already produce 4/4/4/8 B records, so smart padding adds nothing.
Change a payload to **5 B** to get a 7 B raw record and 8 B stored stride.
For four consecutive records beginning at a page boundary, smart padding
reduces record crossings from six to four. The five-byte payload itself still
crosses one boundary per sample in either case. A 3 B payload gives a 5 B
stride: smart padding leaves it unchanged, so later records can be unaligned.

At 240 s with **Matched workload** and default payloads, payload crossings are
62 / 50 / 25 / 25 for baseline / R1 / R2 / R3. Thus R3 avoids 37 payload
crossings relative to baseline, while its additional smart-padding savings are
zero. The comparison distinguishes these two effects.

## Exploratory layouts

Changing payload lengths or adding sensors applies the existing layout rules
to a hypothetical sensor set. These changes update byte maps, partition
allocation, CRC examples, sample counts, capacity limits, page counts and the
JSON export; they do not change the firmware or produce compatible dumps.
Illustrative payload bytes extend with zeros or truncate the default examples.

The model retains each layout's existing header sizes, except R3 reserves an
8 B A/B pointer pair per configured sensor slot (`log_start = 136 + 8 * slots`).
Each enabled R3 stream first receives enough whole pages for one record; the
remaining pages follow the existing stride/rate weighting and bit-order
remainder allocation. Disabled slots retain their pointer reservation. An
undersized tag is rejected if it cannot reserve one record per active stream.
Shared records retain a one-byte mask, limiting exploration to eight sensors.
The baseline-style estimate still excludes TMP119 and includes added sensors;
new non-acceleration sensors use the existing supply-byte convention.
These are geometry assumptions, not a proposed expanded wire protocol or a
claim that firmware headers, RTC RAM or Android support extra sensors.

## Exactly what is counted

For starting byte `a`, length `n > 0`, and four-byte pages:

```text
pages touched          = floor((a + n - 1) / 4) - floor(a / 4) + 1
boundary crossings     = pages touched - 1
extra alignment pages  = pages touched - ceil(n / 4)
avoidable crossings    = extra record pages + extra pointer pages
avoided vs baseline    = baseline metric - revision metric
```

Separate records are counted separately, including repeated touches to the same
physical page. No crossing is counted between the end of one write and the start
of another. Baseline pointer writes touch two pages instead of one; aligned
new-format pointers and checkpoint slots touch one page.

At the default 240-second cursor there are 25 baseline records. Their ten-byte
strides alternate between offsets 1 and 3 within a page: 12 records need an extra
page. Each of the 25 pointer writes also crosses a page. The baseline therefore
has **37 avoidable crossings**, while the new layouts have zero alignment
spill. All three show **37 avoided**. Under the matched workload, baseline and
R1 also differ by exactly 37 total record-plus-pointer page touches.

The scope is **successful logging and periodic pointer checkpoints**. It excludes
provisioning/log clearing, status writes, initialization, explicit stop,
full/error/recovery checkpoints, subsequent baseline full-state attempts,
I²C reads, readiness polling, retries and RTC RAM writes. These are geometry and
software-traffic counts, not measured programming cycles, energy, wear or atomicity.

For R2/R3, periodic checkpoints use the firmware's `next_tick` condition:
`next_tick % min(LCM, 64) == 0 || next_tick % LCM == 0`. Empty sampling ticks
still advance the scheduler and can checkpoint. Each checkpoint writes one
four-byte slot per active pointer, including unchanged stream pointers.
Shared storage uses one pointer; partitions use one per enabled stream.

Capacity simulation checks complete writes before acquisition. One full due
partition closes the whole session; no partial wake is counted. Multi-rate
sessions have a 30-day limit. The baseline's historical strict `end >= 8192`
bound is retained; other selected capacities are explicitly normalized estimates.
The baseline may continue acquiring after writes fail, outside the counter scope.

## Sources and validation

`lib/eeprom.ts` owns the deterministic model; `app/page.tsx` owns the interactive
view. `public/sources/provenance.json` pins the source paths, capture time, hashes
and baseline commit. Copies in `public/sources` make the app and tests portable.
The working-tree snapshot includes the updated R2/R3 naming. Header marker 5 is
not a software revision. Boundaries stay in the session header; Android owns the
allocation calculation. No alternate multi-rate compatibility path is modeled.

Source rules come from:

- Original `main.c` at `7aa87d568ecf9f50d3e38afd571a6753df553919`.
- R1 protocol v3 and `densor_write` in `densor_r1.c`.
- Current R2/R3 protocol, partitioned/shared writers, `mr_commit` and
  `mr_checkpoint`, and Android's `DensorMultirate.allocation`.
- C-produced golden record vectors and full synthetic EEPROM images.

```sh
cd NFC_harvester_battery_free/visualization/eeprom_lab
npm test
npm run typecheck
npm run lint
npm run build
```

Tests verify all 15 record masks against golden vectors, every simulated byte
and pointer in the five complete fixture images, scheduler-pair differences,
all masks and physical densities, full-region preflight, page geometry, default
crossing savings, and checkpoint counting including empty ticks and overlapping
checkpoint intervals. Additional tests cover payload-only crossings, controlled
unpadded comparisons, smart-padding savings, unaligned custom strides, eight
sensors, dynamic metadata/pointers, disabled streams and capacity rejection.
Browser interaction/visual QA and physical hardware tests
are not included in these checks.
