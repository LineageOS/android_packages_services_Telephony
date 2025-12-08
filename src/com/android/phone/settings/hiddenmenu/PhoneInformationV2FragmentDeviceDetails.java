/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.phone.settings.hiddenmenu;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ComponentInfo;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.euicc.EuiccConnector;
import com.android.phone.R;

import java.util.Locale;

public class PhoneInformationV2FragmentDeviceDetails extends Fragment {
    private static final String TAG = "PhoneInformationV2 DeviceDetails";
    private PhoneInformationV2PhoneId mListener;
    private static final boolean IS_USER_BUILD = "user".equals(Build.TYPE);
    private Context mContext;
    private LinearLayout mPhoneButton0, mPhoneButton1;
    private TextView mPhoneTitle0;
    private TextView mPhoneTitle1;
    private TextView mDeviceId; // DeviceId is the IMEI in GSM and the MEID in CDMA
    private TextView mLine1Number;
    private TextView mSubscriptionId;
    private TextView mDds;
    private TextView mSubscriberId;
    private Switch mDsdsSwitch;
    private static final String ACTION_REMOVABLE_ESIM_AS_DEFAULT =
            "android.telephony.euicc.action.REMOVABLE_ESIM_AS_DEFAULT";
    private static final String DSDS_MODE_PROPERTY = "ro.boot.hardware.dsds";
    private static final int ALWAYS_ON_DSDS_MODE = 1;
    private Switch mRemovableEsimSwitch;
    private TelephonyManager mTelephonyManager;
    private Phone mPhone = null;

    private boolean mSystemUser = true;
    private int mPhoneId = SubscriptionManager.INVALID_PHONE_INDEX;
    private static final int DEFAULT_PHONE_ID = 0;

    private int mSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    private static String[] sPhoneIndexLabels = new String[0];

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        // Ensure the host activity implements the callback interface
        if (context instanceof PhoneInformationV2PhoneId) {
            mListener = (PhoneInformationV2PhoneId) context;
        } else {
            throw new RuntimeException(context.toString() + " must implement SharedValueListener");
        }
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view =
                inflater.inflate(
                        R.layout.phone_information_v2_tab_device_details, container, false);
        return view;
    }

    private static void log(String s) {
        Log.d(TAG, s);
    }

    @Override
    public void onResume() {
        super.onResume();

        log("Started onResume");

        updateAllFields();
    }

    private void updateAllFields() {
        updateProperties();
        updateSelectionVisuals();
        updateSubscriptionIds();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        log("onCreate");
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        log("onViewCreated");
        mContext = requireContext();

        mSystemUser = android.os.Process.myUserHandle().isSystem();
        log("onCreate: mSystemUser=" + mSystemUser);

        if (mSystemUser) {
            mPhone = PhoneInformationUtil.getPhone(SubscriptionManager.getDefaultSubscriptionId());
        }
        mSubId = SubscriptionManager.getDefaultSubscriptionId();
        if (mPhone != null) {
            mPhoneId = mPhone.getPhoneId();
        } else {
            mPhoneId = SubscriptionManager.getPhoneId(mSubId);
        }
        if (!SubscriptionManager.isValidPhoneId(mPhoneId)) {
            mPhoneId = DEFAULT_PHONE_ID;
        }
        if (mListener != null) {
            mPhoneId = mListener.getPhoneId();
            mSubId = SubscriptionManager.getSubscriptionId(mPhoneId);
        }
        mTelephonyManager =
                mContext.getSystemService(TelephonyManager.class).createForSubscriptionId(mSubId);

        sPhoneIndexLabels = PhoneInformationUtil.getPhoneIndexLabels(mTelephonyManager);

        mPhoneButton0 = view.findViewById(R.id.phone_button_0);
        mPhoneTitle0 = view.findViewById(R.id.phone_button_0_title);

        mPhoneButton1 = view.findViewById(R.id.phone_button_1);
        mPhoneTitle1 = view.findViewById(R.id.phone_button_1_title);

        PhoneInformationUtil.configurePhoneSelectionUi(mPhoneButton0, mPhoneButton1, mPhoneTitle0,
                mPhoneTitle1, sPhoneIndexLabels);

        mDeviceId = (TextView) view.findViewById(R.id.imei);
        mLine1Number = (TextView) view.findViewById(R.id.number);
        mSubscriptionId = (TextView) view.findViewById(R.id.subid);
        mDds = (TextView) view.findViewById(R.id.dds);
        mSubscriberId = (TextView) view.findViewById(R.id.imsi);
        mRemovableEsimSwitch = (Switch) view.findViewById(R.id.removable_esim_switch);
        if (!IS_USER_BUILD) {
            mRemovableEsimSwitch.setEnabled(true);
            mRemovableEsimSwitch.setChecked(mTelephonyManager.isRemovableEsimDefaultEuicc());
            mRemovableEsimSwitch.setOnCheckedChangeListener(mRemovableEsimChangeListener);
        }
        mDsdsSwitch = (Switch) view.findViewById(R.id.dsds_switch);
        if (PhoneInformationUtil.isDsdsSupported() && !PhoneInformationUtil.dsdsModeOnly()) {
            mDsdsSwitch.setVisibility(View.VISIBLE);
            mDsdsSwitch.setOnClickListener(
                    v -> {
                        if (mTelephonyManager.doesSwitchMultiSimConfigTriggerReboot()) {
                            // Undo the click action until user clicks the confirm dialog.
                            mDsdsSwitch.toggle();
                            showDsdsChangeDialog();
                        } else {
                            performDsdsSwitch();
                        }
                    });
            mDsdsSwitch.setChecked(PhoneInformationUtil.isDsdsEnabled());
        } else {
            mDsdsSwitch.setVisibility(View.GONE);
        }

        View.OnClickListener selectionListener =
                clickedView -> {
                    int targetPhoneId = -1;
                    if (clickedView.getId() == R.id.phone_button_0) {
                        targetPhoneId = 0;
                    } else if (clickedView.getId() == R.id.phone_button_1) {
                        targetPhoneId = 1;
                    }

                    log("targetPhoneId: " + targetPhoneId + ", mPhoneId: " + mPhoneId);

                    if (targetPhoneId != -1) {
                        // Validation
                        if (mTelephonyManager.getActiveModemCount() > targetPhoneId) {
                            // Check if selection actually changed to avoid redundant work
                            if (mPhoneId != targetPhoneId) {
                                mPhoneId = targetPhoneId;
                                mListener.setPhoneId(mPhoneId);
                                try {
                                    mSubId = SubscriptionManager.getSubscriptionId(mPhoneId);
                                    log(
                                            "Updated phone id to "
                                                    + mPhoneId
                                                    + ", sub id to "
                                                    + mSubId);
                                    updatePhoneIndex();
                                } catch (SecurityException e) {
                                    log("Permission error getting subId for phoneId " + mPhoneId);
                                }
                            }
                        } else {
                            log(
                                    "Selected phone index "
                                            + targetPhoneId
                                            + " is not active/available.");
                        }
                    }
                };
        mPhoneButton0.setOnClickListener(selectionListener);
        mPhoneButton1.setOnClickListener(selectionListener);
    }

    private void updatePhoneIndex() {
        log("updatePhoneIndex");
        // update the subId
        mTelephonyManager = mTelephonyManager.createForSubscriptionId(mSubId);

        // update the phoneId
        if (mSystemUser) {
            mPhone = PhoneFactory.getPhone(mPhoneId);
        }

        updateAllFields();
    }

    private void updateSelectionVisuals() {
        LinearLayout selectedButton, unSelectedButton;
        if (mPhoneId == 0) {
            selectedButton = mPhoneButton0;
            unSelectedButton = mPhoneButton1;
        } else {
            selectedButton = mPhoneButton1;
            unSelectedButton = mPhoneButton0;
        }
        selectedButton.setBackgroundColor(
                ContextCompat.getColor(mContext, android.R.color.holo_green_dark));
        unSelectedButton.setBackgroundColor(
                ContextCompat.getColor(mContext, android.R.color.darker_gray));
    }

    private void showDsdsChangeDialog() {
        final AlertDialog confirmDialog =
                new AlertDialog.Builder(mContext)
                        .setTitle(R.string.dsds_dialog_title)
                        .setMessage(R.string.dsds_dialog_message)
                        .setPositiveButton(
                                R.string.dsds_dialog_confirm, mOnDsdsDialogConfirmedListener)
                        .setNegativeButton(
                                R.string.dsds_dialog_cancel, mOnDsdsDialogConfirmedListener)
                        .create();
        confirmDialog.show();
    }

    private void performDsdsSwitch() {
        mTelephonyManager.switchMultiSimConfig(mDsdsSwitch.isChecked() ? 2 : 1);
    }

    DialogInterface.OnClickListener mOnDsdsDialogConfirmedListener =
            new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (which == DialogInterface.BUTTON_POSITIVE) {
                        mDsdsSwitch.toggle();
                        performDsdsSwitch();
                    }
                }
            };

    OnCheckedChangeListener mRemovableEsimChangeListener =
            (buttonView, isChecked) -> setRemovableEsimAsDefaultEuicc(isChecked);

    private void setRemovableEsimAsDefaultEuicc(boolean isChecked) {
        Log.d(TAG, "setRemovableEsimAsDefaultEuicc isChecked: " + isChecked);
        mTelephonyManager.setRemovableEsimAsDefaultEuicc(isChecked);
        // TODO(b/232528117): Instead of sending intent, add new APIs in platform,
        //  LPA can directly use the API.
        ComponentInfo componentInfo =
                EuiccConnector.findBestComponent(mContext.getPackageManager());
        if (componentInfo == null) {
            Log.d(TAG, "setRemovableEsimAsDefaultEuicc: unable to find suitable component info");
            return;
        }
        final Intent intent = new Intent(ACTION_REMOVABLE_ESIM_AS_DEFAULT);
        intent.setPackage(componentInfo.packageName);
        intent.putExtra("isDefault", isChecked);
        mContext.sendBroadcast(intent);
    }

    private void updateProperties() {
        Resources r = getResources();

        String deviceId = mTelephonyManager.getImei(mPhoneId);
        mDeviceId.setText(deviceId);

        String subscriberId = mTelephonyManager.getSubscriberId();
        if (subscriberId == null || !SubscriptionManager.isValidSubscriptionId(mSubId)) {
            subscriberId = r.getString(R.string.radioInfo_unknown);
        }

        mSubscriberId.setText(subscriberId);

        SubscriptionManager subMgr = mContext.getSystemService(SubscriptionManager.class);
        int subId = mSubId;
        String number =
                subMgr.getPhoneNumber(subId)
                        + " { CARRIER:"
                        + subMgr.getPhoneNumber(
                                subId, SubscriptionManager.PHONE_NUMBER_SOURCE_CARRIER)
                        + ", UICC:"
                        + subMgr.getPhoneNumber(subId, SubscriptionManager.PHONE_NUMBER_SOURCE_UICC)
                        + ", IMS:"
                        + subMgr.getPhoneNumber(subId, SubscriptionManager.PHONE_NUMBER_SOURCE_IMS)
                        + " }";
        mLine1Number.setText(number);
    }

    private void updateSubscriptionIds() {
        mSubscriptionId.setText(String.format(Locale.ROOT, "%d", mSubId));
        mDds.setText(Integer.toString(SubscriptionManager.getDefaultDataSubscriptionId()));
    }
}
