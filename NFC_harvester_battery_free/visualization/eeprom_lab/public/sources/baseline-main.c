/* USER CODE BEGIN Header */
/**
  ******************************************************************************
  * @file           : main.c
  * @brief          : Firmware for the Densor project.
  ******************************************************************************
  */
/* USER CODE END Header */
/* Includes ------------------------------------------------------------------*/
#include "main.h"

/* Private includes ----------------------------------------------------------*/
/* USER CODE BEGIN Includes */
#include <stdbool.h>

/* USER CODE END Includes */

/* Private typedef -----------------------------------------------------------*/
/* USER CODE BEGIN PTD */

/* USER CODE END PTD */

/* Private define ------------------------------------------------------------*/
/* USER CODE BEGIN PD */
#define NFC_ADDR 					0xA6
#define ACCEL_ADDR 					0x32
#define DEBUG_TERMINAL				0xAA
#define AM1805_ADDR 				0xD2

#define ACCEL_TEMP_BASE				0x0D
#define ACCEL_CONTROL1				0x20
#define ACCEL_OUT_X_L				0x28

#define RTC_SECONDS                 0x01
#define RTC_SECONDS_ALARM           0x09
#define RTC_MINUTES					0x02
#define RTC_MINUTES_ALARM			0x0A
#define RTC_OSCILLATOR_CONTROL      0x1C
#define RTC_CONFIGURATION_KEY       0x1F
#define RTC_CONTROL2                0x11
#define RTC_INTMASK                 0x12
#define RTC_COUNTDOWN_TIMER_CONTROL 0x18
#define RTC_STATUS                  0x0F
#define RTC_SLEEP_CONTROL			0x17

#define SENSOR_ENABLE_REG			0x00
#define TIMESTAMP_REG				0x01
#define RTC_INTERVAL_REG			0x05
#define STARTUP_DELAY_REG			0x06
#define MEM_POINTER_REG				0x07

#define MEMORY_SIZE 				8192

#define VREF_CAL_ADDR				((uint16_t*) (0x1FF80078)) // Datasheet p.51
#define VREF_CAL_VDDA				3000 // Datasheet p.51

#define DEBUG_TEST 					false
#define CHARGE_MODE					true

/* USER CODE END PD */

/* Private macro -------------------------------------------------------------*/
/* USER CODE BEGIN PM */

/* USER CODE END PM */

/* Private variables ---------------------------------------------------------*/
ADC_HandleTypeDef hadc;

I2C_HandleTypeDef hi2c1;

/* USER CODE BEGIN PV */

// Pointer to current memory position to write to.
uint16_t memPointer = 9;
// Zero value. Used for wiping memory.
uint8_t zero = 0;
// Holder for the interval set on the RTC. Default is 10 seconds.
uint8_t rtcInterval = 0x10;

// Holder for the value in the seconds register on the RTC at boot.
uint8_t bootSeconds = 0x60;
// Holder for the value in the minutes register on the RTC at boot.
uint8_t bootMinutes = 0x60;
// Holder for the startup delay.
uint8_t startupDelay = 0x00;

// Holder for boolean that determines which clock source the RTC should use. If true uses internal RC clock, if false, uses external XT crystal.
bool rcEnable = true;
// Holders for booleans that determine which sensors are turned on and off.
bool tempEnable = false;
bool moistureEnable = false;
bool pdEnable = false;
bool accelEnable = false;

/* USER CODE END PV */

/* Private function prototypes -----------------------------------------------*/
void SystemClock_Config(void);
static void MX_GPIO_Init(void);
static void MX_I2C1_Init(void);
static void MX_ADC_Init(void);
/* USER CODE BEGIN PFP */

/* USER CODE END PFP */

/* Private user code ---------------------------------------------------------*/
/* USER CODE BEGIN 0 */

/**
 * @brief  Overwrite of the _write function. Outputs all calls to _write on the I2C bus to the DEBUG_TERMINAL address. Returns the length of the string to write.
 *
 * @param file: File descriptor of file into which data is written.
 * @param ptr: Pointer to data to be written.
 * @param len: Length in bytes of data to be written.
 * @retval int
 */
int _write(int file, char *ptr, int len)
{
  (void)file;
  int DataIdx;

  HAL_I2C_Master_Transmit(&hi2c1, DEBUG_TERMINAL, (uint8_t *) ptr, len, HAL_MAX_DELAY);
  return len;
}

// Only set to true, will print debug characters to an external debugger on i2c address DEBUG_TERMINAL. Otherwise does nothing.
#if DEBUG_TEST
/**
 * @brief  Writes a single given character to the DEBUG_TERMINAL through I2C.
 *
 * @param  out: The character to write
 * @retval None
 */
HAL_StatusTypeDef printDebugChar(uint8_t out) {
	HAL_I2C_Master_Transmit(&hi2c1, DEBUG_TERMINAL, &out, 1, HAL_MAX_DELAY);
}

/**
 * @brief  Writes a given character array of given size to the DEBUG_TERMINAL through I2C.
 *
 * @param  out: The character array to write
 * @param  size: The size of the character array
 * @retval None
 */
HAL_StatusTypeDef printDebug(uint8_t* out, int size) {
	HAL_I2C_Master_Transmit(&hi2c1, DEBUG_TERMINAL, out, size, HAL_MAX_DELAY);
}
#else

/**
 * @brief  Placeholder in case debug mode is disabled
 *
 * @param  out: The character to write. Ignored
 * @retval HAL_OK
 */
HAL_StatusTypeDef printDebugChar(uint8_t out) {
	return HAL_OK;
}

/**
 * @brief  Placeholder in case debug mode is disabled
 *
 * @param  out: The character to write. Ignored
 * @retval HAL_OK
 */
HAL_StatusTypeDef printDebug(uint8_t* out, int size) {
	return HAL_OK;
}
#endif

/**
 * @brief  Write a given amount of 0's to the NFC tag, starting from address 0.
 *
 * @param  size: Amount of 0's to write
 * @retval None
 */
void wipeMemory(int size) {
	for (uint16_t addr = 0; addr < size; addr++) {
		  HAL_I2C_Mem_Write(&hi2c1, NFC_ADDR, (uint16_t) addr, 2, &zero, 1, 1000);
	  	  HAL_Delay(10);
	  }
}

/**
 * @brief  Write a given array of bytes to the NFC tag, starting at a given memory address.
 *         Ensures that the NFC tag is done writing before returning.
 *
 * @param  data: Pointer to the byte array to be written
 * @param  size: The amount bytes to write
 * @param  memAddr: The memory-address of the first byte
 * @retval HAL_StatusTypeDef
 */
HAL_StatusTypeDef writeMemory(uint8_t* data, int size, uint16_t memAddr) {
	HAL_StatusTypeDef status = HAL_I2C_Mem_Write(&hi2c1, NFC_ADDR, memAddr, I2C_MEMADD_SIZE_16BIT, data, size, HAL_MAX_DELAY);
	HAL_Delay(2);

	uint8_t count = 0;
	while(HAL_I2C_IsDeviceReady(&hi2c1, NFC_ADDR, 1, 5) != HAL_OK && count < 50) {
		HAL_Delay(5);
		count++;
	}

	return status;
}

/**
 * @brief  Write a given array of bytes to the data section of the NFC tag.
 *         Automatically selects the end of the data section increments the memory pointer (locally, not on the NFC tag).
 *
 * @param  data: Pointer to the byte array to be written
 * @param  size: The amount of bytes to write
 * @retval HAL_StatusTypeDef
 */
HAL_StatusTypeDef writeDataMemory(uint8_t* data, int size) {
	if (memPointer + size >= MEMORY_SIZE || memPointer < 9) {
		return 1;
	}

	HAL_StatusTypeDef status = writeMemory(data, size, memPointer);
	memPointer += size;

	return status;
}

/**
 * @brief  Saves the local memory pointer to the NFC tag.
 *
 * @retval HAL_StatusTypeDef
 */
HAL_StatusTypeDef saveMemPointer() {
	if (memPointer >= 9 && memPointer <= MEMORY_SIZE) {
		uint8_t buff[2] = {memPointer, (memPointer >> 8)};
		return writeMemory(buff, 2, MEM_POINTER_REG);
	}

	return HAL_ERROR;

}

/**
 * @brief  Scans the I2C bus for devices. Writes all addresses that respond to the NFC tag.
 *
 * @retval 0
 */
int I2CDeviceScan() {
	uint8_t final_addr = 0;

	HAL_StatusTypeDef resNfc = HAL_I2C_IsDeviceReady(&hi2c1, NFC_ADDR, 3, 5);
	HAL_StatusTypeDef recRtc = HAL_I2C_IsDeviceReady(&hi2c1, AM1805_ADDR, 3, 5);
	HAL_StatusTypeDef resAccel = HAL_I2C_IsDeviceReady(&hi2c1, ACCEL_ADDR, 3, 5);

	for (uint8_t addr = 0; addr < 128; addr++) {
		  HAL_StatusTypeDef res = HAL_I2C_IsDeviceReady(&hi2c1, (uint16_t)(addr<<1), 3, 5);
		  if (res == HAL_OK) {
			  final_addr = (addr<<1);
			  HAL_I2C_Mem_Write(&hi2c1, NFC_ADDR, memPointer, 2, &final_addr, 1, 1000);
			  memPointer += 1;
		  }

		  HAL_Delay(10);
	  }

	  HAL_I2C_Mem_Write(&hi2c1, NFC_ADDR, memPointer, 2, &zero, 1, 1000);
	  memPointer += 1;

	  return 0;
}

/**
 * @brief  Enables all sensors that should be enabled
 *
 * @retval None
 */
void enableSensors() {
	if (accelEnable || tempEnable) {
		uint8_t enableAccel = 0b00010000; //low power mode, lowest frequency, 12 bit
		enableAccel = 0x3B;
		HAL_I2C_Mem_Write(&hi2c1, ACCEL_ADDR, ACCEL_CONTROL1, I2C_MEMADD_SIZE_8BIT, &enableAccel, 1, HAL_MAX_DELAY);
		enableAccel = 0x03;
		HAL_I2C_Mem_Write(&hi2c1, ACCEL_ADDR, 0x22, I2C_MEMADD_SIZE_8BIT, &enableAccel, 1, HAL_MAX_DELAY);
	} else {
		HAL_I2C_Mem_Write(&hi2c1, ACCEL_ADDR, ACCEL_CONTROL1, I2C_MEMADD_SIZE_8BIT, &zero, 1, HAL_MAX_DELAY);
	}

	if (pdEnable) {
		HAL_ADCEx_Calibration_Start(&hadc, ADC_SINGLE_ENDED);
	}

}

/**
 * @brief  Set the RTC to sleep for the given interval.
 *
 * @param  interval: The sleep interval in BCD. The first bit determines if the interval is in seconds (0) or minutes (1)
 * @retval None
 */
void initRtcSleep(uint8_t interval) {

	// Setup the RTC into alarm mode.
	uint8_t value = 0b10100001; // 0xA1
	HAL_I2C_Mem_Write(&hi2c1, AM1805_ADDR, RTC_CONFIGURATION_KEY, I2C_MEMADD_SIZE_8BIT, &value, 1, 100);

	value = rcEnable ? 0b10000100 : 0b00000100;
	 // 0x04 for XT -> changed to 0x84 for RC
	HAL_I2C_Mem_Write(&hi2c1, AM1805_ADDR, RTC_OSCILLATOR_CONTROL, I2C_MEMADD_SIZE_8BIT, &value, 1, 100);

	uint8_t timeReg;
	uint8_t alarmReg;

	bool minutesSelected = interval & 0b10000000;

	// Determine the alarm values that should be set.
	if (minutesSelected) {
		// Minutes interval
		timeReg = RTC_MINUTES;
		alarmReg = RTC_MINUTES_ALARM;
		value = bootMinutes;

	} else {
		// Seconds interval
		timeReg = RTC_SECONDS;
		alarmReg = RTC_SECONDS_ALARM;
		value = bootSeconds;

	}

	// Set the alarm value.
	if (value > 0x59) {
		HAL_I2C_Mem_Read(&hi2c1, AM1805_ADDR, timeReg, I2C_MEMADD_SIZE_8BIT, &value, 1, 100);
	}

	// Check if both value and interval are in BCD and are not out of bounds.
	if (((value & 0b11110000) >> 4) >= 6 || (value & 0b00001111) > 9) {
		value = 0x00;
	}
	if (((interval & 0b01110000) >> 4) >= 6 || (interval & 0b00001111) > 9) {
		interval = 0x00;
	}

	if (((value & 0b00001111) + (interval & 0b00001111)) > 9) value += 6; // If the ones overflow, increment by 6 to get back to actual value!
	value += (interval & 0b01111111);
	if (value > 0x59) value = value - 0x60; // Changed from setting to 0 to setting to decrementing by 0x59. This will keep functionality as expected!

	// Check if value is still BCD after calculation.
	if (((value & 0b11110000) >> 4) >= 6 || (value & 0b00001111) > 9) {
		value = 0x00;
	}

	// Set and enable alarm. Should turn off MCU.
	HAL_I2C_Mem_Write(&hi2c1, AM1805_ADDR, alarmReg, I2C_MEMADD_SIZE_8BIT, &value, 1, 100);

	value = 0b00111000; // 0x38
	HAL_I2C_Mem_Write(&hi2c1, AM1805_ADDR, RTC_CONTROL2, I2C_MEMADD_SIZE_8BIT, &value, 1, 100);

	value = 0b10000100; // 0x84
	HAL_I2C_Mem_Write(&hi2c1, AM1805_ADDR, RTC_INTMASK, I2C_MEMADD_SIZE_8BIT, &value, 1, 100);

	value = minutesSelected ? 0b00010111 : 0b00011011; // 0x1B
	HAL_I2C_Mem_Write(&hi2c1, AM1805_ADDR, RTC_COUNTDOWN_TIMER_CONTROL, I2C_MEMADD_SIZE_8BIT, &value, 1, 100);

	value = 0;
	HAL_I2C_Mem_Write(&hi2c1, AM1805_ADDR, RTC_STATUS, I2C_MEMADD_SIZE_8BIT, &value, 1, 100);

	value = 0b10000000; // 0x80
	HAL_I2C_Mem_Write(&hi2c1, AM1805_ADDR, RTC_SLEEP_CONTROL, I2C_MEMADD_SIZE_8BIT, &value, 1, 100);
}

/**
 * @brief  Tries to trigger a sleep on the RTC for the given interval. If it fails 10 times, resets the MCU.
 *
 * @retval None
 */
void rtcSleep(uint8_t interval, bool saveMP) {
	// Only save memory pointer when requested. Default is false.
	if (saveMP) {
		saveMemPointer();
	}


	uint8_t stop_count = 0;

	// Try to stop the MCU through the RTC 10 times.
	while (stop_count < 10) {
		initRtcSleep(interval);
		HAL_Delay(50);
		stop_count++;
	}

	// If stopping through the RTC failed, reset the MCU.
	NVIC_SystemReset();
}

/* USER CODE END 0 */

/**
  * @brief  The application entry point.
  * @retval int
  */
int main(void)
{
  /* USER CODE BEGIN 1 */

  /* USER CODE END 1 */

  /* MCU Configuration--------------------------------------------------------*/

  /* Reset of all peripherals, Initializes the Flash interface and the Systick. */
  HAL_Init();

  /* USER CODE BEGIN Init */

  /* USER CODE END Init */

  /* Configure the system clock */
  SystemClock_Config();

  /* USER CODE BEGIN SysInit */

  /* USER CODE END SysInit */

  /* Initialize all configured peripherals */
  MX_GPIO_Init();
  MX_I2C1_Init();
  MX_ADC_Init();
  /* USER CODE BEGIN 2 */

//  I2CDeviceScan();
  // Wait for the RTC to start. Necessary when this is the first time the entire systems starts.
  HAL_Delay(5);

  // Retrieve RTC seconds and minutes at boot.
  HAL_StatusTypeDef status = HAL_I2C_Mem_Read(&hi2c1, AM1805_ADDR, RTC_SECONDS, I2C_MEMADD_SIZE_8BIT, &bootSeconds, 1, 100);
  status = HAL_I2C_Mem_Read(&hi2c1, AM1805_ADDR, RTC_MINUTES, I2C_MEMADD_SIZE_8BIT, &bootMinutes, 1, 100);

  // Check the first two bytes of the timestamp. If < 3400, retrieve VDDA, store it in the first two ts bytes and rtcSleep for 5 seconds.
#if CHARGE_MODE
  uint8_t ts_buff[2] = {};
  status = HAL_I2C_Mem_Read(&hi2c1, NFC_ADDR, TIMESTAMP_REG, I2C_MEMADD_SIZE_16BIT, ts_buff, 2, HAL_MAX_DELAY);
  uint16_t timestamp  = ((uint32_t) ts_buff[0] << 8) | ts_buff[1];

  if (timestamp < 3400) {
	  HAL_ADCEx_Calibration_Start(&hadc, ADC_SINGLE_ENDED);

	  HAL_ADC_Start(&hadc);
	  HAL_ADC_PollForConversion(&hadc, HAL_MAX_DELAY);
	  HAL_ADC_GetValue(&hadc);

	  HAL_ADC_Start(&hadc);
	  HAL_ADC_PollForConversion(&hadc, HAL_MAX_DELAY);
	  uint32_t vrefMeasured = HAL_ADC_GetValue(&hadc);
	  uint32_t vdda = VREF_CAL_VDDA * (*VREF_CAL_ADDR) / vrefMeasured; // VDDA * 1000

	  uint8_t vdda_buff[2] = {};
	  vdda_buff[0] = ((vdda & 0x0000ff00) >> 8);
	  vdda_buff[1] = (vdda & 0x000000ff);
	  if (status == HAL_OK) {
		  HAL_I2C_Mem_Write(&hi2c1, NFC_ADDR, (uint16_t) TIMESTAMP_REG, I2C_MEMADD_SIZE_16BIT, vdda_buff, 2, 1000);
		  HAL_Delay(10);
	  }

	  rtcSleep(0x05, false);
  }
#endif

  // Retrieve settings from the NFC tag. Break sensor enable byte into separate booleans.
  uint8_t sensorEnable = 0b10000000; // <0:IX, 1:RC>0<temperature><moisture><pd_en>00<accel_en>.

  if (DEBUG_TEST) {
	  sensorEnable = 0b00101111;
	  rtcInterval = 0x10;
  } else {
	  HAL_StatusTypeDef status =  HAL_I2C_Mem_Read(&hi2c1, NFC_ADDR, SENSOR_ENABLE_REG, I2C_MEMADD_SIZE_16BIT, &sensorEnable, 1, HAL_MAX_DELAY);
	  HAL_I2C_Mem_Read(&hi2c1, NFC_ADDR, RTC_INTERVAL_REG, I2C_MEMADD_SIZE_16BIT, &rtcInterval, 1, HAL_MAX_DELAY);
	  HAL_I2C_Mem_Read(&hi2c1, NFC_ADDR, STARTUP_DELAY_REG, I2C_MEMADD_SIZE_16BIT, &startupDelay, 1, HAL_MAX_DELAY);

	  uint8_t buff[2] = {} ;
	  HAL_I2C_Mem_Read(&hi2c1, NFC_ADDR, MEM_POINTER_REG, I2C_MEMADD_SIZE_16BIT, buff, 2, HAL_MAX_DELAY);

	  memPointer = buff[0] | ((uint16_t) buff[1] << 8);
  }

  if (startupDelay & 0b10000000) {
	  uint8_t newStartupDelay = startupDelay & 0b01111111;
	  writeMemory(&newStartupDelay, 1, STARTUP_DELAY_REG);
	  rtcSleep(startupDelay, false);
  }

  // Conversion to bool, False if 0, True otherwise.
  rcEnable = sensorEnable 	 & 0b10000000; // 0 means RTC on XT oscillator. 1 means RTC on RC oscillator.
  tempEnable = sensorEnable 	 & 0b00100000;
  if ((sensorEnable & 0b00010110)) {
	  sensorEnable = sensorEnable & 0b11101001;
	  writeMemory(&sensorEnable, 1, SENSOR_ENABLE_REG);
  }
  pdEnable = sensorEnable 		 & 0b00001000;
  accelEnable = sensorEnable 	 & 0b00000001;

  // Enable sensors that are turned on in enable sensor register.
  enableSensors();
  HAL_Delay(5);

  uint8_t inputPointer = 0;
  uint8_t inputBuffer[22] = {};

  /* USER CODE END 2 */

  /* Infinite loop */
  /* USER CODE BEGIN WHILE */

  // Read all sensors that are turned on and store the values in a buffer.
  if (tempEnable) {
	  HAL_I2C_Mem_Read(&hi2c1, (uint16_t) ACCEL_ADDR, ACCEL_TEMP_BASE, I2C_MEMADD_SIZE_8BIT, inputBuffer, 2, HAL_MAX_DELAY);
	  inputPointer += 2;
  } else if (pdEnable) {
	  // Add an extra byte to the buffer in case the temperature is not enabled, but pd is. Byte is later used to store VDDA.
	  inputBuffer[0] = 0;
	  inputPointer += 1;
  }

  if (pdEnable) {
	  // Skip first value
	  HAL_ADC_Start(&hadc);
	  HAL_ADC_PollForConversion(&hadc, HAL_MAX_DELAY);
	  HAL_ADC_GetValue(&hadc);
	  HAL_ADC_Start(&hadc);
	  HAL_ADC_PollForConversion(&hadc, HAL_MAX_DELAY);
	  HAL_ADC_GetValue(&hadc);

	  HAL_ADC_Start(&hadc);
	  HAL_ADC_PollForConversion(&hadc, HAL_MAX_DELAY);
	  uint32_t pdValue = HAL_ADC_GetValue(&hadc);
	  // To calibrate: pdValue = ((pdValue * vdda) / 4095) / 1000;
	  inputBuffer[inputPointer] = pdValue;
	  inputBuffer[inputPointer+1] = (pdValue >> 8) & 0x0F;
	  inputPointer += 2;

  }

  // If temperature and/or photodiode is enabled, retrieve VDDA.
  if (tempEnable || pdEnable) {
	  if (!pdEnable) {
		  HAL_ADC_Start(&hadc);
		  HAL_ADC_PollForConversion(&hadc, HAL_MAX_DELAY);
		  HAL_ADC_GetValue(&hadc);
	  }

	  HAL_ADC_Start(&hadc);
	  HAL_ADC_PollForConversion(&hadc, HAL_MAX_DELAY);
	  uint32_t vrefMeasured = HAL_ADC_GetValue(&hadc);
	  uint32_t vdda = VREF_CAL_VDDA * (*VREF_CAL_ADDR) / vrefMeasured; // VDDA * 1000
	  vdda = (vdda / 100); //VDDA * 10 -> [18,33] mapped to [0, 15]. Also takes 0.1V difference into account with reality into account
	  vdda = (vdda > 33) ? 33 : vdda;
	  vdda = (vdda < 18) ? 18 : vdda;
	  vdda -= 18;
	  inputBuffer[0] = inputBuffer[0] | (vdda & 0x0F);
  }

  if (accelEnable) {
	  HAL_I2C_Mem_Read(&hi2c1, (uint16_t) ACCEL_ADDR, ACCEL_OUT_X_L, I2C_MEMADD_SIZE_8BIT, inputBuffer + inputPointer, 6, HAL_MAX_DELAY);
	  inputPointer += 6;
  }

  // Write sensor values to NFC tag. Then enter RTC sleep with interval set in settings.
  writeDataMemory(inputBuffer, inputPointer);
  rtcSleep(rtcInterval, true);
    /* USER CODE END WHILE */

    /* USER CODE BEGIN 3 */
  /* USER CODE END 3 */
}

/**
  * @brief System Clock Configuration
  * @retval None
  */
void SystemClock_Config(void)
{
  RCC_OscInitTypeDef RCC_OscInitStruct = {0};
  RCC_ClkInitTypeDef RCC_ClkInitStruct = {0};
  RCC_PeriphCLKInitTypeDef PeriphClkInit = {0};

  /** Configure the main internal regulator output voltage
  */
  __HAL_PWR_VOLTAGESCALING_CONFIG(PWR_REGULATOR_VOLTAGE_SCALE1);

  /** Initializes the RCC Oscillators according to the specified parameters
  * in the RCC_OscInitTypeDef structure.
  */
  RCC_OscInitStruct.OscillatorType = RCC_OSCILLATORTYPE_MSI;
  RCC_OscInitStruct.MSIState = RCC_MSI_ON;
  RCC_OscInitStruct.MSICalibrationValue = 0;
  RCC_OscInitStruct.MSIClockRange = RCC_MSIRANGE_5;
  RCC_OscInitStruct.PLL.PLLState = RCC_PLL_NONE;
  if (HAL_RCC_OscConfig(&RCC_OscInitStruct) != HAL_OK)
  {
    Error_Handler();
  }

  /** Initializes the CPU, AHB and APB buses clocks
  */
  RCC_ClkInitStruct.ClockType = RCC_CLOCKTYPE_HCLK|RCC_CLOCKTYPE_SYSCLK
                              |RCC_CLOCKTYPE_PCLK1|RCC_CLOCKTYPE_PCLK2;
  RCC_ClkInitStruct.SYSCLKSource = RCC_SYSCLKSOURCE_MSI;
  RCC_ClkInitStruct.AHBCLKDivider = RCC_SYSCLK_DIV1;
  RCC_ClkInitStruct.APB1CLKDivider = RCC_HCLK_DIV1;
  RCC_ClkInitStruct.APB2CLKDivider = RCC_HCLK_DIV1;

  if (HAL_RCC_ClockConfig(&RCC_ClkInitStruct, FLASH_LATENCY_0) != HAL_OK)
  {
    Error_Handler();
  }
  PeriphClkInit.PeriphClockSelection = RCC_PERIPHCLK_I2C1;
  PeriphClkInit.I2c1ClockSelection = RCC_I2C1CLKSOURCE_SYSCLK;
  if (HAL_RCCEx_PeriphCLKConfig(&PeriphClkInit) != HAL_OK)
  {
    Error_Handler();
  }
}

/**
  * @brief ADC Initialization Function
  * @param None
  * @retval None
  */
static void MX_ADC_Init(void)
{

  /* USER CODE BEGIN ADC_Init 0 */

  /* USER CODE END ADC_Init 0 */

  ADC_ChannelConfTypeDef sConfig = {0};

  /* USER CODE BEGIN ADC_Init 1 */

  /* USER CODE END ADC_Init 1 */

  /** Configure the global features of the ADC (Clock, Resolution, Data Alignment and number of conversion)
  */
  hadc.Instance = ADC1;
  hadc.Init.OversamplingMode = DISABLE;
  hadc.Init.ClockPrescaler = ADC_CLOCK_SYNC_PCLK_DIV1;
  hadc.Init.Resolution = ADC_RESOLUTION_12B;
  hadc.Init.SamplingTime = ADC_SAMPLETIME_1CYCLE_5;
  hadc.Init.ScanConvMode = ADC_SCAN_DIRECTION_FORWARD;
  hadc.Init.DataAlign = ADC_DATAALIGN_RIGHT;
  hadc.Init.ContinuousConvMode = DISABLE;
  hadc.Init.DiscontinuousConvMode = ENABLE;
  hadc.Init.ExternalTrigConvEdge = ADC_EXTERNALTRIGCONVEDGE_NONE;
  hadc.Init.ExternalTrigConv = ADC_SOFTWARE_START;
  hadc.Init.DMAContinuousRequests = DISABLE;
  hadc.Init.EOCSelection = ADC_EOC_SINGLE_CONV;
  hadc.Init.Overrun = ADC_OVR_DATA_PRESERVED;
  hadc.Init.LowPowerAutoWait = DISABLE;
  hadc.Init.LowPowerFrequencyMode = ENABLE;
  hadc.Init.LowPowerAutoPowerOff = DISABLE;
  if (HAL_ADC_Init(&hadc) != HAL_OK)
  {
    Error_Handler();
  }

  /** Configure for the selected ADC regular channel to be converted.
  */
  sConfig.Channel = ADC_CHANNEL_0;
  sConfig.Rank = ADC_RANK_CHANNEL_NUMBER;
  if (HAL_ADC_ConfigChannel(&hadc, &sConfig) != HAL_OK)
  {
    Error_Handler();
  }

  /** Configure for the selected ADC regular channel to be converted.
  */
  sConfig.Channel = ADC_CHANNEL_VREFINT;
  if (HAL_ADC_ConfigChannel(&hadc, &sConfig) != HAL_OK)
  {
    Error_Handler();
  }
  /* USER CODE BEGIN ADC_Init 2 */

  /* USER CODE END ADC_Init 2 */

}

/**
  * @brief I2C1 Initialization Function
  * @param None
  * @retval None
  */
static void MX_I2C1_Init(void)
{

  /* USER CODE BEGIN I2C1_Init 0 */

  /* USER CODE END I2C1_Init 0 */

  /* USER CODE BEGIN I2C1_Init 1 */

  /* USER CODE END I2C1_Init 1 */
  hi2c1.Instance = I2C1;
  hi2c1.Init.Timing = 0x00000708;
  hi2c1.Init.OwnAddress1 = 0;
  hi2c1.Init.AddressingMode = I2C_ADDRESSINGMODE_7BIT;
  hi2c1.Init.DualAddressMode = I2C_DUALADDRESS_DISABLE;
  hi2c1.Init.OwnAddress2 = 0;
  hi2c1.Init.OwnAddress2Masks = I2C_OA2_NOMASK;
  hi2c1.Init.GeneralCallMode = I2C_GENERALCALL_DISABLE;
  hi2c1.Init.NoStretchMode = I2C_NOSTRETCH_DISABLE;
  if (HAL_I2C_Init(&hi2c1) != HAL_OK)
  {
    Error_Handler();
  }

  /** Configure Analogue filter
  */
  if (HAL_I2CEx_ConfigAnalogFilter(&hi2c1, I2C_ANALOGFILTER_ENABLE) != HAL_OK)
  {
    Error_Handler();
  }

  /** Configure Digital filter
  */
  if (HAL_I2CEx_ConfigDigitalFilter(&hi2c1, 0) != HAL_OK)
  {
    Error_Handler();
  }
  /* USER CODE BEGIN I2C1_Init 2 */

  /* USER CODE END I2C1_Init 2 */

}

/**
  * @brief GPIO Initialization Function
  * @param None
  * @retval None
  */
static void MX_GPIO_Init(void)
{
  GPIO_InitTypeDef GPIO_InitStruct = {0};
/* USER CODE BEGIN MX_GPIO_Init_1 */
/* USER CODE END MX_GPIO_Init_1 */

  /* GPIO Ports Clock Enable */
  __HAL_RCC_GPIOC_CLK_ENABLE();
  __HAL_RCC_GPIOA_CLK_ENABLE();

  /*Configure GPIO pin Output Level */
  HAL_GPIO_WritePin(NFC_SWITCH_GPIO_Port, NFC_SWITCH_Pin, GPIO_PIN_RESET);

  /*Configure GPIO pin : NFC_SWITCH_Pin */
  GPIO_InitStruct.Pin = NFC_SWITCH_Pin;
  GPIO_InitStruct.Mode = GPIO_MODE_OUTPUT_PP;
  GPIO_InitStruct.Pull = GPIO_NOPULL;
  GPIO_InitStruct.Speed = GPIO_SPEED_FREQ_LOW;
  HAL_GPIO_Init(NFC_SWITCH_GPIO_Port, &GPIO_InitStruct);

/* USER CODE BEGIN MX_GPIO_Init_2 */
/* USER CODE END MX_GPIO_Init_2 */
}

/* USER CODE BEGIN 4 */

//int __io_putchar(int ch) {
//	ITM_SendChar(ch);
//
//	return ch;
//}

/* USER CODE END 4 */

/**
  * @brief  This function is executed in case of error occurrence.
  * @retval None
  */
void Error_Handler(void)
{
  /* USER CODE BEGIN Error_Handler_Debug */
  /* User can add his own implementation to report the HAL error return state */
  __disable_irq();
  while (1)
  {
  }
  /* USER CODE END Error_Handler_Debug */
}

#ifdef  USE_FULL_ASSERT
/**
  * @brief  Reports the name of the source file and the source line number
  *         where the assert_param error has occurred.
  * @param  file: pointer to the source file name
  * @param  line: assert_param error line source number
  * @retval None
  */
void assert_failed(uint8_t *file, uint32_t line)
{
  /* USER CODE BEGIN 6 */
  /* User can add his own implementation to report the file name and line number,
     ex: printf("Wrong parameters value: file %s on line %d\r\n", file, line) */
  /* USER CODE END 6 */
}
#endif /* USE_FULL_ASSERT */
