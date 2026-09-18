package com.st.st25nfc.densor;

import com.st.st25nfc.densor.data.DensorDataSample;
import java.util.ArrayList;

/** Pure Java byte protocol. Keep in sync with docs/r1-protocol.md, not UI settings. */
public final class DensorProtocol {
    private DensorProtocol() {}
    public static final int LOG_START = 128, HEADER = 12, STATUS = 76, PENDING = 92, MARKER = 124;
    public static final int OLD = 1, TMP119 = 2, PD = 4, ACCEL = 8;
    public static final int STOPPED = 0, CONFIGURING = 1, RUNNING = 2, ERROR = 3, FULL = 4;
    public static final int APPLY_SETTINGS = 2;
    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException(message);
    }
    public static int u16(byte[] b, int p) { return (b[p] & 255) | ((b[p + 1] & 255) << 8); }
    public static long u32(byte[] b, int p) { return u16(b, p) | ((long)u16(b, p + 2) << 16); }
    public static void put16(byte[] b, int p, int v) { b[p] = (byte)v; b[p + 1] = (byte)(v >>> 8); }
    public static void put32(byte[] b, int p, long v) { put16(b, p, (int)v); put16(b, p + 2, (int)(v >>> 16)); }
    public static int crc16(byte[] b, int p, int n) {
        int crc = 65535;
        for (int i = p; i < p + n; i++) {
            crc ^= (b[i] & 255) << 8;
            for (int bit = 0; bit < 8; bit++) crc = ((crc << 1) ^ ((crc & 32768) != 0 ? 0x1021 : 0)) & 65535;
        }
        return crc;
    }
    private static boolean ascii(byte[] b, int p, String s) {
        if (p + s.length() > b.length) return false;
        for (int i = 0; i < s.length(); i++) if (b[p + i] != (byte)s.charAt(i)) return false;
        return true;
    }
    private static boolean zero(byte[] b, int p, int n) {
        for (int i = p; i < p + n; i++) if (b[i] != 0) return false;
        return true;
    }
    public static int stride(int mask) {
        require(mask > 0 && (mask & ~15) == 0, "Unsupported sensor mask");
        int bytes = 1 + ((mask & OLD) != 0 ? 2 : 0) + ((mask & TMP119) != 0 ? 2 : 0)
                + ((mask & PD) != 0 ? 2 : 0) + ((mask & ACCEL) != 0 ? 6 : 0);
        return (bytes + 3) & ~3;
    }
    public static boolean periodValid(long period) { return period > 0 && (period < 60 || (period <= 3540 && period % 60 == 0)); }
    public static final class Info {
        public boolean legacy, timeValid;
        public int version, capacity, pointer, logStart, mask, stride, period, state, error, detected, oscillator, legacyMask, delay;
        public long sessionId, acknowledgedId, pendingId, startTime;
        public int pendingCommand, pendingMask, pendingPeriod, pendingDelay;
        public boolean complete() { return !legacy && state != RUNNING && state != CONFIGURING; }
        public int samples() { return stride == 0 ? 0 : (pointer - logStart) / stride; }
        public String wakeNotice() {
            return "Settings/reset apply on the next regular wake (current interval: "
                    + (periodValid(period) ? period : 120) + " s).";
        }
        public String summary() {
            if (legacy) return "Legacy recording (read-only): " + samples() + " samples, " + period + " s interval";
            String[] states = {"Stopped", "Configuring", "Running", "Error", "Full"};
            return "R1 v" + version + " / " + states[state] + " / session " + sessionId + "\nActive: " + sensorNames(mask)
                    + ", " + period + " s; startup delay " + delay / 60 + " min; " + samples() + " samples\n"
                    + "Memory: " + pointer + "/" + capacity + " bytes; " + (stride == 0 ? 0 : (capacity - pointer) / stride)
                    + " samples remaining\nLast confirmed sensors: " + sensorNames(detected)
                    + (error == 0 ? "" : "\nDevice error " + error + ": " + errorName(error))
                    + (pendingId > acknowledgedId ? "\nPending request " + pendingId + ": "
                        + "apply settings and reset: " + sensorNames(pendingMask) + ", " + pendingPeriod + " s"
                        + ", startup delay " + pendingDelay / 60 + " min — awaiting a device wake" : "\nAcknowledged request " + acknowledgedId)
                    + "\n" + wakeNotice() + "\nUTC start unavailable; times are nominal elapsed seconds.";
        }
    }
    public static String errorName(int error) {
        String[] errors = {"none", "bus/write readiness failure", "invalid header", "invalid pointer", "invalid configuration",
                "selected sensor missing or failed", "memory full", "invalid state", "acquisition timeout"};
        return error >= 0 && error < errors.length ? errors[error] : "unknown error";
    }
    public static String sensorNames(int mask) {
        ArrayList<String> names = new ArrayList<>();
        if ((mask & OLD) != 0) names.add("LIS2DW12 temperature");
        if ((mask & TMP119) != 0) names.add("TMP119 temperature");
        if ((mask & PD) != 0) names.add("photodiode");
        if ((mask & ACCEL) != 0) names.add("acceleration");
        StringBuilder joined = new StringBuilder();
        for (String name : names) { if (joined.length() != 0) joined.append(", "); joined.append(name); }
        return names.isEmpty() ? "none" : joined.toString();
    }
    public static Info inspect(byte[] b, int physicalCapacity) {
        require(physicalCapacity == 512 || physicalCapacity == 2048 || physicalCapacity == 8192, "Unsupported tag density");
        require(b.length >= 9, "Truncated header");
        if (!ascii(b, 0, "DENSOR1!")) {
            require(!ascii(b, 0, "DEN") && !ascii(b, HEADER, "DNR"), "Damaged or unsupported Densor header; cannot use legacy decoding");
            return legacy(b, physicalCapacity);
        }
        require(b.length >= LOG_START, "Truncated R1 metadata");
        int h = HEADER, s = STATUS, version = b[h + 4] & 255;
        require(zero(b, 10, 2) && ascii(b, h, "DNR1") && (version == 2 || version == 3) && b[h + 5] == 1
                && b[h + 6] == 64 && b[h + 7] == 0 && u16(b, h + 8) == physicalCapacity && u16(b, h + 10) == LOG_START
                && (b[h + 14] & 255) <= 1 && b[h + 15] == 15 && b[h + 16] == 0 && (b[h + 17] & 240) == 0
                && (version == 2 ? b[h + 18] == 0 : (b[h + 18] & 255) <= 59) && b[h + 19] == 0 && zero(b, h + 32, 30) && crc16(b, h, 62) == u16(b, h + 62),
                "Unsupported or inconsistent R1 header");
        require(crc16(b, s, 14) == u16(b, s + 14) && (b[s + 2] & 255) <= FULL && (b[s + 3] & 255) <= 8
                && (b[s + 4] & 240) == 0 && b[s + 5] == 0 && u32(b, s + 10) == u32(b, h + 20), "Invalid R1 acknowledgement/status");
        Info info = new Info();
        info.version = version; info.delay = (b[h + 18] & 255) * 60;
        info.capacity = physicalCapacity; info.pointer = u16(b, 8); info.logStart = LOG_START;
        info.mask = b[h + 12] & 255; info.stride = b[h + 13] & 255; info.oscillator = b[h + 14];
        info.sessionId = u32(b, h + 20); long configId = u32(b, h + 24), period = u32(b, h + 28);
        info.state = b[s + 2]; info.error = b[s + 3]; info.detected = b[s + 4]; info.acknowledgedId = u32(b, s + 6);
        if (info.mask == 0) require(info.stride == 0 && info.sessionId == 0 && configId == 0 && period == 0 && info.state != RUNNING && info.delay == 0, "Invalid unconfigured session");
        else require(info.stride == stride(info.mask) && info.sessionId != 0 && info.sessionId == configId
                && periodValid(period) && (info.mask & (b[h + 17] & 255)) == info.mask, "Invalid active session configuration");
        info.period = (int)period;
        require(info.pointer >= LOG_START && info.pointer <= physicalCapacity && info.pointer % 4 == 0
                && (info.stride == 0 ? info.pointer == LOG_START : (info.pointer - LOG_START) % info.stride == 0), "Invalid R1 next-write pointer");
        if (u32(b, MARKER) != 0 && u32(b, MARKER) == u32(b, PENDING + 4)
                && ascii(b, PENDING, "DCP" + version) && b[PENDING + 11] == version && crc16(b, PENDING, 30) == u16(b, PENDING + 30)) {
            info.pendingId = u32(b, MARKER); info.pendingCommand = b[PENDING + 8] & 255;
            info.pendingMask = b[PENDING + 9] & 255; info.pendingPeriod = (int)u32(b, PENDING + 12); info.pendingDelay = (b[PENDING + 20] & 255) * 60;
        }
        return info;
    }
    private static int bcd(int raw, boolean positive) {
        int n = raw & 127;
        require((n & 15) <= 9 && (n >> 4) <= 5 && (!positive || n != 0), "Invalid legacy BCD period/delay");
        return (n >> 4) * 10 + (n & 15);
    }
    private static Info legacy(byte[] b, int capacity) {
        Info info = new Info(); info.legacy = true; info.capacity = capacity; info.logStart = 9;
        int mask = b[0] & 255;
        require((mask & ~0xaf) == 0, "Unsupported format; legacy header is invalid");
        info.legacyMask = mask; info.mask = ((mask & 32) != 0 ? OLD : 0) | ((mask & 8) != 0 ? PD : 0) | ((mask & 1) != 0 ? ACCEL : 0);
        info.stride = ((mask & 32) != 0 ? 2 : 0) + ((mask & 8) != 0 ? 2 : 0)
                + ((mask & 4) != 0 ? 10 : 0) + ((mask & 2) != 0 ? 2 : 0) + ((mask & 1) != 0 ? 6 : 0)
                + ((mask & 8) != 0 && (mask & 32) == 0 ? 1 : 0);
        require(info.stride > 0, "Legacy header has no enabled fields");
        info.period = bcd(b[5] & 255, true) * (b[5] < 0 ? 60 : 1);
        info.delay = bcd(b[6] & 255, false) * 60;
        info.pointer = u16(b, 7);
        require(info.pointer >= 9 && info.pointer <= capacity && (info.pointer - 9) % info.stride == 0, "Invalid legacy pointer/stride");
        info.startTime = ((long)(b[1] & 255) << 24) | ((long)(b[2] & 255) << 16) | ((b[3] & 255) << 8) | (b[4] & 255);
        info.timeValid = (((b[1] & 255) << 8) | (b[2] & 255)) >= 3400;
        return info;
    }
    public static byte[] request(Info info, int command, int mask, int period, int oscillator) {
        return request(info, command, mask, period, oscillator, 0);
    }
    public static byte[] request(Info info, int command, int mask, int period, int oscillator, int startupMinutes) {
        require(!info.legacy, "Legacy firmware is read-only. Export, flash R1 and provision through SWD first.");
        require(info.version == 3, "This older R1 format is read-only. Export, update firmware and reprovision before configuring.");
        require(startupMinutes >= 0 && startupMinutes <= 59, "Startup delay must be 0–59 minutes (0 disables it)");
        require(info.pendingId <= info.acknowledgedId, "A configuration request is awaiting acknowledgement");
        require(info.acknowledgedId < 0xffffffffL, "Request IDs exhausted; export and reprovision");
        require(command == APPLY_SETTINGS, "Unsupported command; R1 uses apply settings/reset-and-log");
        require(info.state != CONFIGURING, "Device initialization is incomplete; export and reprovision");
        stride(mask); require(periodValid(period) && oscillator >= 0 && oscillator <= 1, "Use 1–59 seconds or whole minutes up to 3540 seconds");
        byte[] p = new byte[32]; p[0] = 'D'; p[1] = 'C'; p[2] = 'P'; p[3] = '3';
        put32(p, 4, info.acknowledgedId + 1); p[8] = (byte)command; p[9] = (byte)mask; p[10] = (byte)oscillator; p[11] = 3; p[20] = (byte)startupMinutes;
        put32(p, 12, period); put32(p, 16, info.sessionId); put16(p, 30, crc16(p, 0, 30)); return p;
    }
    public static ArrayList<DensorDataSample> decode(byte[] dump, Info info) {
        require(dump.length == info.pointer, "Recording length does not match its pointer");
        ArrayList<DensorDataSample> samples = new ArrayList<>();
        if (info.stride == 0) return samples;
        for (int address = info.logStart; address < info.pointer; address += info.stride) {
            int p = address, code = 255, mask = info.mask;
            Float old = null, tmp = null, supply = null; Integer pd = null, future2 = null;
            Integer[] future1 = {null, null, null, null, null}; Float[] accel = {null, null, null};
            if (!info.legacy) {
                code = dump[p++] & 255;
                require(code <= 15 || code == 255, "Invalid supply code");
                require((mask & 7) != 0 ? code != 255 : code == 255, "Supply code conflicts with the session acquisition policy");
            }
            if ((mask & OLD) != 0) {
                int raw = (short)u16(dump, p); p += 2;
                if (info.legacy) { code = raw & 15; raw &= ~15; }
                old = raw / 256f + 25f;
            } else if (info.legacy && (mask & PD) != 0) { code = dump[p++] & 255; require(code <= 15, "Invalid legacy supply code"); }
            if ((mask & TMP119) != 0) { tmp = ((short)u16(dump, p)) / 128f; p += 2; }
            if ((mask & PD) != 0) { pd = u16(dump, p); p += 2; require(pd <= 4095, "Invalid photodiode code"); }
            if (info.legacy && (info.legacyMask & 4) != 0) for (int i = 0; i < 5; ++i) { future1[i] = u16(dump, p); p += 2; }
            if (info.legacy && (info.legacyMask & 2) != 0) { future2 = ((dump[p] & 255) << 8) | (dump[p + 1] & 255); p += 2; }
            if ((mask & ACCEL) != 0) for (int i = 0; i < 3; ++i) { accel[i] = ((short)u16(dump, p)) / 16384f; p += 2; }
            require(p <= address + info.stride && zero(dump, p, address + info.stride - p), "Nonzero record padding");
            if (code != 255) supply = (18 + code) / 10f;
            samples.add(new DensorDataSample(old, tmp, pd, future1, future2, accel, supply));
        }
        return samples;
    }
}
