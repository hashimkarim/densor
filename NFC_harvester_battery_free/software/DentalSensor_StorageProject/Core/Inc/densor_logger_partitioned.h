#ifndef DENSOR_LOGGER_PARTITIONED_H
#define DENSOR_LOGGER_PARTITIONED_H
#include "densor_logger.h"
/* Smart padding avoids a three-byte remainder, not every page crossing. */
static inline unsigned mr_partitioned_padded_size(unsigned bytes) {
    return bytes + (bytes % 4 == 3 ? 1u : 0u);
}
#endif
