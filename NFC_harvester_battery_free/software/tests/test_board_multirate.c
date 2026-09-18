/* Exercise the production board entry point and inspect the actual RTC alarm
 * writes. Each invocation is a fresh MCU boot, including zeroed static state.
 * This checks control flow only; the fake HAL cannot measure real power. */
#include "main.h"
#include "densor_board.h"
#include "densor_multirate.h"
#include "densor_logger.h"
#include "densor_scheduler.h"
#include <assert.h>
#include <setjmp.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

ADC_HandleTypeDef hadc;
I2C_HandleTypeDef hi2c1;
static uint8_t eeprom[512], rtc[128], pending[4];
static uint16_t pending_address, pending_size, tmp_config;
static unsigned acquisitions, conversions, reads, writes, tick;
static unsigned calendars, controls, bank_reads, bank_writes, retained_reads, retained_writes, alarm_writes;
static bool busy, suspended;
static const char *scenario;
static jmp_buf power_exit;
static bool is(const char *name) { return !strcmp(scenario, name); }
static bool mem_read(uint16_t a, uint8_t *p, uint16_t n) {
    assert((unsigned)a + n <= sizeof eeprom && !busy);
    memcpy(p, eeprom + a, n); return true;
}
static bool mem_write(uint16_t a, const uint8_t *p, uint16_t n) {
    assert(n && n <= 4 && a / 4 == (a + n - 1) / 4 && (unsigned)a + n <= sizeof eeprom && !busy);
    pending_address = a; pending_size = n; memcpy(pending, p, n); busy = true; return true;
}
static bool mem_ready(void) {
    assert(busy); memcpy(eeprom + pending_address, pending, pending_size); busy = false; return true;
}
static const densor_io io = {mem_read, mem_write, mem_ready};
static bool ram_read(uint16_t a,uint8_t *p,uint16_t n) { memcpy(p,rtc+64+a,n);return true; }
static bool ram_write(uint16_t a,const uint8_t *p,uint16_t n) { memcpy(rtc+64+a,p,n);return true; }
static const mr_ram ram={ram_read,ram_write,NULL};
static void request(const mr_session *s,unsigned command,unsigned period,unsigned delay) {
    uint8_t *p=eeprom+92;memset(p,0,MR_REQUEST_SIZE+4);memcpy(p,"DCP5",4);densor_put32(p+4,densor_u32(s->status+6)+1);
    p[8]=command;p[9]=8;p[10]=1;p[11]=MR_VERSION;densor_put16(p+12,period);densor_put16(p+20,1);
    densor_put32(p+22,densor_u32(s->h+20));p[26]=delay;p[27]=DENSOR_STORAGE;p[28]=DENSOR_TIMING;
    densor_put16(p+30+(DENSOR_STORAGE==1?6:0),(s->capacity-MR_LOG)/4);
    densor_put16(p+MR_REQUEST_SIZE-2,densor_crc16(p,MR_REQUEST_SIZE-2));densor_put32(p+MR_REQUEST_SIZE,densor_u32(p+4));
}
static void state(uint8_t value, uint8_t error) {
    uint8_t *p = eeprom + DENSOR_STATUS; p[2] = value; p[3] = error;
    densor_put16(p + 14, densor_crc16(p, 14));
}
HAL_StatusTypeDef HAL_I2C_Mem_Read(I2C_HandleTypeDef *bus, uint16_t dev, uint16_t reg,
                                 uint16_t width, uint8_t *p, uint16_t n, uint32_t timeout) {
    (void)bus; (void)width; assert(timeout == 25); memset(p, 0, n);
    if (dev == 0xa6) {
        ++reads; if (is("read-fail")) return HAL_ERROR;
        return mem_read(reg, p, n) ? HAL_OK : HAL_ERROR;
    }
    if (dev == 0xae) { assert(reg == 0x14 && n == 3); p[0] = 127; p[2] = is("bad-density") ? 0 : 3; }
    else if (dev == 0xd2) {
        if(reg==1 && n==6) ++calendars;
        if(reg==0x10) ++controls;
        if(reg==0x3f) ++bank_reads;
        if(reg>=64) retained_reads+=n;
        assert(reg + n <= sizeof rtc); memcpy(p, rtc + reg, n);
    }
    else if (dev == 0x90) {
        if (is("tmp-bus")) return HAL_ERROR;
        assert(n == 2); uint16_t v = reg == 0x0f ? 0x2117 : tmp_config;
        p[0] = (uint8_t)(v >> 8); p[1] = (uint8_t)v;
    } else if (dev == 0x32) {
        if (reg == 0x0f) p[0] = is("missing-sensor") ? 0 : 0x44;
        else if (reg == 0x27) p[0] = 1;
        else assert(reg == 0x28 && n == 6);
    } else assert(false);
    return HAL_OK;
}
HAL_StatusTypeDef HAL_I2C_Mem_Write(I2C_HandleTypeDef *bus, uint16_t dev, uint16_t reg,
                                  uint16_t width, uint8_t *p, uint16_t n, uint32_t timeout) {
    (void)bus; (void)width; assert(timeout == 25);
    if (dev == 0xa6) {
        ++writes; if (is("write-fail") || busy) return HAL_ERROR;
        return mem_write(reg, p, n) ? HAL_OK : HAL_ERROR;
    }
    if (dev == 0xd2) {
        if (is("rtc-write-fail")) return HAL_ERROR;
        assert(reg+n<=sizeof rtc);
        if(reg==0x3f) ++bank_writes;
        else if(reg>=64) retained_writes+=n;
        else ++alarm_writes;
        memcpy(rtc+reg,p,n);
        if (reg == 0x17 && p[0] == 0x80) { assert(!busy); longjmp(power_exit, 1); }
    } else if (dev == 0x90) {
        assert(reg == 1 && n == 2); tmp_config = (uint16_t)((p[0] << 8) | p[1]);
        if (tmp_config == 0x0c00) ++conversions;
    } else if (dev == 0x32) {
        assert(n == 1); if (reg == 0x22) ++acquisitions;
    } else assert(false);
    return HAL_OK;
}
HAL_StatusTypeDef HAL_I2C_IsDeviceReady(I2C_HandleTypeDef *bus, uint16_t dev, uint32_t attempts, uint32_t timeout) {
    (void)bus; assert(dev == 0xa6 && attempts == 1 && timeout == 2);
    if (is("ready-fail")) return HAL_ERROR;
    return mem_ready() ? HAL_OK : HAL_ERROR;
}
uint32_t HAL_I2C_GetError(I2C_HandleTypeDef *bus) { (void)bus; return 1; }
uint32_t HAL_GetTick(void) { return tick; }
void HAL_Delay(uint32_t n) { tick += n; }
void HAL_SuspendTick(void) { suspended = true; }
_Noreturn void __WFI(void) { assert(suspended); longjmp(power_exit, 2); }
/* Acceleration-only running fixtures avoid the physical VREF calibration
 * address. Any ADC use on an idle wake must fail this regression test. */
HAL_StatusTypeDef HAL_ADCEx_Calibration_Start(ADC_HandleTypeDef *a, uint32_t mode) { (void)a; (void)mode; abort(); }
HAL_StatusTypeDef HAL_ADC_Start(ADC_HandleTypeDef *a) { (void)a; abort(); }
HAL_StatusTypeDef HAL_ADC_PollForConversion(ADC_HandleTypeDef *a, uint32_t ms) { (void)a; (void)ms; abort(); }
uint32_t HAL_ADC_GetValue(ADC_HandleTypeDef *a) { (void)a; abort(); }
HAL_StatusTypeDef HAL_ADC_Stop(ADC_HandleTypeDef *a) { (void)a; abort(); }
static unsigned seconds(uint8_t b) { return (b >> 4) * 10u + (b & 15); }
static int wake(void) {
    int result = setjmp(power_exit);
    if (!result) { densor_board_run(); assert(false); }
    return result;
}
int main(int argc,char **argv) {
    assert(argc==3);scenario=argv[1];unsigned period=atoi(argv[2]);
    mr_session s; const uint32_t now=762479930u; /* 2024-02-28 23:58:50, epoch 2000. */
    rtc[1]=0x50;rtc[2]=0x58;rtc[3]=0x23;rtc[4]=0x28;rtc[5]=2;rtc[6]=0x24;
    assert(!mr_format(&io,&s,512,15));request(&s,2,period,0);assert(!mr_request(&io,&ram,&s,15,now));
    unsigned expected=period, expected_acquisitions=0;int expected_exit=1;
    uint8_t expected_state=DENSOR_RUNNING;
    if(is("running")) expected_acquisitions=1;
    if(is("stopped")) { assert(!mr_close(&io,&ram,&s,0));expected_state=DENSOR_STOPPED; }
    if(is("full")) { state(DENSOR_FULL,6);expected_state=DENSOR_FULL; }
    if(is("error")) { state(DENSOR_ERROR,5);expected_state=DENSOR_ERROR; }
    if(is("configuring")) {state(DENSOR_CONFIGURING,0);expected_state=DENSOR_CONFIGURING;expected=120;}
    if(is("cold")) {memset(rtc+64,0,64);expected_state=DENSOR_ERROR;}
    if(is("late")) {rtc[1]=0x52;expected_state=DENSOR_ERROR;}
    if(is("early")) {
        densor_sample sample={.supply=255};assert(!mr_begin(&ram,&s));assert(!mr_logger_append(&io,&s,8,&sample));assert(!mr_commit(&io,&ram,&s));
    }
    if(is("pending-stop")) {request(&s,1,period,0);expected_state=DENSOR_STOPPED;}
    if(is("missing-sensor") || is("tmp-bus") || is("clock-invalid")) expected_state=DENSOR_ERROR;
    if(is("clock-invalid")) rtc[1]=0xff;
    unsigned delay=is("startup-1")?1:is("startup-59")?59:0;
    if(delay) {assert(!mr_close(&io,&ram,&s,0));request(&s,2,period,delay);expected=delay*60;}
    if(is("write-fail") || is("ready-fail") || is("rtc-write-fail")) {expected_exit=2;expected_acquisitions=is("rtc-write-fail")?0:1;}
    acquisitions=0;writes=0;
    int result=wake();
    if(result!=expected_exit || acquisitions!=expected_acquisitions || eeprom[78]!=expected_state) {
        fprintf(stderr,"%s: exit %d/%d acq %u/%u state %u/%u\n",scenario,result,expected_exit,acquisitions,expected_acquisitions,eeprom[78],expected_state);abort();
    }
    assert(conversions==0);
    if(is("running")) assert(calendars==3 && controls==3 && bank_reads==6 && bank_writes==6
        && retained_reads==128 && retained_writes==64 && alarm_writes==10);
    if(result==1) {
        unsigned boot=is("clock-invalid")?0:is("late")?58*60+52:58*60+50;
        unsigned actual=(seconds(rtc[10])*60+seconds(rtc[9])+3600-boot)%3600;
        if(actual!=expected) {fprintf(stderr,"%s alarm %u/%u\n",scenario,actual,expected);abort();}
        assert(rtc[8]==0 && rtc[0x18]==0x17);
    } else assert(rtc[0x17]==0);
    if(is("stopped") || is("full") || is("error")) assert(writes==0);
    if(delay) {assert(densor_u32(eeprom+82)==2);assert(densor_u32(eeprom+36)==now+delay*60);}
    printf("v5 board %d/%d %s %u: verified\n",DENSOR_STORAGE,DENSOR_TIMING,scenario,period);
    return 0;
}
