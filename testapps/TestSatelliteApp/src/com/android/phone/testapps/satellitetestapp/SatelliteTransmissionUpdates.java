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

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.satellite.PointingInfo;
import android.telephony.satellite.SatelliteManager;
import android.telephony.satellite.SatelliteTransmissionUpdateCallback;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.TextView;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Activity related to SatelliteTransmissionUpdates APIs.
 */
public class SatelliteTransmissionUpdates extends Activity {

    private static final String TAG = "SatelliteTransmissionUpdates";

    private SatelliteManager mSatelliteManager;
    private SatelliteTransmissionUpdateCallbackTestApp mCallback;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mSatelliteManager = getSystemService(SatelliteManager.class);
        mCallback = new SatelliteTransmissionUpdateCallbackTestApp();

        setContentView(R.layout.activity_SatelliteTransmissionUpdates);
        findViewById(R.id.startSatelliteTransmissionUpdates)
                .setOnClickListener(this::startSatelliteTransmissionUpdatesApp);
        findViewById(R.id.stopSatelliteTransmissionUpdates)
                .setOnClickListener(this::stopSatelliteTransmissionUpdatesApp);
        findViewById(R.id.Back).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(SatelliteTransmissionUpdates.this,
                        SatelliteTestApp.class));
            }
        });
    }

    protected static class SatelliteTransmissionUpdateCallbackTestApp implements
            SatelliteTransmissionUpdateCallback {
        @Override
        public void onSatellitePositionChanged(PointingInfo pointingInfo) {
            Log.d(TAG, "onSatellitePositionChanged in TestApp: pointingInfo =" + pointingInfo);
        }

        @Override
        public void onSendDatagramStateChanged(int state, int sendPendingCount, int errorCode) {
            Log.d(TAG, "onSendDatagramStateChanged in TestApp: state =" + state
                    + ", sendPendingCount =" + sendPendingCount + ", errorCode=" + errorCode);
        }

        @Override
        public void onReceiveDatagramStateChanged(
                int state, int receivePendingCount, int errorCode) {
            Log.d(TAG, "onReceiveDatagramStateChanged in TestApp: state=" + state + ", "
                    + "receivePendingCount=" + receivePendingCount + ", errorCode=" + errorCode);
        }
    }

    private void startSatelliteTransmissionUpdatesApp(View view) {
        LinkedBlockingQueue<Integer> error = new LinkedBlockingQueue<>(1);
        mSatelliteManager.startSatelliteTransmissionUpdates(Runnable::run, error::offer, mCallback);
        try {
            Integer value = error.poll(1000, TimeUnit.MILLISECONDS);
            TextView textView = findViewById(R.id.text_id);
            if (value == 0) {
                textView.setText("startSatelliteTransmissionUpdates is Successful");
            } else {
                textView.setText("Status for startSatelliteTransmissionUpdates : "
                        + SatelliteErrorUtils.mapError(value));
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "exception caught =" + e);
        }
    }

    private void stopSatelliteTransmissionUpdatesApp(View view) {
        LinkedBlockingQueue<Integer> error = new LinkedBlockingQueue<>(1);
        mSatelliteManager.stopSatelliteTransmissionUpdates(mCallback, Runnable::run, error::offer);
        try {
            Integer value = error.poll(1000, TimeUnit.MILLISECONDS);
            TextView textView = findViewById(R.id.text_id);
            if (value == 0) {
                textView.setText("stopSatelliteTransmissionUpdates is Successful");
            } else {
                textView.setText("Status for stopSatelliteTransmissionUpdates : "
                        + SatelliteErrorUtils.mapError(value));
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "exception caught =" + e);
        }
    }
}
