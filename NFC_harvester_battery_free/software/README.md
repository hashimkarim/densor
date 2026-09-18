# Densor firmware and Android software

The current firmware implements independent sensor periods with two storage
layouts and two schedulers. R2 uses a shared EEPROM pool; R3 uses per-sensor
partitions. Suffix **a** selects a retained finite-state machine (FSM), and
suffix **b** selects RTC-time scheduling. All four use protocol version 5.
They are software-tested candidates; physical timing, energy and power-loss
validation remain pending.

| Revision | Storage | Scheduler | Build selector |
| --- | --- | --- | --- |
| R2a | Shared pool | Retained FSM | `REVISION=R2a` |
| R2b | Shared pool | RTC time | `REVISION=R2b` |
| R3a | Per-sensor partitions | Retained FSM | `REVISION=R3a` |
| R3b | Per-sensor partitions | RTC time | `REVISION=R3b` |

The Makefile defaults to **R3a**. Storage and scheduler are selected when
building firmware; the Android app detects them and cannot switch them.
One current app supports legacy recordings, R1, and all four R2/R3 variants.

- [Implementation and operation](docs/multirate-implementation.md): scheduling,
  storage, migration, configuration and recovery.
- [Protocol version 5](docs/multirate-protocol.md): EEPROM map, records,
  requests and retained state.
- [Validation and limitations](docs/multirate-validation.md): checks run against
  this checkout and remaining hardware work.
- [R1 reference](docs/r1-software.md) and [R1 protocol v3](docs/r1-protocol.md):
  historical common-period logging and compatibility details.

## Build and check

From the repository root, with GNU Make, Python 3 and the
`arm-none-eabi-gcc` toolchain on `PATH`:

```sh
make -C NFC_harvester_battery_free/software/DentalSensor_StorageProject -j4 REVISION=R2a
make -C NFC_harvester_battery_free/software/DentalSensor_StorageProject -j4 REVISION=R2b
make -C NFC_harvester_battery_free/software/DentalSensor_StorageProject -j4 REVISION=R3a
make -C NFC_harvester_battery_free/software/DentalSensor_StorageProject -j4 REVISION=R3b
```

Each variant has its own directory under
`DentalSensor_StorageProject/build/<storage>-<timing>/`, containing
`densor-<revision>-<storage>-<timing>.elf`, `.bin`, `.hex`, `.map` and
`-manifest.json`. For example, R2b produces
`build/shared-rtc/densor-r2b-shared-rtc.elf`. Compiler flags and stack-usage
reports are also retained. Switching variants does not require cleaning.
The equivalent selectors are `STORAGE=shared|partitioned` and `TIMING=fsm|rtc`;
conflicting revision/strategy choices fail.

Host checks additionally require Clang with address/undefined-behavior sanitizer
runtimes, `javac` and `java`:

```sh
python3 NFC_harvester_battery_free/software/tools/test_r1.py
python3 NFC_harvester_battery_free/software/tools/test_multirate.py
```

To package all four variants and the matching Android app, set `JAVA_HOME` to
JDK 17 and `ANDROID_HOME` to an SDK with Android 33, build-tools 35.0.0,
platform-tools and accepted licenses, then run:

```sh
python3 NFC_harvester_battery_free/software/tools/build_multirate.py
```

The helper also accepts `--java-home` and `--sdk`. It creates a scoped SDK view,
checks build selections, runs both host suites, and builds/tests the Android
variants. Output is in `software/build/multirate-candidate/`: firmware images,
the release APK, manifests, checksums and build/test logs. This command builds
an unpublished candidate; it does not install or flash a device.

The app identifies as `com.st.st25nfc`, version `3.11.0-multirate`, code 29,
minimum API 23 and target API 33. It retains the project's local debug signing
configuration. A successful build does not establish compatibility with a
previously deployed signing key or release readiness.

`build_r1.py` is historical: it expects R1 artifact paths and APK version 28.
The current Makefile has no `REVISION=R1` target; use `test_r1.py` to check the
retained R1 implementation and decoder.

## Start a recording

1. Save the existing EEPROM dump, build the selected revision, and follow the
   [SWD migration procedure](docs/multirate-implementation.md#migration-and-provisioning).
2. In the app, read the detected revision and state. For a running R2/R3 session,
   select **Stop and checkpoint recording**, refresh after a device wake, and
   wait for acknowledgement before exporting the final recording.
3. Select sensors, a base period and each enabled sensor's period. Periods are
   whole multiples of the base. For example, base 10 s with LIS2DW12 temperature,
   TMP119, photodiode and acceleration periods of 120/60/30/10 s gives
   multipliers 12/6/3/1. Startup delay is 0–59 minutes.
4. Select **Apply settings and reset log**. This replaces the previous session
   once the MCU accepts it. Pending settings remain distinct from active ones.
5. Read binary/CSV snapshots in Data, or Stop and await acknowledgement for a
   completed recording. Times are elapsed seconds; UTC is unavailable.

R1 uses its own apply/reset workflow without a Stop command. The original
firmware's Time Sync/Charge workflow does not apply to R1/R2/R3.

## Work without a board

Build `:app:assembleDebug` from
[`source_ST25NFCApplication_V3_9`](source_ST25NFCApplication_V3_9), open
**ST25 dbg → Debug mode**, and select a sample or import a raw EEPROM `.bin`.
The debug package `com.st.st25nfc.dbg` can coexist with the release app.
R2/R3 imports require the complete 512-, 2048- or 8192-byte EEPROM image.
Virtual memory supports decoding, settings requests and export; it does not
simulate firmware acknowledgements, acquisition or time progression.

The [Sampling lab](../visualization/sampling_lab/README.md) exports synthetic
R1/R2a/R2b/R3a/R3b EEPROM images. The
[EEPROM lab](../visualization/eeprom_lab/README.md) compares memory layouts.
These are test and visualization tools; synthetic rates do not establish
physical firmware sampling performance.
