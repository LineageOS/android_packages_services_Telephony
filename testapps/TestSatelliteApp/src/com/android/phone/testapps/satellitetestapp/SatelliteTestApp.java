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

package com.android.phone.testapps.satellitetestapp;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.satellite.SatelliteManager;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Toast;

import java.util.List;

/** SatelliteTestApp main activity to navigate to other APIs related to satellite. */
public class SatelliteTestApp extends Activity {

    private static final String TAG = "SatelliteTestApp";
    private SatelliteManager mSatelliteManager;
    private SubscriptionManager mSubscriptionManager;
    Context mContext;
    Handler mHandler;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TestSatelliteUtils.setupEdgeToEdge(this);
        mSubscriptionManager = getSystemService(SubscriptionManager.class);
        mContext = getApplicationContext();
        TestSatelliteUtils.setupEdgeToEdge(this);
        mHandler = new Handler(Looper.getMainLooper());
        mSatelliteManager = getSystemService(SatelliteManager.class);

        setContentView(R.layout.activity_SatelliteTestApp);
        findViewById(R.id.PssActivity).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(SatelliteTestApp.this, PssActivity.class);
                startActivity(intent);
            }
        });

        findViewById(R.id.ApiTestApp)
                .setOnClickListener(
                        new OnClickListener() {
                            @Override
                            public void onClick(View view) {
                                Intent intent = new Intent(SatelliteTestApp.this, ApiTestApp.class);
                                startActivity(intent);
                            }
                        });

        findViewById(R.id.SatelliteDataTest)
                .setOnClickListener(
                        new OnClickListener() {
                            @Override
                            public void onClick(View view) {
                                int subId = getActiveSubId();
                                int satelliteDataMode =
                                        mSatelliteManager.getSatelliteDataSupportMode(subId);

                                if (satelliteDataMode
                                        > SatelliteManager.SATELLITE_DATA_SUPPORT_RESTRICTED) {
                                    Intent intent =
                                            new Intent(
                                                    SatelliteTestApp.this, SatelliteDataTest.class);
                                    intent.putExtra("SATELLITE_DATA_MODE", satelliteDataMode);
                                    intent.putExtra("SUBID", subId);
                                    startActivity(intent);
                                } else {
                                    displayToast("Data in restricted mode");
                                }
                            }
                        });
    }

    private int getActiveSubId() {
        int subId;
        List<SubscriptionInfo> subscriptionInfoList =
                mSubscriptionManager.getActiveSubscriptionInfoList();

        if (subscriptionInfoList != null && subscriptionInfoList.size() > 0) {
            subId = subscriptionInfoList.get(0).getSubscriptionId();
        } else {
            subId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        }
        Log.d(TAG, "getActiveSubId() returns " + subId);
        return subId;
    }

    private void displayToast(String message) {
        mHandler.post(
                new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(mContext, message, Toast.LENGTH_SHORT).show();
                    }
                });
    }
}
