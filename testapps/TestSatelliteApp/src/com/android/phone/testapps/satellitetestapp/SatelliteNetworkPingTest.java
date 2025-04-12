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
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telephony.AccessNetworkConstants;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.telephony.satellite.SatelliteManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SatelliteNetworkPingTest extends Activity {

    private static final String TAG = "SatelliteNetworkPingTest";
    Handler mHandler;
    ConnectivityManager mConnectivityManager;
    Network mNetwork = null;
    Context mContext;
    NetworkRequest mRequest;
    NetworkCallback mSatelliteConstrainNetworkCallback;
    final int NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED = 37;
    boolean isSatelliteNetworkAnalysisRequested = false;
    public int totalSatelliteDataRequests = 0;
    public int failedSatelliteDataRequests = 0;
    public int passedSatelliteDataRequests = 0;
    public long minSatelliteDataLatency = Long.MAX_VALUE;
    public long maxSatelliteDataLatency = 0;
    public long totalSatelliteDataLatency = 0;
    private TextView statsTextView;
    private TextView processView;
    private TextView mSatDataModeTextView;
    private int mSubId = SubscriptionManager.DEFAULT_SUBSCRIPTION_ID;
    private final ExecutorService pingExecutor = Executors.newSingleThreadExecutor();
    private Button pingTest;
    private Button stopTest;
    private static final int INVALID_SUB_ID = -1;
    private TelephonyManager mTelephonyManager;
    private RadioInfoTelephonyCallback mTelephonyCallback;
    private ServiceState mServiceState;
    private boolean mIsSatellite;

    private class RadioInfoTelephonyCallback extends TelephonyCallback
            implements TelephonyCallback.ServiceStateListener {
        @Override
        public void onServiceStateChanged(ServiceState serviceState) {
            if (serviceState == null) {
                return;
            }
            Log.d(TAG, "onServiceStateChanged: ServiceState=" + serviceState);
            NetworkRegistrationInfo newNri =
                    serviceState.getNetworkRegistrationInfo(
                            NetworkRegistrationInfo.DOMAIN_PS,
                            AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
            if (mServiceState != null && !mServiceState.equals(serviceState)) {
                if (mServiceState.getDataRegistrationState() == ServiceState.STATE_IN_SERVICE
                                && mIsSatellite
                                && serviceState.getDataRegistrationState()
                                        != ServiceState.STATE_IN_SERVICE
                        || !newNri.isNonTerrestrialNetwork()) {
                    Log.d(TAG, "NTN to OOS");
                    if (mNetwork != null) {
                        displayProcessUI("Satellite network lost");
                        disableView();
                    }
                }
            }
            mServiceState = serviceState;
            mIsSatellite = newNri.isNonTerrestrialNetwork();
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TestSatelliteUtils.setupEdgeToEdge(this);
        mHandler = new Handler(Looper.getMainLooper());
        mContext = getApplicationContext();
        mConnectivityManager = getSystemService(ConnectivityManager.class);

        setContentView(R.layout.activity_SatelliteNetworkPingTest);
        mSubId = getIntent().getIntExtra("SUBID", SubscriptionManager.DEFAULT_SUBSCRIPTION_ID);
        int satDataMode =
                getIntent()
                        .getIntExtra(
                                "SATELLITE_DATA_MODE",
                                SatelliteManager.SATELLITE_DATA_SUPPORT_RESTRICTED);
        mSatDataModeTextView = findViewById(R.id.satelliteDataMode);
        statsTextView = findViewById(R.id.statsTextView);
        processView = findViewById(R.id.ProcessView);
        updateSatelliteDataMode(satDataMode);

        pingTest = findViewById(R.id.StartPingTest);
        stopTest = findViewById(R.id.StopPingTest);
        disableView();
        checkForNetwork();

        pingTest.setOnClickListener(view -> startSatellitePingTest());

        stopTest.setOnClickListener(view -> stopSatellitePingTest());

        if (mSubId != INVALID_SUB_ID) {
            mTelephonyManager =
                    ((TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE))
                            .createForSubscriptionId(mSubId);
        }
        registerServiceStateChange();
    }

    private void registerServiceStateChange() {
        if (mTelephonyCallback == null) {
            mTelephonyCallback = new RadioInfoTelephonyCallback();
            if (mTelephonyManager != null) {
                Log.d(TAG, "Register for service state change");
                mTelephonyManager.registerTelephonyCallback(
                        mContext.getMainExecutor(), mTelephonyCallback);
            }
        }
    }

    private void unregister() {
        mTelephonyManager.unregisterTelephonyCallback(mTelephonyCallback);
        mTelephonyCallback = null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDataMode();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregister();
        if (mNetwork != null) {
            releasingNetwork();
        }
    }

    private void startSatellitePingTest() {
        if (!isSatelliteNetworkAnalysisRequested) {
            resetPingTest();
            displayMessageInUI("Ping Tests in progress ...");
            displayToast("Satellite Ping test started!");
            enableButton(stopTest);
            disableButton(pingTest);
            makeSatelliteDataAnalysisPing();
        } else {
            displayToast("Satellite Ping test is already running!");
        }
    }

    private void resetPingTest() {
        totalSatelliteDataRequests = 0;
        failedSatelliteDataRequests = 0;
        passedSatelliteDataRequests = 0;
        minSatelliteDataLatency = Long.MAX_VALUE;
        maxSatelliteDataLatency = 0;
        totalSatelliteDataLatency = 0;
    }

    private void stopSatellitePingTest() {
        if (isSatelliteNetworkAnalysisRequested) {
            displayProcessUI("Preparing results ...");
            isSatelliteNetworkAnalysisRequested = false;
            enableButton(pingTest);
            disableButton(stopTest);
        } else {
            displayToast("Ping test: Not started or stopped.");
        }
    }

    private void checkForNetwork() {
        displayProcessUI("Waiting for network to connect");
        mSatelliteConstrainNetworkCallback =
                new NetworkCallback() {
                    @Override
                    public void onAvailable(@NonNull final Network network) {
                        Log.d(TAG, "SatelliteConstrainNetworkCallback: onAvailable");
                        mNetwork = network;
                        if (isSatelliteNetworkAnalysisRequested) {
                            makePing();
                            enableButton(stopTest);
                        } else {
                            enableButton(pingTest);
                        }
                        displayProcessUI("Connected");
                    }

                    @Override
                    public void onLost(Network network) {
                        if (mNetwork != null && mNetwork.equals(network)) {
                            mNetwork = null;
                            displayProcessUI("Satellite network lost");
                        }
                        releasingNetwork();
                        disableView();
                        Log.d(TAG, "SatelliteConstrainNetworkCallback: Network Lost " + network);
                        requestingNetwork();
                    }

                    @Override
                    public void onUnavailable() {
                        super.onUnavailable();
                        Log.d(TAG, "SatelliteConstrainNetworkCallback : onUnavailable");
                    }

                    @Override
                    public void onLosing(@NonNull Network arg1, int arg2) {
                        super.onLosing(arg1, arg2);
                        Log.d(TAG, "SatelliteConstrainNetworkCallback: onLosing");
                    }
                };
        requestingNetwork();
    }

    private void disableView() {
        runOnUiThread(
                () -> {
                    pingTest.setEnabled(false);
                    stopTest.setEnabled(false);
                });
    }

    private void disableButton(Button button) {
        runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        button.setEnabled(false);
                    }
                });
    }

    private void enableButton(Button button) {
        runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        button.setEnabled(true);
                    }
                });
    }

    private void makePing() {
        pingExecutor.execute(
                new Runnable() {
                    @Override
                    public void run() {
                        String pingResult = null;
                        try {
                            PingTask pingTask = new PingTask();
                            long startTime = System.currentTimeMillis();
                            if (!isSatelliteNetworkAnalysisRequested) return;
                            pingResult = pingTask.ping(mNetwork);
                            if (!isSatelliteNetworkAnalysisRequested) return;
                            long latency = System.currentTimeMillis() - startTime;
                            if (pingResult != null) {
                                passedSatelliteDataRequests = passedSatelliteDataRequests + 1;
                                minSatelliteDataLatency =
                                        Math.min(minSatelliteDataLatency, latency);
                                maxSatelliteDataLatency =
                                        Math.max(maxSatelliteDataLatency, latency);
                                totalSatelliteDataLatency = totalSatelliteDataLatency + latency;
                            } else {
                                failedSatelliteDataRequests = failedSatelliteDataRequests + 1;
                            }
                            totalSatelliteDataRequests = totalSatelliteDataRequests + 1;
                        } catch (Exception e) {
                            Log.d(TAG, "Exception at ping: " + e);
                        } finally {
                            displayAnalysis();
                            if (!isSatelliteNetworkAnalysisRequested) {
                                displayProcessUI("");
                                displayToast("Satellite Ping test stopped!");
                            } else {
                                if (mNetwork == null) {
                                    // wait for connection to come back
                                    displayProcessUI("Satellite network lost");
                                } else if (pingResult == null) {
                                    mHandler.postDelayed(
                                            new Runnable() {
                                                @Override
                                                public void run() {
                                                    makePing();
                                                }
                                            },
                                            1000);
                                } else {
                                    makePing();
                                }
                            }
                        }
                    }
                });
    }

    private void displayAnalysis() {
        String stats =
                "Total Ping Count: "
                        + totalSatelliteDataRequests
                        + "\n"
                        + "Failed Ping Count: "
                        + failedSatelliteDataRequests
                        + "\n"
                        + "Passed Ping Count: "
                        + passedSatelliteDataRequests
                        + "\n"
                        + "Max Ping Latency: "
                        + ((maxSatelliteDataLatency == 0)
                                ? "--"
                                : SatelliteDataTest.formatLatency(maxSatelliteDataLatency))
                        + "\n"
                        + "Min Ping Latency: "
                        + ((minSatelliteDataLatency == Long.MAX_VALUE)
                                ? "--"
                                : SatelliteDataTest.formatLatency(minSatelliteDataLatency))
                        + "\n"
                        + "Average Ping Latency: "
                        + ((passedSatelliteDataRequests == 0)
                                ? "--"
                                : SatelliteDataTest.formatLatency(
                                        totalSatelliteDataLatency / passedSatelliteDataRequests));

        displayMessageInUI(stats);
    }

    private void makeSatelliteDataAnalysisPing() {
        isSatelliteNetworkAnalysisRequested = true;
        makePing();
    }

    private void displayMessageInUI(String message) {
        runOnUiThread(() -> statsTextView.setText(message));
    }

    private void displayProcessUI(String message) {
        runOnUiThread(() -> processView.setText(message));
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

    private void requestingNetwork() {
        Log.e(TAG, "Requesting Network");
        if (mRequest == null) {
            mRequest =
                    new NetworkRequest.Builder()
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED)
                            .removeCapability(NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED)
                            .addTransportType(NetworkCapabilities.TRANSPORT_SATELLITE)
                            .build();
        }

        // Requesting for Network
        mConnectivityManager.requestNetwork(mRequest, mSatelliteConstrainNetworkCallback);
        Log.e(TAG, "onClick + " + mRequest);
    }

    private void releasingNetwork() {
        Log.e(TAG, "Releasing Network");
        try {
            mConnectivityManager.unregisterNetworkCallback(mSatelliteConstrainNetworkCallback);
        } catch (Exception e) {
            Log.d("SatelliteDataConstrained", "Exception: " + e);
        }
    }

    private void refreshDataMode() {
        SatelliteManager satelliteManager = new SatelliteManager(mContext);
        int satelliteDataMode = satelliteManager.getSatelliteDataSupportMode(mSubId);
        updateSatelliteDataMode(satelliteDataMode);
    }

    private void updateSatelliteDataMode(int satDataMode) {
        String satData = getResources().getString(R.string.dataMode);
        satData =
                switch (satDataMode) {
                    case SatelliteManager.SATELLITE_DATA_SUPPORT_RESTRICTED ->
                            satData + " Restricted";
                    case SatelliteManager.SATELLITE_DATA_SUPPORT_CONSTRAINED ->
                            satData + " Limited";
                    case SatelliteManager.SATELLITE_DATA_SUPPORT_UNCONSTRAINED ->
                            satData + " UnLimited";
                    default -> satData + " UnKnown";
                };
        mSatDataModeTextView.setText(satData);
        mSatDataModeTextView.setVisibility(View.VISIBLE);
    }
}
