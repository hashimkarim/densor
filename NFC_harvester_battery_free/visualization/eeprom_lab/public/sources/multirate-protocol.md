# Densor R2/R3 EEPROM protocol

R2 uses a shared pool; R3 uses partitions. Suffix a selects the retained FSM and
suffix b selects the RTC timer. Both families use one EEPROM protocol and app.
All integers are little-endian; no C structure is persisted. CRC16 is
CCITT-FALSE (as in R1). CRC8 uses polynomial 07, initial 00, no reflection or final
XOR (`123456789` = F4). CRC detects errors; it does not guarantee atomic writes.

The firmware revision is derived from storage and timing: storage 2 → R2,
storage 1 → R3; timing 1 → a, timing 2 → b. The common signatures are `DENSOR2!`
and `DNR2`. The header format byte is fixed at 5 as a byte-layout validation
marker; it is not a firmware revision. There is no alternate multi-rate format.
Unknown DENSOR/DNR formats are rejected. Legacy and R1 remain readable; current
R1 settings are also supported.

The [synthetic EEPROM fixtures](fixtures/bin-dump-export/manifest.json) include
complete 2048-byte examples for R1, R2a, R2b, R3a and R3b. Each FSM/RTC pair has
identical records; these completed examples differ only at absolute byte 19
(scheduler) and bytes 74–75 (header CRC). R3 adds no per-record timestamps.
The manifest records sample values, periods, counts, pointers and file hashes.
[`DumpExportCheck.java`](../tests/DumpExportCheck.java) reads these full images
through the Android pure-Java decoder and reports their revision and sample counts.

## EEPROM map

| Absolute address | Bytes | Content |
|---|---:|---|
| 0 | 8 | `DENSOR2!`, committed last by explicit SWD provisioning |
| 8 | 4 | zero (no R1 pointer) |
| 12 | 64 | session header below |
| 76 | 16 | status: sequence16, state8, error8, detected8, zero8, acknowledged request32, session32, CRC16 |
| 92 | 40 | pending configuration below |
| 132 | 4 | request ID commit marker; host clears it, writes/verifies request, then writes/verifies ID |
| 136 | 32 | four pairs of 4-byte A/B pointer slots; shared uses pair zero only |
| 168 | capacity−168 | records (512/2048/8192-byte physical tags only) |

Header offsets: 0 `DNR2`; 4 version=5; 5 storage (1 partitioned, 2 shared);
6 length=64; 7 timing (1 retained FSM, 2 RTC); 8 capacity16; 10 log start16=168;
12 sensor mask; 13 zero; 14 oscillator (0 XT, 1 RC); 15 supported mask=15;
16 zero; 17 sensors confirmed at initialization; 18 startup delay minutes 0–59;
19 continuity tolerance seconds (0 for T0=1, otherwise 1); 20 session/request ID32;
24 epoch32; 28 T0 seconds16 (1–3540); 30 LCM16 (1–65535);
32 four multipliers16 (disabled=0); 40 four region starts16; 48 four region ends16;
56 checkpoint interval16=min(LCM,64); 58–61 zero; 62 CRC16 over bytes 0–61.
Shared has only region zero; disabled partition bounds are zero. Blank provisioned
headers have zero mask/session/epoch/rates/bounds/checkpoint/tolerance/delay.

Pending offsets: 0 `DCP5`; 4 monotonically increasing request ID32; 8 command
(1 stop/checkpoint, 2 apply and start new session); 9 mask; 10 oscillator;
11 version=5; 12 T0 seconds16; 14 four multipliers16; 22 expected session32;
26 startup minutes; 27 storage; 28 timing; 29 zero; 30 four region sizes16 in **4-byte pages**, calculated by Android;
38 CRC16 over bytes 0–37. Disabled partition sizes must be zero. In shared mode,
size zero holds the entire pool and sizes 1–3 are zero. Firmware reads the
marker twice, rejects wrong session/strategy/capabilities, and acknowledges only
after durable work. Incomplete host writes are ignored. Stop and export before
applying a new configuration to a running recording. Request IDs never wrap.
States: 0 stopped, 1 configuring, 2 running, 3 error, 4 full. Errors 0–8 retain
R1 meanings; 9 means schedule phase lost, 10 ambiguous/corrupt recovery state.

## Scheduling and clock

Streams/bits/payload order: 0/01 LIS2DW12 temperature (signed16), 1/02 TMP119
(signed16), 2/04 photodiode (unsigned16 ≤4095), 3/08 acceleration (three signed16).
At tick zero every enabled stream is due; otherwise tick modulo multiplier=0.
Periods may differ between temperatures. Every product T0×mi must be ≤30 days;
LCM uses checked arithmetic without a schedule table. Maximum session elapsed
time is 30 days. Empty slots advance state without a sensor conversion or record.

For periods in seconds, choosing T0 = GCD(periods) gives the largest common RTC
tick. LCM(periods) is the repeating cycle duration, not the wake interval. For
120/30/10 s, wake every 10 s, sample at multiples of 12/3/1 ticks, and repeat the
FSM after 12 ticks (120 s). Smaller common divisors are also valid base periods.

RTC time is integer seconds since 2000-01-01, supported calendar 2000–2099 in
24-hour mode. A single burst reads registers 01–06 coherently (AM18X5 datasheet
§5.5); STOP/12-hour mode and invalid BCD/calendar fields are errors. Calendar
rollovers are supported within the century; century wrap/backwards changes are
discontinuities. Epoch is accepting wake time plus startup delay; no tick exists
before epoch. UTC synchronization is not claimed. Both modes read the RTC on
every wake. FSM retains stage and next tick; RTC derives tick by division and
checks next tick to prevent duplicates. Wakes before the next slot acquire
nothing; backwards jumps before the last committed slot, skipped slots, or
lateness exceeding tolerance close the session. Sub-second jumps and changes
within tolerance cannot be detected with this representation. Nominal elapsed
timestamps have the advertised tolerance. Neither mode backfills samples.

## Records and allocation

Supply code is 0–15 → (18+code)/10 V, FF unavailable. One measurement per wake
when any temperature/photodiode is due; acceleration-only wakes use FF. On a
mixed wake the acceleration partition also stores the measured code.

Partitioned records (R3a/R3b): payload, supply, CRC8 over payload+supply. Strides 4/4/4/8;
the general smart-padding rule adds a zero only when payload+supply+CRC modulo
4 is 3: `stored = unpadded + (unpadded % 4 == 3 ? 1 : 0)`. Remainders 0, 1 and 2
are unchanged; rounding those up does not reduce page crossings. The current
unpadded records include supply and CRC and are already 4/8 bytes. Android allocation
reserves one record per enabled stream first, then divides
remaining whole pages proportionally to stride/mi using integer weights
stride×(LCM/mi); floors each quota and gives leftover pages to enabled streams in
ascending bit order. Android sends the resulting four page counts with the rates; firmware only
validates that enabled regions hold at least one record, disabled regions are
empty, and the contiguous page-aligned regions exactly cover record memory.
It converts the supplied counts into start/end addresses without computing rate
weights or redistributing space, and commits them in the new session header.
The reader uses those actual header boundaries, including valid non-proportional
layouts. Boundaries freeze for the session. Preflight every due
stream before any acquisition/write; any full partition closes the whole session.

Shared records (R2a/R2b): mask, supply, fixed-order payloads, CRC8 over preceding bytes,
zero padding to 4-byte alignment: `stored = (unpadded + 3) & ~3`. Unlike smart padding,
every record in the mixed pool starts and ends on a page boundary.
One 2-byte payload uses 8 bytes; all streams
use 16. Zero/unknown masks, unexpected scheduled masks, bad supply/CRC/padding
or out-of-bounds lengths end the valid prefix. Acquire all due fields first.

## Durability and recovery

Pointer slot: address16, generation8, CRC8 over first three bytes. A valid-CRC
out-of-bounds address is an error. Modulo generation difference 1–127 selects
newer; 128 or differing addresses at equal generation is ambiguous. At least one
valid slot is required. Both equal copies are initialized at generation zero.
Checkpoint every min(LCM,64) committed ticks, also at each complete LCM cycle, and on stop/full/error. Partition
checkpoints are independent; power loss may leave different stream prefixes.

64 of the AM1805's 256 physical RAM bytes hold two 32-byte copies (physical
offsets 0/32 through standard RAM window 40–7F with XADS=0). Copy offsets:
0 `R5`; 2 sequence8; 3 transaction (0 clean, 1 append/checkpoint in progress);
4 session32; 8 epoch32; 12 next tick32; 16 FSM stage16; 18 four hot pointers16;
26 four durable generations8; 30 CRC16. Copy selection uses the same modulo
rules. Bounds, session, epoch, stage/tick and reserved pointer state are checked.

Before any append/checkpoint, persist and read-verify an in-progress copy to the
alternate slot. Only then write data, waiting for bounded EEPROM programming
completion after every page. Checkpoint if due, advance scheduler once, and
persist/read-verify a new clean copy. A torn final copy leaves the newer marker;
an older valid clean cache must never be used to ignore that marker. Failure to
confirm a write stops further writes/acquisitions on that wake.

Lost/ambiguous/in-progress retained state causes durable data recovery and closes
the session with error 9/10; it never establishes phase or resumes sampling.
Recover partition prefixes independently. Shared recovery scans from the start
to validate scheduled masks and checkpoint boundaries, then scans its tail;
checkpoint alone does not encode a tick. Reject corruption before a checkpoint.
Stop at the first invalid record; never scan past it looking for plausible data.
FF initialization has invalid CRC for every partition stride and an invalid
shared mask. Persist configuring status first, fill all prospective log bytes
with FF and initialize pointer pairs, then commit the new header, retained state,
and running acknowledgement. Interrupted initialization requires explicit
reprovisioning if metadata is torn; no old tail can become a new recording.

NFC reads use full physical dumps for R2/R3 and recheck metadata. Running/error/full
reads are labelled snapshots/recovered prefixes. Only a stopped acknowledged
request with checkpointed pointers is a complete export. Firmware never modifies
pending bytes, and the app never modifies active header/status/pointers. Strategy
changes require export, the matching firmware image and explicit provisioning.
Stopped/full/error wakes use the configured base interval (120 s before initial
configuration), with no additional five-second command poll.

Physical power-cut, sensor, RF concurrency, stack high-water and energy validation
remain unmeasured without a board. CRC collisions and within-tolerance clock
changes are limits of this protocol, not tested atomicity guarantees.
