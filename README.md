# Densor

<img src="NFC_harvester_battery_free/images/densor_inuse.png" width="800">

## About the project

Densor is an intraoral sensor platform, attached to regular dental retainers or aligners, that can take measurements inside the mouth. 

## Rationale

At present, in mouth measurements are typically made with external tools and in a controlled labaratory setting. Thus, real data from users daily lives is missing. To overcome this, we propose using widely used deltal retainers (or aligners), and enhance them with the Densor sensing platform. This way data is collected from inside the mouth without external tools. 

## Features

1. **Contactless transmission:** Densor is completely sealed and interacts with a smartphone via [NFC protocol](https://en.wikipedia.org/wiki/Near-field_communication). 
2. **Energy harvesting:** To stay powered Densor harvests energy, either from the [NFC communication itself](https://github.com/TUDSSL/densor/tree/master/NFC_harvester_battery_free) or from the [temperature gradient between the body and cold water](https://github.com/TUDSSL/densor/tree/master/TEG_harvester_battery_free). 
4. **Free of external hardware:** Operating Densor does not require any specialized tools, other than a NFC-compatible smartphone.

## Project layout

For the current NFC firmware (R2/R3 multirate logging), Android app, build
commands and protocol documentation, see the [software guide](NFC_harvester_battery_free/software/README.md).

This repository contains the design (both hardware and software) of two versions of Densor, based on the source of energy harvesting. Each version of the project is contained in its own folder:

1. **[Densor with NFC-based energy harvesting from a smartphone](https://github.com/TUDSSL/densor/tree/master/NFC_harvester_battery_free)**, as presented in [this research article](https://dl.acm.org/doi/10.1145/3699746);
2. **[Densor with thermoelectric energy harvesting from drinking cold water](https://github.com/TUDSSL/densor/tree/master/TEG_harvester_battery_free)**.

<a href="https://www.tudelft.nl"><img src="https://github.com/TUDSSL/DIPS/blob/master/images/tudelft_logo.png" width="300px"></a><a href="https://www.radboudumc.nl/en/about-radboudumc"><img src="https://github.com/TUDSSL/densor/blob/master/NFC_harvester_battery_free/images/radboudumc-logo-en-us.svg" width="300px"></a> 

## Copyright

Copyright (C) 2024 TU Delft Embedded Systems Group/[Sustainable Systems Laboratory](https://github.com/TUDSSL).

MIT License or otherwise specified. See [license](https://github.com/TUDSSL/densor/blob/master/LICENSE.txt) file for details.
