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

import static android.telephony.AccessNetworkConstants.TRANSPORT_TYPE_WWAN;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.assertNotNull;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.LinkProperties;
import android.os.HandlerThread;
import android.os.Looper;
import android.telephony.CarrierConfigManager;
import android.telephony.PreciseDataConnectionState;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.telephony.data.ApnSetting;
import android.testing.TestableLooper;
import android.util.Log;

import com.android.TestContext;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.Executor;

/**
 * Unit tests for DataConnectionStateHelper
 */
public class DataConnectionStateHelperTest {
    private static final String TAG = "DataConnectionStateHelperTest";

    private static final int SLOT_0 = 0;
    private static final int SLOT_1 = 1;
    private static final int SUB_1 = 1;
    private static final int SUB_2 = 2;

    @Mock private TelephonyManager mTm1;
    @Mock private TelephonyManager mTm2;
    @Mock private EmergencyCallDomainSelector mDomainSelector;

    private Context mContext;
    private HandlerThread mHandlerThread;
    private TestableLooper mLooper;
    private DataConnectionStateHelper mEpdnHelper;
    private CarrierConfigManager mCarrierConfigManager;
    private TelephonyManager mTelephonyManager;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mContext = new TestContext() {
            private Intent mIntent;

            @Override
            public String getSystemServiceName(Class<?> serviceClass) {
                if (serviceClass == TelephonyManager.class) {
                    return Context.TELEPHONY_SERVICE;
                } else if (serviceClass == CarrierConfigManager.class) {
                    return Context.CARRIER_CONFIG_SERVICE;
                }
                return super.getSystemServiceName(serviceClass);
            }

            @Override
            public String getOpPackageName() {
                return "";
            }

            @Override
            public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
                return mIntent;
            }

            @Override
            public void sendStickyBroadcast(Intent intent) {
                mIntent = intent;
            }
        };

        if (Looper.myLooper() == null) {
            Looper.prepare();
        }

        mHandlerThread = new HandlerThread("DataConnectionStateHelperTest");
        mHandlerThread.start();

        try {
            mLooper = new TestableLooper(mHandlerThread.getLooper());
        } catch (Exception e) {
            logd("Unable to create looper from handler.");
        }

        mCarrierConfigManager = mContext.getSystemService(CarrierConfigManager.class);
        mTelephonyManager = mContext.getSystemService(TelephonyManager.class);
        doReturn(mTm1).when(mTelephonyManager).createForSubscriptionId(eq(SUB_1));
        doReturn(mTm2).when(mTelephonyManager).createForSubscriptionId(eq(SUB_2));

        mEpdnHelper = new DataConnectionStateHelper(mContext, mHandlerThread.getLooper());
        mEpdnHelper.setEmergencyCallDomainSelector(mDomainSelector);
    }

    @After
    public void tearDown() throws Exception {
        if (mEpdnHelper != null) {
            mEpdnHelper.destroy();
            mEpdnHelper = null;
        }

        if (mLooper != null) {
            mLooper.destroy();
            mLooper = null;
        }
    }

    @Test
    public void testInit() throws Exception {
        ArgumentCaptor<CarrierConfigManager.CarrierConfigChangeListener> callbackCaptor =
                ArgumentCaptor.forClass(CarrierConfigManager.CarrierConfigChangeListener.class);
        ArgumentCaptor<Executor> executorCaptor = ArgumentCaptor.forClass(Executor.class);

        verify(mCarrierConfigManager).registerCarrierConfigChangeListener(executorCaptor.capture(),
                callbackCaptor.capture());
        assertNotNull(executorCaptor.getValue());
        assertNotNull(callbackCaptor.getValue());
    }

    @Test
    public void testCarrierConfigChanged() throws Exception {
        ArgumentCaptor<CarrierConfigManager.CarrierConfigChangeListener> callbackCaptor =
                ArgumentCaptor.forClass(CarrierConfigManager.CarrierConfigChangeListener.class);

        verify(mCarrierConfigManager).registerCarrierConfigChangeListener(any(),
                callbackCaptor.capture());

        CarrierConfigManager.CarrierConfigChangeListener callback = callbackCaptor.getValue();

        assertNotNull(callback);

        callback.onCarrierConfigChanged(SLOT_0, SUB_1, 0, 0);

        verify(mTelephonyManager).createForSubscriptionId(eq(SUB_1));

        ArgumentCaptor<TelephonyCallback> telephonyCallbackCaptor1 =
                ArgumentCaptor.forClass(TelephonyCallback.class);

        // TelephonyCallback for SUB_1 registered
        verify(mTm1).registerTelephonyCallback(any(), telephonyCallbackCaptor1.capture());

        assertNotNull(telephonyCallbackCaptor1.getValue());

        callback.onCarrierConfigChanged(SLOT_1, SUB_2, 0, 0);

        verify(mTelephonyManager).createForSubscriptionId(eq(SUB_2));

        ArgumentCaptor<TelephonyCallback> telephonyCallbackCaptor2 =
                ArgumentCaptor.forClass(TelephonyCallback.class);

        // TelephonyCallback for SUB_2 registered
        verify(mTm2).registerTelephonyCallback(any(), telephonyCallbackCaptor2.capture());

        assertNotNull(telephonyCallbackCaptor2.getValue());

        verify(mTm1, never()).unregisterTelephonyCallback(any());
        verify(mTm2, never()).unregisterTelephonyCallback(any());
    }

    @Test
    public void testSubscriptionChangedOnTheSameSlot() throws Exception {
        ArgumentCaptor<CarrierConfigManager.CarrierConfigChangeListener> callbackCaptor =
                ArgumentCaptor.forClass(CarrierConfigManager.CarrierConfigChangeListener.class);

        verify(mCarrierConfigManager).registerCarrierConfigChangeListener(any(),
                callbackCaptor.capture());

        CarrierConfigManager.CarrierConfigChangeListener callback = callbackCaptor.getValue();

        assertNotNull(callback);

        callback.onCarrierConfigChanged(SLOT_0, SUB_1, 0, 0);

        verify(mTelephonyManager).createForSubscriptionId(eq(SUB_1));

        ArgumentCaptor<TelephonyCallback> telephonyCallbackCaptor1 =
                ArgumentCaptor.forClass(TelephonyCallback.class);

        // TelephonyCallback for SUB_1 registered
        verify(mTm1).registerTelephonyCallback(any(), telephonyCallbackCaptor1.capture());

        TelephonyCallback telephonyCallback1 = telephonyCallbackCaptor1.getValue();

        assertNotNull(telephonyCallback1);

        // Subscription changed
        callback.onCarrierConfigChanged(SLOT_0, SUB_2, 0, 0);

        // TelephonyCallback for SUB_1 unregistered
        verify(mTelephonyManager).unregisterTelephonyCallback(eq(telephonyCallback1));

        verify(mTelephonyManager).createForSubscriptionId(eq(SUB_2));

        ArgumentCaptor<TelephonyCallback> telephonyCallbackCaptor2 =
                ArgumentCaptor.forClass(TelephonyCallback.class);

        // TelephonyCallback for SUB_2 registered
        verify(mTm2).registerTelephonyCallback(any(), telephonyCallbackCaptor2.capture());

        TelephonyCallback telephonyCallback2 = telephonyCallbackCaptor2.getValue();

        assertNotNull(telephonyCallback2);
    }

    @Test
    public void testDataConnectionStateChanged() throws Exception {
        ArgumentCaptor<CarrierConfigManager.CarrierConfigChangeListener> callbackCaptor =
                ArgumentCaptor.forClass(CarrierConfigManager.CarrierConfigChangeListener.class);

        verify(mCarrierConfigManager).registerCarrierConfigChangeListener(any(),
                callbackCaptor.capture());

        CarrierConfigManager.CarrierConfigChangeListener callback = callbackCaptor.getValue();

        assertNotNull(callback);

        callback.onCarrierConfigChanged(SLOT_0, SUB_1, 0, 0);

        verify(mTelephonyManager).createForSubscriptionId(eq(SUB_1));

        ArgumentCaptor<TelephonyCallback> telephonyCallbackCaptor1 =
                ArgumentCaptor.forClass(TelephonyCallback.class);

        // TelephonyCallback for SUB_1 registered
        verify(mTm1).registerTelephonyCallback(any(), telephonyCallbackCaptor1.capture());

        TelephonyCallback cb1 = telephonyCallbackCaptor1.getValue();

        assertNotNull(cb1);
        assertTrue(cb1 instanceof TelephonyCallback.PreciseDataConnectionStateListener);

        callback.onCarrierConfigChanged(SLOT_1, SUB_2, 0, 0);

        verify(mTelephonyManager).createForSubscriptionId(eq(SUB_2));

        ArgumentCaptor<TelephonyCallback> telephonyCallbackCaptor2 =
                ArgumentCaptor.forClass(TelephonyCallback.class);

        // TelephonyCallback for SUB_2 registered
        verify(mTm2).registerTelephonyCallback(any(), telephonyCallbackCaptor2.capture());

        TelephonyCallback cb2 = telephonyCallbackCaptor2.getValue();

        assertNotNull(cb2);
        assertTrue(cb2 instanceof TelephonyCallback.PreciseDataConnectionStateListener);

        TelephonyCallback.PreciseDataConnectionStateListener listener1 =
                (TelephonyCallback.PreciseDataConnectionStateListener) cb1;
        TelephonyCallback.PreciseDataConnectionStateListener listener2 =
                (TelephonyCallback.PreciseDataConnectionStateListener) cb2;

        PreciseDataConnectionState state = getPreciseDataConnectionState(
                ApnSetting.TYPE_DEFAULT, TelephonyManager.DATA_CONNECTED);
        listener1.onPreciseDataConnectionStateChanged(state);
        listener2.onPreciseDataConnectionStateChanged(state);

        verify(mDomainSelector, never()).notifyDataConnectionStateChange(anyInt(), anyInt());
        verify(mDomainSelector, never()).notifyDataConnectionStateChange(anyInt(), anyInt());

        state = getPreciseDataConnectionState(
                ApnSetting.TYPE_EMERGENCY, TelephonyManager.DATA_CONNECTED);
        listener1.onPreciseDataConnectionStateChanged(state);
        listener2.onPreciseDataConnectionStateChanged(state);

        verify(mDomainSelector, times(1)).notifyDataConnectionStateChange(
                eq(SLOT_0), eq(TelephonyManager.DATA_CONNECTED));
        verify(mDomainSelector, times(1)).notifyDataConnectionStateChange(
                eq(SLOT_1), eq(TelephonyManager.DATA_CONNECTED));

        state = getPreciseDataConnectionState(
                ApnSetting.TYPE_EMERGENCY, TelephonyManager.DATA_DISCONNECTING);
        listener1.onPreciseDataConnectionStateChanged(state);
        listener2.onPreciseDataConnectionStateChanged(state);

        verify(mDomainSelector, times(1)).notifyDataConnectionStateChange(
                eq(SLOT_0), eq(TelephonyManager.DATA_DISCONNECTING));
        verify(mDomainSelector, times(1)).notifyDataConnectionStateChange(
                eq(SLOT_1), eq(TelephonyManager.DATA_DISCONNECTING));

        state = getPreciseDataConnectionState(
                ApnSetting.TYPE_EMERGENCY, TelephonyManager.DATA_DISCONNECTED);
        listener1.onPreciseDataConnectionStateChanged(state);
        listener2.onPreciseDataConnectionStateChanged(state);

        verify(mDomainSelector, times(1)).notifyDataConnectionStateChange(
                eq(SLOT_0), eq(TelephonyManager.DATA_DISCONNECTED));
        verify(mDomainSelector, times(1)).notifyDataConnectionStateChange(
                eq(SLOT_1), eq(TelephonyManager.DATA_DISCONNECTED));
    }

    @Test
    public void testEmergencyCallbackModeEnter() throws Exception {
        // Enter ECBM on slot 1
        mContext.sendStickyBroadcast(getIntent(true, SLOT_1));

        assertFalse(mEpdnHelper.isInEmergencyCallbackMode(SLOT_0));
        assertTrue(mEpdnHelper.isInEmergencyCallbackMode(SLOT_1));
    }

    @Test
    public void testEmergencyCallbackModeExit() throws Exception {
        // Exit ECBM
        mContext.sendStickyBroadcast(getIntent(false, SLOT_0));

        assertFalse(mEpdnHelper.isInEmergencyCallbackMode(SLOT_0));
    }

    private static Intent getIntent(boolean inEcm, int slotIndex) {
        Intent intent = new Intent(TelephonyManager.ACTION_EMERGENCY_CALLBACK_MODE_CHANGED);
        intent.putExtra(TelephonyManager.EXTRA_PHONE_IN_ECM_STATE, inEcm);
        intent.putExtra(SubscriptionManager.EXTRA_SLOT_INDEX, slotIndex);
        return intent;
    }

    private static PreciseDataConnectionState getPreciseDataConnectionState(
            int apnType, int state) {
        return new PreciseDataConnectionState.Builder()
                .setTransportType(TRANSPORT_TYPE_WWAN)
                .setId(1)
                .setState(state)
                .setNetworkType(TelephonyManager.NETWORK_TYPE_LTE)
                .setApnSetting(new ApnSetting.Builder()
                        .setApnTypeBitmask(apnType)
                        .setApnName("default")
                        .setEntryName("default")
                        .build())
                .setLinkProperties(new LinkProperties())
                .setFailCause(0)
                .build();
    }

    private static void logd(String str) {
        Log.d(TAG, str);
    }
}
