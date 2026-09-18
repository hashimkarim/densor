# Synthetic multirate sampling dataset

A reproducible, simulated recording to replay as the input to a sampling
experiment. It contains **one three-axis accelerometer, one light sensor, and
two temperature sensors**: six measurement channels. These are synthetic
signals, not measurements from a person or calibrated Densor hardware.

The supplied recording lasts **30 minutes at 100 Hz** (180,000 rows). Each row
represents an instantaneous observation, 10 ms after the previous one. The
last timestamp is 1799.99 s; the recording interval is `[0, 1800)`.

## Files

- [dataset/samples.csv](dataset/samples.csv): noisy measurements for the scanner.
- [dataset/ground_truth.csv](dataset/ground_truth.csv): matching noise-free
  curves, for evaluation only. Both temperature columns are identical here.
- [dataset/events.csv](dataset/events.csv): event labels and parameters, for
  evaluation only. Intervals include their start and exclude their end.
- [dataset/metadata.json](dataset/metadata.json): seed, units, model assumptions,
  sources, and file checksums.
- [dataset/validation.json](dataset/validation.json): generated-data checks and
  measured noise statistics.
- [dataset/overview.png](dataset/overview.png): whole recording.
- [dataset/detail.png](dataset/detail.png): close-ups of jaw motion and the
  small difference between the temperature channels.

## Columns and noise

| Column | Meaning | Independent Gaussian noise, standard deviation |
|---|---|---:|
| `time_s` | Elapsed time in seconds | None |
| `accel_x_g` | X acceleration, including gravity, in g | 0.0020 g |
| `accel_y_g` | Y acceleration, including gravity, in g | 0.0024 g |
| `accel_z_g` | Z acceleration, including gravity, in g | 0.0022 g |
| `light_norm` | Normalized light intensity, 0–1 | 0.0030 |
| `temp_1_c` | Temperature sensor 1, degrees Celsius | 0.015 °C |
| `temp_2_c` | Temperature sensor 2, degrees Celsius | 0.020 °C |

The temperature sensors have the **same underlying temperature, response, and
calibration**. Only their independent noise differs. These noise levels are
chosen simulation parameters, not measured sensor specifications. Light is
dimensionless and is not a lux measurement or the Densor ADC output.

The six measurement channels have independent noise streams. Event generation
uses a separate stream, so changing the noise cannot move the events.

## Events and assumptions

The default schedule has 8 rhythmic jaw-movement bouts, 5 speaking bouts,
4 mouth-open intervals, 3 cool-drink events, and 5 posture transitions.
Onsets are randomized without consulting the 120-second sampling grid.
The first 30 seconds contain no activity events and can be used to inspect
noise and slow baseline variation.

- **Posture:** smooth changes to roll and pitch rotate the 1 g gravity vector.
  The new posture persists after the transition. The event interval labels
  the transition, not the entire subsequent held posture.
- **Rhythmic jaw movement:** smoothly enveloped 0.5–1.5 Hz motion, with a small
  second harmonic and different projections onto the three axes. These bouts
  do not force the mouth open or produce a light signal.
- **Speaking:** irregular light fluctuations and smaller jaw movements during
  the same labeled interval. Illumination is assumed sufficient.
- **Mouth opening:** a sustained light increase and small pitch change, with
  smoothed opening and closing edges.
- **Drinking:** a light/opening transient and a smooth temperature decrease,
  followed by recovery. Temperature recovery continues after drinking ends.
  `end_s` labels the drinking action; `response_end_s` marks the observed end
  of the modeled thermal response. Recovery is smoothly tapered to zero
  between five and six time constants.
- **Temperature baseline:** slow variation around 36.4 °C. Both temperature
  channels receive exactly the same baseline and drink response.

The jaw rhythm range is motivated by Martinot et al. (2021), who report
0.5–1.5 Hz mandibular movements in phasic sleep bruxism:
<https://pmc.ncbi.nlm.nih.gov/articles/PMC8397703/>.
Their chin-mounted inertial system differs from Densor. Chun et al. (2020)
demonstrate intraoral acceleration sensing at 54 Hz with four-second analysis
windows: <https://doi.org/10.1145/3410531.3414309>.
The Densor paper motivates the light/speaking and temperature/drinking
relationships: <https://doi.org/10.1145/3699746>.

Event durations, amplitudes, occurrence counts, noise levels, temperature
recovery constants, and 100 Hz reference rate are **simulation choices**.
The recording combines illustrative activities and is not a clinical sleep
recording, a validated physiological model, or evidence of detection accuracy.

## Scan or replay

The scanner should receive only `samples.csv`. At replay time `t`, expose the
newest row with `time_s <= t`. For real-time playback, release rows every
10 ms; for accelerated playback, advance the same simulation clock faster.
Use elapsed timestamps to determine availability rather than relying on the
precision of operating-system sleep calls.

For a sampler starting at zero, the suggested fixed rates align exactly with
the reference grid:

| Sampler | Rows selected, counting the first data row as index 0 |
|---|---|
| Original, all sensors every 120 s | 0, 12000, 24000, … |
| Accelerometer, 25 Hz | 0, 4, 8, …; read all three axes together |
| Light, 1 Hz | 0, 100, 200, … |
| Both temperatures, 0.1 Hz | 0, 1000, 2000, … |

These are point samples. Do not average the rows between observations unless
that is the acquisition behavior being evaluated. A held/interpolated output
is not an additional measurement. A 100 Hz reference is a discrete surrogate
for a continuous signal; it does not model unobserved content above 50 Hz,
sensor filtering, quantization, startup delay, energy, or storage constraints.

Keep `ground_truth.csv` and `events.csv` out of the scanner; use them afterward
to score detection and reconstruction. Repeat with multiple seeds and sampling
phases before drawing conclusions. The optional multirate settings here are
comparison settings, not validated optimal rates or a firmware implementation.

## Regenerate

With Python 3.11 or newer, from this directory:

```bash
python -m pip install -r requirements.txt
python generate.py
```

The default output is `dataset/` next to the generator. To make a separate
recording without overwriting the supplied one:

```bash
python generate.py --seed 42 --output /tmp/densor-synthetic-42
```

`--duration-s` (minimum 300 s) and `--rate-hz` (minimum 50 Hz) are configurable.
The seed defaults to 20260918. Identical parameters and NumPy version reproduce
the CSV files byte for byte. `validation.json` includes serialization,
temperature-noise independence, and event/quiet-region checks.
