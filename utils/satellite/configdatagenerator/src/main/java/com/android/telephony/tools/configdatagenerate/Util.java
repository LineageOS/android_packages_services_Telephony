/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.telephony.tools.configdatagenerate;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Util {

    public static final int SERVICE_TYPE_INVALID = -1;
    public static final int SERVICE_TYPE_VOICE = 1;
    public static final int SERVICE_TYPE_SMS = 3;
    public static final int SERVICE_TYPE_MMS = 6;

    private static final int FIRST_SERVICE_TYPE = SERVICE_TYPE_VOICE;
    private static final int LAST_SERVICE_TYPE = SERVICE_TYPE_MMS;

    // Referring CarrierConfigManager.java
    public static final int SATELLITE_DATA_SUPPORT_ONLY_RESTRICTED = 0;
    public static final int SATELLITE_DATA_SUPPORT_BANDWIDTH_CONSTRAINED = 1;
    public static final int SATELLITE_DATA_SUPPORT_ALL = 2;

    private static boolean isValidPattern(String input, String regex) {
        if ((input == null) || (regex == null)) {
            return false;
        }
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(input);
        if (!matcher.matches()) {
            return false;
        }
        return true;
    }

    /**
     * @param countryCode two letters country code based on the ISO 3166-1.
     * @return {@code true} if the countryCode is valid {@code false} otherwise.
     */
    public static boolean isValidCountryCode(String countryCode) {
        return isValidPattern(countryCode, "^[A-Za-z]{2}$");
    }

    /**
     * @param plmn target plmn for validation.
     * @return {@code true} if the target plmn is valid {@code false} otherwise.
     */
    public static boolean isValidPlmn(String plmn) {
        return isValidPattern(plmn, "^(?:[0-9]{3})(?:[0-9]{2}|[0-9]{3})$");
    }

    /**
     * @param serviceType target serviceType for validation.
     * @return {@code true} if the target serviceType is valid {@code false} otherwise.
     */
    public static boolean isValidService(int serviceType) {
        if (serviceType < FIRST_SERVICE_TYPE || serviceType > LAST_SERVICE_TYPE) {
            return false;
        }
        return true;
    }

    /**
     * @param maxAllowedDataMode target max allowed data mode for validation.
     * @return {@code true} if the target maxAllowedDataMode is valid {@code false} otherwise.
     */
    public static boolean isValidMaxAllowedDataMode(int maxAllowedDataMode) {
        if (maxAllowedDataMode < SATELLITE_DATA_SUPPORT_ONLY_RESTRICTED
                || maxAllowedDataMode > SATELLITE_DATA_SUPPORT_ALL) {
            return false;
        }
        return true;
    }
}
