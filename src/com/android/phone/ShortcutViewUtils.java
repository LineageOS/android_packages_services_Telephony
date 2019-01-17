/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.phone;

import android.telephony.TelephonyManager;
import android.telephony.emergency.EmergencyNumber;
import android.util.ArrayMap;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class ShortcutViewUtils {
    private static final String LOG_TAG = "ShortcutViewUtils";

    // Emergency services which will be promoted on the shortcut view.
    static final int[] PROMOTED_CATEGORIES = {
            EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_POLICE,
            EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_AMBULANCE,
            EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_FIRE_BRIGADE,
    };

    static final int PROMOTED_CATEGORIES_BITMASK;

    static {
        int bitmask = 0;
        for (int category : PROMOTED_CATEGORIES) {
            bitmask |= category;
        }
        PROMOTED_CATEGORIES_BITMASK = bitmask;
    }

    @NonNull
    static Map<Integer, List<EmergencyNumber>> getPromotedEmergencyNumberLists(
            @NonNull TelephonyManager telephonyManager) {
        Map<Integer, List<EmergencyNumber>> allLists =
                telephonyManager.getCurrentEmergencyNumberList();
        if (allLists == null || allLists.isEmpty()) {
            Log.w(LOG_TAG, "Unable to retrieve emergency number lists!");
            return new ArrayMap<>();
        }

        boolean isDebugLoggable = Log.isLoggable(LOG_TAG, Log.DEBUG);
        Map<Integer, List<EmergencyNumber>> promotedEmergencyNumberLists = new ArrayMap<>();
        for (Map.Entry<Integer, List<EmergencyNumber>> entry : allLists.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            List<EmergencyNumber> emergencyNumberList = entry.getValue();
            if (isDebugLoggable) {
                Log.d(LOG_TAG, "Emergency numbers of " + entry.getKey());
            }

            // The list of promoted emergency numbers which will be visible on shortcut view.
            List<EmergencyNumber> promotedList = new ArrayList<>();
            // A temporary list for non-prioritized emergency numbers.
            List<EmergencyNumber> tempList = new ArrayList<>();

            for (EmergencyNumber emergencyNumber : emergencyNumberList) {
                boolean isPromotedCategory = (emergencyNumber.getEmergencyServiceCategoryBitmask()
                        & PROMOTED_CATEGORIES_BITMASK) != 0;

                // Emergency numbers in DATABASE are prioritized for shortcut view since they were
                // well-categorized.
                boolean isFromPrioritizedSource =
                        (emergencyNumber.getEmergencyNumberSourceBitmask()
                                & EmergencyNumber.EMERGENCY_NUMBER_SOURCE_DATABASE) != 0;
                if (isDebugLoggable) {
                    Log.d(LOG_TAG, "  " + emergencyNumber
                            + (isPromotedCategory ? "M" : "")
                            + (isFromPrioritizedSource ? "P" : ""));
                }

                if (isPromotedCategory) {
                    if (isFromPrioritizedSource) {
                        promotedList.add(emergencyNumber);
                    } else {
                        tempList.add(emergencyNumber);
                    }
                }
            }
            // Puts numbers in temp list after prioritized numbers.
            promotedList.addAll(tempList);

            if (!promotedList.isEmpty()) {
                promotedEmergencyNumberLists.put(entry.getKey(), promotedList);
            }
        }

        if (promotedEmergencyNumberLists.isEmpty()) {
            Log.w(LOG_TAG, "No promoted emergency number found!");
        }
        return promotedEmergencyNumberLists;
    }
}
