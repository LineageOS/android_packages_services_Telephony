/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.phone.anas;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.ServiceManager;
import android.telephony.Rlog;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.IAnas;
import com.android.internal.telephony.TelephonyPermissions;

/**
 * AlternativeNetworkAccessService implements ianas.
 * It scans network and matches the results with opportunistic subscriptions.
 * Use the same to provide user opportunistic data in areas with corresponding networks
 */
public class AlternativeNetworkAccessService extends IAnas.Stub {
    private Context mContext;
    private TelephonyManager mTelephonyManager;
    private SubscriptionManager mSubsriptionManager;

    private final Object mLock = new Object();
    private boolean mIsEnabled;
    private ANASProfileSelector mProfileSelector;
    private ANASServiceStateEvaluator mServiceStateEvaluator;
    private SharedPreferences mSharedPref;

    /** The singleton instance. */
    private static AlternativeNetworkAccessService sInstance = null;
    private static final String TAG = "ANAS";
    private static final String PREF_NAME = TAG;
    private static final String PREF_ENABLED = "isEnabled";
    private static final boolean DBG = true;

    /**
     * Profile selection callback. Will be called once Profile selector decides on
     * the opportunistic data profile.
     */
    private ANASProfileSelector.ANASProfileSelectionCallback  mProfileSelectionCallback =
            new ANASProfileSelector.ANASProfileSelectionCallback() {

                @Override
                public void onProfileSelectionDone(int dataSubId, int voiceSubId) {
                    logDebug("profile selection done");
                    mProfileSelector.stopProfileSelection();
                    mServiceStateEvaluator.startEvaluation(dataSubId, voiceSubId);
                }
            };

    /**
     * Service state evaluator callback. Will be called once service state evaluator thinks
     * that current opportunistic data is not providing good service.
     */
    private ANASServiceStateEvaluator.ANASServiceEvaluatorCallback mServiceEvaluatorCallback =
            new ANASServiceStateEvaluator.ANASServiceEvaluatorCallback() {
                @Override
                public void onBadDataService() {
                    logDebug("Bad opportunistic data service");
                    mServiceStateEvaluator.stopEvaluation();
                    mProfileSelector.selectPrimaryProfileForData();
                    mProfileSelector.startProfileSelection();
                }
            };

    /**
     * create AlternativeNetworkAccessService instance
     *
     * @param c context
     *
     */
    public static void initInstance(Context c) {
        if (sInstance == null) {
            sInstance = new AlternativeNetworkAccessService(c);
        }
        return;
    }

    /**
     * get AlternativeNetworkAccessService instance
     *
     */
    @VisibleForTesting
    public static AlternativeNetworkAccessService getInstance() {
        if (sInstance == null) {
            Log.wtf(TAG, "getInstance null");
        }
        return sInstance;
    }

    /**
     * Enable or disable Alternative Network Access service.
     *
     * This method should be called to enable or disable
     * AlternativeNetworkAccess service on the device.
     *
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     * Or the calling app has carrier privileges. @see #hasCarrierPrivileges
     *
     * @param enable enable(True) or disable(False)
     * @param callingPackage caller's package name
     * @return returns true if successfully set.
     */
    @Override
    public boolean setEnable(boolean enable, String callingPackage) {
        TelephonyPermissions.enforceCallingOrSelfModifyPermissionOrCarrierPrivilege(
                mContext, mSubsriptionManager.getDefaultSubscriptionId(), "setEnable");
        log("setEnable: " + enable);

        final long identity = Binder.clearCallingIdentity();
        try {
            enableAlternativeNetworkAccess(enable);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }

        return true;
    }

    /**
     * is Alternative Network Access service enabled
     *
     * This method should be called to determine if the Alternative Network Access service
     * is enabled
     *
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * Or the calling app has carrier privileges. @see #hasCarrierPrivileges
     *
     * @param callingPackage caller's package name
     */
    @Override
    public boolean isEnabled(String callingPackage) {
        TelephonyPermissions.enforeceCallingOrSelfReadPhoneStatePermissionOrCarrierPrivilege(
                mContext, mSubsriptionManager.getDefaultSubscriptionId(), "isEnabled");
        return mIsEnabled;
    }

    /**
     * initialize ANAS and register as service.
     * Read persistent state to update enable state
     * Start sub components if already enabled.
     * @param context context instance
     */
    private void initializeAndRegisterAsService(Context context) {
        mContext = context;
        mTelephonyManager = TelephonyManager.from(mContext);
        mServiceStateEvaluator = new ANASServiceStateEvaluator(mContext, mServiceEvaluatorCallback);
        mProfileSelector = new ANASProfileSelector(mContext, mProfileSelectionCallback);
        mSharedPref = mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        mSubsriptionManager = (SubscriptionManager) mContext.getSystemService(
                Context.TELEPHONY_SUBSCRIPTION_SERVICE);

        /* register the service */
        if (ServiceManager.getService("ianas") == null) {
            ServiceManager.addService("ianas", this);
        }

        enableAlternativeNetworkAccess(getPersistentEnableState());
    }

    private AlternativeNetworkAccessService(Context c) {
        initializeAndRegisterAsService(c);
        log("init completed");
    }

    private boolean getPersistentEnableState() {
        return mSharedPref.getBoolean(PREF_ENABLED, true);
    }

    private void updateEnableState(boolean enable) {
        mIsEnabled = enable;
        mSharedPref.edit().putBoolean(PREF_ENABLED, mIsEnabled).apply();
    }

    /**
     * update the enable state
     * start profile selection if enabled.
     * @param enable enable(true) or disable(false)
     */
    private void enableAlternativeNetworkAccess(boolean enable) {
        synchronized (mLock) {
            if (mIsEnabled != enable) {
                updateEnableState(enable);
                if (mIsEnabled) {
                    mProfileSelector.startProfileSelection();
                } else {
                    mProfileSelector.stopProfileSelection();
                }
            }
        }
        logDebug("service is enable state " + mIsEnabled);
    }

    private void log(String msg) {
        Rlog.d(TAG, msg);
    }

    private void logDebug(String msg) {
        if (DBG) Rlog.d(TAG, msg);
    }
}
