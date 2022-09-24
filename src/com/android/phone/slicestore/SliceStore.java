/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.phone.slicestore;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.ConnectivityManager;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PersistableBundle;
import android.telephony.AnomalyReporter;
import android.telephony.CarrierConfigManager;
import android.telephony.TelephonyManager;
import android.telephony.data.NetworkSliceInfo;
import android.telephony.data.NetworkSlicingConfig;
import android.util.Log;
import android.util.SparseBooleanArray;

import com.android.internal.telephony.Phone;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * The SliceStore controls the purchase and availability of all cellular premium capabilities.
 * Applications can check whether premium capabilities are available by calling
 * {@link TelephonyManager#isPremiumCapabilityAvailableForPurchase(int)}. If this returns true,
 * they can then call {@link TelephonyManager#purchasePremiumCapability(int, Executor, Consumer)}
 * to purchase the premium capability. If all conditions are met, a notification will be displayed
 * to the user prompting them to purchase the premium capability. If the user confirms on the
 * notification, a (TODO: add link) WebView will open that allows the user to purchase the
 * premium capability from the carrier. If the purchase is successful, the premium capability
 * will be available for all applications to request through
 * {@link ConnectivityManager#requestNetwork}.
 */
public class SliceStore extends Handler {
    @NonNull private static final String TAG = "SliceStore";
    /** Purchasing the premium capability is no longer throttled. */
    private static final int EVENT_PURCHASE_UNTHROTTLED = 1;
    /** Slicing config changed. */
    private static final int EVENT_SLICING_CONFIG_CHANGED = 2;
    /** Display booster notification. */
    private static final int EVENT_DISPLAY_BOOSTER_NOTIFICATION = 3;
    /** Boost was not purchased within the timeout specified by carrier configs. */
    private static final int EVENT_PURCHASE_TIMEOUT = 4;

    /** UUID to report an anomaly when a premium capability is throttled twice in a row. */
    private static final String UUID_CAPABILITY_THROTTLED_TWICE =
            "15574927-e2e2-4593-99d4-2f340d22b383";

    /** Map of phone ID -> SliceStore. */
    @NonNull private static final Map<Integer, SliceStore> sInstances = new HashMap<>();

    @NonNull private final Phone mPhone;
    @NonNull private final SparseBooleanArray mPurchasedCapabilities = new SparseBooleanArray();
    @NonNull private final SparseBooleanArray mThrottledCapabilities = new SparseBooleanArray();
    @NonNull private final SparseBooleanArray mPendingPurchaseCapabilities =
            new SparseBooleanArray();
    @Nullable private NetworkSlicingConfig mSlicingConfig;

    /**
     * Get the static SliceStore instance for the given phone.
     *
     * @param phone The phone to get the SliceStore for
     * @return The static SliceStore instance
     */
    @NonNull public static synchronized SliceStore getInstance(@NonNull Phone phone) {
        // TODO: Add listeners for multi sim setting changed (maybe carrier config changed too)
        //  that dismiss notifications and update SliceStore instance
        int phoneId = phone.getPhoneId();
        if (sInstances.get(phoneId) == null) {
            sInstances.put(phoneId, new SliceStore(phone));
        }
        return sInstances.get(phoneId);
    }

    private SliceStore(@NonNull Phone phone) {
        super(Looper.myLooper());
        mPhone = phone;
        // TODO: Create a cached value for slicing config in DataIndication and initialize here
        mPhone.mCi.registerForSlicingConfigChanged(this, EVENT_SLICING_CONFIG_CHANGED, null);
    }

    @Override
    public void handleMessage(@NonNull Message msg) {
        switch (msg.what) {
            case EVENT_PURCHASE_UNTHROTTLED: {
                int capability = (int) msg.obj;
                log("EVENT_PURCHASE_UNTHROTTLED: for capability "
                        + TelephonyManager.convertPremiumCapabilityToString(capability));
                mThrottledCapabilities.setValueAt(capability, false);
                break;
            }
            case EVENT_SLICING_CONFIG_CHANGED: {
                AsyncResult ar = (AsyncResult) msg.obj;
                NetworkSlicingConfig config = (NetworkSlicingConfig) ar.result;
                log("EVENT_SLICING_CONFIG_CHANGED: from " + mSlicingConfig + " to " + config);
                mSlicingConfig = config;
                break;
            }
            case EVENT_DISPLAY_BOOSTER_NOTIFICATION: {
                onDisplayBoosterNotification(msg.arg1, (Message) msg.obj);
                break;
            }
            case EVENT_PURCHASE_TIMEOUT: {
                int capability = msg.arg1;
                log("EVENT_PURCHASE_TIMEOUT: for capability "
                        + TelephonyManager.convertPremiumCapabilityToString(capability));
                onTimeout(capability, (Message) msg.obj);
                break;
            }
        }
    }

    /**
     * Check whether the given premium capability is available for purchase from the carrier.
     *
     * @param capability The premium capability to check.
     * @return Whether the given premium capability is available to purchase.
     */
    public boolean isPremiumCapabilityAvailableForPurchase(
            @TelephonyManager.PremiumCapability int capability) {
        if (!arePremiumCapabilitiesSupportedByDevice()) {
            log("Premium capabilities unsupported by the device.");
            return false;
        }
        if (!isPremiumCapabilitySupportedByCarrier(capability)) {
            log("Premium capability "
                    + TelephonyManager.convertPremiumCapabilityToString(capability)
                    + " unsupported by the carrier.");
            return false;
        }
        if (!arePremiumCapabilitiesEnabledByUser()) {
            log("Premium capabilities disabled by the user.");
            return false;
        }
        log("Premium capability "
                + TelephonyManager.convertPremiumCapabilityToString(capability)
                + " is available for purchase.");
        return true;
    }

    /**
     * Purchase the given premium capability from the carrier.
     *
     * @param capability The premium capability to purchase.
     * @param onComplete The callback message to send when the purchase request is complete.
     */
    public synchronized void purchasePremiumCapability(
            @TelephonyManager.PremiumCapability int capability, @NonNull Message onComplete) {
        log("purchasePremiumCapability: "
                + TelephonyManager.convertPremiumCapabilityToString(capability));
        // Check whether the premium capability can be purchased.
        if (!arePremiumCapabilitiesSupportedByDevice()) {
            sendPurchaseResult(capability,
                    TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_FEATURE_NOT_SUPPORTED,
                    onComplete);
            return;
        }
        if (!isPremiumCapabilitySupportedByCarrier(capability)) {
            sendPurchaseResult(capability,
                    TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_CARRIER_DISABLED,
                    onComplete);
            return;
        }
        if (!arePremiumCapabilitiesEnabledByUser()) {
            sendPurchaseResult(capability,
                    TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_USER_DISABLED,
                    onComplete);
            return;
        }
        if (mPurchasedCapabilities.get(capability) || isSlicingConfigActive(capability)) {
            sendPurchaseResult(capability,
                    TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_ALREADY_PURCHASED,
                    onComplete);
            return;
        }
        if (mThrottledCapabilities.get(capability)) {
            sendPurchaseResult(capability,
                    TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_THROTTLED,
                    onComplete);
            return;
        }
        if (mPhone.getServiceState().getDataNetworkType() != TelephonyManager.NETWORK_TYPE_NR) {
            sendPurchaseResult(capability,
                    TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_NETWORK_NOT_AVAILABLE,
                    onComplete);
            return;
        }
        if (isNetworkCongested(capability)) {
            throttleCapability(capability);
            sendPurchaseResult(capability,
                    TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_NETWORK_CONGESTED,
                    onComplete);
            return;
        }
        if (mPendingPurchaseCapabilities.get(capability)) {
            sendPurchaseResult(capability,
                    TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_ALREADY_IN_PROGRESS,
                    onComplete);
            return;
        }

        // All state checks passed. Mark purchase pending and display the booster notification to
        // prompt user purchase. Process through the handler since this method is synchronized.
        mPendingPurchaseCapabilities.put(capability, true);
        sendMessage(obtainMessage(EVENT_DISPLAY_BOOSTER_NOTIFICATION,
                capability, 0 /* unused */, onComplete));
    }

    private void sendPurchaseResult(@TelephonyManager.PremiumCapability int capability,
            @TelephonyManager.PurchasePremiumCapabilityResult int result,
            @NonNull Message onComplete) {
        // Send the onComplete message with the purchase result.
        log("Purchase result for capability "
                + TelephonyManager.convertPremiumCapabilityToString(capability)
                + ": " + TelephonyManager.convertPurchaseResultToString(result));
        AsyncResult.forMessage(onComplete, result, null);
        onComplete.sendToTarget();
    }

    private void throttleCapability(@TelephonyManager.PremiumCapability int capability) {
        // Throttle subsequent requests if necessary.
        if (!mThrottledCapabilities.get(capability)) {
            long throttleTime = getThrottleDuration(capability);
            if (throttleTime > 0) {
                log("Throttle purchase requests for capability "
                        + TelephonyManager.convertPremiumCapabilityToString(capability) + " for "
                        + (throttleTime / 1000) + " seconds.");
                mThrottledCapabilities.setValueAt(capability, true);
                sendMessageDelayed(obtainMessage(EVENT_PURCHASE_UNTHROTTLED, capability),
                        throttleTime);
            }
        } else {
            String logStr = TelephonyManager.convertPremiumCapabilityToString(capability)
                    + " is already throttled.";
            log(logStr);
            AnomalyReporter.reportAnomaly(UUID.fromString(UUID_CAPABILITY_THROTTLED_TWICE), logStr);
        }
    }

    private void onDisplayBoosterNotification(@TelephonyManager.PremiumCapability int capability,
            @NonNull Message onComplete) {
        long timeout = getCarrierConfigs().getLong(CarrierConfigManager
                .KEY_PREMIUM_CAPABILITY_NOTIFICATION_DISPLAY_TIMEOUT_MILLIS_LONG);
        log("Display the booster notification for capability "
                + TelephonyManager.convertPremiumCapabilityToString(capability) + " for "
                + (timeout / 1000) + " seconds.");
        sendMessageDelayed(
                obtainMessage(EVENT_PURCHASE_TIMEOUT, capability, 0 /* unused */, onComplete),
                timeout);
        // TODO(b/245882092): Display notification with listener for
        //  EVENT_USER_ACTION or EVENT_USER_CANCELED + EVENT_USER_CONFIRMED
    }

    private void closeBoosterNotification(@TelephonyManager.PremiumCapability int capability) {
        // TODO(b/245882092): Close notification; maybe cancel purchase timeout
    }

    private void onTimeout(@TelephonyManager.PremiumCapability int capability,
            @NonNull Message onComplete) {
        closeBoosterNotification(capability);
        mPendingPurchaseCapabilities.put(capability, false);
        throttleCapability(capability);
        sendPurchaseResult(capability, TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_TIMEOUT,
                onComplete);
    }

    private void onUserCanceled(@TelephonyManager.PremiumCapability int capability) {
        // TODO(b/245882092): Process and return user canceled; throttle
    }

    private void onUserConfirmed(@TelephonyManager.PremiumCapability int capability) {
        // TODO(b/245882092, b/245882601): Open webview listening for carrier response
        //  --> EVENT_CARRIER_SUCCESS or EVENT_CARRIER_ERROR
    }

    private void onCarrierSuccess(@TelephonyManager.PremiumCapability int capability) {
        // TODO(b/245882601): Process and return success.
        //  Probably need to handle capability expiry as well
    }

    private void onCarrierError(@TelephonyManager.PremiumCapability int capability) {
        // TODO(b/245882601): Process and return carrier error; throttle
    }

    @Nullable private PersistableBundle getCarrierConfigs() {
        return mPhone.getContext().getSystemService(CarrierConfigManager.class)
                .getConfigForSubId(mPhone.getSubId());
    }

    private long getThrottleDuration(@TelephonyManager.PurchasePremiumCapabilityResult int result) {
        if (result == TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_USER_CANCELED
                || result == TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_TIMEOUT) {
            return getCarrierConfigs().getLong(CarrierConfigManager
                    .KEY_PREMIUM_CAPABILITY_NOTIFICATION_BACKOFF_HYSTERESIS_TIME_MILLIS_LONG);
        }
        if (result == TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_NETWORK_CONGESTED
                || result == TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_CARRIER_ERROR) {
            return getCarrierConfigs().getLong(CarrierConfigManager
                    .KEY_PREMIUM_CAPABILITY_PURCHASE_CONDITION_BACKOFF_HYSTERESIS_TIME_MILLIS_LONG);
        }
        return 0;
    }

    private boolean isPremiumCapabilitySupportedByCarrier(
            @TelephonyManager.PremiumCapability int capability) {
        int[] supportedCapabilities = getCarrierConfigs().getIntArray(
                CarrierConfigManager.KEY_SUPPORTED_PREMIUM_CAPABILITIES_INT_ARRAY);
        if (supportedCapabilities == null) {
            return false;
        }
        return Arrays.stream(supportedCapabilities)
                .anyMatch(supportedCapability -> supportedCapability == capability);
    }

    private boolean arePremiumCapabilitiesSupportedByDevice() {
        // TODO: Add more checks?
        //  Maybe device resource overlay to enable/disable in addition to carrier configs
        return (mPhone.getCachedAllowedNetworkTypesBitmask()
                & TelephonyManager.NETWORK_TYPE_BITMASK_NR) != 0;
    }

    private boolean arePremiumCapabilitiesEnabledByUser() {
        // TODO(b/245882396): Create and set user settings
        return false;
    }

    private boolean isSlicingConfigActive(@TelephonyManager.PremiumCapability int capability) {
        if (mSlicingConfig == null) {
            return false;
        }
        int capabilityServiceType = getSliceServiceType(capability);
        for (NetworkSliceInfo sliceInfo : mSlicingConfig.getSliceInfo()) {
            // TODO: check if TrafficDescriptor has realtime capability slice
            if (sliceInfo.getSliceServiceType() == capabilityServiceType
                    && sliceInfo.getStatus() == NetworkSliceInfo.SLICE_STATUS_ALLOWED) {
                return true;
            }
        }
        return false;
    }

    private @NetworkSliceInfo.SliceServiceType int getSliceServiceType(
            @TelephonyManager.PremiumCapability int capability) {
        // TODO: Implement properly -- potentially need to add new slice service types?
        return NetworkSliceInfo.SLICE_SERVICE_TYPE_NONE;
    }

    private boolean isNetworkCongested(@TelephonyManager.PremiumCapability int capability) {
        // TODO: Implement TS43
        return true;
    }

    private void log(String s) {
        Log.d(TAG + "-" + mPhone.getPhoneId(), s);
    }
}
