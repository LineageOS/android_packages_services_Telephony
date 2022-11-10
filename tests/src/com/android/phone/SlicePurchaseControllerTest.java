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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;

import android.content.Context;
import android.content.Intent;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.data.NetworkSliceInfo;
import android.telephony.data.NetworkSlicingConfig;
import android.testing.TestableLooper;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.TelephonyTestBase;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.Phone;
import com.android.phone.slice.PremiumNetworkEntitlementApi;
import com.android.phone.slice.PremiumNetworkEntitlementResponse;
import com.android.phone.slice.SlicePurchaseController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;

@RunWith(AndroidJUnit4.class)
public class SlicePurchaseControllerTest extends TelephonyTestBase {
    private static final String TAG = "SlicePurchaseControllerTest";
    private static final String URL = "file:///android_asset/slice_purchase_test.html";
    private static final int PHONE_ID = 0;
    private static final long NOTIFICATION_TIMEOUT = 1000;
    private static final long PURCHASE_CONDITION_TIMEOUT = 2000;
    private static final long NETWORK_SETUP_TIMEOUT = 3000;
    private static final long THROTTLE_TIMEOUT = 4000;

    @Mock Phone mPhone;
    @Mock Context mMockedContext;
    @Mock CarrierConfigManager mCarrierConfigManager;
    @Mock CommandsInterface mCommandsInterface;
    @Mock ServiceState mServiceState;
    @Mock PremiumNetworkEntitlementApi mPremiumNetworkEntitlementApi;

    private SlicePurchaseController mSlicePurchaseController;
    private PersistableBundle mBundle;
    private PremiumNetworkEntitlementResponse mEntitlementResponse;
    private Handler mHandler;
    private TestableLooper mTestableLooper;
    @TelephonyManager.PurchasePremiumCapabilityResult private int mResult;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        HandlerThread handlerThread = new HandlerThread("SlicePurchaseControllerTest");
        handlerThread.start();
        mHandler = new Handler(handlerThread.getLooper()) {
            @Override
            public void handleMessage(Message msg) {
                AsyncResult ar = (AsyncResult) msg.obj;
                mResult = (int) ar.result;
            }
        };
        mTestableLooper = new TestableLooper(mHandler.getLooper());

        doReturn(PHONE_ID).when(mPhone).getPhoneId();
        doReturn(mMockedContext).when(mPhone).getContext();
        doReturn(mServiceState).when(mPhone).getServiceState();
        mPhone.mCi = mCommandsInterface;

        doReturn(Context.CARRIER_CONFIG_SERVICE).when(mMockedContext)
                .getSystemServiceName(eq(CarrierConfigManager.class));
        doReturn(mCarrierConfigManager).when(mMockedContext)
                .getSystemService(eq(Context.CARRIER_CONFIG_SERVICE));
        mBundle = new PersistableBundle();
        doReturn(mBundle).when(mCarrierConfigManager).getConfigForSubId(anyInt());

        mSlicePurchaseController = new SlicePurchaseController(mPhone, mHandler.getLooper());
        replaceInstance(SlicePurchaseController.class, "sInstances", mSlicePurchaseController,
                Map.of(PHONE_ID, mSlicePurchaseController));
        replaceInstance(SlicePurchaseController.class, "mPremiumNetworkEntitlementApi",
                mSlicePurchaseController, mPremiumNetworkEntitlementApi);
        mEntitlementResponse = new PremiumNetworkEntitlementResponse();
        doReturn(mEntitlementResponse).when(mPremiumNetworkEntitlementApi)
                .checkEntitlementStatus(anyInt());
    }

    @Test
    public void testIsPremiumCapabilityAvailableForPurchase() {
        assertFalse(mSlicePurchaseController.isPremiumCapabilityAvailableForPurchase(
                TelephonyManager.PREMIUM_CAPABILITY_PRIORITIZE_LATENCY));

        // all conditions met
        doReturn((int) TelephonyManager.NETWORK_TYPE_BITMASK_NR).when(mPhone)
                .getCachedAllowedNetworkTypesBitmask();
        mBundle.putIntArray(CarrierConfigManager.KEY_SUPPORTED_PREMIUM_CAPABILITIES_INT_ARRAY,
                new int[]{TelephonyManager.PREMIUM_CAPABILITY_PRIORITIZE_LATENCY});
        mBundle.putString(CarrierConfigManager.KEY_PREMIUM_CAPABILITY_PURCHASE_URL_STRING, URL);
        doReturn(mBundle).when(mCarrierConfigManager).getConfigForSubId(anyInt());
        doReturn(SubscriptionManager.getDefaultDataSubscriptionId()).when(mPhone).getSubId();

        // retry to verify available
        assertTrue(mSlicePurchaseController.isPremiumCapabilityAvailableForPurchase(
                TelephonyManager.PREMIUM_CAPABILITY_PRIORITIZE_LATENCY));
    }

    @Test
    public void testPurchasePremiumCapabilityResultFeatureNotSupported() {
        tryPurchasePremiumCapability();
        assertEquals(TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_FEATURE_NOT_SUPPORTED,
                mResult);

        // retry after enabling feature
        doReturn((int) TelephonyManager.NETWORK_TYPE_BITMASK_NR).when(mPhone)
                .getCachedAllowedNetworkTypesBitmask();

        tryPurchasePremiumCapability();
        assertNotEquals(TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_FEATURE_NOT_SUPPORTED,
                mResult);
    }

    @Test
    public void testPurchasePremiumCapabilityResultCarrierDisabled() {
        doReturn((int) TelephonyManager.NETWORK_TYPE_BITMASK_NR).when(mPhone)
                .getCachedAllowedNetworkTypesBitmask();

        tryPurchasePremiumCapability();
        assertEquals(TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_CARRIER_DISABLED, mResult);

        // retry after enabling carrier configs
        mBundle.putIntArray(CarrierConfigManager.KEY_SUPPORTED_PREMIUM_CAPABILITIES_INT_ARRAY,
                new int[]{TelephonyManager.PREMIUM_CAPABILITY_PRIORITIZE_LATENCY});
        mBundle.putString(CarrierConfigManager.KEY_PREMIUM_CAPABILITY_PURCHASE_URL_STRING, URL);
        doReturn(mBundle).when(mCarrierConfigManager).getConfigForSubId(anyInt());

        tryPurchasePremiumCapability();
        assertNotEquals(TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_CARRIER_DISABLED,
                mResult);
    }

    @Test
    public void testPurchasePremiumCapabilityResultNotDefaultDataSubscription() {
        doReturn((int) TelephonyManager.NETWORK_TYPE_BITMASK_NR).when(mPhone)
                .getCachedAllowedNetworkTypesBitmask();
        mBundle.putIntArray(CarrierConfigManager.KEY_SUPPORTED_PREMIUM_CAPABILITIES_INT_ARRAY,
                new int[]{TelephonyManager.PREMIUM_CAPABILITY_PRIORITIZE_LATENCY});
        mBundle.putString(CarrierConfigManager.KEY_PREMIUM_CAPABILITY_PURCHASE_URL_STRING, URL);
        doReturn(mBundle).when(mCarrierConfigManager).getConfigForSubId(anyInt());

        tryPurchasePremiumCapability();
        assertEquals(TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_NOT_DEFAULT_DATA_SUB,
                mResult);

        // retry on default data subscription
        doReturn(SubscriptionManager.getDefaultDataSubscriptionId()).when(mPhone).getSubId();

        tryPurchasePremiumCapability();
        assertNotEquals(TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_NOT_DEFAULT_DATA_SUB,
                mResult);
    }

    @Test
    public void testPurchasePremiumCapabilityResultNetworkNotAvailable() {
        doReturn((int) TelephonyManager.NETWORK_TYPE_BITMASK_NR).when(mPhone)
                .getCachedAllowedNetworkTypesBitmask();
        mBundle.putIntArray(CarrierConfigManager.KEY_SUPPORTED_PREMIUM_CAPABILITIES_INT_ARRAY,
                new int[]{TelephonyManager.PREMIUM_CAPABILITY_PRIORITIZE_LATENCY});
        mBundle.putString(CarrierConfigManager.KEY_PREMIUM_CAPABILITY_PURCHASE_URL_STRING, URL);
        doReturn(mBundle).when(mCarrierConfigManager).getConfigForSubId(anyInt());
        doReturn(SubscriptionManager.getDefaultDataSubscriptionId()).when(mPhone).getSubId();

        tryPurchasePremiumCapability();
        assertEquals(TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_NETWORK_NOT_AVAILABLE,
                mResult);

        // retry with valid network
        doReturn(TelephonyManager.NETWORK_TYPE_NR).when(mServiceState).getDataNetworkType();

        tryPurchasePremiumCapability();
        assertNotEquals(TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_NETWORK_NOT_AVAILABLE,
                mResult);
    }

    @Test
    public void testPurchasePremiumCapabilityResultEntitlementCheckFailed() throws Exception {
        doReturn((int) TelephonyManager.NETWORK_TYPE_BITMASK_NR).when(mPhone)
                .getCachedAllowedNetworkTypesBitmask();
        mBundle.putIntArray(CarrierConfigManager.KEY_SUPPORTED_PREMIUM_CAPABILITIES_INT_ARRAY,
                new int[]{TelephonyManager.PREMIUM_CAPABILITY_PRIORITIZE_LATENCY});
        mBundle.putString(CarrierConfigManager.KEY_PREMIUM_CAPABILITY_PURCHASE_URL_STRING, URL);
        doReturn(mBundle).when(mCarrierConfigManager).getConfigForSubId(anyInt());
        doReturn(SubscriptionManager.getDefaultDataSubscriptionId()).when(mPhone).getSubId();
        doReturn(TelephonyManager.NETWORK_TYPE_NR).when(mServiceState).getDataNetworkType();
        doReturn(null).when(mPremiumNetworkEntitlementApi).checkEntitlementStatus(anyInt());

        tryPurchasePremiumCapability();
        assertEquals(TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_ENTITLEMENT_CHECK_FAILED,
                mResult);

        // retry with provisioned response
        mEntitlementResponse.mProvisionStatus =
                PremiumNetworkEntitlementResponse.PREMIUM_NETWORK_PROVISION_STATUS_PROVISIONED;
        doReturn(mEntitlementResponse).when(mPremiumNetworkEntitlementApi)
                .checkEntitlementStatus(anyInt());

        tryPurchasePremiumCapability();
        assertEquals(TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_ALREADY_PURCHASED,
                mResult);

        // retry with provisioning response
        mEntitlementResponse.mProvisionStatus =
                PremiumNetworkEntitlementResponse.PREMIUM_NETWORK_PROVISION_STATUS_IN_PROGRESS;
        doReturn(mEntitlementResponse).when(mPremiumNetworkEntitlementApi)
                .checkEntitlementStatus(anyInt());

        tryPurchasePremiumCapability();
        assertEquals(TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_ALREADY_IN_PROGRESS,
                mResult);

        // retry with disallowed response and throttling
        mEntitlementResponse.mProvisionStatus =
                PremiumNetworkEntitlementResponse.PREMIUM_NETWORK_PROVISION_STATUS_NOT_PROVISIONED;
        mEntitlementResponse.mEntitlementStatus =
                PremiumNetworkEntitlementResponse.PREMIUM_NETWORK_ENTITLEMENT_STATUS_INCOMPATIBLE;
        doReturn(mEntitlementResponse).when(mPremiumNetworkEntitlementApi)
                .checkEntitlementStatus(anyInt());
        mBundle.putLong(CarrierConfigManager
                .KEY_PREMIUM_CAPABILITY_PURCHASE_CONDITION_BACKOFF_HYSTERESIS_TIME_MILLIS_LONG,
                PURCHASE_CONDITION_TIMEOUT);
        doReturn(mBundle).when(mCarrierConfigManager).getConfigForSubId(anyInt());

        tryPurchasePremiumCapability();
        assertEquals(TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_ENTITLEMENT_CHECK_FAILED,
                mResult);

        // retry to verify throttled
        tryPurchasePremiumCapability();
        assertEquals(TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_THROTTLED, mResult);

        // retry with valid entitlement check to verify unthrottled
        mTestableLooper.moveTimeForward(PURCHASE_CONDITION_TIMEOUT);
        mTestableLooper.processAllMessages();

        testPurchasePremiumCapabilityResultSuccess();
    }

    @Test
    public void testPurchasePremiumCapabilityResultAlreadyInProgress() {
        sendValidPurchaseRequest();

        tryPurchasePremiumCapability();
        assertEquals(TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_ALREADY_IN_PROGRESS,
                mResult);

        // retry to verify same result
        tryPurchasePremiumCapability();
        assertEquals(TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_ALREADY_IN_PROGRESS,
                mResult);
    }

    @Test
    public void testPurchasePremiumCapabilityResultSuccess() throws Exception {
        sendValidPurchaseRequest();

        Intent intent = new Intent();
        intent.setAction("com.android.phone.slice.action.SLICE_PURCHASE_APP_RESPONSE_SUCCESS");
        intent.putExtra(SlicePurchaseController.EXTRA_PHONE_ID, PHONE_ID);
        intent.putExtra(SlicePurchaseController.EXTRA_PREMIUM_CAPABILITY,
                TelephonyManager.PREMIUM_CAPABILITY_PRIORITIZE_LATENCY);
        receiveSlicePurchaseResponse(intent);
        assertEquals(TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_SUCCESS, mResult);

        // retry tested in testPurchasePremiumCapabilityResultPendingNetworkSetup
    }

    @Test
    public void testPurchasePremiumCapabilityResultPendingNetworkSetup() throws Exception {
        testPurchasePremiumCapabilityResultSuccess();

        tryPurchasePremiumCapability();
        assertEquals(TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_PENDING_NETWORK_SETUP,
                mResult);

        // retry to verify unthrottled
        mTestableLooper.moveTimeForward(NETWORK_SETUP_TIMEOUT);
        mTestableLooper.processAllMessages();

        testPurchasePremiumCapabilityResultSuccess();
    }

    @Test
    public void testPurchasePremiumCapabilityResultAlreadyPurchased() throws Exception {
        testPurchasePremiumCapabilityResultSuccess();

        // TODO: implement slicing config logic properly
        NetworkSlicingConfig slicingConfig = new NetworkSlicingConfig(Collections.emptyList(),
                Collections.singletonList(new NetworkSliceInfo.Builder()
                        .setStatus(NetworkSliceInfo.SLICE_STATUS_ALLOWED).build()));
        mSlicePurchaseController.obtainMessage(2 /* EVENT_SLICING_CONFIG_CHANGED */,
                new AsyncResult(null, slicingConfig, null)).sendToTarget();
        mTestableLooper.processAllMessages();

        tryPurchasePremiumCapability();
        assertEquals(TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_ALREADY_PURCHASED,
                mResult);

        // retry to verify same result
        tryPurchasePremiumCapability();
        assertEquals(TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_ALREADY_PURCHASED,
                mResult);

        // retry to verify purchase expired
        slicingConfig = new NetworkSlicingConfig(Collections.emptyList(), Collections.emptyList());
        mSlicePurchaseController.obtainMessage(2 /* EVENT_SLICING_CONFIG_CHANGED */,
                new AsyncResult(null, slicingConfig, null)).sendToTarget();
        mTestableLooper.processAllMessages();

        testPurchasePremiumCapabilityResultSuccess();
    }

    @Test
    public void testPurchasePremiumCapabilityResultTimeout() throws Exception {
        sendValidPurchaseRequest();

        mTestableLooper.moveTimeForward(NOTIFICATION_TIMEOUT);
        mTestableLooper.processAllMessages();
        assertEquals(TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_TIMEOUT, mResult);

        // retry to verify throttled
        tryPurchasePremiumCapability();
        assertEquals(TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_THROTTLED, mResult);

        // retry to verify unthrottled
        mTestableLooper.moveTimeForward(THROTTLE_TIMEOUT);
        mTestableLooper.processAllMessages();

        testPurchasePremiumCapabilityResultSuccess();
    }

    @Test
    public void testPurchasePremiumCapabilityResultUserCanceled() throws Exception {
        sendValidPurchaseRequest();

        Intent intent = new Intent();
        intent.setAction("com.android.phone.slice.action.SLICE_PURCHASE_APP_RESPONSE_CANCELED");
        intent.putExtra(SlicePurchaseController.EXTRA_PHONE_ID, PHONE_ID);
        intent.putExtra(SlicePurchaseController.EXTRA_PREMIUM_CAPABILITY,
                TelephonyManager.PREMIUM_CAPABILITY_PRIORITIZE_LATENCY);
        receiveSlicePurchaseResponse(intent);
        assertEquals(TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_USER_CANCELED, mResult);

        // retry to verify throttled
        tryPurchasePremiumCapability();
        assertEquals(TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_THROTTLED, mResult);

        // retry to verify unthrottled
        mTestableLooper.moveTimeForward(THROTTLE_TIMEOUT);
        mTestableLooper.processAllMessages();

        testPurchasePremiumCapabilityResultSuccess();
    }

    @Test
    public void testPurchasePremiumCapabilityResultCarrierError() throws Exception {
        sendValidPurchaseRequest();

        Intent intent = new Intent();
        intent.setAction(
                "com.android.phone.slice.action.SLICE_PURCHASE_APP_RESPONSE_CARRIER_ERROR");
        intent.putExtra(SlicePurchaseController.EXTRA_PHONE_ID, PHONE_ID);
        intent.putExtra(SlicePurchaseController.EXTRA_PREMIUM_CAPABILITY,
                TelephonyManager.PREMIUM_CAPABILITY_PRIORITIZE_LATENCY);
        intent.putExtra(SlicePurchaseController.EXTRA_FAILURE_CODE,
                SlicePurchaseController.FAILURE_CODE_SERVER_UNREACHABLE);
        receiveSlicePurchaseResponse(intent);
        assertEquals(TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_CARRIER_ERROR, mResult);

        // retry to verify throttled
        tryPurchasePremiumCapability();
        assertEquals(TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_THROTTLED, mResult);

        // retry to verify unthrottled
        mTestableLooper.moveTimeForward(PURCHASE_CONDITION_TIMEOUT);
        mTestableLooper.processAllMessages();

        testPurchasePremiumCapabilityResultSuccess();
    }

    @Test
    public void testPurchasePremiumCapabilityResultRequestFailed() throws Exception {
        sendValidPurchaseRequest();

        Intent intent = new Intent();
        intent.setAction(
                "com.android.phone.slice.action.SLICE_PURCHASE_APP_RESPONSE_REQUEST_FAILED");
        intent.putExtra(SlicePurchaseController.EXTRA_PHONE_ID, PHONE_ID);
        intent.putExtra(SlicePurchaseController.EXTRA_PREMIUM_CAPABILITY,
                TelephonyManager.PREMIUM_CAPABILITY_PRIORITIZE_LATENCY);
        receiveSlicePurchaseResponse(intent);
        assertEquals(TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_REQUEST_FAILED, mResult);

        // retry to verify no throttling
        testPurchasePremiumCapabilityResultSuccess();
    }

    @Test
    public void testPurchasePremiumCapabilityResultNotDefaultDataSubscriptionResponse()
            throws Exception {
        sendValidPurchaseRequest();

        Intent intent = new Intent();
        intent.setAction(
                "com.android.phone.slice.action.SLICE_PURCHASE_APP_RESPONSE_NOT_DEFAULT_DATA_SUB");
        intent.putExtra(SlicePurchaseController.EXTRA_PHONE_ID, PHONE_ID);
        intent.putExtra(SlicePurchaseController.EXTRA_PREMIUM_CAPABILITY,
                TelephonyManager.PREMIUM_CAPABILITY_PRIORITIZE_LATENCY);
        receiveSlicePurchaseResponse(intent);
        assertEquals(TelephonyManager.PURCHASE_PREMIUM_CAPABILITY_RESULT_NOT_DEFAULT_DATA_SUB,
                mResult);

        // retry to verify no throttling
        testPurchasePremiumCapabilityResultSuccess();
    }

    private void sendValidPurchaseRequest() {
        // feature supported
        doReturn((int) TelephonyManager.NETWORK_TYPE_BITMASK_NR).when(mPhone)
                .getCachedAllowedNetworkTypesBitmask();
        // carrier supported
        mBundle.putIntArray(CarrierConfigManager.KEY_SUPPORTED_PREMIUM_CAPABILITIES_INT_ARRAY,
                new int[]{TelephonyManager.PREMIUM_CAPABILITY_PRIORITIZE_LATENCY});
        mBundle.putString(CarrierConfigManager.KEY_PREMIUM_CAPABILITY_PURCHASE_URL_STRING, URL);
        mBundle.putLong(CarrierConfigManager
                .KEY_PREMIUM_CAPABILITY_NOTIFICATION_DISPLAY_TIMEOUT_MILLIS_LONG,
                NOTIFICATION_TIMEOUT);
        mBundle.putLong(CarrierConfigManager.KEY_PREMIUM_CAPABILITY_NETWORK_SETUP_TIME_MILLIS_LONG,
                NETWORK_SETUP_TIMEOUT);
        mBundle.putLong(CarrierConfigManager
                .KEY_PREMIUM_CAPABILITY_NOTIFICATION_BACKOFF_HYSTERESIS_TIME_MILLIS_LONG,
                THROTTLE_TIMEOUT);
        mBundle.putLong(CarrierConfigManager
                .KEY_PREMIUM_CAPABILITY_PURCHASE_CONDITION_BACKOFF_HYSTERESIS_TIME_MILLIS_LONG,
                PURCHASE_CONDITION_TIMEOUT);
        doReturn(mBundle).when(mCarrierConfigManager).getConfigForSubId(anyInt());
        // default data subscription
        doReturn(SubscriptionManager.getDefaultDataSubscriptionId()).when(mPhone).getSubId();
        // network available
        doReturn(TelephonyManager.NETWORK_TYPE_NR).when(mServiceState).getDataNetworkType();
        // entitlement check passed
        mEntitlementResponse.mEntitlementStatus =
                PremiumNetworkEntitlementResponse.PREMIUM_NETWORK_ENTITLEMENT_STATUS_ENABLED;
        doReturn(mEntitlementResponse).when(mPremiumNetworkEntitlementApi)
                .checkEntitlementStatus(anyInt());

        tryPurchasePremiumCapability();
        // verify that the purchase request was sent successfully and purchase timeout started
        assertTrue(mSlicePurchaseController.hasMessages(4 /* EVENT_PURCHASE_TIMEOUT */,
                TelephonyManager.PREMIUM_CAPABILITY_PRIORITIZE_LATENCY));
    }

    private void tryPurchasePremiumCapability() {
        try {
            mSlicePurchaseController.purchasePremiumCapability(
                    TelephonyManager.PREMIUM_CAPABILITY_PRIORITIZE_LATENCY, TAG,
                    mHandler.obtainMessage());
            mTestableLooper.processAllMessages();
        } catch (Exception expected) {
            // SecurityException rethrown as RuntimeException when broadcasting intent
            Log.d(TAG, expected.getMessage());
        }
        mTestableLooper.processAllMessages();
    }

    private void receiveSlicePurchaseResponse(Intent intent) throws Exception {
        Class<?> receiverClass = Class.forName("com.android.phone.slice.SlicePurchaseController"
                + "$SlicePurchaseControllerBroadcastReceiver");
        Constructor<?> constructor = receiverClass.getDeclaredConstructor(
                SlicePurchaseController.class, int.class);
        constructor.setAccessible(true);
        Object receiver = constructor.newInstance(mSlicePurchaseController,
                TelephonyManager.PREMIUM_CAPABILITY_PRIORITIZE_LATENCY);

        Method method = receiver.getClass().getDeclaredMethod(
                "onReceive", Context.class, Intent.class);
        method.invoke(receiver, mMockedContext, intent);
        mTestableLooper.processAllMessages();
    }
}
