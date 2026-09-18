import com.st.st25nfc.densor.DensorProtocol;
import com.st.st25nfc.densor.DensorMultirate;
import com.st.st25nfc.densor.data.DensorDataSet;
import com.st.st25nfc.densor.data.DensorDataSample;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

/** Uses the actual Android decoder; JSON lines let Node compare independent results. */
public final class ExportDecoderOracle {
    public static void main(String[] paths) throws Exception {
        for (String path : paths) {
            try {
                byte[] image = Files.readAllBytes(Paths.get(path));
                int capacity = DensorProtocol.u16(image, 20);
                if (image.length != capacity) throw new IllegalArgumentException("Full image required");
                DensorProtocol.Info header = DensorProtocol.inspect(image, capacity);
                DensorDataSet data = new DensorDataSet(header.multirate ? image : Arrays.copyOf(image, header.pointer), capacity);
                DensorProtocol.Info info = data.getInfo();
                int[] counts = info.recordCounts.clone();
                if (!info.multirate) for (int i = 0; i < 4; i++) if ((info.mask & (1 << i)) != 0) counts[i] = info.samples();
                StringBuilder out = new StringBuilder("{\"ok\":true,\"complete\":").append(info.complete())
                    .append(",\"revision\":\"").append(info.multirate ? DensorMultirate.revision(info.storage, info.timing) : "R1")
                    .append("\",\"counts\":").append(Arrays.toString(counts))
                    .append(",\"pointers\":").append(Arrays.toString(info.multirate ? info.pointers : new int[]{info.pointer, 0, 0, 0}))
                    .append(",\"times\":").append(data.getTimestamps()).append(",\"rows\":[");
                boolean first = true;
                for (DensorDataSample row : data.getSamples()) {
                    if (!first) out.append(','); first = false;
                    out.append('[').append(row.getTemp()).append(',').append(row.getTmp119()).append(',').append(row.getPd());
                    for (Float a : row.getAccel()) out.append(',').append(a);
                    out.append(',').append(row.getVdda()).append(']');
                }
                String label = data.toCsv().split("\n")[1].split(",")[0];
                System.out.println(out.append("],\"csv_format\":\"").append(label).append("\"}"));
            } catch (IllegalArgumentException ex) {
                System.out.println("{\"ok\":false}");
            }
        }
    }
}
