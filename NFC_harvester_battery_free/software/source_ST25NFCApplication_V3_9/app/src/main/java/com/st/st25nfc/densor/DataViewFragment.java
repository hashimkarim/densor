package com.st.st25nfc.densor;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import com.androidplot.xy.LineAndPointFormatter;
import com.androidplot.xy.SimpleXYSeries;
import com.androidplot.xy.XYPlot;
import com.androidplot.xy.XYGraphWidget;
import com.androidplot.xy.BoundaryMode;
import com.androidplot.xy.StepMode;
import com.st.st25nfc.R;
import com.st.st25nfc.densor.data.DensorDataSample;
import com.st.st25nfc.densor.data.DensorDataSet;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.text.DecimalFormat;

/** Separate temperatures and checked exports labelled as live or stable. */
public class DataViewFragment extends DensorFragment {
    private DensorDataSet data;
    private XYPlot oldPlot, tmpPlot, pdPlot, accelPlot, supplyPlot, future1Plot, future2Plot;
    private byte[] exportBytes;
    private Context appContext;
    public static DataViewFragment newInstance(Context context) {
        DataViewFragment fragment = new DataViewFragment(); fragment.setTitle("Densor data"); return fragment;
    }
    @Override public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
        LinearLayout root = column(); appContext = requireContext().getApplicationContext();
        button(root, "Read recording", v -> fillView());
        button(root, "Save binary recording", v -> export(false));
        button(root, "Save CSV", v -> export(true));
        oldPlot = plot(root, "LIS2DW12 temperature (°C)"); tmpPlot = plot(root, "TMP119 temperature (°C)");
        pdPlot = plot(root, "Photodiode (raw ADC)"); accelPlot = plot(root, "Acceleration (g)");
        supplyPlot = plot(root, "Supply (V, coarse telemetry)");
        future1Plot = plot(root, "Legacy future1"); future2Plot = plot(root, "Legacy future2");
        accelPlot.getLegend().setVisible(true); future1Plot.getLegend().setVisible(true);
        ScrollView scroll = new ScrollView(requireContext()); scroll.addView(root); initView(); return scroll;
    }
    private XYPlot plot(LinearLayout root, String title) {
        XYPlot plot = (XYPlot)LayoutInflater.from(requireContext()).inflate(R.layout.densor_plot, root, false);
        plot.setTitle(title); root.addView(plot);
        plot.setDomainStep(StepMode.SUBDIVIDE, 4); plot.setRangeStep(StepMode.SUBDIVIDE, 5);
        plot.getGraph().getLineLabelStyle(XYGraphWidget.Edge.LEFT).setFormat(new DecimalFormat("0.##"));
        plot.getGraph().getLineLabelStyle(XYGraphWidget.Edge.BOTTOM).setFormat(new DecimalFormat("0"));
        plot.setVisibility(View.GONE); return plot;
    }
    @Override public void fillView() {
        if (status == null || mView == null) return;
        perform(tag -> {
            DensorDataSet recording = DensorNfc.recording(tag);
            return () -> { data = recording; display(); };
        });
    }
    private void series(XYPlot plot, Number[] values, String name, int color) {
        double[] range = (double[])plot.getTag();
        if (range == null) range = new double[]{Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (Number value : values) if (value != null) {
            range[0] = Math.min(range[0], value.doubleValue()); range[1] = Math.max(range[1], value.doubleValue());
        }
        if (!Double.isInfinite(range[0]) && !Double.isInfinite(range[1])) {
            double padding = Math.max(0.1, (range[1] - range[0]) * 0.05);
            plot.setRangeBoundaries(range[0] - padding, range[1] + padding, BoundaryMode.FIXED);
        }
        plot.setTag(range);
        ArrayList<Long> x=new ArrayList<>(); ArrayList<Number> y=new ArrayList<>();
        ArrayList<Long> times=data.getTimestamps();
        for(int i=0;i<values.length;i++) if(values[i]!=null) { x.add(times.get(i));y.add(values[i]); }
        plot.addSeries(new SimpleXYSeries(x, y, name), new LineAndPointFormatter(color, color, null, null));
    }
    private static Float last(Float[] values) { for(int i=values.length-1;i>=0;i--) if(values[i]!=null) return values[i]; return null; }
    private static String value(Float value) { return value == null ? "unavailable" : value.toString(); }
    private void display() {
        DensorProtocol.Info info = data.getInfo();
        String kind = info.legacy ? "Legacy snapshot: stop the original device before migration."
                : info.complete() ? "Complete recording through the stopped, acknowledged checkpoint." : "Snapshot / recovered prefix; read again after an acknowledged stop for a complete export.";
        String latest = "";
        if (info.samples() > 0) {
            DensorDataSample sample = data.getSamples().get(info.samples() - 1);
            latest = "\nLatest LIS2DW12: " + value(last(data.getTemp())) + " °C\nLatest TMP119: " + value(last(data.getTmp119())) + " °C\nSupply: " + value(last(data.getVdda()));
        }
        status.setText(info.summary() + "\n" + kind + latest);
        XYPlot[] plots = {oldPlot, tmpPlot, pdPlot, accelPlot, supplyPlot, future1Plot, future2Plot};
        for (XYPlot plot : plots) { plot.clear(); plot.setTag(null); plot.setVisibility(View.GONE); plot.setDomainLabel(info.timeValid ? "Epoch seconds" : "Nominal elapsed seconds"); }
        if (info.samples() == 0) return;
        double first = data.getTimestamps().get(0).doubleValue(), last = data.getTimestamps().get(info.samples() - 1).doubleValue();
        double timePadding = Math.max(0.5, info.period * 0.5);
        for (XYPlot plot : plots) plot.setDomainBoundaries(first - timePadding, last + timePadding, BoundaryMode.FIXED);
        if ((info.mask & DensorProtocol.OLD) != 0) { oldPlot.setVisibility(View.VISIBLE); series(oldPlot, data.getTemp(), "LIS2DW12", Color.CYAN); }
        if ((info.mask & DensorProtocol.TMP119) != 0) { tmpPlot.setVisibility(View.VISIBLE); series(tmpPlot, data.getTmp119(), "TMP119", Color.YELLOW); }
        if ((info.mask & DensorProtocol.PD) != 0) { pdPlot.setVisibility(View.VISIBLE); series(pdPlot, data.getPd(), "Photodiode", Color.GREEN); }
        if ((info.mask & DensorProtocol.ACCEL) != 0) {
            accelPlot.setVisibility(View.VISIBLE); Float[][] axes = data.getAccel();
            series(accelPlot, axes[0], "X", Color.RED); series(accelPlot, axes[1], "Y", Color.GREEN); series(accelPlot, axes[2], "Z", Color.CYAN);
        }
        if ((info.mask & 7) != 0) { supplyPlot.setVisibility(View.VISIBLE); series(supplyPlot, data.getVdda(), "Supply", Color.MAGENTA); }
        if (info.legacy && (info.legacyMask & 4) != 0) {
            future1Plot.setVisibility(View.VISIBLE); Integer[][] readings = data.getFuture1();
            int[] colors = {Color.RED, Color.GREEN, Color.CYAN, Color.YELLOW, Color.MAGENTA};
            for (int i = 0; i < 5; i++) series(future1Plot, readings[i], "future1 " + (i + 1), colors[i]);
        }
        if (info.legacy && (info.legacyMask & 2) != 0) { future2Plot.setVisibility(View.VISIBLE); series(future2Plot, data.getFuture2(), "future2", Color.CYAN); }
        for (XYPlot plot : plots) plot.redraw();
    }
    private void export(boolean csv) {
        if (data == null) { status.setText("Read a recording first"); return; }
        try {
            exportBytes = csv ? data.toCsv().getBytes(StandardCharsets.UTF_8) : data.getBinary();
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(csv ? "text/csv" : "application/octet-stream");
            intent.putExtra(Intent.EXTRA_TITLE, "densor-" + (data.getInfo().legacy ? "legacy-snapshot" : (data.getInfo().multirate?"multirate-session-":"r1-session-") + data.getInfo().sessionId
                    + (data.getInfo().complete() ? "-stable" : "-live-snapshot")) + (csv ? ".csv" : ".bin"));
            startActivityForResult(intent, 710);
        } catch (Exception e) { status.setText("Export failed: " + e.getMessage()); }
    }
    @Override public void onActivityResult(int requestCode, int resultCode, Intent result) {
        super.onActivityResult(requestCode, resultCode, result);
        if (requestCode != 710) return;
        try {
            if (resultCode != Activity.RESULT_OK || result == null || result.getData() == null || exportBytes == null) return;
            try (OutputStream out = appContext.getContentResolver().openOutputStream(result.getData(), "w")) {
                if (out == null) throw new IllegalStateException("No writable export destination");
                out.write(exportBytes); out.flush();
            }
            if (isAdded() && status != null) status.setText("Export saved. A live snapshot excludes samples logged after the read.");
        } catch (Exception e) { if (isAdded() && status != null) status.setText("Export failed: " + e.getMessage()); }
        finally { exportBytes = null; }
    }
}
