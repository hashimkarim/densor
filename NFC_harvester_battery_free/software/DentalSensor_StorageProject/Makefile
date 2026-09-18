PREFIX ?= arm-none-eabi-
CC = $(PREFIX)gcc
BUILD ?= build/r1
OPT ?= -Os
CPPFLAGS = -DUSE_HAL_DRIVER -DSTM32L021xx -ICore/Inc \
 -IDrivers/STM32L0xx_HAL_Driver/Inc -IDrivers/STM32L0xx_HAL_Driver/Inc/Legacy \
 -IDrivers/CMSIS/Device/ST/STM32L0xx/Include -IDrivers/CMSIS/Include
CFLAGS = -mcpu=cortex-m0plus -mthumb -std=gnu11 $(OPT) -g3 \
 -ffunction-sections -fdata-sections -fstack-usage -Wall -Wextra
LDFLAGS = -mcpu=cortex-m0plus -mthumb --specs=nano.specs --specs=nosys.specs \
 -TSTM32L021F4UX_FLASH.ld -Wl,--gc-sections,-Map=$(BUILD)/densor-r1.map,--print-memory-usage
SOURCES = $(wildcard Core/Src/*.c) $(wildcard Drivers/STM32L0xx_HAL_Driver/Src/*.c)
OBJECTS = $(addprefix $(BUILD)/,$(SOURCES:.c=.o)) $(BUILD)/startup.o

.PHONY: all clean
all: $(BUILD)/densor-r1.elf $(BUILD)/densor-r1.bin $(BUILD)/densor-r1.hex
$(BUILD)/densor-r1.elf: $(OBJECTS) STM32L021F4UX_FLASH.ld
	$(CC) $(OBJECTS) $(LDFLAGS) -o $@
$(BUILD)/densor-r1.bin: $(BUILD)/densor-r1.elf
	$(PREFIX)objcopy -O binary $< $@
$(BUILD)/densor-r1.hex: $(BUILD)/densor-r1.elf
	$(PREFIX)objcopy -O ihex $< $@
$(BUILD)/%.o: %.c
	@mkdir -p $(dir $@)
	$(CC) $(CPPFLAGS) $(CFLAGS) -MMD -MP -c $< -o $@
$(BUILD)/startup.o: Core/Startup/startup_stm32l021f4ux.s
	@mkdir -p $(dir $@)
	$(CC) -mcpu=cortex-m0plus -mthumb -g3 -c $< -o $@
clean:
	rm -rf $(BUILD)
-include $(OBJECTS:.o=.d)
