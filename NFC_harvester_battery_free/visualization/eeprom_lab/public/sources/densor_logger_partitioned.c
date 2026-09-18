#include "densor_logger_partitioned.h"
#include <string.h>
uint8_t mr_logger_count(const mr_session *s) { (void)s; return 4; }
/* R3a/R3b smart padding: add one zero only for a 3-byte page remainder.
 * Current payload+supply+CRC lengths are already 4/8 bytes, so no pad is needed. */
uint8_t mr_logger_stride(unsigned i) { return mr_partitioned_padded_size((i == 3 ? 6 : 2) + 2); }
bool mr_logger_layout_valid(const uint8_t *h, uint16_t capacity) {
    /* Android chooses sizes. Validate only complete coverage, enabled streams,
     * page alignment and space for at least one record; never reallocate here. */
    uint16_t p = MR_LOG;
    for (unsigned i = 0; i < 4; ++i) {
        uint16_t start=densor_u16(h+40+2*i), end=densor_u16(h+48+2*i);
        if (!(h[12] & (1u<<i))) { if (start || end) return false; continue; }
        if (start!=p || end<start || end>capacity || (end&3) || end-start<mr_logger_stride(i)) return false;
        p=end;
    }
    return p==capacity;
}
uint8_t mr_logger_preflight(const mr_session *s, uint8_t due) {
    if (due != mr_due(s, densor_u32(s->cache + 12))) return DENSOR_BAD_CONFIG;
    for (unsigned i = 0; i < 4; ++i) if (due & (1u << i)) {
        uint16_t p = mr_pointer(s, i);
        if (p < mr_start(s, i) || p > mr_end(s, i) || (p - mr_start(s, i)) % mr_logger_stride(i)) return DENSOR_BAD_POINTER;
        if (mr_logger_stride(i) > mr_end(s, i) - p) return DENSOR_NO_SPACE;
    }
    return DENSOR_OK;
}
uint8_t mr_logger_append(const densor_io *io, mr_session *s, uint8_t due, const densor_sample *v) {
    uint8_t error = mr_logger_preflight(s, due);
    if (error) return error;
    if (!mr_supply_valid(due, v->supply) || ((due & DENSOR_PD) && v->photodiode > 4095)) return DENSOR_BAD_CONFIG;
    for (unsigned i = 0; i < 4; ++i) if (due & (1u << i)) {
        uint8_t b[8] = {0}; unsigned n = mr_payload(i, v, b); b[n++] = v->supply; b[n] = mr_crc8(b, n); ++n;
        n = mr_partitioned_padded_size(n);
        if (densor_write(io, s->capacity, mr_pointer(s, i), b, n)) return DENSOR_IO;
        mr_set_pointer(s, i, mr_pointer(s, i) + n);
    }
    return DENSOR_OK;
}
uint8_t mr_logger_recover(const densor_io *io, mr_session *s) {
    for (unsigned i = 0; i < 4; ++i) if (mr_multiplier(s, i)) {
        uint16_t checkpoint = mr_pointer(s, i), p = mr_start(s, i); unsigned n = mr_logger_stride(i);
        uint8_t b[8];
        for (; n <= (unsigned)(mr_end(s, i) - p); p += n) {
            if (!io->read(p, b, n)) return DENSOR_IO;
            uint8_t supply = b[n-2];
            uint32_t tick = (uint32_t)((p-mr_start(s,i))/n)*mr_multiplier(s,i);
            if (mr_crc8(b, n-1) != b[n-1] || (supply > 15 && supply != 255)
                || tick>MR_MAX_SECONDS/densor_u16(s->h+28) || !mr_supply_valid(mr_due(s,tick),supply) || (i == 2 && densor_u16(b) > 4095)) break;
        }
        if (p < checkpoint) return MR_RECOVERY;
        mr_set_pointer(s, i, p);
    }
    return DENSOR_OK;
}
