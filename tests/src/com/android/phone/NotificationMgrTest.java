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

import static com.android.phone.NotificationMgr.DATA_ROAMING_NOTIFICATION;
import static com.android.phone.NotificationMgr.LIMITED_SIM_FUNCTION_NOTIFICATION;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.StatusBarManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.res.Resources;
import android.os.Build;
import android.os.UserHandle;
import android.os.UserManager;
import android.telecom.TelecomManager;
import android.telephony.ServiceState;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.util.NotificationChannelController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Field;

/**
 * Unit Test for NotificationMgr
 */
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class NotificationMgrTest {

    private static final int TEST_SUB_ID = 1;
    private static final long SERIAL_NUMBER_OF_USER = 1234567L;
    private static final String TEST_LABEL_CF = "test_call_forwarding";
    private static final String TEST_SUB_INFO_DISPLAY_NAME = "display_name";
    private static final String TEST_PACKAGE_NAME = "com.android.phone";
    private static final String TEST_SELECTED_NETWORK_OPERATOR_NAME = "TheOperator";
    private static final String MOBILE_NETWORK_SELECTION_PACKAGE = "com.android.phone";
    private static final String MOBILE_NETWORK_SELECTION_CLASS = ".testClass";
    private static final String CARRIER_NAME = "CoolCarrier";

    @Mock PhoneGlobals mApp;
    @Mock StatusBarManager mStatusBarManager;
    @Mock UserManager mUserManager;
    @Mock SubscriptionManager mSubscriptionManager;
    @Mock TelecomManager mTelecomManager;
    @Mock TelephonyManager mTelephonyManager;
    @Mock Phone mPhone;
    @Mock SharedPreferences mSharedPreferences;
    @Mock NotificationManager mNotificationManager;
    @Mock SubscriptionInfo mSubscriptionInfo;
    @Mock Resources mResources;
    @Mock ServiceState mServiceState;

    private Phone[] mPhones;
    private NotificationMgr mNotificationMgr;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mPhones = new Phone[]{mPhone};
        replaceInstance(PhoneFactory.class, "sPhones", null, mPhones);
        when(mPhone.getPhoneType()).thenReturn(PhoneConstants.PHONE_TYPE_GSM);
        when(mApp.getSharedPreferences(anyString(), anyInt())).thenReturn(mSharedPreferences);

        when(mApp.getPackageName()).thenReturn(TEST_PACKAGE_NAME);
        when(mApp.getSystemService(Context.STATUS_BAR_SERVICE)).thenReturn(mStatusBarManager);
        when(mApp.getSystemService(Context.USER_SERVICE)).thenReturn(mUserManager);
        when(mApp.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)).thenReturn(
                mSubscriptionManager);
        when(mApp.getSystemServiceName(TelecomManager.class)).thenReturn(Context.TELECOM_SERVICE);
        when(mApp.getSystemService(TelecomManager.class)).thenReturn(mTelecomManager);
        when(mApp.getSystemService(Context.TELEPHONY_SERVICE)).thenReturn(mTelephonyManager);

        when(mApp.createPackageContextAsUser(any(), eq(0), any())).thenReturn(mApp);
        when(mApp.getSystemService(Context.NOTIFICATION_SERVICE)).thenReturn(mNotificationManager);
        when(mUserManager.getSerialNumbersOfUsers(true)).thenReturn(
                new long[]{SERIAL_NUMBER_OF_USER});
        when(mUserManager.getUserForSerialNumber(eq(SERIAL_NUMBER_OF_USER))).thenReturn(
                UserHandle.SYSTEM);
        when(mApp.getResources()).thenReturn(mResources);
        when(mResources.getString(R.string.labelCF)).thenReturn(TEST_LABEL_CF);
        ApplicationInfo appWithSdkS = buildApplicationInfo(Build.VERSION_CODES.S);
        when(mApp.getApplicationInfo()).thenReturn(appWithSdkS);
        when(mTelephonyManager.createForSubscriptionId(anyInt())).thenReturn(mTelephonyManager);
        when(mTelephonyManager.getServiceState()).thenReturn(mServiceState);

        mNotificationMgr = new NotificationMgr(mApp);
    }

    @Test
    public void testUpdateCfi_visible_noActiveSubscription_notificationNeverSent()
            throws Exception {
        // Given no active subscription available
        when(mSubscriptionManager.getActiveSubscriptionInfo(eq(TEST_SUB_ID))).thenReturn(null);

        // When updateCfi method is called
        mNotificationMgr.updateCfi(TEST_SUB_ID, /*visible=*/true, /*isFresh=*/false);

        // Then the notification should never be sent
        verify(mNotificationManager, never()).notify(any(), anyInt(), any());
    }

    @Test
    public void testUpdateCfi_visible_hasActiveSub_singleSIM_notificationSent()
            throws Exception {
        when(mTelephonyManager.getPhoneCount()).thenReturn(1);
        when(mSubscriptionManager.getActiveSubscriptionInfo(eq(TEST_SUB_ID))).thenReturn(
                mSubscriptionInfo);

        mNotificationMgr.updateCfi(TEST_SUB_ID, /*visible=*/true, /*isFresh=*/false);

        verifyNotificationSentWithChannelId(NotificationChannelController.CHANNEL_ID_CALL_FORWARD);
    }

    @Test
    public void testUpdateCfi_visible_hasActiveSub_multiSIM_notificationSentWithoutDisplayName()
            throws Exception {
        when(mTelephonyManager.getPhoneCount()).thenReturn(2);
        when(mSubscriptionManager.getActiveSubscriptionInfo(eq(TEST_SUB_ID))).thenReturn(
                mSubscriptionInfo);
        when(mSubscriptionInfo.getDisplayName()).thenReturn(null);

        mNotificationMgr.updateCfi(TEST_SUB_ID, /*visible=*/true, /*isFresh=*/false);

        verifyNotificationSentWithChannelId(NotificationChannelController.CHANNEL_ID_CALL_FORWARD);
    }

    @Test
    public void testUpdateCfi_visible_hasActiveSub_multiSIM_notificationSentWithDisplayName()
            throws Exception {
        when(mTelephonyManager.getPhoneCount()).thenReturn(2);
        when(mSubscriptionManager.getActiveSubscriptionInfo(eq(TEST_SUB_ID))).thenReturn(
                mSubscriptionInfo);
        when(mSubscriptionInfo.getDisplayName()).thenReturn(TEST_SUB_INFO_DISPLAY_NAME);

        mNotificationMgr.updateCfi(TEST_SUB_ID, /*visible=*/true, /*isFresh=*/false);

        verifyNotificationSentWithChannelId(NotificationChannelController.CHANNEL_ID_CALL_FORWARD);
    }

    @Test
    public void testUpdateCfi_invisible_hasUnmanagedProfile_notificationCanceled()
            throws Exception {
        when(mUserManager.isManagedProfile(anyInt())).thenReturn(false);

        mNotificationMgr.updateCfi(TEST_SUB_ID, /*visible=*/false, /*isFresh=*/false);

        verify(mNotificationManager).cancel(any(), anyInt());
    }

    @Test
    public void testUpdateCfi_invisible_allProfilesAreManaged_notificationNeverCanceled()
            throws Exception {
        when(mUserManager.isManagedProfile(anyInt())).thenReturn(true);

        mNotificationMgr.updateCfi(TEST_SUB_ID, /*visible=*/false, /*isFresh=*/false);

        verify(mNotificationManager, never()).cancel(any(), anyInt());
    }

    @Test
    public void testShowDataRoamingNotification_roamingOn() throws Exception {
        mNotificationMgr.showDataRoamingNotification(TEST_SUB_ID, /*roamingOn=*/true);

        verifyNotificationSentWithChannelId(
                NotificationChannelController.CHANNEL_ID_MOBILE_DATA_STATUS);
    }

    @Test
    public void testShowDataRoamingNotification_roamingOff() throws Exception {
        mNotificationMgr.showDataRoamingNotification(TEST_SUB_ID, /*roamingOn=*/false);

        verifyNotificationSentWithChannelId(
                NotificationChannelController.CHANNEL_ID_MOBILE_DATA_STATUS);
    }

    @Test
    public void testHideDataRoamingNotification() {
        mNotificationMgr.hideDataRoamingNotification();

        verify(mNotificationManager).cancel(any(), eq(DATA_ROAMING_NOTIFICATION));
    }

    @Test
    public void testUpdateNetworkSelection_justOutOfService_notificationNeverSent()
            throws Exception {
        prepareResourcesForNetworkSelection();

        mNotificationMgr.updateNetworkSelection(ServiceState.STATE_OUT_OF_SERVICE, TEST_SUB_ID);
        try {
            Thread.sleep(2000);
        } catch (InterruptedException ignored) {
        }

        verify(mNotificationManager, never()).notify(any(), anyInt(), any());
    }

    @Test
    public void testUpdateNetworkSelection_outOfServiceForEnoughTime_notificationSent()
            throws Exception {
        prepareResourcesForNetworkSelection();

        mNotificationMgr.updateNetworkSelection(ServiceState.STATE_OUT_OF_SERVICE, TEST_SUB_ID);
        // TODO: use effective TestLooper time eclipse instead of sleeping
        try {
            Thread.sleep(10000);
        } catch (InterruptedException ignored) {
        }
        mNotificationMgr.updateNetworkSelection(ServiceState.STATE_OUT_OF_SERVICE, TEST_SUB_ID);

        verifyNotificationSentWithChannelId(NotificationChannelController.CHANNEL_ID_ALERT);
    }

    @Test
    public void testShowLimitedSimFunctionWarningNotification_forTheFirstTime_notificationSent()
            throws Exception {
        when(mResources.getText(R.string.limited_sim_function_notification_message)).thenReturn(
                CARRIER_NAME);
        when(mResources.getText(
                R.string.limited_sim_function_with_phone_num_notification_message)).thenReturn(
                "123");

        mNotificationMgr.showLimitedSimFunctionWarningNotification(TEST_SUB_ID, CARRIER_NAME);

        verifyNotificationSentWithChannelId(
                NotificationChannelController.CHANNEL_ID_SIM_HIGH_PRIORITY);
    }

    @Test
    public void testShowLimitedSimFunctionWarningNotification_consecutiveCall_notificationSentOnce()
            throws Exception {
        when(mResources.getText(R.string.limited_sim_function_notification_message)).thenReturn(
                CARRIER_NAME);
        when(mResources.getText(
                R.string.limited_sim_function_with_phone_num_notification_message)).thenReturn(
                "123");

        // Call the method TWICE with the same subscription
        mNotificationMgr.showLimitedSimFunctionWarningNotification(TEST_SUB_ID, CARRIER_NAME);
        mNotificationMgr.showLimitedSimFunctionWarningNotification(TEST_SUB_ID, CARRIER_NAME);

        // Verify the notification is only sent ONCE
        verifyNotificationSentWithChannelId(
                NotificationChannelController.CHANNEL_ID_SIM_HIGH_PRIORITY);
    }

    @Test
    public void testDismissLimitedSimFunctionWarningNotification_noShowCalledBefore_noCancelSent()
            throws Exception {
        // showLimitedSimFunctionWarningNotification was never called before

        mNotificationMgr.dismissLimitedSimFunctionWarningNotification(TEST_SUB_ID);

        verify(mNotificationManager, never()).cancel(any(), anyInt());
    }

    @Test
    public void testDismissLimitedSimFunctionWarningNotification_showCalledBefore_cancelSent()
            throws Exception {
        when(mResources.getText(R.string.limited_sim_function_notification_message)).thenReturn(
                CARRIER_NAME);
        when(mResources.getText(
                R.string.limited_sim_function_with_phone_num_notification_message)).thenReturn(
                "123");
        mNotificationMgr.showLimitedSimFunctionWarningNotification(TEST_SUB_ID, CARRIER_NAME);

        mNotificationMgr.dismissLimitedSimFunctionWarningNotification(TEST_SUB_ID);

        verify(mNotificationManager).cancel(any(), eq(LIMITED_SIM_FUNCTION_NOTIFICATION));
    }

    private ApplicationInfo buildApplicationInfo(int targetSdkVersion) {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.targetSdkVersion = targetSdkVersion;
        return applicationInfo;
    }

    private void verifyNotificationSentWithChannelId(String expectedNotificationChannelId) {
        ArgumentCaptor<Notification> notificationArgumentCaptor = ArgumentCaptor.forClass(
                Notification.class);
        verify(mNotificationManager).notify(any(), anyInt(), notificationArgumentCaptor.capture());
        Notification capturedNotification = notificationArgumentCaptor.getAllValues().get(0);
        assertThat(capturedNotification.getChannelId()).isEqualTo(expectedNotificationChannelId);
    }

    private void prepareResourcesForNetworkSelection() {
        when(mSharedPreferences.getString(Phone.NETWORK_SELECTION_NAME_KEY + TEST_SUB_ID,
                "")).thenReturn(TEST_SELECTED_NETWORK_OPERATOR_NAME);
        when(mResources.getBoolean(
                com.android.internal.R.bool.skip_restoring_network_selection)).thenReturn(false);
        when(mServiceState.getState()).thenReturn(ServiceState.STATE_OUT_OF_SERVICE);
        when(mApp.getString(R.string.mobile_network_settings_package)).thenReturn(
                MOBILE_NETWORK_SELECTION_PACKAGE);
        when(mApp.getString(R.string.mobile_network_settings_class)).thenReturn(
                MOBILE_NETWORK_SELECTION_CLASS);
    }

    private static void replaceInstance(final Class c,
            final String instanceName, final Object obj, final Object newValue) throws Exception {
        Field field = c.getDeclaredField(instanceName);
        field.setAccessible(true);
        field.set(obj, newValue);
    }
}
