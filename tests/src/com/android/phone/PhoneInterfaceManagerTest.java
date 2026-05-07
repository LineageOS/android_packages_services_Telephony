/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.telephony.TelephonyManager.CHANGE_ICC_LOCK_SUCCESS;
import static android.telephony.TelephonyManager.GET_AUTO_MANAGED_PIN_RESULT_FAILED_NOT_ENROLLED;
import static android.telephony.TelephonyManager.GET_AUTO_MANAGED_PIN_RESULT_SUCCESSFUL;
import static android.telephony.TelephonyManager.GET_AUTO_MANAGED_PIN_RESULT_USER_AUTH_REQUIRED;
import static android.telephony.TelephonyManager.SIM_PIN_ENROLLMENT_RESULT_FAILED_INVALID_SIM;
import static android.telephony.TelephonyManager.SIM_PIN_ENROLLMENT_RESULT_FAILED_SIM_LOCK_ALREADY_ACTIVE;
import static android.telephony.TelephonyManager.SIM_PIN_ENROLLMENT_RESULT_FAILED_WRONG_PIN;
import static android.telephony.TelephonyManager.SIM_PIN_ENROLLMENT_RESULT_SUCCESSFUL;
import static android.telephony.TelephonyManager.SIM_PIN_UNENROLLMENT_RESULT_FAILED_CANNOT_CHANGE_PIN;
import static android.telephony.TelephonyManager.SIM_PIN_UNENROLLMENT_RESULT_FAILED_CANNOT_DISABLE_PIN;
import static android.telephony.TelephonyManager.SIM_PIN_UNENROLLMENT_RESULT_FAILED_NOT_ENROLLED;
import static android.telephony.TelephonyManager.SIM_PIN_UNENROLLMENT_RESULT_FAILED_PIN_UNAVAILABLE;
import static android.telephony.TelephonyManager.SIM_PIN_UNENROLLMENT_RESULT_FAILED_SIM_NOT_PRESENT;
import static android.telephony.TelephonyManager.SIM_PIN_UNENROLLMENT_RESULT_SUCCESSFUL;

import static com.android.internal.telephony.util.TelephonyUtils.TELEPHONY_FEATURE_ENFORCEMENT_VENDOR_API_LEVEL;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNotNull;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.app.AppOpsManager;
import android.compat.testing.PlatformCompatChangeRule;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.os.UserHandle;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.telephony.CarrierConfigManager;
import android.telephony.NetworkSecurityEvent;
import android.telephony.RadioAccessFamily;
import android.telephony.Rlog;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.UiccPortInfo;
import android.telephony.UiccSlotInfo;
import android.telephony.UiccSlotMapping;
import android.telephony.satellite.EnableRequestAttributes;
import android.telephony.satellite.SatelliteManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.Pair;

import androidx.test.annotation.UiThreadTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.TelephonyTestBase;
import com.android.internal.telephony.HalVersion;
import com.android.internal.telephony.IIntegerConsumer;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.flags.FeatureFlags;
import com.android.internal.telephony.flags.Flags;
import com.android.internal.telephony.satellite.SatelliteController;
import com.android.internal.telephony.subscription.SubscriptionManagerService;
import com.android.internal.telephony.uicc.IccCardStatus;
import com.android.internal.telephony.uicc.PinStorage;
import com.android.internal.telephony.uicc.UiccController;
import com.android.internal.telephony.uicc.UiccSlot;
import com.android.phone.satellite.accesscontrol.SatelliteAccessController;

import libcore.junit.util.compat.CoreCompatChangeRule.EnableCompatChanges;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Unit Test for PhoneInterfaceManager.
 */
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class PhoneInterfaceManagerTest extends TelephonyTestBase {
    private static final String CARD_STRING = "8944303493379959293F";
    @Rule
    public TestRule compatChangeRule = new PlatformCompatChangeRule();

    private static final String TAG = "PhoneInterfaceManagerTest";

    private PhoneInterfaceManager mPhoneInterfaceManager;
    private SharedPreferences mSharedPreferences;
    @Mock private IIntegerConsumer mIIntegerConsumer;
    private static final String sDebugPackageName =
            PhoneInterfaceManagerTest.class.getPackageName();

    @Mock
    FeatureFlags mFeatureFlags;
    @Mock
    PackageManager mPackageManager;
    @Mock
    private SubscriptionManagerService mSubscriptionManagerService;
    @Mock
    private android.media.AudioManager mAudioManager;
    @Mock
    private SatelliteController mSatelliteController;
    @Mock
    private AppOpsManager mAppOps;
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();
    private UiccController mUiccController = null;
    private PinStorage mPinStorage = null;

    @Before
    @UiThreadTest
    public void setUp() throws Exception {
        super.setUp();
        doReturn(sDebugPackageName).when(mPhoneGlobals).getOpPackageName();

        replaceInstance(SatelliteAccessController.class, "sInstance", null,
                Mockito.mock(SatelliteAccessController.class));

        replaceInstance(SatelliteController.class, "sInstance", null, mSatelliteController);

        // Some message handlers query this method of the satellite controller, so return an empty
        // pair.
        doReturn(new Pair<>(false, null)).when(
                mSatelliteController).isUsingNonTerrestrialNetworkViaCarrier();

        mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        doReturn(mSharedPreferences).when(mPhoneGlobals)
                .getSharedPreferences(anyString(), anyInt());
        mSharedPreferences.edit().remove(Phone.PREF_NULL_CIPHER_AND_INTEGRITY_ENABLED).commit();
        mSharedPreferences.edit().remove(Phone.PREF_NULL_CIPHER_NOTIFICATIONS_ENABLED).commit();

        mUiccController = Mockito.mock(UiccController.class);
        mPinStorage = Mockito.mock(PinStorage.class);
        doReturn(mPinStorage).when(mUiccController).getPinStorage();
        doReturn(new UiccSlot[]{}).when(mUiccController).getUiccSlots();

        replaceInstance(UiccController.class, "mInstance", null, mUiccController);
        // Trigger sInstance restore in tearDown, after PhoneInterfaceManager.init.
        replaceInstance(PhoneInterfaceManager.class, "sInstance", null, null);
        // Note that PhoneInterfaceManager is a singleton. Calling init gives us a handle to the
        // global singleton, but the context that is passed in is unused if the phone app is already
        // alive on a test devices. You must use the spy to mock behavior. Mocks stemming from the
        // passed context will remain unused.
        mPhoneInterfaceManager = spy(PhoneInterfaceManager.init(mPhoneGlobals, mFeatureFlags));
        doReturn(mPhoneGlobals).when(mPhoneGlobals).getBaseContext();
        doReturn(mPhoneGlobals).when(mPhoneGlobals).createContextAsUser(
                any(UserHandle.class), anyInt());
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        doReturn(context.getContentResolver()).when(mPhoneGlobals).getContentResolver();
        doReturn(context.getUserId()).when(mPhoneGlobals).getUserId();
        doReturn(mSubscriptionManagerService).when(mPhoneInterfaceManager)
                .getSubscriptionManagerService();
        TelephonyManager.setupISubForTest(mSubscriptionManagerService);
        replaceInstance(SubscriptionManagerService.class, "sInstance", null,
                mSubscriptionManagerService);
        doReturn(new int[0]).when(mSubscriptionManagerService).getActiveSubIdList(anyBoolean());

        // Some message handlers query these methods on the default phone instance.
        // Make sure they return sensible values and the mPhone mock instance is set
        // as the default phone.
        doReturn(new HalVersion(2, 1)).when(mPhone).getHalVersion(anyInt());
        when(mPhone.getContext()).thenReturn(mPhoneGlobals);
        doReturn(mDataNetworkController).when(mPhone).getDataNetworkController();
        doReturn(mPhone).when(mPhoneInterfaceManager).getDefaultPhone();

        // In order not to affect the existing implementation, define a telephony features
        // and disabled enforce_telephony_feature_mapping_for_public_apis feature flag
        mPhoneInterfaceManager.setFeatureFlags(mFeatureFlags);
        mPhoneInterfaceManager.setPackageManager(mPackageManager);
        doReturn(mPackageManager).when(mPhoneGlobals).getPackageManager();
        doReturn(true).when(mPackageManager).hasSystemFeature(anyString());
        doReturn(new String[]{sDebugPackageName}).when(mPackageManager).getPackagesForUid(anyInt());

        mPhoneInterfaceManager.setAppOpsManager(mAppOps);
        when(mPhoneGlobals.getSystemServiceName(AppOpsManager.class)).thenReturn(
                Context.APP_OPS_SERVICE);
        when(mPhoneGlobals.getSystemService(AppOpsManager.class)).thenReturn(mAppOps);
        when(mPhoneGlobals.getSystemService(Context.APP_OPS_SERVICE)).thenReturn(mAppOps);
        doNothing().when(mAppOps).checkPackage(anyInt(), anyString());

        when(mPhoneGlobals.getSystemServiceName(android.media.AudioManager.class)).thenReturn(
                Context.AUDIO_SERVICE);
        when(mPhoneGlobals.getSystemService(android.media.AudioManager.class)).thenReturn(
                mAudioManager);
        when(mPhoneGlobals.getSystemService(Context.AUDIO_SERVICE)).thenReturn(mAudioManager);
        when(mAudioManager.isWiredHeadsetOn()).thenReturn(false);
    }

    @Test
    public void cleanUpAllowedNetworkTypes_validPhoneAndSubId_doSetAllowedNetwork() {
        long defaultNetworkType = RadioAccessFamily.getRafFromNetworkType(
                RILConstants.PREFERRED_NETWORK_MODE);

        mPhoneInterfaceManager.cleanUpAllowedNetworkTypes(mPhone, 1);

        verify(mPhone).loadAllowedNetworksFromSubscriptionDatabase();
        verify(mPhone).setAllowedNetworkTypes(TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER,
                defaultNetworkType, null);
    }

    @Test
    public void cleanUpAllowedNetworkTypes_validPhoneAndInvalidSubId_doNotSetAllowedNetwork() {
        long defaultNetworkType = RadioAccessFamily.getRafFromNetworkType(
                RILConstants.PREFERRED_NETWORK_MODE);

        mPhoneInterfaceManager.cleanUpAllowedNetworkTypes(mPhone, -1);

        verify(mPhone, never()).loadAllowedNetworksFromSubscriptionDatabase();
        verify(mPhone, never()).setAllowedNetworkTypes(
                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER, defaultNetworkType, null);
    }

    @Test
    public void matchLocaleFromSupportedLocaleList_inputLocaleChangeToSupportedLocale_notMatched() {
        Context context = mock(Context.class);
        when(mPhone.getContext()).thenReturn(context);
        Resources resources = mock(Resources.class);
        when(context.getResources()).thenReturn(resources);
        when(resources.getStringArray(anyInt()))
                .thenReturn(new String[]{"fi-FI", "ff-Adlm-BF", "en-US"});

        // Input empty string, then return default locale of ICU.
        String resultInputEmpty = mPhoneInterfaceManager.matchLocaleFromSupportedLocaleList(mPhone,
                Locale.forLanguageTag(""));

        assertEquals("und", resultInputEmpty);

        // Input en, then look up the matched supported locale. No matched, so return input locale.
        String resultOnlyLanguage = mPhoneInterfaceManager.matchLocaleFromSupportedLocaleList(
                mPhone,
                Locale.forLanguageTag("en"));

        assertEquals("en", resultOnlyLanguage);
    }

    @Test
    public void matchLocaleFromSupportedLocaleList_inputLocaleChangeToSupportedLocale() {
        Context context = mock(Context.class);
        when(mPhone.getContext()).thenReturn(context);
        Resources resources = mock(Resources.class);
        when(context.getResources()).thenReturn(resources);
        when(resources.getStringArray(anyInt())).thenReturn(new String[]{"zh-Hant-TW"});

        // Input zh-TW, then look up the matched supported locale, zh-Hant-TW, instead.
        String resultInputZhTw = mPhoneInterfaceManager.matchLocaleFromSupportedLocaleList(mPhone,
                Locale.forLanguageTag("zh-TW"));

        assertEquals("zh-Hant-TW", resultInputZhTw);

        when(resources.getStringArray(anyInt())).thenReturn(
                new String[]{"fi-FI", "ff-Adlm-BF", "ff-Latn-BF"});

        // Input ff-BF, then find the matched supported locale, ff-Latn-BF, instead.
        String resultFfBf = mPhoneInterfaceManager.matchLocaleFromSupportedLocaleList(mPhone,
                Locale.forLanguageTag("ff-BF"));

        assertEquals("ff-Latn-BF", resultFfBf);
    }

    @Test
    public void setNullCipherAndIntegrityEnabled_successfullyEnable() {
        whenModemSupportsNullCiphers();
        doReturn(201).when(mPhoneInterfaceManager).getHalVersion(anyInt());
        doNothing().when(mPhoneInterfaceManager).enforceModifyPermission();
        assertFalse(mSharedPreferences.contains(Phone.PREF_NULL_CIPHER_AND_INTEGRITY_ENABLED));

        mPhoneInterfaceManager.setNullCipherAndIntegrityEnabled(true);

        assertTrue(
                mSharedPreferences.getBoolean(Phone.PREF_NULL_CIPHER_AND_INTEGRITY_ENABLED, false));
    }

    @Test
    public void setNullCipherAndIntegrityEnabled_successfullyDisable() {
        whenModemSupportsNullCiphers();
        doReturn(201).when(mPhoneInterfaceManager).getHalVersion(anyInt());
        doNothing().when(mPhoneInterfaceManager).enforceModifyPermission();
        assertFalse(mSharedPreferences.contains(Phone.PREF_NULL_CIPHER_AND_INTEGRITY_ENABLED));

        mPhoneInterfaceManager.setNullCipherAndIntegrityEnabled(false);

        assertFalse(
                mSharedPreferences.getBoolean(Phone.PREF_NULL_CIPHER_AND_INTEGRITY_ENABLED, true));
    }

    @Test
    public void setNullCipherAndIntegrityEnabled_lackingNecessaryHal() {
        doReturn(101).when(mPhoneInterfaceManager).getHalVersion(anyInt());
        doNothing().when(mPhoneInterfaceManager).enforceModifyPermission();

        assertThrows(UnsupportedOperationException.class, () -> {
            mPhoneInterfaceManager.setNullCipherAndIntegrityEnabled(true);
        });

    }

    @Test
    public void setNullCipherAndIntegrityEnabled_lackingPermissions() {
        doReturn(201).when(mPhoneInterfaceManager).getHalVersion(anyInt());
        doThrow(SecurityException.class).when(mPhoneInterfaceManager).enforceModifyPermission();

        assertThrows(SecurityException.class, () -> {
            mPhoneInterfaceManager.setNullCipherAndIntegrityEnabled(true);
        });
    }

    @Test
    public void isNullCipherAndIntegrityPreferenceEnabled() {
        whenModemSupportsNullCiphers();
        doReturn(201).when(mPhoneInterfaceManager).getHalVersion(anyInt());
        doNothing().when(mPhoneInterfaceManager).enforceModifyPermission();

        mPhoneInterfaceManager.setNullCipherAndIntegrityEnabled(false);
        assertFalse(
                mSharedPreferences.getBoolean(Phone.PREF_NULL_CIPHER_AND_INTEGRITY_ENABLED, true));
    }

    @Test
    public void isNullCipherAndIntegrityPreferenceEnabled_lackingNecessaryHal() {
        doReturn(101).when(mPhoneInterfaceManager).getHalVersion(anyInt());
        doNothing().when(mPhoneInterfaceManager).enforceModifyPermission();

        assertThrows(UnsupportedOperationException.class, () -> {
            mPhoneInterfaceManager.isNullCipherAndIntegrityPreferenceEnabled();
        });

    }

    @Test
    public void isNullCipherAndIntegrityPreferenceEnabled_lackingModemSupport() {
        whenModemDoesNotSupportNullCiphers();
        doReturn(201).when(mPhoneInterfaceManager).getHalVersion(anyInt());
        doNothing().when(mPhoneInterfaceManager).enforceModifyPermission();

        assertThrows(UnsupportedOperationException.class, () -> {
            mPhoneInterfaceManager.isNullCipherAndIntegrityPreferenceEnabled();
        });

    }

    @Test
    public void isNullCipherAndIntegrityPreferenceEnabled_lackingPermissions() {
        doReturn(201).when(mPhoneInterfaceManager).getHalVersion(anyInt());
        doThrow(SecurityException.class).when(mPhoneInterfaceManager).enforceReadPermission();

        assertThrows(SecurityException.class, () -> {
            mPhoneInterfaceManager.isNullCipherAndIntegrityPreferenceEnabled();
        });
    }

    private void whenModemDoesNotSupportNullCiphers() {
        doReturn(false).when(mPhone).isNullCipherAndIntegritySupported();
        doReturn(mPhone).when(
                mPhoneInterfaceManager).getDefaultPhone();
    }

    private void whenModemSupportsNullCiphers() {
        doReturn(true).when(mPhone).isNullCipherAndIntegritySupported();
        doReturn(mPhone).when(
                mPhoneInterfaceManager).getDefaultPhone();
    }

    private static void loge(String message) {
        Rlog.e(TAG, message);
    }

    @Test
    public void setNullCipherNotificationsEnabled_allReqsMet_successfullyEnabled() {
        setModemSupportsNullCipherNotification(true);
        doNothing().when(mPhoneInterfaceManager).enforceModifyPermission();
        doReturn(202).when(mPhoneInterfaceManager).getHalVersion(anyInt());
        assertFalse(mSharedPreferences.contains(Phone.PREF_NULL_CIPHER_NOTIFICATIONS_ENABLED));

        mPhoneInterfaceManager.setNullCipherNotificationsEnabled(true);

        assertTrue(
                mSharedPreferences.getBoolean(Phone.PREF_NULL_CIPHER_NOTIFICATIONS_ENABLED, false));
    }

    @Test
    public void setNullCipherNotificationsEnabled_allReqsMet_successfullyDisabled() {
        setModemSupportsNullCipherNotification(true);
        doNothing().when(mPhoneInterfaceManager).enforceModifyPermission();
        doReturn(202).when(mPhoneInterfaceManager).getHalVersion(anyInt());
        assertFalse(mSharedPreferences.contains(Phone.PREF_NULL_CIPHER_NOTIFICATIONS_ENABLED));

        mPhoneInterfaceManager.setNullCipherNotificationsEnabled(false);

        assertFalse(
                mSharedPreferences.getBoolean(Phone.PREF_NULL_CIPHER_NOTIFICATIONS_ENABLED, true));
    }

    @Test
    public void setNullCipherNotificationsEnabled_lackingNecessaryHal_throwsException() {
        setModemSupportsNullCipherNotification(true);
        doNothing().when(mPhoneInterfaceManager).enforceModifyPermission();
        doReturn(102).when(mPhoneInterfaceManager).getHalVersion(anyInt());

        assertThrows(UnsupportedOperationException.class,
                () -> mPhoneInterfaceManager.setNullCipherNotificationsEnabled(true));
    }

    @Test
    public void setNullCipherNotificationsEnabled_lackingModemSupport_throwsException() {
        setModemSupportsNullCipherNotification(false);
        doNothing().when(mPhoneInterfaceManager).enforceModifyPermission();
        doReturn(202).when(mPhoneInterfaceManager).getHalVersion(anyInt());

        assertThrows(UnsupportedOperationException.class,
                () -> mPhoneInterfaceManager.setNullCipherNotificationsEnabled(true));
    }

    @Test
    public void setNullCipherNotificationsEnabled_lackingPermissions_throwsException() {
        setModemSupportsNullCipherNotification(true);
        doReturn(202).when(mPhoneInterfaceManager).getHalVersion(anyInt());
        doThrow(SecurityException.class).when(mPhoneInterfaceManager).enforceModifyPermission();

        assertThrows(SecurityException.class, () ->
                mPhoneInterfaceManager.setNullCipherNotificationsEnabled(true));
    }

    @Test
    public void isNullCipherNotificationsEnabled_allReqsMet_returnsTrue() {
        setModemSupportsNullCipherNotification(true);
        doReturn(202).when(mPhoneInterfaceManager).getHalVersion(anyInt());
        doNothing().when(mPhoneInterfaceManager).enforceReadPrivilegedPermission(anyString());
        doReturn(true).when(mPhone).getNullCipherNotificationsPreferenceEnabled();

        assertTrue(mPhoneInterfaceManager.isNullCipherNotificationsEnabled());
    }

    @Test
    public void isNullCipherNotificationsEnabled_lackingNecessaryHal_throwsException() {
        setModemSupportsNullCipherNotification(true);
        doReturn(102).when(mPhoneInterfaceManager).getHalVersion(anyInt());
        doNothing().when(mPhoneInterfaceManager).enforceReadPrivilegedPermission(anyString());

        assertThrows(UnsupportedOperationException.class, () ->
                mPhoneInterfaceManager.isNullCipherNotificationsEnabled());
    }

    @Test
    public void isNullCipherNotificationsEnabled_lackingModemSupport_throwsException() {
        setModemSupportsNullCipherNotification(false);
        doReturn(202).when(mPhoneInterfaceManager).getHalVersion(anyInt());
        doNothing().when(mPhoneInterfaceManager).enforceReadPrivilegedPermission(anyString());

        assertThrows(UnsupportedOperationException.class, () ->
                mPhoneInterfaceManager.isNullCipherNotificationsEnabled());
    }

    @Test
    public void isNullCipherNotificationsEnabled_lackingPermissions_throwsException() {
        setModemSupportsNullCipherNotification(true);
        doReturn(202).when(mPhoneInterfaceManager).getHalVersion(anyInt());
        doThrow(SecurityException.class).when(
                mPhoneInterfaceManager).enforceReadPrivilegedPermission(anyString());

        assertThrows(SecurityException.class, () ->
                mPhoneInterfaceManager.isNullCipherNotificationsEnabled());
    }

    private void setModemSupportsNullCipherNotification(boolean enable) {
        doReturn(enable).when(mPhone).isNullCipherNotificationSupported();
        doReturn(mPhone).when(mPhoneInterfaceManager).getDefaultPhone();
    }

    @Test
    @EnableFlags(Flags.FLAG_NETWORK_SECURITY_EVENT_INDICATIONS)
    public void getSupportedNetworkAlertCategories_allReqsMet_returnsCategories() {
        doNothing().when(mPhoneInterfaceManager).enforceReadPrivilegedPermission(anyString());
        doReturn(mPhone).when(mPhoneInterfaceManager).getDefaultPhone();
        doReturn(204).when(mPhoneInterfaceManager).getHalVersion(anyInt());

        int[] expectedCategories = new int[]{
                NetworkSecurityEvent.ALERT_CATEGORY_DOWNGRADE,
                NetworkSecurityEvent.ALERT_CATEGORY_IMPRISONMENT
        };
        doReturn(expectedCategories).when(mPhone).getSupportedNetworkAlertCategories();

        int[] actualCategories = mPhoneInterfaceManager.getSupportedNetworkAlertCategories();

        assertArrayEquals(expectedCategories, actualCategories);
    }

    @Test
    public void getSupportedNetworkAlertCategories_lackingHalVersion_throwsException() {
        doNothing().when(mPhoneInterfaceManager).enforceReadPrivilegedPermission(anyString());
        doReturn(mPhone).when(mPhoneInterfaceManager).getDefaultPhone();
        doReturn(203).when(mPhoneInterfaceManager).getHalVersion(anyInt());

        assertThrows(UnsupportedOperationException.class,
                () -> mPhoneInterfaceManager.getSupportedNetworkAlertCategories());
    }

    @Test
    public void getSupportedNetworkAlertCategories_lackingPermissions_throwsException() {
        doThrow(new SecurityException("Test Exception")).when(mPhoneInterfaceManager)
                .enforceReadPrivilegedPermission(anyString());
        doReturn(mPhone).when(mPhoneInterfaceManager).getDefaultPhone();
        doReturn(204).when(mPhoneInterfaceManager).getHalVersion(anyInt());

        assertThrows(SecurityException.class,
                () -> mPhoneInterfaceManager.getSupportedNetworkAlertCategories());
    }

    @Test
    public void getSupportedNetworkAlertCategories_modemUnsupported_returnsEmptyArray() {
        doNothing().when(mPhoneInterfaceManager).enforceReadPrivilegedPermission(anyString());
        doReturn(mPhone).when(mPhoneInterfaceManager).getDefaultPhone();
        doReturn(204).when(mPhoneInterfaceManager).getHalVersion(anyInt());
        doThrow(new UnsupportedOperationException()).when(mPhone)
                .getSupportedNetworkAlertCategories();

        int[] categories = mPhoneInterfaceManager.getSupportedNetworkAlertCategories();

        assertEquals(0, categories.length);
    }


    /**
     * Verify getCarrierRestrictionStatus throws exception for invalid caller package name.
     */
    @Test
    public void getCarrierRestrictionStatus_ReadPrivilegedException2() {
        doThrow(SecurityException.class).when(
                mPhoneInterfaceManager).enforceReadPrivilegedPermission(anyString());
        assertThrows(SecurityException.class, () -> {
            mPhoneInterfaceManager.getCarrierRestrictionStatus(mIIntegerConsumer, "");
        });
    }

    /**
     * Verify getCarrierRestrictionStatus doesn't throw any exception with valid package name
     * and with READ_PHONE_STATE permission granted.
     */
    @Test
    public void getCarrierRestrictionStatus() {
        when(mPhoneInterfaceManager.validateCallerAndGetCarrierIds(anyString())).thenReturn(
                Collections.singleton(1));
        mPhoneInterfaceManager.getCarrierRestrictionStatus(mIIntegerConsumer,
                "com.test.package");
    }

    @Test
    public void notifyEnableDataWithAppOps_enableByUser_doNoteOp() {
        mSetFlagsRule.enableFlags(
                android.permission.flags.Flags.FLAG_OP_ENABLE_MOBILE_DATA_BY_USER);
        String packageName = "INVALID_PACKAGE";
        mPhoneInterfaceManager.setDataEnabledForReason(1,
                TelephonyManager.DATA_ENABLED_REASON_USER, true, packageName);
        verify(mAppOps).noteOpNoThrow(eq(AppOpsManager.OPSTR_ENABLE_MOBILE_DATA_BY_USER), anyInt(),
                eq(packageName), isNull(), isNull());
    }

    @Test
    public void notifyEnableDataWithAppOps_enableByCarrier_doNotNoteOp() {
        mSetFlagsRule.enableFlags(
                android.permission.flags.Flags.FLAG_OP_ENABLE_MOBILE_DATA_BY_USER);
        String packageName = "INVALID_PACKAGE";
        verify(mAppOps, never()).noteOpNoThrow(eq(AppOpsManager.OPSTR_ENABLE_MOBILE_DATA_BY_USER),
                anyInt(), eq(packageName), isNull(), isNull());
    }

    @Test
    public void notifyEnableDataWithAppOps_disableByUser_doNotNoteOp() {
        mSetFlagsRule.enableFlags(
                android.permission.flags.Flags.FLAG_OP_ENABLE_MOBILE_DATA_BY_USER);
        String packageName = "INVALID_PACKAGE";
        String error = "";
        try {
            mPhoneInterfaceManager.setDataEnabledForReason(1,
                    TelephonyManager.DATA_ENABLED_REASON_USER, false, packageName);
        } catch (SecurityException expected) {
            // The test doesn't have access to note the op, but we're just interested that it makes
            // the attempt.
            error = expected.getMessage();
        }
        assertEquals("Expected error to be empty, was " + error, error, "");
    }

    @Test
    public void notifyEnableDataWithAppOps_noPackageNameAndEnableByUser_doNotnoteOp() {
        mSetFlagsRule.enableFlags(
                android.permission.flags.Flags.FLAG_OP_ENABLE_MOBILE_DATA_BY_USER);
        String error = "";
        try {
            mPhoneInterfaceManager.setDataEnabledForReason(1,
                    TelephonyManager.DATA_ENABLED_REASON_USER, false, null);
        } catch (SecurityException expected) {
            // The test doesn't have access to note the op, but we're just interested that it makes
            // the attempt.
            error = expected.getMessage();
        }
        assertEquals("Expected error to be empty, was " + error, error, "");
    }

    @Test
    @EnableCompatChanges({TelephonyManager.ENABLE_FEATURE_MAPPING})
    public void testWithTelephonyFeatureAndCompatChanges() throws Exception {
        mPhoneInterfaceManager.setFeatureFlags(mFeatureFlags);
        doNothing().when(mPhoneInterfaceManager).enforceModifyPermission();

        // FEATURE_TELEPHONY_CALLING
        mPhoneInterfaceManager.getVoiceActivationState(1, "com.test.package");

        // FEATURE_TELEPHONY_RADIO_ACCESS
        mPhoneInterfaceManager.toggleRadioOnOffForSubscriber(1);
    }

    @Test
    @EnableCompatChanges({TelephonyManager.ENABLE_FEATURE_MAPPING})
    public void testWithoutTelephonyFeatureAndCompatChanges() throws Exception {
        // Replace field to set vendor API level to the one where the exceptions are enabled.
        replaceInstance(PhoneInterfaceManager.class, "mVendorApiLevel", mPhoneInterfaceManager,
                TELEPHONY_FEATURE_ENFORCEMENT_VENDOR_API_LEVEL);

        // telephony features is not defined, expect UnsupportedOperationException.
        doReturn(false).when(mPackageManager).hasSystemFeature(
                PackageManager.FEATURE_TELEPHONY_CALLING);
        doReturn(false).when(mPackageManager).hasSystemFeature(
                PackageManager.FEATURE_TELEPHONY_RADIO_ACCESS);
        mPhoneInterfaceManager.setPackageManager(mPackageManager);
        mPhoneInterfaceManager.setFeatureFlags(mFeatureFlags);
        doNothing().when(mPhoneInterfaceManager).enforceModifyPermission();

        assertThrows(UnsupportedOperationException.class,
                () -> mPhoneInterfaceManager.handlePinMmiForSubscriber(1, "123456789"));
        assertThrows(UnsupportedOperationException.class,
                () -> mPhoneInterfaceManager.toggleRadioOnOffForSubscriber(1));
    }

    @Test
    public void testGetCurrentPackageNameWithNoKnownPackage() throws Exception {
        Field field = PhoneInterfaceManager.class.getDeclaredField("mApp");
        field.setAccessible(true);
        Field modifiersField = Field.class.getDeclaredField("accessFlags");
        modifiersField.setAccessible(true);
        modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);
        field.set(mPhoneInterfaceManager, mPhoneGlobals);

        doReturn(mPackageManager).when(mPhoneGlobals).getPackageManager();
        doReturn(null).when(mPackageManager).getPackagesForUid(anyInt());

        String packageName = mPhoneInterfaceManager.getCurrentPackageName();
        assertEquals(null, packageName);
    }

    @Test
    public void testGetSatelliteDataOptimizedApps() throws Exception {
        mPhoneInterfaceManager.setFeatureFlags(mFeatureFlags);
        loge("FeatureFlagApi is set to return true");
        boolean containsCtsApp = false;
        String ctsPackageName = "android.telephony.cts";
        String satelliteCtsPackageName = "android.telephony.satellite.cts";
        List<String> listSatelliteApplications =
                mPhoneInterfaceManager.getSatelliteDataOptimizedApps();

        for (String packageName : listSatelliteApplications) {
            if (ctsPackageName.equals(packageName) || satelliteCtsPackageName.equals(packageName)) {
                containsCtsApp = true;
            }
        }

        assertFalse(containsCtsApp);
    }

    @Test
    public void testGetSlotsMapping() throws Exception {
        doNothing().when(mPhoneInterfaceManager).enforceReadPrivilegedPermission(anyString());
        doReturn(true).when(mPackageManager).hasSystemFeature(anyString());

        // UiccPort info
        UiccPortInfo portInfo = new UiccPortInfo(
                UiccPortInfo.ICCID_REDACTED,
                /* portIndex= */ 0,
                /* logicalSlotIndex= */ 0,
                /* isActive= */ true);

        // eUICC slot info
        UiccSlotInfo[] infos = new UiccSlotInfo[] {new UiccSlotInfo(
                /* isEuicc= */ true,
                /* cardId= */ "CARD_ID",
                UiccSlotInfo.CARD_STATE_INFO_PRESENT,
                /* isExtendedApduSupported= */ true,
                /* isRemovable= */ false,
                List.of(portInfo))};

        doReturn(infos).when(mPhoneInterfaceManager).getUiccSlotsInfo(anyString());

        List<UiccSlotMapping> slotMappings = mPhoneInterfaceManager.getSlotsMapping(anyString());
        assertEquals(slotMappings.size(), infos.length);
        assertEquals(slotMappings.getFirst().getLogicalSlotIndex(), 0);
        assertEquals(slotMappings.getFirst().getPortIndex(), 0);
        assertEquals(slotMappings.getFirst().getPhysicalSlotIndex(), 0);
    }

    @Test
    public void testGetUiccSlotsInfo() throws Exception {
        doNothing().when(mPhoneInterfaceManager).enforceReadPrivilegedPermission(anyString());
        doReturn(true).when(mPackageManager).hasSystemFeature(anyString());

        UiccSlot slot = Mockito.mock(UiccSlot.class);

        doReturn(new UiccSlot[]{slot}).when(mUiccController).getUiccSlots();

        doReturn(true).when(slot).isActive();
        doReturn(IccCardStatus.CardState.CARDSTATE_PRESENT).when(slot).getCardState();
        doReturn("3F979580BFFE8210428031A073BE211797").when(slot).getEid();
        doReturn(null).when(slot).getUiccCard();
        doReturn(new int[] {0}).when(slot).getPortList();
        doReturn(0).when(slot).getPhoneIdFromPortIndex(anyInt());
        doReturn(true).when(slot).isPortActive(anyInt());
        doReturn(true).when(slot).isExtendedApduSupported();
        doReturn(true).when(slot).isEuicc();

        UiccSlotInfo[] infos = mPhoneInterfaceManager.getUiccSlotsInfo(anyString());

        assertEquals(1, infos.length);
        assertEquals("3F979580BFFE8210428031A073BE211797", infos[0].getCardId());

        // only one port is configured, so size should be 1
        Collection<UiccPortInfo> ports = infos[0].getPorts();
        assertEquals(1, ports.size());
        UiccPortInfo portInfo = null;
        if (ports.stream().findFirst().isPresent()) {
            portInfo = ports.stream().findFirst().get();
        }
        assertNotNull(portInfo);
        assertTrue(portInfo.isActive());
        assertEquals(0, portInfo.getPortIndex());
        assertEquals(0, portInfo.getLogicalSlotIndex());
        assertTrue(infos[0].getIsEuicc());
    }

    @Test
    @EnableFlags(Flags.FLAG_SUPPORT_SLOT_SWITCHING_2PSIM_1ESIM_CONFIG)
    public void testGetSlotsMapping_enableFlag() throws Exception {
        doNothing().when(mPhoneInterfaceManager).enforceReadPrivilegedPermission(anyString());
        doReturn(true).when(mPackageManager).hasSystemFeature(anyString());
        doReturn(true).when(mFeatureFlags).supportSlotSwitching2psim1esimConfig();
        // UiccPort info
        UiccPortInfo portInfo = new UiccPortInfo(
                UiccPortInfo.ICCID_REDACTED,
                /* portIndex= */ 0,
                /* logicalSlotIndex= */ 0,
                /* isActive= */ true);

        // eUICC slot info
        UiccSlotInfo[] infos = new UiccSlotInfo[] {new UiccSlotInfo(
                /* isEuicc= */ true,
                /* cardId= */ "CARD_ID",
                UiccSlotInfo.CARD_STATE_INFO_PRESENT,
                /* isExtendedApduSupported= */ true,
                /* isRemovable= */ false,
                List.of(portInfo),
                TelephonyManager.SIM_TYPE_EMBEDDED,
                new int[] {TelephonyManager.SIM_TYPE_EMBEDDED})};

        doReturn(infos).when(mPhoneInterfaceManager).getUiccSlotsInfo(anyString());

        List<UiccSlotMapping> slotMappings = mPhoneInterfaceManager.getSlotsMapping(anyString());
        assertEquals(slotMappings.size(), infos.length);
        assertEquals(0, slotMappings.getFirst().getLogicalSlotIndex());
        assertEquals(0, slotMappings.getFirst().getPortIndex());
        assertEquals(0, slotMappings.getFirst().getPhysicalSlotIndex());
        assertEquals(TelephonyManager.SIM_TYPE_EMBEDDED, slotMappings.getFirst().getSimType());
    }

    @Test
    @EnableFlags(Flags.FLAG_SUPPORT_SLOT_SWITCHING_2PSIM_1ESIM_CONFIG)
    public void testGetUiccSlotsInfo_enableFlag() throws Exception {
        doNothing().when(mPhoneInterfaceManager).enforceReadPrivilegedPermission(anyString());
        doReturn(true).when(mPackageManager).hasSystemFeature(anyString());
        doReturn(true).when(mFeatureFlags).supportSlotSwitching2psim1esimConfig();

        UiccSlot slot = Mockito.mock(UiccSlot.class);

        doReturn(new UiccSlot[]{slot}).when(mUiccController).getUiccSlots();

        doReturn(true).when(slot).isActive();
        doReturn(IccCardStatus.CardState.CARDSTATE_PRESENT).when(slot).getCardState();
        doReturn("3F979580BFFE8210428031A073BE211797").when(slot).getEid();
        doReturn(null).when(slot).getUiccCard();
        doReturn(new int[] {0}).when(slot).getPortList();
        doReturn(TelephonyManager.SIM_TYPE_EMBEDDED).when(slot).getSimType();
        doReturn(new int[] {TelephonyManager.SIM_TYPE_EMBEDDED}).when(slot).getSupportedSimTypes();
        doReturn(0).when(slot).getPhoneIdFromPortIndex(anyInt());
        doReturn(true).when(slot).isPortActive(anyInt());
        doReturn(true).when(slot).isExtendedApduSupported();
        doReturn(true).when(slot).isEuicc();


        UiccSlotInfo[] infos = mPhoneInterfaceManager.getUiccSlotsInfo(anyString());

        assertEquals(1, infos.length);
        assertEquals("3F979580BFFE8210428031A073BE211797", infos[0].getCardId());
        assertEquals(TelephonyManager.SIM_TYPE_EMBEDDED, infos[0].getSimType());
        int[] expectedSimTypes = {TelephonyManager.SIM_TYPE_EMBEDDED};
        int[] actualSimTypes = infos[0].getSupportedSimTypes();
        assertNotNull(actualSimTypes);
        assertEquals(1, actualSimTypes.length);
        assertArrayEquals(expectedSimTypes, actualSimTypes);
    }

    @Test
    public void testGetCurrentTtyMode_headsetNotConnected_returnsTtyOff() {
        // Setup: Mock permissions and feature checks to pass
        doNothing().when(mPhoneInterfaceManager).enforceReadPrivilegedPermission(anyString());

        // Set the TTY mode setting to a specific value (not OFF)
        int preferredTtyMode = TelephonyManager.TTY_MODE_HCO;
        Settings.Secure.putIntForUser(mPhoneGlobals.getContentResolver(),
                Settings.Secure.PREFERRED_TTY_MODE, preferredTtyMode, mPhoneGlobals.getUserId());

        // Mock AudioManager to report no wired headset
        when(mAudioManager.isWiredHeadsetOn()).thenReturn(false);

        // Action: Call the method under test
        int actualTtyMode = mPhoneInterfaceManager.getCurrentTtyMode();

        // Assert: TTY_MODE_OFF is returned because headset is not connected
        assertEquals(TelephonyManager.TTY_MODE_OFF, actualTtyMode);

        // Cleanup
        Settings.Secure.putIntForUser(mPhoneGlobals.getContentResolver(),
                Settings.Secure.PREFERRED_TTY_MODE, TelephonyManager.TTY_MODE_OFF,
                mPhoneGlobals.getUserId());
    }

    @Test
    public void testGetCurrentTtyMode_headsetConnected_returnsCorrectValue() {
        // Setup: Mock permissions and feature checks to pass
        doNothing().when(mPhoneInterfaceManager).enforceReadPrivilegedPermission(anyString());

        // Set the TTY mode setting to a specific value
        int expectedTtyMode = TelephonyManager.TTY_MODE_HCO;
        Settings.Secure.putIntForUser(mPhoneGlobals.getContentResolver(),
                Settings.Secure.PREFERRED_TTY_MODE, expectedTtyMode, mPhoneGlobals.getUserId());

        // Mock AudioManager to report wired headset is connected
        when(mAudioManager.isWiredHeadsetOn()).thenReturn(true);

        // Action: Call the method under test
        int actualTtyMode = mPhoneInterfaceManager.getCurrentTtyMode();

        // Assert: The correct TTY mode is returned because headset is connected
        assertEquals(expectedTtyMode, actualTtyMode);

        // Cleanup
        Settings.Secure.putIntForUser(mPhoneGlobals.getContentResolver(),
                Settings.Secure.PREFERRED_TTY_MODE, TelephonyManager.TTY_MODE_OFF,
                mPhoneGlobals.getUserId());
    }

    @Test
    public void testGetCurrentTtyMode_settingNotFound_returnsDefault() {
        // Setup: Mock permissions and feature checks to pass
        doNothing().when(mPhoneInterfaceManager).enforceReadPrivilegedPermission(anyString());

        // Ensure the setting is not present
        Settings.Secure.putIntForUser(mPhoneGlobals.getContentResolver(),
                Settings.Secure.PREFERRED_TTY_MODE, TelephonyManager.TTY_MODE_OFF,
                mPhoneGlobals.getUserId());

        // Action: Call the method under test
        int actualTtyMode = mPhoneInterfaceManager.getCurrentTtyMode();

        // Assert: The default TTY mode is returned
        assertEquals(TelephonyManager.TTY_MODE_OFF, actualTtyMode);
    }

    @Test
    public void testGetCurrentTtyMode_noPermission_throwsSecurityException() {
        // Setup: Mock permission check to fail
        doThrow(new SecurityException("Test Exception")).when(mPhoneInterfaceManager)
                .enforceReadPrivilegedPermission(anyString());

        // Action & Assert: Expect a SecurityException
        assertThrows(SecurityException.class, () -> mPhoneInterfaceManager.getCurrentTtyMode());
    }

    @Test
    public void testGetCurrentTtyMode_featureNotSupported_throwsException() throws Exception {
        // Setup: Mock permissions to pass, but feature check to fail
        doNothing().when(mPhoneInterfaceManager).enforceReadPrivilegedPermission(anyString());
        replaceInstance(PhoneInterfaceManager.class, "mVendorApiLevel", mPhoneInterfaceManager,
                TELEPHONY_FEATURE_ENFORCEMENT_VENDOR_API_LEVEL);
        doReturn(false).when(mPackageManager)
                .hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING);

        // Action & Assert: Expect an UnsupportedOperationException
        assertThrows(UnsupportedOperationException.class,
                () -> mPhoneInterfaceManager.getCurrentTtyMode());
    }

    @Test
    public void uncapMaxAllowedSatelliteDataMode_noShellPermission_throwsSecurityException() {
        // This method is protected by TelephonyPermissions.enforceShellOnly.
        // The test runner does not have shell UID, so this should throw a SecurityException.
        // This test verifies that the permission check is in place.
        assertThrows(SecurityException.class,
                () -> mPhoneInterfaceManager.uncapMaxAllowedSatelliteDataMode());

        // Verify that the underlying controller method is not called due to permission failure.
        verify(mSatelliteController, never()).uncapMaxAllowedDataMode();
    }

    @Test
    public void testGetSimAutoPinManagementEnrollmentStatus_noSubscription() {
        doReturn(null).when(mSubscriptionManagerService).getSubscriptionInfo(1);

        assertThrows(IllegalArgumentException.class, () -> {
            mPhoneInterfaceManager.getSimAutoPinManagementEnrollmentStatus(1);
        });
    }

    private void addSubscriptionInfo(int subId, String cardString) {
        SubscriptionInfo.Builder siBuilder = new SubscriptionInfo.Builder();
        siBuilder.setCardString(cardString).setId(subId).setSimSlotIndex(0);

        SubscriptionInfo si = siBuilder.build();
        doReturn(si).when(mSubscriptionManagerService).getSubscriptionInfo(subId);
    }

    @Test
    public void testGetSimAutoPinManagementEnrollmentStatus_notEnrolled() throws Exception {
        addSubscriptionInfo(1, CARD_STRING);

        doReturn(false).when(mPinStorage).isPinPlatformManaged(CARD_STRING);

        assertEquals(TelephonyManager.SIM_PIN_ENROLLMENT_STATUS_MANUALLY_MANAGED,
                mPhoneInterfaceManager.getSimAutoPinManagementEnrollmentStatus(1));
    }

    @Test
    public void testGetSimAutoPinManagementEnrollmentStatus_isPlatformManaged() throws Exception {
        addSubscriptionInfo(1, CARD_STRING);

        doReturn(true).when(mPinStorage).isPinPlatformManaged(CARD_STRING);

        assertEquals(TelephonyManager.SIM_PIN_ENROLLMENT_STATUS_PLATFORM_MANAGED,
                mPhoneInterfaceManager.getSimAutoPinManagementEnrollmentStatus(1));
    }

    void setupPhoneGlobalsToThrowWhenCheckingControlSimAutoPinManagementPermission() {
        doThrow(SecurityException.class).when(
                mPhoneGlobals).enforceCallingOrSelfPermission(
                eq(Manifest.permission.CONTROL_SIM_AUTO_PIN_MANAGEMENT), anyString());
    }

    @Test
    public void enrollSimInAutoPinManagement_noPermission() {
        setupPhoneGlobalsToThrowWhenCheckingControlSimAutoPinManagementPermission();

        assertThrows(SecurityException.class, () -> {
            mPhoneInterfaceManager.enrollSimInAutoPinManagement(1, "1234",
                    mock(ResultReceiver.class));
        });
    }

    @Test
    public void unenrollSimFromAutoPinManagement_noPermission() {
        setupPhoneGlobalsToThrowWhenCheckingControlSimAutoPinManagementPermission();

        assertThrows(SecurityException.class, () -> {
            mPhoneInterfaceManager.unenrollSimFromAutoPinManagement(1, mock(ResultReceiver.class));
        });
    }

    @Test
    public void getAutoManagedPinForSim_noPermission() {
        setupPhoneGlobalsToThrowWhenCheckingControlSimAutoPinManagementPermission();

        assertThrows(SecurityException.class, () -> {
            mPhoneInterfaceManager.getAutoManagedPinForSim(1, mock(ResultReceiver.class));
        });
    }

    void setupPhoneGlobalsToDoNothingWhenCheckingControlSimAutoPinManagementPermission() {
        doNothing().when(mPhoneGlobals).enforceCallingOrSelfPermission(
                eq(Manifest.permission.CONTROL_SIM_AUTO_PIN_MANAGEMENT),
                anyString());
    }

    @Test
    public void enrollSimInAutoPinManagement_failsIfInvalidSubscription() {
        doReturn(null).when(mSubscriptionManagerService).getSubscriptionInfo(2);
        setupPhoneGlobalsToDoNothingWhenCheckingControlSimAutoPinManagementPermission();

        ResultReceiver receiver = mock(ResultReceiver.class);
        mPhoneInterfaceManager.enrollSimInAutoPinManagement(2, "1234", receiver);
        verify(receiver).send(eq(SIM_PIN_ENROLLMENT_RESULT_FAILED_INVALID_SIM), isNotNull());
    }

    @Test
    public void enrollSimInAutoPinManagement_failsIfIccLockEnabled() {
        addSubscriptionInfo(1, CARD_STRING);
        setupPhoneGlobalsToDoNothingWhenCheckingControlSimAutoPinManagementPermission();
        doReturn(true).when(mPhoneInterfaceManager).isIccLockEnabled(1);

        ResultReceiver receiver = mock(ResultReceiver.class);
        mPhoneInterfaceManager.enrollSimInAutoPinManagement(1, "1234", receiver);
        verify(receiver).send(eq(SIM_PIN_ENROLLMENT_RESULT_FAILED_SIM_LOCK_ALREADY_ACTIVE),
                isNotNull());
    }

    @Test
    public void enrollSimInAutoPinManagement_failsIfCannotEnableIccLock() {
        addSubscriptionInfo(1, CARD_STRING);
        setupPhoneGlobalsToDoNothingWhenCheckingControlSimAutoPinManagementPermission();
        doReturn(false).when(mPhoneInterfaceManager).isIccLockEnabled(1);
        doReturn(2).when(mPhoneInterfaceManager).setIccLockEnabled(1, true, "1234");

        ArgumentCaptor<Bundle> captor = ArgumentCaptor.forClass(Bundle.class);

        ResultReceiver receiver = mock(ResultReceiver.class);
        mPhoneInterfaceManager.enrollSimInAutoPinManagement(1, "1234", receiver);
        verify(receiver).send(eq(SIM_PIN_ENROLLMENT_RESULT_FAILED_WRONG_PIN), captor.capture());
        Bundle received = captor.getValue();
        assertEquals(2, received.getInt(TelephonyManager.KEY_MANAGED_SIM_PIN_ENROLLMENT_ATTEMPTS));
    }

    @Test
    public void enrollSimInAutoPinManagement_failsIfCannotChangeIccLock() {
        addSubscriptionInfo(1, CARD_STRING);
        setupPhoneGlobalsToDoNothingWhenCheckingControlSimAutoPinManagementPermission();
        doReturn(false).when(mPhoneInterfaceManager).isIccLockEnabled(1);
        doReturn(CHANGE_ICC_LOCK_SUCCESS).when(mPhoneInterfaceManager).setIccLockEnabled(1, true,
                "1234");
        doReturn(2).when(mPhoneInterfaceManager).changeIccLockPassword(eq(1), eq("1234"),
                anyString());

        ArgumentCaptor<Bundle> captor = ArgumentCaptor.forClass(Bundle.class);
        ResultReceiver receiver = mock(ResultReceiver.class);
        mPhoneInterfaceManager.enrollSimInAutoPinManagement(1, "1234", receiver);
        verify(receiver).send(eq(SIM_PIN_ENROLLMENT_RESULT_FAILED_WRONG_PIN), captor.capture());
        Bundle received = captor.getValue();
        assertEquals(2, received.getInt(TelephonyManager.KEY_MANAGED_SIM_PIN_ENROLLMENT_ATTEMPTS));
    }

    @Test
    public void enrollSimInAutoPinManagement_succeeds() {
        addSubscriptionInfo(1, CARD_STRING);
        setupPhoneGlobalsToDoNothingWhenCheckingControlSimAutoPinManagementPermission();
        doReturn(false).when(mPhoneInterfaceManager).isIccLockEnabled(1);
        doReturn(CHANGE_ICC_LOCK_SUCCESS).when(mPhoneInterfaceManager).setIccLockEnabled(1, true,
                "1234");

        doReturn(CHANGE_ICC_LOCK_SUCCESS).when(mPhoneInterfaceManager).changeIccLockPassword(eq(1),
                eq("1234"),
                anyString());

        ArgumentCaptor<Bundle> captor = ArgumentCaptor.forClass(Bundle.class);
        ResultReceiver receiver = mock(ResultReceiver.class);
        mPhoneInterfaceManager.enrollSimInAutoPinManagement(1, "1234", receiver);
        verify(mPinStorage).storePlatformManagedPin(eq(0), anyString(), eq("1234"));
        verify(receiver).send(eq(SIM_PIN_ENROLLMENT_RESULT_SUCCESSFUL), captor.capture());
        Bundle received = captor.getValue();
        assertEquals(4, received.getString(
                TelephonyManager.KEY_MANAGED_SIM_PIN_ENROLLMENT_GENERATED_PIN).length());
    }

    @Test
    public void unenrollSimInAutoPinManagement_failsIfInvalidSubscription() {
        doReturn(null).when(mSubscriptionManagerService).getSubscriptionInfo(2);
        setupPhoneGlobalsToDoNothingWhenCheckingControlSimAutoPinManagementPermission();

        ResultReceiver receiver = mock(ResultReceiver.class);
        mPhoneInterfaceManager.unenrollSimFromAutoPinManagement(2, receiver);
        verify(receiver).send(eq(SIM_PIN_UNENROLLMENT_RESULT_FAILED_SIM_NOT_PRESENT), isNotNull());
    }

    @Test
    public void unenrollSimInAutoPinManagement_failsIfNotPlatformManaged() {
        addSubscriptionInfo(1, CARD_STRING);
        setupPhoneGlobalsToDoNothingWhenCheckingControlSimAutoPinManagementPermission();
        doReturn(false).when(mPinStorage).isPinPlatformManaged(eq(CARD_STRING));

        ResultReceiver receiver = mock(ResultReceiver.class);
        mPhoneInterfaceManager.unenrollSimFromAutoPinManagement(1, receiver);
        verify(receiver).send(eq(SIM_PIN_UNENROLLMENT_RESULT_FAILED_NOT_ENROLLED), isNotNull());
    }

    @Test
    public void unenrollSimInAutoPinManagement_failsIfNoPin() {
        addSubscriptionInfo(1, CARD_STRING);
        setupPhoneGlobalsToDoNothingWhenCheckingControlSimAutoPinManagementPermission();
        doReturn(true).when(mPinStorage).isPinPlatformManaged(eq(CARD_STRING));
        doReturn("").when(mPinStorage).getPin(eq(0), eq(CARD_STRING));

        ResultReceiver receiver = mock(ResultReceiver.class);
        mPhoneInterfaceManager.unenrollSimFromAutoPinManagement(1, receiver);
        verify(receiver).send(eq(SIM_PIN_UNENROLLMENT_RESULT_FAILED_PIN_UNAVAILABLE), isNotNull());
    }

    @Test
    public void unenrollSimInAutoPinManagement_failsIfNoOld() {
        addSubscriptionInfo(1, CARD_STRING);
        setupPhoneGlobalsToDoNothingWhenCheckingControlSimAutoPinManagementPermission();
        doReturn(true).when(mPinStorage).isPinPlatformManaged(eq(CARD_STRING));
        doReturn("1234").when(mPinStorage).getPin(eq(0), eq(CARD_STRING));
        doReturn("").when(mPinStorage).getOldPin(eq(CARD_STRING));

        ResultReceiver receiver = mock(ResultReceiver.class);
        mPhoneInterfaceManager.unenrollSimFromAutoPinManagement(1, receiver);
        verify(receiver).send(eq(SIM_PIN_UNENROLLMENT_RESULT_FAILED_PIN_UNAVAILABLE), isNotNull());
    }

    @Test
    public void unenrollSimInAutoPinManagement_failsIfFailingToChangePin() {
        addSubscriptionInfo(1, CARD_STRING);
        setupPhoneGlobalsToDoNothingWhenCheckingControlSimAutoPinManagementPermission();
        doReturn(true).when(mPinStorage).isPinPlatformManaged(eq(CARD_STRING));
        doReturn("1234").when(mPinStorage).getPin(eq(0), eq(CARD_STRING));
        doReturn("0000").when(mPinStorage).getOldPin(eq(CARD_STRING));
        doReturn(2).when(mPhoneInterfaceManager).changeIccLockPassword(eq(1), eq("1234"),
                eq("0000"));

        ResultReceiver receiver = mock(ResultReceiver.class);
        mPhoneInterfaceManager.unenrollSimFromAutoPinManagement(1, receiver);
        verify(receiver).send(eq(SIM_PIN_UNENROLLMENT_RESULT_FAILED_CANNOT_CHANGE_PIN),
                isNotNull());
    }

    @Test
    public void unenrollSimInAutoPinManagement_failsIfFailingToDisableLock() {
        addSubscriptionInfo(1, CARD_STRING);
        setupPhoneGlobalsToDoNothingWhenCheckingControlSimAutoPinManagementPermission();
        doReturn(true).when(mPinStorage).isPinPlatformManaged(eq(CARD_STRING));
        doReturn("1234").when(mPinStorage).getPin(eq(0), eq(CARD_STRING));
        doReturn("0000").when(mPinStorage).getOldPin(eq(CARD_STRING));
        doReturn(CHANGE_ICC_LOCK_SUCCESS).when(mPhoneInterfaceManager).changeIccLockPassword(eq(1),
                eq("1234"),
                eq("0000"));
        doReturn(2).when(mPhoneInterfaceManager).setIccLockEnabled(1, false,
                "0000");

        ResultReceiver receiver = mock(ResultReceiver.class);
        mPhoneInterfaceManager.unenrollSimFromAutoPinManagement(1, receiver);
        verify(receiver).send(eq(SIM_PIN_UNENROLLMENT_RESULT_FAILED_CANNOT_DISABLE_PIN),
                isNotNull());
        verify(mPinStorage).clearPlatformManagedPin(eq(0));
    }

    @Test
    public void unenrollSimInAutoPinManagement_succeeds() {
        addSubscriptionInfo(1, CARD_STRING);
        setupPhoneGlobalsToDoNothingWhenCheckingControlSimAutoPinManagementPermission();
        doReturn(true).when(mPinStorage).isPinPlatformManaged(eq(CARD_STRING));
        doReturn("1234").when(mPinStorage).getPin(eq(0), eq(CARD_STRING));
        doReturn("0000").when(mPinStorage).getOldPin(eq(CARD_STRING));
        doReturn(CHANGE_ICC_LOCK_SUCCESS).when(mPhoneInterfaceManager).changeIccLockPassword(eq(1),
                eq("1234"),
                eq("0000"));
        doReturn(CHANGE_ICC_LOCK_SUCCESS).when(mPhoneInterfaceManager).setIccLockEnabled(1, false,
                "0000");

        ResultReceiver receiver = mock(ResultReceiver.class);
        mPhoneInterfaceManager.unenrollSimFromAutoPinManagement(1, receiver);
        verify(receiver).send(eq(SIM_PIN_UNENROLLMENT_RESULT_SUCCESSFUL), isNotNull());
        verify(mPinStorage).clearPlatformManagedPin(eq(0));
    }

    @Test
    public void getAutoManagedPinForSim_failsIfnoSubscription() {
        doReturn(null).when(mSubscriptionManagerService).getSubscriptionInfo(2);
        setupPhoneGlobalsToDoNothingWhenCheckingControlSimAutoPinManagementPermission();

        ResultReceiver receiver = mock(ResultReceiver.class);
        mPhoneInterfaceManager.getAutoManagedPinForSim(2, receiver);
        verify(receiver).send(eq(GET_AUTO_MANAGED_PIN_RESULT_FAILED_NOT_ENROLLED), isNotNull());
    }

    @Test
    public void getAutoManagedPinForSim_failsIfnotPlatformManaged() {
        addSubscriptionInfo(1, CARD_STRING);
        setupPhoneGlobalsToDoNothingWhenCheckingControlSimAutoPinManagementPermission();
        doReturn(false).when(mPinStorage).isPinPlatformManaged(eq(CARD_STRING));
        ResultReceiver receiver = mock(ResultReceiver.class);
        mPhoneInterfaceManager.getAutoManagedPinForSim(2, receiver);
        verify(receiver).send(eq(GET_AUTO_MANAGED_PIN_RESULT_FAILED_NOT_ENROLLED), isNotNull());
    }

    @Test
    public void getAutoManagedPinForSim_failsIfNotAuthenticated() {
        addSubscriptionInfo(1, CARD_STRING);
        setupPhoneGlobalsToDoNothingWhenCheckingControlSimAutoPinManagementPermission();
        doReturn(true).when(mPinStorage).isPinPlatformManaged(eq(CARD_STRING));
        doReturn("").when(mPinStorage).getPin(eq(0), eq(CARD_STRING));

        ResultReceiver receiver = mock(ResultReceiver.class);
        mPhoneInterfaceManager.getAutoManagedPinForSim(1, receiver);
        verify(receiver).send(eq(GET_AUTO_MANAGED_PIN_RESULT_USER_AUTH_REQUIRED), isNotNull());
    }

    @Test
    public void getAutoManagedPinForSim_succeeds() {
        addSubscriptionInfo(1, CARD_STRING);
        setupPhoneGlobalsToDoNothingWhenCheckingControlSimAutoPinManagementPermission();
        doReturn(true).when(mPinStorage).isPinPlatformManaged(eq(CARD_STRING));
        doReturn("5678").when(mPinStorage).getPin(eq(0), eq(CARD_STRING));

        ArgumentCaptor<Bundle> captor = ArgumentCaptor.forClass(Bundle.class);
        ResultReceiver receiver = mock(ResultReceiver.class);
        mPhoneInterfaceManager.getAutoManagedPinForSim(1, receiver);
        verify(receiver).send(eq(GET_AUTO_MANAGED_PIN_RESULT_SUCCESSFUL), captor.capture());
        Bundle received = captor.getValue();
        assertEquals("5678", received.getString(
                TelephonyManager.KEY_MANAGED_SIM_PIN_ENROLLMENT_GENERATED_PIN));
    }

    @Test
    public void testRequestEnableSatellite_Auto() {
        final int subId = 1;

        // Setup attributes for automatic mode
        EnableRequestAttributes attributes = new EnableRequestAttributes.Builder(true)
                .setConnectType(CarrierConfigManager.CARRIER_ROAMING_NTN_CONNECT_AUTOMATIC)
                .setSatelliteEnablementRequestReason(
                        SatelliteManager.SATELLITE_ENABLEMENT_REQUEST_REASON_USER
                )
                .build();

        // Mock permission check
        doNothing().when(mPhoneGlobals).enforceCallingOrSelfPermission(
                eq(Manifest.permission.SATELLITE_COMMUNICATION), anyString());

        // Call requestEnableSatellite
        mPhoneInterfaceManager.requestEnableSatellite(subId, attributes, mIIntegerConsumer);

        // Verify mSatelliteController.requestEnableSatelliteForCarrier is called
        verify(mSatelliteController).requestEnableSatelliteForCarrier(eq(subId),
                eq(true),
                eq(SatelliteManager.SATELLITE_COMMUNICATION_RESTRICTION_REASON_USER),
                eq(mIIntegerConsumer));
        // Verify mSatelliteController.requestSatelliteEnabled is NOT called
        verify(mSatelliteController, never())
                .requestSatelliteEnabled(anyBoolean(), anyBoolean(), anyBoolean(), any());
    }

    @Test
    public void testRequestEnableSatellite_Auto_NotUser() throws Exception {
        final int subId = 1;
        int[] nonUserReasons = {
                SatelliteManager.SATELLITE_ENABLEMENT_REQUEST_REASON_UNKNOWN,
                SatelliteManager.SATELLITE_ENABLEMENT_REQUEST_REASON_PURCHASE,
                SatelliteManager.SATELLITE_ENABLEMENT_REQUEST_REASON_POWER,
                SatelliteManager.SATELLITE_ENABLEMENT_REQUEST_REASON_CARRIER_CONFIG_UPDATE,
                SatelliteManager.SATELLITE_ENABLEMENT_REQUEST_REASON_ENTITLEMENT
        };

        for (int reason : nonUserReasons) {
            // Setup attributes for automatic mode but reason not user
            EnableRequestAttributes attributes = new EnableRequestAttributes.Builder(true)
                    .setConnectType(CarrierConfigManager.CARRIER_ROAMING_NTN_CONNECT_AUTOMATIC)
                    .setSatelliteEnablementRequestReason(reason)
                    .build();

            // Mock permission check
            doNothing().when(mPhoneGlobals).enforceCallingOrSelfPermission(
                    eq(Manifest.permission.SATELLITE_COMMUNICATION), anyString());

            // Call requestEnableSatellite
            mPhoneInterfaceManager.requestEnableSatellite(subId, attributes, mIIntegerConsumer);

            // Verify mIIntegerConsumer.accept is called with SATELLITE_RESULT_REQUEST_NOT_SUPPORTED
            verify(mIIntegerConsumer).accept(
                    eq(SatelliteManager.SATELLITE_RESULT_REQUEST_NOT_SUPPORTED));

            clearInvocations(mIIntegerConsumer);
        }

        // Verify neither requestEnableSatelliteForCarrier nor requestSatelliteEnabled is called
        verify(mSatelliteController, never()).requestEnableSatelliteForCarrier(anyInt(),
                anyBoolean(), anyInt(), any());
        verify(mSatelliteController, never()).requestSatelliteEnabled(anyBoolean(),
                anyBoolean(), anyBoolean(), any());
    }

    @Test
    public void testHandleUssdRequest_SubscriptionNotAssociatedWithUser_ThrowsSecurityException() {
        int subId = 1;
        String ussdRequest = "*121#";
        ResultReceiver callback = mock(ResultReceiver.class);

        // Mock subscription NOT associated with user
        doReturn(false)
                .when(mSubscriptionManagerService)
                .isSubscriptionAssociatedWithUser(eq(subId), any(UserHandle.class));

        assertThrows(
                SecurityException.class,
                () -> mPhoneInterfaceManager.handleUssdRequest(subId, ussdRequest, callback));
    }

    @Test
    public void testHandleUssdRequest_ValidSubscription_Success() {
        int subId = 1;
        String ussdRequest = "*121#";
        ResultReceiver callback = mock(ResultReceiver.class);

        // Mock subscription IS associated with user
        doReturn(true)
                .when(mSubscriptionManagerService)
                .isSubscriptionAssociatedWithUser(eq(subId), any(UserHandle.class));
        SubscriptionManager sm = (SubscriptionManager) mContext.getSystemService(
                Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        doReturn(true).when(sm).isSubscriptionAssociatedWithUser(eq(subId), any(UserHandle.class));

        doReturn(true)
                .when(mPackageManager)
                .hasSystemFeature(PackageManager.FEATURE_TELEPHONY_RADIO_ACCESS);

        // Should not throw SecurityException. It will throw RuntimeException because of deadlock
        // since we are calling it from the main thread. Reaching that point means the security
        // check passed.
        try {
            mPhoneInterfaceManager.handleUssdRequest(subId, ussdRequest, callback);
        } catch (RuntimeException e) {
            if (!e.getMessage().contains("deadlock")) {
                throw e;
            }
        }
    }
}
