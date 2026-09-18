#include "densor_multirate.h"
#include "densor_logger.h"
#include "densor_scheduler.h"
#include <string.h>

uint8_t mr_crc8(const uint8_t *p, size_t n) {
    uint8_t c = 0;
    while (n--) { c ^= *p++; for (unsigned i=0; i<8; ++i) c = (uint8_t)((c << 1) ^ ((c & 128) ? 7 : 0)); }
    return c;
}
static bool valid16(const uint8_t *b, unsigned n) { return densor_u16(b+n-2) == densor_crc16(b,n-2); }
static void seal16(uint8_t *b, unsigned n) { densor_put16(b+n-2,densor_crc16(b,n-2)); }
static bool zero(const uint8_t *p, unsigned n) { while (n--) if (*p++) return false; return true; }
static bool capacity_valid(uint16_t n) { return n==512 || n==2048 || n==8192; }
uint16_t mr_multiplier(const mr_session *s, unsigned i) { return densor_u16(s->h+32+2*i); }
uint16_t mr_start(const mr_session *s, unsigned i) { return densor_u16(s->h+40+2*i); }
uint16_t mr_end(const mr_session *s, unsigned i) { return densor_u16(s->h+48+2*i); }
uint16_t mr_pointer(const mr_session *s, unsigned i) { return densor_u16(s->cache+18+2*i); }
void mr_set_pointer(mr_session *s, unsigned i, uint16_t p) { densor_put16(s->cache+18+2*i,p); }
uint8_t mr_due(const mr_session *s, uint32_t tick) {
    uint8_t mask=0;
    for (unsigned i=0;i<4;++i) { uint16_t m=mr_multiplier(s,i); if (m && tick%m==0) mask |= 1u<<i; }
    return mask;
}
static uint16_t gcd(uint16_t a, uint16_t b) { while (b) { uint16_t t=a%b; a=b; b=t; } return a; }
uint8_t mr_config(uint8_t *h) {
    uint16_t base=densor_u16(h+28); uint32_t lcm=1;
    if (!h[12] || (h[12]&0xf0) || h[14]>1 || h[18]>59 || !base || base>3540) return DENSOR_BAD_CONFIG;
    for (unsigned i=0;i<4;++i) {
        uint16_t m=densor_u16(h+32+2*i);
        if (!(h[12]&(1u<<i))) { if(m) return DENSOR_BAD_CONFIG; continue; }
        if (!m || (uint32_t)m*base>MR_MAX_SECONDS) return DENSOR_BAD_CONFIG;
        lcm=(lcm/gcd((uint16_t)lcm,m))*m;
        if (lcm>65535) return DENSOR_BAD_CONFIG;
    }
    densor_put16(h+30,(uint16_t)lcm); densor_put16(h+56,lcm<64 ? (uint16_t)lcm : 64);
    h[19]=base>1 ? 1 : 0;
    return DENSOR_OK;
}
bool mr_supply_valid(uint8_t mask, uint8_t supply) { return (mask&7) ? supply<=15 : supply==255; }
uint8_t mr_payload(unsigned i, const densor_sample *v, uint8_t *out) {
    int16_t value = i==0 ? v->old_temperature : i==1 ? v->tmp119_temperature : (int16_t)v->photodiode;
    if (i==3) { for (unsigned j=0;j<3;++j) densor_put16(out+2*j,(uint16_t)v->acceleration[j]); return 6; }
    densor_put16(out,(uint16_t)value); return 2;
}
/* 0/1 chosen, 2 ambiguous. Equal sequences require identical serialized data. */
static unsigned newer(const uint8_t *a, const uint8_t *b, unsigned n, unsigned seq) {
    uint8_t d=(uint8_t)(b[seq]-a[seq]);
    if (!d) return memcmp(a,b,n) ? 2 : 0;
    if (d==128) return 2;
    return d<128 ? 1 : 0;
}
uint8_t mr_slot_select(const uint8_t b[8], uint16_t start, uint16_t end, uint8_t stride, uint16_t *p, uint8_t *gen) {
    bool valid[2];
    for (unsigned i=0;i<2;++i) {
        const uint8_t *v=b+4*i; uint16_t a=densor_u16(v);
        valid[i]=mr_crc8(v,3)==v[3];
        if (valid[i] && (a<start || a>end || (a-start)%stride)) return MR_RECOVERY;
    }
    if (!valid[0] && !valid[1]) return MR_RECOVERY;
    unsigned chosen=valid[0] ? 0 : 1;
    if (valid[0] && valid[1]) chosen=newer(b,b+4,4,2);
    if (chosen==2) return MR_RECOVERY;
    *p=densor_u16(b+4*chosen); *gen=b[4*chosen+2]; return DENSOR_OK;
}
static bool pointer_valid(const mr_session *s, unsigned i) {
    uint16_t p=mr_pointer(s,i), start=mr_start(s,i), end=mr_end(s,i);
    return p>=start && p<=end && (p-start)%mr_logger_stride(i)==0;
}
uint8_t mr_checkpoint(const densor_io *io, mr_session *s, bool initialize) {
    for (unsigned i=0;i<mr_logger_count(s);++i) if (mr_end(s,i)) {
        if (!pointer_valid(s,i)) return DENSOR_BAD_POINTER;
        uint8_t b[4], gen=initialize ? 0 : (uint8_t)(s->cache[26+i]+1);
        densor_put16(b,mr_pointer(s,i)); b[2]=gen; b[3]=mr_crc8(b,3);
        if (densor_write(io,s->capacity,MR_SLOTS+8*i+4*(gen&1),b,4)) return DENSOR_IO;
        if (initialize && densor_write(io,s->capacity,MR_SLOTS+8*i+4,b,4)) return DENSOR_IO;
        s->cache[26+i]=gen;
    }
    return DENSOR_OK;
}
static uint8_t cache_write(const mr_ram *ram, mr_session *s, uint8_t transaction) {
    uint8_t check[32];
    s->cache[0]='R'; s->cache[1]='5'; ++s->cache[2]; s->cache[3]=transaction;
    densor_put32(s->cache+4,densor_u32(s->h+20)); densor_put32(s->cache+8,densor_u32(s->h+24));
    seal16(s->cache,32); unsigned slot=s->cache_slot^1u;
    if (!ram->write(slot*32,s->cache,32) || !ram->read(slot*32,check,32) || memcmp(check,s->cache,32)) return DENSOR_IO;
    s->cache_slot=(uint8_t)slot; return DENSOR_OK;
}
static uint8_t cache_initialize(const mr_ram *ram, mr_session *s) {
    const uint8_t blank[16]={0};
    for (unsigned a=0;a<64;a+=16) if (!ram->write(a,blank,16)) return DENSOR_IO;
    s->cache_slot=1; s->cache[2]=0;
    return cache_write(ram,s,0);
}
uint8_t mr_begin(const mr_ram *ram, mr_session *s) { return cache_write(ram,s,1); }
static uint8_t status_write(const densor_io *io, mr_session *s, uint8_t state, uint8_t error, uint32_t ack) {
    densor_put16(s->status,densor_u16(s->status)+1); s->status[2]=state; s->status[3]=error;
    densor_put32(s->status+6,ack); densor_put32(s->status+10,densor_u32(s->h+20)); seal16(s->status,16);
    return densor_write(io,s->capacity,DENSOR_STATUS,s->status,16);
}
uint8_t mr_presence(const densor_io *io, mr_session *s, uint8_t detected) {
    if (s->status[4]==detected) return DENSOR_OK;
    s->status[4]=detected;
    return status_write(io,s,s->status[2],s->status[3],densor_u32(s->status+6));
}
static bool header_valid(mr_session *s) {
    uint8_t *h=s->h;
    if (memcmp(h,"DNR2",4) || h[4]!=MR_VERSION || h[5]!=DENSOR_STORAGE || h[6]!=64 || h[7]!=DENSOR_TIMING
        || densor_u16(h+8)!=s->capacity || densor_u16(h+10)!=MR_LOG || h[13] || h[15]!=15
        || h[16] || (h[17]&0xf0) || !zero(h+58,4) || !valid16(h,64)) return false;
    if (!h[12]) return h[14]<=1 && !h[18] && !h[19] && zero(h+20,38);
    uint8_t copy[64]; memcpy(copy,h,64);
    if (mr_config(copy) || memcmp(copy,h,64) || !densor_u32(h+20) || (h[12]&h[17])!=h[12]
        || densor_u32(h+24)>3155759999u-MR_MAX_SECONDS) return false;
    return mr_logger_layout_valid(h,s->capacity);
}
static bool cache_valid(const mr_session *s, const uint8_t *b) {
    return b[0]=='R' && b[1]=='5' && b[3]<=1 && valid16(b,32)
        && densor_u32(b+4)==densor_u32(s->h+20) && densor_u32(b+8)==densor_u32(s->h+24);
}
static uint8_t recover(const densor_io *io, const mr_ram *ram, mr_session *s, uint8_t reason) {
    memset(s->cache,0,32);
    for (unsigned i=0;i<mr_logger_count(s);++i) if (mr_end(s,i)) {
        uint8_t b[8], gen; uint16_t p;
        if (!io->read(MR_SLOTS+8*i,b,8)) return DENSOR_IO;
        uint8_t error=mr_slot_select(b,mr_start(s,i),mr_end(s,i),mr_logger_stride(i),&p,&gen);
        if (error) return error;
        mr_set_pointer(s,i,p); s->cache[26+i]=gen;
    }
    uint8_t error=mr_logger_recover(io,s);
    if (error) return error;
    if (mr_checkpoint(io,s,false)) return DENSOR_IO;
    /* Close durably before discarding competing retained generations. */
    if (s->status[2]!=DENSOR_STOPPED && status_write(io,s,DENSOR_ERROR,reason,densor_u32(s->status+6))) return DENSOR_IO;
    return cache_initialize(ram,s);
}
uint8_t mr_load(const densor_io *io, const mr_ram *ram, mr_session *s, uint16_t capacity) {
    uint8_t prefix[12], other[32]; memset(s,0,sizeof(*s)); s->capacity=capacity;
    if (!capacity_valid(capacity)) return DENSOR_BAD_HEADER;
    if (!io->read(0,prefix,12) || !io->read(12,s->h,64) || !io->read(76,s->status,16)) return DENSOR_IO;
    if (memcmp(prefix,"DENSOR2!",8) || !zero(prefix+8,4) || !header_valid(s)
        || !valid16(s->status,16) || s->status[2]>DENSOR_FULL || s->status[3]>MR_RECOVERY
        || (s->status[4]&0xf0) || s->status[5] || densor_u32(s->status+10)!=densor_u32(s->h+20)) return DENSOR_BAD_HEADER;
    if (s->status[2]==DENSOR_CONFIGURING) return DENSOR_BAD_STATE;
    if (!s->h[12]) return s->status[2]==DENSOR_STOPPED ? DENSOR_OK : DENSOR_BAD_STATE;
    if (!ram->read(0,s->cache,32) || !ram->read(32,other,32)) return DENSOR_IO;
    bool a=cache_valid(s,s->cache), b=cache_valid(s,other);
    unsigned chosen=a ? 0 : b ? 1 : 2;
    if (a && b) chosen=newer(s->cache,other,32,2);
    if (chosen==2) return recover(io,ram,s,a&&b ? MR_RECOVERY : MR_PHASE);
    if (chosen==1) memcpy(s->cache,other,32);
    s->cache_slot=(uint8_t)chosen;
    if (s->cache[3]) return recover(io,ram,s,MR_PHASE);
    for (unsigned i=0;i<4;++i) if (!pointer_valid(s,i) || (!mr_end(s,i) && s->cache[26+i])) return recover(io,ram,s,MR_RECOVERY);
    uint32_t next=densor_u32(s->cache+12);
    if (next>MR_MAX_SECONDS/densor_u16(s->h+28)+1 || densor_u16(s->cache+16)!=next%densor_u16(s->h+30)) return recover(io,ram,s,MR_PHASE);
    return DENSOR_OK;
}
uint8_t mr_format(const densor_io *io, mr_session *s, uint16_t capacity, uint8_t detected) {
    if (!capacity_valid(capacity) || (detected&0xf0)) return DENSOR_BAD_CONFIG;
    memset(s,0,sizeof(*s)); s->capacity=capacity;
    memcpy(s->h,"DNR2",4); s->h[4]=MR_VERSION; s->h[5]=DENSOR_STORAGE; s->h[6]=64; s->h[7]=DENSOR_TIMING;
    densor_put16(s->h+8,capacity); densor_put16(s->h+10,MR_LOG); s->h[15]=15; s->h[17]=detected; seal16(s->h,64);
    uint8_t blank[MR_REQUEST_SIZE]={0};
    if (densor_write(io,capacity,0,blank,12) || densor_write(io,capacity,92,blank,MR_REQUEST_SIZE)
        || densor_write(io,capacity,MR_MARKER,blank,4) || densor_write(io,capacity,12,s->h,64)) return DENSOR_IO;
    s->status[4]=detected;
    if (status_write(io,s,DENSOR_STOPPED,0,0) || densor_write(io,capacity,0,(const uint8_t *)"DENSOR2!",8)) return DENSOR_IO;
    return DENSOR_OK;
}
uint32_t mr_next_time(const mr_session *s) { return densor_u32(s->h+24)+densor_u32(s->cache+12)*densor_u16(s->h+28); }
uint8_t mr_clock_check(const mr_session *s, uint32_t now, bool *ready) {
    *ready=false; uint32_t next=densor_u32(s->cache+12), epoch=densor_u32(s->h+24), target=mr_next_time(s);
    if ((next && now<target-densor_u16(s->h+28)) || (now>=epoch && now-epoch>MR_MAX_SECONDS)) return MR_PHASE;
    if (!next && now<epoch && epoch-now>(uint32_t)s->h[18]*60u) return MR_PHASE;
    if (now<target) return DENSOR_OK;
    if (now-target>s->h[19]) return MR_PHASE;
    *ready=true; return DENSOR_OK;
}
uint8_t mr_commit(const densor_io *io, const mr_ram *ram, mr_session *s) {
    uint32_t next=densor_u32(s->cache+12)+1;
    if ((next%densor_u16(s->h+56)==0 || next%densor_u16(s->h+30)==0)
        && mr_checkpoint(io,s,false)) return DENSOR_IO;
    mr_scheduler_commit(s); return cache_write(ram,s,0);
}
uint8_t mr_close(const densor_io *io, const mr_ram *ram, mr_session *s, uint8_t error) {
    if (s->h[12] && (mr_begin(ram,s) || mr_checkpoint(io,s,false) || cache_write(ram,s,0))) return DENSOR_IO;
    return status_write(io,s,error ? (error==DENSOR_NO_SPACE ? DENSOR_FULL : DENSOR_ERROR) : DENSOR_STOPPED,error,densor_u32(s->status+6));
}
uint8_t mr_request(const densor_io *io, const mr_ram *ram, mr_session *s, uint8_t detected, uint32_t now) {
    uint8_t marker[4], after[4], p[MR_REQUEST_SIZE];
    if (!io->read(MR_MARKER,marker,4)) return DENSOR_IO;
    uint32_t id=densor_u32(marker);
    if (!id || id<=densor_u32(s->status+6)) return DENSOR_OK;
    if (!io->read(92,p,MR_REQUEST_SIZE) || !io->read(MR_MARKER,after,4)) return DENSOR_IO;
    if (memcmp(marker,after,4) || densor_u32(p+4)!=id || !valid16(p,MR_REQUEST_SIZE)) return DENSOR_OK;
    uint8_t error=DENSOR_OK;
    if (memcmp(p,"DCP5",4) || p[11]!=MR_VERSION || p[27]!=DENSOR_STORAGE || p[28]!=DENSOR_TIMING || p[29]
        || densor_u32(p+22)!=densor_u32(s->h+20)) error=DENSOR_BAD_CONFIG;
    else if (p[8]==MR_STOP) {
        if (mr_close(io,ram,s,0)) return DENSOR_IO;
        return status_write(io,s,DENSOR_STOPPED,0,id);
    } else if (p[8]==DENSOR_APPLY_SETTINGS) {
        if (s->status[2]==DENSOR_RUNNING) error=DENSOR_BAD_STATE;
        uint8_t h[64]; memcpy(h,s->h,64); h[12]=p[9]; h[14]=p[10]; h[18]=p[26];
        densor_put16(h+28,densor_u16(p+12)); memcpy(h+32,p+14,8);
        if (!error) error=mr_config(h);
        /* Host-provided page counts become immutable contiguous boundaries.
         * Bound every addition before narrowing to 16 bits. No rate weighting here. */
        memset(h+40,0,16); uint16_t at=MR_LOG;
        for (unsigned i=0;!error && i<4;++i) {
            uint32_t bytes=(uint32_t)densor_u16(p+30+2*i)*4;
            if (bytes>(uint32_t)s->capacity-at) { error=DENSOR_BAD_CONFIG; break; }
            if (bytes) { densor_put16(h+40+2*i,at); at+=(uint16_t)bytes; densor_put16(h+48+2*i,at); }
        }
        if (!error && !mr_logger_layout_valid(h,s->capacity)) error=DENSOR_BAD_CONFIG;
        if (!error && (h[12]&detected)!=h[12]) error=DENSOR_SENSOR;
        uint32_t delay=(uint32_t)h[18]*60;
        if (!error && now>3155759999u-MR_MAX_SECONDS-delay) error=DENSOR_BAD_CONFIG;
        if (!error) {
            if (status_write(io,s,DENSOR_CONFIGURING,0,densor_u32(s->status+6))) return DENSOR_IO;
            memcpy(s->h,h,64); s->h[17]=detected;
            densor_put32(s->h+20,id);
            /* FF is explicitly invalid for every record format, including acceleration. */
            uint8_t blank[16]; memset(blank,255,16);
            for (uint16_t a=MR_SLOTS;a<s->capacity;a+=16) {
                uint16_t n=s->capacity-a; if (n>16) n=16;
                if (densor_write(io,s->capacity,a,blank,n)) return DENSOR_IO;
            }
            memset(s->cache,0,32);
            for (unsigned i=0;i<4;++i) mr_set_pointer(s,i,mr_start(s,i));
            if (mr_checkpoint(io,s,true)) return DENSOR_IO;
            if (ram->clock && !ram->clock(&now)) return DENSOR_IO;
            if (now>3155759999u-MR_MAX_SECONDS-delay) return DENSOR_BAD_CONFIG;
            densor_put32(s->h+24,now+delay); seal16(s->h,64);
            if (densor_write(io,s->capacity,12,s->h,64)) return DENSOR_IO;
            /* Invalidate both previous-session copies before installing the first clean one. */
            if (cache_initialize(ram,s)) return DENSOR_IO;
            s->status[4]=detected;
            return status_write(io,s,DENSOR_RUNNING,0,id);
        }
    } else error=DENSOR_BAD_CONFIG;
    if (mr_close(io,ram,s,error)) return DENSOR_IO;
    return status_write(io,s,DENSOR_ERROR,error,id);
}
