package com.st.st25nfc.densor;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.CheckBox;
import android.widget.Button;
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
    private final EditText[] streamPeriods=new EditText[4];
    private LinearLayout rateFields;
    private TextView intervalLabel;
    private TextView capacityPreview;
    private DensorProtocol.Info installed;
    private Button stop;
    public static DensorSettingsFragment newInstance(Context context) {
        DensorSettingsFragment fragment = new DensorSettingsFragment(); fragment.setTitle("Densor settings"); return fragment;
    }
    @Override public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
        LinearLayout root = column();
        TextView explanation = new TextView(requireContext());
        explanation.setText("Supports R1, R2 (shared pool) and R3 (partitions). Suffix a uses the FSM; suffix b uses the RTC timer. Settings follow the installed firmware. R1 uses one common period. R2 and R3 support individual periods; partition sizes are calculated automatically. Save existing data first. Multi-rate recordings must be stopped and acknowledged before applying new settings. Recording starts after the optional one-time startup delay."); root.addView(explanation);
        temperature = new Spinner(requireContext());
        temperature.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Old only (LIS2DW12)", "TMP119 only", "Both temperatures", "Temperature disabled"})); root.addView(temperature);
        photodiode = check(root, "Photodiode"); acceleration = check(root, "Acceleration"); rc = check(root, "Internal RC clock");
        intervalLabel = new TextView(requireContext()); intervalLabel.setText("Common sample period in seconds (1–59, or whole minutes up to 3540)"); root.addView(intervalLabel);
        period = new EditText(requireContext()); period.setInputType(InputType.TYPE_CLASS_NUMBER); period.setText("120"); root.addView(period);
        TextView delayLabel = new TextView(requireContext()); delayLabel.setText("One-time startup delay in minutes (0–59; 0 starts immediately)"); root.addView(delayLabel);
        startupDelay = new EditText(requireContext()); startupDelay.setInputType(InputType.TYPE_CLASS_NUMBER);
        startupDelay.setContentDescription("Startup delay in minutes"); startupDelay.setText("0"); root.addView(startupDelay);
        rateFields=new LinearLayout(requireContext()); rateFields.setOrientation(LinearLayout.VERTICAL); root.addView(rateFields);
        String[] labels={"LIS2DW12 period (seconds)","TMP119 period (seconds)","Photodiode period (seconds)","Acceleration period (seconds)"};
        int[] defaults={120,120,30,10};
        for(int i=0;i<4;i++) {
            TextView label=new TextView(requireContext());label.setText(labels[i]);rateFields.addView(label);
            streamPeriods[i]=new EditText(requireContext());streamPeriods[i].setInputType(InputType.TYPE_CLASS_NUMBER);
            streamPeriods[i].setContentDescription(labels[i]);streamPeriods[i].setText(Integer.toString(defaults[i]));rateFields.addView(streamPeriods[i]);
        }
        capacityPreview=new TextView(requireContext());
        capacityPreview.setContentDescription("Proposed recording capacity");rateFields.addView(capacityPreview);
        TextWatcher ratesChanged=new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count) { updateCapacityPreview(); }
            @Override public void afterTextChanged(Editable s) {}
        };
        period.addTextChangedListener(ratesChanged);
        for(EditText input:streamPeriods) input.addTextChangedListener(ratesChanged);
        temperature.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent,View view,int position,long id) { updateCapacityPreview(); }
            @Override public void onNothingSelected(AdapterView<?> parent) { updateCapacityPreview(); }
        });
        photodiode.setOnCheckedChangeListener((button,checked)->updateCapacityPreview());
        acceleration.setOnCheckedChangeListener((button,checked)->updateCapacityPreview());
        rateFields.setVisibility(View.GONE);
        stop=button(root,"Stop and checkpoint recording",v -> stop());stop.setEnabled(false);
        button(root, "Refresh active / pending settings", v -> fillView());
        button(root, "Apply settings and reset log", v -> configure());
        ScrollView scroll = new ScrollView(requireContext()); scroll.addView(root); initView(); return scroll;
    }
    private CheckBox check(LinearLayout root, String label) { CheckBox box = new CheckBox(requireContext()); box.setText(label); root.addView(box); return box; }
    @Override public void fillView() {
        if (status == null || mView == null) return;
        perform(tag -> { DensorProtocol.Info info = DensorNfc.info(tag); return () -> {
            installed=info;
            status.setText(info.summary() + (info.legacy ? "\nLegacy settings are read-only. Export before upgrading firmware." : ""));
            rateFields.setVisibility(info.multirate?View.VISIBLE:View.GONE);stop.setEnabled(info.multirate);
            intervalLabel.setText(info.multirate?"Base period in seconds (1–3540); each enabled period must be a whole multiple":"Common sample period in seconds (1–59, or whole minutes up to 3540)");
            if(info.multirate) {
                period.setText(Integer.toString(info.period>0?info.period:10));
                for(int i=0;i<4;i++) if(info.multipliers[i]>0) streamPeriods[i].setText(Long.toString((long)info.period*info.multipliers[i]));
            }
            updateCapacityPreview();
        }; });
    }
    private int selectedMask() {
        int[] selections={DensorProtocol.OLD,DensorProtocol.TMP119,DensorProtocol.OLD|DensorProtocol.TMP119,0};
        return selections[temperature.getSelectedItemPosition()] | (photodiode.isChecked()?DensorProtocol.PD:0)
                | (acceleration.isChecked()?DensorProtocol.ACCEL:0);
    }
    private String[] requestedPeriods() {
        String[] values=new String[4];
        for(int i=0;i<4;i++) values[i]=streamPeriods[i].getText().toString();
        return values;
    }
    private static int[] multipliers(int mask,int base,String[] periods) {
        if(base<=0) throw new IllegalArgumentException("Enter a positive base period");
        int[] m=new int[4];
        for(int i=0;i<4;i++) if((mask&(1<<i))!=0) {
            final int seconds;
            try { seconds=Integer.parseInt(periods[i]); }
            catch(NumberFormatException e) { throw new IllegalArgumentException("Enter a whole-number period for each enabled sensor"); }
            if(seconds<=0 || seconds%base!=0) throw new IllegalArgumentException("Each enabled period must be a positive multiple of the base period");
            m[i]=seconds/base;
        }
        DensorMultirate.validate(mask,base,m);
        return m;
    }
    private void updateCapacityPreview() {
        if(installed==null || !installed.multirate) return;
        int mask=selectedMask();
        for(int i=0;i<4;i++) streamPeriods[i].setEnabled((mask&(1<<i))!=0);
        try {
            int base=Integer.parseInt(period.getText().toString());
            int[] m=multipliers(mask,base,requestedPeriods());
            capacityPreview.setText(DensorMultirate.capacityPreview(installed.capacity,installed.storage,installed.timing,mask,base,m));
        } catch(NumberFormatException e) {
            capacityPreview.setText("Proposed capacity: enter a whole-number base period");
        } catch(IllegalArgumentException e) {
            capacityPreview.setText("Proposed capacity: "+e.getMessage());
        }
    }
    private void stop() {
        perform(tag -> {
            DensorProtocol.Info info=DensorNfc.info(tag);
            DensorNfc.sendMultirate(tag,info,DensorMultirate.STOP,info.mask,info.period,info.multipliers,info.oscillator,info.delay/60);
            return () -> status.setText("Stop requested. Await acknowledgement, then export before changing settings. " + info.wakeNotice());
        });
    }
    private void configure() {
        final int seconds, delayMinutes;
        final String[] requestedPeriods=requestedPeriods();
        try { seconds = Integer.parseInt(period.getText().toString()); delayMinutes = Integer.parseInt(startupDelay.getText().toString()); }
        catch (NumberFormatException e) { status.setText("Enter whole numbers for the period and startup delay"); return; }
        final int mask=selectedMask();
        final int oscillator = rc.isChecked() ? 1 : 0;
        perform(tag -> {
            DensorProtocol.Info info = DensorNfc.info(tag);
            if(info.multirate) {
                int[] m=multipliers(mask,seconds,requestedPeriods);
                DensorNfc.sendMultirate(tag,info,DensorProtocol.APPLY_SETTINGS,mask,seconds,m,oscillator,delayMinutes);
            } else DensorNfc.send(tag, info, DensorProtocol.APPLY_SETTINGS, mask, seconds, oscillator, delayMinutes);
            return () -> status.setText("Settings/reset requested. Active settings have not changed yet. "
                    + info.wakeNotice() + " Refresh to see acknowledgement or an error.");
        });
    }
}
