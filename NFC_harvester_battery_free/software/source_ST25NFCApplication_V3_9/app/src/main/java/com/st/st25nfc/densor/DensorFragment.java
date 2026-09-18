package com.st.st25nfc.densor;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.st.st25nfc.generic.STFragment;
import com.st.st25sdk.NFCTag;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Serializes Densor NFC operations across all tabs without blocking the UI. */
public abstract class DensorFragment extends STFragment {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    protected TextView status;
    private boolean busy;
    private int viewGeneration;
    protected interface Operation { Runnable run(NFCTag tag) throws Exception; }
    protected LinearLayout column() {
        viewGeneration++;
        LinearLayout root = new LinearLayout(requireContext()); root.setOrientation(LinearLayout.VERTICAL);
        int padding = (int)(16 * getResources().getDisplayMetrics().density); root.setPadding(padding, padding, padding, padding);
        mView = root; status = new TextView(requireContext()); status.setText("Tap Refresh to read the connected Densor."); root.addView(status);
        return root;
    }
    protected Button button(LinearLayout root, String label, View.OnClickListener action) {
        Button button = new Button(requireContext()); button.setText(label); button.setOnClickListener(action); root.addView(button); return button;
    }
    protected void perform(Operation operation) {
        if (busy || !isAdded()) return;
        final NFCTag tag = ((STFragment.STFragmentListener)requireActivity()).getTag();
        if (tag == null) { status.setText("No NFC tag connected"); return; }
        busy = true; int generation = viewGeneration; status.setText("Reading device…");
        IO.execute(() -> {
            Runnable result;
            try { result = operation.run(tag); }
            catch (Exception e) { String message = e.getMessage(); result = () -> status.setText("Error: " + (message == null ? e.getClass().getSimpleName() : message)); }
            final Runnable completed = result;
            ui.post(() -> { busy = false; if (isAdded() && generation == viewGeneration && mView != null) completed.run(); });
        });
    }
    public final boolean isBusy() { return busy; }
    @Override public void onDestroyView() { viewGeneration++; mView = null; super.onDestroyView(); }
}
