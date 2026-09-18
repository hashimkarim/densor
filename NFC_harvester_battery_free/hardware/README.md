# Densor - Hardware

The TMP119 and its 100 nF capacitor sit **14.5 mm beyond the original PCB edge**.
R2/R3 remain **100 kΩ**, C14 connects `V_MCU` to `PSW`, and ALERT is unconnected.
The neck enforces 0.15 mm tracks for `V_MCU`/SDA/SCL and 0.20 mm for `PSW`,
with no vias/components; the continuous back GND fill and repaired GND connection remain.

[Current schematic](gerbers_schematic/schematic_nfc_baterry_free_fpc.pdf) ·
[Gerbers and drills](gerbers_schematic/gerbers_fpc) ·
[PCB layout](exports/pcb.pdf) · [3D render](exports/3d-oblique.png) ·
[BOM](exports/bom.csv) · [Placements](exports/placement.csv)

The original CAD stackup is retained, including its recorded **1.6 mm** thickness.
`exports/densor_nfc_v2.step` contains the native stackup and components;
`exports/board-1.6mm.step` is the nominal board envelope. Actual flex construction
and bend radius are unspecified in the original. Model sources and licenses are in
`Densor_kicad_project/vendor/`.

Latest checks: zero unrouted connections and no new DRC/ERC/parity findings.
The 31 DRC, 19 ERC and 20 metadata findings are inherited. Final I²C, wake/sleep
and bent/epoxied fit measurements across 2.6–1.8 V remain pending.
The purchasing links below are historical; use the schematic BOM for current
values, and complete its missing MPN fields before ordering.

## About the project 

This repository contains the design of Densor: an intraoral, actively powered, battery-free platform featuring multi-modal sensors and an extended lifespan. The repository consists of all components that make Densor:
- [Hardware](https://github.com/TUDSSL/densor/tree/master/NFC_harvester_battery_free/hardware)
- [Densor Firmware](https://github.com/TUDSSL/densor/tree/master/NFC_harvester_battery_free/software/DentalSensor_StorageProject)
- [Android app](https://github.com/TUDSSL/densor/tree/master/NFC_harvester_battery_free/software/source_ST25NFCApplication_V3_9)
- [Experiment setup and data collected](https://github.com/TUDSSL/densor/tree/master/NFC_harvester_battery_free/data/experiments)

This folder contains all the harware design files.

## Folder layout

- [KiCad project](Densor_kicad_project/densor_nfc_v2.kicad_pro): current design, local symbols, footprints and all 25 component STEP models.
- [Datasheets](datasheets): component reference PDFs.
- [Gerbers and schematic](gerbers_schematic): current extension Gerbers, drills, job file and schematic PDF.
- [Exports](exports): PCB views, renders, assembly drawings, BOM, placements and STEP files.

## Historical baseline purchasing references

| Component                  | Qty | Purchased from                                                                                                                                                                                                            |
|----------------------------|-----|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Storage Capacitor          | 2   | [link to buy](https://www.digikey.com/en/products/detail/seiko-instruments/CPH3225A/8692444)                                                                                                                              |
| Decoupling Capacitor 100nF | 6   | [link to buy](https://nl.farnell.com/en-NL/kemet/c0603x104m4ractu/cap-mlcc-0-1uf-16v-0603/dp/2904941?st=0603%200.1uf)                                                                                                     |
| Zener Diode                | 1   | [link to buy](https://nl.farnell.com/on-semiconductor/mm3z3v6c/zener-diode-0-2w-3-6v-sod-323f/dp/2824925?CMP=i-ddd7-00001003)                                                                                             |
| Schottky Diode             | 1   | [link to buy](https://nl.farnell.com/nexperia/1ps76sb40-115/diode-schottky-sod323/dp/1349646?CMP=i-ddd7-00001003)                                                                                                         |
| Photo Diode                | 1   | [link to buy](https://nl.mouser.com/ProductDetail/Vishay-Semiconductors/VEMD1060X01?qs=pdQl6rENvLQkGyXPnNHd%2FQ%3D%3D&gclid=Cj0KCQjwmvSoBhDOARIsAK6aV7gSam9QYNxRe1G3dFcvfZGu4WvhXWOQdtZbTP3WNCzxGxyePn8TLa0aAiUbEALw_wcB) |
| Debug header               | 1   | [link to buy](https://nl.farnell.com/samtec/ftsh-105-01-l-d-k/connector-header-10pos-2row-1/dp/2856435?CMP=i-ddd7-00001003)                                                                                               |
| FPC connector              | 1   | [link to buy](https://nl.mouser.com/ProductDetail/Molex/503480-0800?qs=bodV9ulq6Gz33SKuZL445g%3D%3D&srsltid=AfmBOoqTYanmI1YXInVOzdpLIJ4tpVXYK3TQdvr3HJ4Y6jSj3ONDx022)                                                     |
| Pin Header 01x04           | 1   | [link to buy](https://nl.mouser.com/ProductDetail/Harwin/M20-9770446?qs=ulE8k0yEMYbCPpMPHeYh4w%3D%3D)                                                                                                                     |
| Resistor 100kohm           | 3   | [link to buy](https://nl.farnell.com/vishay/crcw0603100kfkea/res-100k-1-0-1w-0603-thick-film/dp/1469649?st=0603%20100k)                                                                                                   |
| Resistor 8Mohm             | 1   | [link to buy](https://nl.farnell.com/vishay/crcw06038m06fkea/res-8m06-1-0-1w-0603-thick-film/dp/2138717)                                                                                                                  |
| NFC Tag - ST25DV04K-JFR6D3 | 1   | [link to buy](https://www.digikey.nl/en/products/detail/stmicroelectronics/ST25DV64K-JFR6D3/7312974)                                                                                                                      |
| RTC - AM1805               | 1   | [link to buy](https://nl.farnell.com/en-NL/abracon/ab1805-t3/real-time-clock-i2c-qfn-16/dp/2467703?st=ab1805)                                                                                                             |
| MCU - STM32L021F3Ux        | 1   | [link to buy](https://www.digikey.nl/en/products/detail/stmicroelectronics/STM32L021F4U6TR/6166967)                                                                                                                       |
| Accelerometer - LIS2DW12   | 1   | [link to buy](https://nl.farnell.com/en-NL/stmicroelectronics/lis2dw12tr/lga-12-i-mems-digital-o-p-motion/dp/2849615?st=LIS2DW12TR)                                                                                       |
| SPDT - TS3A44159           | 1   | [link to buy](https://www.digikey.nl/en/products/detail/texas-instruments/TS3A44159RSVR/1909585)                                                                                                                          |
| Crystal                    | 1   | [link to buy](https://nl.farnell.com/en-NL/abracon/abs05w-32-768khz-j-2-t/crystal-32-768khz-4pf-smd-1-6/dp/2850126?st=abs05w)                                                                                             |
| Solder paste               | -   | [link to buy](https://www.digikey.nl/en/products/detail/chip-quik-inc/SMD291SNL50T3/5130160)                                                                                                                              |


## Copyright

<a href="https://www.tudelft.nl"><img src="https://github.com/TUDSSL/DIPS/blob/master/images/tudelft_logo.png" width="300px"></a><a href="https://www.radboudumc.nl/en/about-radboudumc"><img src="https://github.com/TUDSSL/densor/blob/master/NFC_harvester_battery_free/images/radboudumc-logo-en-us.svg" width="250px"></a> 

Copyright (C) 2024 TU Delft Embedded Systems Group/Sustainable Systems Laboratory.

MIT Licence or otherwise specified. See [license](https://github.com/TUDSSL/ENGAGE/blob/master/LICENSE) file for details.
