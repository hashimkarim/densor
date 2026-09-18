# Densor visualization labs

Local browser tools for exploring Densor sampling and EEPROM storage.

| Lab | Purpose | Default port | Requirements |
| --- | --- | --- | --- |
| [Sampling lab](sampling_lab/README.md) | Explore sensor rates and export synthetic recordings. | 8765 | Bash and Python 3.10+ |
| [EEPROM lab](eeprom_lab/README.md) | Compare memory layouts and page crossings; vary payloads and add sensors. | 8767 | Bash, Node.js 22.18+ and npm |

From the repository root, start either lab:

```sh
./NFC_harvester_battery_free/visualization/sampling_lab/run.sh
./NFC_harvester_battery_free/visualization/eeprom_lab/run.sh
```

Each script also works from inside its lab folder as `./run.sh [port]`, or by
absolute path from any directory. For example, `./run.sh 9000` starts at port
9000. Without an argument, it starts at the default above. If the port is busy,
it tries successive ports until it can bind, then prints the URL and opens it
in your default browser. Valid ports are 1–65535. On a headless machine, open
the printed URL manually. Stop the server with Ctrl+C.

Both servers bind to `127.0.0.1`. The EEPROM launcher installs its locked npm
dependencies on first run if they are missing (requires network access).
The sampling lab reads the existing
[synthetic dataset](../data/synthetic_multirate/README.md); generate it first if
it is missing. Each lab's README describes its features and validation commands.
