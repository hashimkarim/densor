import { STREAMS, PRESETS, parseDataset, parseEvents, sampleDataset, lowerBound } from './sampler.mjs';
import { EXPORT_ADAPTERS, makeZip, firmwareSnapStep, snapFirmwarePeriods } from './exporters.mjs';

const $ = id => document.getElementById(id);
const number = value => value.toLocaleString(undefined, { maximumFractionDigits: 4 });
const rateNumber = value => value.toLocaleString(undefined, { maximumSignificantDigits: 6 });
let dataset, events = [], sampled = [], config, valid = false, unit = 'hz';
let firmwareBundle = null;
let rateAdjustment = null;
const chartElements = [];
$('export-format').replaceChildren(...EXPORT_ADAPTERS.map(adapter => new Option(adapter.name, adapter.id)));
// Keep exact preset periods independently of the slider's finite positions.
const periods = new Map(STREAMS.map((stream, i) => [stream.id, PRESETS.multirate[i]]));

for (const stream of STREAMS) {
  const control = document.createElement('div');
  control.className = 'sensor-control';
  control.style.setProperty('--sensor-color', stream.color);
  control.innerHTML = `<label class="sensor-label" style="color:${stream.color}"><input id="${stream.id}-enabled" type="checkbox" checked>${stream.name}</label><output class="rate-value" id="${stream.id}-value" for="${stream.id}-rate"></output><input id="${stream.id}-rate" aria-label="${stream.name} sampling rate in Hz" aria-describedby="${stream.id}-hint snap-hint" type="range" min="0" max="1000" step="1" value="500"><span class="rate-hint" id="${stream.id}-hint"></span>`;
  $('sensor-controls').append(control);
  for (const column of stream.columns) {
    const chart = document.createElement('div');
    chart.className = 'chart';
    const name = stream.id === 'accel' ? `Acceleration ${column.split('_')[1].toUpperCase()}` : stream.name;
    chart.innerHTML = `<div class="chart-heading"><strong style="color:${stream.color}">${name} <span> / ${stream.unit}</span></strong><span class="chart-count">Loading…</span></div><canvas role="img" aria-label="${name}: reference signal and acquired samples over time"></canvas>`;
    $('charts').append(chart);
    chartElements.push({ element: chart, canvas: chart.querySelector('canvas'), count: chart.querySelector('.chart-count'), stream, column });
  }
}

function rateBounds() {
  const minimumPeriod = 1 / dataset.metadata.reference_rate_hz;
  const maximumPeriod = dataset.metadata.duration_s;
  return unit === 'hz' ? [1 / maximumPeriod, 1 / minimumPeriod] : [minimumPeriod, maximumPeriod];
}

function currentSnapStep() {
  if (!$('snap-gcd').checked) return 0;
  return firmwareSnapStep(readConfig().streams);
}

function beginRateAdjustment(input) {
  if (!rateAdjustment || rateAdjustment.id !== input.id) rateAdjustment = { id: input.id, step: currentSnapStep() };
  return rateAdjustment.step;
}

function syncSnapHint() {
  const step = rateAdjustment?.step ?? currentSnapStep();
  $('snap-hint').textContent = !$('snap-gcd').checked
    ? 'Free adjustment. Firmware exports still require compatible periods.'
    : $('export-format').value === 'r1'
      ? 'Moving one slider sets all enabled sensors to the same valid R1 period.'
      : `${rateNumber(step)} s steps from the GCD. Moving one slider aligns all enabled periods to whole seconds. Turn off for fractional rates.`;
}

function alignSamplingPeriods(changedId) {
  if (!$('snap-gcd').checked) return;
  const revision = $('export-format').value === 'preview-v1' ? 'r3b' : $('export-format').value;
  const result = snapFirmwarePeriods(readConfig(), {
    revision, step: rateAdjustment?.step || currentSnapStep(),
    maximumPeriod: dataset.metadata.duration_s, changedId,
  });
  result.config.streams.forEach(stream => periods.set(stream.id, stream.period));
  if (rateAdjustment) rateAdjustment.step = result.step;
}

function syncRateSliders() {
  const [min, max] = rateBounds();
  for (const stream of STREAMS) {
    const input = $(`${stream.id}-rate`);
    const value = unit === 'hz' ? 1 / periods.get(stream.id) : periods.get(stream.id);
    const label = `${rateNumber(value)} ${unit === 'hz' ? 'Hz' : 's'}`;
    input.value = Math.round(Math.log(value / min) / Math.log(max / min) * 1000);
    input.setAttribute('aria-label', `${stream.name} sampling ${unit === 'hz' ? 'rate in Hz' : 'period in seconds'}`);
    input.setAttribute('aria-valuetext', label);
    $(`${stream.id}-value`).textContent = label;
  }
}

function syncTimeSliders() {
  // Keep a nonempty interval and a first observation before its exclusive end.
  $('start').max = Math.max(0, $('end').valueAsNumber - 0.01).toFixed(2);
  $('end').min = ($('start').valueAsNumber + 0.01).toFixed(2);
  $('phase').max = Math.max(0, $('end').valueAsNumber - $('start').valueAsNumber - 0.001).toFixed(3);
  $('phase').value = Math.min($('phase').valueAsNumber, Number($('phase').max));
  for (const id of ['start', 'end', 'phase']) {
    const label = `${number($(id).valueAsNumber)} s`;
    $(`${id}-value`).textContent = label;
    $(id).setAttribute('aria-valuetext', label);
  }
}

function readConfig() {
  return {
    start: $('start').valueAsNumber, end: $('end').valueAsNumber, phase: $('phase').valueAsNumber,
    streams: STREAMS.map(stream => ({ id: stream.id, enabled: $(`${stream.id}-enabled`).checked, period: periods.get(stream.id) })),
  };
}

function updateSampling() {
  if (!dataset) return;
  syncTimeSliders();
  syncRateSliders();
  syncSnapHint();
  config = readConfig();
  for (const stream of config.streams) {
    const input = $(`${stream.id}-rate`);
    input.disabled = !stream.enabled;
    $(`${stream.id}-hint`).textContent = !stream.enabled ? 'Disabled' : `Every ${rateNumber(stream.period)} s · ${rateNumber(1 / stream.period)} Hz`;
  }
  try {
    sampled = sampleDataset(dataset, config);
    valid = true;
    $('config-error').hidden = true;
    const observations = sampled.reduce((total, stream) => total + stream.times.length, 0);
    const values = sampled.reduce((total, stream) => total + stream.times.length * stream.columns.length, 0);
    const selectedRows = new Set(sampled.flatMap(stream => Array.from(stream.indices)));
    $('sample-stat').textContent = number(observations);
    $('value-stat').textContent = number(values);
    $('coverage-stat').textContent = `${number(selectedRows.size)} / ${number(dataset.count)}`;
    $('coverage-stat').style.fontSize = '16px';
  } catch (error) {
    valid = false;
    $('config-error').textContent = error.message;
    $('config-error').hidden = false;
    $('sample-stat').textContent = '—'; $('value-stat').textContent = '—'; $('coverage-stat').textContent = '—';
  }
  document.querySelector('.results').classList.toggle('invalid', !valid);
  updateExport();
  drawCharts();
}

function updateExport() {
  const adapter = EXPORT_ADAPTERS.find(item => item.id === $('export-format').value);
  const firmware = adapter.firmwareCompatible;
  firmwareBundle = null;
  $('capacity-field').hidden = !firmware;
  $('export-error').hidden = true;
  $('export-badge').textContent = firmware ? 'EEPROM image' : 'Lab preview';
  $('export-description').textContent = firmware
    ? 'One complete EEPROM .bin for all enabled sensors, plus a JSON sidecar with source settings, conversions and exported counts.'
    : 'One .preview.bin per enabled sensor, plus a manifest of rates, units and source timestamps.';
  $('export-note-title').textContent = firmware ? 'Import the extracted .bin in the Android debug build.' : 'Lab preview files are for signal analysis.';
  $('export-note').textContent = firmware
    ? 'Synthetic recording; supply is assumed to be 2.6 V when required. Export stops before a wake that cannot fit. This is an EEPROM data dump, not flashable firmware.'
    : 'Lab preview files cannot be imported as EEPROM images. Select R1/R2/R3 for Android debug import. Fractional sampling periods remain available here.';
  $('download').textContent = firmware ? 'Download EEPROM bundle ↓' : 'Download preview bundle ↓';
  $('download').disabled = !valid;
  if (!valid) { $('export-summary').textContent = 'Choose valid sampling settings to export.'; return; }
  if (!firmware) {
    const byteCount = sampled.reduce((sum, stream) => sum + 32 + stream.times.length * (16 + 4 * stream.columns.length), 0);
    $('export-summary').textContent = `${sampled.length} binary files + manifest · ${number(byteCount)} binary bytes · [${number(config.start)}, ${number(config.end)}) s. Preview size is not firmware storage usage.`;
    return;
  }
  try {
    firmwareBundle = adapter.encode(dataset, sampled, config, { capacity: Number($('export-capacity').value) });
    const info = firmwareBundle.manifest;
    const counts = info.streams.filter(stream => stream.enabled).map(stream => `${stream.id}: ${number(stream.exported_count)}`).join(' · ');
    const stop = info.stop_reason === 'capacity'
      ? `Capacity reached before source ${number(info.first_omitted_wake.source_time_s)} s (elapsed ${number(info.first_omitted_wake.elapsed_s)} s).`
      : 'Entire requested window fits.';
    $('export-summary').textContent = `${info.revision} · ${number(info.capacity_bytes)} bytes · ${number(info.union_wake_count)} recorded wakes · ${counts}. ${stop} Plots show the selected source window; this export contains its stored prefix.`;
  } catch (error) {
    $('download').disabled = true;
    $('export-error').textContent = error.message;
    $('export-error').hidden = false;
    $('export-summary').textContent = 'This configuration cannot be encoded in the selected firmware format. Lab preview is still available.';
  }
}

function viewport() {
  const duration = dataset.metadata.duration_s;
  const width = Math.min(duration, Number($('view-window').value));
  const start = Math.max(0, Math.min(duration - width, $('view-start').valueAsNumber || 0));
  $('view-start').max = duration - width;
  $('view-start').value = start;
  const selectedStart = $('view-start').valueAsNumber;
  $('view-start-value').textContent = `${number(selectedStart)} s`;
  $('view-start').setAttribute('aria-valuetext', `${number(selectedStart)} seconds`);
  return { start: selectedStart, end: selectedStart + width };
}

function drawCharts() {
  const resulting = document.querySelector('input[name="plot-mode"]:checked').value === 'resulting';
  $('plot-title').textContent = resulting ? 'Resulting signal' : 'Reference & samples';
  $('plot-description').textContent = resulting ? 'The acquired values for each channel, connected in sampling order.' : 'Zoom into an event to see what falls between observations.';
  $('comparison-legend').hidden = resulting;
  $('resulting-legend').hidden = !resulting;
  if (!dataset) return;
  const { start, end } = viewport();
  const times = dataset.columns.time_s;
  const first = lowerBound(times, start), last = lowerBound(times, end);
  for (const { element, canvas, count, stream, column } of chartElements) {
    const values = dataset.columns[column];
    const selection = valid ? sampled.find(item => item.id === stream.id) : null;
    const left = selection ? lowerBound(selection.times, start) : 0;
    const right = selection ? lowerBound(selection.times, end) : 0;
    // Include adjacent samples so lines are continuous when the window cuts
    // through a segment. Clip the drawing; never extend beyond real samples.
    const lineFirst = Math.max(0, left - 1);
    const lineEnd = selection ? Math.min(selection.times.length, right + 1) : 0;
    const hasCurve = selection && lineEnd - lineFirst >= 2 && selection.times[lineFirst] < end && selection.times[lineEnd - 1] > start;
    element.classList.toggle('disabled-chart', !selection);
    count.textContent = !valid ? 'Invalid settings' : !selection ? 'Sensor disabled' : `${number(right - left)} samples in view · ${rateNumber(1 / selection.period)} Hz`;
    if (!selection) canvas.setAttribute('aria-label', `${column}: ${valid ? 'sensor disabled' : 'invalid sampling settings'}.${resulting ? ' No resulting curve.' : ' Reference signal only.'}`);
    const width = Math.max(200, canvas.clientWidth), height = canvas.clientHeight;
    const dpr = window.devicePixelRatio || 1;
    canvas.width = Math.round(width * dpr); canvas.height = Math.round(height * dpr);
    const ctx = canvas.getContext('2d');
    ctx.scale(dpr, dpr);
    const margin = { left: 52, right: 12, top: 13, bottom: 22 };
    const plotWidth = width - margin.left - margin.right, plotHeight = height - margin.top - margin.bottom;
    let min = Infinity, max = -Infinity;
    if (!resulting) for (let i = first; i < last; i++) { min = Math.min(min, values[i]); max = Math.max(max, values[i]); }
    if (selection) for (let i = resulting && hasCurve ? lineFirst : left; i < (resulting && hasCurve ? lineEnd : right); i++) { min = Math.min(min, values[selection.indices[i]]); max = Math.max(max, values[selection.indices[i]]); }
    if (!resulting && $('held').checked && selection && left > 0) { min = Math.min(min, values[selection.indices[left - 1]]); max = Math.max(max, values[selection.indices[left - 1]]); }
    if (!Number.isFinite(min)) { min = 0; max = 1; }
    const pad = Math.max((max - min) * .12, stream.id.startsWith('temp') ? .01 : .002);
    min -= pad; max += pad;
    const x = time => margin.left + (time - start) / (end - start) * plotWidth;
    const y = value => margin.top + (max - value) / (max - min) * plotHeight;
    ctx.font = '9px system-ui'; ctx.fillStyle = '#87979b';
    ctx.strokeStyle = '#edf1f2'; ctx.lineWidth = 1;
    for (let tick = 0; tick < 3; tick++) {
      const value = min + (max - min) * tick / 2;
      ctx.beginPath(); ctx.moveTo(margin.left, y(value)); ctx.lineTo(width - margin.right, y(value)); ctx.stroke();
      ctx.textAlign = 'right'; ctx.fillText(value.toFixed(stream.id.startsWith('temp') ? 2 : 3), margin.left - 7, y(value) + 3);
    }
    for (let tick = 0; tick <= 4; tick++) {
      const time = start + (end - start) * tick / 4;
      ctx.textAlign = tick === 4 ? 'right' : tick === 0 ? 'left' : 'center';
      ctx.fillText(`${number(time)} s`, x(time), height - 3);
    }
    ctx.save(); ctx.beginPath(); ctx.rect(margin.left, margin.top, plotWidth, plotHeight); ctx.clip();
    if (!resulting) {
      for (const event of events) {
        if (event.end <= start || event.start >= end) continue;
        ctx.fillStyle = '#eaf3ef';
        ctx.fillRect(x(Math.max(start, event.start)), margin.top, (Math.min(end, event.end) - Math.max(start, event.start)) / (end - start) * plotWidth, plotHeight);
      }
      // Preserve narrow extrema while drawing at most ~2 points per CSS pixel.
      ctx.strokeStyle = '#acbcc1'; ctx.lineWidth = 1; ctx.beginPath();
      const bucket = Math.max(1, Math.ceil((last - first) / plotWidth));
      let started = false;
      for (let i = first; i < last; i += bucket) {
        let low = i, high = i;
        for (let j = i + 1; j < Math.min(last, i + bucket); j++) { if (values[j] < values[low]) low = j; if (values[j] > values[high]) high = j; }
        for (const row of low < high ? [low, high] : [high, low]) {
          if (!started) { ctx.moveTo(x(times[row]), y(values[row])); started = true; }
          else ctx.lineTo(x(times[row]), y(values[row]));
        }
      }
      ctx.stroke();
    }
    if (selection) {
      ctx.strokeStyle = stream.color; ctx.fillStyle = stream.color;
      if (resulting) {
        ctx.lineWidth = 1.7; ctx.lineJoin = 'round'; ctx.beginPath();
        if (hasCurve) for (let i = lineFirst; i < lineEnd; i++) {
          const px = x(selection.times[i]), py = y(values[selection.indices[i]]);
          if (i === lineFirst) ctx.moveTo(px, py);
          else ctx.lineTo(px, py);
        }
        ctx.stroke();
      } else {
        if ($('held').checked) {
          ctx.globalAlpha = .55; ctx.lineWidth = 1; ctx.beginPath();
          for (let i = Math.max(0, left - 1); i < right; i++) {
            const t = Math.max(start, selection.times[i]);
            const stop = Math.min(end, config.end, selection.times[i + 1] ?? config.end);
            if (t >= stop) continue;
            const value = values[selection.indices[i]];
            ctx.moveTo(x(t), y(value)); ctx.lineTo(x(stop), y(value));
            if (i + 1 < right) ctx.lineTo(x(stop), y(values[selection.indices[i + 1]]));
          }
          ctx.stroke(); ctx.globalAlpha = 1;
        }
        // Every selected observation remains represented; exports never use plot buckets.
        ctx.beginPath();
        for (let i = left; i < right; i++) {
          const px = x(selection.times[i]), py = y(values[selection.indices[i]]);
          const radius = right - left > plotWidth / 2 ? 1.1 : 2.6;
          ctx.moveTo(px + radius, py); ctx.arc(px, py, radius, 0, Math.PI * 2);
        }
        ctx.fill();
      }
      const firstSample = right > left ? values[selection.indices[left]] : null;
      canvas.setAttribute('aria-label', `${column}, ${start} to ${end} seconds: ${right - left} acquired samples. ${resulting ? 'Resulting signal, sampled values joined by straight lines.' : 'Reference and acquired samples.'} Plot range ${min.toFixed(4)} to ${max.toFixed(4)} ${stream.unit}.${firstSample === null ? '' : ` First visible sample ${firstSample.toFixed(4)}.`}${resulting && !hasCurve ? ' No curve: at least two samples spanning the window are needed.' : ''}`);
    }
    ctx.restore();
    if (resulting && !hasCurve) {
      ctx.fillStyle = '#87979b'; ctx.textAlign = 'center'; ctx.font = '11px system-ui';
      const message = !valid ? 'Correct the sampling settings to see the signal.' : !selection ? 'Sensor disabled' : right - left === 1 ? 'One sample in view — a curve needs two samples.' : 'No sampled curve in this window. Try a wider view.';
      ctx.fillText(message, margin.left + plotWidth / 2, margin.top + plotHeight / 2, plotWidth - 12);
    }
  }
  $('plot-note').textContent = resulting
    ? `View: [${number(start)}, ${number(end)}) s. Lines connect acquired samples; values between samples are visual interpolation, not additional measurements. Curves stop at the first and last sample. Exports are unchanged.`
    : `View: [${number(start)}, ${number(end)}) s. Shading marks synthetic events. Reference drawing preserves extrema; exports include every selected sample.${$('held').checked ? ' Held lines show the last reading, not additional measurements.' : ''}`;
}

$('settings').addEventListener('submit', event => event.preventDefault());
// Freeze the grid for a drag or keyboard focus session. Recalculating it on
// every input would unexpectedly enlarge the step as the periods line up.
$('settings').addEventListener('pointerdown', event => {
  if (!event.target.id.endsWith('-rate')) return;
  rateAdjustment = null;
  beginRateAdjustment(event.target);
  syncSnapHint();
});
function finishRateAdjustment() {
  if (!rateAdjustment) return;
  rateAdjustment = null;
  syncSnapHint();
}
window.addEventListener('pointerup', finishRateAdjustment);
window.addEventListener('pointercancel', finishRateAdjustment);
$('settings').addEventListener('focusout', event => {
  if (event.target.id.endsWith('-rate')) finishRateAdjustment();
});
$('settings').addEventListener('keydown', event => {
  if (!event.target.id.endsWith('-rate') || !$('snap-gcd').checked) return;
  const direction = { ArrowRight: 1, ArrowUp: 1, ArrowLeft: -1, ArrowDown: -1, PageUp: 10, PageDown: -10 }[event.key];
  if (direction === undefined && !['Home', 'End'].includes(event.key)) return;
  event.preventDefault();
  const step = beginRateAdjustment(event.target);
  if (!step) return;
  const id = event.target.id.replace('-rate', '');
  const minimum = 1 / dataset.metadata.reference_rate_hz, maximum = dataset.metadata.duration_s;
  let period;
  if (event.key === 'Home') period = unit === 'hz' ? maximum : minimum;
  else if (event.key === 'End') period = unit === 'hz' ? minimum : maximum;
  else {
    const delta = direction * (unit === 'hz' ? -1 : 1);
    const current = periods.get(id);
    const increment = $('export-format').value === 'r1'
      ? current < 60 || (current === 60 && delta < 0) ? 1 : 60
      : step;
    period = current + delta * increment;
  }
  periods.set(id, period);
  alignSamplingPeriods(id);
  $('preset').value = 'custom';
  updateSampling();
});
$('settings').addEventListener('input', event => {
  if (event.target.id.endsWith('-rate')) {
    const [min, max] = rateBounds();
    const value = min * (max / min) ** (event.target.valueAsNumber / 1000);
    const position = event.target.valueAsNumber;
    const rounded = position === 0 ? min : position === 1000 ? max : Math.max(min, Math.min(max, Number(value.toPrecision(4))));
    const period = unit === 'hz' ? 1 / rounded : rounded;
    beginRateAdjustment(event.target);
    const id = event.target.id.replace('-rate', '');
    periods.set(id, period);
    alignSamplingPeriods(id);
  }
  if (event.target.id === 'snap-gcd' || event.target.id.endsWith('-enabled')) {
    rateAdjustment = null;
    alignSamplingPeriods(event.target.id.replace('-enabled', ''));
  }
  if (event.target.id.endsWith('-rate') || event.target.id.endsWith('-enabled') || event.target.id === 'snap-gcd') $('preset').value = 'custom';
  if (!['preset', 'rate-unit'].includes(event.target.id)) updateSampling();
});
$('preset').addEventListener('change', () => {
  const preset = PRESETS[$('preset').value];
  if (!preset) return;
  rateAdjustment = null;
  STREAMS.forEach((stream, i) => { periods.set(stream.id, preset[i]); $(`${stream.id}-enabled`).checked = true; });
  if ($('export-format').value !== 'preview-v1') alignSamplingPeriods();
  updateSampling();
});
$('rate-unit').addEventListener('change', () => {
  rateAdjustment = null;
  unit = $('rate-unit').value; updateSampling();
});
for (const id of ['view-start', 'view-window', 'held']) $(id).addEventListener('input', drawCharts);
$('plot-mode').addEventListener('change', drawCharts);
$('event').addEventListener('change', () => {
  const event = events.find(item => item.id === $('event').value);
  if (!event) return;
  $('view-window').value = '60';
  $('view-start').max = Math.max(0, dataset.metadata.duration_s - 60);
  $('view-start').value = Math.max(0, event.start - 10); drawCharts();
});
new ResizeObserver(() => drawCharts()).observe($('charts'));
$('export-format').addEventListener('change', () => {
  if (!dataset) return;
  rateAdjustment = null;
  if ($('export-format').value !== 'preview-v1' && $('snap-gcd').checked) {
    alignSamplingPeriods();
    $('preset').value = 'custom';
  }
  updateSampling();
});
$('export-capacity').addEventListener('change', updateExport);
$('download').addEventListener('click', async () => {
  if (!valid) return;
  $('download').disabled = true;
  try {
    const adapter = EXPORT_ADAPTERS.find(item => item.id === $('export-format').value);
    if (adapter.firmwareCompatible && !firmwareBundle) throw new Error('Correct the firmware export settings first.');
    const { files } = adapter.firmwareCompatible ? firmwareBundle : adapter.encode(dataset, sampled, config);
    const url = URL.createObjectURL(new Blob([makeZip(files)], { type: 'application/zip' }));
    const link = document.createElement('a'); link.href = url;
    link.download = adapter.firmwareCompatible ? files[0].name.replace(/\.bin$/, '.zip') : `densor-preview-${config.start}-${config.end}s.zip`;
    document.body.append(link); link.click(); link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 30000);
    $('export-summary').textContent += adapter.firmwareCompatible
      ? ' Download prepared: extract the .bin and use Open .bin in Android debug mode.'
      : ' Download prepared: extract the ZIP for preview binaries and manifest.json.';
  } catch (error) { $('export-summary').textContent = `Export failed: ${error.message}`; }
  finally { $('download').disabled = !valid || (EXPORT_ADAPTERS.find(item => item.id === $('export-format').value).firmwareCompatible && !firmwareBundle); }
});

async function load() {
  try {
    const [metadataResponse, samplesResponse, eventsResponse] = await Promise.all(['metadata.json', 'samples.csv', 'events.csv'].map(name => fetch(`/dataset/${name}`)));
    if (![metadataResponse, samplesResponse, eventsResponse].every(response => response.ok)) throw new Error('Cannot read the bundled dataset. Start this app with serve.py.');
    const [metadata, samplesBuffer, eventsCsv] = await Promise.all([metadataResponse.json(), samplesResponse.arrayBuffer(), eventsResponse.text()]);
    const hash = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', samplesBuffer)), byte => byte.toString(16).padStart(2, '0')).join('');
    if (hash !== metadata.sha256?.['samples.csv']) throw new Error('samples.csv checksum does not match metadata.json. Regenerate the dataset together with its metadata.');
    dataset = parseDataset(new TextDecoder().decode(samplesBuffer), metadata);
    dataset.sourceHash = hash;
    events = parseEvents(eventsCsv);
    $('dataset-status').textContent = `${number(metadata.duration_s / 60)} minutes · ${number(dataset.count)} reference rows · ${metadata.reference_rate_hz} Hz · seed ${metadata.seed}`;
    $('reference-stat').textContent = `${metadata.reference_rate_hz} Hz`;
    $('start').max = metadata.duration_s; $('end').max = metadata.duration_s;
    $('end').value = metadata.duration_s;
    $('settings-fields').disabled = false;
    for (const id of ['view-start', 'view-window', 'event']) $(id).disabled = false;
    $('view-window').lastElementChild.value = metadata.duration_s;
    for (const event of events) {
      const option = document.createElement('option'); option.value = event.id;
      option.textContent = `${event.kind.replaceAll('_', ' ')} · ${number(event.start)} s`;
      $('event').append(option);
    }
    $('charts').setAttribute('aria-busy', 'false'); updateSampling();
  } catch (error) {
    $('load-error').textContent = `${error.message} Run NFC_harvester_battery_free/visualization/sampling_lab/run.sh from the repository root, then open the printed localhost URL.`;
    $('load-error').hidden = false; $('dataset-status').textContent = 'Dataset unavailable';
    $('charts').setAttribute('aria-busy', 'false');
  }
}
load();
