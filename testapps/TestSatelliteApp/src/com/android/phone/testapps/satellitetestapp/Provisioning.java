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
import android.os.CancellationSignal;
import android.os.OutcomeReceiver;
import android.telephony.satellite.SatelliteManager;
import android.telephony.satellite.SatelliteProvisionStateCallback;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.TextView;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Activity related to Provisioning APIs.
 */
public class Provisioning extends Activity {

    private static final String TAG = "Provisioning";

    private boolean mProvisioned = false;

    private SatelliteManager mSatelliteManager;
    private SatelliteProvisionStateCallbackTestApp mCallback;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mSatelliteManager = getSystemService(SatelliteManager.class);
        mCallback = new SatelliteProvisionStateCallbackTestApp();

        setContentView(R.layout.activity_Provisioning);
        findViewById(R.id.provisionSatelliteService)
                .setOnClickListener(this::provisionSatelliteServiceApp);
        findViewById(R.id.deprovisionSatelliteService)
                .setOnClickListener(this::deprovisionSatelliteServiceApp);
        findViewById(R.id.requestIsSatelliteProvisioned)
                .setOnClickListener(this::requestIsSatelliteProvisionedApp);
        findViewById(R.id.registerForSatelliteProvisionStateChanged)
                .setOnClickListener(this::registerForSatelliteProvisionStateChangedApp);
        findViewById(R.id.unregisterForSatelliteProvisionStateChanged)
                .setOnClickListener(this::unregisterForSatelliteProvisionStateChangedApp);
        findViewById(R.id.showCurrentSatelliteProvisionState)
                .setOnClickListener(this::showCurrentSatelliteProvisionStateApp);
        findViewById(R.id.Back).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(Provisioning.this, SatelliteTestApp.class));
            }
        });
    }

    protected class SatelliteProvisionStateCallbackTestApp implements
            SatelliteProvisionStateCallback {
        @Override
        public void onSatelliteProvisionStateChanged(boolean provisioned) {
            mProvisioned = provisioned;
            Log.d(TAG, "onSatelliteProvisionStateChanged in SatelliteTestApp: provisioned="
                    + mProvisioned);
        }
    }

    private void provisionSatelliteServiceApp(View view) {
        CancellationSignal cancellationSignal = new CancellationSignal();
        LinkedBlockingQueue<Integer> error = new LinkedBlockingQueue<>(1);
        String mText = "This is test provision data.";
        byte[] testProvisionData = mText.getBytes();
        mSatelliteManager.provisionSatelliteService("SATELLITE_TOKEN", testProvisionData,
                cancellationSignal, Runnable::run, error::offer);
        try {
            Integer value = error.poll(1000, TimeUnit.MILLISECONDS);
            TextView textView = findViewById(R.id.text_id);
            textView.setText("Status for provisionSatelliteService : "
                    + SatelliteErrorUtils.mapError(value));
        } catch (InterruptedException e) {
            Log.e(TAG, "exception caught =" + e);
        }
    }

    private void deprovisionSatelliteServiceApp(View view) {
        LinkedBlockingQueue<Integer> error = new LinkedBlockingQueue<>(1);
        mSatelliteManager.deprovisionSatelliteService("SATELLITE_TOKEN", Runnable::run,
                error::offer);
        try {
            Integer value = error.poll(1000, TimeUnit.MILLISECONDS);
            TextView textView = findViewById(R.id.text_id);
            textView.setText("Status for deprovisionSatelliteService : "
                    + SatelliteErrorUtils.mapError(value));
        } catch (InterruptedException e) {
            Log.e(TAG, "exception caught =" + e);
        }
    }

    private void requestIsSatelliteProvisionedApp(View view) {
        final AtomicReference<Boolean> enabled = new AtomicReference<>();
        final AtomicReference<Integer> errorCode = new AtomicReference<>();
        OutcomeReceiver<Boolean, SatelliteManager.SatelliteException> mReceiver =
                new OutcomeReceiver<>() {
            @Override
            public void onResult(Boolean result) {
                enabled.set(result);
                TextView textView = findViewById(R.id.text_id);
                textView.setText("Status for requestIsSatelliteProvisioned result: "
                        + enabled.get());
            }

            @Override
            public void onError(SatelliteManager.SatelliteException exception) {
                errorCode.set(exception.getErrorCode());
                TextView textView = findViewById(R.id.text_id);
                textView.setText("Status for requestIsSatelliteProvisioned error : "
                        + SatelliteErrorUtils.mapError(errorCode.get()));
            }
        };
        mSatelliteManager.requestIsSatelliteProvisioned(Runnable::run, mReceiver);
    }

    private void registerForSatelliteProvisionStateChangedApp(View view) {
        int result = mSatelliteManager.registerForSatelliteProvisionStateChanged(Runnable::run,
                mCallback);
        TextView textView = findViewById(R.id.text_id);
        textView.setText("Status for registerForSatelliteProvisionStateChanged : "
                + SatelliteErrorUtils.mapError(result));
    }

    private void unregisterForSatelliteProvisionStateChangedApp(View view) {
        mSatelliteManager.unregisterForSatelliteProvisionStateChanged(mCallback);
        TextView textView = findViewById(R.id.text_id);
        textView.setText("unregisterForSatelliteProvisionStateChanged is successful");
    }

    private void showCurrentSatelliteProvisionStateApp(View view) {
        TextView textView = findViewById(R.id.text_id);
        textView.setText("Current Provision State is " + mProvisioned);
    }
}
