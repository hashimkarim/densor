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
import android.app.PendingIntent;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;

import com.google.android.material.navigation.NavigationView;

import androidx.annotation.StringRes;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.FragmentManager;

import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.st.st25android.AndroidReaderInterface;
import com.st.st25nfc.BuildConfig;
import com.st.st25nfc.R;

import com.st.st25nfc.generic.util.AssetsHelper;
import com.st.st25nfc.generic.util.TagDiscovery;
import com.st.st25nfc.generic.util.TagDiscovery.onTagDiscoveryCompletedListener;

import com.st.st25nfc.generic.util.UIHelper;
import com.st.st25nfc.type5.GenericType5TagActivity;
import com.st.st25nfc.type5.st25dv.ST25DVActivity;
import com.st.st25nfc.type5.st25dvpwm.ST25DVWActivity;
import com.st.st25nfc.type5.st25tv.ST25TVActivity;
import com.st.st25nfc.type5.st25tvc.ST25TVCActivity;
import com.st.st25nfc.type5.stlri.STLRiActivity;
import com.st.st25nfc.type5.stlri.STLRiS2kActivity;
import com.st.st25nfc.type5.stlri.STLRiS64kActivity;
import com.st.st25nfc.type5.stm24lr.STM24LR04Activity;
import com.st.st25nfc.type5.stm24lr.STM24LRActivity;
import com.st.st25sdk.Helper;
import com.st.st25sdk.NFCTag;
import com.st.st25sdk.STException;
import com.st.st25sdk.TagCache;
import com.st.st25sdk.TagHelper;
import com.st.st25sdk.ndef.NDEFRecord;
import com.st.st25sdk.type5.st25dv.ST25DVTag;
import com.st.st25sdk.type5.st25tvc.ST25TVCTag;

import java.util.Arrays;

import static com.st.st25nfc.generic.MainActivity.ActionStatus.ACTION_FAILED;
import static com.st.st25nfc.generic.MainActivity.ActionStatus.ACTION_SUCCESSFUL;
import static com.st.st25nfc.generic.MainActivity.ActionStatus.TAG_NOT_IN_THE_FIELD;

import static com.st.st25nfc.generic.MainActivity.Action.INSTANTIATE_TAG;
import static com.st.st25nfc.generic.MainActivity.Action.TOGGLE_UNTRACEABLE_MODE_PWD;

import static com.st.st25sdk.TagHelper.ProductID.PRODUCT_ST_ST25TV02KC;

public class MainActivity extends AppCompatActivity
        implements NavigationView.OnNavigationItemSelectedListener, onTagDiscoveryCompletedListener, STType5PwdDialogFragment.STType5PwdDialogListener {

    private static final String TAG = "MainActivity";
    private static final boolean DBG = true;

    enum Action {
        LEAVE_UNTRACEABLE_MODE,
        INSTANTIATE_TAG,
        TOGGLE_UNTRACEABLE_MODE_PWD
    };

    enum ActionStatus {
        ACTION_SUCCESSFUL,
        ACTION_FAILED,
        TAG_NOT_IN_THE_FIELD,
        CONFIG_PASSWORD_NEEDED
    };

    static public Resources mResources;

    static private NFCTag mTag;

    public static final String NEW_TAG = "new_tag";

    public static final byte[] UNTRACEABLE_UID = new byte[] {(byte) 0xE0, (byte) 0x02, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00 };

    FragmentManager mFragmentManager;
    private NfcAdapter mNfcAdapter;
    private PendingIntent mPendingIntent;
    private TextView mNfcWarningTextView;
    private Button mEnableNfcButton;

    private SharedPreferences mSharedPreferences;
    private final String PREFS_NAME = "LICENSE_AGREEMENT";
    private final String SHARED_PREFERENCE_KEY = "licenseAgreement";
    private boolean mLicenseAgreement = false;
    private AlertDialog mLicenseAlertDialog;
    private Action mCurrentAction;

    private NavigationView mNavigationView;

    public interface NfcIntentHook {
        void newNfcIntent(Intent intent);
    }

    private static NfcIntentHook mNfcIntentHook;

    public MainActivity() {
        if (BuildConfig.DEBUG) {
            enableDebugCode();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.default_layout);
        mResources = getResources();
        // Inflate content of FrameLayout
        FrameLayout frameLayout=(FrameLayout) findViewById(R.id.frame_content);
        View childView = getLayoutInflater().inflate(R.layout.activity_main, null);
        frameLayout.addView(childView);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        DrawerLayout drawer = (DrawerLayout) findViewById(R.id.drawer);
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, drawer, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close);
        drawer.setDrawerListener(toggle);
        toggle.syncState();

        mFragmentManager = getSupportFragmentManager();

        mNavigationView = (NavigationView) findViewById(R.id.navigation_view);
        mNavigationView.setNavigationItemSelectedListener(this);
        mNavigationView.inflateMenu(R.menu.menu_main_activity);
        mNavigationView.inflateMenu(R.menu.menu_help);

        Button debugButton = findViewById(R.id.debugDensorButton);
        debugButton.setVisibility(BuildConfig.DEBUG ? View.VISIBLE : View.GONE);
        debugButton.setOnClickListener(v -> startActivity(new Intent().setClassName(
                this, "com.st.st25nfc.densor.debug.DensorDebugActivity")));

        mNfcAdapter = NfcAdapter.getDefaultAdapter(this);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            mPendingIntent = PendingIntent.getActivity(this, 0, new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_MUTABLE);
        } else {
            // Pre-Android 12 branch: no mutability flag exists/needs setting here.
            //noinspection UnspecifiedImmutableFlag
            mPendingIntent = PendingIntent.getActivity(this, 0, new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), 0);
        }

        mNfcWarningTextView = (TextView) findViewById(R.id.nfcWarningTextView);
        mEnableNfcButton = (Button) findViewById(R.id.enableNfcButton);

        mEnableNfcButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Settings.ACTION_NFC_SETTINGS);
                startActivity(intent);
            }
        });

        if (AssetsHelper.isFileExistingInAssetsDir(AssetsHelper.getLicenseFileName(), getAssets())) {
            mSharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            mLicenseAgreement = getLicenseAgreement();
        } else {
            mLicenseAgreement = true;
        }
    }


    /**
     * Get the license agreement (if any)
     * @return
     */
    private boolean getLicenseAgreement() {
        if (mSharedPreferences == null) return false;
        try {
            String agreement = mSharedPreferences.getString(SHARED_PREFERENCE_KEY, "");

            if (agreement.equals("true")) {
                return true;
            } else {
                return false;
            }
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
            return false;
        }
    }
    /**
     * Save Certificates Location in SharedPreferences
     */
    private void saveLicenseAgreement(boolean agreement) {
        if (mSharedPreferences == null) return;

        SharedPreferences.Editor sharedPreferencesEditor = mSharedPreferences.edit();

        if (sharedPreferencesEditor != null) {
            sharedPreferencesEditor.putString(SHARED_PREFERENCE_KEY, agreement?"true":"false");
            sharedPreferencesEditor.commit();
            mLicenseAgreement = agreement;
            if (mLicenseAgreement) {
                // add the view of the accepted license otherwise license agreement popup will be raised again
                mNavigationView.inflateMenu(R.menu.menu_license);
            }
        }
    }

    private void showLicenseAgreement(){
        if (AssetsHelper.isFileExistingInAssetsDir(AssetsHelper.getLicenseFileName(),getAssets())) {
            byte[] buffer = AssetsHelper.getSTLicense(AssetsHelper.getLicenseFileName(),getAssets());
            displayLicense(new String(buffer));
        }
    }

    private void displayLicense(String license) {
        if (mLicenseAlertDialog != null && mLicenseAlertDialog.isShowing()) {
            return;
        }
        String message = license;
        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this);

        // set title
        alertDialogBuilder.setTitle(getString(R.string.confirmation_needed));

        // set dialog message
        alertDialogBuilder
                .setMessage(message)
                .setCancelable(true)
                .setPositiveButton(getString(R.string.agree_license_label),new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog,int id) {
                        dialog.cancel();
                        saveLicenseAgreement(true);

                    }
                })
                .setNegativeButton(getString(R.string.cancel),new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog,int id) {
                        dialog.cancel();
                    }
                });

        // create alert dialog
        mLicenseAlertDialog = alertDialogBuilder.create();
        // show it
        mLicenseAlertDialog.show();
    }



    @Override
    public void onBackPressed() {
        DrawerLayout drawer = (DrawerLayout) findViewById(R.id.drawer);
        if (drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds read_list_items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.toolbar_empty, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

         switch (id) {
             case R.id.preferred_application:
                 Intent intent = new Intent(this, PreferredApplicationActivity.class);
                 startActivityForResult(intent, 1);
                 break;
             case R.id.about:
                super.onOptionsItemSelected(item);
                break;
             case R.id.license:
                 AssetsHelper.displayLicense(getAssets(),this);
                 break;
            default:
                return super.onOptionsItemSelected(item);
        }
        return true;
    }

    void processIntent(Intent intent) {
        // NFC Intent will not be processed until the License has been agreed
        if (!mLicenseAgreement) {
            showLicenseAgreement();
            return;
        }

        if(intent == null) {
            return;
        }

        Log.d(TAG, "processIntent " + intent);

        if(mNfcIntentHook != null) {
            // NFC Intent hook used only for test purpose!
            mNfcIntentHook.newNfcIntent(intent);
            return;
        }

        Tag androidTag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        if (androidTag != null) {
            // A tag has been taped

            byte[] uid = Helper.reverseByteArray(androidTag.getId());
            if (Arrays.equals(uid, UNTRACEABLE_UID)) {
                leaveUntraceableMode(androidTag, uid);
            } else {
                // Default behavior
                // Perform tag discovery in an asynchronous task
                // onTagDiscoveryCompleted() will be called when the discovery is completed.
                new TagDiscovery(this).execute(androidTag);
            }

            // This intent has been processed. Reset it to be sure that we don't process it again
            // if the MainActivity is resumed
            setIntent(null);
        }
    }

    static public void setNfcIntentHook(NfcIntentHook nfcIntentHook) {
        mNfcIntentHook = nfcIntentHook;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
    }

    @Override
    public void onPause() {
        super.onPause();

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
        Intent intent = getIntent();
        Log.d(TAG, "Resume mainActivity intent: " + intent);
        super.onResume();


        if (mNfcAdapter != null) {
            Log.v(TAG, "enableForegroundDispatch");
            mNfcAdapter.enableForegroundDispatch(this, mPendingIntent, null /*nfcFiltersArray*/, null /*nfcTechLists*/);

            if (mNfcAdapter.isEnabled()) {
                // NFC enabled
                mNfcWarningTextView.setVisibility(View.INVISIBLE);
                mEnableNfcButton.setVisibility(View.INVISIBLE);
            } else {
                // NFC disabled
                mNfcWarningTextView.setText(R.string.nfc_currently_disabled);
                mNfcWarningTextView.setVisibility(View.VISIBLE);
                mEnableNfcButton.setVisibility(View.VISIBLE);
            }

        } else {
            // NFC not available on this phone!!!
            mNfcWarningTextView.setText(R.string.nfc_not_available);
            mNfcWarningTextView.setVisibility(View.VISIBLE);
            mEnableNfcButton.setVisibility(View.INVISIBLE);
        }


        processIntent(intent);

    }

    @SuppressLint("MissingSuperCall")
    @Override
    public void onNewIntent(Intent intent) {
        // onResume gets called after this to handle the intent
        Log.d(TAG, "onNewIntent " + intent);
        setIntent(intent);
    }

    @Override
    public boolean onNavigationItemSelected(MenuItem item) {
        // Handle navigation view item clicks here.
        int id = item.getItemId();
        Intent intent;

        switch (id) {
            case R.id.preferred_application:
                intent = new Intent(this, PreferredApplicationActivity.class);
                startActivityForResult(intent, 1);
                break;
            case R.id.about:
                UIHelper.displayAboutDialogBox(this);
                break;
            case R.id.license:
                AssetsHelper.displayLicense(getAssets(),this);
                break;
            case R.id.activity_menu:

                // Check if an intent has been associated to this menuItem
                intent = item.getIntent();

                if(intent != null) {
                    startActivityForResult(intent, 1);
                }
            break;
        }

        DrawerLayout drawer = (DrawerLayout) findViewById(R.id.drawer);
        drawer.closeDrawer(GravityCompat.START);
        return true;
    }

    static public NFCTag getTag() {
        return mTag;
    }

    static public void setTag(NFCTag nfcTag) {
        mTag = nfcTag;
    }

    @Override
    public void onTagDiscoveryCompleted(NFCTag nfcTag, TagHelper.ProductID productId, STException e) {
        //Toast.makeText(getApplication(), "onTagDiscoveryCompleted. productId:" + productId, Toast.LENGTH_LONG).show();
        if (e != null) {
            Log.i(TAG, e.toString());
            Toast.makeText(getApplication(), R.string.error_while_reading_the_tag, Toast.LENGTH_LONG).show();
            return;
        }

        mTag = nfcTag;

        NavigationView navigationView = (NavigationView) findViewById(R.id.navigation_view);
        Menu menu = navigationView.getMenu();

        MenuItem menuItem = menu.findItem(R.id.activity_menu);

        switch (productId) {
            case PRODUCT_ST_ST25DV64K_I:
            case PRODUCT_ST_ST25DV64K_J:
            case PRODUCT_ST_ST25DV16K_I:
            case PRODUCT_ST_ST25DV16K_J:
            case PRODUCT_ST_ST25DV04K_I:
            case PRODUCT_ST_ST25DV04K_J:
            case PRODUCT_ST_ST25DV04KC_I:
            case PRODUCT_ST_ST25DV04KC_J:
            case PRODUCT_ST_ST25DV16KC_I:
            case PRODUCT_ST_ST25DV16KC_J:
            case PRODUCT_ST_ST25DV64KC_I:
            case PRODUCT_ST_ST25DV64KC_J:
                checkMailboxActivation();
                startTagActivity(ST25DVActivity.class, R.string.st25dv_menus);
                break;

            case PRODUCT_ST_LRi512:
            case PRODUCT_ST_LRi1K:
            case PRODUCT_ST_LRi2K:
                startTagActivity(STLRiActivity.class, R.string.lri_menus);
                break;

            case PRODUCT_ST_LRiS2K:
                startTagActivity(STLRiS2kActivity.class, R.string.lriS2k_menus);
                break;

            case PRODUCT_ST_LRiS64K:
                startTagActivity(STLRiS64kActivity.class, R.string.lriS64k_menus);
                break;

            case PRODUCT_ST_ST25TV64K:
            case PRODUCT_ST_ST25TV16K:
            case PRODUCT_ST_ST25TV04K_P:
                startTagActivity(ST25DVActivity.class, R.string.st25tv64k_menus);
                break;

            case PRODUCT_ST_ST25TV02K:
            case PRODUCT_ST_ST25TV512:
                startTagActivity(ST25TVActivity.class, R.string.st25tv_menus);
                break;

            case PRODUCT_ST_ST25TV02KC:
            case PRODUCT_ST_ST25TV512C:
                startTagActivity(ST25TVCActivity.class, R.string.st25tvc_menus);
                break;

            case PRODUCT_ST_ST25DV02K_W1:
            case PRODUCT_ST_ST25DV02K_W2:
                startTagActivity(ST25DVWActivity.class, R.string.st25dv02kw_menus);
                break;
            case PRODUCT_ST_M24LR16E_R:
            case PRODUCT_ST_M24LR64E_R:
            case PRODUCT_ST_M24LR64_R:
                startTagActivity(STM24LRActivity.class, R.string.m24lr64_menus);
                break;
            case PRODUCT_ST_M24LR04E_R:
                startTagActivity(STM24LR04Activity.class, R.string.m24lr04_menus);
                break;

            case PRODUCT_GENERIC_TYPE5:
                startTagActivity(GenericType5TagActivity.class, R.string.type5_menus);
                break;

            case PRODUCT_GENERIC_TYPE5_AND_ISO15693:
                startTagActivity(GenericType5TagActivity.class, R.string.type5_menus);
                break;

            case PRODUCT_GENERIC_TYPE4B:
            case PRODUCT_GENERIC_ISO14443B:
                menuItem.setTitle(R.string.product_unknown);
                displayInformationUsingTagInformation();
                Log.e(TAG, "Product not yet handled");
                break;

            default:
                menuItem.setTitle(R.string.product_unknown);
                displayInformationUsingTagInformation();
                //Toast.makeText(getApplication(), getResources().getString(R.string.unknown_tag), Toast.LENGTH_LONG).show();
                Log.e(TAG, "Product not recognized");
                break;
        }
    }

    private void checkMailboxActivation() {
        new Thread(new Runnable() {
            public void run() {
                ST25DVTag st25DVTag = (ST25DVTag) mTag;

                try {
                    if(st25DVTag.isMailboxEnabled(true)) {
                        runOnUiThread(new Runnable() {
                            public void run() {
                                Toast.makeText(MainActivity.this, getString(R.string.mailbox_enabled_eeprom_cannot_be_written), Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                } catch (STException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private void displayInformationUsingTagInformation() {
        new Thread(new Runnable() {
            public void run() {
                String uid = null;
                if (mTag != null) {
                    try {
                        uid = mTag.getUidString();
                    } catch (STException e) {
                        e.printStackTrace();
                    }
                }
                if (uid != null) {
                    final String finalUid = uid;
                    runOnUiThread(new Runnable() {
                        public void run() {
                            Toast.makeText(getApplication(), getResources().getString(R.string.product_not_handled_yet,mTag.mDescription + "[" + finalUid + "]"), Toast.LENGTH_LONG).show();
                        }
                    });
                } else {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            Toast.makeText(getApplication(), getResources().getString(R.string.unknown_tag), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    private void startTagActivity(Class<?> cls, int menuTitle) {

        // We are about to start the activity related to a tag so mTag should be non null
        if(getTag() == null) {
            Log.e(TAG, "Error! Trying to start a TagActivity with a null tag!");
            return;
        }

        Log.v(TAG, "startTagActivity: " + cls.getName());

        NavigationView navigationView = (NavigationView) findViewById(R.id.navigation_view);
        Menu menu = navigationView.getMenu();
        MenuItem menuItem = menu.findItem(R.id.activity_menu);
        menuItem.setTitle(menuTitle);
        menuItem.setVisible(true);

        Intent st_intent = new Intent(this, cls);
        st_intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        // Flag indicating that we are displaying the information of a new tag
        st_intent.putExtra(NEW_TAG, true);

        // Save in the menuItem the intent that should be called when this menuItem is clicked
        // It allows to open the same activity with low efforts
        menuItem.setIntent(st_intent);

        startActivityForResult(st_intent, 1);
    }

    private void enableDebugCode() {

        try {
            // Put here the debug features that you want to enable
            TagCache.class.getField("DBG_CACHE_MANAGER").set(null, false);

            NDEFRecord.class.getField("DBG_NDEF_RECORD").set(null, true);



        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
        }
    }

    private void leaveUntraceableMode(Tag androidTag, byte[] uid) {
        AndroidReaderInterface readerInterface = AndroidReaderInterface.newInstance(androidTag);
        new myAsyncTask(Action.LEAVE_UNTRACEABLE_MODE, readerInterface, uid).execute();
    }

    private void requestUntraceableModePwd() {
        mCurrentAction = TOGGLE_UNTRACEABLE_MODE_PWD;
        STType5PwdDialogFragment pwdDialogFragment = STType5PwdDialogFragment.newInstance(
                STType5PwdDialogFragment.STPwdAction.GET_OUT_OF_UNTRACEABLE_MODE,
                ST25TVCTag.ST25TVC_UNTRACEABLE_MODE_PASSWORD_ID,
                getResources().getString(R.string.please_enter_untraceable_mode_pwd));
        if(pwdDialogFragment!=null) pwdDialogFragment.show(mFragmentManager, "pwdDialogFragment");
    }

    @Override
    public void onSTType5PwdDialogFinish(int result) {

        if (result == PwdDialogFragment.RESULT_OK) {
            switch(mCurrentAction) {
                case TOGGLE_UNTRACEABLE_MODE_PWD:
                    try {
                        // The product is now fully functional
                        // Create a new instance of ST25TVCTag
                        AndroidReaderInterface readerInterface = (AndroidReaderInterface) mTag.getReaderInterface();
                        byte[] uid = mTag.getUid();
                        new myAsyncTask(INSTANTIATE_TAG, readerInterface, uid).execute();

                    } catch (STException e) {
                        e.printStackTrace();
                    }
                    break;
            }
        } else {
            Toast.makeText(getApplication(), getResources().getString(R.string.Command_failed), Toast.LENGTH_LONG).show();
        }
    }

    private class myAsyncTask extends AsyncTask<Void, Void, ActionStatus> {
        Action mAction;
        byte[] mUid;
        AndroidReaderInterface mReaderInterface;

        public myAsyncTask(Action action, AndroidReaderInterface readerInterface, byte[] uid) {
            mAction = action;
            mUid = uid;
            mReaderInterface = readerInterface;
        }

        @Override
        protected ActionStatus doInBackground(Void... param) {
            ActionStatus result;

            try {
                switch(mAction) {
                    case LEAVE_UNTRACEABLE_MODE:
                        // The Instantiated Tag will support only a minimum set of commands
                        boolean sendInitCommands = false;
                        mTag = new ST25TVCTag(mReaderInterface, mUid, sendInitCommands);
                        result = ACTION_SUCCESSFUL;
                        break;
                    case INSTANTIATE_TAG:
                        ST25TVCTag st25TVCTag = new ST25TVCTag(mReaderInterface, mUid);
                        st25TVCTag.setName("ST25TV02KC");

                        mTag = st25TVCTag;
                        result = ACTION_SUCCESSFUL;
                        break;
                    default:
                        // Unknown action
                        result = ACTION_FAILED;
                        break;
                }

            } catch (STException e) {
                switch (e.getError()) {
                    case CONFIG_PASSWORD_NEEDED:
                        result = ActionStatus.CONFIG_PASSWORD_NEEDED;
                        break;

                    case TAG_NOT_IN_THE_FIELD:
                        result = TAG_NOT_IN_THE_FIELD;
                        break;

                    default:
                        e.printStackTrace();
                        result = ACTION_FAILED;
                        break;
                }
            }

            return result;
        }

        @Override
        protected void onPostExecute(ActionStatus actionStatus) {

            switch(actionStatus) {
                case ACTION_SUCCESSFUL:
                    switch(mAction) {
                        case LEAVE_UNTRACEABLE_MODE:
                            requestUntraceableModePwd();
                            break;
                        case INSTANTIATE_TAG:
                            // Display the menus related to this tag
                            onTagDiscoveryCompleted(mTag, PRODUCT_ST_ST25TV02KC, null);
                            break;
                    }
                    break;
                case ACTION_FAILED:
                    showToast(R.string.error_while_updating_the_tag);
                    break;

                case TAG_NOT_IN_THE_FIELD:
                    showToast(R.string.tag_not_in_the_field);
                    break;
            }

            return;
        }

        private void showToast(@StringRes int id) {
            Toast.makeText(getApplication(), getResources().getString(id), Toast.LENGTH_LONG).show();
        }
    }

}
