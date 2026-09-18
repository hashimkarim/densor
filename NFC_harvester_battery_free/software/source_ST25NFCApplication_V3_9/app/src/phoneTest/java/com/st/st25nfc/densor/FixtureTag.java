package com.st.st25nfc.densor;

import com.st.st25sdk.NFCTag;
import com.st.st25sdk.ndef.NDEFMsg;
import java.util.Arrays;

/** phoneTest build only. Memory transport; no RF, sensor or firmware behavior is simulated. */
final class FixtureTag extends NFCTag {
    final byte[] memory;
    int writes;
    FixtureTag(byte[] dump) {
        this(dump, 512);
    }
    FixtureTag(byte[] dump, int capacity) {
        memory = new byte[capacity];
        mUid = new byte[8]; mName = "Densor fixture (no NFC)";
        System.arraycopy(dump, 0, memory, 0, dump.length);
    }
    @Override public int getMemSizeInBytes() { return memory.length; }
    @Override public synchronized byte[] readBytes(int address, int length) {
        if (address < 0 || length < 0 || address + length > memory.length) throw new IllegalArgumentException();
        return Arrays.copyOfRange(memory, address, address + length);
    }
    @Override public synchronized void writeBytes(int address, byte[] bytes) {
        if (address < 0 || address + bytes.length > memory.length) throw new IllegalArgumentException();
        System.arraycopy(bytes, 0, memory, address, bytes.length); writes++;
    }
    @Override public int getCCFileLength() { throw new UnsupportedOperationException(); }
    @Override public byte getCCMagicNumber() { throw new UnsupportedOperationException(); }
    @Override public byte getCCMappingVersion() { throw new UnsupportedOperationException(); }
    @Override public byte getCCReadAccess() { throw new UnsupportedOperationException(); }
    @Override public byte getCCWriteAccess() { throw new UnsupportedOperationException(); }
    @Override public int getCCMemorySize() { throw new UnsupportedOperationException(); }
    @Override public void writeNdefMessage(NDEFMsg message) { throw new UnsupportedOperationException(); }
    @Override public NDEFMsg readNdefMessage() { throw new UnsupportedOperationException(); }
    @Override public byte[] readCCFile() { throw new UnsupportedOperationException(); }
    @Override public void writeCCFile() { throw new UnsupportedOperationException(); }
    @Override public void writeCCFile(byte[] data) { throw new UnsupportedOperationException(); }
    @Override public void initEmptyCCFile() { throw new UnsupportedOperationException(); }
    @Override public int getSysFileLength() { throw new UnsupportedOperationException(); }
    @Override public byte[] readSysFile() { throw new UnsupportedOperationException(); }
}
