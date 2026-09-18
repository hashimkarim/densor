#ifndef DENSOR_MULTIRATE_H
#define DENSOR_MULTIRATE_H
#include "densor_r1.h"
#ifndef DENSOR_STORAGE
#define DENSOR_STORAGE 1
#endif
#ifndef DENSOR_TIMING
#define DENSOR_TIMING 1
#endif
enum { MR_VERSION=5, MR_REQUEST_SIZE=40, MR_MARKER=132, MR_LOG=168, MR_SLOTS=136, MR_PHASE=9, MR_RECOVERY=10,
       MR_STOP=1, MR_CACHE_SIZE=32, MR_RETAINED_BYTES=64 };
#define MR_MAX_SECONDS 2592000u
/* Byte-oriented retained state; never persist this enclosing structure. */
typedef struct {
    uint8_t h[64], status[16], cache[32];
    uint16_t capacity;
    uint8_t cache_slot;
} mr_session;
typedef struct {
    bool (*read)(uint16_t address, uint8_t *data, uint16_t size);
    bool (*write)(uint16_t address, const uint8_t *data, uint16_t size);
    /* Optional refresh after EEPROM initialization; board supplies this. */
    bool (*clock)(uint32_t *seconds);
} mr_ram;
uint8_t mr_crc8(const uint8_t *p, size_t n);
uint16_t mr_multiplier(const mr_session *s, unsigned i);
uint16_t mr_start(const mr_session *s, unsigned i);
uint16_t mr_end(const mr_session *s, unsigned i);
uint16_t mr_pointer(const mr_session *s, unsigned i);
void mr_set_pointer(mr_session *s, unsigned i, uint16_t p);
uint8_t mr_due(const mr_session *s, uint32_t tick);
uint8_t mr_config(uint8_t *h);
uint8_t mr_payload(unsigned i, const densor_sample *v, uint8_t *out);
bool mr_supply_valid(uint8_t mask, uint8_t supply);
uint8_t mr_slot_select(const uint8_t slots[8], uint16_t start, uint16_t end, uint8_t stride, uint16_t *p, uint8_t *gen);
uint8_t mr_checkpoint(const densor_io *io, mr_session *s, bool initialize);
uint8_t mr_load(const densor_io *io, const mr_ram *ram, mr_session *s, uint16_t capacity);
uint8_t mr_format(const densor_io *io, mr_session *s, uint16_t capacity, uint8_t detected);
uint8_t mr_request(const densor_io *io, const mr_ram *ram, mr_session *s, uint8_t detected, uint32_t now);
uint8_t mr_begin(const mr_ram *ram, mr_session *s);
uint8_t mr_commit(const densor_io *io, const mr_ram *ram, mr_session *s);
uint8_t mr_close(const densor_io *io, const mr_ram *ram, mr_session *s, uint8_t error);
uint8_t mr_presence(const densor_io *io, mr_session *s, uint8_t detected);
#endif
