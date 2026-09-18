export const STREAMS = Object.freeze([
  { id: 'accel', name: 'Acceleration', columns: ['accel_x_g', 'accel_y_g', 'accel_z_g'], unit: 'g', color: '#326ddd' },
  { id: 'light', name: 'Light', columns: ['light_norm'], unit: 'normalized', color: '#af741b' },
  { id: 'temp1', name: 'Temperature 1', columns: ['temp_1_c'], unit: '°C', color: '#148473' },
  { id: 'temp2', name: 'Temperature 2', columns: ['temp_2_c'], unit: '°C', color: '#a456ab' },
]);
export const COLUMNS = ['time_s', ...STREAMS.flatMap(stream => stream.columns)];
export const PRESETS = Object.freeze({
  multirate: [0.04, 1, 10, 10],
  original: [120, 120, 120, 120],
  firmware: [10, 30, 120, 120],
});

export function parseDataset(csv, metadata) {
  const lines = csv.trim().split(/\r?\n/);
  if (lines.shift() !== COLUMNS.join(',')) throw new Error('Unexpected dataset columns. Use the synthetic_multirate samples.csv.');
  const count = lines.length;
  const rate = metadata.reference_rate_hz;
  if (!Number.isFinite(rate) || rate <= 0 || !count || count !== metadata.rows ||
      !Number.isFinite(metadata.duration_s) || Math.abs(count / rate - metadata.duration_s) > 1e-7) {
    throw new Error('Dataset duration, rate or row count does not match metadata.');
  }
  const columns = Object.fromEntries(COLUMNS.map(key => [key, new Float64Array(count)]));
  lines.forEach((line, i) => {
    const cells = line.split(',');
    if (cells.length !== COLUMNS.length || cells.some(cell => !cell.trim())) throw new Error(`Missing value in dataset row ${i + 2}.`);
    const values = cells.map(Number);
    if (values.some(value => !Number.isFinite(value)) || Math.abs(values[0] - i / rate) > 1e-8) {
      throw new Error(`Invalid value or time grid in dataset row ${i + 2}.`);
    }
    COLUMNS.forEach((key, j) => { columns[key][i] = values[j]; });
  });
  return { columns, metadata, count };
}

export function parseEvents(csv) {
  const lines = csv.trim().split(/\r?\n/);
  const headers = lines.shift().split(',');
  return lines.map(line => {
    const fields = line.split(',');
    const event = Object.fromEntries(headers.map((key, i) => [key, fields[i]]));
    return { id: event.event_id, kind: event.kind, start: Number(event.start_s), end: Number(event.end_s) };
  }).filter(event => Number.isFinite(event.start) && Number.isFinite(event.end) && event.end > event.start);
}

export function validateConfig(config, metadata) {
  const { start, end, phase } = config;
  if (![start, end, phase].every(Number.isFinite) || start < 0 || end > metadata.duration_s || start >= end) {
    throw new Error(`Choose an export interval within 0–${metadata.duration_s} s, with start before end.`);
  }
  if (phase < 0 || phase >= end - start || start + phase >= end) throw new Error('Sampling phase must be nonnegative and shorter than the export interval.');
  if (!config.streams.some(stream => stream.enabled)) throw new Error('Enable at least one sensor.');
  for (const definition of STREAMS) {
    const stream = config.streams.find(item => item.id === definition.id);
    if (!stream) throw new Error(`Missing settings for ${definition.name}.`);
    if (stream.enabled && (!Number.isFinite(stream.period) || stream.period < 1 / metadata.reference_rate_hz)) {
      throw new Error(`${definition.name}: use a positive rate no higher than ${metadata.reference_rate_hz} Hz.`);
    }
  }
}

// Only correct rounding within a few floating-point ULPs, never to a future row.
function snappedInteger(value) {
  const nearest = Math.round(value);
  return Math.abs(value - nearest) <= 8 * Number.EPSILON * Math.max(1, Math.abs(value)) ? nearest : value;
}

export function sampleDataset(dataset, config) {
  validateConfig(config, dataset.metadata);
  const first = config.start + config.phase;
  return STREAMS.filter(definition => config.streams.find(stream => stream.id === definition.id).enabled).map(definition => {
    const { period } = config.streams.find(stream => stream.id === definition.id);
    // Interval is [start, end): do not include a sample exactly at end.
    const count = Math.ceil(snappedInteger((config.end - first) / period));
    const times = new Float64Array(count);
    const indices = new Uint32Array(count);
    for (let i = 0; i < count; i++) {
      const time = first + i * period;
      times[i] = time;
      indices[i] = Math.min(dataset.count - 1, Math.floor(snappedInteger(time * dataset.metadata.reference_rate_hz)));
    }
    return { ...definition, period, times, indices };
  });
}

export function lowerBound(values, target) {
  let low = 0, high = values.length;
  while (low < high) {
    const mid = (low + high) >>> 1;
    if (values[mid] < target) low = mid + 1;
    else high = mid;
  }
  return low;
}
