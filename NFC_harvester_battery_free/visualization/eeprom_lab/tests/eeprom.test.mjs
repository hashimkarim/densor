import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { DEFAULTS, KINDS, NAMES, allocate, crc8, metadata, pageTouches, recordBytes, schedule, simulate, smartPad, snapshot, validate } from '../lib/eeprom.ts';
const read = name => readFileSync(new URL(`../public/sources/${name}`, import.meta.url));
const manifest = JSON.parse(read('fixture-manifest.json'));
const byteHex = bytes => Buffer.from(bytes.map(b => b.value)).toString('hex');

test('names follow R2 shared / R3 partitions, independently of the scheduler suffix', () => {
  assert.deepEqual(KINDS.map(k => NAMES[k]), ['Baseline', 'R1', 'R2a/b', 'R3a/b']);
  for (const f of manifest.files.filter(f => f.timing)) {
    assert.equal(f.revision, `${f.storage === 2 ? 'R2' : 'R3'}${f.timing === 1 ? 'a' : 'b'}`);
  }
});

test('all 15 due masks match independent C-produced shared and partitioned record vectors', () => {
  const fixtures = JSON.parse(read('multirate-record-fixtures.json'));
  assert.equal(fixtures.length, 15);
  for (const f of fixtures) {
    assert.equal(byteHex(recordBytes('shared', f.mask)), f.shared, `Shared mask ${f.mask}`);
    for (const [stream, expected] of Object.entries(f.partitioned)) assert.equal(byteHex(recordBytes('partitioned', f.mask, Number(stream))), expected, `Partition ${stream}, due ${f.mask}`);
  }
  assert.equal(crc8([...Buffer.from('123456789')]), 0xf4);
});

for (const fixture of manifest.files) test(`${fixture.revision}: every simulated record, count, region and final pointer matches golden EEPROM`, () => {
  const data = read(fixture.file);
  assert.equal(createHash('sha256').update(data).digest('hex'), fixture.sha256);
  const kind = fixture.revision === 'R1' ? 'r1' : fixture.storage === 1 ? 'partitioned' : 'shared';
  const settings = { capacity: fixture.file_bytes, mask: 15, periods: fixture.periods_seconds, commonPeriod: fixture.periods_seconds[0] };
  const sim = simulate(kind, settings), snap = snapshot(sim, fixture.last_elapsed_seconds);
  assert.equal(sim.start, fixture.log_start);
  assert.deepEqual(snap.counts, fixture.record_counts);
  assert.equal(snap.wakes, fixture.decoded_rows);
  for (const r of snap.records) assert.equal(byteHex(r.bytes), data.subarray(r.address, r.address + r.bytes.length).toString('hex'), `${fixture.revision} at ${r.address}`);
  if (kind === 'r1') assert.deepEqual(snap.pointers, [fixture.next_write_pointer]);
  else {
    assert.deepEqual(snap.pointers, fixture.next_write_pointers.filter(Boolean));
    assert.deepEqual(sim.regions.map(r => r.start), fixture.region_starts.filter(Boolean));
    assert.deepEqual(sim.regions.map(r => r.end), fixture.region_ends.filter(Boolean));
  }
});

test('FSM and RTC fixtures differ only at the scheduler byte and header CRC', () => {
  for (const family of ['R2', 'R3']) {
    const a = read(manifest.files.find(f => f.revision === `${family}a`).file), b = read(manifest.files.find(f => f.revision === `${family}b`).file);
    assert.deepEqual([...a.keys()].filter(i => a[i] !== b[i]), [19, 74, 75]);
  }
});

test('page geometry, baseline payload and R1 alignment expose the actual changes', () => {
  assert.equal(pageTouches(7, 2), 2);
  assert.equal(pageTouches(8, 2), 1);
  assert.equal(byteHex(recordBytes('baseline', 15)), '08ffd20400c000000040');
  assert.equal(recordBytes('baseline', 15).length, 10);
  assert.equal(recordBytes('r1', 13).length, 12);
  assert.equal(recordBytes('r1', 15).length, 16);
  assert.equal(recordBytes('baseline', 4).length, 3);
  assert.equal(recordBytes('baseline', 8).length, 6);
  assert.deepEqual([4, 5, 6, 7, 8].map(smartPad), [4, 5, 6, 8, 8]);
  const base = simulate('baseline', DEFAULTS), snap = snapshot(base, 240);
  assert.deepEqual(snap.counts, [25, 0, 25, 25]);
  assert.equal(snap.used, 250);
  assert.equal(snap.padding, 0);
  assert.deepEqual(base.records.slice(0, 2).map(r => pageTouches(r.address, r.bytes.length)), [3, 4]);
});

test('allocation, preflight, complete-record boundaries and metadata coverage for all masks and tag densities', () => {
  for (const capacity of [512, 2048, 8192]) for (let mask = 1; mask <= 15; mask++) {
    const settings = { ...DEFAULTS, mask, capacity };
    const regions = allocate(settings);
    assert.equal(regions[0].start, 168);
    assert.equal(regions.at(-1).end, capacity);
    regions.forEach((r, i) => { assert.equal(r.start % 4, 0); assert.equal(r.end % 4, 0); if (i) assert.equal(r.start, regions[i - 1].end); assert.ok(r.end - r.start >= (r.stream === 3 ? 8 : 4)); });
    for (const kind of KINDS) {
      const sim = simulate(kind, settings), m = metadata(kind);
      assert.equal(m[0].start, 0); assert.equal(m.at(-1).end, sim.start);
      m.forEach((r, i) => { if (i) assert.equal(r.start, m[i - 1].end); });
      const addresses = new Set();
      for (const r of sim.records) {
        assert.ok(r.time < sim.stopAt);
        assert.ok(r.address >= sim.start);
        assert.ok(kind === 'baseline' ? r.address + r.bytes.length < capacity : r.address + r.bytes.length <= capacity);
        if (kind !== 'baseline') { assert.equal(r.address % 4, 0); assert.equal(r.bytes.length % 4, 0); }
        for (let a = r.address; a < r.address + r.bytes.length; a++) { assert.ok(!addresses.has(a)); addresses.add(a); }
      }
      if (kind === 'partitioned') {
        for (const time of new Set(sim.records.map(r => r.time))) {
          const due = [0, 1, 2, 3].filter(i => (mask & (1 << i)) && time % settings.periods[i] === 0);
          assert.deepEqual(sim.records.filter(r => r.time === time).map(r => r.stream), due);
        }
      }
      const end = snapshot(sim, sim.stopAt ?? 0);
      assert.equal(end.used, addresses.size);
      assert.ok(end.used + sim.start <= capacity);
      assert.equal(end.counts[1], kind === 'baseline' ? 0 : end.counts[1]);
    }
  }
});

test('schedule input validation and disabled TMP119-only baseline', () => {
  assert.deepEqual(schedule(DEFAULTS), { base: 10, multipliers: [12, 6, 3, 1], lcm: 12 });
  assert.throws(() => validate({ ...DEFAULTS, mask: 0 }), /Enable/);
  assert.throws(() => validate({ ...DEFAULTS, commonPeriod: 61 }), /common period/);
  assert.throws(() => validate({ ...DEFAULTS, periods: [NaN, 60, 30, 10] }), /whole-second/);
  assert.throws(() => validate({ ...DEFAULTS, periods: [53, 59, 61, 67] }), /65,535/);
  const disabled = simulate('baseline', { ...DEFAULTS, mask: 2 });
  assert.equal(disabled.records.length, 0); assert.equal(disabled.stopAt, null);
  assert.equal(snapshot(simulate('shared', DEFAULTS), 0).wakes, 1);
  assert.equal(snapshot(simulate('shared', DEFAULTS), 9).wakes, 1);
  assert.equal(snapshot(simulate('shared', DEFAULTS), 10).wakes, 2);
});

test('page-crossing savings at 240 s: 12 extra record pages and 25 pointer crossings avoided', async () => {
  const { pageMetrics, pageCrossings } = await import('../lib/eeprom.ts');
  assert.equal(DEFAULTS.capacity, 8192);
  assert.equal(pageCrossings(9, 10), 2);
  assert.equal(pageCrossings(19, 10), 3);
  assert.equal(pageCrossings(128, 16), 3); // Normal boundaries still exist in an aligned record.
  const stats = KINDS.map(k => pageMetrics(simulate(k, DEFAULTS), 240));
  assert.deepEqual(stats.map(s => s.avoidableCrossings), [37, 0, 0, 0]);
  assert.deepEqual(stats.map(s => s.totalCrossings), [87, 75, 55, 25]);
  assert.deepEqual(stats.map(s => s.crossingCommands), [50, 0, 0, 0]);
  assert.deepEqual(stats.map(s => s.pointerWrites), [25, 25, 2, 8]);
  assert.deepEqual(stats.map(s => s.totalPageTouches), [137, 125, 82, 75]);
  assert.deepEqual([0, 10].map(t => pageMetrics(simulate('baseline', DEFAULTS), t).avoidableCrossings), [1, 3]);
  const matched = { ...DEFAULTS, mask: 13, periods: [10, 10, 10, 10] };
  const base = pageMetrics(simulate('baseline', matched), 240), r1 = pageMetrics(simulate('r1', matched), 240);
  assert.equal(base.totalCrossings - r1.totalCrossings, 37);
  assert.equal(base.totalPageTouches - r1.totalPageTouches, 37);
});

test('periodic pointer traffic includes empty ticks, deduplicates overlapping checkpoints and stops before full', async () => {
  const { pageMetrics } = await import('../lib/eeprom.ts');
  const settings = { ...DEFAULTS, mask: 5, periods: [100, 60, 130, 10] }; // GCD 10; multipliers 10/13; LCM 130.
  const sim = simulate('shared', settings);
  assert.equal(pageMetrics(sim, 620).pointerWrites, 0);
  assert.equal(pageMetrics(sim, 630).pointerWrites, 1); // next=64, although tick 63 has no sample.
  assert.equal(pageMetrics(sim, 1290).pointerWrites, 3); // next=64,128,130.
  // Independently enumerate firmware's checkpoint condition over successful base ticks.
  for (const kind of ['shared', 'partitioned']) {
    const m = simulate(kind, settings), slots = kind === 'shared' ? 1 : 2;
    for (const time of [0, 630, 1290, 41590, m.stopAt, m.stopAt + 1000]) {
      let checkpoints = 0;
      for (let t = 0; t <= time && t < m.stopAt; t += 10) {
        const next = t / 10 + 1;
        if (next % 64 === 0 || next % 130 === 0) checkpoints++;
      }
      assert.equal(pageMetrics(m, time).pointerWrites, checkpoints * slots);
    }
  }
});

test('payload boundaries are separate from record and pointer boundaries', async () => {
  const { pageMetrics } = await import('../lib/eeprom.ts');
  const stats = KINDS.map(k => pageMetrics(simulate(k, DEFAULTS), 240));
  assert.deepEqual(stats.map(s => s.payloadCrossings), [62, 75, 25, 25]);
  assert.deepEqual(stats.map(s => s.payloadExtraCrossings), [37, 50, 0, 0]);
  const matched = { ...DEFAULTS, mask: 13, periods: [10, 10, 10, 10] };
  assert.deepEqual(KINDS.map(k => pageMetrics(simulate(k, matched), 240).payloadCrossings), [62, 50, 25, 25]);
});

test('packing replay holds observations and fields fixed while removing only padding', async () => {
  const { packingMetrics } = await import('../lib/eeprom.ts');
  const settings = { ...DEFAULTS, mask: 1, periods: [10, 10, 10, 10] };
  const r1 = packingMetrics(simulate('r1', settings), 30);
  // Four 3-byte records at offsets 0,3,2,1 cross 0,1,1,0 boundaries.
  // Payloads start one byte later and cross only at offset 3.
  assert.equal(r1.unpaddedRecordCrossings, 2);
  assert.equal(r1.recordCrossingsSaved, 2);
  assert.equal(r1.payloadCrossingsSaved, 1);
  assert.equal(r1.paddingBytes, 4);
  const shared = packingMetrics(simulate('shared', settings), 30);
  assert.equal(shared.recordCrossingsSaved, 0); // Both 5-byte and 8-byte writes span two pages here.
  assert.equal(shared.payloadCrossingsSaved, 1);
  const partition = packingMetrics(simulate('partitioned', settings), 30);
  assert.equal(partition.paddingBytes, 0);
  assert.equal(partition.recordCrossingsSaved, 0);
  assert.equal(partition.payloadCrossingsSaved, 0);
});

test('editable payloads expose smart-padding savings and strides that still cross pages', async () => {
  const { SENSORS, packingMetrics, isFirmwareLayout } = await import('../lib/eeprom.ts');
  const settings = { ...DEFAULTS, mask: 1, periods: [10, 10, 10, 10], sensors: SENSORS.map((s, i) => ({ ...s, payload: i === 0 ? 5 : s.payload })) };
  assert.equal(isFirmwareLayout(settings), false);
  const sim = simulate('partitioned', settings), p = packingMetrics(sim, 30);
  assert.equal(sim.exploratory, true);
  assert.deepEqual(sim.records.slice(0, 4).map(r => r.address), [168, 176, 184, 192]);
  assert.equal(p.paddingBytes, 4);
  assert.equal(p.unpaddedRecordCrossings, 6); // 7-byte records at offsets 0,3,2,1: 1+2+2+1.
  assert.equal(p.recordCrossings, 4);
  assert.equal(p.streams[0].recordCrossingsSaved, 2);
  assert.equal(p.payloadCrossingsSaved, 0); // A five-byte payload spans two pages at every offset.
  settings.sensors[0].payload = 3;
  const unaligned = simulate('partitioned', settings), q = packingMetrics(unaligned, 30);
  assert.deepEqual(unaligned.records.slice(0, 4).map(r => r.address), [168, 173, 178, 183]);
  assert.equal(q.paddingBytes, 0); // Smart padding leaves a one-byte remainder unchanged.
  assert.equal(q.payloadExtraCrossings, 2);
  assert.equal(q.recordCrossingsSaved, 0);
});

test('additional sensors participate in allocation, records, checkpoints, payload counts and capacity limits', async () => {
  const { SENSORS, packingMetrics, pageMetrics } = await import('../lib/eeprom.ts');
  const sensors = [...SENSORS, ...[1, 3, 5, 7].map((payload, i) => ({ name: `Extra ${i}`, short: `E${i}`, type: `extra${i + 1}`, payload, raw: [], example: 'Synthetic' }))];
  const settings = { ...DEFAULTS, sensors, mask: 255, periods: sensors.map(() => 10) };
  for (const kind of KINDS) {
    const sim = simulate(kind, settings), s = snapshot(sim, 30), p = packingMetrics(sim, 30);
    assert.equal(s.counts.length, 8);
    assert.deepEqual(s.counts, sensors.map((_, i) => kind === 'baseline' && i === 1 ? 0 : 4));
    assert.deepEqual(p.streams.map(s => s.observations), s.counts);
    assert.equal(s.payload, sensors.reduce((n, sensor, i) => n + sensor.payload * s.counts[i], 0));
    for (const r of sim.records) {
      const region = sim.regions.find(region => region.stream === r.stream);
      assert.ok(r.address >= region.start && r.address + r.bytes.length <= region.end);
    }
    if (kind === 'partitioned') {
      assert.equal(sim.start, 200); // 136 + eight 8-byte pointer pairs.
      assert.equal(metadata(kind, sensors).at(-1).end, 200);
      assert.equal(pageMetrics(sim, 30).pointerWrites, 32);
      assert.deepEqual(sim.regions.map(r => r.stream), [0, 1, 2, 3, 4, 5, 6, 7]);
      const stopped = packingMetrics(sim, sim.stopAt), later = packingMetrics(sim, sim.stopAt + 1000);
      assert.deepEqual(stopped, later);
    }
  }
  // Disabling an interior stream keeps the surviving streams' identities.
  const disabled = simulate('partitioned', { ...settings, mask: 255 ^ 32 });
  assert.deepEqual(disabled.regions.map(r => r.stream), [0, 1, 2, 3, 4, 6, 7]);
  assert.equal(snapshot(disabled, 0).counts[5], 0);
  assert.throws(() => simulate('partitioned', { ...settings, capacity: 512, sensors: sensors.map(s => ({ ...s, payload: 64 })) }), /too small/);
  assert.throws(() => validate({ ...settings, sensors: sensors.map(s => ({ ...s, payload: 0 })) }), /Payload lengths/);
  assert.throws(() => validate({ ...settings, sensors: [...sensors, sensors[0]], periods: [...settings.periods, 10] }), /sensor slots/);
});
