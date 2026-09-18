import { STREAMS, validateConfig } from './sampler.mjs';

const encoder = new TextEncoder();

// Lab interchange remains separate from the complete EEPROM image adapters.
export function encodePreviewStream(dataset, stream) {
  const stride = 16 + 4 * stream.columns.length;
  const bytes = new Uint8Array(32 + stride * stream.times.length);
  const view = new DataView(bytes.buffer);
  bytes.set(encoder.encode('DSMRLAB\0'));
  view.setUint16(8, 1, true);
  view.setUint8(10, STREAMS.findIndex(item => item.id === stream.id));
  view.setUint8(11, stream.columns.length);
  view.setUint32(12, stream.times.length, true);
  view.setFloat64(16, stream.period, true);
  view.setFloat64(24, stream.times[0], true);
  stream.indices.forEach((row, i) => {
    const offset = 32 + stride * i;
    view.setFloat64(offset, stream.times[i], true);
    view.setFloat64(offset + 8, dataset.columns.time_s[row], true);
    stream.columns.forEach((column, j) => view.setFloat32(offset + 16 + j * 4, dataset.columns[column][row], true));
  });
  return bytes;
}

export function buildPreviewBundle(dataset, streams, config) {
  const files = streams.map(stream => ({ name: `${stream.id}.preview.bin`, bytes: encodePreviewStream(dataset, stream) }));
  const manifest = {
    format: 'densor-sampling-lab-preview', version: 1, synthetic: true,
    firmware_compatible: false,
    compatibility: 'Lab interchange only. NOT an EEPROM image. Select an R1/R2/R3 EEPROM export for Android debug import.',
    source: { file: 'samples.csv', sha256: dataset.sourceHash, seed: dataset.metadata.seed, reference_rate_hz: dataset.metadata.reference_rate_hz },
    interval: { start_s: config.start, end_s_exclusive: config.end, phase_s: config.phase },
    sampling: 'Instantaneous point samples, newest reference row at or before scheduled time; no filtering or averaging.',
    time_origin: 'Seconds from start of source recording; phase is relative to interval start.',
    header: { bytes: 32, byte_order: 'little', fields: ['0: magic[8] DSMRLAB\\0', '8: uint16 version=1', '10: uint8 stream_id (accel=0,light=1,temp1=2,temp2=3)', '11: uint8 channel_count', '12: uint32 record_count', '16: float64 period_s', '24: float64 first_scheduled_time_s'] },
    record: { fields: ['float64 scheduled_time_s', 'float64 source_time_s', 'float32[channel_count] values'], byte_order: 'little' },
    streams: streams.map((stream, i) => ({ id: stream.id, file: files[i].name, columns: stream.columns, unit: stream.unit, period_s: stream.period, rate_hz: 1 / stream.period, count: stream.times.length, record_bytes: 16 + 4 * stream.columns.length, file_bytes: files[i].bytes.length })),
  };
  files.push({ name: 'manifest.json', bytes: encoder.encode(JSON.stringify(manifest, null, 2) + '\n') });
  return { files, manifest };
}

export function crc8(bytes, start = 0, end = bytes.length) {
  let crc = 0;
  for (let i = start; i < end; i++) {
    crc ^= bytes[i];
    for (let bit = 0; bit < 8; bit++) crc = ((crc << 1) ^ ((crc & 0x80) ? 0x07 : 0)) & 255;
  }
  return crc;
}

export function crc16(bytes, start = 0, end = bytes.length) {
  let crc = 0xffff;
  for (let i = start; i < end; i++) {
    crc ^= bytes[i] << 8;
    for (let bit = 0; bit < 8; bit++) crc = ((crc << 1) ^ ((crc & 0x8000) ? 0x1021 : 0)) & 0xffff;
  }
  return crc;
}

export const FIRMWARE_STREAMS = Object.freeze([
  { id: 'temp1', sensor: 'LIS2DW12 temperature', columns: ['temp_1_c'], payload: 2, stride: 4, scale: 256, offset: 25, conversion: 'Math.round((celsius - 25) * 256)' },
  { id: 'temp2', sensor: 'TMP119 temperature', columns: ['temp_2_c'], payload: 2, stride: 4, scale: 128, offset: 0, conversion: 'Math.round(celsius * 128)' },
  { id: 'light', sensor: 'Photodiode', columns: ['light_norm'], payload: 2, stride: 4, scale: 4095, offset: 0, conversion: 'Math.round(light_norm * 4095); light_norm in [0,1]' },
  { id: 'accel', sensor: 'Acceleration', columns: ['accel_x_g', 'accel_y_g', 'accel_z_g'], payload: 6, stride: 8, scale: 16384, offset: 0, conversion: 'Math.round(g * 16384) per axis' },
]);
export const REVISIONS = Object.freeze({
  r1: { label: 'R1', version: 3, storage: 1, scheduler: 0, logStart: 128 },
  // R2 = shared pool, R3 = partitions; a = FSM, b = RTC timer.
  r2a: { label: 'R2a', version: 5, storage: 2, scheduler: 1, logStart: 168 },
  r2b: { label: 'R2b', version: 5, storage: 2, scheduler: 2, logStart: 168 },
  r3a: { label: 'R3a', version: 5, storage: 1, scheduler: 1, logStart: 168 },
  r3b: { label: 'R3b', version: 5, storage: 1, scheduler: 2, logStart: 168 },
});
const MAX_SECONDS = 2592000;
const align4 = value => (value + 3) & ~3;
const requireValue = (condition, message) => { if (!condition) throw new Error(message); };
function gcd(a, b) { while (b) [a, b] = [b, a % b]; return a; }

// UI alignment is explicit and happens before sampling. Export encoders still
// reject unsupported periods and never silently round a requested schedule.
export function firmwareSnapStep(streams) {
  const periods = streams.filter(stream => stream.enabled).map(stream => Math.max(1, Math.round(stream.period)));
  return periods.length ? Math.min(3540, periods.reduce(gcd)) : 1;
}

export function snapFirmwarePeriods(config, { revision = 'r3b', step = firmwareSnapStep(config.streams), maximumPeriod, changedId } = {}) {
  requireValue(Object.hasOwn(REVISIONS, revision), 'Unknown firmware revision.');
  const maximum = Math.min(MAX_SECONDS, Math.floor(maximumPeriod));
  requireValue(Number.isSafeInteger(maximum) && maximum >= 1, 'Firmware snapping needs a sampling range of at least one second.');
  requireValue(Number.isSafeInteger(step) && step >= 1, 'Invalid firmware snapping step.');
  if (!config.streams.some(stream => stream.enabled)) return { config, step };
  requireValue(config.streams.every(stream => !stream.enabled || Number.isFinite(stream.period)), 'Cannot snap a nonfinite sampling period.');
  const withPeriods = convert => ({ ...config, streams: config.streams.map(stream => stream.enabled ? { ...stream, period: convert(stream.period) } : { ...stream }) });
  if (revision === 'r1') {
    const requested = config.streams.find(stream => stream.enabled && stream.id === changedId)?.period
      ?? config.streams.find(stream => stream.enabled).period;
    const allowed = Array.from({ length: Math.min(59, maximum) }, (_, i) => i + 1);
    for (let period = 60; period <= Math.min(3540, maximum); period += 60) allowed.push(period);
    const common = allowed.reduce((best, period) => Math.abs(period - requested) <= Math.abs(best - requested) ? period : best);
    const aligned = withPeriods(() => common);
    firmwareSchedule(aligned, revision);
    return { config: aligned, step: common };
  }
  // Start with the current GCD and coarsen only if the resulting schedule would
  // exceed firmware multiplier/LCM limits. All enabled sensors move together.
  for (let candidate = Math.min(step, maximum, 3540); candidate <= Math.min(maximum, 3540); candidate++) {
    const aligned = withPeriods(period => Math.max(1, Math.min(Math.floor(maximum / candidate), Math.round(period / candidate))) * candidate);
    try {
      firmwareSchedule(aligned, revision);
      return { config: aligned, step: candidate };
    } catch { /* Try a coarser common grid. */ }
  }
  throw new Error('No compatible GCD schedule fits this sampling range.');
}

export function firmwareSchedule(config, revision) {
  requireValue(Object.hasOwn(REVISIONS, revision), 'Unknown firmware revision.');
  const periods = FIRMWARE_STREAMS.map(stream => {
    const setting = config.streams.find(item => item.id === stream.id);
    requireValue(setting, `Missing settings for ${stream.id}.`);
    if (!setting.enabled) return 0;
    requireValue(Number.isSafeInteger(setting.period) && setting.period >= 1 && setting.period <= MAX_SECONDS,
      `${stream.sensor}: firmware requires whole-second periods from 1 to ${MAX_SECONDS} s. Use the Firmware comparison preset or keep Lab preview; rates are never rounded.`);
    return setting.period;
  });
  const enabled = periods.filter(Boolean);
  requireValue(enabled.length > 0, 'Enable at least one sensor.');
  const mask = periods.reduce((mask, period, i) => mask | (period ? 1 << i : 0), 0);
  if (revision === 'r1') {
    const period = enabled[0];
    requireValue(enabled.every(value => value === period), 'R1 needs one common period for all enabled sensors. Choose the Original preset or use R2/R3 for mixed rates.');
    requireValue(period <= 59 || (period <= 3540 && period % 60 === 0), 'R1 periods must be 1–59 seconds or a whole minute up to 3540 seconds.');
    return { mask, periods, base: period, multipliers: periods.map(value => value ? 1 : 0), lcm: 1 };
  }
  const common = enabled.reduce(gcd);
  let base = Math.min(common, 3540);
  while (common % base !== 0) base--;
  const multipliers = periods.map(period => period / base);
  let lcm = 1;
  for (const multiplier of multipliers.filter(Boolean)) {
    requireValue(multiplier <= 65535, 'Firmware multiplier exceeds 65535. Choose a simpler schedule.');
    lcm = lcm / gcd(lcm, multiplier) * multiplier;
    requireValue(lcm <= 65535, 'Firmware schedule LCM exceeds 65535. Choose periods with a smaller common cycle.');
  }
  return { mask, periods, base, multipliers, lcm };
}

export function allocatePartitions(capacity, schedule) {
  const { multipliers, lcm } = schedule;
  const pages = multipliers.map((m, i) => m ? FIRMWARE_STREAMS[i].stride / 4 : 0);
  const remaining = (capacity - 168) / 4 - pages.reduce((a, b) => a + b, 0);
  const weights = multipliers.map((m, i) => m ? FIRMWARE_STREAMS[i].stride * (lcm / m) : 0);
  const total = weights.reduce((a, b) => a + b, 0);
  let left = remaining;
  weights.forEach((weight, i) => { const quota = Math.floor(remaining * weight / total); pages[i] += quota; left -= quota; });
  for (let i = 0; left > 0; i = (i + 1) % 4) if (weights[i]) { pages[i]++; left--; }
  let at = 168;
  const starts = [0, 0, 0, 0], ends = [0, 0, 0, 0];
  pages.forEach((page, i) => { if (page) { starts[i] = at; at += page * 4; ends[i] = at; } });
  return { pages, starts, ends };
}

function payloadFor(dataset, sampled, index, definition, expectedTime) {
  requireValue(index < sampled.times.length && index < sampled.indices.length, `Missing due value for ${definition.id} at ${expectedTime} s.`);
  const tolerance = 8 * Number.EPSILON * Math.max(1, Math.abs(expectedTime));
  requireValue(Math.abs(sampled.times[index] - expectedTime) <= tolerance, `Unexpected sample time for ${definition.id}; gaps or independent phases cannot be encoded.`);
  const row = sampled.indices[index];
  const sourceTime = dataset.columns.time_s?.[row];
  requireValue(Number.isInteger(row) && row >= 0 && row < dataset.count && Number.isFinite(sourceTime) && sourceTime <= expectedTime + tolerance &&
    (row + 1 === dataset.count || dataset.columns.time_s[row + 1] > expectedTime - tolerance), `Invalid source row for ${definition.id} at ${expectedTime} s.`);
  return definition.columns.map(column => {
    const value = dataset.columns[column]?.[row];
    requireValue(Number.isFinite(value), `${column}: cannot encode a missing or nonfinite value.`);
    const light = definition.id === 'light';
    if (light) requireValue(value >= 0 && value <= 1, 'light_norm must be within [0,1]; no saturation is applied.');
    const raw = Math.round((value - definition.offset) * definition.scale);
    requireValue(raw >= (light ? 0 : -32768) && raw <= (light ? 4095 : 32767), `${column}: raw value ${raw} is out of range; no saturation or wraparound is applied.`);
    return raw;
  });
}

/** One complete offline, stopped EEPROM recording plus synthetic provenance. */
export function buildFirmwareBundle(dataset, streams, config, { revision, capacity = 8192 } = {}) {
  validateConfig(config, dataset.metadata);
  requireValue([512, 2048, 8192].includes(capacity), 'EEPROM capacity must be 512, 2048 or 8192 bytes.');
  const schedule = firmwareSchedule(config, revision);
  const target = REVISIONS[revision], r1 = revision === 'r1';
  const sourceOrigin = config.start + config.phase;
  const ordered = FIRMWARE_STREAMS.map((definition, i) => {
    const matching = streams.filter(stream => stream.id === definition.id);
    requireValue(matching.length === (schedule.periods[i] ? 1 : 0), `Missing, duplicated or disabled sampled stream: ${definition.id}.`);
    if (!schedule.periods[i]) return null;
    const sampled = matching[0];
    requireValue(sampled.period === schedule.periods[i] && sampled.columns.join(',') === definition.columns.join(','), `Sampled ${definition.id} does not match the export settings.`);
    return sampled;
  });
  requireValue(streams.length === ordered.filter(Boolean).length, 'Unknown sampled stream.');
  const allocation = !r1 && target.storage === 1 ? allocatePartitions(capacity, schedule)
    : { pages: [(capacity - target.logStart) / 4, 0, 0, 0], starts: [target.logStart, 0, 0, 0], ends: [capacity, 0, 0, 0] };
  const pointers = [...allocation.starts], counts = [0, 0, 0, 0];
  const image = new Uint8Array(capacity).fill(0xff);
  image.fill(0, 0, target.logStart);
  const view = new DataView(image.buffer);
  const combinedStride = align4(1 + FIRMWARE_STREAMS.reduce((sum, stream, i) => sum + (schedule.periods[i] ? stream.payload : 0), 0));
  let elapsed = 0, wakeCount = 0, lastElapsed = null, stopReason, omitted;
  for (;;) {
    const dueMask = schedule.periods.reduce((mask, period, i) => mask | (period && elapsed % period === 0 ? 1 << i : 0), 0);
    const sourceTime = sourceOrigin + elapsed;
    omitted = { elapsed_s: elapsed, source_time_s: sourceTime, due_mask: dueMask };
    if (sourceTime >= config.end) { stopReason = 'window-end'; break; }
    const payloadSize = FIRMWARE_STREAMS.reduce((sum, stream, i) => sum + (dueMask & (1 << i) ? stream.payload : 0), 0);
    const recordSize = r1 ? combinedStride : align4(3 + payloadSize);
    const fits = !r1 && target.storage === 1
      ? FIRMWARE_STREAMS.every((stream, i) => !(dueMask & (1 << i)) || pointers[i] + stream.stride <= allocation.ends[i])
      : pointers[0] + recordSize <= capacity;
    if (!fits) { stopReason = 'capacity'; break; }
    requireValue(elapsed <= MAX_SECONDS, 'Firmware session elapsed time exceeds 30 days. Shorten the export interval.');
    // Preflight the whole wake, then quantize every due value before any write.
    const payloads = FIRMWARE_STREAMS.map((stream, i) => dueMask & (1 << i) ? payloadFor(dataset, ordered[i], counts[i], stream, sourceTime) : null);
    const supply = dueMask & 7 ? 8 : 0xff;
    const writePayload = (at, values) => { for (const raw of values) { view.setUint16(at, raw & 0xffff, true); at += 2; } return at; };
    if (!r1 && target.storage === 1) {
      payloads.forEach((payload, i) => {
        if (!payload) return;
        const start = pointers[i];
        let at = writePayload(start, payload);
        image[at++] = supply;
        image[at] = crc8(image, start, at);
        pointers[i] += FIRMWARE_STREAMS[i].stride;
      });
    } else {
      const start = pointers[0];
      image.fill(0, start, start + recordSize);
      let at = start;
      if (!r1) image[at++] = dueMask;
      image[at++] = supply;
      payloads.forEach(payload => { if (payload) at = writePayload(at, payload); });
      if (!r1) image[at] = crc8(image, start, at);
      pointers[0] += recordSize;
    }
    payloads.forEach((payload, i) => { if (payload) counts[i]++; });
    wakeCount++; lastElapsed = elapsed;
    // Advance directly to the next nonempty tick; empty ticks have no record.
    elapsed = Math.min(...schedule.periods.map((period, i) => period ? counts[i] * period : Infinity));
  }
  image.set(encoder.encode(r1 ? 'DENSOR1!' : 'DENSOR2!'), 0);
  const H = 12, S = 76;
  image.set(encoder.encode(r1 ? 'DNR1' : 'DNR2'), H);
  image[H + 4] = target.version; image[H + 5] = target.storage; image[H + 6] = 64; image[H + 7] = target.scheduler;
  view.setUint16(H + 8, capacity, true); view.setUint16(H + 10, target.logStart, true);
  image[H + 12] = schedule.mask; image[H + 15] = 15; image[H + 17] = 15;
  view.setUint32(H + 20, 1, true);
  view.setUint32(H + 24, r1 ? 1 : 100000, true);
  if (r1) {
    view.setUint16(8, pointers[0], true); image[H + 13] = combinedStride;
    view.setUint32(H + 28, schedule.base, true);
  } else {
    image[H + 19] = schedule.base === 1 ? 0 : 1;
    view.setUint16(H + 28, schedule.base, true); view.setUint16(H + 30, schedule.lcm, true);
    for (let i = 0; i < 4; i++) {
      view.setUint16(H + 32 + 2 * i, schedule.multipliers[i], true);
      view.setUint16(H + 40 + 2 * i, allocation.starts[i], true);
      view.setUint16(H + 48 + 2 * i, allocation.ends[i], true);
      if (allocation.ends[i]) for (const slot of [136 + 8 * i, 140 + 8 * i]) {
        view.setUint16(slot, pointers[i], true); image[slot + 3] = crc8(image, slot, slot + 3);
      }
    }
    view.setUint16(H + 56, Math.min(schedule.lcm, 64), true);
  }
  view.setUint16(S, 1, true); image[S + 4] = 15;
  view.setUint32(S + 6, 1, true); view.setUint32(S + 10, 1, true);
  view.setUint16(H + 62, crc16(image, H, H + 62), true);
  view.setUint16(S + 14, crc16(image, S, S + 14), true);
  const name = `synthetic-${revision}-v${target.version}-${capacity}`;
  const manifest = {
    synthetic: true, format: 'densor-user-eeprom', revision: target.label, protocol_version: target.version,
    firmware_compatible: true, decoder_source_commit: '48a8f137aa9dba162e88bdad67b549bfde232d40',
    file: `${name}.bin`, capacity_bytes: capacity, storage: r1 ? 'combined' : target.storage === 1 ? 'partitioned' : 'shared',
    scheduler: r1 ? 'common-period' : target.scheduler === 1 ? 'FSM' : 'RTC timer',
    source: { file: 'samples.csv', sha256: dataset.sourceHash ?? dataset.metadata.sha256?.['samples.csv'], seed: dataset.metadata.seed, reference_rate_hz: dataset.metadata.reference_rate_hz },
    interval: { start_s: config.start, end_s_exclusive: config.end, phase_s: config.phase, source_origin_s: sourceOrigin },
    sampling: 'Newest source row at or before source_origin_s + elapsed_s; no filtering, independent phases, resampling or missing wakes.',
    channel_mapping_convention: 'Synthetic channels are assigned to firmware identities for export, not physical transfer-function models. Light is synthetic ADC scaling, not lux.',
    streams: FIRMWARE_STREAMS.map((stream, i) => ({ id: stream.id, sensor: stream.sensor, firmware_index: i, bit: 1 << i, columns: stream.columns,
      enabled: Boolean(schedule.periods[i]), requested_period_s: schedule.periods[i] || null, encoded_period_s: schedule.periods[i] || null,
      raw_conversion: stream.conversion, requested_count: ordered[i]?.times.length ?? 0, exported_count: counts[i], clipped_values: 0 })),
    rounding: 'Math.round: nearest integer, ties toward positive infinity', clipping: { policy: 'reject; no saturation or wraparound', total_clipped_values: 0 },
    supply: { synthetic_assumption: true, volts: 2.6, code: 8, rule: '(dueMask & 0x07) != 0 ? 8 : 255; one common supply byte per wake' },
    schedule: { base_period_s: schedule.base, multipliers: r1 ? null : schedule.multipliers, lcm: r1 ? null : schedule.lcm, startup_delay_minutes: 0,
      epoch_seconds_since_2000: r1 ? null : 100000, epoch_is_synthetic: !r1, session_id: 1, acknowledged_request_id: 1 },
    allocation: { log_start: target.logStart, pages: allocation.pages, starts: allocation.starts, ends: allocation.ends, pointers,
      used_record_bytes: pointers.reduce((sum, pointer, i) => sum + pointer - allocation.starts[i], 0), erased_tail_byte: 255 },
    record_counts: counts, union_wake_count: wakeCount, last_elapsed_s: lastElapsed, state: 'STOPPED', error: 0,
    stop_reason: stopReason, first_omitted_wake: omitted,
    import_instructions: 'Extract the .bin from this ZIP and open it in the Android debug build. This is a complete offline user-EEPROM recording, not a flashable or resumable firmware image.',
  };
  requireValue(typeof manifest.source.sha256 === 'string' && /^[a-f0-9]{64}$/i.test(manifest.source.sha256), 'A source SHA-256 is required for synthetic provenance.');
  return { files: [{ name: `${name}.bin`, bytes: image }, { name: `${name}.json`, bytes: encoder.encode(JSON.stringify(manifest, null, 2) + '\n') }], manifest };
}

const crcTable = Uint32Array.from({ length: 256 }, (_, i) => {
  let value = i;
  for (let bit = 0; bit < 8; bit++) value = (value >>> 1) ^ (value & 1 ? 0xedb88320 : 0);
  return value >>> 0;
});
function crc32(bytes) {
  let crc = 0xffffffff;
  for (const byte of bytes) crc = (crc >>> 8) ^ crcTable[(crc ^ byte) & 255];
  return (crc ^ 0xffffffff) >>> 0;
}

// Small, uncompressed ZIP writer: deterministic output, no runtime dependencies.
export function makeZip(files) {
  const locals = [], central = [];
  let offset = 0;
  for (const file of files) {
    const name = encoder.encode(file.name);
    const crc = crc32(file.bytes);
    const local = new Uint8Array(30 + name.length);
    const lv = new DataView(local.buffer);
    lv.setUint32(0, 0x04034b50, true); lv.setUint16(4, 20, true);
    lv.setUint16(12, 33, true); // 1980-01-01
    lv.setUint32(14, crc, true); lv.setUint32(18, file.bytes.length, true); lv.setUint32(22, file.bytes.length, true);
    lv.setUint16(26, name.length, true); local.set(name, 30);
    const entry = new Uint8Array(46 + name.length);
    const cv = new DataView(entry.buffer);
    cv.setUint32(0, 0x02014b50, true); cv.setUint16(4, 20, true); cv.setUint16(6, 20, true);
    cv.setUint16(14, 33, true); cv.setUint32(16, crc, true);
    cv.setUint32(20, file.bytes.length, true); cv.setUint32(24, file.bytes.length, true);
    cv.setUint16(28, name.length, true); cv.setUint32(42, offset, true); entry.set(name, 46);
    locals.push(local, file.bytes); central.push(entry);
    offset += local.length + file.bytes.length;
  }
  const centralSize = central.reduce((sum, entry) => sum + entry.length, 0);
  const end = new Uint8Array(22), ev = new DataView(end.buffer);
  ev.setUint32(0, 0x06054b50, true); ev.setUint16(8, files.length, true); ev.setUint16(10, files.length, true);
  ev.setUint32(12, centralSize, true); ev.setUint32(16, offset, true);
  const result = new Uint8Array(offset + centralSize + end.length);
  let cursor = 0;
  for (const part of [...locals, ...central, end]) { result.set(part, cursor); cursor += part.length; }
  return result;
}

export const EXPORT_ADAPTERS = Object.freeze([
  { id: 'preview-v1', name: 'Lab preview v1', firmwareCompatible: false, encode: buildPreviewBundle },
  ...Object.entries(REVISIONS).map(([revision, target]) => ({ id: revision,
    name: `${target.label} · ${revision === 'r1' ? 'common-period combined' : `${target.storage === 1 ? 'partitions' : 'shared pool'} · ${target.scheduler === 1 ? 'FSM' : 'RTC timer'}`} · v${target.version}`,
    firmwareCompatible: true, encode: (dataset, streams, config, options) => buildFirmwareBundle(dataset, streams, config, { ...options, revision }),
  })),
]);
