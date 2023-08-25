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

package com.android.phone.testapps.satellitetestapp;

import android.telephony.satellite.stub.SatelliteResult;
import android.util.Log;

/**
 * Utils class for satellite error to display as string in SatelliteTestApp UI.
 */
public class SatelliteErrorUtils {
    private static final String TAG = "SatelliteErrorUtils";

   /**
     * @param error int from the satellite manager.
     * @return The converted SatelliteError for the testapp, result of the operation.
     */
    public static String mapError(int error) {
        switch (error) {
            case SatelliteResult.SATELLITE_RESULT_SUCCESS:
                return "SATELLITE_RESULT_SUCCESS";
            case SatelliteResult.SATELLITE_RESULT_ERROR:
                return "SATELLITE_RESULT_ERROR";
            case SatelliteResult.SATELLITE_RESULT_SERVER_ERROR:
                return "SATELLITE_RESULT_SERVER_ERROR";
            case SatelliteResult.SATELLITE_RESULT_SERVICE_ERROR:
                return "SATELLITE_RESULT_SERVICE_ERROR";
            case SatelliteResult.SATELLITE_RESULT_MODEM_ERROR:
                return "SATELLITE_RESULT_MODEM_ERROR";
            case SatelliteResult.SATELLITE_RESULT_NETWORK_ERROR:
                return "SATELLITE_RESULT_NETWORK_ERROR";
            case SatelliteResult.SATELLITE_RESULT_INVALID_MODEM_STATE:
                return "SATELLITE_RESULT_INVALID_MODEM_STATE";
            case SatelliteResult.SATELLITE_RESULT_INVALID_ARGUMENTS:
                return "SATELLITE_RESULT_INVALID_ARGUMENTS";
            case SatelliteResult.SATELLITE_RESULT_REQUEST_FAILED:
                return "SATELLITE_RESULT_REQUEST_FAILED";
            case SatelliteResult.SATELLITE_RESULT_RADIO_NOT_AVAILABLE:
                return "SATELLITE_RESULT_RADIO_NOT_AVAILABLE";
            case SatelliteResult.SATELLITE_RESULT_REQUEST_NOT_SUPPORTED:
                return "SATELLITE_RESULT_REQUEST_NOT_SUPPORTED";
            case SatelliteResult.SATELLITE_RESULT_NO_RESOURCES:
                return "SATELLITE_RESULT_NO_RESOURCES";
            case SatelliteResult.SATELLITE_RESULT_SERVICE_NOT_PROVISIONED:
                return "SATELLITE_RESULT_SERVICE_NOT_PROVISIONED";
            case SatelliteResult.SATELLITE_RESULT_SERVICE_PROVISION_IN_PROGRESS:
                return "SATELLITE_RESULT_SERVICE_PROVISION_IN_PROGRESS";
            case SatelliteResult.SATELLITE_RESULT_REQUEST_ABORTED:
                return "SATELLITE_RESULT_REQUEST_ABORTED";
            case SatelliteResult.SATELLITE_RESULT_ACCESS_BARRED:
                return "SATELLITE_RESULT_ACCESS_BARRED";
            case SatelliteResult.SATELLITE_RESULT_NETWORK_TIMEOUT:
                return "SATELLITE_RESULT_NETWORK_TIMEOUT";
            case SatelliteResult.SATELLITE_RESULT_NOT_REACHABLE:
                return "SATELLITE_RESULT_NOT_REACHABLE";
            case SatelliteResult.SATELLITE_RESULT_NOT_AUTHORIZED:
                return "SATELLITE_RESULT_NOT_AUTHORIZED";
        }
        Log.d(TAG, "Received invalid satellite service error: " + error);
        return "SATELLITE_RESULT_SERVICE_ERROR";
    }
}
