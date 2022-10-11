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
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.net.ConnectivityManager;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PersistableBundle;
import android.telephony.AnomalyReporter;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.data.NetworkSliceInfo;
import android.telephony.data.NetworkSlicingConfig;
import android.util.Log;
import android.webkit.WebView;

import com.android.internal.telephony.Phone;
import com.android.phone.R;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The SliceStore controls the purchase and availability of all cellular premium capabilities.
 * Applications can check whether premium capabilities are available by calling
 * {@link TelephonyManager#isPremiumCapabilityAvailableForPurchase(int)}. If this returns true,
 * they can then call {@link TelephonyManager#purchasePremiumCapability(int, Executor, Consumer)}
 * to purchase the premium capability. If all conditions are met, a notification will be displayed
 * to the user prompting them to purchase the premium capability. If the user confirms on the
 * notification, a {@link WebView} will open that allows the user to purchase the premium capability
 * from the carrier. If the purchase is successful, the premium capability will be available for
 * all applications to request through {@link ConnectivityManager#requestNetwork}.
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
    /** UUID to report an anomaly when the BroadcastReceiver receives an invalid phone ID. */
    private static final String UUID_INVALID_PHONE_ID = "ced79f1a-8ac0-4260-8cf3-08b54c0494f3";
    /** UUID to report an anomaly when the BroadcastReceiver receives an unknown action. */
    private static final String UUID_UNKNOWN_ACTION = "0197efb0-dab1-4b0a-abaf-ac9336ec7923";

    /** Channel ID for the network boost notification. */
    private static final String NETWORK_BOOST_NOTIFICATION_CHANNEL_ID = "network_boost";
    /** Tag for the network boost notification. */
    private static final String NETWORK_BOOST_NOTIFICATION_TAG = "SliceStore.Notification";

    /** Action for when the network boost notification is cancelled. */
    private static final String ACTION_NOTIFICATION_CANCELED =
            "com.android.phone.slicestore.action.NOTIFICATION_CANCELED";
    /** Action for when the user clicks the "Not now" button on the network boost notification. */
    private static final String ACTION_NOTIFICATION_DELAYED =
            "com.android.phone.slicestore.action.NOTIFICATION_DELAYED";
    /** Action for when the user clicks the "Manage" button on the network boost notification. */
    private static final String ACTION_NOTIFICATION_MANAGE =
            "com.android.phone.slicestore.action.NOTIFICATION_MANAGE";
    /** Extra for phone ID to send from the network boost notification. */
    private static final String EXTRA_PHONE_ID = "com.android.phone.slicestore.extra.PHONE_ID";
    /** Extra for premium capability to send from the network boost notification. */
    private static final String EXTRA_PREMIUM_CAPABILITY =
            "com.android.phone.slicestore.extra.PREMIUM_CAPABILITY";

    /** Map of phone ID -> SliceStore instances. */
    @NonNull private static final Map<Integer, SliceStore> sInstances = new HashMap<>();

    /** The Phone instance used to create the SliceStore */
    @NonNull private final Phone mPhone;
    /** The set of purchased capabilities. */
    @NonNull private final Set<Integer> mPurchasedCapabilities = new HashSet<>();
    /** The set of throttled capabilities. */
    @NonNull private final Set<Integer> mThrottledCapabilities = new HashSet<>();
    /** A map of pending capabilities to the onComplete message for the purchase request. */
    @NonNull private final Map<Integer, Message> mPendingPurchaseCapabilities = new HashMap<>();
    /** A map of capabilities to the CapabilityBroadcastReceiver for the boost notification. */
    @NonNull private final Map<Integer, CapabilityBroadcastReceiver> mBroadcastReceivers =
            new HashMap<>();
    /** The current network slicing configuration. */
    @Nullable private NetworkSlicingConfig mSlicingConfig;

    private final class CapabilityBroadcastReceiver extends BroadcastReceiver {
        @TelephonyManager.PremiumCapability final int mCapability;

        CapabilityBroadcastReceiver(@TelephonyManager.PremiumCapability int capability) {
            mCapability = capability;
        }

        @Override
        public void onReceive(@NonNull Context context, @NonNull Intent intent) {
            String action = intent.getAction();
            log("CapabilityBroadcastReceiver("
                    + TelephonyManager.convertPremiumCapabilityToString(mCapability)
                    + ") received action: " + action);
            int phoneId = intent.getIntExtra(EXTRA_PHONE_ID,
                    SubscriptionManager.INVALID_PHONE_INDEX);
            int capability = intent.getIntExtra(EXTRA_PREMIUM_CAPABILITY, -1);
            if (SliceStore.getInstance(phoneId) == null) {
                String logStr = "CapabilityBroadcastReceiver( "
                        + TelephonyManager.convertPremiumCapabilityToString(mCapability)
                        + ") received invalid phoneId: " + phoneId;
                loge(logStr);
                AnomalyReporter.reportAnomaly(UUID.fromString(UUID_INVALID_PHONE_ID), logStr);
                return;
            } else if (capability != mCapability) {
                log("CapabilityBroadcastReceiver("
                        + TelephonyManager.convertPremiumCapabilityToString(mCapability)
                        + ") received invalid capability: "
                        + TelephonyManager.convertPremiumCapabilityToString(capability));
                return;
            }
            switch (action) {
                case ACTION_NOTIFICATION_CANCELED:
                    SliceStore.getInstance(phoneId).onUserCanceled(capability);
                    break;
                case ACTION_NOTIFICATION_DELAYED:
                    SliceStore.getInstance(phoneId).onUserDelayed(capability);
                    break;
                case ACTION_NOTIFICATION_MANAGE:
                    SliceStore.getInstance(phoneId).onUserManage(capability);
                    break;
                default:
                    String logStr = "CapabilityBroadcastReceiver("
                            + TelephonyManager.convertPremiumCapabilityToString(mCapability)
                            + ") received unknown action: " + action;
                    loge(logStr);
                    AnomalyReporter.reportAnomaly(UUID.fromString(UUID_UNKNOWN_ACTION), logStr);
                    break;
            }
        }
    }

    /**
     * Get the static SliceStore instance for the given phone or create one if it doesn't exist.
     *
     * @param phone The Phone to get the SliceStore for.
     * @return The static SliceStore instance.
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

    /**
     * Get the static SliceStore instance for the given phone ID if it exists.
     *
     * @param phoneId The phone ID to get the SliceStore for.
     * @return The static SliceStore instance or {@code null} if it hasn't been created yet.
     */
    @Nullable private static SliceStore getInstance(int phoneId) {
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
                mThrottledCapabilities.remove(capability);
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
                int capability = msg.arg1;
                String appName = (String) msg.obj;
                log("EVENT_DISPLAY_BOOSTER_NOTIFICATION: " + appName + " requests capability "
                        + TelephonyManager.convertPremiumCapabilityToString(capability));
                onDisplayBoosterNotification(capability, appName);
                break;
            }
            case EVENT_PURCHASE_TIMEOUT: {
                int capability = (int) msg.obj;
                log("EVENT_PURCHASE_TIMEOUT: for capability "
                        + TelephonyManager.convertPremiumCapabilityToString(capability));
                onTimeout(capability);
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
     * @param appName The name of the application requesting premium capabilities.
     * @param onComplete The callback message to send when the purchase request is complete.
     */
    public synchronized void purchasePremiumCapability(
            @TelephonyManager.PremiumCapability int capability, @NonNull String appName,
            @NonNull Message onComplete) {
        log("purchasePremiumCapability: " + appName + " requests capability "
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
        if (mPurchasedCapabilities.contains(capability) || isSlicingConfigActive(capability)) {
            // TODO (b/245882601): Handle capability expiry
            sendPurchaseResult(capability,
                    TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_ALREADY_PURCHASED,
                    onComplete);
            return;
        }
        if (mThrottledCapabilities.contains(capability)) {
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
            throttleCapability(capability, getThrottleDuration(
                    TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_NETWORK_CONGESTED));
            sendPurchaseResult(capability,
                    TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_NETWORK_CONGESTED,
                    onComplete);
            return;
        }
        if (mPendingPurchaseCapabilities.containsKey(capability)) {
            sendPurchaseResult(capability,
                    TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_ALREADY_IN_PROGRESS,
                    onComplete);
            return;
        }

        // All state checks passed. Mark purchase pending and display the booster notification to
        // prompt user purchase. Process through the handler since this method is synchronized.
        mPendingPurchaseCapabilities.put(capability, onComplete);
        sendMessage(obtainMessage(EVENT_DISPLAY_BOOSTER_NOTIFICATION, capability, 0 /* unused */,
                appName));
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

    private void throttleCapability(@TelephonyManager.PremiumCapability int capability,
            long throttleDuration) {
        // Throttle subsequent requests if necessary.
        if (!mThrottledCapabilities.contains(capability)) {
            if (throttleDuration > 0) {
                log("Throttle purchase requests for capability "
                        + TelephonyManager.convertPremiumCapabilityToString(capability) + " for "
                        + TimeUnit.MILLISECONDS.toMinutes(throttleDuration) + " minutes.");
                mThrottledCapabilities.add(capability);
                sendMessageDelayed(obtainMessage(EVENT_PURCHASE_UNTHROTTLED, capability),
                        throttleDuration);
            }
        } else {
            String logStr = TelephonyManager.convertPremiumCapabilityToString(capability)
                    + " is already throttled.";
            loge(logStr);
            AnomalyReporter.reportAnomaly(UUID.fromString(UUID_CAPABILITY_THROTTLED_TWICE), logStr);
        }
    }

    private void onDisplayBoosterNotification(@TelephonyManager.PremiumCapability int capability,
            @NonNull String appName) {
        // Start timeout on handler instead of setTimeoutAfter to differentiate cancel and timeout.
        long timeout = getCarrierConfigs().getLong(CarrierConfigManager
                .KEY_PREMIUM_CAPABILITY_NOTIFICATION_DISPLAY_TIMEOUT_MILLIS_LONG);
        sendMessageDelayed(obtainMessage(EVENT_PURCHASE_TIMEOUT, capability), timeout);

        log("Display the booster notification for capability "
                + TelephonyManager.convertPremiumCapabilityToString(capability) + " for "
                + TimeUnit.MILLISECONDS.toMinutes(timeout) + " minutes.");

        mPhone.getContext().getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(NETWORK_BOOST_NOTIFICATION_CHANNEL_ID,
                        mPhone.getContext().getResources().getString(
                                R.string.network_boost_notification_channel),
                        NotificationManager.IMPORTANCE_DEFAULT));
        mBroadcastReceivers.put(capability, new CapabilityBroadcastReceiver(capability));
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_NOTIFICATION_CANCELED);
        filter.addAction(ACTION_NOTIFICATION_DELAYED);
        filter.addAction(ACTION_NOTIFICATION_MANAGE);
        mPhone.getContext().registerReceiver(mBroadcastReceivers.get(capability), filter);

        Notification notification =
                new Notification.Builder(mPhone.getContext(), NETWORK_BOOST_NOTIFICATION_CHANNEL_ID)
                .setContentTitle(String.format(mPhone.getContext().getResources().getString(
                        R.string.network_boost_notification_title), appName))
                .setContentText(mPhone.getContext().getResources().getString(
                        R.string.network_boost_notification_detail))
                .setSmallIcon(R.drawable.ic_network_boost)
                .setContentIntent(getContentIntent(capability))
                .setDeleteIntent(getDeleteIntent(capability))
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(mPhone.getContext(), R.drawable.ic_network_boost),
                        mPhone.getContext().getResources().getString(
                                R.string.network_boost_notification_button_delay),
                        getDelayIntent(capability)).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(mPhone.getContext(), R.drawable.ic_network_boost),
                        mPhone.getContext().getResources().getString(
                                R.string.network_boost_notification_button_manage),
                        getManageIntent(capability)).build())
                .setAutoCancel(true)
                .build();

        mPhone.getContext().getSystemService(NotificationManager.class)
                .notify(NETWORK_BOOST_NOTIFICATION_TAG, capability, notification);
    }

    /**
     * Create the content intent for when the user clicks on the network boost notification.
     * Ths will start the {@link SliceStoreActivity} and display the {@link android.webkit.WebView}
     * to purchase the premium capability from the carrier.
     *
     * @param capability The premium capability that was requested.
     * @return The content intent.
     */
    @NonNull private PendingIntent getContentIntent(
            @TelephonyManager.PremiumCapability int capability) {
        Intent intent = new Intent(mPhone.getContext(), SliceStoreActivity.class);
        intent.putExtra(EXTRA_PHONE_ID, mPhone.getPhoneId());
        intent.putExtra(EXTRA_PREMIUM_CAPABILITY, capability);
        return PendingIntent.getActivity(mPhone.getContext(), 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
    }

    /**
     * Create the delete intent for when the user cancels the network boost notification.
     * This will send {@link #ACTION_NOTIFICATION_CANCELED}.
     *
     * @param capability The premium capability that was requested.
     * @return The delete intent.
     */
    @NonNull private PendingIntent getDeleteIntent(
            @TelephonyManager.PremiumCapability int capability) {
        Intent intent = new Intent(ACTION_NOTIFICATION_CANCELED);
        intent.putExtra(EXTRA_PHONE_ID, mPhone.getPhoneId());
        intent.putExtra(EXTRA_PREMIUM_CAPABILITY, capability);
        return PendingIntent.getBroadcast(mPhone.getContext(), 0, intent,
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_MUTABLE);
    }

    /**
     * Create the delay intent for when the user clicks the "Not now" button on the network boost
     * notification. This will send {@link #ACTION_NOTIFICATION_DELAYED}.
     *
     * @param capability The premium capability that was requested.
     * @return The delay intent.
     */
    @NonNull private PendingIntent getDelayIntent(
            @TelephonyManager.PremiumCapability int capability) {
        Intent intent = new Intent(ACTION_NOTIFICATION_DELAYED);
        intent.putExtra(EXTRA_PHONE_ID, mPhone.getPhoneId());
        intent.putExtra(EXTRA_PREMIUM_CAPABILITY, capability);
        return PendingIntent.getBroadcast(mPhone.getContext(), 0, intent,
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_MUTABLE);
    }

    /**
     * Create the manage intent for when the user clicks the "Manage" button on the network boost
     * notification. This will send {@link #ACTION_NOTIFICATION_MANAGE}.
     *
     * @param capability The premium capability that was requested.
     * @return The manage intent.
     */
    @NonNull private PendingIntent getManageIntent(
            @TelephonyManager.PremiumCapability int capability) {
        Intent intent = new Intent(ACTION_NOTIFICATION_MANAGE);
        intent.putExtra(EXTRA_PHONE_ID, mPhone.getPhoneId());
        intent.putExtra(EXTRA_PREMIUM_CAPABILITY, capability);
        return PendingIntent.getBroadcast(mPhone.getContext(), 0, intent,
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_MUTABLE);
    }

    private void cleanupBoosterNotification(@TelephonyManager.PremiumCapability int capability,
            @TelephonyManager.PurchasePremiumCapabilityResult int result) {
        mPhone.getContext().getSystemService(NotificationManager.class)
                .cancel(NETWORK_BOOST_NOTIFICATION_TAG, capability);
        mPhone.getContext().unregisterReceiver(mBroadcastReceivers.remove(capability));
        Message onComplete = mPendingPurchaseCapabilities.remove(capability);
        throttleCapability(capability, getThrottleDuration(result));
        sendPurchaseResult(capability, result, onComplete);
    }

    private void onTimeout(@TelephonyManager.PremiumCapability int capability) {
        log("onTimeout: " + TelephonyManager.convertPremiumCapabilityToString(capability));
        cleanupBoosterNotification(capability,
                TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_TIMEOUT);
        // TODO: Cancel SliceStoreActivity as well.
    }

    private void onUserCanceled(@TelephonyManager.PremiumCapability int capability) {
        log("onUserCanceled: " + TelephonyManager.convertPremiumCapabilityToString(capability));
        if (hasMessages(EVENT_PURCHASE_TIMEOUT, capability)) {
            log("onUserCanceled: Removing timeout for capability "
                    + TelephonyManager.convertPremiumCapabilityToString(capability));
            removeMessages(EVENT_PURCHASE_TIMEOUT, capability);
        }
        cleanupBoosterNotification(capability,
                TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_USER_CANCELED);
    }

    private void onUserDelayed(@TelephonyManager.PremiumCapability int capability) {
        log("onUserDelayed: " + TelephonyManager.convertPremiumCapabilityToString(capability));
        // TODO(b/245882092): implement
    }

    private void onUserManage(@TelephonyManager.PremiumCapability int capability) {
        log("onUserManage: " + TelephonyManager.convertPremiumCapabilityToString(capability));
        // TODO(b/245882092): implement
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

    @NetworkSliceInfo.SliceServiceType private int getSliceServiceType(
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

    private void loge(String s) {
        Log.e(TAG + "-" + mPhone.getPhoneId(), s);
    }
}
