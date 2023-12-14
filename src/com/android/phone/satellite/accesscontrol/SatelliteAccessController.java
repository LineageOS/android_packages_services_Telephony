/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.phone.satellite.accesscontrol;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ResultReceiver;
import android.telephony.Rlog;
import android.telephony.satellite.SatelliteManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.flags.FeatureFlags;

/**
 * This module is responsible for making sure that satellite communication can be used by devices
 * in only regions allowed by OEMs.
 */
public class SatelliteAccessController extends Handler {
    private static final String TAG = "SatelliteAccessController";

    private static final int CMD_IS_SATELLITE_COMMUNICATION_ALLOWED = 1;

    /** Feature flags to control behavior and errors. */
    @NonNull private final FeatureFlags mFeatureFlags;
    @Nullable private final SatelliteOnDeviceAccessController mSatelliteOnDeviceAccessController;

    /**
     * Create a SatelliteAccessController instance.
     *
     * @param featureFlags The FeatureFlags that are supported.
     * @param looper The Looper to run the SatelliteAccessController on.
     * @param satelliteOnDeviceAccessController The location-based satellite restriction lookup.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public SatelliteAccessController(@NonNull FeatureFlags featureFlags, @NonNull Looper looper,
            @Nullable SatelliteOnDeviceAccessController satelliteOnDeviceAccessController) {
        super(looper);
        mFeatureFlags = featureFlags;
        mSatelliteOnDeviceAccessController = satelliteOnDeviceAccessController;
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case CMD_IS_SATELLITE_COMMUNICATION_ALLOWED:
                handleRequestIsSatelliteCommunicationAllowedForCurrentLocation(
                        (ResultReceiver) msg.obj);
                break;
            default:
                logw("SatelliteAccessControllerHandler: unexpected message code: " + msg.what);
                break;
        }
    }

    /**
     * Request to get whether satellite communication is allowed for the current location.
     *
     * @param result The result receiver that returns whether satellite communication is allowed
     *               for the current location if the request is successful or an error code
     *               if the request failed.
     */
    public void requestIsSatelliteCommunicationAllowedForCurrentLocation(
            @NonNull ResultReceiver result) {
        if (!mFeatureFlags.oemEnabledSatelliteFlag()) {
            logd("oemEnabledSatelliteFlag is disabled");
            result.send(SatelliteManager.SATELLITE_RESULT_REQUEST_NOT_SUPPORTED, null);
            return;
        }
        sendRequestAsync(CMD_IS_SATELLITE_COMMUNICATION_ALLOWED, result);
    }

    private void handleRequestIsSatelliteCommunicationAllowedForCurrentLocation(
            @NonNull ResultReceiver result) {
        // To be implemented
    }

    /**
     * Posts the specified command to be executed on the main thread and returns immediately.
     *
     * @param command command to be executed on the main thread
     * @param argument additional parameters required to perform of the operation
     */
    private void sendRequestAsync(int command, @NonNull Object argument) {
        Message msg = this.obtainMessage(command, argument);
        msg.sendToTarget();
    }

    private static void logd(@NonNull String log) {
        Rlog.d(TAG, log);
    }

    private static void logw(@NonNull String log) {
        Rlog.w(TAG, log);
    }

    private static void loge(@NonNull String log) {
        Rlog.e(TAG, log);
    }
}
