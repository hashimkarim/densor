#ifndef DENSOR_TMP119_H
#define DENSOR_TMP119_H
#include <stdint.h>
typedef struct {
    uint8_t (*read)(uint8_t reg, uint16_t *value);
    uint8_t (*write)(uint8_t reg, uint16_t value);
    uint32_t (*now)(void);
    void (*delay)(uint32_t ms);
} tmp119_bus;
uint8_t tmp119_shutdown(const tmp119_bus *bus);
uint8_t tmp119_start(const tmp119_bus *bus, uint32_t *started);
uint8_t tmp119_result(const tmp119_bus *bus, uint32_t started, int16_t *result);
#endif
