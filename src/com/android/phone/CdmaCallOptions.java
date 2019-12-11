/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.phone;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.CarrierConfigManager;
import android.telephony.ims.feature.ImsFeature;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.MenuItem;

import com.android.ims.ImsException;
import com.android.ims.ImsManager;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.SubscriptionController;

import java.util.List;

public class CdmaCallOptions extends TimeConsumingPreferenceActivity
               implements DialogInterface.OnClickListener,
               DialogInterface.OnCancelListener {
    private static final String LOG_TAG = "CdmaCallOptions";
    private final boolean DBG = (PhoneGlobals.DBG_LEVEL >= 2);

    public static final int CALL_WAITING = 7;
    private static final String BUTTON_VP_KEY = "button_voice_privacy_key";
    private SwitchPreference mButtonVoicePrivacy;
    public static final String CALL_FORWARD_INTENT = "org.codeaurora.settings.CDMA_CALL_FORWARDING";
    public static final String CALL_WAITING_INTENT = "org.codeaurora.settings.CDMA_CALL_WAITING";

    private CallWaitingSwitchPreference mCWButton;
    private PreferenceScreen mPrefCW;
    private boolean mUtEnabled = false;
    private boolean mCommon = false;
    private Phone mPhone = null;
    private boolean mCdmaCfCwEnabled = false;
    private static final String BUTTON_CW_KEY = "button_cw_ut_key";

    private static boolean isActivityPresent(Context context, String intentName) {
        PackageManager pm = context.getPackageManager();
        // check whether the target handler exist in system
        Intent intent = new Intent(intentName);
        List<ResolveInfo> list = pm.queryIntentActivities(intent, 0);
        for (ResolveInfo resolveInfo : list){
            if ((resolveInfo.activityInfo.applicationInfo.flags &
                    ApplicationInfo.FLAG_SYSTEM) != 0) {
                return true;
            }
        }
        return false;
    }

    public static boolean isCdmaCallForwardingActivityPresent(Context context) {
        return isActivityPresent(context, CALL_FORWARD_INTENT);
    }

    public static boolean isCdmaCallWaitingActivityPresent(Context context) {
        return isActivityPresent(context, CALL_WAITING_INTENT);
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

    /*
     * Some operators ask to prompt user to switch DDS to sub which query CF/CW over UT
     */
    private  boolean maybePromptUserToSwitchDds() {
        // check the active data sub.
        int sub = mPhone.getSubId();
        final SubscriptionManager subMgr = SubscriptionManager.from(this);
        int slotId = subMgr.getSlotIndex(sub);
        int defaultDataSub = subMgr.getDefaultDataSubscriptionId();
        Log.d(LOG_TAG, "isUtEnabled = " + mPhone.isUtEnabled() + ", need to check DDS ");
        if (mPhone != null && sub != defaultDataSub && !mPhone.isUtEnabled()) {
            Log.d(LOG_TAG, "Show dds switch dialog if data sub is not on current sub");
            showSwitchDdsDialog(slotId);
            return true;
        }
        return false;
    }

    private void showSwitchDdsDialog(int slotId) {
        String title = (String)this.getResources().getText(R.string.no_mobile_data);
        int simId = slotId + 1;
        String message = (String)this.getResources()
            .getText(R.string.switch_dds_to_sub_alert_msg) + String.valueOf(simId);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(title);
        builder.setMessage(message);
        builder.setIconAttribute(android.R.attr.alertDialogIcon);
        builder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                Intent newIntent = new Intent("com.qualcomm.qti.simsettings.SIM_SETTINGS");
                newIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(newIntent);
                finish();
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

    @Override
    public void onCancel(DialogInterface dialog) {
        finish();
        return;
    }

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        addPreferencesFromResource(R.xml.cdma_call_privacy);

        SubscriptionInfoHelper subInfoHelper = new SubscriptionInfoHelper(this, getIntent());
        PersistableBundle carrierConfig;
        if (subInfoHelper.hasSubId()) {
            carrierConfig = PhoneGlobals.getInstance().getCarrierConfigForSubId(
                    subInfoHelper.getSubId());
        } else {
            carrierConfig = PhoneGlobals.getInstance().getCarrierConfig();
        }
        mCommon = carrierConfig.getBoolean("config_common_callsettings_support_bool");
        subInfoHelper.setActionBarTitle(
                getActionBar(), getResources(),
                mCommon ? R.string.labelCommonMore_with_label : R.string.labelCdmaMore_with_label);

        mButtonVoicePrivacy = (SwitchPreference) findPreference(BUTTON_VP_KEY);
        mPhone = subInfoHelper.getPhone();
        Log.d(LOG_TAG, "sub id = " + subInfoHelper.getSubId() + " phone id = " +
                mPhone.getPhoneId());

        mCdmaCfCwEnabled = carrierConfig
            .getBoolean(CarrierConfigManager.KEY_CDMA_CW_CF_ENABLED_BOOL);
        PreferenceScreen prefScreen = getPreferenceScreen();
        if (mPhone.getPhoneType() != PhoneConstants.PHONE_TYPE_CDMA ||
                carrierConfig.getBoolean(CarrierConfigManager.KEY_VOICE_PRIVACY_DISABLE_UI_BOOL)) {
            CdmaVoicePrivacySwitchPreference prefPri = (CdmaVoicePrivacySwitchPreference)
                    prefScreen.findPreference("button_voice_privacy_key");
            if (prefPri != null) {
                prefPri.setEnabled(false);
            }
        }

        if(carrierConfig.getBoolean("check_mobile_data_for_cf") && maybePromptUserToSwitchDds()) {
            return;
        }
        if(mPhone.getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA
                && isPromptTurnOffEnhance4GLTE(mPhone)
                && carrierConfig.getBoolean(CarrierConfigManager.KEY_CDMA_CW_CF_ENABLED_BOOL)) {
            String title = (String)this.getResources()
                .getText(R.string.ut_not_support);
            String msg = (String)this.getResources()
                .getText(R.string.ct_ut_not_support_close_4glte);
            showAlertDialog(title, msg);
        }

        mCWButton = (CallWaitingSwitchPreference) prefScreen.findPreference(BUTTON_CW_KEY);
        if (mPhone.getPhoneType() != PhoneConstants.PHONE_TYPE_CDMA
                || !carrierConfig.getBoolean(CarrierConfigManager.KEY_CDMA_CW_CF_ENABLED_BOOL)
                || !isCdmaCallWaitingActivityPresent(this)) {
            Log.d(LOG_TAG, "Disabled CW CF");
            mPrefCW = (PreferenceScreen) prefScreen.findPreference("button_cw_key");
            if (mCWButton != null) {
                 prefScreen.removePreference(mCWButton);
            }

            if (mPrefCW != null) {
                mPrefCW.setEnabled(false);
            }
            PreferenceScreen prefCF = (PreferenceScreen)
                    prefScreen.findPreference("button_cf_expand_key");
            if (prefCF != null) {
                prefCF.setEnabled(false);
            }
        } else {
            Log.d(LOG_TAG, "Enabled CW CF");
            mPrefCW = (PreferenceScreen) prefScreen.findPreference("button_cw_key");

            ImsManager imsMgr = ImsManager.getInstance(this, mPhone.getPhoneId());
            Boolean isEnhanced4G = imsMgr.isEnhanced4gLteModeSettingEnabledByUser();
            if (mPhone.isUtEnabled() && isEnhanced4G) {
                mUtEnabled = mPhone.isUtEnabled();
                prefScreen.removePreference(mPrefCW);
                mCWButton.init(this, false, mPhone);
            } else {
                if (mCWButton != null) {
                    prefScreen.removePreference(mCWButton);
                }
                if (mPrefCW != null) {
                    mPrefCW.setOnPreferenceClickListener(
                            new Preference.OnPreferenceClickListener() {
                                @Override
                                public boolean onPreferenceClick(Preference preference) {
                                    Intent intent = new Intent(CALL_WAITING_INTENT);
                                    intent.putExtra(PhoneConstants.SUBSCRIPTION_KEY,
                                        mPhone.getSubId());
                                    startActivity(intent);
                                    return true;
                                }
                            });
                }
            }
            PreferenceScreen prefCF = (PreferenceScreen)
                    prefScreen.findPreference("button_cf_expand_key");
            if (prefCF != null) {
                prefCF.setOnPreferenceClickListener(
                        new Preference.OnPreferenceClickListener() {
                            @Override
                            public boolean onPreferenceClick(Preference preference) {
                                Intent intent = mPhone.isUtEnabled() ?
                                    subInfoHelper.getIntent(GsmUmtsCallForwardOptions.class)
                                    : new Intent(CALL_FORWARD_INTENT);
                                if (mPhone.isUtEnabled()) {
                                    intent.putExtra(PhoneUtils.SERVICE_CLASS,
                                        CommandsInterface.SERVICE_CLASS_VOICE);
                                } else {
                                    intent.putExtra(PhoneConstants.SUBSCRIPTION_KEY,
                                        mPhone.getSubId());
                                }
                                startActivity(intent);
                                return true;
                            }
                        });
            }
        }
    }

    @Override
    public void onFinished(Preference preference, boolean reading) {
        if (mCdmaCfCwEnabled && mUtEnabled && mPhone != null && !mPhone.isUtEnabled()) {
            if (isPromptTurnOffEnhance4GLTE(mPhone)) {
                String title = (String)this.getResources()
                    .getText(R.string.ut_not_support);
                String msg = (String)this.getResources()
                    .getText(R.string.ct_ut_not_support_close_4glte);
                showAlertDialog(title, msg);
            }
            mUtEnabled = false;
            if (mCWButton != null) {
                PreferenceScreen prefScreen = getPreferenceScreen();
                prefScreen.removePreference(mCWButton);
                prefScreen.addPreference(mPrefCW);
                if (mPrefCW != null) {
                    mPrefCW.setOnPreferenceClickListener(
                            new Preference.OnPreferenceClickListener() {
                                @Override
                                public boolean onPreferenceClick(Preference preference) {
                                    Intent intent = new Intent(CALL_WAITING_INTENT);
                                    intent.putExtra(PhoneConstants.SUBSCRIPTION_KEY,
                                        mPhone.getSubId());
                                    startActivity(intent);
                                    return true;
                                }
                            });
                }
            }
        }
        super.onFinished(preference, reading);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final int itemId = item.getItemId();
        if (itemId == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        if (preference.getKey().equals(BUTTON_VP_KEY)) {
            return true;
        }
        return false;
    }

}
