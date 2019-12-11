package com.android.phone;

import android.app.ActionBar;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.os.SystemProperties;
import android.preference.Preference;
import android.preference.PreferenceScreen;
import android.telephony.CarrierConfigManager;
import android.telephony.ims.feature.ImsFeature;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.MenuItem;
import android.widget.Toast;
import com.android.ims.ImsException;
import com.android.ims.ImsManager;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyIntents;

import com.android.internal.telephony.CallForwardInfo;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.Phone;

import java.util.ArrayList;

public class GsmUmtsCallForwardOptions extends TimeConsumingPreferenceActivity
    implements DialogInterface.OnClickListener, DialogInterface.OnCancelListener {
    private static final String LOG_TAG = "GsmUmtsCallForwardOptions";

    private static final String NUM_PROJECTION[] = {
        android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
    };

    public static final String CALL_FORWARD_INTENT = "org.codeaurora.settings.CDMA_CALL_FORWARDING";

    private static final String BUTTON_CFU_KEY   = "button_cfu_key";
    private static final String BUTTON_CFB_KEY   = "button_cfb_key";
    private static final String BUTTON_CFNRY_KEY = "button_cfnry_key";
    private static final String BUTTON_CFNRC_KEY = "button_cfnrc_key";

    private static final String KEY_TOGGLE = "toggle";
    private static final String KEY_STATUS = "status";
    private static final String KEY_NUMBER = "number";
    private static final String KEY_ENABLE = "enable";

    private CallForwardEditPreference mButtonCFU;
    private CallForwardEditPreference mButtonCFB;
    private CallForwardEditPreference mButtonCFNRy;
    private CallForwardEditPreference mButtonCFNRc;

    private final ArrayList<CallForwardEditPreference> mPreferences =
            new ArrayList<CallForwardEditPreference> ();
    private int mInitIndex= 0;

    private boolean mFirstResume;
    private Bundle mIcicle;
    private Phone mPhone;
    private SubscriptionInfoHelper mSubscriptionInfoHelper;
    private boolean mReplaceInvalidCFNumbers;
    private int mServiceClass;
    private BroadcastReceiver mReceiver = null;
    private SubscriptionManager mSubscriptionManager;
    private boolean mCheckData = false;
    AlertDialog.Builder builder = null;
    private CarrierConfigManager mCarrierConfig;
    private boolean mCallForwardByUssd;

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        addPreferencesFromResource(R.xml.callforward_options);

        mSubscriptionInfoHelper = new SubscriptionInfoHelper(this, getIntent());
        mSubscriptionInfoHelper.setActionBarTitle(
                getActionBar(), getResources(), R.string.call_forwarding_settings_with_label);
        mPhone = mSubscriptionInfoHelper.getPhone();

        mCarrierConfig = (CarrierConfigManager)
                getSystemService(CARRIER_CONFIG_SERVICE);

        if (mCarrierConfig != null) {
            PersistableBundle pb = mCarrierConfig.getConfigForSubId(mPhone.getSubId());
            mCheckData = pb.getBoolean("check_mobile_data_for_cf");
        }
        PersistableBundle b = null;
        if (mSubscriptionInfoHelper.hasSubId()) {
            b = PhoneGlobals.getInstance().getCarrierConfigForSubId(
                    mSubscriptionInfoHelper.getSubId());
        } else {
            b = PhoneGlobals.getInstance().getCarrierConfig();
        }
        if (b != null) {
            mReplaceInvalidCFNumbers = b.getBoolean(
                    CarrierConfigManager.KEY_CALL_FORWARDING_MAP_NON_NUMBER_TO_VOICEMAIL_BOOL);
            mCallForwardByUssd = b.getBoolean(
                    CarrierConfigManager.KEY_USE_CALL_FORWARDING_USSD_BOOL);
        }

        PreferenceScreen prefSet = getPreferenceScreen();
        mButtonCFU = (CallForwardEditPreference) prefSet.findPreference(BUTTON_CFU_KEY);
        mButtonCFB = (CallForwardEditPreference) prefSet.findPreference(BUTTON_CFB_KEY);
        mButtonCFNRy = (CallForwardEditPreference) prefSet.findPreference(BUTTON_CFNRY_KEY);
        mButtonCFNRc = (CallForwardEditPreference) prefSet.findPreference(BUTTON_CFNRC_KEY);

        mButtonCFU.setParentActivity(this, mButtonCFU.reason);
        mButtonCFB.setParentActivity(this, mButtonCFB.reason);
        mButtonCFNRy.setParentActivity(this, mButtonCFNRy.reason);
        mButtonCFNRc.setParentActivity(this, mButtonCFNRc.reason);

        mPreferences.add(mButtonCFU);
        mPreferences.add(mButtonCFB);
        mPreferences.add(mButtonCFNRy);
        mPreferences.add(mButtonCFNRc);

        if (mCallForwardByUssd) {
            //the call forwarding ussd command's behavior is similar to the call forwarding when
            //unanswered,so only display the call forwarding when unanswered item.
            prefSet.removePreference(mButtonCFU);
            prefSet.removePreference(mButtonCFB);
            prefSet.removePreference(mButtonCFNRc);
            mPreferences.remove(mButtonCFU);
            mPreferences.remove(mButtonCFB);
            mPreferences.remove(mButtonCFNRc);
            mButtonCFNRy.setDependency(null);
        }

        // we wait to do the initialization until onResume so that the
        // TimeConsumingPreferenceActivity dialog can display as it
        // relies on onResume / onPause to maintain its foreground state.

        /*Retrieve Call Forward ServiceClass*/
        Intent intent = getIntent();
        Log.d(LOG_TAG, "Intent is " + intent);
        mServiceClass = intent.getIntExtra(PhoneUtils.SERVICE_CLASS,
                CommandsInterface.SERVICE_CLASS_VOICE);
        Log.d(LOG_TAG, "serviceClass: " + mServiceClass);

        mFirstResume = true;
        mIcicle = icicle;

        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            // android.R.id.home will be triggered in onOptionsItemSelected()
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
    }

    /**
     * Receiver for intent broadcasts the Phone app cares about.
     */
    private class PhoneAppBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(TelephonyIntents.ACTION_ANY_DATA_CONNECTION_STATE_CHANGED)) {
                String state = intent.getStringExtra(PhoneConstants.STATE_KEY);
                final String apnType = intent.getStringExtra(PhoneConstants.DATA_APN_TYPE_KEY);
                Log.d(LOG_TAG, "apntype is: " + apnType + " state is: " + state);
                if (PhoneConstants.DataState.DISCONNECTED.name().equals(state) &&
                            PhoneConstants.APN_TYPE_DEFAULT.equals(apnType)) {
                    Log.d(LOG_TAG, "default data is disconnected.");
                    checkDataStatus();
                }
            }
        }
    }

    public void checkDataStatus() {
        // check the active data sub.
        int sub = mPhone.getSubId();
        int slotId = mSubscriptionManager.getSlotIndex(sub);
        int defaultDataSub = mSubscriptionManager.getDefaultDataSubscriptionId();
        Log.d(LOG_TAG, "isUtEnabled = " + mPhone.isUtEnabled() + ", checkData= " + mCheckData);
        // Find out if the sim card is ready.
        boolean isSimReady = TelephonyManager.from(this).getSimState(slotId)
                == TelephonyManager.SIM_STATE_READY;
        if (!isSimReady) {
            Log.d(LOG_TAG, "SIM is not ready!");
            String title = (String)this.getResources().getText(R.string.sim_is_not_ready);
            String message = (String)this.getResources()
                .getText(R.string.sim_is_not_ready);
            showAlertDialog(title, message);
            return;
        }
        if (mPhone != null) {
            int activeNetworkType = getActiveNetworkType();
            boolean isDataRoaming = mPhone.getServiceState().getDataRoaming();
            boolean isDataRoamingEnabled = mPhone.getDataRoamingEnabled();
            boolean promptForDataRoaming = isDataRoaming && !isDataRoamingEnabled;
            Log.d(LOG_TAG, "activeNetworkType = " + getActiveNetworkType() + ", sub = " + sub +
                    ", defaultDataSub = " + defaultDataSub + ", isDataRoaming = " +
                    isDataRoaming + ", isDataRoamingEnabled= " + isDataRoamingEnabled);
            if ((sub != defaultDataSub) && !mPhone.isUtEnabled()) {
                Log.d(LOG_TAG, "Show dds switch dialog if data sub is not on current sub");
                showSwitchDdsDialog(slotId);
                return;
            }

            if (mPhone.isUtEnabled() && mCheckData) {
                boolean isDataEnabled = TelephonyManager.from(this).getDataEnabled(sub);
                Log.d(LOG_TAG, "isDataEnabled: " + isDataEnabled);
                if ((!isDataEnabled || activeNetworkType != ConnectivityManager.TYPE_MOBILE)
                        && !(activeNetworkType == ConnectivityManager.TYPE_NONE
                        && promptForDataRoaming)) {
                    Log.d(LOG_TAG,
                            "Show alert dialog if data sub is not on current sub or WLAN is on");
                    String title = (String)this.getResources().getText(R.string.no_mobile_data);
                    String message = (String)this.getResources()
                            .getText(R.string.cf_setting_mobile_data_alert);
                    showAlertDialog(title, message);
                    return;
                }
                if (promptForDataRoaming) {
                       Log.d(LOG_TAG, "Show alert dialog if data roaming is disabled");
                       String title = (String)this.getResources()
                               .getText(R.string.no_mobile_data_roaming);
                       String message = (String)this.getResources()
                               .getText(R.string.cf_setting_mobile_data_roaming_alert);
                       showAlertDialog(title, message);
                       return;
                }
                if (sub != defaultDataSub) {
                    Log.d(LOG_TAG, "Show data in use indication if data sub is not on current sub");
                    showDataInuseToast();
                }
            }
        }
        initCallforwarding();
    }

    @Override
    public void onClick(DialogInterface dialog, int id) {
        if (id == DialogInterface.BUTTON_POSITIVE) {
            Intent newIntent = new Intent("android.settings.SETTINGS");
            newIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(newIntent);
        }
        finish();
        return;
    }

    private int getActiveNetworkType() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(
                Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            NetworkInfo ni = cm.getActiveNetworkInfo();
            if ((ni == null) || !ni.isConnected()){
                return ConnectivityManager.TYPE_NONE;
            }
            return ni.getType();
        }
        return ConnectivityManager.TYPE_NONE;
    }

    //prompt dialog to notify user turn off Enhance 4G LTE switch
    private boolean isPromptTurnOffEnhance4GLTE(Phone phone) {
        if (phone == null || phone.getImsPhone() == null) {
            return false;
        }

        ImsManager imsMgr = ImsManager.getInstance(this, phone.getPhoneId());
        try {
            if (imsMgr.getImsServiceState() != ImsFeature.STATE_READY) {
                Log.d(LOG_TAG, "ImsServiceStatus is not ready!");
                return false;
            }
        } catch (ImsException ex) {
            Log.d(LOG_TAG, "Exception when trying to get ImsServiceStatus: " + ex);
            return false;
        }

        return imsMgr.isEnhanced4gLteModeSettingEnabledByUser()
            && imsMgr.isNonTtyOrTtyOnVolteEnabled()
            && !phone.isUtEnabled()
            && !phone.isVolteEnabled()
            && !phone.isVideoEnabled();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mCarrierConfig.getConfigForSubId(mPhone.getSubId())
                .getBoolean(CarrierConfigManager.KEY_CDMA_CW_CF_ENABLED_BOOL)
                && isPromptTurnOffEnhance4GLTE(mPhone)) {
            String title = (String)this.getResources()
                .getText(R.string.ut_not_support);
            String msg = (String)this.getResources()
                .getText(R.string.ct_ut_not_support_close_4glte);
            showAlertDialog(title, msg);
            return;
        }

        if (mCheckData) {
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(TelephonyIntents.ACTION_ANY_DATA_CONNECTION_STATE_CHANGED);
            mReceiver = new PhoneAppBroadcastReceiver();
            registerReceiver(mReceiver, intentFilter);
            final SubscriptionManager mSubscriptionManager = SubscriptionManager.from(this);
            checkDataStatus();
        } else {
            initCallforwarding();
        }
    }

    private void initCallforwarding () {
        if (mFirstResume) {
            if (mIcicle == null) {
                Log.d(LOG_TAG, "start to init ");
                CallForwardEditPreference pref = mPreferences.get(mInitIndex);
                pref.init(this, mPhone, mReplaceInvalidCFNumbers, mServiceClass, mCallForwardByUssd);
                pref.startCallForwardOptionsQuery();

            } else {
                mInitIndex = mPreferences.size();

                for (CallForwardEditPreference pref : mPreferences) {
                    Bundle bundle = mIcicle.getParcelable(pref.getKey());
                    pref.setToggled(bundle.getBoolean(KEY_TOGGLE));
                    pref.setEnabled(bundle.getBoolean(KEY_ENABLE));
                    CallForwardInfo cf = new CallForwardInfo();
                    cf.number = bundle.getString(KEY_NUMBER);
                    cf.status = bundle.getInt(KEY_STATUS);
                    pref.init(this, mPhone, mReplaceInvalidCFNumbers, mServiceClass, mCallForwardByUssd);
                    pref.restoreCallForwardInfo(cf);
                }
            }
            mFirstResume = false;
            mIcicle = null;
        }
    }

    private void showDataInuseToast() {
        String message = (String)this.getResources()
                .getText(R.string.mobile_data_alert);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void showSwitchDdsDialog(int slotId) {
        String title = (String)this.getResources().getText(R.string.no_mobile_data);
        int simId = slotId + 1;
        String message = (String)this.getResources()
                .getText(R.string.switch_dds_to_sub_alert) + String.valueOf(simId);
        if (builder == null) {
            builder=new AlertDialog.Builder(this);
            builder.setTitle(title);
            builder.setMessage(message);
            builder.setIconAttribute(android.R.attr.alertDialogIcon);
            builder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    Intent newIntent = new Intent("com.qualcomm.qti.simsettings.SIM_SETTINGS");
                    newIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(newIntent);
                }
            });
            builder.setNegativeButton(android.R.string.cancel,
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        finish();
                    }
            });
            builder.create().show();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mCheckData && mReceiver != null) {
            unregisterReceiver(mReceiver);
        }
        for (CallForwardEditPreference pref : mPreferences) {
            pref.deInit();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        for (CallForwardEditPreference pref : mPreferences) {
            Bundle bundle = new Bundle();
            bundle.putBoolean(KEY_TOGGLE, pref.isToggled());
            bundle.putBoolean(KEY_ENABLE, pref.isEnabled());
            if (pref.callForwardInfo != null) {
                bundle.putString(KEY_NUMBER, pref.callForwardInfo.number);
                bundle.putInt(KEY_STATUS, pref.callForwardInfo.status);
            }
            outState.putParcelable(pref.getKey(), bundle);
        }
    }

    @Override
    public void onFinished(Preference preference, boolean reading) {
        if (mInitIndex < mPreferences.size()-1 && !isFinishing()) {
            if (mInitIndex == 0 && mButtonCFU.isAutoRetryCfu()) {
                Log.i(LOG_TAG, "auto retry case: ");
                CarrierConfigManager carrierConfig = (CarrierConfigManager)
                    getSystemService(CARRIER_CONFIG_SERVICE);
                if(carrierConfig != null && mPhone != null
                        && carrierConfig.getConfigForSubId(mPhone.getSubId())
                            .getBoolean(CarrierConfigManager.KEY_CDMA_CW_CF_ENABLED_BOOL)) {
                    if (isPromptTurnOffEnhance4GLTE(mPhone)) {
                        String title = (String)this.getResources()
                            .getText(R.string.ut_not_support);
                        String msg = (String)this.getResources()
                            .getText(R.string.ct_ut_not_support_close_4glte);
                        showAlertDialog(title, msg);
                    }else if (mPhone.getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA ){
                        Log.i(LOG_TAG, "auto retry and switch to cmda method UI.");
                        Intent intent = new Intent(CALL_FORWARD_INTENT);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                        finish();
                    }
                }
            } else {
                mInitIndex++;
                CallForwardEditPreference pref = mPreferences.get(mInitIndex);
                pref.init(this, mPhone, mReplaceInvalidCFNumbers, mServiceClass, mCallForwardByUssd);
                pref.startCallForwardOptionsQuery();
            }
        }

        super.onFinished(preference, reading);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        Log.d(LOG_TAG, "onActivityResult: done");
        if (resultCode != RESULT_OK) {
            Log.d(LOG_TAG, "onActivityResult: contact picker result not OK.");
            return;
        }
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(data.getData(),
                NUM_PROJECTION, null, null, null);
            if ((cursor == null) || (!cursor.moveToFirst())) {
                Log.d(LOG_TAG, "onActivityResult: bad contact data, no results found.");
                return;
            }

            switch (requestCode) {
                case CommandsInterface.CF_REASON_UNCONDITIONAL:
                    mButtonCFU.onPickActivityResult(cursor.getString(0));
                    break;
                case CommandsInterface.CF_REASON_BUSY:
                    mButtonCFB.onPickActivityResult(cursor.getString(0));
                    break;
                case CommandsInterface.CF_REASON_NO_REPLY:
                    mButtonCFNRy.onPickActivityResult(cursor.getString(0));
                    break;
                case CommandsInterface.CF_REASON_NOT_REACHABLE:
                    mButtonCFNRc.onPickActivityResult(cursor.getString(0));
                    break;
                default:
                    // TODO: may need exception here.
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final int itemId = item.getItemId();
        if (itemId == android.R.id.home) {  // See ActionBar#setDisplayHomeAsUpEnabled()
            CallFeaturesSetting.goUpToTopLevelSetting(this, mSubscriptionInfoHelper);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showAlertDialog(String title, String message) {
        Dialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setIconAttribute(android.R.attr.alertDialogIcon)
                .setPositiveButton(android.R.string.ok, this)
                .setNegativeButton(android.R.string.cancel, this)
                .setOnCancelListener(this)
                .create();
        dialog.show();
    }
}
