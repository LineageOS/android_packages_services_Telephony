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

import android.telephony.satellite.stub.SatelliteError;
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
            case SatelliteError.ERROR_NONE:
                return "SATELLITE_ERROR_NONE";
            case SatelliteError.SATELLITE_ERROR:
                return "SATELLITE_ERROR";
            case SatelliteError.SERVER_ERROR:
                return "SATELLITE_SERVER_ERROR";
            case SatelliteError.SERVICE_ERROR:
                return "SATELLITE_SERVICE_ERROR";
            case SatelliteError.MODEM_ERROR:
                return "SATELLITE_MODEM_ERROR";
            case SatelliteError.NETWORK_ERROR:
                return "SATELLITE_NETWORK_ERROR";
            case SatelliteError.INVALID_TELEPHONY_STATE:
                return "SATELLITE_INVALID_TELEPHONY_STATE";
            case SatelliteError.INVALID_MODEM_STATE:
                return "SATELLITE_INVALID_MODEM_STATE";
            case SatelliteError.INVALID_ARGUMENTS:
                return "SATELLITE_INVALID_ARGUMENTS";
            case SatelliteError.REQUEST_FAILED:
                return "SATELLITE_REQUEST_FAILED";
            case SatelliteError.RADIO_NOT_AVAILABLE:
                return "SATELLITE_RADIO_NOT_AVAILABLE";
            case SatelliteError.REQUEST_NOT_SUPPORTED:
                return "SATELLITE_REQUEST_NOT_SUPPORTED";
            case SatelliteError.NO_RESOURCES:
                return "SATELLITE_NO_RESOURCES";
            case SatelliteError.SERVICE_NOT_PROVISIONED:
                return "SATELLITE_SERVICE_NOT_PROVISIONED";
            case SatelliteError.SERVICE_PROVISION_IN_PROGRESS:
                return "SATELLITE_SERVICE_PROVISION_IN_PROGRESS";
            case SatelliteError.REQUEST_ABORTED:
                return "SATELLITE_REQUEST_ABORTED";
            case SatelliteError.SATELLITE_ACCESS_BARRED:
                return "SATELLITE_ACCESS_BARRED";
            case SatelliteError.NETWORK_TIMEOUT:
                return "SATELLITE_NETWORK_TIMEOUT";
            case SatelliteError.SATELLITE_NOT_REACHABLE:
                return "SATELLITE_NOT_REACHABLE";
            case SatelliteError.NOT_AUTHORIZED:
                return "SATELLITE_NOT_AUTHORIZED";
        }
        Log.d(TAG, "Received invalid satellite service error: " + error);
        return "SATELLITE_SERVICE_ERROR";
    }
}
