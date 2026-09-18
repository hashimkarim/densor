/** Executable layout model. Sources and limits are documented in README.md. */
export type Kind = 'baseline' | 'r1' | 'partitioned' | 'shared';
export type ByteKind = 'old' | 'tmp' | 'light' | 'accel' | 'extra1' | 'extra2' | 'extra3' | 'extra4' | 'supply' | 'crc' | 'pad' | 'mask' | 'meta' | 'pointer' | 'free';
export type Byte = { type: ByteKind; label: string; value: number | null; time?: number; stream?: number };
export type Sensor = { name: string; short: string; type: ByteKind; payload: number; raw: readonly number[]; example: string };
export type Settings = { capacity: number; mask: number; periods: number[]; commonPeriod: number; sensors?: readonly Sensor[] };
export type RecordEntry = { time: number; mask: number; address: number; bytes: Byte[]; stream: number | null };
export type Region = { name: string; stream: number | null; start: number; end: number };
export type Simulation = { kind: Kind; mask: number; start: number; records: RecordEntry[]; regions: Region[]; stopAt: number | null; lastAt: number | null; stopReason: string; base: number; lcm: number; capacity: number; sensors: readonly Sensor[]; exploratory: boolean };
export const MAX_SENSORS = 8;
export const SENSORS: readonly Sensor[] = [
  { name: 'LIS2DW12 temperature', short: 'LIS temp', type: 'old', payload: 2, raw: [0x00, 0xff], example: '24 °C' },
  { name: 'TMP119 temperature', short: 'TMP119', type: 'tmp', payload: 2, raw: [0x80, 0xff], example: '−1 °C' },
  { name: 'Photodiode', short: 'Light', type: 'light', payload: 2, raw: [0xd2, 0x04], example: '1234 ADC' },
  { name: 'Acceleration XYZ', short: 'Accel', type: 'accel', payload: 6, raw: [0x00, 0xc0, 0x00, 0x00, 0x00, 0x40], example: '−1, 0, +1 g' },
] as const;
export const KINDS: Kind[] = ['baseline', 'r1', 'shared', 'partitioned'];
export const NAMES: Record<Kind, string> = { baseline: 'Baseline', r1: 'R1', partitioned: 'R3a/b', shared: 'R2a/b' };
export const DEFAULTS: Settings = { capacity: 8192, mask: 15, periods: [120, 60, 30, 10], commonPeriod: 10 };
export const sensorsFor = (settings: Settings) => settings.sensors ?? SENSORS;
export const isFirmwareLayout = (settings: Settings) => {
  const sensors = sensorsFor(settings);
  return sensors.length === SENSORS.length && sensors.every((s, i) => s.type === SENSORS[i].type && s.payload === SENSORS[i].payload);
};
const logStart = (kind: Kind, sensors: readonly Sensor[]) => kind === 'baseline' ? 9 : kind === 'r1' ? 128 : kind === 'partitioned' ? 136 + sensors.length * 8 : 168;
export const MAX_SECONDS = 2592000;
export const gcd = (a: number, b: number): number => b ? gcd(b, a % b) : a;
export const hex = (n: number, digits = 2) => n.toString(16).toUpperCase().padStart(digits, '0');
export const selected = (mask: number) => Array.from({ length: MAX_SENSORS }, (_, i) => i).filter(i => mask & (1 << i));
export const align4 = (n: number) => Math.ceil(n / 4) * 4;
export const smartPad = (n: number) => n + (n % 4 === 3 ? 1 : 0);
export const pageTouches = (address: number, size: number) => size ? Math.floor((address + size - 1) / 4) - Math.floor(address / 4) + 1 : 0;
export const pageCrossings = (address: number, size: number) => Math.max(0, pageTouches(address, size) - 1);
export function schedule(settings: Settings) {
  const periods = selected(settings.mask).map(i => settings.periods[i]);
  if (!periods.length) throw new Error('Enable at least one sensor.');
  if (periods.some(n => !Number.isInteger(n) || n < 1 || n > 3540)) throw new Error('Use whole-second sensor periods from 1 to 3540.');
  const base = periods.reduce(gcd);
  const multipliers = settings.periods.map((p, i) => settings.mask & (1 << i) ? p / base : 0);
  const lcm = multipliers.filter(Boolean).reduce((a, b) => a / gcd(a, b) * b, 1);
  if (lcm > 65535) throw new Error('These periods need more than 65,535 schedule ticks per cycle. Choose periods with more common factors.');
  return { base, multipliers, lcm };
}
export function validate(settings: Settings) {
  const sensors = sensorsFor(settings);
  if (sensors.length < 4 || sensors.length > MAX_SENSORS) throw new Error(`Use four to ${MAX_SENSORS} sensor slots; disable unused sensors.`);
  if (sensors.some(s => !Number.isInteger(s.payload) || s.payload < 1 || s.payload > 64)) throw new Error('Payload lengths must be whole bytes from 1 to 64.');
  if (settings.periods.length !== sensors.length) throw new Error('Each sensor needs a sampling period.');
  if (![512, 2048, 8192].includes(settings.capacity)) throw new Error('Choose a 512 B, 2 KiB or 8 KiB tag.');
  if (!Number.isInteger(settings.mask) || settings.mask < 1 || settings.mask >= (1 << sensors.length)) throw new Error('Enable at least one configured sensor.');
  const p = settings.commonPeriod;
  if (!Number.isInteger(p) || p < 1 || p > 3540 || (p > 59 && p % 60 !== 0)) throw new Error('The common period must be 1–59 seconds, or whole minutes up to 59 minutes.');
  return schedule(settings);
}
export function crc8(bytes: number[]) {
  let c = 0;
  for (const b of bytes) { c ^= b; for (let i = 0; i < 8; i++) c = ((c << 1) ^ (c & 128 ? 7 : 0)) & 255; }
  return c;
}
const byte = (type: ByteKind, label: string, value: number | null): Byte => ({ type, label, value });
function payload(stream: number, sensors: readonly Sensor[]): Byte[] {
  const sensor = sensors[stream];
  return Array.from({ length: sensor.payload }, (_, i) => ({
    ...byte(sensor.type, `${sensor.name} · payload byte ${i + 1}`, sensor.raw[i] ?? 0), stream,
  }));
}
/** Constant illustrative values match the C-produced golden record fixtures. */
export function recordBytes(kind: Kind, mask: number, stream: number | null = null, sensors: readonly Sensor[] = SENSORS): Byte[] {
  if (!Number.isInteger(mask) || mask < 1 || mask >= (1 << sensors.length)) throw new Error('Invalid record mask.');
  const supply = mask & ~8 ? 8 : 255;
  if (kind === 'partitioned') {
    if (stream === null || !(mask & (1 << stream))) throw new Error('Partition record needs a due stream.');
    const b = [...payload(stream, sensors), byte('supply', supply === 255 ? 'Supply unavailable on acceleration-only wake' : 'Supply · 2.6 V', supply)];
    b.push(byte('crc', 'CRC8 of payload + supply', crc8(b.map(b => b.value!))));
    while (b.length < smartPad(b.length)) b.push(byte('pad', 'Smart padding · zero', 0));
    return b;
  }
  const active = kind === 'baseline' ? mask & ~2 : mask;
  const b: Byte[] = [];
  if (kind === 'shared') b.push(byte('mask', 'Due sensor mask', mask));
  if (kind !== 'baseline' || (!(active & 1) && (active & ~8))) b.push(byte('supply', supply === 255 ? 'Supply unavailable' : 'Supply · 2.6 V', supply));
  for (const i of selected(active)) b.push(...payload(i, sensors));
  if (kind === 'baseline') {
    if (active & 1) b[0] = { ...byte('old', 'LIS2DW12 temperature low byte OR supply nibble (08)', b[0].value! | 8), stream: 0 };
    return b;
  }
  if (kind === 'shared') b.push(byte('crc', 'CRC8 of mask + supply + payloads', crc8(b.map(b => b.value!))));
  const length = align4(b.length);
  while (b.length < length) b.push(byte('pad', 'Whole-page padding · zero', 0));
  return b;
}
/** Android v5 allocation: reserve one record, floor weighted quotas, distribute remainder by bit order. */
export function allocate(settings: Settings): Region[] {
  const { multipliers, lcm } = validate(settings);
  const sensors = sensorsFor(settings);
  const ids = selected(settings.mask);
  const strides = sensors.map(s => smartPad(s.payload + 2));
  const pages = strides.map((n, i) => ids.includes(i) ? Math.ceil(n / 4) : 0);
  const start = logStart('partitioned', sensors);
  const remaining = (settings.capacity - start) / 4 - pages.reduce((a, b) => a + b, 0);
  if (remaining < 0) throw new Error('Tag is too small to reserve one record per enabled sensor. Reduce payloads or choose a larger tag.');
  const weights = strides.map((n, i) => multipliers[i] ? n * (lcm / multipliers[i]) : 0);
  const total = weights.reduce((a, b) => a + b, 0);
  let left = remaining;
  for (const i of ids) { const add = Math.floor(remaining * weights[i] / total); pages[i] += add; left -= add; }
  for (let j = 0; left > 0; j++, left--) pages[ids[j % ids.length]]++;
  let address = start;
  return ids.map(i => { const start = address; address += pages[i] * 4; return { name: sensors[i].short, stream: i, start, end: address }; });
}
export function simulate(kind: Kind, settings: Settings): Simulation {
  const scheduleInfo = validate(settings);
  const sensors = sensorsFor(settings);
  const multi = kind === 'partitioned' || kind === 'shared';
  const mask = kind === 'baseline' ? settings.mask & ~2 : settings.mask;
  const start = logStart(kind, sensors);
  const regions = kind === 'partitioned' ? allocate(settings) : [{ name: kind === 'shared' ? 'Shared pool' : 'Combined log', stream: null, start, end: settings.capacity }];
  const result: Simulation = { kind, mask, start, records: [], regions, stopAt: null, lastAt: null, stopReason: '', base: multi ? scheduleInfo.base : settings.commonPeriod, lcm: multi ? scheduleInfo.lcm : 1, capacity: settings.capacity, sensors, exploratory: !isFirmwareLayout(settings) };
  if (!mask) { result.stopReason = 'TMP119 is not present in the baseline'; return result; }
  const pointers = regions.map(r => r.start);
  let time = 0;
  while (true) {
    if (multi && time > MAX_SECONDS) { result.stopAt = MAX_SECONDS; result.stopReason = '30-day session limit'; break; }
    const due = multi ? selected(mask).reduce((m, i) => m | (time % settings.periods[i] === 0 ? 1 << i : 0), 0) : mask;
    const writes = kind === 'partitioned'
      ? regions.map((r, i) => ({ region: i, stream: r.stream, bytes: due & (1 << r.stream!) ? recordBytes(kind, due, r.stream, sensors) : [] })).filter(w => w.bytes.length)
      : [{ region: 0, stream: null, bytes: recordBytes(kind, due, null, sensors) }];
    const full = writes.find(w => kind === 'baseline' ? pointers[w.region] + w.bytes.length >= regions[w.region].end : pointers[w.region] + w.bytes.length > regions[w.region].end);
    if (full) { result.stopAt = time; result.stopReason = kind === 'partitioned' ? `${regions[full.region].name} partition full` : kind === 'baseline' ? 'First rejected record (strict end bound)' : 'Next complete record does not fit'; break; }
    for (const w of writes) {
      result.records.push({ time, mask: due, address: pointers[w.region], bytes: w.bytes, stream: w.stream });
      pointers[w.region] += w.bytes.length;
    }
    result.lastAt = time;
    time = multi ? Math.min(...selected(mask).map(i => (Math.floor(time / settings.periods[i]) + 1) * settings.periods[i])) : time + settings.commonPeriod;
  }
  return result;
}
export type Segment = { start: number; end: number; name: string; type: 'meta' | 'pointer' };
export function metadata(kind: Kind, sensors: readonly Sensor[] = SENSORS): Segment[] {
  if (kind === 'baseline') return [
    { start: 0, end: 1, name: 'Sensor enable + oscillator', type: 'meta' },
    { start: 1, end: 5, name: 'Legacy timestamp / supply field', type: 'meta' },
    { start: 5, end: 6, name: 'BCD common period', type: 'meta' },
    { start: 6, end: 7, name: 'BCD startup delay', type: 'meta' },
    { start: 7, end: 9, name: 'Raw next-write pointer (LE16)', type: 'pointer' },
  ];
  const multi = kind !== 'r1';
  return [
    { start: 0, end: 8, name: multi ? 'DENSOR2! format guard' : 'DENSOR1! format guard', type: 'meta' },
    { start: 8, end: 10, name: multi ? 'Reserved zero' : 'Raw next-write pointer (LE16)', type: multi ? 'meta' : 'pointer' },
    { start: 10, end: 12, name: 'Reserved zero', type: 'meta' },
    { start: 12, end: 76, name: `64 B session header · ${multi ? 'v5' : 'v3'} · CRC16`, type: 'meta' },
    { start: 76, end: 92, name: 'Status / acknowledgement · CRC16', type: 'meta' },
    { start: 92, end: multi ? 132 : 124, name: `Pending request · ${multi ? 'DCP5 + allocation' : 'DCP3'} · CRC16`, type: 'meta' },
    { start: multi ? 132 : 124, end: multi ? 136 : 128, name: 'Request ID commit marker', type: 'meta' },
    ...(multi ? [{ start: 136, end: logStart(kind, sensors), name: kind === 'shared' ? '32 B pointer area · first A/B pair used' : `${sensors.length} A/B pointer pairs · address + generation + CRC8`, type: 'pointer' as const }] : []),
  ];
}
export function snapshot(sim: Simulation, time: number) {
  const memory: Byte[] = Array.from({ length: sim.capacity }, () => byte('free', 'Unused record space', null));
  for (const s of metadata(sim.kind, sim.sensors)) for (let p = s.start; p < s.end; p++) memory[p] = byte(s.type, `${s.name} · byte ${p - s.start}`, null);
  const records = sim.records.filter(r => r.time <= time);
  const counts = sim.sensors.map(() => 0);
  let payload = 0, padding = 0;
  for (const r of records) {
    r.bytes.forEach((b, i) => { memory[r.address + i] = { ...b, time: r.time }; if (b.type === 'pad') padding++; if (b.stream !== undefined) payload++; });
    for (const i of selected(r.stream === null ? sim.mask & r.mask : 1 << r.stream)) counts[i]++;
  }
  const used = records.reduce((n, r) => n + r.bytes.length, 0);
  const wakes = new Set(records.map(r => r.time)).size;
  const pointers = sim.regions.map(region => records.filter(r => r.address >= region.start && r.address < region.end).reduce((p, r) => Math.max(p, r.address + r.bytes.length), region.start));
  return { memory, records, counts, used, payload, padding, wakes, pointers, full: sim.stopAt !== null && time >= sim.stopAt };
}
export function formatTime(seconds: number | null) {
  if (seconds === null) return '—';
  if (seconds < 60) return `${seconds} s`;
  const d = Math.floor(seconds / 86400), h = Math.floor(seconds % 86400 / 3600), m = Math.floor(seconds % 3600 / 60), s = seconds % 60;
  return [d ? `${d}d` : '', h ? `${h}h` : '', m ? `${m}m` : '', s ? `${s}s` : ''].filter(Boolean).join(' ');
}

/** Count boundaries inside each sensor payload. XYZ is one six-byte payload. */
function payloadBoundaries(address: number, bytes: Byte[], sensors: readonly Sensor[]) {
  return sensors.map((_, stream) => {
    const offset = bytes.findIndex(b => b.stream === stream);
    if (offset < 0) return { crossings: 0, extra: 0, observations: 0 };
    const size = bytes.filter(b => b.stream === stream).length;
    const crossings = pageCrossings(address + offset, size);
    return { crossings, extra: crossings - Math.max(0, Math.ceil(size / 4) - 1), observations: 1 };
  });
}

/** Replay the SAME stored records without padding, preserving region starts,
 * field order, supply, CRC and sampling times. No extra records are admitted.
 * This isolates padding from different schedules, formats and capacity limits.
 */
export function packingMetrics(sim: Simulation, time: number) {
  const pointers = new Map(sim.regions.map(r => [r.stream, r.start]));
  const streams = sim.sensors.map((sensor, stream) => ({
    stream, name: sensor.short, observations: 0, payloadCrossings: 0,
    payloadExtraCrossings: 0, unpaddedPayloadCrossings: 0, payloadCrossingsSaved: 0,
    recordCrossings: 0, unpaddedRecordCrossings: 0, recordCrossingsSaved: 0,
  }));
  let recordCrossings = 0, unpaddedRecordCrossings = 0, paddingBytes = 0;
  for (const record of sim.records) {
    if (record.time > time) continue;
    const unpadded = record.bytes.filter(b => b.type !== 'pad');
    const address = pointers.get(record.stream)!;
    pointers.set(record.stream, address + unpadded.length);
    paddingBytes += record.bytes.length - unpadded.length;
    recordCrossings += pageCrossings(record.address, record.bytes.length);
    unpaddedRecordCrossings += pageCrossings(address, unpadded.length);
    const actualPayload = payloadBoundaries(record.address, record.bytes, sim.sensors);
    const unpaddedPayload = payloadBoundaries(address, unpadded, sim.sensors);
    streams.forEach((stream, i) => {
      stream.observations += actualPayload[i].observations;
      stream.payloadCrossings += actualPayload[i].crossings;
      stream.payloadExtraCrossings += actualPayload[i].extra;
      stream.unpaddedPayloadCrossings += unpaddedPayload[i].crossings;
      stream.payloadCrossingsSaved += unpaddedPayload[i].crossings - actualPayload[i].crossings;
      if (record.stream === i) {
        stream.recordCrossings += pageCrossings(record.address, record.bytes.length);
        stream.unpaddedRecordCrossings += pageCrossings(address, unpadded.length);
        stream.recordCrossingsSaved = stream.unpaddedRecordCrossings - stream.recordCrossings;
      }
    });
  }
  return {
    reference: 'Same stored records, same region starts and fields, with only padding removed',
    recordCrossings, unpaddedRecordCrossings,
    recordCrossingsSaved: unpaddedRecordCrossings - recordCrossings,
    payloadCrossings: streams.reduce((n, s) => n + s.payloadCrossings, 0),
    payloadExtraCrossings: streams.reduce((n, s) => n + s.payloadExtraCrossings, 0),
    unpaddedPayloadCrossings: streams.reduce((n, s) => n + s.unpaddedPayloadCrossings, 0),
    payloadCrossingsSaved: streams.reduce((n, s) => n + s.payloadCrossingsSaved, 0),
    paddingBytes, streams,
  };
}

/** Successful logging only. Excludes provisioning, status, closing/recovery writes
 * and baseline pointer re-writes after storage fills. Crossings are counted per
 * logical record/pointer write, NOT between separate records. Page touches count
 * repeated touches (they are not a count of unique physical pages).
 */
export function pageMetrics(sim: Simulation, time: number) {
  const packing = packingMetrics(sim, time);
  const records = sim.records.filter(r => r.time <= time);
  const recordPages = records.reduce((n, r) => n + pageTouches(r.address, r.bytes.length), 0);
  const recordCrossings = records.reduce((n, r) => n + pageCrossings(r.address, r.bytes.length), 0);
  const extraPages = records.reduce((n, r) => n + pageTouches(r.address, r.bytes.length) - Math.ceil(r.bytes.length / 4), 0);
  const crossingRecords = records.filter(r => pageCrossings(r.address, r.bytes.length) > 0).length;
  let pointerWrites = records.length;
  let checkpoints = 0;
  if (sim.kind === 'shared' || sim.kind === 'partitioned') {
    // mr_commit checks next tick even on an empty sampling slot.
    const lastSuccessfulTime = sim.stopAt === null || sim.stopReason === '30-day session limit'
      ? Math.min(time, MAX_SECONDS) : Math.min(time, sim.stopAt - sim.base);
    const committedTicks = Math.max(0, Math.floor(lastSuccessfulTime / sim.base) + 1);
    const interval = Math.min(sim.lcm, 64);
    const overlap = interval / gcd(interval, sim.lcm) * sim.lcm;
    checkpoints = Math.floor(committedTicks / interval) + Math.floor(committedTicks / sim.lcm) - Math.floor(committedTicks / overlap);
    pointerWrites = checkpoints * sim.regions.length;
  }
  const pointerCrossings = sim.kind === 'baseline' ? pointerWrites : 0;
  const pointerPages = pointerWrites * (sim.kind === 'baseline' ? 2 : 1);
  const recordWriteCommands = sim.kind === 'baseline' ? records.length : recordPages;
  return {
    payloadCrossings: packing.payloadCrossings,
    payloadExtraCrossings: packing.payloadExtraCrossings,
    packing,
    records: records.length, recordPages, recordCrossings, crossingRecords, extraPages,
    pointerWrites, pointerPages, pointerCrossings, checkpoints,
    totalCrossings: recordCrossings + pointerCrossings,
    avoidableCrossings: extraPages + pointerCrossings,
    totalPageTouches: recordPages + pointerPages,
    writeCommands: recordWriteCommands + pointerWrites,
    // densor_write splits new-format commands at 4-byte boundaries.
    crossingCommands: sim.kind === 'baseline' ? crossingRecords + pointerWrites : 0,
  };
}
