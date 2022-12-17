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

package com.android.services.telephony.domainselection;

import static android.telephony.DomainSelectionService.SELECTOR_TYPE_CALLING;
import static android.telephony.DomainSelectionService.SELECTOR_TYPE_UT;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;

import android.annotation.NonNull;
import android.content.Context;
import android.os.CancellationSignal;
import android.os.HandlerThread;
import android.os.Looper;
import android.telephony.AccessNetworkConstants;
import android.telephony.DisconnectCause;
import android.telephony.DomainSelectionService;
import android.telephony.DomainSelector;
import android.telephony.EmergencyRegResult;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;
import android.telephony.TransportSelectorCallback;
import android.telephony.WwanSelectorCallback;
import android.telephony.ims.ImsManager;
import android.telephony.ims.ImsMmTelManager;
import android.telephony.ims.ImsReasonInfo;
import android.util.Log;

import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Unit tests for DomainSelectorBase.
 */
@RunWith(AndroidJUnit4.class)
public class NormalCallDomainSelectorTest {
    private static final String TAG = "NormalCallDomainSelectorTest";

    private static final int SLOT_ID = 0;
    private static final int SUB_ID_1 = 1;
    private static final int SUB_ID_2 = 2;
    private static final String TEST_CALLID = "01234";

    private HandlerThread mHandlerThread;
    private NormalCallDomainSelector mNormalCallDomainSelector;

    @Mock private Context mMockContext;
    @Mock private ImsManager mMockImsManager;
    @Mock private ImsMmTelManager mMockMmTelManager;
    @Mock private ServiceState mMockServiceState;
    @Mock private ImsStateTracker mMockImsStateTracker;
    @Mock private DomainSelectorBase.DestroyListener mMockDestroyListener;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        doReturn(Context.TELEPHONY_IMS_SERVICE).when(mMockContext)
                .getSystemServiceName(ImsManager.class);
        doReturn(mMockImsManager).when(mMockContext)
                .getSystemService(Context.TELEPHONY_IMS_SERVICE);
        doReturn(mMockMmTelManager).when(mMockImsManager).getImsMmTelManager(SUB_ID_1);
        doReturn(mMockMmTelManager).when(mMockImsManager).getImsMmTelManager(SUB_ID_2);
        doNothing().when(mMockImsStateTracker).removeServiceStateListener(any());
        doNothing().when(mMockImsStateTracker).removeImsStateListener(any());
        doReturn(true).when(mMockImsStateTracker).isMmTelFeatureAvailable();

        // Set up the looper if it does not exist on the test thread.
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }

        mHandlerThread = new HandlerThread(
                NormalCallDomainSelectorTest.class.getSimpleName());
        mHandlerThread.start();

        mNormalCallDomainSelector = new NormalCallDomainSelector(mMockContext, SLOT_ID, SUB_ID_1,
                mHandlerThread.getLooper(), mMockImsStateTracker, mMockDestroyListener);
    }

    @After
    public void tearDown() throws Exception {
        if (mHandlerThread != null) {
            mHandlerThread.quit();
        }
    }

    private void initialize(ServiceState serviceState, boolean isImsRegistered,
                            boolean isImsRegisteredOverWlan, boolean isImsVoiceCapable,
                            boolean isImsVideoCapable) {
        if (serviceState != null) mNormalCallDomainSelector.onServiceStateUpdated(serviceState);
        doReturn(isImsRegistered).when(mMockImsStateTracker).isImsStateReady();
        doReturn(isImsRegistered).when(mMockImsStateTracker).isImsRegistered();
        doReturn(isImsVoiceCapable).when(mMockImsStateTracker).isImsVoiceCapable();
        doReturn(isImsVideoCapable).when(mMockImsStateTracker).isImsVideoCapable();
        doReturn(isImsRegisteredOverWlan).when(mMockImsStateTracker).isImsRegisteredOverWlan();
        mNormalCallDomainSelector.onImsRegistrationStateChanged();
        mNormalCallDomainSelector.onImsMmTelCapabilitiesChanged();
    }

    @Test
    public void testInit() {
        assertEquals(SLOT_ID, mNormalCallDomainSelector.getSlotId());
        assertEquals(SUB_ID_1, mNormalCallDomainSelector.getSubId());
    }

    @Test
    public void testSelectDomainInputParams() {
        MockTransportSelectorCallback transportSelectorCallback =
                new MockTransportSelectorCallback();

        DomainSelectionService.SelectionAttributes attributes =
                new DomainSelectionService.SelectionAttributes.Builder(
                        SLOT_ID, SUB_ID_1, SELECTOR_TYPE_CALLING)
                        .setCallId(TEST_CALLID)
                        .setEmergency(false)
                        .setVideoCall(true)
                        .setExitedFromAirplaneMode(false)
                        .build();
        mNormalCallDomainSelector.selectDomain(attributes, transportSelectorCallback);


        // Case 1: null inputs
        try {
            mNormalCallDomainSelector.selectDomain(null, null);
        } catch (Exception e) {
            fail("Invalid input params not handled.");
        }

        // Case 2: null TransportSelectorCallback
        try {
            mNormalCallDomainSelector.selectDomain(attributes, null);
        } catch (Exception e) {
            fail("Invalid params (SelectionAttributes) not handled.");
        }

        // Case 3: null SelectionAttributes
        transportSelectorCallback.mSelectionTerminated = false;
        try {
            mNormalCallDomainSelector.selectDomain(null, transportSelectorCallback);
        } catch (Exception e) {
            fail("Invalid params (SelectionAttributes) not handled.");
        }

        assertTrue(transportSelectorCallback
                .verifyOnSelectionTerminated(DisconnectCause.OUTGOING_FAILURE));

        // Case 4: Invalid Subscription-id
        attributes = new DomainSelectionService.SelectionAttributes.Builder(
                SLOT_ID, SubscriptionManager.INVALID_SUBSCRIPTION_ID, SELECTOR_TYPE_CALLING)
                .setCallId(TEST_CALLID)
                .setEmergency(false)
                .setVideoCall(true)
                .setExitedFromAirplaneMode(false)
                .build();
        try {
            mNormalCallDomainSelector.selectDomain(attributes, transportSelectorCallback);
        } catch (Exception e) {
            fail("Invalid params (SelectionAttributes) not handled.");
        }

        assertTrue(transportSelectorCallback
                .verifyOnSelectionTerminated(DisconnectCause.OUTGOING_FAILURE));

        // Case 5: Invalid SELECTOR_TYPE
        attributes =
                new DomainSelectionService.SelectionAttributes.Builder(
                        SLOT_ID, SUB_ID_1, SELECTOR_TYPE_UT)
                        .setCallId(TEST_CALLID)
                        .setEmergency(false)
                        .setVideoCall(true)
                        .setExitedFromAirplaneMode(false)
                        .build();
        try {
            mNormalCallDomainSelector.selectDomain(null, transportSelectorCallback);
        } catch (Exception e) {
            fail("Invalid params (SelectionAttributes) not handled.");
        }

        assertTrue(transportSelectorCallback
                .verifyOnSelectionTerminated(DisconnectCause.OUTGOING_FAILURE));

        // Case 6: Emergency Call
        attributes = new DomainSelectionService.SelectionAttributes.Builder(
                SLOT_ID, SUB_ID_1, SELECTOR_TYPE_UT)
                .setCallId(TEST_CALLID)
                .setEmergency(true)
                .setVideoCall(true)
                .setExitedFromAirplaneMode(false)
                .build();
        try {
            mNormalCallDomainSelector.selectDomain(null, transportSelectorCallback);
        } catch (Exception e) {
            fail("Invalid params (SelectionAttributes) not handled.");
        }

        assertTrue(transportSelectorCallback
                .verifyOnSelectionTerminated(DisconnectCause.OUTGOING_FAILURE));
    }

    @Test
    public void testOutOfService() {
        MockTransportSelectorCallback transportSelectorCallback =
                new MockTransportSelectorCallback();
        DomainSelectionService.SelectionAttributes attributes =
                new DomainSelectionService.SelectionAttributes.Builder(
                        SLOT_ID, SUB_ID_1, SELECTOR_TYPE_CALLING)
                        .setCallId(TEST_CALLID)
                        .setEmergency(false)
                        .setVideoCall(true)
                        .setExitedFromAirplaneMode(false)
                        .build();
        mNormalCallDomainSelector.selectDomain(attributes, transportSelectorCallback);
        ServiceState serviceState = new ServiceState();
        serviceState.setStateOutOfService();
        mNormalCallDomainSelector.onServiceStateUpdated(serviceState);
        assertTrue(transportSelectorCallback
                .verifyOnSelectionTerminated(DisconnectCause.OUT_OF_SERVICE));
    }

    @Test
    public void testDomainSelection() {
        MockTransportSelectorCallback transportSelectorCallback =
                new MockTransportSelectorCallback();
        DomainSelectionService.SelectionAttributes attributes =
                new DomainSelectionService.SelectionAttributes.Builder(
                        SLOT_ID, SUB_ID_1, SELECTOR_TYPE_CALLING)
                        .setCallId(TEST_CALLID)
                        .setEmergency(false)
                        .setVideoCall(false)
                        .setExitedFromAirplaneMode(false)
                        .build();

        // Case 1: WLAN
        ServiceState serviceState = new ServiceState();
        serviceState.setState(ServiceState.STATE_IN_SERVICE);
        initialize(serviceState, true, true, true, true);
        mNormalCallDomainSelector.selectDomain(attributes, transportSelectorCallback);
        assertTrue(transportSelectorCallback.verifyOnWlanSelected());

        // Case 2: 5G
        mNormalCallDomainSelector.selectDomain(attributes, transportSelectorCallback);
        initialize(serviceState, true, false, true, true);
        mNormalCallDomainSelector.selectDomain(attributes, transportSelectorCallback);
        assertTrue(transportSelectorCallback.verifyOnWwanSelected());
        assertTrue(transportSelectorCallback
                .verifyOnDomainSelected(NetworkRegistrationInfo.DOMAIN_PS));

        // Case 3: PS -> CS redial
        ImsReasonInfo imsReasonInfo = new ImsReasonInfo();
        imsReasonInfo.mCode = ImsReasonInfo.CODE_LOCAL_CALL_CS_RETRY_REQUIRED;
        attributes = new DomainSelectionService.SelectionAttributes.Builder(
                SLOT_ID, SUB_ID_1, SELECTOR_TYPE_CALLING)
                .setCallId(TEST_CALLID)
                .setEmergency(false)
                .setVideoCall(false)
                .setExitedFromAirplaneMode(false)
                .setPsDisconnectCause(imsReasonInfo)
                .build();
        mNormalCallDomainSelector.reselectDomain(attributes);
        assertTrue(transportSelectorCallback
                .verifyOnDomainSelected(NetworkRegistrationInfo.DOMAIN_CS));

        // Case 4: CS call
        NetworkRegistrationInfo nwRegistrationInfo = new NetworkRegistrationInfo(
                NetworkRegistrationInfo.DOMAIN_CS, AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                NetworkRegistrationInfo.REGISTRATION_STATE_HOME,
                AccessNetworkConstants.AccessNetworkType.UTRAN, 0, false,
                null, null, null, false, 0, 0, 0);
        serviceState.addNetworkRegistrationInfo(nwRegistrationInfo);
        mNormalCallDomainSelector.selectDomain(attributes, transportSelectorCallback);
        initialize(serviceState, false, false, false, false);
        mNormalCallDomainSelector.selectDomain(attributes, transportSelectorCallback);
        assertTrue(transportSelectorCallback.verifyOnWwanSelected());
        assertTrue(transportSelectorCallback
                .verifyOnDomainSelected(NetworkRegistrationInfo.DOMAIN_CS));
    }

    class MockTransportSelectorCallback implements TransportSelectorCallback, WwanSelectorCallback {
        public boolean mCreated = false;
        public boolean mWlanSelected = false;
        public boolean mWwanSelected = false;
        public boolean mSelectionTerminated = false;
        int mCauseCode = 0;
        int mSelectedDomain = 0;

        @Override
        public synchronized void onCreated(DomainSelector selector) {
            Log.d(TAG, "onCreated");
            mCreated = true;
            notifyAll();
        }

        public boolean verifyOnCreated() {
            mCreated = false;
            Log.d(TAG, "verifyOnCreated");
            waitForCallback();
            return mCreated;
        }

        @Override
        public synchronized void onWlanSelected() {
            Log.d(TAG, "onWlanSelected");
            mWlanSelected = true;
            notifyAll();
        }

        public boolean verifyOnWlanSelected() {
            Log.d(TAG, "verifyOnWlanSelected");
            waitForCallback();
            return mWlanSelected;
        }

        @Override
        public synchronized WwanSelectorCallback onWwanSelected() {
            mWwanSelected = true;
            notifyAll();
            return (WwanSelectorCallback) this;
        }

        @Override
        public void onWwanSelected(final Consumer<WwanSelectorCallback> consumer) {
            mWwanSelected = true;
            Executors.newSingleThreadExecutor().execute(() -> {
                consumer.accept(this);
            });
        }

        public boolean verifyOnWwanSelected() {
            waitForCallback();
            return mWwanSelected;
        }

        @Override
        public synchronized void onSelectionTerminated(int cause) {
            Log.i(TAG, "onSelectionTerminated - called");
            mCauseCode = cause;
            mSelectionTerminated = true;
            notifyAll();
        }

        public boolean verifyOnSelectionTerminated(int cause) {
            Log.i(TAG, "verifyOnSelectionTerminated - called");
            if (!mSelectionTerminated) {
                waitForCallback();
            }
            return (mSelectionTerminated && cause == mCauseCode);
        }

        private synchronized void waitForCallback() {
            try {
                wait(1000);
            } catch (Exception e) {
                return;
            }
        }

        @Override
        public void onRequestEmergencyNetworkScan(@NonNull List<Integer> preferredNetworks,
                                                  int scanType,
                                                  @NonNull CancellationSignal signal,
                                                  @NonNull Consumer<EmergencyRegResult> consumer) {
            Log.i(TAG, "onRequestEmergencyNetworkScan - called");

        }

        public synchronized void onDomainSelected(@NetworkRegistrationInfo.Domain int domain) {
            Log.i(TAG, "onDomainSelected - called");
            mSelectedDomain = domain;
            notifyAll();
        }

        public boolean verifyOnDomainSelected(int domain) {
            Log.i(TAG, "verifyOnDomainSelected - called");
            waitForCallback();
            return (domain == mSelectedDomain);
        }
    }
}
