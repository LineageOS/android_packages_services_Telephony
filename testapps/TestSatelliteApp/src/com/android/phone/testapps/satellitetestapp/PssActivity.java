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
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.telephony.IIntegerConsumer;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.satellite.stub.ISatellite;
import android.telephony.satellite.stub.ISatelliteListener;
import android.telephony.satellite.stub.NtnSignalStrength;
import android.telephony.satellite.stub.PointingInfo;
import android.telephony.satellite.stub.SatelliteCapabilities;
import android.telephony.satellite.stub.SatelliteDatagram;
import android.telephony.satellite.stub.SatelliteModemEnableRequestAttributes;
import android.telephony.satellite.stub.SatelliteSubscriptionInfo;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.NumberPicker;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Activity to bind PSS. */
public class PssActivity extends Activity implements AdapterView.OnItemSelectedListener {

    private static final String TAG = "PssActivity";
    private static final String SATELLITE_ACTION = "android.telephony.satellite.SatelliteService";
    private static final String PSS_PACKAGE = "com.google.android.satellite";
    private static final String PIXEL_2024_EXPERIENCE =
            "com.google.android.feature.PIXEL_2024_EXPERIENCE";

    private ISatellite mSatelliteService;
    private SubscriptionManager mSubscriptionManager;
    private boolean mIsDemoModeSwitchEnabled = false;
    private boolean mIsEnabledSwitchEnabled = false;
    private int mIterations = 1;
    private int mSuccessCount = 0;
    private int mCurrentIteration = 0;
    private long mTotalTime = 0;
    private long mStartTime;
    private String mIccId = "";
    private Runnable mTask;
    private TextView mLogTextView;
    private final ArrayDeque<String> mLogQueue = new ArrayDeque();
    private static final int QUEUE_SIZE = 10;

    private List<Carrier> mSubList;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!getPackageManager().hasSystemFeature(PIXEL_2024_EXPERIENCE)) {
            Toast.makeText(this, "This activity is not supported on this device",
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        setContentView(R.layout.activity_Pss);
        setTitle(R.string.PssActivity);

        initViews();
        getSubscriptionInfo();
        bindPss();
    }

    private void getSubscriptionInfo() {
        if (mSubscriptionManager == null) {
            mSubscriptionManager =
                    (SubscriptionManager)
                            getBaseContext()
                                    .getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        }
        mSubList = new ArrayList();

        for (SubscriptionInfo subInfo : mSubscriptionManager.getAllSubscriptionInfoList()) {
            logi("iccId: " + subInfo.getIccId() + " carrier name " + subInfo.getCarrierName());
            boolean isNtn = subInfo.isOnlyNonTerrestrialNetwork();
            mSubList.add(
                    new Carrier(subInfo.getIccId(), subInfo.getCarrierName().toString(), isNtn));

            if (isNtn) {
                mIccId = subInfo.getIccId();
                logi("NTN iccId: " + mIccId);
            }
        }
        Spinner mSpinner = findViewById(R.id.spinner);
        ArrayAdapter<Carrier> adapter =
                new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, mSubList);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mSpinner.setAdapter(adapter);
        mSpinner.setOnItemSelectedListener(this);
    }

    private void initViews() {
        findViewById(R.id.requestSatelliteEnable).setOnClickListener(this::requestSatelliteEnable);

        mLogTextView = findViewById(R.id.logView);
        Switch demoSwitch = findViewById(R.id.DemoModeSwitch);
        demoSwitch.setOnCheckedChangeListener(
                (buttonView, isChecked) -> mIsDemoModeSwitchEnabled = isChecked);

        Switch enableSwitch = findViewById(R.id.EnableSwitch);
        enableSwitch.setOnCheckedChangeListener(
                (buttonView, isChecked) -> mIsEnabledSwitchEnabled = isChecked);

        NumberPicker numberPicker = findViewById(R.id.numberPicker);
        numberPicker.setMinValue(1);
        numberPicker.setMaxValue(100);
        numberPicker.setValue(1);
        numberPicker.setOnValueChangedListener((picker, oldVal, newVal) -> mIterations = newVal);
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        Carrier selectedItem = mSubList.get(position);
        mIccId = selectedItem.getIccId();
        Toast.makeText(this, "Selected: " + selectedItem.getName(), Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {
        // Optional: Handle the case where nothing is selected
    }

    private void requestSatelliteEnable(View v) {
        mCurrentIteration = 1;
        mTotalTime = 0;
        mSuccessCount = 0;
        mStartTime = System.currentTimeMillis();
        boolean isSwitchEnabled = mIsEnabledSwitchEnabled;
        mTask =
                () -> {
                    IIntegerConsumer callback =
                            new IIntegerConsumer.Stub() {
                                @Override
                                public void accept(int result) {
                                    long duration = System.currentTimeMillis() - mStartTime;
                                    mStartTime = System.currentTimeMillis();
                                    logi(
                                            "Request type: "
                                                    + (mIsEnabledSwitchEnabled
                                                            ? "Enable"
                                                            : "Disable")
                                                    + " mCurrentIteration: "
                                                    + mCurrentIteration
                                                    + " requestSatelliteEnable error: "
                                                    + result
                                                    + " duration: "
                                                    + duration);
                                    mIsEnabledSwitchEnabled = !mIsEnabledSwitchEnabled;
                                    if (result == 0) {
                                        mTotalTime += duration;
                                        mSuccessCount += 1;
                                    }
                                    if (mCurrentIteration < mIterations) {
                                        mCurrentIteration++;
                                        mTask.run();
                                    } else {
                                        logi(
                                                "Pass: "
                                                        + mSuccessCount
                                                        + "/"
                                                        + mIterations
                                                        + " Average duration in ms : "
                                                        + mTotalTime / mSuccessCount);
                                        mIsEnabledSwitchEnabled = isSwitchEnabled;
                                    }
                                }
                            };
                    try {
                        mSatelliteService.requestSatelliteEnabled(
                                createModemEnableRequest(), callback);
                    } catch (Exception e) {
                        logi("requestSatelliteEnable: " + e);
                    }
                };
        mTask.run();
    }

    private SatelliteModemEnableRequestAttributes createModemEnableRequest() {
        String apn = "pixel.ntn";
        SatelliteModemEnableRequestAttributes attributes =
                new SatelliteModemEnableRequestAttributes();
        attributes.isEnabled = mIsEnabledSwitchEnabled;
        attributes.isDemoMode = mIsDemoModeSwitchEnabled;
        SatelliteSubscriptionInfo info = new SatelliteSubscriptionInfo();
        info.iccId = mIccId;
        info.niddApn = apn;
        attributes.satelliteSubscriptionInfo = info;
        return attributes;
    }

    private final class PssConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            logi("onServiceConnected Service: " + name);
            mSatelliteService = ISatellite.Stub.asInterface(service);
            try {
                mSatelliteService.setSatelliteListener(mSatelliteListener);
            } catch (Exception e) {
                Log.e(TAG, "onServiceConnected: setListener error: ", e);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            logi("onServiceDisconnected Service: " + name);
        }
    }

    private void bindPss() {
        logi("Binding PSS...");
        Intent intent = new Intent(SATELLITE_ACTION);
        intent.setPackage(PSS_PACKAGE);
        PssConnection pssConnection = new PssConnection();

        try {
            getBaseContext()
                    .bindService(
                            intent,
                            pssConnection,
                            Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE);
        } catch (SecurityException exception) {
            logi("BindService failed " + exception);
        }
    }

    private final ISatelliteListener mSatelliteListener =
            new ISatelliteListener.Stub() {
                @Override
                public void onSatelliteDatagramReceived(
                        SatelliteDatagram datagram, int pendingCount) {
                    logi("onSatelliteDatagramReceived: " + Arrays.toString(datagram.data));
                }

                @Override
                public void onPendingDatagrams() {
                    logi("onPendingDatagrams: ");
                }

                @Override
                public void onSatellitePositionChanged(PointingInfo pointingInfo) {
                    logi(
                            "onSatellitePositionChanged: Azimuth= "
                                    + pointingInfo.satelliteAzimuth
                                    + " Elevation "
                                    + pointingInfo.satelliteElevation);
                }

                @Override
                public void onSatelliteModemStateChanged(int state) {
                    logi("onSatelliteModemStateChanged: " + state);
                }

                @Override
                public void onNtnSignalStrengthChanged(NtnSignalStrength ntnSignalStrength) {
                    logi("onNtnSignalStrengthChanged: " + ntnSignalStrength.signalStrengthLevel);
                }

                @Override
                public void onSatelliteCapabilitiesChanged(SatelliteCapabilities capabilities) {
                    logi(
                            "onSatelliteCapabilitiesChanged: "
                                    + Arrays.toString(capabilities.supportedRadioTechnologies));
                }

                @Override
                public void onSatelliteSupportedStateChanged(boolean supported) {
                    logi("onSatelliteSupportedStateChanged: " + supported);
                }

                @Override
                public void onRegistrationFailure(int causeCode) {
                    logi("onRegistrationFailure: " + causeCode);
                }

                @Override
                public void onTerrestrialNetworkAvailableChanged(boolean isAvailable) {
                    logi("onTerrestrialNetworkAvailableChanged: " + isAvailable);
                }
            };

    private void logi(String str) {
        Log.i(TAG, str);
        if (mLogQueue.size() >= QUEUE_SIZE) {
            mLogQueue.pollLast();
        }
        mLogQueue.offerFirst(str);
        updateTextView();
    }

    private void updateTextView() {
        StringBuilder sb = new StringBuilder();
        for (String s : mLogQueue) {
            sb.append(s).append("\n");
        }
        runOnUiThread(() -> mLogTextView.setText(sb.toString()));
    }

    private static class Carrier {
        private final String mIccId;
        private final String mName;
        private final boolean mIsNtn;

        Carrier(String iccId, String name, boolean isNtn) {
            this.mIccId = iccId;
            this.mName = name;
            this.mIsNtn = isNtn;
        }

        public boolean isNtn() {
            return mIsNtn;
        }

        public String getIccId() {
            return mIccId;
        }

        public String getName() {
            return mName;
        }

        @Override
        public String toString() {
            return mName + (isNtn() ? " (NTN)" : "");
        }
    }
}
