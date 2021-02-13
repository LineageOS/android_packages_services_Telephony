/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.google.android.sample.rcsclient.util;

import android.content.Context;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;

public class NumberUtils {

    /**
     * Format a number in E164 format.
     * <p>
     * Note: if the number can not be formatted, this method will return null.
     */
    public static String formatNumber(Context context, String number) {
        TelephonyManager manager = context.getSystemService(TelephonyManager.class);
        String simCountryIso = manager.getSimCountryIso().toUpperCase();
        return PhoneNumberUtils.formatNumberToE164(number, simCountryIso);
    }
}
