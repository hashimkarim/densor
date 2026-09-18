#include "densor_r1.h"
#include <string.h>

static const uint8_t guard[8] = {'D','E','N','S','O','R','1','!'};
uint16_t densor_u16(const uint8_t *p) { return (uint16_t)(p[0] | ((uint16_t)p[1] << 8)); }
uint32_t densor_u32(const uint8_t *p) { return densor_u16(p) | ((uint32_t)densor_u16(p + 2) << 16); }
void densor_put16(uint8_t *p, uint16_t v) { p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); }
void densor_put32(uint8_t *p, uint32_t v) { densor_put16(p, (uint16_t)v); densor_put16(p + 2, (uint16_t)(v >> 16)); }

uint16_t densor_crc16(const uint8_t *p, size_t size) {
    uint16_t crc = 0xffff;
    while (size--) {
        crc ^= (uint16_t)*p++ << 8;
        for (unsigned bit = 0; bit < 8; ++bit)
            crc = (uint16_t)((crc << 1) ^ ((crc & 0x8000) ? 0x1021 : 0));
    }
    return crc;
}
static bool crc_valid(const uint8_t *p, size_t size) {
    return densor_crc16(p, size - 2) == densor_u16(p + size - 2);
}
static void seal(uint8_t *p, size_t size) { densor_put16(p + size - 2, densor_crc16(p, size - 2)); }
static bool zeros(const uint8_t *p, size_t n) {
    while (n--) if (*p++) return false;
    return true;
}
static bool capacity_valid(uint16_t n) { return n == 512 || n == 2048 || n == 8192; }
uint8_t densor_stride(uint8_t mask) {
    if (!mask || (mask & 0xf0)) return 0;
    unsigned n = 1 + ((mask & DENSOR_OLD) ? 2 : 0) + ((mask & DENSOR_TMP119) ? 2 : 0)
        + ((mask & DENSOR_PD) ? 2 : 0) + ((mask & DENSOR_ACCEL) ? 6 : 0);
    return (uint8_t)((n + 3) & ~3u);
}
bool densor_period_valid(uint32_t n) { return n > 0 && (n < 60 || (n <= 3540 && n % 60 == 0)); }

bool densor_header_valid(const uint8_t *h, uint16_t capacity) {
    if (!capacity_valid(capacity) || memcmp(h, "DNR1", 4) || h[4] != 3 || h[5] != 1
        || h[6] != 64 || h[7] || densor_u16(h + 8) != capacity
        || densor_u16(h + 10) != DENSOR_LOG_START || h[14] > 1 || h[15] != 15
        || h[16] || (h[17] & 0xf0) || h[18] > 59 || h[19] || !zeros(h + 32, 30)
        || !crc_valid(h, 64)) return false;
    if (!h[12]) return !h[18] && !h[13] && !densor_u32(h + 20) && !densor_u32(h + 24) && !densor_u32(h + 28);
    return h[13] == densor_stride(h[12]) && h[13] != 0 && densor_u32(h + 20) != 0
        && densor_u32(h + 20) == densor_u32(h + 24) && densor_period_valid(densor_u32(h + 28))
        && (h[12] & h[17]) == h[12];
}
bool densor_pointer_valid(const densor_session *s) {
    if (s->pointer < DENSOR_LOG_START || s->pointer > s->capacity || (s->pointer & 3)) return false;
    return s->header[13] ? (s->pointer - DENSOR_LOG_START) % s->header[13] == 0 : s->pointer == DENSOR_LOG_START;
}
uint8_t densor_encode(uint8_t mask, const densor_sample *v, uint8_t out[16]) {
    uint8_t stride = densor_stride(mask), pos = 1;
    if (!stride || (v->supply > 15 && v->supply != 255)
        || ((mask & 7) ? v->supply == 255 : v->supply != 255)
        || ((mask & DENSOR_PD) && v->photodiode > 4095)) return 0;
    memset(out, 0, 16);
    out[0] = v->supply;
    if (mask & DENSOR_OLD) { densor_put16(out + pos, (uint16_t)v->old_temperature); pos += 2; }
    if (mask & DENSOR_TMP119) { densor_put16(out + pos, (uint16_t)v->tmp119_temperature); pos += 2; }
    if (mask & DENSOR_PD) { densor_put16(out + pos, v->photodiode); pos += 2; }
    if (mask & DENSOR_ACCEL) for (unsigned i = 0; i < 3; ++i, pos += 2) densor_put16(out + pos, (uint16_t)v->acceleration[i]);
    return stride;
}

uint8_t densor_write(const densor_io *io, uint16_t capacity, uint16_t address,
                     const uint8_t *data, uint16_t size) {
    if (address > capacity || size > capacity - address) return DENSOR_NO_SPACE;
    while (size) {
        uint16_t n = (uint16_t)(4 - (address & 3));
        if (n > size) n = size;
        if (!io->write(address, data, n) || !io->ready()) return DENSOR_IO;
        address += n; data += n; size -= n;
    }
    return DENSOR_OK;
}
static uint8_t status_write(const densor_io *io, densor_session *s, uint8_t state, uint8_t error, uint32_t ack) {
    densor_put16(s->status, (uint16_t)(densor_u16(s->status) + 1));
    s->status[2] = state; s->status[3] = error;
    densor_put32(s->status + 6, ack);
    densor_put32(s->status + 10, densor_u32(s->header + 20));
    seal(s->status, 16);
    return densor_write(io, s->capacity, DENSOR_STATUS, s->status, 16);
}
uint8_t densor_fail(const densor_io *io, densor_session *s, uint8_t error) {
    (void)status_write(io, s, error == DENSOR_NO_SPACE ? DENSOR_FULL : DENSOR_ERROR, error, densor_u32(s->status + 6));
    return error;
}
uint8_t densor_presence(const densor_io *io, densor_session *s, uint8_t detected) {
    if (detected & 0xf0) return DENSOR_BAD_CONFIG;
    if (s->status[4] == detected) return DENSOR_OK;
    s->status[4] = detected;
    return status_write(io, s, s->status[2], s->status[3], densor_u32(s->status + 6));
}
uint8_t densor_load(const densor_io *io, densor_session *s, uint16_t capacity) {
    uint8_t prefix[12];
    memset(s, 0, sizeof(*s)); s->capacity = capacity;
    if (!capacity_valid(capacity)) return DENSOR_BAD_HEADER;
    if (!io->read(0, prefix, 12) || !io->read(DENSOR_HEADER, s->header, 64)
        || !io->read(DENSOR_STATUS, s->status, 16)) return DENSOR_IO;
    if (memcmp(prefix, guard, 8) || !zeros(prefix + 10, 2) || !densor_header_valid(s->header, capacity)
        || !crc_valid(s->status, 16) || s->status[2] > DENSOR_FULL || s->status[3] > DENSOR_TIMEOUT
        || (s->status[4] & 0xf0) || s->status[5]
        || densor_u32(s->status + 10) != densor_u32(s->header + 20)) return DENSOR_BAD_HEADER;
    s->pointer = densor_u16(prefix + 8);
    if (!densor_pointer_valid(s)) return DENSOR_BAD_POINTER;
    if (s->status[2] == DENSOR_CONFIGURING || (s->status[2] == DENSOR_RUNNING && !s->header[12])) return DENSOR_BAD_STATE;
    return DENSOR_OK;
}
uint8_t densor_format(const densor_io *io, densor_session *s, uint16_t capacity, uint8_t detected) {
    uint8_t prefix[12] = {0};
    uint8_t blank[36] = {0};
    if (!capacity_valid(capacity) || (detected & 0xf0)) return DENSOR_BAD_CONFIG;
    memset(s, 0, sizeof(*s)); s->capacity = capacity; s->pointer = DENSOR_LOG_START;
    memset(prefix, 0xff, 8); densor_put16(prefix + 8, s->pointer);
    memcpy(s->header, "DNR1", 4); s->header[4] = 3; s->header[5] = 1; s->header[6] = 64;
    densor_put16(s->header + 8, capacity); densor_put16(s->header + 10, DENSOR_LOG_START);
    s->header[15] = 15; s->header[17] = detected; seal(s->header, 64);
    s->status[4] = detected;
    if (densor_write(io, capacity, 0, prefix, 12) || densor_write(io, capacity, DENSOR_PENDING, blank, 36)
        || densor_write(io, capacity, DENSOR_HEADER, s->header, 64)
        || status_write(io, s, DENSOR_STOPPED, DENSOR_OK, 0)
        || densor_write(io, capacity, 0, guard, 8)) return DENSOR_IO;
    return DENSOR_OK;
}

uint8_t densor_request(const densor_io *io, densor_session *s, uint8_t detected) {
    uint8_t marker[4], after[4], p[32];
    if (!io->read(DENSOR_MARKER, marker, 4)) return densor_fail(io, s, DENSOR_IO);
    uint32_t id = densor_u32(marker);
    if (!id || id <= densor_u32(s->status + 6)) return DENSOR_OK;
    if (!io->read(DENSOR_PENDING, p, 32) || !io->read(DENSOR_MARKER, after, 4)) return densor_fail(io, s, DENSOR_IO);
    if (memcmp(marker, after, 4) || id != densor_u32(p + 4) || !crc_valid(p, 32)) return DENSOR_OK;
    uint8_t error = DENSOR_OK;
    if (memcmp(p, "DCP3", 4) || p[11] != 3 || p[20] > 59 || !zeros(p + 21, 9)
        || densor_u32(p + 16) != densor_u32(s->header + 20)) error = DENSOR_BAD_CONFIG;
    else if (p[8] == DENSOR_APPLY_SETTINGS) {
        if (s->status[2] == DENSOR_CONFIGURING) error = DENSOR_BAD_STATE;
        else if (!densor_stride(p[9]) || p[10] > 1 || !densor_period_valid(densor_u32(p + 12))) error = DENSOR_BAD_CONFIG;
        else if ((p[9] & detected) != p[9]) error = DENSOR_SENSOR;
        else {
            if (status_write(io, s, DENSOR_CONFIGURING, DENSOR_OK, densor_u32(s->status + 6))) return DENSOR_IO;
            s->header[12] = p[9]; s->header[13] = densor_stride(p[9]); s->header[14] = p[10]; s->header[17] = detected; s->header[18] = p[20];
            densor_put32(s->header + 20, id); densor_put32(s->header + 24, id);
            densor_put32(s->header + 28, densor_u32(p + 12)); seal(s->header, 64);
            s->pointer = DENSOR_LOG_START; densor_put16(after, s->pointer);
            if (densor_write(io, s->capacity, DENSOR_POINTER, after, 2)
                || densor_write(io, s->capacity, DENSOR_HEADER, s->header, 64)) return DENSOR_IO;
            s->status[4] = detected;
            return status_write(io, s, DENSOR_RUNNING, DENSOR_OK, id);
        }
    } else error = DENSOR_BAD_CONFIG;
    (void)status_write(io, s, DENSOR_ERROR, error, id);
    return error;
}

uint8_t densor_append(const densor_io *io, densor_session *s, const densor_sample *sample) {
    uint8_t record[16], pointer[2];
    if (s->status[2] != DENSOR_RUNNING) return DENSOR_BAD_STATE;
    if (!densor_pointer_valid(s)) return densor_fail(io, s, DENSOR_BAD_POINTER);
    uint8_t stride = densor_encode(s->header[12], sample, record);
    if (!stride || stride != s->header[13]) return densor_fail(io, s, DENSOR_BAD_CONFIG);
    if (stride > s->capacity - s->pointer) return densor_fail(io, s, DENSOR_NO_SPACE);
    if (densor_write(io, s->capacity, s->pointer, record, stride)) return densor_fail(io, s, DENSOR_IO);
    /* Data programming is complete. Do not expose a committed RAM pointer until its write also completes. */
    uint16_t next = (uint16_t)(s->pointer + stride);
    densor_put16(pointer, next);
    if (densor_write(io, s->capacity, DENSOR_POINTER, pointer, 2)) return densor_fail(io, s, DENSOR_IO);
    s->pointer = next;
    if (stride > s->capacity - next) return status_write(io, s, DENSOR_FULL, DENSOR_NO_SPACE, densor_u32(s->status + 6));
    return DENSOR_OK;
}
