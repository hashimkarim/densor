package com.st.st25nfc.densor.data;

import com.st.st25nfc.densor.DensorProtocol;
import com.st.st25nfc.densor.DensorMultirate;
import java.util.ArrayList;

/** A recording interpreted entirely from its frozen on-tag session metadata. */
public class DensorDataSet {
    private final byte[] binary;
    private final DensorProtocol.Info info;
    private final ArrayList<DensorDataSample> samples;
    private final ArrayList<Long> timestamps = new ArrayList<>();
    public DensorDataSet(byte[] dump, int physicalCapacity) {
        binary = dump.clone(); info = DensorProtocol.inspect(binary, physicalCapacity);
        samples = DensorProtocol.decode(binary, info);
        if(info.multirate) timestamps.addAll(info.sampleTimes);
        else for (int i = 0; i < samples.size(); i++) timestamps.add((info.timeValid ? info.startTime + info.delay : 0) + (long)i * info.period);
    }
    public DensorProtocol.Info getInfo() { return info; }
    public byte[] getBinary() { return binary.clone(); }
    public ArrayList<DensorDataSample> getSamples() { return new ArrayList<>(samples); }
    public ArrayList<Long> getTimestamps() { return new ArrayList<>(timestamps); }
    public int getInterval() { return info.period; }
    public int getStartupDelay() { return info.delay; }
    public long getStartTime() { return info.startTime; }
    public Float[] getTemp() { Float[] out = new Float[samples.size()]; for (int i=0;i<out.length;i++) out[i]=samples.get(i).getTemp(); return out; }
    public Float[] getTmp119() { Float[] out = new Float[samples.size()]; for (int i=0;i<out.length;i++) out[i]=samples.get(i).getTmp119(); return out; }
    public Integer[] getPd() { Integer[] out = new Integer[samples.size()]; for (int i=0;i<out.length;i++) out[i]=samples.get(i).getPd(); return out; }
    public Float[] getVdda() { Float[] out = new Float[samples.size()]; for (int i=0;i<out.length;i++) out[i]=samples.get(i).getVdda(); return out; }
    public Float[][] getAccel() {
        Float[][] out = new Float[3][samples.size()];
        for (int i=0;i<samples.size();i++) for (int j=0;j<3;j++) out[j][i]=samples.get(i).getAccel()[j]; return out;
    }
    public Integer[][] getFuture1() {
        Integer[][] out = new Integer[5][samples.size()];
        for (int i=0;i<samples.size();i++) for (int j=0;j<5;j++) out[j][i]=samples.get(i).getFuture1()[j]; return out;
    }
    public Integer[] getFuture2() { Integer[] out = new Integer[samples.size()]; for (int i=0;i<out.length;i++) out[i]=samples.get(i).getFuture2(); return out; }
    private static String value(Object value) { return value == null ? "" : value.toString(); }
    public String toCsv() {
        StringBuilder csv = new StringBuilder("format,session_id,snapshot_kind,sample_index,time_valid,epoch_seconds,nominal_elapsed_seconds,old_temperature_c,tmp119_temperature_c,photodiode_raw,accel_x_g,accel_y_g,accel_z_g,supply_v,legacy_future1_1,legacy_future1_2,legacy_future1_3,legacy_future1_4,legacy_future1_5,legacy_future2\n");
        for (int i = 0; i < samples.size(); i++) {
            DensorDataSample s = samples.get(i);
            csv.append(info.legacy ? "legacy" : info.multirate ? DensorMultirate.csvFormat(info) : "r1").append(',').append(info.sessionId).append(',')
                .append(info.multirate ? (info.complete()?"complete":info.state==DensorProtocol.RUNNING?"live-snapshot":"recovered-prefix") : info.complete() ? "stable" : "live").append(',').append(i).append(',').append(info.timeValid).append(',')
                .append(info.timeValid ? timestamps.get(i).toString() : "").append(',').append(info.multirate ? timestamps.get(i) : (long)i * info.period).append(',')
                .append(value(s.getTemp())).append(',').append(value(s.getTmp119())).append(',').append(value(s.getPd()));
            for (Float a : s.getAccel()) csv.append(',').append(value(a));
            csv.append(',').append(value(s.getVdda()));
            for (Integer f : s.getFuture1()) csv.append(',').append(value(f));
            csv.append(',').append(value(s.getFuture2())).append('\n');
        }
        return csv.toString();
    }
}
