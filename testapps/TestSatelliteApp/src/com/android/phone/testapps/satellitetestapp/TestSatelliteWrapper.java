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
import android.telephony.satellite.wrapper.NtnSignalStrengthCallbackWrapper;
import android.telephony.satellite.wrapper.NtnSignalStrengthWrapper;
import android.telephony.satellite.wrapper.SatelliteManagerWrapper;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Activity related to SatelliteControl APIs for satellite.
 */
public class TestSatelliteWrapper extends Activity {
    private static final String TAG = "TestSatelliteWrapper";

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private SatelliteManagerWrapper mSatelliteManagerWrapper;
    private NtnSignalStrengthCallback mNtnSignalStrengthCallback = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mSatelliteManagerWrapper = SatelliteManagerWrapper.getInstance(this);

        setContentView(R.layout.activity_TestSatelliteWrapper);
        findViewById(R.id.requestNtnSignalStrength)
                .setOnClickListener(this::requestNtnSignalStrength);
        findViewById(R.id.registerForNtnSignalStrengthChanged)
                .setOnClickListener(this::registerForNtnSignalStrengthChanged);
        findViewById(R.id.unregisterForNtnSignalStrengthChanged)
                .setOnClickListener(this::unregisterForNtnSignalStrengthChanged);
        findViewById(R.id.Back).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(TestSatelliteWrapper.this, SatelliteTestApp.class));
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (mSatelliteManagerWrapper != null && mNtnSignalStrengthCallback != null) {
            Log.d(TAG, "unregisterForNtnSignalStrengthChanged()");
            mSatelliteManagerWrapper.unregisterForNtnSignalStrengthChanged(
                    mNtnSignalStrengthCallback);
        }
    }

    private void requestNtnSignalStrength(View view) {
        Log.d(TAG, "requestNtnSignalStrength");
        TextView textView = findViewById(R.id.text_id);
        OutcomeReceiver<NtnSignalStrengthWrapper,
                SatelliteManagerWrapper.SatelliteExceptionWrapper> receiver =
                new OutcomeReceiver<>() {
                    @Override
                    public void onResult(NtnSignalStrengthWrapper level) {
                        TextView textView = findViewById(R.id.text_id);
                        if (level != null) {
                            textView.setText("requestNtnSignalStrength level is "
                                    + level.getLevel());
                        }
                    }

                    @Override
                    public void onError(
                            SatelliteManagerWrapper.SatelliteExceptionWrapper exception) {
                        if (exception != null) {
                            TextView textView = findViewById(R.id.text_id);
                            String onError = "requestNtnSignalStrength exception: "
                                    + translateResultCodeToString(exception.getErrorCode());
                            Log.d(TAG, onError);
                            textView.setText(onError);
                        }
                    }
                };

        mSatelliteManagerWrapper.requestNtnSignalStrength(mExecutor, receiver);
    }

    private void registerForNtnSignalStrengthChanged(View view) {
        Log.d(TAG, "registerForNtnSignalStrengthChanged()");
        TextView textView = findViewById(R.id.text_id);
        if (mNtnSignalStrengthCallback == null) {
            Log.d(TAG, "create new NtnSignalStrengthCallback instance.");
            mNtnSignalStrengthCallback = new NtnSignalStrengthCallback();
        }
        int result = mSatelliteManagerWrapper.registerForNtnSignalStrengthChanged(mExecutor,
                mNtnSignalStrengthCallback);
        if (result != SatelliteManagerWrapper.SATELLITE_RESULT_SUCCESS) {
            String onError = translateResultCodeToString(result);
            Log.d(TAG, onError);
            textView.setText(onError);
            mNtnSignalStrengthCallback = null;
        }
    }

    private void unregisterForNtnSignalStrengthChanged(View view) {
        Log.d(TAG, "unregisterForNtnSignalStrengthChanged()");
        TextView textView = findViewById(R.id.text_id);
        if (mNtnSignalStrengthCallback != null) {
            mSatelliteManagerWrapper.unregisterForNtnSignalStrengthChanged(
                    mNtnSignalStrengthCallback);
            mNtnSignalStrengthCallback = null;
            textView.setText("mNtnSignalStrengthCallback was unregistered");
        } else {
            textView.setText("mNtnSignalStrengthCallback is null, ignored.");
        }
    }

    public class NtnSignalStrengthCallback implements NtnSignalStrengthCallbackWrapper {
        @Override
        public void onNtnSignalStrengthChanged(
                @NonNull NtnSignalStrengthWrapper ntnSignalStrength) {
            String toastMessage = "Received NTN SignalStrength : " + ntnSignalStrength.getLevel();
            Log.d(TAG, toastMessage);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(getApplicationContext(), toastMessage,
                            Toast.LENGTH_SHORT).show();
                }
            });
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
}
