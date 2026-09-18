package com.st.st25nfc.densor.debug;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.st.st25nfc.R;
import com.st.st25nfc.densor.DensorFragment;
import com.st.st25nfc.densor.DensorNfc;
import com.st.st25nfc.densor.DensorProtocol;
import com.st.st25nfc.densor.data.DensorDataSet;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = 35)
public class DensorDebugActivityTest {
    @Test public void settingsAndDelayStayPendingAcrossRecreation() throws Exception {
        try (ActivityController<DensorDebugActivity> controller = launch()) {
            DensorDebugActivity activity = controller.get();
            byte[] before = tag(activity).snapshot();
            widgets(fragment(activity).getView(), EditText.class).get(0).setText("600");
            widgets(fragment(activity).getView(), EditText.class).get(1).setText("15");
            click(activity, "Apply settings and reset log"); await(() -> status(activity).contains("have not changed yet"));
            DensorProtocol.Info info = DensorNfc.info(tag(activity));
            assertEquals(120, info.period); assertEquals(0, info.delay);
            assertEquals(600, info.pendingPeriod); assertEquals(900, info.pendingDelay);
            byte[] after = tag(activity).snapshot();
            assertArrayEquals(Arrays.copyOf(before, 92), Arrays.copyOf(after, 92));
            assertArrayEquals(Arrays.copyOfRange(before, 128, 512), Arrays.copyOfRange(after, 128, 512));
            controller.recreate();
            await(() -> status(controller.get()).contains("Pending request 2"));
            assertArrayEquals(after, tag(controller.get()).snapshot());
        }
    }
    @Test public void invalidDelayAndPeriodNeverWrite() throws Exception {
        try (ActivityController<DensorDebugActivity> controller = launch()) {
            DensorDebugActivity activity = controller.get(); byte[] before = tag(activity).snapshot();
            EditText delay = widgets(fragment(activity).getView(), EditText.class).get(1);
            for (String invalid : new String[]{"60", "-1", ""}) {
                delay.setText(invalid); click(activity, "Apply settings and reset log");
                await(() -> !fragment(activity).isBusy());
                assertArrayEquals(before, tag(activity).snapshot());
                assertTrue(status(activity).contains("0–59") || status(activity).contains("whole numbers"));
            }
            delay.setText("0"); widgets(fragment(activity).getView(), EditText.class).get(0).setText("61");
            click(activity, "Apply settings and reset log"); await(() -> status(activity).contains("whole minutes"));
            assertArrayEquals(before, tag(activity).snapshot());
        }
    }
    @Test public void pickerImportsAndExportsAndPlotsHistoricalRecording() throws Exception {
        try (ActivityController<DensorDebugActivity> controller = launch()) {
            DensorDebugActivity activity = controller.get();
            byte[] original = asset(activity, "historical-reconstructed-r1.bin");
            Uri input = Uri.parse("content://densor-test/input.bin");
            Shadows.shadowOf(activity.getContentResolver()).registerInputStream(input, new ByteArrayInputStream(original));
            activity.findViewById(R.id.debug_open_dump).performClick();
            ((AlertDialog)ShadowDialog.getLatestDialog()).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            ShadowLooper.idleMainLooper();
            ShadowActivity.IntentForResult open = Shadows.shadowOf(activity).getNextStartedActivityForResult();
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, open.intent.getAction());
            activity.getActivityResultRegistry().dispatchResult(open.requestCode, Activity.RESULT_OK, new Intent().setData(input));
            await(() -> fileStatus(activity).contains("Loaded") && !fragment(activity).isBusy());
            assertEquals(8192, tag(activity).getMemSizeInBytes());
            activity.findViewById(R.id.debug_show_data).performClick();
            await(() -> status(activity).contains("210 samples") && status(activity).contains("Latest TMP119: unavailable"));
            assertArrayEquals(original, Arrays.copyOf(tag(activity).snapshot(), original.length));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Uri target = Uri.parse("content://densor-test/output.bin");
            Shadows.shadowOf(activity.getContentResolver()).registerOutputStream(target, output);
            activity.findViewById(R.id.debug_export_dump).performClick();
            ShadowActivity.IntentForResult save = Shadows.shadowOf(activity).getNextStartedActivityForResult();
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, save.intent.getAction());
            activity.getActivityResultRegistry().dispatchResult(save.requestCode, Activity.RESULT_OK, new Intent().setData(target));
            await(() -> fileStatus(activity).contains("Exported 8192"));
            assertArrayEquals(tag(activity).snapshot(), output.toByteArray());
        }
    }
    @Test public void legacyDumpRemainsReadOnly() throws Exception {
        try (ActivityController<DensorDebugActivity> controller = launch()) {
            DensorDebugActivity activity = controller.get();
            tag(activity).load(asset(activity, "historical-reconstructed-legacy.bin"));
            click(activity, "Apply settings and reset log"); await(() -> status(activity).contains("Legacy firmware is read-only"));
            byte[] before = tag(activity).snapshot();
            activity.findViewById(R.id.debug_show_data).performClick(); await(() -> status(activity).contains("Legacy recording"));
            assertArrayEquals(before, tag(activity).snapshot());
            DensorProtocol.Info info = DensorNfc.info(tag(activity)); assertEquals(210, info.samples());
            assertNull(DensorNfc.recording(tag(activity)).getTmp119()[0]);
        }
    }
    @Test public void densityAndCommittedPrefixAreValidatedBeforeReplacement() throws Exception {
        try (ActivityController<DensorDebugActivity> controller = launch()) {
            DensorDebugActivity activity = controller.get(); byte[] valid = tag(activity).snapshot();
            assertEquals(512, valid.length);
            for (byte[] invalid : new byte[][]{Arrays.copyOf(valid, 130), Arrays.copyOf(valid, 513), new byte[9]}) {
                assertThrows(IllegalArgumentException.class, () -> tag(activity).load(invalid));
                assertArrayEquals(valid, tag(activity).snapshot());
            }
            byte[] broken = valid.clone(); broken[74] ^= 1;
            assertThrows(IllegalArgumentException.class, () -> tag(activity).load(broken));
            assertArrayEquals(valid, tag(activity).snapshot());
            assertThrows(java.io.IOException.class, () -> DebugDensorTag.readDump(new ByteArrayInputStream(new byte[8193])));
            assertThrows(com.st.st25sdk.STException.class, () -> tag(activity).readBytes(511, 2));
            assertThrows(com.st.st25sdk.STException.class, () -> tag(activity).writeBytes(512, new byte[1]));
        }
    }
    @Test public void resetReusesConfiguredStartupDelay() throws Exception {
        try (ActivityController<DensorDebugActivity> controller = launch()) {
            DensorDebugActivity activity = controller.get(); byte[] delayed = tag(activity).snapshot();
            delayed[DensorProtocol.HEADER + 18] = 59;
            DensorProtocol.put16(delayed, 74, DensorProtocol.crc16(delayed, 12, 62));
            tag(activity).load(delayed);
            activity.findViewById(R.id.debug_show_reset).performClick(); await(() -> status(activity).contains("startup delay 59 min"));
            click(activity, "Reset log using current settings"); await(() -> status(activity).contains("Reset requested"));
            DensorProtocol.Info info = DensorNfc.info(tag(activity));
            assertEquals(3540, info.pendingDelay); assertEquals(120, info.pendingPeriod); assertEquals(3, info.pendingMask);
        }
    }
    private ActivityController<DensorDebugActivity> launch() throws Exception {
        ActivityController<DensorDebugActivity> controller = Robolectric.buildActivity(DensorDebugActivity.class).setup();
        await(() -> status(controller.get()).contains("R1") && !fragment(controller.get()).isBusy()); return controller;
    }
    private static DebugDensorTag tag(DensorDebugActivity a) { return (DebugDensorTag)a.getTag(); }
    private static DensorFragment fragment(DensorDebugActivity a) { return (DensorFragment)a.getSupportFragmentManager().findFragmentById(R.id.debug_settings); }
    private static String status(DensorDebugActivity a) { return widgets(fragment(a).getView(), TextView.class).get(0).getText().toString(); }
    private static String fileStatus(DensorDebugActivity a) { return ((TextView)a.findViewById(R.id.debug_file_status)).getText().toString(); }
    private static byte[] asset(DensorDebugActivity a, String name) throws Exception {
        try (InputStream input = a.getAssets().open("densor-debug/" + name)) { return DebugDensorTag.readDump(input); }
    }
    private static <T> List<T> widgets(View view, Class<T> type) {
        List<T> result = new ArrayList<>(); if (type.isInstance(view)) result.add(type.cast(view));
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); ++i)
            result.addAll(widgets(((ViewGroup)view).getChildAt(i), type));
        return result;
    }
    private static void click(DensorDebugActivity a, String label) {
        for (Button button : widgets(fragment(a).getView(), Button.class))
            if (button.getText().toString().equals(label)) { button.performClick(); return; }
        fail("Missing button " + label);
    }
    private static void await(BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + 10_000_000_000L;
        do { ShadowLooper.idleMainLooper(); if (condition.getAsBoolean()) return; Thread.sleep(10); } while (System.nanoTime() < end);
        fail("Timed out waiting for UI");
    }
}
