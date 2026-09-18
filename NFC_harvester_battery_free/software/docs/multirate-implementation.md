# R2/R3 implementation and operation

This guide describes the implemented protocol-v5 firmware in this checkout.
See the [software overview](../README.md) for builds, the
[byte protocol](multirate-protocol.md) for serialization, and
[validation record](multirate-validation.md) for evidence and open gates.

## Changes from R1

R1 samples all enabled channels on one common period and writes one padded
record per wake. R2/R3 retain independent LIS2DW12 and TMP119 temperature
selection, photodiode and acceleration, but give each stream its own period.
Both temperature sources can be enabled together, separately, or disabled;
at least one of the four streams must be enabled.

| Behavior | R1, protocol v3 | R2/R3, protocol v5 |
| --- | --- | --- |
| Acquisition | All enabled streams each wake | Only streams due on that base tick |
| Storage | Combined records from byte 128 | Shared pool or partitions from byte 168 |
| Integrity | Metadata CRC; raw pointer and records have no CRC | Record CRC8, paired pointer checkpoints, retained-state CRC16 |
| Scheduling | One period; no missed-wake reconstruction | Retained phase plus RTC checks; timing failures stop the session |
| Configuration | Apply/reset can replace a running session | Stop and acknowledge before Apply/reset |
| Progress | EEPROM pointer every successful wake | RTC RAM each tick; EEPROM checkpoints periodically and on close |

The protocol numbers and revision names are different concepts: R2a, R2b,
R3a and R3b all use version 5. Internal storage ID **1 is partitioned (R3)**
and **2 is shared (R2)**. The `DENSOR2!`/`DNR2` magic is common to both.

## Source organization

All variants share one board entry point and acquisition path in
[`densor_board.c`](../DentalSensor_StorageProject/Core/Src/densor_board.c).
[`densor_multirate.c`](../DentalSensor_StorageProject/Core/Src/densor_multirate.c)
owns metadata, configuration, retained state, checkpoints and recovery.
The Makefile links exactly one `densor_logger_shared.c` or
`densor_logger_partitioned.c`, and one `densor_scheduler_fsm.c` or
`densor_scheduler_rtc.c`. Common byte I/O and CRC16 helpers remain in
`densor_r1.c`; the TMP119 driver is `tmp119.c`.

The Android counterpart is
[`DensorMultirate.java`](../source_ST25NFCApplication_V3_9/app/src/main/java/com/st/st25nfc/densor/DensorMultirate.java).
It validates the same schedule, proposes partition sizes, creates requests,
decodes recordings and labels the detected revision. `DensorNfc.java` provides
verified NFC writes and metadata checks around reads.

## Scheduling and acquisition

The base period is an integer from 1 to 3540 seconds. Each enabled stream has
a positive 16-bit integer multiplier; disabled streams use zero. Its period is
`base × multiplier`, at most 2,592,000 seconds (30 days). The least common
multiple (LCM) of enabled multipliers must fit in 65,535 ticks.

Tick zero samples every enabled stream. Later ticks sample stream `i` when
`tick % multiplier[i] == 0`. With base 10 s and multipliers 12/6/3/1, all
streams sample at 0 s, only acceleration at 10 and 20 s, photodiode plus
acceleration at 30 s, and TMP119 joins them at 60 s. There is a wake on every
base tick, even if no stream is due; empty ticks advance the scheduler without
writing a record. Independent rates reduce acquisitions, but energy savings
must still be measured.

Both schedulers use the RTC calendar and 64 bytes of retained RTC RAM:

- **FSM (a):** selects streams from the retained phase modulo the LCM.
- **RTC time (b):** derives the tick from `(now − session_epoch) / base` and
  checks it against the retained next tick.

Both reject phase loss instead of backfilling missed measurements. Early or
duplicate wakes do not advance the schedule. Lateness tolerance is zero whole
seconds for a 1 s base and one second for a larger base; excessive lateness,
clock rollback or session duration beyond 30 days causes a phase error. These
are software rules, not measured timing guarantees. The RTC-time variant still
requires retained state and does not resume automatically after its loss.

After initializing the EEPROM, firmware rereads the RTC and sets the session
epoch to that reading plus the startup delay (0–59 minutes). Zero delay permits
the first sample on the accepting wake. The epoch is an RTC calendar count
from 2000-01-01, without a UTC synchronization guarantee. Exports use elapsed
seconds, not wall-clock timestamps. Scheduling strategy and crystal/RC oscillator
selection are separate settings; both strategies use the RTC to wake the MCU.

Only due channels are acquired. TMP119 uses bounded, unaveraged one-shots;
its conversion overlaps other sensor work. LIS2DW12 is configured for acquisition
when its temperature or acceleration is due. ADC supply measurement occurs
when a temperature or photodiode channel is due; acceleration-only wakes carry
an unavailable-supply marker. Sensor discovery and TMP119 shutdown still occur
on each boot, even when those channels are not due.

## Storage and full behavior

**R2 shared pool:** each nonempty tick appends a presence mask, supply code,
due payloads and CRC8, then zero-pads to a four-byte boundary. All channels
share the space from byte 168 to the physical capacity. The next complete
record must fit; there is no overwrite or circular buffer.

**R3 partitions:** each enabled stream has a fixed contiguous region. Scalar
records occupy four bytes; acceleration records occupy eight. Android allocates
at least one record to each enabled stream, then distributes remaining pages
in proportion to `record_size × (LCM / multiplier)`, assigning rounding leftovers
in stream order. Firmware validates and commits the supplied boundaries; it
does not calculate a different allocation. Boundaries stay fixed during a session.
The padding rule adds one zero byte only for an unpadded length congruent to
three modulo four; current scalar/acceleration records already need no padding.

Before acquisition, all records due on a tick are checked for available space.
If any would not fit, the whole session becomes FULL without acquiring that
tick. R3 therefore stops at the first limiting partition, even if other
partitions have room. The app previews allocation and expected capacity.

Each committed tick updates alternating retained-state copies. Durable EEPROM
pointer pairs are updated at `min(LCM, 64)`-tick intervals, LCM boundaries, and
Stop/full/error closure. This reduces routine pointer writes relative to R1.
Initialization fills checkpoint/data space with `0xFF`; CRC checks and schedule
validation distinguish records from the unused tail.

## Migration and provisioning

Changing R1 to R2/R3, or changing storage/timing variants, requires exporting
the old data and explicitly initializing the new format. Ordinary boot rejects
legacy, unknown or mismatched metadata without automatically formatting it.

1. Save and check the old full EEPROM binary before migration.
2. Build the intended revision using the [current Makefile commands](../README.md#build-and-check).
3. Use an independently powered bench board and its actual SWD probe. For
   example, this R3a command prepares a **dry run** from the repository root:

   ```sh
   python3 NFC_harvester_battery_free/software/tools/provision_r1.py \
     --protocol multirate \
     --elf NFC_harvester_battery_free/software/DentalSensor_StorageProject/build/partitioned-fsm/densor-r3a-partitioned-fsm.elf \
     --export saved-original.bin --interface interface/stlink.cfg
   ```

4. After verifying the export, ELF and probe selection, adding `--apply` flashes
   the MCU and destructively initializes metadata. The helper uses OpenOCD,
   breaks at `densor_boot_gate` after C initialization, and sets
   `densor_provision_request` to `0x44525035` for multirate firmware.
5. Read the tag with the current Android app and verify the expected R2/R3
   variant, physical capacity and empty STOPPED state before applying settings.

The helper's filename is historical; **`--protocol multirate` is required**.
Its default is R1, and it does not verify that the selected ELF matches that
protocol. The board also needs a valid RTC calendar; this helper does not set
or synchronize it. The physical provisioning sequence remains unvalidated.

## Configure, stop and export

The current app adapts its controls to the detected firmware. R2/R3 settings
show the base period, individual sensor periods, oscillator and startup delay.
The storage and timing strategies require a firmware change to replace.

For a running session, select **Stop and checkpoint recording**. It becomes
a pending request; refresh after the MCU's next wake and wait for STOPPED
with the request acknowledged. Export binary and/or CSV before choosing
**Apply settings and reset log**, which erases the previous recording on
acceptance. An outstanding request must be acknowledged before another is sent.
Stop does not power the board down indefinitely: stopped, full and error states
continue waking at the configured base period, or 120 s before configuration.
There is no extra command-polling loop. During startup delay, a request waits
until the scheduled wake.

Requests are CRC-protected and committed by a separate marker. Android shows
pending values without changing the active session. Firmware rejects Apply
while RUNNING and validates sensor availability and layout before starting the
new session. A valid but rejected request can close the session with an error;
inspect the reported state and acknowledgement rather than assuming success.

R2/R3 binary exports contain the entire physical EEPROM (512/2048/8192 bytes).
The decoder validates records and can scan a valid tail beyond the last EEPROM
checkpoint. Running reads are snapshots; metadata equality before/after a read
does not establish an atomic multi-stream acquisition snapshot. R3 recovery
may preserve different valid tails per stream after an interrupted wake.
Only STOPPED, error-free, acknowledged sessions are labelled complete.
Full/error exports are snapshots or recovered prefixes.

## Recovery and limits

Retained state has paired generations and an in-progress transaction flag.
Missing, ambiguous or interrupted state triggers a CRC-checked record scan
that verifies the durable checkpoint boundaries. A recoverable recording is
closed with a phase/recovery error; it is not silently resumed. A previously stopped recording remains
stopped after retained-state loss. Invalid pointers or corruption before a
checkpoint can prevent recovery entirely.

Configuration is not journaled as a whole. An interrupted CONFIGURING state or
damaged header/status can require saving a raw dump and reprovisioning. CRCs
detect many corruptions but are neither authentication nor proof of atomic
power-loss behavior. If EEPROM completion or a retained-state write is uncertain,
the board halts instead of deliberately cutting power and reacquiring. A bus
failure can also prevent recording the error in EEPROM.

TMP119 startup/shutdown costs, nonvolatile startup provisioning, real NFC
contention, runtime stack use and power-cut recovery still need bench tests.
See the [validation record](multirate-validation.md) before treating a candidate
as a measured hardware result.
