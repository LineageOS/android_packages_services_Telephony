/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.phone.ecc;

import android.content.Context;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Provides a mapping table from country ISO to ECC info. The data is stored in Protocol Buffers
 * binary format, compressed with GZIP.
 */
public class IsoToEccProtobufRepository implements IsoToEccRepository {
    private static final String LOG_TAG = "EccRepository";

    private static IsoToEccProtobufRepository sInstance;

    /**
     * Returns the singleton instance of IsoToEccProtobufRepository
     */
    public static synchronized IsoToEccProtobufRepository getInstance() {
        if (sInstance == null) {
            sInstance = new IsoToEccProtobufRepository();
        }
        return sInstance;
    }

    private final Map<String, CountryEccInfo> mEccTable = new HashMap<>();

    private IsoToEccProtobufRepository() {
    }

    @Override
    @Nullable
    public CountryEccInfo getCountryEccInfo(@NonNull Context context, String iso)
            throws IOException {
        if (TextUtils.isEmpty(iso)) {
            return null;
        }

        synchronized (mEccTable) {
            return mEccTable.get(iso.toUpperCase());
        }
    }

    /**
     * Loads the mapping table.
     */
    public void loadMappingTable(@NonNull Context context) {
        ProtobufEccData.AllInfo allEccData = null;

        long startTime = SystemClock.uptimeMillis();
        try {
            allEccData = parseEccData(new BufferedInputStream(
                    context.getAssets().open("eccdata")));
        } catch (IOException e) {
            Log.e(LOG_TAG, "Failed to retrieve ECC: ", e);
        }
        long endTime = SystemClock.uptimeMillis();

        if (allEccData == null) {
            return;
        }

        if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
            Log.d(LOG_TAG, "Loading time = " + (endTime - startTime) + "ms"
                    + ", Country Count = " + allEccData.getCountriesCount()
                    + ", initialized = " + allEccData.isInitialized());
        }

        // Converts to run-time data from Protobuf data.
        synchronized (mEccTable) {
            mEccTable.clear();
            for (ProtobufEccData.CountryInfo countryData : allEccData.getCountriesList()) {
                if (countryData.hasIsoCode()) {
                    CountryEccInfo countryInfo = loadCountryEccInfo(countryData);
                    if (countryInfo != null) {
                        mEccTable.put(countryData.getIsoCode().toUpperCase(), countryInfo);
                    }
                }
            }
        }
    }

    private ProtobufEccData.AllInfo parseEccData(InputStream input) throws IOException {
        return ProtobufEccData.AllInfo.parseFrom(new GZIPInputStream(input));
    }

    private EccInfo loadEccInfo(String isoCode, ProtobufEccData.EccInfo eccData) {
        String phoneNumber = eccData.getPhoneNumber().trim();
        if (phoneNumber.isEmpty()) {
            Log.i(LOG_TAG, "Discard ecc " + phoneNumber
                    + " for " + isoCode + " due to empty phone number");
            return null;
        }

        ArraySet<EccInfo.Type> eccTypes = new ArraySet<>(eccData.getTypesCount());
        for (ProtobufEccData.EccInfo.Type typeData : eccData.getTypesList()) {
            switch (typeData) {
                case POLICE:
                    eccTypes.add(EccInfo.Type.POLICE);
                    break;
                case AMBULANCE:
                    eccTypes.add(EccInfo.Type.AMBULANCE);
                    break;
                case FIRE:
                    eccTypes.add(EccInfo.Type.FIRE);
                    break;
                default:
                    // Ignores unknown types.
            }
        }

        if (eccTypes.isEmpty()) {
            Log.i(LOG_TAG, "Discard ecc " + phoneNumber
                    + " for " + isoCode + " due to no valid type");
            return null;
        }
        return new EccInfo(phoneNumber, eccTypes);
    }

    private CountryEccInfo loadCountryEccInfo(ProtobufEccData.CountryInfo countryData) {
        ArrayMap<String, EccInfo> eccInfoMap = new ArrayMap<>(countryData.getEccsCount());
        for (ProtobufEccData.EccInfo eccData : countryData.getEccsList()) {
            EccInfo eccInfo = loadEccInfo(countryData.getIsoCode(), eccData);
            String key = eccInfo.getNumber().trim();
            EccInfo existentEccInfo = eccInfoMap.get(key);
            if (existentEccInfo == null) {
                eccInfoMap.put(key, eccInfo);
            } else {
                // Merges types of duplicated ECC info objects.
                ArraySet<EccInfo.Type> eccTypes = new ArraySet<>(
                        eccInfo.getTypesCount() + existentEccInfo.getTypesCount());
                for (EccInfo.Type type : eccInfo.getTypes()) {
                    eccTypes.add(type);
                }
                for (EccInfo.Type type : existentEccInfo.getTypes()) {
                    eccTypes.add(type);
                }
                eccInfoMap.put(key, new EccInfo(eccInfo.getNumber(), eccTypes));
            }
        }

        if (eccInfoMap.isEmpty() && !countryData.hasEccFallback()) {
            Log.i(LOG_TAG, "Discard empty data for " + countryData.getIsoCode());
            return null;
        }
        return new CountryEccInfo(countryData.getEccFallback(), eccInfoMap.values());
    }
}
