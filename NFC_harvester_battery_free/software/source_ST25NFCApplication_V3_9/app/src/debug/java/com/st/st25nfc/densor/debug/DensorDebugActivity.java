package com.st.st25nfc.densor.debug;

import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.st.st25nfc.R;
import com.st.st25nfc.densor.DataViewFragment;
import com.st.st25nfc.densor.DensorFragment;
import com.st.st25nfc.densor.DensorSettingsFragment;
import com.st.st25nfc.densor.TimeSyncFragment;
import com.st.st25nfc.generic.STFragment;
import com.st.st25sdk.NFCTag;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Real app fragments backed by virtual EEPROM; never opens an NFC adapter. */
public final class DensorDebugActivity extends AppCompatActivity implements STFragment.STFragmentListener {
    private static final String[] EXAMPLES = {"r1-mask-3.bin", "historical-reconstructed-r1.bin",
            "historical-reconstructed-legacy.bin"};
    private DebugDensorTag tag;
    private final ExecutorService files = Executors.newSingleThreadExecutor();
    private TextView sourceView, fileStatus;
    private String sourceName, screen = "settings";
    private boolean fileBusy, refreshNeeded;
    private final ActivityResultLauncher<String[]> openDump = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> { if (uri != null) importDump(uri); });
    private final ActivityResultLauncher<String> exportDump = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/octet-stream"), uri -> { if (uri != null) exportDump(uri); });

    @Override protected void onCreate(Bundle state) {
        // Fragments request the tag during restoration in super.onCreate.
        tag = new DebugDensorTag(state == null ? example(0) : state.getByteArray("memory"));
        super.onCreate(state);
        setContentView(R.layout.activity_densor_debug);
        setSupportActionBar(findViewById(R.id.debug_toolbar));
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        sourceView = findViewById(R.id.debug_source); fileStatus = findViewById(R.id.debug_file_status);
        sourceName = state == null ? getString(R.string.densor_debug_new_tag) : state.getString("source");
        screen = state == null ? "settings" : state.getString("screen", "settings");
        updateSource();
        findViewById(R.id.debug_open_dump).setOnClickListener(v -> {
            if (canChangeMemory()) new AlertDialog.Builder(this).setMessage(R.string.densor_debug_replace)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.densor_debug_open, (dialog, which) -> openDump.launch(new String[]{"*/*"})).show();
        });
        findViewById(R.id.debug_export_dump).setOnClickListener(v -> {
            if (canChangeMemory()) exportDump.launch("densor-debug.bin");
        });
        findViewById(R.id.debug_new_tag).setOnClickListener(v -> {
            if (canChangeMemory()) new AlertDialog.Builder(this).setTitle(R.string.densor_debug_replace)
                    .setItems(R.array.densor_debug_examples, (dialog, which) -> {
                        if (!canChangeMemory()) return;
                        tag.load(example(which));
                        sourceName = getResources().getStringArray(R.array.densor_debug_examples)[which];
                        updateSource(); fileStatus.setText(R.string.densor_debug_unsaved); showScreen(screen);
                    }).setNegativeButton(android.R.string.cancel, null).show();
        });
        findViewById(R.id.debug_show_settings).setOnClickListener(v -> switchScreen("settings"));
        findViewById(R.id.debug_show_data).setOnClickListener(v -> switchScreen("data"));
        findViewById(R.id.debug_show_reset).setOnClickListener(v -> switchScreen("reset"));
        if (state == null) showScreen(screen);
    }

    private byte[] example(int index) {
        try (InputStream input = getAssets().open("densor-debug/" + EXAMPLES[index])) {
            return DebugDensorTag.readDump(input);
        } catch (IOException e) { throw new IllegalStateException("Missing packaged debug fixture", e); }
    }
    @Override public NFCTag getTag() { return tag; }
    private DensorFragment fragment() {
        return (DensorFragment)getSupportFragmentManager().findFragmentById(R.id.debug_settings);
    }
    private boolean canChangeMemory() {
        DensorFragment fragment = fragment();
        if (fileBusy || (fragment != null && fragment.isBusy())) {
            fileStatus.setText(R.string.densor_debug_wait); return false;
        }
        return true;
    }
    private void switchScreen(String value) { if (canChangeMemory()) showScreen(value); }
    private void showScreen(String value) {
        screen = value;
        DensorFragment next = "data".equals(value) ? new DataViewFragment()
                : "reset".equals(value) ? new TimeSyncFragment() : new DensorSettingsFragment();
        getSupportFragmentManager().beginTransaction().replace(R.id.debug_settings, next).commitNow();
    }
    private void importDump(Uri uri) {
        setFileBusy(true);
        files.execute(() -> {
            try {
                byte[] dump;
                try (InputStream input = getContentResolver().openInputStream(uri)) { dump = DebugDensorTag.readDump(input); }
                String name = fileName(uri);
                tag.load(dump); // Validates first, then replaces atomically with respect to DensorNfc operations.
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    sourceName = name; updateSource(); setFileBusy(false);
                    fileStatus.setText(getString(R.string.densor_debug_imported, dump.length));
                    if (getSupportFragmentManager().isStateSaved()) refreshNeeded = true;
                    else showScreen(screen); // Discard values and exports from the previous image.
                });
            } catch (IOException | SecurityException | IllegalArgumentException e) { reportError(e); }
        });
    }
    private void exportDump(Uri uri) {
        setFileBusy(true);
        files.execute(() -> {
            try {
                byte[] snapshot = tag.snapshot();
                try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                    if (output == null) throw new IOException("Cannot open the destination file.");
                    output.write(snapshot);
                }
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    setFileBusy(false); fileStatus.setText(getString(R.string.densor_debug_exported, snapshot.length));
                });
            } catch (IOException | SecurityException e) { reportError(e); }
        });
    }
    private String fileName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        }
        return getString(R.string.densor_debug_imported_tag);
    }
    private void reportError(Exception error) {
        runOnUiThread(() -> {
            if (isDestroyed() || isFinishing()) return;
            setFileBusy(false); fileStatus.setText(getString(R.string.densor_debug_file_error, error.getMessage()));
        });
    }
    private void updateSource() { sourceView.setText(getString(R.string.densor_debug_source, sourceName)); }
    private void setFileBusy(boolean busy) {
        fileBusy = busy;
        for (int id : new int[]{R.id.debug_open_dump, R.id.debug_export_dump, R.id.debug_new_tag,
                R.id.debug_show_settings, R.id.debug_show_data, R.id.debug_show_reset, R.id.debug_settings})
            enable(findViewById(id), !busy);
        if (busy) fileStatus.setText(R.string.densor_debug_working);
    }
    private void enable(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); ++i) enable(group.getChildAt(i), enabled);
        }
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putByteArray("memory", tag.snapshot()); state.putString("source", sourceName); state.putString("screen", screen);
        super.onSaveInstanceState(state);
    }
    @Override protected void onPostResume() {
        super.onPostResume();
        if (refreshNeeded) { refreshNeeded = false; showScreen(screen); }
    }
    @Override public boolean onSupportNavigateUp() { finish(); return true; }
    @Override protected void onDestroy() { files.shutdownNow(); super.onDestroy(); }
}
