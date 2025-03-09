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

package com.android.phone.satellite.accesscontrol;

import static com.android.phone.satellite.accesscontrol.SatelliteAccessConfigurationParser.SATELLITE_ACCESS_CONTROL_CONFIGS;
import static com.android.phone.satellite.accesscontrol.SatelliteAccessConfigurationParser.SATELLITE_CONFIG_ID;
import static com.android.phone.satellite.accesscontrol.SatelliteAccessConfigurationParser.SATELLITE_INFOS;
import static com.android.phone.satellite.accesscontrol.SatelliteAccessConfigurationParser.isRegionalConfigIdValid;
import static com.android.phone.satellite.accesscontrol.SatelliteAccessConfigurationParser.parseSatelliteBandList;
import static com.android.phone.satellite.accesscontrol.SatelliteAccessConfigurationParser.parseSatelliteEarfcnRangeList;
import static com.android.phone.satellite.accesscontrol.SatelliteAccessConfigurationParser.parseSatelliteId;
import static com.android.phone.satellite.accesscontrol.SatelliteAccessConfigurationParser.parseSatelliteInfoList;
import static com.android.phone.satellite.accesscontrol.SatelliteAccessConfigurationParser.parseSatellitePosition;
import static com.android.phone.satellite.accesscontrol.SatelliteAccessConfigurationParser.parseSatelliteTagIdList;
import static com.android.phone.satellite.accesscontrol.SatelliteAccessConfigurationParser.readJsonStringFromFile;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.telephony.satellite.EarfcnRange;
import android.telephony.satellite.SatelliteAccessConfiguration;
import android.telephony.satellite.SatelliteInfo;
import android.telephony.satellite.SatellitePosition;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Unit test for {@link SatelliteAccessConfigurationParser} */
@RunWith(AndroidJUnit4.class)
public class SatelliteAccessConfigurationParserTest {
    private static final String TAG = "SatelliteAccessConfigurationParserTest";

    private static final String TEST_FILE_NAME = "test.json";
    private static final String TEST_INVALID_FILE_NAME = "nonexistent_file.json";

    private static final String TEST_SATELLITE_UUID1 = "5d0cc4f8-9223-4196-ad7a-803002db7af7";
    private static final String TEST_SATELLITE_UUID2 = "0d30312e-a73f-444d-b99b-a893dfb42ee9";
    private static final String TEST_SATELLITE_UUID3 = "01a0b0ca-11bc-4777-87ae-f39afbbec1e9";

    private static final String VALID_JSON_STRING =
            """
            {
             "access_control_configs": [
               {
                 "config_id": 123,
                 "satellite_infos": [
                   {
                     "satellite_id": "5d0cc4f8-9223-4196-ad7a-803002db7af7",
                     "satellite_position": {
                       "longitude": 45.5,
                       "altitude": 35786000
                     },
                     "bands": [
                       1234,
                       5678
                     ],
                     "earfcn_ranges": [
                       {
                         "start_earfcn": 1500,
                         "end_earfcn": 1800
                       }
                     ]
                   },
                   {
                     "satellite_id": "0d30312e-a73f-444d-b99b-a893dfb42ee9",
                     "satellite_position": {
                       "longitude": -120.3,
                       "altitude": 35786000
                     },
                     "bands": [
                       3456,
                       7890
                     ],
                     "earfcn_ranges": [
                       {
                         "start_earfcn": 2000,
                         "end_earfcn": 2300
                       }
                     ]
                   }
                 ],
                 "tag_ids": [
                   7,
                   10
                 ]
               },
               {
                 "config_id": 890,
                 "satellite_infos": [
                   {
                     "satellite_id": "01a0b0ca-11bc-4777-87ae-f39afbbec1e9",
                     "satellite_position": {
                       "longitude": -120,
                       "altitude": 1234567
                     },
                     "bands": [
                       13579,
                       24680
                     ],
                     "earfcn_ranges": [
                       {
                         "start_earfcn": 6420,
                         "end_earfcn": 15255
                       }
                     ]
                   }
                 ],
                 "tag_ids": [
                   6420,
                   15255
                 ]
               }
             ]
             }
            """;


    // Mandatory : config_id ( >= 0)
    // SatelliteInfoList : NonNull
    // UUID (0-9, a-f and hyphen : '_' and 'z' are invalid)
    // longitude (-180 ~ 180)
    // altitude ( >= 0)
    private static final String INVALID_JSON_STRING =
            """
            {
              "access_control_configs": [
                {
                  "config_id": -100,
                  "satellite_infos": [
                    {
                      "satellite_id": "01z0b0ca-11bc-4777_87ae-f39afbbec1e9",
                      "satellite_position": {
                        "longitude": -181,
                        "altitude": -1
                      },
                      "earfcn_ranges": [
                        {
                          "start_earfcn": -1,
                          "end_earfcn": 65536
                        }
                      ]
                    }
                  ]
                }
              ]
            }
            """;

    @Before
    public void setUp() throws Exception {
        Log.d(TAG, "setUp");
        MockitoAnnotations.initMocks(this);
    }

    @After
    public void tearDown() throws Exception {
        Log.d(TAG, "tearDown");
    }

    @AfterClass
    public static void afterClass() throws Exception {
    }

    @BeforeClass
    public static void beforeClass() throws Exception {
    }

    private static File createTestJsonFile(@NonNull String content) throws Exception {
        Log.d(TAG, "createTestJsonFile");
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File testFile = new File(context.getCacheDir(), TEST_FILE_NAME);
        try (FileOutputStream fos = new FileOutputStream(testFile)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return testFile;
    }

    @Test
    public void testLoadJsonFile() throws Exception {
        Log.d(TAG, "testLoadJsonFile");
        assertNull(readJsonStringFromFile(TEST_INVALID_FILE_NAME));
        assertNull(readJsonStringFromFile(null));

        File file = createTestJsonFile(VALID_JSON_STRING);
        assertEquals(VALID_JSON_STRING, readJsonStringFromFile(file.getPath()));

        assertTrue(file.delete());
    }


    private SatelliteInfo getSatelliteInfo(UUID id, SatellitePosition position,
            List<Integer> bandList, List<EarfcnRange> rangeList) {
        return new SatelliteInfo(id, position, bandList, rangeList);
    }

    private Map<Integer, SatelliteAccessConfiguration> getExpectedMap() {
        List<SatelliteInfo> satelliteInfoList1 = new ArrayList<>();
        satelliteInfoList1.add(
                getSatelliteInfo(UUID.fromString(TEST_SATELLITE_UUID1),
                        new SatellitePosition(45.5, 35786000),
                        List.of(1234, 5678),
                        new ArrayList<>(List.of(new EarfcnRange(1500, 1800)))
                ));
        satelliteInfoList1.add(
                getSatelliteInfo(UUID.fromString(TEST_SATELLITE_UUID2),
                        new SatellitePosition(-120.3, 35786000),
                        List.of(3456, 7890),
                        new ArrayList<>(List.of(new EarfcnRange(2000, 2300)))
                ));

        List<Integer> tagIdList1 = List.of(7, 10);
        SatelliteAccessConfiguration satelliteAccessConfiguration1 =
                new SatelliteAccessConfiguration(satelliteInfoList1, tagIdList1);

        HashMap<Integer, SatelliteAccessConfiguration> expectedResult = new HashMap<>();
        expectedResult.put(123, satelliteAccessConfiguration1);

        List<SatelliteInfo> satelliteInfoList2 = new ArrayList<>();
        List<Integer> tagIdList2 = List.of(6420, 15255);
        satelliteInfoList2.add(
                getSatelliteInfo(UUID.fromString(TEST_SATELLITE_UUID3),
                        new SatellitePosition(-120, 1234567),
                        List.of(13579, 24680),
                        new ArrayList<>(List.of(new EarfcnRange(6420, 15255)))
                ));
        SatelliteAccessConfiguration satelliteAccessConfiguration2 =
                new SatelliteAccessConfiguration(satelliteInfoList2, tagIdList2);
        expectedResult.put(890, satelliteAccessConfiguration2);
        return expectedResult;
    }


    @Test
    public void testParsingValidSatelliteAccessConfiguration() throws Exception {
        Log.d(TAG, "testParsingValidSatelliteAccessConfiguration");
        File file = createTestJsonFile(VALID_JSON_STRING);
        assertEquals(getExpectedMap(),
                SatelliteAccessConfigurationParser.parse(file.getCanonicalPath()));
    }

    @Test
    public void testParsingInvalidSatelliteAccessConfiguration() throws Exception {
        Log.d(TAG, "testParsingInvalidSatelliteAccessConfiguration");
        File file = createTestJsonFile(INVALID_JSON_STRING);
        String jsonString = readJsonStringFromFile(file.getCanonicalPath());
        JSONObject satelliteAccessConfigJsonObject = new JSONObject(jsonString);
        JSONArray configurationArrayJson = satelliteAccessConfigJsonObject.optJSONArray(
                SATELLITE_ACCESS_CONTROL_CONFIGS);

        for (int i = 0; i < configurationArrayJson.length(); i++) {
            JSONObject configJson = configurationArrayJson.getJSONObject(i);

            int configId = configJson.optInt(SATELLITE_CONFIG_ID, -1);
            assertFalse(isRegionalConfigIdValid(configId));

            JSONArray satelliteInfoArray = configJson.getJSONArray(SATELLITE_INFOS);
            List<SatelliteInfo> satelliteInfoList = parseSatelliteInfoList(satelliteInfoArray);
            assertNotNull(satelliteInfoList);
            assertTrue(satelliteInfoList.isEmpty());

            for (int j = 0; j < satelliteInfoArray.length(); j++) {
                JSONObject infoJson = satelliteInfoArray.getJSONObject(i);
                assertNull(parseSatelliteId(infoJson));
                SatellitePosition satellitePosition = parseSatellitePosition(infoJson);
                assertNotNull(satellitePosition);
                assertTrue(Double.isNaN(satellitePosition.getLongitudeDegrees()));
                assertTrue(Double.isNaN(satellitePosition.getAltitudeKm()));
                assertTrue(parseSatelliteEarfcnRangeList(infoJson).isEmpty());
                assertNotNull(parseSatelliteBandList(infoJson));
                assertEquals(0, parseSatelliteBandList(infoJson).size());
            }

            List<Integer> tagIdList = parseSatelliteTagIdList(configJson);
            assertNotNull(tagIdList);
        }
    }
}
