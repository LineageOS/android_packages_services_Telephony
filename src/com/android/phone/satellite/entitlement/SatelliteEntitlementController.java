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

package com.android.phone.satellite.entitlement;

import static java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME;
import static java.time.temporal.ChronoUnit.SECONDS;

import android.annotation.NonNull;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.telephony.Rlog;
import android.telephony.SubscriptionManager;


import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.ExponentialBackoff;
import com.android.internal.telephony.flags.FeatureFlags;
import com.android.internal.telephony.satellite.SatelliteConstants;
import com.android.internal.telephony.satellite.SatelliteController;
import com.android.internal.telephony.satellite.metrics.EntitlementMetricsStats;
import com.android.internal.telephony.subscription.SubscriptionManagerService;
import com.android.libraries.entitlement.ServiceEntitlementException;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * This class query the entitlement server to receive values for satellite services and passes the
 * response to the {@link com.android.internal.telephony.satellite.SatelliteController}.
 * @hide
 */
public class SatelliteEntitlementController extends Handler {
    private static final String TAG = "SatelliteEntitlementController";
    @NonNull private static SatelliteEntitlementController sInstance;
    /** Message code used in handleMessage() */
    private static final int CMD_START_QUERY_ENTITLEMENT = 1;
    private static final int CMD_RETRY_QUERY_ENTITLEMENT = 2;
    private static final int CMD_SIM_REFRESH = 3;

    /** Retry on next trigger event. */
    private static final int HTTP_RESPONSE_500 = 500;
    /** Retry after the time specified in the “Retry-After” header. After retry count doesn't exceed
     * MAX_RETRY_COUNT. */
    private static final int HTTP_RESPONSE_503 = 503;
    /** Default query refresh time is 1 month. */

    private static final int DEFAULT_QUERY_REFRESH_DAYS = 7;
    private static final long INITIAL_DELAY_MILLIS = TimeUnit.MINUTES.toMillis(10); // 10 min
    private static final long MAX_DELAY_MILLIS = TimeUnit.DAYS.toMillis(5); // 5 days
    private static final int MULTIPLIER = 2;
    private static final int MAX_RETRY_COUNT = 5;
    @NonNull private final SubscriptionManagerService mSubscriptionManagerService;
    @NonNull private final CarrierConfigManager mCarrierConfigManager;
    @NonNull private final CarrierConfigManager.CarrierConfigChangeListener
            mCarrierConfigChangeListener;
    @NonNull private final ConnectivityManager mConnectivityManager;
    @NonNull private final ConnectivityManager.NetworkCallback mNetworkCallback;
    @NonNull private final BroadcastReceiver mReceiver;
    @NonNull private final Context mContext;
    private final Object mLock = new Object();
    /** Map key : subId, value : ExponentialBackoff. */
    @GuardedBy("mLock")
    private Map<Integer, ExponentialBackoff> mExponentialBackoffPerSub = new HashMap<>();
    /** Map key : subId, value : SatelliteEntitlementResult. */
    @GuardedBy("mLock")
    private Map<Integer, SatelliteEntitlementResult> mSatelliteEntitlementResultPerSub =
            new HashMap<>();
    /** Map key : subId, value : the last query time to millis. */
    @GuardedBy("mLock")
    private Map<Integer, Long> mLastQueryTimePerSub = new HashMap<>();
    /** Map key : subId, value : Count the number of retries caused by the 'ExponentialBackoff' and
     * '503 error case with the Retry-After header'. */
    @GuardedBy("mLock")
    private Map<Integer, Integer> mRetryCountPerSub = new HashMap<>();
    /** Map key : subId, value : Whether query is in progress. */
    @GuardedBy("mLock")
    private Map<Integer, Boolean> mIsEntitlementInProgressPerSub = new HashMap<>();
    /** Map key : slotId, value : The last used subId. */
    @GuardedBy("mLock")
    private Map<Integer, Integer> mSubIdPerSlot = new HashMap<>();
    @NonNull private final EntitlementMetricsStats mEntitlementMetricsStats;

    /**
     * Create the SatelliteEntitlementController singleton instance.
     * @param context      The Context to use to create the SatelliteEntitlementController.
     * @param featureFlags The feature flag.
     */
    public static void make(@NonNull Context context, @NonNull FeatureFlags featureFlags) {
        if (!featureFlags.carrierEnabledSatelliteFlag()) {
            logd("carrierEnabledSatelliteFlag is disabled. don't created this.");
            return;
        }
        if (sInstance == null) {
            HandlerThread handlerThread = new HandlerThread(TAG);
            handlerThread.start();
            sInstance =
                    new SatelliteEntitlementController(context, handlerThread.getLooper());
        }
    }

    /**
     * Create a SatelliteEntitlementController to request query to the entitlement server for
     * satellite services and receive responses.
     *
     * @param context      The Context for the SatelliteEntitlementController.
     * @param looper       The looper for the handler. It does not run on main thread.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public SatelliteEntitlementController(@NonNull Context context, @NonNull Looper looper) {
        super(looper);
        mContext = context;
        mSubscriptionManagerService = SubscriptionManagerService.getInstance();
        mCarrierConfigManager = context.getSystemService(CarrierConfigManager.class);
        mCarrierConfigChangeListener = (slotIndex, subId, carrierId, specificCarrierId) ->
                handleCarrierConfigChanged(slotIndex, subId, carrierId, specificCarrierId);
        mCarrierConfigManager.registerCarrierConfigChangeListener(this::post,
                mCarrierConfigChangeListener);
        mConnectivityManager = context.getSystemService(ConnectivityManager.class);
        mNetworkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                handleInternetConnected();
            }
        };
        NetworkRequest networkrequest = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
        mConnectivityManager.registerNetworkCallback(networkrequest, mNetworkCallback, this);
        mReceiver = new SatelliteEntitlementControllerReceiver();
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        context.registerReceiver(mReceiver, intentFilter);
        mEntitlementMetricsStats = EntitlementMetricsStats.getOrCreateInstance();
        SatelliteController.getInstance().registerIccRefresh(this, CMD_SIM_REFRESH);
    }

    @Override
    public void handleMessage(@NonNull Message msg) {
        switch (msg.what) {
            case CMD_START_QUERY_ENTITLEMENT:
                handleCmdStartQueryEntitlement();
                break;
            case CMD_RETRY_QUERY_ENTITLEMENT:
                handleCmdRetryQueryEntitlement(msg.arg1);
                break;
            case CMD_SIM_REFRESH:
                handleSimRefresh();
                break;
            default:
                logd("do not used this message");
        }
    }

    private void handleCarrierConfigChanged(int slotIndex, int subId, int carrierId,
            int specificCarrierId) {
        logd("handleCarrierConfigChanged(): slotIndex(" + slotIndex + "), subId("
                + subId + "), carrierId(" + carrierId + "), specificCarrierId("
                + specificCarrierId + ")");
        processSimChanged(slotIndex, subId);
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            return;
        }

        sendEmptyMessage(CMD_START_QUERY_ENTITLEMENT);
        synchronized (mLock) {
            mSubIdPerSlot.put(slotIndex, subId);
        }
    }

    // When SIM is removed or changed, then reset the previous subId's retry related objects.
    private void processSimChanged(int slotIndex, int subId) {
        int previousSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        synchronized (mLock) {
            previousSubId = mSubIdPerSlot.getOrDefault(slotIndex,
                    SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        }
        logd("processSimChanged prev subId:" + previousSubId);
        if (previousSubId != subId) {
            synchronized (mLock) {
                mSubIdPerSlot.remove(slotIndex);
            }
            logd("processSimChanged resetEntitlementQueryPerSubId");
            resetEntitlementQueryPerSubId(previousSubId);
        }
    }

    private class SatelliteEntitlementControllerReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_AIRPLANE_MODE_CHANGED)) {
                boolean airplaneMode = intent.getBooleanExtra("state", false);
                handleAirplaneModeChange(airplaneMode);
            }
        }
    }

    private void handleAirplaneModeChange(boolean airplaneMode) {
        if (!airplaneMode) {
            resetEntitlementQueryCounts(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        }
    }

    private void handleSimRefresh() {
        resetEntitlementQueryCounts(cmdToString(CMD_SIM_REFRESH));
        sendMessageDelayed(obtainMessage(CMD_START_QUERY_ENTITLEMENT),
                TimeUnit.SECONDS.toMillis(10));
    }

    private boolean isInternetConnected() {
        Network activeNetwork = mConnectivityManager.getActiveNetwork();
        NetworkCapabilities networkCapabilities =
                mConnectivityManager.getNetworkCapabilities(activeNetwork);
        // TODO b/319780796 Add checking if it is not a satellite.
        return networkCapabilities != null
                && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void handleInternetConnected() {
        sendEmptyMessage(CMD_START_QUERY_ENTITLEMENT);
    }

    /**
     * Check if the device can request to entitlement server (if there is an internet connection and
     * if the throttle time has passed since the last request), and then pass the response to
     * SatelliteController if the response is received.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public void handleCmdStartQueryEntitlement() {
        for (int subId : mSubscriptionManagerService.getActiveSubIdList(true)) {
            if (!shouldStartQueryEntitlement(subId)) {
                continue;
            }

            // Check the satellite service query result from the entitlement server for the
            // satellite service.
            try {
                synchronized (mLock) {
                    mIsEntitlementInProgressPerSub.put(subId, true);
                    SatelliteEntitlementResult entitlementResult =  getSatelliteEntitlementApi(
                            subId).checkEntitlementStatus();
                    mSatelliteEntitlementResultPerSub.put(subId, entitlementResult);
                    mEntitlementMetricsStats.reportSuccess(subId,
                            getEntitlementStatus(entitlementResult), false);
                }
            } catch (ServiceEntitlementException e) {
                loge(e.toString());
                mEntitlementMetricsStats.reportError(subId, e.getErrorCode(), false);
                if (!isInternetConnected()) {
                    logd("StartQuery: disconnected. " + e);
                    synchronized (mLock) {
                        mIsEntitlementInProgressPerSub.remove(subId);
                    }
                    return;
                }
                if (isPermanentError(e)) {
                    logd("StartQuery: shouldPermanentError.");
                    queryCompleted(subId);
                    continue;
                } else if (isRetryAfterError(e)) {
                    long retryAfterSeconds = parseSecondsFromRetryAfter(e.getRetryAfter());
                    logd("StartQuery: next retry will be in " + TimeUnit.SECONDS.toMillis(
                            retryAfterSeconds) + " sec");
                    sendMessageDelayed(obtainMessage(CMD_RETRY_QUERY_ENTITLEMENT, subId, 0),
                            TimeUnit.SECONDS.toMillis(retryAfterSeconds));
                    stopExponentialBackoff(subId);
                    continue;
                } else {
                    startExponentialBackoff(subId);
                    continue;
                }
            }
            queryCompleted(subId);
        }
    }

    /** When airplane mode changes from on to off, reset the values required to start the first
     * query. */
    private void resetEntitlementQueryCounts(String event) {
        logd("resetEntitlementQueryCounts: " + event);
        synchronized (mLock) {
            mLastQueryTimePerSub = new HashMap<>();
            mExponentialBackoffPerSub = new HashMap<>();
            mRetryCountPerSub = new HashMap<>();
            mIsEntitlementInProgressPerSub = new HashMap<>();
        }
    }

    /**
     * If the HTTP response does not receive a body containing the 200 ok with sat mode
     * configuration,
     *
     * 1. If the 500 response received, then no more retry until next event occurred.
     * 2. If the 503 response with Retry-After header received, then the query is retried until
     * MAX_RETRY_COUNT.
     * 3. If other response or exception is occurred, then the query is retried until
     * MAX_RETRY_COUNT is reached using the ExponentialBackoff.
     */
    private void handleCmdRetryQueryEntitlement(int subId) {
        if (!shouldRetryQueryEntitlement(subId)) {
            return;
        }
        try {
            synchronized (mLock) {
                int currentRetryCount = getRetryCount(subId);
                mRetryCountPerSub.put(subId, currentRetryCount + 1);
                logd("[" + subId + "] retry cnt:" + getRetryCount(subId));
                SatelliteEntitlementResult entitlementResult =  getSatelliteEntitlementApi(
                        subId).checkEntitlementStatus();
                mSatelliteEntitlementResultPerSub.put(subId, entitlementResult);
                mEntitlementMetricsStats.reportSuccess(subId,
                        getEntitlementStatus(entitlementResult), true);
            }
        } catch (ServiceEntitlementException e) {
            loge(e.toString());
            mEntitlementMetricsStats.reportError(subId, e.getErrorCode(), true);
            if (!isRetryAvailable(subId)) {
                logd("retryQuery: unavailable.");
                queryCompleted(subId);
                return;
            }
            if (!isInternetConnected()) {
                logd("retryQuery: Internet disconnected.");
                stopExponentialBackoff(subId);
                synchronized (mLock) {
                    mIsEntitlementInProgressPerSub.remove(subId);
                }
                return;
            }
            if (isPermanentError(e)) {
                logd("retryQuery: shouldPermanentError.");
                queryCompleted(subId);
                return;
            } else if (isRetryAfterError(e)) {
                long retryAfterSeconds = parseSecondsFromRetryAfter(e.getRetryAfter());
                logd("retryQuery: next retry will be in " + TimeUnit.SECONDS.toMillis(
                        retryAfterSeconds) + " sec");
                sendMessageDelayed(obtainMessage(CMD_RETRY_QUERY_ENTITLEMENT, subId, 0),
                        TimeUnit.SECONDS.toMillis(retryAfterSeconds));
                stopExponentialBackoff(subId);
                return;
            } else {
                ExponentialBackoff exponentialBackoff = null;
                synchronized (mLock) {
                    exponentialBackoff = mExponentialBackoffPerSub.get(subId);
                }
                if (exponentialBackoff == null) {
                    startExponentialBackoff(subId);
                } else {
                    exponentialBackoff.notifyFailed();
                    logd("retryQuery: The next retry will be in "
                            + exponentialBackoff.getCurrentDelay() + " ms.");
                }
                return;
            }
        }
        queryCompleted(subId);
    }

    // If the 500 response is received, no retry until the next trigger event occurs.
    private boolean isPermanentError(ServiceEntitlementException e) {
        return e.getHttpStatus() == HTTP_RESPONSE_500;
    }

    /** If the 503 response with Retry-After header, retry is attempted according to the value in
     * the Retry-After header up to MAX_RETRY_COUNT. */
    private boolean isRetryAfterError(ServiceEntitlementException e) {
        int responseCode = e.getHttpStatus();
        logd("shouldRetryAfterError: received the " + responseCode);
        if (responseCode == HTTP_RESPONSE_503 && e.getRetryAfter() != null
                && !e.getRetryAfter().isEmpty()) {
            long retryAfterSeconds = parseSecondsFromRetryAfter(e.getRetryAfter());
            if (retryAfterSeconds == -1) {
                logd("Unable parsing the retry-after. try to exponential backoff.");
                return false;
            }
            return true;
        }
        return false;
    }

    /** Parse the HTTP-date or a number of seconds in the retry-after value. */
    private long parseSecondsFromRetryAfter(String retryAfter) {
        try {
            return Long.parseLong(retryAfter);
        } catch (NumberFormatException numberFormatException) {
        }

        try {
            return SECONDS.between(
                    Instant.now(), RFC_1123_DATE_TIME.parse(retryAfter, Instant::from));
        } catch (DateTimeParseException dateTimeParseException) {
        }

        return -1;
    }

    private void startExponentialBackoff(int subId) {
        ExponentialBackoff exponentialBackoff = null;
        stopExponentialBackoff(subId);
        synchronized (mLock) {
            mExponentialBackoffPerSub.put(subId,
                    new ExponentialBackoff(INITIAL_DELAY_MILLIS, MAX_DELAY_MILLIS,
                            MULTIPLIER, this.getLooper(), () -> {
                        synchronized (mLock) {
                            sendMessage(obtainMessage(CMD_RETRY_QUERY_ENTITLEMENT, subId, 0));
                        }
                    }));
            exponentialBackoff = mExponentialBackoffPerSub.get(subId);
        }
        if (exponentialBackoff != null) {
            exponentialBackoff.start();
            logd("start ExponentialBackoff, cnt: " + getRetryCount(subId) + ". Retrying in "
                    + exponentialBackoff.getCurrentDelay() + " ms.");
        }
    }

    /** If the Internet connection is lost during the ExponentialBackoff, stop the
     * ExponentialBackoff and reset it. */
    private void stopExponentialBackoff(int subId) {
        synchronized (mLock) {
            if (mExponentialBackoffPerSub.get(subId) != null) {
                logd("stopExponentialBackoff: reset ExponentialBackoff");
                mExponentialBackoffPerSub.get(subId).stop();
                mExponentialBackoffPerSub.remove(subId);
            }
        }
    }

    /**
     * No more query retry, update the result. If there is no response from the server, then used
     * the default value - 'satellite disabled' and empty 'PLMN allowed list'.
     * And then it send a delayed message to trigger the query again after A refresh day has passed.
     */
    private void queryCompleted(int subId) {
        SatelliteEntitlementResult entitlementResult;
        synchronized (mLock) {
            if (!mSatelliteEntitlementResultPerSub.containsKey(subId)) {
                logd("queryCompleted: create default SatelliteEntitlementResult");
                mSatelliteEntitlementResultPerSub.put(subId,
                        SatelliteEntitlementResult.getDefaultResult());
            }
            entitlementResult = mSatelliteEntitlementResultPerSub.get(subId);
            stopExponentialBackoff(subId);
            mIsEntitlementInProgressPerSub.remove(subId);
            logd("reset retry count for refresh query");
            mRetryCountPerSub.remove(subId);
        }

        saveLastQueryTime(subId);
        Message message = obtainMessage();
        message.what = CMD_START_QUERY_ENTITLEMENT;
        message.arg1 = subId;
        sendMessageDelayed(message, TimeUnit.DAYS.toMillis(
                getSatelliteEntitlementStatusRefreshDays(subId)));
        logd("queryCompleted: updateSatelliteEntitlementStatus");
        updateSatelliteEntitlementStatus(subId, entitlementResult.getEntitlementStatus() ==
                        SatelliteEntitlementResult.SATELLITE_ENTITLEMENT_STATUS_ENABLED,
                entitlementResult.getAllowedPLMNList(), entitlementResult.getBarredPLMNList());
    }

    private boolean shouldStartQueryEntitlement(int subId) {
        logd("shouldStartQueryEntitlement " + subId);
        if (!shouldRetryQueryEntitlement(subId)) {
            return false;
        }

        synchronized (mLock) {
            if (mIsEntitlementInProgressPerSub.getOrDefault(subId, false)) {
                logd("In progress retry");
                return false;
            }
        }
        return true;
    }

    private boolean shouldRetryQueryEntitlement(int subId) {
        if (!isSatelliteEntitlementSupported(subId)) {
            logd("Doesn't support entitlement query for satellite.");
            resetSatelliteEntitlementRestrictedReason(subId);
            return false;
        }

        if (!isInternetConnected()) {
            stopExponentialBackoff(subId);
            synchronized (mLock) {
                mIsEntitlementInProgressPerSub.remove(subId);
            }
            logd("Internet disconnected");
            return false;
        }

        if (!shouldRefreshEntitlementStatus(subId)) {
            return false;
        }

        return isRetryAvailable(subId);
    }

    // update for removing the satellite entitlement restricted reason
    private void resetSatelliteEntitlementRestrictedReason(int subId) {
        SatelliteEntitlementResult previousResult;
        SatelliteEntitlementResult enabledResult = new SatelliteEntitlementResult(
                SatelliteEntitlementResult.SATELLITE_ENTITLEMENT_STATUS_ENABLED,
                new ArrayList<>(), new ArrayList<>());
        synchronized (mLock) {
            previousResult = mSatelliteEntitlementResultPerSub.get(subId);
        }
        if (previousResult != null && previousResult.getEntitlementStatus()
                != SatelliteEntitlementResult.SATELLITE_ENTITLEMENT_STATUS_ENABLED) {
            logd("set enabled status for removing satellite entitlement restricted reason");
            synchronized (mLock) {
                mSatelliteEntitlementResultPerSub.put(subId, enabledResult);
            }
            updateSatelliteEntitlementStatus(subId, true, enabledResult.getAllowedPLMNList(),
                    enabledResult.getBarredPLMNList());
        }
        resetEntitlementQueryPerSubId(subId);
    }

    private void resetEntitlementQueryPerSubId(int subId) {
        logd("resetEntitlementQueryPerSubId: " + subId);
        stopExponentialBackoff(subId);
        synchronized (mLock) {
            mLastQueryTimePerSub.remove(subId);
            mRetryCountPerSub.remove(subId);
            mIsEntitlementInProgressPerSub.remove(subId);
        }
        removeMessages(CMD_RETRY_QUERY_ENTITLEMENT,
                obtainMessage(CMD_RETRY_QUERY_ENTITLEMENT, subId, 0));
    }

    /**
     * Compare the last query time to the refresh time from the CarrierConfig to see if the device
     * can query the entitlement server.
     */
    private boolean shouldRefreshEntitlementStatus(int subId) {
        long lastQueryTimeMillis = getLastQueryTime(subId);
        long refreshTimeMillis = TimeUnit.DAYS.toMillis(
                getSatelliteEntitlementStatusRefreshDays(subId));
        boolean isAvailable =
                (System.currentTimeMillis() - lastQueryTimeMillis) > refreshTimeMillis;
        if (!isAvailable) {
            logd("query is already done. can query after " + Instant.ofEpochMilli(
                    refreshTimeMillis + lastQueryTimeMillis));
        }
        return isAvailable;
    }

    /**
     * Get the SatelliteEntitlementApi.
     *
     * @param subId The subId of the subscription for creating SatelliteEntitlementApi
     * @return A new SatelliteEntitlementApi object.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public SatelliteEntitlementApi getSatelliteEntitlementApi(int subId) {
        return new SatelliteEntitlementApi(mContext, getConfigForSubId(subId), subId);
    }

    /** If there is a value stored in the cache, it is used. If there is no value stored in the
     * cache, it is considered the first query. */
    private long getLastQueryTime(int subId) {
        synchronized (mLock) {
            return mLastQueryTimePerSub.getOrDefault(subId, 0L);
        }
    }

    /** Return the satellite entitlement status refresh days from carrier config. */
    private int getSatelliteEntitlementStatusRefreshDays(int subId) {
        return getConfigForSubId(subId).getInt(
                CarrierConfigManager.KEY_SATELLITE_ENTITLEMENT_STATUS_REFRESH_DAYS_INT,
                DEFAULT_QUERY_REFRESH_DAYS);
    }

    private boolean isRetryAvailable(int subId) {
        if (getRetryCount(subId) >= MAX_RETRY_COUNT) {
            logd("The retry will not be attempted until the next trigger event.");
            return false;
        }
        return true;
    }

    /** Return the satellite entitlement supported bool from carrier config. */
    private boolean isSatelliteEntitlementSupported(int subId) {
        return getConfigForSubId(subId).getBoolean(
                CarrierConfigManager.KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL);
    }

    @NonNull
    private PersistableBundle getConfigForSubId(int subId) {
        PersistableBundle config = mCarrierConfigManager.getConfigForSubId(subId,
                CarrierConfigManager.ImsServiceEntitlement.KEY_ENTITLEMENT_SERVER_URL_STRING,
                CarrierConfigManager.KEY_SATELLITE_ENTITLEMENT_STATUS_REFRESH_DAYS_INT,
                CarrierConfigManager.KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL,
                CarrierConfigManager.KEY_SATELLITE_ENTITLEMENT_APP_NAME_STRING);
        if (config == null || config.isEmpty()) {
            config = CarrierConfigManager.getDefaultConfig();
        }
        return config;
    }

    private void saveLastQueryTime(int subId) {
        long lastQueryTimeMillis = System.currentTimeMillis();
        synchronized (mLock) {
            mLastQueryTimePerSub.put(subId, lastQueryTimeMillis);
        }
    }

    private int getRetryCount(int subId) {
        synchronized (mLock) {
            return mRetryCountPerSub.getOrDefault(subId, 0);
        }
    }

    /**
     * Send to satelliteController for update the satellite service enabled or not and plmn Allowed
     * list.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public void updateSatelliteEntitlementStatus(int subId, boolean enabled,
            List<String> plmnAllowedList, List<String> plmnBarredList) {
        SatelliteController.getInstance().onSatelliteEntitlementStatusUpdated(subId, enabled,
                plmnAllowedList, plmnBarredList, null);
    }

    private @SatelliteConstants.SatelliteEntitlementStatus int getEntitlementStatus(
            SatelliteEntitlementResult entitlementResult) {
        switch (entitlementResult.getEntitlementStatus()) {
            case SatelliteEntitlementResult.SATELLITE_ENTITLEMENT_STATUS_DISABLED:
                return SatelliteConstants.SATELLITE_ENTITLEMENT_STATUS_DISABLED;
            case SatelliteEntitlementResult.SATELLITE_ENTITLEMENT_STATUS_ENABLED:
                return SatelliteConstants.SATELLITE_ENTITLEMENT_STATUS_ENABLED;
            case SatelliteEntitlementResult.SATELLITE_ENTITLEMENT_STATUS_INCOMPATIBLE:
                return SatelliteConstants.SATELLITE_ENTITLEMENT_STATUS_INCOMPATIBLE;
            case SatelliteEntitlementResult.SATELLITE_ENTITLEMENT_STATUS_PROVISIONING:
                return SatelliteConstants.SATELLITE_ENTITLEMENT_STATUS_PROVISIONING;
            default:
                return SatelliteConstants.SATELLITE_ENTITLEMENT_STATUS_UNKNOWN;
        }
    }

    private static String cmdToString(int cmd) {
        switch (cmd) {
            case CMD_SIM_REFRESH:
                return "SIM_REFRESH";
            default:
                return "UNKNOWN(" + cmd + ")";
        }
    }

    private static void logd(String log) {
        Rlog.d(TAG, log);
    }

    private static void loge(String log) {
        Rlog.e(TAG, log);
    }
}
