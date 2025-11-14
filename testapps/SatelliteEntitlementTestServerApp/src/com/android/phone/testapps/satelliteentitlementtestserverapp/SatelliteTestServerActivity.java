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

package com.android.phone.testapps.satelliteentitlementtestserverapp;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;

/**
 * Activity related to SatelliteTestServerActivity.
 */
public class SatelliteTestServerActivity extends Activity {
    private static final String TAG = "SatelliteTestActivity";
    private static final int HTTP_RESPONSE_CODE_DEFAULT = -1;
    public static final int HTTP_RESPONSE_CODE_OK = 200;
    public static final int HTTP_RESPONSE_CODE_INTERNAL_SERVER_ERROR = 500;
    public static final int HTTP_RESPONSE_CODE_SERVICE_UNAVAILABLE = 503;
    private static final int ENTITLEMENT_STATUS_DISABLED = 0;
    private static final int ENTITLEMENT_STATUS_ENABLED = 1;
    private static final int ENTITLEMENT_STATUS_INCOMPATIBLE = 2;
    private static final int ENTITLEMENT_STATUS_PROVISIONING = 3;
    private static final int RESPONSE_CODE_503_NO_RETRY_AFTER = -2;
    private static final long DELAY_DURATION_MILLISECONDS = 1000; // 1 seconds
    private static final String DEFAULT_PLMN_LIST =
            " \"PLMNAllowed\":[{\"PLMN\":\"40445\",\"DataPlanType\":\"metered\","
                    + "\"AllowedServicesInfo\":[{\"AllowedServices\":{\"ServiceType\":\"data\","
                    + "\"ServicePolicy\":\"unconstrained\"}}]}]";
    private boolean mIsServerUp = false;
    private int mEntitlementStatus = ENTITLEMENT_STATUS_ENABLED;
    private static int mResponseCode = HTTP_RESPONSE_CODE_OK;
    private int mResponseCount = 0;
    private boolean mUserInputPlmnList = false;
    private SatelliteInfoUiUpdater mSatelliteInfoUiUpdater;
    private Button mServerButton, mRefreshButton;
    private TextView mServerStatusTextView, mClientRequestTextView, mServerResponseTextView,
            mPlmnsDisplayTextView, mNtnStatusTextView, mUsingNtnTextView,
            mAvailableServicesTextView, mCurrentPlmnTextView;
    private EditText mPlmnChangeEditText;
    private Handler mUiHandler;
    private SimpleAndroidHttpServer mTestHttpServer;

    public void updateUiWithCurrentSatelliteInfo(boolean isDelayReq) {
        Runnable delayedTaskRunnable = () -> {
            // --- This code runs on the UI thread ---
            Log.d(TAG, "Delayed task is executing...");
            mSatelliteInfoUiUpdater.updateCurrentPlmnTextView(mCurrentPlmnTextView);
            mSatelliteInfoUiUpdater.updateSatellitePlmnTextView(mPlmnsDisplayTextView);
            mSatelliteInfoUiUpdater.updateNtnStatusTextView(mNtnStatusTextView);
            mSatelliteInfoUiUpdater.updateCurrentNtnStatusTextView(mUsingNtnTextView);
            mSatelliteInfoUiUpdater.updateAvailableServicesTextView(mAvailableServicesTextView);
            mRefreshButton.setVisibility(View.VISIBLE);
        };
        mUiHandler.postDelayed(delayedTaskRunnable, (isDelayReq) ? DELAY_DURATION_MILLISECONDS : 0);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.satellite_activity);
        Utils.setupEdgeToEdge(this);
        mServerStatusTextView = findViewById(R.id.serverStatusTextView);
        mClientRequestTextView = findViewById(R.id.clientRequestTextView);
        mServerResponseTextView = findViewById(R.id.serverResponseCodeTextView);
        mPlmnsDisplayTextView = findViewById(R.id.getPlmnsDisplay);
        mNtnStatusTextView = findViewById(R.id.getIsNtn);
        mUsingNtnTextView = findViewById(R.id.getIsUsingNtn);
        mAvailableServicesTextView = findViewById(R.id.availableServices);

        mSatelliteInfoUiUpdater = new SatelliteInfoUiUpdater(this);
        mCurrentPlmnTextView = findViewById(R.id.currentPlmn);
        mSatelliteInfoUiUpdater.updateCurrentPlmnTextView(mCurrentPlmnTextView);
        mServerButton = findViewById(R.id.serverButton);
        mUiHandler = new Handler(Looper.getMainLooper());
        mServerButton.setOnClickListener(view -> {
            if (mIsServerUp) {
                stopServer();
                mIsServerUp = false;
            } else if (mSatelliteInfoUiUpdater.getActiveSubId()
                    != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                startServer();
                mIsServerUp = true;
                Runnable delayedTaskRunnable =
                        () -> mSatelliteInfoUiUpdater.enableSatelliteEntitlementConfig();
                mUiHandler.postDelayed(delayedTaskRunnable, DELAY_DURATION_MILLISECONDS);
            } else {
                Log.d(TAG, "MainActivity onCreate: Invalid Sub");
                Toast.makeText(this, "Invalid Subscription", Toast.LENGTH_SHORT).show();
            }
        });

        AdapterView entitlementStatusSpinner = findViewById(R.id.entitlementStatusSpinner);
        ArrayAdapter<CharSequence> entitlementArrayAdapter = ArrayAdapter.createFromResource(this,
                R.array.entitlement_status_for_satellite, android.R.layout.simple_spinner_item);
        entitlementArrayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_item);
        entitlementStatusSpinner.setAdapter(entitlementArrayAdapter);
        entitlementStatusSpinner.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> parent, View view, int position,
                            long id) {
                        updateSatelliteEntitlementStatus(
                                parent.getItemAtPosition(position).toString());
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {
                    }
                });

        mPlmnChangeEditText = findViewById(R.id.plmnChangeEditText);
        Button plmnChangeButton = findViewById(R.id.plmnChangeButton);
        Button plmnRestroreButton = findViewById(R.id.plmnRestoreButton);
        mPlmnChangeEditText.setText(DEFAULT_PLMN_LIST);

        plmnChangeButton.setOnClickListener(v -> {
            mPlmnChangeEditText.setText(mPlmnChangeEditText.getText());
            mUserInputPlmnList = true;
        });

        plmnRestroreButton.setOnClickListener(v -> {
            mPlmnChangeEditText.setText(DEFAULT_PLMN_LIST);
            mUserInputPlmnList = false;
        });

        AdapterView responseSpinner = findViewById(R.id.responseSpinner);
        ArrayAdapter<CharSequence> responseArrayAdapter = ArrayAdapter.createFromResource(this,
                R.array.response, android.R.layout.simple_spinner_item);
        responseArrayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_item);
        responseSpinner.setAdapter(responseArrayAdapter);
        responseSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String responseCode = parent.getItemAtPosition(position).toString();
                updateResponseCode(responseCode);
                Log.d(TAG, "Response code is changed to " + responseCode);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        mRefreshButton = findViewById(R.id.refreshButton);
        mRefreshButton.setOnClickListener(view -> updateUiWithCurrentSatelliteInfo(false));
    }

    private static final int REQUEST_CODE_PHONE_STATE = 1;

    @Override
    protected void onResume() {
        super.onResume();
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_PHONE_STATE},
                    REQUEST_CODE_PHONE_STATE);
        }
        mSatelliteInfoUiUpdater.registerTelephonyCallback();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mSatelliteInfoUiUpdater.unRegisterTelephonyCallback();
    }

    private void startServer() {
        Utils.logd("StartServer method called");
        if (mTestHttpServer == null) {
            mTestHttpServer = new SimpleAndroidHttpServer(this);
        }
        if (!mTestHttpServer.isServerRunning()) {
            try {
                mTestHttpServer.start(); // Start daemon thread
                String serverStatus = "Server Started Successfully";
                mServerStatusTextView.setText(serverStatus);
                mServerButton.setText(R.string.stop_server);
            } catch (IOException e) {
                Utils.logd("Failed to start the server  Exp = " + e.getMessage());
            }
        } else {
            Utils.logd("Server already running.");
        }
    }

    private void stopServer() {
        Utils.logd("stopServer method called");
        if (mTestHttpServer != null) {
            mTestHttpServer.stop();
            mTestHttpServer = null;
            mResponseCount = 0;
            Utils.logd("Server stopped.");
        }
        mServerStatusTextView.setText(R.string.server_down);
        mServerButton.setText(R.string.start_server);
    }

    public int getResponseCode() {
        return mResponseCode;
    }


    public String getTS43ResponseForSatellite() {
        String t43Response =
                "{" + "  \"Vers\":{" + "    \"version\": \"1\"," + "    \"validity\": \"1728000\""
                        + "  }," + "  \"Token\":{"
                        + "    \"token\": \"kZYfCEpSsMr88KZVmab5UsZVzl+nWSsX\"" + "  },"
                        + "  \"ap2016\":{" + "    \"EntitlementStatus\" : \"" + mEntitlementStatus
                        + "\"" + getPLMNList() + "  },"
                        + "  \"eap-relay-packet\":\"EapAkaChallengeRequest\"" + "}";
        if (mResponseCode == HTTP_RESPONSE_CODE_OK) {
            Log.d(TAG, "-------------------------START-----------------------------");
            Log.d(TAG, t43Response);
            Log.d(TAG, "-------------------------END-----------------------------");
        }
        return t43Response;
    }

    private String getPLMNList() {
        return mEntitlementStatus == ENTITLEMENT_STATUS_ENABLED ? "," + getUserInputPlmnList() : "";
    }

    private String getUserInputPlmnList() {
        return mUserInputPlmnList ? mPlmnChangeEditText.getText().toString() : DEFAULT_PLMN_LIST;
    }

    public void updateClientRequestTextView(String status) {
        if (TextUtils.isEmpty(status)) {
            status = "Client Request: Sent " + ++mResponseCount + " responses to the clients";
        }
        String finalStatus = status;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mClientRequestTextView.setText(finalStatus);
            }
        });
    }

    public void updateServerStatusTextView(String status) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mServerStatusTextView.setText(status);
            }
        });
    }

    private void updateSatelliteEntitlementStatus(String status) {
        int resId = R.string.entitlement_status_disable;
        switch (status) {
            case "DISABLED":
                mEntitlementStatus = ENTITLEMENT_STATUS_DISABLED;
                break;
            case "ENABLED":
                mEntitlementStatus = ENTITLEMENT_STATUS_ENABLED;
                resId = R.string.entitlement_status_enable;
                break;
            case "INCOMPATIBLE":
                mEntitlementStatus = ENTITLEMENT_STATUS_INCOMPATIBLE;
                resId = R.string.entitlement_status_incompatible;
                break;
            case "PROVISIONING":
                mEntitlementStatus = ENTITLEMENT_STATUS_PROVISIONING;
                resId = R.string.entitlement_status_provisioning;
                break;
        }
        mClientRequestTextView.setText(resId);
    }

    private void updateResponseCode(String response) {
        int resId = R.string.response_200;
        switch (response) {
            case "200":
                mResponseCode = HTTP_RESPONSE_CODE_OK;
                break;
            case "NO":
                mResponseCode = HTTP_RESPONSE_CODE_DEFAULT;
                resId = R.string.response_no;
                break;
            case "500":
                mResponseCode = HTTP_RESPONSE_CODE_INTERNAL_SERVER_ERROR;
                resId = R.string.response_500;
                break;
            case "503_NO_RETRY_AFTER":
                mResponseCode = RESPONSE_CODE_503_NO_RETRY_AFTER;
                resId = R.string.response_503_no_retry_after;
                break;
            case "503":
                mResponseCode = HTTP_RESPONSE_CODE_SERVICE_UNAVAILABLE;
                resId = R.string.response_503;
                break;
        }
        mServerResponseTextView.setText(resId);
    }
}
