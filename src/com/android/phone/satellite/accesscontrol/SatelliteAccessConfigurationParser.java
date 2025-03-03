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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.ParseException;
import android.os.Build;
import android.telephony.satellite.EarfcnRange;
import android.telephony.satellite.SatelliteAccessConfiguration;
import android.telephony.satellite.SatelliteInfo;
import android.telephony.satellite.SatellitePosition;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class SatelliteAccessConfigurationParser {
    private static final String TAG = "SatelliteAccessConfigurationParser";

    public static final String SATELLITE_ACCESS_CONTROL_CONFIGS = "access_control_configs";
    public static final String SATELLITE_CONFIG_ID = "config_id";
    public static final String SATELLITE_INFOS = "satellite_infos";
    public static final String SATELLITE_ID = "satellite_id";
    public static final String SATELLITE_POSITION = "satellite_position";
    public static final String SATELLITE_LONGITUDE = "longitude";
    public static final String SATELLITE_ALTITUDE = "altitude";
    public static final String SATELLITE_EARFCN_RANGES = "earfcn_ranges";
    public static final String SATELLITE_START_EARFCN = "start_earfcn";
    public static final String SATELLITE_END_EARFCN = "end_earfcn";
    public static final String SATELLITE_BANDS = "bands";
    public static final String SATELLITE_TAG_ID_LIST = "tag_ids";

    /**
     * Parses a JSON file containing satellite access configurations.
     *
     * @param fileName The name of the JSON file to parse.
     * @return A map of satellite access configurations, keyed by config ID.
     * @throws RuntimeException if the JSON file cannot be parsed or if a required field is missing.
     */
    @Nullable
    public static Map<Integer, SatelliteAccessConfiguration> parse(@NonNull String fileName) {
        logd("SatelliteAccessConfigurationParser: parse: " + fileName);
        Map<Integer, SatelliteAccessConfiguration> satelliteAccessConfigurationMap;

        try {
            String jsonString = readJsonStringFromFile(fileName);
            JSONObject satelliteAccessConfigJsonObject = new JSONObject(jsonString);
            JSONArray configurationArrayJson = satelliteAccessConfigJsonObject.optJSONArray(
                    SATELLITE_ACCESS_CONTROL_CONFIGS);

            if (configurationArrayJson == null) {
                loge("parse : failed to parse satellite access configurations json");
                return null;
            }

            satelliteAccessConfigurationMap =
                    parseSatelliteAccessConfigurations(configurationArrayJson);

        } catch (JSONException | ParseException e) {
            loge("Failed to parse satellite access configurations: " + e.getMessage());
            throw new RuntimeException(e);
        }

        logd("satelliteAccessConfigurationMap= " + satelliteAccessConfigurationMap);
        return satelliteAccessConfigurationMap;
    }

    private static void logd(String log) {
        if (!Build.TYPE.equals("user")) {
            Log.d(TAG, log);
        }
    }

    private static void loge(String log) {
        Log.e(TAG, log);
    }

    @NonNull
    protected static List<Integer> parseSatelliteTagIdList(@NonNull JSONObject satelliteInfoJson) {
        List<Integer> tagIdList = new ArrayList<>();
        try {
            JSONArray tagIdArray = satelliteInfoJson.optJSONArray(SATELLITE_TAG_ID_LIST);
            tagIdList = parseIntegerList(tagIdArray);
        } catch (JSONException e) {
            loge("parseSatelliteInfo:  parsing is error");
            return tagIdList;
        }

        logd("parseSatelliteBandList: " + tagIdList);
        return tagIdList;
    }

    @Nullable
    private static Map<Integer, SatelliteAccessConfiguration> parseSatelliteAccessConfigurations(
            JSONArray satelliteAccessConfigurationJsonArray) throws JSONException {
        Map<Integer, SatelliteAccessConfiguration> satelliteConfigMap = new HashMap<>();
        if (satelliteAccessConfigurationJsonArray == null) {
            loge("parseSatelliteAccessConfigurations: jsonArray is null, return null");
            return null;
        }

        for (int i = 0; i < satelliteAccessConfigurationJsonArray.length(); i++) {
            JSONObject satelliteAccessConfigurationJson =
                    satelliteAccessConfigurationJsonArray.getJSONObject(i);

            int configId = satelliteAccessConfigurationJson.optInt(SATELLITE_CONFIG_ID, -1);
            if (!isRegionalConfigIdValid(configId)) {
                loge("parseAccessControlConfigs: invalid config_id, return null");
                return null;
            }

            JSONArray satelliteInfoJsonArray = satelliteAccessConfigurationJson
                    .getJSONArray(SATELLITE_INFOS);
            List<SatelliteInfo> satelliteInfoList = parseSatelliteInfoList(satelliteInfoJsonArray);
            if (satelliteInfoList.isEmpty()) {
                logd("parseAccessControlConfigs: satelliteInfoList is empty");
            }

            List<Integer> tagIdList = parseSatelliteTagIdList(satelliteAccessConfigurationJson);
            if (satelliteInfoList.isEmpty() && tagIdList.isEmpty()) {
                loge("parseAccessControlConfigs: satelliteInfoList is empty and tagId is null");
                return null;
            }

            satelliteConfigMap.put(configId,
                    new SatelliteAccessConfiguration(satelliteInfoList, tagIdList));
        }

        logd("parseSatelliteAccessConfigurations: " + satelliteConfigMap);
        return satelliteConfigMap;
    }

    /**
     * Checks if a regional configuration ID is valid.
     * A valid regional configuration ID is a non-null integer that is greater than or equal to
     * zero.
     *
     * @param configId The regional configuration ID to check.
     * @return {@code true} if the ID is valid, {@code false} otherwise.
     */
    public static boolean isRegionalConfigIdValid(@Nullable Integer configId) {
        return (configId != null && configId >= 0);
    }

    @Nullable
    protected static UUID parseSatelliteId(@NonNull JSONObject satelliteInfoJson) {
        String uuidString = satelliteInfoJson.optString(SATELLITE_ID, null);
        UUID satelliteId;
        if (uuidString != null) {
            try {
                satelliteId = UUID.fromString(uuidString);
            } catch (IllegalArgumentException e) {
                loge("getSatelliteId: invalid UUID format: " + uuidString + " | " + e.getMessage());
                return null;
            }
        } else {
            loge("getSatelliteId: satellite uuid is missing");
            return null;
        }

        logd("getSatelliteId: satellite uuid is " + satelliteId);
        return satelliteId;
    }

    @NonNull
    protected static SatellitePosition parseSatellitePosition(
            @NonNull JSONObject satelliteInfoJson) {
        JSONObject jsonObject = satelliteInfoJson.optJSONObject(SATELLITE_POSITION);
        SatellitePosition satellitePosition = new SatellitePosition(Double.NaN, Double.NaN);

        if (jsonObject == null) {
            loge("parseSatellitePosition: jsonObject is null");
            return satellitePosition;
        }

        try {
            double longitude = jsonObject.getDouble(SATELLITE_LONGITUDE);
            double altitude = jsonObject.getDouble(SATELLITE_ALTITUDE);
            if (isValidLongitude(longitude) && isValidAltitude(altitude)) {
                satellitePosition = new SatellitePosition(longitude, altitude);
            } else {
                loge("parseSatellitePosition: invalid value: " + longitude + " | " + altitude);
                return satellitePosition;
            }
        } catch (JSONException e) {
            loge("parseSatellitePosition: json parsing error " + e.getMessage());
            return satellitePosition;
        }

        logd("parseSatellitePosition: " + satellitePosition);
        return satellitePosition;
    }

    @NonNull
    protected static List<EarfcnRange> parseSatelliteEarfcnRangeList(
            @NonNull JSONObject satelliteInfoJson) {
        JSONArray earfcnRangesArray = satelliteInfoJson.optJSONArray(SATELLITE_EARFCN_RANGES);
        List<EarfcnRange> earfcnRangeList = new ArrayList<>();
        if (earfcnRangesArray == null) {
            loge("parseSatelliteEarfcnRangeList: earfcn_ranges is missing");
            return earfcnRangeList;
        }

        try {
            for (int j = 0; j < earfcnRangesArray.length(); j++) {
                JSONObject earfcnRangeJson = earfcnRangesArray.getJSONObject(j);
                EarfcnRange earfcnRange = parseEarfcnRange(earfcnRangeJson);
                if (earfcnRange == null) {
                    loge("parseSatelliteEarfcnRangeList: earfcnRange is null, return empty list");
                    earfcnRangeList.clear();
                    return earfcnRangeList;
                }
                earfcnRangeList.add(earfcnRange);
            }
        } catch (JSONException e) {
            loge("parseSatelliteEarfcnRangeList: earfcnRange json parsing error");
            earfcnRangeList.clear();
            return earfcnRangeList;
        }
        logd("parseSatelliteEarfcnRangeList: " + earfcnRangeList);
        return earfcnRangeList;
    }

    @NonNull
    protected static List<Integer> parseSatelliteBandList(@NonNull JSONObject satelliteInfoJson) {
        List<Integer> bandList = new ArrayList<>();
        try {
            JSONArray bandArray = satelliteInfoJson.getJSONArray(SATELLITE_BANDS);
            bandList = parseIntegerList(bandArray);
        } catch (JSONException e) {
            loge("parseSatelliteInfo: bands parsing is error");
            return bandList;
        }

        logd("parseSatelliteBandList: " + bandList);
        return bandList;
    }

    @NonNull
    protected static List<SatelliteInfo> parseSatelliteInfoList(JSONArray satelliteInfojsonArray)
            throws JSONException {
        List<SatelliteInfo> satelliteInfoList = new ArrayList<>();
        for (int i = 0; i < satelliteInfojsonArray.length(); i++) {
            JSONObject SatelliteInfoJson = satelliteInfojsonArray.getJSONObject(i);
            if (SatelliteInfoJson == null) {
                satelliteInfoList.clear();
                break;
            }
            UUID id = parseSatelliteId(SatelliteInfoJson);
            SatellitePosition position = parseSatellitePosition(SatelliteInfoJson);
            List<EarfcnRange> earfcnRangeList = parseSatelliteEarfcnRangeList(SatelliteInfoJson);
            List<Integer> bandList = parseSatelliteBandList(SatelliteInfoJson);

            if (id == null || (bandList.isEmpty() && earfcnRangeList.isEmpty())) {
                loge("parseSatelliteInfo: id is " + id
                        + " or both band list and earfcn range list are empty");
                satelliteInfoList.clear();
                return satelliteInfoList;
            }

            SatelliteInfo info = new SatelliteInfo(id, position, bandList, earfcnRangeList);
            satelliteInfoList.add(info);
        }
        logd("parseSatelliteInfoList: " + satelliteInfoList);
        return satelliteInfoList;
    }

    /**
     * Load json file from the filePath
     *
     * @param jsonFilePath The file path of json file
     * @return json string type json contents
     */
    @Nullable
    public static String readJsonStringFromFile(@NonNull String jsonFilePath) {
        logd("jsonFilePath is " + jsonFilePath);
        String json = null;
        try (InputStream inputStream = new FileInputStream(jsonFilePath);
                ByteArrayOutputStream byteArrayStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int length;
            while ((length = inputStream.read(buffer)) != -1) {
                byteArrayStream.write(buffer, 0, length);
            }
            json = byteArrayStream.toString(StandardCharsets.UTF_8);
        } catch (FileNotFoundException e) {
            loge("Error file " + jsonFilePath + " is not founded: " + e.getMessage());
        } catch (IOException | NullPointerException e) {
            loge("Error reading file " + jsonFilePath + ": " + e);
        } finally {
            logd("jsonString is " + json);
        }
        return json;
    }

    private static boolean isValidEarfcn(int earfcn) {
        if (earfcn >= 0) {
            return true;
        }
        loge("isValidEarfcn: earfcn value is out of valid range: " + earfcn);
        return false;
    }

    private static boolean isValidEarfcnRange(int start, int end) {
        if (start <= end) {
            return true;
        }
        loge("isValidEarfcnRange: earfcn range start " + start + " is bigger than end " + end);
        return false;
    }

    @Nullable
    private static EarfcnRange parseEarfcnRange(@Nullable JSONObject jsonObject) {
        logd("parseEarfcnRange");
        if (jsonObject == null) {
            loge("parseEarfcnRange: jsonObject is null");
            return null;
        }
        try {
            int start = jsonObject.getInt(SATELLITE_START_EARFCN);
            int end = jsonObject.getInt(SATELLITE_END_EARFCN);

            if (isValidEarfcn(start) && isValidEarfcn(end) && isValidEarfcnRange(start, end)) {
                return new EarfcnRange(start, end);
            }

            loge("parseEarfcnRange: earfcn value is not valid, return null");
            return null;
        } catch (JSONException e) {
            loge("parseEarfcnRange: json parsing error: " + e.getMessage());
            return null;
        }
    }

    @NonNull
    private static List<Integer> parseIntegerList(@Nullable JSONArray jsonArray)
            throws JSONException {
        List<Integer> intList = new ArrayList<>();
        if (jsonArray == null) {
            loge("parseIntegerList: jsonArray is null, return IntArray with empty");
            return intList;
        }
        for (int i = 0; i < jsonArray.length(); i++) {
            try {
                intList.add(jsonArray.getInt(i));
            } catch (JSONException e) {
                loge("parseIntegerList: jsonArray parsing error: " + e.getMessage());
                intList.clear();
            }
        }
        logd("parseIntegerList: " + intList);
        return intList;
    }

    private static boolean isValidLongitude(double longitude) {
        return (longitude >= -180.0 && longitude <= 180.0);
    }

    private static boolean isValidAltitude(double altitude) {
        return (altitude >= 0);
    }
}
