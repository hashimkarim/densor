import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { STREAMS, sampleDataset, parseDataset } from '../sampler.mjs';
import { FIRMWARE_STREAMS, REVISIONS, EXPORT_ADAPTERS, buildFirmwareBundle, firmwareSchedule, firmwareSnapStep, snapFirmwarePeriods, crc8, crc16, makeZip } from '../exporters.mjs';

const software = new URL('../../../software/', import.meta.url);
// Preserve the earlier import artifacts, whose filenames used the old names.
const output = new URL('build/sampling-lab-export-check/current/', software);
mkdirSync(output, { recursive: true });
const classes = new URL('classes/', output); mkdirSync(classes, { recursive: true });
const javaRoot = 'source_ST25NFCApplication_V3_9/app/src/main/java/com/st/st25nfc/densor/';
const decoderCommit = '48a8f137aa9dba162e88bdad67b549bfde232d40';
// Read the authoritative sources without checking out or modifying a shared
// worktree. Unrelated Android edits must not silently change this test oracle.
const pinned = new URL('decoder-source/', output);
mkdirSync(new URL('data/', pinned), { recursive: true });
const sources = ['DensorProtocol.java', 'DensorMultirate.java', 'data/DensorDataSample.java', 'data/DensorDataSet.java'].map(name => {
  const result = spawnSync('git', ['show', `${decoderCommit}:NFC_harvester_battery_free/software/${javaRoot}${name}`], { cwd: fileURLToPath(software), encoding: 'utf8' });
  assert.equal(result.status, 0, `Authoritative decoder commit must be available: ${result.stderr}`);
  const path = new URL(name, pinned); writeFileSync(path, result.stdout); return fileURLToPath(path);
});
const compile = spawnSync('javac', ['-d', fileURLToPath(classes), ...sources, fileURLToPath(new URL('ExportDecoderOracle.java', import.meta.url))], { encoding: 'utf8' });
assert.equal(compile.status, 0, compile.stderr);

const dataRoot = new URL('../data/synthetic_multirate/dataset/', software);
const bytes = readFileSync(new URL('samples.csv', dataRoot));
const real = parseDataset(bytes.toString(), JSON.parse(readFileSync(new URL('metadata.json', dataRoot))));
real.sourceHash = createHash('sha256').update(bytes).digest('hex');

function constantDataset(duration = 250, rate = 1) {
  const count = Math.ceil(duration * rate);
  const columns = { time_s: Float64Array.from({ length: count }, (_, i) => i / rate) };
  const values = [-1, 0, 1, 1234 / 4095, 24, -1];
  STREAMS.flatMap(stream => stream.columns).forEach((column, i) => { columns[column] = new Float64Array(count).fill(values[i]); });
  return { columns, count, metadata: { duration_s: duration, reference_rate_hz: rate }, sourceHash: createHash('sha256').update(JSON.stringify({ values, duration, rate })).digest('hex') };
}

function config(dataset, mask = 15, periods = [120, 60, 30, 10], patch = {}) {
  return { start: 0, end: dataset.metadata.duration_s, phase: 0, streams: STREAMS.map(stream => {
    const index = FIRMWARE_STREAMS.findIndex(item => item.id === stream.id);
    return { id: stream.id, enabled: Boolean(mask & (1 << index)), period: periods[index] };
  }), ...patch };
}
function build(dataset, cfg, revision, capacity = 2048) { return buildFirmwareBundle(dataset, sampleDataset(dataset, cfg), cfg, { revision, capacity }); }
function save(bundle, name = bundle.files[0].name) {
  const path = new URL(name, output); writeFileSync(path, bundle.files[0].bytes); return fileURLToPath(path);
}
function decode(paths) {
  const result = spawnSync('java', ['-ea', '-cp', fileURLToPath(classes), 'ExportDecoderOracle', ...paths], { encoding: 'utf8', maxBuffer: 32e6 });
  assert.equal(result.status, 0, result.stderr);
  return result.stdout.trim().split('\n').map(line => JSON.parse(line));
}
function expectErasedTails(bundle) {
  const { starts, ends, pointers } = bundle.manifest.allocation;
  for (let i = 0; i < 4; i++) if (ends[i]) {
    assert.ok(pointers[i] >= starts[i] && pointers[i] <= ends[i]);
    assert.ok(bundle.files[0].bytes.slice(pointers[i], ends[i]).every(value => value === 255));
  }
}

test('revision names select the advertised storage and scheduler in the header and sidecar', () => {
  const dataset = constantDataset();
  for (const [revision, storage, scheduler, description] of [
    ['r2a', 2, 1, 'shared pool · FSM'], ['r2b', 2, 2, 'shared pool · RTC timer'],
    ['r3a', 1, 1, 'partitions · FSM'], ['r3b', 1, 2, 'partitions · RTC timer'],
  ]) {
    const adapter = EXPORT_ADAPTERS.find(item => item.id === revision);
    const cfg = config(dataset);
    const bundle = adapter.encode(dataset, sampleDataset(dataset, cfg), cfg, { capacity: 2048 });
    assert.equal(adapter.name, `${REVISIONS[revision].label} · ${description} · v5`);
    assert.equal(bundle.files[0].bytes[17], storage);
    assert.equal(bundle.files[0].bytes[19], scheduler);
    assert.equal(bundle.manifest.revision.toLowerCase(), revision);
    assert.equal(bundle.manifest.scheduler, scheduler === 1 ? 'FSM' : 'RTC timer');
    assert.deepEqual(bundle.files.map(file => file.name), [`synthetic-${revision}-v5-2048.bin`, `synthetic-${revision}-v5-2048.json`]);
  }
});

test('one rate adjustment aligns all enabled periods without changing the source window or disabled sensors', () => {
  const cfg = config(real, 7, [119.2, 58.5, 34.8, NaN], { start: 12.003, end: 1000, phase: .0173 });
  const original = structuredClone(cfg);
  const result = snapFirmwarePeriods(cfg, { step: 10, maximumPeriod: 1800 });
  assert.deepEqual(result.config.streams.map(stream => stream.period), [NaN, 30, 120, 60]);
  assert.equal(result.config.start, cfg.start);
  assert.equal(result.config.end, cfg.end);
  assert.equal(result.config.phase, cfg.phase);
  assert.deepEqual(cfg, original, 'alignment must not mutate its input');
  assert.equal(firmwareSchedule(result.config, 'r3b').base, 30);
  assert.equal(result.step, 10, 'keep the original step throughout the adjustment');
  assert.equal(firmwareSnapStep(config(real).streams), 10);
});

test('fractional exploration settings align to integral periods and every revision decodes', () => {
  const cfg = config(real, 15, [10, 10, 1, .04]);
  const paths = [];
  for (const revision of Object.keys(REVISIONS)) {
    const aligned = snapFirmwarePeriods(cfg, { revision, maximumPeriod: 1800, changedId: 'light' }).config;
    assert.ok(aligned.streams.every(stream => Number.isInteger(stream.period) && stream.period >= 1));
    if (revision === 'r1') assert.ok(aligned.streams.every(stream => stream.period === 1));
    else assert.deepEqual(aligned.streams.map(stream => stream.period), [1, 1, 10, 10]);
    const bundle = build(real, aligned, revision, 8192);
    paths.push(save(bundle, `snapped-${revision}.bin`));
  }
  assert.ok(decode(paths).every(result => result.ok && result.complete));
});

test('automatic alignment coarsens an oversized LCM and clamps to whole in-range multiples', () => {
  const cfg = config(real, 15, [1790, 1730, 1670, 10]);
  assert.throws(() => firmwareSchedule(cfg, 'r3b'), /LCM/);
  const result = snapFirmwarePeriods(cfg, { step: 10, maximumPeriod: 1800 });
  assert.ok(result.step > 10);
  assert.ok(firmwareSchedule(result.config, 'r3b').lcm <= 65535);
  assert.ok(result.config.streams.every(stream => stream.period % result.step === 0 && stream.period <= 1800));
  const edges = snapFirmwarePeriods(config(real, 15, [.01, 1800, -10, 9999]), { step: 7, maximumPeriod: 1800 });
  assert.deepEqual(edges.config.streams.map(stream => stream.period), [1799, 7, 7, 1799]);
  const none = config(real, 0);
  assert.deepEqual(snapFirmwarePeriods(none, { maximumPeriod: 1800 }).config, none);
  assert.throws(() => snapFirmwarePeriods(config(real, 1, [NaN, 1, 1, 1]), { step: 1, maximumPeriod: 1800 }), /nonfinite/);
});

test('R1 alignment follows the moved sensor and respects the seconds/minutes encoding boundary', () => {
  for (const [requested, expected] of [[.04, 1], [59.4, 59], [59.6, 60], [61, 60], [91, 120], [1801, 1800]]) {
    const cfg = config(real, 15, [120, 120, requested, 10]);
    const aligned = snapFirmwarePeriods(cfg, { revision: 'r1', maximumPeriod: 1800, changedId: 'light' }).config;
    assert.ok(aligned.streams.every(stream => stream.period === expected));
    assert.equal(firmwareSchedule(aligned, 'r1').base, expected);
  }
});

test('CRC vectors and all five canonical images match byte for byte', () => {
  const vector = new TextEncoder().encode('123456789');
  assert.equal(crc8(vector), 0xf4); assert.equal(crc16(vector), 0x29b1);
  const goldenRoot = new URL('docs/fixtures/bin-dump-export/', software);
  const manifest = JSON.parse(readFileSync(new URL('manifest.json', goldenRoot)));
  const generated = [];
  for (const golden of manifest.files) {
    const dataset = constantDataset();
    const cfg = config(dataset, 15, golden.periods_seconds);
    // Golden filenames predate the naming correction. Match their wire fields.
    const revision = Object.keys(REVISIONS).find(key => {
      const target = REVISIONS[key];
      return target.version === golden.protocol_version && target.storage === golden.storage && target.scheduler === golden.timing;
    });
    assert.ok(revision, `Unknown golden wire fields: ${golden.file}`);
    const bundle = build(dataset, cfg, revision);
    const image = bundle.files[0].bytes;
    assert.equal(createHash('sha256').update(image).digest('hex'), golden.sha256);
    assert.deepEqual(Buffer.from(image), readFileSync(new URL(golden.file, goldenRoot)));
    assert.deepEqual(bundle.manifest.record_counts, golden.record_counts);
    assert.equal(bundle.manifest.union_wake_count, 25);
    assert.equal(bundle.manifest.last_elapsed_s, 240);
    expectErasedTails(bundle);
    generated.push(save(bundle));
  }
  assert.ok(decode(generated).every(result => result.ok && result.complete));
});

test('all 5 revisions × 15 sensor masks × 3 capacities decode against quantized real CSV samples', () => {
  const cases = [];
  for (const revision of Object.keys(REVISIONS)) for (let mask = 1; mask <= 15; mask++) for (const capacity of [512, 2048, 8192]) {
    const periods = revision === 'r1' ? [10, 10, 10, 10] : [6, 10, 15, 4];
    const cfg = config(real, mask, periods, { start: 12.003, phase: .0173 });
    const bundle = build(real, cfg, revision, capacity);
    assert.equal(bundle.files[0].bytes.length, capacity);
    expectErasedTails(bundle);
    const path = save(bundle, `${revision}-mask${mask}-${capacity}.bin`);
    cases.push({ cfg, bundle, path, revision, mask, periods });
  }
  const results = decode(cases.map(item => item.path));
  cases.forEach(({ cfg, bundle, revision, mask, periods }, n) => {
    const result = results[n];
    assert.ok(result.ok && result.complete, `${revision} mask ${mask}`);
    // The pinned decoder predates the naming correction, but its wire decoding
    // remains authoritative. Check its historical labels separately from ours.
    const target = REVISIONS[revision];
    const historicalRevision = revision === 'r1' ? 'r1' : `r${target.scheduler === 1 ? 2 : 3}${target.storage === 1 ? 'a' : 'b'}`;
    assert.equal(result.revision.toLowerCase(), historicalRevision);
    assert.equal(result.csv_format, revision === 'r1' ? 'r1' : `${historicalRevision}-v5`);
    assert.equal(bundle.manifest.revision.toLowerCase(), revision);
    assert.deepEqual(result.counts, bundle.manifest.record_counts);
    assert.deepEqual(result.pointers, bundle.manifest.allocation.pointers);
    assert.equal(result.times.length, bundle.manifest.union_wake_count);
    const origin = cfg.start + cfg.phase, counts = [0, 0, 0, 0];
    let elapsed = 0;
    result.times.forEach((time, j) => {
      assert.equal(time, elapsed);
      assert.ok(origin + time < cfg.end);
      const due = periods.map((period, i) => Boolean(mask & (1 << i)) && time % period === 0);
      const row = Math.floor((origin + time) * real.metadata.reference_rate_hz);
      const expected = [];
      for (let i = 0; i < 4; i++) {
        const stream = FIRMWARE_STREAMS[i];
        expected.push(...stream.columns.map(column => {
          if (!due[i]) return null;
          const raw = Math.round((real.columns[column][row] - stream.offset) * stream.scale);
          return i === 2 ? raw : raw / stream.scale + stream.offset;
        }));
        if (due[i]) counts[i]++;
      }
      expected.push(due.slice(0, 3).some(Boolean) ? 2.6 : null);
      result.rows[j].forEach((value, k) => {
        if (expected[k] === null) assert.equal(value, null);
        else assert.ok(Math.abs(value - expected[k]) < 0.000005, `${revision} row ${j} column ${k}`);
      });
      elapsed = Math.min(...periods.map((period, i) => mask & (1 << i) ? counts[i] * period : Infinity));
    });
    assert.deepEqual(counts, result.counts);
    assert.equal(bundle.manifest.first_omitted_wake.elapsed_s, elapsed);
    if (bundle.manifest.stop_reason === 'window-end') assert.ok(origin + elapsed >= cfg.end);
    else {
      assert.ok(origin + elapsed < cfg.end);
      const { pointers, ends } = bundle.manifest.allocation;
      const due = periods.map((period, i) => Boolean(mask & (1 << i)) && elapsed % period === 0);
      if (revision !== 'r1' && target.storage === 1) assert.ok(due.some((yes, i) => yes && pointers[i] + FIRMWARE_STREAMS[i].stride > ends[i]));
      else {
        const payload = due.reduce((sum, yes, i) => sum + (yes ? FIRMWARE_STREAMS[i].payload : 0), 0);
        const stride = Math.ceil(((revision === 'r1' ? 1 : 3) + payload) / 4) * 4;
        assert.ok(pointers[0] + stride > ends[0]);
      }
    }
  });
  writeFileSync(new URL('matrix-summary.json', output), JSON.stringify({ cases: cases.length, result: 'passed', source_sha256: real.sourceHash, revisions: Object.keys(REVISIONS), capacities: [512, 2048, 8192], masks: '1..15' }, null, 2));
});

test('FSM/RTC pairs differ only at scheduler and header CRC bytes', () => {
  for (const revision of ['r2', 'r3']) {
    const cfg = config(real);
    const a = build(real, cfg, `${revision}a`).files[0].bytes;
    const b = build(real, cfg, `${revision}b`).files[0].bytes;
    assert.deepEqual(Array.from(a.keys()).filter(i => a[i] !== b[i]), [19, 74, 75]);
  }
});

test('capacity exact fits, empty ticks, and preflight of a whole mixed wake', () => {
  for (const [revision, mask, stride, log] of [['r1', 15, 16, 128], ['r3a', 8, 8, 168], ['r2b', 4, 8, 168]]) {
    const cfg = config(real, mask, [1, 1, 1, 1]);
    const bundle = build(real, cfg, revision, 512);
    assert.equal(bundle.manifest.union_wake_count, (512 - log) / stride);
    assert.equal(bundle.manifest.stop_reason, 'capacity');
    assert.ok(bundle.manifest.allocation.pointers.includes(512));
  }
  const mixed = build(real, config(real, 15, [6, 10, 15, 4]), 'r3a', 512);
  const stop = mixed.manifest.first_omitted_wake.elapsed_s;
  mixed.manifest.record_counts.forEach((count, i) => assert.equal(count, Math.ceil(stop / [6, 10, 15, 4][i])));
  const decoded = decode([save(mixed, 'empty-ticks.bin')])[0];
  assert.equal(decoded.times[0], 0); assert.equal(decoded.times[1], 4);
  assert.ok(!decoded.times.includes(1));
});

test('invalid schedules, nonfinite/out-of-range values and missing due samples fail explicitly', () => {
  for (const period of [.04, 1.5, NaN, Infinity, 0, -1, 2592001]) {
    assert.throws(() => firmwareSchedule(config(real, 1, [period, 1, 1, 1]), 'r2a'));
  }
  assert.throws(() => firmwareSchedule(config(real, 3, [256, 257, 1, 1]), 'r2b'), /LCM/);
  assert.equal(firmwareSchedule(config(real, 3, [255, 257, 1, 1]), 'r2b').lcm, 65535);
  assert.equal(firmwareSchedule(config(real, 1, [7200, 1, 1, 1]), 'r2a').base, 2400);
  assert.throws(() => firmwareSchedule(config(real, 1, [2591999, 1, 1, 1]), 'r3a'), /multiplier/);
  assert.throws(() => firmwareSchedule(config(real), 'r1'), /common period/);
  assert.throws(() => firmwareSchedule(config(real, 1, [61, 1, 1, 1]), 'r1'), /R1 periods/);
  for (const [column, value] of [['temp_1_c', NaN], ['temp_1_c', 200], ['temp_2_c', -300], ['light_norm', -.001], ['light_norm', 1.001], ['accel_x_g', 2], ['accel_y_g', -2.001]]) {
    const dataset = constantDataset(); dataset.columns[column][0] = value;
    assert.throws(() => build(dataset, config(dataset), 'r3b'), /nonfinite|range|within/);
  }
  const cfg = config(real), streams = sampleDataset(real, cfg);
  streams[0].times = streams[0].times.slice(1);
  assert.throws(() => buildFirmwareBundle(real, streams, cfg, { revision: 'r2b' }), /Unexpected sample time/);
  assert.throws(() => build(real, cfg, 'r2b', 1024), /capacity/);
  const long = constantDataset(7776000, 1 / 2592000);
  assert.throws(() => build(long, config(long, 1, [2592000, 1, 1, 1]), 'r3a'), /30 days/);
});

test('rounding ties, signed extremes and disabled invalid columns do not wrap', () => {
  const dataset = constantDataset();
  dataset.columns.temp_1_c[0] = 25 - .5 / 256;
  dataset.columns.temp_2_c[0] = -.5 / 128;
  dataset.columns.accel_x_g[0] = -2;
  dataset.columns.accel_y_g[0] = 32767 / 16384;
  const bundle = build(dataset, config(dataset), 'r2b');
  const row = decode([save(bundle, 'signed-edges.bin')])[0].rows[0];
  assert.deepEqual(row.slice(0, 2), [25, 0]);
  assert.equal(row[3], -2); assert.ok(Math.abs(row[4] - 32767 / 16384) < 1e-7);
  dataset.columns.light_norm[0] = NaN;
  assert.doesNotThrow(() => build(dataset, config(dataset, 3), 'r2a'));
});

test('Android rejects damaged metadata, committed records, pointer pairs and padding', () => {
  const paths = [];
  function corrupt(revision, offset, name) {
    const cfg = config(real, 15, [10, 10, 10, 10]);
    const bundle = build(real, cfg, revision);
    bundle.files[0].bytes[offset] ^= 1;
    paths.push(save(bundle, name));
  }
  corrupt('r3a', 74, 'bad-header.bin'); corrupt('r2b', 90, 'bad-status.bin');
  corrupt('r3a', 168, 'bad-partition-crc.bin'); corrupt('r2b', 170, 'bad-shared-crc.bin');
  corrupt('r2b', 183, 'bad-shared-padding.bin'); corrupt('r1', 143, 'bad-r1-padding.bin');
  const pairs = build(real, config(real), 'r3a');
  pairs.files[0].bytes[139] ^= 1; pairs.files[0].bytes[143] ^= 1;
  paths.push(save(pairs, 'bad-pointer-pairs.bin'));
  assert.ok(decode(paths).every(result => !result.ok));
});

test('real-source Android import candidates include sidecars and validate independently', () => {
  const paths = [];
  for (const revision of ['r3b', 'r2b']) {
    const bundle = build(real, config(real, 15, [120, 60, 30, 10]), revision);
    for (const file of bundle.files) writeFileSync(new URL(`csv-${file.name}`, output), file.bytes);
    paths.push(save(bundle, `csv-${bundle.files[0].name}`));
  }
  assert.ok(decode(paths).every(result => result.ok && result.complete));
});

test('firmware ZIP contains one full EEPROM image plus its synthetic JSON sidecar', () => {
  const bundle = build(real, config(real), 'r3b', 512);
  const archive = makeZip(bundle.files);
  const unzip = spawnSync('python3', ['-c', `
import io, json, sys, zipfile
with zipfile.ZipFile(io.BytesIO(sys.stdin.buffer.read())) as archive:
    assert archive.testzip() is None
    assert archive.namelist() == ['synthetic-r3b-v5-512.bin', 'synthetic-r3b-v5-512.json']
    image = archive.read(archive.namelist()[0])
    assert len(image) == 512 and image[:8] == b'DENSOR2!'
    manifest = json.loads(archive.read(archive.namelist()[1]))
    assert manifest['synthetic'] and manifest['firmware_compatible']
    assert manifest['stop_reason'] == 'capacity'
    assert manifest['supply']['synthetic_assumption'] and manifest['supply']['code'] == 8
    assert manifest['clipping']['total_clipped_values'] == 0
    assert len(manifest['source']['sha256']) == 64
`], { input: archive });
  assert.equal(unzip.status, 0, unzip.stderr.toString());
});
