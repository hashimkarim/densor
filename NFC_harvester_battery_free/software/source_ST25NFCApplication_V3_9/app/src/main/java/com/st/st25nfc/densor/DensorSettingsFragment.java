package com.st.st25nfc.densor;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

/** Pending configuration is never displayed as active before MCU acknowledgement. */
public class DensorSettingsFragment extends DensorFragment {
    private Spinner temperature;
    private CheckBox photodiode, acceleration, rc;
    private EditText period, startupDelay;
    public static DensorSettingsFragment newInstance(Context context) {
        DensorSettingsFragment fragment = new DensorSettingsFragment(); fragment.setTitle("Densor settings"); return fragment;
    }
    @Override public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
        LinearLayout root = column();
        TextView explanation = new TextView(requireContext());
        explanation.setText("Choose sensors and one common interval. Applying settings resets the log on the next regular wake. Recording starts after the optional one-time startup delay. Save existing data first; logging can continue until the reset is accepted."); root.addView(explanation);
        temperature = new Spinner(requireContext());
        temperature.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Old only (LIS2DW12)", "TMP119 only", "Both temperatures", "Temperature disabled"})); root.addView(temperature);
        photodiode = check(root, "Photodiode"); acceleration = check(root, "Acceleration"); rc = check(root, "Internal RC clock");
        TextView intervalLabel = new TextView(requireContext()); intervalLabel.setText("Common sample period in seconds (1–59, or whole minutes up to 3540)"); root.addView(intervalLabel);
        period = new EditText(requireContext()); period.setInputType(InputType.TYPE_CLASS_NUMBER); period.setText("120"); root.addView(period);
        TextView delayLabel = new TextView(requireContext()); delayLabel.setText("One-time startup delay in minutes (0–59; 0 starts immediately)"); root.addView(delayLabel);
        startupDelay = new EditText(requireContext()); startupDelay.setInputType(InputType.TYPE_CLASS_NUMBER);
        startupDelay.setContentDescription("Startup delay in minutes"); startupDelay.setText("0"); root.addView(startupDelay);
        button(root, "Refresh active / pending settings", v -> fillView());
        button(root, "Apply settings and reset log", v -> configure());
        ScrollView scroll = new ScrollView(requireContext()); scroll.addView(root); initView(); return scroll;
    }
    private CheckBox check(LinearLayout root, String label) { CheckBox box = new CheckBox(requireContext()); box.setText(label); root.addView(box); return box; }
    @Override public void fillView() {
        if (status == null || mView == null) return;
        perform(tag -> { DensorProtocol.Info info = DensorNfc.info(tag); return () -> status.setText(info.summary()
                + (info.legacy ? "\nExport the original recording, flash R1, then provision through SWD. Legacy settings are read-only here." : "")); });
    }
    private void configure() {
        final int seconds, delayMinutes;
        try { seconds = Integer.parseInt(period.getText().toString()); delayMinutes = Integer.parseInt(startupDelay.getText().toString()); }
        catch (NumberFormatException e) { status.setText("Enter whole numbers for the period and startup delay"); return; }
        int[] selections = {DensorProtocol.OLD, DensorProtocol.TMP119, DensorProtocol.OLD | DensorProtocol.TMP119, 0};
        final int mask = selections[temperature.getSelectedItemPosition()] | (photodiode.isChecked() ? DensorProtocol.PD : 0) | (acceleration.isChecked() ? DensorProtocol.ACCEL : 0);
        final int oscillator = rc.isChecked() ? 1 : 0;
        perform(tag -> {
            DensorProtocol.Info info = DensorNfc.info(tag);
            DensorNfc.send(tag, info, DensorProtocol.APPLY_SETTINGS, mask, seconds, oscillator, delayMinutes);
            return () -> status.setText("Settings/reset requested. Active settings have not changed yet. "
                    + info.wakeNotice() + " Refresh to see acknowledgement or an error.");
        });
    }
}
