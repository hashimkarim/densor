#include "main.h"
#include "densor_board.h"
#include "densor_r1.h"
#include "tmp119.h"
#include <string.h>

extern ADC_HandleTypeDef hadc;
extern I2C_HandleTypeDef hi2c1;
enum { NFC = 0xa6, NFC_SYSTEM = 0xae, LIS = 0x32, DENSOR_RTC_ADDR = 0xd2, TMP = 0x90,
       BUS_TIMEOUT_MS = 25 };
static densor_session session;
static bool session_loaded;
static bool memory_write_failed;
static uint8_t boot_seconds, boot_minutes;
volatile uint32_t densor_provision_request;

/* The provisioning helper breaks HERE, after C initialization, and sets the
 * request through SWD. Normal firmware cannot format an unknown/legacy tag. */
__attribute__((noinline)) void densor_boot_gate(void) { __asm volatile ("nop" ::: "memory"); }
static bool read_reg(uint16_t dev, uint16_t reg, uint16_t width, uint8_t *p, uint16_t n) {
    return HAL_I2C_Mem_Read(&hi2c1, dev, reg, width, p, n, BUS_TIMEOUT_MS) == HAL_OK;
}
static bool write_reg(uint16_t dev, uint16_t reg, uint16_t width, const uint8_t *p, uint16_t n) {
    return HAL_I2C_Mem_Write(&hi2c1, dev, reg, width, (uint8_t *)p, n, BUS_TIMEOUT_MS) == HAL_OK;
}
static bool read_memory(uint16_t a, uint8_t *p, uint16_t n) { return read_reg(NFC, a, I2C_MEMADD_SIZE_16BIT, p, n); }
static bool write_memory(uint16_t a, const uint8_t *p, uint16_t n) {
    bool ok = write_reg(NFC, a, I2C_MEMADD_SIZE_16BIT, p, n);
    if (!ok) memory_write_failed = true;
    return ok;
}
static bool memory_ready(void) {
    for (unsigned attempt = 0; attempt < 12; ++attempt) {
        if (HAL_I2C_IsDeviceReady(&hi2c1, NFC, 1, 2) == HAL_OK) return true;
        HAL_Delay(1);
    }
    memory_write_failed = true;
    return false;
}
static const densor_io memory = { read_memory, write_memory, memory_ready };
static uint8_t tmp_read(uint8_t reg, uint16_t *value) {
    uint8_t p[2];
    if (!read_reg(TMP, reg, I2C_MEMADD_SIZE_8BIT, p, 2))
        return HAL_I2C_GetError(&hi2c1) == HAL_I2C_ERROR_AF ? DENSOR_SENSOR : DENSOR_IO;
    *value = (uint16_t)(((uint16_t)p[0] << 8) | p[1]);
    return DENSOR_OK;
}
static uint8_t tmp_write(uint8_t reg, uint16_t value) {
    uint8_t p[2] = {(uint8_t)(value >> 8), (uint8_t)value};
    return write_reg(TMP, reg, I2C_MEMADD_SIZE_8BIT, p, 2) ? DENSOR_OK : DENSOR_IO;
}
static const tmp119_bus temperature = { tmp_read, tmp_write, HAL_GetTick, HAL_Delay };
static bool rtc_write(uint8_t reg, uint8_t value) { return write_reg(DENSOR_RTC_ADDR, reg, I2C_MEMADD_SIZE_8BIT, &value, 1); }
static bool bcd_valid(uint8_t b) { return (b & 15) <= 9 && (b >> 4) <= 5; }
static uint8_t bcd_decode(uint8_t b) { return (uint8_t)((b >> 4) * 10 + (b & 15)); }
static uint8_t bcd_encode(unsigned b) { return (uint8_t)(((b / 10) << 4) | (b % 10)); }
static bool rtc_read_boot(void) {
    for (unsigned n = 0; n < 3; ++n) {
        uint8_t time[2], seconds;
        if (!read_reg(DENSOR_RTC_ADDR, 1, 1, time, 2) || !read_reg(DENSOR_RTC_ADDR, 1, 1, &seconds, 1)) return false;
        if (seconds == time[0] && bcd_valid(seconds) && bcd_valid(time[1])) {
            boot_seconds = time[0]; boot_minutes = time[1]; return true;
        }
    }
    return false;
}
static void power_off(uint32_t startup_delay) {
    /* If programming completion is unknown, do not deliberately remove power
     * or reset and retry acquisition. A failed bus may also prevent reporting
     * the error to the tag; external recovery is then required. */
    if (memory_write_failed) { HAL_SuspendTick(); for (;;) __WFI(); }
    /* One configured wake interval, including full/error states. Settings are
     * picked up on that normal wake; there is no separate command-poll timer.
     * Before valid settings exist, use a two-minute default. */
    uint32_t period = startup_delay ? startup_delay : (session_loaded ? densor_u32(session.header + 28) : 0);
    uint8_t rc = session_loaded ? session.header[14] : 1;
    if (!densor_period_valid(period)) period = 120;
    unsigned due = (bcd_decode(boot_minutes) * 60u + bcd_decode(boot_seconds) + period) % 3600;
    for (unsigned attempt = 0; attempt < 3; ++attempt) {
        /* Hourly repeat compares minute/second/hundredth, avoiding a minute
         * interval that silently discards the seconds phase. */
        if (!rtc_write(0x1f, 0xa1) || !rtc_write(0x1c, rc ? 0x84 : 0x04)
            || !rtc_write(0x08, 0) || !rtc_write(0x09, bcd_encode(due % 60))
            || !rtc_write(0x0a, bcd_encode(due / 60)) || !rtc_write(0x11, 0x38)
            || !rtc_write(0x12, 0x84) || !rtc_write(0x18, 0x17)
            || !rtc_write(0x0f, 0) || !rtc_write(0x17, 0x80)) break;
        HAL_Delay(50);
    }
    /* Never reset and reacquire when the power-switch transaction fails. */
    if (session_loaded) (void)densor_fail(&memory, &session, DENSOR_IO);
    HAL_SuspendTick();
    for (;;) __WFI();
}
static uint8_t sensors_present(uint8_t tmp_status) {
    uint8_t who = 0, detected = DENSOR_PD; /* Analog hardware has no identity register. */
    if (tmp_status == DENSOR_OK) detected |= DENSOR_TMP119;
    if (read_reg(LIS, 0x0f, 1, &who, 1) && who == 0x44) detected |= DENSOR_OLD | DENSOR_ACCEL;
    return detected;
}
static uint8_t acquire(uint8_t mask, densor_sample *sample) {
    uint32_t tmp_started = 0, lis_started = 0;
    uint8_t error, p[6];
    memset(sample, 0, sizeof(*sample)); sample->supply = 255;
    if (mask & DENSOR_TMP119) {
        error = tmp119_start(&temperature, &tmp_started);
        if (error) return error;
    }
    if (mask & (DENSOR_OLD | DENSOR_ACCEL)) {
        uint8_t control = 0x0c; /* BDU + address increment. */
        if (!write_reg(LIS, 0x21, 1, &control, 1)) return DENSOR_IO;
        control = 0x3b; /* On-demand, low power mode 4, original +/-2g scale. */
        if (!write_reg(LIS, 0x20, 1, &control, 1)) return DENSOR_IO;
        control = 3;
        if (!write_reg(LIS, 0x22, 1, &control, 1)) return DENSOR_IO;
        lis_started = HAL_GetTick();
    }
    if (mask & 7) {
        if (HAL_ADCEx_Calibration_Start(&hadc, ADC_SINGLE_ENDED) != HAL_OK) return DENSOR_IO;
        uint32_t values[2] = {0};
        /* Channel 0 then VREFINT; discard first pair after power-up. */
        for (unsigned i = 0; i < 4; ++i) {
            if (HAL_ADC_Start(&hadc) != HAL_OK || HAL_ADC_PollForConversion(&hadc, 5) != HAL_OK) return DENSOR_TIMEOUT;
            values[i & 1] = HAL_ADC_GetValue(&hadc);
        }
        if (HAL_ADC_Stop(&hadc) != HAL_OK || values[1] == 0) return DENSOR_IO;
        sample->photodiode = (uint16_t)values[0];
        uint32_t supply = 3000u * (*(const uint16_t *)0x1ff80078u) / values[1] / 100u;
        if (supply < 18) supply = 18;
        if (supply > 33) supply = 33;
        sample->supply = (uint8_t)(supply - 18);
    }
    if (mask & (DENSOR_OLD | DENSOR_ACCEL)) {
        for (;;) {
            if (!read_reg(LIS, 0x27, 1, p, 1)) return DENSOR_IO;
            if (p[0] & 1) break;
            if ((uint32_t)(HAL_GetTick() - lis_started) >= 25) return DENSOR_TIMEOUT;
            HAL_Delay(1);
        }
        if (mask & DENSOR_OLD) {
            if (!read_reg(LIS, 0x0d, 1, p, 2)) return DENSOR_IO;
            sample->old_temperature = (int16_t)densor_u16(p);
        }
        if (mask & DENSOR_ACCEL) {
            if (!read_reg(LIS, 0x28, 1, p, 6)) return DENSOR_IO;
            for (unsigned i = 0; i < 3; ++i) sample->acceleration[i] = (int16_t)densor_u16(p + 2 * i);
        }
    }
    if (mask & DENSOR_TMP119) return tmp119_result(&temperature, tmp_started, &sample->tmp119_temperature);
    return DENSOR_OK;
}
void densor_board_run(void) {
    HAL_Delay(5); /* Supply/DENSOR_RTC_ADDR/TMP119 startup; startup cost exists on every wake. */
    uint8_t tmp_status = tmp119_shutdown(&temperature);
    uint8_t density[3];
    bool clock_ok = rtc_read_boot();
    uint16_t capacity = 0;
    if (read_reg(NFC_SYSTEM, 0x14, 2, density, 3) && density[2] == 3) {
        uint32_t n = ((uint32_t)densor_u16(density) + 1) * 4;
        if (n == 512 || n == 2048 || n == 8192) capacity = (uint16_t)n;
    }
    uint8_t detected = sensors_present(tmp_status);
    densor_boot_gate();
    if (densor_provision_request == 0x44525031u) {
        densor_provision_request = 0;
        session_loaded = densor_format(&memory, &session, capacity, detected) == DENSOR_OK;
        power_off(0);
    }
    uint8_t error = densor_load(&memory, &session, capacity);
    if (error) power_off(0);
    session_loaded = true;
    if (densor_presence(&memory, &session, detected)) power_off(0);
    if (!clock_ok) { (void)densor_fail(&memory, &session, DENSOR_IO); power_off(0); }
    if (tmp_status != DENSOR_OK && tmp_status != DENSOR_SENSOR) {
        (void)densor_fail(&memory, &session, tmp_status); power_off(0);
    }
    uint32_t previous_session = densor_u32(session.header + 20);
    error = densor_request(&memory, &session, detected);
    /* A new durable acknowledgement consumes the delay once, before sleep.
     * A duplicate request on the next MCU boot must proceed to acquisition. */
    if (!error && session.status[2] == DENSOR_RUNNING
        && densor_u32(session.header + 20) != previous_session && session.header[18])
        power_off((uint32_t)session.header[18] * 60u);
    if (!error && session.status[2] == DENSOR_RUNNING) {
        if ((session.header[12] & detected) != session.header[12]) error = DENSOR_SENSOR;
        else if (!densor_pointer_valid(&session)) error = DENSOR_BAD_POINTER;
        else if (session.header[13] > session.capacity - session.pointer) error = DENSOR_NO_SPACE;
        else {
            densor_sample sample;
            error = acquire(session.header[12], &sample);
            if (!error) error = densor_append(&memory, &session, &sample);
        }
        if (error) (void)densor_fail(&memory, &session, error);
    }
    power_off(0);
}
