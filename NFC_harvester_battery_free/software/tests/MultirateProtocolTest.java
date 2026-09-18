import com.st.st25nfc.densor.*;
import com.st.st25nfc.densor.data.*;
import java.nio.file.*;
import java.util.*;

public class MultirateProtocolTest {
    private static void rejects(Runnable f) { boolean rejected=false;try{f.run();}catch(IllegalArgumentException e){rejected=true;}assert rejected; }
    private static void seal(byte[] b,int at,int size) { DensorProtocol.put16(b,at+size-2,DensorProtocol.crc16(b,at,size-2)); }
    public static void main(String[] args) throws Exception {
        if(args[0].equals("--verify-applied")) {
            int count=0;
            try(DirectoryStream<Path> entries=Files.newDirectoryStream(Paths.get(args[1]),"*-applied.bin")) {
                for(Path path:entries) {
                    byte[] b=Files.readAllBytes(path);DensorDataSet data=new DensorDataSet(b,b.length);
                    assert data.getInfo().complete() && data.getInfo().sessionId==2;
                    assert data.getSamples().size()==1 && data.getTimestamps().get(0)==0;
                    count++;
                }
            }
            assert count==360;System.out.println("Java -> firmware -> Java: 360 allocation requests committed and decoded");return;
        }
        assert DensorMultirate.crc8("123456789".getBytes("US-ASCII"),0,9)==0xf4;
        // R2: every mask uses whole pages, including one payload (8 B) and all (16 B).
        int[] sharedBytes={0,8,8,8,8,8,8,12,12,12,12,16,12,16,16,16};
        for(int mask=1;mask<16;mask++) assert DensorMultirate.recordSize(mask)==sharedBytes[mask];
        int[] smartBytes={0,1,2,4,4,5,6,8,8,9,10,12,12,13,14,16,16};
        for(int n=1;n<smartBytes.length;n++) assert DensorMultirate.smartPaddedSize(n)==smartBytes[n];
        assert Arrays.equals(DensorMultirate.allocation(2048,3,new int[]{12,12,0,0},12),new int[]{235,235,0,0});
        assert Arrays.equals(DensorMultirate.allocation(2048,3,new int[]{12,6,0,0},12),new int[]{157,313,0,0});
        assert DensorMultirate.capacityPreview(2048,1,3,10,new int[]{12,6,0,0}).contains("First full: TMP119 temperature at 18780 s; unused 0 bytes");
        rejects(()->DensorMultirate.capacityPreview(2048,1,0,10,new int[4]));
        rejects(()->DensorMultirate.capacityPreview(2048,1,3,1,new int[]{65535,65534,0,0}));
        int files=0;
        for(String storage:new String[]{"partitioned","shared"}) for(String timing:new String[]{"fsm","rtc"})
        for(int cap:new int[]{512,2048,8192}) for(int mask=1;mask<16;mask++) for(int kind=0;kind<2;kind++) {
            byte[] b=Files.readAllBytes(Paths.get(args[0],storage+"-"+timing+"-"+cap+"-"+mask+"-"+kind+".bin"));
            DensorDataSet data=new DensorDataSet(b,cap); DensorProtocol.Info s=data.getInfo();
            assert s.multirate && s.mask==mask && s.complete();assert s.storage==(storage.equals("partitioned")?1:2);assert s.timing==(timing.equals("fsm")?1:2);
            ArrayList<DensorDataSample> samples=data.getSamples();ArrayList<Long> times=data.getTimestamps();
            for(int n=0;n<samples.size();n++) {
                DensorDataSample v=samples.get(n);long t=times.get(n);assert t%10==0;
                int due=DensorMultirate.due(s,t/10);
                assert (v.getTemp()!=null)==((due&1)!=0);if(v.getTemp()!=null) assert v.getTemp()==24f;
                assert (v.getTmp119()!=null)==((due&2)!=0);if(v.getTmp119()!=null) assert v.getTmp119()==-1f;
                assert (v.getPd()!=null)==((due&4)!=0);if(v.getPd()!=null) assert v.getPd()==1234;
                assert (v.getAccel()[0]!=null)==((due&8)!=0);if(v.getAccel()[0]!=null) {assert v.getAccel()[0]==-1f;assert v.getAccel()[2]==1f;}
                assert (v.getVdda()!=null)==((due&7)!=0);if(v.getVdda()!=null) assert v.getVdda()==2.6f;
            }
            String revision=(storage.equals("shared")?"R2":"R3")+(timing.equals("fsm")?"a":"b");
            assert samples.size()>0;assert data.toCsv().contains("\n"+revision.toLowerCase(Locale.ROOT)+",");
            assert s.summary().startsWith(revision+" / ");
            assert s.summary().contains("capacity") || s.summary().contains("Pooled");
            String preview=DensorMultirate.capacityPreview(cap,s.storage,s.timing,mask,s.period,s.multipliers);
            assert preview.startsWith("Proposed "+revision+(storage.equals("partitioned")?" automatic partitions":" shared pool"));
            if(s.storage==1) {
                for(int i=0;i<4;i++) if((mask&(1<<i))!=0) {
                    int bytes=s.ends[i]-s.starts[i],stride=i==3?8:4;
                    assert preview.contains("capacity "+bytes/stride+" records ("+bytes+" bytes)");
                    // R3 smart padding adds nothing to the current 4/8-byte records.
                    assert DensorMultirate.crc8(b,s.starts[i],stride-1)==(b[s.starts[i]+stride-1]&255);
                }
            } else {
                assert preview.contains("Pooled capacity "+(cap-168)+" bytes");
                int payloadEnd=170;
                for(int i=0;i<4;i++) if((mask&(1<<i))!=0) payloadEnd+=i==3?6:2;
                for(int p=payloadEnd+1;p<168+sharedBytes[mask];p++) assert b[p]==0;
                byte[] padding=b.clone();padding[168+sharedBytes[mask]-1]=1;
                rejects(()->new DensorDataSet(padding,cap));
            }
            byte[] req=DensorMultirate.request(s,2,mask,s.period,s.multipliers,1,59);
            assert req.length==40 && req[3]=='5' && DensorMultirate.MARKER==132;
            for(int i=0;i<4;i++) assert DensorProtocol.u16(req,30+2*i)==(s.ends[i]-s.starts[i])/4;
            Files.write(Paths.get(args[0],storage+"-"+timing+"-"+cap+"-"+mask+"-"+kind+".request"),req);
            assert req[26]==59 && req[27]==s.storage && req[28]==s.timing;
            for(int i=0;i<4;i++) assert DensorProtocol.u16(req,14+2*i)==s.multipliers[i];
            files++;
        }
        byte[] original=Files.readAllBytes(Paths.get(args[0],"shared-rtc-2048-15-0.bin"));
        for(int offset:new int[]{0,16,17,19,44,136,168,183}) {
            byte[] b=original.clone();b[offset]^=0x20;
            // One damaged pointer slot is tolerated if its duplicate is valid.
            if(offset==136) { new DensorDataSet(b,2048); continue; }
            rejects(()->new DensorDataSet(b,2048));
        }
        for(int unsupported:new int[]{4,99}) {
            byte[] bad=original.clone();bad[16]=(byte)unsupported;seal(bad,12,64);rejects(()->new DensorDataSet(bad,2048));
        }
        byte[] partitioned=Files.readAllBytes(Paths.get(args[0],"partitioned-fsm-2048-15-0.bin"));
        byte[] overlap=partitioned.clone();DensorProtocol.put16(overlap,54,DensorProtocol.u16(overlap,52));seal(overlap,12,64);
        rejects(()->new DensorDataSet(overlap,2048));
        byte[] gap=partitioned.clone();DensorProtocol.put16(gap,54,DensorProtocol.u16(gap,54)+4);seal(gap,12,64);
        rejects(()->new DensorDataSet(gap,2048));
        byte[] running=original.clone();running[78]=2;seal(running,76,16);DensorProtocol.Info live=DensorProtocol.inspect(running,2048);
        assert !live.complete();rejects(()->DensorMultirate.request(live,2,15,10,live.multipliers,1,0));
        assert DensorMultirate.request(live,1,15,10,live.multipliers,1,0)[8]==1;
        rejects(()->DensorMultirate.validate(3,1,new int[]{65535,65534,0,0}));
        assert DensorMultirate.validate(1,1,new int[]{65535,0,0,0})==65535;
        System.out.println("Java: "+files+" firmware fixtures, independent times/temperatures, CSV, capabilities and corruption checks passed");
    }
}
