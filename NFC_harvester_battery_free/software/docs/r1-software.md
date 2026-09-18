# Densor R1 software candidate (historical reference)

For current R2/R3 firmware, start with the [software overview](../README.md)
and [multirate implementation guide](multirate-implementation.md). The operation,
APK version and test evidence below describe R1; R2/R3 use a different protocol
and require Stop before applying settings.

For synthetic multirate sampling experiments, use the local
[Sampling lab webapp](../../visualization/sampling_lab/README.md). It previews independent sensor
rates and exports lab binaries or full R1/R2a/R2b/R3a/R3b EEPROM images for
Android debug import, with synthetic provenance and capacity checks.

The [visualization folder](../../visualization/README.md) also contains the
[EEPROM lab](../../visualization/eeprom_lab/README.md) for comparing memory layouts
and page crossings.

R1 implements aligned combined logging, independent LIS2DW12/TMP119 temperature
selection and a matching Android reader/configurator. It is a **software-tested
candidate**, with hardware validation still pending.

The [byte protocol](r1-protocol.md) defines the 128-byte metadata map,
settings/reset transaction, legacy detection and record encoding. Source lives in
[`DentalSensor_StorageProject`](../DentalSensor_StorageProject) and
[`source_ST25NFCApplication_V3_9`](../source_ST25NFCApplication_V3_9).

## Historical build and check

The commands and artifact names below describe the R1 candidate checkout.
They are not an R1 build recipe for the current tree: plain `make` now selects
R3a, and `build_r1.py` still expects the older R1 output paths and APK version.
Use the [current build instructions](../README.md#build-and-check) for R2/R3.
`test_r1.py` remains valid for compatibility testing.

Historical commands, from the repository root:

```sh
python3 NFC_harvester_battery_free/software/tools/test_r1.py
make -C NFC_harvester_battery_free/software/DentalSensor_StorageProject -j4
python3 NFC_harvester_battery_free/software/tools/build_r1.py
```

Set `JAVA_HOME` to your JDK 17 installation and `ANDROID_HOME` (or
`ANDROID_SDK_ROOT`) to your Android SDK. The helper also accepts `--java-home`
and `--sdk` paths relative to the directory where you invoke it.
Required SDK components are Android
33 and build-tools 35.0.0. The wrapper pins Gradle 8.9; the app uses AGP 8.7.1.
The build helper creates a local SDK view so the old lint parser does not scan
unrelated preview SDK names. It does not change the installed SDK. The ARM
compiler is `arm-none-eabi-gcc`; the host fault tests use Clang and address/
undefined-behavior sanitizers by default. `DENSOR_SANITIZE=0` is an explicit
fallback on hosts without sanitizer runtimes and is reported as such.

Firmware is optimized with `-Os` and includes debug symbols, `.su` compiler stack
reports and the linker map. Physical linker limits remain 16,384 bytes Flash and
2,048 bytes SRAM; the 512-byte heap and 1,024-byte stack reservations are retained.
Inspect the map, `.su` reports and [stack review](r1-stack-review.md)
together; those reservations are not a measured runtime stack margin.

Historical candidate artifacts were generated in `software/build/r1-candidate/`:

- `densor-r1.bin`, `.hex`, `.elf` and `.map`.
- `ST25NFCTap-signed-V3.10.2-r1.apk` and AGP output metadata.
- Protocol, build/test logs, APK manifest/signature checks, ELF measurements,
  source hashes/build manifest and `SHA256SUMS`.

The APK is the non-debuggable **release variant** of `com.st.st25nfc`, version
`3.10.2-r1`, version code 28, minimum Android 6/API 23, target API 33. It retains
the project's existing local debug signing configuration. Its certificate is
recorded in the manifest, but compatibility with an installed older APK has not
been verified. No app-specific production signing profile is configured, so the
Android deployment doctor's profile-based release check is pending. Do not
publish this candidate as a signed-off upgrade or rotate an existing deployed
key to work around an installation failure.

## Migration and operation

1. Save the old binary log before flashing. The new app reads validated legacy
   recordings and labels them as snapshots; it does not configure legacy tags.
2. On a powered bench board, explicitly initialize R1 through SWD after checking
   that export. The helper defaults to a dry run:

   ```sh
   python3 NFC_harvester_battery_free/software/tools/provision_r1.py \
     --elf NFC_harvester_battery_free/software/build/r1-candidate/densor-r1.elf \
     --export saved-original.bin --interface interface/stlink.cfg
   ```

   Choose the actual probe configuration. Adding `--apply` flashes the ELF and
   requests destructive initialization through a firmware breakpoint. Confirm
   the stopped R1 header in Android afterwards. This sequence has not been
   exercised on a physical board. Normal firmware boot leaves legacy/unknown
   layouts alone; Android cannot initiate new-format writes to a legacy tag.
3. In Settings, choose old-only, TMP119-only, both, or no temperature, and enable
   photodiode/acceleration independently. Use one common period: 1–59 seconds or
   whole minutes up to 3,540 seconds. The optional **startup delay** is 0–59
   minutes; zero starts immediately. **Apply settings and reset log** is accepted
   on the next regular wake, then the first measurement follows that one-time delay. Active and pending settings remain
   distinct until the MCU accepts the request. No separate Start/Stop sequence.
4. **Reset log using current settings** restarts at the beginning with the same
   sensors, period and startup delay. Save existing data first. Applying/resetting settings
   replaces the previous recording; firmware validates the complete request and
   selected sensors before accepting it.
5. Read/save binary or CSV in Data at any time. Reads check that metadata and
   pointer stay unchanged; retry if the board wakes during the read. Running
   recordings are labelled **live snapshots**, which exclude later samples.
   Full/error exports contain the stable committed prefix. CSV and plots keep
   separate old/TMP119 values and leave unavailable supply readings blank.

Except for the one-time startup delay, all ordinary wakes use the configured sampling interval, including full/error
states. There is no separate five-second command-polling loop. Before valid
settings exist, the default is 120 seconds. Full/error states skip acquisition
until a valid settings/reset request is accepted. Actual idle/sampling power
remains unmeasured. Missing selected sensors cause explicit errors; TMP119-only
does not initialize LIS2DW12 unless acceleration is enabled.

Protocol version 3 supports the startup delay within the settings/reset contract.
Original legacy and version-2 recordings remain readable;
version-2 firmware is read-only in this app. Export before updating firmware and
explicitly reprovisioning. The delay is consumed before sleeping, so an unexpected
power loss before RTC power-off can shorten or skip it, as in the original firmware.

R1 does not establish UTC from a delayed phone command. Its header explicitly
marks UTC unavailable; graphs/exports use sample indices and nominal elapsed
seconds. R1 cannot reconstruct missed wakes. Existing charge-mode timestamp
bytes are never displayed as a voltage in an R1 header. Older Android versions
must not configure new-format firmware.

TMP119 uses unaveraged one-shots with finite bus/readiness deadlines, overlapping
LIS2DW12/ADC work. Firmware shuts a fitted sensor down on every wake before use,
including when temperature is disabled. It does not program TMP119 nonvolatile
settings automatically. A verified once-only shutdown-startup provisioning
procedure and its power-cycle check remain a bench task; otherwise startup and
shutdown energy must be counted. Individual samples do not inherit the accuracy
specification for averaged conversions.

## Evidence and remaining gates

Host checks exercise all 15 nonempty sensor masks, 512/2,048/8,192-byte memory
bounds, exact-full memory, page boundaries, signed temperatures, supply fields,
padding, incomplete/duplicate requests, sensor availability, failed transmission
and readiness, interrupted configuration commits, and bounded TMP119 polling
including tick wrap. Another 110 scenarios run the real board entry point with a
fake HAL and check RTC alarm writes, settings/reset transitions, full/error
intervals, startup delay followed by normal sampling, and fault halts. These do not measure physical power. Java decodes 45 C-produced EEPROM images plus fixed binary
vectors and legacy records, and rejects damaged/unknown metadata and padding.
The [historical-data fixtures](../tests/HISTORICAL_DATA.md) also compare all 210
reconstructed samples from an existing CSV between legacy and R1 decoding.
They are explicitly labelled reconstructions; the old raw NFC dumps are absent.
Release vital lint is enabled. The pre-Android-12 NFC PendingIntent branches have
narrow false-positive lint suppressions; their Android-12 guards remain intact.
The [Android NFC documentation](https://developer.android.com/develop/connectivity/nfc/advanced-nfc)
requires mutability for foreground dispatch to populate tag information.

[Phone checks](android-phone-tests.md) cover APK installation/launch and
six fixture-based Android tests on the USB-connected S21 FE (Android 16). The
fixture host is included only in a test build. The release APK is restored after
testing. Physical NFC communication remains unmeasured.

The [build table](../measurements/build-results.csv) and
[run template](../measurements/run-results.csv) distinguish measured software
allocation from unmeasured physical results. Empty cells are unmeasured.

| Gate | Status |
| --- | --- |
| Firmware build and physical linker limits | Checked by candidate build |
| Portable storage/driver fault tests and golden decoding | Checked by host tests |
| Android release build, vital lint, manifest and signature | Checked by candidate build |
| Actual ST25DV density and sensor identity/wiring | Unmeasured; firmware reads density at runtime |
| Physical power-loss, NFC contention and actual device logs | Unmeasured |
| Stack high-water, interrupt margin, heap peak | Unmeasured; `.su` and map available |
| Energy, wake duration, bus/page-program counts at 2.6/2.2/1.8 V | Unmeasured |
| TMP119 startup provisioning and power-cycle readback | Unmeasured |
| APK installation/launch and same-signer reinstall on S21 FE | Checked; see phone evidence |
| Android fixture decoding, settings/reset and plots | Six instrumented tests; see phone evidence |
| Real NFC exchange and compatibility with the original deployed signer | Unmeasured / unverified |

R1 keeps one raw two-byte pointer write per successful wake. Padding reduces
avoidable page programming but does not eliminate pointer wear, add record CRCs
or provide torn-write recovery. An aligned corrupt pointer may be undetectable;
power loss can leave an orphan record or incomplete metadata. If EEPROM
programming completion cannot be confirmed, firmware halts instead of
deliberately cutting power or resetting into a duplicate acquisition. A failed
bus may prevent saving the error status. See the protocol before recovery.

Before release, run the voltage/sensor matrix in `measurements/run-results.csv`, measure
runtime stack and energy, establish Android signing/upgrade compatibility,
confirm migration on hardware and bind the exact source revision to the release.

## Debug mode without a board

Build `:app:assembleDebug`, open **ST25 dbg**, and select **Debug mode** to use a
virtual EEPROM with the R1 protocol.
The debug package `com.st.st25nfc.dbg` can coexist with the release app. Its virtual
tag and activity are excluded from release builds.

Use **Samples** for a synthetic both-temperature recording or the 210-sample
historical CSV reconstructed as legacy/R1. Those are test fixtures, not original
raw NFC captures; the historical data has no TMP119 samples. **Settings**, **Data**
and **Reset** run the same fragments and protocol code as the real app. Applying
settings (including startup delay) writes a checked pending request. There is no
firmware simulator: no acknowledgement, new samples or timing/power measurement.

**Open .bin** imports a raw image starting at EEPROM address zero. It must contain
all bytes through the committed pointer. R1 uses its header's declared capacity;
legacy full 512/2048-byte images retain that size, while a legacy prefix is modelled
with 8192 virtual bytes because it has no density field. Only the unused tail is
zero-filled. Bad headers, truncated records and invalid padding are rejected
without replacing the current image. Legacy/version-2 settings remain read-only.
**Export .bin** saves the full virtual memory, including a pending request. Import
leaves the source file untouched. Export before leaving or replacing the image;
virtual memory survives activity recreation but is not saved as an app document.

Run `:app:testDebugUnitTest` for the debug UI/import/export/validation tests.
