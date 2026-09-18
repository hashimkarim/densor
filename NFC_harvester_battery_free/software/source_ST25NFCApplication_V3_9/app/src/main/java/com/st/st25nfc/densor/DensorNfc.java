package com.st.st25nfc.densor;

import com.st.st25sdk.NFCTag;
import com.st.st25sdk.STException;
import java.io.IOException;
import com.st.st25nfc.densor.data.DensorDataSet;
import java.util.Arrays;

/** NFC transport: exact lengths, bounded chunks, and checked snapshots. */
public final class DensorNfc {
    private DensorNfc() {}
    private static int memorySize(NFCTag tag) throws IOException {
        try { return tag.getMemSizeInBytes(); }
        catch (STException e) { throw new IOException("Cannot read NFC density: " + e.getMessage(), e); }
    }
    public static byte[] readExact(NFCTag tag, int address, int length) throws Exception {
        if (address < 0 || length < 0 || address + length > memorySize(tag)) throw new IllegalArgumentException("NFC read outside physical memory");
        byte[] result = new byte[length];
        for (int done = 0; done < length;) {
            int count = Math.min(200, length - done);
            byte[] part;
            try { part = tag.readBytes(address + done, count); }
            catch (STException e) { throw new IOException("NFC read failed: " + e.getMessage(), e); }
            if (part == null || part.length == 0 || part.length > count) throw new IllegalStateException("Incomplete NFC read");
            System.arraycopy(part, 0, result, done, part.length); done += part.length;
        }
        return result;
    }
    public static synchronized DensorProtocol.Info info(NFCTag tag) throws Exception {
        byte[] before = readExact(tag, 0, 128), after = readExact(tag, 0, 128);
        DensorProtocol.Info result = DensorProtocol.inspect(before, memorySize(tag));
        if (!Arrays.equals(before, after)) throw new IllegalStateException("Tag changed during reading; refresh after its wake");
        return result;
    }
    public static synchronized DensorDataSet recording(NFCTag tag) throws Exception {
        byte[] before = readExact(tag, 0, 128);
        DensorProtocol.Info info = DensorProtocol.inspect(before, memorySize(tag));
        if (info.state == DensorProtocol.CONFIGURING && !info.legacy) throw new IllegalStateException("Session initialization is incomplete; export/reprovision required");
        byte[] dump = Arrays.copyOf(before, info.pointer);
        byte[] data = readExact(tag, info.logStart, info.pointer - info.logStart);
        System.arraycopy(data, 0, dump, info.logStart, data.length);
        byte[] after = readExact(tag, 0, 128);
        int immutable = info.legacy ? 9 : 92;
        if (!Arrays.equals(Arrays.copyOf(before, immutable), Arrays.copyOf(after, immutable)))
            throw new IllegalStateException("Recording changed while reading; read again between device wakes");
        return new DensorDataSet(dump, memorySize(tag));
    }
    private static void verifiedWrite(NFCTag tag, int address, byte[] bytes) throws Exception {
        try { tag.writeBytes(address, bytes); }
        catch (STException e) { throw new IOException("NFC write failed: " + e.getMessage(), e); }
        if (!Arrays.equals(bytes, readExact(tag, address, bytes.length))) throw new IllegalStateException("NFC write verification failed; request is not active");
    }
    public static synchronized void send(NFCTag tag, DensorProtocol.Info expected, int command, int mask, int period, int oscillator, int startupMinutes) throws Exception {
        DensorProtocol.Info current = info(tag);
        if (current.legacy != expected.legacy || current.version != expected.version || current.sessionId != expected.sessionId || current.acknowledgedId != expected.acknowledgedId
                || current.pointer != expected.pointer || current.state != expected.state)
            throw new IllegalStateException("Device state changed; refresh and retry");
        byte[] request = DensorProtocol.request(current, command, mask, period, oscillator, startupMinutes);
        verifiedWrite(tag, DensorProtocol.MARKER, new byte[4]);
        for (int offset = 0; offset < request.length; offset += 4)
            verifiedWrite(tag, DensorProtocol.PENDING + offset, Arrays.copyOfRange(request, offset, offset + 4));
        verifiedWrite(tag, DensorProtocol.MARKER, Arrays.copyOfRange(request, 4, 8));
    }
}
