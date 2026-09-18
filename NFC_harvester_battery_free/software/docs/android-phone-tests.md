# R1 Android phone checks

Result on 2026-09-18 for simplified R1: **6 tests passed**; release installation
and launch passed. Settings/reset controls and temperature plots were inspected.
The [result JSON](../measurements/android-phone-results.json) identifies the exact
APKs and limitations; [instrumentation output](../measurements/android-instrumentation-results.txt)
records the run.

Device: Samsung Galaxy S21 FE (`SM-G990B`), Android 16 / API 36, connected over
USB. Testing uses the ADB coordination skill's exclusive claim. Android Agent
Lab registered the Gradle project at `source_ST25NFCApplication_V3_9`.

The release variant has application ID `com.st.st25nfc`, version code 28 and
version `3.10.2-r1`. Installation and launch succeeded on this phone. Reinstalling
with the same local signer also succeeded without clearing application data.
This does not establish compatibility with an old deployed APK's signing key;
that APK/key was unavailable. The local debug signing configuration remains a
publication limitation.

The `phoneTest` build uses the same release sources and package/signing identity,
plus a fixture activity and an in-memory `NFCTag`. The fixture activity is absent
from the release APK and says **TEST FIXTURE — no NFC hardware**. The companion
instrumentation APK runs these six tests:

1. Old-only, TMP119-only and both-temperature binary decoding in Android's Java
   runtime, including independent fields.
2. Applying settings to a running recording in one request while keeping active
   settings/data unchanged until firmware acknowledgement, including a 59-minute startup delay.
3. Separate source-labelled temperature plots, including nonzero display bounds
   for a recording containing just one sample.
4. Resetting with the current settings, including the one-time startup delay, without Start/Stop controls.
5. Writing a pending configuration without changing the displayed active
   configuration or pretending the MCU acknowledged it; incomplete commit markers
   remain inactive.
6. Rendering the [210-sample historical CSV reconstruction](../tests/HISTORICAL_DATA.md),
   comparing legacy/R1 readings and keeping TMP119 absent.

NFC read/write operations in these tests use simulated memory. RF exchange,
actual firmware acknowledgement, sensor acquisition, host/MCU contention and
board power cycles remain unmeasured. A UI test is not evidence for those gates.
The unrelated upstream Type2 instrumentation suite is excluded because its ST
test-rig dependencies (`AndroidHelper` and `Type2Tests`) are not in this checkout.

Build the candidate first, then from the Gradle root use the JDK 17 selected by
`JAVA_HOME` and the scoped SDK created by `build_r1.py`:

```sh
ANDROID_HOME=../build/android-sdk \
ANDROID_SDK_ROOT=../build/android-sdk \
bash gradlew --no-daemon :app:assemblePhoneTest :app:assemblePhoneTestAndroidTest
```

Claim the explicitly selected device with `adb_coord.py` following the
ADB coordination skill. Store its returned JSON privately outside the repository
with mode `0600`; do not use another thread's claim or a human reservation. From
the repository root, supply the relative paths to that claim and your installed
coordinator script:

```sh
python3 NFC_harvester_battery_free/software/tools/test_android_phone.py \
  --claim-file ../private/this-thread-claim.json \
  --coordinator path/to/adb_coord.py
```

The helper wraps every device operation through the coordinator, runs the six
tests and copies screenshots. It restores and launches the release APK after
testing, including on failure. Evidence is in `build/phone-r1`: a result JSON
with APK hashes, instrumentation output, a token-free ADB transcript and screen
captures. A copied measurement JSON records the particular APKs tested; a later
build must not inherit those results if its APK hash differs. Release the claim
when finished.

## Debug mode checks

Six Robolectric tests (Android API 35) exercise the debug activity with the real
R1 fragments: pending interval/startup-delay writes and recreation; invalid
settings leaving memory unchanged; document-picker import/export and historical
plots; legacy write rejection; density/truncation/bounds checks; and reset keeping
the active startup delay. The debug-only example assets match the C-produced and
historical reconstruction fixtures checked by `test_r1.py`.

The debug APK was also installed on the S21 FE as `com.st.st25nfc.dbg`, alongside
the release APK. Main screen → Debug mode, Settings/Data switching, and the startup
delay control passed the device smoke check; screenshots were inspected. See
[debug phone results](../measurements/android-debug-phone-results.json). Import and
export were exercised by Robolectric; physical RF and MCU timing remain unmeasured.
