/*
  * @author STMicroelectronics MMY Application team
  *
  ******************************************************************************
  * @attention
  *
  * <h2><center>&copy; COPYRIGHT 2017 STMicroelectronics</center></h2>
  *
  * Licensed under ST MIX_MYLIBERTY SOFTWARE LICENSE AGREEMENT (the "License");
  * You may not use this file except in compliance with the License.
  * You may obtain a copy of the License at:
  *
  *        http://www.st.com/Mix_MyLiberty
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied,
  * AND SPECIFICALLY DISCLAIMING THE IMPLIED WARRANTIES OF MERCHANTABILITY,
  * FITNESS FOR A PARTICULAR PURPOSE, AND NON-INFRINGEMENT.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  *
  ******************************************************************************
*/

package com.st.st25nfc.generic;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.res.Resources;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;
import androidx.annotation.Nullable;

import androidx.appcompat.app.AppCompatActivity;
import android.util.Log;
import android.widget.Toast;

import com.st.st25sdk.Helper;
import com.st.st25sdk.NFCTag;
import com.st.st25sdk.STException;
import com.st.st25android.AndroidReaderInterface;


// NB: AppCompatActivity extends FragmentActivity
public class STFragmentActivity extends AppCompatActivity {

    public interface TagTapedListener {
        void tagTaped(boolean tagChanged);
    }

    static final String TAG = "STFragmentActivity";
    public NfcAdapter mNfcAdapter;
    public PendingIntent mPendingIntent;
    public ST25Menu mMenu;
    private NFCTag myTag;
    protected boolean mIsActive;
    private TagTapedListener mTagTapedListener;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setNfcAdapter();
        myTag = MainActivity.getTag();
        mMenu = ST25Menu.newInstance(myTag);

    }

    public void setNfcAdapter() {
        //Log.v(TAG, "setNfcAdapter");

        mNfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (mNfcAdapter == null) {
            Log.e(TAG, "Invalid NfcAdapter!");
            finish();
            return;
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            mPendingIntent = PendingIntent.getActivity(this, 0, new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_MUTABLE);
        } else {
            // Pre-Android 12 branch: no mutability flag exists/needs setting here.
            //noinspection UnspecifiedImmutableFlag
            mPendingIntent = PendingIntent.getActivity(this, 0, new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), 0);
        }
    }

    public NFCTag getTag() {
        return myTag;
    }

    public static boolean tagChanged(Activity activity, NFCTag currentTag) {

        Intent intent = activity.getIntent();

        Tag androidTag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);

        if (androidTag != null) {
            AndroidReaderInterface readerInterface = AndroidReaderInterface.newInstance(androidTag);
            try {
                boolean tagChanged = false;
                byte[] uid = androidTag.getId();
                if (readerInterface != null)  {
                    NFCTag.NfcTagTypes type = readerInterface.decodeTagType(uid);
                    if (type == NFCTag.NfcTagTypes.NFC_TAG_TYPE_V)
                        uid = Helper.reverseByteArray(uid);
                }

                if(currentTag == null) {
                    // androidTag != null and currentTag == null
                    tagChanged = true;
                } else {
                    if (uid.length != currentTag.getUid().length) return true;

                    int i = 0;
                    while (!tagChanged && i < uid.length) {
                        tagChanged = (uid[i] == currentTag.getUid()[i]) ? false : true;
                        i++;
                    }
                }

                return tagChanged;

            } catch (STException e) {
                e.printStackTrace();
            }
        }

        return false;
    }

    public void onPause() {
        super.onPause();

        mIsActive = false;

        if (mNfcAdapter != null) {
            try {
                mNfcAdapter.disableForegroundDispatch(this);
                Log.v(TAG, "disableForegroundDispatch");
            } catch (IllegalStateException e) {
                Log.w(TAG, "Illegal State Exception disabling NFC. Assuming application is terminating.");
            }
            catch (UnsupportedOperationException e) {
                Log.w(TAG, "FEATURE_NFC is unavailable.");
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        NFCTag currentTag = (NFCTag) MainActivity.getTag();
        mIsActive = true;

        boolean tagChanged = tagChanged(this, currentTag);

        if (mTagTapedListener != null) {
            mTagTapedListener.tagTaped(tagChanged);
        }

        if (tagChanged) {
            Log.d(TAG, "=== Tag has changed : Restart MainActivity ===");

            // Tag has changed. We want to do the following actions;
            // - Go back to the MainActivity and flush the activity stack history (MainActivity will be seen as the sole activity launched)
            // - The intent should contain the NFC Intent details so that MainActivity can process it and do the right actions.

            // Get current NFC intent and retrieve the NFC information (NfcAdapter.EXTRA_TAG)
            Intent nfcIntent = getIntent();
            Tag androidTag = nfcIntent.getParcelableExtra(NfcAdapter.EXTRA_TAG);

            // Create an intent to start the MainActivity
            Intent intent = new Intent(this, MainActivity.class);

            // Attach the NFC information to this intent
            intent.putExtra(NfcAdapter.EXTRA_TAG, androidTag);

            // Set the flags to flush the activity stack history
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

            startActivity(intent);
        } else {
            // The same tag has been tapped
            // If a new androidTag handle is available we should update the AndroidReaderInterface of the currentTag
            Intent intent = getIntent();
            Tag newAndroidTag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
            if (newAndroidTag != null) {
                if (currentTag != null) {
                    AndroidReaderInterface androidReaderInterface = (AndroidReaderInterface) currentTag.getReaderInterface();
                    Tag currentAndroidTag = androidReaderInterface.getAndroidTag();
                    if (newAndroidTag != currentAndroidTag) {
                        Log.w(TAG, "Update of androidTag handle: " + newAndroidTag);
                        androidReaderInterface.setAndroidTag(newAndroidTag);
                    }
                }
            }
        }

        Log.v(TAG, "enableForegroundDispatch");
        mNfcAdapter.enableForegroundDispatch(this, mPendingIntent, null /*nfcFiltersArray*/, null /*nfcTechLists*/);
    }

    @SuppressLint("MissingSuperCall")
    @Override
    public void onNewIntent(Intent intent) {
        setIntent(intent);
    }

    /**
     * Helper function to display a Toast from non UI thread
     * @param resource_id
     * @param formatArgs
     */
    public void showToast(final int resource_id, final Object... formatArgs) {
        runOnUiThread(new Runnable() {
            public void run() {
                // This function can be called from a background thread so it may happen after the
                // destruction of the activity. In such case, getResmessageources() may be null.
                Resources resources = getResources();
                if(resources != null) {
                    String message = resources.getString(resource_id, formatArgs);
                    Toast.makeText(STFragmentActivity.this, message, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    /**
     * This function is typically used when a critical issue is detected (ex: nfcTag is null).
     * It goes back to the MainActivity.
     */
    public void goBackToMainActivity() {
        Intent intent = new Intent(this, MainActivity.class);

        // Set the flags to flush the activity stack history
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

        startActivity(intent);
    }

    public void registerTapTagListener(TagTapedListener listener) {
        mTagTapedListener = listener;
    }

    public void unRegisterTapTagListener() {
        mTagTapedListener = null;
    }

}
