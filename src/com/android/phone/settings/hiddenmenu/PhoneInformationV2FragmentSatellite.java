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

import static android.telephony.CarrierConfigManager.KEY_CARRIER_ROAMING_SATELLITE_DEFAULT_SERVICES_INT_ARRAY;
import static android.telephony.CarrierConfigManager.KEY_CARRIER_SUPPORTED_SATELLITE_SERVICES_PER_PROVIDER_BUNDLE;
import static android.telephony.CarrierConfigManager.KEY_SATELLITE_ATTACH_SUPPORTED_BOOL;
import static android.telephony.CarrierConfigManager.KEY_SATELLITE_DATA_SUPPORT_MODE_INT;
import static android.telephony.CarrierConfigManager.KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.telephony.AccessNetworkConstants;
import android.telephony.CarrierConfigManager;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.RadioAccessFamily;
import android.telephony.RadioAccessSpecifier;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.satellite.SatelliteManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.RILConstants;
import com.android.phone.R;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class PhoneInformationV2FragmentSatellite extends Fragment {
    private static final String TAG = "PhoneInformationV2 Satellite";
    private PhoneInfoSharedViewModel mViewModel;
    private PhoneInformationV2PhoneId mListener;
    private Switch mEnforceSatelliteChannel;
    private Switch mMockSatellite;
    private Switch mMockSatelliteDataSwitch;
    private RadioGroup mMockSatelliteData;
    private Button mEsosButton;
    private Button mSatelliteEnableNonEmergencyModeButton;
    private Button mEsosDemoButton;
    private Button mNbIotConfigViewerButton;
    private LinearLayout mPhoneButton0, mPhoneButton1;
    private TextView mPhoneTitle0;
    private TextView mPhoneTitle1;
    private String mActionEsos;
    private String mActionEsosDemo;
    private Intent mNonEsosIntent;
    private int mSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    private int mPhoneId = SubscriptionManager.INVALID_PHONE_INDEX;
    private final PersistableBundle[] mCarrierSatelliteOriginalBundle = new PersistableBundle[2];
    private final PersistableBundle[] mSatelliteDataOriginalBundle = new PersistableBundle[2];
    private final PersistableBundle[] mOriginalSystemChannels = new PersistableBundle[2];
    private Phone mPhone = null;
    private boolean mSystemUser = true;
    private TelephonyManager mTelephonyManager;
    private CarrierConfigManager mCarrierConfigManager;
    private static String[] sPhoneIndexLabels = new String[0];
    private Context mContext;

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

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mViewModel = new ViewModelProvider(requireActivity()).get(PhoneInfoSharedViewModel.class);
        log("onCreate");
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        log("onCreateView");
        View view = inflater.inflate(R.layout.phone_information_v2_tab_satellite, container, false);
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        log("onViewCreated");

        mContext = requireContext();
        mSubId = SubscriptionManager.getDefaultSubscriptionId();
        if (mSystemUser) {
            mPhone = getPhone(SubscriptionManager.getDefaultSubscriptionId());
        }
        if (mPhone != null) {
            mPhoneId = mPhone.getPhoneId();
        } else {
            mPhoneId = SubscriptionManager.getPhoneId(mSubId);
        }
        if (mListener != null) {
            mPhoneId = mListener.getPhoneId();
            mSubId = SubscriptionManager.getSubscriptionId(mPhoneId);
        }
        mTelephonyManager =
                mContext.getSystemService(TelephonyManager.class).createForSubscriptionId(mSubId);

        Resources r = getResources();

        sPhoneIndexLabels = getPhoneIndexLabels(mTelephonyManager);
        mActionEsos =
                r.getString(
                        com.android.internal.R.string
                                .config_satellite_test_with_esp_replies_intent_action);

        mActionEsosDemo =
                r.getString(
                        com.android.internal.R.string.config_satellite_demo_mode_sos_intent_action);

        mPhoneButton0 = view.findViewById(R.id.phone_button_0);
        mPhoneTitle0 = view.findViewById(R.id.phone_button_0_title);

        mPhoneButton1 = view.findViewById(R.id.phone_button_1);
        mPhoneTitle1 = view.findViewById(R.id.phone_button_1_title);

        mPhoneTitle0.setText(sPhoneIndexLabels[0]);
        mPhoneTitle1.setText(sPhoneIndexLabels[1]);

        mMockSatellite = (Switch) view.findViewById(R.id.mock_carrier_roaming_satellite);
        mMockSatelliteDataSwitch =
                (Switch) view.findViewById(R.id.satellite_data_controller_switch);
        mMockSatelliteData = view.findViewById(R.id.satellite_data_controller);
        mEnforceSatelliteChannel = (Switch) view.findViewById(R.id.enforce_satellite_channel);
        if (!Build.isDebuggable()) {
            mMockSatellite.setVisibility(View.GONE);
            mMockSatelliteDataSwitch.setVisibility(View.GONE);
            mMockSatelliteData.setVisibility(View.GONE);
            mEnforceSatelliteChannel.setVisibility(View.GONE);
        }

        mSatelliteEnableNonEmergencyModeButton =
                (Button) view.findViewById(R.id.satellite_enable_non_emergency_mode);
        mNbIotConfigViewerButton = (Button) view.findViewById(R.id.nb_iot_config_viewer);

        mEsosButton = (Button) view.findViewById(R.id.esos_questionnaire);
        mEsosDemoButton = (Button) view.findViewById(R.id.demo_esos_questionnaire);

        if (shouldHideButton(mActionEsos)) {
            mEsosButton.setVisibility(View.GONE);
            log("mActionEsos is not visible");
        } else {
            mEsosButton.setOnClickListener(
                    v ->
                            mContext.startActivityAsUser(
                                    new Intent(mActionEsos)
                                            .addFlags(
                                                    Intent.FLAG_ACTIVITY_NEW_TASK
                                                            | Intent.FLAG_ACTIVITY_CLEAR_TASK),
                                    UserHandle.CURRENT));
            log("mActionEsos is visible");
        }
        if (shouldHideButton(mActionEsosDemo)) {
            mEsosDemoButton.setVisibility(View.GONE);
            log("mActionEsosDemo is gone");
        } else {
            mEsosDemoButton.setOnClickListener(
                    v ->
                            mContext.startActivityAsUser(
                                    new Intent(mActionEsosDemo)
                                            .addFlags(
                                                    Intent.FLAG_ACTIVITY_NEW_TASK
                                                            | Intent.FLAG_ACTIVITY_CLEAR_TASK),
                                    UserHandle.CURRENT));
            log("mActionEsosDemo is visible");
        }
        if (shouldHideNonEmergencyMode()) {
            mSatelliteEnableNonEmergencyModeButton.setVisibility(View.GONE);
        } else {
            mSatelliteEnableNonEmergencyModeButton.setOnClickListener(
                    v -> {
                        if (mNonEsosIntent != null) {
                            mContext.sendBroadcast(mNonEsosIntent);
                        }
                    });
        }

        mNbIotConfigViewerButton.setOnClickListener(
                v -> {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.putExtra("mSubId", mSubId);
                    intent.setClassName(
                            "com.android.phone",
                            "com.android.phone.settings.SatelliteConfigViewer");
                    mContext.startActivityAsUser(intent, UserHandle.CURRENT);
                });

        View.OnClickListener selectionListener =
                clickedView -> {
                    int targetPhoneId = -1;
                    if (clickedView.getId() == R.id.phone_button_0) {
                        targetPhoneId = 0;
                    } else if (clickedView.getId() == R.id.phone_button_1) {
                        targetPhoneId = 1;
                    }

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
                                    updatePhoneIndex(); // Update based on new selection
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
        updateUI();
    }

    private void updateUI() {
        mCarrierSatelliteOriginalBundle[0] = mViewModel.getSatelliteEnabledBundle(0);
        mCarrierSatelliteOriginalBundle[1] = mViewModel.getSatelliteEnabledBundle(1);
        mSatelliteDataOriginalBundle[0] = mViewModel.getSatelliteDataModeBundle(0);
        mSatelliteDataOriginalBundle[1] = mViewModel.getSatelliteDataModeBundle(1);
        mMockSatellite.setChecked(
                Boolean.TRUE.equals(mViewModel.getSatelliteEnabled(mPhoneId).getValue()));
        mMockSatelliteData.setEnabled(
                Boolean.TRUE.equals(mViewModel.getSatelliteDataEnabled(mPhoneId).getValue()));
        setDataModeChangeVisibility(
                Boolean.TRUE.equals(mViewModel.getSatelliteDataEnabled(mPhoneId).getValue()));
        mViewModel.getSatelliteDataMode(mPhoneId);
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

    boolean shouldHideButton(String action) {
        if (!Build.isDebuggable()) {
            return true;
        }
        if (TextUtils.isEmpty(action)) {
            return true;
        }
        PackageManager pm = mContext.getPackageManager();
        Intent intent = new Intent(action);
        if (pm.resolveActivity(intent, 0) == null) {
            return true;
        }
        return false;
    }

    @Override
    public void onResume() {
        super.onResume();
        updateAllFields();
        log("Started onResume");
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mListener = null; // Clean up mListener
    }

    @Override
    public void onDestroy() {
        log("onDestroy");
        super.onDestroy();
    }

    private void updateAllFields() {
        // set phone index
        if (mPhoneId == -1) mPhoneId = 0;
        mSubId = SubscriptionManager.getSubscriptionId(mPhoneId);

        mMockSatellite.setChecked(mCarrierSatelliteOriginalBundle[mPhoneId] != null);
        mMockSatellite.setOnCheckedChangeListener(mMockSatelliteListener);
        mMockSatelliteDataSwitch.setChecked(mSatelliteDataOriginalBundle[mPhoneId] != null);
        mMockSatelliteDataSwitch.setOnCheckedChangeListener(mMockSatelliteDataSwitchListener);
        mMockSatelliteData.setOnCheckedChangeListener(mMockSatelliteDataListener);

        updateSatelliteChannelDisplay(mPhoneId);
        updateSelectionVisuals();
        mEnforceSatelliteChannel.setChecked(mOriginalSystemChannels[mPhoneId] != null);
        mEnforceSatelliteChannel.setOnCheckedChangeListener(mForceSatelliteChannelOnChangeListener);
    }

    @Override
    public void onPause() {
        super.onPause();
        log("onPause: unregister phone & data intents");
    }

    // returns array of string labels for each phone index. The array index is equal to the phone
    // index.
    private static String[] getPhoneIndexLabels(TelephonyManager tm) {
        int phones = tm.getActiveModemCount();
        String[] labels = new String[phones];
        for (int i = 0; i < phones; i++) {
            labels[i] = "Phone " + i;
        }
        return labels;
    }

    private static final int SATELLITE_CHANNEL = 8665;
    private final OnCheckedChangeListener mForceSatelliteChannelOnChangeListener =
            (buttonView, isChecked) -> {
                if (!isValidSubscription(mSubId)) {
                    loge("Force satellite channel invalid subId " + mSubId);
                    return;
                }
                if (getCarrierConfig() == null) {
                    loge("Force satellite channel cm == null");
                    return;
                }
                TelephonyManager tm = mTelephonyManager.createForSubscriptionId(mSubId);
                // To be used in thread in case mPhone changes.
                int subId = mSubId;
                int phoneId = mPhoneId;
                if (isChecked) {
                    (new Thread(() -> {
                        // Override carrier config
                        PersistableBundle originalBundle =
                                getCarrierConfig()
                                        .getConfigForSubId(
                                                subId,
                                                KEY_SATELLITE_ATTACH_SUPPORTED_BOOL,
                                                KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL,
                                                CarrierConfigManager
                                                        .KEY_EMERGENCY_MESSAGING_SUPPORTED_BOOL);
                        PersistableBundle overrideBundle = new PersistableBundle();
                        overrideBundle.putBoolean(
                                KEY_SATELLITE_ATTACH_SUPPORTED_BOOL, true);
                        overrideBundle.putBoolean(
                                KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL, true);
                        overrideBundle.putBoolean(
                                CarrierConfigManager
                                        .KEY_EMERGENCY_MESSAGING_SUPPORTED_BOOL,
                                true);

                        // Set only allow LTE network type
                        try {
                            tm.setAllowedNetworkTypesForReason(
                                    TelephonyManager
                                            .ALLOWED_NETWORK_TYPES_REASON_TEST,
                                    RadioAccessFamily.getRafFromNetworkType(
                                            RILConstants.NETWORK_MODE_LTE_ONLY));
                            log("Force satellite channel set to LTE only");
                        } catch (Exception e) {
                            loge(
                                    "Force satellite channel failed to set network"
                                            + " type to LTE "
                                            + e);
                            return;
                        }

                        // Set force channel selection
                        List<RadioAccessSpecifier> mock =
                                List.of(
                                        new RadioAccessSpecifier(
                                                AccessNetworkConstants
                                                        .AccessNetworkType.EUTRAN,
                                                new int[] {
                                                    AccessNetworkConstants
                                                            .EutranBand.BAND_25
                                                },
                                                new int[] {SATELLITE_CHANNEL}));
                        try {
                            log("Force satellite channel new channels " + mock);
                            tm.setSystemSelectionChannels(mock);
                        } catch (Exception e) {
                            loge(
                                    "Force satellite channel failed to set channels"
                                            + " "
                                            + e);
                            return;
                        }
                        log("Force satellite channel new config " + overrideBundle);
                        getCarrierConfig()
                                .overrideConfig(subId, overrideBundle, false);

                        mOriginalSystemChannels[phoneId] = originalBundle;
                        log("Force satellite channel old " + mock + originalBundle);
                    }))
                            .start();
                } else {
                    (new Thread(() -> {
                        try {
                            tm.setSystemSelectionChannels(
                                    Collections
                                            .emptyList() /* isSpecifyChannels false */);
                            log("Force satellite channel successfully cleared" + " channels ");
                            tm.setAllowedNetworkTypesForReason(
                                    TelephonyManager
                                            .ALLOWED_NETWORK_TYPES_REASON_TEST,
                                    TelephonyManager.getAllNetworkTypesBitmask());
                            log("Force satellite channel successfully reset" + " network type to "
                                    + TelephonyManager.getAllNetworkTypesBitmask());
                            PersistableBundle original = mOriginalSystemChannels[phoneId];
                            if (original != null) {
                                getCarrierConfig().overrideConfig(subId, original, false);
                                log(
                                        "Force satellite channel successfully"
                                                + " restored config to "
                                                + original);
                                mOriginalSystemChannels[phoneId] = null;
                            }
                        } catch (Exception e) {
                            loge("Force satellite channel: Can't clear mock " + e);
                        }
                    }))
                            .start();
                }
            };

    /**
     * This method will do extra check to validate the subId.
     *
     * <p>In case user opens the radioInfo when sim is active and enable some checks and go to the
     * SIM settings screen and disabled the screen. Upon return to radioInfo screen subId is still
     * valid but not in active state any more.
     */
    private boolean isValidSubscription(int subId) {
        boolean isValidSubId = false;
        if (SubscriptionManager.isValidSubscriptionId(subId)) {
            SubscriptionManager mSm = mContext.getSystemService(SubscriptionManager.class);
            isValidSubId = mSm.isActiveSubscriptionId(subId);
        }
        log("isValidSubscription, subId [ " + subId + " ] = " + isValidSubId);
        return isValidSubId;
    }

    private void updateSatelliteChannelDisplay(int phoneId) {
        if (mEnforceSatelliteChannel.isChecked()) return;
        // Assume in testing mode
        (new Thread(() -> {
            TelephonyManager tm =
                    mTelephonyManager.createForSubscriptionId(
                            SubscriptionManager.getSubscriptionId(phoneId));
            try {
                List<RadioAccessSpecifier> channels =
                        tm.getSystemSelectionChannels();
                long networkTypeBitMask =
                        tm.getAllowedNetworkTypesForReason(
                                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_TEST);
                long lteNetworkBitMask =
                        RadioAccessFamily.getRafFromNetworkType(
                                RILConstants.NETWORK_MODE_LTE_ONLY);
                mHandler.post(
                        () -> {
                            log(
                                    "Force satellite get channel "
                                            + channels
                                            + " get networkTypeBitMask "
                                            + networkTypeBitMask
                                            + " lte "
                                            + lteNetworkBitMask);
                            // if SATELLITE_CHANNEL is the current channel
                            mEnforceSatelliteChannel.setChecked(
                                    channels.stream()
                                                    .filter(specifier -> specifier
                                                            .getRadioAccessNetwork()
                                                            == AccessNetworkConstants
                                                            .AccessNetworkType
                                                            .EUTRAN)
                                                    .flatMapToInt(specifier ->
                                                            Arrays.stream(
                                                                    specifier
                                                                            .getChannels()))
                                                    .anyMatch(channel -> channel
                                                            == SATELLITE_CHANNEL)
                                            // OR ALLOWED_NETWORK_TYPES_REASON_TEST
                                            // is LTE only.
                                            || (networkTypeBitMask
                                                            & lteNetworkBitMask)
                                                    == networkTypeBitMask);
                        });
            } catch (Exception e) {
                loge("updateSatelliteChannelDisplay " + e);
            }
        }))
                .start();
    }

    /**
     * Method will create the PersistableBundle and pack the satellite services like SMS, MMS,
     * EMERGENCY CALL, DATA in it.
     *
     * @return PersistableBundle
     */
    public PersistableBundle getSatelliteServicesBundleForOperatorPlmn(
            PersistableBundle originalBundle) {
        String plmn = mTelephonyManager.getNetworkOperatorForPhone(mPhoneId);
        if (TextUtils.isEmpty(plmn)) {
            loge("satData: NetworkOperator PLMN is empty");
            plmn = mTelephonyManager.getSimOperatorNumeric(mSubId);
            loge("satData: SimOperator PLMN = " + plmn);
        }
        int[] supportedServicesArray = {
            NetworkRegistrationInfo.SERVICE_TYPE_DATA,
            NetworkRegistrationInfo.SERVICE_TYPE_SMS,
            NetworkRegistrationInfo.SERVICE_TYPE_EMERGENCY,
            NetworkRegistrationInfo.SERVICE_TYPE_MMS
        };

        PersistableBundle satServicesPerBundle =
                originalBundle.getPersistableBundle(
                        KEY_CARRIER_SUPPORTED_SATELLITE_SERVICES_PER_PROVIDER_BUNDLE);
        // New bundle is required, as existed one will throw `ArrayMap is immutable` when we try
        // to modify.
        PersistableBundle newSatServicesPerBundle = new PersistableBundle();
        // Copy the values from the old bundle into the new bundle.
        boolean hasPlmnKey = false;
        if (satServicesPerBundle != null) {
            for (String key : satServicesPerBundle.keySet()) {
                if (!TextUtils.isEmpty(key) && key.equalsIgnoreCase(plmn)) {
                    newSatServicesPerBundle.putIntArray(plmn, supportedServicesArray);
                    hasPlmnKey = true;
                } else {
                    newSatServicesPerBundle.putIntArray(key, satServicesPerBundle.getIntArray(key));
                }
            }
        }
        if (!hasPlmnKey) {
            newSatServicesPerBundle.putIntArray(plmn, supportedServicesArray);
        }
        log("satData: New SatelliteServicesBundle = " + newSatServicesPerBundle);
        return newSatServicesPerBundle;
    }

    /**
     * This method will check the required carrier config keys which plays role in enabling /
     * supporting satellite data and update the keys accordingly.
     *
     * @param bundleToModify : PersistableBundle
     */
    private void updateCarrierConfigToSupportData(PersistableBundle bundleToModify) {
        // KEY_CARRIER_ROAMING_SATELLITE_DEFAULT_SERVICES_INT_ARRAY key info update
        int[] availableServices =
                bundleToModify.getIntArray(
                        KEY_CARRIER_ROAMING_SATELLITE_DEFAULT_SERVICES_INT_ARRAY);
        int[] newServices;
        if (availableServices != null && availableServices.length > 0) {
            if (Arrays.stream(availableServices)
                    .anyMatch(element -> element == NetworkRegistrationInfo.SERVICE_TYPE_DATA)) {
                newServices = new int[availableServices.length];
                System.arraycopy(availableServices, 0, newServices, 0, availableServices.length);
            } else {
                newServices = new int[availableServices.length + 1];
                System.arraycopy(availableServices, 0, newServices, 0, availableServices.length);
                newServices[newServices.length - 1] = NetworkRegistrationInfo.SERVICE_TYPE_DATA;
            }
        } else {
            newServices = new int[1];
            newServices[0] = NetworkRegistrationInfo.SERVICE_TYPE_DATA;
        }
        bundleToModify.putIntArray(
                KEY_CARRIER_ROAMING_SATELLITE_DEFAULT_SERVICES_INT_ARRAY, newServices);
        // KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL setting to false.
        bundleToModify.putBoolean(KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL, false);
        // Below one not required to update as we are not changing this value.
        bundleToModify.remove(KEY_SATELLITE_DATA_SUPPORT_MODE_INT);
        log("satData: changing carrierConfig to : " + bundleToModify);
        getCarrierConfig().overrideConfig(mSubId, bundleToModify, false);
    }

    /** Method that restore the previous satellite data mode selection. */
    private void updateSatelliteDataButton() {
        if (mSatelliteDataOriginalBundle[mPhoneId] == null) {
            // It executes only at first time
            PersistableBundle originalBundle =
                    getCarrierConfig()
                            .getConfigForSubId(
                                    mSubId,
                                    KEY_SATELLITE_DATA_SUPPORT_MODE_INT,
                                    KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL,
                                    KEY_CARRIER_ROAMING_SATELLITE_DEFAULT_SERVICES_INT_ARRAY,
                                    KEY_CARRIER_SUPPORTED_SATELLITE_SERVICES_PER_PROVIDER_BUNDLE);
            mSatelliteDataOriginalBundle[mPhoneId] = originalBundle;
            mViewModel.setSatelliteDataModeBundle(originalBundle, mPhoneId);
            log("satData: OriginalConfig = " + originalBundle);
        }
        PersistableBundle currentBundle =
                getCarrierConfig()
                        .getConfigForSubId(
                                mSubId,
                                KEY_SATELLITE_DATA_SUPPORT_MODE_INT,
                                KEY_CARRIER_ROAMING_SATELLITE_DEFAULT_SERVICES_INT_ARRAY,
                                KEY_CARRIER_SUPPORTED_SATELLITE_SERVICES_PER_PROVIDER_BUNDLE);
        int dataMode = currentBundle.getInt(KEY_SATELLITE_DATA_SUPPORT_MODE_INT, -1);
        log("satData: present dataMode = " + dataMode);
        if (dataMode != -1) {
            int checkedId = 0;
            switch (dataMode) {
                case CarrierConfigManager.SATELLITE_DATA_SUPPORT_ONLY_RESTRICTED ->
                        checkedId = R.id.satellite_data_restricted;
                case CarrierConfigManager.SATELLITE_DATA_SUPPORT_BANDWIDTH_CONSTRAINED ->
                        checkedId = R.id.satellite_data_constrained;
                case CarrierConfigManager.SATELLITE_DATA_SUPPORT_ALL ->
                        checkedId = R.id.satellite_data_unConstrained;
            }
            mMockSatelliteData.check(checkedId);
        }
        updateCarrierConfigToSupportData(currentBundle);
    }

    private final RadioGroup.OnCheckedChangeListener mMockSatelliteDataListener =
            (group, checkedId) -> {
                int dataMode = CarrierConfigManager.SATELLITE_DATA_SUPPORT_ONLY_RESTRICTED;
                mViewModel.setSatelliteDataMode(checkedId, mPhoneId);
                switch (checkedId) {
                    case R.id.satellite_data_restricted:
                        dataMode = CarrierConfigManager.SATELLITE_DATA_SUPPORT_ONLY_RESTRICTED;
                        break;
                    case R.id.satellite_data_constrained:
                        dataMode =
                                CarrierConfigManager.SATELLITE_DATA_SUPPORT_BANDWIDTH_CONSTRAINED;
                        break;
                    case R.id.satellite_data_unConstrained:
                        dataMode = CarrierConfigManager.SATELLITE_DATA_SUPPORT_ALL;
                        break;
                }
                log("satData: OnCheckedChangeListener setting dataMode = " + dataMode);
                if (getCarrierConfig() == null) return;
                PersistableBundle overrideBundle = new PersistableBundle();
                overrideBundle.putInt(KEY_SATELLITE_DATA_SUPPORT_MODE_INT, dataMode);
                overrideBundle.putBoolean(KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL, false);
                if (isValidSubscription(mSubId)) {
                    getCarrierConfig().overrideConfig(mSubId, overrideBundle, false);
                    log("satData: mMockSatelliteDataListener: Updated new config" + overrideBundle);
                }
            };

    private final OnCheckedChangeListener mMockSatelliteDataSwitchListener =
            (buttonView, isChecked) -> {
                log("satData: ServiceData enabling = " + isChecked);
                mViewModel.setSatelliteDataModeEnabled(isChecked, mPhoneId);
                if (isChecked) {
                    if (isValidOperator(mSubId)) {
                        updateSatelliteDataButton();
                    } else {
                        log("satData: Not a valid Operator");
                        mMockSatelliteDataSwitch.setChecked(false);
                        return;
                    }
                } else {
                    reloadCarrierConfigDefaults();
                }
                setDataModeChangeVisibility(isChecked);
            };

    private void setDataModeChangeVisibility(boolean isChecked) {
        if (isChecked) {
            mMockSatelliteData.setVisibility(View.VISIBLE);
        } else {
            mMockSatelliteData.setVisibility(View.GONE);
        }
    }

    private void reloadCarrierConfigDefaults() {
        if (mSatelliteDataOriginalBundle[mPhoneId] != null) {
            log(
                    "satData: Setting originalCarrierConfig = "
                            + mSatelliteDataOriginalBundle[mPhoneId]);
            getCarrierConfig()
                    .overrideConfig(mSubId, mSatelliteDataOriginalBundle[mPhoneId], false);
            mSatelliteDataOriginalBundle[mPhoneId] = null;
            mViewModel.setSatelliteDataModeBundle(null, mPhoneId);
        }
    }

    private final OnCheckedChangeListener mMockSatelliteListener = (buttonView, isChecked) -> {
        int subId = mSubId;
        int phoneId = mPhoneId;
        if (SubscriptionManager.isValidPhoneId(phoneId) && isValidSubscription(subId)) {
            if (getCarrierConfig() == null) {
                log("mMockSatelliteListener: Carrier config is null");
                return;
            }
            mViewModel.setSatelliteEnabled(isChecked, phoneId);
            if (isChecked) {
                if (!isValidOperator(subId)) {
                    mMockSatellite.setChecked(false);
                    loge("mMockSatelliteListener: Can't mock because no operator for" + " phone "
                            + phoneId);
                    return;
                }
                PersistableBundle originalBundle = getCarrierConfig().getConfigForSubId(
                        subId,
                        KEY_SATELLITE_ATTACH_SUPPORTED_BOOL,
                        KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL,
                        KEY_CARRIER_SUPPORTED_SATELLITE_SERVICES_PER_PROVIDER_BUNDLE);
                mCarrierSatelliteOriginalBundle[phoneId] = originalBundle;
                mViewModel.setSatelliteEnabledBundle(originalBundle, phoneId);

                PersistableBundle overrideBundle = new PersistableBundle();
                overrideBundle.putBoolean(KEY_SATELLITE_ATTACH_SUPPORTED_BOOL, true);
                // NOTE: In case of TMO setting KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL
                // to false will result in SIM Settings not to show few items, which is
                // expected.
                overrideBundle.putBoolean(KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL, false);
                overrideBundle.putPersistableBundle(
                        KEY_CARRIER_SUPPORTED_SATELLITE_SERVICES_PER_PROVIDER_BUNDLE,
                        getSatelliteServicesBundleForOperatorPlmn(originalBundle));
                log("mMockSatelliteListener: old " + originalBundle);
                log("mMockSatelliteListener: new " + overrideBundle);
                getCarrierConfig().overrideConfig(subId, overrideBundle, false);
            } else {
                try {
                    getCarrierConfig().overrideConfig(
                            subId, mCarrierSatelliteOriginalBundle[phoneId], false);
                    mCarrierSatelliteOriginalBundle[phoneId] = null;
                    mViewModel.setSatelliteEnabledBundle(null, phoneId);
                    log(
                            "mMockSatelliteListener: Successfully cleared mock for phone "
                                    + phoneId);
                } catch (Exception e) {
                    loge(
                            "mMockSatelliteListener: Can't clear mock because invalid sub"
                                    + " Id "
                                    + subId
                                    + ", insert SIM and use adb shell cmd phone cc"
                                    + " clear-values");
                    // Keep show toggle ON if the view is not destroyed. If destroyed, must
                    // use cmd to reset, because upon creation the view doesn't remember the
                    // last toggle state while override mock is still in place.
                    mMockSatellite.setChecked(true);
                }
            }
        }
    };

    private boolean isValidOperator(int subId) {
        String operatorNumeric = null;
        if (isValidSubscription(subId)) {
            operatorNumeric = mTelephonyManager.getNetworkOperatorForPhone(mPhoneId);
            TelephonyManager tm;
            if (TextUtils.isEmpty(operatorNumeric)
                    && (tm = mContext.getSystemService(TelephonyManager.class)) != null) {
                operatorNumeric = tm.getSimOperatorNumericForPhone(mPhoneId);
            }
        }
        return !TextUtils.isEmpty(operatorNumeric);
    }

    private Phone getPhone(int subId) {
        log("getPhone subId = " + subId);
        Phone phone = PhoneFactory.getPhone(SubscriptionManager.getPhoneId(subId));
        if (phone == null) {
            log("return the default phone");
            return PhoneFactory.getDefaultPhone();
        }

        return phone;
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

    private final Handler mHandler =
            new Handler(Looper.getMainLooper()) {
                @Override
                public void handleMessage(Message msg) {
                    super.handleMessage(msg);
                }
            };

    private boolean shouldHideNonEmergencyMode() {
        if (!Build.isDebuggable()) {
            return true;
        }
        String action = SatelliteManager.ACTION_SATELLITE_START_NON_EMERGENCY_SESSION;
        if (TextUtils.isEmpty(action)) {
            return true;
        }
        if (mNonEsosIntent != null) {
            mNonEsosIntent = null;
        }
        if (getCarrierConfig() == null) {
            loge("shouldHideNonEmergencyMode: cm is null");
            return true;
        }
        PersistableBundle bundle =
                getCarrierConfig()
                        .getConfigForSubId(
                                mSubId,
                                KEY_SATELLITE_ATTACH_SUPPORTED_BOOL,
                                CarrierConfigManager.KEY_SATELLITE_ESOS_SUPPORTED_BOOL);
        if (!bundle.getBoolean(CarrierConfigManager.KEY_SATELLITE_ESOS_SUPPORTED_BOOL, false)) {
            log("shouldHideNonEmergencyMode: esos_supported false");
            return true;
        }
        if (!bundle.getBoolean(KEY_SATELLITE_ATTACH_SUPPORTED_BOOL, false)) {
            log("shouldHideNonEmergencyMode: attach_supported false");
            return true;
        }

        String packageName =
                getStringFromOverlayConfig(
                        com.android.internal.R.string.config_satellite_gateway_service_package);

        String className =
                getStringFromOverlayConfig(
                        com.android.internal.R.string
                                .config_satellite_carrier_roaming_non_emergency_session_class);
        if (packageName == null
                || className == null
                || packageName.isEmpty()
                || className.isEmpty()) {
            log("shouldHideNonEmergencyMode:" + " packageName or className is null or empty.");
            return true;
        }
        PackageManager pm = mContext.getPackageManager();
        Intent intent = new Intent(action);
        intent.setComponent(new ComponentName(packageName, className));
        if (pm.queryBroadcastReceivers(intent, 0).isEmpty()) {
            log("shouldHideNonEmergencyMode: Broadcast receiver not found for intent: " + intent);
            return true;
        }
        mNonEsosIntent = intent;
        return false;
    }

    private CarrierConfigManager getCarrierConfig() {
        if (mCarrierConfigManager == null) {
            mCarrierConfigManager = mContext.getSystemService(CarrierConfigManager.class);
        }
        return mCarrierConfigManager;
    }

    private String getStringFromOverlayConfig(int resourceId) {
        String name;
        try {
            name = getResources().getString(resourceId);
        } catch (Resources.NotFoundException ex) {
            loge("getStringFromOverlayConfig: ex=" + ex);
            name = null;
        }
        return name;
    }

    private static void log(String s) {
        Log.d(TAG, s);
    }

    private static void loge(String s) {
        Log.e(TAG, s);
    }
}
