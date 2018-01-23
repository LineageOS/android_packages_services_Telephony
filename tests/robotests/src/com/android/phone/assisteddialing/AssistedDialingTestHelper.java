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

/**
 * Common variables for Assisted Dialing Unit Tests.
 */
class AssistedDialingTestHelper {

    public static final String INVALID_NUMBER_ENCODING = "\u2026";

    public static final String INTERNATIONAL_TEST_NUMBER_UNITED_STATES = "+1 123-456-7890";

    public static final String INVALID_TEST_NUMBER_UNITED_STATES = "3-456-7890";

    public static final String TEST_NUMBER_UNITED_STATES = "650-456-7890";
    public static final String TEST_NUMBER_UNITED_STATES_WITH_POST_DIAL_EXTENSION =
            "650-456-7890;123";
    public static final String TEST_NUMBER_UNITED_KINGDOM = "0113-169-0455";
    public static final String TEST_NUMBER_JAPAN = "0570-055777";
    public static final String TEST_NUMBER_MEXICO = "55 4155 0000";
    public static final String TEST_NUMBER_CANADA = "604-659-3400";

    public static final String TEST_NUMBER_EMERGENCY_UNITED_STATES = "911";
    public static final String TEST_NUMBER_EMERGENCY_UNITED_KINGDOM = "999";
    public static final String TEST_NUMBER_EMERGENCY_JAPAN = "110";
    public static final String TEST_NUMBER_EMERGENCY_MEXICO = "911";
    public static final String TEST_NUMBER_EMERGENCY_CANADA = "911";

    public static final String UNSUPPORTED_COUNTRY_CODE_NORTH_KOREA = "KP";

    public static final String SUPPORTED_COUNTRY_CODE_CANADA = "CA";
    public static final String SUPPORTED_COUNTRY_CODE_UNITED_KINGDOM = "GB";
    public static final String SUPPORTED_COUNTRY_CODE_JAPAN = "JP";
    public static final String SUPPORTED_COUNTRY_CODE_MEXICO = "MX";
    public static final String SUPPORTED_COUNTRY_CODE_UNITED_STATES = "US";

    public static final String EXPECTED_FROM_UK_TO_US_DIALABLE_NUMBER = "+1 650-456-7890";
    public static final String EXPECTED_FROM_US_TO_UK_DIALABLE_NUMBER = "+44 113 169 0455";
    public static final String EXPECTED_FROM_CA_TO_JP_DIALABLE_NUMBER = "+81 570-055-777";
    public static final String EXPECTED_FROM_JP_TO_MX_DIALABLE_NUMBER = "+52 55 4155 0000";
    public static final String EXPECTED_FROM_US_TO_JP_DIALABLE_NUMBER = "+81 570-055-777";

    public static final int UNITED_STATES_COUNTRY_CALLING_CODE = 1;
}
