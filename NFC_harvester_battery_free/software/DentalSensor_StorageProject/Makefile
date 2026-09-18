PREFIX ?= arm-none-eabi-
CC = $(PREFIX)gcc
ifneq ($(origin REVISION),undefined)
REVISION_KEY := $(subst R,r,$(strip $(REVISION)))
ifneq ($(words $(REVISION_KEY)),1)
$(error REVISION must select exactly one of R2a R2b R3a R3b)
endif
ifeq ($(filter $(REVISION_KEY),r2a r2b r3a r3b),)
$(error REVISION must be R2a R2b R3a or R3b)
endif
REVISION_STORAGE := $(if $(filter r3a r3b,$(REVISION_KEY)),partitioned,shared)
REVISION_TIMING := $(if $(filter r2a r3a,$(REVISION_KEY)),fsm,rtc)
STORAGE ?= $(REVISION_STORAGE)
TIMING ?= $(REVISION_TIMING)
ifneq ($(STORAGE)-$(TIMING),$(REVISION_STORAGE)-$(REVISION_TIMING))
$(error REVISION conflicts with STORAGE or TIMING)
endif
endif
STORAGE ?= partitioned
TIMING ?= fsm
ifneq ($(words $(STORAGE)),1)
$(error STORAGE must select exactly one implementation)
endif
ifneq ($(words $(TIMING)),1)
$(error TIMING must select exactly one implementation)
endif
ifeq ($(filter $(STORAGE),partitioned shared),)
$(error STORAGE must be partitioned or shared)
endif
ifeq ($(filter $(TIMING),fsm rtc),)
$(error TIMING must be fsm or rtc)
endif
VARIANT := $(STORAGE)-$(TIMING)
REVISION_NAME := $(if $(filter shared,$(STORAGE)),r2,r3)$(if $(filter fsm,$(TIMING)),a,b)
BUILD := build/$(VARIANT)
NAME := densor-$(REVISION_NAME)-$(VARIANT)
STORAGE_ID := $(if $(filter partitioned,$(STORAGE)),1,2)
TIMING_ID := $(if $(filter fsm,$(TIMING)),1,2)
OPT ?= -Os
override CPPFLAGS += -DDENSOR_MULTIRATE -DDENSOR_STORAGE=$(STORAGE_ID) -DDENSOR_TIMING=$(TIMING_ID) \
 -DUSE_HAL_DRIVER -DSTM32L021xx -ICore/Inc \
 -IDrivers/STM32L0xx_HAL_Driver/Inc -IDrivers/STM32L0xx_HAL_Driver/Inc/Legacy \
 -IDrivers/CMSIS/Device/ST/STM32L0xx/Include -IDrivers/CMSIS/Include
override CFLAGS += -mcpu=cortex-m0plus -mthumb -std=gnu11 $(OPT) -g3 \
 -flto -ffunction-sections -fdata-sections -fstack-usage -Wall -Wextra
override LDFLAGS += $(OPT) -flto -ffunction-sections -fdata-sections -fstack-usage -mcpu=cortex-m0plus -mthumb --specs=nano.specs --specs=nosys.specs \
 -TSTM32L021F4UX_FLASH.ld -Wl,--gc-sections,-Map=$(BUILD)/$(NAME).map,--print-memory-usage
CORE_SOURCES = main densor_board densor_r1 densor_multirate tmp119 stm32l0xx_hal_msp \
 stm32l0xx_it syscalls sysmem system_stm32l0xx densor_logger_$(STORAGE) densor_scheduler_$(TIMING)
HAL_SOURCES = stm32l0xx_hal stm32l0xx_hal_adc stm32l0xx_hal_adc_ex stm32l0xx_hal_cortex \
 stm32l0xx_hal_dma stm32l0xx_hal_exti stm32l0xx_hal_flash stm32l0xx_hal_flash_ex \
 stm32l0xx_hal_flash_ramfunc stm32l0xx_hal_gpio stm32l0xx_hal_i2c stm32l0xx_hal_i2c_ex \
 stm32l0xx_hal_pwr stm32l0xx_hal_pwr_ex stm32l0xx_hal_rcc stm32l0xx_hal_rcc_ex \
 stm32l0xx_hal_tim stm32l0xx_hal_tim_ex
SOURCES = $(addprefix Core/Src/,$(addsuffix .c,$(CORE_SOURCES))) \
 $(addprefix Drivers/STM32L0xx_HAL_Driver/Src/,$(addsuffix .c,$(HAL_SOURCES)))
OBJECTS = $(addprefix $(BUILD)/,$(SOURCES:.c=.o)) $(BUILD)/startup.o
.PHONY: all clean FORCE
all: $(BUILD)/$(NAME).elf $(BUILD)/$(NAME).bin $(BUILD)/$(NAME).hex $(BUILD)/$(NAME)-manifest.json
# Compare content, preserving mtime when flags have not changed.
$(BUILD)/flags: FORCE
	@mkdir -p $(BUILD)
	@printf '%s\n' '$(CC)' '$(CPPFLAGS)' '$(CFLAGS)' '$(LDFLAGS)' '$(SOURCES)' > $@.tmp
	@cmp -s $@.tmp $@ || cp $@.tmp $@
	@rm -f $@.tmp
$(BUILD)/$(NAME).elf: $(OBJECTS) STM32L021F4UX_FLASH.ld
	@rm -f $(BUILD)/*.ltrans.su
	$(CC) $(OBJECTS) $(LDFLAGS) -o $@
$(BUILD)/$(NAME).bin: $(BUILD)/$(NAME).elf
	$(PREFIX)objcopy -O binary $< $@
$(BUILD)/$(NAME).hex: $(BUILD)/$(NAME).elf
	$(PREFIX)objcopy -O ihex $< $@
$(BUILD)/$(NAME)-manifest.json: $(BUILD)/$(NAME).elf $(BUILD)/$(NAME).bin $(BUILD)/$(NAME).hex FORCE
	python3 ../tools/multirate_manifest.py $(BUILD)/$(NAME).elf $(STORAGE) $(TIMING)
$(BUILD)/%.o: %.c $(BUILD)/flags
	@mkdir -p $(dir $@)
	@rm -f $(@:.o=.su)
	$(CC) $(CPPFLAGS) $(CFLAGS) -MMD -MP -c $< -o $@
$(BUILD)/startup.o: Core/Startup/startup_stm32l021f4ux.s $(BUILD)/flags
	@mkdir -p $(dir $@)
	$(CC) -mcpu=cortex-m0plus -mthumb -g3 -c $< -o $@
clean:
	rm -rf $(BUILD)
-include $(OBJECTS:.o=.d)
