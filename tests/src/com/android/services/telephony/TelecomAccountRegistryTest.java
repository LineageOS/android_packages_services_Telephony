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

package com.android.services.telephony;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.platform.test.flag.junit.SetFlagsRule;
import android.telecom.PhoneAccount;
import android.telecom.TelecomManager;
import android.telephony.CarrierConfigManager;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;
import android.telephony.SubscriptionManager.OnSubscriptionsChangedListener;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.telephony.ims.ImsManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.TelephonyTestBase;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.SimultaneousCallingTracker;
import com.android.internal.telephony.flags.Flags;
import com.android.phone.PhoneInterfaceManager;
import com.android.phone.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class TelecomAccountRegistryTest extends TelephonyTestBase {

    private static final String TAG = "TelecomAccountRegistryTest";
    private static final int TEST_SUB_ID = 1;

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    // We need more functions that what TelephonyTestBase.mContext supports.
    // Use a local mocked Context to make life easier.
    @Mock Context mMockedContext;
    @Mock TelecomManager mTelecomManager;
    @Mock TelephonyManager mTelephonyManager;
    @Mock ImsManager mImsManager;
    @Mock SubscriptionManager mSubscriptionManager;
    @Mock ContentProvider mContentProvider;
    @Mock Phone mPhone;
    @Mock Resources mResources;
    @Mock Drawable mDrawable;
    @Mock PhoneInterfaceManager mPhoneInterfaceManager;

    private TelecomAccountRegistry mTelecomAccountRegistry;

    private OnSubscriptionsChangedListener mOnSubscriptionsChangedListener;
    private TelephonyCallback mTelephonyCallback;
    private BroadcastReceiver mUserSwitchedAndConfigChangedReceiver;
    private BroadcastReceiver mLocaleChangedBroadcastReceiver;
    private ContentResolver mContentResolver;
    private TestableLooper mTestableLooper;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        mSetFlagsRule.disableFlags(Flags.FLAG_DELAY_PHONE_ACCOUNT_REGISTRATION);

        replaceInstance(PhoneInterfaceManager.class, "sInstance", null, mPhoneInterfaceManager);
        when(mPhone.getPhoneType()).thenReturn(PhoneConstants.PHONE_TYPE_GSM);
        when(mPhone.getContext()).thenReturn(mMockedContext);
        when(mPhone.getSubId()).thenReturn(TEST_SUB_ID);
        when(mPhoneInterfaceManager.isRttEnabled(anyInt())).thenReturn(false);

        when(mMockedContext.getResources()).thenReturn(mResources);
        // Enable PSTN PhoneAccount which can place emergency call by default
        when(mResources.getBoolean(R.bool.config_pstn_phone_accounts_enabled)).thenReturn(true);
        when(mResources.getBoolean(R.bool.config_pstnCanPlaceEmergencyCalls)).thenReturn(true);
        when(mResources.getDrawable(anyInt(), any())).thenReturn(mDrawable);
        when(mDrawable.getIntrinsicWidth()).thenReturn(5);
        when(mDrawable.getIntrinsicHeight()).thenReturn(5);

        PersistableBundle bundle = new PersistableBundle();
        bundle.putBoolean(CarrierConfigManager.KEY_SUPPORT_IMS_CONFERENCE_CALL_BOOL, false);
        bundle.putIntArray(CarrierConfigManager.KEY_CELLULAR_SERVICE_CAPABILITIES_INT_ARRAY,
                new int[]{
                        SubscriptionManager.SERVICE_CAPABILITY_VOICE,
                        SubscriptionManager.SERVICE_CAPABILITY_SMS,
                        SubscriptionManager.SERVICE_CAPABILITY_DATA
                });
        when(mPhoneGlobals.getCarrierConfigForSubId(anyInt())).thenReturn(bundle);

        // Mock system services used by TelecomAccountRegistry
        when(mMockedContext.getSystemServiceName(TelecomManager.class))
                .thenReturn(Context.TELECOM_SERVICE);
        when(mMockedContext.getSystemService(TelecomManager.class))
                .thenReturn(mTelecomManager);
        when(mMockedContext.getSystemServiceName(TelephonyManager.class))
                .thenReturn(Context.TELEPHONY_SERVICE);
        when(mMockedContext.getSystemService(TelephonyManager.class))
                .thenReturn(mTelephonyManager);
        when(mMockedContext.getSystemServiceName(ImsManager.class))
                .thenReturn(Context.TELEPHONY_IMS_SERVICE);
        when(mMockedContext.getSystemService(ImsManager.class))
                .thenReturn(mImsManager);
        when(mMockedContext.getSystemServiceName(SubscriptionManager.class))
                .thenReturn(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        when(mMockedContext.getSystemService(SubscriptionManager.class))
                .thenReturn(mSubscriptionManager);

        // Use mocked ContentProvider since we can't really mock ContentResolver
        mContentResolver = ContentResolver.wrap(mContentProvider);
        when(mMockedContext.getContentResolver()).thenReturn(mContentResolver);

        mTestableLooper = TestableLooper.get(this);
        when(mMockedContext.getMainLooper()).thenReturn(mTestableLooper.getLooper());
        mTelecomAccountRegistry = new TelecomAccountRegistry(mMockedContext);
        mTelecomAccountRegistry.setupOnBoot();

        // Capture OnSubscriptionsChangedListener
        ArgumentCaptor<OnSubscriptionsChangedListener> subChangeListenerCaptor =
                ArgumentCaptor.forClass(OnSubscriptionsChangedListener.class);
        verify(mSubscriptionManager).addOnSubscriptionsChangedListener(
                subChangeListenerCaptor.capture());
        mOnSubscriptionsChangedListener = subChangeListenerCaptor.getValue();

        // Capture TelephonyCallback
        ArgumentCaptor<TelephonyCallback> telephonyCallbackArgumentCaptor =
                ArgumentCaptor.forClass(TelephonyCallback.class);
        verify(mTelephonyManager).registerTelephonyCallback(anyInt(), any(),
                telephonyCallbackArgumentCaptor.capture());
        mTelephonyCallback = telephonyCallbackArgumentCaptor.getValue();

        // Capture BroadcastReceivers
        ArgumentCaptor<BroadcastReceiver> broadcastReceiverArgumentCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(mMockedContext, times(2)).registerReceiver(broadcastReceiverArgumentCaptor.capture(),
                any());
        mUserSwitchedAndConfigChangedReceiver =
                broadcastReceiverArgumentCaptor.getAllValues().get(0);
        mLocaleChangedBroadcastReceiver = broadcastReceiverArgumentCaptor.getAllValues().get(1);

        replaceInstance(SimultaneousCallingTracker.class, "sInstance", null,
                Mockito.mock(SimultaneousCallingTracker.class));

        mTestableLooper.processAllMessages();
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @Test
    public void userSwitched_withPSTNAccount_shouldRegisterPSTNAccount() {
        onUserSwitched(UserHandle.CURRENT);

        PhoneAccount phoneAccount = verifyAndCaptureRegisteredPhoneAccount();

        assertThat(phoneAccount.hasCapabilities(
                PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)).isTrue();
        assertThat(phoneAccount.hasCapabilities(
                PhoneAccount.CAPABILITY_CALL_PROVIDER)).isTrue();
        assertThat(phoneAccount.hasCapabilities(
                PhoneAccount.CAPABILITY_PLACE_EMERGENCY_CALLS)).isTrue();
    }

    @Test
    public void onLocaleChanged_withPSTNAccountDisabled_shouldRegisterEmergencyOnlyAccount() {
        when(mResources.getBoolean(R.bool.config_pstn_phone_accounts_enabled)).thenReturn(false);
        when(mResources.getBoolean(
                R.bool.config_emergency_account_emergency_calls_only)).thenReturn(true);
        onLocaleChanged();

        PhoneAccount phoneAccount = verifyAndCaptureRegisteredPhoneAccount();

        assertThat(phoneAccount.hasCapabilities(
                PhoneAccount.CAPABILITY_EMERGENCY_CALLS_ONLY)).isTrue();
    }

    @Test
    public void onLocaleChanged_withSubVoiceCapable_shouldNotRegisterEmergencyOnlyAccount() {
        overrideSubscriptionServiceCapabilities(
                new int[]{SubscriptionManager.SERVICE_CAPABILITY_VOICE});
        onLocaleChanged();

        PhoneAccount phoneAccount = verifyAndCaptureRegisteredPhoneAccount();

        assertThat(phoneAccount.hasCapabilities(
                PhoneAccount.CAPABILITY_EMERGENCY_CALLS_ONLY)).isFalse();
    }

    @Test
    public void onLocaleChanged_withSubNotVoiceCapable_shouldRegisterEmergencyOnlyAccount() {
        overrideSubscriptionServiceCapabilities(
                new int[]{SubscriptionManager.SERVICE_CAPABILITY_DATA});
        onLocaleChanged();

        PhoneAccount phoneAccount = verifyAndCaptureRegisteredPhoneAccount();

        assertThat(phoneAccount.hasCapabilities(
                PhoneAccount.CAPABILITY_EMERGENCY_CALLS_ONLY)).isTrue();
    }

    private PhoneAccount verifyAndCaptureRegisteredPhoneAccount() {
        ArgumentCaptor<PhoneAccount> phoneAccountArgumentCaptor =
                ArgumentCaptor.forClass(PhoneAccount.class);
        verify(mTelecomManager, atLeastOnce()).registerPhoneAccount(
                phoneAccountArgumentCaptor.capture());
        return phoneAccountArgumentCaptor.getValue();
    }

    private void onUserSwitched(UserHandle userHandle) {
        Log.d(TAG, "Broadcast ACTION_USER_SWITCHED...");
        Intent intent = new Intent(Intent.ACTION_USER_SWITCHED);
        intent.putExtra(Intent.EXTRA_USER, userHandle);
        mUserSwitchedAndConfigChangedReceiver.onReceive(mMockedContext, intent);
        mTestableLooper.processAllMessages();
    }

    private void onCarrierConfigChanged(int subId) {
        Log.d(TAG, "Broadcast ACTION_CARRIER_CONFIG_CHANGED...");
        Intent intent = new Intent(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED);
        intent.putExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, subId);
        mUserSwitchedAndConfigChangedReceiver.onReceive(mMockedContext, intent);
        mTestableLooper.processAllMessages();
    }

    private void onSubscriptionsChanged() {
        Log.d(TAG, "Change subscriptions...");
        mOnSubscriptionsChangedListener.onSubscriptionsChanged();
    }

    private void onAddSubscriptionListenerFailed() {
        Log.d(TAG, "Add subscription listener failed...");
        mOnSubscriptionsChangedListener.onAddListenerFailed();
    }

    private void onServiceStateChanged(ServiceState serviceState) {
        if (mTelephonyCallback instanceof TelephonyCallback.ServiceStateListener) {
            TelephonyCallback.ServiceStateListener listener =
                    (TelephonyCallback.ServiceStateListener) mTelephonyCallback;
            listener.onServiceStateChanged(serviceState);
        }
    }

    private void onActiveDataSubscriptionIdChanged(int subId) {
        if (mTelephonyCallback instanceof TelephonyCallback.ActiveDataSubscriptionIdListener) {
            TelephonyCallback.ActiveDataSubscriptionIdListener listener =
                    (TelephonyCallback.ActiveDataSubscriptionIdListener) mTelephonyCallback;
            listener.onActiveDataSubscriptionIdChanged(subId);
        }
    }

    private void onLocaleChanged() {
        Log.d(TAG, "Broadcast ACTION_LOCALE_CHANGED...");
        Intent intent = new Intent(Intent.ACTION_LOCALE_CHANGED);
        mLocaleChangedBroadcastReceiver.onReceive(mMockedContext, intent);
    }

    private void onNetworkCountryChanged() {
        Log.d(TAG, "Broadcast ACTION_NETWORK_COUNTRY_CHANGED...");
        Intent intent = new Intent(TelephonyManager.ACTION_NETWORK_COUNTRY_CHANGED);
        mLocaleChangedBroadcastReceiver.onReceive(mMockedContext, intent);
    }

    private void overrideSubscriptionServiceCapabilities(int[] capabilities) {
        PersistableBundle bundle = new PersistableBundle();
        bundle.putIntArray(CarrierConfigManager.KEY_CELLULAR_SERVICE_CAPABILITIES_INT_ARRAY,
                capabilities);

        when(mPhoneGlobals.getCarrierConfigForSubId(anyInt())).thenReturn(bundle);
        mTestableLooper.processAllMessages();
    }
}
