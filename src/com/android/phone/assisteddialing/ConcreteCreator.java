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

import android.content.Context;
import android.os.UserManager;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.telephony.TelephonyManager;
import android.util.Log;

/**
 * A Creator for AssistedDialingMediators.
 * <p>
 * <p>This helps keep the dependencies required by AssistedDialingMediator for assisted dialing
 * explicit.
 */
public final class ConcreteCreator {

    private static final String LOG_TAG = "ConcreteCreator";

    /**
     * Creates a new AssistedDialingMediator
     *
     * @param telephonyManager The telephony manager used to determine user location.
     * @param context          The context used to determine whether or not a provided number is an
     *                        emergency
     *                         number.
     * @return An AssistedDialingMediator
     */
    public static AssistedDialingMediator createNewAssistedDialingMediator(
            @NonNull TelephonyManager telephonyManager, @NonNull Context context) {

        if (telephonyManager == null) {
            Log.i(
                    LOG_TAG,
                    "provided TelephonyManager was null");
            return new AssistedDialingMediatorStub();
        }
        if (context == null) {
            Log.i(LOG_TAG, "provided context was null");
            return new AssistedDialingMediatorStub();
        }

        UserManager userManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
        if (!userManager.isUserUnlocked()) {
            // To avoid any issues reading preferences, we disable the feature when the user is in a
            // locked state.
            Log.i(LOG_TAG, "user is locked");
            return new AssistedDialingMediatorStub();
        }

        if (!PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(context.getString(
                        R.string.assisted_dialing_setting_toggle_key), true)) {
            Log.i(LOG_TAG, "disabled by local setting");

            return new AssistedDialingMediatorStub();
        }

        Constraints constraints = new Constraints(context, getCountryCodeProvider());
        return new AssistedDialingMediatorImpl(
                new LocationDetector(
                        telephonyManager,
                        PreferenceManager.getDefaultSharedPreferences(context)
                                .getString(context.getString(
                                        R.string.assisted_dialing_setting_cc_key), null)),
                new NumberTransformer(constraints));
    }

    /**
     * Returns a CountryCodeProvider responsible for providing countries eligible for
     * assisted Dialing
     */
    public static CountryCodeProvider getCountryCodeProvider() {
        return new CountryCodeProvider();
    }
}
