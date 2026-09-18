#!/usr/bin/env python3
"""Run Densor fixture UI tests on an explicitly claimed Android device.

Build the candidate and :app:assemblePhoneTest :app:assemblePhoneTestAndroidTest
first. A private claim JSON from adb_coord.py supplies serial and token. This
script never claims a busy device, clears app data or bypasses the coordinator.
It restores the release APK after the test build, including on test failure.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "source_ST25NFCApplication_V3_9"
OUT = ROOT / "build/phone-r1"


def apk(directory):
    metadata = json.loads((directory / "output-metadata.json").read_text())
    assert len(metadata["elements"]) == 1
    return directory / metadata["elements"][0]["outputFile"]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--claim-file", type=Path, required=True)
    parser.add_argument("--coordinator", type=Path, required=True, help="Path to the installed adb_coord.py script")
    args = parser.parse_args()
    if args.claim_file.stat().st_mode & 0o077:
        raise ValueError("The claim file must be private (chmod 600), outside the repository")
    claim = json.loads(args.claim_file.read_text())
    OUT.mkdir(parents=True, exist_ok=True)
    release = apk(APP / "app/build/outputs/apk/release")
    fixture = apk(APP / "app/build/outputs/apk/phoneTest")
    tests = apk(APP / "app/build/outputs/apk/androidTest/phoneTest")
    transcripts = []

    def adb(*command):
        result = subprocess.run([sys.executable, str(args.coordinator), "run", "--serial", claim["serial"],
            "--token", claim["token"], "--timeout", "90", "--", *map(str, command)], capture_output=True, text=True)
        text = result.stdout + result.stderr
        transcripts.append({"command": list(map(str, command)), "exit_code": result.returncode, "output": text})
        if result.returncode:
            raise RuntimeError(text.strip())
        return text

    model = adb("shell", "getprop", "ro.product.model").strip()
    android = adb("shell", "getprop", "ro.build.version.release").strip()
    api = adb("shell", "getprop", "ro.build.version.sdk").strip()
    results = ""
    try:
        print(f"Testing on {model}, Android {android} / API {api}", flush=True)
        assert "Success" in adb("install", "-r", fixture)
        assert "Success" in adb("install", "-r", "-t", tests)
        results = adb("shell", "am", "instrument", "--user", "0", "-w", "-e", "class",
                      "com.st.st25nfc.densor.DensorPhoneTest", "com.st.st25nfc.test/androidx.test.runner.AndroidJUnitRunner")
        (OUT / "instrumentation-results.txt").write_text(results)
        if not re.search(r"OK \(6 tests\)", results) or "FAILURES" in results:
            raise RuntimeError("Phone test failure:\n" + results)
        adb("pull", "/sdcard/Android/data/com.st.st25nfc/files/densor-phone-tests", OUT)
    finally:
        # Leave the actual candidate on the phone, preserving application data.
        try:
            assert "Success" in adb("install", "-r", release)
            launch = adb("shell", "am", "start", "--user", "0", "-W", "-n", "com.st.st25nfc/com.st.st25nfc.generic.MainActivity")
            assert "Status: ok" in launch
        finally:
            (OUT / "adb-transcript.json").write_text(json.dumps(transcripts, indent=2) + "\n")
    report = {
        "tested_at_utc": datetime.now(timezone.utc).isoformat(),
        "device_model": model, "android_version": android, "api_level": int(api),
        "application_id": "com.st.st25nfc", "version": "3.10.2-r1", "version_code": 28,
        "release_apk_sha256": sha(release), "fixture_apk_sha256": sha(fixture), "test_apk_sha256": sha(tests),
        "release_install_and_launch": "passed; release APK restored after fixture tests",
        "same_signer_reinstall": "passed without clearing data",
        "historical_deployed_apk_upgrade": "unverified; original deployed APK/certificate unavailable",
        "fixture_ui_tests": 6, "fixture_ui_result": "passed", "real_nfc_exchange": "unmeasured; no Densor tag available",
        "fixture_scope": "Actual release fragment code plus a phoneTest-only host and memory-backed NFCTag; no RF, MCU acknowledgement or sensor acquisition",
        "screenshots_sha256": {p.name: sha(p) for p in sorted((OUT / "densor-phone-tests").glob("*.png"))},
    }
    (OUT / "android-phone-results.json").write_text(json.dumps(report, indent=2) + "\n")
    print(results.strip()); print(f"Phone evidence: {OUT}")


if __name__ == "__main__":
    main()
