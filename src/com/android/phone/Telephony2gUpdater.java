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

package com.android.phone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.UserManager;
import android.telephony.RadioAccessFamily;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.RILConstants;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * A {@link BroadcastReceiver} that ensures that user restrictions are correctly applied to
 * telephony.
 * This includes handling broadcasts from user restriction state changes, as well as ensuring that
 * SIM-specific settings are correctly applied when new subscriptions become active.
 *
 * Callers are expected to call {@code init()} and keep an instance of this class alive.
 */
public class Telephony2gUpdater extends BroadcastReceiver {
    private static final String TAG = "TelephonyUserManagerReceiver";

    // We can't interact with the HAL on the main thread of the phone process (where
    // receivers are run by default), so we execute our logic from a separate thread.
    private final Executor mExecutor;
    private final Context mContext;
    private final long mBaseAllowedNetworks;

    public Telephony2gUpdater(Executor executor, Context context) {
        this(executor, context,
                RadioAccessFamily.getRafFromNetworkType(RILConstants.PREFERRED_NETWORK_MODE));
    }

    public Telephony2gUpdater(Executor executor, Context context,
            long baseAllowedNetworks) {
        mExecutor = executor;
        mContext = context;
        mBaseAllowedNetworks = baseAllowedNetworks;
    }

    /**
     * Register the given instance as a {@link BroadcastReceiver} and a {@link
     * SubscriptionManager.OnSubscriptionsChangedListener}.
     */
    public void init() {
        mContext.getSystemService(SubscriptionManager.class).addOnSubscriptionsChangedListener(
                mExecutor, new SubscriptionListener());
        IntentFilter filter = new IntentFilter();
        filter.addAction(UserManager.ACTION_USER_RESTRICTIONS_CHANGED);
        mContext.registerReceiver(this, filter);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        Log.i(TAG, "Received callback for action " + intent.getAction());
        final PendingResult result = goAsync();
        mExecutor.execute(() -> {
            Log.i(TAG, "Running handler for action " + intent.getAction());
            handleUserRestrictionsChanged(context);
            result.finish();
        });
    }

    /**
     * Update all active subscriptions with allowed network types depending on the current state
     * of the {@link UserManager.DISALLOW_2G}.
     */
    @VisibleForTesting
    public void handleUserRestrictionsChanged(Context context) {
        UserManager um = context.getSystemService(UserManager.class);
        TelephonyManager tm = context.getSystemService(TelephonyManager.class);
        SubscriptionManager sm = context.getSystemService(SubscriptionManager.class);
        final long twoGBitmask = TelephonyManager.NETWORK_CLASS_BITMASK_2G;

        boolean shouldDisable2g = um.hasUserRestriction(UserManager.DISALLOW_CELLULAR_2G);

        // This is expected when subscription info cannot be determined. We'll get another
        // callback in the future from our SubscriptionListener once we have valid subscriptions.
        List<SubscriptionInfo> subscriptionInfoList = sm.getAvailableSubscriptionInfoList();
        if (subscriptionInfoList == null) {
            return;
        }

        long allowedNetworkTypes = mBaseAllowedNetworks;

        // 2G device admin controls are global
        for (SubscriptionInfo info : subscriptionInfoList) {
            TelephonyManager telephonyManager = tm.createForSubscriptionId(
                    info.getSubscriptionId());
            if (shouldDisable2g) {
                allowedNetworkTypes &= ~twoGBitmask;
            } else {
                allowedNetworkTypes |= twoGBitmask;
            }
            telephonyManager.setAllowedNetworkTypesForReason(
                    TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER_RESTRICTIONS,
                    allowedNetworkTypes);
        }
    }

    private class SubscriptionListener extends SubscriptionManager.OnSubscriptionsChangedListener {
        @Override
        public void onSubscriptionsChanged() {
            Log.i(TAG, "Running handler for subscription change.");
            handleUserRestrictionsChanged(mContext);
        }
    }

}
