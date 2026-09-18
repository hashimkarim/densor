#!/usr/bin/env python3
"""Reproduce the firmware/APK candidate, tests, measurements and checksums."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from measure_elf import measure

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "source_ST25NFCApplication_V3_9"
FW = ROOT / "DentalSensor_StorageProject"
OUT = ROOT / "build/r1-candidate"

def run(argv, *, cwd=ROOT, env=None, log=None):
    completed = subprocess.run([str(x) for x in argv], cwd=cwd, env=env, text=True, capture_output=True)
    output = completed.stdout + completed.stderr
    if log:
        (OUT / log).write_text(output)
    if completed.returncode:
        print(output, file=sys.stderr)
        raise subprocess.CalledProcessError(completed.returncode, argv)
    return output

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, default=os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT"),
                        help="Android SDK path, relative to the current directory; defaults to ANDROID_HOME or ANDROID_SDK_ROOT")
    parser.add_argument("--java-home", type=Path, default=os.environ.get("JAVA_HOME"),
                        help="JDK 17 path, relative to the current directory; defaults to JAVA_HOME")
    args = parser.parse_args()
    if args.sdk is None or args.java_home is None:
        parser.error("Set JAVA_HOME and ANDROID_HOME/ANDROID_SDK_ROOT, or supply --java-home and --sdk")
    args.sdk = args.sdk.expanduser().resolve(strict=True)
    args.java_home = args.java_home.expanduser().resolve(strict=True)
    OUT.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ, JAVA_HOME=str(args.java_home))
    env["PATH"] = str(args.java_home / "bin") + os.pathsep + env["PATH"]
    # A scoped SDK view avoids the old lint parser scanning unrelated SDK preview
    # directories. No globally installed platform or license is changed.
    sdk_view = ROOT / "build/android-sdk"
    for sub in ["platforms/android-33", "build-tools/35.0.0", "licenses", "platform-tools"]:
        source = (args.sdk / sub).resolve(strict=True)
        target = sdk_view / sub
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.is_symlink():
            if target.resolve() != source:
                raise ValueError(f"SDK view points to a different installation: {target}")
        elif target.exists():
            raise ValueError(f"Expected a task-owned SDK symlink: {target}")
        else:
            target.symlink_to(source, target_is_directory=True)
    env["ANDROID_HOME"] = str(sdk_view)
    env["ANDROID_SDK_ROOT"] = str(sdk_view)
    run(["make", "-j4"], cwd=FW, log="firmware-build.log")
    tests = run([sys.executable, ROOT / "tools/test_r1.py"], env=env, log="tests.log")
    print(tests.strip(), flush=True)
    print("Building Android release variant with vital lint enabled…", flush=True)
    run(["bash", "gradlew", "--no-daemon", ":app:assembleRelease"], cwd=APP, env=env, log="android-build.log")
    metadata_path = APP / "app/build/outputs/apk/release/output-metadata.json"
    metadata = json.loads(metadata_path.read_text())
    assert metadata["applicationId"] == "com.st.st25nfc" and metadata["variantName"] == "release"
    assert len(metadata["elements"]) == 1
    element = metadata["elements"][0]
    assert element["versionName"] == "3.10.2-r1" and element["versionCode"] == 28
    apk = metadata_path.parent / element["outputFile"]
    tool_dir = args.sdk / "build-tools/35.0.0"
    badging = run([tool_dir / "aapt2", "dump", "badging", apk], env=env, log="apk-badging.txt")
    assert "package: name='com.st.st25nfc' versionCode='28' versionName='3.10.2-r1'" in badging
    assert "application-debuggable" not in badging and re.search(r"(?:minSdkVersion|sdkVersion):'23'", badging) and "targetSdkVersion:'33'" in badging
    apk_manifest = run([tool_dir / "aapt2", "dump", "xmltree", "--file", "AndroidManifest.xml", apk], env=env, log="apk-manifest.txt")
    assert "DensorDebugActivity" not in apk_manifest, "Debug activity leaked into release APK"
    assert "FixtureActivity" not in apk_manifest, "Test fixture activity leaked into the release APK"
    assert not re.search(r"android:testOnly.*(?:0xffffffff|true)", apk_manifest), "Release APK is testOnly"
    cert = run([tool_dir / "apksigner", "verify", "--verbose", "--print-certs", apk], env=env, log="apk-signature.txt")
    digest = re.search(r"Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]+)", cert)
    assert digest, "APK signer certificate was not reported"
    for suffix in ("elf", "map", "bin", "hex"):
        shutil.copy2(FW / f"build/r1/densor-r1.{suffix}", OUT)
    stack_reports = sorted((FW / "build/r1").rglob("*.su"))
    (OUT / "compiler-stack-usage.tsv").write_text("".join(p.read_text() for p in stack_reports))
    shutil.copy2(ROOT / "docs/r1-stack-review.md", OUT)
    shutil.copy2(apk, OUT)
    shutil.copy2(metadata_path, OUT)
    shutil.copy2(ROOT / "docs/r1-protocol.md", OUT)
    shutil.copy2(ROOT / "build/tests/historical-reconstruction.json", OUT)
    measurements = measure(FW / "build/r1/densor-r1.elf")
    (OUT / "elf-measurements.json").write_text(json.dumps(measurements, indent=2) + "\n")
    phone_evidence = ROOT / "measurements/android-phone-results.json"
    android_checks = {"status": "no matching device evidence for this APK"}
    if phone_evidence.exists():
        report = json.loads(phone_evidence.read_text())
        if report["release_apk_sha256"] == hashlib.sha256(apk.read_bytes()).hexdigest():
            android_checks = report
            shutil.copy2(phone_evidence, OUT)
        elif (OUT / phone_evidence.name).exists():
            (OUT / phone_evidence.name).unlink()
    source_files = sorted(p for p in ROOT.rglob("*") if p.is_file()
                          and not any(part in {"build", ".gradle", "__pycache__"} for part in p.relative_to(ROOT).parts)
                          and (p.suffix in {".c", ".h", ".s", ".ld", ".java", ".xml", ".gradle", ".properties", ".py", ".jar", ".aar"}
                               or p.name in {"Makefile", "gradlew", "record-fixtures.csv"}
                               or (p.suffix == ".bin" and "/assets/" in str(p.relative_to(ROOT)))))
    manifest = {
        "status": "software candidate; not a hardware-validated release",
        "built_at_utc": datetime.now(timezone.utc).isoformat(),
        "source_revision": run(["git", "rev-parse", "HEAD"]).strip(),
        "source_branch": run(["git", "branch", "--show-current"]).strip(),
        "source_dirty": bool(run(["git", "status", "--porcelain", "--", str(ROOT)]).strip()),
        "source_sha256": {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in source_files},
        "compiler": measurements["compiler"], "linker": run(["arm-none-eabi-ld", "--version"]).splitlines()[0],
        "firmware_options": "-mcpu=cortex-m0plus -mthumb -Os -g3 -fstack-usage; existing 16KiB/2KiB linker limits and 512/1024-byte heap/stack reservations",
        "build_commands": ["make -j4", "python3 tools/test_r1.py", "bash gradlew --no-daemon :app:assembleRelease"],
        "jdk": run([args.java_home / "bin/java", "-version"], env=env).strip(),
        "application_id": metadata["applicationId"], "android_variant": "release", "version_name": element["versionName"],
        "version_code": element["versionCode"], "apk_bytes": apk.stat().st_size,
        "apk_signer_sha256": digest.group(1).lower(), "signing": "existing local debug signing configuration; deployed upgrade certificate not verified",
        "hardware_revision": "original Densor, optionally with the agreed TMP119 wiring; assembled revision unverified",
        "protocols": ["legacy read-only", "R1 v2 read-only", "R1 v3 read/configure-reset/startup-delay"],
        "android_device_checks": android_checks,
        "hardware_gates": {name: "unmeasured" for name in ["fitted_eeprom_density", "physical_sensor_modes", "nfc_transactions",
            "power_cut_tests", "stack_high_water", "energy_2_6v", "energy_2_2v", "energy_1_8v", "original_deployed_android_upgrade"]},
        "release_ready": False,
    }
    (OUT / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    files = sorted(p for p in OUT.iterdir() if p.is_file() and p.name != "SHA256SUMS")
    (OUT / "SHA256SUMS").write_text("".join(f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n" for p in files))
    print(f"Candidate: {OUT}\nFlash {measurements['flash_load_extent_bytes']}/16384 bytes; static SRAM {measurements['sram_static_bytes']}/2048 bytes; hardware gates unmeasured.")

if __name__ == "__main__":
    main()
