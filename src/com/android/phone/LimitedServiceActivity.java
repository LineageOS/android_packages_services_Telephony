/*
 * Copyright (c) 2020, The Linux Foundation.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0

 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.phone;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.telephony.ims.ImsMmTelManager;
import android.telephony.TelephonyManager;
import android.telephony.SubscriptionManager;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.TextView;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;
import com.android.internal.telephony.CarrierServiceStateTracker;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;

public class LimitedServiceActivity extends FragmentActivity {

    private static final String LOG_TAG = "LimitedServiceActivity";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(LOG_TAG, "Started LimitedServiceActivity");
        int phoneId = getIntent().getExtras().getInt(PhoneConstants.PHONE_KEY);
        LimitedServiceAlertDialogFragment newFragment = LimitedServiceAlertDialogFragment.
                newInstance(phoneId);
        newFragment.show(getSupportFragmentManager(), null);
    }

    public static class LimitedServiceAlertDialogFragment extends DialogFragment {
        private static final String TAG = "LimitedServiceAlertDialog";
        private static final int EVENT_IMS_CAPABILITIES_CHANGED = 1;
        private static final String KEY_PHONE_ID = "key_phone_id";
        private Phone mPhone;
        private int mPhoneId;
        private TelephonyManager mTelephonyManager;
        private Handler mHandler;

        public static LimitedServiceAlertDialogFragment newInstance(int phoneId) {
            LimitedServiceAlertDialogFragment frag = new LimitedServiceAlertDialogFragment();
            Log.i(TAG, "LimitedServiceAlertDialog for phoneId:" + phoneId);
            Bundle args = new Bundle();
            args.putInt(KEY_PHONE_ID, phoneId);
            frag.setArguments(args);
            return frag;
        }

        @Override
        public Dialog onCreateDialog(Bundle bundle) {
            mPhoneId = getArguments().getInt(KEY_PHONE_ID);
            mPhone = PhoneFactory.getPhone(mPhoneId);
            mTelephonyManager = getContext().getSystemService(TelephonyManager.class).
                    createForSubscriptionId(mPhone.getSubId());
            mHandler = new MsgHandler();
            mPhone.getServiceStateTracker().registerForImsCapabilityChanged(mHandler,
                    EVENT_IMS_CAPABILITIES_CHANGED, null);
            if (!SubscriptionManager.isValidPhoneId(mPhoneId)) return null;
            super.onCreateDialog(bundle);
            View dialogView = View.inflate(getActivity(),
                    R.layout.frag_limited_service_alert_dialog, null);
            TextView textView = (TextView) dialogView.findViewById(R.id.message);
            Resources res = getResources();
            String description = String.format(res.getString(
                            R.string.limited_service_alert_dialog_description),
                    mPhone.getServiceStateTracker().getServiceProviderNameOrPlmn().trim());
            textView.setText(description);
            CheckBox alertCheckBox = dialogView.findViewById(R.id.do_not_show);
            SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(
                    mPhone.getContext());
            Log.i(TAG, "onCreateDialog " + Phone.KEY_DO_NOT_SHOW_LIMITED_SERVICE_ALERT +
                    mPhone.getSubId() + ":" + pref.getBoolean
                    (Phone.KEY_DO_NOT_SHOW_LIMITED_SERVICE_ALERT + mPhone.getSubId(), false));

            AlertDialog alertDialog =
                    new AlertDialog.Builder(getActivity(),
                            android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle(R.string.unavailable_emergency_calls_notification_name)
                            .setView(dialogView)
                            .setNegativeButton(
                                    R.string.turn_off_wfc,
                                    (dialog, which) -> onNegativeButtonClicked())
                            .setPositiveButton(
                                    android.R.string.ok,
                                    (dialog, which) -> onPositiveButtonClicked(pref,
                                            alertCheckBox.isChecked()))
                            .create();
            this.setCancelable(false);
            return alertDialog;
        }

        private void onNegativeButtonClicked() {
            Log.d(TAG, "onNegativeButtonClicked");
            SubscriptionManager subscriptionManager = (SubscriptionManager) getContext().
                    getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            int[] subIds = subscriptionManager.getSubscriptionIds(mPhoneId);
            if (subIds != null && subIds.length > 0 && SubscriptionManager.
                    isValidSubscriptionId(subIds[0])) {
                ImsMmTelManager imsMmTelMgr = ImsMmTelManager.
                        createForSubscriptionId(subIds[0]);
                Log.i(TAG, "Disabling WFC setting");
                imsMmTelMgr.setVoWiFiSettingEnabled(false);
            }
            cleanUp();
        }

        private void onPositiveButtonClicked(@NonNull SharedPreferences preferences,
                boolean isChecked) {
            SharedPreferences.Editor editor = preferences.edit();
            editor.putBoolean(Phone.KEY_DO_NOT_SHOW_LIMITED_SERVICE_ALERT + PhoneFactory.
                    getPhone(mPhoneId).getSubId(), isChecked);
            editor.apply();
            Log.i(TAG, "onPositiveButtonClicked isChecked:" + isChecked + " phoneId:" + mPhoneId
                    + " do not show preference:" + preferences.getBoolean
                    (Phone.KEY_DO_NOT_SHOW_LIMITED_SERVICE_ALERT + mPhone.getSubId(), false));
            if (isChecked) {
                NotificationManager sNotificationManager = (NotificationManager) getContext().
                        getSystemService(NOTIFICATION_SERVICE);
                sNotificationManager.cancel(CarrierServiceStateTracker.EMERGENCY_NOTIFICATION_TAG,
                        mPhone.getSubId());
            }
            cleanUp();
        }

        private void cleanUp() {
            mPhone.getServiceStateTracker().unregisterForImsCapabilityChanged(mHandler);
            dismiss();
            getActivity().finish();
        }

        private class MsgHandler extends Handler {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case EVENT_IMS_CAPABILITIES_CHANGED:
                        if (!mTelephonyManager.isWifiCallingAvailable()) {
                            cleanUp();
                        }
                        break;
                }
            }
        }
    }
}
