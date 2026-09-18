# R1 stack review (software evidence only)

Toolchain: ARM GNU GCC 15.2.0, Cortex-M0+, `-Os -g3 -fstack-usage`, no LTO.
The candidate includes the ELF, linker map and concatenated compiler `.su`
reports in `compiler-stack-usage.tsv`. Recompute this review if code or flags
change. Compiler frames are estimates for individual functions, not observed
runtime high-water marks.

The startup-delay restoration adds 72 Flash bytes (13,504 total) and leaves the
reviewed frames below unchanged. The linked R1 image has 312 bytes of static SRAM, a 512-byte heap reservation,
a 1,024-byte stack reservation and 200 bytes outside those linked allocations.
Those 200 bytes are **not** a measured remaining stack margin. The application
uses fixed-size buffers. No `malloc`, `calloc`, `realloc`, `free` or printf
implementation is linked in this image; the heap reservation is unchanged.

The source and `.su` review identifies these important paths:

| Path | Sum of listed compiler frames |
| --- | ---: |
| `main` → `SystemClock_Config` → `HAL_RCC_OscConfig` | 56 + 104 + 40 = 200 B, before deeper HAL calls |
| `main` → `densor_board_run` → `densor_request` → `status_write` → `densor_write` → `write_memory` → `write_reg` → `HAL_I2C_Mem_Write` → `I2C_WaitOnTXISFlagUntilTimeout` → `I2C_IsErrorOccurred` | 56 + 80 + 96 + 24 + 32 + 16 + 32 + 48 + 24 + 32 = 440 B |
| `main` → `densor_board_run` → `tmp119_result` → `tmp_read` → `read_reg` → `HAL_I2C_Mem_Read` | 56 + 80 + 40 + 32 + 32 + 48 = 288 B, before deeper HAL calls |

The memory and TMP119 callbacks are indirect calls; they are included explicitly
in the paths above. Several acquisition helpers are inlined into
`densor_board_run`, whose frame already includes their local storage. Tail calls
may make a summed path conservative. These selected paths are not a proof of
the global worst case: compiler runtime routines and assembly also need review.

Normal acquisition uses polling rather than DMA or peripheral interrupts. The
enabled SysTick handler has an 8-byte compiler frame and calls `HAL_IncTick`
(0-byte reported frame). Cortex-M exception entry additionally stacks eight
registers (32 bytes), with possible alignment padding. Fault/NMI handlers loop;
they do not perform logging or formatted output. The startup vector table and
HAL clock setup must be revisited if interrupt use changes.

Before publication, fill SRAM with a known pattern without overwriting live
state, exercise the full sensor/configuration/error matrix on the board, inspect
the lowest untouched stack address, and account for interrupt nesting. Record
peak stack, peak heap and remaining margin separately. **All physical stack,
heap and interrupt-margin measurements remain unmeasured.** No linker limit or
reservation was changed to make the build fit.
