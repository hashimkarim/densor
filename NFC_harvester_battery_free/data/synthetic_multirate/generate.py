#!/usr/bin/env python3
"""Generate reproducible, labeled synthetic signals for a sampling experiment."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
from pathlib import Path

import numpy as np


COLUMNS = (
    "time_s", "accel_x_g", "accel_y_g", "accel_z_g", "light_norm",
    "temp_1_c", "temp_2_c",
)
NOISE_SD = np.array([0.0020, 0.0024, 0.0022, 0.0030, 0.015, 0.020])
EVENT_COLORS = {
    "jaw_rhythm": "#7856bd", "speaking": "#dc8a27",
    "mouth_open": "#169b86", "drinking": "#3188bd", "posture": "#78818c",
}


def smoothstep(x: np.ndarray) -> np.ndarray:
    z = np.clip(x, 0.0, 1.0)
    return z * z * (3.0 - 2.0 * z)


def envelope(t: np.ndarray, start: float, end: float, edge: float = 0.35):
    return smoothstep((t - start) / edge) * smoothstep((end - t) / edge)


def make_events(duration: float, rng: np.random.Generator) -> list[dict]:
    # Each slot gets a randomized type and onset, without access to a sampler.
    kinds = ["jaw_rhythm"] * 8 + ["speaking"] * 5
    kinds += ["mouth_open"] * 4 + ["drinking"] * 3
    rng.shuffle(kinds)
    edges = np.linspace(35.0, duration - 25.0, len(kinds) + 1)
    events = []
    for i, kind in enumerate(kinds):
        slot_width = edges[i + 1] - edges[i]
        desired = rng.uniform(6.0, 24.0) if kind != "drinking" else rng.uniform(3.0, 6.0)
        length = min(desired, slot_width * 0.6)
        start = rng.uniform(edges[i] + 0.5, edges[i + 1] - length - 0.5)
        e = {"kind": kind, "start_s": float(start), "end_s": float(start + length)}
        e["response_end_s"] = e["end_s"]
        if kind == "jaw_rhythm":
            e.update(frequency_hz=float(rng.uniform(0.5, 1.5)),
                     amplitude_g=float(rng.uniform(0.025, 0.085)),
                     phase_rad=float(rng.uniform(0.0, 2 * np.pi)))
        elif kind == "speaking":
            e.update(frequency_hz=float(rng.uniform(1.4, 2.8)),
                     amplitude_g=float(rng.uniform(0.008, 0.020)),
                     phase_rad=float(rng.uniform(0.0, 2 * np.pi)))
        elif kind == "mouth_open":
            e["pitch_change_deg"] = float(rng.uniform(4.0, 8.0))
        elif kind == "drinking":
            tau = float(rng.uniform(35.0, 85.0))
            e.update(temperature_drop_c=float(rng.uniform(1.3, 2.8)),
                     recovery_tau_s=tau,
                     response_end_s=float(min(duration, start + length + 6 * tau)))
        events.append(e)

    # Posture transitions may overlap activities. Their orientation change persists.
    for start in np.sort(rng.uniform(40.0, duration - 15.0, 5)):
        end = float(start + rng.uniform(2.0, 5.0))
        events.append({
            "kind": "posture", "start_s": float(start), "end_s": end,
            "response_end_s": end, "roll_target_deg": float(rng.uniform(-32.0, 32.0)),
            "pitch_target_deg": float(rng.uniform(-12.0, 18.0)),
        })
    events.sort(key=lambda e: e["start_s"])
    for i, e in enumerate(events):
        e["event_id"] = f"event_{i + 1:02d}"
    return events


def make_dataset(duration: float, rate: float, seed: int):
    streams = np.random.SeedSequence(seed).spawn(7)
    rng = np.random.default_rng(streams[0])
    n = round(duration * rate)
    t = np.arange(n, dtype=np.float64) / rate
    events = make_events(duration, rng)
    roll = np.full(n, np.deg2rad(5.0))
    pitch = np.full(n, np.deg2rad(2.0))
    last_roll, last_pitch = 5.0, 2.0
    for e in events:
        if e["kind"] != "posture":
            continue
        transition = smoothstep((t - e["start_s"]) / (e["end_s"] - e["start_s"]))
        roll += np.deg2rad(e["roll_target_deg"] - last_roll) * transition
        pitch += np.deg2rad(e["pitch_target_deg"] - last_pitch) * transition
        last_roll, last_pitch = e["roll_target_deg"], e["pitch_target_deg"]

    dynamic_accel = np.zeros((n, 3))
    light = 0.035 + 0.003 * np.sin(2 * np.pi * t / 420.0)
    temperature = 36.4 + 0.09 * np.sin(2 * np.pi * t / 1500.0)
    temperature += 0.025 * np.sin(2 * np.pi * t / 390.0)
    for e in events:
        start, end, kind = e["start_s"], e["end_s"], e["kind"]
        u = t - start
        env = envelope(t, start, end)
        if kind == "jaw_rhythm":
            phase = 2 * np.pi * e["frequency_hz"] * u + e["phase_rad"]
            a = e["amplitude_g"] * env
            dynamic_accel[:, 0] += a * (np.sin(phase) + 0.15 * np.sin(2 * phase))
            dynamic_accel[:, 1] += 0.55 * a * np.sin(phase + 0.6)
            dynamic_accel[:, 2] += 0.80 * a * np.sin(phase - 0.4)
        elif kind == "speaking":
            phase = 2 * np.pi * e["frequency_hz"] * u + e["phase_rad"]
            irregular = 0.5 + 0.5 * np.sin(phase + 0.9 * np.sin(2 * np.pi * 0.31 * u))
            opening = env * (0.12 + 0.88 * irregular**2)
            light += 0.68 * opening
            pitch += np.deg2rad(4.0) * opening
            dynamic_accel[:, 0] += e["amplitude_g"] * env * np.sin(phase + 0.8)
            dynamic_accel[:, 1] += 0.6 * e["amplitude_g"] * env * np.sin(phase * 1.7)
        elif kind == "mouth_open":
            light += 0.72 * env
            pitch += np.deg2rad(e["pitch_change_deg"]) * env
        elif kind == "drinking":
            light += 0.48 * env
            pitch += np.deg2rad(6.0) * env
            # Cooling during the action; recovery continues well after its end.
            cooling = smoothstep(u / (end - start))
            elapsed = np.maximum(t - end, 0.0)
            tau = e["recovery_tau_s"]
            tail = 1.0 - smoothstep((elapsed - 5.0 * tau) / tau)
            temperature -= e["temperature_drop_c"] * cooling * np.exp(-elapsed / tau) * tail

    # Gravity in sensor coordinates: norm exactly 1 g without dynamic acceleration.
    gravity = np.column_stack((
        -np.sin(pitch), np.sin(roll) * np.cos(pitch), np.cos(roll) * np.cos(pitch),
    ))
    ideal = np.column_stack((gravity + dynamic_accel, light, temperature, temperature))
    measured = ideal.copy()
    for i, stream in enumerate(streams[1:]):
        measured[:, i] += np.random.default_rng(stream).normal(0.0, NOISE_SD[i], n)
    measured[:, 3] = np.clip(measured[:, 3], 0.0, 1.0)
    return t, ideal, measured, events


def validate(t, ideal, measured, events, duration, rate) -> dict:
    errors = []
    n = round(duration * rate)
    def check(condition, message):
        if not condition:
            errors.append(message)

    check(ideal.shape == measured.shape == (n, 6), "Unexpected channel/row count")
    check(np.isfinite(measured).all() and np.isfinite(ideal).all(), "Non-finite values")
    check(np.allclose(np.diff(t), 1.0 / rate, atol=1e-10, rtol=0), "Irregular timestamps")
    check(t[0] == 0.0 and t[-1] < duration, "Incorrect recording bounds")
    check(np.array_equal(ideal[:, 4], ideal[:, 5]), "Temperature truth differs")
    check(np.all((measured[:, 3] >= 0) & (measured[:, 3] <= 1)), "Light outside [0, 1]")
    residual = measured - ideal
    empirical_sd = residual.std(axis=0, ddof=1)
    check(np.allclose(empirical_sd, NOISE_SD, rtol=0.04, atol=0), "Unexpected noise SD")
    check(np.all(np.abs(residual.mean(axis=0)) < NOISE_SD * 0.04), "Unexpected noise bias")
    correlation = float(np.corrcoef(residual[:, 4:6].T)[0, 1])
    check(abs(correlation) < 0.025, "Temperature noise streams correlate")
    for e in events:
        check(30 <= e["start_s"] < e["end_s"] <= e["response_end_s"] <= duration,
              f"Invalid bounds: {e['event_id']}")
    quiet = t < 30.0
    check(np.allclose(np.linalg.norm(ideal[quiet, :3], axis=1), 1.0),
          "Quiet accelerometer lacks 1 g gravity")
    counts = {kind: sum(e["kind"] == kind for e in events) for kind in EVENT_COLORS}
    check(counts == {"jaw_rhythm": 8, "speaking": 5, "mouth_open": 4,
                     "drinking": 3, "posture": 5}, "Unexpected event counts")
    # Verify rhythms in the central, fully active portion of every labeled bout.
    frequency_errors = []
    for e in events:
        if e["kind"] != "jaw_rhythm":
            continue
        mask = (t >= e["start_s"] + 0.5) & (t < e["end_s"] - 0.5)
        x = measured[mask, 0]
        spectrum = np.abs(np.fft.rfft((x - x.mean()) * np.hanning(len(x))))
        freqs = np.fft.rfftfreq(len(x), d=1.0 / rate)
        band = (freqs >= 0.3) & (freqs <= 2.0)
        peak = float(freqs[band][np.argmax(spectrum[band])])
        error = abs(peak - e["frequency_hz"])
        frequency_errors.append(error)
        check(error < max(0.2, 1.5 * rate / len(x)), f"Unresolved jaw rhythm: {e['event_id']}")
    if errors:
        raise ValueError("Dataset validation failed: " + "; ".join(errors))
    return {
        "status": "passed", "rows": n, "channels": 6, "event_counts": counts,
        "noise_std_measured": dict(zip(COLUMNS[1:], empirical_sd.tolist())),
        "temperature_noise_correlation": correlation,
        "temperature_difference_std_c": float((measured[:, 4] - measured[:, 5]).std(ddof=1)),
        "max_jaw_frequency_error_hz": max(frequency_errors),
        "quiet_gravity_norm_g": float(np.linalg.norm(ideal[quiet, :3], axis=1).mean()),
        "ranges": {name: {"min": float(measured[:, i].min()), "max": float(measured[:, i].max())}
                   for i, name in enumerate(COLUMNS[1:])},
        "note": "Checks validate the synthetic data, not clinical or firmware performance.",
    }


def make_plots(output: Path, t, ideal, measured, events):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.patches import Patch

    plt.rcParams.update({"font.size": 10, "axes.spines.top": False,
                         "axes.spines.right": False, "figure.facecolor": "white"})
    fig, axes = plt.subplots(5, 1, figsize=(14, 10), sharex=True, layout="constrained")
    # Show up to 20 plotted points per second; the CSV always retains all samples.
    stride = max(1, int(round((1 / (t[1] - t[0])) / 20)))
    for i, ax in enumerate(axes[:3]):
        ax.plot(t[::stride] / 60, measured[::stride, i], color="#7856bd", lw=0.65)
        ax.set_ylabel(f"Accel {'XYZ'[i]} (g)")
    axes[3].plot(t[::stride] / 60, measured[::stride, 3], color="#d88216", lw=0.8)
    axes[3].set_ylabel("Light (0–1)")
    axes[3].set_ylim(-0.03, 1.03)
    axes[4].plot(t[::stride] / 60, measured[::stride, 4], color="#168a80", lw=0.8, label="Temperature 1")
    axes[4].plot(t[::stride] / 60, measured[::stride, 5], color="#de6f57", lw=0.6, alpha=0.7, label="Temperature 2")
    axes[4].set_ylabel("Temperature (°C)")
    axes[4].set_xlabel("Elapsed time (minutes)")
    axes[4].legend(loc="lower right", ncol=2)
    for ax in axes:
        for e in events:
            ax.axvspan(e["start_s"] / 60, e["end_s"] / 60, color=EVENT_COLORS[e["kind"]], alpha=0.11)
        ax.grid(alpha=0.15)
        ax.margins(x=0)
    fig.suptitle("Synthetic Densor recording · six channels · 100 Hz reference" if np.isclose(t[1]-t[0], .01)
                 else "Synthetic Densor recording · six channels", fontsize=16)
    axes[0].legend(handles=[Patch(facecolor=color, alpha=0.5, label=kind.replace("_", " "))
                            for kind, color in EVENT_COLORS.items()], loc="upper right", ncol=5, fontsize=8)
    fig.savefig(output / "overview.png", dpi=150)
    plt.close(fig)

    fig, axes = plt.subplots(3, 1, figsize=(13, 8), layout="constrained")
    e = next(e for e in events if e["kind"] == "jaw_rhythm")
    mask = (t >= e["start_s"] - 2) & (t <= e["end_s"] + 2)
    for i, color in enumerate(["#7856bd", "#238a80", "#d78324"]):
        # Remove the held orientation to make the three movement waveforms visible.
        baseline = ideal[mask, i][0]
        axes[0].plot(t[mask], measured[mask, i] - baseline, lw=0.8, color=color,
                     label=f"{'XYZ'[i]} (initial value subtracted)")
    axes[0].axvspan(e["start_s"], e["end_s"], color="#7856bd", alpha=0.08)
    axes[0].set(title=f"Example jaw bout · {e['frequency_hz']:.2f} Hz · all three axes",
                ylabel="Change from initial (g)", xlabel="Elapsed time (s)")
    axes[0].legend(loc="upper right", ncol=3, fontsize=8)
    mask = (t >= 10.0) & (t < 15.0)
    axes[1].plot(t[mask], measured[mask, 4], color="#168a80", lw=0.8, label="Temperature 1 · noise σ = 0.015 °C")
    axes[1].plot(t[mask], measured[mask, 5], color="#de6f57", lw=0.8, alpha=.8, label="Temperature 2 · noise σ = 0.020 °C")
    axes[1].plot(t[mask], ideal[mask, 4], color="#263445", lw=1.6, label="Shared true temperature")
    axes[1].set(title="Same temperature curve, slightly different independent noise",
                ylabel="Temperature (°C)", xlabel="Elapsed time (s)")
    axes[1].legend(loc="upper right", fontsize=8, ncol=3)
    axes[2].plot(t[mask], measured[mask, 4] - measured[mask, 5], color="#526274", lw=.8)
    axes[2].axhline(0, color="#111827", lw=.8)
    axes[2].set(title="Temperature 1 minus temperature 2 · expected noise σ = 0.025 °C",
                ylabel="Difference (°C)", xlabel="Elapsed time (s)")
    for ax in axes:
        ax.grid(alpha=.15)
        ax.margins(x=0)
    fig.suptitle("Synthetic data detail", fontsize=16)
    fig.savefig(output / "detail.png", dpi=150)
    plt.close(fig)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seed", type=int, default=20260918)
    parser.add_argument("--duration-s", type=float, default=1800.0)
    parser.add_argument("--rate-hz", type=float, default=100.0)
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parent / "dataset")
    args = parser.parse_args()
    if not np.isfinite(args.duration_s) or args.duration_s < 300:
        parser.error("--duration-s must be finite and at least 300 seconds")
    if not np.isfinite(args.rate_hz) or args.rate_hz < 50 or args.rate_hz > 1000:
        parser.error("--rate-hz must be finite and between 50 and 1000 Hz")
    if args.seed < 0:
        parser.error("--seed must be nonnegative")
    if not np.isclose(args.duration_s * args.rate_hz, round(args.duration_s * args.rate_hz), rtol=0, atol=1e-8):
        parser.error("duration × rate must be a whole number of samples")
    t, ideal, measured, events = make_dataset(args.duration_s, args.rate_hz, args.seed)
    checks = validate(t, ideal, measured, events, args.duration_s, args.rate_hz)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    for name, values in [("samples.csv", measured), ("ground_truth.csv", ideal)]:
        np.savetxt(output / name, np.column_stack((t, values)), delimiter=",",
                   header=",".join(COLUMNS), comments="", fmt=["%.9f"] + ["%.7f"] * 6)
    fields = ["event_id", "kind", "start_s", "end_s", "response_end_s", "frequency_hz",
              "amplitude_g", "phase_rad", "pitch_change_deg", "temperature_drop_c", "recovery_tau_s",
              "roll_target_deg", "pitch_target_deg"]
    with (output / "events.csv").open("w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        writer.writerows(events)
    # Re-read the exported measurements to check that serialization preserves the data.
    exported = np.loadtxt(output / "samples.csv", delimiter=",", skiprows=1)
    if not np.allclose(exported[:, 0], t, atol=5.1e-10, rtol=0):
        raise ValueError("Timestamp serialization error")
    if not np.allclose(exported[:, 1:], measured, atol=5.1e-8, rtol=0):
        raise ValueError("Measurement serialization error")
    checks["csv_round_trip"] = "passed"
    (output / "validation.json").write_text(json.dumps(checks, indent=2) + "\n")
    make_plots(output, t, ideal, measured, events)
    metadata = {
        "synthetic": True, "seed": args.seed, "numpy_version": np.__version__,
        "duration_s": args.duration_s, "reference_rate_hz": args.rate_hz,
        "rows": len(t), "time_interval": "[0, duration_s)",
        "sensor_count": 4, "measurement_channel_count": 6,
        "columns": list(COLUMNS), "units": ["s", "g", "g", "g", "normalized_0_to_1", "degC", "degC"],
        "noise": {"model": "independent zero-mean Gaussian per channel and time step",
                  "standard_deviation": dict(zip(COLUMNS[1:], NOISE_SD.tolist())),
                  "light_clipping": [0, 1]},
        "temperature_relationship": "identical underlying curve; independent noise only; no calibration offset or lag difference",
        "event_counts": checks["event_counts"],
        "event_intervals": "[start_s, end_s); posture changes persist; drinking has a thermal recovery tail to response_end_s",
        "parameters_are_assumed": ["noise", "amplitude", "event duration", "occurrence count",
                                   "temperature recovery", "speaking modulation", "posture", "reference rate"],
        "model": {"gravity_g": 1, "temperature_baseline_c": 36.4,
                  "jaw_frequency_range_hz": [0.5, 1.5], "jaw_amplitude_range_g": [0.025, 0.085],
                  "temperature_drop_range_c": [1.3, 2.8], "recovery_tau_range_s": [35, 85],
                  "thermal_tail_taper_tau": [5, 6]},
        "sources": [
            {"doi": "10.2147/NSS.S320664", "basis": "jaw motion frequency range; a different sensor system"},
            {"doi": "10.1145/3410531.3414309", "basis": "intraoral inertial acquisition at 54 Hz; four-second windows"},
            {"doi": "10.1145/3699746", "basis": "Densor light/speaking and temperature/drinking relationships"},
        ],
        "sha256": {},
    }
    for name in ["samples.csv", "ground_truth.csv", "events.csv"]:
        with (output / name).open("rb") as f:
            metadata["sha256"][name] = hashlib.file_digest(f, "sha256").hexdigest()
    (output / "metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
    print(f"Created {len(t):,} rows × 6 channels in {output}")
    print(f"Duration: {args.duration_s:g} s; reference rate: {args.rate_hz:g} Hz; seed: {args.seed}")
    print(f"Temperature noise SD: {checks['noise_std_measured']['temp_1_c']:.5f}, "
          f"{checks['noise_std_measured']['temp_2_c']:.5f} °C")
    print("Validation passed; overview.png and detail.png written.")


if __name__ == "__main__":
    main()
