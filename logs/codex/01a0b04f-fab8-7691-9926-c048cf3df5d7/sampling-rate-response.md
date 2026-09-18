For **different fixed rates per sensor, I would prioritize acceleration**, followed by light, with temperature sampled more slowly. That ranking concerns **useful changes over time**; raw variances in acceleration, light intensity, and temperature have different units and cannot be compared directly.

The strongest evidence is:

- **Acceleration:** Martinot et al. report rhythmic jaw movements during sleep bruxism at **0.5–1.5 Hz**—one cycle every **0.7–2 seconds**. Chun et al.’s intraoral system sampled acceleration at **54 Hz** and used **4-second windows** for chewing detection. A sample every 120 seconds loses these movement patterns almost entirely, although it can still capture head orientation. Martinot used a chin-mounted accelerometer and gyroscope, so its results establish relevant timescales rather than Densor’s detection accuracy. [Martinot, 2021](https://pmc.ncbi.nlm.nih.gov/articles/PMC8397703/), [Chun, 2020](https://users.ece.utexas.edu/~ethomaz/papers/c13.pdf)
- **Light:** Densor itself detects speaking through **light-intensity variance during 30-second recordings**, collected at **1 Hz**. Sampling every 120 seconds can miss the entire recording interval. This is the most direct example already demonstrated on Densor, but it depends on sufficient ambient illumination. [Densor, §7.2](https://research.tudelft.nl/files/231268124/3699746.pdf)
- **Temperature:** Faster sampling helps capture drinking-related temperature dips and their slopes. For slower temperature trends, the benefit is smaller; sensor placement and encapsulation also affect the response. [Densor, §7.2](https://research.tudelft.nl/files/231268124/3699746.pdf)

For your mock comparison, these would be reasonable **starting settings to test**, not established optimal rates:

| Signal | Synthetic curve | Fixed sampling rate |
|---|---|---:|
| Acceleration | Gravity/posture baseline plus short bursts of 0.5–1.5 Hz jaw movement | **25 Hz** |
| Light | Open/closed levels plus irregular fluctuations during speaking | **1 Hz** |
| Temperature | Slow drift plus a cool-water dip and gradual recovery | **0.1 Hz — every 10 s** |

Use the **same underlying curves** for every sampling strategy. Randomize event onset relative to the sampling clock and repeat across different event durations, amplitudes, and noise levels. Label those choices as simulation assumptions.

I’d compare three conditions:

1. **Original:** all three signals every 120 seconds.
2. **Multirate:** the rates above.
3. **Uniform fast:** all three at 25 Hz, to measure how many readings multirate avoids while preserving detection performance.

Report **event recall, false positives, timing error, and sample/storage counts**. For acceleration, also measure how accurately the movement frequency is recovered. Avoid relying only on curve reconstruction error: long quiet periods can hide poor detection of short events.

One firmware constraint matters: the [current sampling loop](/mnt/shared/Git/densor/NFC_harvester_battery_free/software/DentalSensor_StorageProject/Core/Src/main.c:511) reads all enabled sensors, stores a record, and powers down until a shared RTC alarm. Supporting 25 Hz needs a different acquisition schedule. Track energy separately—fewer sensor readings do not translate directly into proportional energy savings.

Your simulation can quantify **detection improvements under the modeled conditions**. Validating the improvement on real Densor signals would then require labeled recordings at a sufficiently high rate.
