import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { STREAMS, COLUMNS, PRESETS, parseDataset, parseEvents, sampleDataset } from '../sampler.mjs';
import { encodePreviewStream, buildPreviewBundle, makeZip, EXPORT_ADAPTERS } from '../exporters.mjs';

const dataRoot = new URL('../../../data/synthetic_multirate/dataset/', import.meta.url);
const source = readFileSync(new URL('samples.csv', dataRoot));
const metadata = JSON.parse(readFileSync(new URL('metadata.json', dataRoot), 'utf8'));
const dataset = parseDataset(source.toString(), metadata);
dataset.sourceHash = createHash('sha256').update(source).digest('hex');

function config(periods = PRESETS.multirate, overrides = {}) {
  return { start: 0, end: 1800, phase: 0, streams: STREAMS.map((stream, i) => ({ id: stream.id, enabled: true, period: periods[i] })), ...overrides };
}

test('real dataset matches its provenance and events remain separate', () => {
  assert.equal(dataset.sourceHash, metadata.sha256['samples.csv']);
  assert.equal(dataset.count, 180000);
  assert.deepEqual(Object.keys(dataset.columns), COLUMNS);
  const events = parseEvents(readFileSync(new URL('events.csv', dataRoot), 'utf8'));
  assert.equal(events.length, Object.values(metadata.event_counts).reduce((sum, value) => sum + value, 0));
});

test('all three presets have independently calculated counts and aligned rows', () => {
  for (const [name, counts] of [['multirate', [45000, 1800, 180, 180]], ['original', [15, 15, 15, 15]], ['firmware', [180, 60, 15, 15]]]) {
    const result = sampleDataset(dataset, config(PRESETS[name]));
    assert.deepEqual(result.map(stream => stream.times.length), counts);
    for (const [i, stream] of result.entries()) {
      assert.equal(stream.indices[0], 0);
      assert.equal(stream.indices[1], PRESETS[name][i] * 100);
      assert.ok(stream.times.at(-1) < 1800);
    }
  }
});

test('off-grid rates, offsets and phases select the newest available row', () => {
  const cfg = config([1 / 3, 1 / 7, 1 / 29.97, 1 / .17], { start: 12.003, end: 234.567, phase: .0173 });
  for (const stream of sampleDataset(dataset, cfg)) {
    assert.equal(stream.times[0], cfg.start + cfg.phase);
    for (let i = 0; i < stream.times.length; i++) {
      const time = stream.times[i], row = stream.indices[i];
      // Independent oracle: search the actual serialized timestamps.
      let low = 0, high = dataset.count;
      while (low < high) {
        const middle = Math.floor((low + high) / 2);
        if (dataset.columns.time_s[middle] <= time) low = middle + 1;
        else high = middle;
      }
      assert.equal(row, low - 1);
      assert.ok(time < cfg.end);
      assert.ok(time - dataset.columns.time_s[row] < .010000001);
    }
  }
});

test('end-exclusive boundaries and 100 Hz floating-point alignment are stable', () => {
  const full = sampleDataset(dataset, config([.01, .01, .01, .01]));
  for (const stream of full) {
    assert.equal(stream.times.length, 180000);
    stream.indices.forEach((row, i) => assert.equal(row, i));
  }
  const short = sampleDataset(dataset, config([.1, .2, .3, .6], { start: .1, end: .7 }));
  assert.deepEqual(short.map(stream => stream.times.length), [6, 3, 2, 1]);
  const phase = sampleDataset(dataset, config([1, 1, 1, 1], { start: 1799, end: 1800, phase: .995 }));
  assert.equal(phase[0].indices[0], 179999);
});

test('disabled sensors are omitted, including invalid unused rate entries', () => {
  const cfg = config(); cfg.streams[0].enabled = false; cfg.streams[0].period = NaN;
  assert.deepEqual(sampleDataset(dataset, cfg).map(stream => stream.id), ['light', 'temp1', 'temp2']);
});

test('invalid rates, windows and phases cannot produce an export', () => {
  for (const patch of [{ start: -1 }, { end: 1801 }, { start: 5, end: 5 }, { phase: -1 }, { phase: 1800 }, { start: NaN }, { end: Infinity }]) {
    assert.throws(() => sampleDataset(dataset, config(undefined, patch)));
  }
  for (const period of [0, -.1, .001, NaN, Infinity]) assert.throws(() => sampleDataset(dataset, config([period, 1, 1, 1])));
  const none = config(); none.streams.forEach(stream => { stream.enabled = false; });
  assert.throws(() => sampleDataset(dataset, none), /Enable at least/);
});

test('malformed source rows and mismatching metadata are rejected', () => {
  const header = COLUMNS.join(',');
  const smallMetadata = { rows: 2, duration_s: .02, reference_rate_hz: 100 };
  const valid = `${header}\n0,1,2,3,0.5,36,36\n.01,1,2,3,0.5,36,36\n`;
  assert.equal(parseDataset(valid, smallMetadata).count, 2);
  for (const bad of [valid.replace('time_s', 'time'), valid.replace('.01,', '.02,'), valid.replace('0.5,', ',') , valid.replace('0.5,', 'NaN,')]) {
    assert.throws(() => parseDataset(bad, smallMetadata));
  }
  assert.throws(() => parseDataset(valid, { ...smallMetadata, rows: 3 }));
});

test('preview binary matches a manually specified little-endian golden vector', () => {
  const one = { columns: { time_s: [0], light_norm: [.5] } };
  const stream = { ...STREAMS[1], period: 1, times: [0], indices: [0] };
  const expected = '44534d524c4142000100010101000000000000000000f03f0000000000000000' + '000000000000000000000000000000000000003f';
  assert.equal(Buffer.from(encodePreviewStream(one, stream)).toString('hex'), expected);
});

test('Python independently unzips and decodes every multirate record', () => {
  const cfg = config([.04, .3, .7, 1.1], { start: 593, end: 598, phase: .013 });
  const samples = sampleDataset(dataset, cfg);
  const bundle = buildPreviewBundle(dataset, samples, cfg);
  assert.equal(bundle.manifest.firmware_compatible, false);
  const archive = makeZip(bundle.files);
  assert.deepEqual(archive, makeZip(bundle.files), 'export is deterministic');
  const check = spawnSync('python3', ['-c', `
import io, json, struct, sys, zipfile
with zipfile.ZipFile(io.BytesIO(sys.stdin.buffer.read())) as archive:
    assert archive.testzip() is None
    manifest = json.loads(archive.read('manifest.json'))
    assert not manifest['firmware_compatible']
    assert len(archive.namelist()) == 5
    decoded = []
    for stream_id, stream in enumerate(manifest['streams']):
        data = archive.read(stream['file'])
        magic, version, sid, channels, count, period, first = struct.unpack_from('<8sHBBIdd', data)
        assert magic == b'DSMRLAB\\0' and version == 1 and sid == stream_id
        assert channels == len(stream['columns']) and count == stream['count']
        assert len(data) == 32 + count * stream['record_bytes'] == stream['file_bytes']
        assert period == stream['period_s']
        records = list(struct.iter_unpack('<dd' + 'f' * channels, data[32:]))
        assert records[0][0] == first
        decoded.append(records)
    print(json.dumps(decoded))
`], { input: archive, maxBuffer: 5e6 });
  assert.equal(check.status, 0, check.stderr.toString());
  const decoded = JSON.parse(check.stdout);
  decoded.forEach((records, s) => records.forEach((record, i) => {
    const stream = samples[s], row = stream.indices[i];
    assert.equal(record[0], stream.times[i]);
    assert.equal(record[1], dataset.columns.time_s[row]);
    stream.columns.forEach((column, c) => assert.equal(record[c + 2], Math.fround(dataset.columns[column][row])));
  }));
});

test('lab preview stays separate from the five firmware adapters', () => {
  assert.equal(EXPORT_ADAPTERS.filter(adapter => adapter.encode).length, 6);
  assert.equal(EXPORT_ADAPTERS[0].firmwareCompatible, false);
  assert.deepEqual(EXPORT_ADAPTERS.filter(adapter => adapter.firmwareCompatible).map(adapter => adapter.id), ['r1', 'r2a', 'r2b', 'r3a', 'r3b']);
});
