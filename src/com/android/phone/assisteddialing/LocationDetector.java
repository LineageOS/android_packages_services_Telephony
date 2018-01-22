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

package com.android.phone.assisteddialing;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import java.util.Locale;
import java.util.Optional;

/**
 * LocationDetector is responsible for determining the Roaming location of the User, in addition to
 * User's home country.
 */
final class LocationDetector {

    private final TelephonyManager mTelephonyManager;
    private final String mUserProvidedHomeCountry;
    private static final String LOG_TAG = "LocationDetector";

    LocationDetector(
            @NonNull TelephonyManager telephonyManager, @Nullable String userProvidedHomeCountry) {
        this.mTelephonyManager = telephonyManager;
        this.mUserProvidedHomeCountry = userProvidedHomeCountry;
    }

    /**
     * Returns what we believe to be the User's home country. This should resolve to
     * PROPERTY_ICC_OPERATOR_ISO_COUNTRY
     */
    public Optional<String> getUpperCaseUserHomeCountry() {
        if (mTelephonyManager == null) {
            Log.i(LOG_TAG, "Provided TelephonyManager was null");
            return Optional.empty();
        }


        if (!TextUtils.isEmpty(mUserProvidedHomeCountry)) {
            Log.i(
                    LOG_TAG,
                    "user provided home country code");
            return Optional.of(mUserProvidedHomeCountry.toUpperCase(Locale.US));
        }

        String simCountryIso = mTelephonyManager.getSimCountryIso();
        if (simCountryIso != null) {
            Log.i(LOG_TAG, "using sim country iso");
            return Optional.of(mTelephonyManager.getSimCountryIso().toUpperCase(Locale.US));
        }
        Log.i(LOG_TAG, "user home country was null");
        return Optional.empty();
    }

    /**
     * Returns what we believe to be the User's current (roaming) country
     */
    public Optional<String> getUpperCaseUserRoamingCountry() {
        if (mTelephonyManager == null) {
            Log.i(LOG_TAG, "Provided TelephonyManager was null");
            return Optional.empty();
        }

        String networkCountryIso = mTelephonyManager.getNetworkCountryIso();
        if (networkCountryIso != null) {
            return Optional.of(mTelephonyManager.getNetworkCountryIso().toUpperCase(Locale.US));
        }
        Log.i(LOG_TAG, "user roaming country was null");
        return Optional.empty();
    }
}
