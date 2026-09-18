package com.st.st25nfc.densor.data;

/**
 * Object that can hold a single densor sample.
 */
public class DensorDataSample {

    /**
     * The temperature reading of the sample.
     */
    private Float temp;
    private Float tmp119;
    /**
     * The photo-diode readings of the sample.
     */
    private Integer pd;
    /**
     * The future1 readings of the sample.
     */
    private Integer[] future1;
    /**
     * The future2 reading of the sample.
     */
    private Integer future2;
    /**
     * The accelerometer readings of the sample.
     */
    private Float[] accel;
    /**
     * The supply voltage readings of the sample.
     */
    private Float vdda;

    /**
     * Creates a new sample with given readings.
     *
     * @param temp The temperature reading of the sample.
     * @param pd The photo-diode reading of the sample.
     * @param future1 The future1 readings of the sample.
     * @param future2 The future2 reading of the sample.
     * @param accel The accelerometer readings of the sample.
     * @param vdda The supply voltage reading of the sample.
     */
    public DensorDataSample(Float temp, Integer pd, Integer[] future1, Integer future2, Float[] accel, Float vdda){
        this(temp, null, pd, future1, future2, accel, vdda);
    }

    public DensorDataSample(Float temp, Float tmp119, Integer pd, Integer[] future1, Integer future2, Float[] accel, Float vdda){
        this.temp = temp;
        this.tmp119 = tmp119;
        this.pd = pd;
        this.future1 = future1;
        this.future2 = future2;
        this.accel = accel;
        this.vdda = vdda;
    }

    /**
     * Returns the temperature reading of the sample.
     *
     * @return The temperature reading.
     */
    public Float getTemp() {
        return temp;
    }

    public Float getTmp119() { return tmp119; }

    /**
     * Returns the photo-diode reading of the sample.
     *
     * @return The photo-diode reading.
     */
    public Integer getPd() {
        return pd;
    }

    /**
     * Returns the future1 readings of the sample.
     *
     * @return The future1 readings.
     */
    public Integer[] getFuture1() {
        return future1;
    }

    /**
     * Returns the future2 reading of the sample.
     *
     * @return The future2 reading.
     */
    public Integer getFuture2() {
        return future2;
    }

    /**
     * Returns the Accelerometer readings of the sample.
     *
     * @return The accelerometers readings.
     */
    public Float[] getAccel() {
        return accel;
    }

    /**
     * Returns the supply voltage reading of the sample.
     *
     * @return The supply voltage reading.
     */
    public Float getVdda() {
        return vdda;
    }
}
