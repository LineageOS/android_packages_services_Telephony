/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.phone.satellite.accesscontrol;

import static android.location.LocationManager.MODE_CHANGED_ACTION;
import static android.telephony.SubscriptionManager.DEFAULT_SUBSCRIPTION_ID;
import static android.telephony.satellite.SatelliteManager.KEY_SATELLITE_ACCESS_CONFIGURATION;
import static android.telephony.satellite.SatelliteManager.KEY_SATELLITE_COMMUNICATION_ALLOWED;
import static android.telephony.satellite.SatelliteManager.KEY_SATELLITE_PROVISIONED;
import static android.telephony.satellite.SatelliteManager.KEY_SATELLITE_SUPPORTED;
import static android.telephony.satellite.SatelliteManager.SATELLITE_RESULT_ACCESS_BARRED;
import static android.telephony.satellite.SatelliteManager.SATELLITE_RESULT_ERROR;
import static android.telephony.satellite.SatelliteManager.SATELLITE_RESULT_LOCATION_DISABLED;
import static android.telephony.satellite.SatelliteManager.SATELLITE_RESULT_LOCATION_NOT_AVAILABLE;
import static android.telephony.satellite.SatelliteManager.SATELLITE_RESULT_MODEM_ERROR;
import static android.telephony.satellite.SatelliteManager.SATELLITE_RESULT_NOT_SUPPORTED;
import static android.telephony.satellite.SatelliteManager.SATELLITE_RESULT_NO_RESOURCES;
import static android.telephony.satellite.SatelliteManager.SATELLITE_RESULT_REQUEST_NOT_SUPPORTED;
import static android.telephony.satellite.SatelliteManager.SATELLITE_RESULT_SUCCESS;

import static com.android.phone.satellite.accesscontrol.SatelliteAccessController.ALLOWED_STATE_CACHE_VALID_DURATION_NANOS;
import static com.android.phone.satellite.accesscontrol.SatelliteAccessController.CMD_IS_SATELLITE_COMMUNICATION_ALLOWED;
import static com.android.phone.satellite.accesscontrol.SatelliteAccessController.CONFIG_UPDATER_SATELLITE_VERSION_KEY;
import static com.android.phone.satellite.accesscontrol.SatelliteAccessController.DEFAULT_DELAY_MINUTES_BEFORE_VALIDATING_POSSIBLE_CHANGE_IN_ALLOWED_REGION;
import static com.android.phone.satellite.accesscontrol.SatelliteAccessController.DEFAULT_MAX_RETRY_COUNT_FOR_VALIDATING_POSSIBLE_CHANGE_IN_ALLOWED_REGION;
import static com.android.phone.satellite.accesscontrol.SatelliteAccessController.DEFAULT_REGIONAL_SATELLITE_CONFIG_ID;
import static com.android.phone.satellite.accesscontrol.SatelliteAccessController.DEFAULT_S2_LEVEL;
import static com.android.phone.satellite.accesscontrol.SatelliteAccessController.DEFAULT_THROTTLE_INTERVAL_FOR_LOCATION_QUERY_MINUTES;
import static com.android.phone.satellite.accesscontrol.SatelliteAccessController.EVENT_CONFIG_DATA_UPDATED;
import static com.android.phone.satellite.accesscontrol.SatelliteAccessController.EVENT_COUNTRY_CODE_CHANGED;
import static com.android.phone.satellite.accesscontrol.SatelliteAccessController.EVENT_KEEP_ON_DEVICE_ACCESS_CONTROLLER_RESOURCES_TIMEOUT;
import static com.android.phone.satellite.accesscontrol.SatelliteAccessController.EVENT_WAIT_FOR_CURRENT_LOCATION_TIMEOUT;
import static com.android.phone.satellite.accesscontrol.SatelliteAccessController.GOOGLE_US_SAN_SAT_S2_FILE_NAME;
import static com.android.phone.satellite.accesscontrol.SatelliteAccessController.SATELLITE_ACCESS_CONFIG_FILE_NAME;
import static com.android.phone.satellite.accesscontrol.SatelliteAccessController.UNKNOWN_REGIONAL_SATELLITE_CONFIG_ID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.Nullable;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationRequest;
import android.os.AsyncResult;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.DropBoxManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.ResultReceiver;
import android.os.UserHandle;
import android.telecom.TelecomManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.satellite.EarfcnRange;
import android.telephony.satellite.ISatelliteCommunicationAccessStateCallback;
import android.telephony.satellite.SatelliteAccessConfiguration;
import android.telephony.satellite.SatelliteInfo;
import android.telephony.satellite.SatelliteManager;
import android.telephony.satellite.SatellitePosition;
import android.telephony.satellite.SystemSelectionSpecifier;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.Log;
import android.util.Pair;

import androidx.test.InstrumentationRegistry;

import com.android.TelephonyTestBase;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.TelephonyCountryDetector;
import com.android.internal.telephony.flags.FeatureFlags;
import com.android.internal.telephony.satellite.SatelliteConfig;
import com.android.internal.telephony.satellite.SatelliteConfigParser;
import com.android.internal.telephony.satellite.SatelliteController;
import com.android.internal.telephony.satellite.SatelliteModemInterface;
import com.android.internal.telephony.satellite.metrics.CarrierRoamingSatelliteControllerStats;
import com.android.internal.telephony.satellite.metrics.ControllerMetricsStats;
import com.android.internal.telephony.subscription.SubscriptionManagerService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Unit test for {@link SatelliteAccessController} */
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class SatelliteAccessControllerTest extends TelephonyTestBase {
    private static final String TAG = "SatelliteAccessControllerTest";
    private static final String[] TEST_SATELLITE_COUNTRY_CODES = {"US", "CA", "UK"};
    private static final String[] TEST_SATELLITE_COUNTRY_CODES_EMPTY = {""};
    private static final String TEST_SATELLITE_COUNTRY_CODE_US = "US";
    private static final String TEST_SATELLITE_COUNTRY_CODE_KR = "KR";
    private static final String TEST_SATELLITE_COUNTRY_CODE_JP = "JP";

    private static final String TEST_SATELLITE_S2_FILE = "sat_s2_file.dat";
    private static final boolean TEST_SATELLITE_ALLOW = true;
    private static final boolean TEST_SATELLITE_NOT_ALLOW = false;
    private static final int TEST_LOCATION_FRESH_DURATION_SECONDS = 10;
    private static final long TEST_LOCATION_FRESH_DURATION_NANOS =
            TimeUnit.SECONDS.toNanos(TEST_LOCATION_FRESH_DURATION_SECONDS);
    private static final long TEST_LOCATION_QUERY_THROTTLE_INTERVAL_NANOS =
            TimeUnit.MINUTES.toNanos(10);  // DEFAULT_THROTTLE_INTERVAL_FOR_LOCATION_QUERY_MINUTES
    private static final long TIMEOUT = 500;
    private static final List<String> EMPTY_STRING_LIST = new ArrayList<>();
    private static final List<String> LOCATION_PROVIDERS =
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER);
    private static final int SUB_ID = 0;

    @Mock
    private LocationManager mMockLocationManager;
    @Mock
    private TelecomManager mMockTelecomManager;
    @Mock
    private TelephonyCountryDetector mMockCountryDetector;
    @Mock
    private SatelliteController mMockSatelliteController;
    @Mock
    private SatelliteModemInterface mMockSatelliteModemInterface;
    @Mock
    private DropBoxManager mMockDropBoxManager;
    private Context mMockContext;  // alias of mContext
    @Mock
    private Phone mMockPhone;
    @Mock
    private Phone mMockPhone2;
    @Mock
    private FeatureFlags mMockFeatureFlags;
    @Mock
    private Resources mMockResources;
    @Mock
    private SatelliteOnDeviceAccessController mMockSatelliteOnDeviceAccessController;
    @Mock
    Location mMockLocation0;
    @Mock
    Location mMockLocation1;
    @Mock
    File mMockSatS2File;
    @Mock
    SharedPreferences mMockSharedPreferences;
    @Mock
    private SharedPreferences.Editor mMockSharedPreferencesEditor;
    @Mock
    private Map<SatelliteOnDeviceAccessController.LocationToken, Integer>
            mMockCachedAccessRestrictionMap;
    @Mock
    HashMap<Integer, SatelliteAccessConfiguration> mMockSatelliteAccessConfigMap;

    @Mock
    private Intent mMockLocationIntent;
    @Mock
    private Set<ResultReceiver> mMockSatelliteAllowResultReceivers;
    @Mock
    private TelephonyManager mMockTelephonyManager;
    @Mock
    private PackageManager mMockPackageManager;
    @Mock
    private List<ResolveInfo> mMockResolveInfoList;
    @Mock
    private NotificationManager mMockNotificationManager;
    @Mock
    private ApplicationInfo mMockApplicationInfo;
    @Mock
    private ResultReceiver mMockResultReceiver;
    @Mock
    private ConcurrentHashMap<IBinder, ISatelliteCommunicationAccessStateCallback>
            mSatelliteCommunicationAllowedStateCallbackMap;
    @Mock
    private ConcurrentHashMap<IBinder, ISatelliteCommunicationAccessStateCallback>
            mMockSatelliteCommunicationAccessStateChangedListeners;
    @Mock
    private CarrierRoamingSatelliteControllerStats mCarrierRoamingSatelliteControllerStats;

    private SatelliteInfo mSatelliteInfo;

    private TestableLooper mTestableLooper;
    private Phone[] mPhones;
    private TestSatelliteAccessController mSatelliteAccessControllerUT;

    @Captor
    private ArgumentCaptor<CancellationSignal> mLocationRequestCancellationSignalCaptor;
    @Captor
    private ArgumentCaptor<Consumer<Location>> mLocationRequestConsumerCaptor;
    @Captor
    private ArgumentCaptor<Handler> mConfigUpdateHandlerCaptor;
    @Captor
    private ArgumentCaptor<Integer> mConfigUpdateIntCaptor;
    @Captor
    private ArgumentCaptor<Object> mConfigUpdateObjectCaptor;
    @Captor
    private ArgumentCaptor<Handler> mCountryDetectorHandlerCaptor;
    @Captor
    private ArgumentCaptor<Integer> mCountryDetectorIntCaptor;
    @Captor
    private ArgumentCaptor<Object> mCountryDetectorObjCaptor;
    @Captor
    private ArgumentCaptor<BroadcastReceiver> mLocationBroadcastReceiverCaptor;
    @Captor
    private ArgumentCaptor<IntentFilter> mIntentFilterCaptor;
    @Captor
    private ArgumentCaptor<LocationRequest> mLocationRequestCaptor;
    @Captor
    private ArgumentCaptor<String> mLocationProviderStringCaptor;
    @Captor
    private ArgumentCaptor<Integer> mResultCodeIntCaptor;
    @Captor
    private ArgumentCaptor<Bundle> mResultDataBundleCaptor;
    @Captor
    private ArgumentCaptor<ISatelliteCommunicationAccessStateCallback> mAllowedStateCallbackCaptor;

    private boolean mQueriedSatelliteAllowed = false;
    private int mQueriedSatelliteAllowedResultCode = SATELLITE_RESULT_SUCCESS;
    private Semaphore mSatelliteAllowedSemaphore = new Semaphore(0);
    private ResultReceiver mSatelliteAllowedReceiver = new ResultReceiver(null) {
        @Override
        protected void onReceiveResult(int resultCode, Bundle resultData) {
            mQueriedSatelliteAllowedResultCode = resultCode;
            if (resultCode == SATELLITE_RESULT_SUCCESS) {
                if (resultData.containsKey(KEY_SATELLITE_COMMUNICATION_ALLOWED)) {
                    mQueriedSatelliteAllowed = resultData.getBoolean(
                            KEY_SATELLITE_COMMUNICATION_ALLOWED);
                } else {
                    logd("KEY_SATELLITE_COMMUNICATION_ALLOWED does not exist.");
                    mQueriedSatelliteAllowed = false;
                }
            } else {
                logd("mSatelliteAllowedReceiver: resultCode=" + resultCode);
                mQueriedSatelliteAllowed = false;
            }
            try {
                mSatelliteAllowedSemaphore.release();
            } catch (Exception ex) {
                fail("mSatelliteAllowedReceiver: Got exception in releasing semaphore, ex=" + ex);
            }
        }
    };

    private int mQueriedSystemSelectionChannelUpdatedResultCode = SATELLITE_RESULT_SUCCESS;
    private Semaphore mSystemSelectionChannelUpdatedSemaphore = new Semaphore(0);
    private ResultReceiver mSystemSelectionChannelUpdatedReceiver = new ResultReceiver(null) {
        @Override
        protected void onReceiveResult(int resultCode, Bundle resultData) {
            mQueriedSystemSelectionChannelUpdatedResultCode = resultCode;
            try {
                mSystemSelectionChannelUpdatedSemaphore.release();
            } catch (Exception ex) {
                fail("mSystemSelectionChannelUpdatedReceiver: Got exception in releasing "
                        + "semaphore, ex="
                        + ex);
            }
        }
    };

    @Before
    public void setUp() throws Exception {
        logd("SatelliteAccessControllerTest setUp");
        super.setUp();

        mMockContext = mContext;
        mTestableLooper = TestableLooper.get(this);
        when(mMockContext.getSystemServiceName(LocationManager.class)).thenReturn(
                Context.LOCATION_SERVICE);
        when(mMockContext.getSystemServiceName(TelecomManager.class)).thenReturn(
                Context.TELECOM_SERVICE);
        when(mMockContext.getSystemServiceName(DropBoxManager.class)).thenReturn(
                Context.DROPBOX_SERVICE);
        when(mMockContext.getSystemService(LocationManager.class)).thenReturn(
                mMockLocationManager);
        when(mMockContext.getSystemService(TelecomManager.class)).thenReturn(
                mMockTelecomManager);
        when(mMockContext.getSystemService(DropBoxManager.class)).thenReturn(
                mMockDropBoxManager);
        doAnswer(inv -> {
            var args = inv.getArguments();
            return InstrumentationRegistry.getTargetContext()
                    .getDir((String) args[0], (Integer) args[1]);
        }).when(mPhoneGlobals).getDir(anyString(), anyInt());
        doAnswer(
                        inv -> {
                            return InstrumentationRegistry.getTargetContext().getAssets();
                        })
                .when(mPhoneGlobals)
                .getAssets();
        mPhones = new Phone[]{mMockPhone, mMockPhone2};
        replaceInstance(PhoneFactory.class, "sPhones", null, mPhones);
        replaceInstance(SatelliteController.class, "sInstance", null,
                mMockSatelliteController);
        replaceInstance(SatelliteModemInterface.class, "sInstance", null,
                mMockSatelliteModemInterface);
        replaceInstance(SubscriptionManagerService.class, "sInstance", null,
                mock(SubscriptionManagerService.class));
        replaceInstance(TelephonyCountryDetector.class, "sInstance", null,
                mMockCountryDetector);
        replaceInstance(ControllerMetricsStats.class, "sInstance", null,
                mock(ControllerMetricsStats.class));
        replaceInstance(CarrierRoamingSatelliteControllerStats.class, "sInstance", null,
                mCarrierRoamingSatelliteControllerStats);
        when(mMockSatelliteController.getSatellitePhone()).thenReturn(mMockPhone);
        when(mMockPhone.getSubId()).thenReturn(SubscriptionManager.getDefaultSubscriptionId());

        when(mMockContext.getResources()).thenReturn(mMockResources);
        when(mMockResources.getStringArray(
                com.android.internal.R.array.config_oem_enabled_satellite_country_codes))
                .thenReturn(TEST_SATELLITE_COUNTRY_CODES);
        when(mMockResources.getBoolean(
                com.android.internal.R.bool.config_oem_enabled_satellite_access_allow))
                .thenReturn(TEST_SATELLITE_ALLOW);
        when(mMockResources.getString(
                com.android.internal.R.string.config_oem_enabled_satellite_s2cell_file))
                .thenReturn(TEST_SATELLITE_S2_FILE);
        when(mMockResources.getInteger(com.android.internal.R.integer
                .config_oem_enabled_satellite_location_fresh_duration))
                .thenReturn(TEST_LOCATION_FRESH_DURATION_SECONDS);
        when(mMockResources.getInteger(com.android.internal.R.integer
                .config_satellite_delay_minutes_before_retry_validating_possible_change_in_allowed_region))
                .thenReturn(
                        DEFAULT_DELAY_MINUTES_BEFORE_VALIDATING_POSSIBLE_CHANGE_IN_ALLOWED_REGION);
        when(mMockResources.getInteger(com.android.internal.R.integer
                .config_satellite_max_retry_count_for_validating_possible_change_in_allowed_region))
                .thenReturn(
                        DEFAULT_MAX_RETRY_COUNT_FOR_VALIDATING_POSSIBLE_CHANGE_IN_ALLOWED_REGION);
        when(mMockResources.getInteger(com.android.internal.R.integer
                .config_satellite_location_query_throttle_interval_minutes))
                .thenReturn(DEFAULT_THROTTLE_INTERVAL_FOR_LOCATION_QUERY_MINUTES);

        when(mMockLocationManager.getProviders(true)).thenReturn(LOCATION_PROVIDERS);
        when(mMockLocationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER))
                .thenReturn(mMockLocation0);
        when(mMockLocationManager.getLastKnownLocation(LocationManager.FUSED_PROVIDER))
                .thenReturn(mMockLocation1);
        when(mMockLocation0.getLatitude()).thenReturn(0.0);
        when(mMockLocation0.getLongitude()).thenReturn(0.0);
        when(mMockLocation1.getLatitude()).thenReturn(1.0);
        when(mMockLocation1.getLongitude()).thenReturn(1.0);
        when(mMockSatelliteOnDeviceAccessController.getRegionalConfigIdForLocation(
                any(SatelliteOnDeviceAccessController.LocationToken.class)))
                .thenReturn(DEFAULT_REGIONAL_SATELLITE_CONFIG_ID);

        doReturn(mMockSharedPreferences).when(mMockContext)
                .getSharedPreferences(anyString(), anyInt());
        when(mMockSharedPreferences.getBoolean(anyString(), anyBoolean())).thenReturn(true);
        when(mMockSharedPreferences.getStringSet(anyString(), any()))
                .thenReturn(Set.of(TEST_SATELLITE_COUNTRY_CODES));
        when(mMockSharedPreferences.getInt(
                eq(CONFIG_UPDATER_SATELLITE_VERSION_KEY), anyInt())).thenReturn(0);
        doReturn(mMockSharedPreferencesEditor).when(mMockSharedPreferences).edit();
        doReturn(mMockSharedPreferencesEditor).when(mMockSharedPreferencesEditor)
                .putBoolean(anyString(), anyBoolean());
        doReturn(mMockSharedPreferencesEditor).when(mMockSharedPreferencesEditor)
                .putStringSet(anyString(), any());
        doReturn(mMockSharedPreferencesEditor).when(mMockSharedPreferencesEditor)
                .putLong(anyString(), anyLong());
        doReturn(mMockSharedPreferencesEditor).when(mMockSharedPreferencesEditor)
                .putInt(anyString(), anyInt());
        doNothing().when(mMockSharedPreferencesEditor).apply();

        when(mMockFeatureFlags.geofenceEnhancementForBetterUx()).thenReturn(true);
        when(mMockFeatureFlags.carrierRoamingNbIotNtn()).thenReturn(true);

        when(mMockContext.getSystemService(Context.TELEPHONY_SERVICE))
                .thenReturn(mMockTelephonyManager);
        when(mMockTelephonyManager.isSmsCapable()).thenReturn(true);
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        mMockResolveInfoList = new ArrayList<>();
        when(mMockPackageManager.queryBroadcastReceiversAsUser(any(Intent.class), anyInt(), any(
                UserHandle.class)))
                .thenReturn(mMockResolveInfoList);
        when(mMockContext.getSystemServiceName(
                NotificationManager.class)).thenReturn(Context.NOTIFICATION_SERVICE);
        when(mMockContext.getSystemService(Context.NOTIFICATION_SERVICE))
                .thenReturn(mMockNotificationManager);
        doReturn(mMockApplicationInfo).when(mMockContext).getApplicationInfo();
        mMockApplicationInfo.targetSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
        when(mMockPackageManager.getApplicationInfo(anyString(), anyInt()))
                .thenReturn(mMockApplicationInfo);
        when(mCarrierRoamingSatelliteControllerStats.isMultiSim()).thenReturn(false);

        mSatelliteInfo = new SatelliteInfo(
                UUID.randomUUID(),
                new SatellitePosition(10, 15),
                new ArrayList<>(Arrays.asList(5, 30)),
                new ArrayList<>(Arrays.asList(new EarfcnRange(0, 250))));

        logd("setUp: Initializing mSatelliteAccessControllerUT:TestSatelliteAccessController");
        mSatelliteAccessControllerUT = new TestSatelliteAccessController(mMockContext,
                mMockFeatureFlags, mTestableLooper.getLooper(), mMockLocationManager,
                mMockTelecomManager, mMockSatelliteOnDeviceAccessController, mMockSatS2File);
        mTestableLooper.processAllMessages();
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @Test
    public void testGetInstance() {
        SatelliteAccessController inst1 =
                SatelliteAccessController.getOrCreateInstance(mMockContext, mMockFeatureFlags);
        SatelliteAccessController inst2 =
                SatelliteAccessController.getOrCreateInstance(mMockContext, mMockFeatureFlags);
        assertEquals(inst1, inst2);
    }

    @Test
    public void testOnCurrentLocationNotAvailable() throws Exception {
        // Verify the cache is used when the location is null and the cache is valid and true.
        mSatelliteAccessControllerUT.elapsedRealtimeNanos =
                ALLOWED_STATE_CACHE_VALID_DURATION_NANOS - 1;
        mSatelliteAccessControllerUT
                .setIsSatelliteCommunicationAllowedForCurrentLocationCache("cache_allowed");
        mSatelliteAccessControllerUT.setLocationRequestCancellationSignalAsNull(false);

        sendCurrentLocationTimeoutEvent();
        assertTrue(mSatelliteAccessControllerUT.isCurrentSatelliteAllowedState());

        // Verify the cache is used when the location is null and the cache is valid and false.
        mSatelliteAccessControllerUT
                .setIsSatelliteCommunicationAllowedForCurrentLocationCache("cache_not_allowed");
        mSatelliteAccessControllerUT.setLocationRequestCancellationSignalAsNull(false);

        sendCurrentLocationTimeoutEvent();
        assertFalse(mSatelliteAccessControllerUT.isCurrentSatelliteAllowedState());

        // Verify the result code is SATELLITE_RESULT_LOCATION_NOT_AVAILABLE
        // and allowedState is false when the location is null and the cache is expired
        mSatelliteAccessControllerUT.elapsedRealtimeNanos =
                ALLOWED_STATE_CACHE_VALID_DURATION_NANOS + 1;
        Iterator<ResultReceiver> mockResultReceiverIterator = mock(Iterator.class);
        doReturn(mockResultReceiverIterator).when(mMockSatelliteAllowResultReceivers).iterator();
        doReturn(true, false).when(mockResultReceiverIterator).hasNext();
        doNothing().when(mMockSatelliteAllowResultReceivers).clear();
        doReturn(mMockResultReceiver).when(mockResultReceiverIterator).next();
        replaceInstance(SatelliteAccessController.class, "mSatelliteAllowResultReceivers",
                mSatelliteAccessControllerUT, mMockSatelliteAllowResultReceivers);
        mSatelliteAccessControllerUT.setIsSatelliteCommunicationAllowedForCurrentLocationCache(
                "cache_clear_and_not_allowed");
        mSatelliteAccessControllerUT.setLocationRequestCancellationSignalAsNull(false);

        sendCurrentLocationTimeoutEvent();
        verify(mMockResultReceiver)
                .send(mResultCodeIntCaptor.capture(), any());
        assertEquals(Integer.valueOf(SATELLITE_RESULT_LOCATION_NOT_AVAILABLE),
                mResultCodeIntCaptor.getValue());
        assertFalse(mSatelliteAccessControllerUT.isCurrentSatelliteAllowedState());
    }

    @Test
    public void testIsSatelliteAccessAllowedForLocation() {
        // Test disallowList case
        when(mMockResources.getBoolean(
                com.android.internal.R.bool.config_oem_enabled_satellite_access_allow))
                .thenReturn(TEST_SATELLITE_NOT_ALLOW);

        // configuration is EMPTY then we return true with any network country code.
        when(mMockResources.getStringArray(
                com.android.internal.R.array.config_oem_enabled_satellite_country_codes))
                .thenReturn(TEST_SATELLITE_COUNTRY_CODES_EMPTY);
        mSatelliteAccessControllerUT.loadOverlayConfigs(mMockContext);
        assertTrue(mSatelliteAccessControllerUT
                .isSatelliteAccessAllowedForLocation(List.of(TEST_SATELLITE_COUNTRY_CODE_US)));
        assertTrue(mSatelliteAccessControllerUT
                .isSatelliteAccessAllowedForLocation(List.of(TEST_SATELLITE_COUNTRY_CODE_JP)));

        // configuration is ["US", "CA", "UK"]
        // - if network country code is ["US"] or ["US","KR"] or [EMPTY] return false;
        // - if network country code is ["KR"] return true;
        when(mMockResources.getStringArray(
                com.android.internal.R.array.config_oem_enabled_satellite_country_codes))
                .thenReturn(TEST_SATELLITE_COUNTRY_CODES);
        mSatelliteAccessControllerUT.loadOverlayConfigs(mMockContext);
        assertFalse(mSatelliteAccessControllerUT.isSatelliteAccessAllowedForLocation(List.of()));
        assertFalse(mSatelliteAccessControllerUT
                .isSatelliteAccessAllowedForLocation(List.of(TEST_SATELLITE_COUNTRY_CODE_US)));
        assertFalse(mSatelliteAccessControllerUT.isSatelliteAccessAllowedForLocation(
                List.of(TEST_SATELLITE_COUNTRY_CODE_US, TEST_SATELLITE_COUNTRY_CODE_KR)));
        assertTrue(mSatelliteAccessControllerUT
                .isSatelliteAccessAllowedForLocation(List.of(TEST_SATELLITE_COUNTRY_CODE_KR)));

        // Test allowList case
        when(mMockResources.getBoolean(
                com.android.internal.R.bool.config_oem_enabled_satellite_access_allow))
                .thenReturn(TEST_SATELLITE_ALLOW);

        // configuration is [EMPTY] then return false in case of any network country code
        when(mMockResources.getStringArray(
                com.android.internal.R.array.config_oem_enabled_satellite_country_codes))
                .thenReturn(TEST_SATELLITE_COUNTRY_CODES_EMPTY);
        mSatelliteAccessControllerUT.loadOverlayConfigs(mMockContext);
        assertFalse(mSatelliteAccessControllerUT
                .isSatelliteAccessAllowedForLocation(List.of(TEST_SATELLITE_COUNTRY_CODE_US)));
        assertFalse(mSatelliteAccessControllerUT
                .isSatelliteAccessAllowedForLocation(List.of(TEST_SATELLITE_COUNTRY_CODE_JP)));

        // configuration is ["US", "CA", "UK"]
        // - if network country code is [EMPTY] or ["US","KR"] or [KR] return false;
        // - if network country code is ["US"] return true;
        when(mMockResources.getStringArray(
                com.android.internal.R.array.config_oem_enabled_satellite_country_codes))
                .thenReturn(TEST_SATELLITE_COUNTRY_CODES);
        mSatelliteAccessControllerUT.loadOverlayConfigs(mMockContext);
        assertFalse(mSatelliteAccessControllerUT.isSatelliteAccessAllowedForLocation(List.of()));
        assertFalse(mSatelliteAccessControllerUT
                .isSatelliteAccessAllowedForLocation(List.of(TEST_SATELLITE_COUNTRY_CODE_KR)));
        assertFalse(mSatelliteAccessControllerUT.isSatelliteAccessAllowedForLocation(
                List.of(TEST_SATELLITE_COUNTRY_CODE_US, TEST_SATELLITE_COUNTRY_CODE_KR)));
        assertTrue(mSatelliteAccessControllerUT
                .isSatelliteAccessAllowedForLocation(List.of(TEST_SATELLITE_COUNTRY_CODE_US)));
    }


    private void setSatelliteCommunicationAllowed() throws Exception {
        when(mMockContext.getResources()).thenReturn(mMockResources);
        when(mMockResources.getBoolean(
                com.android.internal.R.bool.config_oem_enabled_satellite_access_allow))
                .thenReturn(TEST_SATELLITE_ALLOW);
        setUpResponseForRequestIsSatelliteSupported(true, SATELLITE_RESULT_SUCCESS);
        setUpResponseForRequestIsSatelliteProvisioned(true, SATELLITE_RESULT_SUCCESS);
        doReturn(true).when(mMockLocationManager).isLocationEnabled();
        when(mMockSatelliteOnDeviceAccessController.getRegionalConfigIdForLocation(
                any(SatelliteOnDeviceAccessController.LocationToken.class)))
                .thenReturn(DEFAULT_REGIONAL_SATELLITE_CONFIG_ID);
        replaceInstance(SatelliteAccessController.class, "mCachedAccessRestrictionMap",
                mSatelliteAccessControllerUT, mMockCachedAccessRestrictionMap);
        doReturn(true).when(mMockCachedAccessRestrictionMap).containsKey(any());
        doReturn(DEFAULT_REGIONAL_SATELLITE_CONFIG_ID)
                .when(mMockCachedAccessRestrictionMap).get(any());
    }

    @Test
    public void testRequestSatelliteAccessConfigurationForCurrentLocation() throws Exception {
        // setup result receiver and satellite access configuration data
        ResultReceiver mockResultReceiver = mock(ResultReceiver.class);
        ArgumentCaptor<Integer> resultCodeCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        SatelliteAccessConfiguration satelliteAccessConfig = getSatelliteAccessConfiguration();

        // setup satellite communication allowed state as true
        setSatelliteCommunicationAllowed();

        // setup map data of location and configId.
        replaceInstance(SatelliteAccessController.class, "mSatelliteAccessConfigMap",
                mSatelliteAccessControllerUT, mMockSatelliteAccessConfigMap);
        doReturn(satelliteAccessConfig).when(mMockSatelliteAccessConfigMap).get(anyInt());
        doReturn(null).when(mMockSatelliteAccessConfigMap).get(eq(null));
        doReturn(null).when(mMockSatelliteAccessConfigMap)
                .get(eq(UNKNOWN_REGIONAL_SATELLITE_CONFIG_ID));

        // setup callback
        ISatelliteCommunicationAccessStateCallback mockSatelliteAllowedStateCallback = mock(
                ISatelliteCommunicationAccessStateCallback.class);
        ArgumentCaptor<SatelliteAccessConfiguration> satelliteAccessConfigurationCaptor =
                ArgumentCaptor.forClass(SatelliteAccessConfiguration.class);

        when(mSatelliteCommunicationAllowedStateCallbackMap.values())
                .thenReturn(List.of(mockSatelliteAllowedStateCallback));
        replaceInstance(SatelliteAccessController.class,
                "mSatelliteCommunicationAccessStateChangedListeners", mSatelliteAccessControllerUT,
                mSatelliteCommunicationAllowedStateCallbackMap);

        // Test when the featureFlags.carrierRoamingNbIotNtn() is false
        doReturn(false).when(mMockFeatureFlags).carrierRoamingNbIotNtn();

        clearInvocations(mockResultReceiver);
        mSatelliteAccessControllerUT
                .requestSatelliteAccessConfigurationForCurrentLocation(mockResultReceiver);
        mTestableLooper.processAllMessages();
        verify(mockResultReceiver, times(1)).send(resultCodeCaptor.capture(),
                bundleCaptor.capture());
        assertEquals(SATELLITE_RESULT_REQUEST_NOT_SUPPORTED, (int) resultCodeCaptor.getValue());
        assertNull(bundleCaptor.getValue());
        verify(mockSatelliteAllowedStateCallback, never())
                .onAccessConfigurationChanged(any());

        doReturn(true).when(mMockFeatureFlags).carrierRoamingNbIotNtn();

        // Verify if the map is maintained after the cleanup event
        sendSatelliteDeviceAccessControllerResourcesTimeOutEvent();

        // satellite communication allowed state is enabled and
        // regional config id is DEFAULT_REGIONAL_SATELLITE_CONFIG_ID.
        clearInvocations(mockResultReceiver);
        clearInvocations(mockSatelliteAllowedStateCallback);
        mSatelliteAccessControllerUT
                .requestSatelliteAccessConfigurationForCurrentLocation(mockResultReceiver);
        mTestableLooper.processAllMessages();
        verify(mockResultReceiver, times(1)).send(resultCodeCaptor.capture(),
                bundleCaptor.capture());
        assertEquals(SatelliteManager.SATELLITE_RESULT_SUCCESS, (int) resultCodeCaptor.getValue());
        assertTrue(bundleCaptor.getValue().containsKey(KEY_SATELLITE_ACCESS_CONFIGURATION));
        assertSame(bundleCaptor.getValue().getParcelable(KEY_SATELLITE_ACCESS_CONFIGURATION,
                SatelliteAccessConfiguration.class), satelliteAccessConfig);
        verify(mockSatelliteAllowedStateCallback, times(1))
                .onAccessConfigurationChanged(
                        satelliteAccessConfigurationCaptor.capture());
        assertEquals(satelliteAccessConfigurationCaptor.getValue(), satelliteAccessConfig);

        // satellite communication allowed state is disabled and
        // regional config id is null.
        clearInvocations(mockResultReceiver);
        clearInvocations(mockSatelliteAllowedStateCallback);
        when(mMockCachedAccessRestrictionMap.get(any())).thenReturn(null);
        mSatelliteAccessControllerUT
                .requestSatelliteAccessConfigurationForCurrentLocation(mockResultReceiver);
        mTestableLooper.processAllMessages();

        verify(mockResultReceiver, times(1)).send(resultCodeCaptor.capture(),
                bundleCaptor.capture());
        assertEquals(SATELLITE_RESULT_NO_RESOURCES, (int) resultCodeCaptor.getValue());
        assertNull(bundleCaptor.getValue());

        verify(mockSatelliteAllowedStateCallback, times(1))
                .onAccessConfigurationChanged(
                        satelliteAccessConfigurationCaptor.capture());
        assertNull(satelliteAccessConfigurationCaptor.getValue());
    }

    private SatelliteAccessConfiguration getSatelliteAccessConfiguration() {
        List<SatelliteInfo> satelliteInfoList = new ArrayList<>();
        satelliteInfoList.add(mSatelliteInfo);
        List<Integer> tagIds = new ArrayList<>(List.of(1, 2));
        return new SatelliteAccessConfiguration(satelliteInfoList, tagIds);
    }

    @Test
    public void testRegisterForCommunicationAllowedStateChanged() throws Exception {
        ISatelliteCommunicationAccessStateCallback mockSatelliteAllowedStateCallback = mock(
                ISatelliteCommunicationAccessStateCallback.class);
        doReturn(true).when(mSatelliteCommunicationAllowedStateCallbackMap)
                .put(any(IBinder.class), any(ISatelliteCommunicationAccessStateCallback.class));
        replaceInstance(SatelliteAccessController.class,
                "mSatelliteCommunicationAccessStateChangedListeners", mSatelliteAccessControllerUT,
                mSatelliteCommunicationAllowedStateCallbackMap);

        int result = mSatelliteAccessControllerUT.registerForCommunicationAccessStateChanged(
                DEFAULT_SUBSCRIPTION_ID, mockSatelliteAllowedStateCallback);
        mTestableLooper.processAllMessages();
        assertEquals(SATELLITE_RESULT_SUCCESS, result);
        verify(mockSatelliteAllowedStateCallback, times(1))
                .onAccessAllowedStateChanged(anyBoolean());
        verify(mockSatelliteAllowedStateCallback, times(1))
                .onAccessConfigurationChanged(
                        nullable(SatelliteAccessConfiguration.class));
    }

    @Test
    public void testNotifyRegionalSatelliteConfigurationChanged() throws Exception {
        // setup test
        ISatelliteCommunicationAccessStateCallback mockSatelliteAllowedStateCallback = mock(
                ISatelliteCommunicationAccessStateCallback.class);
        ArgumentCaptor<SatelliteAccessConfiguration> satelliteAccessConfigurationCaptor =
                ArgumentCaptor.forClass(SatelliteAccessConfiguration.class);

        when(mSatelliteCommunicationAllowedStateCallbackMap.values())
                .thenReturn(List.of(mockSatelliteAllowedStateCallback));
        replaceInstance(SatelliteAccessController.class,
                "mSatelliteCommunicationAccessStateChangedListeners", mSatelliteAccessControllerUT,
                mSatelliteCommunicationAllowedStateCallbackMap);

        // register callback
        mSatelliteAccessControllerUT.registerForCommunicationAccessStateChanged(
                DEFAULT_SUBSCRIPTION_ID, mockSatelliteAllowedStateCallback);

        // verify if the callback is
        // the same instance from onmSatelliteCommunicationAllowedStateCallbackMap
        verify(mSatelliteCommunicationAllowedStateCallbackMap).put(any(),
                mAllowedStateCallbackCaptor.capture());
        assertSame(mockSatelliteAllowedStateCallback, mAllowedStateCallbackCaptor.getValue());

        // create SatelliteAccessConfiguration data for this test
        SatelliteAccessConfiguration satelliteAccessConfig = getSatelliteAccessConfiguration();

        // trigger notifyRegionalSatelliteConfigurationChanged
        mSatelliteAccessControllerUT
                .notifyRegionalSatelliteConfigurationChanged(satelliteAccessConfig);

        // verify if the satelliteAccessConfig is the same instance with the captured one.
        verify(mockSatelliteAllowedStateCallback).onAccessConfigurationChanged(
                satelliteAccessConfigurationCaptor.capture());
        assertSame(satelliteAccessConfig, satelliteAccessConfigurationCaptor.getValue());
    }

    @Test
    public void testCheckSatelliteAccessRestrictionForLocation() throws Exception {
        // Setup
        logd("testCheckSatelliteAccessRestrictionForLocation : setup");
        ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        ArgumentCaptor<Integer> regionalConfigIdCaptor = ArgumentCaptor.forClass(Integer.class);
        replaceInstance(SatelliteAccessController.class, "mS2Level",
                mSatelliteAccessControllerUT, DEFAULT_S2_LEVEL);
        Iterator<ResultReceiver> mockResultReceiverIterator = mock(Iterator.class);
        mSatelliteAccessControllerUT.setRegionalConfigId(null);

        doReturn(mockResultReceiverIterator).when(mMockSatelliteAllowResultReceivers).iterator();
        doReturn(true, false).when(mockResultReceiverIterator).hasNext();
        doNothing().when(mMockSatelliteAllowResultReceivers).clear();
        doReturn(mMockResultReceiver).when(mockResultReceiverIterator).next();
        replaceInstance(SatelliteAccessController.class, "mSatelliteAllowResultReceivers",
                mSatelliteAccessControllerUT, mMockSatelliteAllowResultReceivers);
        replaceInstance(SatelliteAccessController.class, "mCachedAccessRestrictionMap",
                mSatelliteAccessControllerUT, mMockCachedAccessRestrictionMap);

        // when mMockCachedAccessRestrictionMap is hit and has DEFAULT_REGIONAL_SATELLITE_CONFIG_ID,
        // verify belows
        // - the bundle data of KEY_SATELLITE_COMMUNICATION_ALLOWED is true
        // - the newRegionalConfigId is the same as DEFAULT_REGIONAL_SATELLITE_CONFIG_ID
        // - the regionalConfigId is the same as DEFAULT_REGIONAL_SATELLITE_CONFIG_ID
        logd("testCheckSatelliteAccessRestrictionForLocation : case 1");
        clearInvocations(mMockSatelliteOnDeviceAccessController);
        clearInvocations(mMockCachedAccessRestrictionMap);

        doReturn(true).when(mMockCachedAccessRestrictionMap)
                .containsKey(any(SatelliteOnDeviceAccessController.LocationToken.class));
        doReturn(DEFAULT_REGIONAL_SATELLITE_CONFIG_ID).when(mMockCachedAccessRestrictionMap)
                .get(any(SatelliteOnDeviceAccessController.LocationToken.class));

        mSatelliteAccessControllerUT.checkSatelliteAccessRestrictionForLocation(mMockLocation0);
        verify(mMockResultReceiver, times(1))
                .send(mResultCodeIntCaptor.capture(), bundleCaptor.capture());
        verify(mMockSatelliteOnDeviceAccessController, never()).getRegionalConfigIdForLocation(
                any(SatelliteOnDeviceAccessController.LocationToken.class));
        assertEquals(Integer.valueOf(SATELLITE_RESULT_SUCCESS), mResultCodeIntCaptor.getValue());
        assertTrue(bundleCaptor.getValue().getBoolean(KEY_SATELLITE_COMMUNICATION_ALLOWED));
        assertEquals(Integer.valueOf(DEFAULT_REGIONAL_SATELLITE_CONFIG_ID),
                mSatelliteAccessControllerUT.getNewRegionalConfigId());
        assertEquals(Integer.valueOf(DEFAULT_REGIONAL_SATELLITE_CONFIG_ID),
                mSatelliteAccessControllerUT.getRegionalConfigId());

        // when mMockCachedAccessRestrictionMap is not hit and regionalConfigId is null
        // verify belows
        // - the bundle data of KEY_SATELLITE_COMMUNICATION_ALLOWED is false
        // - the regionalConfigId is null
        logd("testCheckSatelliteAccessRestrictionForLocation : case 2");
        clearInvocations(mMockCachedAccessRestrictionMap);
        doReturn(false).when(mMockCachedAccessRestrictionMap)
                .containsKey(any(SatelliteOnDeviceAccessController.LocationToken.class));
        doReturn(true, false).when(mockResultReceiverIterator).hasNext();
        when(mMockSatelliteOnDeviceAccessController.getRegionalConfigIdForLocation(
                any(SatelliteOnDeviceAccessController.LocationToken.class)))
                .thenReturn(null);

        mSatelliteAccessControllerUT.checkSatelliteAccessRestrictionForLocation(mMockLocation0);
        verify(mMockResultReceiver, times(2))
                .send(mResultCodeIntCaptor.capture(), bundleCaptor.capture());
        assertEquals(Integer.valueOf(SATELLITE_RESULT_SUCCESS), mResultCodeIntCaptor.getValue());
        assertFalse(bundleCaptor.getValue().getBoolean(KEY_SATELLITE_COMMUNICATION_ALLOWED));
        verify(mMockCachedAccessRestrictionMap, times(1))
                .put(any(), regionalConfigIdCaptor.capture());
        assertNull(regionalConfigIdCaptor.getValue());
        assertNull(mSatelliteAccessControllerUT.getNewRegionalConfigId());
        assertNull(mSatelliteAccessControllerUT.getRegionalConfigId());

        // when mMockCachedAccessRestrictionMap is not hit and
        // regionalConfigId is DEFAULT_REGIONAL_SATELLITE_CONFIG_ID
        // verify belows
        // - the bundle data of KEY_SATELLITE_COMMUNICATION_ALLOWED is true
        // - the regionalConfigId is DEFAULT_REGIONAL_SATELLITE_CONFIG_ID
        logd("testCheckSatelliteAccessRestrictionForLocation : case 3");
        clearInvocations(mMockCachedAccessRestrictionMap);
        when(mMockSatelliteOnDeviceAccessController.getRegionalConfigIdForLocation(
                any(SatelliteOnDeviceAccessController.LocationToken.class)))
                .thenReturn(DEFAULT_REGIONAL_SATELLITE_CONFIG_ID);
        doReturn(true, false).when(mockResultReceiverIterator).hasNext();

        mSatelliteAccessControllerUT.checkSatelliteAccessRestrictionForLocation(mMockLocation0);
        verify(mMockResultReceiver, times(3))
                .send(mResultCodeIntCaptor.capture(), bundleCaptor.capture());
        assertEquals(Integer.valueOf(SATELLITE_RESULT_SUCCESS), mResultCodeIntCaptor.getValue());
        assertTrue(bundleCaptor.getValue().getBoolean(KEY_SATELLITE_COMMUNICATION_ALLOWED));
        verify(mMockCachedAccessRestrictionMap, times(1))
                .put(any(), regionalConfigIdCaptor.capture());

        assertEquals(Integer.valueOf(DEFAULT_REGIONAL_SATELLITE_CONFIG_ID),
                regionalConfigIdCaptor.getValue());
        assertEquals(Integer.valueOf(DEFAULT_REGIONAL_SATELLITE_CONFIG_ID),
                mSatelliteAccessControllerUT.getNewRegionalConfigId());
        assertEquals(Integer.valueOf(DEFAULT_REGIONAL_SATELLITE_CONFIG_ID),
                mSatelliteAccessControllerUT.getRegionalConfigId());


        // when mMockCachedAccessRestrictionMap is not hit and regionalConfigId is null
        // verify belows
        // - the bundle data of KEY_SATELLITE_COMMUNICATION_ALLOWED is false
        // - the regionalConfigId is null
        logd("testCheckSatelliteAccessRestrictionForLocation : case 4");
        clearInvocations(mMockCachedAccessRestrictionMap);
        when(mMockSatelliteOnDeviceAccessController.getRegionalConfigIdForLocation(
                any(SatelliteOnDeviceAccessController.LocationToken.class)))
                .thenReturn(null);
        doReturn(true, false).when(mockResultReceiverIterator).hasNext();

        mSatelliteAccessControllerUT.checkSatelliteAccessRestrictionForLocation(mMockLocation0);
        verify(mMockResultReceiver, times(4))
                .send(mResultCodeIntCaptor.capture(), bundleCaptor.capture());
        assertEquals(Integer.valueOf(SATELLITE_RESULT_SUCCESS), mResultCodeIntCaptor.getValue());
        assertFalse(bundleCaptor.getValue().getBoolean(KEY_SATELLITE_COMMUNICATION_ALLOWED));
        verify(mMockCachedAccessRestrictionMap, times(1))
                .put(any(), regionalConfigIdCaptor.capture());
        assertNull(regionalConfigIdCaptor.getValue());
        assertNull(mSatelliteAccessControllerUT.getNewRegionalConfigId());
        assertNull(mSatelliteAccessControllerUT.getRegionalConfigId());
    }

    @Test
    public void testIsRegionDisallowed() throws Exception {
        when(mMockContext.getResources()).thenReturn(mMockResources);
        when(mMockResources.getBoolean(
                com.android.internal.R.bool.config_oem_enabled_satellite_access_allow))
                .thenReturn(TEST_SATELLITE_ALLOW);
        setUpResponseForRequestIsSatelliteSupported(true, SATELLITE_RESULT_SUCCESS);
        setUpResponseForRequestIsSatelliteProvisioned(true, SATELLITE_RESULT_SUCCESS);
        doReturn(true).when(mMockLocationManager).isLocationEnabled();
        when(mMockSatelliteOnDeviceAccessController.getRegionalConfigIdForLocation(
                any(SatelliteOnDeviceAccessController.LocationToken.class)))
                .thenReturn(DEFAULT_REGIONAL_SATELLITE_CONFIG_ID);
        replaceInstance(SatelliteAccessController.class, "mCachedAccessRestrictionMap",
                mSatelliteAccessControllerUT, mMockCachedAccessRestrictionMap);
        doReturn(true).when(mMockCachedAccessRestrictionMap).containsKey(any());
        doReturn(DEFAULT_REGIONAL_SATELLITE_CONFIG_ID)
                .when(mMockCachedAccessRestrictionMap).get(any());

        // get allowed country codes EMPTY from resources
        when(mMockResources.getStringArray(
                com.android.internal.R.array.config_oem_enabled_satellite_country_codes))
                .thenReturn(TEST_SATELLITE_COUNTRY_CODES_EMPTY);

        // allow case that network country codes [US] with [EMPTY] configuration
        // location will not be compared and mQueriedSatelliteAllowed will be set false
        clearInvocations(mMockCachedAccessRestrictionMap);
        when(mMockCountryDetector.getCurrentNetworkCountryIso())
                .thenReturn(List.of(TEST_SATELLITE_COUNTRY_CODE_US));
        mSatelliteAccessControllerUT.loadOverlayConfigs(mMockContext);
        mSatelliteAccessControllerUT.requestIsCommunicationAllowedForCurrentLocation(
                mSatelliteAllowedReceiver, false);
        mTestableLooper.processAllMessages();
        verify(mMockCachedAccessRestrictionMap, times(0)).containsKey(any());
        assertFalse(mQueriedSatelliteAllowed);

        // allow case that network country codes [EMPTY] with [EMPTY] configuration
        // location will be compared and mQueriedSatelliteAllowed will be set true
        clearInvocations(mMockCachedAccessRestrictionMap);
        when(mMockCountryDetector.getCurrentNetworkCountryIso()).thenReturn(List.of());
        mSatelliteAccessControllerUT.loadOverlayConfigs(mMockContext);
        mSatelliteAccessControllerUT.requestIsCommunicationAllowedForCurrentLocation(
                mSatelliteAllowedReceiver, false);
        mTestableLooper.processAllMessages();
        verify(mMockCachedAccessRestrictionMap, times(1)).containsKey(any());
        assertTrue(mQueriedSatelliteAllowed);

        // get allowed country codes [US, CA, UK] from resources
        when(mMockResources.getStringArray(
                com.android.internal.R.array.config_oem_enabled_satellite_country_codes))
                .thenReturn(TEST_SATELLITE_COUNTRY_CODES);

        // allow case that network country codes [US, CA, UK] with [US, CA, UK] configuration
        // location will be compared and mQueriedSatelliteAllowed will be set true
        clearInvocations(mMockCachedAccessRestrictionMap);
        when(mMockCountryDetector.getCurrentNetworkCountryIso())
                .thenReturn(List.of(TEST_SATELLITE_COUNTRY_CODES));
        mSatelliteAccessControllerUT.loadOverlayConfigs(mMockContext);
        mSatelliteAccessControllerUT.requestIsCommunicationAllowedForCurrentLocation(
                mSatelliteAllowedReceiver, false);
        mTestableLooper.processAllMessages();
        verify(mMockCachedAccessRestrictionMap, times(1)).containsKey(any());
        assertTrue(mQueriedSatelliteAllowed);

        // allow case that network country codes [US] with [US, CA, UK] configuration
        // location will be compared and mQueriedSatelliteAllowed will be set true
        clearInvocations(mMockCachedAccessRestrictionMap);
        when(mMockCountryDetector.getCurrentNetworkCountryIso())
                .thenReturn(List.of(TEST_SATELLITE_COUNTRY_CODE_US));
        mSatelliteAccessControllerUT.loadOverlayConfigs(mMockContext);
        mSatelliteAccessControllerUT.requestIsCommunicationAllowedForCurrentLocation(
                mSatelliteAllowedReceiver, false);
        mTestableLooper.processAllMessages();
        verify(mMockCachedAccessRestrictionMap, times(1)).containsKey(any());
        assertTrue(mQueriedSatelliteAllowed);

        // allow case that network country codes [US, KR] with [US, CA, UK] configuration
        // location will be compared and mQueriedSatelliteAllowed will be set true
        clearInvocations(mMockCachedAccessRestrictionMap);
        when(mMockCountryDetector.getCurrentNetworkCountryIso()).thenReturn(
                List.of(TEST_SATELLITE_COUNTRY_CODE_US, TEST_SATELLITE_COUNTRY_CODE_KR));
        mSatelliteAccessControllerUT.loadOverlayConfigs(mMockContext);
        mSatelliteAccessControllerUT.requestIsCommunicationAllowedForCurrentLocation(
                mSatelliteAllowedReceiver, false);
        mTestableLooper.processAllMessages();
        verify(mMockCachedAccessRestrictionMap, times(1)).containsKey(any());
        assertTrue(mQueriedSatelliteAllowed);

        // allow case that network country codes [US] with [EMPTY] configuration
        // location will be compared and mQueriedSatelliteAllowed will be set true
        clearInvocations(mMockCachedAccessRestrictionMap);
        when(mMockCountryDetector.getCurrentNetworkCountryIso()).thenReturn(List.of());
        mSatelliteAccessControllerUT.loadOverlayConfigs(mMockContext);
        mSatelliteAccessControllerUT.requestIsCommunicationAllowedForCurrentLocation(
                mSatelliteAllowedReceiver, false);
        mTestableLooper.processAllMessages();
        verify(mMockCachedAccessRestrictionMap, times(1)).containsKey(any());
        assertTrue(mQueriedSatelliteAllowed);

        // allow case that network country codes [KR, JP] with [US, CA, UK] configuration
        // location will not be compared and mQueriedSatelliteAllowed will be set false
        clearInvocations(mMockCachedAccessRestrictionMap);
        when(mMockCountryDetector.getCurrentNetworkCountryIso()).thenReturn(
                List.of(TEST_SATELLITE_COUNTRY_CODE_KR, TEST_SATELLITE_COUNTRY_CODE_JP));
        mSatelliteAccessControllerUT.loadOverlayConfigs(mMockContext);
        mSatelliteAccessControllerUT.requestIsCommunicationAllowedForCurrentLocation(
                mSatelliteAllowedReceiver, false);
        mTestableLooper.processAllMessages();
        verify(mMockCachedAccessRestrictionMap, times(0)).containsKey(any());
        assertFalse(mQueriedSatelliteAllowed);

        // allow case that network country codes [KR] with [US, CA, UK] configuration
        // location will not be compared and mQueriedSatelliteAllowed will be set false
        clearInvocations(mMockCachedAccessRestrictionMap);
        when(mMockCountryDetector.getCurrentNetworkCountryIso())
                .thenReturn(List.of(TEST_SATELLITE_COUNTRY_CODE_KR));
        mSatelliteAccessControllerUT.loadOverlayConfigs(mMockContext);
        mSatelliteAccessControllerUT.requestIsCommunicationAllowedForCurrentLocation(
                mSatelliteAllowedReceiver, false);
        mTestableLooper.processAllMessages();
        verify(mMockCachedAccessRestrictionMap, times(0)).containsKey(any());
        assertFalse(mQueriedSatelliteAllowed);


        // set disallowed list case
        when(mMockResources.getBoolean(
                com.android.internal.R.bool.config_oem_enabled_satellite_access_allow))
                .thenReturn(TEST_SATELLITE_NOT_ALLOW);
        // get disallowed country codes list [EMPTY] from resources
        when(mMockResources.getStringArray(
                com.android.internal.R.array.config_oem_enabled_satellite_country_codes))
                .thenReturn(TEST_SATELLITE_COUNTRY_CODES_EMPTY);

        // disallow case that network country codes [US] with [EMPTY] configuration
        // location will be compared and mQueriedSatelliteAllowed will be set true
        clearInvocations(mMockCachedAccessRestrictionMap);
        when(mMockCountryDetector.getCurrentNetworkCountryIso())
                .thenReturn(List.of(TEST_SATELLITE_COUNTRY_CODE_US));
        mSatelliteAccessControllerUT.loadOverlayConfigs(mMockContext);
        mSatelliteAccessControllerUT.requestIsCommunicationAllowedForCurrentLocation(
                mSatelliteAllowedReceiver, false);
        mTestableLooper.processAllMessages();
        verify(mMockCachedAccessRestrictionMap, times(1)).containsKey(any());
        assertTrue(mQueriedSatelliteAllowed);

        // get disallowed country codes list ["US", "CA", "UK"] from resources
        when(mMockResources.getStringArray(
                com.android.internal.R.array.config_oem_enabled_satellite_country_codes))
                .thenReturn(TEST_SATELLITE_COUNTRY_CODES);

        // disallow case that network country codes [EMPTY] with [US, CA, UK] configuration
        // location will be compared and mQueriedSatelliteAllowed will be set true
        clearInvocations(mMockCachedAccessRestrictionMap);
        when(mMockCountryDetector.getCurrentNetworkCountryIso())
                .thenReturn(List.of(TEST_SATELLITE_COUNTRY_CODES_EMPTY));
        mSatelliteAccessControllerUT.loadOverlayConfigs(mMockContext);
        mSatelliteAccessControllerUT.requestIsCommunicationAllowedForCurrentLocation(
                mSatelliteAllowedReceiver, false);
        mTestableLooper.processAllMessages();
        verify(mMockCachedAccessRestrictionMap, times(1)).containsKey(any());
        assertTrue(mQueriedSatelliteAllowed);

        // disallow case that network country codes [US, JP] with [US, CA, UK] configuration
        // location will be compared and mQueriedSatelliteAllowed will be set true
        clearInvocations(mMockCachedAccessRestrictionMap);
        when(mMockCountryDetector.getCurrentNetworkCountryIso()).thenReturn(
                List.of(TEST_SATELLITE_COUNTRY_CODE_US, TEST_SATELLITE_COUNTRY_CODE_JP));
        mSatelliteAccessControllerUT.loadOverlayConfigs(mMockContext);
        mSatelliteAccessControllerUT.requestIsCommunicationAllowedForCurrentLocation(
                mSatelliteAllowedReceiver, false);
        mTestableLooper.processAllMessages();
        verify(mMockCachedAccessRestrictionMap, times(1)).containsKey(any());
        assertTrue(mQueriedSatelliteAllowed);

        // disallow case that network country codes [JP] with [US, CA, UK] configuration
        // location will be compared and mQueriedSatelliteAllowed will be set true
        clearInvocations(mMockCachedAccessRestrictionMap);
        when(mMockCountryDetector.getCurrentNetworkCountryIso())
                .thenReturn(List.of(TEST_SATELLITE_COUNTRY_CODE_JP));
        mSatelliteAccessControllerUT.loadOverlayConfigs(mMockContext);
        mSatelliteAccessControllerUT.requestIsCommunicationAllowedForCurrentLocation(
                mSatelliteAllowedReceiver, false);
        mTestableLooper.processAllMessages();
        verify(mMockCachedAccessRestrictionMap, times(1)).containsKey(any());
        assertTrue(mQueriedSatelliteAllowed);

        // disallow case that network country codes [US] with [US, CA, UK] configuration
        // location will not be compared and mQueriedSatelliteAllowed will be set false
        clearInvocations(mMockCachedAccessRestrictionMap);
        when(mMockCountryDetector.getCurrentNetworkCountryIso())
                .thenReturn(List.of(TEST_SATELLITE_COUNTRY_CODE_US));
        mSatelliteAccessControllerUT.loadOverlayConfigs(mMockContext);
        mSatelliteAccessControllerUT.requestIsCommunicationAllowedForCurrentLocation(
                mSatelliteAllowedReceiver, false);
        mTestableLooper.processAllMessages();
        verify(mMockCachedAccessRestrictionMap, times(0)).containsKey(any());
        assertFalse(mQueriedSatelliteAllowed);
    }

    @Test
    public void testRequestIsSatelliteCommunicationAllowedForCurrentLocation() throws Exception {
        // Satellite is not supported
        setUpResponseForRequestIsSatelliteSupported(false, SATELLITE_RESULT_SUCCESS);
        clearAllInvocations();
        mSatelliteAccessControllerUT.requestIsCommunicationAllowedForCurrentLocation(
                mSatelliteAllowedReceiver, false);
        mTestableLooper.processAllMessages();
        assertTrue(waitForRequestIsSatelliteAllowedForCurrentLocationResult(
                mSatelliteAllowedSemaphore, 1));
        assertEquals(SATELLITE_RESULT_NOT_SUPPORTED, mQueriedSatelliteAllowedResultCode);
        assertFalse(mQueriedSatelliteAllowed);

        // Failed to query whether satellite is supported or not
        setUpResponseForRequestIsSatelliteSupported(false, SATELLITE_RESULT_MODEM_ERROR);
        clearAllInvocations();
        mSatelliteAccessControllerUT.requestIsCommunicationAllowedForCurrentLocation(
                mSatelliteAllowedReceiver, false);
        mTestableLooper.processAllMessages();
        assertTrue(waitForRequestIsSatelliteAllowedForCurrentLocationResult(
                mSatelliteAllowedSemaphore, 1));
        assertEquals(SATELLITE_RESULT_MODEM_ERROR, mQueriedSatelliteAllowedResultCode);

        // Network country codes are not available. TelecomManager.isInEmergencyCall() returns true.
        // On-device access controller will be used. Last known location is available and fresh.
        clearAllInvocations();
        setUpResponseForRequestIsSatelliteSupported(true, SATELLITE_RESULT_SUCCESS);
        setUpResponseForRequestIsSatelliteProvisioned(true, SATELLITE_RESULT_SUCCESS);
        when(mMockCountryDetector.getCurrentNetworkCountryIso()).thenReturn(EMPTY_STRING_LIST);
        when(mMockTelecomManager.isInEmergencyCall()).thenReturn(true);
        mSatelliteAccessControllerUT.elapsedRealtimeNanos = TEST_LOCATION_FRESH_DURATION_NANOS + 1;
        when(mMockLocation0.getElapsedRealtimeNanos()).thenReturn(2L);
        when(mMockLocation1.getElapsedRealtimeNanos()).thenReturn(0L);
        mSatelliteAccessControllerUT.requestIsCommunicationAllowedForCurrentLocation(
                mSatelliteAllowedReceiver, false);
        mTestableLooper.processAllMessages();
        assertTrue(
                mSatelliteAccessControllerUT.isKeepOnDeviceAccessControllerResourcesTimerStarted());
        verify(mMockSatelliteOnDeviceAccessController).getRegionalConfigIdForLocation(
                any(SatelliteOnDeviceAccessController.LocationToken.class));
        assertTrue(waitForRequestIsSatelliteAllowedForCurrentLocationResult(
                mSatelliteAllowedSemaphore, 1));
        assertEquals(SATELLITE_RESULT_SUCCESS, mQueriedSatelliteAllowedResultCode);
        assertTrue(mQueriedSatelliteAllowed);

        // Move time forward and verify resources are cleaned up
        clearAllInvocations();
        mTestableLooper.moveTimeForward(mSatelliteAccessControllerUT
                .getKeepOnDeviceAccessControllerResourcesTimeoutMillis());
        mTestableLooper.processAllMessages();
        assertFalse(
                mSatelliteAccessControllerUT.isKeepOnDeviceAccessControllerResourcesTimerStarted());
        assertTrue(mSatelliteAccessControllerUT.isSatelliteOnDeviceAccessControllerReset());
        verify(mMockSatelliteOnDeviceAccessController).close();

        // Restore SatelliteOnDeviceAccessController for next verification
        mSatelliteAccessControllerUT.setSatelliteOnDeviceAccessController(
                mMockSatelliteOnDeviceAccessController);

        // Network country codes are not available. TelecomManager.isInEmergencyCall() returns
        // false. Phone0 is in ECM. On-device access controller will be used. Last known location is
        // not fresh.
        clearAllInvocations();
        when(mMockCountryDetector.getCurrentNetworkCountryIso()).thenReturn(EMPTY_STRING_LIST);
        when(mMockTelecomManager.isInEmergencyCall()).thenReturn(false);
        when(mMockPhone.isInEcm()).thenReturn(true);
        when(mMockPhone.getContext()).thenReturn(mMockContext);
        when(mMockPhone2.getContext()).thenReturn(mMockContext);
        mSatelliteAccessControllerUT.elapsedRealtimeNanos = TEST_LOCATION_FRESH_DURATION_NANOS + 1;
        when(mMockLocation0.getElapsedRealtimeNanos()).thenReturn(0L);
        when(mMockLocation1.getElapsedRealtimeNanos()).thenReturn(0L);
        mSatelliteAccessControllerUT.requestIsCommunicationAllowedForCurrentLocation(
                mSatelliteAllowedReceiver, false);
        mTestableLooper.processAllMessages();
        assertFalse(
                mSatelliteAccessControllerUT.isKeepOnDeviceAccessControllerResourcesTimerStarted());
        verify(mMockLocationManager).getCurrentLocation(eq(LocationManager.FUSED_PROVIDER),
                any(LocationRequest.class), mLocationRequestCancellationSignalCaptor.capture(),
                any(Executor.class), mLocationRequestConsumerCaptor.capture());
        assertTrue(mSatelliteAccessControllerUT.isWaitForCurrentLocationTimerStarted());
        sendLocationRequestResult(mMockLocation0);
        assertFalse(mSatelliteAccessControllerUT.isWaitForCurrentLocationTimerStarted());
        // The LocationToken should be already in the cache
        verify(mMockSatelliteOnDeviceAccessController, never()).getRegionalConfigIdForLocation(
                any(SatelliteOnDeviceAccessController.LocationToken.class));
        assertTrue(waitForRequestIsSatelliteAllowedForCurrentLocationResult(
                mSatelliteAllowedSemaphore, 1));
        assertEquals(SATELLITE_RESULT_SUCCESS, mQueriedSatelliteAllowedResultCode);
        assertTrue(mQueriedSatelliteAllowed);

        // Timed out to wait for current location. No cached allowed state.
        clearAllInvocations();
        mSatelliteAccessControllerUT.setIsSatelliteCommunicationAllowedForCurrentLocationCache(
                "cache_clear_and_not_allowed");
        when(mMockCountryDetector.getCurrentNetworkCountryIso()).thenReturn(EMPTY_STRING_LIST);
        when(mMockTelecomManager.isInEmergencyCall()).thenReturn(false);
        when(mMockPhone.isInEcm()).thenReturn(true);
        mSatelliteAccessControllerUT.elapsedRealtimeNanos = TEST_LOCATION_FRESH_DURATION_NANOS + 1;
        when(mMockLocation0.getElapsedRealtimeNanos()).thenReturn(0L);
        when(mMockLocation1.getElapsedRealtimeNanos()).thenReturn(0L);
        when(mMockCountryDetector.getCachedLocationCountryIsoInfo()).thenReturn(new Pair<>("", 0L));
        when(mMockCountryDetector.getCachedNetworkCountryIsoInfo()).thenReturn(new HashMap<>());
        mSatelliteAccessControllerUT.requestIsCommunicationAllowedForCurrentLocation(
                mSatelliteAllowedReceiver, false);
        mTestableLooper.processAllMessages();
        assertFalse(
                mSatelliteAccessControllerUT.isKeepOnDeviceAccessControllerResourcesTimerStarted());
        verify(mMockLocationManager).getCurrentLocation(anyString(), any(LocationRequest.class),
                any(CancellationSignal.class), any(Executor.class), any(Consumer.class));
        assertTrue(mSatelliteAccessControllerUT.isWaitForCurrentLocationTimerStarted());
        // Timed out
        mTestableLooper.moveTimeForward(
                mSatelliteAccessControllerUT.getWaitForCurrentLocationTimeoutMillis());
        mTestableLooper.processAllMessages();
        assertFalse(mSatelliteAccessControllerUT.isWaitForCurrentLocationTimerStarted());
        verify(mMockSatelliteOnDeviceAccessController, never()).getRegionalConfigIdForLocation(
                any(SatelliteOnDeviceAccessController.LocationToken.class));
        assertTrue(waitForRequestIsSatelliteAllowedForCurrentLocationResult(
                mSatelliteAllowedSemaphore, 1));
        assertEquals(SATELLITE_RESULT_LOCATION_NOT_AVAILABLE, mQueriedSatelliteAllowedResultCode);

        // Network country codes are not available. TelecomManager.isInEmergencyCall() returns
        // false. No phone is in ECM. Last known location is not fresh. Cached country codes should
        // be used for verifying satellite allow. No cached country codes are available.
        clearAllInvocations();
        when(mMockCountryDetector.getCurrentNetworkCountryIso()).thenReturn(EMPTY_STRING_LIST);
        when(mMockCountryDetector.getCachedLocationCountryIsoInfo()).thenReturn(new Pair<>("", 0L));
        when(mMockCountryDetector.getCachedNetworkCountryIsoInfo()).thenReturn(new HashMap<>());
        when(mMockTelecomManager.isInEmergencyCall()).thenReturn(false);
        when(mMockPhone.isInEcm()).thenReturn(false);
        when(mMockPhone2.isInEcm()).thenReturn(false);
        mSatelliteAccessControllerUT.elapsedRealtimeNanos = TEST_LOCATION_FRESH_DURATION_NANOS + 1;
        when(mMockLocation0.getElapsedRealtimeNanos()).thenReturn(0L);
        when(mMockLocation1.getElapsedRealtimeNanos()).thenReturn(0L);
        doReturn(false).when(mMockLocationManager).isLocationEnabled();
        mSatelliteAccessControllerUT.requestIsCommunicationAllowedForCurrentLocation(
                mSatelliteAllowedReceiver, false);
        mTestableLooper.processAllMessages();
        verify(mMockLocationManager, never()).getCurrentLocation(anyString(),
                any(LocationRequest.class), any(CancellationSignal.class), any(Executor.class),
                any(Consumer.class));
        verify(mMockSatelliteOnDeviceAccessController, never()).getRegionalConfigIdForLocation(
                any(SatelliteOnDeviceAccessController.LocationToken.class));
        assertTrue(waitForRequestIsSatelliteAllowedForCurrentLocationResult(
                mSatelliteAllowedSemaphore, 1));
        assertEquals(SATELLITE_RESULT_LOCATION_DISABLED, mQueriedSatelliteAllowedResultCode);
        assertFalse(mQueriedSatelliteAllowed);
    }

    @Test
    public void testLocationQueryThrottleTimeUpdate() {
        long firstMccChangedTime = 1;
        long lastKnownLocationElapsedRealtime =
                firstMccChangedTime + TEST_LOCATION_QUERY_THROTTLE_INTERVAL_NANOS;

        verify(mMockCountryDetector).registerForCountryCodeChanged(
                mCountryDetectorHandlerCaptor.capture(), mCountryDetectorIntCaptor.capture(),
                mCountryDetectorObjCaptor.capture());

        assertSame(mCountryDetectorHandlerCaptor.getValue(), mSatelliteAccessControllerUT);
        assertSame(EVENT_COUNTRY_CODE_CHANGED, mCountryDetectorIntCaptor.getValue());
        assertNull(mCountryDetectorObjCaptor.getValue());

        // Setup to invoke GPS query
        clearInvocations(mMockSatelliteOnDeviceAccessController);
        setUpResponseForRequestIsSatelliteSupported(true, SATELLITE_RESULT_SUCCESS);
        setUpResponseForRequestIsSatelliteProvisioned(true, SATELLITE_RESULT_SUCCESS);
        doReturn(true).when(mMockLocationManager).isLocationEnabled();
        when(mMockLocationManager.getLastKnownLocation(LocationManager.FUSED_PROVIDER))
                .thenReturn(null);
        when(mMockLocationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER))
                .thenReturn(null);

        // When mcc changed first, so queried a location with GPS,
        // verify if the mLastLocationQueryForPossibleChangeInAllowedRegionTimeNanos
        // is the same with firstMccChangedTime.
        // verify mMockLocationManager.getCurrentLocation() is invoked
        // verify time(mLastLocationQueryForPossibleChangeInAllowedRegionTimeNanos) is
        // firstMccChangedTime
        clearInvocations(mMockLocationManager);
        mSatelliteAccessControllerUT.elapsedRealtimeNanos = firstMccChangedTime;
        sendCommandValidateCountryCodeChangeEvent(mMockContext);
        verify(mMockLocationManager, times(1))
                .getCurrentLocation(any(), any(), any(), any(), any());
        assertEquals(firstMccChangedTime, mSatelliteAccessControllerUT
                .mLastLocationQueryForPossibleChangeInAllowedRegionTimeNanos);

        // set current time less than throttle_interval
        // verify mMockLocationManager.getCurrentLocation() is not invoked
        // verify time(mLastLocationQueryForPossibleChangeInAllowedRegionTimeNanos) is not updated
        clearInvocations(mMockLocationManager);
        doReturn(lastKnownLocationElapsedRealtime).when(mMockLocation1).getElapsedRealtimeNanos();
        mSatelliteAccessControllerUT.elapsedRealtimeNanos =
                (firstMccChangedTime + TEST_LOCATION_QUERY_THROTTLE_INTERVAL_NANOS - 1);
        sendCommandValidateCountryCodeChangeEvent(mMockContext);
        verify(mMockLocationManager, never())
                .getCurrentLocation(any(), any(), any(), any(), any());
        assertEquals(firstMccChangedTime, mSatelliteAccessControllerUT
                .mLastLocationQueryForPossibleChangeInAllowedRegionTimeNanos);

        // Test the scenario when last know location is fresh and
        // current time is greater than the location query throttle interval
        // verify mMockLocationManager.getCurrentLocation() is not invoked
        // verify time(mLastLocationQueryForPossibleChangeInAllowedRegionTimeNanos) is not updated
        clearInvocations(mMockLocationManager);
        doReturn(lastKnownLocationElapsedRealtime).when(mMockLocation1).getElapsedRealtimeNanos();
        mSatelliteAccessControllerUT.elapsedRealtimeNanos =
                (lastKnownLocationElapsedRealtime + TEST_LOCATION_FRESH_DURATION_NANOS - 1);
        sendCommandValidateCountryCodeChangeEvent(mMockContext);
        verify(mMockLocationManager, never())
                .getCurrentLocation(any(), any(), any(), any(), any());
        assertEquals(firstMccChangedTime, mSatelliteAccessControllerUT
                .mLastLocationQueryForPossibleChangeInAllowedRegionTimeNanos);

        // Test the scenario when last know location is not fresh and
        // current time is greater than the location query throttle interval
        // verify mMockLocationManager.getCurrentLocation() is invoked
        // verify time(mLastLocationQueryForPossibleChangeInAllowedRegionTimeNanos) is updated
        clearInvocations(mMockLocationManager);
        mSatelliteAccessControllerUT.setLocationRequestCancellationSignalAsNull(true);
        mSatelliteAccessControllerUT.elapsedRealtimeNanos =
                (lastKnownLocationElapsedRealtime + TEST_LOCATION_FRESH_DURATION_NANOS + 1);
        sendCommandValidateCountryCodeChangeEvent(mMockContext);
        verify(mMockLocationManager, times(1))
                .getCurrentLocation(any(), any(), any(), any(), any());
        assertEquals(lastKnownLocationElapsedRealtime + TEST_LOCATION_FRESH_DURATION_NANOS + 1,
                mSatelliteAccessControllerUT
                        .mLastLocationQueryForPossibleChangeInAllowedRegionTimeNanos);
    }


    @Test
    public void testAllowLocationQueryForSatelliteAllowedCheck() {
        mSatelliteAccessControllerUT.mLatestSatelliteCommunicationAllowedSetTime = 1;

        mSatelliteAccessControllerUT.setIsSatelliteAllowedRegionPossiblyChanged(false);
        // cash is invalid
        mSatelliteAccessControllerUT.elapsedRealtimeNanos =
                ALLOWED_STATE_CACHE_VALID_DURATION_NANOS + 10;
        assertTrue(mSatelliteAccessControllerUT.allowLocationQueryForSatelliteAllowedCheck());

        // cash is valid
        mSatelliteAccessControllerUT.elapsedRealtimeNanos =
                ALLOWED_STATE_CACHE_VALID_DURATION_NANOS - 10;
        assertFalse(mSatelliteAccessControllerUT.allowLocationQueryForSatelliteAllowedCheck());

        mSatelliteAccessControllerUT.setIsSatelliteAllowedRegionPossiblyChanged(true);
        // cash is invalid
        mSatelliteAccessControllerUT.elapsedRealtimeNanos =
                ALLOWED_STATE_CACHE_VALID_DURATION_NANOS + 10;
        assertTrue(mSatelliteAccessControllerUT.allowLocationQueryForSatelliteAllowedCheck());

        // cash is valid and throttled
        mSatelliteAccessControllerUT.elapsedRealtimeNanos =
                ALLOWED_STATE_CACHE_VALID_DURATION_NANOS - 10;

        // cash is valid and never queried before
        mSatelliteAccessControllerUT.mLastLocationQueryForPossibleChangeInAllowedRegionTimeNanos =
                0;
        assertTrue(mSatelliteAccessControllerUT.allowLocationQueryForSatelliteAllowedCheck());

        // cash is valid and throttled
        mSatelliteAccessControllerUT.mLastLocationQueryForPossibleChangeInAllowedRegionTimeNanos =
                mSatelliteAccessControllerUT.elapsedRealtimeNanos
                        - TEST_LOCATION_QUERY_THROTTLE_INTERVAL_NANOS + 100;
        assertFalse(mSatelliteAccessControllerUT.allowLocationQueryForSatelliteAllowedCheck());

        // cash is valid and not throttled
        mSatelliteAccessControllerUT.mLastLocationQueryForPossibleChangeInAllowedRegionTimeNanos =
                mSatelliteAccessControllerUT.elapsedRealtimeNanos
                        - TEST_LOCATION_QUERY_THROTTLE_INTERVAL_NANOS - 100;
        assertTrue(mSatelliteAccessControllerUT.allowLocationQueryForSatelliteAllowedCheck());
    }

    @Test
    public void testValidatePossibleChangeInSatelliteAllowedRegion() throws Exception {
        verify(mMockCountryDetector).registerForCountryCodeChanged(
                mCountryDetectorHandlerCaptor.capture(), mCountryDetectorIntCaptor.capture(),
                mCountryDetectorObjCaptor.capture());

        assertSame(mCountryDetectorHandlerCaptor.getValue(), mSatelliteAccessControllerUT);
        assertSame(mCountryDetectorIntCaptor.getValue(), EVENT_COUNTRY_CODE_CHANGED);
        assertNull(mCountryDetectorObjCaptor.getValue());

        // Normal case that invokes
        // mMockSatelliteOnDeviceAccessController.getRegionalConfigIdForLocation
        clearInvocations(mMockSatelliteOnDeviceAccessController);
        setUpResponseForRequestIsSatelliteSupported(true, SATELLITE_RESULT_SUCCESS);
        setUpResponseForRequestIsSatelliteProvisioned(true, SATELLITE_RESULT_SUCCESS);
        doReturn(true).when(mMockLocationManager).isLocationEnabled();
        mSatelliteAccessControllerUT.elapsedRealtimeNanos = TEST_LOCATION_FRESH_DURATION_NANOS;
        sendCommandValidateCountryCodeChangeEvent(mMockContext);
        verify(mMockSatelliteOnDeviceAccessController,
                times(1)).getRegionalConfigIdForLocation(
                any(SatelliteOnDeviceAccessController.LocationToken.class));

        // Case that isCommunicationAllowedCacheValid is true
        clearInvocations(mMockSatelliteOnDeviceAccessController);
        mSatelliteAccessControllerUT.elapsedRealtimeNanos = TEST_LOCATION_FRESH_DURATION_NANOS + 1;
        sendCommandValidateCountryCodeChangeEvent(mMockContext);
        verify(mMockSatelliteOnDeviceAccessController, never()).getRegionalConfigIdForLocation(
                any(SatelliteOnDeviceAccessController.LocationToken.class));

        // Case that mLatestCacheEnforcedValidateTimeNanos is over
        // ALLOWED_STATE_CACHE_VALIDATE_INTERVAL_NANOS (1hours)
        clearInvocations(mMockSatelliteOnDeviceAccessController);
        mSatelliteAccessControllerUT.elapsedRealtimeNanos =
                mSatelliteAccessControllerUT.elapsedRealtimeNanos
                        + TEST_LOCATION_QUERY_THROTTLE_INTERVAL_NANOS + 1;
        when(mMockLocation0.getElapsedRealtimeNanos())
                .thenReturn(mSatelliteAccessControllerUT.elapsedRealtimeNanos + 1L);
        when(mMockLocation1.getElapsedRealtimeNanos())
                .thenReturn(mSatelliteAccessControllerUT.elapsedRealtimeNanos + 1L);
        when(mMockLocation0.getLatitude()).thenReturn(2.0);
        when(mMockLocation0.getLongitude()).thenReturn(2.0);
        when(mMockLocation1.getLatitude()).thenReturn(3.0);
        when(mMockLocation1.getLongitude()).thenReturn(3.0);
        when(mMockSatelliteOnDeviceAccessController.getRegionalConfigIdForLocation(
                any(SatelliteOnDeviceAccessController.LocationToken.class))).thenReturn(null);
        sendCommandValidateCountryCodeChangeEvent(mMockContext);
        verify(mMockSatelliteOnDeviceAccessController,
                times(1)).getRegionalConfigIdForLocation(
                any(SatelliteOnDeviceAccessController.LocationToken.class));
    }

    @Test
    public void testRetryValidatePossibleChangeInSatelliteAllowedRegion() throws Exception {
        verify(mMockCountryDetector).registerForCountryCodeChanged(
                mCountryDetectorHandlerCaptor.capture(), mCountryDetectorIntCaptor.capture(),
                mCountryDetectorObjCaptor.capture());

        assertSame(mCountryDetectorHandlerCaptor.getValue(), mSatelliteAccessControllerUT);
        assertSame(mCountryDetectorIntCaptor.getValue(), EVENT_COUNTRY_CODE_CHANGED);
        assertNull(mCountryDetectorObjCaptor.getValue());

        assertTrue(mSatelliteAccessControllerUT
                .getRetryCountPossibleChangeInSatelliteAllowedRegion() == 0);

        setUpResponseForRequestIsSatelliteSupported(true, SATELLITE_RESULT_LOCATION_NOT_AVAILABLE);
        sendCommandValidateCountryCodeChangeEvent(mMockContext);

        assertTrue(mSatelliteAccessControllerUT
                .getRetryCountPossibleChangeInSatelliteAllowedRegion() == 1);

        mSatelliteAccessControllerUT.setRetryCountPossibleChangeInSatelliteAllowedRegion(
                DEFAULT_MAX_RETRY_COUNT_FOR_VALIDATING_POSSIBLE_CHANGE_IN_ALLOWED_REGION);
        sendSatelliteCommunicationAllowedEvent();
        assertTrue(mSatelliteAccessControllerUT
                .getRetryCountPossibleChangeInSatelliteAllowedRegion() == 0);

        mSatelliteAccessControllerUT.setRetryCountPossibleChangeInSatelliteAllowedRegion(2);
        setUpResponseForRequestIsSatelliteSupported(true, SATELLITE_RESULT_SUCCESS);
        sendSatelliteCommunicationAllowedEvent();
        assertTrue(mSatelliteAccessControllerUT
                .getRetryCountPossibleChangeInSatelliteAllowedRegion() == 0);
    }

    @Test
    public void testLoadSatelliteAccessConfigurationFromDeviceConfig() {
        when(mMockContext.getResources()).thenReturn(mMockResources);
        when(mMockResources
                .getString(eq(com.android.internal.R.string.satellite_access_config_file)))
                .thenReturn("test_satellite_file.json");
        assertEquals("test_satellite_file.json", mSatelliteAccessControllerUT
                .getSatelliteConfigurationFileNameFromOverlayConfig(mMockContext));

        when(mMockResources
                .getString(eq(com.android.internal.R.string.satellite_access_config_file)))
                .thenReturn(null);
        assertNull(mSatelliteAccessControllerUT
                .getSatelliteConfigurationFileNameFromOverlayConfig(mMockContext));
        try {
            mSatelliteAccessControllerUT.loadSatelliteAccessConfiguration();
        } catch (Exception e) {
            fail("Unexpected exception thrown: " + e.getMessage());
        }
    }


    @Test
    public void testUpdateSatelliteAccessDataWithConfigUpdaterData() throws Exception {
        logd("registering for config update changed");
        verify(mMockSatelliteController).registerForConfigUpdateChanged(
                mConfigUpdateHandlerCaptor.capture(), mConfigUpdateIntCaptor.capture(),
                mConfigUpdateObjectCaptor.capture());

        assertSame(mConfigUpdateHandlerCaptor.getValue(), mSatelliteAccessControllerUT);
        assertSame(mConfigUpdateIntCaptor.getValue(), EVENT_CONFIG_DATA_UPDATED);
        assertSame(mConfigUpdateObjectCaptor.getValue(), mMockContext);

        logd("replacing instance for mCachedAccessRestrictionMap");
        replaceInstance(SatelliteAccessController.class, "mCachedAccessRestrictionMap",
                mSatelliteAccessControllerUT, mMockCachedAccessRestrictionMap);

        // These APIs are executed during loadRemoteConfigs
        logd("verify load remote configs shared preferences method calls");
        verify(mMockSharedPreferences, times(1)).getInt(anyString(), anyInt());
        verify(mMockSharedPreferences, times(0)).getStringSet(anyString(), any());
        verify(mMockSharedPreferences, times(4)).getBoolean(anyString(), anyBoolean());

        // satelliteConfig is null
        logd("test for satelliteConfig is null");
        SatelliteConfigParser spyConfigParser =
                spy(new SatelliteConfigParser("test".getBytes()));
        doReturn(spyConfigParser).when(mMockSatelliteController).getSatelliteConfigParser();
        assertNull(spyConfigParser.getConfig());

        sendConfigUpdateChangedEvent(mMockContext);
        verify(mMockSharedPreferences, never()).edit();
        verify(mMockCachedAccessRestrictionMap, never()).clear();
        verify(mMockSatelliteController, times(1)).getSatelliteConfig();

        // satelliteConfig has satellite config data version(0) which is from device config.
        logd("test for satelliteConfig from device config version 0");
        SatelliteConfig mockConfig = mock(SatelliteConfig.class);
        doReturn(0).when(mockConfig).getSatelliteConfigDataVersion();
        doReturn(mockConfig).when(mMockSatelliteController).getSatelliteConfig();
        doReturn(false).when(mockConfig).isSatelliteDataForAllowedRegion();

        sendConfigUpdateChangedEvent(mMockContext);
        verify(mMockSharedPreferences, never()).edit();
        verify(mMockCachedAccessRestrictionMap, never()).clear();
        verify(mMockSatelliteController, times(2)).getSatelliteConfig();
        verify(mockConfig, times(1)).getSatelliteConfigDataVersion();
        verify(mockConfig, times(0)).getDeviceSatelliteCountryCodes();
        verify(mockConfig, times(0)).isSatelliteDataForAllowedRegion();
        verify(mockConfig, times(0)).getSatelliteS2CellFile(mMockContext);
        verify(mockConfig, times(0)).getSatelliteAccessConfigJsonFile(mMockContext);

        // satelliteConfig has invalid country codes
        logd("test for satelliteConfig with invalid country codes");
        doReturn(1).when(mockConfig).getSatelliteConfigDataVersion();
        doReturn(List.of("USA", "JAP")).when(mockConfig).getDeviceSatelliteCountryCodes();
        doReturn(mockConfig).when(mMockSatelliteController).getSatelliteConfig();
        doReturn(false).when(mockConfig).isSatelliteDataForAllowedRegion();

        sendConfigUpdateChangedEvent(mMockContext);
        verify(mMockSharedPreferences, never()).edit();
        verify(mMockCachedAccessRestrictionMap, never()).clear();
        verify(mMockSatelliteController, times(3)).getSatelliteConfig();
        verify(mockConfig, times(2)).getSatelliteConfigDataVersion();
        verify(mockConfig, times(1)).getDeviceSatelliteCountryCodes();
        verify(mockConfig, times(0)).isSatelliteDataForAllowedRegion();
        verify(mockConfig, times(0)).getSatelliteS2CellFile(mMockContext);
        verify(mockConfig, times(0)).getSatelliteAccessConfigJsonFile(mMockContext);

        // satelliteConfig does not have is_allow_access_control data
        logd("test for satelliteConfig does not have is_allow_access_control data");
        doReturn(List.of(TEST_SATELLITE_COUNTRY_CODES))
                .when(mockConfig).getDeviceSatelliteCountryCodes();
        doReturn(null).when(mockConfig).isSatelliteDataForAllowedRegion();

        sendConfigUpdateChangedEvent(mMockContext);
        verify(mMockSharedPreferences, never()).edit();
        verify(mMockCachedAccessRestrictionMap, never()).clear();
        verify(mMockSatelliteController, times(4)).getSatelliteConfig();
        verify(mockConfig, times(3)).getSatelliteConfigDataVersion();
        verify(mockConfig, times(2)).getDeviceSatelliteCountryCodes();
        verify(mockConfig, times(1)).isSatelliteDataForAllowedRegion();
        verify(mockConfig, times(0)).getSatelliteS2CellFile(mMockContext);
        verify(mockConfig, times(0)).getSatelliteAccessConfigJsonFile(mMockContext);

        // satelliteConfig doesn't have both S2CellFile and satellite access config json file
        logd(
                "test for satelliteConfig doesn't have both S2CellFile and satellite access config"
                        + " json file");
        File mockS2File = mock(File.class);
        doReturn(false).when(mockS2File).exists();
        File mockSatelliteAccessConfigJsonFile = mock(File.class);
        doReturn(false).when(mockSatelliteAccessConfigJsonFile).exists();
        doReturn(List.of(TEST_SATELLITE_COUNTRY_CODES))
                .when(mockConfig).getDeviceSatelliteCountryCodes();
        doReturn(true).when(mockConfig).isSatelliteDataForAllowedRegion();
        doReturn(mockS2File).when(mockConfig).getSatelliteS2CellFile(mMockContext);
        doReturn(mockSatelliteAccessConfigJsonFile)
                .when(mockConfig)
                .getSatelliteAccessConfigJsonFile(mMockContext);

        sendConfigUpdateChangedEvent(mMockContext);
        verify(mMockSharedPreferences, never()).edit();
        verify(mMockCachedAccessRestrictionMap, never()).clear();
        verify(mMockSatelliteController, times(5)).getSatelliteConfig();
        verify(mockConfig, times(4)).getSatelliteConfigDataVersion();
        verify(mockConfig, times(3)).getDeviceSatelliteCountryCodes();
        verify(mockConfig, times(2)).isSatelliteDataForAllowedRegion();
        verify(mockConfig, times(1)).getSatelliteS2CellFile(mMockContext);
        verify(mockConfig, times(0)).getSatelliteAccessConfigJsonFile(mMockContext);

        // satelliteConfig has s2CellFill, but doesn't have satellite access config json file
        logd(
                "test for satelliteConfig having s2CellFill, but doesn't have satellite access"
                        + " config json file");
        doReturn(mockConfig).when(mMockSatelliteController).getSatelliteConfig();
        File testS2File = mSatelliteAccessControllerUT
                .getTestSatelliteS2File(GOOGLE_US_SAN_SAT_S2_FILE_NAME);
        assertTrue("Test S2 file not created", testS2File != null && testS2File.exists());
        mockSatelliteAccessConfigJsonFile = mock(File.class);
        doReturn(false).when(mockSatelliteAccessConfigJsonFile).exists();
        doReturn(List.of(TEST_SATELLITE_COUNTRY_CODES))
                .when(mockConfig).getDeviceSatelliteCountryCodes();
        doReturn(true).when(mockConfig).isSatelliteDataForAllowedRegion();
        doReturn(testS2File).when(mockConfig).getSatelliteS2CellFile(mMockContext);
        doReturn(mockSatelliteAccessConfigJsonFile)
                .when(mockConfig)
                .getSatelliteAccessConfigJsonFile(mMockContext);

        sendConfigUpdateChangedEvent(mMockContext);
        verify(mMockSharedPreferences, never()).edit();
        verify(mMockCachedAccessRestrictionMap, never()).clear();
        verify(mMockSatelliteController, times(6)).getSatelliteConfig();
        verify(mockConfig, times(5)).getSatelliteConfigDataVersion();
        verify(mockConfig, times(4)).getDeviceSatelliteCountryCodes();
        verify(mockConfig, times(3)).isSatelliteDataForAllowedRegion();
        verify(mockConfig, times(2)).getSatelliteS2CellFile(mMockContext);
        verify(mockConfig, times(1)).getSatelliteAccessConfigJsonFile(mMockContext);

        // satelliteConfig has valid data
        logd("test for satelliteConfig having valid data");
        doReturn(true).when(mockConfig).isSatelliteDataForAllowedRegion();
        doReturn(List.of(TEST_SATELLITE_COUNTRY_CODES))
                .when(mockConfig)
                .getDeviceSatelliteCountryCodes();
        testS2File =
                mSatelliteAccessControllerUT.getTestSatelliteS2File(GOOGLE_US_SAN_SAT_S2_FILE_NAME);
        assertTrue("Test S2 file not created", testS2File != null && testS2File.exists());
        doReturn(testS2File).when(mockConfig).getSatelliteS2CellFile(mMockContext);
        File testSatelliteAccessConfigFile =
                mSatelliteAccessControllerUT.getTestSatelliteConfiguration(
                        SATELLITE_ACCESS_CONFIG_FILE_NAME);
        assertTrue(
                "Test satellite access config file not created",
                testSatelliteAccessConfigFile != null && testSatelliteAccessConfigFile.exists());
        doReturn(testSatelliteAccessConfigFile)
                .when(mockConfig)
                .getSatelliteAccessConfigJsonFile(mMockContext);

        sendConfigUpdateChangedEvent(mMockContext);
        verify(mMockSharedPreferences, times(3)).edit();
        verify(mMockCachedAccessRestrictionMap, times(1)).clear();
        verify(mMockSatelliteController, times(7)).getSatelliteConfig();
        verify(mockConfig, times(6)).getSatelliteConfigDataVersion();
        verify(mockConfig, times(5)).getDeviceSatelliteCountryCodes();
        verify(mockConfig, times(5)).isSatelliteDataForAllowedRegion();
        verify(mockConfig, times(3)).getSatelliteS2CellFile(mMockContext);
        verify(mockConfig, times(2)).getSatelliteAccessConfigJsonFile(mMockContext);
    }

    private String setupTestFileFromRawResource(int resId, String targetFileName)
            throws IOException {
        logd("setting up rest file for resId: " + resId + ", targetFileName: " + targetFileName);
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        InputStream is = context.getResources().openRawResource(resId);
        logd("Copying test file to temp_satellite_config_update");
        File tempDir =
                InstrumentationRegistry.getInstrumentation()
                        .getTargetContext()
                        .getDir("temp_satellite_config_update", Context.MODE_PRIVATE);
        File tempFile = new File(tempDir, targetFileName);
        FileOutputStream fos = new FileOutputStream(tempFile);
        byte[] buffer = new byte[1024];
        int length;
        while ((length = is.read(buffer)) > 0) {
            fos.write(buffer, 0, length);
        }
        is.close();
        fos.close();
        return tempFile.getAbsolutePath();
    }

    private boolean isLocationAllowed(
            ArgumentCaptor<Bundle> bundleCaptor,
            Iterator<ResultReceiver> mockResultReceiverIterator,
            Location location)
            throws Exception {
        clearInvocations(mMockResultReceiver);
        when(mMockLocationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER))
                .thenReturn(location);
        when(mMockLocationManager.getLastKnownLocation(LocationManager.FUSED_PROVIDER))
                .thenReturn(location);
        doReturn(true, false).when(mockResultReceiverIterator).hasNext();
        mSatelliteAccessControllerUT.checkSatelliteAccessRestrictionForLocation(location);
        verify(mMockResultReceiver, times(1))
                .send(mResultCodeIntCaptor.capture(), bundleCaptor.capture());
        if (mResultCodeIntCaptor.getValue() != SATELLITE_RESULT_SUCCESS) return false;
        return bundleCaptor.getValue().getBoolean(KEY_SATELLITE_COMMUNICATION_ALLOWED);
    }

    private void setupOnDeviceGeofenceData(
            int sats2ResId,
            String targetSats2FileName,
            int satelliteAccessConfigResId,
            String targetSatelliteAccessConfigFileName)
            throws Exception {
        logd("setting up on device geofence data");

        logd("Clearing on device geofence data");
        sendSatelliteDeviceAccessControllerResourcesTimeOutEvent();

        logd("Creating sats2.dat and satellite_access_config.json files");
        // set given sats2.dat and satellite_access_config.json as device geofence files
        doReturn(0).when(mMockSharedPreferences)
                .getInt(eq(CONFIG_UPDATER_SATELLITE_VERSION_KEY), anyInt());
        String sats2FilePath = setupTestFileFromRawResource(sats2ResId, targetSats2FileName);
        when(mMockResources.getString(
                        com.android.internal.R.string.config_oem_enabled_satellite_s2cell_file))
                .thenReturn(sats2FilePath);
        String satelliteAccessConfigFilePath =
                setupTestFileFromRawResource(
                        satelliteAccessConfigResId, targetSatelliteAccessConfigFileName);
        when(mMockResources.getString(com.android.internal.R.string.satellite_access_config_file))
                .thenReturn(satelliteAccessConfigFilePath);
        mSatelliteAccessControllerUT.loadOverlayConfigs(mMockContext);
    }

    private void setupOtaGeofenceData(
            int version,
            SatelliteConfig mockConfig,
            int sats2ResId,
            String targetSats2FileName,
            int satelliteAccessConfigResId,
            String targetSatelliteAccessConfigFileName,
            String[] countryCodes)
            throws Exception {
        String sats2FilePath = setupTestFileFromRawResource(sats2ResId, targetSats2FileName);
        String satelliteAccessConfigFilePath =
                setupTestFileFromRawResource(
                        satelliteAccessConfigResId, targetSatelliteAccessConfigFileName);

        File sats2File = new File(sats2FilePath);
        assertTrue("OTA geofence S2 file not created", sats2File != null && sats2File.exists());
        doReturn(sats2File).when(mockConfig).getSatelliteS2CellFile(mMockContext);

        File satelliteAccessConfigFile = new File(satelliteAccessConfigFilePath);
        assertTrue(
                "OTA geofence satellite access config file not created",
                satelliteAccessConfigFile != null && satelliteAccessConfigFile.exists());
        doReturn(satelliteAccessConfigFile)
                .when(mockConfig)
                .getSatelliteAccessConfigJsonFile(mMockContext);

        doReturn(true).when(mockConfig).isSatelliteDataForAllowedRegion();
        doReturn(List.of(TEST_SATELLITE_COUNTRY_CODES))
                .when(mockConfig)
                .getDeviceSatelliteCountryCodes();
        doReturn(version).when(mockConfig).getSatelliteConfigDataVersion();
        doReturn(version).when(mMockSharedPreferences)
                .getInt(eq(CONFIG_UPDATER_SATELLITE_VERSION_KEY), anyInt());
    }

    private boolean areOnDeviceAndOtaFilesValidAndDifferent(
            File onDeviceSats2File,
            File onDeviceSatelliteAccessConfigFile,
            File otaSats2File,
            File otaSatelliteAccessConfigFile) {
        if (onDeviceSats2File == null
                || onDeviceSatelliteAccessConfigFile == null
                || otaSats2File == null
                || otaSatelliteAccessConfigFile == null) {
            throw new AssertionError("Both on device and OTA files should NOT be null");
        }
        String onDeviceSats2FileAbsPath = onDeviceSats2File.getAbsolutePath();
        String onDeviceSatelliteAccessConfigFileAbsPath =
                onDeviceSatelliteAccessConfigFile.getAbsolutePath();
        String otaSats2FileAbsPath = otaSats2File.getAbsolutePath();
        String otaSatelliteAccessConfigFileAbsPath = otaSatelliteAccessConfigFile.getAbsolutePath();

        logd("onDeviceSats2FileAbsPath: " + onDeviceSats2FileAbsPath);
        logd(
                "onDeviceSatelliteAccessConfigFileAbsPath: "
                        + onDeviceSatelliteAccessConfigFileAbsPath);
        logd("otaSats2FileAbsPath: " + otaSats2FileAbsPath);
        logd("otaSatelliteAccessConfigFileAbsPath: " + otaSatelliteAccessConfigFileAbsPath);

        if (onDeviceSats2FileAbsPath.equals(otaSats2FileAbsPath)
                || onDeviceSatelliteAccessConfigFileAbsPath.equals(
                        otaSatelliteAccessConfigFileAbsPath)) {
            return false;
        }

        logd("areOnDeviceAndOtaFilesValidAndDifferent: true");
        return true;
    }

    @Test
    public void testConfigUpdateAndCorrespondingSatelliteAllowedAtLocationChecks()
            throws Exception {
        replaceInstance(
                SatelliteAccessController.class,
                "mS2Level",
                mSatelliteAccessControllerUT,
                DEFAULT_S2_LEVEL);
        when(mMockFeatureFlags.carrierRoamingNbIotNtn()).thenReturn(true);
        when(mMockContext.getResources()).thenReturn(mMockResources);
        when(mMockResources.getBoolean(
                        com.android.internal.R.bool.config_oem_enabled_satellite_access_allow))
                .thenReturn(TEST_SATELLITE_ALLOW);
        setUpResponseForRequestIsSatelliteSupported(true, SATELLITE_RESULT_SUCCESS);
        setUpResponseForRequestIsSatelliteProvisioned(true, SATELLITE_RESULT_SUCCESS);
        doReturn(true).when(mMockLocationManager).isLocationEnabled();

        ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        Iterator<ResultReceiver> mockResultReceiverIterator = mock(Iterator.class);
        doReturn(mockResultReceiverIterator).when(mMockSatelliteAllowResultReceivers).iterator();
        doNothing().when(mMockSatelliteAllowResultReceivers).clear();
        doReturn(mMockResultReceiver).when(mockResultReceiverIterator).next();
        replaceInstance(
                SatelliteAccessController.class,
                "mSatelliteAllowResultReceivers",
                mSatelliteAccessControllerUT,
                mMockSatelliteAllowResultReceivers);
        replaceInstance(
                SatelliteAccessController.class,
                "mCachedAccessRestrictionMap",
                mSatelliteAccessControllerUT,
                mMockCachedAccessRestrictionMap);

        SatelliteConfig mockConfig = mock(SatelliteConfig.class);
        doReturn(mockConfig).when(mMockSatelliteController).getSatelliteConfig();

        Location locationUS = mock(Location.class);
        when(locationUS.getLatitude()).thenReturn(37.7749);
        when(locationUS.getLongitude()).thenReturn(-122.4194);
        Location locationKR = mock(Location.class);
        when(locationKR.getLatitude()).thenReturn(37.5665);
        when(locationKR.getLongitude()).thenReturn(126.9780);
        Location locationTW = mock(Location.class);
        when(locationTW.getLatitude()).thenReturn(25.034);
        when(locationTW.getLongitude()).thenReturn(121.565);

        // Test v15 geofence data - supports US location
        // set v15's sats2.dat and satellite_access_config.json as device geofence files and
        // verify for below locations are allowed or not for satellite commiunication as expected.
        // location1 - US - allowed; location2 - KR - not allowed; location3 - TW - not allowed;
        logd(
                "Step 1: Testing v15 (US) satellite config files. Expectation: locationUS -"
                        + " allowed; locationKR - not allowed; locationTW - not allowed");
        setupOnDeviceGeofenceData(
                com.android.phone.tests.R.raw.v15_sats2,
                "v15_sats2.dat",
                com.android.phone.tests.R.raw.v15_satellite_access_config,
                "v15_satellite_access_config.json");
        assertEquals(0, mSatelliteAccessControllerUT.getSatelliteAccessConfigVersion());
        assertTrue(isLocationAllowed(bundleCaptor, mockResultReceiverIterator, locationUS));
        assertFalse(isLocationAllowed(bundleCaptor, mockResultReceiverIterator, locationKR));
        assertFalse(isLocationAllowed(bundleCaptor, mockResultReceiverIterator, locationTW));
        Map<Integer, SatelliteAccessConfiguration> satelliteAccessConfigurationMap =
                mSatelliteAccessControllerUT.getSatelliteAccessConfigMap();
        logd("Obatined satelliteAccessConfigurationMap: " + satelliteAccessConfigurationMap);
        assertEquals(6, satelliteAccessConfigurationMap.size());
        assertEquals(
                "62de127d-ead1-481f-8524-b58e2664103a",
                satelliteAccessConfigurationMap
                        .get(5)
                        .getSatelliteInfos()
                        .get(0)
                        .getSatelliteId()
                        .toString());
        assertEquals(
                -98.0,
                satelliteAccessConfigurationMap
                        .get(5)
                        .getSatelliteInfos()
                        .get(0)
                        .getSatellitePosition()
                        .getLongitudeDegrees(),
                0.001);
        assertEquals(
                35775.1,
                satelliteAccessConfigurationMap
                        .get(5)
                        .getSatelliteInfos()
                        .get(0)
                        .getSatellitePosition()
                        .getAltitudeKm(),
                0.001);
        File onDeviceSats2File = mSatelliteAccessControllerUT.getSatelliteS2CellFile();
        File onDeviceSatelliteAccessConfigFile =
                mSatelliteAccessControllerUT.getSatelliteAccessConfigFile();

        // Test v16 geofence data - supports US, KR, TW locations
        // Simulate config update to override v16's sats2.dat and satellite_access_config.json
        // as the geofence files.
        // And verify for below locations are allowed or not for satellite commiunication as
        // expected.
        // location1 - US - allowed; location2 - KR - allowed; location3 - TW - allowed;
        logd(
                "Step 2: Testing v16 (US, KR, TW) satellite config files."
                        + " Simulate config update for v16 files. Expectation: locationUS -"
                        + " allowed; locationKR - allowed; locationTW - allowed");
        setupOtaGeofenceData(
                16,
                mockConfig,
                com.android.phone.tests.R.raw.v16_sats2,
                "v16_sats2.dat",
                com.android.phone.tests.R.raw.v16_satellite_access_config,
                "v16_satellite_access_config.json",
                new String[] {"US", "CA", "UK", "KR", "TW"});
        sendConfigUpdateChangedEvent(mMockContext);

        assertEquals(16, mSatelliteAccessControllerUT.getSatelliteAccessConfigVersion());
        assertTrue(isLocationAllowed(bundleCaptor, mockResultReceiverIterator, locationUS));
        assertTrue(isLocationAllowed(bundleCaptor, mockResultReceiverIterator, locationKR));
        assertTrue(isLocationAllowed(bundleCaptor, mockResultReceiverIterator, locationTW));
        satelliteAccessConfigurationMap =
                mSatelliteAccessControllerUT.getSatelliteAccessConfigMap();
        logd("Obatined satelliteAccessConfigurationMap: " + satelliteAccessConfigurationMap);
        assertEquals(8, satelliteAccessConfigurationMap.size());
        assertEquals(
                "c9d78ffa-ffa5-4d41-a81b-34693b33b496",
                satelliteAccessConfigurationMap
                        .get(6)
                        .getSatelliteInfos()
                        .get(0)
                        .getSatelliteId()
                        .toString());
        assertEquals(
                -101.3,
                satelliteAccessConfigurationMap
                        .get(6)
                        .getSatelliteInfos()
                        .get(0)
                        .getSatellitePosition()
                        .getLongitudeDegrees(),
                0.001);
        assertEquals(
                35786.0,
                satelliteAccessConfigurationMap
                        .get(6)
                        .getSatelliteInfos()
                        .get(0)
                        .getSatellitePosition()
                        .getAltitudeKm(),
                0.001);
        File otaSats2File = mSatelliteAccessControllerUT.getSatelliteS2CellFile();
        File otaSatelliteAccessConfigFile =
                mSatelliteAccessControllerUT.getSatelliteAccessConfigFile();
        assertTrue(
                areOnDeviceAndOtaFilesValidAndDifferent(
                        onDeviceSats2File,
                        onDeviceSatelliteAccessConfigFile,
                        otaSats2File,
                        otaSatelliteAccessConfigFile));

        // Test v17 geofence data - supports US location
        // Simulate config update to override v17's sats2.dat and satellite_access_config.json
        // as the geofence files.
        // And verify for below locations are allowed or not for satellite commiunication as
        // expected.
        // location1 - US - allowed; location2 - KR - not allowed; location3 - TW - not allowed;
        logd(
                "Step 3: Testing v17 (US) satellite config files."
                        + " Simulate config update for v17 files. Expectation: locationUS -"
                        + " allowed; locationKR - not allowed; locationTW - not allowed");
        setupOtaGeofenceData(
                17,
                mockConfig,
                com.android.phone.tests.R.raw.v17_sats2,
                "v17_sats2.dat",
                com.android.phone.tests.R.raw.v17_satellite_access_config,
                "v17_satellite_access_config.json",
                new String[] {"US", "CA", "UK", "KR", "TW"});
        sendConfigUpdateChangedEvent(mMockContext);

        assertEquals(17, mSatelliteAccessControllerUT.getSatelliteAccessConfigVersion());
        assertTrue(isLocationAllowed(bundleCaptor, mockResultReceiverIterator, locationUS));
        assertFalse(isLocationAllowed(bundleCaptor, mockResultReceiverIterator, locationKR));
        assertFalse(isLocationAllowed(bundleCaptor, mockResultReceiverIterator, locationTW));
        satelliteAccessConfigurationMap =
                mSatelliteAccessControllerUT.getSatelliteAccessConfigMap();
        logd("Obatined satelliteAccessConfigurationMap: " + satelliteAccessConfigurationMap);
        assertEquals(6, satelliteAccessConfigurationMap.size());
        assertEquals(
                "62de127d-ead1-481f-8524-b58e2664103a",
                satelliteAccessConfigurationMap
                        .get(5)
                        .getSatelliteInfos()
                        .get(0)
                        .getSatelliteId()
                        .toString());
        assertEquals(
                -98.0,
                satelliteAccessConfigurationMap
                        .get(5)
                        .getSatelliteInfos()
                        .get(0)
                        .getSatellitePosition()
                        .getLongitudeDegrees(),
                0.001);
        assertEquals(
                35775.1,
                satelliteAccessConfigurationMap
                        .get(5)
                        .getSatelliteInfos()
                        .get(0)
                        .getSatellitePosition()
                        .getAltitudeKm(),
                0.001);
        otaSats2File = mSatelliteAccessControllerUT.getSatelliteS2CellFile();
        otaSatelliteAccessConfigFile = mSatelliteAccessControllerUT.getSatelliteAccessConfigFile();
        assertTrue(
                areOnDeviceAndOtaFilesValidAndDifferent(
                        onDeviceSats2File,
                        onDeviceSatelliteAccessConfigFile,
                        otaSats2File,
                        otaSatelliteAccessConfigFile));
    }

    @Test
    public void testLocationModeChanged() throws Exception {
        logd("testLocationModeChanged: setup to query the current location");
        when(mMockFeatureFlags.oemEnabledSatelliteFlag()).thenReturn(true);
        when(mMockContext.getResources()).thenReturn(mMockResources);
        when(mMockResources.getBoolean(
                com.android.internal.R.bool.config_oem_enabled_satellite_access_allow))
                .thenReturn(TEST_SATELLITE_ALLOW);
        setUpResponseForRequestIsSatelliteSupported(true, SATELLITE_RESULT_SUCCESS);
        setUpResponseForRequestIsSatelliteProvisioned(true, SATELLITE_RESULT_SUCCESS);
        when(mMockSatelliteOnDeviceAccessController.getRegionalConfigIdForLocation(
                any(SatelliteOnDeviceAccessController.LocationToken.class)))
                .thenReturn(DEFAULT_REGIONAL_SATELLITE_CONFIG_ID);
        replaceInstance(SatelliteAccessController.class, "mCachedAccessRestrictionMap",
                mSatelliteAccessControllerUT, mMockCachedAccessRestrictionMap);
        doReturn(false).when(mMockCachedAccessRestrictionMap).containsKey(any());
        mSatelliteAccessControllerUT.elapsedRealtimeNanos = TEST_LOCATION_FRESH_DURATION_NANOS + 1;

        logd("testLocationModeChanged: "
                + "captor and verify if the mockReceiver and mockContext is registered well");
        verify(mMockContext, times(2)).registerReceiver(
                mLocationBroadcastReceiverCaptor.capture(), mIntentFilterCaptor.capture());

        logd("testLocationModeChanged: verify if the location manager doesn't invoke "
                + "isLocationEnabled(), when the intent action is not MODE_CHANGED_ACTION");
        doReturn("").when(mMockLocationIntent).getAction();
        mSatelliteAccessControllerUT.setIsSatelliteAllowedRegionPossiblyChanged(false);
        mSatelliteAccessControllerUT.getLocationBroadcastReceiver()
                .onReceive(mMockContext, mMockLocationIntent);
        verify(mMockLocationManager, never()).isLocationEnabled();

        // When the intent action is MODE_CHANGED_ACTION and isLocationEnabled() is true,
        // verify if mIsSatelliteAllowedRegionPossiblyChanged is true
        logd("testLocationModeChanged: verify if mIsSatelliteAllowedRegionPossiblyChanged is true, "
                + "when the intent action is MODE_CHANGED_ACTION and isLocationEnabled() is true");
        doReturn(MODE_CHANGED_ACTION).when(mMockLocationIntent).getAction();
        doReturn(true).when(mMockLocationManager).isLocationEnabled();
        clearInvocations(mMockLocationManager);
        mSatelliteAccessControllerUT.setIsSatelliteAllowedRegionPossiblyChanged(false);
        mSatelliteAccessControllerUT.getLocationBroadcastReceiver()
                .onReceive(mMockContext, mMockLocationIntent);
        verify(mMockLocationManager, times(1)).isLocationEnabled();
        mTestableLooper.processAllMessages();
        assertEquals(true, mSatelliteAccessControllerUT.isSatelliteAllowedRegionPossiblyChanged());

        logd("testLocationModeChanged: "
                + "verify if mIsSatelliteAllowedRegionPossiblyChanged is false, "
                + "when the intent action is MODE_CHANGED_ACTION and isLocationEnabled() is false");
        mSatelliteAccessControllerUT
                .setIsSatelliteCommunicationAllowedForCurrentLocationCache("cache_allowed");
        replaceInstance(SatelliteAccessController.class,
                "mSatelliteCommunicationAccessStateChangedListeners",
                mSatelliteAccessControllerUT,
                mMockSatelliteCommunicationAccessStateChangedListeners);
        doReturn(false).when(mMockLocationManager).isLocationEnabled();
        clearInvocations(mMockLocationManager);
        mSatelliteAccessControllerUT.setIsSatelliteAllowedRegionPossiblyChanged(false);
        mSatelliteAccessControllerUT.getLocationBroadcastReceiver()
                .onReceive(mMockContext, mMockLocationIntent);
        verify(mMockLocationManager, times(1)).isLocationEnabled();
        mTestableLooper.processAllMessages();
        assertEquals(false,
                mSatelliteAccessControllerUT.isSatelliteAllowedRegionPossiblyChanged());
        verify(mMockSatelliteCommunicationAccessStateChangedListeners, times(1)).values();
    }

    @Test
    public void testCheckSatelliteAccessRestrictionUsingGPS() {
        // In emergency case,
        // verify if the location manager get FUSED provider and ignore location settings
        doReturn(true).when(mMockTelecomManager).isInEmergencyCall();
        mSatelliteAccessControllerUT.setLocationRequestCancellationSignalAsNull(true);
        mSatelliteAccessControllerUT.elapsedRealtimeNanos = TEST_LOCATION_FRESH_DURATION_NANOS + 1;
        mSatelliteAccessControllerUT.checkSatelliteAccessRestrictionUsingGPS();

        verify(mMockLocationManager, times(1))
                .getCurrentLocation(mLocationProviderStringCaptor.capture(),
                        mLocationRequestCaptor.capture(), any(), any(), any());
        assertEquals(LocationManager.FUSED_PROVIDER, mLocationProviderStringCaptor.getValue());
        assertTrue(mLocationRequestCaptor.getValue().isLocationSettingsIgnored());

        // In non-emergency case,
        // verify if the location manager get FUSED provider and not ignore location settings
        clearInvocations(mMockLocationManager);
        doReturn(false).when(mMockTelecomManager).isInEmergencyCall();
        doReturn(false).when(mMockPhone).isInEcm();
        doReturn(false).when(mMockPhone2).isInEcm();
        doReturn(false).when(mMockSatelliteController).isInEmergencyMode();
        doReturn(true).when(mMockLocationManager).isLocationEnabled();
        mSatelliteAccessControllerUT.setLocationRequestCancellationSignalAsNull(true);
        mSatelliteAccessControllerUT.checkSatelliteAccessRestrictionUsingGPS();

        verify(mMockLocationManager, times(1))
                .getCurrentLocation(mLocationProviderStringCaptor.capture(),
                        mLocationRequestCaptor.capture(), any(), any(), any());
        assertEquals(LocationManager.FUSED_PROVIDER, mLocationProviderStringCaptor.getValue());
        assertFalse(mLocationRequestCaptor.getValue().isLocationSettingsIgnored());
    }

    @Test
    public void testHandleIsSatelliteSupportedResult() throws Exception {
        // Setup for this test case
        Iterator<ResultReceiver> mockResultReceiverIterator = mock(Iterator.class);
        doReturn(mockResultReceiverIterator).when(mMockSatelliteAllowResultReceivers).iterator();
        doReturn(true, false).when(mockResultReceiverIterator).hasNext();
        doReturn(mMockResultReceiver).when(mockResultReceiverIterator).next();

        replaceInstance(SatelliteAccessController.class, "mSatelliteAllowResultReceivers",
                mSatelliteAccessControllerUT, mMockSatelliteAllowResultReceivers);
        doNothing().when(mMockSatelliteAllowResultReceivers).clear();

        // case that resultCode is not SATELLITE_RESULT_SUCCESS
        int resultCode = SATELLITE_RESULT_ERROR;
        Bundle bundle = new Bundle();
        doReturn(true, false).when(mockResultReceiverIterator).hasNext();
        clearInvocations(mMockResultReceiver);
        mSatelliteAccessControllerUT.handleIsSatelliteSupportedResult(resultCode, bundle);
        verify(mMockResultReceiver)
                .send(mResultCodeIntCaptor.capture(), any());
        assertEquals(Integer.valueOf(SATELLITE_RESULT_ERROR), mResultCodeIntCaptor.getValue());

        // case no KEY_SATELLITE_SUPPORTED in the bundle data.
        // verify that the resultCode is delivered as it were
        resultCode = SATELLITE_RESULT_SUCCESS;
        bundle.putBoolean(KEY_SATELLITE_PROVISIONED, false);
        doReturn(true, false).when(mockResultReceiverIterator).hasNext();
        clearInvocations(mMockResultReceiver);
        mSatelliteAccessControllerUT.handleIsSatelliteSupportedResult(resultCode, bundle);
        verify(mMockResultReceiver).send(mResultCodeIntCaptor.capture(), any());
        assertEquals(Integer.valueOf(SATELLITE_RESULT_SUCCESS), mResultCodeIntCaptor.getValue());

        // case KEY_SATELLITE_SUPPORTED is false
        // verify SATELLITE_RESULT_NOT_SUPPORTED is captured
        bundle.putBoolean(KEY_SATELLITE_SUPPORTED, false);
        doReturn(true, false).when(mockResultReceiverIterator).hasNext();
        clearInvocations(mMockResultReceiver);
        mSatelliteAccessControllerUT.handleIsSatelliteSupportedResult(resultCode, bundle);
        verify(mMockResultReceiver)
                .send(mResultCodeIntCaptor.capture(), mResultDataBundleCaptor.capture());
        assertEquals(Integer.valueOf(SATELLITE_RESULT_NOT_SUPPORTED),
                mResultCodeIntCaptor.getValue());
        assertEquals(false,
                mResultDataBundleCaptor.getValue().getBoolean(KEY_SATELLITE_COMMUNICATION_ALLOWED));

        // case KEY_SATELLITE_SUPPORTED is success and region is not allowed
        // verify SATELLITE_RESULT_SUCCESS is captured
        bundle.putBoolean(KEY_SATELLITE_SUPPORTED, true);
        when(mMockCountryDetector.getCurrentNetworkCountryIso())
                .thenReturn(List.of(TEST_SATELLITE_COUNTRY_CODE_KR));
        doReturn(true, false).when(mockResultReceiverIterator).hasNext();
        clearInvocations(mMockResultReceiver);
        mSatelliteAccessControllerUT.handleIsSatelliteSupportedResult(resultCode, bundle);
        verify(mMockResultReceiver)
                .send(mResultCodeIntCaptor.capture(), mResultDataBundleCaptor.capture());
        assertEquals(Integer.valueOf(SATELLITE_RESULT_SUCCESS),
                mResultCodeIntCaptor.getValue());
        assertEquals(false,
                mResultDataBundleCaptor.getValue().getBoolean(KEY_SATELLITE_COMMUNICATION_ALLOWED));

        // case KEY_SATELLITE_SUPPORTED is success and locationManager is disabled
        // verify SATELLITE_RESULT_LOCATION_DISABLED is captured
        when(mMockCountryDetector.getCurrentNetworkCountryIso())
                .thenReturn(List.of(TEST_SATELLITE_COUNTRY_CODE_US));
        doReturn(false).when(mMockLocationManager).isLocationEnabled();
        doReturn(true, false).when(mockResultReceiverIterator).hasNext();
        clearInvocations(mMockResultReceiver);
        mSatelliteAccessControllerUT.handleIsSatelliteSupportedResult(resultCode, bundle);
        verify(mMockResultReceiver)
                .send(mResultCodeIntCaptor.capture(), mResultDataBundleCaptor.capture());
        assertEquals(Integer.valueOf(SATELLITE_RESULT_LOCATION_DISABLED),
                mResultCodeIntCaptor.getValue());
        assertEquals(false,
                mResultDataBundleCaptor.getValue().getBoolean(KEY_SATELLITE_COMMUNICATION_ALLOWED));
    }

    @Test
    public void testRequestIsCommunicationAllowedForCurrentLocationWithEnablingSatellite() {
        // Set non-emergency case
        setUpResponseForRequestIsSatelliteSupported(true, SATELLITE_RESULT_SUCCESS);
        setUpResponseForRequestIsSatelliteProvisioned(true, SATELLITE_RESULT_SUCCESS);
        when(mMockCountryDetector.getCurrentNetworkCountryIso()).thenReturn(EMPTY_STRING_LIST);
        doReturn(false).when(mMockTelecomManager).isInEmergencyCall();
        doReturn(false).when(mMockPhone).isInEcm();
        doReturn(false).when(mMockPhone2).isInEcm();
        doReturn(false).when(mMockSatelliteController).isInEmergencyMode();
        doReturn(true).when(mMockLocationManager).isLocationEnabled();
        mSatelliteAccessControllerUT.setLocationRequestCancellationSignalAsNull(true);
        mSatelliteAccessControllerUT.elapsedRealtimeNanos = TEST_LOCATION_FRESH_DURATION_NANOS + 1;

        // Invoking requestIsCommunicationAllowedForCurrentLocation(resultReceiver, "false");
        // verify that mLocationManager.isLocationEnabled() is invoked
        mSatelliteAccessControllerUT.requestIsCommunicationAllowedForCurrentLocation(
                mSatelliteAllowedReceiver, false);
        mTestableLooper.processAllMessages();
        verify(mMockLocationManager, times(1)).isLocationEnabled();
        verify(mMockLocationManager, times(1)).getCurrentLocation(anyString(),
                any(LocationRequest.class), any(CancellationSignal.class), any(Executor.class),
                any(Consumer.class));

        // Invoking requestIsCommunicationAllowedForCurrentLocation(resultReceiver, "true");
        // verify that mLocationManager.isLocationEnabled() is not invoked
        clearInvocations(mMockLocationManager);
        mSatelliteAccessControllerUT.requestIsCommunicationAllowedForCurrentLocation(
                mSatelliteAllowedReceiver, true);
        mTestableLooper.processAllMessages();
        verify(mMockLocationManager, times(1)).isLocationEnabled();
        verify(mMockLocationManager, never()).getCurrentLocation(anyString(),
                any(LocationRequest.class), any(CancellationSignal.class), any(Executor.class),
                any(Consumer.class));
    }

    @Test
    public void testUpdateSystemSelectionChannels() {
        // Set non-emergency case
        when(mMockFeatureFlags.carrierRoamingNbIotNtn()).thenReturn(true);

        setUpResponseForRequestIsSatelliteSupported(true, SATELLITE_RESULT_SUCCESS);
        setUpResponseForRequestIsSatelliteProvisioned(true, SATELLITE_RESULT_SUCCESS);
        when(mMockCountryDetector.getCurrentNetworkCountryIso()).thenReturn(EMPTY_STRING_LIST);

        // Invoke when regional config ID is not set.
        mSatelliteAccessControllerUT.setRegionalConfigId(null);
        mSatelliteAccessControllerUT.updateSystemSelectionChannels(
                mSystemSelectionChannelUpdatedReceiver);
        mTestableLooper.processAllMessages();
        assertTrue(waitForRequestUpdateSystemSelectionChannelResult(
                mSystemSelectionChannelUpdatedSemaphore, 1));
        assertEquals(SATELLITE_RESULT_ACCESS_BARRED,
                mQueriedSystemSelectionChannelUpdatedResultCode);

        // Invoke when mSatelliteAccessConfigMap does not have data for given regional config ID
        int satelliteRegionalConfigId = DEFAULT_REGIONAL_SATELLITE_CONFIG_ID;
        mSatelliteAccessControllerUT.setRegionalConfigId(satelliteRegionalConfigId);
        mSatelliteAccessControllerUT.resetSatelliteAccessConfigMap();
        mSatelliteAccessControllerUT.updateSystemSelectionChannels(
                mSystemSelectionChannelUpdatedReceiver);
        mTestableLooper.processAllMessages();
        assertTrue(waitForRequestUpdateSystemSelectionChannelResult(
                mSystemSelectionChannelUpdatedSemaphore, 1));
        assertEquals(SATELLITE_RESULT_ACCESS_BARRED,
                mQueriedSystemSelectionChannelUpdatedResultCode);

        // Invoke when mSatelliteAccessConfigMap does not have data and given data is old format.
        satelliteRegionalConfigId = UNKNOWN_REGIONAL_SATELLITE_CONFIG_ID;
        mSatelliteAccessControllerUT.setRegionalConfigId(satelliteRegionalConfigId);
        mSatelliteAccessControllerUT.resetSatelliteAccessConfigMap();
        mSatelliteAccessControllerUT.updateSystemSelectionChannels(
                mSystemSelectionChannelUpdatedReceiver);
        mTestableLooper.processAllMessages();
        assertTrue(waitForRequestUpdateSystemSelectionChannelResult(
                mSystemSelectionChannelUpdatedSemaphore, 1));
        assertEquals(SATELLITE_RESULT_ACCESS_BARRED,
                mQueriedSystemSelectionChannelUpdatedResultCode);

        satelliteRegionalConfigId = DEFAULT_REGIONAL_SATELLITE_CONFIG_ID;
        // Return success when SatelliteController.updateSystemSelectionChannels was invoked
        setupResponseForUpdateSystemSelectionChannels(SATELLITE_RESULT_SUCCESS);

        // Invoke updateSystemSelectionChannels when there is corresponding satellite access config.
        // Create satellite info 1
        String seed1 = "test-seed-satellite1";
        UUID uuid1 = UUID.nameUUIDFromBytes(seed1.getBytes());
        SatellitePosition satellitePosition1 = new SatellitePosition(0, 35876);
        int[] bands1 = {200, 201, 202};
        EarfcnRange earfcnRange1 = new EarfcnRange(300, 301);
        EarfcnRange earfcnRange2 = new EarfcnRange(310, 311);
        List<EarfcnRange> earfcnRangeList1 = new ArrayList<>(
                Arrays.asList(earfcnRange1, earfcnRange2));
        SatelliteInfo satelliteInfo1 = new SatelliteInfo(uuid1, satellitePosition1, Arrays.stream(
                bands1).boxed().collect(Collectors.toList()), earfcnRangeList1);
        // Create satellite info 2
        String seed2 = "test-seed-satellite2";
        UUID uuid2 = UUID.nameUUIDFromBytes(seed2.getBytes());
        SatellitePosition satellitePosition2 = new SatellitePosition(120, 35876);
        int[] bands2 = {210, 211, 212};
        EarfcnRange earfcnRange3 = new EarfcnRange(320, 321);
        EarfcnRange earfcnRange4 = new EarfcnRange(330, 331);
        List<EarfcnRange> earfcnRangeList2 = new ArrayList<>(
                Arrays.asList(earfcnRange3, earfcnRange4));
        SatelliteInfo satelliteInfo2 = new SatelliteInfo(uuid2, satellitePosition2, Arrays.stream(
                bands2).boxed().collect(Collectors.toList()), earfcnRangeList2);
        // Create satellite info 3
        String seed3 = "test-seed-satellite3";
        UUID uuid3 = UUID.nameUUIDFromBytes(seed3.getBytes());
        SatellitePosition satellitePosition3 = new SatellitePosition(120, 35876);
        int[] bands3 = {220, 221, 222};
        EarfcnRange earfcnRange5 = new EarfcnRange(340, 341);
        EarfcnRange earfcnRange6 = new EarfcnRange(350, 351);
        List<EarfcnRange> earfcnRangeList3 = new ArrayList<>(
                Arrays.asList(earfcnRange5, earfcnRange6));
        SatelliteInfo satelliteInfo3 = new SatelliteInfo(uuid3, satellitePosition3, Arrays.stream(
                bands3).boxed().collect(Collectors.toList()), earfcnRangeList3);

        int[] tagIds = {1, 2, 3};
        SatelliteAccessConfiguration satelliteAccessConfiguration =
                new SatelliteAccessConfiguration(new ArrayList<>(
                        Arrays.asList(satelliteInfo1, satelliteInfo2, satelliteInfo3)),
                        Arrays.stream(tagIds).boxed().collect(Collectors.toList()));

        // Add satellite access configuration to map
        mSatelliteAccessControllerUT.setSatelliteAccessConfigMap(satelliteRegionalConfigId,
                satelliteAccessConfiguration);

        // Invoke updateSystemSelectionChannel
        mSatelliteAccessControllerUT.updateSystemSelectionChannels(
                mSystemSelectionChannelUpdatedReceiver);
        mTestableLooper.processAllMessages();
        assertTrue(waitForRequestUpdateSystemSelectionChannelResult(
                mSystemSelectionChannelUpdatedSemaphore, 1));
        assertEquals(SATELLITE_RESULT_SUCCESS,
                mQueriedSystemSelectionChannelUpdatedResultCode);
        ArgumentCaptor<List<SystemSelectionSpecifier>> systemSelectionSpecifierListCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(mMockSatelliteController, times(1)).updateSystemSelectionChannels(
                systemSelectionSpecifierListCaptor.capture(), any(ResultReceiver.class));
        List<SystemSelectionSpecifier> capturedList = systemSelectionSpecifierListCaptor.getValue();
        SystemSelectionSpecifier systemSelectionSpecifier = capturedList.getFirst();

        // Verify the fields value of given systemSelectionSpecifier matched with expected.
        int[] expectedBandsArray = IntStream.concat(
                IntStream.concat(Arrays.stream(bands1), Arrays.stream(bands2)),
                Arrays.stream(bands3)).toArray();
        int[] actualBandsArray = systemSelectionSpecifier.getBands();
        assertArrayEquals(expectedBandsArray, actualBandsArray);

        int[] expectedEarfcnsArray = {300, 301, 310, 311, 320, 321, 330, 331, 340, 341, 350, 351};
        int[] actualEarfcnsArray = systemSelectionSpecifier.getEarfcns();
        assertArrayEquals(expectedEarfcnsArray, actualEarfcnsArray);

        SatelliteInfo[] expectedSatelliteInfos = {satelliteInfo1, satelliteInfo2, satelliteInfo3};
        assertArrayEquals(expectedSatelliteInfos,
                systemSelectionSpecifier.getSatelliteInfos().toArray(new SatelliteInfo[0]));

        int[] actualTagIdArray = systemSelectionSpecifier.getTagIds();
        assertArrayEquals(tagIds, actualTagIdArray);

        // Verify backward compatibility when there is valid data for default regional config ID
        satelliteRegionalConfigId = UNKNOWN_REGIONAL_SATELLITE_CONFIG_ID;
        mSatelliteAccessControllerUT.setRegionalConfigId(satelliteRegionalConfigId);
        mSatelliteAccessControllerUT.updateSystemSelectionChannels(
                mSystemSelectionChannelUpdatedReceiver);
        mTestableLooper.processAllMessages();

        // updateSelectionChannelResult will be invoked with the data for default regional config ID
        assertTrue(waitForRequestUpdateSystemSelectionChannelResult(
                mSystemSelectionChannelUpdatedSemaphore, 1));
        systemSelectionSpecifierListCaptor = ArgumentCaptor.forClass(List.class);
        verify(mMockSatelliteController, times(2)).updateSystemSelectionChannels(
                systemSelectionSpecifierListCaptor.capture(), any(ResultReceiver.class));
        capturedList = systemSelectionSpecifierListCaptor.getValue();
        systemSelectionSpecifier = capturedList.getFirst();

        // Data will be same with default regional config ID

        // Verify the fields value of given systemSelectionSpecifier matched with expected.
        actualBandsArray = systemSelectionSpecifier.getBands();
        assertArrayEquals(expectedBandsArray, actualBandsArray);

        actualEarfcnsArray = systemSelectionSpecifier.getEarfcns();
        assertArrayEquals(expectedEarfcnsArray, actualEarfcnsArray);

        assertArrayEquals(expectedSatelliteInfos,
                systemSelectionSpecifier.getSatelliteInfos().toArray(new SatelliteInfo[0]));

        actualTagIdArray = systemSelectionSpecifier.getTagIds();
        assertArrayEquals(tagIds, actualTagIdArray);

        mSatelliteAccessControllerUT.resetSatelliteAccessConfigMap();
    }

    @Test
    public void testUpdateSystemSelectionChannels_HandleInvalidInput() {
        // Set non-emergency case
        when(mMockFeatureFlags.carrierRoamingNbIotNtn()).thenReturn(true);

        setUpResponseForRequestIsSatelliteSupported(true, SATELLITE_RESULT_SUCCESS);
        setUpResponseForRequestIsSatelliteProvisioned(true, SATELLITE_RESULT_SUCCESS);
        when(mMockCountryDetector.getCurrentNetworkCountryIso()).thenReturn(EMPTY_STRING_LIST);
        int satelliteRegionalConfigId = DEFAULT_REGIONAL_SATELLITE_CONFIG_ID;
        mSatelliteAccessControllerUT.setRegionalConfigId(satelliteRegionalConfigId);
        // Set return success when SatelliteController.updateSystemSelectionChannels was invoked
        setupResponseForUpdateSystemSelectionChannels(SATELLITE_RESULT_SUCCESS);

        // Create satellite info in which satellite position is null.
        String seed1 = "test-seed-satellite1";
        UUID uuid1 = UUID.nameUUIDFromBytes(seed1.getBytes());
        SatellitePosition satellitePosition1 = null;
        List<Integer> bandList1 = new ArrayList<>(List.of(200, 201, 202));
        EarfcnRange earfcnRange1 = new EarfcnRange(300, 301);
        EarfcnRange earfcnRange2 = new EarfcnRange(310, 311);
        List<EarfcnRange> earfcnRangeList1 = new ArrayList<>(
                Arrays.asList(earfcnRange1, earfcnRange2));
        SatelliteInfo satelliteInfo1 = new SatelliteInfo(uuid1, satellitePosition1, bandList1,
                earfcnRangeList1);

        // Create satellite info in which band list is empty
        String seed2 = "test-seed-satellite2";
        UUID uuid2 = UUID.nameUUIDFromBytes(seed2.getBytes());
        SatellitePosition satellitePosition2 = new SatellitePosition(120, 35876);
        List<Integer> bandList2 = new ArrayList<>();
        EarfcnRange earfcnRange3 = new EarfcnRange(320, 321);
        EarfcnRange earfcnRange4 = new EarfcnRange(330, 331);
        List<EarfcnRange> earfcnRangeList2 = new ArrayList<>(
                Arrays.asList(earfcnRange3, earfcnRange4));
        SatelliteInfo satelliteInfo2 = new SatelliteInfo(uuid2, satellitePosition2, bandList2,
                earfcnRangeList2);

        // Create satellite info 3, every field is valid
        String seed3 = "test-seed-satellite3";
        UUID uuid3 = UUID.nameUUIDFromBytes(seed3.getBytes());
        SatellitePosition satellitePosition3 = new SatellitePosition(120, 35876);
        List<Integer> bandList3 = new ArrayList<>(List.of(220, 221, 222));
        EarfcnRange earfcnRange5 = new EarfcnRange(340, 341);
        EarfcnRange earfcnRange6 = new EarfcnRange(350, 351);
        List<EarfcnRange> earfcnRangeList3 = new ArrayList<>(
                Arrays.asList(earfcnRange5, earfcnRange6));
        SatelliteInfo satelliteInfo3 = new SatelliteInfo(uuid3, satellitePosition3, bandList3,
                earfcnRangeList3);
        // Add empty tagId list
        List<Integer> tagIdList = new ArrayList<>();

        // Create satelliteAccessConfiguration with some of files of added Satellite info are empty.
        SatelliteAccessConfiguration satelliteAccessConfiguration1 =
                new SatelliteAccessConfiguration(new ArrayList<>(
                        Arrays.asList(satelliteInfo1, satelliteInfo2, satelliteInfo3)), tagIdList);

        // Add satellite access configuration to map
        mSatelliteAccessControllerUT.setSatelliteAccessConfigMap(satelliteRegionalConfigId,
                satelliteAccessConfiguration1);

        // Invoke updateSystemSelectionChannel
        mSatelliteAccessControllerUT.updateSystemSelectionChannels(
                mSystemSelectionChannelUpdatedReceiver);
        mTestableLooper.processAllMessages();
        assertTrue(waitForRequestUpdateSystemSelectionChannelResult(
                mSystemSelectionChannelUpdatedSemaphore, 1));
        assertEquals(SATELLITE_RESULT_SUCCESS,
                mQueriedSystemSelectionChannelUpdatedResultCode);
        ArgumentCaptor<List<SystemSelectionSpecifier>> systemSelectionSpecifierListCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(mMockSatelliteController, times(1)).updateSystemSelectionChannels(
                systemSelectionSpecifierListCaptor.capture(), any(ResultReceiver.class));
        List<SystemSelectionSpecifier> capturedList = systemSelectionSpecifierListCaptor.getValue();
        SystemSelectionSpecifier systemSelectionSpecifier = capturedList.getFirst();

        // Verify the fields value of given systemSelectionSpecifier matched with expected.
        List<Integer> expectedBandList = new ArrayList<>(bandList1);
        expectedBandList.addAll(bandList2);
        expectedBandList.addAll(bandList3);

        List<Integer> actualBandList = Arrays.stream(systemSelectionSpecifier.getBands()).boxed()
                .collect(Collectors.toList());
        assertEquals(expectedBandList, actualBandList);

        List<Integer> expectedEarfcnList = new ArrayList<>(
                List.of(300, 301, 310, 311, 320, 321, 330, 331, 340, 341, 350, 351));
        List<Integer> actualEarfcnList = Arrays.stream(systemSelectionSpecifier.getEarfcns())
                .boxed().collect(Collectors.toList());
        assertEquals(expectedEarfcnList, actualEarfcnList);

        assertEquals(satelliteInfo1, systemSelectionSpecifier.getSatelliteInfos().get(0));
        assertEquals(satelliteInfo2, systemSelectionSpecifier.getSatelliteInfos().get(1));
        assertEquals(satelliteInfo3, systemSelectionSpecifier.getSatelliteInfos().get(2));

        List<Integer> actualTagIdList = Arrays.stream(systemSelectionSpecifier.getTagIds()).boxed()
                .collect(Collectors.toList());
        assertEquals(tagIdList, actualTagIdList);

        // Create satelliteAccessConfiguration with empty list of SatelliteInfo.
        SatelliteAccessConfiguration satelliteAccessConfiguration2 =
                new SatelliteAccessConfiguration(new ArrayList<>(), tagIdList);
        mSatelliteAccessControllerUT.setSatelliteAccessConfigMap(
                DEFAULT_REGIONAL_SATELLITE_CONFIG_ID, satelliteAccessConfiguration2);

        // Invoke updateSystemSelectionChannel
        mSatelliteAccessControllerUT.updateSystemSelectionChannels(
                mSystemSelectionChannelUpdatedReceiver);
        mTestableLooper.processAllMessages();
        assertTrue(waitForRequestUpdateSystemSelectionChannelResult(
                mSystemSelectionChannelUpdatedSemaphore, 1));
        assertEquals(SATELLITE_RESULT_SUCCESS,
                mQueriedSystemSelectionChannelUpdatedResultCode);
        systemSelectionSpecifierListCaptor = ArgumentCaptor.forClass(List.class);
        verify(mMockSatelliteController, times(2)).updateSystemSelectionChannels(
                systemSelectionSpecifierListCaptor.capture(), any(ResultReceiver.class));
        capturedList = systemSelectionSpecifierListCaptor.getValue();
        systemSelectionSpecifier = capturedList.getFirst();

        // Verify the fields value of given systemSelectionSpecifier matched with expected.
        assertEquals(0, systemSelectionSpecifier.getBands().length);
        assertEquals(0, systemSelectionSpecifier.getEarfcns().length);

        SatelliteInfo[] expectedSatelliteInfoArray = new SatelliteInfo[0];
        assertArrayEquals(expectedSatelliteInfoArray,
                systemSelectionSpecifier.getSatelliteInfos().toArray(new SatelliteInfo[0]));

        actualTagIdList = Arrays.stream(systemSelectionSpecifier.getTagIds()).boxed().collect(
                Collectors.toList());
        assertEquals(tagIdList, actualTagIdList);

        mSatelliteAccessControllerUT.resetSatelliteAccessConfigMap();
    }

    @Test
    public void testCheckSharedPreferenceException() {
        doReturn(mMockSharedPreferencesEditor).when(mMockSharedPreferencesEditor)
                .remove(anyString());
        doThrow(new ClassCastException()).when(mMockSharedPreferences)
                .getBoolean(anyString(), eq(false));

        mSatelliteAccessControllerUT = new TestSatelliteAccessController(mMockContext,
                mMockFeatureFlags, mTestableLooper.getLooper(), mMockLocationManager,
                mMockTelecomManager, mMockSatelliteOnDeviceAccessController, mMockSatS2File);

        verify(mMockSharedPreferencesEditor, times(4)).remove(anyString());
    }

    private void sendSatelliteCommunicationAllowedEvent() {
        Pair<Integer, ResultReceiver> requestPair =
                new Pair<>(DEFAULT_SUBSCRIPTION_ID,
                        mSatelliteAccessControllerUT.getResultReceiverCurrentLocation());
        Message msg = mSatelliteAccessControllerUT.obtainMessage(
                CMD_IS_SATELLITE_COMMUNICATION_ALLOWED);
        msg.obj = requestPair;
        msg.sendToTarget();
        mTestableLooper.processAllMessages();
    }

    private void sendSatelliteDeviceAccessControllerResourcesTimeOutEvent() {
        logd("sendSatelliteDeviceAccessControllerResourcesTimeOutEvent");
        Message msg = mSatelliteAccessControllerUT
                .obtainMessage(EVENT_KEEP_ON_DEVICE_ACCESS_CONTROLLER_RESOURCES_TIMEOUT);
        msg.sendToTarget();
        mTestableLooper.processAllMessages();
    }

    private void sendConfigUpdateChangedEvent(Context context) {
        Message msg = mSatelliteAccessControllerUT.obtainMessage(EVENT_CONFIG_DATA_UPDATED);
        msg.obj = new AsyncResult(context, SATELLITE_RESULT_SUCCESS, null);
        msg.sendToTarget();
        mTestableLooper.processAllMessages();
    }

    private void sendCurrentLocationTimeoutEvent() {
        Message msg = mSatelliteAccessControllerUT
                .obtainMessage(EVENT_WAIT_FOR_CURRENT_LOCATION_TIMEOUT);
        msg.sendToTarget();
        mTestableLooper.processAllMessages();
    }

    private void sendCommandValidateCountryCodeChangeEvent(Context context) {
        Message msg = mSatelliteAccessControllerUT.obtainMessage(EVENT_COUNTRY_CODE_CHANGED);
        msg.obj = new AsyncResult(context, SATELLITE_RESULT_SUCCESS, null);
        msg.sendToTarget();
        mTestableLooper.processAllMessages();
    }

    private void clearAllInvocations() {
        clearInvocations(mMockSatelliteController);
        clearInvocations(mMockSatelliteOnDeviceAccessController);
        clearInvocations(mMockLocationManager);
        clearInvocations(mMockCountryDetector);
    }

    private void verifyCountryDetectorApisCalled() {
        verify(mMockCountryDetector).getCurrentNetworkCountryIso();
        verify(mMockCountryDetector).getCachedLocationCountryIsoInfo();
        verify(mMockCountryDetector).getCachedLocationCountryIsoInfo();
    }

    private boolean waitForRequestIsSatelliteAllowedForCurrentLocationResult(Semaphore semaphore,
            int expectedNumberOfEvents) {
        for (int i = 0; i < expectedNumberOfEvents; i++) {
            try {
                if (!semaphore.tryAcquire(TIMEOUT, TimeUnit.MILLISECONDS)) {
                    logd("Timeout to receive "
                            + "requestIsCommunicationAllowedForCurrentLocation()"
                            + " callback");
                    return false;
                }
            } catch (Exception ex) {
                logd("waitForRequestIsSatelliteSupportedResult: Got exception=" + ex);
                return false;
            }
        }
        return true;
    }

    private boolean waitForRequestUpdateSystemSelectionChannelResult(Semaphore semaphore,
            int expectedNumberOfEvents) {
        for (int i = 0; i < expectedNumberOfEvents; i++) {
            try {
                if (!semaphore.tryAcquire(TIMEOUT, TimeUnit.MILLISECONDS)) {
                    logd("Timeout to receive "
                            + "updateSystemSelectionChannel()"
                            + " callback");
                    return false;
                }
            } catch (Exception ex) {
                logd("updateSystemSelectionChannel: Got exception=" + ex);
                return false;
            }
        }
        return true;
    }

    private void sendLocationRequestResult(Location location) {
        mLocationRequestConsumerCaptor.getValue().accept(location);
        mTestableLooper.processAllMessages();
    }

    private void setUpResponseForRequestIsSatelliteSupported(
            boolean isSatelliteSupported, @SatelliteManager.SatelliteResult int error) {
        doAnswer(invocation -> {
            ResultReceiver resultReceiver = invocation.getArgument(0);
            if (error == SATELLITE_RESULT_SUCCESS) {
                Bundle bundle = new Bundle();
                bundle.putBoolean(SatelliteManager.KEY_SATELLITE_SUPPORTED, isSatelliteSupported);
                resultReceiver.send(error, bundle);
            } else {
                resultReceiver.send(error, Bundle.EMPTY);
            }
            return null;
        }).when(mMockSatelliteController).requestIsSatelliteSupported(any(ResultReceiver.class));
    }

    private void setUpResponseForRequestIsSatelliteProvisioned(
            boolean isSatelliteProvisioned, @SatelliteManager.SatelliteResult int error) {
        doAnswer(invocation -> {
            ResultReceiver resultReceiver = invocation.getArgument(0);
            if (error == SATELLITE_RESULT_SUCCESS) {
                Bundle bundle = new Bundle();
                bundle.putBoolean(KEY_SATELLITE_PROVISIONED,
                        isSatelliteProvisioned);
                resultReceiver.send(error, bundle);
            } else {
                resultReceiver.send(error, Bundle.EMPTY);
            }
            return null;
        }).when(mMockSatelliteController).requestIsSatelliteProvisioned(any(ResultReceiver.class));
    }

    private void setupResponseForUpdateSystemSelectionChannels(
            @SatelliteManager.SatelliteResult int error) {
        doAnswer(invocation -> {
            ResultReceiver resultReceiver = invocation.getArgument(1);
            resultReceiver.send(error, null);
            return null;
        }).when(mMockSatelliteController).updateSystemSelectionChannels(anyList(),
                any(ResultReceiver.class));
    }

    @SafeVarargs
    private static <E> List<E> listOf(E... values) {
        return Arrays.asList(values);
    }

    private static void logd(String message) {
        Log.d(TAG, message);
    }

    private static class TestSatelliteAccessController extends SatelliteAccessController {
        public long elapsedRealtimeNanos = 0;

        /**
         * Create a SatelliteAccessController instance.
         *
         * @param context                           The context associated with the
         *                                          {@link SatelliteAccessController} instance.
         * @param featureFlags                      The FeatureFlags that are supported.
         * @param looper                            The Looper to run the SatelliteAccessController
         *                                          on.
         * @param locationManager                   The LocationManager for querying current
         *                                          location of the
         *                                          device.
         * @param satelliteOnDeviceAccessController The on-device satellite access controller
         *                                          instance.
         */
        protected TestSatelliteAccessController(Context context, FeatureFlags featureFlags,
                Looper looper, LocationManager locationManager, TelecomManager telecomManager,
                SatelliteOnDeviceAccessController satelliteOnDeviceAccessController,
                File s2CellFile) {
            super(context, featureFlags, looper, locationManager, telecomManager,
                    satelliteOnDeviceAccessController, s2CellFile);
        }

        @Override
        protected long getElapsedRealtimeNanos() {
            return elapsedRealtimeNanos;
        }

        public boolean isKeepOnDeviceAccessControllerResourcesTimerStarted() {
            return hasMessages(EVENT_KEEP_ON_DEVICE_ACCESS_CONTROLLER_RESOURCES_TIMEOUT);
        }

        public boolean isSatelliteOnDeviceAccessControllerReset() {
            synchronized (mLock) {
                return (mSatelliteOnDeviceAccessController == null);
            }
        }

        public void setSatelliteOnDeviceAccessController(
                @Nullable SatelliteOnDeviceAccessController accessController) {
            synchronized (mLock) {
                mSatelliteOnDeviceAccessController = accessController;
            }
        }

        public long getKeepOnDeviceAccessControllerResourcesTimeoutMillis() {
            return KEEP_ON_DEVICE_ACCESS_CONTROLLER_RESOURCES_TIMEOUT_MILLIS;
        }

        public long getWaitForCurrentLocationTimeoutMillis() {
            return WAIT_FOR_CURRENT_LOCATION_TIMEOUT_MILLIS;
        }

        public boolean isWaitForCurrentLocationTimerStarted() {
            return hasMessages(EVENT_WAIT_FOR_CURRENT_LOCATION_TIMEOUT);
        }

        public int getRetryCountPossibleChangeInSatelliteAllowedRegion() {
            return mRetryCountForValidatingPossibleChangeInAllowedRegion;
        }

        public void setRetryCountPossibleChangeInSatelliteAllowedRegion(int retryCount) {
            mRetryCountForValidatingPossibleChangeInAllowedRegion = retryCount;
        }

        public ResultReceiver getResultReceiverCurrentLocation() {
            return mHandlerForSatelliteAllowedResult;
        }

        public BroadcastReceiver getLocationBroadcastReceiver() {
            return mLocationModeChangedBroadcastReceiver;
        }

        public void setLocationRequestCancellationSignalAsNull(boolean isNull) {
            synchronized (mLock) {
                mLocationRequestCancellationSignal = isNull ? null : new CancellationSignal();
            }
        }

        public boolean isCurrentSatelliteAllowedState() {
            synchronized (mSatelliteCommunicationAllowStateLock) {
                return mCurrentSatelliteAllowedState;
            }
        }

        @Nullable
        public Integer getRegionalConfigId() {
            synchronized (mLock) {
                return mRegionalConfigId;
            }
        }

        @Nullable
        public Integer getNewRegionalConfigId() {
            synchronized (mLock) {
                return mNewRegionalConfigId;
            }
        }

        public void setRegionalConfigId(@Nullable Integer regionalConfigId) {
            synchronized (mLock) {
                mRegionalConfigId = regionalConfigId;
            }
        }

        public void setSatelliteAccessConfigMap(int regionalConfigId,
                SatelliteAccessConfiguration satelliteAccessConfiguration) {
            synchronized (mLock) {
                if (mSatelliteAccessConfigMap == null) {
                    mSatelliteAccessConfigMap = new HashMap<>();
                }
                mSatelliteAccessConfigMap.put(regionalConfigId, satelliteAccessConfiguration);
            }
        }

        public void resetSatelliteAccessConfigMap() {
            synchronized (mLock) {
                if (mSatelliteAccessConfigMap == null) {
                    mSatelliteAccessConfigMap = new HashMap<>();
                } else {
                    mSatelliteAccessConfigMap.clear();
                }
            }
        }

        public Map<Integer, SatelliteAccessConfiguration> getSatelliteAccessConfigMap() {
            synchronized (mLock) {
                return mSatelliteAccessConfigMap;
            }
        }

        public int getSatelliteAccessConfigVersion() {
            synchronized (mLock) {
                return mSatelliteAccessConfigVersion;
            }
        }
    }
}
