package com.st.st25nfc.densor.debug;

import com.st.st25nfc.densor.DensorNfc;
import com.st.st25nfc.densor.DensorProtocol;
import com.st.st25nfc.densor.data.DensorDataSet;
import com.st.st25sdk.NFCTag;
import com.st.st25sdk.STException;
import com.st.st25sdk.ndef.NDEFMsg;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/** Writable EEPROM simulation only; it never opens an NFC connection. */
public class DebugDensorTag extends NFCTag {
    public static final int CAPACITY = 8192;
    private byte[] memory;

    public DebugDensorTag(byte[] dump) {
        mName = "Densor virtual tag";
        mUid = new byte[] {0x44, 0x45, 0x42, 0x55, 0x47};
        load(dump);
    }

    public void load(byte[] dump) {
        if (dump == null || dump.length < 9 || dump.length > CAPACITY)
            throw new IllegalArgumentException("Use a raw EEPROM dump of 9 to 8192 bytes, starting at address 0.");
        // R1 exports include density. Legacy prefixes do not: model an 8 KiB
        // virtual tag unless a full supported-size image establishes its size.
        boolean r1 = dump.length >= 128 && dump[0] == 'D' && dump[1] == 'E' && dump[2] == 'N';
        int capacity = r1 ? DensorProtocol.u16(dump, DensorProtocol.HEADER + 8)
                : dump.length == 512 || dump.length == 2048 ? dump.length : CAPACITY;
        DensorProtocol.Info info = DensorProtocol.inspect(dump, capacity);
        if (dump.length < info.pointer || dump.length > capacity)
            throw new IllegalArgumentException("Dump is truncated before its pointer or exceeds its declared capacity.");
        new DensorDataSet(Arrays.copyOf(dump, info.pointer), capacity); // Validate every committed record.
        // Lock in the same order as DensorNfc.send: no import/export in the
        // middle of its multi-page pending-configuration transaction.
        synchronized (DensorNfc.class) {
            synchronized (this) { memory = Arrays.copyOf(dump, capacity); }
        }
    }

    public static byte[] readDump(InputStream input) throws IOException {
        if (input == null) throw new IOException("The selected file could not be opened.");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (bytes.size() + count > CAPACITY) {
                throw new IOException("Dump is too large. Maximum size is 8192 bytes.");
            }
            bytes.write(buffer, 0, count);
        }
        if (bytes.size() < 9) {
            throw new IOException("Dump is too short. Include at least the 9-byte Densor header.");
        }
        return bytes.toByteArray();
    }

    public byte[] snapshot() {
        synchronized (DensorNfc.class) {
            synchronized (this) { return memory.clone(); }
        }
    }

    @Override
    public synchronized byte[] readBytes(int address, int length) throws STException {
        checkBounds(address, length);
        return Arrays.copyOfRange(memory, address, address + length);
    }

    @Override
    public synchronized void writeBytes(int address, byte[] bytes) throws STException {
        if (bytes == null) throw new STException("Missing bytes to write.");
        checkBounds(address, bytes.length);
        System.arraycopy(bytes, 0, memory, address, bytes.length);
    }

    private void checkBounds(int address, int length) throws STException {
        if (address < 0 || length < 0 || address > memory.length || length > memory.length - address) {
            throw new STException("Read or write exceeds the virtual tag memory.");
        }
    }

    @Override public synchronized int getMemSizeInBytes() { return memory.length; }

    // EEPROM dumps contain neither NDEF metadata nor the chip configuration area.
    private STException unsupported() {
        return new STException("This debug tag only simulates Densor EEPROM reads and writes.");
    }
    @Override public int getCCFileLength() throws STException { throw unsupported(); }
    @Override public byte getCCMagicNumber() throws STException { throw unsupported(); }
    @Override public byte getCCMappingVersion() throws STException { throw unsupported(); }
    @Override public byte getCCReadAccess() throws STException { throw unsupported(); }
    @Override public byte getCCWriteAccess() throws STException { throw unsupported(); }
    @Override public int getCCMemorySize() throws STException { throw unsupported(); }
    @Override public void writeNdefMessage(NDEFMsg message) throws STException { throw unsupported(); }
    @Override public NDEFMsg readNdefMessage() throws STException { throw unsupported(); }
    @Override public byte[] readCCFile() throws STException { throw unsupported(); }
    @Override public void writeCCFile() throws STException { throw unsupported(); }
    @Override public void writeCCFile(byte[] bytes) throws STException { throw unsupported(); }
    @Override public void initEmptyCCFile() throws STException { throw unsupported(); }
    @Override public int getSysFileLength() throws STException { throw unsupported(); }
    @Override public byte[] readSysFile() throws STException { throw unsupported(); }
}
