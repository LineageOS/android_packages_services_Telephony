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

import static android.telephony.satellite.SatelliteManager.SATELLITE_RESULT_SUCCESS;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.OutcomeReceiver;
import android.telephony.satellite.SatelliteManager;
import android.telephony.satellite.SatelliteSupportedStateCallback;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.TextView;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Activity related to NB IoT satellite APIs.
 */
public class NbIotSatellite extends Activity {

    private static final String TAG = "NbIotSatellite";
    private static final String MY_SHARED_PREF = "MySharedPref";
    private static final String SHARED_PREF_KEY = "supported_stated";
    TextView mTextView;
    private boolean mSatelliteSupported = false;
    public static TestSatelliteService sSatelliteService;
    private SatelliteManager mSatelliteManager;
    private TestSatelliteSupportedStateCallback mSatelliteSupportedStateCallback;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sSatelliteService = SatelliteTestApp.getTestSatelliteService();
        mSatelliteManager = getSystemService(SatelliteManager.class);

        setContentView(R.layout.activity_NbIotSatellite);
        findViewById(R.id.testRegisterForSupportedStateChanged)
                .setOnClickListener(this::testRegisterForSupportedStateChanged);
        findViewById(R.id.testUnregisterForSupportedStateChanged)
                .setOnClickListener(this::testUnregisterForSupportedStateChanged);
        findViewById(R.id.testRequestIsSupported)
                .setOnClickListener(this::testRequestIsSupported);
        findViewById(R.id.reportSatelliteSupportedFromModem)
                .setOnClickListener(this::reportSatelliteSupportedFromModem);
        findViewById(R.id.reportSatelliteNotSupportedFromModem)
                .setOnClickListener(this::reportSatelliteNotSupportedFromModem);
        findViewById(R.id.showCurrentSatelliteSupportedStated)
                .setOnClickListener(this::showCurrentSatelliteSupportedStated);
        findViewById(R.id.Back).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(NbIotSatellite.this, SatelliteTestApp.class));
            }
        });

        mTextView = findViewById(R.id.text_id);
    }

    protected class TestSatelliteSupportedStateCallback implements SatelliteSupportedStateCallback {
        @Override
        public void onSatelliteSupportedStateChanged(boolean supported) {
            mSatelliteSupported = supported;
            updateLogMessage("onSatelliteSupportedStateChanged: "
                    + (mSatelliteSupported ? "Satellite is supported"
                    : "Satellite is not supported"));
            Log.d(TAG, "onSatelliteSupportedStateChanged(): supported="
                    + mSatelliteSupported);
        }
    }

    @SuppressLint("MissingPermission")
    private void testRegisterForSupportedStateChanged(View view) {
        if (mSatelliteSupportedStateCallback == null) {
            mSatelliteSupportedStateCallback = new TestSatelliteSupportedStateCallback();
        }
        int result = mSatelliteManager.registerForSupportedStateChanged(Runnable::run,
                mSatelliteSupportedStateCallback);

        if (result == SATELLITE_RESULT_SUCCESS) {
            updateLogMessage("testRegisterForSupportedStateChanged(): "
                    + "registered mSatelliteSupportedStateCallback");
        } else {
            updateLogMessage("Failed to registerForSupportedStateChanged(), reason=" + result);
        }
    }

    @SuppressLint("MissingPermission")
    private void testUnregisterForSupportedStateChanged(View view) {
        if (mSatelliteSupportedStateCallback != null) {
            mSatelliteManager.unregisterForSupportedStateChanged(mSatelliteSupportedStateCallback);
            mSatelliteSupportedStateCallback = null;
            updateLogMessage("testUnregisterForSupportedStateChanged(): unregister callback.");
        } else {
            updateLogMessage("testUnregisterForSupportedStateChanged(): ignored, "
                    + "mSatelliteSupportedStateCallback is already null");
        }
    }

    private void testRequestIsSupported(View view) {
        final AtomicReference<Boolean> enabled = new AtomicReference<>();
        final AtomicReference<Integer> errorCode = new AtomicReference<>();
        OutcomeReceiver<Boolean, SatelliteManager.SatelliteException> mReceiver =
                new OutcomeReceiver<>() {
                        @Override
                        public void onResult(Boolean result) {
                            enabled.set(result);
                            updateLogMessage("Status for requestIsSupported result: "
                                    + enabled.get());
                        }

                        @Override
                        public void onError(SatelliteManager.SatelliteException exception) {
                            errorCode.set(exception.getErrorCode());
                            updateLogMessage("Status for requestIsSupported error : "
                                    + SatelliteErrorUtils.mapError(errorCode.get()));
                        }
                    };
        mSatelliteManager.requestIsSupported(Runnable::run, mReceiver);
    }

    private void showCurrentSatelliteSupportedStated(View view) {
        boolean mModemSupportedState = sSatelliteService.getSatelliteSupportedState();
        updateLogMessage("reported supported state is " + mSatelliteSupported
                        + ", modem supported state is " + mModemSupportedState);
    }

    private void reportSatelliteSupportedFromModem(View view) {
        sSatelliteService.updateSatelliteSupportedState(true);
    }

    private void reportSatelliteNotSupportedFromModem(View view) {
        sSatelliteService.updateSatelliteSupportedState(false);
    }

    // Fetch the stored data in onResume()
    // Because this is what will be called when the app opens again
    @Override
    protected void onResume() {
        super.onResume();
        // Fetching the stored data from the SharedPreference
        SharedPreferences sh = getSharedPreferences("MySharedPref", MODE_PRIVATE);
        boolean isProvisioned = sh.getBoolean("provision_state", mSatelliteSupported);

        // Setting the fetched data
        mSatelliteSupported = isProvisioned;
    }

    // Store the data in the SharedPreference in the onPause() method
    // When the user closes the application onPause() will be called and data will be stored
    @Override
    protected void onPause() {
        super.onPause();
        // Creating a shared pref object with a file name "MySharedPref" in private mode
        SharedPreferences sharedPreferences = getSharedPreferences(MY_SHARED_PREF, MODE_PRIVATE);
        SharedPreferences.Editor myEdit = sharedPreferences.edit();

        // write all the data entered by the user in SharedPreference and apply
        myEdit.putBoolean(SHARED_PREF_KEY, mSatelliteSupported);
        myEdit.apply();
    }

    private void updateLogMessage(String message) {
        runOnUiThread(() -> mTextView.setText(message));
    }

    protected void onDestroy() {
        super.onDestroy();
        SharedPreferences sharedPreferences = getSharedPreferences(MY_SHARED_PREF, MODE_PRIVATE);

        final SharedPreferences.Editor sharedPrefsEditor = sharedPreferences.edit();
        sharedPrefsEditor.remove(SHARED_PREF_KEY);
        sharedPrefsEditor.apply();
    }
}
