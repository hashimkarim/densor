#ifndef DENSOR_SCHEDULER_H
#define DENSOR_SCHEDULER_H
#include "densor_multirate.h"
/* ready=false: early/duplicate wake, no advancement. */
uint8_t mr_scheduler_due(const mr_session *s, uint32_t now, uint8_t *due, bool *ready);
void mr_scheduler_commit(mr_session *s);
uint8_t mr_clock_check(const mr_session *s, uint32_t now, bool *ready);
uint32_t mr_next_time(const mr_session *s);
#endif
