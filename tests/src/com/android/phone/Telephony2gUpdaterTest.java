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

import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.UserManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.TelephonyTestBase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@RunWith(AndroidJUnit4.class)
public class Telephony2gUpdaterTest extends TelephonyTestBase {
    private Telephony2gUpdater mTelephony2gUpdater;
    private Executor mExecutor;

    private UserManager mMockUserManager;
    private TelephonyManager mMockTelephonyManager;
    private SubscriptionManager mMockSubscriptionManager;

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

        mExecutor = Executors.newSingleThreadExecutor();
        mTelephony2gUpdater = new Telephony2gUpdater(mExecutor,
                getTestContext(), BASE_NETWORK);
    }

    @Test
    public void handleUserRestrictionsChanged_noSubscriptions_noAllowedNetworksChanged() {
        when(mMockSubscriptionManager.getAvailableSubscriptionInfoList()).thenReturn(
                new ArrayList<>());
        mTelephony2gUpdater.handleUserRestrictionsChanged(getTestContext());
        verify(mMockTelephonyManager, never()).setAllowedNetworkTypesForReason(anyInt(), anyInt());
    }

    @Test
    public void handleUserRestrictionsChanged_nullSubscriptions_noAllowedNetworksChanged() {
        when(mMockSubscriptionManager.getAvailableSubscriptionInfoList()).thenReturn(null);
        mTelephony2gUpdater.handleUserRestrictionsChanged(getTestContext());
        verify(mMockTelephonyManager, never()).setAllowedNetworkTypesForReason(anyInt(), anyInt());
    }

    @Test
    public void handleUserRestrictionsChanged_oneSubscription_allowedNetworksUpdated() {
        TelephonyManager tmSubscription1 = mock(TelephonyManager.class);
        when(mMockSubscriptionManager.getAvailableSubscriptionInfoList()).thenReturn(
                Collections.singletonList(getSubInfo(1)));
        when(mMockTelephonyManager.createForSubscriptionId(1)).thenReturn(tmSubscription1);
        when(mMockUserManager.hasUserRestriction(UserManager.DISALLOW_CELLULAR_2G)).thenReturn(
                true);

        mTelephony2gUpdater.handleUserRestrictionsChanged(getTestContext());

        System.out.println(TelephonyManager.convertNetworkTypeBitmaskToString(11L));
        verify(tmSubscription1, times(1)).setAllowedNetworkTypesForReason(
                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER_RESTRICTIONS, EXPECTED_DISABLED);
    }

    @Test
    public void handleUserRestrictionsChanged_manySubscriptionsDisallow2g_allowedNetworkUpdated() {

        // Two subscriptions are available
        when(mMockSubscriptionManager.getAvailableSubscriptionInfoList()).thenReturn(
                Arrays.asList(getSubInfo(1), getSubInfo(2)));
        TelephonyManager tmSubscription1 = mock(TelephonyManager.class);
        TelephonyManager tmSubscription2 = mock(TelephonyManager.class);
        when(mMockTelephonyManager.createForSubscriptionId(1)).thenReturn(tmSubscription1);
        when(mMockTelephonyManager.createForSubscriptionId(2)).thenReturn(tmSubscription2);
        // 2g is disallowed
        when(mMockUserManager.hasUserRestriction(UserManager.DISALLOW_CELLULAR_2G)).thenReturn(
                true);

        mTelephony2gUpdater.handleUserRestrictionsChanged(getTestContext());

        verify(tmSubscription1, times(1)).setAllowedNetworkTypesForReason(
                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER_RESTRICTIONS, EXPECTED_DISABLED);
        verify(tmSubscription1, times(1)).setAllowedNetworkTypesForReason(
                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER_RESTRICTIONS, EXPECTED_DISABLED);
    }

    @Test
    public void handleUserRestrictionsChanged_manySubscriptionsAllow2g_allowedNetworkUpdated() {

        // Two subscriptions are available
        when(mMockSubscriptionManager.getAvailableSubscriptionInfoList()).thenReturn(
                Arrays.asList(getSubInfo(1), getSubInfo(2)));
        TelephonyManager tmSubscription1 = mock(TelephonyManager.class);
        TelephonyManager tmSubscription2 = mock(TelephonyManager.class);
        when(mMockTelephonyManager.createForSubscriptionId(1)).thenReturn(tmSubscription1);
        when(mMockTelephonyManager.createForSubscriptionId(2)).thenReturn(tmSubscription2);

        // 2g is allowed
        when(mMockUserManager.hasUserRestriction(UserManager.DISALLOW_CELLULAR_2G)).thenReturn(
                false);

        mTelephony2gUpdater.handleUserRestrictionsChanged(getTestContext());

        verify(tmSubscription1, times(1)).setAllowedNetworkTypesForReason(
                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER_RESTRICTIONS, EXPECTED_ENABLED);
        verify(tmSubscription1, times(1)).setAllowedNetworkTypesForReason(
                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER_RESTRICTIONS, EXPECTED_ENABLED);
    }

    private SubscriptionInfo getSubInfo(int id) {
        return new SubscriptionInfo(id, "890126042XXXXXXXXXXX", 0, "T-mobile", "T-mobile", 0, 255,
                "12345", 0, null, "310", "260", "156", false, null, null);
    }
}
