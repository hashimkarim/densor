# Summary

This conversation was used to add a debug mode to the Android app so I could test sampling-interval settings without a Densor board. It supports importing EEPROM `.bin` dumps, changing settings in simulated tag memory, verifying them by reading them back, and exporting and reopening the modified dumps. The workflow was tested on the Samsung S21 FE and 13 automated tests passed; actual sensor sampling timing still requires a Densor board.
