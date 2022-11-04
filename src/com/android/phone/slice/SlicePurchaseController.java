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

package com.android.phone.slice;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.PersistableBundle;
import android.telephony.AnomalyReporter;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.data.NetworkSliceInfo;
import android.telephony.data.NetworkSlicingConfig;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.WebView;

import com.android.internal.telephony.Phone;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.MalformedURLException;
import java.net.URL;
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
 * The SlicePurchaseController controls the purchase and availability of all cellular premium
 * capabilities. Applications can check whether premium capabilities are available by calling
 * {@link TelephonyManager#isPremiumCapabilityAvailableForPurchase(int)}. If this returns true,
 * they can then call {@link TelephonyManager#purchasePremiumCapability(int, Executor, Consumer)}
 * to purchase the premium capability. If all conditions are met, a notification will be displayed
 * to the user prompting them to purchase the premium capability. If the user confirms on the
 * notification, a {@link WebView} will open that allows the user to purchase the premium capability
 * from the carrier. If the purchase is successful, the premium capability will be available for
 * all applications to request through {@link ConnectivityManager#requestNetwork}.
 */
public class SlicePurchaseController extends Handler {
    @NonNull private static final String TAG = "SlicePurchaseController";

    /** Unknown failure code. */
    public static final int FAILURE_CODE_UNKNOWN = 0;
    /** Network boost purchase failed because the carrier URL is unavailable. */
    public static final int FAILURE_CODE_CARRIER_URL_UNAVAILABLE = 1;
    /** Network boost purchase failed because the server is unreachable. */
    public static final int FAILURE_CODE_SERVER_UNREACHABLE = 2;
    /** Network boost purchase failed because user authentication failed. */
    public static final int FAILURE_CODE_AUTHENTICATION_FAILED = 3;
    /** Network boost purchase failed because the payment failed. */
    public static final int FAILURE_CODE_PAYMENT_FAILED = 4;

    /**
     * Failure codes that the carrier website can return when a premium capability purchase fails.
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "FAILURE_CODE_" }, value = {
            FAILURE_CODE_UNKNOWN,
            FAILURE_CODE_CARRIER_URL_UNAVAILABLE,
            FAILURE_CODE_SERVER_UNREACHABLE,
            FAILURE_CODE_AUTHENTICATION_FAILED,
            FAILURE_CODE_PAYMENT_FAILED})
    public @interface FailureCode {}

    /** Value for an invalid premium capability. */
    public static final int PREMIUM_CAPABILITY_INVALID = -1;

    /** Purchasing the premium capability is no longer throttled. */
    private static final int EVENT_PURCHASE_UNTHROTTLED = 1;
    /** Slicing config changed. */
    private static final int EVENT_SLICING_CONFIG_CHANGED = 2;
    /** Display booster notification. */
    private static final int EVENT_DISPLAY_BOOSTER_NOTIFICATION = 3;
    /**
     * Premium capability was not purchased within the timeout specified by
     * {@link CarrierConfigManager#KEY_PREMIUM_CAPABILITY_NOTIFICATION_DISPLAY_TIMEOUT_MILLIS_LONG}.
     */
    private static final int EVENT_PURCHASE_TIMEOUT = 4;
    /**
     * Network did not set up the slicing configuration within the timeout specified by
     * {@link CarrierConfigManager#KEY_PREMIUM_CAPABILITY_NETWORK_SETUP_TIME_MILLIS_LONG}.
     */
    private static final int EVENT_SETUP_TIMEOUT = 5;

    /** UUID to report an anomaly when a premium capability is throttled twice in a row. */
    private static final String UUID_CAPABILITY_THROTTLED_TWICE =
            "15574927-e2e2-4593-99d4-2f340d22b383";
    /** UUID to report an anomaly when receiving an invalid phone ID. */
    private static final String UUID_INVALID_PHONE_ID = "ced79f1a-8ac0-4260-8cf3-08b54c0494f3";
    /** UUID to report an anomaly when receiving an unknown action. */
    private static final String UUID_UNKNOWN_ACTION = "0197efb0-dab1-4b0a-abaf-ac9336ec7923";
    /** UUID to report an anomaly when receiving an unknown failure code with a non-empty reason. */
    private static final String UUID_UNKNOWN_FAILURE_CODE = "76943b23-4415-400c-9855-b534fc4fc62c";
    /**
     * UUID to report an anomaly when the network fails to set up a slicing configuration after
     * the user purchases a premium capability.
     */
    private static final String UUID_NETWORK_SETUP_FAILED = "12eeffbf-08f8-40ed-9a00-d344199552fc";

    /**
     * Action to start the slice purchase application and display the network boost notification.
     */
    public static final String ACTION_START_SLICE_PURCHASE_APP =
            "com.android.phone.slice.action.START_SLICE_PURCHASE_APP";
    /** Action indicating the premium capability purchase was not completed in time. */
    public static final String ACTION_SLICE_PURCHASE_APP_RESPONSE_TIMEOUT =
            "com.android.phone.slice.action.SLICE_PURCHASE_APP_RESPONSE_TIMEOUT";
    /** Action indicating the network boost notification or WebView was canceled. */
    private static final String ACTION_SLICE_PURCHASE_APP_RESPONSE_CANCELED =
            "com.android.phone.slice.action.SLICE_PURCHASE_APP_RESPONSE_CANCELED";
    /** Action indicating a carrier error prevented premium capability purchase. */
    private static final String ACTION_SLICE_PURCHASE_APP_RESPONSE_CARRIER_ERROR =
            "com.android.phone.slice.action.SLICE_PURCHASE_APP_RESPONSE_CARRIER_ERROR";
    /**
     * Action indicating a Telephony or slice purchase application error prevented premium
     * capability purchase.
     */
    private static final String ACTION_SLICE_PURCHASE_APP_RESPONSE_REQUEST_FAILED =
            "com.android.phone.slice.action.SLICE_PURCHASE_APP_RESPONSE_REQUEST_FAILED";
    /** Action indicating the purchase request was not made on the default data subscription. */
    private static final String ACTION_SLICE_PURCHASE_APP_RESPONSE_NOT_DEFAULT_DATA_SUB =
            "com.android.phone.slice.action.SLICE_PURCHASE_APP_RESPONSE_NOT_DEFAULT_DATA_SUB";
    /** Action indicating the purchase request was successful. */
    private static final String ACTION_SLICE_PURCHASE_APP_RESPONSE_SUCCESS =
            "com.android.phone.slice.action.SLICE_PURCHASE_APP_RESPONSE_SUCCESS";

    /** Extra for the phone index to send to the slice purchase application. */
    public static final String EXTRA_PHONE_ID = "com.android.phone.slice.extra.PHONE_ID";
    /** Extra for the subscription ID to send to the slice purchase application. */
    public static final String EXTRA_SUB_ID = "com.android.phone.slice.extra.SUB_ID";
    /**
     * Extra for the requested premium capability to purchase from the slice purchase application.
     */
    public static final String EXTRA_PREMIUM_CAPABILITY =
            "com.android.phone.slice.extra.PREMIUM_CAPABILITY";
    /** Extra for the duration of the purchased premium capability. */
    public static final String EXTRA_PURCHASE_DURATION =
            "com.android.phone.slice.extra.PURCHASE_DURATION";
    /** Extra for the {@link FailureCode} why the premium capability purchase failed. */
    public static final String EXTRA_FAILURE_CODE = "com.android.phone.slice.extra.FAILURE_CODE";
    /** Extra for the human-readable reason why the premium capability purchase failed. */
    public static final String EXTRA_FAILURE_REASON =
            "com.android.phone.slice.extra.FAILURE_REASON";
    /**
     * Extra for the application name requesting to purchase the premium capability
     * from the slice purchase application.
     */
    public static final String EXTRA_REQUESTING_APP_NAME =
            "com.android.phone.slice.extra.REQUESTING_APP_NAME";
    /**
     * Extra for the canceled PendingIntent that the slice purchase application can send as a
     * response if the network boost notification or WebView was canceled by the user.
     * Sends {@link #ACTION_SLICE_PURCHASE_APP_RESPONSE_CANCELED}.
     */
    public static final String EXTRA_INTENT_CANCELED =
            "com.android.phone.slice.extra.INTENT_CANCELED";
    /**
     * Extra for the carrier error PendingIntent that the slice purchase application can send as a
     * response if the premium capability purchase request failed due to a carrier error.
     * Sends {@link #ACTION_SLICE_PURCHASE_APP_RESPONSE_CARRIER_ERROR}.
     * Sender can modify the intent to specify the failure code and reason for failure with
     * {@link #EXTRA_FAILURE_CODE} and {@link #EXTRA_FAILURE_REASON}.
     */
    public static final String EXTRA_INTENT_CARRIER_ERROR =
            "com.android.phone.slice.extra.INTENT_CARRIER_ERROR";
    /**
     * Extra for the request failed PendingIntent that the slice purchase application can send as a
     * response if the premium capability purchase request failed due to an error in Telephony or
     * the slice purchase application.
     * Sends {@link #ACTION_SLICE_PURCHASE_APP_RESPONSE_REQUEST_FAILED}.
     */
    public static final String EXTRA_INTENT_REQUEST_FAILED =
            "com.android.phone.slice.extra.INTENT_REQUEST_FAILED";
    /**
     * Extra for the not-default data subscription ID PendingIntent that the slice purchase
     * application can send as a response if the premium capability purchase request failed because
     * it was not requested on the default data subscription.
     * Sends {@link #ACTION_SLICE_PURCHASE_APP_RESPONSE_NOT_DEFAULT_DATA_SUB}.
     */
    public static final String EXTRA_INTENT_NOT_DEFAULT_DATA_SUB =
            "com.android.phone.slice.extra.INTENT_NOT_DEFAULT_DATA_SUB";
    /**
     * Extra for the success PendingIntent that the slice purchase application can send as a
     * response if the premium capability purchase request was successful.
     * Sends {@link #ACTION_SLICE_PURCHASE_APP_RESPONSE_SUCCESS}.
     * Sender can modify the intent to specify a purchase duration with
     * {@link #EXTRA_PURCHASE_DURATION}.
     */
    public static final String EXTRA_INTENT_SUCCESS =
            "com.android.phone.slice.extra.INTENT_SUCCESS";

    /** Component name to send an explicit broadcast to SlicePurchaseBroadcastReceiver. */
    private static final ComponentName SLICE_PURCHASE_APP_COMPONENT_NAME =
            ComponentName.unflattenFromString(
                    "com.android.carrierdefaultapp/.SlicePurchaseBroadcastReceiver");

    /** Map of phone ID -> SlicePurchaseController instances. */
    @NonNull private static final Map<Integer, SlicePurchaseController> sInstances =
            new HashMap<>();

    /** The Phone instance used to create the SlicePurchaseController. */
    @NonNull private final Phone mPhone;
    /** The set of capabilities that are pending network setup. */
    @NonNull private final Set<Integer> mPendingSetupCapabilities = new HashSet<>();
    /** The set of throttled capabilities. */
    @NonNull private final Set<Integer> mThrottledCapabilities = new HashSet<>();
    /** A map of pending capabilities to the onComplete message for the purchase request. */
    @NonNull private final Map<Integer, Message> mPendingPurchaseCapabilities = new HashMap<>();
    /**
     * A map of capabilities to the SlicePurchaseControllerBroadcastReceiver to handle
     * slice purchase application responses.
     */
    @NonNull private final Map<Integer, SlicePurchaseControllerBroadcastReceiver>
            mSlicePurchaseControllerBroadcastReceivers = new HashMap<>();
    /** The current network slicing configuration. */
    @Nullable private NetworkSlicingConfig mSlicingConfig;

    private class SlicePurchaseControllerBroadcastReceiver extends BroadcastReceiver {
        @TelephonyManager.PremiumCapability private final int mCapability;

        SlicePurchaseControllerBroadcastReceiver(
                @TelephonyManager.PremiumCapability int capability) {
            mCapability = capability;
        }

        @Override
        public void onReceive(@NonNull Context context, @NonNull Intent intent) {
            String action = intent.getAction();
            logd("SlicePurchaseControllerBroadcastReceiver("
                    + TelephonyManager.convertPremiumCapabilityToString(mCapability)
                    + ") received action: " + action);
            int phoneId = intent.getIntExtra(EXTRA_PHONE_ID,
                    SubscriptionManager.INVALID_PHONE_INDEX);
            int capability = intent.getIntExtra(EXTRA_PREMIUM_CAPABILITY,
                    PREMIUM_CAPABILITY_INVALID);
            if (SlicePurchaseController.getInstance(phoneId) == null) {
                reportAnomaly(UUID_INVALID_PHONE_ID, "SlicePurchaseControllerBroadcastReceiver( "
                        + TelephonyManager.convertPremiumCapabilityToString(mCapability)
                        + ") received invalid phoneId: " + phoneId);
                return;
            } else if (capability != mCapability) {
                logd("SlicePurchaseControllerBroadcastReceiver("
                        + TelephonyManager.convertPremiumCapabilityToString(mCapability)
                        + ") ignoring intent for capability "
                        + TelephonyManager.convertPremiumCapabilityToString(capability));
                return;
            }
            switch (action) {
                case ACTION_SLICE_PURCHASE_APP_RESPONSE_CANCELED: {
                    logd("Slice purchase application canceled for capability: "
                            + TelephonyManager.convertPremiumCapabilityToString(capability));
                    SlicePurchaseController.getInstance(phoneId)
                            .sendPurchaseResultFromSlicePurchaseApp(capability,
                            TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_USER_CANCELED,
                            true);
                    break;
                }
                case ACTION_SLICE_PURCHASE_APP_RESPONSE_CARRIER_ERROR: {
                    int failureCode = intent.getIntExtra(EXTRA_FAILURE_CODE, FAILURE_CODE_UNKNOWN);
                    String failureReason = intent.getStringExtra(EXTRA_FAILURE_REASON);
                    SlicePurchaseController.getInstance(phoneId).onCarrierError(
                            capability, failureCode, failureReason);
                    break;
                }
                case ACTION_SLICE_PURCHASE_APP_RESPONSE_REQUEST_FAILED: {
                    logd("Purchase premium capability request failed for capability: "
                            + TelephonyManager.convertPremiumCapabilityToString(capability));
                    SlicePurchaseController.getInstance(phoneId)
                            .sendPurchaseResultFromSlicePurchaseApp(capability,
                            TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_REQUEST_FAILED,
                            false);
                    break;
                }
                case ACTION_SLICE_PURCHASE_APP_RESPONSE_NOT_DEFAULT_DATA_SUB: {
                    logd("Purchase premium capability request was not made on the default data "
                            + "subscription for capability: "
                            + TelephonyManager.convertPremiumCapabilityToString(capability));
                    SlicePurchaseController.getInstance(phoneId)
                            .sendPurchaseResultFromSlicePurchaseApp(capability,
                            TelephonyManager
                                    .PURCHASE_PREMIUM_CAPABILITY_RESULT_NOT_DEFAULT_DATA_SUB,
                            false);
                    break;
                }
                case ACTION_SLICE_PURCHASE_APP_RESPONSE_SUCCESS: {
                    long duration = intent.getLongExtra(EXTRA_PURCHASE_DURATION, 0);
                    SlicePurchaseController.getInstance(phoneId).onCarrierSuccess(
                            capability, duration);
                    break;
                }
                default:
                    reportAnomaly(UUID_UNKNOWN_ACTION, "SlicePurchaseControllerBroadcastReceiver("
                            + TelephonyManager.convertPremiumCapabilityToString(mCapability)
                            + ") received unknown action: " + action);
                    break;
            }
        }
    }

    /**
     * Get the static SlicePurchaseController instance for the given phone or create one if it
     * doesn't exist.
     *
     * @param phone The Phone to get the SlicePurchaseController for.
     * @return The static SlicePurchaseController instance.
     */
    @NonNull public static synchronized SlicePurchaseController getInstance(@NonNull Phone phone) {
        // TODO: Add listeners for multi sim setting changed (maybe carrier config changed too)
        //  that dismiss notifications and update SlicePurchaseController instance
        int phoneId = phone.getPhoneId();
        if (sInstances.get(phoneId) == null) {
            sInstances.put(phoneId, new SlicePurchaseController(phone));
        }
        return sInstances.get(phoneId);
    }

    /**
     * Get the static SlicePurchaseController instance for the given phone ID if it exists.
     *
     * @param phoneId The phone ID to get the SlicePurchaseController for.
     * @return The static SlicePurchaseController instance or
     *         {@code null} if it hasn't been created yet.
     */
    @Nullable private static SlicePurchaseController getInstance(int phoneId) {
        return sInstances.get(phoneId);
    }

    private SlicePurchaseController(@NonNull Phone phone) {
        super(phone.getLooper());
        mPhone = phone;
        // TODO: Create a cached value for slicing config in DataIndication and initialize here
        mPhone.mCi.registerForSlicingConfigChanged(this, EVENT_SLICING_CONFIG_CHANGED, null);
    }

    @Override
    public void handleMessage(@NonNull Message msg) {
        switch (msg.what) {
            case EVENT_PURCHASE_UNTHROTTLED: {
                int capability = (int) msg.obj;
                logd("EVENT_PURCHASE_UNTHROTTLED: for capability "
                        + TelephonyManager.convertPremiumCapabilityToString(capability));
                mThrottledCapabilities.remove(capability);
                break;
            }
            case EVENT_SLICING_CONFIG_CHANGED: {
                AsyncResult ar = (AsyncResult) msg.obj;
                NetworkSlicingConfig config = (NetworkSlicingConfig) ar.result;
                logd("EVENT_SLICING_CONFIG_CHANGED: from " + mSlicingConfig + " to " + config);
                mSlicingConfig = config;
                onSlicingConfigChanged();
                break;
            }
            case EVENT_DISPLAY_BOOSTER_NOTIFICATION: {
                int capability = msg.arg1;
                String appName = (String) msg.obj;
                logd("EVENT_DISPLAY_BOOSTER_NOTIFICATION: " + appName + " requests capability "
                        + TelephonyManager.convertPremiumCapabilityToString(capability));
                onDisplayBoosterNotification(capability, appName);
                break;
            }
            case EVENT_PURCHASE_TIMEOUT: {
                int capability = (int) msg.obj;
                logd("EVENT_PURCHASE_TIMEOUT: for capability "
                        + TelephonyManager.convertPremiumCapabilityToString(capability));
                onTimeout(capability);
                break;
            }
            case EVENT_SETUP_TIMEOUT:
                int capability = (int) msg.obj;
                logd("EVENT_SETUP_TIMEOUT: for capability "
                        + TelephonyManager.convertPremiumCapabilityToString(capability));
                onSetupTimeout(capability);
                break;
            default:
                loge("Unknown event: " + msg.obj);
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
            logd("Premium capabilities unsupported by the device.");
            return false;
        }
        if (!isPremiumCapabilitySupportedByCarrier(capability)) {
            logd("Premium capability "
                    + TelephonyManager.convertPremiumCapabilityToString(capability)
                    + " unsupported by the carrier.");
            return false;
        }
        if (!isDefaultDataSub()) {
            logd("Premium capability "
                    + TelephonyManager.convertPremiumCapabilityToString(capability)
                    + " unavailable on the non-default data subscription.");
            return false;
        }
        logd("Premium capability "
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
        logd("purchasePremiumCapability: " + appName + " requests capability "
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
        if (!isDefaultDataSub()) {
            sendPurchaseResult(capability,
                    TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_NOT_DEFAULT_DATA_SUB,
                    onComplete);
            return;
        }
        if (isSlicingConfigActive(capability)) {
            sendPurchaseResult(capability,
                    TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_ALREADY_PURCHASED,
                    onComplete);
            return;
        }
        if (mPendingSetupCapabilities.contains(capability)) {
            sendPurchaseResult(capability,
                    TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_PENDING_NETWORK_SETUP,
                    onComplete);
            return;
        }
        if (mThrottledCapabilities.contains(capability)) {
            sendPurchaseResult(capability,
                    TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_THROTTLED,
                    onComplete);
            return;
        }
        if (!isNetworkAvailable()) {
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
        logd("Purchase result for capability "
                + TelephonyManager.convertPremiumCapabilityToString(capability)
                + ": " + TelephonyManager.convertPurchaseResultToString(result));
        AsyncResult.forMessage(onComplete, result, null);
        onComplete.sendToTarget();
    }

    private void sendPurchaseResultFromSlicePurchaseApp(
            @TelephonyManager.PremiumCapability int capability,
            @TelephonyManager.PurchasePremiumCapabilityResult int result, boolean throttle) {
        mPhone.getContext().unregisterReceiver(
                mSlicePurchaseControllerBroadcastReceivers.remove(capability));
        removeMessages(EVENT_PURCHASE_TIMEOUT, capability);
        if (throttle) {
            throttleCapability(capability, getThrottleDuration(result));
        }
        sendPurchaseResult(capability, result, mPendingPurchaseCapabilities.remove(capability));
    }

    private void throttleCapability(@TelephonyManager.PremiumCapability int capability,
            long throttleDuration) {
        // Throttle subsequent requests if necessary.
        if (!mThrottledCapabilities.contains(capability)) {
            if (throttleDuration > 0) {
                logd("Throttle purchase requests for capability "
                        + TelephonyManager.convertPremiumCapabilityToString(capability) + " for "
                        + TimeUnit.MILLISECONDS.toMinutes(throttleDuration) + " minutes.");
                mThrottledCapabilities.add(capability);
                sendMessageDelayed(obtainMessage(EVENT_PURCHASE_UNTHROTTLED, capability),
                        throttleDuration);
            }
        } else {
            reportAnomaly(UUID_CAPABILITY_THROTTLED_TWICE,
                    TelephonyManager.convertPremiumCapabilityToString(capability)
                            + " is already throttled.");
        }
    }

    private void onSlicingConfigChanged() {
        for (int capability : new int[] {TelephonyManager.PREMIUM_CAPABILITY_PRIORITIZE_LATENCY}) {
            if (isSlicingConfigActive(capability) && hasMessages(EVENT_SETUP_TIMEOUT, capability)) {
                logd("Successfully set up slicing configuration for "
                        + TelephonyManager.convertPremiumCapabilityToString(capability));
                mPendingSetupCapabilities.remove(capability);
                removeMessages(EVENT_SETUP_TIMEOUT, capability);
            }
        }
    }

    private void onDisplayBoosterNotification(@TelephonyManager.PremiumCapability int capability,
            @NonNull String appName) {
        // Start timeout for purchase completion.
        long timeout = getCarrierConfigs().getLong(CarrierConfigManager
                .KEY_PREMIUM_CAPABILITY_NOTIFICATION_DISPLAY_TIMEOUT_MILLIS_LONG);
        logd("Start purchase timeout for "
                + TelephonyManager.convertPremiumCapabilityToString(capability) + " for "
                + TimeUnit.MILLISECONDS.toMinutes(timeout) + " minutes.");
        sendMessageDelayed(obtainMessage(EVENT_PURCHASE_TIMEOUT, capability), timeout);

        // Broadcast start intent to start the slice purchase application
        Intent intent = new Intent(ACTION_START_SLICE_PURCHASE_APP);
        intent.setComponent(SLICE_PURCHASE_APP_COMPONENT_NAME);
        intent.putExtra(EXTRA_PHONE_ID, mPhone.getPhoneId());
        intent.putExtra(EXTRA_SUB_ID, mPhone.getSubId());
        intent.putExtra(EXTRA_PREMIUM_CAPABILITY, capability);
        intent.putExtra(EXTRA_REQUESTING_APP_NAME, appName);
        intent.putExtra(EXTRA_INTENT_CANCELED, createPendingIntent(
                ACTION_SLICE_PURCHASE_APP_RESPONSE_CANCELED, capability, false));
        intent.putExtra(EXTRA_INTENT_CARRIER_ERROR, createPendingIntent(
                ACTION_SLICE_PURCHASE_APP_RESPONSE_CARRIER_ERROR, capability, true));
        intent.putExtra(EXTRA_INTENT_REQUEST_FAILED, createPendingIntent(
                ACTION_SLICE_PURCHASE_APP_RESPONSE_REQUEST_FAILED, capability, false));
        intent.putExtra(EXTRA_INTENT_NOT_DEFAULT_DATA_SUB, createPendingIntent(
                ACTION_SLICE_PURCHASE_APP_RESPONSE_NOT_DEFAULT_DATA_SUB, capability, false));
        intent.putExtra(EXTRA_INTENT_SUCCESS, createPendingIntent(
                ACTION_SLICE_PURCHASE_APP_RESPONSE_SUCCESS, capability, true));
        logd("Broadcasting start intent to SlicePurchaseBroadcastReceiver.");
        mPhone.getContext().sendBroadcast(intent);

        // Listen for responses from the slice purchase application
        mSlicePurchaseControllerBroadcastReceivers.put(capability,
                new SlicePurchaseControllerBroadcastReceiver(capability));
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SLICE_PURCHASE_APP_RESPONSE_CANCELED);
        filter.addAction(ACTION_SLICE_PURCHASE_APP_RESPONSE_CARRIER_ERROR);
        filter.addAction(ACTION_SLICE_PURCHASE_APP_RESPONSE_REQUEST_FAILED);
        filter.addAction(ACTION_SLICE_PURCHASE_APP_RESPONSE_NOT_DEFAULT_DATA_SUB);
        filter.addAction(ACTION_SLICE_PURCHASE_APP_RESPONSE_SUCCESS);
        mPhone.getContext().registerReceiver(
                mSlicePurchaseControllerBroadcastReceivers.get(capability), filter);
    }

    /**
     * Create the PendingIntent to allow the slice purchase application to send back responses.
     *
     * @param action The action that will be sent for this PendingIntent
     * @param capability The premium capability that was requested.
     * @param mutable {@code true} if the PendingIntent should be mutable and
     *                {@code false} if it should be immutable.
     * @return The PendingIntent for the given action and capability.
     */
    @NonNull private PendingIntent createPendingIntent(@NonNull String action,
            @TelephonyManager.PremiumCapability int capability, boolean mutable) {
        Intent intent = new Intent(action);
        intent.putExtra(EXTRA_PHONE_ID, mPhone.getPhoneId());
        intent.putExtra(EXTRA_PREMIUM_CAPABILITY, capability);
        return PendingIntent.getBroadcast(mPhone.getContext(), capability, intent,
                PendingIntent.FLAG_CANCEL_CURRENT
                        | (mutable ? PendingIntent.FLAG_MUTABLE : PendingIntent.FLAG_IMMUTABLE));
    }

    private void onTimeout(@TelephonyManager.PremiumCapability int capability) {
        logd("onTimeout: " + TelephonyManager.convertPremiumCapabilityToString(capability));
        // Broadcast timeout intent to clean up the slice purchase notification and activity
        Intent intent = new Intent(ACTION_SLICE_PURCHASE_APP_RESPONSE_TIMEOUT);
        intent.setComponent(SLICE_PURCHASE_APP_COMPONENT_NAME);
        intent.putExtra(EXTRA_PHONE_ID, mPhone.getPhoneId());
        intent.putExtra(EXTRA_PREMIUM_CAPABILITY, capability);
        logd("Broadcasting timeout intent to SlicePurchaseBroadcastReceiver.");
        mPhone.getContext().sendBroadcast(intent);

        sendPurchaseResultFromSlicePurchaseApp(
                capability, TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_TIMEOUT, true);
    }

    private void onCarrierError(@TelephonyManager.PremiumCapability int capability,
            @FailureCode int failureCode, @Nullable String failureReason) {
        logd("Carrier error for capability: "
                + TelephonyManager.convertPremiumCapabilityToString(capability) + " with code: "
                + convertFailureCodeToString(failureCode) + " and reason: " + failureReason);
        if (failureCode == FAILURE_CODE_UNKNOWN && !TextUtils.isEmpty(failureReason)) {
            reportAnomaly(UUID_UNKNOWN_FAILURE_CODE,
                    "Failure code needs to be added for: " + failureReason);
        }
        sendPurchaseResultFromSlicePurchaseApp(capability,
                TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_CARRIER_ERROR, true);
    }

    private void onCarrierSuccess(@TelephonyManager.PremiumCapability int capability,
            long duration) {
        logd("Successfully purchased premium capability "
                + TelephonyManager.convertPremiumCapabilityToString(capability) + (duration > 0
                ? " for " + TimeUnit.MILLISECONDS.toMinutes(duration) + " minutes." : "."));
        mPendingSetupCapabilities.add(capability);
        long setupDuration = getCarrierConfigs().getLong(
                CarrierConfigManager.KEY_PREMIUM_CAPABILITY_NETWORK_SETUP_TIME_MILLIS_LONG);
        logd("Waiting " + TimeUnit.MILLISECONDS.toMinutes(setupDuration) + " minutes for the "
                + "network to set up the slicing configuration.");
        sendMessageDelayed(obtainMessage(EVENT_SETUP_TIMEOUT, capability), setupDuration);
        sendPurchaseResultFromSlicePurchaseApp(
                capability, TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_SUCCESS, false);
    }

    private void onSetupTimeout(@TelephonyManager.PremiumCapability int capability) {
        logd("onSetupTimeout: " + TelephonyManager.convertPremiumCapabilityToString(capability));
        mPendingSetupCapabilities.remove(capability);
        if (!isSlicingConfigActive(capability)) {
            reportAnomaly(UUID_NETWORK_SETUP_FAILED,
                    "Failed to set up slicing configuration for capability "
                            + TelephonyManager.convertPremiumCapabilityToString(capability)
                            + " within the time specified.");
        }
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
        String url = getCarrierConfigs().getString(
                CarrierConfigManager.KEY_PREMIUM_CAPABILITY_PURCHASE_URL_STRING);
        if (TextUtils.isEmpty(url)) {
            return false;
        } else {
            try {
                new URL(url);
            } catch (MalformedURLException e) {
                return false;
            }
        }
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

    private boolean isDefaultDataSub() {
        return mPhone.getSubId() == SubscriptionManager.getDefaultDataSubscriptionId();
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

    private boolean isNetworkAvailable() {
        // TODO (b/251558673): Create a listener for data network type changed to dismiss
        //  notification and activity when the network is no longer available.
        switch (mPhone.getServiceState().getDataNetworkType()) {
            case TelephonyManager.NETWORK_TYPE_NR:
                return true;
            case TelephonyManager.NETWORK_TYPE_LTE:
            case TelephonyManager.NETWORK_TYPE_LTE_CA:
                return getCarrierConfigs().getBoolean(
                        CarrierConfigManager.KEY_PREMIUM_CAPABILITY_SUPPORTED_ON_LTE_BOOL);
        }
        return false;
    }

    private boolean isNetworkCongested(@TelephonyManager.PremiumCapability int capability) {
        // TODO: Implement TS43
        return true;
    }

    /**
     * Returns the failure code {@link FailureCode} as a String.
     *
     * @param failureCode The failure code.
     * @return The failure code as a String.
     */
    @NonNull private static String convertFailureCodeToString(@FailureCode int failureCode) {
        switch (failureCode) {
            case FAILURE_CODE_UNKNOWN: return "UNKNOWN";
            case FAILURE_CODE_CARRIER_URL_UNAVAILABLE: return "CARRIER_URL_UNAVAILABLE";
            case FAILURE_CODE_SERVER_UNREACHABLE: return "SERVER_UNREACHABLE";
            case FAILURE_CODE_AUTHENTICATION_FAILED: return "AUTHENTICATION_FAILED";
            case FAILURE_CODE_PAYMENT_FAILED: return "PAYMENT_FAILED";
            default:
                return "UNKNOWN(" + failureCode + ")";
        }
    }

    private void reportAnomaly(@NonNull String uuid, @NonNull String log) {
        loge(log);
        AnomalyReporter.reportAnomaly(UUID.fromString(uuid), log);
    }

    private void logd(String s) {
        Log.d(TAG + "-" + mPhone.getPhoneId(), s);
    }

    private void loge(String s) {
        Log.e(TAG + "-" + mPhone.getPhoneId(), s);
    }
}
