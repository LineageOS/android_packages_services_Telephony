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
import android.telephony.CellSignalStrength;
import android.telephony.CellSignalStrengthNr;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.telephony.satellite.SatelliteManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
    private RadioGroup mProtocolRadioGroup;
    private int mSubId = SubscriptionManager.DEFAULT_SUBSCRIPTION_ID;
    private final ExecutorService pingExecutor = Executors.newSingleThreadExecutor();
    private Future<?> mPingFuture;
    private Button pingTest;
    private Button stopTest;
    private String mPingResult;
    private static final int INVALID_SUB_ID = -1;
    private TelephonyManager mTelephonyManager;
    private ServiceState mServiceState;
    private boolean mIsSatellite;
    private TextView mSignalStrengthTextView;
    private TextView mNetworkBandWidthTextView;
    private RadioInfoTelephonyCallback mTelephonyCallback;
    private SignalStrength mSignalStrength;
    // Protocol Selection
    private enum PingProtocol {
        ICMP,
        HTTP_GET
    }
    private PingProtocol mSelectedProtocol = PingProtocol.ICMP;

    private class RadioInfoTelephonyCallback extends TelephonyCallback
            implements TelephonyCallback.ServiceStateListener,
            TelephonyCallback.SignalStrengthsListener {
        @Override
        public void onServiceStateChanged(ServiceState serviceState) {
            if (serviceState == null) {
                return;
            }
            logd("onServiceStateChanged: ServiceState=" + serviceState);
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
                    logd("NTN to OOS");
                    if (mNetwork != null) {
                        mNetwork = null;
                        displayProcessUI("Satellite network lost");
                        disableView();
                    }
                }
            }
            mServiceState = serviceState;
            mIsSatellite = newNri.isNonTerrestrialNetwork();
        }

        @Override
        public void onSignalStrengthsChanged(SignalStrength signalStrength) {
            mSignalStrength = signalStrength;
            updateSignalStrength();
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
        mSignalStrengthTextView = findViewById(R.id.signalStrengthTextView);
        mNetworkBandWidthTextView = findViewById(R.id.networkBandWidthTextView);
        updateSatelliteDataMode(satDataMode);

        pingTest = findViewById(R.id.StartPingTest);
        stopTest = findViewById(R.id.StopPingTest);
        mProtocolRadioGroup = findViewById(R.id.protocolRadioGroup);
        disableView();
        checkForNetwork();

        // Protocol Selection Listener
        mProtocolRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.icmpRadioButton) {
                mSelectedProtocol = PingProtocol.ICMP;
                logd("Protocol selected: ICMP");
            } else if (checkedId == R.id.httpRadioButton) {
                mSelectedProtocol = PingProtocol.HTTP_GET;
                logd("Protocol selected: HTTP GET");
            }
        });

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
                logd("Register for service state change");
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
            disableRadioButton();
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
            enableRadioButton();
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
                        logd("SatelliteConstrainNetworkCallback: onAvailable");
                        mNetwork = network;
                        if (isSatelliteNetworkAnalysisRequested) {
                            try {
                                if (mPingFuture != null) {
                                    mPingFuture.get();
                                }
                            } catch (Exception e) {
                                // Nothing
                            }
                            mHandler.postDelayed(() -> makePing(), 500);
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
                            if (mPingFuture != null && !mPingFuture.isDone()) {
                                mPingFuture.cancel(true);
                            }
                        }
                        disableView();
                        logd("SatelliteConstrainNetworkCallback: Network Lost " + network);
                    }

                    @Override
                    public void onUnavailable() {
                        super.onUnavailable();
                        logd("SatelliteConstrainNetworkCallback : onUnavailable");
                    }

                    @Override
                    public void onLosing(@NonNull Network arg1, int arg2) {
                        super.onLosing(arg1, arg2);
                        logd("SatelliteConstrainNetworkCallback: onLosing");
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
        runOnUiThread(() -> button.setEnabled(false));
    }

    private void enableButton(Button button) {
        runOnUiThread(() ->button.setEnabled(true));
    }

    private void disableRadioButton() {
        for (int i = 0; i < mProtocolRadioGroup.getChildCount(); i++) {
            mProtocolRadioGroup.getChildAt(i).setEnabled(false);
        }
    }

    private void enableRadioButton() {
        for (int i = 0; i < mProtocolRadioGroup.getChildCount(); i++) {
            mProtocolRadioGroup.getChildAt(i).setEnabled(true);
        }
    }

    private void makePing() {
        mPingFuture = pingExecutor.submit(() -> {
            mPingResult = null;
            try {
                PingTask pingTask = new PingTask();
                long startTime = System.currentTimeMillis();
                if (!isSatelliteNetworkAnalysisRequested || mNetwork == null) return;
                if (mSelectedProtocol == PingProtocol.ICMP) {
                    mPingResult = pingTask.pingIcmp();
                } else {
                    mPingResult = pingTask.ping(mNetwork);
                }
                if (!isSatelliteNetworkAnalysisRequested || mNetwork == null) return;
                long latency = System.currentTimeMillis() - startTime;
                logd(String.format("makePing: latency: %s, Ping result: %s", latency, mPingResult));
                if (mPingResult != null && mNetwork != null) {
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
                logd("Exception at ping: " + e);
            } finally {
                displayAnalysis();
                if (!isSatelliteNetworkAnalysisRequested) {
                    displayProcessUI("");
                    displayToast("Satellite Ping test stopped!");
                } else {
                    if (mNetwork == null) {
                        displayProcessUI("Satellite network lost");
                    } else {
                        mHandler.postDelayed(() -> makePing(), 500);
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
        loge("Requesting Network");
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
        loge("onClick + " + mRequest);
    }

    private void releasingNetwork() {
        loge("Releasing Network");
        try {
            mConnectivityManager.unregisterNetworkCallback(mSatelliteConstrainNetworkCallback);
        } catch (Exception e) {
            logd("Exception: " + e);
        }
    }

    private void refreshDataMode() {
        SatelliteManager satelliteManager = mContext.getSystemService(SatelliteManager.class);
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

    private void updateSignalStrength() {
        if (mServiceState == null || mServiceState.getState() != ServiceState.STATE_IN_SERVICE) {
            return;
        }

        mSignalStrengthTextView.setText(buildCellInfoString());
        int [] networkBandWidth = mServiceState.getCellBandwidths();
        if (networkBandWidth != null && networkBandWidth.length > 0) {
            logd(String.format("networkBandWidth: %sMHz", networkBandWidth[0] / 1000));
            mNetworkBandWidthTextView.setText(String.format("Network BandWidth: %sMHz",
                    networkBandWidth[0] / 1000));
            mNetworkBandWidthTextView.setVisibility(View.VISIBLE);
        }
        mSignalStrengthTextView.setVisibility(View.VISIBLE);
    }

    private String buildCellInfoString() {
        StringBuilder value = new StringBuilder("Signal Strength: ");
        value.append(buildLteInfoString());
        value.append((buildNrInfoString()));
        return value.toString();
    }

    private String buildLteInfoString() {
        return String.format(
                "\nLTE- RSRP: %s, RSSNR: %s, RSRQ: %s",
                getCellInfoDisplayString(mSignalStrength.getLteRsrp()),
                getCellInfoDisplayString(mSignalStrength.getLteRsrq()),
                getCellInfoDisplayString(mSignalStrength.getLteRssnr()));
    }

    private String buildNrInfoString() {
        List<CellSignalStrength> ssNrs = mSignalStrength.getCellSignalStrengths();

        String nrInfo = "";
        for (CellSignalStrength ssNr: ssNrs) {
            if (ssNr instanceof CellSignalStrengthNr) {
                nrInfo = String.format("\n5G- SSRSRP: %s, SSRSRQ: %s",
                        getCellInfoDisplayString(((CellSignalStrengthNr) ssNr).getSsRsrp()),
                        getCellInfoDisplayString(((CellSignalStrengthNr) ssNr).getSsRsrq()));
            }
        }
        return nrInfo;
    }

    private String getCellInfoDisplayString(int i) {
        return (i != Integer.MAX_VALUE) ? Integer.toString(i) : "";
    }

    private void logd(String message) {
        Log.d(TAG, message);
    }

    private void loge(String message) {
        Log.e(TAG, message);
    }
}
