/* Exercise the production board entry point and inspect the actual RTC alarm
 * writes. Each invocation is a fresh MCU boot, including zeroed static state.
 * This checks control flow only; the fake HAL cannot measure real power. */
#include "main.h"
#include "densor_board.h"
#include "densor_r1.h"
#include <assert.h>
#include <setjmp.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

ADC_HandleTypeDef hadc;
I2C_HandleTypeDef hi2c1;
static uint8_t eeprom[512], rtc[64], pending[4];
static uint16_t pending_address, pending_size, tmp_config;
static unsigned acquisitions, conversions, reads, writes, tick;
static bool busy, suspended;
static const char *scenario;
static jmp_buf power_exit;
static bool is(const char *name) { return !strcmp(scenario, name); }
static bool mem_read(uint16_t a, uint8_t *p, uint16_t n) {
    assert((unsigned)a + n <= sizeof eeprom && !busy);
    memcpy(p, eeprom + a, n); return true;
}
static bool mem_write(uint16_t a, const uint8_t *p, uint16_t n) {
    assert(n && n <= 4 && a / 4 == (a + n - 1) / 4 && (unsigned)a + n <= sizeof eeprom && !busy);
    pending_address = a; pending_size = n; memcpy(pending, p, n); busy = true; return true;
}
static bool mem_ready(void) {
    assert(busy); memcpy(eeprom + pending_address, pending, pending_size); busy = false; return true;
}
static const densor_io io = {mem_read, mem_write, mem_ready};
static void request(const densor_session *s, uint8_t command, unsigned period, uint32_t id) {
    uint8_t *p = eeprom + DENSOR_PENDING;
    memset(p, 0, 36); memcpy(p, "DCP3", 4); densor_put32(p + 4, id);
    p[8] = command; p[11] = 3;
    if (command == DENSOR_APPLY_SETTINGS) { p[9] = DENSOR_ACCEL; densor_put32(p + 12, period); }
    densor_put32(p + 16, densor_u32(s->header + 20));
    densor_put16(p + 30, densor_crc16(p, 30)); densor_put32(p + 32, id);
}
static void state(uint8_t value, uint8_t error) {
    uint8_t *p = eeprom + DENSOR_STATUS; p[2] = value; p[3] = error;
    densor_put16(p + 14, densor_crc16(p, 14));
}
HAL_StatusTypeDef HAL_I2C_Mem_Read(I2C_HandleTypeDef *bus, uint16_t dev, uint16_t reg,
                                 uint16_t width, uint8_t *p, uint16_t n, uint32_t timeout) {
    (void)bus; (void)width; assert(timeout == 25); memset(p, 0, n);
    if (dev == 0xa6) {
        ++reads; if (is("read-fail")) return HAL_ERROR;
        return mem_read(reg, p, n) ? HAL_OK : HAL_ERROR;
    }
    if (dev == 0xae) { assert(reg == 0x14 && n == 3); p[0] = 127; p[2] = is("bad-density") ? 0 : 3; }
    else if (dev == 0xd2) { assert(reg + n <= sizeof rtc); memcpy(p, rtc + reg, n); }
    else if (dev == 0x90) {
        if (is("tmp-bus")) return HAL_ERROR;
        assert(n == 2); uint16_t v = reg == 0x0f ? 0x2117 : tmp_config;
        p[0] = (uint8_t)(v >> 8); p[1] = (uint8_t)v;
    } else if (dev == 0x32) {
        if (reg == 0x0f) p[0] = is("missing-sensor") ? 0 : 0x44;
        else if (reg == 0x27) p[0] = 1;
        else assert(reg == 0x28 && n == 6);
    } else assert(false);
    return HAL_OK;
}
HAL_StatusTypeDef HAL_I2C_Mem_Write(I2C_HandleTypeDef *bus, uint16_t dev, uint16_t reg,
                                  uint16_t width, uint8_t *p, uint16_t n, uint32_t timeout) {
    (void)bus; (void)width; assert(timeout == 25);
    if (dev == 0xa6) {
        ++writes; if (is("write-fail") || busy) return HAL_ERROR;
        return mem_write(reg, p, n) ? HAL_OK : HAL_ERROR;
    }
    if (dev == 0xd2) {
        if (is("rtc-write-fail")) return HAL_ERROR;
        assert(n == 1 && reg < sizeof rtc); rtc[reg] = p[0];
        if (reg == 0x17 && p[0] == 0x80) { assert(!busy); longjmp(power_exit, 1); }
    } else if (dev == 0x90) {
        assert(reg == 1 && n == 2); tmp_config = (uint16_t)((p[0] << 8) | p[1]);
        if (tmp_config == 0x0c00) ++conversions;
    } else if (dev == 0x32) {
        assert(n == 1); if (reg == 0x22) ++acquisitions;
    } else assert(false);
    return HAL_OK;
}
HAL_StatusTypeDef HAL_I2C_IsDeviceReady(I2C_HandleTypeDef *bus, uint16_t dev, uint32_t attempts, uint32_t timeout) {
    (void)bus; assert(dev == 0xa6 && attempts == 1 && timeout == 2);
    if (is("ready-fail")) return HAL_ERROR;
    return mem_ready() ? HAL_OK : HAL_ERROR;
}
uint32_t HAL_I2C_GetError(I2C_HandleTypeDef *bus) { (void)bus; return 1; }
uint32_t HAL_GetTick(void) { return tick; }
void HAL_Delay(uint32_t n) { tick += n; }
void HAL_SuspendTick(void) { suspended = true; }
_Noreturn void __WFI(void) { assert(suspended); longjmp(power_exit, 2); }
/* Acceleration-only running fixtures avoid the physical VREF calibration
 * address. Any ADC use on an idle wake must fail this regression test. */
HAL_StatusTypeDef HAL_ADCEx_Calibration_Start(ADC_HandleTypeDef *a, uint32_t mode) { (void)a; (void)mode; abort(); }
HAL_StatusTypeDef HAL_ADC_Start(ADC_HandleTypeDef *a) { (void)a; abort(); }
HAL_StatusTypeDef HAL_ADC_PollForConversion(ADC_HandleTypeDef *a, uint32_t ms) { (void)a; (void)ms; abort(); }
uint32_t HAL_ADC_GetValue(ADC_HandleTypeDef *a) { (void)a; abort(); }
HAL_StatusTypeDef HAL_ADC_Stop(ADC_HandleTypeDef *a) { (void)a; abort(); }
static unsigned seconds(uint8_t b) { return (b >> 4) * 10u + (b & 15); }
static int wake(void) {
    int result = setjmp(power_exit);
    if (!result) { densor_board_run(); assert(false); }
    return result;
}
int main(int argc, char **argv) {
    assert(argc == 3); scenario = argv[1]; unsigned period = (unsigned)atoi(argv[2]);
    assert(densor_period_valid(period));
    densor_session s;
    assert(densor_format(&io, &s, sizeof eeprom, 15) == DENSOR_OK);
    request(&s, DENSOR_APPLY_SETTINGS, period, 1); assert(densor_request(&io, &s, 15) == DENSOR_OK);
    unsigned expected = period, expected_acquisitions = 0;
    uint8_t expected_state = DENSOR_RUNNING;
    int expected_exit = 1;
    if (is("full")) { state(DENSOR_FULL, DENSOR_NO_SPACE); densor_put16(eeprom + 8, 512); expected_state = DENSOR_FULL; }
    if (is("error")) { state(DENSOR_ERROR, DENSOR_SENSOR); expected_state = DENSOR_ERROR; }
    if (is("unconfigured") || is("provision")) {
        assert(densor_format(&io, &s, sizeof eeprom, 15) == DENSOR_OK);
        expected = 120; expected_state = DENSOR_STOPPED;
        if (is("provision")) densor_provision_request = 0x44525031u;
    }
    if (is("bad-header")) { eeprom[DENSOR_HEADER] ^= 1; expected = 120; }
    if (is("bad-pointer")) { densor_put16(eeprom + 8, 129); expected = 120; }
    if (is("configuring")) { state(DENSOR_CONFIGURING, DENSOR_OK); expected = 120; expected_state = DENSOR_CONFIGURING; }
    if (is("bad-density") || is("read-fail")) expected = 120;
    if (is("pending-settings")) { request(&s, DENSOR_APPLY_SETTINGS, 600, 2); expected = 600; expected_acquisitions = 1; }
    if (is("pending-reset")) { request(&s, DENSOR_APPLY_SETTINGS, period, 2); densor_put16(eeprom + 8, 136); }
    if (is("running") || is("pending-reset")) { expected = period; expected_acquisitions = 1; }
    if (is("running-last")) { densor_put16(eeprom + 8, 504); expected_acquisitions = 1; expected_state = DENSOR_FULL; }
    if (is("already-full")) { densor_put16(eeprom + 8, 512); expected_state = DENSOR_FULL; }
    if (is("missing-sensor") || is("tmp-bus") || is("clock-invalid")) expected_state = DENSOR_ERROR;
    unsigned startup = is("startup-1") ? 1 : is("startup-59") ? 59 : 0;
    if (startup) {
        request(&s, DENSOR_APPLY_SETTINGS, period, 2);
        eeprom[DENSOR_PENDING + 20] = (uint8_t)startup;
        densor_put16(eeprom + DENSOR_PENDING + 30, densor_crc16(eeprom + DENSOR_PENDING, 30));
        densor_put16(eeprom + 8, 136); /* Existing recording must reset before waiting. */
        expected = startup * 60;
    }
    /* 58:50 exercises carry across both a minute and the hourly alarm wrap. */
    rtc[1] = 0x50; rtc[2] = 0x58;
    if (is("clock-invalid")) rtc[1] = 0xff;
    if (is("ready-fail") || is("write-fail") || is("rtc-write-fail")) {
        expected_exit = 2; expected_state = DENSOR_ERROR; expected_acquisitions = 1;
        if (!is("rtc-write-fail")) expected_state = DENSOR_RUNNING; /* Status could not be persisted. */
    }
    int result = wake();
    assert(result == expected_exit && acquisitions == expected_acquisitions && conversions == 0);
    assert(eeprom[DENSOR_STATUS + 2] == expected_state);
    if (result == 1) {
        unsigned boot = is("clock-invalid") ? 0 : 58 * 60 + 50;
        assert(rtc[8] == 0 && rtc[0x18] == 0x17);
        assert((seconds(rtc[0x0a]) * 60 + seconds(rtc[9]) + 3600 - boot) % 3600 == expected);
        if (expected_acquisitions) assert(densor_u16(eeprom + 8) == (is("running-last") ? 512 : 136));
    } else assert(rtc[0x17] == 0); /* No power-off after uncertain EEPROM completion. */
    if (is("full") || is("error")) assert(writes == 0);
    if (is("pending-settings") || is("pending-reset")) assert(densor_u32(eeprom + DENSOR_STATUS + 6) == 2);
    if (startup) {
        assert(densor_u16(eeprom + 8) == 128 && acquisitions == 0 && conversions == 0);
        assert(densor_u32(eeprom + DENSOR_STATUS + 6) == 2 && eeprom[DENSOR_HEADER + 18] == startup);
        for (unsigned boot = 0; boot < 2; ++boot) {
            /* Re-enter with EEPROM/RTC preserved. The production loader clears
             * and reloads its session; all other successful-path boot state is
             * overwritten before use, equivalent to these normal MCU boots. */
            rtc[1] = rtc[9]; rtc[2] = rtc[10]; rtc[0x17] = 0;
            unsigned start = seconds(rtc[2]) * 60 + seconds(rtc[1]);
            acquisitions = 0; writes = 0;
            assert(wake() == 1 && acquisitions == 1 && conversions == 0);
            assert((seconds(rtc[10]) * 60 + seconds(rtc[9]) + 3600 - start) % 3600 == period);
            assert(densor_u16(eeprom + 8) == 136 + 8 * boot);
            assert(writes == 3); /* Two record pages + raw pointer; no repeated reset/delay. */
        }
    }
    printf("%s / %u s: %s", scenario, period, result == 1 ? "RTC interval verified\n" : "latched fault held\n");
    return 0;
}
