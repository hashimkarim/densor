package com.st.st25nfc.densor;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
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
        awaitStatus(fragment, "R1");
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
}
