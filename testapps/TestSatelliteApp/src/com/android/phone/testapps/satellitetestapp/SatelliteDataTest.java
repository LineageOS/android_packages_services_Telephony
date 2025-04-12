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

package com.android.phone.testapps.satellitetestapp;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
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
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** SatelliteDataTest main activity to navigate to other APIs related to satellite. */
public class SatelliteDataTest extends Activity {

    private static final String TAG = "SatelliteDataTest";
    Network mNetwork = null;
    Context mContext;
    Handler mHandler;
    ConnectivityManager mConnectivityManager;
    NetworkCallback mSatelliteConstrainNetworkCallback;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private SatelliteManager mSatelliteManager;
    private final int NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED = 37;
    private boolean isNetworkRequested = false;
    public int totalSatelliteRequests = 0;
    private SubscriptionManager mSubscriptionManager;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mContext = getApplicationContext();
        TestSatelliteUtils.setupEdgeToEdge(this);
        mHandler = new Handler(Looper.getMainLooper());
        mConnectivityManager = getSystemService(ConnectivityManager.class);
        mSubscriptionManager = getSystemService(SubscriptionManager.class);
        mSatelliteManager = new SatelliteManager(mContext);

        setContentView(R.layout.activity_SatelliteDataTest);

        findViewById(R.id.TestSatelliteConstrainConnection)
                .setOnClickListener(
                        view -> {
                            executor.execute(
                                    () -> {
                                        Log.e(TAG, "onClick");
                                        mSatelliteConstrainNetworkCallback =
                                                new NetworkCallback() {
                                                    @Override
                                                    public void onAvailable(final Network network) {
                                                        makeSatelliteDataConstrainedPing(network);
                                                    }
                                                };

                                        if (!isNetworkRequested) {
                                            requestingNetwork();
                                        } else {
                                            Log.e(TAG, "another request is in progress!");
                                            displayToast("another request is in progress!");
                                        }
                                    });
                        });

        findViewById(R.id.SatelliteDataConnectionPingTest)
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
                                                    SatelliteDataTest.this,
                                                    SatelliteNetworkPingTest.class);
                                    intent.putExtra("SATELLITE_DATA_MODE", satelliteDataMode);
                                    intent.putExtra("SUBID", subId);
                                    startActivity(intent);
                                } else {
                                    displayToast("Data in restricted mode");
                                }
                            }
                        });

        findViewById(R.id.SatelliteSpeedTest)
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
                                                    SatelliteDataTest.this,
                                                    SatelliteSpeedTest.class);
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

        if (subscriptionInfoList != null && !subscriptionInfoList.isEmpty()) {
            subId = subscriptionInfoList.getFirst().getSubscriptionId();
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isNetworkRequested) {
            releasingNetwork();
        }
    }

    private void requestingNetwork() {
        Log.e(TAG, "Requesting Network");
        totalSatelliteRequests = totalSatelliteRequests + 1;
        isNetworkRequested = true;
        NetworkRequest request =
                new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED)
                        .removeCapability(NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED)
                        .addTransportType(NetworkCapabilities.TRANSPORT_SATELLITE)
                        .build();

        // Requesting for Network
        mConnectivityManager.requestNetwork(request, mSatelliteConstrainNetworkCallback);
        checkRequestNetworkComplete(totalSatelliteRequests);
        Log.e(TAG, "onClick + " + request);
    }

    private void checkRequestNetworkComplete(int currentSatelliteRequestNumber) {
        mHandler.postDelayed(
                new Runnable() {
                    @Override
                    public void run() {
                        if (isNetworkRequested
                                && currentSatelliteRequestNumber == totalSatelliteRequests) {
                            releasingNetwork();
                            Log.e(TAG, "ping not supported in this mode!");
                            displayToast("ping not supported in this mode!");
                        }
                    }
                },
                2000);
    }

    private void makeSatelliteDataConstrainedPing(final Network network) {
        Log.e(TAG, "onAvailable + " + network);
        mNetwork = network;

        try {
            PingTask pingTask = new PingTask();
            Log.d(TAG, "Connecting Satellite for ping");
            long startTime = System.currentTimeMillis();
            String pingResult = pingTask.ping(mNetwork);
            long latency = System.currentTimeMillis() - startTime;
            if (pingResult != null) {
                displayToast("Ping Passed! Latency: " + formatLatency(latency));
            } else {
                displayToast("Ping Failed!");
            }
        } catch (Exception e) {
            Log.d(TAG, "Exception at ping: " + e);
        } finally {
            // Releasing the callback in the background thread
            releasingNetwork();
        }
    }

    /**
     * Formats a time in milliseconds into a human-readable string.
     *
     * @param milliseconds The time in milliseconds.
     * @return The formatted time string (e.g., "1.234 seconds").
     */
    public static String formatLatency(long milliseconds) {
        double seconds = milliseconds / 1000.0;
        return String.format(Locale.getDefault(), "%.3f seconds", seconds);
    }

    private void releasingNetwork() {
        Log.e(TAG, "Releasing Network");
        try {
            mConnectivityManager.unregisterNetworkCallback(mSatelliteConstrainNetworkCallback);
        } catch (Exception e) {
            Log.d("SatelliteDataConstrained", "Exception: " + e);
        }
        isNetworkRequested = false;
    }
}
