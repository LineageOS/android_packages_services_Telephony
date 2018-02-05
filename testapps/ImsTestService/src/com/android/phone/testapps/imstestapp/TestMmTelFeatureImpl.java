/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.phone.testapps.imstestapp;

import android.app.PendingIntent;
import android.os.RemoteException;
import android.telephony.ims.feature.MMTelFeature;
import android.telephony.ims.internal.feature.MmTelFeature;
import android.telephony.ims.stub.ImsRegistrationImplBase;
import android.util.Log;
import android.widget.Toast;

import com.android.ims.ImsConfig;
import com.android.ims.internal.IImsConfig;
import com.android.ims.internal.IImsRegistrationListener;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TestMmTelFeatureImpl extends MMTelFeature {

    private final String[] mImsFeatureStrings = {"VoLTE", "ViLTE", "VoWiFi", "ViWiFi",
            "UTLTE", "UTWiFi"};

    public static TestMmTelFeatureImpl sTestMmTelFeatureImpl;
    private boolean mIsReady = false;
    private PendingIntent mIncomingCallIntent;
    private List<IImsRegistrationListener> mRegistrationListeners = new ArrayList<>();
    private TestImsConfigImpl mConfig = new TestImsConfigImpl();

    public TestMmTelFeatureImpl() {
        setFeatureState(STATE_READY);
    }

    public static TestMmTelFeatureImpl getInstance() {
        if (sTestMmTelFeatureImpl == null) {
            sTestMmTelFeatureImpl = new TestMmTelFeatureImpl();
        }
        return sTestMmTelFeatureImpl;
    }

    public boolean isReady() {
        return mIsReady;
    }

    public void sendCapabilitiesUpdate(MmTelFeature.MmTelCapabilities c) {
        // Size of ImsConfig.FeatureConstants
        int[] enabledCapabilities = new int[6];
        // Apparently means disabled...?
        Arrays.fill(enabledCapabilities, ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN);
        int[] disabledCapabilities = new int[6];
        Arrays.fill(disabledCapabilities, ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN);
        int radioTech = TestImsRegistrationImpl.getInstance().getConnectionType();
        StringBuilder sb = new StringBuilder(120);
        // populate enabledCapabilities
        switch (radioTech) {
            case ImsRegistrationImplBase.REGISTRATION_TECH_LTE: {
                if (c.isCapable(MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VOICE)) {
                    // enabled means equal to its own integer value.
                    enabledCapabilities[ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE] =
                            ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE;
                    sb.append(mImsFeatureStrings[
                            ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE]);
                    sb.append(" ");
                }
                if (c.isCapable(MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VIDEO)) {
                    enabledCapabilities[ImsConfig.FeatureConstants.FEATURE_TYPE_VIDEO_OVER_LTE] =
                            ImsConfig.FeatureConstants.FEATURE_TYPE_VIDEO_OVER_LTE;
                    sb.append(mImsFeatureStrings[
                            ImsConfig.FeatureConstants.FEATURE_TYPE_VIDEO_OVER_LTE]);
                    sb.append(" ");
                }
                if (c.isCapable(MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_UT)) {
                    enabledCapabilities[ImsConfig.FeatureConstants.FEATURE_TYPE_UT_OVER_LTE] =
                            ImsConfig.FeatureConstants.FEATURE_TYPE_UT_OVER_LTE;
                    sb.append(mImsFeatureStrings[
                            ImsConfig.FeatureConstants.FEATURE_TYPE_UT_OVER_LTE]);
                }
                break;
            }
            case ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN: {
                if (c.isCapable(MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VOICE)) {
                    enabledCapabilities[ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI] =
                            ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI;
                    sb.append(mImsFeatureStrings[
                            ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI]);
                    sb.append(" ");
                }
                if (c.isCapable(MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VIDEO)) {
                    enabledCapabilities[ImsConfig.FeatureConstants.FEATURE_TYPE_VIDEO_OVER_WIFI] =
                            ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI;
                    sb.append(mImsFeatureStrings[
                            ImsConfig.FeatureConstants.FEATURE_TYPE_VIDEO_OVER_WIFI]);
                    sb.append(" ");
                }
                if (c.isCapable(MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_UT)) {
                    enabledCapabilities[ImsConfig.FeatureConstants.FEATURE_TYPE_UT_OVER_WIFI] =
                            ImsConfig.FeatureConstants.FEATURE_TYPE_UT_OVER_WIFI;
                    sb.append(mImsFeatureStrings[
                            ImsConfig.FeatureConstants.FEATURE_TYPE_UT_OVER_WIFI]);
                }
                break;
            }
        }
        // make disabledCapabilities  the opposite of enabledCapabilities
        for (int i = 0; i < enabledCapabilities.length; i++) {
            if (enabledCapabilities[i] != i) {
                disabledCapabilities[i] = i;
            }
        }
        Toast.makeText(mContext, "Sending Capabilities:{" + sb.toString() + "}",
                Toast.LENGTH_LONG).show();
        mRegistrationListeners.forEach((l) -> {
            try {
                l.registrationFeatureCapabilityChanged(1 /*ImsServiceClass.MMTel*/,
                        enabledCapabilities,
                        disabledCapabilities);
            } catch (RemoteException e) {
                // ignore
            }
        });
    }

    @Override
    public int startSession(PendingIntent incomingCallIntent, IImsRegistrationListener listener) {
        Log.i(TestImsService.LOG_TAG,
                "startSession Called: pi=" + incomingCallIntent + " listener: " + listener);
        mIncomingCallIntent = incomingCallIntent;
        mRegistrationListeners.add(listener);
        return super.startSession(incomingCallIntent, listener);
    }

    @Override
    public void addRegistrationListener(IImsRegistrationListener listener) {
        Log.i(TestImsService.LOG_TAG, "addRegistrationListener: " + listener);
        mRegistrationListeners.add(listener);
    }

    @Override
    public IImsConfig getConfigInterface() {
        return mConfig;
    }

    @Override
    public void onFeatureReady() {
        mIsReady = true;
    }
}
