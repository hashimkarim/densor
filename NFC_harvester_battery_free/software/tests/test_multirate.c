#include "densor_multirate.h"
#include "densor_logger.h"
#include "densor_scheduler.h"
#if DENSOR_STORAGE==1
#include "densor_logger_partitioned.h"
#endif
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
static uint8_t ee[8192], ram_bytes[64];
static int cut=-1, events;
static bool event(void) { return events++!=cut; }
static bool read_ee(uint16_t a,uint8_t *p,uint16_t n) { assert(a+n<=sizeof ee); memcpy(p,ee+a,n); return true; }
static bool write_ee(uint16_t a,const uint8_t *p,uint16_t n) {
    assert(n && n<=4 && a/4==(a+n-1)/4 && a+n<=sizeof ee);
    for (unsigned i=0;i<n;++i) { if(!event()) return false; ee[a+i]=p[i]; } return true;
}
static bool ready(void) { return event(); }
static bool read_ram(uint16_t a,uint8_t *p,uint16_t n) { assert(a+n<=64); memcpy(p,ram_bytes+a,n); return true; }
static bool write_ram(uint16_t a,const uint8_t *p,uint16_t n) {
    assert(a+n<=64); for(unsigned i=0;i<n;++i) { if(!event()) return false; ram_bytes[a+i]=p[i]; } return true;
}
static const densor_io io={read_ee,write_ee,ready};
static const mr_ram ram={read_ram,write_ram,NULL};
static void request(mr_session *s,uint8_t command,uint8_t mask,unsigned base,const uint16_t m[4],unsigned delay) {
    uint8_t *p=ee+92; memset(p,0,MR_REQUEST_SIZE); memcpy(p,"DCP5",4); densor_put32(p+4,densor_u32(s->status+6)+1);
    p[8]=command; p[9]=mask; p[10]=1; p[11]=MR_VERSION; densor_put16(p+12,base);
    for(unsigned i=0;i<4;++i) densor_put16(p+14+2*i,m && (mask&(1u<<i)) ? m[i] : 0);
    densor_put32(p+22,densor_u32(s->h+20)); p[26]=delay; p[27]=DENSOR_STORAGE; p[28]=DENSOR_TIMING;
    /* Test host implementation; the production allocator lives only in Android. */
#if DENSOR_STORAGE==1
    uint32_t lcm=1,total=0,weights[4]={0};uint16_t pages[4]={0},left=(s->capacity-MR_LOG)/4;
    for(unsigned i=0;i<4;++i) if(mask&(1u<<i)) {
        unsigned a=lcm,b=m[i];while(b){unsigned t=a%b;a=b;b=t;}lcm=lcm/a*m[i];
    }
    for(unsigned i=0;i<4;++i) if(mask&(1u<<i)) {
        pages[i]=i==3?2:1;left-=pages[i];weights[i]=(i==3?8:4)*(lcm/m[i]);total+=weights[i];
    }
    unsigned available=left;
    for(unsigned i=0;i<4;++i) if(weights[i]) {unsigned n=available*weights[i]/total;pages[i]+=n;left-=n;}
    for(unsigned i=0;left;i=(i+1)%4) if(weights[i]) {++pages[i];--left;}
    for(unsigned i=0;i<4;++i) densor_put16(p+30+2*i,pages[i]);
#else
    densor_put16(p+30,(s->capacity-MR_LOG)/4);
#endif
    densor_put16(p+MR_REQUEST_SIZE-2,densor_crc16(p,MR_REQUEST_SIZE-2)); densor_put32(ee+MR_MARKER,densor_u32(p+4));
}
static void setup(mr_session *s,unsigned mask,unsigned capacity,unsigned base,const uint16_t m[4],unsigned delay) {
    cut=-1; memset(ee,0xa5,sizeof ee); memset(ram_bytes,0x77,sizeof ram_bytes);
    assert(!mr_format(&io,s,capacity,15)); assert(!mr_load(&io,&ram,s,capacity));
    request(s,2,mask,base,m,delay); assert(!mr_request(&io,&ram,s,15,100000));
    assert(s->status[2]==DENSOR_RUNNING); assert(!mr_load(&io,&ram,s,capacity));
}
static uint8_t wake(mr_session *s,uint32_t now) {
    uint8_t mask=0; bool due=false; uint8_t e=mr_scheduler_due(s,now,&mask,&due);
    if(e || !due) return e;
    if((e=mr_logger_preflight(s,mask))) return e;
    densor_sample v={.old_temperature=-256,.tmp119_temperature=-128,.photodiode=1234,
        .acceleration={-16384,0,16384},.supply=(mask&7)?8:255};
    if((e=mr_begin(&ram,s))) return e;
    if(mask && (e=mr_logger_append(&io,s,mask,&v))) return e;
    return mr_commit(&io,&ram,s);
}
static void slot(uint8_t *b,unsigned p,unsigned gen) { densor_put16(b,p); b[2]=gen; b[3]=mr_crc8(b,3); }
static void tests(void) {
#if DENSOR_STORAGE==1
    const unsigned smart[] = {0,1,2,4,4,5,6,8,8,9,10,12,12,13,14,16,16};
    for(unsigned n=1;n<sizeof smart/sizeof smart[0];++n) assert(mr_partitioned_padded_size(n)==smart[n]);
#endif
    assert(mr_crc8((const uint8_t*)"123456789",9)==0xf4);
    uint8_t ff[8]; memset(ff,255,8); assert(mr_crc8(ff,3)!=255 && mr_crc8(ff,7)!=255);
    mr_session s; const uint16_t mixed[4]={12,12,3,1}, different[4]={12,6,3,1}, uniform[4]={1,1,1,1};
    for(unsigned mask=1;mask<16;++mask) for(unsigned workload=0;workload<3;++workload) {
        const uint16_t *m=workload==0?mixed:workload==1?different:uniform;
        setup(&s,mask,8192,10,m,0);
        unsigned counts[4]={0};
        for(unsigned tick=0;tick<301;++tick) {
            uint8_t due; bool active; assert(!mr_scheduler_due(&s,100000+10*tick,&due,&active) && active);
            for(unsigned i=0;i<4;++i) if(mask&(1u<<i)) { assert(!!(due&(1u<<i))==(tick%m[i]==0)); if(due&(1u<<i)) ++counts[i]; }
            assert(!wake(&s,100000+10*tick));
            uint32_t next=densor_u32(s.cache+12); assert(next==tick+1);
            assert(!wake(&s,100000+10*tick)); assert(densor_u32(s.cache+12)==next);
            assert(!mr_load(&io,&ram,&s,8192)); assert(densor_u32(s.cache+12)==next);
        }
        assert(!mr_close(&io,&ram,&s,0)); memset(ram_bytes,0,64);
        assert(!mr_load(&io,&ram,&s,8192)); assert(s.status[2]==DENSOR_STOPPED);
#if DENSOR_STORAGE==1
        for(unsigned i=0;i<4;++i) if(mask&(1u<<i)) assert(mr_pointer(&s,i)-mr_start(&s,i)==counts[i]*mr_logger_stride(i));
#endif
    }
    for(unsigned mask=1;mask<=3;++mask) {
        setup(&s,mask,512,120,uniform,0);
        for(unsigned tick=0;tick<8;++tick) assert(!wake(&s,100000+120*tick));
        assert(!mr_close(&io,&ram,&s,0));
    }
    setup(&s,3,512,120,uniform,59); uint8_t due; bool active;
    assert(!mr_scheduler_due(&s,100000,&due,&active) && !active);
    assert(!wake(&s,103540)); assert(densor_u32(s.cache+12)==1);
    assert(!wake(&s,103659)); assert(densor_u32(s.cache+12)==1);
    assert(!wake(&s,103661)); assert(densor_u32(s.cache+12)==2);
    assert(wake(&s,103782)==MR_PHASE); assert(wake(&s,103539)==MR_PHASE);
    setup(&s,1,512,1,uniform,0); assert(wake(&s,100001)==MR_PHASE);
    assert(wake(&s,100000+MR_MAX_SECONDS+1)==MR_PHASE);
    uint8_t h[64]; memcpy(h,s.h,64); densor_put16(h+32,0); assert(mr_config(h)==DENSOR_BAD_CONFIG);
    memcpy(h,s.h,64); h[12]=3; densor_put16(h+32,65535); densor_put16(h+34,65534); assert(mr_config(h));
    densor_put16(h+34,1); assert(!mr_config(h) && densor_u16(h+30)==65535 && densor_u16(h+56)==64);
    densor_put16(h+28,3540); assert(mr_config(h));
    uint8_t b[8]; uint16_t p; uint8_t gen;
    slot(b,160,255); slot(b+4,164,0); assert(!mr_slot_select(b,160,512,4,&p,&gen) && p==164 && gen==0);
    slot(b,160,128); assert(mr_slot_select(b,160,512,4,&p,&gen)==MR_RECOVERY);
    slot(b,160,0); assert(mr_slot_select(b,160,512,4,&p,&gen)==MR_RECOVERY);
    slot(b,516,1); assert(mr_slot_select(b,160,512,4,&p,&gen)==MR_RECOVERY);
    b[3]^=1; assert(!mr_slot_select(b,160,512,4,&p,&gen) && p==164);
    /* Every byte boundary of marker/data/checkpoint/clean-cache writes, plus readiness. */
    setup(&s,15,512,10,uniform,0);
    uint8_t saved_ee[8192],saved_ram[64]; memcpy(saved_ee,ee,sizeof ee); memcpy(saved_ram,ram_bytes,64); mr_session saved=s;
    events=0; assert(!wake(&s,100000)); int boundaries=events;
    uint16_t upper[4]; for(unsigned i=0;i<4;++i) upper[i]=mr_pointer(&s,i);
    for(int n=0;n<boundaries;++n) {
        memcpy(ee,saved_ee,sizeof ee); memcpy(ram_bytes,saved_ram,64); s=saved;
        cut=n; events=0; assert(wake(&s,100000)==DENSOR_IO); cut=-1;
        assert(!mr_load(&io,&ram,&s,512));
        for(unsigned i=0;i<4;++i) assert(mr_pointer(&s,i)>=mr_start(&s,i) && mr_pointer(&s,i)<=upper[i]);
        if(s.status[2]==DENSOR_RUNNING) { assert(densor_u32(s.cache+12)==0); for(unsigned i=0;i<4;++i) assert(mr_pointer(&s,i)==mr_start(&s,i)); }
        else assert(s.status[2]==DENSOR_ERROR);
    }
    /* Stop checkpoints/status cannot silently truncate committed data. */
    setup(&s,15,512,10,mixed,0);assert(!wake(&s,100000));
    memcpy(saved_ee,ee,sizeof ee);memcpy(saved_ram,ram_bytes,64);saved=s;
    for(unsigned i=0;i<4;++i) upper[i]=mr_pointer(&s,i);
    events=0;assert(!mr_close(&io,&ram,&s,0));int stopping=events;
    for(int n=0;n<stopping;++n) {
        memcpy(ee,saved_ee,sizeof ee);memcpy(ram_bytes,saved_ram,64);s=saved;cut=n;events=0;
        assert(mr_close(&io,&ram,&s,0)==DENSOR_IO);cut=-1;
        uint8_t loaded=mr_load(&io,&ram,&s,512);assert(!loaded || loaded==DENSOR_BAD_HEADER);
        if(!loaded) for(unsigned i=0;i<4;++i) assert(mr_pointer(&s,i)==upper[i]);
    }
    /* Every initialization boundary: a new session must never accept an old tail. */
    setup(&s,15,512,10,mixed,0); assert(!wake(&s,100000)); assert(!mr_close(&io,&ram,&s,0));
    request(&s,2,15,10,mixed,0);
    memcpy(saved_ee,ee,sizeof ee); memcpy(saved_ram,ram_bytes,64); saved=s;
    events=0; assert(!mr_request(&io,&ram,&s,15,200000)); int initialization=events;
    for(int n=0;n<initialization;++n) {
        memcpy(ee,saved_ee,sizeof ee);memcpy(ram_bytes,saved_ram,64);s=saved;cut=n;events=0;
        assert(mr_request(&io,&ram,&s,15,200000)==DENSOR_IO);cut=-1;
        uint8_t loaded=mr_load(&io,&ram,&s,512);
        assert(loaded==DENSOR_OK || loaded==DENSOR_BAD_HEADER || loaded==DENSOR_BAD_STATE);
        if(!loaded && densor_u32(s.h+20)==2) for(unsigned i=0;i<4;++i) assert(mr_pointer(&s,i)==mr_start(&s,i));
    }
    /* Lost/ambiguous cache generations are replaced without leaving a competing copy. */
    setup(&s,15,512,10,uniform,0);assert(!wake(&s,100000));
    memcpy(ram_bytes+32,ram_bytes,32);ram_bytes[2]=70;ram_bytes[34]=198;
    densor_put16(ram_bytes+30,densor_crc16(ram_bytes,30));densor_put16(ram_bytes+62,densor_crc16(ram_bytes+32,30));
    assert(!mr_load(&io,&ram,&s,512) && s.status[2]==DENSOR_ERROR);
    events=0;assert(!mr_load(&io,&ram,&s,512));assert(events==0);
    /* Empty slots and a large LCM use arithmetic, including stage wrap. */
    const uint16_t large[4]={65535,0,0,0}; setup(&s,1,512,1,large,0);
    assert(!wake(&s,100000));assert(!wake(&s,100001));assert(densor_u32(s.cache+12)==2);
    densor_put32(s.cache+12,65534);densor_put16(s.cache+16,65534);
    assert(!wake(&s,165534));assert(densor_u16(s.cache+16)==0);
    assert(!wake(&s,165535));
    /* Cold scanning rejects a CRC-valid pointer into a shared record. */
#if DENSOR_STORAGE==2
    setup(&s,15,512,10,mixed,0);assert(!wake(&s,100000));
    slot(ee+MR_SLOTS,MR_LOG+4,1);slot(ee+MR_SLOTS+4,MR_LOG+4,1);memset(ram_bytes,0,64);
    assert(mr_load(&io,&ram,&s,512)==MR_RECOVERY);
#endif
    /* A successful wake survives ordinary MCU resets; loss of RTC closes it. */
    setup(&s,15,512,10,mixed,0); assert(!wake(&s,100000)); memset(ram_bytes,0,64);
    assert(!mr_load(&io,&ram,&s,512) && s.status[2]==DENSOR_ERROR && s.status[3]==MR_PHASE);
    /* Reinitialization invalidates a previously valid tail, including all-zero payloads. */
    request(&s,2,15,10,mixed,0); assert(!mr_request(&io,&ram,&s,15,200000)); memset(ram_bytes,0,64);
    assert(!mr_load(&io,&ram,&s,512)); for(unsigned i=0;i<4;++i) assert(mr_pointer(&s,i)==mr_start(&s,i));
    /* Exact full boundary and all-stream preflight. */
    setup(&s,DENSOR_STORAGE==1?8:1,512,120,uniform,0); unsigned tick=0; uint8_t e;
    while(!(e=wake(&s,100000+120*tick))) ++tick;
    assert(e==DENSOR_NO_SPACE); assert(mr_pointer(&s,DENSOR_STORAGE==1?3:0)==512);
    assert(!mr_close(&io,&ram,&s,e) && s.status[2]==DENSOR_FULL);
    /* Missing selected hardware, partial request, and installed strategy mismatch. */
    setup(&s,1,512,120,uniform,0); assert(!mr_close(&io,&ram,&s,0));
    request(&s,2,3,120,uniform,0); assert(!mr_request(&io,&ram,&s,1,200000)); assert(s.status[3]==DENSOR_SENSOR);
    request(&s,2,1,120,uniform,0); ee[122]^=1; unsigned old=densor_u32(s.status+6); assert(!mr_request(&io,&ram,&s,15,200000)); assert(densor_u32(s.status+6)==old);
    /* Android owns allocation. Accept a deliberately non-proportional layout. */
#if DENSOR_STORAGE==1
    setup(&s,3,512,120,uniform,0);assert(!mr_close(&io,&ram,&s,0));
    request(&s,2,3,120,uniform,0);
    densor_put16(ee+92+30,1);densor_put16(ee+92+32,(512-MR_LOG)/4-1);
    densor_put16(ee+92+38,densor_crc16(ee+92,38));
    assert(!mr_request(&io,&ram,&s,15,200000) && s.status[2]==DENSOR_RUNNING);
    assert(mr_start(&s,0)==MR_LOG && mr_end(&s,0)==MR_LOG+4 && mr_start(&s,1)==MR_LOG+4 && mr_end(&s,1)==512);
    assert(!mr_load(&io,&ram,&s,512));
#endif
    /* Reject zero, excessive, partial and disabled-stream allocations before erasing data. */
    for(unsigned fault=0;fault<4;++fault) {
        setup(&s,1,512,120,uniform,0);assert(!wake(&s,100000));assert(!mr_close(&io,&ram,&s,0));
        uint8_t old_header[64],old_data[512-MR_LOG];memcpy(old_header,s.h,64);memcpy(old_data,ee+MR_LOG,sizeof old_data);
        request(&s,2,1,120,uniform,0);
        densor_put16(ee+92+30,fault==0?0:fault==1?65535:(512-MR_LOG)/4-1);
        if(fault==3)densor_put16(ee+92+32,1);
        densor_put16(ee+92+38,densor_crc16(ee+92,38));
        assert(!mr_request(&io,&ram,&s,15,200000) && s.status[3]==DENSOR_BAD_CONFIG);
        assert(!memcmp(old_header,s.h,64) && !memcmp(old_data,ee+MR_LOG,sizeof old_data));
    }
#if DENSOR_STORAGE==1
    setup(&s,9,512,120,uniform,0);assert(!mr_close(&io,&ram,&s,0));request(&s,2,9,120,uniform,0);
    densor_put16(ee+92+30,(512-MR_LOG)/4-1);densor_put16(ee+92+36,1);
    densor_put16(ee+92+38,densor_crc16(ee+92,38));
    assert(!mr_request(&io,&ram,&s,15,200000) && s.status[3]==DENSOR_BAD_CONFIG);
#endif
    printf("Initialization: %d, stop: %d power-cut boundaries passed\n",initialization,stopping);
    printf("v5 %d/%d: modes, schedules, rollover, bounds, recovery and %d transaction fault boundaries passed\n",DENSOR_STORAGE,DENSOR_TIMING,boundaries);
}
int main(int argc,char **argv) {
    if(argc>1 && !strcmp(argv[1],"--apply-request")) {
        assert(argc==5);mr_session s;memset(ram_bytes,0,64);
        FILE *f=fopen(argv[2],"rb");assert(f);unsigned cap=fread(ee,1,sizeof ee,f);fclose(f);
        assert(!mr_load(&io,&ram,&s,cap));
        f=fopen(argv[3],"rb");assert(f);assert(fread(ee+92,1,MR_REQUEST_SIZE,f)==MR_REQUEST_SIZE);fclose(f);
        uint8_t request_copy[MR_REQUEST_SIZE];memcpy(request_copy,ee+92,MR_REQUEST_SIZE);
        densor_put32(ee+MR_MARKER,densor_u32(ee+96));
        assert(!mr_request(&io,&ram,&s,15,200000) && s.status[2]==DENSOR_RUNNING);
        for(unsigned i=0;i<4;++i) assert(mr_end(&s,i)-mr_start(&s,i)==4*densor_u16(request_copy+30+2*i));
        assert(densor_u32(s.h+20)==densor_u32(request_copy+4));
        assert(!mr_load(&io,&ram,&s,cap));assert(!wake(&s,200000+s.h[18]*60u));assert(!mr_close(&io,&ram,&s,0));
        f=fopen(argv[4],"wb");assert(f);assert(fwrite(ee,1,cap,f)==cap);fclose(f);return 0;
    }
    if(argc>1 && !strcmp(argv[1],"--dump")) {
        assert(argc==6); mr_session s; unsigned mask=atoi(argv[3]),cap=atoi(argv[4]),kind=atoi(argv[5]);
        uint16_t m[4]={12,6,3,1}; if(kind==1) for(unsigned i=0;i<4;++i) m[i]=1;
        setup(&s,mask,cap,10,m,0);
        for(unsigned tick=0;tick<25;++tick) { uint8_t e=wake(&s,100000+10*tick); if(e==DENSOR_NO_SPACE) break; assert(!e); }
        assert(!mr_close(&io,&ram,&s,0));
        FILE *f=fopen(argv[2],"wb"); assert(f); assert(fwrite(ee,1,cap,f)==cap); fclose(f); return 0;
    }
    tests(); return 0;
}
