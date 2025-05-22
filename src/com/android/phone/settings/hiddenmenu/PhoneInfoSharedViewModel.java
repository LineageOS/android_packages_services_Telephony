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

import android.os.PersistableBundle;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

public class PhoneInfoSharedViewModel extends ViewModel {

    // --- Default Values: Data Network ---
    private static final int DEFAULT_DATA_SIGNAL_STRENGTH = -113;
    private static final boolean DEFAULT_SIMULATE_OUT_OF_SERVICE = false;
    private static final int DEFAULT_SELECTED_NETWORK_MODE_ID_RADIOGROUP = -1;

    // --- Default Values: Satellite ---
    private static final boolean DEFAULT_SATELLITE_ENABLED = false;
    private static final boolean DEFAULT_SATELLITE_DATA_MODE_ENABLED = false;
    private static final int DEFAULT_SATELLITE_DATA_MODE = -1;

    // --- LiveData: Data Network ---
    private final MutableLiveData<Integer>[] mDataSignalStrength =
            new MutableLiveData[] {new MutableLiveData<>(), new MutableLiveData<>()};
    private final MutableLiveData<Integer>[] mDataNetworkTypeDisplay =
            new MutableLiveData[] {new MutableLiveData<>(), new MutableLiveData<>()};
    private final MutableLiveData<Boolean>[] mSimulateOutOfService =
            new MutableLiveData[] {new MutableLiveData<>(), new MutableLiveData<>()};

    // --- LiveData: Satellite ---
    private final MutableLiveData<Boolean>[] mSatelliteEnabled =
            new MutableLiveData[] {new MutableLiveData<>(), new MutableLiveData<>()};
    private final MutableLiveData<Boolean>[] mSatelliteDataEnabled =
            new MutableLiveData[] {new MutableLiveData<>(), new MutableLiveData<>()};
    private final MutableLiveData<Integer>[] mSatelliteDataMode =
            new MutableLiveData[] {new MutableLiveData<>(), new MutableLiveData<>()};
    private final PersistableBundle[] mCarrierSatelliteOriginalBundle = new PersistableBundle[2];
    private final PersistableBundle[] mSatelliteDataOriginalBundle = new PersistableBundle[2];

    public PhoneInfoSharedViewModel() {
        resetToDefaults();
    }

    // --- Getters and Setters: Data Network ---
    public void setDataSignalStrength(int strength, int phoneId) {
        mDataSignalStrength[phoneId].setValue(strength);
    }

    public LiveData<Integer> getDataSignalStrength(int phoneId) {
        return mDataSignalStrength[phoneId];
    }

    public void setDataNetworkTypeDisplay(int type, int phoneId) {
        mDataNetworkTypeDisplay[phoneId].setValue(type);
    }

    public LiveData<Integer> getDataNetworkTypeDisplay(int phoneId) {
        return mDataNetworkTypeDisplay[phoneId];
    }

    public void setSimulateOutOfService(boolean enabled, int phoneId) {
        mSimulateOutOfService[phoneId].setValue(enabled);
    }

    public LiveData<Boolean> getSimulateOutOfService(int phoneId) {
        return mSimulateOutOfService[phoneId];
    }

    // --- Getters and Setters: Satellite ---
    public void setSatelliteEnabled(boolean enabled, int phoneId) {
        mSatelliteEnabled[phoneId].setValue(enabled);
    }

    public LiveData<Boolean> getSatelliteEnabled(int phoneId) {
        return mSatelliteEnabled[phoneId];
    }

    public void setSatelliteEnabledBundle(PersistableBundle bundle, int phoneId) {
        mCarrierSatelliteOriginalBundle[phoneId] = bundle;
    }

    public PersistableBundle getSatelliteEnabledBundle(int phoneId) {
        return mCarrierSatelliteOriginalBundle[phoneId];
    }

    public void setSatelliteDataModeEnabled(boolean enabled, int phoneId) {
        mSatelliteDataEnabled[phoneId].setValue(enabled);
    }

    public LiveData<Boolean> getSatelliteDataEnabled(int phoneId) {
        return mSatelliteDataEnabled[phoneId];
    }

    public void setSatelliteDataMode(int mode, int phoneId) {
        mSatelliteDataMode[phoneId].setValue(mode);
    }

    public LiveData<Integer> getSatelliteDataMode(int phoneId) {
        return mSatelliteDataMode[phoneId];
    }

    public void setSatelliteDataModeBundle(PersistableBundle bundle, int phoneId) {
        mSatelliteDataOriginalBundle[phoneId] = bundle;
    }

    public PersistableBundle getSatelliteDataModeBundle(int phoneId) {
        return mSatelliteDataOriginalBundle[phoneId];
    }

    // --- Reset to Defaults ---
    public void resetToDefaults() {
        // Data Network
        mDataSignalStrength[0].setValue(DEFAULT_DATA_SIGNAL_STRENGTH);
        mDataNetworkTypeDisplay[0].setValue(DEFAULT_SELECTED_NETWORK_MODE_ID_RADIOGROUP);
        mSimulateOutOfService[0].setValue(DEFAULT_SIMULATE_OUT_OF_SERVICE);
        mDataSignalStrength[1].setValue(DEFAULT_DATA_SIGNAL_STRENGTH);
        mDataNetworkTypeDisplay[1].setValue(DEFAULT_SELECTED_NETWORK_MODE_ID_RADIOGROUP);
        mSimulateOutOfService[1].setValue(DEFAULT_SIMULATE_OUT_OF_SERVICE);

        // Satellite
        mSatelliteEnabled[0].setValue(DEFAULT_SATELLITE_ENABLED);
        mSatelliteDataEnabled[0].setValue(DEFAULT_SATELLITE_DATA_MODE_ENABLED);
        mSatelliteDataMode[0].setValue(DEFAULT_SATELLITE_DATA_MODE);
        mSatelliteEnabled[1].setValue(DEFAULT_SATELLITE_ENABLED);
        mSatelliteDataEnabled[1].setValue(DEFAULT_SATELLITE_DATA_MODE_ENABLED);
        mSatelliteDataMode[1].setValue(DEFAULT_SATELLITE_DATA_MODE);
    }
}
