#ifndef DENSOR_R1_H
#define DENSOR_R1_H
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

enum { DENSOR_POINTER = 8, DENSOR_HEADER = 12, DENSOR_STATUS = 76,
       DENSOR_PENDING = 92, DENSOR_MARKER = 124, DENSOR_LOG_START = 128 };
enum { DENSOR_OLD = 1, DENSOR_TMP119 = 2, DENSOR_PD = 4, DENSOR_ACCEL = 8 };
enum { DENSOR_STOPPED, DENSOR_CONFIGURING, DENSOR_RUNNING, DENSOR_ERROR, DENSOR_FULL };
enum { DENSOR_OK, DENSOR_IO, DENSOR_BAD_HEADER, DENSOR_BAD_POINTER,
       DENSOR_BAD_CONFIG, DENSOR_SENSOR, DENSOR_NO_SPACE, DENSOR_BAD_STATE,
       DENSOR_TIMEOUT };
enum { DENSOR_APPLY_SETTINGS = 2 };

typedef struct {
    bool (*read)(uint16_t address, uint8_t *data, uint16_t size);
    bool (*write)(uint16_t address, const uint8_t *data, uint16_t size);
    /* Must return only after completion or a bounded timeout. */
    bool (*ready)(void);
} densor_io;

typedef struct {
    uint8_t header[64];
    uint8_t status[16];
    uint16_t capacity;
    uint16_t pointer;
} densor_session;

typedef struct {
    int16_t old_temperature, tmp119_temperature, acceleration[3];
    uint16_t photodiode;
    uint8_t supply;
} densor_sample;

uint16_t densor_u16(const uint8_t *p);
uint32_t densor_u32(const uint8_t *p);
void densor_put16(uint8_t *p, uint16_t v);
void densor_put32(uint8_t *p, uint32_t v);
uint16_t densor_crc16(const uint8_t *p, size_t size);
uint8_t densor_stride(uint8_t mask);
bool densor_period_valid(uint32_t seconds);
bool densor_header_valid(const uint8_t *header, uint16_t capacity);
bool densor_pointer_valid(const densor_session *s);
uint8_t densor_encode(uint8_t mask, const densor_sample *sample, uint8_t out[16]);
uint8_t densor_write(const densor_io *io, uint16_t capacity, uint16_t address,
                     const uint8_t *data, uint16_t size);
uint8_t densor_load(const densor_io *io, densor_session *s, uint16_t capacity);
/* Destructive; called ONLY by an explicit SWD provisioning operation. */
uint8_t densor_format(const densor_io *io, densor_session *s, uint16_t capacity,
                      uint8_t detected);
uint8_t densor_request(const densor_io *io, densor_session *s, uint8_t detected);
uint8_t densor_presence(const densor_io *io, densor_session *s, uint8_t detected);
uint8_t densor_fail(const densor_io *io, densor_session *s, uint8_t error);
uint8_t densor_append(const densor_io *io, densor_session *s, const densor_sample *sample);
#endif
