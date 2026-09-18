#include "densor_scheduler_fsm.h"
uint8_t mr_scheduler_due(const mr_session *s, uint32_t now, uint8_t *due, bool *ready) {
    uint8_t error = mr_clock_check(s, now, ready);
    *due = (!error && *ready) ? mr_due(s, densor_u16(s->cache + 16)) : 0;
    return error;
}
void mr_scheduler_commit(mr_session *s) {
    densor_put32(s->cache + 12, densor_u32(s->cache + 12) + 1);
    densor_put16(s->cache + 16, (uint16_t)((densor_u16(s->cache + 16) + 1u) % densor_u16(s->h + 30)));
}
