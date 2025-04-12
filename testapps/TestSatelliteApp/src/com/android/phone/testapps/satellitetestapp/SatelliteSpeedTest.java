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
import android.os.AsyncTask;
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
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

public class SatelliteSpeedTest extends Activity {

    private static final String TAG = "SatelliteSpeedTest";
    Handler mHandler;
    NetworkCallback mSatelliteConstrainNetworkCallback;
    ConnectivityManager mConnectivityManager;
    NetworkRequest mRequest;
    Context mContext;
    private String fileName = "100KB";
    private int fileSize = 100;
    final int NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED = 37;
    public TextView statusText;
    public RadioGroup radioGroup;
    public ProgressBar progressBar;
    public TextView speedText;
    public TextView progressText;
    public TextView timeText;
    public Network mNetwork;
    private TextView mSatDataModeTextView;
    private FileUploadTask fileUploadTask;
    private FileDownloadTask fileDownloadTask;
    private Button uploadTask;
    private Button downloadTask;
    private Button stopTask;
    private int mSubId = SubscriptionManager.DEFAULT_SUBSCRIPTION_ID;
    private static final int INVALID_SUB_ID = -1;
    private TelephonyManager mTelephonyManager;
    private SatelliteSpeedTest.RadioInfoTelephonyCallback mTelephonyCallback;
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
                    Log.d(TAG, "Satellite network lost");
                    if (mNetwork != null) {
                        displayMessageInUI("Satellite network lost");
                        disableView();
                        stopTask();
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
        mConnectivityManager = getSystemService(ConnectivityManager.class);
        TestSatelliteUtils.setupEdgeToEdge(this);
        mHandler = new Handler(Looper.getMainLooper());
        mContext = getApplicationContext();

        setContentView(R.layout.activity_SatelliteSpeedTest);
        mSubId = getIntent().getIntExtra("SUBID", SubscriptionManager.DEFAULT_SUBSCRIPTION_ID);
        int satDataMode =
                getIntent()
                        .getIntExtra(
                                "SATELLITE_DATA_MODE",
                                SatelliteManager.SATELLITE_DATA_SUPPORT_RESTRICTED);
        mSatDataModeTextView = findViewById(R.id.satelliteDataMode);
        updateSatelliteDataMode(satDataMode);

        progressBar = findViewById(R.id.ProgressBar);
        statusText = findViewById(R.id.StatusText);
        speedText = findViewById(R.id.SpeedText);
        progressText = findViewById(R.id.ProgressText);
        timeText = findViewById(R.id.TimeText);

        uploadTask = findViewById(R.id.UploadDataInSatelliteMode);
        downloadTask = findViewById(R.id.DownloadDataInSatelliteMode);
        stopTask = findViewById(R.id.StopTask);
        radioGroup = findViewById(R.id.RadioGroup);
        disableView();
        displayMessageInUI("waiting for network");
        generateNetwork();
        uploadTask.setOnClickListener(view -> uploadData());

        downloadTask.setOnClickListener(view -> downloadData());

        stopTask.setOnClickListener(view -> stopTask());

        radioGroup.setOnCheckedChangeListener(
                new RadioGroup.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(RadioGroup group, int checkedId) {
                        RadioButton checkedRadioButton = findViewById(checkedId);
                        if (checkedRadioButton != null) {
                            Log.d(
                                    "RadioButton",
                                    "Selected option: " + checkedRadioButton.getText());
                            fileName = checkedRadioButton.getText().toString();
                            switch (fileName) {
                                case "100KB" -> {
                                    fileSize = 100;
                                }
                                case "1MB" -> {
                                    fileSize = 1024;
                                }
                                case "10MB" -> {
                                    fileSize = 10 * 1024;
                                }
                            }
                        }
                    }
                });

        if (mSubId != INVALID_SUB_ID) {
            mTelephonyManager =
                    ((TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE))
                            .createForSubscriptionId(mSubId);
        }
        registerServiceStateChange();
    }

    private void registerServiceStateChange() {
        if (mTelephonyCallback == null) {
            mTelephonyCallback = new SatelliteSpeedTest.RadioInfoTelephonyCallback();
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

    private void disableView() {
        mHandler.post(
                new Runnable() {
                    @Override
                    public void run() {
                        uploadTask.setEnabled(false);
                        downloadTask.setEnabled(false);
                        stopTask.setEnabled(false);
                        for (int i = 0; i < radioGroup.getChildCount(); i++) {
                            radioGroup.getChildAt(i).setEnabled(false);
                        }
                    }
                });
    }

    private void enableRadioGroup() {
        mHandler.post(
                new Runnable() {
                    @Override
                    public void run() {
                        for (int i = 0; i < radioGroup.getChildCount(); i++) {
                            radioGroup.getChildAt(i).setEnabled(true);
                        }
                    }
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

    public void setDefaultView() {
        enableButton(uploadTask);
        enableButton(downloadTask);
        disableButton(stopTask);
        enableRadioGroup();
    }

    private void stopTask() {
        if (fileDownloadTask != null && fileDownloadTask.getStatus() == AsyncTask.Status.RUNNING) {
            displayToast("Download Canceled.");
            fileDownloadTask.cancelDownload();
        }
        if (fileUploadTask != null && fileUploadTask.getStatus() == AsyncTask.Status.RUNNING) {
            displayToast("Upload Cancelled.");
            fileUploadTask.cancelUpload();
        }
    }

    private void uploadData() {
        statusText.setText("Starting Upload...");
        displayToast("uploading start");
        disableButton(uploadTask);
        disableButton(downloadTask);
        enableButton(stopTask);
        progressBar.setVisibility(View.VISIBLE);
        String filePathToUpload = RandomDataGenerator.getFilesDir() + fileName;
        createSampleFile();
        String uploadUrl = "http://speedtest.tele2.net/upload.php";
        fileUploadTask = new FileUploadTask();
        fileUploadTask.setActivity(this);
        fileUploadTask.execute(filePathToUpload, uploadUrl);
    }

    private void downloadData() {
        statusText.setText("Starting Download...");
        String downloadUrl = "http://speedtest.tele2.net/" + fileName + ".zip";
        displayToast("downloading start");
        disableButton(uploadTask);
        disableButton(downloadTask);
        enableButton(stopTask);
        progressBar.setVisibility(View.VISIBLE);
        fileDownloadTask = new FileDownloadTask();
        fileDownloadTask.setActivity(this);
        fileDownloadTask.execute(downloadUrl);
    }

    private void createSampleFile() {
        new RandomDataGenerator().generateRandomData(fileName, fileSize);
        displayToast("sample file created");
    }

    private void generateNetwork() {
        mSatelliteConstrainNetworkCallback =
                new NetworkCallback() {
                    @Override
                    public void onAvailable(@NonNull final Network network) {
                        Log.d(TAG, "SatelliteConstrainNetworkCallback: onAvailable");
                        mNetwork = network;
                        displayMessageInUI("");
                        setDefaultView();
                    }

                    @Override
                    public void onLost(Network network) {
                        displayMessageInUI("Satellite network lost");
                        disableView();
                        Log.d(TAG, "SatelliteConstrainNetworkCallback: Network Lost");
                        releasingNetwork();
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

    private void displayToast(String message) {
        mHandler.post(
                new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(mContext, message, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    public void updateUI(int progress, String speed, String time) {
        progressBar.setProgress(progress);
        progressText.setText("Progress: " + progress + "%");
        speedText.setText("Speed: " + speed);
        timeText.setText("Time: " + time);
    }

    public void finishUI(String result, String averageSpeed, String time) {
        statusText.setText(result);
        speedText.setText("Average Speed: " + averageSpeed);
        timeText.setText("Time: " + time);
        progressText.setText("");
        progressBar.setVisibility(View.GONE);
    }

    public void resetUI(String status, String averageSpeed, String time) {
        statusText.setText(status);
        speedText.setText("Average Speed: " + averageSpeed);
        timeText.setText("Time: " + time);
        progressText.setText("");
        progressBar.setVisibility(View.GONE);
    }

    private void displayMessageInUI(String message) {
        runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        // Update your UI elements here
                        statusText.setText(message);
                    }
                });
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
}
