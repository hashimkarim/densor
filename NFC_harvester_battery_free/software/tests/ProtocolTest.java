import com.st.st25nfc.densor.DensorProtocol;
import com.st.st25nfc.densor.data.DensorDataSample;
import com.st.st25nfc.densor.data.DensorDataSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class ProtocolTest {
    private static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    private static void rejects(Runnable run) {
        try { run.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid recording was accepted");
    }
    private static void equal(Float value, float expected) { check(value != null && Math.abs(value - expected) < 0.000001); }
    private static byte[] unhex(String s) {
        byte[] result = new byte[s.length() / 2];
        for (int i = 0; i < result.length; i++) result[i] = (byte)Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return result;
    }
    public static void main(String[] args) throws Exception {
        check(DensorProtocol.crc16("123456789".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, 9) == 0x29b1);
        Path dir = Path.of(args[0]);
        for (int capacity : new int[]{512, 2048, 8192}) for (int mask = 1; mask <= 15; mask++) {
            byte[] dump = Files.readAllBytes(dir.resolve("c-" + capacity + "-" + mask + ".bin"));
            DensorDataSet data = new DensorDataSet(dump, capacity);
            DensorProtocol.Info info = data.getInfo();
            check(info.mask == mask && info.sessionId == 1 && info.acknowledgedId == 1 && info.state == DensorProtocol.RUNNING);
            check(info.period == 120 && data.getSamples().size() == 1 && !info.timeValid && info.logStart == 128);
            DensorDataSample sample = data.getSamples().get(0);
            if ((mask & 1) != 0) equal(sample.getTemp(), -5f); else check(sample.getTemp() == null);
            if ((mask & 2) != 0) equal(sample.getTmp119(), -1f); else check(sample.getTmp119() == null);
            if ((mask & 4) != 0) check(sample.getPd() == 2048); else check(sample.getPd() == null);
            if ((mask & 8) != 0) { equal(sample.getAccel()[0], 1f); equal(sample.getAccel()[1], -1f); equal(sample.getAccel()[2], 0f); }
            if ((mask & 7) != 0) equal(sample.getVdda(), 2.6f); else check(sample.getVdda() == null);
            check(data.toCsv().contains("old_temperature_c,tmp119_temperature_c") && data.toCsv().contains(",false,,0,"));
            byte[] request = DensorProtocol.request(info, DensorProtocol.APPLY_SETTINGS, 3, 120, 1, 59);
            check(DensorProtocol.u32(request, 4) == 2 && DensorProtocol.u32(request, 16) == 1 && request[9] == 3);
            byte[] pending = dump.clone(); System.arraycopy(request, 0, pending, 92, 32); DensorProtocol.put32(pending, 124, 2);
            DensorProtocol.Info staged = DensorProtocol.inspect(pending, capacity);
            check(staged.mask == mask && staged.pendingMask == 3 && staged.pendingPeriod == 120 && staged.pendingDelay == 3540 && staged.pendingId == 2);
            check(staged.summary().contains("Pending request 2"));
            rejects(() -> DensorProtocol.request(staged, DensorProtocol.APPLY_SETTINGS, 1, 120, 0));
            rejects(() -> DensorProtocol.request(info, 3, 0, 0, 0));
            check(info.wakeNotice().contains("120 s"));
            check(data.toCsv().contains(",live,"));
            for (int pos : new int[]{0, 10, 12, 16, 17, 18, 20, 24, 40, 74, 76, 78, 80, 90}) {
                byte[] broken = dump.clone(); broken[pos] ^= 0x40;
                rejects(() -> new DensorDataSet(broken, capacity));
            }
            byte[] broken = dump.clone(); DensorProtocol.put16(broken, 8, 127); rejects(() -> new DensorDataSet(broken, capacity));
            byte[] truncated = Arrays.copyOf(dump, dump.length - 1); rejects(() -> new DensorDataSet(truncated, capacity));
        }
        for (String line : Files.readAllLines(Path.of(args[1])).subList(1, Files.readAllLines(Path.of(args[1])).size())) {
            String[] f = line.split(",", -1); int mask = Integer.parseInt(f[1]);
            byte[] template = Files.readAllBytes(dir.resolve("c-512-" + mask + ".bin"));
            byte[] record = unhex(f[9]); System.arraycopy(record, 0, template, 128, record.length);
            DensorDataSample s = new DensorDataSet(template, 512).getSamples().get(0);
            if (!f[3].isEmpty()) equal(s.getTemp(), Integer.parseInt(f[3]) / 256f + 25);
            if (!f[4].isEmpty()) equal(s.getTmp119(), Integer.parseInt(f[4]) / 128f);
        }
        byte[] delayed = Files.readAllBytes(dir.resolve("c-512-3.bin"));
        delayed[DensorProtocol.HEADER + 18] = 59;
        DensorProtocol.put16(delayed, 74, DensorProtocol.crc16(delayed, 12, 62));
        DensorDataSet delayedData = new DensorDataSet(delayed, 512);
        check(delayedData.getStartupDelay() == 3540 && delayedData.getTimestamps().get(0) == 0);
        rejects(() -> DensorProtocol.request(delayedData.getInfo(), 2, 3, 120, 0, 60));
        rejects(() -> DensorProtocol.request(delayedData.getInfo(), 2, 3, 120, 0, -1));
        byte[] previous = Files.readAllBytes(dir.resolve("c-512-3.bin"));
        previous[16] = 2; DensorProtocol.put16(previous, 74, DensorProtocol.crc16(previous, 12, 62));
        DensorDataSet previousData = new DensorDataSet(previous, 512);
        check(previousData.getInfo().version == 2 && previousData.getStartupDelay() == 0);
        rejects(() -> DensorProtocol.request(previousData.getInfo(), 2, 3, 120, 0));
        byte[] padded = Files.readAllBytes(dir.resolve("c-512-3.bin")); padded[padded.length - 1] = 1;
        rejects(() -> new DensorDataSet(padded, 512));
        byte[] voltage = Files.readAllBytes(dir.resolve("c-512-3.bin")); voltage[128] = 16;
        rejects(() -> new DensorDataSet(voltage, 512));
        /* Original nine-byte header; old temp contains voltage nibble. */
        byte[] legacy = unhex("a06500000082000b0008e2");
        DensorDataSet original = new DensorDataSet(legacy, 512);
        check(original.getInfo().legacy && original.getInterval() == 120);
        equal(original.getTemp()[0], -5f); equal(original.getVdda()[0], 2.6f); check(original.getTmp119()[0] == null);
        rejects(() -> DensorProtocol.request(original.getInfo(), DensorProtocol.APPLY_SETTINGS, 3, 120, 0));
        byte[] future = unhex("04650000001000130001000200030004000500");
        DensorDataSet futureData = new DensorDataSet(future, 512);
        check(futureData.getInfo().stride == 10 && futureData.getFuture1()[4][0] == 5 && futureData.getTmp119()[0] == null);
        byte[] emptyMask = legacy.clone(); emptyMask[0] = 0; rejects(() -> new DensorDataSet(emptyMask, 512));
        byte[] badBcd = legacy.clone(); badBcd[5] = 0x1a; rejects(() -> new DensorDataSet(badBcd, 512));
        DensorDataSet historicalLegacy = new DensorDataSet(Files.readAllBytes(dir.resolve("historical-reconstructed-legacy.bin")), 8192);
        DensorDataSet historicalR1 = new DensorDataSet(Files.readAllBytes(dir.resolve("historical-reconstructed-r1.bin")), 8192);
        check(historicalLegacy.getInfo().legacy && !historicalR1.getInfo().legacy);
        check(historicalLegacy.getInfo().samples() == 210 && historicalR1.getInfo().samples() == 210);
        check(historicalR1.getInfo().period == 1 && historicalR1.getInfo().mask == 13 && !historicalR1.getInfo().timeValid);
        equal(historicalR1.getTemp()[0], 25.0625f); check(historicalR1.getPd()[0] == 1133);
        equal(historicalR1.getAccel()[0][0], -1700f / 16384); equal(historicalR1.getVdda()[0], 2.6f);
        for (int i = 0; i < 210; i++) {
            equal(historicalR1.getTemp()[i], historicalLegacy.getTemp()[i]);
            equal(historicalR1.getVdda()[i], historicalLegacy.getVdda()[i]);
            check(historicalR1.getPd()[i].equals(historicalLegacy.getPd()[i]));
            for (int axis = 0; axis < 3; axis++) equal(historicalR1.getAccel()[axis][i], historicalLegacy.getAccel()[axis][i]);
            check(historicalR1.getTmp119()[i] == null && historicalLegacy.getTmp119()[i] == null);
        }
        System.out.println("Java decoding, legacy compatibility, metadata rejection and CSV tests passed (45 C-produced recordings + golden vectors)");
        System.out.println("Historical CSV reconstruction: 210 samples agree across legacy/R1 decoding; TMP119 absent");
    }
}
