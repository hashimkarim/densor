# Densor with NFC-based Energy Harvesting from a Smartphone

For the current R1/R2/R3 software, use the [firmware and Android guide](software/README.md).
The memory diagram and Time Sync/Charge operating steps below describe the
original published firmware. R2/R3 use independent sensor periods and the
Stop/Apply workflow documented in the current guide.

<img src="images/densor_assembled.jpg" width="800">

This is the official public repository for intraoral sensing platform, called Densor (a portmanteau of a word _dental_ and _sensor_), based on [NFC protocol](https://en.wikipedia.org/wiki/Near-field_communication) for energy harvesting and communication from a NFC-enabled smartphone. For a Densor version with thermoelectric energy harvesting from drinking cold water please refer to [this repository](https://github.com/TUDSSL/densor/tree/master/TEG_harvester_battery_free). 

## About the Project

This repository contains the design files of the NFC-based Densor:

- [Densor hardware](https://github.com/TUDSSL/densor/tree/master/NFC_harvester_battery_free/hardware)
- [Densor firmware](https://github.com/TUDSSL/densor/tree/master/NFC_harvester_battery_free/software/DentalSensor_StorageProject)
- [Android app](https://github.com/TUDSSL/densor/tree/master/NFC_harvester_battery_free/software/source_ST25NFCApplication_V3_9)
- [Experiment setup and data collected from Densor evaluation](https://github.com/TUDSSL/densor/tree/master/NFC_harvester_battery_free/data/experiments) presented in Densor's [accompanying publciation](https://dl.acm.org/doi/10.1145/3699746).

## Rationale behind Densor

Intraoral sensors are another type of sensors in the field of healthcare and technology. While head-worn wearables have made significant advancements in monitoring various health metrics, placing sensors directly in the mouth remains a challenge. Currently, we rely heavily on external tools to gather dental and oral health data, which leaves critical information missing. For those looking to deploy in-mouth sensors, the process is daunting, requiring them to start from scratch. Additionally, there is no straightforward way to keep these sensors small, safe, and capable of long-lasting communication, making it difficult to integrate them into practical applications.

## Challenges

Intraoral sensing is unique because it operates within one of the most dynamic and challenging environments in the human body—the mouth. Unlike other forms of wearable technology, intraoral sensors must contend with constant exposure to saliva, varying temperatures, and the mechanical forces of chewing and speaking. These sensors need to be not only highly sensitive and accurate but also biocompatible and safe for long-term use. Additionally, they offer the potential to gather real-time data on oral health, dietary habits, and even health conditions, providing insights that are difficult to obtain through external sensors. This makes intraoral sensing a powerful tool with unique capabilities that go beyond existing methods.

## Densor Architecture

<img src="images/densor_arch.png" width="800">

### Communication and Data Storage

Densor uses the NFC interface (based on STMicroelectronics' [ST25DV64K](https://www.st.com/en/nfc/st25dv64k.html) NFC/RFID tag IC) for communication with a smartphone, and the on board non-voltatile memory to store measurements. This way, data collected by Densor is always available to be read out by the smartphone.

### Energy Harvesting

Energy is harvested from the NFC tag itself, and stored for later use. This allows for Densor's continous operation and reader-independent data collection.

### Energy Storage

The harvested energy is stored on capacitors instead of batteries. This makes Densor a battery-free device and thus safer and more acceptable than Densor based on batteries.

### Power Control

Despite using low-power sensors, energy would be drained from the storage capacitor(s) rapidly if Densor is kept continuously powered. As the harvested energy stored on the capacitors is limited, it is imperative that we save and expend this power carefully. To overcome this, we use a low power Real-Time Clock (RTC) [AB1805](https://abracon.com/Precisiontiming/AB18X5-RTC.pdf) with power switch for power management. This means that the microcontroller unit (MCU) and sensors are completely powered off in the inter-sample period, with only the RTC powered to determine wake up time. This method achieves better power saving than deep-sleep or any of the low power run modes of the MCU.

### Densor On-Board Sensors

As of now, Densor includes three sensing modalities: temperature, accelaration and light intensity. Measuring oral temperature, jaw position, and whether the mouth is open using intraoral sensors is useful for gaining insight into oral and overall health. Oral temperature sensors provide accurate readings to detect early signs of fever and inflammation. Jaw position sensors can monitor alignment and movement, making them valuable for detecting sleep patterns. Light sensors that detect whether the mouth is open can help track breathing patterns and oral habits, which are essential for identifying issues like mouth breathing or sleep-related disorders.

## Densor Operation

<img src="images/results_sensor_data.png" width="800">

The above image shows examples of data collected by Densor and demonstrates its capabilities. The temperature can be seen rising to body temperature when Densor is inserted in the mouth. The light level falls when the mouth is closed, and rises when the mouth is open. It also varies when the user is speaking. When drinking cold water, the temperature initally falls and then rises back to body temperature.

<img src="images/results_sleep_data.png" width="800">

Densor can also determine the orientation of the jaw when the head is tilted. This is particularly useful for measuring head position during sleep, as seen in the above figure. The figure shows actual data collected from a user during a full night's sleep with _one sample collected every two minutes_. We can see the orientation of the head change between left, right and center as the user changes their sleep position.

## Getting Started with Densor

The long-term goal of the Densor project is to lower the ceiling of development of intraoral sensors. Thus, we wish that everyone can build, use and even expand Densor to improve sensing capabilities and data collection. Here we explain the steps to create Densor from scratch.

### Building the Densor Hardware

<img src="images/densor_pcb_labelled.png" width="800">

The Densor printed circuit board (PCB) is built using off-the-shelf components and assembled onto a flexible PCB. In our case we have used the flex PCB services from [PCBway](https://www.pcbway.com/fpc-rigid-flex-pcb/flex-pcb.html). The picture above shows the main components of Densor ( (a comprehensive list of Densor hardware, along with links to buy them, can be found under [Hardware](https://github.com/TUDSSL/densor/tree/master/NFC_harvester_battery_free/hardware)).

- **A** Custom-designed PCB antenna
- **B** STMicroelectronics [ST25DV64K](https://www.st.com/en/nfc/st25dv64k.html) NFC tag
- **C** Seiko [CPH3225A](https://www.sii.co.jp/en/me/datasheets/chip-capacitor/cph3225a/) chip capacitor bank
- **D** Abracon [AB1805](https://abracon.com/Precisiontiming/AB18X5-RTC.pdf) RTC
- **E** STMicroelectronics [LIS2DW12](https://www.st.com/en/mems-and-sensors/lis2dw12.html) accelerometer
- **F** STMicroelectronics [STM32L021F4](https://www.st.com/en/microcontrollers-microprocessors/stm32l021f4.html) MCU
- **G** Vishay [VEMD1060X01](https://www.vishay.com/docs/84295/vemd1060x01.pdf) photodiode
- **H** Serial Wire Debug (SWD) programming port (which is cut off before embedding in a dental aligner).

The [Densor hardware](https://github.com/TUDSSL/densor/tree/master/NFC_harvester_battery_free/hardware) folder contains the [gerber files](https://github.com/TUDSSL/densor/tree/master/NFC_harvester_battery_free/hardware/gerbers_schematic) which can be used to order the PCBs directly. To order directly, use the `.gbr` Gerber output files and `.drl` drill file for manufacturing.

<img src="images/kicad_screenshot.png" width="800">

The hardware folder also contains Densor' [schematic](https://github.com/TUDSSL/densor/tree/master/NFC_harvester_battery_free/hardware/gerbers_schematic) and [KiCad](https://www.kicad.org/blog/2021/12/KiCad-6.0.0-Release/) project which can be used for further expansion or modifications of Densor. A screenshot of the Densor PCB design in KiCad can be seen above. To open the project using Kicad, launch Kicad and navigate to 'File' -> 'Open project' -> And choose the [`densor_nfc_v2.kicad_pro`](https://github.com/TUDSSL/densor/blob/master/NFC_harvester_battery_free/hardware/Densor_kicad_project/densor_nfc_v2.kicad_pro) project file. The project includes the KiCad project files `.kicad_pcb`, `.pro` and `.sch`.

With the PCB and components, we used the lead-free [SAC305](https://nl.mouser.com/datasheet/2/73/SMD291SNL250T3-595229.pdf) solder paste to assemble the Densor as per the schematic.

### Uploading the Firmware to Densor's MCU

Once the Densor hardware is assembled, the [STM32L021F4](https://www.st.com/en/microcontrollers-microprocessors/stm32l021f4.html) MCU on board the Densor needs to have the firmware uploaded. The Densor [software folder](https://github.com/TUDSSL/densor/tree/master/NFC_harvester_battery_free/software/DentalSensor_StorageProject) contains the [STM32CubeIDE](https://www.st.com/en/development-tools/stm32cubeide.html)-based project is required for this. The firmware is uploaded using a [J-Link Debug probe](https://www.segger.com/products/debug-probes/j-link/) and a 10-pin header.

_Note:_ The current version of the PCB also has an [5034800800 FPC connector](https://www.molex.com/en-us/products/part-detail/5034800800) with all SWD signals which can also be used for programming.

### Memory Layout and Pinout

<img src="images/densor_bf_memmap.png" width="400">

### Using the Densor Smartphone App

With the firmware uploaded to the MCU, the Densor is ready to be uses. This can be verified with an Android-based smartphone running the Densor [smartphone app](https://github.com/TUDSSL/densor/tree/master/NFC_harvester_battery_free/software/source_ST25NFCApplication_V3_9). The app (screenshot below) is built using Android Studio and can interact with the assembled Densor PCB via NFC. Detailed instructions on how to build the app can be found [here](https://github.com/TUDSSL/densor/tree/master/NFC_harvester_battery_free/software/source_ST25NFCApplication_V3_9).

<img src="images/densor_app_screenshot.png" width="200">

### Attaching Densor to Aligners using Epoxy

Finally, the Densor PCB has to be attached to the retiners or aligners. Clear plastic vaccum formed [retainers](https://en.wikipedia.org/wiki/Retainer_(orthodontics)) are widely used in orthodontic treatment. They can easily be fabricated with the help of a dental technician.

In our Densor fabrication process, first a [dental impression](https://en.wikipedia.org/wiki/Dental_impression) of each test subject was taken using [condensation silicone](https://products.coltene.com/EN/US/products/prosthetics/c-silicones/speedex/speedex-putty) placed on a dental impression tray. The hardened impression was later used to create a [plaster model](https://bredent-group.com/wp-content/uploads/2020/06/gipse-von-hoechster-Qualitaet_000727GB-20150601.pdf) of the subject's lower jaw teeth. Next, the plaster was placed inside an [Erkoform-3d+ dental  thermoforming unit](https://www.erkodent.de/en/product/?id=521210). Then, a dental [thermoforming plate](https://www.erkodent.de/wp-content/documents/products/thermoprosp_EN.pdf) was heated to 160 degrees Celcius by Erkoform-3d+, placed over the dental plaster and vacuum sealed. After vacuum sealing, all redundant and sharp edges of the fabricated aligner were removed by the cut-off wheel. The remaining imperfections were removed with the same cut-off wheel, and finally disinfected.

We used a [food safe epoxy](https://polyestershoppen.com/epoxy/voedselveilige-epoxy-419.html) to attach the assembled Densor PCB to the retainers. The epoxy has to be carefully mixed in a 10:6 ratio of resin and hardner, and applied over the PCB with a brush. The epoxy takes a _full five days to cure completely_ and must not be used before that even though it appears to have hardened.

**Once the epoxy is fully cured, Voila! You Densor is ready to use**.

## How to Operate Densor

To use Densor, begin with the smartphone app, and fully assembled Densor.

### Starting and Stopping Densor

_Step 1._ When you hold Densor close to the smartphone such that both NFC antennas of a Densor and a smartphone align, the app will launch automatically and Densor will begin charging.

_Step 2._ The capacitor's charged voltage will be displayed in the app. While Densor theoretically supports 3.3 V maximum capacitor voltage, in practice, it reaches about 2.6 V.

_Step 3._ To configure the on-board Densor sensors, go to the settings tab. Here, you can enable specific sensors, select the sampling rate, and set an optional delayed start. Click the `Update` button to apply these settings to the sensor.

_Step 4._ The Densor will only begin measuring once the time is synced. In the `Time Sync` tab, click the `Sync` button to start the sensor from the current time. The `Wipe` button deletes all previously collected data, while `Delete` resets the cursor without overwriting previous data with zeros.

_Step 5._ To stop the sensor, switch to `Charge` mode. This will revert the sensor to showing feedback on the charging voltage reached. _Note:_ This will also reset the timestamp. Please save measurements before putting Densor to charge mode.

### Reading the Data

_Step 1._ To save the data, go to the `Memory` tab and click `Dump memory to file`. In the 'Destination folder' field, enter your desired file name, then click `OK`. The data will be saved as a `.bin` file on your phone. The file is located in the 'Downloads' folder of the internal memory.

_Step 2._ To analyse this data, use the provided scripts avaible in the [data folder](https://github.com/TUDSSL/densor/tree/master/NFC_harvester_battery_free/data) of this repository.

_Step 3._ If the [experiment master](https://github.com/TUDSSL/densor/blob/master/NFC_harvester_battery_free/data/experiments/experiment_master_ui.py) script was used to generate the data as per a protocol, then place the generated `.bin` file in the [data dumps folder](https://github.com/TUDSSL/densor/tree/master/NFC_harvester_battery_free/data/experiments/data_dumps), and view the results with the [plot_labelled.py](https://github.com/TUDSSL/densor/blob/master/NFC_harvester_battery_free/data/experiments/plot_labeled.py) script. Make sure to use the [environment](https://github.com/TUDSSL/densor/blob/master/NFC_harvester_battery_free/data/environment.yml) provided.

## Collecting New Data

Data collected using Densor can be labelled and stored using the scripts in the [data folder](https://github.com/TUDSSL/densor/tree/master/NFC_harvester_battery_free/data/experiments).

1. **Using a predefined experiment protocol:** You can use a set of [existing experiment protocols](https://github.com/TUDSSL/densor/tree/master/NFC_harvester_battery_free/data/experiments/experiment_protocols) or create a new `.json` file to decide the tasks that will be performed while labeling data collected by Densor.
2. **Executing an experiment:** Run the [`experiment_master_ui.py`](https://github.com/TUDSSL/densor/blob/master/NFC_harvester_battery_free/data/experiments/experiment_master_ui.py) file to launch the instructing script. Perform the actions as instructed by the script for the prescribed duration.

<img src="images/ui_screenshot.png" width="600">

3. **Save the data:** Save the data using the smartphone app with the name prompted by the script to a `.bin` file and place it in the [data_dumps](https://github.com/TUDSSL/densor/tree/master/NFC_harvester_battery_free/data/experiments/data_dumps) folder.
4. **Label and view the data:** Run the `data_labeller.py` script to label the data for further machine learning tasks, or `plot_labelled.py` file to view the data.

## Frequently Asked Questions

**1. How long does it take to charge Densor?**

It takes approximately a minute to charge Densor using a smartphone. The exact charging time depends on how well the antennas of Densor and the smartphone align. The exact charging voltage of the capacitors can be read on the app for feedback.

**2. How long does Densor run when fully charged?**

Densor's lifetime depends on the sampling rate and number of capacitors used. For example, when using two capacitors and sampling once every two minutes Densor lasts for about 7 hours on a single charge. To increase the lifetime, more capacitors can be added. This will however change the charging behavior as well.

**3. Is Densor comfortable to wear?**

In our opinion, the experience of using Densor is no different than using retainers. Thus, if using retainers/aligners anyway, Densor should cause no additional discomfort beyond usinh the retainer.

**4. Will the mouth opening detection based on light intensity work when sleeping in the dark?**

Unfortunately, the mouth opening detection only works when there is sufficient ambient light. We are investigating ways to increase sensitivity for dark environments.

**5. Can I buy a Densor?**

Densor can be built with the design files in _this repository_. We desire Densor to be a free open source platform that can be rebuilt and expanded by everyone. If you wish to use (or expand) for your own research, please do not hesitate to contact us. We are happy to assist in reproduction efforts.

## How to Contribute to this Project

We look forward to your contributions, improvements, additions and changes. Please follow the standard [GitHub flow](https://docs.github.com/en/free-pro-team@latest/github/collaborating-with-issues-and-pull-requests/github-flow) for code contributions. In macro terms this means the following.

1. [Fork the `master` branch](https://docs.github.com/en/free-pro-team@latest/github/getting-started-with-github/fork-a-repo#keep-your-fork-synced)  of this repository; make sure that your fork will be [up to date](https://docs.github.com/en/free-pro-team@latest/github/getting-started-with-github/fork-a-repo#keep-your-fork-synced) with the latest `master` branch.
1. Create an issue [here](https://github.com/TUDSSL/densor/issues) with a new feature or a bug report.
1. Perform changes on your local branch and [push them to your forked clone](https://docs.github.com/en/free-pro-team@latest/github/collaborating-with-issues-and-pull-requests/merging-an-upstream-repository-into-your-fork).
1. Create a [pull request](https://docs.github.com/en/free-pro-team@latest/github/collaborating-with-issues-and-pull-requests/creating-a-pull-request) referencing the issue it covers and wait for our response.

### List of Known Issues

List of all known issues is listed in the [Issues](https://github.com/TUDSSL/densor/issues) list of this project. If you found a bug or you would like to enhance Densor with new functionalities: [please contribute](#How-to-Contribute-to-this-Project)! We look forward to your additions.

## How to Cite This Work

The results of this project have been published in a peer-reviewed academic publication (from which certain technical figures and text in this file originate). Details of the publication are as follows.

* **Authors and the project team:** [Vivian Dsouza](https://www.linkedin.com/in/dsouzavivian), [Jeffrey Pronk](https://www.linkedin.com/in/jeffrey-p-ba3225332), [Christian Peppelman](https://www.linkedin.com/in/peppelmanc), [Víctor Ignacio Madariaga](https://www.linkedin.com/in/vignaciomr), [Tatiana Pereira-Cenci](https://www.linkedin.com/in/tatiana-pereira-cenci-78813118/), [Bas Loomans](https://www.linkedin.com/in/bas-loomans-370ba11/), [Przemysław Pawełczak](http://www.pawelczak.net/)
* **Publication title:** _Densor: An Intraoral Battery-Free Sensing Platform_
* **Pulication venue:** [Proceedings of the ACM on Interactive, Mobile, Wearable and Ubiquitous Technologies, Volume 8, Issue 4, November 2024](https://dl.acm.org/toc/imwut/2024/8/4)
* **Link to publication:** https://dl.acm.org/doi/10.1145/3699746 (Open Access)

To cite this publication please use the following BiBTeX entry.

```
@article{dsouza:imwut:2024:densor,
  title = {Densor: An Intraoral Battery-Free Sensing Platform},
  author = {Vivian {Dsouza} and Jeffrey {Pronk} and Christian {Peppelman} and V\'{i}ctor Ignacio {Madariaga} and Tatiana {Pereira-Cenci} and Bas {Loomans} and Przemys{\l}aw {Pawe{\l}czak}},
  journal = {Proc. ACM Interact. Mob. Wearable Ubiquitous Technol.},
  volume = {8},
  number = {4},
  pages = {191:1--191:30},
  year = {2024},
  publisher = {ACM}
}
```

## Acknowledgements

We thank [Tofik Babayev](https://www.linkedin.com/in/tofik-babayev-36aa561a0) from [TofDent](https://www.tofdent.nl), The Hague, The Netherlands, for help in fabricating all versions of the Densor. We also thank [Giuseppe Deininger](https://www.linkedin.com/in/giuseppe-deininger-564a40153) and [Jakub Patałuch](https://www.linkedin.com/in/jakubpat) for contribution to the development of Densor at the initial states of the development. We also thank [Lennart Klaver](https://www.linkedin.com/in/lennart-klaver-19010945) for first attempts in designing intraoral sensor hardware, [Jasper de Winkel](https://www.linkedin.com/in/jdewinkel) for system design consulting and [Izabela Grudzińska](https://www.linkedin.com/in/grudzinskaizabela) for dentistry consulting and for the inspiration of this project. The study was partly funded by the [ORANGE-FORCE](https://www.nivel.nl/nl/project/orange-force-mond-en-tandheelkundige-zorg-voor-ouderen) project co-funded by the [PPP allowance](https://www.health-holland.com/public-private-partnerships) made available by [Health Holland](https://www.health-holland.com), [Top Sector Life Sciences and Health](https://www.academictransfer.com/en/employer/Health-Holland/).

<a href="https://www.tudelft.nl"><img src="https://github.com/TUDSSL/DIPS/blob/master/images/tudelft_logo.png" width="300px"></a><a href="https://www.radboudumc.nl/en/about-radboudumc"><img src="https://github.com/TUDSSL/densor/blob/master/NFC_harvester_battery_free/images/radboudumc-logo-en-us.svg" width="300px"></a> 

## Copyright

Copyright (C) 2024 TU Delft Embedded Systems Group/[Sustainable Systems Laboratory](https://github.com/TUDSSL).

MIT License or otherwise specified. See [license](https://github.com/TUDSSL/densor/blob/master/LICENSE.txt) file for details.
