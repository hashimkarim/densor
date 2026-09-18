#ifndef DENSOR_BOARD_H
#define DENSOR_BOARD_H
#include <stdint.h>
extern volatile uint32_t densor_provision_request;
void densor_boot_gate(void);
void densor_board_run(void);
#endif
