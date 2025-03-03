/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.phone.settings;

import static android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID;
import static android.telephony.SubscriptionManager.getDefaultSubscriptionId;

import android.annotation.ArrayRes;
import android.annotation.NonNull;
import android.app.ActionBar;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.widget.TextView;

import com.android.internal.telephony.flags.FeatureFlagsImpl;
import com.android.internal.telephony.satellite.SatelliteController;
import com.android.phone.R;
import com.android.phone.satellite.accesscontrol.SatelliteAccessConfigurationParser;
import com.android.phone.satellite.accesscontrol.SatelliteAccessController;

import java.io.File;
import java.util.HashMap;
import java.util.List;

public class SatelliteConfigViewer extends Activity {
    private static final String TAG = SatelliteConfigViewer.class.getSimpleName();

    private TextView mVersion;
    private TextView mServiceType;
    private TextView mAllowAccess;
    private TextView mCountryCodes;
    private TextView mSizeOfSats2;
    private TextView mConfigAccessJson;

    private SatelliteController mSatelliteController;
    private SatelliteAccessController mSatelliteAccessController;

    private int mSubId = INVALID_SUBSCRIPTION_ID;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.satellite_config_viewer);
        Log.d(TAG, "SatelliteConfigViewer: onCreate");

        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        Intent intentRadioInfo = getIntent();
        mSubId = intentRadioInfo.getIntExtra("mSubId", getDefaultSubscriptionId());
        Log.d(TAG, "SatelliteConfigViewer: mSubId: " + mSubId);

        mVersion = (TextView) findViewById(R.id.version);
        mServiceType = (TextView) findViewById(R.id.svc_type);
        mAllowAccess = (TextView) findViewById(R.id.allow_access);
        mCountryCodes = (TextView) findViewById(R.id.country_codes);
        mSizeOfSats2 = (TextView) findViewById(R.id.size_of_sats2);
        mConfigAccessJson = (TextView) findViewById(R.id.config_json);

        mSatelliteController = SatelliteController.getInstance();
        mSatelliteAccessController = SatelliteAccessController.getOrCreateInstance(
                getApplicationContext(), new FeatureFlagsImpl());

        mVersion.setText(getSatelliteConfigVersion());
        mServiceType.setText(getSatelliteCarrierConfigUpdateData());
        mAllowAccess.setText(getSatelliteAllowAccess());
        mCountryCodes.setText(getSatelliteConfigCountryCodes());
        mSizeOfSats2.setText(getSatelliteS2SatFileSize(getApplicationContext()));
        mConfigAccessJson.setText(getSatelliteConfigJsonFile(getApplicationContext()));
    }

    private String getSatelliteConfigVersion() {
        logd("getSatelliteConfigVersion");
        return Integer.toString(mSatelliteAccessController.getSatelliteAccessConfigVersion());
    }

    private String getSatelliteCarrierConfigUpdateData() {
        logd("getSatelliteCarrierConfigUpdateData");
        HashMap<String, List<Integer>> mapPlmnServiceType = new HashMap<>();
        List<String> plmnList = mSatelliteController.getSatellitePlmnsForCarrier(mSubId);
        for (String plmn : plmnList) {
            List<Integer> listServiceType =
                    mSatelliteController.getSupportedSatelliteServicesForPlmn(mSubId, plmn);
            mapPlmnServiceType.put(plmn, listServiceType);
        }
        logd("getSatelliteCarrierConfigUpdateData: " + "subId: " + mSubId + ": "
                + mapPlmnServiceType);
        return "subId: " + mSubId + ": " + mapPlmnServiceType;
    }

    private String getSatelliteAllowAccess() {
        logd("getSatelliteAllowAccess");
        return Boolean.toString(mSatelliteAccessController.isSatelliteAllowAccessControl());
    }

    private String getSatelliteConfigCountryCodes() {
        logd("getSatelliteConfigCountryCodes");
        return String.join(",", mSatelliteAccessController.getSatelliteCountryCodes());
    }

    private String getSatelliteConfigJsonFile(Context context) {
        logd("getSatelliteConfigJsonFile");

        File jsonFile = mSatelliteAccessController.getSatelliteAccessConfigFile();
        if (jsonFile == null) {
            loge("getSatelliteConfigJsonFile: satellite access config json file is null");
            return "satellite access config json file is not ready";
        }
        return SatelliteAccessConfigurationParser
                .readJsonStringFromFile(jsonFile.getAbsolutePath());
    }

    private String getSatelliteS2SatFileSize(Context context) {
        logd("getSatelliteS2SatFileSize");
        File s2CellFile = mSatelliteAccessController.getSatelliteS2CellFile();
        if (s2CellFile == null) {
            loge("getSatelliteS2SatFileSize: s2satFile is null");
            return "s2satFile is null";
        }
        return Long.toString(s2CellFile.length());
    }

    @NonNull
    private static String[] readStringArrayFromOverlayConfig(
            @NonNull Context context, @ArrayRes int id) {
        String[] strArray = null;
        try {
            strArray = context.getResources().getStringArray(id);
        } catch (Resources.NotFoundException ex) {
            loge("readStringArrayFromOverlayConfig: id= " + id + ", ex=" + ex);
        }
        if (strArray == null) {
            strArray = new String[0];
        }
        return strArray;
    }

    @Override
    public boolean onOptionsItemSelected(@androidx.annotation.NonNull MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                finish();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private static void logd(@NonNull String log) {
        Log.d(TAG, log);
    }

    private static void loge(@NonNull String log) {
        Log.e(TAG, log);
    }
}
