package com.st.st25nfc.densor;

import com.st.st25nfc.densor.data.DensorDataSample;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import static com.st.st25nfc.densor.DensorProtocol.*;

/** R2 shared / R3 partitioned: immutable session metadata and validated streams. */
public final class DensorMultirate {
    private DensorMultirate() {}
    public static final int FORMAT_VERSION = 5, LOG = 168, MARKER = 132, REQUEST_SIZE = 40, STOP = 1, MAX_SECONDS = 2592000;
    private static final String[] NAMES={"LIS2DW12 temperature","TMP119 temperature","Photodiode","Acceleration"};
    private static void require(boolean ok, String why) { if (!ok) throw new IllegalArgumentException(why); }
    public static String revision(int storage,int timing) {
        require((storage==1 || storage==2) && (timing==1 || timing==2),"Unsupported storage/timing strategy");
        return (storage==2?"R2":"R3")+(timing==1?"a":"b");
    }
    public static String csvFormat(Info s) {
        return revision(s.storage,s.timing).toLowerCase(java.util.Locale.ROOT);
    }
    private static boolean text(byte[] b, int p, String s) {
        if (b.length < p+s.length()) return false;
        for (int i=0;i<s.length();i++) if(b[p+i]!=(byte)s.charAt(i)) return false;
        return true;
    }
    private static boolean zeros(byte[] b,int p,int n) { for(int i=0;i<n;i++) if(b[p+i]!=0) return false; return true; }
    public static int crc8(byte[] b,int p,int n) {
        int c=0; for(int i=0;i<n;i++) { c^=b[p+i]&255; for(int j=0;j<8;j++) c=((c<<1)^((c&128)!=0?7:0))&255; } return c;
    }
    private static int gcd(int a,int b) { while(b!=0) { int t=a%b; a=b; b=t; } return a; }
    public static int validate(int mask,int base,int[] m) {
        require(mask>0 && (mask&~15)==0 && base>0 && base<=3540 && m.length==4,"Invalid streams or base period (1–3540 s)");
        long l=1;
        for(int i=0;i<4;i++) {
            require(m[i]>=0 && m[i]<=65535 && (((mask&(1<<i))!=0)==(m[i]>0)),"Enabled streams need positive integer multipliers; disabled streams use zero");
            if(m[i]==0) continue;
            require((long)base*m[i]<=MAX_SECONDS,"Stream period exceeds 30 days");
            l=l/gcd((int)l,m[i])*m[i]; require(l<=65535,"Schedule LCM exceeds 65535");
        }
        return (int)l;
    }
    public static int due(Info s,long tick) { int mask=0; for(int i=0;i<4;i++) if(s.multipliers[i]>0 && tick%s.multipliers[i]==0) mask|=1<<i; return mask; }
    private static long nextTick(Info s,long tick) {
        long next=Long.MAX_VALUE;
        for(int m:s.multipliers) if(m>0) next=Math.min(next,((tick+m-1)/m)*m);
        return next;
    }
    /** R3a/R3b leave remainders 0/1/2 alone; only remainder 3 gains a zero byte. */
    public static int smartPaddedSize(int bytes) {
        require(bytes>0 && bytes<Integer.MAX_VALUE,"Invalid unpadded record length");
        return bytes+(bytes%4==3?1:0);
    }
    public static int partitionedRecordSize(int stream) {
        require(stream>=0 && stream<4,"Invalid partitioned stream");
        return smartPaddedSize((stream==3?6:2)+2); // payload + supply + CRC
    }
    /** R2a/R2b pad the complete masked record to a whole 4-byte EEPROM page. */
    public static int recordSize(int mask) {
        require(mask>0 && (mask&~15)==0,"Invalid shared presence mask");
        int n=3; for(int i=0;i<4;i++) if((mask&(1<<i))!=0) n+=i==3?6:2; return (n+3)&~3;
    }
    public static int[] allocation(int capacity,int mask,int[] m,int lcm) {
        long total=0; long[] weights=new long[4]; int[] pages=new int[4]; int remaining=(capacity-LOG)/4;
        for(int i=0;i<4;i++) if((mask&(1<<i))!=0) { pages[i]=partitionedRecordSize(i)/4; remaining-=pages[i]; weights[i]=partitionedRecordSize(i)*(long)(lcm/m[i]); total+=weights[i]; }
        int left=remaining;
        for(int i=0;i<4;i++) if(weights[i]>0) { int n=(int)(remaining*weights[i]/total); pages[i]+=n; left-=n; }
        for(int i=0;left>0;i=(i+1)%4) if(weights[i]>0) { pages[i]++; left--; }
        return pages;
    }
    /** Android sends these page counts; firmware commits the session boundaries. */
    public static int[] proposedPages(Info s,int mask,int base,int[] m) {
        int lcm=validate(mask,base,m);
        return s.storage==1?allocation(s.capacity,mask,m,lcm):new int[]{(s.capacity-LOG)/4,0,0,0};
    }
    public static String capacityPreview(int capacity,int storage,int mask,int base,int[] m) {
        return capacityPreview(capacity,storage,1,mask,base,m);
    }
    public static String capacityPreview(int capacity,int storage,int timing,int mask,int base,int[] m) {
        require(capacity==512 || capacity==2048 || capacity==8192,"Unsupported tag density");
        String revision=revision(storage,timing);
        Info proposed=new Info();proposed.version=FORMAT_VERSION;proposed.logStart=LOG;proposed.capacity=capacity;proposed.storage=storage;proposed.timing=timing;proposed.mask=mask;proposed.period=base;
        proposed.lcm=validate(mask,base,m);System.arraycopy(m,0,proposed.multipliers,0,4);
        if(storage==1) {
            int[] pages=proposedPages(proposed,mask,base,m);int at=proposed.logStart;
            for(int i=0;i<4;i++) if(pages[i]>0) { proposed.starts[i]=at;at+=pages[i]*4;proposed.ends[i]=at; }
        }
        StringBuilder out=new StringBuilder("Proposed ").append(revision).append(storage==1?" automatic partitions":" shared pool");
        appendCapacity(out,proposed);
        return out.append("\nPreview for a new session; active allocation changes after Apply is acknowledged.").toString();
    }
    private static int pointer(byte[] b,int at,int start,int end,int stride) {
        boolean a=crc8(b,at,3)==(b[at+3]&255), c=crc8(b,at+4,3)==(b[at+7]&255);
        for(int i=0;i<2;i++) if(i==0?a:c) {
            int p=u16(b,at+4*i); require(p>=start && p<=end && (p-start)%stride==0,"Pointer slot outside stream bounds");
        }
        require(a||c,"No valid durable pointer slot");
        if(!a) return u16(b,at+4); if(!c) return u16(b,at);
        int d=((b[at+6]&255)-(b[at+2]&255))&255;
        require(d!=128 && (d!=0 || u16(b,at)==u16(b,at+4)),"Ambiguous pointer generations");
        return u16(b,at+(d>0 && d<128?4:0));
    }
    public static Info inspect(byte[] b,int capacity) {
        require(b.length>=LOG && text(b,0,"DENSOR2!") && zeros(b,8,4),"Truncated/damaged multi-rate metadata");
        int h=12,st=76;
        require(b[h+4]==FORMAT_VERSION && text(b,h,"DNR2") && (b[h+5]==1 || b[h+5]==2) && b[h+6]==64
                && (b[h+7]==1 || b[h+7]==2) && u16(b,h+8)==capacity && u16(b,h+10)==LOG
                && b[h+13]==0 && (b[h+14]&255)<=1 && b[h+15]==15 && b[h+16]==0 && (b[h+17]&240)==0
                && (b[h+18]&255)<=59 && zeros(b,h+58,4) && crc16(b,h,62)==u16(b,h+62),"Unsupported/inconsistent multi-rate header");
        require(crc16(b,st,14)==u16(b,st+14) && (b[st+2]&255)<=FULL && (b[st+3]&255)<=10
                && (b[st+4]&240)==0 && b[st+5]==0 && u32(b,st+10)==u32(b,h+20),"Invalid multi-rate status");
        Info s=new Info(); s.version=FORMAT_VERSION; s.multirate=true; s.capacity=capacity; s.pointer=capacity; s.logStart=LOG;
        s.storage=b[h+5]; s.timing=b[h+7]; s.mask=b[h+12]&255; s.oscillator=b[h+14]; s.delay=(b[h+18]&255)*60;
        s.sessionId=u32(b,h+20); s.startTime=u32(b,h+24); s.period=u16(b,h+28); s.lcm=u16(b,h+30);
        s.state=b[st+2]; s.error=b[st+3]; s.detected=b[st+4]; s.acknowledgedId=u32(b,st+6);
        for(int i=0;i<4;i++) { s.multipliers[i]=u16(b,h+32+2*i); s.starts[i]=u16(b,h+40+2*i); s.ends[i]=u16(b,h+48+2*i); }
        if(s.mask==0) require(s.state==STOPPED && zeros(b,h+18,40),"Invalid unconfigured multi-rate session");
        else {
            require(s.sessionId!=0 && (s.mask&(b[h+17]&255))==s.mask && s.startTime<=3155759999L-MAX_SECONDS
                    && validate(s.mask,s.period,s.multipliers)==s.lcm && (b[h+19]&255)==(s.period>1?1:0)
                    && u16(b,h+56)==Math.min(s.lcm,64),"Invalid schedule metadata");
            int at=LOG;
            for(int i=0;i<4;i++) {
                boolean enabled=s.storage==1?(s.mask&(1<<i))!=0:i==0;
                if(!enabled) require(s.starts[i]==0 && s.ends[i]==0,"Disabled stream has a partition");
                else {
                    int stride=s.storage==1?partitionedRecordSize(i):4;
                    require(s.starts[i]==at && s.ends[i]<=capacity && (s.ends[i]&3)==0 && s.ends[i]-at>=stride,"Invalid/overlapping allocation");
                    s.pointers[i]=pointer(b,LOG-32+8*i,s.starts[i],s.ends[i],stride);
                    at=s.ends[i];
                }
            }
            require(at==capacity,"Allocation does not cover recording memory");
        }
        int p=92;
        if(u32(b,MARKER)!=0 && u32(b,MARKER)==u32(b,p+4) && text(b,p,"DCP"+FORMAT_VERSION) && b[p+11]==FORMAT_VERSION && crc16(b,p,REQUEST_SIZE-2)==u16(b,p+REQUEST_SIZE-2)) {
            s.pendingId=u32(b,p+4); s.pendingCommand=b[p+8]&255; s.pendingMask=b[p+9]&255; s.pendingPeriod=u16(b,p+12); s.pendingDelay=(b[p+26]&255)*60;
            for(int i=0;i<4;i++) s.pendingMultipliers[i]=u16(b,p+14+2*i);
            for(int i=0;i<4;i++) s.pendingPages[i]=u16(b,p+30+2*i);
        }
        return s;
    }
    public static byte[] request(Info s,int command,int mask,int base,int[] m,int oscillator,int startup) {
        require(s.multirate && s.version==FORMAT_VERSION,"Installed firmware does not support multi-rate settings");
        require(s.pendingId<=s.acknowledgedId && s.acknowledgedId<0xffffffffL,"Pending request or exhausted IDs");
        require(s.state!=CONFIGURING,"Interrupted initialization; export raw data and reprovision");
        require(command==STOP || command==APPLY_SETTINGS,"Unsupported command");
        if(command==APPLY_SETTINGS) {
            require(s.state!=RUNNING,"Stop, await acknowledgement and export before starting a new session");
            validate(mask,base,m); require((mask&~15)==0 && oscillator>=0 && oscillator<=1 && startup>=0 && startup<=59,"Invalid configuration");
        }
        byte[] p=new byte[REQUEST_SIZE]; p[0]='D';p[1]='C';p[2]='P';p[3]=(byte)('0'+FORMAT_VERSION); put32(p,4,s.acknowledgedId+1);
        p[8]=(byte)command;p[9]=(byte)mask;p[10]=(byte)oscillator;p[11]=(byte)s.version;put16(p,12,base);
        for(int i=0;i<4;i++) put16(p,14+2*i,m[i]); put32(p,22,s.sessionId);p[26]=(byte)startup;p[27]=(byte)s.storage;p[28]=(byte)s.timing;
        if(command==APPLY_SETTINGS) {
            int[] pages=proposedPages(s,mask,base,m);
            for(int i=0;i<4;i++) put16(p,30+2*i,pages[i]);
        }
        put16(p,p.length-2,crc16(p,0,p.length-2));return p;
    }
    private static boolean supply(int mask,int v) { return (mask&7)!=0?v<=15:v==255; }
    private static final class Row {
        Float old,tmp,voltage; Integer pd; Float[] accel={null,null,null};
        void supply(int code) { if(code!=255) { float v=(18+code)/10f; require(voltage==null || voltage==v,"Inconsistent supply values within a wake"); voltage=v; } }
        void field(byte[] b,int p,int i) {
            if(i==0) old=((short)u16(b,p))/256f+25;
            if(i==1) tmp=((short)u16(b,p))/128f;
            if(i==2) pd=u16(b,p);
            if(i==3) for(int j=0;j<3;j++) accel[j]=((short)u16(b,p+2*j))/16384f;
        }
        DensorDataSample sample() { return new DensorDataSample(old,tmp,pd,new Integer[5],null,accel,voltage); }
    }
    public static ArrayList<DensorDataSample> decode(byte[] b,Info s) {
        require(b.length==s.capacity,"Multi-rate recordings require a full physical EEPROM dump");
        require(s.state!=CONFIGURING,"Session initialization incomplete");
        TreeMap<Long,Row> rows=new TreeMap<>(); Arrays.fill(s.recordCounts,0);
        if(s.mask==0) return new ArrayList<>();
        for(int stream=0;stream<(s.storage==1?4:1);stream++) {
            if(s.ends[stream]==0) continue;
            int p=s.starts[stream],checkpoint=s.pointers[stream],index=0; long tick=0; boolean boundary=p==checkpoint;
            while(p<s.ends[stream]) {
                int mask,bytes,payload,code;
                if(s.storage==1) {
                    mask=1<<stream;bytes=partitionedRecordSize(stream);payload=p;code=p+bytes-2;tick=(long)index*s.multipliers[stream];
                } else {
                    mask=b[p]&255;if(mask==0 || (mask&~15)!=0) break;
                    bytes=recordSize(mask);payload=p+2;code=p+1;tick=nextTick(s,tick);
                    if(mask!=due(s,tick)) break;
                }
                if(p+bytes>s.ends[stream] || tick*s.period>MAX_SECONDS) break;
                int c=b[code]&255;
                if(!supply(s.storage==1?due(s,tick):mask,c)) break;
                int crcAt=s.storage==1?p+bytes-1:payload;
                if(s.storage==2) for(int i=0;i<4;i++) if((mask&(1<<i))!=0) crcAt+=i==3?6:2;
                if(crc8(b,p,crcAt-p)!=(b[crcAt]&255) || !zeros(b,crcAt+1,p+bytes-crcAt-1)) break;
                int field=payload; boolean values=true;
                for(int i=0;i<4;i++) if((mask&(1<<i))!=0) { if(i==2 && u16(b,field)>4095) values=false; field+=i==3?6:2; }
                if(!values) break;
                Row row=rows.get(tick); if(row==null) { row=new Row(); rows.put(tick,row); }
                row.supply(c); field=payload;
                for(int i=0;i<4;i++) if((mask&(1<<i))!=0) { row.field(b,field,i);field+=i==3?6:2;s.recordCounts[i]++; }
                p+=bytes; index++; if(p==checkpoint) boundary=true; if(s.storage==2) tick++;
            }
            require(boundary && p>=checkpoint,"Corruption before the durable checkpoint");
            if(s.state==STOPPED) require(p==checkpoint,"Stopped session has an uncheckpointed tail");
            s.pointers[stream]=p;
        }
        ArrayList<DensorDataSample> samples=new ArrayList<>();s.sampleTimes.clear();
        for(Map.Entry<Long,Row> e:rows.entrySet()) { s.sampleTimes.add(e.getKey()*s.period);samples.add(e.getValue().sample()); }
        return samples;
    }
    public static String summary(Info s) {
        String[] states={"Stopped","Configuring","Running","Error","Full"};
        StringBuilder out=new StringBuilder(revision(s.storage,s.timing)).append(s.storage==1?" / Partitioned":" / Shared pool")
            .append(" / ").append(s.timing==1?"Retained FSM":"RTC time").append(" / ").append(states[s.state])
            .append("\nActive session ").append(s.sessionId).append("; base ").append(s.period).append(" s; startup ").append(s.delay/60).append(" min");
        appendCapacity(out,s);
        out.append("\nLast confirmed sensors: ").append(sensorNames(s.detected));
        if(s.error!=0) out.append("\nError: ").append(errorName(s.error));
        if(s.pendingId>s.acknowledgedId) {
            out.append("\nPending request ").append(s.pendingId).append(s.pendingCommand==STOP?": stop and checkpoint":": new settings");
            if(s.pendingCommand!=STOP) for(int i=0;i<4;i++) if(s.pendingMultipliers[i]>0) out.append("; ").append(NAMES[i]).append(' ').append((long)s.pendingPeriod*s.pendingMultipliers[i]).append(" s");
            if(s.pendingCommand!=STOP) for(int i=0;i<4;i++) if(s.pendingPages[i]>0)
                out.append("\nPending ").append(s.storage==1?NAMES[i]:"shared pool").append(": ").append(s.pendingPages[i]*4).append(" bytes");
        } else out.append("\nAcknowledged request ").append(s.acknowledgedId);
        return out.append("\n").append(s.wakeNotice()).append("\n").append(s.complete()?"Stopped, acknowledged checkpoint":"Snapshot / recovered prefix")
            .append(". Times are elapsed seconds; UTC is unavailable.\nInstalled strategies require a firmware change to replace.").toString();
    }
    private static void appendCapacity(StringBuilder out,Info s) {
        long first=Long.MAX_VALUE; int limiting=-1;
        for(int i=0;i<4;i++) if(s.multipliers[i]>0) {
            out.append('\n').append(NAMES[i]).append(": ").append((long)s.period*s.multipliers[i]).append(" s");
            if(s.storage==1) {
                int slots=(s.ends[i]-s.starts[i])/partitionedRecordSize(i);
                out.append("; capacity ").append(slots).append(" records (").append(s.ends[i]-s.starts[i]).append(" bytes)");
                long duration=(long)slots*s.multipliers[i]*s.period; if(duration<first) {first=duration;limiting=i;}
            }
        }
        if(s.mask!=0 && s.storage==1) {
            long unused=0;
            for(int i=0;i<4;i++) if(s.multipliers[i]>0) {
                long count=(first-1)/((long)s.period*s.multipliers[i])+1;
                unused+=s.ends[i]-s.starts[i]-count*partitionedRecordSize(i);
            }
            out.append("\nFirst full: ").append(NAMES[limiting]).append(" at ").append(first).append(" s; unused ").append(unused).append(" bytes");
        } else if(s.mask!=0) {
            long tick=0;int bytes=0;
            while(true) { tick=nextTick(s,tick);int n=recordSize(due(s,tick)); if(bytes+n>s.capacity-s.logStart || tick*s.period>MAX_SECONDS) break; bytes+=n;tick++; }
            out.append("\nPooled capacity ").append(s.capacity-s.logStart).append(" bytes; expected stop at ").append(Math.min(tick*s.period,MAX_SECONDS)).append(" s");
        }
    }
}
