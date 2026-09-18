#ifndef DENSOR_LOGGER_H
#define DENSOR_LOGGER_H
#include "densor_multirate.h"
bool mr_logger_layout_valid(const uint8_t *header, uint16_t capacity);
uint8_t mr_logger_count(const mr_session *s);
uint8_t mr_logger_stride(unsigned i);
uint8_t mr_logger_preflight(const mr_session *s, uint8_t due);
uint8_t mr_logger_append(const densor_io *io, mr_session *s, uint8_t due, const densor_sample *v);
uint8_t mr_logger_recover(const densor_io *io, mr_session *s);
#endif
