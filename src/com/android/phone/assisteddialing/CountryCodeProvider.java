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

import android.support.annotation.VisibleForTesting;

import java.util.Arrays;
import java.util.List;

/**
 * A class to provide the appropriate country codes related to assisted dialing.
 */
public final class CountryCodeProvider {

    // ISO 3166-1 alpha-2 Country Codes that are eligible for assisted dialing.
    @VisibleForTesting
    static final List<String> DEFAULT_COUNTRY_CODES =
            Arrays.asList(
                    "CA" /* Canada */,
                    "GB" /* United Kingdom */,
                    "JP" /* Japan */,
                    "MX" /* Mexico */,
                    "US" /* United States */);

    /**
     * Checks whether a supplied country code is supported.
     */
    public boolean isSupportedCountryCode(String countryCode) {
        return DEFAULT_COUNTRY_CODES.contains(countryCode);
    }
}
