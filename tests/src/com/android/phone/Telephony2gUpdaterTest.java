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

import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Intent;
import android.os.UserManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.TelephonyTestBase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class Telephony2gUpdaterTest extends TelephonyTestBase {
    private static final long DRAIN_TIMEOUT = 10;
    private Telephony2gUpdater mTelephony2gUpdater;
    private SubscriptionManager.OnSubscriptionsChangedListener mChangedListener;
    private Executor mExecutor;
    private CountDownLatch mLatch;

    private UserManager mMockUserManager;
    private TelephonyManager mMockTelephonyManager;
    private SubscriptionManager mMockSubscriptionManager;

    // Set up to be returned from mMockSubscriptionManager.getCompleteActiveSubscriptionInfoList()
    // Updates will be reflected in subsequent calls to the mock method.
    private List<SubscriptionInfo> mCurrentSubscriptions;

    // 2G Bitmask is 0b10000000_01001011
    private static final long BASE_NETWORK = 0b11111111_11111111;
    private static final long EXPECTED_DISABLED = 0b01111111_10110100;
    private static final long EXPECTED_ENABLED = 0b11111111_11111111;


    @Before
    public void setUp() throws Exception {
        super.setUp();

        mMockTelephonyManager = mContext.getSystemService(TelephonyManager.class);
        mMockUserManager = mContext.getSystemService(UserManager.class);
        mMockSubscriptionManager = mContext.getSystemService(SubscriptionManager.class);

        mCurrentSubscriptions = new ArrayList<>();
        setupMutableSubscriptionInfoMock();

        mExecutor = Executors.newSingleThreadExecutor();
        mTelephony2gUpdater = new Telephony2gUpdater(mExecutor, getTestContext(), BASE_NETWORK);
        mTelephony2gUpdater.init();
        ArgumentCaptor<SubscriptionManager.OnSubscriptionsChangedListener> argument =
                ArgumentCaptor.forClass(SubscriptionManager.OnSubscriptionsChangedListener.class);
        verify(mMockSubscriptionManager).addOnSubscriptionsChangedListener(any(Executor.class),
                argument.capture());
        mChangedListener = argument.getValue();
    }

    @Test
    public void onSubscriptionsChanged_noSubscriptions_noAllowedNetworksChanged() {
        triggerOnSubscriptionChangedAndWait();
        verify(mMockTelephonyManager, never()).setAllowedNetworkTypesForReason(anyInt(), anyInt());
    }

    @Test
    public void onSubscriptionsChanged_oneSubscription_allowedNetworksUpdated() {
        TelephonyManager tmSubscription1 = addSubscriptionAndGetMock(1001);
        triggerOnSubscriptionChangedAndWait();

        verify(tmSubscription1, times(1)).setAllowedNetworkTypesForReason(
                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER_RESTRICTIONS, EXPECTED_ENABLED);
    }

    @Test
    public void onSubscriptionsChanged_manySubscriptionsDisallow2g_allowedNetworkUpdated() {
        // 2g is disallowed
        when(mMockUserManager.hasUserRestriction(UserManager.DISALLOW_CELLULAR_2G)).thenReturn(
                true);
        triggerBroadcastReceiverAndWait();

        TelephonyManager tmSubscription1 = addSubscriptionAndGetMock(1001);
        TelephonyManager tmSubscription2 = addSubscriptionAndGetMock(1002);

        triggerOnSubscriptionChangedAndWait();

        verify(tmSubscription1, times(1)).setAllowedNetworkTypesForReason(
                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER_RESTRICTIONS, EXPECTED_DISABLED);
        verify(tmSubscription2, times(1)).setAllowedNetworkTypesForReason(
                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER_RESTRICTIONS, EXPECTED_DISABLED);
    }

    @Test
    public void onSubscriptionsChanged_noNewSubscriptions_noAllowedNetworksChanged() {
        when(mMockUserManager.hasUserRestriction(UserManager.DISALLOW_CELLULAR_2G)).thenReturn(
                true);
        triggerBroadcastReceiverAndWait();

        TelephonyManager tmSubscription1 = addSubscriptionAndGetMock(1001);

        triggerOnSubscriptionChangedAndWait();
        triggerOnSubscriptionChangedAndWait();

        // subscriptions were updated twice, but we have no new subIds so we only expect one update
        verify(tmSubscription1, times(1)).setAllowedNetworkTypesForReason(
                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER_RESTRICTIONS, EXPECTED_DISABLED);
    }

    @Test
    public void onSubscriptionsChanged_removeSubscription_noAdditionalNetworkChanges() {
        // We start with 2 subIds
        TelephonyManager tmSubscription1 = addSubscriptionAndGetMock(1001);
        TelephonyManager tmSubscription2 = addSubscriptionAndGetMock(1002);

        triggerOnSubscriptionChangedAndWait();

        // 2g is still enabled since the default is to not set the user restriction
        verify(tmSubscription1, times(1)).setAllowedNetworkTypesForReason(
                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER_RESTRICTIONS, EXPECTED_ENABLED);
        verify(tmSubscription2, times(1)).setAllowedNetworkTypesForReason(
                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER_RESTRICTIONS, EXPECTED_ENABLED);


        mCurrentSubscriptions.remove(1);
        triggerOnSubscriptionChangedAndWait();

        // Subscriptions have changed, but we've only removed a subscription so there should be no
        // extra updates to allowed network types
        verify(tmSubscription1, times(1)).setAllowedNetworkTypesForReason(
                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER_RESTRICTIONS, EXPECTED_ENABLED);
        verify(tmSubscription2, times(1)).setAllowedNetworkTypesForReason(
                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER_RESTRICTIONS, EXPECTED_ENABLED);
    }

    @Test
    public void onSubscriptionsChanged_removeSubscriptionAndReAdd() {
        when(mMockUserManager.hasUserRestriction(UserManager.DISALLOW_CELLULAR_2G)).thenReturn(
                true);
        triggerBroadcastReceiverAndWait();

        TelephonyManager tmSubscription1 = addSubscriptionAndGetMock(1001);
        triggerOnSubscriptionChangedAndWait();
        mCurrentSubscriptions.remove(0);
        triggerOnSubscriptionChangedAndWait();
        mCurrentSubscriptions.add(getSubInfo(1001));
        triggerOnSubscriptionChangedAndWait();

        // subscriptions were updated thrice, but one of those updates removed a subscription
        // such that the sub list was empty, so we only expect an update on the first and last
        // updates.
        verify(tmSubscription1, times(2)).setAllowedNetworkTypesForReason(
                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER_RESTRICTIONS, EXPECTED_DISABLED);
    }

    @Test
    public void onSubscriptionsChanged_addSubscription_updateAllowedNetworks() {
        // We start with 2 subIds and update subscriptions
        TelephonyManager tmSubscription1 = addSubscriptionAndGetMock(1001);
        TelephonyManager tmSubscription2 = addSubscriptionAndGetMock(1002);
        triggerOnSubscriptionChangedAndWait();

        // Then add a subId and update subscriptions again
        TelephonyManager tmSubscription3 = addSubscriptionAndGetMock(1003);
        triggerOnSubscriptionChangedAndWait();

        // we only need to update the new subscription
        verify(tmSubscription1, times(1)).setAllowedNetworkTypesForReason(
                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER_RESTRICTIONS, EXPECTED_ENABLED);
        verify(tmSubscription2, times(1)).setAllowedNetworkTypesForReason(
                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER_RESTRICTIONS, EXPECTED_ENABLED);
        verify(tmSubscription3, times(1)).setAllowedNetworkTypesForReason(
                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER_RESTRICTIONS, EXPECTED_ENABLED);
    }

    @Test
    public void onUserRestrictionUnchanged_noChangeToRestriction_noAllowedNetworksUpdated() {
        TelephonyManager tmSubscription = addSubscriptionAndGetMock(1001);
        triggerOnSubscriptionChangedAndWait();
        // precondition: we've updated allowed networks to the default (2g enabled)
        verify(tmSubscription, times(1)).setAllowedNetworkTypesForReason(
                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER_RESTRICTIONS, EXPECTED_ENABLED);
        verify(tmSubscription, never()).setAllowedNetworkTypesForReason(
                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER_RESTRICTIONS, EXPECTED_DISABLED);

        when(mMockUserManager.hasUserRestriction(UserManager.DISALLOW_CELLULAR_2G)).thenReturn(
                true);
        triggerBroadcastReceiverAndWait();
        triggerBroadcastReceiverAndWait();

        // expect we only updated once even though we got two broadcasts for user restriction
        // updates
        verify(tmSubscription, times(1)).setAllowedNetworkTypesForReason(
                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER_RESTRICTIONS, EXPECTED_DISABLED);
        // extra check to ensure we haven't also somehow updated back to enabled along the way
        verify(tmSubscription, times(1)).setAllowedNetworkTypesForReason(
                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER_RESTRICTIONS, EXPECTED_ENABLED);
    }

    @Test
    public void onUserRestrictionChanged_restrictionChanged_allowedNetworksUpdated() {
        // precondition: we've updated allowed networks to the default (2g enabled)
        TelephonyManager tmSubscription = addSubscriptionAndGetMock(1001);
        triggerOnSubscriptionChangedAndWait();
        verify(tmSubscription, times(1)).setAllowedNetworkTypesForReason(
                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER_RESTRICTIONS, EXPECTED_ENABLED);
        verify(tmSubscription, never()).setAllowedNetworkTypesForReason(
                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER_RESTRICTIONS, EXPECTED_DISABLED);

        // update the user restriction to disallow 2g
        reset(tmSubscription);
        when(mMockUserManager.hasUserRestriction(UserManager.DISALLOW_CELLULAR_2G)).thenReturn(
                true);
        triggerBroadcastReceiverAndWait();
        verify(tmSubscription, never()).setAllowedNetworkTypesForReason(
                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER_RESTRICTIONS, EXPECTED_ENABLED);
        verify(tmSubscription, times(1)).setAllowedNetworkTypesForReason(
                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER_RESTRICTIONS, EXPECTED_DISABLED);


        // update the user restriction to allow 2g again
        reset(tmSubscription);
        when(mMockUserManager.hasUserRestriction(UserManager.DISALLOW_CELLULAR_2G)).thenReturn(
                false);
        triggerBroadcastReceiverAndWait();
        verify(tmSubscription, times(1)).setAllowedNetworkTypesForReason(
                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER_RESTRICTIONS, EXPECTED_ENABLED);
        verify(tmSubscription, never()).setAllowedNetworkTypesForReason(
                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER_RESTRICTIONS, EXPECTED_DISABLED);

    }

    private SubscriptionInfo getSubInfo(int id) {
        return new SubscriptionInfo(id, "890126042XXXXXXXXXXX", 0, "T-mobile", "T-mobile", 0, 255,
                "12345", 0, null, "310", "260", "156", false, null, null);
    }

    private void triggerOnSubscriptionChangedAndWait() {
        mExecutor.execute(() -> mChangedListener.onSubscriptionsChanged());
        drainSingleThreadedExecutor();
    }

    private void triggerBroadcastReceiverAndWait() {
        mTelephony2gUpdater.onReceive(mContext, new Intent());
        drainSingleThreadedExecutor();
    }

    /**
     * Wait for all tasks on executor up to the point of invocation to drain, then return.
     *
     * This helper takes advantage of the fact that we're using an immutable single threaded
     * executor that guarantees tasks are executed in the order they are enqueued. It enqueues a
     * task that decrements a latch and then waits on that task to finish. By definition, once the
     * test task finishes, all previously enqueued tasks will have also completed.
     */
    private void drainSingleThreadedExecutor() {
        resetExecutorLatch();
        mExecutor.execute(() -> mLatch.countDown());
        try {
            mLatch.await(DRAIN_TIMEOUT, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            fail(e.getMessage());
        }
    }

    private void resetExecutorLatch() {
        mLatch = new CountDownLatch(1);
    }

    /**
     * Helper that allows you to update subInfo and have that change reflected on subsequent calls
     * to {@link SubscriptionManager#getCompleteActiveSubscriptionInfoList()}
     */
    private void setupMutableSubscriptionInfoMock() {
        var answer = new Answer<List<SubscriptionInfo>>() {
            @Override
            public List<SubscriptionInfo> answer(InvocationOnMock invocation) throws Throwable {
                return mCurrentSubscriptions;
            }
        };
        when(mMockSubscriptionManager.getCompleteActiveSubscriptionInfoList()).thenAnswer(answer);
    }

    private TelephonyManager addSubscriptionAndGetMock(int subId) {
        mCurrentSubscriptions.add(getSubInfo(subId));
        TelephonyManager tmSubscription = mock(TelephonyManager.class);
        when(mMockTelephonyManager.createForSubscriptionId(subId)).thenReturn(tmSubscription);
        return tmSubscription;
    }

}
