#include "densor_logger_shared.h"
#include <string.h>
uint8_t mr_logger_count(const mr_session *s) { (void)s; return 1; }
uint8_t mr_logger_stride(unsigned i) { (void)i; return 4; }
static unsigned record_size(uint8_t mask) {
    if (!mask || (mask & 0xf0)) return 0;
    unsigned n = 3;
    for (unsigned i = 0; i < 4; ++i) if (mask & (1u << i)) n += i == 3 ? 6 : 2;
    return (n + 3) & ~3u; /* R2a/R2b always pad to a whole 4-byte EEPROM page. */
}
bool mr_logger_layout_valid(const uint8_t *h, uint16_t capacity) {
    if (densor_u16(h+40)!=MR_LOG || densor_u16(h+48)!=capacity) return false;
    for (unsigned i=1;i<4;++i) if (densor_u16(h+40+2*i) || densor_u16(h+48+2*i)) return false;
    return true;
}
uint8_t mr_logger_preflight(const mr_session *s, uint8_t due) {
    if (due != mr_due(s, densor_u32(s->cache + 12))) return DENSOR_BAD_CONFIG;
    uint16_t p = mr_pointer(s, 0);
    if (p < MR_LOG || p > s->capacity || (p & 3)) return DENSOR_BAD_POINTER;
    if (record_size(due) > s->capacity - p) return DENSOR_NO_SPACE;
    return DENSOR_OK;
}
uint8_t mr_logger_append(const densor_io *io, mr_session *s, uint8_t due, const densor_sample *v) {
    uint8_t error = mr_logger_preflight(s, due);
    if (error || !due) return error;
    if (!mr_supply_valid(due, v->supply) || ((due & DENSOR_PD) && v->photodiode > 4095)) return DENSOR_BAD_CONFIG;
    uint8_t b[16] = {0}; b[0] = due; b[1] = v->supply; unsigned n = 2;
    for (unsigned i = 0; i < 4; ++i) if (due & (1u << i)) n += mr_payload(i, v, b + n);
    b[n] = mr_crc8(b, n); n = record_size(due);
    if (densor_write(io, s->capacity, mr_pointer(s, 0), b, n)) return DENSOR_IO;
    mr_set_pointer(s, 0, mr_pointer(s, 0) + n);
    return DENSOR_OK;
}
uint8_t mr_logger_recover(const densor_io *io, mr_session *s) {
    uint16_t p = MR_LOG, checkpoint = mr_pointer(s, 0); uint32_t tick = 0;
    bool boundary = checkpoint == MR_LOG;
    while (p < s->capacity) {
        uint8_t b[16], mask;
        if (!io->read(p, b, 4)) return DENSOR_IO;
        unsigned n = record_size(b[0]);
        if (!n || n > (unsigned)(s->capacity - p)) break;
        if (!io->read(p, b, n)) return DENSOR_IO;
        uint32_t next = UINT32_MAX;
        for (unsigned i=0;i<4;++i) {
            uint16_t m=mr_multiplier(s,i);
            if (m) { uint32_t t=((tick+m-1)/m)*m; if (t<next) next=t; }
        }
        tick=next; mask=mr_due(s,tick++);
        if (tick > MR_MAX_SECONDS / densor_u16(s->h + 28) + 1 || b[0] != mask || !mr_supply_valid(mask, b[1])) break;
        unsigned payload = 2; bool values = true;
        for (unsigned i = 0; i < 4; ++i) if (mask & (1u << i)) {
            if (i == 2 && densor_u16(b + payload) > 4095) values = false;
            payload += i == 3 ? 6 : 2;
        }
        if (!values || b[payload] != mr_crc8(b, payload)) break;
        bool padding = true;
        for (unsigned i = payload + 1; i < n; ++i) if (b[i]) padding = false;
        if (!padding) break;
        p += n;
        if (p == checkpoint) boundary = true;
    }
    if (!boundary || p < checkpoint) return MR_RECOVERY;
    mr_set_pointer(s, 0, p);
    return DENSOR_OK;
}
