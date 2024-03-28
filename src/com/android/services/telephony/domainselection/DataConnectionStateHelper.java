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

package com.android.services.telephony.domainselection;

import static android.telephony.SubscriptionManager.EXTRA_SLOT_INDEX;
import static android.telephony.SubscriptionManager.INVALID_SIM_SLOT_INDEX;
import static android.telephony.TelephonyManager.ACTION_EMERGENCY_CALLBACK_MODE_CHANGED;
import static android.telephony.TelephonyManager.EXTRA_PHONE_IN_ECM_STATE;

import android.annotation.NonNull;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemProperties;
import android.telephony.AccessNetworkConstants;
import android.telephony.CarrierConfigManager;
import android.telephony.PreciseDataConnectionState;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.telephony.data.ApnSetting;
import android.util.ArrayMap;
import android.util.Log;

/** Helper class to cache emergency data connection state. */
public class DataConnectionStateHelper extends Handler {
    private static final String TAG = "DataConnectionStateHelper";
    private static final boolean DBG = (SystemProperties.getInt("ro.debuggable", 0) == 1);

    /**
     * TelephonyCallback used to monitor ePDN state.
     */
    private static final class DataConnectionStateListener extends TelephonyCallback
            implements TelephonyCallback.PreciseDataConnectionStateListener {

        private final Handler mHandler;
        private final TelephonyManager mTelephonyManager;
        private final DataConnectionStateHelper mOwner;
        private final int mSubId;
        private final int mSlotIndex;
        private int mTransportType = AccessNetworkConstants.TRANSPORT_TYPE_INVALID;
        private int mState = TelephonyManager.DATA_UNKNOWN;

        DataConnectionStateListener(Handler handler, TelephonyManager tm,
                DataConnectionStateHelper owner, int subId, int slotIndex) {
            mHandler = handler;
            mTelephonyManager = tm;
            mOwner = owner;
            mSubId = subId;
            mSlotIndex = slotIndex;
        }

        @Override
        public void onPreciseDataConnectionStateChanged(
                @NonNull PreciseDataConnectionState dataConnectionState) {
            ApnSetting apnSetting = dataConnectionState.getApnSetting();
            if ((apnSetting == null)
                    || ((apnSetting.getApnTypeBitmask() & ApnSetting.TYPE_EMERGENCY) == 0)) {
                return;
            }
            mTransportType = dataConnectionState.getTransportType();
            mState = dataConnectionState.getState();
            mOwner.notifyDataConnectionStateChange(mSlotIndex, mState);
            Log.i(TAG, "onPreciseDataConnectionStateChanged ePDN state=" + mState
                    + ", transport=" + mTransportType + ", subId=" + mSubId);
        }

        public void registerTelephonyCallback() {
            Log.i(TAG, "registerTelephonyCallback subId=" + mSubId);
            TelephonyManager tm = mTelephonyManager.createForSubscriptionId(mSubId);
            tm.registerTelephonyCallback(mHandler::post, this);
        }

        public void unregisterTelephonyCallback() {
            Log.i(TAG, "unregisterTelephonyCallback subId=" + mSubId);
            mTelephonyManager.unregisterTelephonyCallback(this);
        }

        public int getSubId() {
            return mSubId;
        }

        public int getTransportType() {
            return mTransportType;
        }

        public int getState() {
            return mState;
        }
    }

    private final Context mContext;
    private final TelephonyManager mTelephonyManager;
    private final CarrierConfigManager mConfigManager;

    private final ArrayMap<Integer, DataConnectionStateListener>
            mDataConnectionStateListeners = new ArrayMap<>();

    private final CarrierConfigManager.CarrierConfigChangeListener mCarrierConfigChangeListener =
            (slotIndex, subId, carrierId, specificCarrierId) -> onCarrierConfigChanged(
                    slotIndex, subId, carrierId);

    private EmergencyCallDomainSelector mSelector;

    /**
     * Creates an instance.
     *
     * @param context The Context this is associated with.
     * @param looper The Looper to run the DataConnectionStateHelper.
     */
    public DataConnectionStateHelper(@NonNull Context context, @NonNull Looper looper) {
        super(looper);

        mContext = context;
        mTelephonyManager = context.getSystemService(TelephonyManager.class);
        mConfigManager = context.getSystemService(CarrierConfigManager.class);
        mConfigManager.registerCarrierConfigChangeListener(this::post,
                mCarrierConfigChangeListener);
    }

    /**
     * Returns whether it is in emergency callback mode.
     *
     * @param slotIndex The logical SIM slot index.
     * @return true if it is in emergency callback mode.
     */
    public boolean isInEmergencyCallbackMode(int slotIndex) {
        Intent intent = mContext.registerReceiver(null,
                new IntentFilter(ACTION_EMERGENCY_CALLBACK_MODE_CHANGED));
        if (intent != null
                && ACTION_EMERGENCY_CALLBACK_MODE_CHANGED.equals(intent.getAction())) {
            boolean inEcm = intent.getBooleanExtra(EXTRA_PHONE_IN_ECM_STATE, false);
            int index = intent.getIntExtra(EXTRA_SLOT_INDEX, INVALID_SIM_SLOT_INDEX);
            Log.i(TAG, "isInEmergencyCallbackMode inEcm=" + inEcm + ", slotIndex=" + index);
            return inEcm && (slotIndex == index);
        }
        return false;
    }

    /**
     * Returns the transport type of emergency data connection.
     *
     * @param slotIndex The logical SIM slot index.
     * @return the transport type of emergency data connection.
     */
    public int getTransportType(int slotIndex) {
        DataConnectionStateListener listener =
                mDataConnectionStateListeners.get(Integer.valueOf(slotIndex));
        if (listener == null) return AccessNetworkConstants.TRANSPORT_TYPE_INVALID;
        Log.i(TAG, "getTransportType " + listener.getTransportType());
        return listener.getTransportType();
    }

    /**
     * Returns the data connection state.
     *
     * @param slotIndex The logical SIM slot index.
     * @return the data connection state.
     */
    public int getDataConnectionState(int slotIndex) {
        DataConnectionStateListener listener =
                mDataConnectionStateListeners.get(Integer.valueOf(slotIndex));
        if (listener == null) return TelephonyManager.DATA_UNKNOWN;
        Log.i(TAG, "getDataConnectionState " + listener.getState());
        return listener.getState();
    }

    /**
     * Sets the EmergencyCallDomainSelector instance.
     *
     * @param selector The instance of {@link EmergencyCallDomainSelector}.
     */
    public void setEmergencyCallDomainSelector(EmergencyCallDomainSelector selector) {
        mSelector = selector;
    }

    private void notifyDataConnectionStateChange(int slotIndex, int state) {
        EmergencyCallDomainSelector selector = mSelector;
        if (selector != null) {
            Log.i(TAG, "notifyDataConnectionStateChange slot=" + slotIndex + ", state=" + state);
            selector.notifyDataConnectionStateChange(slotIndex, state);
        }
    }


    @Override
    public void handleMessage(Message msg) {
        switch(msg.what) {
            default:
                super.handleMessage(msg);
                break;
        }
    }

    private void onCarrierConfigChanged(int slotIndex, int subId, int carrierId) {
        Log.i(TAG, "onCarrierConfigChanged slotIndex=" + slotIndex
                + ", subId=" + subId + ", carrierId=" + carrierId);

        if (slotIndex < 0) {
            return;
        }

        DataConnectionStateListener listener =
                mDataConnectionStateListeners.get(Integer.valueOf(slotIndex));

        // Remove stale listener.
        if (listener != null && listener.getSubId() != subId) {
            listener.unregisterTelephonyCallback();
            mDataConnectionStateListeners.remove(Integer.valueOf(slotIndex));
            listener = null;
        }

        if (listener == null
                && SubscriptionManager.isValidSubscriptionId(subId)) {
            listener = new DataConnectionStateListener(this, mTelephonyManager,
                    this, subId, slotIndex);
            listener.registerTelephonyCallback();
            mDataConnectionStateListeners.put(Integer.valueOf(slotIndex), listener);
        }
    }

    /** Destroys the instance. */
    public void destroy() {
        if (DBG) Log.d(TAG, "destroy");
        mConfigManager.unregisterCarrierConfigChangeListener(mCarrierConfigChangeListener);
        mDataConnectionStateListeners.forEach((k, v) -> v.unregisterTelephonyCallback());
    }
}
