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

package com.android.phone.satellite.entitlement;

import static com.android.libraries.entitlement.ServiceEntitlementException.ERROR_HTTP_STATUS_NOT_SUCCESS;
import static com.android.phone.satellite.entitlement.SatelliteEntitlementResult.SATELLITE_ENTITLEMENT_STATUS_DISABLED;
import static com.android.phone.satellite.entitlement.SatelliteEntitlementResult.SATELLITE_ENTITLEMENT_STATUS_ENABLED;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyVararg;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.telephony.TelephonyManager;
import android.testing.TestableLooper;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.TelephonyTestBase;
import com.android.internal.telephony.ExponentialBackoff;
import com.android.internal.telephony.satellite.SatelliteController;
import com.android.internal.telephony.subscription.SubscriptionManagerService;
import com.android.libraries.entitlement.ServiceEntitlementException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class SatelliteEntitlementControllerTest extends TelephonyTestBase {
    private static final String TAG = "SatelliteEntitlementControllerTest";
    private static final int SUB_ID = 0;
    private static final int SUB_ID_2 = 1;
    private static final int[] ACTIVE_SUB_ID = {SUB_ID};
    private static final int DEFAULT_QUERY_REFRESH_DAY = 7;
    private static final List<String> PLMN_ALLOWED_LIST = Arrays.asList("31026", "302820");
    private static final List<String> PLMN_BARRED_LIST = Arrays.asList("12345", "98765");
    private static final List<String> EMPTY_PLMN_LIST = new ArrayList<>();
    private static final int CMD_START_QUERY_ENTITLEMENT = 1;
    private static final int CMD_RETRY_QUERY_ENTITLEMENT = 2;
    private static final int CMD_SIM_REFRESH = 3;
    private static final int MAX_RETRY_COUNT = 5;
    @Mock
    CarrierConfigManager mCarrierConfigManager;
    @Mock
    ConnectivityManager mConnectivityManager;
    @Mock Network mNetwork;
    @Mock TelephonyManager mTelephonyManager;
    @Mock SubscriptionManagerService mMockSubscriptionManagerService;
    @Mock SatelliteEntitlementApi mSatelliteEntitlementApi;
    @Mock SatelliteEntitlementResult mSatelliteEntitlementResult;
    @Mock SatelliteController mSatelliteController;
    private PersistableBundle mCarrierConfigBundle;
    private TestSatelliteEntitlementController mSatelliteEntitlementController;
    private Handler mHandler;
    private TestableLooper mTestableLooper;
    private List<Pair<Executor, CarrierConfigManager.CarrierConfigChangeListener>>
            mCarrierConfigChangedListenerList = new ArrayList<>();
    @Before
    public void setUp() throws Exception {
        super.setUp();
        MockitoAnnotations.initMocks(this);

        replaceInstance(SubscriptionManagerService.class, "sInstance", null,
                mMockSubscriptionManagerService);
        replaceInstance(SatelliteController.class, "sInstance", null, mSatelliteController);

        HandlerThread handlerThread = new HandlerThread("SatelliteEntitlementController");
        handlerThread.start();
        mHandler = new Handler(handlerThread.getLooper()) {
            @Override
            public void handleMessage(Message msg) {
            }
        };
        mTestableLooper = new TestableLooper(mHandler.getLooper());
        doReturn(Context.TELEPHONY_SERVICE).when(mContext).getSystemServiceName(
                TelephonyManager.class);
        doReturn(mTelephonyManager).when(mContext).getSystemService(Context.TELEPHONY_SERVICE);
        doReturn(mTelephonyManager).when(mTelephonyManager).createForSubscriptionId(anyInt());
        doReturn(Context.CARRIER_CONFIG_SERVICE).when(mContext).getSystemServiceName(
                CarrierConfigManager.class);
        doReturn(mCarrierConfigManager).when(mContext).getSystemService(
                Context.CARRIER_CONFIG_SERVICE);
        doAnswer(invocation -> {
            Executor executor = invocation.getArgument(0);
            CarrierConfigManager.CarrierConfigChangeListener listener = invocation.getArgument(1);
            mCarrierConfigChangedListenerList.add(new Pair<>(executor, listener));
            return null;
        }).when(mCarrierConfigManager).registerCarrierConfigChangeListener(
                any(Executor.class),
                any(CarrierConfigManager.CarrierConfigChangeListener.class));
        mCarrierConfigBundle = new PersistableBundle();
        mCarrierConfigBundle.putInt(
                CarrierConfigManager.KEY_SATELLITE_ENTITLEMENT_STATUS_REFRESH_DAYS_INT,
                DEFAULT_QUERY_REFRESH_DAY);
        mCarrierConfigBundle.putBoolean(
                CarrierConfigManager.KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL, true);
        doReturn(mCarrierConfigBundle)
                .when(mCarrierConfigManager).getConfigForSubId(anyInt(), anyVararg());
        doReturn(Context.CONNECTIVITY_SERVICE).when(mContext).getSystemServiceName(
                ConnectivityManager.class);
        doReturn(mConnectivityManager).when(mContext).getSystemService(
                Context.CONNECTIVITY_SERVICE);
        doReturn(mNetwork).when(mConnectivityManager).getActiveNetwork();
        doReturn(ACTIVE_SUB_ID).when(mMockSubscriptionManagerService).getActiveSubIdList(true);
        mSatelliteEntitlementController = new TestSatelliteEntitlementController(mContext,
                mHandler.getLooper(), mSatelliteEntitlementApi);
        mSatelliteEntitlementController = spy(mSatelliteEntitlementController);
        doReturn(mSatelliteEntitlementResult).when(
                mSatelliteEntitlementApi).checkEntitlementStatus();
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @Test
    public void testShouldStartQueryEntitlement() throws Exception {
        logd("testShouldStartQueryEntitlement");
        doReturn(ACTIVE_SUB_ID).when(mMockSubscriptionManagerService).getActiveSubIdList(true);

        // Verify don't start the query when KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL is false.
        mCarrierConfigBundle.putBoolean(
                CarrierConfigManager.KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL, false);
        mSatelliteEntitlementController.handleCmdStartQueryEntitlement();

        verify(mSatelliteEntitlementApi, never()).checkEntitlementStatus();
        verify(mSatelliteController, never()).onSatelliteEntitlementStatusUpdated(anyInt(),
                anyBoolean(), anyList(), anyList(), any());

        mCarrierConfigBundle.putBoolean(
                CarrierConfigManager.KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL, true);
        // Verify don't start the query when Internet is disconnected.
        clearInvocationsForMock();
        setInternetConnected(false);
        mSatelliteEntitlementController.handleCmdStartQueryEntitlement();

        verify(mSatelliteEntitlementApi, never()).checkEntitlementStatus();
        verify(mSatelliteController, never()).onSatelliteEntitlementStatusUpdated(anyInt(),
                anyBoolean(), anyList(), anyList(), any());

        setInternetConnected(true);
        // Verify don't start the query when last query refresh time is not expired.
        setLastQueryTime(System.currentTimeMillis());
        mSatelliteEntitlementController.handleCmdStartQueryEntitlement();

        verify(mSatelliteEntitlementApi, never()).checkEntitlementStatus();
        verify(mSatelliteController, never()).onSatelliteEntitlementStatusUpdated(anyInt(),
                anyBoolean(), anyList(), anyList(), any());

        setLastQueryTime(0L);
        // Verify don't start the query when retry count is reached max
        setLastQueryTime(0L);
        Map<Integer, Integer> mRetryCountPerSub = new HashMap<>();
        mRetryCountPerSub.put(SUB_ID, MAX_RETRY_COUNT);
        replaceInstance(SatelliteEntitlementController.class, "mRetryCountPerSub",
                mSatelliteEntitlementController, mRetryCountPerSub);
        mSatelliteEntitlementController.handleCmdStartQueryEntitlement();

        verify(mSatelliteEntitlementApi, never()).checkEntitlementStatus();
        verify(mSatelliteController, never()).onSatelliteEntitlementStatusUpdated(anyInt(),
                anyBoolean(), anyList(), anyList(), any());

        replaceInstance(SatelliteEntitlementController.class, "mRetryCountPerSub",
                mSatelliteEntitlementController, new HashMap<>());

        // Verify don't start the query when query is in progressed.
        Map<Integer, Boolean> mIsEntitlementInProgressPerSub = new HashMap<>();
        mIsEntitlementInProgressPerSub.put(SUB_ID, true);
        replaceInstance(SatelliteEntitlementController.class, "mIsEntitlementInProgressPerSub",
                mSatelliteEntitlementController, mIsEntitlementInProgressPerSub);
        mSatelliteEntitlementController.handleCmdStartQueryEntitlement();

        verify(mSatelliteEntitlementApi, never()).checkEntitlementStatus();
        verify(mSatelliteController, never()).onSatelliteEntitlementStatusUpdated(anyInt(),
                anyBoolean(), anyList(), anyList(), any());

        replaceInstance(SatelliteEntitlementController.class, "mIsEntitlementInProgressPerSub",
                mSatelliteEntitlementController, new HashMap<>());
        // Verify the query starts when ShouldStartQueryEntitlement returns true.
        doReturn(mSatelliteEntitlementResult).when(
                mSatelliteEntitlementApi).checkEntitlementStatus();
        setSatelliteEntitlementResult(SATELLITE_ENTITLEMENT_STATUS_ENABLED, PLMN_ALLOWED_LIST,
                PLMN_BARRED_LIST);
        mSatelliteEntitlementController.handleCmdStartQueryEntitlement();

        verify(mSatelliteEntitlementApi).checkEntitlementStatus();
        verify(mSatelliteController).onSatelliteEntitlementStatusUpdated(anyInt(),
                anyBoolean(), anyList(), anyList(), any());
    }

    @Test
    public void testCheckSatelliteEntitlementStatus() throws Exception {
        logd("testCheckSatelliteEntitlementStatus");
        setIsQueryAvailableTrue();
        // Verify don't call the checkSatelliteEntitlementStatus when getActiveSubIdList is empty.
        doReturn(new int[]{}).when(mMockSubscriptionManagerService).getActiveSubIdList(true);
        mSatelliteEntitlementController.handleCmdStartQueryEntitlement();

        verify(mSatelliteEntitlementApi, never()).checkEntitlementStatus();
        // Verify don't call the updateSatelliteEntitlementStatus.
        verify(mSatelliteController, never()).onSatelliteEntitlementStatusUpdated(anyInt(),
                anyBoolean(), anyList(), anyList(), any());

        // Verify call the checkSatelliteEntitlementStatus with invalid response.
        setIsQueryAvailableTrue();
        doReturn(mSatelliteEntitlementResult).when(
                mSatelliteEntitlementApi).checkEntitlementStatus();
        replaceInstance(SatelliteEntitlementController.class,
                "mSatelliteEntitlementResultPerSub", mSatelliteEntitlementController,
                new HashMap<>());
        mSatelliteEntitlementController.handleCmdStartQueryEntitlement();

        verify(mSatelliteEntitlementApi).checkEntitlementStatus();
        // Verify call the updateSatelliteEntitlementStatus with satellite service is disabled
        // , empty PLMNAllowed and empty PLMNBarred.
        verify(mSatelliteController).onSatelliteEntitlementStatusUpdated(eq(SUB_ID),
                eq(false), eq(EMPTY_PLMN_LIST), eq(EMPTY_PLMN_LIST), any());

        // Verify call the checkSatelliteEntitlementStatus with the subscribed result.
        clearInvocationsForMock();
        setIsQueryAvailableTrue();
        doReturn(mSatelliteEntitlementResult).when(
                mSatelliteEntitlementApi).checkEntitlementStatus();
        setSatelliteEntitlementResult(SATELLITE_ENTITLEMENT_STATUS_ENABLED, PLMN_ALLOWED_LIST,
                PLMN_BARRED_LIST);
        mSatelliteEntitlementController.handleCmdStartQueryEntitlement();

        verify(mSatelliteEntitlementApi).checkEntitlementStatus();
        // Verify call the updateSatelliteEntitlementStatus with satellite service is enable,
        // availablePLMNAllowedList and availablePLMNBarredList.
        verify(mSatelliteController).onSatelliteEntitlementStatusUpdated(eq(SUB_ID), eq(true),
                eq(PLMN_ALLOWED_LIST), eq(PLMN_BARRED_LIST), any());

        // Change subId and verify call the updateSatelliteEntitlementStatus with satellite
        // service is enable, availablePLMNAllowedList and availablePLMNBarredList
        clearInvocationsForMock();
        doReturn(new int[]{SUB_ID_2}).when(mMockSubscriptionManagerService).getActiveSubIdList(
                true);
        mSatelliteEntitlementController.handleCmdStartQueryEntitlement();

        verify(mSatelliteEntitlementApi).checkEntitlementStatus();
        verify(mSatelliteController).onSatelliteEntitlementStatusUpdated(eq(SUB_ID_2), eq(true),
                eq(PLMN_ALLOWED_LIST), eq(PLMN_BARRED_LIST), any());

        // Verify call the updateSatelliteEntitlementStatus with satellite service is enable,
        // availablePLMNAllowedList and empty plmn barred list.
        clearInvocationsForMock();
        setIsQueryAvailableTrue();
        setSatelliteEntitlementResult(SATELLITE_ENTITLEMENT_STATUS_ENABLED, PLMN_ALLOWED_LIST,
                new ArrayList<>());
        mSatelliteEntitlementController.handleCmdStartQueryEntitlement();

        verify(mSatelliteEntitlementApi).checkEntitlementStatus();
        verify(mSatelliteController).onSatelliteEntitlementStatusUpdated(eq(SUB_ID), eq(true),
                eq(PLMN_ALLOWED_LIST), eq(EMPTY_PLMN_LIST), any());

        // Verify call the updateSatelliteEntitlementStatus with satellite service is enable,
        // empty PLMNAllowedList and PLMNBarredList.
        clearInvocationsForMock();
        setIsQueryAvailableTrue();
        setSatelliteEntitlementResult(SATELLITE_ENTITLEMENT_STATUS_ENABLED, new ArrayList<>(),
                new ArrayList<>());
        mSatelliteEntitlementController.handleCmdStartQueryEntitlement();

        verify(mSatelliteEntitlementApi).checkEntitlementStatus();
        verify(mSatelliteController).onSatelliteEntitlementStatusUpdated(eq(SUB_ID), eq(true),
                eq(EMPTY_PLMN_LIST), eq(EMPTY_PLMN_LIST), any());

        // Verify call the updateSatelliteEntitlementStatus with satellite service is enable,
        // empty PLMNAllowedList and availablePLMNBarredList.
        clearInvocationsForMock();
        setIsQueryAvailableTrue();
        setSatelliteEntitlementResult(SATELLITE_ENTITLEMENT_STATUS_ENABLED, new ArrayList<>(),
                PLMN_BARRED_LIST);
        mSatelliteEntitlementController.handleCmdStartQueryEntitlement();

        verify(mSatelliteEntitlementApi).checkEntitlementStatus();
        verify(mSatelliteController).onSatelliteEntitlementStatusUpdated(eq(SUB_ID), eq(true),
                eq(EMPTY_PLMN_LIST), eq(PLMN_BARRED_LIST), any());
    }

    @Test
    public void testCheckSatelliteEntitlementStatusWhenInternetConnected() throws Exception {
        logd("testCheckSatelliteEntitlementStatusWhenInternetConnected");
        ConnectivityManager.NetworkCallback networkCallback =
                (ConnectivityManager.NetworkCallback) getValue("mNetworkCallback");
        Network mockNetwork = mock(Network.class);

        // Verify the called the checkSatelliteEntitlementStatus when Internet is connected.
        setInternetConnected(true);
        setLastQueryTime(0L);
        setSatelliteEntitlementResult(SATELLITE_ENTITLEMENT_STATUS_ENABLED, PLMN_ALLOWED_LIST,
                PLMN_BARRED_LIST);

        networkCallback.onAvailable(mockNetwork);
        mTestableLooper.processAllMessages();

        verify(mSatelliteEntitlementApi).checkEntitlementStatus();
        // Verify call the updateSatelliteEntitlementStatus with satellite service is available.
        verify(mSatelliteController).onSatelliteEntitlementStatusUpdated(eq(SUB_ID), eq(true),
                eq(PLMN_ALLOWED_LIST), eq(PLMN_BARRED_LIST), any());
    }

    @Test
    public void testCheckSatelliteEntitlementStatusWhenCarrierConfigChanged() throws Exception {
        logd("testCheckSatelliteEntitlementStatusWhenCarrierConfigChanged");
        // Verify the called the checkSatelliteEntitlementStatus when CarrierConfigChanged
        // occurred and Internet is connected.
        setInternetConnected(true);
        setLastQueryTime(0L);
        setSatelliteEntitlementResult(SATELLITE_ENTITLEMENT_STATUS_ENABLED, PLMN_ALLOWED_LIST,
                PLMN_BARRED_LIST);
        triggerCarrierConfigChanged();

        verify(mSatelliteEntitlementApi).checkEntitlementStatus();
        // Verify call the updateSatelliteEntitlementStatus with satellite service is available.
        verify(mSatelliteController).onSatelliteEntitlementStatusUpdated(eq(SUB_ID), eq(true),
                eq(PLMN_ALLOWED_LIST), eq(PLMN_BARRED_LIST), any());
    }

    @Test
    public void testCheckWhenStartCmdIsReceivedDuringRetry() throws Exception {
        logd("testCheckWhenStartCmdIsReceivedDuringRetry");
        // Verify that start cmd is ignored and retry is performed up to 5 times when start cmd
        // occurs during retries.
        setIsQueryAvailableTrue();
        set503RetryAfterResponse();
        Map<Integer, Integer> retryCountPerSub =
                (Map<Integer, Integer>) getValue("mRetryCountPerSub");

        // Verify that the first query.
        sendMessage(CMD_START_QUERY_ENTITLEMENT, SUB_ID);
        mTestableLooper.processAllMessages();
        verify(mSatelliteEntitlementApi, times(1)).checkEntitlementStatus();
        // Verify that the retry count is 0 after receiving a 503 with retry-after header in
        // response.
        assertTrue(retryCountPerSub.getOrDefault(SUB_ID, 0) == 0);

        // Verify that the retry count is 1 for the second query when receiving a 503 with
        // retry-after header in response.
        mTestableLooper.moveTimeForward(TimeUnit.SECONDS.toMillis(1));
        mTestableLooper.processAllMessages();
        verify(mSatelliteEntitlementApi, times(2)).checkEntitlementStatus();
        assertTrue(retryCountPerSub.get(SUB_ID) == 1);

        // Verify that the retry count is 2 for the third query when receiving a 503 with
        // retry-after header in response.
        mTestableLooper.moveTimeForward(TimeUnit.SECONDS.toMillis(1));
        mTestableLooper.processAllMessages();
        verify(mSatelliteEntitlementApi, times(3)).checkEntitlementStatus();
        assertTrue(retryCountPerSub.get(SUB_ID) == 2);

        // Verify that start CMD is ignored during retries.
        sendMessage(CMD_START_QUERY_ENTITLEMENT, SUB_ID);
        mTestableLooper.processAllMessages();
        verify(mSatelliteEntitlementApi, times(3)).checkEntitlementStatus();
        assertTrue(retryCountPerSub.get(SUB_ID) == 2);

        // Verify that the retry count is 3 for the forth query when receiving a 503 with
        // retry-after header in response.
        mTestableLooper.moveTimeForward(TimeUnit.SECONDS.toMillis(1));
        mTestableLooper.processAllMessages();
        verify(mSatelliteEntitlementApi, times(4)).checkEntitlementStatus();
        assertTrue(retryCountPerSub.get(SUB_ID) == 3);

        // Verify that the retry count is 4 for the fifth query when receiving a 503 with
        // retry-after header in response.
        mTestableLooper.moveTimeForward(TimeUnit.SECONDS.toMillis(1));
        mTestableLooper.processAllMessages();
        verify(mSatelliteEntitlementApi, times(5)).checkEntitlementStatus();
        assertTrue(retryCountPerSub.get(SUB_ID) == 4);

        // Verify that start CMD is ignored during retries.
        sendMessage(CMD_START_QUERY_ENTITLEMENT, SUB_ID);
        mTestableLooper.processAllMessages();
        verify(mSatelliteEntitlementApi, times(5)).checkEntitlementStatus();
        assertTrue(retryCountPerSub.get(SUB_ID) == 4);

        // Verify that the retry count is 5 for the sixth query when receiving a 503 with
        // retry-after header in response.
        mTestableLooper.moveTimeForward(TimeUnit.SECONDS.toMillis(1));
        mTestableLooper.processAllMessages();
        verify(mSatelliteEntitlementApi, times(6)).checkEntitlementStatus();
        assertNull(retryCountPerSub.get(SUB_ID));

        // Verify only called onSatelliteEntitlementStatusUpdated once.
        verify(mSatelliteController, times(1)).onSatelliteEntitlementStatusUpdated(eq(SUB_ID),
                eq(false), eq(EMPTY_PLMN_LIST), eq(EMPTY_PLMN_LIST), any());

        // Verify that the query is not restarted after reaching the maximum retry count even if
        // a start cmd is received.
        sendMessage(CMD_START_QUERY_ENTITLEMENT, SUB_ID);
        mTestableLooper.processAllMessages();
        verify(mSatelliteEntitlementApi, times(6)).checkEntitlementStatus();
        assertNull(retryCountPerSub.get(SUB_ID));

        // Verify that the query is not restarted after reaching the maximum retry count even if
        // a start cmd is received.
        sendMessage(CMD_RETRY_QUERY_ENTITLEMENT, SUB_ID);
        mTestableLooper.processAllMessages();
        verify(mSatelliteEntitlementApi, times(6)).checkEntitlementStatus();
        assertNull(retryCountPerSub.get(SUB_ID));
    }

    @Test
    public void testCheckAfterInternetConnectionChangedDuringRetry() throws Exception {
        logd("testCheckAfterInternetConnectionChangedDuringRetry");
        // Verify that the retry count is maintained even when internet connection is lost and
        // connected during retries, and that up to 5 retries are performed.
        setIsQueryAvailableTrue();
        set503RetryAfterResponse();
        Map<Integer, Integer> retryCountPerSub =
                (Map<Integer, Integer>) getValue("mRetryCountPerSub");

        // Verify that the first query.
        sendMessage(CMD_START_QUERY_ENTITLEMENT, SUB_ID);
        mTestableLooper.processAllMessages();
        verify(mSatelliteEntitlementApi, times(1)).checkEntitlementStatus();
        // Verify that the retry count is 0 after receiving a 503 with retry-after header in
        // response.
        assertTrue(retryCountPerSub.getOrDefault(SUB_ID, 0) == 0);

        // Verify that the retry count is 1 for the second query when receiving a 503 with
        // retry-after header in response.
        mTestableLooper.moveTimeForward(TimeUnit.SECONDS.toMillis(1));
        mTestableLooper.processAllMessages();
        verify(mSatelliteEntitlementApi, times(2)).checkEntitlementStatus();
        assertTrue(retryCountPerSub.get(SUB_ID) == 1);

        // Verify that no query is executed and the retry count does not increase when internet
        // connection is lost during the second retry.
        setInternetConnected(false);
        mTestableLooper.moveTimeForward(TimeUnit.SECONDS.toMillis(1));
        mTestableLooper.processAllMessages();
        verify(mSatelliteEntitlementApi, times(2)).checkEntitlementStatus();
        assertTrue(retryCountPerSub.get(SUB_ID) == 1);

        // Verify that the query is started when internet connection is restored and that the
        // retry count does not increase.
        setInternetConnected(true);
        logd("internet connected again");
        sendMessage(CMD_START_QUERY_ENTITLEMENT, SUB_ID);
        mTestableLooper.processAllMessages();
        verify(mSatelliteEntitlementApi, times(3)).checkEntitlementStatus();
        assertTrue(retryCountPerSub.get(SUB_ID) == 1);

        // Verify that the retry count is increases after received a 503 with retry-after header
        // in response.
        mTestableLooper.moveTimeForward(TimeUnit.SECONDS.toMillis(1));
        mTestableLooper.processAllMessages();
        verify(mSatelliteEntitlementApi, times(4)).checkEntitlementStatus();
        assertTrue(retryCountPerSub.get(SUB_ID) == 2);

        mTestableLooper.moveTimeForward(TimeUnit.SECONDS.toMillis(1));
        mTestableLooper.processAllMessages();
        verify(mSatelliteEntitlementApi, times(5)).checkEntitlementStatus();
        assertTrue(retryCountPerSub.get(SUB_ID) == 3);

        mTestableLooper.moveTimeForward(TimeUnit.SECONDS.toMillis(1));
        mTestableLooper.processAllMessages();
        verify(mSatelliteEntitlementApi, times(6)).checkEntitlementStatus();
        assertTrue(retryCountPerSub.get(SUB_ID) == 4);

        mTestableLooper.moveTimeForward(TimeUnit.SECONDS.toMillis(1));
        mTestableLooper.processAllMessages();
        verify(mSatelliteEntitlementApi, times(7)).checkEntitlementStatus();
        assertNull(retryCountPerSub.get(SUB_ID));

        // Verify that the query is not restarted after reaching the maximum retry count even if
        // a start cmd is received.
        sendMessage(CMD_START_QUERY_ENTITLEMENT, SUB_ID);
        mTestableLooper.processAllMessages();
        verify(mSatelliteEntitlementApi, times(7)).checkEntitlementStatus();
        assertNull(retryCountPerSub.get(SUB_ID));

        // Verify that the query is not restarted after reaching the maximum retry count even if
        // a retry cmd is received.
        sendMessage(CMD_START_QUERY_ENTITLEMENT, SUB_ID);
        mTestableLooper.processAllMessages();
        verify(mSatelliteEntitlementApi, times(7)).checkEntitlementStatus();
        assertNull(retryCountPerSub.get(SUB_ID));

        // Verify only called onSatelliteEntitlementStatusUpdated once.
        verify(mSatelliteController, times(1)).onSatelliteEntitlementStatusUpdated(eq(SUB_ID),
                eq(false), eq(EMPTY_PLMN_LIST), eq(EMPTY_PLMN_LIST), any());
    }

    @Test
    public void testStartQueryEntitlementStatus_error500() throws Exception {
        logd("testStartQueryEntitlementStatus_error500");
        setIsQueryAvailableTrue();
        Map<Integer, Integer> retryCountPerSub =
                (Map<Integer, Integer>) getValue("mRetryCountPerSub");
        setErrorResponse(500);

        sendMessage(CMD_START_QUERY_ENTITLEMENT, SUB_ID);
        mTestableLooper.processAllMessages();
        verify(mSatelliteEntitlementApi, times(1)).checkEntitlementStatus();
        assertNull(retryCountPerSub.get(SUB_ID));
        verify(mSatelliteController, times(1)).onSatelliteEntitlementStatusUpdated(eq(SUB_ID),
                eq(false), eq(EMPTY_PLMN_LIST), eq(EMPTY_PLMN_LIST), any());
    }

    @Test
    public void testStartQueryEntitlementStatus_error503_retrySuccess() throws Exception {
        logd("testStartQueryEntitlementStatus_error503_retrySuccess");
        setIsQueryAvailableTrue();
        set503RetryAfterResponse();
        Map<Integer, Integer> retryCountPerSub =
                (Map<Integer, Integer>) getValue("mRetryCountPerSub");

        // Verify that the first query.
        sendMessage(CMD_START_QUERY_ENTITLEMENT, SUB_ID);
        mTestableLooper.processAllMessages();
        verify(mSatelliteEntitlementApi, times(1)).checkEntitlementStatus();
        assertNull(retryCountPerSub.get(SUB_ID));

        // Verify whether the query has been retried and verify called
        // onSatelliteEntitlementStatusUpdated after receive a success case.
        doReturn(mSatelliteEntitlementResult).when(
                mSatelliteEntitlementApi).checkEntitlementStatus();
        setSatelliteEntitlementResult(SATELLITE_ENTITLEMENT_STATUS_ENABLED, PLMN_ALLOWED_LIST,
                PLMN_BARRED_LIST);
        mTestableLooper.moveTimeForward(TimeUnit.SECONDS.toMillis(1));
        mTestableLooper.processAllMessages();
        verify(mSatelliteEntitlementApi, times(2)).checkEntitlementStatus();
        assertNull(retryCountPerSub.get(SUB_ID));
        verify(mSatelliteController).onSatelliteEntitlementStatusUpdated(eq(SUB_ID), eq(true),
                eq(PLMN_ALLOWED_LIST), eq(PLMN_BARRED_LIST), any());
    }

    @Test
    public void testStartQueryEntitlementStatus_otherError_retrySuccess() throws Exception {
        logd("testStartQueryEntitlementStatus_otherError_retrySuccess");
        setIsQueryAvailableTrue();
        Map<Integer, Integer> retryCountPerSub =
                (Map<Integer, Integer>) getValue("mRetryCountPerSub");
        Map<Integer, Boolean> isEntitlementInProgressPerSub =
                (Map<Integer, Boolean>) getValue("mIsEntitlementInProgressPerSub");
        Map<Integer, ExponentialBackoff> exponentialBackoffPerSub =
                (Map<Integer, ExponentialBackoff>) getValue("mExponentialBackoffPerSub");
        setErrorResponse(400);

        // Verify start the exponentialBackoff.
        sendMessage(CMD_START_QUERY_ENTITLEMENT, SUB_ID);
        mTestableLooper.processAllMessages();
        verify(mSatelliteEntitlementApi, times(1)).checkEntitlementStatus();
        assertNull(retryCountPerSub.get(SUB_ID));
        assertTrue(isEntitlementInProgressPerSub.get(SUB_ID));
        assertNotNull(exponentialBackoffPerSub.get(SUB_ID));
        // Verify don't call the onSatelliteEntitlementStatusUpdated.
        verify(mSatelliteController, never()).onSatelliteEntitlementStatusUpdated(anyInt(),
                anyBoolean(), anyList(), anyList(), any());

        // Verify the retry in progress.
        sendMessage(CMD_RETRY_QUERY_ENTITLEMENT, SUB_ID);
        mTestableLooper.processAllMessages();
        verify(mSatelliteEntitlementApi, times(2)).checkEntitlementStatus();
        assertTrue(retryCountPerSub.get(SUB_ID) == 1);
        // Verify don't call the onSatelliteEntitlementStatusUpdated.
        verify(mSatelliteController, never()).onSatelliteEntitlementStatusUpdated(anyInt(),
                anyBoolean(), anyList(), anyList(), any());

        // Received the 200 response, Verify call the onSatelliteEntitlementStatusUpdated.
        setIsQueryAvailableTrue();
        doReturn(mSatelliteEntitlementResult).when(
                mSatelliteEntitlementApi).checkEntitlementStatus();
        setSatelliteEntitlementResult(SATELLITE_ENTITLEMENT_STATUS_ENABLED, PLMN_ALLOWED_LIST,
                PLMN_BARRED_LIST);

        sendMessage(CMD_RETRY_QUERY_ENTITLEMENT, SUB_ID);
        mTestableLooper.processAllMessages();
        verify(mSatelliteEntitlementApi, times(3)).checkEntitlementStatus();
        assertTrue(retryCountPerSub.get(SUB_ID) == 1);
        verify(mSatelliteController).onSatelliteEntitlementStatusUpdated(eq(SUB_ID), eq(true),
                eq(PLMN_ALLOWED_LIST), eq(PLMN_BARRED_LIST), any());
    }

    @Test
    public void testSatelliteEntitlementSupportedChangedFromSupportToNotSupport() throws Exception {
        logd("testSatelliteEntitlementSupportedChangedFromSupportToNotSupport");
        setIsQueryAvailableTrue();

        // KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL changed from Support(entitlement status
        // disabled) to not support.
        doReturn(mSatelliteEntitlementResult).when(
                mSatelliteEntitlementApi).checkEntitlementStatus();
        setSatelliteEntitlementResult(SATELLITE_ENTITLEMENT_STATUS_DISABLED, EMPTY_PLMN_LIST,
                EMPTY_PLMN_LIST);
        sendMessage(CMD_START_QUERY_ENTITLEMENT, SUB_ID);
        mTestableLooper.processAllMessages();

        // Verify call the onSatelliteEntitlementStatusUpdated - entitlement status false
        verify(mSatelliteEntitlementApi).checkEntitlementStatus();
        verify(mSatelliteController).onSatelliteEntitlementStatusUpdated(anyInt(),
                eq(false), eq(EMPTY_PLMN_LIST), eq(EMPTY_PLMN_LIST), any());

        // Verify call the onSatelliteEntitlementStatusUpdated - entitlement status true
        mCarrierConfigBundle.putBoolean(
                CarrierConfigManager.KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL, false);
        sendMessage(CMD_START_QUERY_ENTITLEMENT, SUB_ID);
        mTestableLooper.processAllMessages();

        verify(mSatelliteController).onSatelliteEntitlementStatusUpdated(anyInt(),
                eq(true), eq(EMPTY_PLMN_LIST), eq(EMPTY_PLMN_LIST), any());

        // KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL changed from Support(entitlement status
        // enabled) to not support.
        mCarrierConfigBundle.putBoolean(
                CarrierConfigManager.KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL, true);
        doReturn(mSatelliteEntitlementResult).when(
                mSatelliteEntitlementApi).checkEntitlementStatus();
        setSatelliteEntitlementResult(SATELLITE_ENTITLEMENT_STATUS_ENABLED, PLMN_ALLOWED_LIST,
                PLMN_BARRED_LIST);
        sendMessage(CMD_START_QUERY_ENTITLEMENT, SUB_ID);
        mTestableLooper.processAllMessages();

        // Verify call the onSatelliteEntitlementStatusUpdated - entitlement status true.
        verify(mSatelliteEntitlementApi, times(2)).checkEntitlementStatus();
        verify(mSatelliteController).onSatelliteEntitlementStatusUpdated(anyInt(),
                eq(true), eq(PLMN_ALLOWED_LIST), eq(PLMN_BARRED_LIST), any());

        // Verify not call the onSatelliteEntitlementStatusUpdated.
        clearInvocationsForMock();
        mCarrierConfigBundle.putBoolean(
                CarrierConfigManager.KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL, false);
        sendMessage(CMD_START_QUERY_ENTITLEMENT, SUB_ID);
        mTestableLooper.processAllMessages();

        verify(mSatelliteController, never()).onSatelliteEntitlementStatusUpdated(anyInt(),
                eq(true), eq(EMPTY_PLMN_LIST), eq(EMPTY_PLMN_LIST), any());
    }

    @Test
    public void testStartQueryEntitlementStatus_refreshStatus() throws Exception {
        logd("testStartQueryEntitlementStatus_refreshStatus");
        setIsQueryAvailableTrue();
        mCarrierConfigBundle.putInt(
                CarrierConfigManager.KEY_SATELLITE_ENTITLEMENT_STATUS_REFRESH_DAYS_INT, 1);

        // Verify start query and success.
        doReturn(mSatelliteEntitlementResult).when(
                mSatelliteEntitlementApi).checkEntitlementStatus();
        setSatelliteEntitlementResult(SATELLITE_ENTITLEMENT_STATUS_ENABLED, PLMN_ALLOWED_LIST,
                PLMN_BARRED_LIST);
        sendMessage(CMD_START_QUERY_ENTITLEMENT, SUB_ID);
        mTestableLooper.processAllMessages();

        verify(mSatelliteEntitlementApi).checkEntitlementStatus();
        verify(mSatelliteController).onSatelliteEntitlementStatusUpdated(anyInt(),
                anyBoolean(), anyList(), anyList(), any());

        // After move to the refresh time, verify the query started and success.
        setLastQueryTime(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1) - 1000);
        mTestableLooper.moveTimeForward(TimeUnit.DAYS.toMillis(1));
        mTestableLooper.processAllMessages();

        verify(mSatelliteEntitlementApi, times(2)).checkEntitlementStatus();
        verify(mSatelliteController, times(2)).onSatelliteEntitlementStatusUpdated(anyInt(),
                anyBoolean(), anyList(), anyList(), any());
    }

    @Test
    public void testStartQueryEntitlementStatus_internetDisconnectedAndConnectedAgain()
            throws Exception {
        logd("testStartQueryEntitlementStatus_internetDisconnectedAndConnectedAgain");
        setIsQueryAvailableTrue();

        // Verify the query does not start if there is no internet connection.
        setInternetConnected(false);
        sendMessage(CMD_START_QUERY_ENTITLEMENT, SUB_ID);
        mTestableLooper.processAllMessages();

        verify(mSatelliteEntitlementApi, never()).checkEntitlementStatus();
        verify(mSatelliteController, never()).onSatelliteEntitlementStatusUpdated(anyInt(),
                anyBoolean(), anyList(), anyList(), any());

        // Verify the query start and success after internet connected.
        setInternetConnected(true);
        doReturn(mSatelliteEntitlementResult).when(
                mSatelliteEntitlementApi).checkEntitlementStatus();
        setSatelliteEntitlementResult(SATELLITE_ENTITLEMENT_STATUS_ENABLED, PLMN_ALLOWED_LIST,
                PLMN_BARRED_LIST);
        sendMessage(CMD_START_QUERY_ENTITLEMENT, SUB_ID);
        mTestableLooper.processAllMessages();

        verify(mSatelliteEntitlementApi).checkEntitlementStatus();
        verify(mSatelliteController).onSatelliteEntitlementStatusUpdated(eq(SUB_ID), eq(true),
                eq(PLMN_ALLOWED_LIST), eq(PLMN_BARRED_LIST), any());
    }

    @Test
    public void testStartQueryEntitlementStatus_error503_error500() throws Exception {
        logd("testStartQueryEntitlementStatus_error503_error500");
        setIsQueryAvailableTrue();
        set503RetryAfterResponse();

        // Verify that the first query was triggered and that onSatelliteEntitlementStatusUpdated
        // was not called after received a 503 error.
        sendMessage(CMD_START_QUERY_ENTITLEMENT, SUB_ID);
        mTestableLooper.processAllMessages();
        verify(mSatelliteEntitlementApi).checkEntitlementStatus();
        verify(mSatelliteController, never()).onSatelliteEntitlementStatusUpdated(anyInt(),
                anyBoolean(), anyList(), anyList(), any());

        // Verify whether the second query has been triggered and whether
        // onSatelliteEntitlementStatusUpdated has been called after received the 500 error.
        reset(mSatelliteEntitlementApi);
        setErrorResponse(500);
        mTestableLooper.moveTimeForward(TimeUnit.SECONDS.toMillis(1));
        mTestableLooper.processAllMessages();
        verify(mSatelliteEntitlementApi).checkEntitlementStatus();
        verify(mSatelliteController).onSatelliteEntitlementStatusUpdated(eq(SUB_ID),
                eq(false), eq(EMPTY_PLMN_LIST), eq(EMPTY_PLMN_LIST), any());
    }

    @Test
    public void testStartQueryEntitlementStatus_error503_otherError() throws Exception {
        logd("testStartQueryEntitlementStatus_error503_otherError");
        setIsQueryAvailableTrue();
        set503RetryAfterResponse();

        // Verify that the first query was triggered and that onSatelliteEntitlementStatusUpdated
        // was not called after received a 503 error.
        sendMessage(CMD_START_QUERY_ENTITLEMENT, SUB_ID);
        mTestableLooper.processAllMessages();
        verify(mSatelliteEntitlementApi).checkEntitlementStatus();
        verify(mSatelliteController, never()).onSatelliteEntitlementStatusUpdated(anyInt(),
                anyBoolean(), anyList(), anyList(), any());

        // Verify whether the second query was triggered and onSatelliteEntitlementStatusUpdated
        // was not called after received a 503 error without valid retry-after header.
        reset(mSatelliteEntitlementApi);
        setErrorResponse(503);
        mTestableLooper.moveTimeForward(TimeUnit.SECONDS.toMillis(1));
        mTestableLooper.processAllMessages();
        verify(mSatelliteEntitlementApi).checkEntitlementStatus();
        verify(mSatelliteController, never()).onSatelliteEntitlementStatusUpdated(anyInt(),
                anyBoolean(), anyList(), anyList(), any());

        // Verify whether the third query was triggered and onSatelliteEntitlementStatusUpdated
        // was called after received a success case.
        doReturn(mSatelliteEntitlementResult).when(
                mSatelliteEntitlementApi).checkEntitlementStatus();
        setSatelliteEntitlementResult(SATELLITE_ENTITLEMENT_STATUS_ENABLED, PLMN_ALLOWED_LIST,
                PLMN_BARRED_LIST);
        mTestableLooper.moveTimeForward(TimeUnit.MINUTES.toMillis(10));
        mTestableLooper.processAllMessages();

        verify(mSatelliteEntitlementApi, times(2)).checkEntitlementStatus();
        verify(mSatelliteController).onSatelliteEntitlementStatusUpdated(eq(SUB_ID), eq(true),
                eq(PLMN_ALLOWED_LIST), eq(PLMN_BARRED_LIST), any());
    }

    @Test
    public void testStartQueryEntitlementStatus_AfterSimRefresh() throws Exception {
        logd("testStartQueryEntitlementStatus_AfterSimRefresh");
        setIsQueryAvailableTrue();

        // Verify the first query complete.
        doReturn(mSatelliteEntitlementResult).when(
                mSatelliteEntitlementApi).checkEntitlementStatus();
        setSatelliteEntitlementResult(SATELLITE_ENTITLEMENT_STATUS_ENABLED, PLMN_ALLOWED_LIST,
                PLMN_BARRED_LIST);
        mSatelliteEntitlementController.handleCmdStartQueryEntitlement();

        verify(mSatelliteEntitlementApi).checkEntitlementStatus();
        verify(mSatelliteController).onSatelliteEntitlementStatusUpdated(anyInt(),
                anyBoolean(), anyList(), anyList(), any());

        // SIM_REFRESH event occurred before expired the query refresh timer, verify the start
        // the query.
        sendMessage(CMD_SIM_REFRESH, SUB_ID);
        mTestableLooper.moveTimeForward(TimeUnit.MINUTES.toMillis(10));
        mTestableLooper.processAllMessages();

        verify(mSatelliteEntitlementApi, times(2)).checkEntitlementStatus();
        verify(mSatelliteController, times(2)).onSatelliteEntitlementStatusUpdated(anyInt(),
                anyBoolean(), anyList(), anyList(), any());
    }

    private void triggerCarrierConfigChanged() {
        for (Pair<Executor, CarrierConfigManager.CarrierConfigChangeListener> pair
                : mCarrierConfigChangedListenerList) {
            pair.first.execute(() -> pair.second.onCarrierConfigChanged(
                    /*slotIndex*/ 0, /*subId*/ SUB_ID, /*carrierId*/ 0, /*specificCarrierId*/ 0)
            );
        }
        mTestableLooper.processAllMessages();
    }

    private void triggerCarrierConfigChanged(int subId) {
        for (Pair<Executor, CarrierConfigManager.CarrierConfigChangeListener> pair
                : mCarrierConfigChangedListenerList) {
            pair.first.execute(() -> pair.second.onCarrierConfigChanged(
                    /*slotIndex*/ 0, /*subId*/ subId, /*carrierId*/ 0, /*specificCarrierId*/ 0)
            );
        }
        mTestableLooper.processAllMessages();
    }

    private void clearInvocationsForMock() {
        clearInvocations(mSatelliteEntitlementApi);
        clearInvocations(mSatelliteController);
    }

    private void setIsQueryAvailableTrue() throws Exception {
        doReturn(ACTIVE_SUB_ID).when(mMockSubscriptionManagerService).getActiveSubIdList(true);
        mCarrierConfigBundle.putBoolean(
                CarrierConfigManager.KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL, true);
        replaceInstance(SatelliteEntitlementController.class, "mRetryCountPerSub",
                mSatelliteEntitlementController, new HashMap<>());
        replaceInstance(SatelliteEntitlementController.class, "mIsEntitlementInProgressPerSub",
                mSatelliteEntitlementController, new HashMap<>());
        setInternetConnected(true);
        setLastQueryTime(0L);
        replaceInstance(SatelliteEntitlementController.class,
                "mSatelliteEntitlementResultPerSub", mSatelliteEntitlementController,
                new HashMap<>());
        replaceInstance(SatelliteEntitlementController.class,
                "mSubIdPerSlot", mSatelliteEntitlementController, new HashMap<>());
    }

    private void setInternetConnected(boolean connected) {
        NetworkCapabilities networkCapabilities = new NetworkCapabilities.Builder().build();

        if (connected) {
            networkCapabilities = new NetworkCapabilities.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setTransportInfo(mock(WifiInfo.class))
                    .build();
        }
        doReturn(networkCapabilities).when(mConnectivityManager).getNetworkCapabilities(mNetwork);
    }

    private void setSatelliteEntitlementResult(int entitlementStatus,
            List<String> plmnAllowedList, List<String> plmnBarredList) {
        doReturn(entitlementStatus).when(mSatelliteEntitlementResult).getEntitlementStatus();
        doReturn(plmnAllowedList).when(mSatelliteEntitlementResult).getAllowedPLMNList();
        doReturn(plmnBarredList).when(mSatelliteEntitlementResult).getBarredPLMNList();
    }

    private void setLastQueryTime(Long lastQueryTime) throws Exception {
        Map<Integer, Long> lastQueryTimePerSub = new HashMap<>();
        replaceInstance(SatelliteEntitlementController.class, "mLastQueryTimePerSub",
                mSatelliteEntitlementController, lastQueryTimePerSub);
        lastQueryTimePerSub.put(SUB_ID, lastQueryTime);
    }

    private void set503RetryAfterResponse() throws Exception {
        when(mSatelliteEntitlementApi.checkEntitlementStatus()).thenAnswer(
                new Answer() {
                    @Override
                    public Object answer(InvocationOnMock invocation) throws Throwable {
                        throw new ServiceEntitlementException(
                                ERROR_HTTP_STATUS_NOT_SUCCESS, 503, "1", "503 occurred");
                    }
                }
        );
    }

    private void setErrorResponse(int errorCode) throws Exception {
        when(mSatelliteEntitlementApi.checkEntitlementStatus()).thenAnswer(
                new Answer() {
                    @Override
                    public Object answer(InvocationOnMock invocation) throws Throwable {
                        throw new ServiceEntitlementException(
                                ERROR_HTTP_STATUS_NOT_SUCCESS, errorCode, "",
                                errorCode + " occurred");
                    }
                }
        );
    }

    private void sendMessage(int what, int subId) {
        mSatelliteEntitlementController.handleMessage(mHandler.obtainMessage(what, subId, 0));
    }

    private Object getValue(String originalObjectName) throws Exception {
        Field field = SatelliteEntitlementController.class.getDeclaredField(originalObjectName);
        field.setAccessible(true);
        return field.get(mSatelliteEntitlementController);
    }

    public static class TestSatelliteEntitlementController extends SatelliteEntitlementController {
        private SatelliteEntitlementApi mInjectSatelliteEntitlementApi;

        TestSatelliteEntitlementController(@NonNull Context context, @NonNull Looper looper,
                SatelliteEntitlementApi api) {
            super(context, looper);
            mInjectSatelliteEntitlementApi = api;
        }

        @Override
        public SatelliteEntitlementApi getSatelliteEntitlementApi(int subId) {
            logd("getSatelliteEntitlementApi");
            return mInjectSatelliteEntitlementApi;
        }
    }

    private static void logd(String log) {
        Log.d(TAG, log);
    }
}
