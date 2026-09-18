#!/usr/bin/env python3
"""Build/test all four strategy selections and one matching unpublished Android APK."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
FW = ROOT / 'DentalSensor_StorageProject'
APP = ROOT / 'source_ST25NFCApplication_V3_9'
OUT = ROOT / 'build/multirate-candidate'
VARIANTS = [('R2a','shared','fsm'), ('R2b','shared','rtc'),
            ('R3a','partitioned','fsm'), ('R3b','partitioned','rtc')]

def run(argv, cwd=ROOT, env=None, log=None):
    result = subprocess.run([str(v) for v in argv], cwd=cwd, env=env, text=True, capture_output=True)
    output = result.stdout + result.stderr
    if log: (OUT / log).write_text(output)
    if result.returncode:
        print(output, file=sys.stderr)
        raise subprocess.CalledProcessError(result.returncode, argv)
    return output

def digest(p): return hashlib.sha256(p.read_bytes()).hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, default=os.environ.get('ANDROID_HOME'))
    parser.add_argument('--java-home', type=Path, default=os.environ.get('JAVA_HOME'))
    args = parser.parse_args()
    if not args.sdk or not args.java_home: parser.error('Supply --sdk and --java-home (JDK 17), or ANDROID_HOME/JAVA_HOME')
    OUT.mkdir(parents=True, exist_ok=True)
    sdk = args.sdk.expanduser().resolve(strict=True)
    java = args.java_home.expanduser().resolve(strict=True)
    view = ROOT / 'build/android-sdk'
    for sub in ['platforms/android-33', 'build-tools/35.0.0', 'licenses', 'platform-tools']:
        target = view / sub; source = (sdk / sub).resolve(strict=True)
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.is_symlink():
            if target.resolve() != source: raise ValueError(f'SDK view points elsewhere: {target}')
        elif target.exists(): raise ValueError(f'Expected SDK view symlink: {target}')
        else: target.symlink_to(source, target_is_directory=True)
    env = dict(os.environ, JAVA_HOME=str(java), ANDROID_HOME=str(view), ANDROID_SDK_ROOT=str(view))
    env['PATH'] = str(java / 'bin') + os.pathsep + env['PATH']
    for value in [('STORAGE=invalid',), ('TIMING=invalid',), ('STORAGE=partitioned shared',),
                  ('REVISION=invalid',), ('REVISION=',), ('REVISION=R2a R3a',),
                  ('REVISION=R3a','TIMING=rtc'), ('REVISION=R3b','STORAGE=shared')]:
        check = subprocess.run(['make', *value], cwd=FW, capture_output=True)
        assert check.returncode, f'Accepted invalid build selection: {value}'
    # A compiler flag change must invalidate objects within that variant only.
    selection=['make','-j4','STORAGE=shared','TIMING=rtc']
    run(selection,cwd=FW,log='flag-baseline.log')
    selected=FW/'build/shared-rtc/Core/Src/densor_board.o'
    first=selected.stat().st_mtime_ns
    others={p:p.stat().st_mtime_ns for p in (FW/'build').glob('*/Core/Src/densor_board.o') if p!=selected}
    run([*selection,'CFLAGS=-DDENSOR_BUILD_STAMP_TEST=1'],cwd=FW,log='flag-changed.log')
    assert selected.stat().st_mtime_ns!=first
    changed=selected.stat().st_mtime_ns
    run(selection,cwd=FW,log='flag-restored.log')
    assert selected.stat().st_mtime_ns!=changed and all(p.stat().st_mtime_ns==t for p,t in others.items())
    reports = []
    for revision,storage,timing in VARIANTS:
        variant = f'{storage}-{timing}'; name = f'densor-{revision.lower()}-{variant}'
        run(['make', '-j4', f'STORAGE={storage}', f'TIMING={timing}'], cwd=FW, log=f'{variant}-build.log')
        folder = FW / 'build' / variant
        flags = (folder / 'flags').read_text()
        other_storage = 'shared' if storage == 'partitioned' else 'partitioned'
        other_timing = 'rtc' if timing == 'fsm' else 'fsm'
        mapping = (folder / f'{name}.map').read_text()
        for excluded in [f'densor_logger_{other_storage}', f'densor_scheduler_{other_timing}']:
            assert excluded not in flags and excluded not in mapping, f'Unselected module linked: {excluded}'
        # Named revisions select exactly the same objects and outputs as the original axes.
        before = {p: p.stat().st_mtime_ns for p in folder.rglob('*.o')}
        for spelling in [revision,revision.lower()]:
            run(['make','-j4',f'REVISION={spelling}'],cwd=FW,log=f'{spelling}-selection.log')
            assert all(p.stat().st_mtime_ns==mtime for p,mtime in before.items())
        report = json.loads((folder / f'{name}-manifest.json').read_text())
        assert (report['software_revision'],report['storage'],report['timing'])==(revision,storage,timing)
        reports.append(report)
        for suffix in ['.elf', '.bin', '.hex', '.map', '-manifest.json']:
            shutil.copy2(folder / (name+suffix), OUT)
            # Retire generated copies bearing superseded revision names.
            (OUT / (f'densor-{variant}'+suffix)).unlink(missing_ok=True)
            for output in [OUT, folder]:
                for previous in output.glob(f'densor-r*-{variant}{suffix}'):
                    if previous.name != name+suffix: previous.unlink()
        stack = sorted(folder.glob(f'{name}.elf.ltrans*.su'))
        assert stack, 'Missing compiler stack usage reports'
        (OUT / f'{variant}-stack-usage.tsv').write_text(''.join(p.read_text() for p in stack))
        print(f'{revision} ({variant}): {report["flash_load_extent_bytes"]} Flash / {report["sram_static_bytes"]} static SRAM bytes', flush=True)
    # Switch to partitioned/FSM without cleaning or needless recompilation.
    folder = FW / 'build/partitioned-fsm'
    before = {p: p.stat().st_mtime_ns for p in folder.rglob('*.o')}
    run(['make', '-j4', 'STORAGE=partitioned', 'TIMING=fsm'], cwd=FW, log='variant-switch.log')
    assert all(p.stat().st_mtime_ns == mtime for p,mtime in before.items())
    for test in ['test_r1.py', 'test_multirate.py']:
        print(run([sys.executable, ROOT/'tools'/test], env=env, log=test+'.log').strip(), flush=True)
    run(['bash', 'gradlew', '--no-daemon', '--max-workers=2', '-Dorg.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8', ':app:testDebugUnitTest', ':app:assembleRelease', ':app:assembleDebug',
         ':app:assemblePhoneTest', ':app:assemblePhoneTestAndroidTest'], cwd=APP, env=env, log='android-build.log')
    metadata = json.loads((APP/'app/build/outputs/apk/release/output-metadata.json').read_text())
    item = metadata['elements'][0]; apk = APP/'app/build/outputs/apk/release'/item['outputFile']
    assert metadata['applicationId'] == 'com.st.st25nfc' and item['versionCode'] == 29 and item['versionName'] == '3.11.0-multirate'
    manifest = run([sdk/'build-tools/35.0.0/aapt2', 'dump', 'xmltree', '--file', 'AndroidManifest.xml', apk], env=env, log='apk-manifest.txt')
    assert 'DensorDebugActivity' not in manifest and 'FixtureActivity' not in manifest
    signature = run([sdk/'build-tools/35.0.0/apksigner', 'verify', '--verbose', '--print-certs', apk], env=env, log='apk-signature.txt')
    assert 'Signer #1 certificate SHA-256 digest:' in signature
    shutil.copy2(apk, OUT)
    result = {
        'built_at_utc': datetime.now(timezone.utc).isoformat(),
        'source_revision': run(['git','rev-parse','HEAD']).strip(),
        'protocol': 5, 'variants': reports,
        'application_id': metadata['applicationId'], 'version_name': item['versionName'], 'version_code': item['versionCode'],
        'apk': apk.name, 'apk_bytes': apk.stat().st_size, 'apk_sha256': digest(apk),
        'signing': 'Existing local debug signing configuration; compatibility checked by preserving-data phone install',
        'tests': ['ASan/UBSan C core and board tests (all four)', 'Java firmware fixture/CSV cross-check', 'R1 and historical compatibility',
                  'Revision/strategy selectors, invalid and conflicting options, isolated outputs, sequential switches and selected source exclusion', 'Android assemble and vital lint'],
        'hardware_gates': {k:'unmeasured' for k in ['physical_sensor_modes','NFC_RF_transactions','power_cut_tests','stack_high_water',
            'energy_2_6V','energy_2_2V','energy_1_8V']}, 'release_ready': False,
    }
    # Never package phone evidence for an older APK as if it tested this candidate.
    (OUT/'android-phone-results.json').unlink(missing_ok=True)
    phone = ROOT/'build/phone-multirate/android-phone-results.json'
    if phone.exists():
        evidence = json.loads(phone.read_text())
        if evidence['release_apk_sha256'] == result['apk_sha256']:
            result['phone_tests'] = evidence
            shutil.copy2(phone, OUT)
    (OUT/'manifest.json').write_text(json.dumps(result,indent=2)+'\n')
    (OUT/'SHA256SUMS').write_text(''.join(f'{digest(p)}  {p.name}\n' for p in sorted(OUT.iterdir()) if p.is_file() and p.name!='SHA256SUMS'))
    print(f'Unpublished candidate: {OUT}',flush=True)

if __name__ == '__main__': main()
