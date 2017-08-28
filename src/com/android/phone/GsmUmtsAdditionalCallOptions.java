package com.android.phone;

import android.app.ActionBar;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.preference.Preference;
import android.preference.PreferenceScreen;
import android.telephony.TelephonyManager;
import android.telephony.CarrierConfigManager;
import android.util.Log;
import android.view.MenuItem;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;

import java.util.ArrayList;

public class GsmUmtsAdditionalCallOptions extends TimeConsumingPreferenceActivity {
    private static final String LOG_TAG = "GsmUmtsAdditionalCallOptions";
    private final boolean DBG = (PhoneGlobals.DBG_LEVEL >= 2);

    private static final String BUTTON_CLIR_KEY  = "button_clir_key";
    private static final String BUTTON_CW_KEY    = "button_cw_key";
    private static final String BUTTON_PN_KEY    = "button_pn_key";

    private CLIRListPreference mCLIRButton;
    private CallWaitingCheckBoxPreference mCWButton;
    private MSISDNEditPreference mMSISDNButton;

    private final ArrayList<Preference> mPreferences = new ArrayList<Preference>();
    private int mInitIndex = 0;
    private Phone mPhone;
    private SubscriptionInfoHelper mSubscriptionInfoHelper;

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        addPreferencesFromResource(R.xml.gsm_umts_additional_options);

        mSubscriptionInfoHelper = new SubscriptionInfoHelper(this, getIntent());
        mSubscriptionInfoHelper.setActionBarTitle(
                getActionBar(), getResources(), R.string.additional_gsm_call_settings_with_label);
        mPhone = mSubscriptionInfoHelper.getPhone();

        PreferenceScreen prefSet = getPreferenceScreen();
        mCLIRButton = (CLIRListPreference) prefSet.findPreference(BUTTON_CLIR_KEY);
        mCWButton = (CallWaitingCheckBoxPreference) prefSet.findPreference(BUTTON_CW_KEY);
        mMSISDNButton = (MSISDNEditPreference) prefSet.findPreference(BUTTON_PN_KEY);

        mPreferences.add(mCLIRButton);
        mPreferences.add(mCWButton);
        mPreferences.add(mMSISDNButton);

        if (icicle == null) {
            if (DBG) Log.d(LOG_TAG, "start to init ");
            if (isUtEnabledToDisableClir()) {
                mCLIRButton.setSummary(R.string.sum_default_caller_id);
                mCWButton.init(this, false, mPhone);
            } else {
                mCLIRButton.init(this, false, mPhone);
                mMSISDNButton.init(this, false, mPhone);
            }
        } else {
            if (DBG) Log.d(LOG_TAG, "restore stored states");
            mInitIndex = mPreferences.size();
            if (isUtEnabledToDisableClir()) {
                mCLIRButton.setSummary(R.string.sum_default_caller_id);
                mCWButton.init(this, true, mPhone);
            } else {
                mCLIRButton.init(this, true, mPhone);
                mCWButton.init(this, true, mPhone);
                mMSISDNButton.init(this, true, mPhone);
                int[] clirArray = icicle.getIntArray(mCLIRButton.getKey());
                if (clirArray != null) {
                    if (DBG) Log.d(LOG_TAG, "onCreate:  clirArray[0]="
                            + clirArray[0] + ", clirArray[1]=" + clirArray[1]);
                    mCLIRButton.handleGetCLIRResult(clirArray);
                } else {
                    mCLIRButton.init(this, false, mPhone);
                }
            }
        }

        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            // android.R.id.home will be triggered in onOptionsItemSelected()
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
    }

    private boolean isUtEnabledToDisableClir() {
        boolean skipClir = false;
        CarrierConfigManager configManager = (CarrierConfigManager)
            getSystemService(Context.CARRIER_CONFIG_SERVICE);
        PersistableBundle pb = configManager.getConfigForSubId(mPhone.getSubId());
        if (pb != null) {
            skipClir = pb.getBoolean("config_disable_clir_over_ut");
        }
        return mPhone.isUtEnabled() && skipClir;
    }
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        if (mCLIRButton.clirArray != null) {
            outState.putIntArray(mCLIRButton.getKey(), mCLIRButton.clirArray);
        }
    }

    @Override
    public void onFinished(Preference preference, boolean reading) {
        if (mInitIndex < mPreferences.size()-1 && !isFinishing()) {
            mInitIndex++;
            Preference pref = mPreferences.get(mInitIndex);
            if (pref instanceof CallWaitingCheckBoxPreference) {
                ((CallWaitingCheckBoxPreference) pref).init(this, false, mPhone);
            } else if (pref instanceof MSISDNEditPreference) {
                ((MSISDNEditPreference) pref).init(this, false, mPhone);
            }
        }
        super.onFinished(preference, reading);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final int itemId = item.getItemId();
        if (itemId == android.R.id.home) {  // See ActionBar#setDisplayHomeAsUpEnabled()
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
