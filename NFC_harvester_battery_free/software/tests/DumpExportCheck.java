import com.st.st25nfc.densor.DensorProtocol;
import com.st.st25nfc.densor.DensorMultirate;
import com.st.st25nfc.densor.data.DensorDataSet;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

/** Read-only compatibility check for full EEPROM images exported by the sampling lab. */
public final class DumpExportCheck {
    public static void main(String[] paths) throws Exception {
        if(paths.length==0) throw new IllegalArgumentException("Pass one or more full EEPROM .bin files");
        for(String path:paths) {
            byte[] image=Files.readAllBytes(Paths.get(path));
            if(image.length<128) throw new IllegalArgumentException("Missing full R1/R2/R3 metadata: "+path);
            int capacity=DensorProtocol.u16(image,20);
            if(image.length!=capacity) throw new IllegalArgumentException("Expected full declared capacity: "+path);
            DensorProtocol.Info header=DensorProtocol.inspect(image,capacity);
            if(header.legacy) throw new IllegalArgumentException("Use an R1/R2/R3 image: "+path);
            DensorDataSet data=new DensorDataSet(Arrays.copyOf(image,header.pointer),capacity);
            DensorProtocol.Info info=data.getInfo();
            int[] counts=info.recordCounts.clone();
            if(!info.multirate) for(int i=0;i<4;i++) if((info.mask&(1<<i))!=0) counts[i]=info.samples();
            String revision=info.multirate?DensorMultirate.revision(info.storage,info.timing):"R1";
            System.out.println(Paths.get(path).getFileName()+": "+revision
                    +"; capacity="+capacity+"; complete="+info.complete()+"; rows="+info.samples()
                    +"; stream counts="+Arrays.toString(counts)+"; last elapsed s="
                    +(data.getTimestamps().isEmpty()?"none":data.getTimestamps().get(data.getTimestamps().size()-1)));
        }
    }
}
