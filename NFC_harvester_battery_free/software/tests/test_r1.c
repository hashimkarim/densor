#include "densor_r1.h"
#include "tmp119.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static uint8_t eeprom[8192], pending[4];
static uint16_t pending_address, pending_size;
static unsigned writes, completions, fail_write, fail_ready, pointer_writes;
static bool busy;
static bool mem_read(uint16_t a, uint8_t *p, uint16_t n) {
    if (busy || (unsigned)a + n > sizeof eeprom) return false;
    memcpy(p, eeprom + a, n); return true;
}
static bool mem_write(uint16_t a, const uint8_t *p, uint16_t n) {
    assert(n && n <= 4 && (a / 4 == (a + n - 1) / 4));
    if (busy || ++writes == fail_write) return false;
    pending_address = a; pending_size = n; memcpy(pending, p, n); busy = true;
    if (a == DENSOR_POINTER) ++pointer_writes;
    return true;
}
static bool mem_ready(void) {
    assert(busy);
    if (++completions == fail_ready) return false;
    memcpy(eeprom + pending_address, pending, pending_size); busy = false; return true;
}
static const densor_io io = { mem_read, mem_write, mem_ready };
static void reset_faults(void) { writes = completions = fail_write = fail_ready = pointer_writes = 0; busy = false; }
static void request(densor_session *s, uint8_t cmd, uint8_t mask, uint32_t period, uint32_t id) {
    uint8_t *p = eeprom + DENSOR_PENDING;
    memset(p, 0, 36); memcpy(p, "DCP3", 4); densor_put32(p + 4, id);
    p[8] = cmd; p[9] = mask; p[11] = 3; densor_put32(p + 12, period);
    densor_put32(p + 16, densor_u32(s->header + 20));
    densor_put16(p + 30, densor_crc16(p, 30)); densor_put32(p + 32, id);
}
static void setup(densor_session *s, uint16_t capacity, uint8_t mask) {
    reset_faults(); memset(eeprom, 0xa5, sizeof eeprom);
    assert(densor_format(&io, s, capacity, 15) == 0);
    assert(densor_load(&io, s, capacity) == 0);
    request(s, DENSOR_APPLY_SETTINGS, mask, 120, 1); assert(densor_request(&io, s, 15) == 0);
    reset_faults();
}
static densor_sample values(uint8_t mask) {
    densor_sample s = {.old_temperature = -7680, .tmp119_temperature = -128,
        .acceleration = {16384, -16384, 0}, .photodiode = 2048,
        .supply = (mask & 7) ? 8 : 255};
    return s;
}
static void storage_tests(void) {
    assert(densor_crc16((const uint8_t *)"123456789", 9) == 0x29b1);
    assert(!densor_stride(0) && !densor_stride(16));
    assert(!densor_period_valid(0) && !densor_period_valid(61) && !densor_period_valid(3600));
    assert(densor_period_valid(1) && densor_period_valid(59) && densor_period_valid(120) && densor_period_valid(3540));
    const uint16_t sizes[] = {512, 2048, 8192};
    for (unsigned ci = 0; ci < 3; ++ci) for (uint8_t mask = 1; mask <= 15; ++mask) {
        densor_session s; setup(&s, sizes[ci], mask); densor_sample v = values(mask);
        unsigned count = (sizes[ci] - DENSOR_LOG_START) / densor_stride(mask);
        for (unsigned i = 0; i < count; ++i) assert(densor_append(&io, &s, &v) == 0);
        assert(pointer_writes == count && s.status[2] == DENSOR_FULL);
        assert(s.pointer == DENSOR_LOG_START + count * densor_stride(mask));
        if (sizes[ci] < sizeof eeprom) assert(eeprom[sizes[ci]] == 0xa5);
        unsigned before = writes; assert(densor_append(&io, &s, &v) == DENSOR_BAD_STATE && writes == before);
        assert(densor_load(&io, &s, sizes[ci]) == 0);
    }
    for (unsigned point = 1; point <= 5; ++point) for (unsigned mode = 0; mode < 2; ++mode) {
        densor_session s; setup(&s, 512, 15); densor_sample v = values(15);
        if (mode) fail_ready = point; else fail_write = point;
        assert(densor_append(&io, &s, &v) == DENSOR_IO);
        assert(s.pointer == 128 && densor_u16(eeprom + 8) == 128);
        assert(pointer_writes == (mode && point == 5 ? 1u : 0u));
    }
    densor_session s; setup(&s, 512, 1); densor_sample v = values(1);
    assert(densor_presence(&io, &s, 15) == 0 && writes == 0);
    assert(densor_presence(&io, &s, 13) == 0 && writes == 4);
    assert(densor_load(&io, &s, 512) == 0 && s.status[4] == 13 && s.header[17] == 15);
    densor_put16(eeprom + 8, 129); assert(densor_load(&io, &s, 512) == DENSOR_BAD_POINTER);
    setup(&s, 512, 15); densor_put16(eeprom + 8, 132); assert(densor_load(&io, &s, 512) == DENSOR_BAD_POINTER);
    setup(&s, 512, 1); assert(densor_load(&io, &s, 8192) == DENSOR_BAD_HEADER);
    for (unsigned delay = 0; delay <= 60; ++delay) {
        setup(&s, 512, 1); request(&s, DENSOR_APPLY_SETTINGS, 3, 120, 2);
        eeprom[DENSOR_PENDING + 20] = (uint8_t)delay;
        densor_put16(eeprom + DENSOR_PENDING + 30, densor_crc16(eeprom + DENSOR_PENDING, 30));
        assert(densor_request(&io, &s, 15) == (delay <= 59 ? DENSOR_OK : DENSOR_BAD_CONFIG));
        if (delay <= 59) {
            assert(densor_load(&io, &s, 512) == DENSOR_OK && s.header[18] == delay);
            unsigned before = writes; assert(densor_request(&io, &s, 15) == DENSOR_OK && writes == before);
        } else assert(s.header[18] == 0 && densor_u32(s.header + 20) == 1);
    }
    /* Applying settings while logging resets once and immediately records on
     * the next board wake. There are no stop/start lifecycle commands. */
    setup(&s, 512, 1); assert(densor_append(&io, &s, &v) == 0);
    request(&s, DENSOR_APPLY_SETTINGS, 2, 600, 2);
    assert(densor_request(&io, &s, 15) == 0 && s.header[12] == 2);
    assert(s.status[2] == DENSOR_RUNNING && s.pointer == 128 && densor_u32(s.header + 28) == 600);
    unsigned before = writes; assert(densor_request(&io, &s, 15) == 0 && writes == before);
    setup(&s, 512, 1); request(&s, DENSOR_APPLY_SETTINGS, 2, 120, 2);
    assert(densor_request(&io, &s, 13) == DENSOR_SENSOR);
    assert(s.header[12] == 1 && densor_u32(s.header + 20) == 1 && s.status[2] == DENSOR_ERROR);
    setup(&s, 512, 1); request(&s, DENSOR_APPLY_SETTINGS, 3, 120, 2);
    eeprom[DENSOR_PENDING + 15] ^= 1; assert(densor_request(&io, &s, 15) == 0 && s.header[12] == 1 && s.status[2] == DENSOR_RUNNING);
    request(&s, DENSOR_APPLY_SETTINGS, 3, 120, 2); memset(eeprom + DENSOR_MARKER, 0, 4);
    assert(densor_request(&io, &s, 15) == 0 && s.header[12] == 1 && s.status[2] == DENSOR_RUNNING);
    for (unsigned cmd = 1; cmd <= 3; cmd += 2) {
        setup(&s, 512, 1); request(&s, (uint8_t)cmd, 0, 0, 2);
        assert(densor_request(&io, &s, 15) == DENSOR_BAD_CONFIG && s.header[12] == 1);
    }
    setup(&s, 512, 1); request(&s, DENSOR_APPLY_SETTINGS, 3, 120, 2);
    eeprom[DENSOR_PENDING + 3] = '1'; eeprom[DENSOR_PENDING + 11] = 1;
    densor_put16(eeprom + DENSOR_PENDING + 30, densor_crc16(eeprom + DENSOR_PENDING, 30));
    assert(densor_request(&io, &s, 15) == DENSOR_BAD_CONFIG);
    /* Cuts leave either the old consistent recording or reject initialization;
     * no accepted mixed header/pointer. After a complete transaction, the new
     * recording is already running without a second command. */
    for (unsigned cut = 1; cut <= 26; ++cut) {
        setup(&s, 512, 1); assert(densor_append(&io, &s, &v) == 0);
        request(&s, DENSOR_APPLY_SETTINGS, 3, 600, 2); reset_faults(); fail_ready = cut;
        (void)densor_request(&io, &s, 15); busy = false;
        uint8_t loaded = densor_load(&io, &s, 512);
        if (!loaded) {
            assert(s.status[2] == DENSOR_RUNNING);
            if (densor_u32(s.header + 20) == 1) assert(s.header[12] == 1 && s.pointer == 132);
            else assert(densor_u32(s.header + 20) == 2 && s.header[12] == 3 && s.pointer == 128);
        }
    }
}
static uint32_t now, ready_at;
static uint16_t tmp_config, tmp_id;
static uint8_t tmp_fault;
static unsigned tmp_writes;
static uint8_t sensor_read(uint8_t reg, uint16_t *v) {
    if (tmp_fault) return tmp_fault;
    *v = reg == 15 ? tmp_id : reg == 0 ? 0xff80 : tmp_config;
    if (reg == 1 && tmp_config == 0x0c00 && (int32_t)(now - ready_at) >= 0) *v |= 0x2000;
    return 0;
}
static uint8_t sensor_write(uint8_t reg, uint16_t v) { assert(reg == 1); ++tmp_writes; tmp_config = v; return tmp_fault; }
static uint32_t ticks(void) { return now; }
static void delay(uint32_t ms) { now += ms; }
static const tmp119_bus bus = {sensor_read, sensor_write, ticks, delay};
static void sensor_tests(void) {
    tmp_id = 0x2117; tmp_config = 0x220; now = 0;
    assert(tmp119_shutdown(&bus) == 0 && tmp_config == 0x400 && tmp_writes == 1);
    uint32_t started; int16_t raw = 0;
    ready_at = 18; assert(tmp119_start(&bus, &started) == 0);
    now = 7; assert(tmp119_result(&bus, started, &raw) == 0 && raw == -128 && now == 18);
    now = 0; ready_at = 1000; assert(tmp119_start(&bus, &started) == 0);
    assert(tmp119_result(&bus, started, &raw) == DENSOR_TIMEOUT && now == 25);
    now = UINT32_MAX - 10; ready_at = 7; assert(tmp119_start(&bus, &started) == 0);
    assert(tmp119_result(&bus, started, &raw) == 0 && now == 7);
    tmp_config = 0x1000; now = 0; assert(tmp119_shutdown(&bus) == DENSOR_TIMEOUT && now == 10);
    tmp_id = 0x1117; assert(tmp119_shutdown(&bus) == DENSOR_SENSOR);
    tmp_fault = DENSOR_IO; assert(tmp119_start(&bus, &started) == DENSOR_IO);
    assert(tmp119_shutdown(&bus) == DENSOR_IO && tmp119_result(&bus, started, &raw) == DENSOR_IO);
}
int main(int argc, char **argv) {
    if (argc == 4 && !strcmp(argv[1], "--replay")) {
        densor_session s; setup(&s, 8192, 13);
        request(&s, DENSOR_APPLY_SETTINGS, 13, 1, 2); assert(densor_request(&io, &s, 15) == 0);
        FILE *input = fopen(argv[2], "r"); assert(input);
        int supply, old, pd, x, y, z, count;
        while ((count = fscanf(input, "%d %d %d %d %d %d", &supply, &old, &pd, &x, &y, &z)) == 6) {
            assert(supply >= 0 && supply <= 15 && old >= INT16_MIN && old <= INT16_MAX);
            assert(pd >= 0 && pd <= 4095 && x >= INT16_MIN && x <= INT16_MAX);
            assert(y >= INT16_MIN && y <= INT16_MAX && z >= INT16_MIN && z <= INT16_MAX);
            densor_sample v = {.supply = (uint8_t)supply, .old_temperature = (int16_t)old,
                .photodiode = (uint16_t)pd, .acceleration = {(int16_t)x, (int16_t)y, (int16_t)z}};
            assert(densor_append(&io, &s, &v) == 0);
        }
        assert(count == EOF && !ferror(input)); assert(fclose(input) == 0);
        FILE *output = fopen(argv[3], "wb"); assert(output);
        assert(fwrite(eeprom, 1, s.pointer, output) == s.pointer); assert(fclose(output) == 0);
        return 0;
    }
    if (argc == 5 && !strcmp(argv[1], "--dump")) {
        densor_session s; uint8_t mask = (uint8_t)atoi(argv[3]);
        setup(&s, (uint16_t)atoi(argv[4]), mask); densor_sample v = values(mask);
        assert(densor_append(&io, &s, &v) == 0);
        FILE *f = fopen(argv[2], "wb"); assert(f);
        assert(fwrite(eeprom, 1, s.pointer, f) == s.pointer); assert(fclose(f) == 0); return 0;
    }
    if (argc == 9) {
        uint8_t mask = (uint8_t)atoi(argv[1]);
        densor_sample s = {.supply = (uint8_t)atoi(argv[2]), .old_temperature = (int16_t)atoi(argv[3]),
            .tmp119_temperature = (int16_t)atoi(argv[4]), .photodiode = (uint16_t)atoi(argv[5]),
            .acceleration = {(int16_t)atoi(argv[6]), (int16_t)atoi(argv[7]), (int16_t)atoi(argv[8])}};
        uint8_t p[16], n = densor_encode(mask, &s, p); assert(n);
        for (unsigned i = 0; i < n; ++i) printf("%02x", p[i]);
        puts(""); return 0;
    }
    storage_tests(); sensor_tests(); puts("R1 storage, configuration, fault injection and TMP119 tests passed"); return 0;
}
