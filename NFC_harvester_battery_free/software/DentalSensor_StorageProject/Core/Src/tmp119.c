#include "tmp119.h"
#include "densor_r1.h"

/* TMP119 SNIS236: bus words are MSB first; HAL adapter handles that encoding.
 * No EEPROM unlock/write, no ALERT interrupt. All poll loops are bounded. */
uint8_t tmp119_shutdown(const tmp119_bus *bus) {
    uint16_t value;
    uint8_t error = bus->read(0x0f, &value);
    if (error) return error;
    if (value != 0x2117) return DENSOR_SENSOR;
    uint32_t started = bus->now();
    do {
        error = bus->read(1, &value);
        if (error) return error;
        if (!(value & 0x1000)) {
            error = bus->write(1, 0x0400);
            if (error) return error;
            error = bus->read(1, &value);
            if (error) return error;
            return (value & 0x0fff) == 0x0400 ? DENSOR_OK : DENSOR_SENSOR;
        }
        bus->delay(1);
    } while ((uint32_t)(bus->now() - started) < 10);
    return DENSOR_TIMEOUT;
}
uint8_t tmp119_start(const tmp119_bus *bus, uint32_t *started) {
    uint8_t error = bus->write(1, 0x0c00); /* One shot, AVG=00. */
    *started = bus->now();
    return error;
}
uint8_t tmp119_result(const tmp119_bus *bus, uint32_t started, int16_t *result) {
    uint16_t value;
    /* 17.5 ms maximum conversion time, 25 ms software deadline. Bus calls
     * have separate finite deadlines. Unsigned subtraction handles tick wrap. */
    for (;;) {
        uint8_t error = bus->read(1, &value);
        if (error) return error;
        if (value & 0x2000) {
            error = bus->read(0, &value);
            if (!error) *result = (int16_t)value;
            return error;
        }
        if ((uint32_t)(bus->now() - started) >= 25) return DENSOR_TIMEOUT;
        bus->delay(1);
    }
}
