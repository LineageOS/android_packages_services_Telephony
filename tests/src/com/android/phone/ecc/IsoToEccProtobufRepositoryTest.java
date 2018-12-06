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

import static com.google.common.truth.Truth.assertThat;

import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.util.Log;

import com.android.TelephonyTestBase;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashSet;
import java.util.Map;

/**
 * Unit tests for IsoToEccProtobufRepository.
 */

@RunWith(AndroidJUnit4.class)
public class IsoToEccProtobufRepositoryTest extends TelephonyTestBase {
    private static final String LOG_TAG = "IsoToEccProtobufRepositoryTest";

    @Test
    public void testEccDataContent() {
        IsoToEccProtobufRepository repository = IsoToEccProtobufRepository.getInstance();
        repository.loadMappingTable(InstrumentationRegistry.getTargetContext());
        Map<String, CountryEccInfo> eccTable = repository.getEccTable();
        HashSet loadedIsos = new HashSet(300);
        HashSet loadedNumbers = new HashSet(5);

        assertThat(eccTable).isNotEmpty();
        for (Map.Entry<String, CountryEccInfo> entry : eccTable.entrySet()) {
            String countryIso = entry.getKey();
            CountryEccInfo countryEccInfo = entry.getValue();
            EccInfo[] eccInfoList = countryEccInfo.getEccInfoList();
            if (eccInfoList.length > 0) {
                Log.i(LOG_TAG, "Verifying country " + countryIso + " with "
                        + eccInfoList.length + " ecc(s)");
            } else {
                Log.w(LOG_TAG, "Verifying country " + countryIso + " with no ecc");
            }

            assertThat(countryIso).isNotEmpty();
            assertThat(countryIso).isEqualTo(countryIso.toUpperCase().trim());
            assertThat(loadedIsos.contains(countryIso)).isFalse();
            loadedIsos.add(countryIso);

            assertThat(countryEccInfo.getFallbackEcc()).isNotEmpty();

            if (eccInfoList.length != 0) {
                loadedNumbers.clear();
                for (EccInfo eccInfo : eccInfoList) {
                    String eccNumber = eccInfo.getNumber();
                    assertThat(eccNumber).isNotEmpty();
                    assertThat(eccNumber).isEqualTo(eccNumber.trim());
                    assertThat(eccInfo.getTypes()).isNotEmpty();
                    assertThat(loadedNumbers.contains(eccNumber)).isFalse();
                    loadedNumbers.add(eccNumber);
                }
            }
        }
    }
}
