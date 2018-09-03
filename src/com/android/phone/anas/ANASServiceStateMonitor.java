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

import android.annotation.IntDef;
import android.content.Context;
import android.net.ConnectivityManager;
import android.telephony.PhoneStateListener;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * ANASServiceStateMonitor class which will monitor service state of a given subscription.
 */
public class ANASServiceStateMonitor {
    @VisibleForTesting
    protected Context mContext;

    @VisibleForTesting
    protected TelephonyManager mTelephonyManager;

    @VisibleForTesting
    protected ConnectivityManager mConnectivityManager;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"EVALUATED_STATE_"},
            value = {
                    EVALUATED_STATE_UNKNOWN,
                    EVALUATED_STATE_NO_SERVICE,
                    EVALUATED_STATE_BAD,
                    EVALUATED_STATE_GOOD})
    public @interface EvaluatedState {}

    /* service states to be used while reporting onServiceMonitorUpdate */
    public static final int EVALUATED_STATE_UNKNOWN = 0;

    /* network is not available */
    public static final int EVALUATED_STATE_NO_SERVICE = 1;

    /* network is available but not good */
    public static final int EVALUATED_STATE_BAD = 2;

    /* network is available and good */
    public static final int EVALUATED_STATE_GOOD = 3;

    private static final String LOG_TAG = "ANASServiceStateMonitor";
    private static final boolean DBG = true;
    private ANASServiceMonitorCallback mServiceMonitorCallback;
    private PhoneStateListener mPhoneStateListener;
    private int mSubId;
    private @EvaluatedState int mSignalStrengthEvaluatedState;
    private @EvaluatedState int mServiceStateEvaluatedState;
    private final Object mLock = new Object();

    protected void init(Context c, ANASServiceMonitorCallback serviceMonitorCallback) {
        mContext = c;
        mTelephonyManager = TelephonyManager.from(mContext);
        mConnectivityManager = (ConnectivityManager) mContext.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        mSignalStrengthEvaluatedState = EVALUATED_STATE_UNKNOWN;
        mServiceStateEvaluatedState = EVALUATED_STATE_UNKNOWN;
        mServiceMonitorCallback = serviceMonitorCallback;
        logDebug("[ANASServiceStateMonitor] init by Context");
    }

    /**
     * get the string name of a state
     * @param state service state
     * @return string name of a state
     */
    public static String getStateString(@EvaluatedState int state) {
        switch (state) {
            case EVALUATED_STATE_NO_SERVICE:
                return "No Service";
            case EVALUATED_STATE_BAD:
                return "Bad Service";
            case EVALUATED_STATE_GOOD:
                return "Good Service";
            default:
                return "Unknown";
        }
    }

    /**
     * returns whether the fail reason is permanent
     * @param failCause fail reason
     * @return true if reason is permanent
     */
    @VisibleForTesting
    public static boolean isFatalFailCause(String failCause) {
        if (failCause == null || failCause.isEmpty()) {
            return false;
        }

        switch (failCause) {
            case "OPERATOR_BARRED":
            case "USER_AUTHENTICATION":
            case "ACTIVATION_REJECT_GGSN":
            case "SERVICE_OPTION_NOT_SUPPORTED":
            case "SERVICE_OPTION_NOT_SUBSCRIBED":
            case "SERVICE_OPTION_OUT_OF_ORDER":
            case "PROTOCOL_ERRORS":
                return true;
            default:
                return false;
        }
    }

    private void updateCallbackOnFinalState() {
        int evaluatedState = EVALUATED_STATE_UNKNOWN;

        logDebug("mServiceStateEvaluatedState: " + getStateString(mServiceStateEvaluatedState)
                + " mSignalStrengthEvaluatedState: "
                + getStateString(mSignalStrengthEvaluatedState));

        /* Service state has highest priority in this validation. If no service, no need to
           check further. */
        if (mServiceStateEvaluatedState == EVALUATED_STATE_GOOD) {
            evaluatedState = EVALUATED_STATE_GOOD;
        } else if (mServiceStateEvaluatedState == EVALUATED_STATE_NO_SERVICE) {
            evaluatedState = EVALUATED_STATE_NO_SERVICE;
            mServiceMonitorCallback.onServiceMonitorUpdate(mSubId, EVALUATED_STATE_NO_SERVICE);
            return;
        }

        /* use signal strength to determine service quality only, i.e is good or bad. */
        if (evaluatedState == EVALUATED_STATE_GOOD) {
            if (mSignalStrengthEvaluatedState == EVALUATED_STATE_BAD) {
                evaluatedState = EVALUATED_STATE_BAD;
            }
        }

        if (evaluatedState != EVALUATED_STATE_UNKNOWN) {
            mServiceMonitorCallback.onServiceMonitorUpdate(mSubId, evaluatedState);
        }
    }

    private void analyzeSignalStrengthChange(SignalStrength signalStrength) {
        if (mServiceMonitorCallback == null) {
            return;
        }

        if (signalStrength.getLevel() <= SignalStrength.SIGNAL_STRENGTH_POOR) {
            mSignalStrengthEvaluatedState = EVALUATED_STATE_BAD;
        } else {
            mSignalStrengthEvaluatedState = EVALUATED_STATE_GOOD;
        }

        updateCallbackOnFinalState();
    }

    private void analyzeServiceStateChange(ServiceState serviceState) {
        logDebug("analyzeServiceStateChange state:"
                + serviceState.getDataRegState());
        if (mServiceMonitorCallback == null) {
            return;
        }

        if ((serviceState.getDataRegState() == ServiceState.STATE_OUT_OF_SERVICE)
                || (serviceState.getState() == ServiceState.STATE_EMERGENCY_ONLY)) {
            mServiceStateEvaluatedState = EVALUATED_STATE_NO_SERVICE;
        } else if (serviceState.getDataRegState() == ServiceState.STATE_IN_SERVICE) {
            mServiceStateEvaluatedState = EVALUATED_STATE_GOOD;
        }

        updateCallbackOnFinalState();
    }

    /**
     * Implements phone state listener
     */
    @VisibleForTesting
    public class PhoneStateListenerImpl extends PhoneStateListener {
        PhoneStateListenerImpl(int subId) {
            super(subId);
        }

        private boolean shouldIgnore() {
            if (PhoneStateListenerImpl.this.mSubId != ANASServiceStateMonitor.this.mSubId) {
                return true;
            }

            return false;
        }

        @Override
        public void onSignalStrengthsChanged(SignalStrength signalStrength) {
            synchronized (mLock) {
                if (shouldIgnore()) {
                    return;
                }

                analyzeSignalStrengthChange(signalStrength);
            }
        }

        @Override
        public void onServiceStateChanged(ServiceState serviceState) {
            synchronized (mLock) {
                if (shouldIgnore()) {
                    return;
                }

                analyzeServiceStateChange(serviceState);
            }
        }
    };

    /**
     * get phone state listener instance
     * @param subId subscription id
     * @return the listener instance
     */
    @VisibleForTesting
    public PhoneStateListener getPhoneStateListener(int subId) {
        synchronized (mLock) {
            if (mPhoneStateListener != null && subId == mSubId) {
                return mPhoneStateListener;
            }

            mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_NONE);
            mSubId = subId;
            mPhoneStateListener = (PhoneStateListener) new PhoneStateListenerImpl(subId);
        }
        return mPhoneStateListener;
    }

    /**
     * call back interface
     */
    public interface ANASServiceMonitorCallback {
        /**
         * call back interface
         */
        void onServiceMonitorUpdate(int subId, @EvaluatedState int state);
    }

    /**
     * request to start listening for network changes.
     */
    public void startListeningForNetworkConditionChange(int subId) {

        logDebug("start network condition listen for " + subId);
        /* monitor service state, signal strength and data connection state */
        synchronized (mLock) {
            int events = PhoneStateListener.LISTEN_SERVICE_STATE
                    | PhoneStateListener.LISTEN_SIGNAL_STRENGTH;
            mTelephonyManager.listen(getPhoneStateListener(subId), events);
        }
    }

    /**
     * request to stop listening for network changes.
     */
    public void stopListeningForNetworkConditionChange() {
        logDebug("stop network condition listen for " + mSubId);
        synchronized (mLock) {
            if (mPhoneStateListener != null) {
                mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_NONE);
            }
            mSignalStrengthEvaluatedState = EVALUATED_STATE_UNKNOWN;
            mServiceStateEvaluatedState = EVALUATED_STATE_UNKNOWN;
        }
    }

    public ANASServiceStateMonitor(Context c, ANASServiceMonitorCallback serviceMonitorCallback) {
        init(c, serviceMonitorCallback);
    }

    private static void log(String msg) {
        Rlog.d(LOG_TAG, msg);
    }

    private static void logDebug(String msg) {
        if (DBG) {
            Rlog.d(LOG_TAG, msg);
        }
    }
}
