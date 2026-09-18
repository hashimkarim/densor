# Densor debug settings validation

Implemented a debug-only virtual NFC tag using writable 8 KiB EEPROM memory.
The existing settings fragment now reads, writes, reloads, and verifies settings
against either the physical tag or the debug tag.

## Samsung S21 FE (requested final device)

Repeated the complete import/update/validation/export/reopen workflow on the
physical Samsung SM-G990B, Android 16, serial RFCT10E2EYM, after its previous
claim was released. Installed the debug APK without clearing app data.

- Imported the separate synthetic file Download/densor-sample-test-01a0b4ab.bin.
- Read 45 seconds, changed to 20 minutes, and verified successful read-back.
- Rejected 60; Reload restored the stored 20 minutes.
- Exported Download/densor-s21-20min-01a0b4ab.bin (8192 bytes).
- Pulled the export and verified that only byte 0x05 changed: 0x45 to 0xA0.
- Reopened the exported file and confirmed 20 minutes remained selected.
- Inspected the phone screenshot and checked AndroidRuntime logs for the app
  process; no runtime errors were reported.
- Left the debug screen open and released the cooperative device claim.

Evidence: [S21 FE screenshot](s21-fe-debug-20-minutes.png),
[S21 FE exported dump](densor-s21-exported.bin).

The earlier Docker emulator was stopped with its Android data preserved.
The Android Agent Lab desktop app remains open with the project open request
sent; physical-phone control used its shared ADB coordination workflow.

## Earlier Android Agent Lab emulator check

Used the Android Agent Lab Docker emulator, Android 16 / API 36.1,
serial 127.0.0.1:15555. Registered the Gradle root with the desktop app using
android-agent-lab; the open request was acknowledged. GUI project selection was
not independently inspected. Device actions used the shared ADB coordinator.

Confirmed on the running app:

- Launch without NFC hardware and enter Debug mode.
- Import densor-input.bin through Android's document picker; read 45 seconds.
- Change to 20 minutes and Update; successful read-back shown.
- Enter 60; validation rejects it. Reload restores the stored value of 20 minutes.
- Export densor-debug.bin through the system file picker; output is 8192 bytes.
- Compare input/output: only byte 0x05 changes, from 0x45 to 0xA0.
- Reopen the exported file; 20 minutes is selected.
- No AndroidRuntime errors for the app process were reported during this session.

Screenshot: [Reopened 20-minute dump](densor-debug-20-minutes.png).
Binary fixtures: [input](densor-input.bin), [exported](densor-exported.bin).
Fixtures are synthetic test data, not measurements from a real sensor.

## Automated checks

13 tests passed: 10 EEPROM/settings tests and 3 Robolectric Android UI tests.
Coverage includes all 0–59 interval encodings in both units, startup-delay flags,
reserved-bit and measurement-byte preservation, invalid input and dump lengths,
memory bounds, silent dropped writes, document import/export, and activity
recreation.

Debug and release APK builds succeeded. The release APK does not contain the
DensorDebugActivity class. Lint reports no errors in the new settings/debug
sources; the repository still has pre-existing lint findings, including pending
intent flags. Lint is configured by the existing project not to abort the build.

The installed Android SDK also contains 37.1 platforms that this older Android
Gradle plugin's lint cannot parse. Validation used a temporary SDK overlay with
Android 33 and the installed build tools; the SDK and project compile target
were not modified. Ordinary assembleDebug also succeeds with the normal SDK.

The host's native emulator crashes during startup. The successful device tests
above used Android Agent Lab instead. No physical Densor sampling timing, NFC
radio behavior, or energy harvesting was tested.
