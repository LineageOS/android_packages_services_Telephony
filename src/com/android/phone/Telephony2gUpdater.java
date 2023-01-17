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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * A {@link BroadcastReceiver} that ensures that user restrictions are correctly applied to
 * telephony.
 * This includes handling broadcasts from user restriction state changes, as well as ensuring that
 * SIM-specific settings are correctly applied when new subscriptions become active.
 *
 * <p>
 * Callers are expected to call {@code init()} and keep an instance of this class alive.
 * </p>
 */
public class Telephony2gUpdater extends BroadcastReceiver {
    private static final String TAG = "Telephony2gUpdater";

    // We can't interact with the HAL on the main thread of the phone process (where
    // receivers are run by default), so we execute our logic from a separate thread.
    // The correctness of this implementation relies heavily on this executor ensuring
    // tasks are serially executed i.e. ExecutorService.newSingleThreadExecutor()
    private final Executor mExecutor;
    private final Context mContext;
    private final long mBaseAllowedNetworks;

    private UserManager mUserManager;
    private TelephonyManager mTelephonyManager;
    private SubscriptionManager mSubscriptionManager;

    // The current subscription ids
    // Ensure this value is never accessed concurrently
    private Set<Integer> mCurrentSubscriptions;
    // We keep track of the last value to avoid updating when unrelated user restrictions change
    // Ensure this value is never accessed concurrently
    private boolean mDisallowCellular2gRestriction;

    public Telephony2gUpdater(Context context) {
        this(Executors.newSingleThreadExecutor(), context,
                RadioAccessFamily.getRafFromNetworkType(RILConstants.PREFERRED_NETWORK_MODE));
    }

    @VisibleForTesting
    public Telephony2gUpdater(Executor executor, Context context, long baseAllowedNetworks) {
        mExecutor = executor;
        mContext = context;
        mBaseAllowedNetworks = baseAllowedNetworks;

        mUserManager = mContext.getSystemService(UserManager.class);
        mTelephonyManager = mContext.getSystemService(TelephonyManager.class);
        mSubscriptionManager = mContext.getSystemService(SubscriptionManager.class);

        // All user restrictions are false by default
        mDisallowCellular2gRestriction = false;
        mCurrentSubscriptions = new HashSet<>();
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
            boolean disallow2g = mUserManager.hasUserRestriction(UserManager.DISALLOW_CELLULAR_2G);
            if (mDisallowCellular2gRestriction == disallow2g) {
                Log.i(TAG, "No update to DISALLOW_CELLULAR_2G restriction.");
                return;
            }

            mDisallowCellular2gRestriction = disallow2g;

            Log.i(TAG, "Running handler for all subscriptions based on DISALLOW_CELLULAR_2G change."
                    + " Restriction value: " + mDisallowCellular2gRestriction);
            handleUserRestrictionsChanged(mCurrentSubscriptions);
            if (result != null) {
                result.finish();
            }
        });
    }

    /**
     * Update subscriptions with allowed network types depending on the current state
     * of the {@link UserManager#DISALLOW_CELLULAR_2G}.
     *
     * @param subIds A list of subIds to update.
     */
    private void handleUserRestrictionsChanged(Collection<Integer> subIds) {
        final long twoGBitmask = TelephonyManager.NETWORK_CLASS_BITMASK_2G;

        long allowedNetworkTypes = mBaseAllowedNetworks;

        // 2G device admin controls are global
        for (Integer subId : subIds) {
            TelephonyManager telephonyManager = mTelephonyManager.createForSubscriptionId(subId);
            if (mDisallowCellular2gRestriction) {
                Log.i(TAG, "Disabling 2g based on user restriction for subId: " + subId);
                allowedNetworkTypes &= ~twoGBitmask;
            } else {
                Log.i(TAG, "Enabling 2g based on user restriction for subId: " + subId);
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
            // Note that this entire callback gets invoked in the single threaded executor
            List<SubscriptionInfo> allSubscriptions =
                    mSubscriptionManager.getCompleteActiveSubscriptionInfoList();

            HashSet<Integer> updatedSubIds = new HashSet<>(allSubscriptions.size());
            List<Integer> newSubIds = new ArrayList<>();

            for (SubscriptionInfo info : allSubscriptions) {
                updatedSubIds.add(info.getSubscriptionId());
                if (!mCurrentSubscriptions.contains(info.getSubscriptionId())) {
                    newSubIds.add(info.getSubscriptionId());
                }
            }

            mCurrentSubscriptions = updatedSubIds;

            if (newSubIds.isEmpty()) {
                Log.d(TAG, "No new subIds. Skipping update.");
                return;
            }

            Log.i(TAG, "New subscriptions found. Running handler to update 2g restrictions with "
                    + "subIds " + newSubIds.toString());
            handleUserRestrictionsChanged(newSubIds);
        }
    }

}
