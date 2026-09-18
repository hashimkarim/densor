package com.st.st25nfc.densor;

import android.os.Bundle;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.st.st25nfc.generic.STFragment;
import com.st.st25nfc.generic.STFragmentActivity;
import com.st.st25sdk.NFCTag;

/** Hosts the real release fragments. Included only in the phoneTest build. */
public final class FixtureActivity extends STFragmentActivity implements STFragment.STFragmentListener {
    static FixtureTag fixture;
    static final int CONTAINER = 0x0123abcd;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this); title.setText("TEST FIXTURE — no NFC hardware"); root.addView(title);
        FrameLayout frame = new FrameLayout(this); frame.setId(CONTAINER);
        root.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(root);
    }
    @Override public NFCTag getTag() { return fixture; }
    void show(DensorFragment fragment) {
        getSupportFragmentManager().beginTransaction().replace(CONTAINER, fragment).commitNow();
    }
}
