/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.annotation.NonNull;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.OutcomeReceiver;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.satellite.SatelliteManager;
import android.telephony.satellite.wrapper.NtnSignalStrengthCallbackWrapper;
import android.telephony.satellite.wrapper.NtnSignalStrengthWrapper;
import android.telephony.satellite.wrapper.SatelliteCapabilitiesCallbackWrapper;
import android.telephony.satellite.wrapper.SatelliteManagerWrapper;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;


/**
 * Activity related to SatelliteControl APIs for satellite.
 */
public class TestSatelliteWrapper extends Activity {
    private static final String TAG = "TestSatelliteWrapper";
    ArrayList<String> mLogMessages = new ArrayList<>();
    ArrayAdapter<String> mAdapter;

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private SatelliteManagerWrapper mSatelliteManagerWrapper;
    private NtnSignalStrengthCallback mNtnSignalStrengthCallback = null;
    private SatelliteCapabilitiesCallbackWrapper mSatelliteCapabilitiesCallback;
    private SubscriptionManager mSubscriptionManager;

    private ListView mLogListView;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mSatelliteManagerWrapper = SatelliteManagerWrapper.getInstance(this);
        mSubscriptionManager = getSystemService(SubscriptionManager.class);

        setContentView(R.layout.activity_TestSatelliteWrapper);
        findViewById(R.id.requestNtnSignalStrength)
                .setOnClickListener(this::requestNtnSignalStrength);
        findViewById(R.id.registerForNtnSignalStrengthChanged)
                .setOnClickListener(this::registerForNtnSignalStrengthChanged);
        findViewById(R.id.unregisterForNtnSignalStrengthChanged)
                .setOnClickListener(this::unregisterForNtnSignalStrengthChanged);
        findViewById(R.id.isOnlyNonTerrestrialNetworkSubscription)
                .setOnClickListener(this::isOnlyNonTerrestrialNetworkSubscription);
        findViewById(R.id.registerForSatelliteCapabilitiesChanged)
                .setOnClickListener(this::registerForSatelliteCapabilitiesChanged);
        findViewById(R.id.unregisterForSatelliteCapabilitiesChanged)
                .setOnClickListener(this::unregisterForSatelliteCapabilitiesChanged);
        findViewById(R.id.Back).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(TestSatelliteWrapper.this, SatelliteTestApp.class));
            }
        });
        findViewById(R.id.ClearLog).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                clearListView();
            }
        });

        mLogListView = findViewById(R.id.logListView);
        mAdapter = new ArrayAdapter<>(this, R.layout.log_textview, mLogMessages);
        mLogListView.setAdapter(mAdapter);

        addLogMessage("TestSatelliteWrapper.onCreate()");
    }


    private void clearListView() {
        mLogMessages.clear();
        mAdapter.notifyDataSetChanged();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (mSatelliteManagerWrapper != null) {
            if (mNtnSignalStrengthCallback != null) {
                Log.d(TAG, "unregisterForNtnSignalStrengthChanged()");
                mSatelliteManagerWrapper.unregisterForNtnSignalStrengthChanged(
                        mNtnSignalStrengthCallback);
            }
            if (mSatelliteCapabilitiesCallback != null) {
                Log.d(TAG, "unregisterForSatelliteCapabilitiesChanged()");
                mSatelliteManagerWrapper.unregisterForSatelliteCapabilitiesChanged(
                        mSatelliteCapabilitiesCallback);
            }
        }
    }

    private void requestNtnSignalStrength(View view) {
        addLogMessage("requestNtnSignalStrength");
        Log.d(TAG, "requestNtnSignalStrength");
        OutcomeReceiver<NtnSignalStrengthWrapper,
                SatelliteManagerWrapper.SatelliteExceptionWrapper> receiver =
                new OutcomeReceiver<>() {
                    @Override
                    public void onResult(NtnSignalStrengthWrapper level) {
                        if (level != null) {
                            addLogMessage("requestNtnSignalStrength level is " + level.getLevel());
                        }
                    }

                    @Override
                    public void onError(
                            SatelliteManagerWrapper.SatelliteExceptionWrapper exception) {
                        if (exception != null) {
                            String onError = "requestNtnSignalStrength exception: "
                                    + translateResultCodeToString(exception.getErrorCode());
                            Log.d(TAG, onError);
                            addLogMessage(onError);
                        }
                    }
                };

        try {
            mSatelliteManagerWrapper.requestNtnSignalStrength(mExecutor, receiver);
        } catch (SecurityException | IllegalStateException ex) {
            String errorMessage = "requestNtnSignalStrength: " + ex.getMessage();
            Log.d(TAG, errorMessage);
            addLogMessage(errorMessage);
        }
    }

    private void registerForNtnSignalStrengthChanged(View view) {
        addLogMessage("registerForNtnSignalStrengthChanged");
        Log.d(TAG, "registerForNtnSignalStrengthChanged()");
        if (mNtnSignalStrengthCallback == null) {
            Log.d(TAG, "create new NtnSignalStrengthCallback instance.");
            mNtnSignalStrengthCallback = new NtnSignalStrengthCallback();
        }

        try {
            mSatelliteManagerWrapper.registerForNtnSignalStrengthChanged(mExecutor,
                    mNtnSignalStrengthCallback);
        } catch (Exception ex) {
            String errorMessage;
            if (ex instanceof SatelliteManager.SatelliteException) {
                errorMessage =
                        "registerForNtnSignalStrengthChanged: " + translateResultCodeToString(
                                ((SatelliteManager.SatelliteException) ex).getErrorCode());
            } else {
                errorMessage = "registerForNtnSignalStrengthChanged: " + ex.getMessage();
            }
            Log.d(TAG, errorMessage);
            addLogMessage(errorMessage);
            mNtnSignalStrengthCallback = null;

        }
    }

    private void unregisterForNtnSignalStrengthChanged(View view) {
        addLogMessage("unregisterForNtnSignalStrengthChanged");
        Log.d(TAG, "unregisterForNtnSignalStrengthChanged()");
        if (mNtnSignalStrengthCallback != null) {
            mSatelliteManagerWrapper.unregisterForNtnSignalStrengthChanged(
                    mNtnSignalStrengthCallback);
            mNtnSignalStrengthCallback = null;
            addLogMessage("mNtnSignalStrengthCallback was unregistered");
        } else {
            addLogMessage("mNtnSignalStrengthCallback is null, ignored.");
        }
    }

    private void isOnlyNonTerrestrialNetworkSubscription(View view) {
        addLogMessage("isOnlyNonTerrestrialNetworkSubscription");
        Log.d(TAG, "isOnlyNonTerrestrialNetworkSubscription()");
        List<SubscriptionInfo> infoList = mSubscriptionManager.getAvailableSubscriptionInfoList();
        List<Integer> subIdList = infoList.stream()
                .map(SubscriptionInfo::getSubscriptionId)
                .toList();

        Map<Integer, Boolean> resultMap = subIdList.stream().collect(
                Collectors.toMap(
                        id -> id,
                        id -> {
                            boolean result = mSatelliteManagerWrapper
                                    .isOnlyNonTerrestrialNetworkSubscription(id);
                            addLogMessage("SatelliteManagerWrapper"
                                    + ".isOnlyNonTerrestrialNetworkSubscription(" + id + ")");
                            return result;
                        }
                ));

        for (Map.Entry<Integer, Boolean> entry : resultMap.entrySet()) {
            int subId = entry.getKey();
            boolean result = entry.getValue();
            addLogMessage("Subscription ID: " + subId + ", Result: " + result);
        }
    }

    private void registerForSatelliteCapabilitiesChanged(View view) {
        addLogMessage("registerForSatelliteCapabilitiesChanged");
        Log.d(TAG, "registerForSatelliteCapabilitiesChanged()");
        if (mSatelliteCapabilitiesCallback == null) {
            mSatelliteCapabilitiesCallback =
                    SatelliteCapabilities -> {
                        String message = "Received SatelliteCapabillities : "
                                + SatelliteCapabilities;
                        Log.d(TAG, message);
                        runOnUiThread(() -> addLogMessage(message));
                    };
        }

        int result = mSatelliteManagerWrapper.registerForSatelliteCapabilitiesChanged(mExecutor,
                mSatelliteCapabilitiesCallback);
        if (result != SatelliteManagerWrapper.SATELLITE_RESULT_SUCCESS) {
            String onError = translateResultCodeToString(result);
            Log.d(TAG, onError);
            addLogMessage(onError);
            mSatelliteCapabilitiesCallback = null;
        }
    }

    private void unregisterForSatelliteCapabilitiesChanged(View view) {
        addLogMessage("unregisterForSatelliteCapabilitiesChanged");
        Log.d(TAG, "unregisterForSatelliteCapabilitiesChanged()");
        if (mSatelliteCapabilitiesCallback != null) {
            mSatelliteManagerWrapper.unregisterForSatelliteCapabilitiesChanged(
                    mSatelliteCapabilitiesCallback);
            mSatelliteCapabilitiesCallback = null;
            addLogMessage("mSatelliteCapabilitiesCallback was unregistered");
        } else {
            addLogMessage("mSatelliteCapabilitiesCallback is null, ignored.");
        }
    }

    public class NtnSignalStrengthCallback implements NtnSignalStrengthCallbackWrapper {
        @Override
        public void onNtnSignalStrengthChanged(
                @NonNull NtnSignalStrengthWrapper ntnSignalStrength) {
            String message = "Received NTN SignalStrength : " + ntnSignalStrength.getLevel();
            Log.d(TAG, message);
            runOnUiThread(() -> addLogMessage(message));
        }
    }

    private String translateResultCodeToString(
            @SatelliteManagerWrapper.SatelliteResult int result) {
        switch (result) {
            case SatelliteManagerWrapper.SATELLITE_RESULT_SUCCESS:
                return "SATELLITE_RESULT_SUCCESS";
            case SatelliteManagerWrapper.SATELLITE_RESULT_ERROR:
                return "SATELLITE_RESULT_ERROR";
            case SatelliteManagerWrapper.SATELLITE_RESULT_SERVER_ERROR:
                return "SATELLITE_RESULT_SERVER_ERROR";
            case SatelliteManagerWrapper.SATELLITE_RESULT_SERVICE_ERROR:
                return "SATELLITE_RESULT_SERVICE_ERROR";
            case SatelliteManagerWrapper.SATELLITE_RESULT_MODEM_ERROR:
                return "SATELLITE_RESULT_MODEM_ERROR";
            case SatelliteManagerWrapper.SATELLITE_RESULT_NETWORK_ERROR:
                return "SATELLITE_RESULT_NETWORK_ERROR";
            case SatelliteManagerWrapper.SATELLITE_RESULT_INVALID_TELEPHONY_STATE:
                return "SATELLITE_RESULT_INVALID_TELEPHONY_STATE";
            case SatelliteManagerWrapper.SATELLITE_RESULT_INVALID_MODEM_STATE:
                return "SATELLITE_RESULT_INVALID_MODEM_STATE";
            case SatelliteManagerWrapper.SATELLITE_RESULT_INVALID_ARGUMENTS:
                return "SATELLITE_RESULT_INVALID_ARGUMENTS";
            case SatelliteManagerWrapper.SATELLITE_RESULT_REQUEST_FAILED:
                return "SATELLITE_RESULT_REQUEST_FAILED";
            case SatelliteManagerWrapper.SATELLITE_RESULT_RADIO_NOT_AVAILABLE:
                return "SATELLITE_RESULT_RADIO_NOT_AVAILABLE";
            case SatelliteManagerWrapper.SATELLITE_RESULT_REQUEST_NOT_SUPPORTED:
                return "SATELLITE_RESULT_REQUEST_NOT_SUPPORTED";
            case SatelliteManagerWrapper.SATELLITE_RESULT_NO_RESOURCES:
                return "SATELLITE_RESULT_NO_RESOURCES";
            case SatelliteManagerWrapper.SATELLITE_RESULT_SERVICE_NOT_PROVISIONED:
                return "SATELLITE_RESULT_SERVICE_NOT_PROVISIONED";
            case SatelliteManagerWrapper.SATELLITE_RESULT_SERVICE_PROVISION_IN_PROGRESS:
                return "SATELLITE_RESULT_SERVICE_PROVISION_IN_PROGRESS";
            case SatelliteManagerWrapper.SATELLITE_RESULT_REQUEST_ABORTED:
                return "SATELLITE_RESULT_REQUEST_ABORTED";
            case SatelliteManagerWrapper.SATELLITE_RESULT_ACCESS_BARRED:
                return "SATELLITE_RESULT_ACCESS_BARRED";
            case SatelliteManagerWrapper.SATELLITE_RESULT_NETWORK_TIMEOUT:
                return "SATELLITE_RESULT_NETWORK_TIMEOUT";
            case SatelliteManagerWrapper.SATELLITE_RESULT_NOT_REACHABLE:
                return "SATELLITE_RESULT_NOT_REACHABLE";
            case SatelliteManagerWrapper.SATELLITE_RESULT_NOT_AUTHORIZED:
                return "SATELLITE_RESULT_NOT_AUTHORIZED";
            case SatelliteManagerWrapper.SATELLITE_RESULT_NOT_SUPPORTED:
                return "SATELLITE_RESULT_NOT_SUPPORTED";
            case SatelliteManagerWrapper.SATELLITE_RESULT_REQUEST_IN_PROGRESS:
                return "SATELLITE_RESULT_REQUEST_IN_PROGRESS";
            case SatelliteManagerWrapper.SATELLITE_RESULT_MODEM_BUSY:
                return "SATELLITE_RESULT_MODEM_BUSY";
            default:
                return "INVALID CODE: " + result;
        }
    }

    private void addLogMessage(String message) {
        mLogMessages.add(message);
        mAdapter.notifyDataSetChanged();
        mLogListView.setSelection(mAdapter.getCount() - 1);
    }
}
