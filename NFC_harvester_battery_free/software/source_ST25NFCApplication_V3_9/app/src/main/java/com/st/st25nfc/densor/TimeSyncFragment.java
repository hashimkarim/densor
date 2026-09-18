package com.st.st25nfc.densor;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Original reset-and-log workflow; class name retains existing tab wiring. */
public class TimeSyncFragment extends DensorFragment {
    public static TimeSyncFragment newInstance(Context context) {
        TimeSyncFragment fragment = new TimeSyncFragment(); fragment.setTitle("Reset recording"); return fragment;
    }
    @Override public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
        LinearLayout root = column();
        TextView help = new TextView(requireContext());
        help.setText("Save existing data in Data before resetting. Reset keeps the current sensors, interval and startup delay. Firmware clears the log on the next regular wake, then starts after that one-time delay.\n\nR1 timestamps are nominal elapsed seconds. Live exports are snapshots; logging may continue until reset is accepted."); root.addView(help);
        button(root, "Refresh session", v -> fillView());
        button(root, "Reset log using current settings", v -> reset());
        ScrollView scroll = new ScrollView(requireContext()); scroll.addView(root); initView(); return scroll;
    }
    @Override public void fillView() {
        if (status == null || mView == null) return;
        perform(tag -> { DensorProtocol.Info info = DensorNfc.info(tag); return () -> status.setText(info.summary()); });
    }
    private void reset() {
        perform(tag -> {
            DensorProtocol.Info info = DensorNfc.info(tag);
            if (info.mask == 0) throw new IllegalStateException("Choose sensor settings first");
            DensorNfc.send(tag, info, DensorProtocol.APPLY_SETTINGS, info.mask, info.period, info.oscillator, info.delay / 60);
            return () -> status.setText("Reset requested. " + info.wakeNotice() + " Recording restarts after acknowledgement and the configured startup delay.");
        });
    }
}
