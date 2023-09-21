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
import android.os.OutcomeReceiver;
import android.telephony.satellite.SatelliteCapabilities;
import android.telephony.satellite.SatelliteManager;
import android.telephony.satellite.stub.SatelliteResult;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.TextView;

import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Activity related to SatelliteControl APIs for satellite.
 */
public class SatelliteControl extends Activity {

    private static final long TIMEOUT = 3000;

    private SatelliteManager mSatelliteManager;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mSatelliteManager = getSystemService(SatelliteManager.class);

        setContentView(R.layout.activity_SatelliteControl);
        findViewById(R.id.enableSatellite)
                .setOnClickListener(this::enableSatelliteApp);
        findViewById(R.id.disableSatellite)
                .setOnClickListener(this::disableSatelliteApp);
        findViewById(R.id.requestIsSatelliteEnabled)
                .setOnClickListener(this::requestIsSatelliteEnabledApp);
        findViewById(R.id.requestIsDemoModeEnabled)
                .setOnClickListener(this::requestIsDemoModeEnabledApp);
        findViewById(R.id.requestIsSatelliteSupported)
                .setOnClickListener(this::requestIsSatelliteSupportedApp);
        findViewById(R.id.requestSatelliteCapabilities)
                .setOnClickListener(this::requestSatelliteCapabilitiesApp);
        findViewById(R.id.requestIsSatelliteCommunicationAllowedForCurrentLocation)
                .setOnClickListener(
                this::requestIsSatelliteCommunicationAllowedForCurrentLocationApp);
        findViewById(R.id.requestTimeForNextSatelliteVisibility)
                .setOnClickListener(this::requestTimeForNextSatelliteVisibilityApp);
        findViewById(R.id.Back).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(SatelliteControl.this, SatelliteTestApp.class));
            }
        });
    }

    private void enableSatelliteApp(View view) {
        LinkedBlockingQueue<Integer> error = new LinkedBlockingQueue<>(1);
        mSatelliteManager.requestSatelliteEnabled(true, true, Runnable::run, error::offer);
        TextView textView = findViewById(R.id.text_id);
        try {
            Integer value = error.poll(TIMEOUT, TimeUnit.MILLISECONDS);
            if (value == null) {
                textView.setText("Timed out to enable the satellite");
            } else if (value != SatelliteResult.SATELLITE_RESULT_SUCCESS) {
                textView.setText("Failed to enable the satellite, error ="
                        + SatelliteErrorUtils.mapError(value));
            } else {
                textView.setText("Successfully enabled the satellite");
            }
        } catch (InterruptedException e) {
            textView.setText("Enable SatelliteService exception caught =" + e);
        }
    }

    private void disableSatelliteApp(View view) {
        LinkedBlockingQueue<Integer> error = new LinkedBlockingQueue<>(1);
        mSatelliteManager.requestSatelliteEnabled(false, true, Runnable::run, error::offer);
        TextView textView = findViewById(R.id.text_id);
        try {
            Integer value = error.poll(TIMEOUT, TimeUnit.MILLISECONDS);
            if (value == null) {
                textView.setText("Timed out to disable the satellite");
            } else if (value != SatelliteResult.SATELLITE_RESULT_SUCCESS) {
                textView.setText("Failed to disable the satellite, error ="
                        + SatelliteErrorUtils.mapError(value));
            } else {
                textView.setText("Successfully disabled the satellite");
            }
        } catch (InterruptedException e) {
            textView.setText("Disable SatelliteService exception caught =" + e);
        }
    }

    private void requestIsSatelliteEnabledApp(View view) {
        final AtomicReference<Boolean> enabled = new AtomicReference<>();
        final AtomicReference<Integer> errorCode = new AtomicReference<>();
        OutcomeReceiver<Boolean, SatelliteManager.SatelliteException> receiver =
                new OutcomeReceiver<>() {
            @Override
            public void onResult(Boolean result) {
                enabled.set(result);
                TextView textView = findViewById(R.id.text_id);
                if (enabled.get()) {
                    textView.setText("requestIsSatelliteEnabled is true");
                } else {
                    textView.setText("Status for requestIsSatelliteEnabled result : "
                            + enabled.get());
                }
            }

            @Override
            public void onError(SatelliteManager.SatelliteException exception) {
                errorCode.set(exception.getErrorCode());
                TextView textView = findViewById(R.id.text_id);
                textView.setText("Status for requestIsSatelliteEnabled error : "
                        + SatelliteErrorUtils.mapError(errorCode.get()));
            }
        };
        mSatelliteManager.requestIsSatelliteEnabled(Runnable::run, receiver);
    }

    private void requestIsDemoModeEnabledApp(View view) {
        final AtomicReference<Boolean> enabled = new AtomicReference<>();
        final AtomicReference<Integer> errorCode = new AtomicReference<>();
        OutcomeReceiver<Boolean, SatelliteManager.SatelliteException> receiver =
                new OutcomeReceiver<>() {
            @Override
            public void onResult(Boolean result) {
                enabled.set(result);
                TextView textView = findViewById(R.id.text_id);
                if (enabled.get()) {
                    textView.setText("requestIsDemoModeEnabled is true");
                } else {
                    textView.setText("Status for requestIsDemoModeEnabled result : "
                            + enabled.get());
                }
            }

            @Override
            public void onError(SatelliteManager.SatelliteException exception) {
                errorCode.set(exception.getErrorCode());
                TextView textView = findViewById(R.id.text_id);
                textView.setText("Status for requestIsDemoModeEnabled error : "
                        + SatelliteErrorUtils.mapError(errorCode.get()));
            }
        };
        mSatelliteManager.requestIsDemoModeEnabled(Runnable::run, receiver);
    }

    private void requestIsSatelliteSupportedApp(View view) {
        final AtomicReference<Boolean> enabled = new AtomicReference<>();
        final AtomicReference<Integer> errorCode = new AtomicReference<>();
        OutcomeReceiver<Boolean, SatelliteManager.SatelliteException> receiver =
                new OutcomeReceiver<>() {
            @Override
            public void onResult(Boolean result) {
                enabled.set(result);
                TextView textView = findViewById(R.id.text_id);
                if (enabled.get()) {
                    textView.setText("requestIsSatelliteSupported is true");
                } else {
                    textView.setText("Status for requestIsSatelliteSupported result : "
                            + enabled.get());
                }
            }

            @Override
            public void onError(SatelliteManager.SatelliteException exception) {
                errorCode.set(exception.getErrorCode());
                TextView textView = findViewById(R.id.text_id);
                textView.setText("Status for requestIsSatelliteSupported error : "
                        + SatelliteErrorUtils.mapError(errorCode.get()));
            }
        };
        mSatelliteManager.requestIsSatelliteSupported(Runnable::run, receiver);
    }

    private void requestSatelliteCapabilitiesApp(View view) {
        final AtomicReference<SatelliteCapabilities> capabilities = new AtomicReference<>();
        final AtomicReference<Integer> errorCode = new AtomicReference<>();
        OutcomeReceiver<SatelliteCapabilities, SatelliteManager.SatelliteException> receiver =
                new OutcomeReceiver<>() {
            @Override
            public void onResult(SatelliteCapabilities result) {
                capabilities.set(result);
                TextView textView = findViewById(R.id.text_id);
                textView.setText("Status for requestSatelliteCapabilities result: "
                        + capabilities.get());
            }

            @Override
            public void onError(SatelliteManager.SatelliteException exception) {
                errorCode.set(exception.getErrorCode());
                TextView textView = findViewById(R.id.text_id);
                textView.setText("Status for requestSatelliteCapabilities error : "
                        + SatelliteErrorUtils.mapError(errorCode.get()));
            }
        };
        mSatelliteManager.requestSatelliteCapabilities(Runnable::run, receiver);
    }

    private void requestIsSatelliteCommunicationAllowedForCurrentLocationApp(View view) {
        final AtomicReference<Boolean> enabled = new AtomicReference<>();
        final AtomicReference<Integer> errorCode = new AtomicReference<>();
        String display = "requestIsSatelliteCommunicationAllowedForCurrentLocation";
        OutcomeReceiver<Boolean, SatelliteManager.SatelliteException> receiver =
                new OutcomeReceiver<>() {
            @Override
            public void onResult(Boolean result) {
                enabled.set(result);
                TextView textView = findViewById(R.id.text_id);
                if (enabled.get()) {
                    textView.setText(display + "is true");
                } else {
                    textView.setText("Status for" + display + "result: " + enabled.get());
                }

            }

            @Override
            public void onError(SatelliteManager.SatelliteException exception) {
                errorCode.set(exception.getErrorCode());
                TextView textView = findViewById(R.id.text_id);
                textView.setText("Status for"  + display + "error: "
                        + SatelliteErrorUtils.mapError(errorCode.get()));
            }
        };
        mSatelliteManager.requestIsSatelliteCommunicationAllowedForCurrentLocation(Runnable::run,
                receiver);
    }

    private void requestTimeForNextSatelliteVisibilityApp(View view) {
        final AtomicReference<Duration> nextVisibilityDuration = new AtomicReference<>();
        final AtomicReference<Integer> errorCode = new AtomicReference<>();
        OutcomeReceiver<Duration, SatelliteManager.SatelliteException> receiver =
                new OutcomeReceiver<>() {
            @Override
            public void onResult(Duration result) {
                nextVisibilityDuration.set(result);
                TextView textView = findViewById(R.id.text_id);
                textView.setText("Status for requestTimeForNextSatelliteVisibility result : "
                        + result.getSeconds());
            }

            @Override
            public void onError(SatelliteManager.SatelliteException exception) {
                errorCode.set(exception.getErrorCode());
                TextView textView = findViewById(R.id.text_id);
                textView.setText("Status for requestTimeForNextSatelliteVisibility error : "
                        + SatelliteErrorUtils.mapError(errorCode.get()));
            }
        };
        mSatelliteManager.requestTimeForNextSatelliteVisibility(Runnable::run, receiver);
    }
}
