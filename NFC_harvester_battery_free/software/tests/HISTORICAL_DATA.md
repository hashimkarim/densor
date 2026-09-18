# Historical recordings used in R1 tests

This checkout contains **54 Densor CSV recordings** in
[`data/experiments/labeled_data`](../../data/experiments/labeled_data),
plus their session metadata in
[`index.json`](../../data/experiments/index.json).
The original NFC `.bin` dumps are absent. The retainer data-dumps README lists
recordings, but the binaries are absent.

The regression fixture uses
[`2024-04-05-p1-e-1.csv`](../../data/experiments/labeled_data/2024-04-05-p1-e-1.csv):
210 rows containing LIS2DW12 temperature, photodiode, three acceleration axes
and supply voltage at a one-second interval. The source contains **no TMP119**.

These CSV values are processed data. The historical `data_master.py` enables
compensation by default; `plot_readings.py` subtracts
`(supply_V * 10 - 25) * 0.14` from temperature and converts photodiode ADC codes
to millivolts with `raw * supply_V * 1000 / 4095`. Acceleration columns are
signed raw codes in these CSVs, despite an old reader docstring describing g.

[`replay_csv_fixture.py`](../tools/replay_csv_fixture.py) reverses those two
documented transformations. Every inverse result must land on its sensor's
integer-code grid within 1e-6 and satisfy the code bounds; otherwise generation
fails. It sets the old temperature's unavailable low four bits to zero and does
not generate TMP119 readings. It produces:

- A **reconstructed legacy fixture**, retaining the source interval and epoch
  but using a pointer for the CSV's 210-row prefix (2,109 bytes).
- A **reconstructed R1 fixture**, written through the actual C session/logger
  code with mask `0x0D`, 12-byte records and a 2,648-byte final pointer. It uses a
  simulated 8,192-byte memory; this is not proof of the populated hardware's
  density. R1 nominal elapsed times start at zero and do not claim a measured
  new session epoch.
- A provenance JSON file recording input hashes, transforms, lengths and
  limitations, generated in `build/tests` and copied into the candidate bundle.

The source index reports a 2,599-byte original pointer (259 records), while the
labelled CSV has only 210. The remaining 49 samples and original EEPROM bytes
cannot be recovered from this CSV. These fixtures are **reconstructions**, not
real NFC dumps, R1 acquisitions, measured TMP119 data or an in-place migration.

The host suite compares every reconstructed reading between the legacy and R1
Java decoders, checks representative values independently, and verifies that
the checked-in Android test assets match fresh output from the C writer. The
phone test renders the reconstructed R1 trace and verifies that the TMP119 plot
stays hidden. Separate synthetic fixtures exercise TMP119-only/both modes and
negative result codes.

Regenerate and check with:

```sh
python3 NFC_harvester_battery_free/software/tools/test_r1.py
python3 NFC_harvester_battery_free/software/tools/replay_csv_fixture.py
```

This tooling writes only local test files. Device migration remains
export-and-new-session; raw legacy records cannot be appended directly to an
R1 recording.
