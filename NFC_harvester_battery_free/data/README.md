# Densor - Data 

## About the project 

This repository contains the design of Densor: an intraoral, actively powered, battery-free platform featuring multi-modal sensors and an extended lifespan. The repository consists of all components that make Densor:
- [Hardware](https://github.com/TUDSSL/densor/tree/master/NFC_harvester_battery_free/hardware)
- [Densor Firmware](https://github.com/TUDSSL/densor/tree/master/NFC_harvester_battery_free/software/DentalSensor_StorageProject)
- [Android app](https://github.com/TUDSSL/densor/tree/master/NFC_harvester_battery_free/software/source_ST25NFCApplication_V3_9)
- [Experiment setup and data collected](https://github.com/TUDSSL/densor/tree/master/NFC_harvester_battery_free/data/experiments)

This folder contains all the scripts to collect, process and analyse the data using Densor, as well as the data collected itself.

## Folder layout

- [Synthetic multirate dataset](synthetic_multirate/README.md): simulated acceleration, light, and two temperature channels at 100 Hz, with noise-free curves and event labels for sampling comparisons.

- [Charging:](https://github.com/TUDSSL/densor/tree/master/NFC_harvester_battery_free/data/charging)  contains the data traces of how densor charges with a smartphone. 
- [Earpiece:](https://github.com/TUDSSL/densor/tree/master/NFC_harvester_battery_free/data/earpiece) contains the scripts used to collect data from the ear piece for the experiments that compare Densor to a similar earable platform.
- [Experiments:](https://github.com/TUDSSL/densor/tree/master/NFC_harvester_battery_free/data/experiments) contains all scripts and data used for the machine learning experiments.
- [Retainer:](https://github.com/TUDSSL/densor/tree/master/NFC_harvester_battery_free/data/retainer) contains all scripts and data collected from the Densor that are not related to the machine learning experiments.

## Setting Up the Environment

To set up the Conda environment from the provided `.yml` file, follow these steps:

### 1. Ensure Conda is Installed

Make sure you have Conda installed on your system. If you don't have it installed, you can download and install it from the [official Anaconda website](https://www.anaconda.com/products/distribution) or by using [Miniconda](https://docs.conda.io/en/latest/miniconda.html) for a lighter version.

### 2. Clone the Repository

Clone this repository to your local machine if you haven't done so already:

```bash
git clone https://github.com/TUDSSL/densor.git
cd densor
```

### 3. Create the Environment from the .yml File

Use the following command to create a new environment from the environment.yml file:

```bash
conda env create -f environment.yml
```

### 4. Activating the environment

Use the following command to activate the environment:

```bash
conda activate densor
```

## Description of how figures in the paper were produced

1. **Figure 5: Charging characteristics:** Run the `plot_charging_traces.py' script

```bash
cd NFC_harvester_battery_free/data/charging/
python3 plot_charging_traces.py
```   

2. **Figure 6 (a,b,c): Power and Lifetime** Run the `math_model_life.py' script

Set the below variables to True, based on the desired output
```
PLOT_CAPS = True
PLOT_CURRENT = False
PLOT_START_VOLTAGE = False
```

```bash
cd NFC_harvester_battery_free/data/charging/
python3 math_model_life.py
```   


3. **Figure 7: Example data from all sensors** Run the `plot_pretty_manually.py` script

```bash
cd NFC_harvester_battery_free/data/retainer/
python3 plot_pretty_manually.py
```
4. **Figure 10: Distribution of temperature and light intensity** Run the `data_ml.py` script with variable `plot_box_plots = True`
   
```bash
cd NFC_harvester_battery_free/data/experiments
python3 data_ml.py
```
5. **Figure 11 (a,b,c): Data from Densor and Earable** Run the `plot_ear_mouth_example_trace.py' script with arguments as explained [here](https://github.com/TUDSSL/densor/tree/master/NFC_harvester_battery_free/data/experiments).
6. **Figure 12: ROC** Run the `data_ml.py` script with variable `plot_roc_curve = True`

```bash
cd NFC_harvester_battery_free/data/experiments
python3 data_ml.py
```
7. **Figure 13: Example data from sleep** Run the `plot_pretty_sleep_manually.py` script

```bash
cd NFC_harvester_battery_free/data/retainer/
python3 plot_pretty_sleep_manually.py
```

## Copyright

<a href="https://www.tudelft.nl"><img src="https://github.com/TUDSSL/DIPS/blob/master/images/tudelft_logo.png" width="300px"></a><a href="https://www.radboudumc.nl/en/about-radboudumc"><img src="https://github.com/TUDSSL/densor/blob/master/NFC_harvester_battery_free/images/radboudumc-logo-en-us.svg" width="250px"></a> 

Copyright (C) 2024 TU Delft Embedded Systems Group/Sustainable Systems Laboratory.

MIT Licence or otherwise specified. See [license](https://github.com/TUDSSL/ENGAGE/blob/master/LICENSE) file for details.
