package com.android.phone.testapps.satelliteentitlementtestserverapp;
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

import static android.telephony.CarrierConfigManager.ImsServiceEntitlement.KEY_ENTITLEMENT_SERVER_URL_STRING;
import static android.telephony.CarrierConfigManager.KEY_SATELLITE_ATTACH_SUPPORTED_BOOL;
import static android.telephony.CarrierConfigManager.KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL;

import android.content.Context;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.telephony.satellite.NtnSignalStrength;
import android.telephony.satellite.wrapper.SatelliteManagerWrapper;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The class reads the satellite connectivity information and update that info to the
 * SatelliteTestServerActivity activity.
 */

public class SatelliteInfoUiUpdater {
    private static final String TAG = "SatelliteInfoUiUpdater";
    private static final String LOCAL_ENTITLEMENT_SERVER_URL = "http://127.0.0.1:5555";
    private SatelliteManagerWrapper mSatelliteManagerWrapper;
    private SubscriptionManager mSubscriptionManager;
    private CarrierConfigManager mCarrierConfigManager;
    private final Context mContext;
    private PersistableBundle mOriginalBundle;
    private CarrierRoamingNtnModeCallback mNtnModeCallback;
    private TelephonyManager mTelephonyManager;

    public SatelliteInfoUiUpdater(Context context) {
        mContext = context;
    }

    public void updateSatellitePlmnTextView(TextView view) {
        logd("getSatellitePlmnsForCarrier");
        List<String> plmnList = new ArrayList<>();
        try {
            plmnList = getSatelliteManagerWrapper().getSatellitePlmnsForCarrier(getActiveSubId());
            String strPlmnList = String.join(", ", plmnList);
            logd("getSatellitePlmnsForCarrier=" + strPlmnList);
            strPlmnList = "Allowed Plmns : " + strPlmnList;
            view.setText(strPlmnList);
        } catch (SecurityException | IllegalArgumentException ex) {
            String errorMessage = "getSatellitePlmnsForCarrier: " + ex.getMessage();
            logd(errorMessage);
        }
    }

    public void updateNtnStatusTextView(TextView view) {
        boolean isNonTerrestrialNetwork = getSatelliteManagerWrapper().isNonTerrestrialNetwork(
                getActiveSubId());
        logd("isNonTerrestrialNetwork=" + isNonTerrestrialNetwork);
        String ntnStatus = "Is NTN : " + isNonTerrestrialNetwork;
        view.setText(ntnStatus);
        view.setVisibility(View.VISIBLE);
    }

    public void updateCurrentNtnStatusTextView(TextView view) {
        boolean isUsingNonTerrestrialNetwork =
                getSatelliteManagerWrapper().isUsingNonTerrestrialNetwork(getActiveSubId());
        logd("isUsingNonTerrestrialNetwork=" + isUsingNonTerrestrialNetwork);
        String ntnUsingStatus = "Is Using NTN : " + isUsingNonTerrestrialNetwork;
        view.setText(ntnUsingStatus);
        view.setVisibility(View.VISIBLE);
    }

    public void updateAvailableServicesTextView(TextView view) {
        List<Integer> as = getSatelliteManagerWrapper().getAvailableServices(getActiveSubId());
        String serviceList = convertAvailableServicesListToString(as);
        logd("getAvailableServices from WRAPPER =" + serviceList);
        view.setText(serviceList);
        view.setVisibility(View.VISIBLE);
        if (mNtnModeCallback != null && mNtnModeCallback.getAvailableServices() != null) {
            serviceList = mNtnModeCallback.getAvailableServices();
            logd("getAvailableServices from TelephonyCB =" + serviceList);
            view.setText(serviceList);
        }
    }

    public void enableSatelliteEntitlementConfig() {
        if (mOriginalBundle == null) {
            mOriginalBundle = getCarrierConfig().getConfigForSubId(getActiveSubId(),
                    KEY_ENTITLEMENT_SERVER_URL_STRING, KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL,
                    KEY_SATELLITE_ATTACH_SUPPORTED_BOOL);
        }
        logd("Modifying originalConfig = " + mOriginalBundle);
        PersistableBundle bundleToModify = new PersistableBundle();
        bundleToModify.putString(KEY_ENTITLEMENT_SERVER_URL_STRING, LOCAL_ENTITLEMENT_SERVER_URL);
        bundleToModify.putBoolean(KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL, true);
        bundleToModify.putBoolean(KEY_SATELLITE_ATTACH_SUPPORTED_BOOL, true);
        logd("Modifying carrierConfig = " + bundleToModify);
        try {
            getCarrierConfig().overrideConfig(getActiveSubId(), bundleToModify, false);
        } catch (NoSuchMethodError exp) {
            Toast.makeText(mContext, "Manually override carrierConfig", Toast.LENGTH_LONG).show();
            loge("CarrierConfig override exp = " + exp.getMessage());
        }
    }

    public int getActiveSubId() {
        int subId;
        List<SubscriptionInfo> subscriptionInfoList =
                getSubscriptionManager().getActiveSubscriptionInfoList();

        if (subscriptionInfoList != null && !subscriptionInfoList.isEmpty()) {
            subId = subscriptionInfoList.getFirst().getSubscriptionId();
        } else {
            subId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        }
        logd("getActiveSubId() returns " + subId);
        return subId;
    }

    public void updateCurrentPlmnTextView(TextView currentPlmnView) {
        String displayPlmn = "Not Supported";
        try {
            int subId = getActiveSubId();
            TelephonyManager telephonyManager = mContext.getSystemService(
                    TelephonyManager.class).createForSubscriptionId(subId);
            String plmn = telephonyManager.getNetworkOperator();
            logd("displayCurrentPlmn PLMN = " + plmn);
            displayPlmn = mContext.getResources().getString(R.string.current_plmn);
            if (!TextUtils.isEmpty(plmn)) {
                displayPlmn = displayPlmn + plmn;
            }
        } catch (Exception exp) {
            loge("displayCurrentPlmn exception = " + exp.getMessage());
        }
        currentPlmnView.setText(displayPlmn);
    }

    private CarrierConfigManager getCarrierConfig() {
        if (mCarrierConfigManager == null) {
            mCarrierConfigManager = mContext.getSystemService(CarrierConfigManager.class);
        }
        return mCarrierConfigManager;
    }

    private SatelliteManagerWrapper getSatelliteManagerWrapper() {
        if (mSatelliteManagerWrapper == null) {
            mSatelliteManagerWrapper = SatelliteManagerWrapper.getInstance(mContext);
        }
        return mSatelliteManagerWrapper;
    }

    private SubscriptionManager getSubscriptionManager() {
        if (mSubscriptionManager == null) {
            mSubscriptionManager = mContext.getSystemService(SubscriptionManager.class);
        }
        return mSubscriptionManager;
    }

    class CarrierRoamingNtnModeCallback extends TelephonyCallback implements
            TelephonyCallback.CarrierRoamingNtnListener {
        private String mAvailableServices;

        public String getAvailableServices() {
            return mAvailableServices;
        }

        CarrierRoamingNtnModeCallback() {
            logd("CarrierRoamingNtnModeCallback Registered");
        }

        @Override
        public void onCarrierRoamingNtnModeChanged(boolean active) {
            logd("onCarrierRoamingNtnModeChanged = " + active);
        }

        @Override
        public void onCarrierRoamingNtnEligibleStateChanged(boolean eligible) {
            logd("onCarrierRoamingNtnEligibleStateChanged = " + eligible);
            CarrierRoamingNtnListener.super.onCarrierRoamingNtnEligibleStateChanged(eligible);
        }

        @Override
        public void onCarrierRoamingNtnAvailableServicesChanged(int[] availableServices) {
            CarrierRoamingNtnListener.super.onCarrierRoamingNtnAvailableServicesChanged(
                    availableServices);
            List<Integer> as = Arrays.stream(availableServices).boxed().toList();
            mAvailableServices = convertAvailableServicesListToString(as);
            logd("onCarrierRoamingNtnAvailableServicesChanged  = " + mAvailableServices);
        }

        @Override
        public void onCarrierRoamingNtnSignalStrengthChanged(
                @NonNull NtnSignalStrength ntnSignalStrength) {
            logd("onCarrierRoamingNtnSignalStrengthChanged");
            CarrierRoamingNtnListener.super.onCarrierRoamingNtnSignalStrengthChanged(
                    ntnSignalStrength);
        }
    }

    public void registerTelephonyCallback() {
        mTelephonyManager = mContext.getSystemService(TelephonyManager.class);
        mTelephonyManager = mTelephonyManager.createForSubscriptionId(getActiveSubId());
        mNtnModeCallback = new CarrierRoamingNtnModeCallback();
        if (mTelephonyManager != null) {
            mTelephonyManager.registerTelephonyCallback(mContext.getMainExecutor(),
                    mNtnModeCallback);
        }
    }

    public void unRegisterTelephonyCallback() {
        mTelephonyManager.unregisterTelephonyCallback(mNtnModeCallback);
    }

    private static String convertAvailableServicesListToString(List<Integer> ntnServices) {
        String availableServices = ntnServices.stream().map(Object::toString).collect(
                Collectors.joining(", "));
        return "Available Services : " + availableServices;
    }

    private void logd(String message) {
        Log.i(TAG, message);
    }

    private void loge(String message) {
        Log.e(TAG, message);
    }
}
