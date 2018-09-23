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

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Message;
import android.telephony.CellInfo;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoWcdma;
import android.telephony.Rlog;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Profile selector class which will select the right profile based upon
 * geographic information input and network scan results.
 */
public class ANASProfileSelector {
    private static final String LOG_TAG = "ANASProfileSelector";
    private static final boolean DBG = true;
    private final Object mLock = new Object();

    private static final int INVALID_SEQUENCE_ID = -1;
    private static final int START_SEQUENCE_ID = 1;

    /* message to indicate profile update */
    private static final int MSG_PROFILE_UPDATE = 1;

    /* message to indicate start of profile selection process */
    private static final int MSG_START_PROFILE_SELECTION = 2;
    private boolean mIsEnabled = false;

    @VisibleForTesting
    protected Context mContext;

    @VisibleForTesting
    protected TelephonyManager mTelephonyManager;

    @VisibleForTesting
    protected ANASNetworkScanCtlr mNetworkScanCtlr;

    private SubscriptionManager mSubscriptionManager;
    private ANASProfileSelectionCallback mProfileSelectionCallback;
    private int mSequenceId;

    /* monitor the subscription for registration */
    private ANASServiceStateMonitor mRegMonitor;
    public static final String ACTION_SUB_SWITCH =
            "android.intent.action.SUBSCRIPTION_SWITCH_REPLY";

    /* service monitor callback will get called for service state change on a particular subId. */
    private ANASServiceStateMonitor.ANASServiceMonitorCallback mServiceMonitorCallback =
            new ANASServiceStateMonitor.ANASServiceMonitorCallback() {
                @Override
                public void onServiceMonitorUpdate(int subId, int state) {
                    switch (state) {
                        case ANASServiceStateMonitor.EVALUATED_STATE_BAD:
                            switchPreferredData(subId);
                            break;
                        default:
                            break;
                    }
                }
            };

    private SubscriptionManager.OnOpportunisticSubscriptionsChangedListener mProfileChangeListener =
            new SubscriptionManager.OnOpportunisticSubscriptionsChangedListener() {
                @Override
                public void onOpportunisticSubscriptionsChanged() {
                    mHandler.sendEmptyMessage(MSG_PROFILE_UPDATE);
                }
            };

    @VisibleForTesting
    protected Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_PROFILE_UPDATE:
                case MSG_START_PROFILE_SELECTION:
                    logDebug("Msg received for profile update");
                    checkProfileUpdate();
                    break;
                default:
                    log("invalid message");
                    break;
            }
        }
    };

    /**
     * Broadcast receiver to receive intents
     */
    @VisibleForTesting
    protected final BroadcastReceiver mProfileSelectorBroadcastReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    int sequenceId;
                    int subId;
                    String action = intent.getAction();
                    if (!mIsEnabled || action == null) {
                        return;
                    }

                    switch (action) {
                        case ACTION_SUB_SWITCH:
                            sequenceId = intent.getIntExtra("sequenceId",  INVALID_SEQUENCE_ID);
                            subId = intent.getIntExtra("subId",
                                    SubscriptionManager.INVALID_SUBSCRIPTION_ID);
                            if (sequenceId != mSequenceId) {
                                return;
                            }

                            onSubSwitchComplete(subId);
                            break;
                    }
                }
            };

    /**
     * Network scan callback handler
     */
    @VisibleForTesting
    protected ANASNetworkScanCtlr.NetworkAvailableCallBack mNetworkAvailableCallBack =
            new ANASNetworkScanCtlr.NetworkAvailableCallBack() {
                @Override
                public void onNetworkAvailability(List<CellInfo> results) {
                    /* sort the results according to signal strength level */
                    Collections.sort(results, new Comparator<CellInfo>() {
                        @Override
                        public int compare(CellInfo cellInfo1, CellInfo cellInfo2) {
                            return getSignalLevel(cellInfo1) - getSignalLevel(cellInfo2);
                        }
                    });

                    /* get subscription id for the best network scan result */
                    int subId = getSubId(getMcc(results.get(0)), getMnc(results.get(0)));
                    if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                        /* could not find any matching subscriptions */
                        return;
                    }

                    /* if subscription is already active, proceed to data switch */
                    if (mSubscriptionManager.isActiveSubId(subId)) {
                        /* if subscription already is data subscription,
                         complete the profile selection process */
                        /* Todo: change to getPreferredDataSubscriptionId once ready */
                        if (mSubscriptionManager.getDefaultDataSubscriptionId() == subId) {
                            mProfileSelectionCallback.onProfileSelectionDone(subId,
                                    mSubscriptionManager.getDefaultSubscriptionId());
                        } else {
                            switchPreferredData(subId);
                        }
                    } else {
                        switchToSubscription(subId);
                    }
                }

                @Override
                public void onError(int error) {
                    log("Network scan failed with error " + error);
                }
            };

    /**
     * interface call back to confirm profile selection
     */
    public interface ANASProfileSelectionCallback {

        /**
         * interface call back to confirm profile selection
         */
        void onProfileSelectionDone(int dataSubId, int voiceSubId);
    }

    /**
     * ANASProfileSelector constructor
     * @param c context
     * @param profileSelectionCallback callback to be called once selection is done
     */
    public ANASProfileSelector(Context c, ANASProfileSelectionCallback profileSelectionCallback) {
        init(c, profileSelectionCallback);
        log("ANASProfileSelector init complete");
    }

    private int getSignalLevel(CellInfo cellInfo) {
        if (cellInfo != null) {
            return cellInfo.getCellSignalStrength().getLevel();
        } else {
            return SignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
        }
    }

    private String getMcc(CellInfo cellInfo) {
        String mcc = "";
        if (cellInfo instanceof CellInfoGsm) {
            mcc = ((CellInfoGsm) cellInfo).getCellIdentity().getMccString();
        } else if (cellInfo instanceof CellInfoLte) {
            mcc = ((CellInfoLte) cellInfo).getCellIdentity().getMccString();
        } else if (cellInfo instanceof CellInfoWcdma) {
            mcc = ((CellInfoWcdma) cellInfo).getCellIdentity().getMccString();
        }

        return mcc;
    }

    private String getMnc(CellInfo cellInfo) {
        String mnc = "";
        if (cellInfo instanceof CellInfoGsm) {
            mnc = ((CellInfoGsm) cellInfo).getCellIdentity().getMncString();
        } else if (cellInfo instanceof CellInfoLte) {
            mnc = ((CellInfoLte) cellInfo).getCellIdentity().getMncString();
        } else if (cellInfo instanceof CellInfoWcdma) {
            mnc = ((CellInfoWcdma) cellInfo).getCellIdentity().getMncString();
        }

        return mnc;
    }

    private int getSubId(String mcc, String mnc) {
        List<SubscriptionInfo> subscriptionInfos =
                mSubscriptionManager.getOpportunisticSubscriptions(1);
        for (SubscriptionInfo subscriptionInfo : subscriptionInfos) {
            if (TextUtils.equals(subscriptionInfo.getMccString(), mcc)
                    && TextUtils.equals(subscriptionInfo.getMncString(), mnc)) {
                return subscriptionInfo.getSubscriptionId();
            }
        }

        return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    }

    private void switchToSubscription(int subId) {
        Intent callbackIntent = new Intent(ACTION_SUB_SWITCH);
        callbackIntent.setClass(mContext, ANASProfileSelector.class);
        callbackIntent.putExtra("sequenceId", getAndUpdateToken());
        callbackIntent.putExtra("subId", subId);

        PendingIntent replyIntent = PendingIntent.getService(mContext,
                1, callbackIntent,
                Intent.FILL_IN_ACTION);
        mSubscriptionManager.switchToSubscription(subId, replyIntent);
    }

    private void switchPreferredData(int subId) {
        mSubscriptionManager.setPreferredData(mSubscriptionManager.getSlotIndex(subId));
        onDataSwitchComplete(subId);
    }

    private void onSubSwitchComplete(int subId) {
        mRegMonitor.startListeningForNetworkConditionChange(subId);
    }

    private void onDataSwitchComplete(int subId) {
        mProfileSelectionCallback.onProfileSelectionDone(subId,
                mSubscriptionManager.getDefaultSubscriptionId());
    }

    private int getAndUpdateToken() {
        synchronized (mLock) {
            return mSequenceId++;
        }
    }

    private void checkProfileUpdate() {
        List<SubscriptionInfo> subscriptionInfos =
                mSubscriptionManager.getOpportunisticSubscriptions(1);
        if (subscriptionInfos == null) {
            logDebug("received null subscription infos");
            return;
        }

        if (subscriptionInfos.size() > 0) {
            logDebug("opportunistic subscriptions size " + subscriptionInfos.size());

            /* start scan immediately */
            mNetworkScanCtlr.startFastNetworkScan(subscriptionInfos);
        } else if (subscriptionInfos.size() == 0) {
            /* check if no profile */
            log("checkProfileUpdate 0 out");
            mNetworkScanCtlr.stopNetworkScan();
        }
    }

    /**
     * start profile selection procedure
     */
    public void startProfileSelection() {
        synchronized (mLock) {
            if (!mIsEnabled) {
                mIsEnabled = true;
                mHandler.sendEmptyMessage(MSG_START_PROFILE_SELECTION);
            }
        }
    }

    /**
     * select primary profile for data
     */
    public void selectPrimaryProfileForData() {
        mSubscriptionManager.setPreferredData(mSubscriptionManager.getDefaultSubscriptionId());
    }

    /**
     * stop profile selection procedure
     */
    public void stopProfileSelection() {
        mNetworkScanCtlr.stopNetworkScan();
        synchronized (mLock) {
            mIsEnabled = false;
        }
    }

    protected void init(Context c, ANASProfileSelectionCallback profileSelectionCallback) {
        mContext = c;
        mNetworkScanCtlr = new ANASNetworkScanCtlr(mContext, mTelephonyManager,
                mNetworkAvailableCallBack);
        mSequenceId = START_SEQUENCE_ID;
        mProfileSelectionCallback = profileSelectionCallback;
        mTelephonyManager = (TelephonyManager)
                mContext.getSystemService(Context.TELEPHONY_SERVICE);
        mSubscriptionManager = (SubscriptionManager)
                mContext.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        mRegMonitor = new ANASServiceStateMonitor(mContext, mServiceMonitorCallback);

        /* register for profile update events */
        mSubscriptionManager.addOnOpportunisticSubscriptionsChangedListener(
                AsyncTask.SERIAL_EXECUTOR, mProfileChangeListener);

        /* register for subscription switch intent */
        mContext.registerReceiver(mProfileSelectorBroadcastReceiver,
                new IntentFilter(ACTION_SUB_SWITCH));
    }

    private void log(String msg) {
        Rlog.d(LOG_TAG, msg);
    }

    private void logDebug(String msg) {
        if (DBG) {
            Rlog.d(LOG_TAG, msg);
        }
    }
}
