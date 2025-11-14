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
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SatelliteSpeedTest extends Activity
        implements TaskCallback, FileDownloadTask.NetworkProvider, FileUploadTask.NetworkProvider {

    private static final String TAG = "SatelliteSpeedTest";
    private ExecutorService mTaskExecutor;
    private Handler mHandler;
    private NetworkCallback mSatelliteConstrainNetworkCallback;
    private ConnectivityManager mConnectivityManager;
    private NetworkRequest mRequest;
    private Context mContext;
    private String mFileName = "100KB";
    private int mFileSize = 100;
    private String mUploadFilePath;
    private String mDownloadUrl;

    private static final String BASE_DOWNLOAD_URL = "http://speedtest.tele2.net/";
    private static final String UPLOAD_URL = "http://speedtest.tele2.net/upload.php";

    private TextView mStatusText;
    private RadioGroup mRadioGroup;
    private ProgressBar mProgressBar;
    private TextView mSpeedText;
    private TextView mProgressText;
    private TextView mTimeText;
    private TextView mSatDataModeTextView;
    private Button mUploadTaskButton;
    private Button mDownloadTaskButton;
    private Button mStopTaskButton;
    private volatile Network mNetwork;
    private int mSubId = SubscriptionManager.DEFAULT_SUBSCRIPTION_ID;
    private static final int INVALID_SUB_ID = -1;
    private TelephonyManager mTelephonyManager;
    private RadioInfoTelephonyCallback mTelephonyCallback;
    private ServiceState mServiceState;
    private boolean mIsSatellite;
    private SignalStrength mSignalStrength;
    private TextView mNetworkBandWidthTextView;
    private TextView mSignalStrengthTextView;

    enum TaskType {
        NONE,
        UPLOAD,
        DOWNLOAD
    }

    private volatile TaskType mCurrentRunningTaskType = TaskType.NONE;
    private volatile TaskType mPausedTaskType = TaskType.NONE;
    private volatile Runnable mCurrentTaskRunnable = null;
    private volatile boolean mIsPausedForNetwork = false;

    private volatile long mTimeElapsedBeforePauseMillis = 0;
    public volatile long currentDownloadBytes = 0;
    public volatile long currentUploadBytes = 0;

    @Override
    public Network getNetwork() {
        return mNetwork;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mTaskExecutor = Executors.newSingleThreadExecutor();
        mHandler = new Handler(Looper.getMainLooper());

        mConnectivityManager = getSystemService(ConnectivityManager.class);
        TestSatelliteUtils.setupEdgeToEdge(this);
        mContext = getApplicationContext();

        setContentView(R.layout.activity_SatelliteSpeedTest);
        mSubId = getIntent().getIntExtra("SUBID", SubscriptionManager.DEFAULT_SUBSCRIPTION_ID);
        int satDataMode =
                getIntent()
                        .getIntExtra(
                                "SATELLITE_DATA_MODE",
                                SatelliteManager.SATELLITE_DATA_SUPPORT_RESTRICTED);

        mSatDataModeTextView = findViewById(R.id.satelliteDataMode);
        mProgressBar = findViewById(R.id.ProgressBar);
        mStatusText = findViewById(R.id.StatusText);
        mNetworkBandWidthTextView = findViewById(R.id.networkBandWidthTextView);
        mSignalStrengthTextView = findViewById(R.id.signalStrengthTextView);
        mSpeedText = findViewById(R.id.SpeedText);
        mProgressText = findViewById(R.id.ProgressText);
        mTimeText = findViewById(R.id.TimeText);
        mUploadTaskButton = findViewById(R.id.UploadDataInSatelliteMode);
        mDownloadTaskButton = findViewById(R.id.DownloadDataInSatelliteMode);
        mStopTaskButton = findViewById(R.id.StopTask);
        mRadioGroup = findViewById(R.id.RadioGroup);

        updateSatelliteDataMode(satDataMode);
        setIdleView();
        displayMessageInUI("Waiting for Satellite Network...");

        mUploadTaskButton.setOnClickListener(view -> startUpload());
        mDownloadTaskButton.setOnClickListener(view -> startDownload());
        mStopTaskButton.setOnClickListener(view -> stopCurrentTask());
        mRadioGroup.setOnCheckedChangeListener(
                (group, checkedId) -> {
                    RadioButton checkedRadioButton = findViewById(checkedId);
                    if (checkedRadioButton != null) {
                        logd("Selected option: " + checkedRadioButton.getText());
                        mFileName = checkedRadioButton.getText().toString();
                        switch (mFileName) {
                            case "100KB" -> mFileSize = 100;
                            case "1MB" -> mFileSize = 1024;
                            case "10MB" -> mFileSize = 10 * 1024;
                            default -> mFileSize = 100;
                        }
                        mDownloadUrl = BASE_DOWNLOAD_URL + mFileName + ".zip";
                        mUploadFilePath = null;
                        logd(
                                "Selected file: "
                                        + mFileName
                                        + " ("
                                        + mFileSize
                                        + " KB). Download URL: "
                                        + mDownloadUrl);
                    }
                });

        RadioButton defaultButton = findViewById(R.id.radioButtonOption1);
        if (defaultButton != null) defaultButton.setChecked(true);
        mDownloadUrl = BASE_DOWNLOAD_URL + mFileName + ".zip";

        if (mSubId != INVALID_SUB_ID) {
            mTelephonyManager =
                    ((TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE))
                            .createForSubscriptionId(mSubId);
            registerServiceStateChange();
        } else {
            loge("Invalid Subscription ID provided.");
            displayMessageInUI("Error: Invalid Subscription ID.");
        }

        generateNetworkRequest();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDataMode();
        registerServiceStateChange();
        if (mNetwork == null) {
            logd("onResume: Network not available, requesting...");
            requestingNetwork();
        } else {
            logd("onResume: Network available.");
            if (mIsPausedForNetwork) {
                displayMessageInUI("Network available. Resuming task...");
            } else if (mCurrentRunningTaskType == TaskType.NONE) {
                setIdleView();
            } else {
                setActiveTaskView();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        logd("onDestroy called.");
        stopCurrentTask();
        unregisterServiceStateChange();
        releaseNetworkRequest();
        if (mTaskExecutor != null && !mTaskExecutor.isShutdown()) {
            mTaskExecutor.shutdown();
            logi("ExecutorService shutdown initiated.");
        }
    }

    private class RadioInfoTelephonyCallback extends TelephonyCallback
            implements TelephonyCallback.ServiceStateListener,
            TelephonyCallback.SignalStrengthsListener {
        @Override
        public void onServiceStateChanged(ServiceState serviceState) {
            if (serviceState == null) return;
            logd("onServiceStateChanged: ServiceState=" + serviceState);
            boolean wasSatellite = mIsSatellite;
            mServiceState = serviceState;
            NetworkRegistrationInfo nri =
                    serviceState.getNetworkRegistrationInfo(
                            NetworkRegistrationInfo.DOMAIN_PS,
                            AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
            mIsSatellite = (nri != null && nri.isRegistered() && nri.isNonTerrestrialNetwork());
            logd(
                    "onServiceStateChanged: mIsSatellite = "
                            + mIsSatellite
                            + " | wasSatellite = "
                            + wasSatellite);
            if (wasSatellite && !mIsSatellite && mCurrentRunningTaskType != TaskType.NONE) {
                logw("Satellite service lost during active task.");
                handleNetworkLoss();
            }
        }

        @Override
        public void onSignalStrengthsChanged(SignalStrength signalStrength) {
            mSignalStrength = signalStrength;
            updateSignalStrength();
        }
    }

    private void startUpload() {
        if (mNetwork == null) {
            displayToast("Satellite Network not available.");
            return;
        }

        mUploadFilePath = RandomDataGenerator.getFilesDir() + mFileName;
        logd("Creating sample file: " + mUploadFilePath + " (" + mFileSize + " KB)");
        createSampleFile();

        logd(
                "Starting FileUploadTask with path: "
                        + mUploadFilePath
                        + " | URL: "
                        + UPLOAD_URL
                        + " | Resume Bytes: "
                        + currentUploadBytes
                        + " | Prev Time: "
                        + mTimeElapsedBeforePauseMillis);

        mCurrentRunningTaskType = TaskType.UPLOAD;
        mIsPausedForNetwork = false;
        if (currentUploadBytes == 0) {
            mTimeElapsedBeforePauseMillis = 0;
        }

        FileUploadTask task =
                new FileUploadTask(
                        mUploadFilePath,
                        UPLOAD_URL,
                        currentUploadBytes,
                        mTimeElapsedBeforePauseMillis,
                        this,
                        this);
        mCurrentTaskRunnable = task;
        mTaskExecutor.execute(task);
    }

    private void startDownload() {
        if (mNetwork == null) {
            displayToast("Satellite Network not available.");
            return;
        }

        logd(
                "Starting FileDownloadTask with URL: "
                        + mDownloadUrl
                        + " | Resume Bytes: "
                        + currentDownloadBytes
                        + " | Prev Time: "
                        + mTimeElapsedBeforePauseMillis);
        mCurrentRunningTaskType = TaskType.DOWNLOAD;
        mIsPausedForNetwork = false;

        FileDownloadTask task =
                new FileDownloadTask(
                        mDownloadUrl,
                        currentDownloadBytes,
                        mTimeElapsedBeforePauseMillis,
                        this,
                        this);
        mCurrentTaskRunnable = task;
        mTaskExecutor.execute(task);
    }

    private void stopCurrentTask() {
        logw("stopCurrentTask called. Current task type: " + mCurrentRunningTaskType);
        mTimeElapsedBeforePauseMillis = 0;
        mPausedTaskType = TaskType.NONE;

        if (mCurrentRunningTaskType != TaskType.NONE && mCurrentTaskRunnable != null) {
            mIsPausedForNetwork = false;
            if (mCurrentRunningTaskType == TaskType.DOWNLOAD
                    && mCurrentTaskRunnable instanceof FileDownloadTask) {
                ((FileDownloadTask) mCurrentTaskRunnable).cancelDownload();
                displayToast("Download Cancelled by user.");
            } else if (mCurrentRunningTaskType == TaskType.UPLOAD
                    && mCurrentTaskRunnable instanceof FileUploadTask) {
                ((FileUploadTask) mCurrentTaskRunnable).cancelUpload();
                displayToast("Upload Cancelled by user.");
            }
        } else {
            logd("stopCurrentTask: No task was running.");
        }
        setIdleView();
        resetTaskStateVariables();
    }

    private void generateNetworkRequest() {
        mSatelliteConstrainNetworkCallback =
                new NetworkCallback() {
                    @Override
                    public void onAvailable(@NonNull final Network network) {
                        logi("Network Available - " + network);
                        mNetwork = network;
                        runOnUiThread(
                                () -> {
                                    if (mIsPausedForNetwork) {
                                        logi("Network became available while paused."
                                                + " Attempting auto-resume.");
                                        displayMessageInUI("Resuming "
                                                + mPausedTaskType + "...");
                                        TaskType taskToResume = mPausedTaskType;
                                        mIsPausedForNetwork = false;
                                        mPausedTaskType = TaskType.NONE;

                                        if (taskToResume == TaskType.DOWNLOAD) {
                                            startDownload();
                                        } else if (taskToResume == TaskType.UPLOAD) {
                                            startUpload();
                                        } else {
                                            logw("Paused task type was NONE, cannot auto-resume.");
                                            setIdleView();
                                        }
                                    } else {
                                        displayMessageInUI("Satellite Network Ready.");
                                        if (mCurrentRunningTaskType == TaskType.NONE) {
                                            setIdleView();
                                        }
                                    }
                                });
                    }

                    @Override
                    public void onLost(@NonNull Network network) {
                        logw("Network Lost - " + network);
                        if (mNetwork != null && mNetwork.equals(network)) {
                            handleNetworkLoss();
                        }
                    }

                    @Override
                    public void onUnavailable() {
                        super.onUnavailable();
                        logw("NetworkCallback: onUnavailable");
                        handleNetworkLoss();
                    }
                };
        requestingNetwork();
    }

    private void handleNetworkLoss() {
        logw("Handling network loss. Current task type: " + mCurrentRunningTaskType);
        mNetwork = null;
        mHandler.post(() -> {
            displayMessageInUI("Satellite Network Lost.");
            setActiveTaskView();

            logd("handleNetworkLoss: TaskType = "
                    + mCurrentRunningTaskType + ", Runnable = " + mCurrentTaskRunnable);

            if (mCurrentRunningTaskType != TaskType.NONE && mCurrentTaskRunnable != null) {
                if (!mIsPausedForNetwork) {
                    logi("Network lost during active task. Attempting to pause.");
                    mIsPausedForNetwork = true;
                    mPausedTaskType = mCurrentRunningTaskType;

                    if (mCurrentRunningTaskType == TaskType.DOWNLOAD
                            && mCurrentTaskRunnable instanceof FileDownloadTask) {
                        ((FileDownloadTask) mCurrentTaskRunnable).pauseDownload();
                    } else if (mCurrentRunningTaskType == TaskType.UPLOAD
                            && mCurrentTaskRunnable instanceof FileUploadTask) {
                        ((FileUploadTask) mCurrentTaskRunnable).pauseUpload();
                    }
                } else {
                    logd("handleNetworkLoss: Already marked as paused for network.");
                }
            } else {
                logd("Network lost, but no task was running or runnable ref missing.");
                mIsPausedForNetwork = false;
                mPausedTaskType = TaskType.NONE;
                setIdleView();
            }
        });
    }

    private void requestingNetwork() {
        if (mConnectivityManager == null) {
            loge("ConnectivityManager is null");
            return;
        }
        logi("Requesting Satellite Network...");
        displayMessageInUI("Requesting Satellite Network...");
        if (mRequest == null) {
            int netCapabilityNotBandwidthConstrained = 37;
            mRequest =
                    new NetworkRequest.Builder()
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED)
                            .removeCapability(netCapabilityNotBandwidthConstrained)
                            .addTransportType(NetworkCapabilities.TRANSPORT_SATELLITE)
                            .build();
        }
        try {
            mConnectivityManager.requestNetwork(mRequest, mSatelliteConstrainNetworkCallback);
            logd("Network request submitted.");
        } catch (SecurityException se) {
            loge("SecurityException requesting network. Check Permissions. " + se.getMessage());
            displayMessageInUI("Error: Network permission missing.");
        } catch (Exception e) {
            loge("Exception requesting network. " + e.getMessage());
            displayMessageInUI("Error requesting network.");
        }
    }

    private void releaseNetworkRequest() {
        if (mConnectivityManager != null && mSatelliteConstrainNetworkCallback != null) {
            logi("Releasing Network Request.");
            try {
                mConnectivityManager.unregisterNetworkCallback(mSatelliteConstrainNetworkCallback);
            } catch (IllegalArgumentException e) {
                logw("NetworkCallback not registered?");
            } catch (Exception e) {
                loge("Exception releasing network callback: " + e.getMessage());
            } finally {
                mNetwork = null;
                mSatelliteConstrainNetworkCallback = null;
            }
        }
    }

    @Override
    public void onPreExecute() {
        mHandler.post(
                () -> {
                    logd("TaskCallback: onPreExecute");
                    setActiveTaskView();
                    mProgressBar.setVisibility(View.VISIBLE);
                    mProgressBar.setIndeterminate(false);

                    String initialStatus = "Starting " + mCurrentRunningTaskType + "...";
                    String initialProgress = "Progress: 0%";
                    String initialTime = formatTime(mTimeElapsedBeforePauseMillis);

                    if (mCurrentRunningTaskType == TaskType.DOWNLOAD && currentDownloadBytes > 0) {
                        initialStatus = "Resuming DOWNLOAD...";
                        initialProgress = "Progress: Resuming...";
                    } else if (mCurrentRunningTaskType == TaskType.UPLOAD
                            && currentUploadBytes > 0) {
                        initialStatus = "Resuming UPLOAD...";
                        initialProgress = "Progress: Resuming...";
                    } else {
                        mProgressBar.setProgress(0);
                        mSpeedText.setText("Speed: --");
                        mProgressText.setText(initialProgress);
                    }
                    mStatusText.setText(initialStatus);
                    mTimeText.setText("Time: " + initialTime);
                });
    }

    @Override
    public void onProgressUpdate(int progress, String speed, String time, long currentBytes) {
        mHandler.post(
                () -> {
                    if (mCurrentRunningTaskType == TaskType.NONE && !mIsPausedForNetwork) {
                        return;
                    }
                    if (mStatusText.getText().toString().contains("Retrying")) {
                        mStatusText.setText(mCurrentRunningTaskType + " in progress...");
                    }

                    if (progress >= 0) {
                        mProgressBar.setIndeterminate(false);
                        mProgressBar.setProgress(progress);
                        mProgressText.setText(
                                String.format(
                                        Locale.getDefault(),
                                        "Progress: %d%% (%s)",
                                        progress,
                                        formatBytes(currentBytes)));
                    } else {
                        mProgressText.setText(
                                String.format(
                                        Locale.getDefault(),
                                        "Progress: %s",
                                        formatBytes(currentBytes)));
                    }
                    mSpeedText.setText("Speed: " + speed);
                    mTimeText.setText("Time: " + time);
                });
    }

    @Override
    public void onRetryAttempt(int attemptNumber, long delayMillis) {
        mHandler.post(() -> {
            if (!mIsPausedForNetwork) {
                logi("TaskCallback: onRetryAttempt "
                        + attemptNumber + " in " + delayMillis + "ms");
                mStatusText.setText(String.format(Locale.getDefault(),
                        "Network issue. Retrying attempt #%d in %ds...",
                        attemptNumber,
                        delayMillis / 1000));
            }
        });
    }

    @Override
    public void onPaused(String message, long currentOverallBytes, long timeElapsedThisRunMillis) {
        mHandler.post(
                () -> {
                    logi("TaskCallback: onPaused: "
                            + message
                            + " at "
                            + currentOverallBytes
                            + " bytes. Time this run: "
                            + timeElapsedThisRunMillis
                            + "ms");

                    mTimeElapsedBeforePauseMillis += timeElapsedThisRunMillis;
                    logi("Total time elapsed before pause now: "
                            + mTimeElapsedBeforePauseMillis
                            + "ms");

                    if (mPausedTaskType == TaskType.DOWNLOAD) {
                        currentDownloadBytes = currentOverallBytes;
                    }
                    if (mPausedTaskType == TaskType.UPLOAD) {
                        currentUploadBytes = currentOverallBytes;
                    }

                    if (mIsPausedForNetwork) {
                        mProgressBar.setIndeterminate(false);
                        mSpeedText.setText("Speed: --");
                        mTimeText.setText("Time: " + formatTime(mTimeElapsedBeforePauseMillis));
                        setActiveTaskView();
                        mCurrentRunningTaskType = TaskType.NONE;
                        mCurrentTaskRunnable = null;
                    } else {
                        logw("TaskCallback: onPaused received but mIsPausedForNetwork is false");
                        resetTaskStateVariables();
                        setIdleView();
                    }
                });
    }

    @Override
    public void onCancelled(String message, String averageSpeed, String time) {
        mHandler.post(
                () -> {
                    logw("TaskCallback: onCancelled: " + message);
                    mStatusText.setText(message);
                    mSpeedText.setText("Avg Speed: " + averageSpeed);
                    mTimeText.setText("Total Time: " + time);
                    mProgressText.setText("");
                    mProgressBar.setVisibility(View.GONE);
                    mProgressBar.setIndeterminate(false);
                    mProgressBar.setProgress(0);

                    resetTaskStateVariables();
                    setIdleView();
                });
    }

    @Override
    public void onPostExecute(
            String result, String averageSpeed, String totalAccumulatedTimeStr, long finalBytes) {
        mHandler.post(
                () -> {
                    logi("TaskCallback: onPostExecute: " + result);
                    if (mCurrentRunningTaskType == TaskType.NONE && !mIsPausedForNetwork) {
                        logw(
                                "TaskCallback: onPostExecute received but task already"
                                        + " stopped/idle, ignoring result: "
                                        + result);
                        return;
                    }

                    mStatusText.setText(result);
                    mSpeedText.setText("Avg Speed: " + averageSpeed);
                    mTimeText.setText("Total Time: " + totalAccumulatedTimeStr);
                    mProgressText.setText(finalBytes > 0 ? "Total: "
                            + formatBytes(finalBytes) : "");
                    mProgressBar.setVisibility(View.GONE);
                    mProgressBar.setIndeterminate(false);
                    mProgressBar.setProgress(0);

                    resetTaskStateVariables();
                    setIdleView();
                });
    }

    private void setIdleView() {
        runOnUiThread(
                () -> {
                    mUploadTaskButton.setEnabled(mNetwork != null);
                    mDownloadTaskButton.setEnabled(mNetwork != null);
                    mStopTaskButton.setEnabled(false);
                    for (int i = 0; i < mRadioGroup.getChildCount(); i++) {
                        mRadioGroup.getChildAt(i).setEnabled(mNetwork != null);
                    }
                    if (mCurrentRunningTaskType == TaskType.NONE && !mIsPausedForNetwork) {
                        mProgressBar.setVisibility(View.GONE);
                    }
                });
    }

    private void setActiveTaskView() {
        runOnUiThread(
                () -> {
                    mUploadTaskButton.setEnabled(false);
                    mDownloadTaskButton.setEnabled(false);
                    mStopTaskButton.setEnabled(true);
                    for (int i = 0; i < mRadioGroup.getChildCount(); i++) {
                        mRadioGroup.getChildAt(i).setEnabled(false);
                    }
                });
    }

    private void resetTaskStateVariables() {
        mCurrentRunningTaskType = TaskType.NONE;
        mCurrentTaskRunnable = null;
        mIsPausedForNetwork = false;
        mPausedTaskType = TaskType.NONE;
        currentDownloadBytes = 0;
        currentUploadBytes = 0;
        mTimeElapsedBeforePauseMillis = 0;
    }

    private void displayToast(String message) {
        runOnUiThread(() -> Toast.makeText(mContext, message, Toast.LENGTH_SHORT).show());
    }

    private void displayMessageInUI(String message) {
        runOnUiThread(() -> mStatusText.setText(message));
    }

    private void createSampleFile() {
        new RandomDataGenerator().generateRandomData(mFileName, mFileSize);
    }

    private void refreshDataMode() {
        if (mContext == null) return;
        SatelliteManager satelliteManager = mContext.getSystemService(SatelliteManager.class);
        if (satelliteManager == null) {
            loge("SatelliteManager not available.");
            return;
        }
        int satelliteDataMode = satelliteManager.getSatelliteDataSupportMode(mSubId);
        updateSatelliteDataMode(satelliteDataMode);
    }

    private void updateSatelliteDataMode(int satDataMode) {
        if (mSatDataModeTextView == null) return;
        String satData = "Data Mode:";
        satData += switch (satDataMode) {
            case SatelliteManager.SATELLITE_DATA_SUPPORT_RESTRICTED -> " Restricted";
            case SatelliteManager.SATELLITE_DATA_SUPPORT_CONSTRAINED -> " Limited";
            case SatelliteManager.SATELLITE_DATA_SUPPORT_UNCONSTRAINED -> " UnLimited";
            default -> " Unknown";
        };
        mSatDataModeTextView.setText(satData);
        mSatDataModeTextView.setVisibility(View.VISIBLE);
    }

    private void registerServiceStateChange() {
        if (mTelephonyCallback == null && mTelephonyManager != null) {
            mTelephonyCallback = new RadioInfoTelephonyCallback();
            logd("Registering for service state change...");
            try {
                mTelephonyManager.registerTelephonyCallback(mContext.getMainExecutor(),
                        mTelephonyCallback);
            } catch (SecurityException e) {
                loge("Permission error registering TelephonyCallback: " + e.getMessage());
                displayMessageInUI("Error: Telephony permission missing.");
            } catch (IllegalStateException e) {
                loge("IllegalStateException registering TelephonyCallback: " + e.getMessage());
                displayMessageInUI("Error: Cannot register telephony callback.");
            }
        }
    }

    private void unregisterServiceStateChange() {
        if (mTelephonyCallback != null && mTelephonyManager != null) {
            logd("Unregistering service state change callback.");
            try {
                mTelephonyManager.unregisterTelephonyCallback(mTelephonyCallback);
            } catch (Exception e) {
                loge("Error unregistering telephony callback: " + e.getMessage());
            } finally {
                mTelephonyCallback = null;
            }
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log((double) bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format(Locale.getDefault(), "%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    private String formatTime(long millis) {
        if (millis < 0) millis = 0;
        long seconds = (millis / 1000) % 60;
        long minutes = (millis / (1000 * 60)) % 60;
        long hours = (millis / (1000 * 60 * 60));
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds);
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

    private void loge(String string) {
        Log.e(TAG, string);
    }

    private void logd(String string) {
        Log.d(TAG, string);
    }

    private void logw(String string) {
        Log.w(TAG, string);
    }

    private void logi(String string) {
        Log.i(TAG, string);
    }
}