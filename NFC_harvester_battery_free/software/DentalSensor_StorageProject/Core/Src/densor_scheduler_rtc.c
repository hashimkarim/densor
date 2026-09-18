#include "densor_scheduler_rtc.h"
uint8_t mr_scheduler_due(const mr_session *s, uint32_t now, uint8_t *due, bool *ready) {
    uint8_t error = mr_clock_check(s, now, ready);
    *due = 0;
    if (!error && *ready) {
        uint32_t tick = (now - densor_u32(s->h + 24)) / densor_u16(s->h + 28);
        if (tick != densor_u32(s->cache + 12)) return MR_PHASE;
        *due = mr_due(s, tick);
    }
    return error;
}
void mr_scheduler_commit(mr_session *s) {
    uint32_t next = densor_u32(s->cache + 12) + 1;
    densor_put32(s->cache + 12, next);
    densor_put16(s->cache + 16, (uint16_t)(next % densor_u16(s->h + 30)));
}
