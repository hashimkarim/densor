package com.st.st25nfc.densor;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Spinner;
import android.widget.ScrollView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.androidplot.xy.XYPlot;
import com.st.st25nfc.densor.data.DensorDataSet;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class DensorPhoneTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private FixtureActivity activity;
    private void screenshot(String name) throws Exception {
        instrumentation.waitForIdleSync();
        // Wait for SurfaceFlinger to present the just-asserted view hierarchy.
        Thread.sleep(500);
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        File directory = new File(instrumentation.getTargetContext().getExternalFilesDir(null), "densor-phone-tests");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { bitmap.recycle(); }
    }
    private byte[] fixture(int mask) throws Exception {
        return asset("r1-mask-" + mask + ".bin");
    }
    private byte[] asset(String name) throws Exception {
        try (InputStream input = instrumentation.getContext().getAssets().open(name)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[256];
            for (int n; (n = input.read(buffer)) != -1;) output.write(buffer, 0, n);
            return output.toByteArray();
        }
    }
    private void launch(int mask, DensorFragment fragment) throws Exception {
        launch(fixture(mask), 512, fragment);
    }
    private void launch(byte[] dump, int capacity, DensorFragment fragment) throws Exception {
        FixtureActivity.fixture = new FixtureTag(dump, capacity);
        Intent intent = new Intent().setClassName(instrumentation.getTargetContext(), FixtureActivity.class.getName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = (FixtureActivity)instrumentation.startActivitySync(intent);
        instrumentation.runOnMainSync(() -> activity.show(fragment));
        DensorProtocol.Info info=DensorProtocol.inspect(dump,capacity);
        awaitStatus(fragment, info.multirate?DensorMultirate.revision(info.storage,info.timing):"R1");
    }
    private void awaitStatus(DensorFragment fragment, String expected) throws Exception {
        long deadline = android.os.SystemClock.uptimeMillis() + 5000;
        String[] actual = {""};
        do {
            instrumentation.runOnMainSync(() -> actual[0] = fragment.status.getText().toString());
            if (actual[0].contains(expected)) return;
            Thread.sleep(20);
        } while (android.os.SystemClock.uptimeMillis() < deadline);
        fail("Expected " + expected + ", got " + actual[0]);
    }
    private <T> List<T> widgets(View view, Class<T> type) {
        List<T> result = new ArrayList<>();
        if (type.isInstance(view)) result.add(type.cast(view));
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i=0; i<group.getChildCount(); i++) result.addAll(widgets(group.getChildAt(i), type));
        }
        return result;
    }
    private void click(DensorFragment fragment, String text) {
        instrumentation.runOnMainSync(() -> {
            for (Button button : widgets(fragment.getView(), Button.class))
                if (button.getText().toString().equals(text)) { button.performClick(); return; }
            fail("Missing button " + text);
        });
    }
    private TextView capacityPreview(DensorFragment fragment) {
        for(TextView view:widgets(fragment.getView(),TextView.class))
            if("Proposed recording capacity".contentEquals(view.getContentDescription()==null?"":view.getContentDescription())) return view;
        throw new AssertionError("Missing capacity preview");
    }
    @After public void finish() {
        if (activity != null) instrumentation.runOnMainSync(() -> activity.finish());
        instrumentation.waitForIdleSync(); FixtureActivity.fixture = null;
    }
    @Test public void temperatureModesDecodeInAndroidRuntime() throws Exception {
        for (int mask : new int[]{1,2,3,15}) {
            DensorDataSet data = new DensorDataSet(fixture(mask), 512);
            assertEquals(1, data.getInfo().samples());
            assertEquals((mask & 1) != 0, data.getSamples().get(0).getTemp() != null);
            assertEquals((mask & 2) != 0, data.getSamples().get(0).getTmp119() != null);
            assertTrue(data.toCsv().contains("tmp119"));
            assertTrue(data.toCsv().contains(",live,"));
        }
    }
    @Test public void settingsApplyFromRunningRecordingInOneRequest() throws Exception {
        DensorSettingsFragment fragment = new DensorSettingsFragment(); launch(15, fragment);
        instrumentation.runOnMainSync(() -> {
            Spinner selection = widgets(fragment.getView(), Spinner.class).get(0);
            assertEquals(4, selection.getCount()); selection.setSelection(2);
            widgets(fragment.getView(), EditText.class).get(1).setText("59");
        });
        click(fragment, "Apply settings and reset log");
        awaitStatus(fragment, "have not changed yet");
        assertEquals(10, FixtureActivity.fixture.writes);
        DensorProtocol.Info active = DensorNfc.info(FixtureActivity.fixture);
        assertEquals(15, active.mask); assertEquals(DensorProtocol.RUNNING, active.state);
        assertEquals(1, active.samples()); assertEquals(3, active.pendingMask);
        assertEquals(0, active.delay); assertEquals(3540, active.pendingDelay);
    }
    @Test public void bothTemperaturePlotsRenderWithSourceLabels() throws Exception {
        DataViewFragment fragment = new DataViewFragment(); launch(15, fragment);
        awaitStatus(fragment, "Latest TMP119");
        instrumentation.runOnMainSync(() -> {
            List<XYPlot> plots = widgets(fragment.getView(), XYPlot.class);
            assertEquals(7, plots.size());
            assertEquals(View.VISIBLE, plots.get(0).getVisibility());
            assertEquals(View.VISIBLE, plots.get(1).getVisibility());
            assertTrue(plots.get(0).getTitle().getText().contains("LIS2DW12"));
            assertTrue(plots.get(1).getTitle().getText().contains("TMP119"));
            ((ScrollView)fragment.getView()).scrollTo(0, plots.get(0).getTop());
        });
        screenshot("both-temperatures");
        instrumentation.runOnMainSync(() -> {
            for (XYPlot plot : widgets(fragment.getView(), XYPlot.class).subList(0, 2)) {
                assertTrue(plot.getBounds().getWidth().doubleValue() > 0);
                assertTrue(plot.getBounds().getHeight().doubleValue() > 0);
            }
        });
    }
    @Test public void resetReusesSettingsWithoutStartStopControls() throws Exception {
        TimeSyncFragment fragment = new TimeSyncFragment(); byte[] delayed = fixture(3);
        delayed[DensorProtocol.HEADER + 18] = 1;
        DensorProtocol.put16(delayed, 74, DensorProtocol.crc16(delayed, 12, 62));
        launch(delayed, 512, fragment);
        click(fragment, "Reset log using current settings"); awaitStatus(fragment, "Reset requested");
        DensorProtocol.Info info = DensorNfc.info(FixtureActivity.fixture);
        assertEquals(10, FixtureActivity.fixture.writes);
        assertEquals(3, info.pendingMask); assertEquals(120, info.pendingPeriod); assertEquals(60, info.pendingDelay);
        assertEquals(DensorProtocol.APPLY_SETTINGS, info.pendingCommand);
        instrumentation.runOnMainSync(() -> {
            for (Button button : widgets(fragment.getView(), Button.class)) {
                assertFalse(button.getText().toString().contains("Request start"));
                assertFalse(button.getText().toString().contains("Request stop"));
            }
        });
    }
    @Test public void newConfigurationRemainsPendingUntilFirmwareAcknowledges() throws Exception {
        DensorSettingsFragment fragment = new DensorSettingsFragment(); launch(15, fragment);
        instrumentation.runOnMainSync(() -> widgets(fragment.getView(), Spinner.class).get(0).setSelection(2));
        click(fragment, "Apply settings and reset log"); awaitStatus(fragment, "have not changed yet");
        click(fragment, "Refresh active / pending settings"); awaitStatus(fragment, "awaiting a device wake");
        DensorProtocol.Info info = DensorNfc.info(FixtureActivity.fixture);
        assertEquals(15, info.mask); assertEquals(3, info.pendingMask);
        assertTrue(info.pendingId > info.acknowledgedId);
        DensorProtocol.put32(FixtureActivity.fixture.memory, DensorProtocol.MARKER, 0);
        assertEquals(0, DensorNfc.info(FixtureActivity.fixture).pendingId);
        assertEquals(15, DensorNfc.info(FixtureActivity.fixture).mask);
        DensorProtocol.put32(FixtureActivity.fixture.memory, DensorProtocol.MARKER, info.pendingId);
        assertEquals(10, FixtureActivity.fixture.writes); // invalidate + 8 body pages + commit marker
        screenshot("pending-settings");
    }
    @Test public void historicalCsvReconstructionPlotsWithoutInventedTmp119() throws Exception {
        byte[] dump = asset("historical-reconstructed-r1.bin");
        DensorDataSet r1 = new DensorDataSet(dump, 8192);
        DensorDataSet legacy = new DensorDataSet(asset("historical-reconstructed-legacy.bin"), 8192);
        assertEquals(210, r1.getInfo().samples());
        assertArrayEquals(legacy.getTemp(), r1.getTemp());
        assertArrayEquals(legacy.getPd(), r1.getPd());
        for (Float value : r1.getTmp119()) assertNull(value);
        DataViewFragment fragment = new DataViewFragment(); launch(dump, 8192, fragment);
        awaitStatus(fragment, "Latest LIS2DW12");
        instrumentation.runOnMainSync(() -> {
            List<XYPlot> plots = widgets(fragment.getView(), XYPlot.class);
            assertEquals(View.VISIBLE, plots.get(0).getVisibility());
            assertEquals(View.GONE, plots.get(1).getVisibility());
            ((ScrollView)fragment.getView()).scrollTo(0, plots.get(0).getTop());
        });
        screenshot("historical-reconstruction");
    }
    @Test public void multirateAllFourImagesDecodeAndExport() throws Exception {
        for(String storage:new String[]{"partitioned","shared"}) for(String timing:new String[]{"fsm","rtc"}) {
            DensorDataSet data=new DensorDataSet(asset("multirate-"+storage+"-"+timing+".bin"),2048);
            assertTrue(data.getInfo().complete());assertEquals(25,data.getInfo().samples());
            assertArrayEquals(new int[]{3,5,9,25},data.getInfo().recordCounts);
            assertEquals(Long.valueOf(240),data.getTimestamps().get(24));
            assertEquals(Float.valueOf(24f),data.getSamples().get(0).getTemp());
            assertEquals(Float.valueOf(-1f),data.getSamples().get(0).getTmp119());
            assertNull(data.getSamples().get(1).getTemp());assertNull(data.getSamples().get(1).getTmp119());
            String revision=(storage.equals("shared")?"R2":"R3")+(timing.equals("fsm")?"a":"b");
            assertTrue(data.toCsv().contains("\n"+revision.toLowerCase(java.util.Locale.ROOT)+","));
            assertTrue(data.getInfo().summary().startsWith(revision+" / "));
        }
    }
    @Test public void multirateSettingsPreserveIndependentRatesAndInstalledStrategies() throws Exception {
        DensorSettingsFragment fragment=new DensorSettingsFragment();
        launch(asset("multirate-partitioned-fsm.bin"),2048,fragment);
        instrumentation.runOnMainSync(()-> {
            widgets(fragment.getView(),Spinner.class).get(0).setSelection(2);
            List<EditText> inputs=widgets(fragment.getView(),EditText.class);
            assertEquals(6,inputs.size());inputs.get(0).setText("10");inputs.get(2).setText("120");inputs.get(3).setText("60");
        });
        click(fragment,"Apply settings and reset log");awaitStatus(fragment,"have not changed yet");
        DensorProtocol.Info info=DensorNfc.info(FixtureActivity.fixture);
        assertEquals(1,info.storage);assertEquals(1,info.timing);assertEquals(15,info.mask);assertEquals(3,info.pendingMask);
        assertArrayEquals(new int[]{12,6,0,0},info.pendingMultipliers);assertEquals(12,FixtureActivity.fixture.writes);
        assertArrayEquals(new int[]{157,313,0,0},info.pendingPages);
        click(fragment,"Refresh active / pending settings");awaitStatus(fragment,"Pending request");
        screenshot("multirate-partitioned-settings");
    }
    @Test public void partitionPreviewRecalculatesWhileEditingWithoutWritingHeader() throws Exception {
        DensorSettingsFragment fragment=new DensorSettingsFragment();
        byte[] original=asset("multirate-partitioned-fsm.bin");launch(original,2048,fragment);
        instrumentation.runOnMainSync(()->widgets(fragment.getView(),Spinner.class).get(0).setSelection(2));
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(()-> {
            List<EditText> inputs=widgets(fragment.getView(),EditText.class);
            inputs.get(2).setText("120");inputs.get(3).setText("120");
            TextView preview=capacityPreview(fragment);
            assertTrue(preview.getText().toString().contains("LIS2DW12 temperature: 120 s; capacity 235 records (940 bytes)"));
            assertTrue(preview.getText().toString().contains("TMP119 temperature: 120 s; capacity 235 records (940 bytes)"));
            inputs.get(3).setText("60");
            assertTrue(preview.getText().toString().contains("LIS2DW12 temperature: 120 s; capacity 157 records (628 bytes)"));
            assertTrue(preview.getText().toString().contains("TMP119 temperature: 60 s; capacity 313 records (1252 bytes)"));
            assertTrue(preview.getText().toString().contains("unused 0 bytes"));
            inputs.get(0).setText("7");
            assertTrue(preview.getText().toString().contains("positive multiple"));
            assertFalse(preview.getText().toString().contains("records"));
            inputs.get(0).setText("");assertTrue(preview.getText().toString().contains("whole-number base"));
            inputs.get(0).setText("10");
            inputs.get(4).setText(""); // Disabled sensors never block a valid proposal.
            assertTrue(preview.getText().toString().contains("1252 bytes"));
            CheckBox pd=widgets(fragment.getView(),CheckBox.class).get(0);pd.setChecked(true);
            assertTrue(preview.getText().toString().contains("each enabled sensor"));
            inputs.get(4).setText("30");
            assertTrue(preview.getText().toString().contains("Photodiode: 30 s; capacity"));
            pd.setChecked(false);assertTrue(preview.getText().toString().contains("1252 bytes"));
            ((ScrollView)fragment.getView()).scrollTo(0,preview.getParent() instanceof View?((View)preview.getParent()).getTop()+preview.getTop():0);
        });
        assertEquals(0,FixtureActivity.fixture.writes);assertArrayEquals(original,FixtureActivity.fixture.memory);
        screenshot("multirate-automatic-partitions");
        click(fragment,"Apply settings and reset log");awaitStatus(fragment,"have not changed yet");
        assertArrayEquals(new int[]{12,6,0,0},DensorNfc.info(FixtureActivity.fixture).pendingMultipliers);
    }
    @Test public void sharedPreviewUsesOnePoolAndRespondsToRateChanges() throws Exception {
        DensorSettingsFragment fragment=new DensorSettingsFragment();launch(asset("multirate-shared-rtc.bin"),2048,fragment);
        instrumentation.runOnMainSync(()-> {
            List<EditText> inputs=widgets(fragment.getView(),EditText.class);
            TextView preview=capacityPreview(fragment);
            inputs.get(2).setText("120");
            assertTrue(preview.getText().toString().contains("Proposed R2b shared pool"));
            assertTrue(preview.getText().toString().contains("Pooled capacity 1880 bytes; expected stop at 28200 s"));
            inputs.get(2).setText("60");
            assertTrue(preview.getText().toString().contains("Pooled capacity 1880 bytes; expected stop at 14100 s"));
            assertFalse(preview.getText().toString().contains("automatic partitions"));
        });
        assertEquals(0,FixtureActivity.fixture.writes);
        click(fragment,"Apply settings and reset log");awaitStatus(fragment,"have not changed yet");
        DensorProtocol.Info info=DensorNfc.info(FixtureActivity.fixture);
        assertEquals(2,info.storage);assertEquals(2,info.timing);
        assertArrayEquals(new int[]{6,0,0,0},info.pendingMultipliers);
        assertArrayEquals(new int[]{470,0,0,0},info.pendingPages);
    }
    @Test public void r3bSettingsUseRtcSchedulerAndAutomaticPartitions() throws Exception {
        DensorSettingsFragment fragment=new DensorSettingsFragment();launch(asset("multirate-partitioned-rtc.bin"),2048,fragment);
        instrumentation.runOnMainSync(()-> {
            assertTrue(fragment.status.getText().toString().contains("R3b / Partitioned / RTC time"));
            assertTrue(capacityPreview(fragment).getText().toString().contains("Proposed R3b automatic partitions"));
            widgets(fragment.getView(),EditText.class).get(2).setText("60");
            assertTrue(capacityPreview(fragment).getText().toString().contains("capacity 470 records (1880 bytes)"));
        });
        assertEquals(0,FixtureActivity.fixture.writes);
        click(fragment,"Apply settings and reset log");awaitStatus(fragment,"have not changed yet");
        DensorProtocol.Info info=DensorNfc.info(FixtureActivity.fixture);
        assertEquals(1,info.storage);assertEquals(2,info.timing);
        assertArrayEquals(new int[]{6,0,0,0},info.pendingMultipliers);
        assertArrayEquals(new int[]{470,0,0,0},info.pendingPages);
        click(fragment,"Refresh active / pending settings");awaitStatus(fragment,"Pending request");
        screenshot("multirate-r3b-settings");
    }
    @Test public void multiratePlotsRenderDifferentTemperaturePeriods() throws Exception {
        DataViewFragment fragment=new DataViewFragment();launch(asset("multirate-shared-rtc.bin"),2048,fragment);
        awaitStatus(fragment,"Latest TMP119");
        instrumentation.runOnMainSync(()-> {
            List<XYPlot> plots=widgets(fragment.getView(),XYPlot.class);
            assertEquals(View.VISIBLE,plots.get(0).getVisibility());assertEquals(View.VISIBLE,plots.get(1).getVisibility());
            assertTrue(fragment.status.getText().toString().contains("120 s"));assertTrue(fragment.status.getText().toString().contains("60 s"));
            ((ScrollView)fragment.getView()).scrollTo(0,plots.get(0).getTop());
        });
        screenshot("multirate-shared-temperatures");
    }
    @Test public void multirateStopIsPendingUntilRealFirmwareAcknowledgement() throws Exception {
        byte[] dump=asset("multirate-shared-fsm.bin");dump[78]=2;DensorProtocol.put16(dump,90,DensorProtocol.crc16(dump,76,14));
        DensorSettingsFragment fragment=new DensorSettingsFragment();launch(dump,2048,fragment);
        click(fragment,"Stop and checkpoint recording");awaitStatus(fragment,"Stop requested");
        DensorProtocol.Info info=DensorNfc.info(FixtureActivity.fixture);
        assertEquals(DensorProtocol.RUNNING,info.state);assertEquals(DensorMultirate.STOP,info.pendingCommand);
        assertTrue(info.pendingId>info.acknowledgedId);assertFalse(info.complete());assertEquals(12,FixtureActivity.fixture.writes);
    }

}
