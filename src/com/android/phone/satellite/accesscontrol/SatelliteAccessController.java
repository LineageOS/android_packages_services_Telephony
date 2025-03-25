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

import static android.telephony.satellite.SatelliteManager.KEY_SATELLITE_ACCESS_CONFIGURATION;
import static android.telephony.satellite.SatelliteManager.KEY_SATELLITE_COMMUNICATION_ALLOWED;
import static android.telephony.satellite.SatelliteManager.KEY_SATELLITE_PROVISIONED;
import static android.telephony.satellite.SatelliteManager.KEY_SATELLITE_SUPPORTED;
import static android.telephony.satellite.SatelliteManager.SATELLITE_DISALLOWED_REASON_LOCATION_DISABLED;
import static android.telephony.satellite.SatelliteManager.SATELLITE_DISALLOWED_REASON_NOT_IN_ALLOWED_REGION;
import static android.telephony.satellite.SatelliteManager.SATELLITE_DISALLOWED_REASON_NOT_PROVISIONED;
import static android.telephony.satellite.SatelliteManager.SATELLITE_DISALLOWED_REASON_NOT_SUPPORTED;
import static android.telephony.satellite.SatelliteManager.SATELLITE_DISALLOWED_REASON_UNSUPPORTED_DEFAULT_MSG_APP;
import static android.telephony.satellite.SatelliteManager.SATELLITE_RESULT_ACCESS_BARRED;
import static android.telephony.satellite.SatelliteManager.SATELLITE_RESULT_INVALID_TELEPHONY_STATE;
import static android.telephony.satellite.SatelliteManager.SATELLITE_RESULT_LOCATION_DISABLED;
import static android.telephony.satellite.SatelliteManager.SATELLITE_RESULT_LOCATION_NOT_AVAILABLE;
import static android.telephony.satellite.SatelliteManager.SATELLITE_RESULT_NOT_SUPPORTED;
import static android.telephony.satellite.SatelliteManager.SATELLITE_RESULT_NO_RESOURCES;
import static android.telephony.satellite.SatelliteManager.SATELLITE_RESULT_REQUEST_NOT_SUPPORTED;
import static android.telephony.satellite.SatelliteManager.SATELLITE_RESULT_SUCCESS;

import static com.android.internal.telephony.satellite.SatelliteConstants.TRIGGERING_EVENT_CONFIG_DATA_UPDATED;
import static com.android.internal.telephony.satellite.SatelliteConstants.TRIGGERING_EVENT_EXTERNAL_REQUEST;
import static com.android.internal.telephony.satellite.SatelliteConstants.TRIGGERING_EVENT_LOCATION_SETTINGS_DISABLED;
import static com.android.internal.telephony.satellite.SatelliteConstants.TRIGGERING_EVENT_LOCATION_SETTINGS_ENABLED;
import static com.android.internal.telephony.satellite.SatelliteConstants.TRIGGERING_EVENT_MCC_CHANGED;
import static com.android.internal.telephony.satellite.SatelliteConstants.TRIGGERING_EVENT_UNKNOWN;
import static com.android.internal.telephony.satellite.SatelliteController.SATELLITE_SHARED_PREF;

import android.annotation.ArrayRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationRequest;
import android.os.AsyncResult;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.telecom.TelecomManager;
import android.telephony.AnomalyReporter;
import android.telephony.CarrierConfigManager;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.PersistentLogger;
import android.telephony.Rlog;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.satellite.EarfcnRange;
import android.telephony.satellite.ISatelliteCommunicationAccessStateCallback;
import android.telephony.satellite.ISatelliteDisallowedReasonsCallback;
import android.telephony.satellite.ISatelliteProvisionStateCallback;
import android.telephony.satellite.SatelliteAccessConfiguration;
import android.telephony.satellite.SatelliteInfo;
import android.telephony.satellite.SatelliteManager;
import android.telephony.satellite.SatelliteSubscriberProvisionStatus;
import android.telephony.satellite.SystemSelectionSpecifier;
import android.text.TextUtils;
import android.util.IntArray;
import android.util.Log;
import android.util.Pair;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.IBooleanConsumer;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.SmsApplication;
import com.android.internal.telephony.TelephonyCountryDetector;
import com.android.internal.telephony.flags.FeatureFlags;
import com.android.internal.telephony.satellite.SatelliteConfig;
import com.android.internal.telephony.satellite.SatelliteConstants;
import com.android.internal.telephony.satellite.SatelliteController;
import com.android.internal.telephony.satellite.SatelliteServiceUtils;
import com.android.internal.telephony.satellite.metrics.AccessControllerMetricsStats;
import com.android.internal.telephony.satellite.metrics.ConfigUpdaterMetricsStats;
import com.android.internal.telephony.satellite.metrics.ControllerMetricsStats;
import com.android.internal.telephony.subscription.SubscriptionManagerService;
import com.android.internal.telephony.util.TelephonyUtils;
import com.android.phone.PhoneGlobals;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This module is responsible for making sure that satellite communication can be used by devices
 * in only regions allowed by OEMs.
 */
public class SatelliteAccessController extends Handler {
    private static final String TAG = "SatelliteAccessController";
    /**
     * UUID to report an anomaly when getting an exception in looking up on-device data for the
     * current location.
     */
    private static final String UUID_ON_DEVICE_LOOKUP_EXCEPTION =
            "dbea1641-630e-4780-9f25-8337ba6c3563";
    /**
     * UUID to report an anomaly when getting an exception in creating the on-device access
     * controller.
     */
    private static final String UUID_CREATE_ON_DEVICE_ACCESS_CONTROLLER_EXCEPTION =
            "3ac767d8-2867-4d60-97c2-ae9d378a5521";
    protected static final long WAIT_FOR_CURRENT_LOCATION_TIMEOUT_MILLIS =
            TimeUnit.SECONDS.toMillis(180);
    protected static final long WAIT_UNTIL_CURRENT_LOCATION_QUERY_IS_DONE_MILLIS =
            TimeUnit.SECONDS.toMillis(90);
    protected static final long KEEP_ON_DEVICE_ACCESS_CONTROLLER_RESOURCES_TIMEOUT_MILLIS =
            TimeUnit.MINUTES.toMillis(30);
    protected static final int DEFAULT_S2_LEVEL = 12;
    private static final int DEFAULT_LOCATION_FRESH_DURATION_SECONDS = 600;
    private static final boolean DEFAULT_SATELLITE_ACCESS_ALLOW = true;
    private static final String ALLOW_MOCK_MODEM_PROPERTY = "persist.radio.allow_mock_modem";
    private static final String BOOT_ALLOW_MOCK_MODEM_PROPERTY = "ro.boot.radio.allow_mock_modem";
    private static final boolean DEBUG = !"user".equals(Build.TYPE);
    private static final int MAX_CACHE_SIZE = 50;

    protected static final int CMD_IS_SATELLITE_COMMUNICATION_ALLOWED = 1;
    protected static final int EVENT_WAIT_FOR_CURRENT_LOCATION_TIMEOUT = 2;
    protected static final int EVENT_KEEP_ON_DEVICE_ACCESS_CONTROLLER_RESOURCES_TIMEOUT = 3;
    protected static final int CMD_UPDATE_CONFIG_DATA = 4;
    protected static final int EVENT_COUNTRY_CODE_CHANGED = 5;
    protected static final int EVENT_LOCATION_SETTINGS_ENABLED = 6;
    protected static final int CMD_UPDATE_SYSTEM_SELECTION_CHANNELS = 7;
    protected static final int EVENT_LOCATION_SETTINGS_DISABLED = 8;
    protected static final int EVENT_SATELLITE_SUBSCRIPTION_CHANGED = 9;
    protected static final int EVENT_CONFIG_DATA_UPDATED = 10;

    public static final int DEFAULT_REGIONAL_SATELLITE_CONFIG_ID = 0;
    public static final int UNKNOWN_REGIONAL_SATELLITE_CONFIG_ID = -1;


    private static final String KEY_AVAILABLE_NOTIFICATION_SHOWN = "available_notification_shown";
    private static final String KEY_UNAVAILABLE_NOTIFICATION_SHOWN =
            "unavailable_notification_shown";
    private static final String AVAILABLE_NOTIFICATION_TAG = "available_notification_tag";
    private static final String UNAVAILABLE_NOTIFICATION_TAG = "unavailable_notification_tag";
    private static final int NOTIFICATION_ID = 1;
    private static final String NOTIFICATION_CHANNEL = "satelliteChannel";
    private static final String NOTIFICATION_CHANNEL_ID = "satellite";
    private static final int SATELLITE_DISALLOWED_REASON_NONE = -1;
    private static final List<Integer> DISALLOWED_REASONS_TO_BE_RESET =
            Arrays.asList(SATELLITE_DISALLOWED_REASON_NOT_IN_ALLOWED_REGION,
                    SATELLITE_DISALLOWED_REASON_LOCATION_DISABLED);

    private static final HashMap<Integer, Pair<Integer, Integer>>
            SATELLITE_SOS_UNAVAILABLE_REASONS = new HashMap<>(Map.of(
            SATELLITE_DISALLOWED_REASON_NOT_SUPPORTED, new Pair<>(
                    R.string.satellite_sos_not_supported_notification_title,
                    R.string.satellite_sos_not_supported_notification_summary),
            SATELLITE_DISALLOWED_REASON_NOT_PROVISIONED, new Pair<>(
                    R.string.satellite_sos_not_provisioned_notification_title,
                    R.string.satellite_sos_not_provisioned_notification_summary),
            SATELLITE_DISALLOWED_REASON_NOT_IN_ALLOWED_REGION, new Pair<>(
                    R.string.satellite_sos_not_in_allowed_region_notification_title,
                    R.string.satellite_sos_not_in_allowed_region_notification_summary),
            SATELLITE_DISALLOWED_REASON_UNSUPPORTED_DEFAULT_MSG_APP, new Pair<>(
                    R.string.satellite_sos_unsupported_default_sms_app_notification_title,
                    R.string.satellite_sos_unsupported_default_sms_app_notification_summary),
            SATELLITE_DISALLOWED_REASON_LOCATION_DISABLED, new Pair<>(
                    R.string.satellite_sos_location_disabled_notification_title,
                    R.string.satellite_sos_location_disabled_notification_summary)
    ));

    private static final HashMap<Integer, Pair<Integer, Integer>>
            SATELLITE_MESSAGING_UNAVAILABLE_REASONS = new HashMap<>(Map.of(
            SATELLITE_DISALLOWED_REASON_NOT_SUPPORTED, new Pair<>(
                    R.string.satellite_messaging_not_supported_notification_title,
                    R.string.satellite_messaging_not_supported_notification_summary),
            SATELLITE_DISALLOWED_REASON_NOT_PROVISIONED, new Pair<>(
                    R.string.satellite_messaging_not_provisioned_notification_title,
                    R.string.satellite_messaging_not_provisioned_notification_summary),
            SATELLITE_DISALLOWED_REASON_NOT_IN_ALLOWED_REGION, new Pair<>(
                    R.string.satellite_messaging_not_in_allowed_region_notification_title,
                    R.string.satellite_messaging_not_in_allowed_region_notification_summary),
            SATELLITE_DISALLOWED_REASON_UNSUPPORTED_DEFAULT_MSG_APP, new Pair<>(
                    R.string.satellite_messaging_unsupported_default_sms_app_notification_title,
                    R.string.satellite_messaging_unsupported_default_sms_app_notification_summary),
            SATELLITE_DISALLOWED_REASON_LOCATION_DISABLED, new Pair<>(
                    R.string.satellite_messaging_location_disabled_notification_title,
                    R.string.satellite_messaging_location_disabled_notification_summary)
    ));

    private static SatelliteAccessController sInstance;

    /** Feature flags to control behavior and errors. */
    @NonNull
    private final FeatureFlags mFeatureFlags;
    @NonNull
    private final Context mContext;
    @GuardedBy("mLock")
    @Nullable
    protected SatelliteOnDeviceAccessController mSatelliteOnDeviceAccessController;
    @NonNull
    private final LocationManager mLocationManager;
    @NonNull
    private final TelecomManager mTelecomManager;
    @NonNull
    private final TelephonyCountryDetector mCountryDetector;
    @NonNull
    private final SatelliteController mSatelliteController;
    @NonNull
    private final ControllerMetricsStats mControllerMetricsStats;
    @NonNull
    private final AccessControllerMetricsStats mAccessControllerMetricsStats;
    @NonNull
    private final ResultReceiver mInternalSatelliteSupportedResultReceiver;
    @NonNull
    private final ResultReceiver mInternalSatelliteProvisionedResultReceiver;
    @NonNull
    private final IBooleanConsumer mInternalSatelliteSupportedStateCallback;
    @NonNull
    private final ISatelliteProvisionStateCallback mInternalSatelliteProvisionStateCallback;
    @NonNull
    private final ResultReceiver mInternalUpdateSystemSelectionChannelsResultReceiver;
    @NonNull
    protected final Object mLock = new Object();
    @GuardedBy("mLock")
    @NonNull
    private final Set<ResultReceiver> mSatelliteAllowResultReceivers = new HashSet<>();
    @NonNull
    private final Set<ResultReceiver>
            mUpdateSystemSelectionChannelsResultReceivers = new HashSet<>();
    @NonNull
    private List<String> mSatelliteCountryCodes;
    private boolean mIsSatelliteAllowAccessControl;
    protected int mSatelliteAccessConfigVersion;
    @Nullable
    private File mSatelliteS2CellFile;
    @Nullable
    private File mSatelliteAccessConfigFile;
    private long mLocationFreshDurationNanos;
    @GuardedBy("mLock")
    private boolean mIsOverlayConfigOverridden = false;
    @NonNull
    private List<String> mOverriddenSatelliteCountryCodes;
    private boolean mOverriddenIsSatelliteAllowAccessControl;
    @Nullable
    private File mOverriddenSatelliteS2CellFile;
    @Nullable
    private File mOverriddenSatelliteAccessConfigFile;
    @Nullable
    private String mOverriddenSatelliteConfigurationFileName;
    private long mOverriddenLocationFreshDurationNanos;

    @GuardedBy("mLock")
    @NonNull
    private final Map<SatelliteOnDeviceAccessController.LocationToken, Integer>
            mCachedAccessRestrictionMap = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(
                Entry<SatelliteOnDeviceAccessController.LocationToken, Integer> eldest) {
            return size() > MAX_CACHE_SIZE;
        }
    };
    @GuardedBy("mLock")
    @Nullable
    protected CancellationSignal mLocationRequestCancellationSignal = null;
    private int mS2Level = DEFAULT_S2_LEVEL;
    @GuardedBy("mLock")
    @Nullable
    private Location mFreshLastKnownLocation = null;
    @GuardedBy("mLock")
    @Nullable
    protected Integer mRegionalConfigId = null;
    @GuardedBy("mLock")
    @Nullable
    protected Integer mNewRegionalConfigId = null;
    @NonNull
    private final CarrierConfigManager mCarrierConfigManager;
    @NonNull
    private final CarrierConfigManager.CarrierConfigChangeListener mCarrierConfigChangeListener;
    /**
     * Key: Sub Id, Value: (key: Regional satellite config Id, value: SatelliteRegionalConfig
     * contains satellite config IDs and set of earfcns in the corresponding regions).
     */
    @GuardedBy("mRegionalSatelliteEarfcnsLock")
    private Map<Integer, Map<Integer, SatelliteRegionalConfig>>
            mSatelliteRegionalConfigPerSubMap = new HashMap();
    @NonNull private final Object mRegionalSatelliteEarfcnsLock = new Object();

    /** Key: Config ID; Value: SatelliteAccessConfiguration */
    @GuardedBy("mLock")
    @Nullable
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected Map<Integer, SatelliteAccessConfiguration> mSatelliteAccessConfigMap;

    /** These are used for CTS test */
    private Path mCtsSatS2FilePath = null;
    private Path mCtsSatelliteAccessConfigurationFilePath = null;
    protected static final String GOOGLE_US_SAN_SAT_S2_FILE_NAME = "google_us_san_sat_s2.dat";
    protected static final String GOOGLE_US_SAN_SAT_MTV_S2_FILE_NAME =
            "google_us_san_mtv_sat_s2.dat";
    protected static final String SATELLITE_ACCESS_CONFIG_FILE_NAME =
            "satellite_access_config.json";

    /** These are for config updater config data */
    private static final String SATELLITE_ACCESS_CONTROL_DATA_DIR = "satellite_access_control";
    private static final String CONFIG_UPDATER_S2_CELL_FILE_NAME = "config_updater_sat_s2.dat";
    private static final String CONFIG_UPDATER_SATELLITE_ACCESS_CONFIG_FILE_NAME =
            "config_updater_satellite_access_config.json";
    private static final int MIN_S2_LEVEL = 0;
    private static final int MAX_S2_LEVEL = 30;
    private static final String CONFIG_UPDATER_SATELLITE_COUNTRY_CODES_KEY =
            "config_updater_satellite_country_codes";
    private static final String CONFIG_UPDATER_SATELLITE_IS_ALLOW_ACCESS_CONTROL_KEY =
            "config_updater_satellite_is_allow_access_control";
    protected static final String CONFIG_UPDATER_SATELLITE_VERSION_KEY =
            "config_updater_satellite_version";

    private static final String LATEST_SATELLITE_COMMUNICATION_ALLOWED_SET_TIME_KEY =
            "latest_satellite_communication_allowed_set_time";
    private static final String LATEST_SATELLITE_COMMUNICATION_ALLOWED_KEY =
            "latest_satellite_communication_allowed";

    private SharedPreferences mSharedPreferences;
    private final ConfigUpdaterMetricsStats mConfigUpdaterMetricsStats;
    @Nullable
    private PersistentLogger mPersistentLogger = null;

    private final Object mPossibleChangeInSatelliteAllowedRegionLock = new Object();
    @GuardedBy("mPossibleChangeInSatelliteAllowedRegionLock")
    private boolean mIsSatelliteAllowedRegionPossiblyChanged = false;
    protected long mLastLocationQueryForPossibleChangeInAllowedRegionTimeNanos = 0;

    protected int mRetryCountForValidatingPossibleChangeInAllowedRegion;
    protected static final int
            DEFAULT_DELAY_MINUTES_BEFORE_VALIDATING_POSSIBLE_CHANGE_IN_ALLOWED_REGION = 10;
    protected static final int
            DEFAULT_MAX_RETRY_COUNT_FOR_VALIDATING_POSSIBLE_CHANGE_IN_ALLOWED_REGION = 3;
    protected static final int DEFAULT_THROTTLE_INTERVAL_FOR_LOCATION_QUERY_MINUTES = 10;
    private static final int MAX_EARFCN_ARRAY_LENGTH = 32;

    private long mRetryIntervalToEvaluateUserInSatelliteAllowedRegion = 0;
    private int mMaxRetryCountForValidatingPossibleChangeInAllowedRegion = 0;
    private long mLocationQueryThrottleIntervalNanos = 0;

    @NonNull
    protected ResultReceiver mHandlerForSatelliteAllowedResult;

    /**
     * Map key: binder of the callback, value: callback to receive the satellite communication
     * allowed state changed events.
     */
    private final ConcurrentHashMap<IBinder, ISatelliteCommunicationAccessStateCallback>
            mSatelliteCommunicationAccessStateChangedListeners = new ConcurrentHashMap<>();
    protected final Object mSatelliteCommunicationAllowStateLock = new Object();
    @GuardedBy("mSatelliteCommunicationAllowStateLock")
    protected boolean mCurrentSatelliteAllowedState = false;

    private final ConcurrentHashMap<IBinder, ISatelliteDisallowedReasonsCallback>
            mSatelliteDisallowedReasonsChangedListeners = new ConcurrentHashMap<>();
    private final Object mSatelliteDisallowedReasonsLock = new Object();

    protected static final long ALLOWED_STATE_CACHE_VALID_DURATION_NANOS =
            TimeUnit.HOURS.toNanos(4);

    private boolean mLatestSatelliteCommunicationAllowed;
    protected long mLatestSatelliteCommunicationAllowedSetTime;

    private long mLocationQueryStartTimeMillis;
    private long mOnDeviceLookupStartTimeMillis;
    private long mTotalCheckingStartTimeMillis;

    private Notification mSatelliteAvailableNotification;
    // Key: SatelliteManager#SatelliteDisallowedReason; Value: Notification
    private final Map<Integer, Notification> mSatelliteUnAvailableNotifications = new HashMap<>();
    private NotificationManager mNotificationManager;
    @GuardedBy("mSatelliteDisallowedReasonsLock")
    private final List<Integer> mSatelliteDisallowedReasons = new ArrayList<>();

    private boolean mIsLocationManagerEnabled = false;

    protected BroadcastReceiver mLocationModeChangedBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Check whether user has turned on/off location manager from settings menu
            if (intent.getAction().equals(LocationManager.MODE_CHANGED_ACTION)) {
                plogd("LocationManager mode is changed");
                if (mLocationManager.isLocationEnabled()) {
                    plogd("Location settings is just enabled");
                    sendRequestAsync(EVENT_LOCATION_SETTINGS_ENABLED, null);
                } else {
                    plogd("Location settings is just disabled");
                    sendRequestAsync(EVENT_LOCATION_SETTINGS_DISABLED, null);
                }
            }

            // Check whether location manager has been enabled when boot up
            if (intent.getAction().equals(LocationManager.PROVIDERS_CHANGED_ACTION)) {
                plogd("mLocationModeChangedBroadcastReceiver: " + intent.getAction()
                        + ", mIsLocationManagerEnabled= " + mIsLocationManagerEnabled);
                if (!mIsLocationManagerEnabled) {
                    if (mLocationManager.isLocationEnabled()) {
                        plogd("Location manager is enabled");
                        mIsLocationManagerEnabled = true;
                        boolean isResultReceiverEmpty;
                        synchronized (mLock) {
                            isResultReceiverEmpty = mSatelliteAllowResultReceivers.isEmpty();
                        }
                        if (isResultReceiverEmpty) {
                            sendRequestAsync(EVENT_LOCATION_SETTINGS_ENABLED, null);
                        } else {
                            plogd("delayed EVENT_LOCATION_SETTINGS_ENABLED due to "
                                    + "requestIsCommunicationAllowedForCurrentLocation is "
                                    + "already being processed");
                            sendDelayedRequestAsync(EVENT_LOCATION_SETTINGS_ENABLED, null,
                                    WAIT_UNTIL_CURRENT_LOCATION_QUERY_IS_DONE_MILLIS);
                        }
                    } else {
                        plogd("Location manager is still disabled, wait until next enabled event");
                    }
                }
            }
        }
    };

    private final Object mIsAllowedCheckBeforeEnablingSatelliteLock = new Object();
    @GuardedBy("mIsAllowedCheckBeforeEnablingSatelliteLock")
    private boolean mIsAllowedCheckBeforeEnablingSatellite;
    private boolean mIsCurrentLocationEligibleForNotification = false;
    private boolean mIsProvisionEligibleForNotification = false;

    /**
     * Create a SatelliteAccessController instance.
     *
     * @param context                           The context associated with the
     *                                          {@link SatelliteAccessController} instance.
     * @param featureFlags                      The FeatureFlags that are supported.
     * @param locationManager                   The LocationManager for querying current
     *                                          location of
     *                                          the device.
     * @param looper                            The Looper to run the SatelliteAccessController
     *                                          on.
     * @param satelliteOnDeviceAccessController The on-device satellite access controller
     *                                          instance.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected SatelliteAccessController(@NonNull Context context,
            @NonNull FeatureFlags featureFlags, @NonNull Looper looper,
            @NonNull LocationManager locationManager, @NonNull TelecomManager telecomManager,
            @Nullable SatelliteOnDeviceAccessController satelliteOnDeviceAccessController,
            @Nullable File s2CellFile) {
        super(looper);
        mContext = context;
        mPersistentLogger = SatelliteServiceUtils.getPersistentLogger(context);
        mFeatureFlags = featureFlags;
        mLocationManager = locationManager;
        mTelecomManager = telecomManager;
        mSatelliteOnDeviceAccessController = satelliteOnDeviceAccessController;

        mCountryDetector = TelephonyCountryDetector.getInstance(context, mFeatureFlags);
        mCountryDetector.registerForCountryCodeChanged(this,
                EVENT_COUNTRY_CODE_CHANGED, null);
        initializeHandlerForSatelliteAllowedResult();
        setIsSatelliteAllowedRegionPossiblyChanged(false);

        mSatelliteController = SatelliteController.getInstance();
        mControllerMetricsStats = ControllerMetricsStats.getInstance();
        mAccessControllerMetricsStats = AccessControllerMetricsStats.getInstance();
        initSharedPreferences(context);
        checkSharedPreference();

        loadOverlayConfigs(context);
        // loadConfigUpdaterConfigs has to be called after loadOverlayConfigs
        // since config updater config has higher priority and thus can override overlay config
        loadConfigUpdaterConfigs();
        mSatelliteController.registerForConfigUpdateChanged(this, CMD_UPDATE_CONFIG_DATA,
                context);
        mSatelliteController.registerForSatelliteSubIdChanged(this,
                EVENT_SATELLITE_SUBSCRIPTION_CHANGED, context);
        if (s2CellFile != null) {
            mSatelliteS2CellFile = s2CellFile;
        }
        mInternalSatelliteSupportedResultReceiver = new ResultReceiver(this) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                handleIsSatelliteSupportedResult(resultCode, resultData);
            }
        };
        mSatelliteController.incrementResultReceiverCount(
                "SAC:mInternalSatelliteSupportedResultReceiver");

        mInternalSatelliteProvisionedResultReceiver = new ResultReceiver(this) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                handleIsSatelliteProvisionedResult(resultCode, resultData);
            }
        };

        mConfigUpdaterMetricsStats = ConfigUpdaterMetricsStats.getOrCreateInstance();
        initializeSatelliteSystemNotification(context);
        registerDefaultSmsAppChangedBroadcastReceiver(context);

        mInternalSatelliteSupportedStateCallback = new IBooleanConsumer.Stub() {
            @Override
            public void accept(boolean isSupported) {
                logd("onSatelliteSupportedStateChanged: isSupported=" + isSupported);
                if (isSupported) {
                    final String caller = "SAC:onSatelliteSupportedStateChanged";
                    requestIsCommunicationAllowedForCurrentLocation(
                            new ResultReceiver(null) {
                                @Override
                                protected void onReceiveResult(int resultCode, Bundle resultData) {
                                    mSatelliteController.decrementResultReceiverCount(caller);
                                    // do nothing
                                }
                            }, false);
                    mSatelliteController.incrementResultReceiverCount(caller);
                    if (isReasonPresentInSatelliteDisallowedReasons(
                            SATELLITE_DISALLOWED_REASON_NOT_SUPPORTED)) {
                        removeReasonFromSatelliteDisallowedReasons(
                                SATELLITE_DISALLOWED_REASON_NOT_SUPPORTED);
                        handleEventDisallowedReasonsChanged();
                    }
                } else {
                    if (!isReasonPresentInSatelliteDisallowedReasons(
                            SATELLITE_DISALLOWED_REASON_NOT_SUPPORTED)) {
                        addReasonToSatelliteDisallowedReasons(
                                SATELLITE_DISALLOWED_REASON_NOT_SUPPORTED);
                        handleEventDisallowedReasonsChanged();
                    }
                }
            }
        };
        int result = mSatelliteController.registerForSatelliteSupportedStateChanged(
                mInternalSatelliteSupportedStateCallback);
        plogd("registerForSatelliteSupportedStateChanged result: " + result);

        mInternalSatelliteProvisionStateCallback = new ISatelliteProvisionStateCallback.Stub() {
            @Override
            public void onSatelliteProvisionStateChanged(boolean isProvisioned) {
                logd("onSatelliteProvisionStateChanged: isProvisioned=" + isProvisioned);
                if (isProvisioned) {
                    mIsProvisionEligibleForNotification = true;
                    final String caller = "SAC:onSatelliteProvisionStateChanged";
                    requestIsCommunicationAllowedForCurrentLocation(
                            new ResultReceiver(null) {
                                @Override
                                protected void onReceiveResult(int resultCode, Bundle resultData) {
                                    mSatelliteController.decrementResultReceiverCount(caller);
                                    // do nothing
                                }
                            }, false);
                    mSatelliteController.incrementResultReceiverCount(caller);
                    if (isReasonPresentInSatelliteDisallowedReasons(
                            SATELLITE_DISALLOWED_REASON_NOT_PROVISIONED)) {
                        removeReasonFromSatelliteDisallowedReasons(
                                SATELLITE_DISALLOWED_REASON_NOT_PROVISIONED);
                        handleEventDisallowedReasonsChanged();
                    }
                } else {
                    if (!isReasonPresentInSatelliteDisallowedReasons(
                            SATELLITE_DISALLOWED_REASON_NOT_PROVISIONED)) {
                        addReasonToSatelliteDisallowedReasons(
                                SATELLITE_DISALLOWED_REASON_NOT_PROVISIONED);
                        handleEventDisallowedReasonsChanged();
                    }
                }
            }

            @Override
            public void onSatelliteSubscriptionProvisionStateChanged(
                    List<SatelliteSubscriberProvisionStatus> satelliteSubscriberProvisionStatus) {
                logd("onSatelliteSubscriptionProvisionStateChanged: "
                        + satelliteSubscriberProvisionStatus);
            }
        };
        result = mSatelliteController.registerForSatelliteProvisionStateChanged(
                mInternalSatelliteProvisionStateCallback);
        plogd("registerForSatelliteProvisionStateChanged result: " + result);

        mInternalUpdateSystemSelectionChannelsResultReceiver = new ResultReceiver(this) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                plogd("UpdateSystemSelectionChannels.onReceiveResult: resultCode=" + resultCode
                          + ", resultData=" + resultData);
                sendUpdateSystemSelectionChannelsResult(resultCode, resultData);
            }
        };

        // Init the SatelliteOnDeviceAccessController so that the S2 level can be cached
        initSatelliteOnDeviceAccessController();
        registerLocationModeChangedBroadcastReceiver(context);

        mCarrierConfigManager = context.getSystemService(CarrierConfigManager.class);
        mCarrierConfigChangeListener =
                (slotIndex, subId, carrierId, specificCarrierId) -> handleCarrierConfigChanged(
                    context, slotIndex, subId, carrierId, specificCarrierId);

        if (mCarrierConfigManager != null) {
            mCarrierConfigManager.registerCarrierConfigChangeListener(
                    new HandlerExecutor(new Handler(looper)), mCarrierConfigChangeListener);
        }
    }

    private void updateCurrentSatelliteAllowedState(boolean isAllowed) {
        plogd("updateCurrentSatelliteAllowedState");
        synchronized (mSatelliteCommunicationAllowStateLock) {
            if (isAllowed != mCurrentSatelliteAllowedState) {
                plogd("updatedValue = " + isAllowed + " | mCurrentSatelliteAllowedState = "
                        + mCurrentSatelliteAllowedState);
                mCurrentSatelliteAllowedState = isAllowed;
                notifySatelliteCommunicationAllowedStateChanged(isAllowed);
                mControllerMetricsStats.reportAllowedStateChanged();
                if (!isAllowed) {
                    synchronized (mLock) {
                        plogd("updateCurrentSatelliteAllowedState : set mNewRegionalConfigId null");
                        mNewRegionalConfigId = null;
                    }
                }
            }
            updateRegionalConfigId();
        }
    }

    /** @return the singleton instance of {@link SatelliteAccessController} */
    public static synchronized SatelliteAccessController getOrCreateInstance(
            @NonNull Context context, @NonNull FeatureFlags featureFlags) {
        if (sInstance == null) {
            HandlerThread handlerThread = new HandlerThread("SatelliteAccessController");
            handlerThread.start();
            LocationManager lm = context.createAttributionContext("telephony")
                    .getSystemService(LocationManager.class);
            sInstance = new SatelliteAccessController(context, featureFlags,
                    handlerThread.getLooper(), lm,
                    context.getSystemService(TelecomManager.class), null, null);
        }
        return sInstance;
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case CMD_IS_SATELLITE_COMMUNICATION_ALLOWED:
                handleCmdIsSatelliteAllowedForCurrentLocation(
                        (Pair<Integer, ResultReceiver>) msg.obj);
                break;
            case EVENT_WAIT_FOR_CURRENT_LOCATION_TIMEOUT:
                handleWaitForCurrentLocationTimedOutEvent();
                break;
            case EVENT_KEEP_ON_DEVICE_ACCESS_CONTROLLER_RESOURCES_TIMEOUT:
                cleanupOnDeviceAccessControllerResources();
                break;
            case CMD_UPDATE_CONFIG_DATA:
                AsyncResult ar = (AsyncResult) msg.obj;
                updateSatelliteAccessDataWithConfigUpdaterData((Context) ar.userObj);
                break;

            case EVENT_CONFIG_DATA_UPDATED:
                plogd("EVENT_CONFIG_DATA_UPDATED");      // Fall through
            case EVENT_LOCATION_SETTINGS_ENABLED:
                plogd("EVENT_LOCATION_SETTINGS_ENABLED");        // Fall through
            case EVENT_LOCATION_SETTINGS_DISABLED:
                plogd("EVENT_LOCATION_SETTINGS_DISABLED");       // Fall through
            case EVENT_COUNTRY_CODE_CHANGED:
                plogd("EVENT_COUNTRY_CODE_CHANGED");
                handleSatelliteAllowedRegionPossiblyChanged(msg.what);
                break;
            case CMD_UPDATE_SYSTEM_SELECTION_CHANNELS:
                handleCmdUpdateSystemSelectionChannels((ResultReceiver) msg.obj);
                break;
            case EVENT_SATELLITE_SUBSCRIPTION_CHANGED:
                plogd("Event: EVENT_SATELLITE_SUBSCRIPTION_CHANGED");
                initializeSatelliteSystemNotification(mContext);
                handleEventDisallowedReasonsChanged();
                break;
            default:
                plogw("SatelliteAccessControllerHandler: unexpected message code: " + msg.what);
                break;
        }
    }

    /**
     * Request to get whether satellite communication is allowed for the current location.
     *
     * @param result The result receiver that returns whether satellite communication is allowed
     *               for the current location if the request is successful or an error code
     *               if the request failed.
     */
    public void requestIsCommunicationAllowedForCurrentLocation(
            @NonNull ResultReceiver result, boolean enablingSatellite) {
        plogd("requestIsCommunicationAllowedForCurrentLocation : "
                + "enablingSatellite is " + enablingSatellite);
        synchronized (mIsAllowedCheckBeforeEnablingSatelliteLock) {
            mIsAllowedCheckBeforeEnablingSatellite = enablingSatellite;
        }
        mAccessControllerMetricsStats.setTriggeringEvent(TRIGGERING_EVENT_EXTERNAL_REQUEST);
        sendRequestAsync(CMD_IS_SATELLITE_COMMUNICATION_ALLOWED,
                new Pair<>(mSatelliteController.getSelectedSatelliteSubId(), result));
        mSatelliteController.incrementResultReceiverCount(
                "SAC:requestIsCommunicationAllowedForCurrentLocation");
    }

    /**
     * Request to get satellite access configuration for the current location.
     *
     * @param result The result receiver that returns satellite access configuration
     *               for the current location if the request is successful or an error code
     *               if the request failed.
     */
    public void requestSatelliteAccessConfigurationForCurrentLocation(
            @NonNull ResultReceiver result) {
        if (!mFeatureFlags.carrierRoamingNbIotNtn()) {
            plogd("carrierRoamingNbIotNtnFlag is disabled");
            result.send(SATELLITE_RESULT_REQUEST_NOT_SUPPORTED, null);
            return;
        }
        plogd("requestSatelliteAccessConfigurationForCurrentLocation");
        ResultReceiver internalResultReceiver = new ResultReceiver(this) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                plogd("requestSatelliteAccessConfigurationForCurrentLocation: resultCode="
                        + resultCode + ", resultData=" + resultData);
                boolean isSatelliteCommunicationAllowed = false;
                if (resultCode == SATELLITE_RESULT_SUCCESS) {
                    if (resultData.containsKey(KEY_SATELLITE_COMMUNICATION_ALLOWED)) {
                        isSatelliteCommunicationAllowed =
                                resultData.getBoolean(KEY_SATELLITE_COMMUNICATION_ALLOWED);
                    } else {
                        loge("KEY_SATELLITE_COMMUNICATION_ALLOWED does not exist.");
                        result.send(SATELLITE_RESULT_INVALID_TELEPHONY_STATE, null);
                        return;
                    }
                } else {
                    loge("resultCode is not SATELLITE_RESULT_SUCCESS.");
                    result.send(resultCode, null);
                    return;
                }

                SatelliteAccessConfiguration satelliteAccessConfig = null;
                synchronized (mLock) {
                    if (isSatelliteCommunicationAllowed && SatelliteAccessConfigurationParser
                            .isRegionalConfigIdValid(mRegionalConfigId)) {
                        plogd("requestSatelliteAccessConfigurationForCurrentLocation : "
                                + "mRegionalConfigId is " + mRegionalConfigId);
                        satelliteAccessConfig = Optional.ofNullable(mSatelliteAccessConfigMap)
                                .map(map -> map.get(mRegionalConfigId))
                                .orElse(null);
                    }
                }
                plogd("requestSatelliteAccessConfigurationForCurrentLocation : "
                        + "satelliteAccessConfig is " + satelliteAccessConfig);
                if (satelliteAccessConfig == null) {
                    result.send(SATELLITE_RESULT_NO_RESOURCES, null);
                } else {
                    Bundle bundle = new Bundle();
                    bundle.putParcelable(KEY_SATELLITE_ACCESS_CONFIGURATION, satelliteAccessConfig);
                    result.send(resultCode, bundle);
                }
            }
        };
        requestIsCommunicationAllowedForCurrentLocation(internalResultReceiver, false);
    }

    /**
     * This API should be used by only CTS tests to override the overlay configs of satellite
     * access controller.
     */
    public boolean setSatelliteAccessControlOverlayConfigs(boolean reset, boolean isAllowed,
            @Nullable String s2CellFile, long locationFreshDurationNanos,
            @Nullable List<String> satelliteCountryCodes,
            @Nullable String satelliteConfigurationFile) {
        if (!isMockModemAllowed()) {
            plogd("setSatelliteAccessControllerOverlayConfigs: mock modem is not allowed");
            return false;
        }
        plogd("setSatelliteAccessControlOverlayConfigs: reset=" + reset
                + ", isAllowed" + isAllowed + ", s2CellFile=" + s2CellFile
                + ", locationFreshDurationNanos=" + locationFreshDurationNanos
                + ", satelliteCountryCodes=" + ((satelliteCountryCodes != null)
                ? String.join(", ", satelliteCountryCodes) : null)
                + ", satelliteConfigurationFile=" + satelliteConfigurationFile);
        synchronized (mLock) {
            if (reset) {
                mIsOverlayConfigOverridden = false;
                cleanUpCtsResources();
                cleanUpTelephonyConfigs();
                cleanUpSatelliteAccessConfigOtaResources();
                cleanupSatelliteConfigOtaResources();
            } else {
                mIsOverlayConfigOverridden = true;
                mOverriddenIsSatelliteAllowAccessControl = isAllowed;
                if (!TextUtils.isEmpty(s2CellFile)) {
                    mOverriddenSatelliteS2CellFile = getTestSatelliteS2File(s2CellFile);
                    if (!mOverriddenSatelliteS2CellFile.exists()) {
                        plogd("The overriding file "
                                + mOverriddenSatelliteS2CellFile.getAbsolutePath()
                                + " does not exist");
                        mOverriddenSatelliteS2CellFile = null;
                    }
                    mCachedAccessRestrictionMap.clear();
                } else {
                    mOverriddenSatelliteS2CellFile = null;
                }
                if (!TextUtils.isEmpty(satelliteConfigurationFile)) {
                    mOverriddenSatelliteAccessConfigFile = getTestSatelliteConfiguration(
                            satelliteConfigurationFile);
                    if (!mOverriddenSatelliteAccessConfigFile.exists()) {
                        plogd("The overriding file "
                                + mOverriddenSatelliteAccessConfigFile.getAbsolutePath()
                                + " does not exist");
                        mOverriddenSatelliteAccessConfigFile = null;
                    }
                } else {
                    mOverriddenSatelliteAccessConfigFile = null;
                }
                mOverriddenLocationFreshDurationNanos = locationFreshDurationNanos;
                if (satelliteCountryCodes != null) {
                    mOverriddenSatelliteCountryCodes = satelliteCountryCodes;
                } else {
                    mOverriddenSatelliteCountryCodes = new ArrayList<>();
                }
            }
            cleanupOnDeviceAccessControllerResources();
            initSatelliteOnDeviceAccessController();
        }
        return true;
    }

    /**
     * Report updated system selection to modem and report the update result.
     */
    public void updateSystemSelectionChannels(@NonNull ResultReceiver result) {
        plogd("updateSystemSelectionChannels");
        if (!mFeatureFlags.carrierRoamingNbIotNtn()) {
            plogd("updateSystemSelectionChannels: "
                    + "carrierRoamingNbIotNtn flag is disabled");
            result.send(SATELLITE_RESULT_REQUEST_NOT_SUPPORTED, null);
            return;
        }
        synchronized (mLock) {
            if (mRegionalConfigId == null) {
                plogd("updateSystemSelectionChannels: Invalid Regional config ID."
                        + " System Selection channels can not be passed down to modem");
                result.send(SATELLITE_RESULT_ACCESS_BARRED, null);
                return;
            }
        }
        sendRequestAsync(CMD_UPDATE_SYSTEM_SELECTION_CHANNELS, result);
    }

    protected File getTestSatelliteS2File(String fileName) {
        plogd("getTestSatelliteS2File: fileName=" + fileName);
        if (TextUtils.equals(fileName, GOOGLE_US_SAN_SAT_S2_FILE_NAME)
                || TextUtils.equals(fileName, GOOGLE_US_SAN_SAT_MTV_S2_FILE_NAME)) {
            mCtsSatS2FilePath = copyTestAssetFileToPhoneDirectory(fileName);
            if (mCtsSatS2FilePath != null) {
                return mCtsSatS2FilePath.toFile();
            } else {
                ploge("getTestSatelliteS2File: mCtsSatS2FilePath is null");
            }
        }
        return new File(fileName);
    }

    protected File getTestSatelliteConfiguration(String fileName) {
        plogd("getTestSatelliteConfiguration: fileName=" + fileName);
        if (TextUtils.equals(fileName, SATELLITE_ACCESS_CONFIG_FILE_NAME)) {
            mCtsSatelliteAccessConfigurationFilePath = copyTestAssetFileToPhoneDirectory(fileName);
            if (mCtsSatelliteAccessConfigurationFilePath != null) {
                return mCtsSatelliteAccessConfigurationFilePath.toFile();
            } else {
                ploge("getTestSatelliteConfiguration: mCtsSatelliteConfigurationFilePath is null");
            }
        }
        return new File(fileName);
    }

    @Nullable
    private static Path copyTestAssetFileToPhoneDirectory(String sourceFileName) {
        PhoneGlobals phoneGlobals = PhoneGlobals.getInstance();
        File ctsFile = phoneGlobals.getDir("cts", Context.MODE_PRIVATE);
        if (!ctsFile.exists()) {
            ctsFile.mkdirs();
        }

        Path targetDir = ctsFile.toPath();
        Path targetFilePath = targetDir.resolve(sourceFileName);
        try {
            var assetManager = phoneGlobals.getAssets();
            if (assetManager == null) {
                loge("copyTestAssetFileToPhoneDirectory: no assets");
                return null;
            }
            InputStream inputStream = assetManager.open(sourceFileName);
            if (inputStream == null) {
                loge("copyTestAssetFileToPhoneDirectory: Resource=" + sourceFileName
                        + " not found");
                return null;
            } else {
                Files.copy(inputStream, targetFilePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            loge("copyTestAssetFileToPhoneDirectory: ex=" + ex);
            return null;
        }
        return targetFilePath;
    }

    @Nullable
    private static File copyFileToLocalDirectory(@NonNull File sourceFile,
            @NonNull String targetFileName) {
        logd(
                "copyFileToLocalDirectory: Copying sourceFile:"
                        + sourceFile.getAbsolutePath()
                        + " to targetFileName:"
                        + targetFileName);
        PhoneGlobals phoneGlobals = PhoneGlobals.getInstance();
        File satelliteAccessControlDir = phoneGlobals.getDir(
                SATELLITE_ACCESS_CONTROL_DATA_DIR, Context.MODE_PRIVATE);
        if (!satelliteAccessControlDir.exists()) {
            satelliteAccessControlDir.mkdirs();
        }

        Path targetDir = satelliteAccessControlDir.toPath();
        Path targetFilePath = targetDir.resolve(targetFileName);
        logd(
                "copyFileToLocalDirectory: Copying from sourceFile="
                        + sourceFile.getAbsolutePath()
                        + " to targetFilePath="
                        + targetFilePath);
        try {
            InputStream inputStream = new FileInputStream(sourceFile);
            if (inputStream == null) {
                loge("copyFileToLocalDirectory: Resource=" + sourceFile.getAbsolutePath()
                        + " not found");
                return null;
            } else {
                Files.copy(inputStream, targetFilePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            loge("copyFileToLocalDirectory: ex=" + ex);
            return null;
        }

        File targetFile = targetFilePath.toFile();
        if (targetFile == null || !targetFile.exists()) {
            loge("copyFileToLocalDirectory: targetFile is null or not exist");
            return null;
        }
        logd(
                "copyFileToLocalDirectory: Copied from sourceFile="
                        + sourceFile.getAbsolutePath()
                        + " to targetFilePath="
                        + targetFilePath);
        return targetFile;
    }

    @Nullable
    private File getConfigUpdaterSatelliteConfigFileFromLocalDirectory(@NonNull String fileName) {
        PhoneGlobals phoneGlobals = PhoneGlobals.getInstance();
        File satelliteAccessControlDataDir = phoneGlobals.getDir(
                SATELLITE_ACCESS_CONTROL_DATA_DIR, Context.MODE_PRIVATE);
        if (!satelliteAccessControlDataDir.exists()) {
            ploge("getConfigUpdaterSatelliteConfigFileFromLocalDirectory: "
                    + "Directory: " + satelliteAccessControlDataDir.getAbsoluteFile()
                    + " is not exist");
            return null;
        }

        Path satelliteAccessControlFileDir = satelliteAccessControlDataDir.toPath();
        Path configUpdaterSatelliteConfigFilePath = satelliteAccessControlFileDir.resolve(fileName);
        File configUpdaterSatelliteConfigFile = configUpdaterSatelliteConfigFilePath.toFile();
        if (!configUpdaterSatelliteConfigFile.exists()) {
            ploge("getConfigUpdaterSatelliteConfigFileFromLocalDirectory: "
                    + "File: " + fileName + " is not exist");
            return null;
        }
        return configUpdaterSatelliteConfigFile;
    }

    private boolean isS2CellFileValid(@NonNull File s2CellFile) {
        try {
            SatelliteOnDeviceAccessController satelliteOnDeviceAccessController =
                    SatelliteOnDeviceAccessController.create(s2CellFile, mFeatureFlags);
            int s2Level = satelliteOnDeviceAccessController.getS2Level();
            if (s2Level < MIN_S2_LEVEL || s2Level > MAX_S2_LEVEL) {
                ploge("isS2CellFileValid: invalid s2 level = " + s2Level);
                satelliteOnDeviceAccessController.close();
                return false;
            }
            satelliteOnDeviceAccessController.close();
        } catch (Exception ex) {
            ploge("isS2CellFileValid: Got exception in reading the file, ex=" + ex);
            return false;
        }
        return true;
    }

    private void cleanUpCtsResources() {
        if (mCtsSatS2FilePath != null) {
            try {
                Files.delete(mCtsSatS2FilePath);
            } catch (IOException ex) {
                ploge("cleanUpCtsResources: ex=" + ex);
            }
        }
    }

    private void cleanUpTelephonyConfigs() {
        mSatelliteController.cleanUpTelephonyConfigs();
    }

    private void cleanUpSatelliteAccessConfigOtaResources() {
        PhoneGlobals phoneGlobals = PhoneGlobals.getInstance();
        File satelliteAccessControlDir =
                phoneGlobals.getDir(SATELLITE_ACCESS_CONTROL_DATA_DIR, Context.MODE_PRIVATE);
        if (satelliteAccessControlDir == null || !satelliteAccessControlDir.exists()) {
            plogd(
                    "cleanUpSatelliteAccessConfigOtaResources: "
                            + SATELLITE_ACCESS_CONTROL_DATA_DIR
                            + " does not exist");
            return;
        }
        plogd(
                "cleanUpSatelliteAccessConfigOtaResources: Deleting contents under "
                        + SATELLITE_ACCESS_CONTROL_DATA_DIR);
        FileUtils.deleteContents(satelliteAccessControlDir);
    }

    private void cleanupSatelliteConfigOtaResources() {
        SatelliteConfig satelliteConfig = mSatelliteController.getSatelliteConfig();
        if (satelliteConfig == null) {
            plogd(
                    "cleanupSatelliteConfigOtaResources: satelliteConfig is null. Cannot or Not"
                        + " needed to delete satellite config OTA files");
            return;
        }
        plogd("cleanupSatelliteConfigOtaResources: Deleting satellite config OTA files");
        satelliteConfig.cleanOtaResources(mContext);
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected long getElapsedRealtimeNanos() {
        return SystemClock.elapsedRealtimeNanos();
    }

    /**
     * @param countryCodes list of country code (two letters based on the ISO 3166-1).
     * @return {@code true} if the countryCode is valid {@code false} otherwise.
     */
    private boolean isValidCountryCodes(@Nullable List<String> countryCodes) {
        if (countryCodes == null || countryCodes.isEmpty()) {
            return false;
        }
        for (String countryCode : countryCodes) {
            if (!TelephonyUtils.isValidCountryCode(countryCode)) {
                ploge("invalid country code : " + countryCode);
                return false;
            }
        }
        return true;
    }

    private boolean updateSharedPreferencesCountryCodes(
            @NonNull Context context, @NonNull List<String> value) {
        if (mSharedPreferences == null) {
            plogd("updateSharedPreferencesCountryCodes: mSharedPreferences is null");
            initSharedPreferences(context);
        }
        if (mSharedPreferences == null) {
            ploge("updateSharedPreferencesCountryCodes: mSharedPreferences is still null");
            return false;
        }
        try {
            mSharedPreferences.edit().putStringSet(
                    CONFIG_UPDATER_SATELLITE_COUNTRY_CODES_KEY, new HashSet<>(value)).apply();
            return true;
        } catch (Exception ex) {
            ploge("updateSharedPreferencesCountryCodes error : " + ex);
            return false;
        }
    }

    private void deleteSharedPreferencesbyKey(
            @NonNull Context context, @NonNull String key) {
        plogd("deletedeleteSharedPreferencesbyKey: " + key);
        if (mSharedPreferences == null) {
            plogd("deleteSharedPreferencesbyKey: mSharedPreferences is null");
            initSharedPreferences(context);
        }
        if (mSharedPreferences == null) {
            plogd("deleteSharedPreferencesbyKey: mSharedPreferences is still null");
            return;
        }
        try {
            mSharedPreferences.edit().remove(key).apply();
        } catch (Exception ex) {
            ploge("deleteSharedPreferencesbyKey error : " + ex);
        }
    }

    private boolean updateSharedPreferencesIsAllowAccessControl(
            @NonNull Context context, boolean value) {
        if (mSharedPreferences == null) {
            plogd("updateSharedPreferencesIsAllowAccessControl: mSharedPreferences is null");
            initSharedPreferences(context);
        }
        if (mSharedPreferences == null) {
            ploge("updateSharedPreferencesIsAllowAccessControl: mSharedPreferences is null");
            return false;
        }
        try {
            mSharedPreferences.edit().putBoolean(
                    CONFIG_UPDATER_SATELLITE_IS_ALLOW_ACCESS_CONTROL_KEY,
                    value).apply();
            return true;
        } catch (Exception ex) {
            ploge("updateSharedPreferencesIsAllowAccessControl error: " + ex);
            return false;
        }
    }

    private boolean updateSharedPreferencesSatelliteAccessConfigVersion(
            @NonNull Context context, int version) {
        if (mSharedPreferences == null) {
            plogd("updateSharedPreferencesSatelliteAccessConfigVersion: "
                    + "mSharedPreferences is null");
            initSharedPreferences(context);
        }
        if (mSharedPreferences == null) {
            ploge("updateSharedPreferencesSatelliteAccessConfigVersion: "
                    + "mSharedPreferences is null");
            return false;
        }
        try {
            mSharedPreferences.edit().putInt(CONFIG_UPDATER_SATELLITE_VERSION_KEY, version).apply();
            return true;
        } catch (Exception ex) {
            ploge("updateSharedPreferencesSatelliteAccessConfigVersion error: " + ex);
            return false;
        }
    }

    private void persistLatestSatelliteCommunicationAllowedState() {
        if (mSharedPreferences == null) {
            ploge("persistLatestSatelliteCommunicationAllowedState: mSharedPreferences is null");
            return;
        }

        try {
            mSharedPreferences.edit().putLong(LATEST_SATELLITE_COMMUNICATION_ALLOWED_SET_TIME_KEY,
                    mLatestSatelliteCommunicationAllowedSetTime).apply();
            mSharedPreferences.edit().putBoolean(LATEST_SATELLITE_COMMUNICATION_ALLOWED_KEY,
                    mLatestSatelliteCommunicationAllowed).apply();
        } catch (Exception ex) {
            ploge("persistLatestSatelliteCommunicationAllowedState error : " + ex);
        }
    }

    /**
     * Update satellite access config data when ConfigUpdater updates with the new config data.
     * - country codes, satellite allow access, sats2.dat, satellite_access_config.json
     */
    private void updateSatelliteAccessDataWithConfigUpdaterData(Context context) {
        plogd("updateSatelliteAccessDataWithConfigUpdaterData");
        SatelliteConfig satelliteConfig = mSatelliteController.getSatelliteConfig();
        if (satelliteConfig == null) {
            ploge("updateSatelliteAccessDataWithConfigUpdaterData: satelliteConfig is null");
            mConfigUpdaterMetricsStats.reportOemAndCarrierConfigError(
                    SatelliteConstants.CONFIG_UPDATE_RESULT_NO_SATELLITE_DATA);
            return;
        }

        // satellite access config version
        int satelliteAccessConfigVersion = satelliteConfig.getSatelliteConfigDataVersion();
        if (satelliteAccessConfigVersion <= 0) {
            plogd("updateSatelliteAccessDataWithConfigUpdaterData: version is invalid: "
                    + satelliteAccessConfigVersion);
            return;
        }

        // validation check country code
        List<String> satelliteCountryCodes = satelliteConfig.getDeviceSatelliteCountryCodes();
        if (!isValidCountryCodes(satelliteCountryCodes)) {
            plogd("updateSatelliteAccessDataWithConfigUpdaterData: country codes is invalid");
            mConfigUpdaterMetricsStats.reportOemConfigError(
                    SatelliteConstants.CONFIG_UPDATE_RESULT_DEVICE_DATA_INVALID_COUNTRY_CODE);
            return;
        }

        // validation check allow region
        Boolean isSatelliteDataForAllowedRegion = satelliteConfig.isSatelliteDataForAllowedRegion();
        if (isSatelliteDataForAllowedRegion == null) {
            ploge("updateSatelliteAccessDataWithConfigUpdaterData: "
                    + "Satellite isSatelliteDataForAllowedRegion is null ");
            mConfigUpdaterMetricsStats.reportOemConfigError(
                    SatelliteConstants.CONFIG_UPDATE_RESULT_DEVICE_DATA_INVALID_S2_CELL_FILE);
            return;
        }

        // validation check s2 cell file
        File configUpdaterS2CellFile = satelliteConfig.getSatelliteS2CellFile(context);
        if (configUpdaterS2CellFile == null || !configUpdaterS2CellFile.exists()) {
            plogd("updateSatelliteAccessDataWithConfigUpdaterData: "
                    + "configUpdaterS2CellFile is not exist");
            mConfigUpdaterMetricsStats.reportOemConfigError(
                    SatelliteConstants.CONFIG_UPDATE_RESULT_DEVICE_DATA_INVALID_S2_CELL_FILE);
            return;
        }

        if (!isS2CellFileValid(configUpdaterS2CellFile)) {
            ploge("updateSatelliteAccessDataWithConfigUpdaterData: "
                    + "the configUpdaterS2CellFile is not valid");
            mConfigUpdaterMetricsStats.reportOemConfigError(
                    SatelliteConstants.CONFIG_UPDATE_RESULT_DEVICE_DATA_INVALID_S2_CELL_FILE);
            return;
        }

        // validation check satellite_access_config file
        File configUpdaterSatelliteAccessConfigJsonFile =
                satelliteConfig.getSatelliteAccessConfigJsonFile(context);
        if (configUpdaterSatelliteAccessConfigJsonFile == null
                || !configUpdaterSatelliteAccessConfigJsonFile.exists()) {
            plogd("updateSatelliteAccessDataWithConfigUpdaterData: "
                    + "satellite_access_config.json does not exist");
            mConfigUpdaterMetricsStats.reportOemConfigError(SatelliteConstants
                            .CONFIG_UPDATE_RESULT_INVALID_SATELLITE_ACCESS_CONFIG_FILE);
            return;
        }

        try {
            if (SatelliteAccessConfigurationParser.parse(
                    configUpdaterSatelliteAccessConfigJsonFile.getAbsolutePath()) == null) {
                ploge("updateSatelliteAccessDataWithConfigUpdaterData: "
                        + "the satellite_access_config.json is not valid");
                mConfigUpdaterMetricsStats.reportOemConfigError(SatelliteConstants
                        .CONFIG_UPDATE_RESULT_INVALID_SATELLITE_ACCESS_CONFIG_FILE);
                return;
            }
        } catch (Exception e) {
            loge("updateSatelliteAccessDataWithConfigUpdaterData: "
                    + "the satellite_access_config.json parse error " + e);
        }

        // copy s2 cell data into the phone internal directory
        File localS2CellFile = copyFileToLocalDirectory(
                configUpdaterS2CellFile, CONFIG_UPDATER_S2_CELL_FILE_NAME);
        if (localS2CellFile == null) {
            ploge("updateSatelliteAccessDataWithConfigUpdaterData: "
                    + "fail to copy localS2CellFile");
            mConfigUpdaterMetricsStats.reportOemConfigError(
                    SatelliteConstants.CONFIG_UPDATE_RESULT_IO_ERROR);
            return;
        }

        // copy satellite_access_config file into the phone internal directory
        File localSatelliteAccessConfigFile = copyFileToLocalDirectory(
                configUpdaterSatelliteAccessConfigJsonFile,
                CONFIG_UPDATER_SATELLITE_ACCESS_CONFIG_FILE_NAME);

        if (localSatelliteAccessConfigFile == null) {
            ploge("updateSatelliteAccessDataWithConfigUpdaterData: "
                    + "fail to copy localSatelliteAccessConfigFile");
            mConfigUpdaterMetricsStats.reportOemConfigError(
                    SatelliteConstants.CONFIG_UPDATE_RESULT_IO_ERROR);
            localS2CellFile.delete();
            return;
        }

        // copy country codes into the shared preferences of phoen
        if (!updateSharedPreferencesCountryCodes(context, satelliteCountryCodes)) {
            ploge("updateSatelliteAccessDataWithConfigUpdaterData: "
                    + "fail to copy country coeds into shared preferences");
            localS2CellFile.delete();
            localSatelliteAccessConfigFile.delete();
            mConfigUpdaterMetricsStats.reportOemConfigError(
                    SatelliteConstants.CONFIG_UPDATE_RESULT_IO_ERROR);
            return;
        }

        // copy allow access into the shared preferences of phone
        if (!updateSharedPreferencesIsAllowAccessControl(
                context, isSatelliteDataForAllowedRegion.booleanValue())) {
            ploge("updateSatelliteAccessDataWithConfigUpdaterData: "
                    + "fail to copy isSatelliteDataForAllowedRegion"
                    + " into shared preferences");
            localS2CellFile.delete();
            localSatelliteAccessConfigFile.delete();
            deleteSharedPreferencesbyKey(
                    context, CONFIG_UPDATER_SATELLITE_COUNTRY_CODES_KEY);
            mConfigUpdaterMetricsStats.reportOemConfigError(
                    SatelliteConstants.CONFIG_UPDATE_RESULT_IO_ERROR);
            return;
        }

        // copy version of satellite access config into the shared preferences of phone
        if (!updateSharedPreferencesSatelliteAccessConfigVersion(
                context, satelliteAccessConfigVersion)) {
            ploge("updateSatelliteAccessDataWithConfigUpdaterData: "
                    + "fail to copy satelliteAccessConfigVersion"
                    + " into shared preferences");
            localS2CellFile.delete();
            localSatelliteAccessConfigFile.delete();
            deleteSharedPreferencesbyKey(
                    context, CONFIG_UPDATER_SATELLITE_COUNTRY_CODES_KEY);
            deleteSharedPreferencesbyKey(
                    context, CONFIG_UPDATER_SATELLITE_IS_ALLOW_ACCESS_CONTROL_KEY);
            mConfigUpdaterMetricsStats.reportOemConfigError(
                    SatelliteConstants.CONFIG_UPDATE_RESULT_IO_ERROR);
            return;
        }

        mSatelliteAccessConfigVersion = satelliteAccessConfigVersion;
        mSatelliteS2CellFile = localS2CellFile;
        mSatelliteAccessConfigFile = localSatelliteAccessConfigFile;
        mSatelliteCountryCodes = satelliteCountryCodes;
        mIsSatelliteAllowAccessControl = satelliteConfig.isSatelliteDataForAllowedRegion();
        plogd("mSatelliteAccessConfigVersion=" + mSatelliteAccessConfigVersion
                + ", Use s2 cell file=" + mSatelliteS2CellFile.getAbsolutePath()
                + ", mSatelliteAccessConfigFile=" + mSatelliteAccessConfigFile.getAbsolutePath()
                + ", country codes=" + String.join(",", mSatelliteCountryCodes)
                + ", mIsSatelliteAllowAccessControl=" + mIsSatelliteAllowAccessControl
                + " from ConfigUpdater");

        // Clean up resources so that the new config data will be used when serving new requests
        cleanupOnDeviceAccessControllerResources();

        // Clean up cached data based on previous geofence data
        synchronized (mLock) {
            plogd("clear mCachedAccessRestrictionMap");
            mCachedAccessRestrictionMap.clear();
        }

        mConfigUpdaterMetricsStats.reportConfigUpdateSuccess();
        // We need to re-evaluate if satellite is allowed at the current location and if
        // satellite access configuration has changed with the config data received from config
        // server, and then notify listeners accordingly.
        sendRequestAsync(EVENT_CONFIG_DATA_UPDATED, null);
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected void loadOverlayConfigs(@NonNull Context context) {
        plogd("loadOverlayConfigs");
        mSatelliteCountryCodes = getSatelliteCountryCodesFromOverlayConfig(context);
        mIsSatelliteAllowAccessControl = getSatelliteAccessAllowFromOverlayConfig(context);
        String satelliteS2CellFileName = getSatelliteS2CellFileFromOverlayConfig(context);
        mSatelliteS2CellFile = TextUtils.isEmpty(satelliteS2CellFileName)
                ? null : new File(satelliteS2CellFileName);
        if (mSatelliteS2CellFile != null && !mSatelliteS2CellFile.exists()) {
            ploge("The satellite S2 cell file " + satelliteS2CellFileName + " does not exist");
            mSatelliteS2CellFile = null;
        }

        String satelliteAccessConfigFileName =
                getSatelliteConfigurationFileNameFromOverlayConfig(context);
        mSatelliteAccessConfigFile = TextUtils.isEmpty(satelliteAccessConfigFileName)
                ? null : new File(satelliteAccessConfigFileName);
        if (mSatelliteAccessConfigFile != null && !mSatelliteAccessConfigFile.exists()) {
            ploge("The satellite access config file " + satelliteAccessConfigFileName
                    + " does not exist");
            mSatelliteAccessConfigFile = null;
        }

        mLocationFreshDurationNanos = getSatelliteLocationFreshDurationFromOverlayConfig(context);
        mAccessControllerMetricsStats.setConfigDataSource(
                SatelliteConstants.CONFIG_DATA_SOURCE_DEVICE_CONFIG);
        mRetryIntervalToEvaluateUserInSatelliteAllowedRegion =
                getDelayBeforeRetryValidatingPossibleChangeInSatelliteAllowedRegionMillis(context);
        mMaxRetryCountForValidatingPossibleChangeInAllowedRegion =
                getMaxRetryCountForValidatingPossibleChangeInAllowedRegion(context);
        mLocationQueryThrottleIntervalNanos = getLocationQueryThrottleIntervalNanos(context);
    }

    protected void loadSatelliteAccessConfiguration() {
        logd("loadSatelliteAccessConfiguration");
        String satelliteConfigurationFileName;
        File satelliteAccessConfigFile = getSatelliteAccessConfigFile();
        synchronized (mLock) {
            if (satelliteAccessConfigFile != null) {
                satelliteConfigurationFileName = satelliteAccessConfigFile.getAbsolutePath();
            } else {
                logd("loadSatelliteAccessConfiguration:");
                satelliteConfigurationFileName = getSatelliteConfigurationFileNameFromOverlayConfig(
                        mContext);
            }
        }

        loadSatelliteAccessConfigurationFileToMap(satelliteConfigurationFileName);
    }

    protected void loadSatelliteAccessConfigurationFileToMap(String fileName) {
        logd("loadSatelliteAccessConfigurationFileToMap: " + fileName);
        if (!TextUtils.isEmpty(fileName)) {
            try {
                synchronized (mLock) {
                    mSatelliteAccessConfigMap =
                            SatelliteAccessConfigurationParser.parse(fileName);
                }
            } catch (Exception e) {
                loge("loadSatelliteAccessConfigurationFileToMap: failed load json file: " + e);
            }
        } else {
            loge("loadSatelliteAccessConfigurationFileToMap: fileName is empty");
        }
    }

    private void loadConfigUpdaterConfigs() {
        plogd("loadConfigUpdaterConfigs");
        if (mSharedPreferences == null) {
            ploge("loadConfigUpdaterConfigs : mSharedPreferences is null");
            return;
        }

        int satelliteConfigVersion = mSharedPreferences.getInt(
                CONFIG_UPDATER_SATELLITE_VERSION_KEY, 0);
        if (satelliteConfigVersion <= 0) {
            ploge("loadConfigUpdaterConfigs: satelliteConfigVersion is invalid: "
                    + satelliteConfigVersion);
            return;
        }

        Set<String> countryCodes =
                mSharedPreferences.getStringSet(CONFIG_UPDATER_SATELLITE_COUNTRY_CODES_KEY, null);

        if (countryCodes == null || countryCodes.isEmpty()) {
            ploge("loadConfigUpdaterConfigs: configupdater country codes are either null or empty");
            return;
        }

        boolean isSatelliteAllowAccessControl =
                mSharedPreferences.getBoolean(
                        CONFIG_UPDATER_SATELLITE_IS_ALLOW_ACCESS_CONTROL_KEY, true);

        File s2CellFile = getConfigUpdaterSatelliteConfigFileFromLocalDirectory(
                CONFIG_UPDATER_S2_CELL_FILE_NAME);
        if (s2CellFile == null) {
            ploge("loadConfigUpdaterConfigs: s2CellFile is null");
            return;
        }

        File satelliteAccessConfigJsonFile = getConfigUpdaterSatelliteConfigFileFromLocalDirectory(
                CONFIG_UPDATER_SATELLITE_ACCESS_CONFIG_FILE_NAME);
        if (satelliteAccessConfigJsonFile == null) {
            ploge("satelliteAccessConfigJsonFile is null");
            return;
        }

        mSatelliteAccessConfigVersion = satelliteConfigVersion;
        mSatelliteS2CellFile = s2CellFile;
        mSatelliteAccessConfigFile = satelliteAccessConfigJsonFile;
        mSatelliteCountryCodes = countryCodes.stream().collect(Collectors.toList());
        mIsSatelliteAllowAccessControl = isSatelliteAllowAccessControl;
        plogd("loadConfigUpdaterConfigs: use satellite config data from configupdater: "
                + " mSatelliteAccessConfigVersion=" + mSatelliteAccessConfigVersion
                + ", Use s2 cell file=" + mSatelliteS2CellFile.getAbsolutePath()
                + ", mSatelliteAccessConfigFile=" + mSatelliteAccessConfigFile.getAbsolutePath()
                + ", country codes=" + String.join(",", mSatelliteCountryCodes)
                + ", mIsSatelliteAllowAccessControl=" + mIsSatelliteAllowAccessControl
                + " from ConfigUpdater");
        mAccessControllerMetricsStats.setConfigDataSource(
                SatelliteConstants.CONFIG_DATA_SOURCE_CONFIG_UPDATER);
    }

    private void loadCachedLatestSatelliteCommunicationAllowedState() {
        if (mSharedPreferences == null) {
            ploge("loadCachedLatestSatelliteCommunicationAllowedState: mSharedPreferences is null");
            return;
        }

        try {
            mLatestSatelliteCommunicationAllowedSetTime =
                    mSharedPreferences.getLong(LATEST_SATELLITE_COMMUNICATION_ALLOWED_SET_TIME_KEY,
                            0);
            mLatestSatelliteCommunicationAllowed =
                    mSharedPreferences.getBoolean(LATEST_SATELLITE_COMMUNICATION_ALLOWED_KEY,
                            false);
        } catch (Exception ex) {
            ploge("loadCachedLatestSatelliteCommunicationAllowedState: ex=" + ex);
        }
        plogd("mLatestSatelliteCommunicationAllowedSetTime="
                + mLatestSatelliteCommunicationAllowedSetTime
                + ", mLatestSatelliteCommunicationAllowed=" + mLatestSatelliteCommunicationAllowed);
    }

    private long getLocationFreshDurationNanos() {
        synchronized (mLock) {
            if (mIsOverlayConfigOverridden) {
                return mOverriddenLocationFreshDurationNanos;
            }
            return mLocationFreshDurationNanos;
        }
    }

    /**
     * Returns a list of satellite country codes.
     *
     * @return The list of satellite country codes.
     */
    @NonNull
    public List<String> getSatelliteCountryCodes() {
        synchronized (mLock) {
            if (mIsOverlayConfigOverridden) {
                return mOverriddenSatelliteCountryCodes;
            }
            return mSatelliteCountryCodes;
        }
    }

    /**
     * Returns a satellite s2 cell file
     *
     * @return The file of satellite s2 cell
     */
    @Nullable
    public File getSatelliteS2CellFile() {
        synchronized (mLock) {
            if (mIsOverlayConfigOverridden) {
                return mOverriddenSatelliteS2CellFile;
            }
            return mSatelliteS2CellFile;
        }
    }

    /**
     * Returns a satellite access config file
     *
     * @return The file of satellite access config
     */
    @Nullable
    public File getSatelliteAccessConfigFile() {
        synchronized (mLock) {
            if (mIsOverlayConfigOverridden) {
                logd("mIsOverlayConfigOverridden: " + mIsOverlayConfigOverridden);
                return mOverriddenSatelliteAccessConfigFile;
            }
            if (mSatelliteAccessConfigFile != null) {
                logd("getSatelliteAccessConfigFile path: "
                        + mSatelliteAccessConfigFile.getAbsoluteFile());
            }
            return mSatelliteAccessConfigFile;
        }
    }

    /**
     * Checks if satellite access control is allowed.
     *
     * @return {@code true} if satellite access control is allowed, {@code false} otherwise.
     */
    public boolean isSatelliteAllowAccessControl() {
        synchronized (mLock) {
            if (mIsOverlayConfigOverridden) {
                return mOverriddenIsSatelliteAllowAccessControl;
            }
            return mIsSatelliteAllowAccessControl;
        }
    }

    private void handleCmdIsSatelliteAllowedForCurrentLocation(
            @NonNull Pair<Integer, ResultReceiver> requestArguments) {
        synchronized (mLock) {
            mSatelliteAllowResultReceivers.add(requestArguments.second);
            if (mSatelliteAllowResultReceivers.size() > 1) {
                plogd("requestIsCommunicationAllowedForCurrentLocation is already being "
                        + "processed");
                return;
            }
            mTotalCheckingStartTimeMillis = System.currentTimeMillis();
            mSatelliteController.requestIsSatelliteSupported(
                    mInternalSatelliteSupportedResultReceiver);
        }
    }

    private void handleWaitForCurrentLocationTimedOutEvent() {
        plogd("Timed out to wait for current location");
        synchronized (mLock) {
            if (mLocationRequestCancellationSignal != null) {
                mLocationRequestCancellationSignal.cancel();
                mLocationRequestCancellationSignal = null;
                onCurrentLocationAvailable(null);
            } else {
                ploge("handleWaitForCurrentLocationTimedOutEvent: "
                        + "mLocationRequestCancellationSignal is null");
            }
        }
    }

    private void registerDefaultSmsAppChangedBroadcastReceiver(Context context) {
        if (!mFeatureFlags.carrierRoamingNbIotNtn()) {
            plogd("registerDefaultSmsAppChangedBroadcastReceiver: Flag "
                    + "carrierRoamingNbIotNtn is disabled");
            return;
        }
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        intentFilter.addDataScheme("package");
        context.registerReceiver(mDefaultSmsAppChangedBroadcastReceiver, intentFilter);
    }

    private void registerLocationModeChangedBroadcastReceiver(Context context) {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(LocationManager.MODE_CHANGED_ACTION);
        intentFilter.addAction(LocationManager.PROVIDERS_CHANGED_ACTION);
        context.registerReceiver(mLocationModeChangedBroadcastReceiver, intentFilter);
    }

    /**
     * At country borders, a multi-SIM device might connect to multiple cellular base
     * stations and thus might have multiple different MCCs.
     * In such cases, framework is not sure whether the region should be disallowed or not,
     * and thus the geofence data will be used to decide whether to allow satellite.
     */
    private boolean isRegionDisallowed(List<String> networkCountryIsoList) {
        if (networkCountryIsoList.isEmpty()) {
            plogd("isRegionDisallowed : false : network country code is not available");
            return false;
        }

        for (String countryCode : networkCountryIsoList) {
            if (isSatelliteAccessAllowedForLocation(List.of(countryCode))) {
                plogd("isRegionDisallowed : false : Country Code " + countryCode
                        + " is allowed but not sure if current location should be allowed.");
                return false;
            }
        }

        plogd("isRegionDisallowed : true : " + networkCountryIsoList);
        return true;
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected void handleIsSatelliteSupportedResult(int resultCode, Bundle resultData) {
        plogd("handleIsSatelliteSupportedResult: resultCode=" + resultCode);
        synchronized (mLock) {
            if (resultCode == SATELLITE_RESULT_SUCCESS) {
                if (resultData.containsKey(KEY_SATELLITE_SUPPORTED)) {
                    boolean isSatelliteSupported = resultData.getBoolean(KEY_SATELLITE_SUPPORTED);
                    if (!isSatelliteSupported) {
                        plogd("Satellite is not supported");
                        Bundle bundle = new Bundle();
                        bundle.putBoolean(SatelliteManager.KEY_SATELLITE_COMMUNICATION_ALLOWED,
                                false);
                        sendSatelliteAllowResultToReceivers(SATELLITE_RESULT_NOT_SUPPORTED, bundle,
                                false);
                    } else {
                        plogd("Satellite is supported");
                        List<String> networkCountryIsoList =
                                mCountryDetector.getCurrentNetworkCountryIso();
                        if (isRegionDisallowed(networkCountryIsoList)) {
                            Bundle bundle = new Bundle();
                            bundle.putBoolean(KEY_SATELLITE_COMMUNICATION_ALLOWED, false);
                            mAccessControllerMetricsStats.setAccessControlType(SatelliteConstants
                                            .ACCESS_CONTROL_TYPE_NETWORK_COUNTRY_CODE)
                                    .setCountryCodes(networkCountryIsoList);
                            sendSatelliteAllowResultToReceivers(SATELLITE_RESULT_SUCCESS, bundle,
                                    false);
                        } else {
                            checkSatelliteAccessRestrictionUsingGPS();
                        }
                    }
                } else {
                    ploge("KEY_SATELLITE_SUPPORTED does not exist.");
                    sendSatelliteAllowResultToReceivers(resultCode, resultData, false);
                }
            } else {
                sendSatelliteAllowResultToReceivers(resultCode, resultData, false);
            }
        }
    }

    private void handleIsSatelliteProvisionedResult(int resultCode, Bundle resultData) {
        plogd("handleIsSatelliteProvisionedResult: resultCode=" + resultCode);
        synchronized (mLock) {
            if (resultCode == SATELLITE_RESULT_SUCCESS) {
                if (resultData.containsKey(KEY_SATELLITE_PROVISIONED)) {
                    boolean isSatelliteProvisioned =
                            resultData.getBoolean(KEY_SATELLITE_PROVISIONED);
                    if (!isSatelliteProvisioned) {
                        plogd("Satellite is not provisioned");
                        Bundle bundle = new Bundle();
                        bundle.putBoolean(SatelliteManager.KEY_SATELLITE_COMMUNICATION_ALLOWED,
                                false);
                        sendSatelliteAllowResultToReceivers(resultCode, bundle, false);
                    } else {
                        plogd("Satellite is provisioned");
                        checkSatelliteAccessRestrictionUsingGPS();
                    }
                } else {
                    ploge("KEY_SATELLITE_PROVISIONED does not exist.");
                    sendSatelliteAllowResultToReceivers(resultCode, resultData, false);
                }
            } else {
                sendSatelliteAllowResultToReceivers(resultCode, resultData, false);
            }
        }
    }

    private void sendSatelliteAllowResultToReceivers(int resultCode, Bundle resultData,
            boolean allowed) {
        plogd("sendSatelliteAllowResultToReceivers : resultCode is " + resultCode);
        switch(resultCode) {
            case SATELLITE_RESULT_SUCCESS:
                updateCurrentSatelliteAllowedState(allowed);
                mIsCurrentLocationEligibleForNotification = true;
                break;

            case SATELLITE_RESULT_LOCATION_DISABLED:
                updateCurrentSatelliteAllowedState(allowed);
                break;
            default:
                break;
        }

        synchronized (mLock) {
            for (ResultReceiver resultReceiver : mSatelliteAllowResultReceivers) {
                resultReceiver.send(resultCode, resultData);
                mSatelliteController.decrementResultReceiverCount(
                        "SAC:requestIsCommunicationAllowedForCurrentLocation");
            }
            mSatelliteAllowResultReceivers.clear();
        }
        if (!shouldRetryValidatingPossibleChangeInAllowedRegion(resultCode)) {
            setIsSatelliteAllowedRegionPossiblyChanged(false);
        }
        Integer disallowedReason = getDisallowedReason(resultCode, allowed);
        boolean isChanged = false;
        if (disallowedReason != SATELLITE_DISALLOWED_REASON_NONE) {
            if (!isReasonPresentInSatelliteDisallowedReasons(disallowedReason)) {
                isChanged = true;
            }
        } else {
            if (isSatelliteDisallowedReasonsEmpty()) {
                if (!hasAlreadyNotified(KEY_AVAILABLE_NOTIFICATION_SHOWN)) {
                    isChanged = true;
                }
            }
            if (isReasonPresentInSatelliteDisallowedReasons(
                    SATELLITE_DISALLOWED_REASON_NOT_IN_ALLOWED_REGION)
                    || isReasonPresentInSatelliteDisallowedReasons(
                    SATELLITE_DISALLOWED_REASON_LOCATION_DISABLED)) {
                isChanged = true;
            }
        }
        removeAllReasonsFromSatelliteDisallowedReasons(DISALLOWED_REASONS_TO_BE_RESET);
        if (disallowedReason != SATELLITE_DISALLOWED_REASON_NONE) {
            addReasonToSatelliteDisallowedReasons(disallowedReason);
        }
        if (isChanged) {
            handleEventDisallowedReasonsChanged();
        }
        synchronized (mIsAllowedCheckBeforeEnablingSatelliteLock) {
            mIsAllowedCheckBeforeEnablingSatellite = false;
        }
        reportMetrics(resultCode, allowed);
    }

    private int getDisallowedReason(int resultCode, boolean allowed) {
        if (resultCode == SATELLITE_RESULT_SUCCESS) {
            if (!allowed) {
                return SATELLITE_DISALLOWED_REASON_NOT_IN_ALLOWED_REGION;
            }
        } else if (resultCode == SATELLITE_RESULT_LOCATION_DISABLED) {
            return SATELLITE_DISALLOWED_REASON_LOCATION_DISABLED;
        }
        return SATELLITE_DISALLOWED_REASON_NONE;
    }

    private void handleEventDisallowedReasonsChanged() {
        if (mNotificationManager == null) {
            logd("showSatelliteSystemNotification: NotificationManager is null");
            return;
        }

        List<Integer> satelliteDisallowedReasons = getSatelliteDisallowedReasonsCopy();
        plogd("getSatelliteDisallowedReasons: satelliteDisallowedReasons:"
                + String.join(", ", satelliteDisallowedReasons.toString()));

        notifySatelliteDisallowedReasonsChanged();
        if (mSatelliteController.isSatelliteSystemNotificationsEnabled(
                CarrierConfigManager.CARRIER_ROAMING_NTN_CONNECT_MANUAL)
                && mIsCurrentLocationEligibleForNotification
                && mIsProvisionEligibleForNotification) {
            showSatelliteSystemNotification();
        } else {
            logd("mSatelliteDisallowedReasons:"
                    + " CurrentLocationAvailable: " + mIsCurrentLocationEligibleForNotification
                    + " SatelliteProvision: " + mIsProvisionEligibleForNotification);
            // If subId does not support satellite, remove the notification currently shown.
            if (hasAlreadyNotified(KEY_UNAVAILABLE_NOTIFICATION_SHOWN)) {
                mNotificationManager.cancel(UNAVAILABLE_NOTIFICATION_TAG, NOTIFICATION_ID);
            }
            if (hasAlreadyNotified(KEY_AVAILABLE_NOTIFICATION_SHOWN)) {
                mNotificationManager.cancel(AVAILABLE_NOTIFICATION_TAG, NOTIFICATION_ID);
            }
        }
    }

    private void showSatelliteSystemNotification() {
        if (mNotificationManager == null) {
            logd("showSatelliteSystemNotification: NotificationManager is null");
            return;
        }

        if (isSatelliteDisallowedReasonsEmpty()) {
            mNotificationManager.cancel(UNAVAILABLE_NOTIFICATION_TAG, NOTIFICATION_ID);
            if (!hasAlreadyNotified(KEY_AVAILABLE_NOTIFICATION_SHOWN)) {
                mNotificationManager.notifyAsUser(
                        AVAILABLE_NOTIFICATION_TAG,
                        NOTIFICATION_ID,
                        mSatelliteAvailableNotification,
                        UserHandle.ALL
                );
                markAsNotified(KEY_AVAILABLE_NOTIFICATION_SHOWN, true);
                markAsNotified(KEY_UNAVAILABLE_NOTIFICATION_SHOWN, false);
                logd("showSatelliteSystemNotification: Notification is shown "
                        + KEY_AVAILABLE_NOTIFICATION_SHOWN);
            } else {
                logd("showSatelliteSystemNotification: Notification is not shown "
                        + KEY_AVAILABLE_NOTIFICATION_SHOWN + " = "
                        + hasAlreadyNotified(KEY_AVAILABLE_NOTIFICATION_SHOWN));
            }
        } else {
            mNotificationManager.cancel(AVAILABLE_NOTIFICATION_TAG, NOTIFICATION_ID);
            for (Integer reason : mSatelliteDisallowedReasons) {
                if (!hasAlreadyNotified(KEY_UNAVAILABLE_NOTIFICATION_SHOWN)) {
                    mNotificationManager.notifyAsUser(
                            UNAVAILABLE_NOTIFICATION_TAG,
                            NOTIFICATION_ID,
                            mSatelliteUnAvailableNotifications.get(reason),
                            UserHandle.ALL
                    );
                    markAsNotified(KEY_UNAVAILABLE_NOTIFICATION_SHOWN, true);
                    markAsNotified(KEY_AVAILABLE_NOTIFICATION_SHOWN, false);
                    logd("showSatelliteSystemNotification: Notification is shown "
                            + KEY_UNAVAILABLE_NOTIFICATION_SHOWN);
                    break;
                } else {
                    logd("showSatelliteSystemNotification: Notification is not shown "
                            + KEY_UNAVAILABLE_NOTIFICATION_SHOWN);
                }
            }
        }
    }

    private boolean hasAlreadyNotified(String key) {
        return mSharedPreferences.getBoolean(key, false);
    }

    private void markAsNotified(String key, boolean notified) {
        mSharedPreferences.edit().putBoolean(key, notified).apply();
    }

    private void checkSharedPreference() {
        String[] keys = {
                CONFIG_UPDATER_SATELLITE_IS_ALLOW_ACCESS_CONTROL_KEY,
                LATEST_SATELLITE_COMMUNICATION_ALLOWED_KEY,
                KEY_AVAILABLE_NOTIFICATION_SHOWN,
                KEY_UNAVAILABLE_NOTIFICATION_SHOWN
        };
        // An Exception may occur if the initial value is set to HashSet while attempting to obtain
        // a boolean value. If an exception occurs, the SharedPreferences will be removed with Keys.
        Arrays.stream(keys).forEach(key -> {
            try {
                mSharedPreferences.getBoolean(key, false);
            } catch (ClassCastException e) {
                mSharedPreferences.edit().remove(key).apply();
            }
        });
    }

    /**
     * Telephony-internal logic to verify if satellite access is restricted at the current
     * location.
     */
    private void checkSatelliteAccessRestrictionForCurrentLocation() {
        synchronized (mLock) {
            List<String> networkCountryIsoList = mCountryDetector.getCurrentNetworkCountryIso();
            if (!networkCountryIsoList.isEmpty()) {
                plogd("Use current network country codes=" + String.join(", ",
                        networkCountryIsoList));

                boolean allowed = isSatelliteAccessAllowedForLocation(networkCountryIsoList);
                Bundle bundle = new Bundle();
                bundle.putBoolean(KEY_SATELLITE_COMMUNICATION_ALLOWED, allowed);
                mAccessControllerMetricsStats
                        .setAccessControlType(
                                SatelliteConstants.ACCESS_CONTROL_TYPE_NETWORK_COUNTRY_CODE)
                        .setCountryCodes(networkCountryIsoList);
                sendSatelliteAllowResultToReceivers(SATELLITE_RESULT_SUCCESS, bundle, allowed);
            } else {
                if (shouldUseOnDeviceAccessController()) {
                    // This will be an asynchronous check when it needs to wait for the current
                    // location from location service
                    checkSatelliteAccessRestrictionUsingOnDeviceData();
                } else {
                    // This is always a synchronous check
                    checkSatelliteAccessRestrictionUsingCachedCountryCodes();
                }
            }
        }
    }

    private boolean shouldRetryValidatingPossibleChangeInAllowedRegion(int resultCode) {
        return (resultCode == SATELLITE_RESULT_LOCATION_NOT_AVAILABLE);
    }

    private void initializeHandlerForSatelliteAllowedResult() {
        mHandlerForSatelliteAllowedResult = new ResultReceiver(null) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                plogd("query satellite allowed for current "
                        + "location, resultCode=" + resultCode + ", resultData=" + resultData);
                synchronized (mPossibleChangeInSatelliteAllowedRegionLock) {
                    if (shouldRetryValidatingPossibleChangeInAllowedRegion(resultCode)
                            && (mRetryCountForValidatingPossibleChangeInAllowedRegion
                            < mMaxRetryCountForValidatingPossibleChangeInAllowedRegion)) {
                        mRetryCountForValidatingPossibleChangeInAllowedRegion++;
                        plogd("mRetryCountForValidatingPossibleChangeInAllowedRegion is "
                                + mRetryCountForValidatingPossibleChangeInAllowedRegion);
                        sendDelayedRequestAsync(CMD_IS_SATELLITE_COMMUNICATION_ALLOWED,
                                new Pair<>(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID,
                                        mHandlerForSatelliteAllowedResult),
                                mRetryIntervalToEvaluateUserInSatelliteAllowedRegion);
                    } else {
                        mRetryCountForValidatingPossibleChangeInAllowedRegion = 0;
                        plogd("Stop retry validating the possible change in satellite allowed "
                                + "region");
                    }
                }
            }
        };
    }

    private void initializeSatelliteSystemNotification(@NonNull Context context) {
        final NotificationChannel notificationChannel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL,
                NotificationManager.IMPORTANCE_DEFAULT
        );
        notificationChannel.setSound(null, null);
        mNotificationManager = context.getSystemService(NotificationManager.class);
        if(mNotificationManager == null) {
            ploge("initializeSatelliteSystemNotification: notificationManager is null");
            return;
        }
        mNotificationManager.createNotificationChannel(notificationChannel);

        createAvailableNotifications(context);
        createUnavailableNotifications(context);
    }

    private Notification createNotification(@NonNull Context context, String title,
            String content) {
        Notification.Builder notificationBuilder = new Notification.Builder(context)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(R.drawable.ic_android_satellite_24px)
                .setChannelId(NOTIFICATION_CHANNEL_ID)
                .setAutoCancel(true)
                .setColor(context.getColor(
                        com.android.internal.R.color.system_notification_accent_color))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setLocalOnly(true);

        return notificationBuilder.build();
    }

    private void createAvailableNotifications(Context context) {
        int subId = mSatelliteController.getSelectedSatelliteSubId();
        int titleId;
        int summaryId;

        if (mSatelliteController.isSatelliteServiceSupportedByCarrier(
                subId, NetworkRegistrationInfo.SERVICE_TYPE_SMS)) {
            titleId = R.string.satellite_messaging_available_notification_title;
            summaryId = R.string.satellite_messaging_available_notification_summary;
        } else {
            titleId = R.string.satellite_sos_available_notification_title;
            summaryId = R.string.satellite_sos_available_notification_summary;
        }

        mSatelliteAvailableNotification = createNotification(
                context,
                context.getResources().getString(titleId),
                context.getResources().getString(summaryId));
    }

    private void createUnavailableNotifications(Context context) {
        int subId = mSatelliteController.getSelectedSatelliteSubId();

        HashMap<Integer, Pair<Integer, Integer>> unavailableReasons;
        if (mSatelliteController.isSatelliteServiceSupportedByCarrier(
                subId, NetworkRegistrationInfo.SERVICE_TYPE_SMS)) {
            unavailableReasons = SATELLITE_MESSAGING_UNAVAILABLE_REASONS;
        } else {
            unavailableReasons = SATELLITE_SOS_UNAVAILABLE_REASONS;
        }

        for (int reason : unavailableReasons.keySet()) {
            Pair<Integer, Integer> notificationString =
                    unavailableReasons.getOrDefault(reason, null);
            if (notificationString != null) {
                mSatelliteUnAvailableNotifications.put(reason,
                        createNotification(
                                context,
                                context.getResources().getString(notificationString.first),
                                context.getResources().getString(notificationString.second)));
            }
        }
    }

    private final BroadcastReceiver mDefaultSmsAppChangedBroadcastReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent.getAction()
                            .equals(Intent.ACTION_PACKAGE_CHANGED)) {
                        evaluatePossibleChangeInDefaultSmsApp(context);
                    }
                }
            };

    private void evaluatePossibleChangeInDefaultSmsApp(@NonNull Context context) {
        if (!mFeatureFlags.carrierRoamingNbIotNtn()) {
            plogd("evaluatePossibleChangeInDefaultSmsApp: Flag "
                    + "carrierRoamingNbIotNtn is disabled");
            return;
        }

        boolean isDefaultMsgAppSupported = false;
        ComponentName componentName = SmsApplication.getDefaultSmsApplicationAsUser(
                        context, true, context.getUser());
        plogd("Current default SMS app:" + componentName);
        if (componentName != null) {
            String packageName = componentName.getPackageName();
            List<String> supportedMsgApps =
                    mSatelliteController.getSatelliteSupportedMsgApps(
                            mSatelliteController.getSelectedSatelliteSubId());
            plogd("supportedMsgApps:" + String.join(", ", supportedMsgApps));
            if (supportedMsgApps.contains(packageName)) {
                isDefaultMsgAppSupported = true;
            }
        } else {
            plogd("No default SMS app");
        }

        if (isDefaultMsgAppSupported) {
            if (isReasonPresentInSatelliteDisallowedReasons(
                    SATELLITE_DISALLOWED_REASON_UNSUPPORTED_DEFAULT_MSG_APP)) {
                removeReasonFromSatelliteDisallowedReasons(
                        SATELLITE_DISALLOWED_REASON_UNSUPPORTED_DEFAULT_MSG_APP);
                handleEventDisallowedReasonsChanged();
            }
        } else {
            if (!isReasonPresentInSatelliteDisallowedReasons(
                    SATELLITE_DISALLOWED_REASON_UNSUPPORTED_DEFAULT_MSG_APP)) {
                addReasonToSatelliteDisallowedReasons(
                        SATELLITE_DISALLOWED_REASON_UNSUPPORTED_DEFAULT_MSG_APP);
                handleEventDisallowedReasonsChanged();
            }
        }
    }

    private void handleSatelliteAllowedRegionPossiblyChanged(int handleEvent) {
        synchronized (mPossibleChangeInSatelliteAllowedRegionLock) {
            logd("handleSatelliteAllowedRegionPossiblyChanged");
            setIsSatelliteAllowedRegionPossiblyChanged(true);
            requestIsCommunicationAllowedForCurrentLocation(
                    mHandlerForSatelliteAllowedResult, false);
            int triggeringEvent = TRIGGERING_EVENT_UNKNOWN;
            if (handleEvent == EVENT_LOCATION_SETTINGS_ENABLED) {
                triggeringEvent = TRIGGERING_EVENT_LOCATION_SETTINGS_ENABLED;
            } else if (handleEvent == EVENT_COUNTRY_CODE_CHANGED) {
                triggeringEvent = TRIGGERING_EVENT_MCC_CHANGED;
            } else if (handleEvent == EVENT_LOCATION_SETTINGS_DISABLED) {
                triggeringEvent = TRIGGERING_EVENT_LOCATION_SETTINGS_DISABLED;
            } else if (handleEvent == EVENT_CONFIG_DATA_UPDATED) {
                triggeringEvent = TRIGGERING_EVENT_CONFIG_DATA_UPDATED;
            }
            mAccessControllerMetricsStats.setTriggeringEvent(triggeringEvent);
        }
    }

    protected boolean allowLocationQueryForSatelliteAllowedCheck() {
        synchronized (mPossibleChangeInSatelliteAllowedRegionLock) {
            if (!isCommunicationAllowedCacheValid()) {
                logd("allowLocationQueryForSatelliteAllowedCheck: cache is not valid");
                return true;
            }

            if (isSatelliteAllowedRegionPossiblyChanged() && !isLocationQueryThrottled()) {
                logd("allowLocationQueryForSatelliteAllowedCheck: location query is not throttled");
                return true;
            }
        }
        logd("allowLocationQueryForSatelliteAllowedCheck: false");
        return false;
    }

    private boolean isLocationQueryThrottled() {
        if (mLastLocationQueryForPossibleChangeInAllowedRegionTimeNanos == 0) {
            plogv("isLocationQueryThrottled: "
                    + "mLastLocationQueryForPossibleChangeInAllowedRegionTimeNanos is 0, return "
                    + "false");
            return false;
        }

        long currentTime = getElapsedRealtimeNanos();
        if (currentTime - mLastLocationQueryForPossibleChangeInAllowedRegionTimeNanos
                > mLocationQueryThrottleIntervalNanos) {
            plogv("isLocationQueryThrottled: currentTime - "
                    + "mLastLocationQueryForPossibleChangeInAllowedRegionTimeNanos is "
                    + "bigger than " + mLocationQueryThrottleIntervalNanos + " so return false");
            return false;
        }

        plogd("isLocationQueryThrottled : true");
        return true;
    }

    /**
     * Telephony-internal logic to verify if satellite access is restricted from the location query.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public void checkSatelliteAccessRestrictionUsingGPS() {
        logv("checkSatelliteAccessRestrictionUsingGPS:");
        synchronized (mIsAllowedCheckBeforeEnablingSatelliteLock) {
            if (isInEmergency()) {
                executeLocationQuery();
            } else {
                if (mLocationManager.isLocationEnabled()) {
                    plogd("location query is allowed");
                    if (allowLocationQueryForSatelliteAllowedCheck()
                            || mIsAllowedCheckBeforeEnablingSatellite) {
                        executeLocationQuery();
                    } else {
                        Bundle bundle = new Bundle();
                        bundle.putBoolean(KEY_SATELLITE_COMMUNICATION_ALLOWED,
                                mLatestSatelliteCommunicationAllowed);
                        sendSatelliteAllowResultToReceivers(SATELLITE_RESULT_SUCCESS, bundle,
                                mLatestSatelliteCommunicationAllowed);
                    }
                } else {
                    plogv("location query is not allowed");
                    Bundle bundle = new Bundle();
                    bundle.putBoolean(KEY_SATELLITE_COMMUNICATION_ALLOWED, false);
                    sendSatelliteAllowResultToReceivers(
                            SATELLITE_RESULT_LOCATION_DISABLED, bundle, false);
                }
            }
        }
    }

    /**
     * @return {@code true} if the latest query was executed within the predefined valid duration,
     * {@code false} otherwise.
     */
    private boolean isCommunicationAllowedCacheValid() {
        if (mLatestSatelliteCommunicationAllowedSetTime > 0) {
            long currentTime = getElapsedRealtimeNanos();
            if ((currentTime - mLatestSatelliteCommunicationAllowedSetTime)
                    <= ALLOWED_STATE_CACHE_VALID_DURATION_NANOS) {
                logv("isCommunicationAllowedCacheValid: cache is valid");
                return true;
            }
        }
        logv("isCommunicationAllowedCacheValid: cache is expired");
        return false;
    }

    private void executeLocationQuery() {
        plogd("executeLocationQuery");
        synchronized (mLock) {
            mFreshLastKnownLocation = getFreshLastKnownLocation();
            checkSatelliteAccessRestrictionUsingOnDeviceData();
        }
    }

    /**
     * This function synchronously checks if satellite is allowed at current location using cached
     * country codes.
     */
    private void checkSatelliteAccessRestrictionUsingCachedCountryCodes() {
        Pair<String, Long> locationCountryCodeInfo =
                mCountryDetector.getCachedLocationCountryIsoInfo();
        Map<String, Long> networkCountryCodeInfoMap =
                mCountryDetector.getCachedNetworkCountryIsoInfo();
        List<String> countryCodeList;

        // Check if the cached location country code's timestamp is newer than all cached network
        // country codes
        if (!TextUtils.isEmpty(locationCountryCodeInfo.first) && isGreaterThanAll(
                locationCountryCodeInfo.second, networkCountryCodeInfoMap.values())) {
            // Use cached location country code
            countryCodeList = Arrays.asList(locationCountryCodeInfo.first);
        } else {
            // Use cached network country codes
            countryCodeList = networkCountryCodeInfoMap.keySet().stream().toList();
        }
        plogd("Use cached country codes=" + String.join(", ", countryCodeList));
        mAccessControllerMetricsStats.setAccessControlType(
                SatelliteConstants.ACCESS_CONTROL_TYPE_CACHED_COUNTRY_CODE);

        boolean allowed = isSatelliteAccessAllowedForLocation(countryCodeList);
        Bundle bundle = new Bundle();
        bundle.putBoolean(KEY_SATELLITE_COMMUNICATION_ALLOWED, allowed);
        sendSatelliteAllowResultToReceivers(SATELLITE_RESULT_SUCCESS, bundle, allowed);
    }

    /**
     * This function asynchronously checks if satellite is allowed at the current location using
     * on-device data. Asynchronous check happens when it needs to wait for the current location
     * from location service.
     */
    private void checkSatelliteAccessRestrictionUsingOnDeviceData() {
        mOnDeviceLookupStartTimeMillis = System.currentTimeMillis();
        synchronized (mLock) {
            plogd("Use on-device data");
            if (mFreshLastKnownLocation != null) {
                mAccessControllerMetricsStats.setAccessControlType(
                        SatelliteConstants.ACCESS_CONTROL_TYPE_LAST_KNOWN_LOCATION);
                checkSatelliteAccessRestrictionForLocation(mFreshLastKnownLocation);
                mFreshLastKnownLocation = null;
            } else {
                Location freshLastKnownLocation = getFreshLastKnownLocation();
                if (freshLastKnownLocation != null) {
                    mAccessControllerMetricsStats.setAccessControlType(
                            SatelliteConstants.ACCESS_CONTROL_TYPE_LAST_KNOWN_LOCATION);
                    checkSatelliteAccessRestrictionForLocation(freshLastKnownLocation);
                } else {
                    queryCurrentLocation();
                }
            }
        }
    }

    private void queryCurrentLocation() {
        synchronized (mLock) {
            if (mLocationRequestCancellationSignal != null) {
                plogd("queryCurrentLocation : "
                        + "Request for current location was already sent to LocationManager");
                return;
            }

            synchronized (mPossibleChangeInSatelliteAllowedRegionLock) {
                if (isSatelliteAllowedRegionPossiblyChanged()) {
                    mLastLocationQueryForPossibleChangeInAllowedRegionTimeNanos =
                            getElapsedRealtimeNanos();
                    plogd("mLastLocationQueryForPossibleChangeInAllowedRegionTimeNanos is set "
                            + mLastLocationQueryForPossibleChangeInAllowedRegionTimeNanos);
                }
            }

            mLocationRequestCancellationSignal = new CancellationSignal();
            mLocationQueryStartTimeMillis = System.currentTimeMillis();
            mLocationManager.getCurrentLocation(LocationManager.FUSED_PROVIDER,
                    new LocationRequest.Builder(0)
                            .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                            .setLocationSettingsIgnored(isInEmergency())
                            .build(),
                    mLocationRequestCancellationSignal, this::post,
                    this::onCurrentLocationAvailable);
            startWaitForCurrentLocationTimer();
        }
    }

    private void onCurrentLocationAvailable(@Nullable Location location) {
        plogd("onCurrentLocationAvailable " + (location != null));
        synchronized (mLock) {
            stopWaitForCurrentLocationTimer();
            mLocationRequestCancellationSignal = null;
            mAccessControllerMetricsStats.setLocationQueryTime(mLocationQueryStartTimeMillis);
            Bundle bundle = new Bundle();
            if (location != null) {
                plogd("onCurrentLocationAvailable: lat=" + Rlog.pii(TAG, location.getLatitude())
                        + ", long=" + Rlog.pii(TAG, location.getLongitude()));
                if (location.isMock() && !isMockModemAllowed()) {
                    logd("location is mock");
                    bundle.putBoolean(KEY_SATELLITE_COMMUNICATION_ALLOWED, false);
                    sendSatelliteAllowResultToReceivers(SATELLITE_RESULT_SUCCESS, bundle, false);
                    return;
                }
                mAccessControllerMetricsStats.setAccessControlType(
                        SatelliteConstants.ACCESS_CONTROL_TYPE_CURRENT_LOCATION);
                mControllerMetricsStats.reportLocationQuerySuccessful(true);
                checkSatelliteAccessRestrictionForLocation(location);
                mIsCurrentLocationEligibleForNotification = true;
            } else {
                plogd("current location is not available");
                if (isCommunicationAllowedCacheValid()) {
                    plogd("onCurrentLocationAvailable: cache is still valid, using it");
                    bundle.putBoolean(KEY_SATELLITE_COMMUNICATION_ALLOWED,
                            mLatestSatelliteCommunicationAllowed);
                    sendSatelliteAllowResultToReceivers(SATELLITE_RESULT_SUCCESS, bundle,
                            mLatestSatelliteCommunicationAllowed);
                    mIsCurrentLocationEligibleForNotification = true;
                } else {
                    bundle.putBoolean(KEY_SATELLITE_COMMUNICATION_ALLOWED, false);
                    sendSatelliteAllowResultToReceivers(
                            SATELLITE_RESULT_LOCATION_NOT_AVAILABLE, bundle, false);
                }
                mControllerMetricsStats.reportLocationQuerySuccessful(false);
            }
        }
    }

    protected void checkSatelliteAccessRestrictionForLocation(@NonNull Location location) {
        synchronized (mLock) {
            try {
                plogd(
                        "checkSatelliteAccessRestrictionForLocation: "
                                + "checking satellite access restriction for location: lat - "
                                + Rlog.pii(TAG, location.getLatitude())
                                + ", long - "
                                + Rlog.pii(TAG, location.getLongitude())
                                + ", mS2Level - "
                                + mS2Level);
                SatelliteOnDeviceAccessController.LocationToken locationToken =
                        SatelliteOnDeviceAccessController.createLocationTokenForLatLng(
                                location.getLatitude(),
                                location.getLongitude(), mS2Level);
                boolean satelliteAllowed;

                if (mCachedAccessRestrictionMap.containsKey(locationToken)) {
                    mNewRegionalConfigId = mCachedAccessRestrictionMap.get(locationToken);
                    satelliteAllowed = (mNewRegionalConfigId != null);
                    plogd("mNewRegionalConfigId from mCachedAccessRestrictionMap is "
                            + mNewRegionalConfigId);
                } else {
                    if (!initSatelliteOnDeviceAccessController()) {
                        ploge("Failed to init SatelliteOnDeviceAccessController");
                        Bundle bundle = new Bundle();
                        bundle.putBoolean(KEY_SATELLITE_COMMUNICATION_ALLOWED, false);
                        sendSatelliteAllowResultToReceivers(SATELLITE_RESULT_SUCCESS, bundle,
                                false);
                        return;
                    }

                    if (mFeatureFlags.carrierRoamingNbIotNtn()) {
                        synchronized (mLock) {
                            mNewRegionalConfigId = mSatelliteOnDeviceAccessController
                                    .getRegionalConfigIdForLocation(locationToken);
                            plogd(
                                    "mNewRegionalConfigId from geofence file lookup is "
                                            + mNewRegionalConfigId);
                            satelliteAllowed = (mNewRegionalConfigId != null);
                        }
                    } else {
                        plogd("checkSatelliteAccessRestrictionForLocation: "
                                + "carrierRoamingNbIotNtn is disabled");
                        satelliteAllowed = mSatelliteOnDeviceAccessController
                                .isSatCommunicationAllowedAtLocation(locationToken);
                        plogd(
                                "checkSatelliteAccessRestrictionForLocation: satelliteAllowed from "
                                        + "geofence file lookup: "
                                        + satelliteAllowed);
                        mNewRegionalConfigId =
                                satelliteAllowed ? UNKNOWN_REGIONAL_SATELLITE_CONFIG_ID : null;
                    }
                    updateCachedAccessRestrictionMap(locationToken, mNewRegionalConfigId);
                }
                mAccessControllerMetricsStats.setOnDeviceLookupTime(mOnDeviceLookupStartTimeMillis);
                plogd(
                        "checkSatelliteAccessRestrictionForLocation: "
                                + (satelliteAllowed ? "Satellite Allowed" : "Satellite NOT Allowed")
                                + " for location: lat - "
                                + Rlog.pii(TAG, location.getLatitude())
                                + ", long - "
                                + Rlog.pii(TAG, location.getLongitude())
                                + ", mS2Level - "
                                + mS2Level);
                Bundle bundle = new Bundle();
                bundle.putBoolean(KEY_SATELLITE_COMMUNICATION_ALLOWED, satelliteAllowed);
                sendSatelliteAllowResultToReceivers(SATELLITE_RESULT_SUCCESS, bundle,
                        satelliteAllowed);
                mLatestSatelliteCommunicationAllowed = satelliteAllowed;
                mLatestSatelliteCommunicationAllowedSetTime = getElapsedRealtimeNanos();
                persistLatestSatelliteCommunicationAllowedState();
            } catch (Exception ex) {
                ploge("checkSatelliteAccessRestrictionForLocation: ex=" + ex);
                reportAnomaly(UUID_ON_DEVICE_LOOKUP_EXCEPTION,
                        "On-device satellite lookup exception");
                Bundle bundle = new Bundle();
                if (isCommunicationAllowedCacheValid()) {
                    bundle.putBoolean(KEY_SATELLITE_COMMUNICATION_ALLOWED,
                            mLatestSatelliteCommunicationAllowed);
                    plogd(
                            "checkSatelliteAccessRestrictionForLocation: cache is still valid, "
                                    + "allowing satellite communication");
                } else {
                    bundle.putBoolean(KEY_SATELLITE_COMMUNICATION_ALLOWED, false);
                    plogd("satellite communication not allowed");
                }
                sendSatelliteAllowResultToReceivers(SATELLITE_RESULT_SUCCESS, bundle,
                        mLatestSatelliteCommunicationAllowed);
            }
        }
    }

    private void updateRegionalConfigId() {
        synchronized (mLock) {
            plogd("mNewRegionalConfigId: updatedValue = " + mNewRegionalConfigId
                    + " | mRegionalConfigId: beforeValue = " + mRegionalConfigId);
            if (!Objects.equals(mRegionalConfigId, mNewRegionalConfigId)) {
                mRegionalConfigId = mNewRegionalConfigId;
                notifyRegionalSatelliteConfigurationChanged(
                        Optional.ofNullable(mSatelliteAccessConfigMap)
                                .map(map -> map.get(mRegionalConfigId))
                                .orElse(null));
            }
        }
    }

    private void updateCachedAccessRestrictionMap(
            @NonNull SatelliteOnDeviceAccessController.LocationToken locationToken,
            Integer regionalConfigId) {
        synchronized (mLock) {
            mCachedAccessRestrictionMap.put(locationToken, regionalConfigId);
        }
    }

    private boolean isGreaterThanAll(
            long comparedItem, @NonNull Collection<Long> itemCollection) {
        for (long item : itemCollection) {
            if (comparedItem <= item) return false;
        }
        return true;
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected boolean isSatelliteAccessAllowedForLocation(
            @NonNull List<String> networkCountryIsoList) {
        if (isSatelliteAllowAccessControl()) {
            // The current country is unidentified, we're uncertain and thus returning false
            if (networkCountryIsoList.isEmpty()) {
                return false;
            }

            // In case of allowed list, satellite is allowed if all country codes are be in the
            // allowed list
            return getSatelliteCountryCodes().containsAll(networkCountryIsoList);
        } else {
            // No country is barred, thus returning true
            if (getSatelliteCountryCodes().isEmpty()) {
                return true;
            }

            // The current country is unidentified, we're uncertain and thus returning false
            if (networkCountryIsoList.isEmpty()) {
                return false;
            }

            // In case of disallowed list, if any country code is in the list, satellite will be
            // disallowed
            for (String countryCode : networkCountryIsoList) {
                if (getSatelliteCountryCodes().contains(countryCode)) {
                    return false;
                }
            }
            return true;
        }
    }

    private boolean shouldUseOnDeviceAccessController() {
        if (getSatelliteS2CellFile() == null) {
            return false;
        }

        if (isInEmergency() || mLocationManager.isLocationEnabled()) {
            return true;
        }

        Location freshLastKnownLocation = getFreshLastKnownLocation();
        if (freshLastKnownLocation != null) {
            synchronized (mLock) {
                mFreshLastKnownLocation = freshLastKnownLocation;
            }
            return true;
        } else {
            synchronized (mLock) {
                mFreshLastKnownLocation = null;
            }
        }
        return false;
    }

    @Nullable
    private Location getFreshLastKnownLocation() {
        Location lastKnownLocation = getLastKnownLocation();
        if (lastKnownLocation != null) {
            long lastKnownLocationAge =
                    getElapsedRealtimeNanos() - lastKnownLocation.getElapsedRealtimeNanos();
            if (lastKnownLocationAge <= getLocationFreshDurationNanos()) {
                plogd("getFreshLastKnownLocation: lat=" + Rlog.pii(TAG,
                        lastKnownLocation.getLatitude())
                        + ", long=" + Rlog.pii(TAG, lastKnownLocation.getLongitude()));
                return lastKnownLocation;
            }
        }
        return null;
    }

    private boolean isInEmergency() {
        // Check if emergency call is ongoing
        if (mTelecomManager.isInEmergencyCall()) {
            plogd("In emergency call");
            return true;
        }

        // Check if the device is in emergency callback mode
        for (Phone phone : PhoneFactory.getPhones()) {
            if (phone.isInEcm()) {
                plogd("In emergency callback mode");
                return true;
            }
        }

        // Check if satellite is in emergency mode
        if (mSatelliteController.isInEmergencyMode()) {
            plogd("In satellite emergency mode");
            return true;
        }
        return false;
    }

    @Nullable
    private Location getLastKnownLocation() {
        Location result = null;
        for (String provider : mLocationManager.getProviders(true)) {
            Location location = mLocationManager.getLastKnownLocation(provider);
            if (location != null && (result == null
                    || result.getElapsedRealtimeNanos() < location.getElapsedRealtimeNanos())) {
                result = location;
            }
        }

        if (result == null || isMockModemAllowed()) {
            return result;
        }

        return result.isMock() ? null : result;
    }

    private void initSharedPreferences(@NonNull Context context) {
        try {
            mSharedPreferences =
                    context.getSharedPreferences(SATELLITE_SHARED_PREF, Context.MODE_PRIVATE);
        } catch (Exception e) {
            ploge("Cannot get default shared preferences: " + e);
        }
    }

    /**
     * @return {@code true} if successfully initialize the {@link SatelliteOnDeviceAccessController}
     * instance, {@code false} otherwise.
     * @throws IllegalStateException in case of getting any exception in creating the
     *                               {@link SatelliteOnDeviceAccessController} instance and the
     *                               device is using a user build.
     */
    private boolean initSatelliteOnDeviceAccessController()
            throws IllegalStateException {
        plogd("initSatelliteOnDeviceAccessController");

        synchronized (mLock) {
            if (getSatelliteS2CellFile() == null) return false;

            // mSatelliteOnDeviceAccessController was already initialized successfully
            if (mSatelliteOnDeviceAccessController != null) {
                restartKeepOnDeviceAccessControllerResourcesTimer();
                return true;
            }

            try {
                mSatelliteOnDeviceAccessController =
                        SatelliteOnDeviceAccessController.create(
                                getSatelliteS2CellFile(), mFeatureFlags);

                plogd(
                        "initSatelliteOnDeviceAccessController: initialized"
                            + " SatelliteOnDeviceAccessController");
                restartKeepOnDeviceAccessControllerResourcesTimer();
                mS2Level = mSatelliteOnDeviceAccessController.getS2Level();
                plogd("mS2Level=" + mS2Level);
                loadSatelliteAccessConfiguration();
            } catch (Exception ex) {
                ploge("Got exception in creating an instance of SatelliteOnDeviceAccessController,"
                        + " ex=" + ex + ", sat s2 file="
                        + getSatelliteS2CellFile().getAbsolutePath());
                reportAnomaly(UUID_CREATE_ON_DEVICE_ACCESS_CONTROLLER_EXCEPTION,
                        "Exception in creating on-device satellite access controller");
                mSatelliteOnDeviceAccessController = null;
                mSatelliteAccessConfigMap = null;
                if (!mIsOverlayConfigOverridden) {
                    mSatelliteS2CellFile = null;
                }
                return false;
            }
            return true;
        }
    }

    private void cleanupOnDeviceAccessControllerResources() {
        synchronized (mLock) {
            plogd("cleanupOnDeviceAccessControllerResources="
                    + (mSatelliteOnDeviceAccessController != null));
            if (mSatelliteOnDeviceAccessController != null) {
                try {
                    mSatelliteOnDeviceAccessController.close();
                } catch (Exception ex) {
                    ploge("cleanupOnDeviceAccessControllerResources: ex=" + ex);
                }
                mSatelliteOnDeviceAccessController = null;
                stopKeepOnDeviceAccessControllerResourcesTimer();
            }
        }
    }

    private void handleCmdUpdateSystemSelectionChannels(
            @NonNull ResultReceiver resultReceiver) {
        synchronized (mLock) {
            mUpdateSystemSelectionChannelsResultReceivers.add(resultReceiver);
            if (mUpdateSystemSelectionChannelsResultReceivers.size() > 1) {
                plogd("updateSystemSelectionChannels is already being processed");
                return;
            }
            int subId =  mSatelliteController.getSelectedSatelliteSubId();
            plogd("handleCmdUpdateSystemSelectionChannels: SatellitePhone subId: " + subId);
            if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                sendUpdateSystemSelectionChannelsResult(
                        SATELLITE_RESULT_INVALID_TELEPHONY_STATE, null);
                return;
            }

            String mccmnc = "";
            final SubscriptionInfo subInfo = SubscriptionManagerService.getInstance()
                    .getSubscriptionInfo(subId);
            if (subInfo != null) {
                mccmnc = subInfo.getMccString() + subInfo.getMncString();
            }

            final Integer[] regionalConfigId = new Integer[1];
            regionalConfigId[0] = getSelectedRegionalConfigId();
            if (regionalConfigId[0] != null
                    && regionalConfigId[0] == UNKNOWN_REGIONAL_SATELLITE_CONFIG_ID) {
                // The geofence file with old format return UNKNOWN_REGIONAL_SATELLITE_CONFIG_ID
                // for an S2 cell present in the file.
                // For backward compatibility, we will use DEFAULT_REGIONAL_SATELLITE_CONFIG_ID
                // for such cases.
                regionalConfigId[0] = DEFAULT_REGIONAL_SATELLITE_CONFIG_ID;
            }
            if (!SatelliteAccessConfigurationParser.isRegionalConfigIdValid(regionalConfigId[0])) {
                plogd("handleCmdUpdateSystemSelectionChannels: mRegionalConfigId is not valid, "
                        + "mRegionalConfig=" + getSelectedRegionalConfigId());
                sendUpdateSystemSelectionChannelsResult(
                        SATELLITE_RESULT_ACCESS_BARRED, null);
                return;
            }

            SatelliteAccessConfiguration satelliteAccessConfiguration;
            synchronized (mLock) {
                satelliteAccessConfiguration = Optional.ofNullable(mSatelliteAccessConfigMap)
                        .map(map -> map.get(regionalConfigId[0]))
                        .orElse(null);
            }
            if (satelliteAccessConfiguration == null) {
                plogd("handleCmdUpdateSystemSelectionChannels: satelliteAccessConfiguration "
                        + "is not valid");
                sendUpdateSystemSelectionChannelsResult(
                        SATELLITE_RESULT_ACCESS_BARRED, null);
                return;
            }

            List<SatelliteInfo> satelliteInfos =
                    satelliteAccessConfiguration.getSatelliteInfos();
            List<Integer> bandList = new ArrayList<>();
            List<Integer> earfcnList = new ArrayList<>();
            for (SatelliteInfo satelliteInfo : satelliteInfos) {
                bandList.addAll(satelliteInfo.getBands());
                List<EarfcnRange> earfcnRangeList = satelliteInfo.getEarfcnRanges();
                earfcnRangeList.stream().flatMapToInt(
                        earfcnRange -> IntStream.of(earfcnRange.getStartEarfcn(),
                                earfcnRange.getEndEarfcn())).boxed().forEach(earfcnList::add);
            }

            IntArray bands = new IntArray(bandList.size());
            bands.addAll(bandList.stream().mapToInt(Integer::intValue).toArray());
            IntArray earfcns = new IntArray(
                    Math.min(earfcnList.size(), MAX_EARFCN_ARRAY_LENGTH));
            for (int i = 0; i < Math.min(earfcnList.size(), MAX_EARFCN_ARRAY_LENGTH); i++) {
                earfcns.add(earfcnList.get(i));
            }
            IntArray tagIds = new IntArray(satelliteAccessConfiguration.getTagIds().size());
            tagIds.addAll(satelliteAccessConfiguration.getTagIds().stream().mapToInt(
                    Integer::intValue).toArray());

            List<SystemSelectionSpecifier> selectionSpecifiers = new ArrayList<>();
            selectionSpecifiers.add(new SystemSelectionSpecifier(mccmnc, bands, earfcns,
                    satelliteInfos.toArray(new SatelliteInfo[0]), tagIds));
            mSatelliteController.updateSystemSelectionChannels(selectionSpecifiers,
                    mInternalUpdateSystemSelectionChannelsResultReceiver);
        }
    }

    private void sendUpdateSystemSelectionChannelsResult(int resultCode, Bundle resultData) {
        plogd("sendUpdateSystemSelectionChannelsResult: resultCode=" + resultCode);

        synchronized (mLock) {
            for (ResultReceiver resultReceiver : mUpdateSystemSelectionChannelsResultReceivers) {
                resultReceiver.send(resultCode, resultData);
            }
            mUpdateSystemSelectionChannelsResultReceivers.clear();
        }
    }

    private static boolean getSatelliteAccessAllowFromOverlayConfig(@NonNull Context context) {
        Boolean accessAllowed = null;
        try {
            accessAllowed = context.getResources().getBoolean(
                    com.android.internal.R.bool.config_oem_enabled_satellite_access_allow);
        } catch (Resources.NotFoundException ex) {
            loge("getSatelliteAccessAllowFromOverlayConfig: got ex=" + ex);
        }
        if (accessAllowed == null && isMockModemAllowed()) {
            logd("getSatelliteAccessAllowFromOverlayConfig: Read "
                    + "config_oem_enabled_satellite_access_allow from device config");
            accessAllowed = DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_TELEPHONY,
                    "config_oem_enabled_satellite_access_allow", DEFAULT_SATELLITE_ACCESS_ALLOW);
        }
        if (accessAllowed == null) {
            logd("Use default satellite access allow=true control");
            accessAllowed = true;
        }
        return accessAllowed;
    }

    @Nullable
    protected String getSatelliteConfigurationFileNameFromOverlayConfig(
            @NonNull Context context) {
        String satelliteAccessControlInfoFile = null;

        if (!mFeatureFlags.carrierRoamingNbIotNtn()) {
            logd("mFeatureFlags: carrierRoamingNbIotNtn is disabled");
            return satelliteAccessControlInfoFile;
        }

        try {
            satelliteAccessControlInfoFile = context.getResources().getString(
                    com.android.internal.R.string.satellite_access_config_file);
        } catch (Resources.NotFoundException ex) {
            loge("getSatelliteConfigurationFileNameFromOverlayConfig: got ex=" + ex);
        }

        logd("satelliteAccessControlInfoFile =" + satelliteAccessControlInfoFile);
        return satelliteAccessControlInfoFile;
    }

    @Nullable
    private static String getSatelliteS2CellFileFromOverlayConfig(@NonNull Context context) {
        String s2CellFile = null;
        try {
            s2CellFile = context.getResources().getString(
                    com.android.internal.R.string.config_oem_enabled_satellite_s2cell_file);
        } catch (Resources.NotFoundException ex) {
            loge("getSatelliteS2CellFileFromOverlayConfig: got ex=" + ex);
        }
        if (TextUtils.isEmpty(s2CellFile) && isMockModemAllowed()) {
            logd("getSatelliteS2CellFileFromOverlayConfig: Read "
                    + "config_oem_enabled_satellite_s2cell_file from device config");
            s2CellFile = DeviceConfig.getString(DeviceConfig.NAMESPACE_TELEPHONY,
                    "config_oem_enabled_satellite_s2cell_file", null);
        }
        logd("s2CellFile=" + s2CellFile);
        return s2CellFile;
    }

    @NonNull
    private static List<String> getSatelliteCountryCodesFromOverlayConfig(
            @NonNull Context context) {
        String[] countryCodes = readStringArrayFromOverlayConfig(context,
                com.android.internal.R.array.config_oem_enabled_satellite_country_codes);
        if (countryCodes.length == 0 && isMockModemAllowed()) {
            logd("getSatelliteCountryCodesFromOverlayConfig: Read "
                    + "config_oem_enabled_satellite_country_codes from device config");
            String countryCodesStr = DeviceConfig.getString(DeviceConfig.NAMESPACE_TELEPHONY,
                    "config_oem_enabled_satellite_country_codes", "");
            countryCodes = countryCodesStr.split(",");
        }
        return Arrays.stream(countryCodes)
                .map(x -> x.toUpperCase(Locale.US))
                .collect(Collectors.toList());
    }

    @NonNull
    private static long getDelayBeforeRetryValidatingPossibleChangeInSatelliteAllowedRegionMillis(
            @NonNull Context context) {
        Integer retryDuration = null;
        try {
            retryDuration = context.getResources().getInteger(com.android.internal.R.integer
                    .config_satellite_delay_minutes_before_retry_validating_possible_change_in_allowed_region);
        } catch (Resources.NotFoundException ex) {
            loge("getDelayBeforeRetryValidatingPossibleChangeInSatelliteAllowedRegionMillis: got "
                    + "ex=" + ex);
        }
        if (retryDuration == null) {
            logd("Use default retry duration for possible change satellite allowed region ="
                    + DEFAULT_DELAY_MINUTES_BEFORE_VALIDATING_POSSIBLE_CHANGE_IN_ALLOWED_REGION);
            retryDuration =
                    DEFAULT_DELAY_MINUTES_BEFORE_VALIDATING_POSSIBLE_CHANGE_IN_ALLOWED_REGION;
        }
        return TimeUnit.MINUTES.toMillis(retryDuration);
    }

    @NonNull
    private static int getMaxRetryCountForValidatingPossibleChangeInAllowedRegion(
            @NonNull Context context) {
        Integer maxRetrycount = null;
        try {
            maxRetrycount = context.getResources().getInteger(com.android.internal.R.integer
                    .config_satellite_max_retry_count_for_validating_possible_change_in_allowed_region);
        } catch (Resources.NotFoundException ex) {
            loge("getMaxRetryCountForValidatingPossibleChangeInAllowedRegion: got ex= " + ex);
        }
        if (maxRetrycount == null) {
            logd("Use default max retry count for possible change satellite allowed region ="
                    + DEFAULT_MAX_RETRY_COUNT_FOR_VALIDATING_POSSIBLE_CHANGE_IN_ALLOWED_REGION);
            maxRetrycount =
                    DEFAULT_MAX_RETRY_COUNT_FOR_VALIDATING_POSSIBLE_CHANGE_IN_ALLOWED_REGION;
        }
        return maxRetrycount;
    }

    @NonNull
    private static long getLocationQueryThrottleIntervalNanos(@NonNull Context context) {
        Integer throttleInterval = null;
        try {
            throttleInterval = context.getResources().getInteger(com.android.internal.R.integer
                    .config_satellite_location_query_throttle_interval_minutes);
        } catch (Resources.NotFoundException ex) {
            loge("getLocationQueryThrottleIntervalNanos: got ex=" + ex);
        }
        if (throttleInterval == null) {
            logd("Use default location query throttle interval ="
                    + DEFAULT_THROTTLE_INTERVAL_FOR_LOCATION_QUERY_MINUTES);
            throttleInterval =
                    DEFAULT_THROTTLE_INTERVAL_FOR_LOCATION_QUERY_MINUTES;
        }
        return TimeUnit.MINUTES.toNanos(throttleInterval);
    }

    @NonNull
    private static String[] readStringArrayFromOverlayConfig(
            @NonNull Context context, @ArrayRes int id) {
        String[] strArray = null;
        try {
            strArray = context.getResources().getStringArray(id);
        } catch (Resources.NotFoundException ex) {
            loge("readStringArrayFromOverlayConfig: id= " + id + ", ex=" + ex);
        }
        if (strArray == null) {
            strArray = new String[0];
        }
        return strArray;
    }

    private static long getSatelliteLocationFreshDurationFromOverlayConfig(
            @NonNull Context context) {
        Integer freshDuration = null;
        try {
            freshDuration = context.getResources().getInteger(com.android.internal.R.integer
                    .config_oem_enabled_satellite_location_fresh_duration);
        } catch (Resources.NotFoundException ex) {
            loge("getSatelliteLocationFreshDurationFromOverlayConfig: got ex=" + ex);
        }
        if (freshDuration == null && isMockModemAllowed()) {
            logd("getSatelliteLocationFreshDurationFromOverlayConfig: Read "
                    + "config_oem_enabled_satellite_location_fresh_duration from device config");
            freshDuration = DeviceConfig.getInt(DeviceConfig.NAMESPACE_TELEPHONY,
                    "config_oem_enabled_satellite_location_fresh_duration",
                    DEFAULT_LOCATION_FRESH_DURATION_SECONDS);
        }
        if (freshDuration == null) {
            logd("Use default satellite location fresh duration="
                    + DEFAULT_LOCATION_FRESH_DURATION_SECONDS);
            freshDuration = DEFAULT_LOCATION_FRESH_DURATION_SECONDS;
        }
        return TimeUnit.SECONDS.toNanos(freshDuration);
    }

    private void startWaitForCurrentLocationTimer() {
        synchronized (mLock) {
            if (hasMessages(EVENT_WAIT_FOR_CURRENT_LOCATION_TIMEOUT)) {
                plogw("WaitForCurrentLocationTimer is already started");
                removeMessages(EVENT_WAIT_FOR_CURRENT_LOCATION_TIMEOUT);
            }
            sendEmptyMessageDelayed(EVENT_WAIT_FOR_CURRENT_LOCATION_TIMEOUT,
                    WAIT_FOR_CURRENT_LOCATION_TIMEOUT_MILLIS);
        }
    }

    private void stopWaitForCurrentLocationTimer() {
        synchronized (mLock) {
            removeMessages(EVENT_WAIT_FOR_CURRENT_LOCATION_TIMEOUT);
        }
    }

    private void restartKeepOnDeviceAccessControllerResourcesTimer() {
        synchronized (mLock) {
            if (hasMessages(EVENT_KEEP_ON_DEVICE_ACCESS_CONTROLLER_RESOURCES_TIMEOUT)) {
                plogd("KeepOnDeviceAccessControllerResourcesTimer is already started. "
                        + "Restarting it...");
                removeMessages(EVENT_KEEP_ON_DEVICE_ACCESS_CONTROLLER_RESOURCES_TIMEOUT);
            }
            sendEmptyMessageDelayed(EVENT_KEEP_ON_DEVICE_ACCESS_CONTROLLER_RESOURCES_TIMEOUT,
                    KEEP_ON_DEVICE_ACCESS_CONTROLLER_RESOURCES_TIMEOUT_MILLIS);
        }
    }

    private void stopKeepOnDeviceAccessControllerResourcesTimer() {
        synchronized (mLock) {
            removeMessages(EVENT_KEEP_ON_DEVICE_ACCESS_CONTROLLER_RESOURCES_TIMEOUT);
        }
    }

    private void reportAnomaly(@NonNull String uuid, @NonNull String log) {
        ploge(log);
        AnomalyReporter.reportAnomaly(UUID.fromString(uuid), log);
    }

    private static boolean isMockModemAllowed() {
        return (DEBUG || SystemProperties.getBoolean(ALLOW_MOCK_MODEM_PROPERTY, false)
                || SystemProperties.getBoolean(BOOT_ALLOW_MOCK_MODEM_PROPERTY, false));
    }

    /**
     * Posts the specified command to be executed on the main thread and returns immediately.
     *
     * @param command  command to be executed on the main thread
     * @param argument additional parameters required to perform of the operation
     */
    private void sendDelayedRequestAsync(int command, @Nullable Object argument, long delayMillis) {
        Message msg = this.obtainMessage(command, argument);
        sendMessageDelayed(msg, delayMillis);
    }

    /**
     * Posts the specified command to be executed on the main thread and returns immediately.
     *
     * @param command  command to be executed on the main thread
     * @param argument additional parameters required to perform of the operation
     */
    private void sendRequestAsync(int command, @Nullable Object argument) {
        Message msg = this.obtainMessage(command, argument);
        msg.sendToTarget();
    }

    /**
     * Registers for the satellite communication allowed state changed.
     *
     * @param subId    The subId of the subscription to register for the satellite communication
     *                 allowed state changed.
     * @param callback The callback to handle the satellite communication allowed state changed
     *                 event.
     * @return The {@link SatelliteManager.SatelliteResult} result of the operation.
     */
    @SatelliteManager.SatelliteResult
    public int registerForCommunicationAccessStateChanged(int subId,
            @NonNull ISatelliteCommunicationAccessStateCallback callback) {
        mSatelliteCommunicationAccessStateChangedListeners.put(callback.asBinder(), callback);

        this.post(() -> {
            try {
                synchronized (mSatelliteCommunicationAllowStateLock) {
                    callback.onAccessAllowedStateChanged(mCurrentSatelliteAllowedState);
                    logd("registerForCommunicationAccessStateChanged: "
                            + "mCurrentSatelliteAllowedState " + mCurrentSatelliteAllowedState);
                }
                synchronized (mLock) {
                    SatelliteAccessConfiguration satelliteAccessConfig =
                            Optional.ofNullable(mSatelliteAccessConfigMap)
                                    .map(map -> map.get(mRegionalConfigId))
                                    .orElse(null);
                    callback.onAccessConfigurationChanged(satelliteAccessConfig);
                    logd("registerForCommunicationAccessStateChanged: satelliteAccessConfig: "
                            + satelliteAccessConfig + " of mRegionalConfigId: "
                            + mRegionalConfigId);
                }
            } catch (RemoteException ex) {
                ploge("registerForCommunicationAccessStateChanged: RemoteException ex=" + ex);
            }
        });

        return SATELLITE_RESULT_SUCCESS;
    }

    /**
     * Unregisters for the satellite communication allowed state changed.
     * If callback was not registered before, the request will be ignored.
     *
     * @param subId    The subId of the subscription to unregister for the satellite communication
     *                 allowed state changed.
     * @param callback The callback that was passed to
     *                 {@link #registerForCommunicationAccessStateChanged(int,
     *                 ISatelliteCommunicationAccessStateCallback)}.
     */
    public void unregisterForCommunicationAccessStateChanged(
            int subId, @NonNull ISatelliteCommunicationAccessStateCallback callback) {
        mSatelliteCommunicationAccessStateChangedListeners.remove(callback.asBinder());
    }

    /**
     * Returns integer array of disallowed reasons of satellite.
     *
     * @return Integer array of disallowed reasons of satellite.
     */
    @NonNull
    public List<Integer> getSatelliteDisallowedReasons() {
        if (!mFeatureFlags.carrierRoamingNbIotNtn()) {
            plogd("getSatelliteDisallowedReasons: carrierRoamingNbIotNtn is disabled");
            return new ArrayList<>();
        }

        List<Integer> satelliteDisallowedReasons = getSatelliteDisallowedReasonsCopy();
        plogd("getSatelliteDisallowedReasons: satelliteDisallowedReasons:"
                + String.join(", ", satelliteDisallowedReasons.toString()));
        return satelliteDisallowedReasons;
    }

    /**
     * Registers for disallowed reasons change event from satellite service.
     *
     * @param callback The callback to handle disallowed reasons changed event.
     */
    public void registerForSatelliteDisallowedReasonsChanged(
            @NonNull ISatelliteDisallowedReasonsCallback callback) {
        if (!mFeatureFlags.carrierRoamingNbIotNtn()) {
            plogd("registerForSatelliteDisallowedReasonsChanged: carrierRoamingNbIotNtn is "
                    + "disabled");
            return;
        }

        mSatelliteDisallowedReasonsChangedListeners.put(callback.asBinder(), callback);

        this.post(() -> {
            try {
                List<Integer> satelliteDisallowedReasons = getSatelliteDisallowedReasonsCopy();
                callback.onSatelliteDisallowedReasonsChanged(
                        satelliteDisallowedReasons.stream()
                                .mapToInt(Integer::intValue)
                                .toArray());
                logd("registerForSatelliteDisallowedReasonsChanged: "
                        + "satelliteDisallowedReasons " + satelliteDisallowedReasons.size());
            } catch (RemoteException ex) {
                ploge("registerForSatelliteDisallowedReasonsChanged: RemoteException ex=" + ex);
            }
        });
    }

    /**
     * Unregisters for disallowed reasons change event from satellite service.
     * If callback was not registered before, the request will be ignored.
     *
     * @param callback The callback that was passed to
     *                 {@link #registerForSatelliteDisallowedReasonsChanged(
     *ISatelliteDisallowedReasonsCallback)}.
     */
    public void unregisterForSatelliteDisallowedReasonsChanged(
            @NonNull ISatelliteDisallowedReasonsCallback callback) {
        if (!mFeatureFlags.carrierRoamingNbIotNtn()) {
            plogd("unregisterForSatelliteDisallowedReasonsChanged: "
                    + "carrierRoamingNbIotNtn is disabled");
            return;
        }

        mSatelliteDisallowedReasonsChangedListeners.remove(callback.asBinder());
    }

    /**
     * This API can be used by only CTS to set the cache whether satellite communication is allowed.
     *
     * @param state a state indicates whether satellite access allowed state should be cached and
     *              the allowed state.
     * @return {@code true} if the setting is successful, {@code false} otherwise.
     */
    public boolean setIsSatelliteCommunicationAllowedForCurrentLocationCache(String state) {
        if (!isMockModemAllowed()) {
            logd("setIsSatelliteCommunicationAllowedForCurrentLocationCache: "
                    + "mock modem not allowed.");
            return false;
        }

        logd("setIsSatelliteCommunicationAllowedForCurrentLocationCache: state=" + state);

        synchronized (mSatelliteCommunicationAllowStateLock) {
            if ("cache_allowed".equalsIgnoreCase(state)) {
                mLatestSatelliteCommunicationAllowedSetTime = getElapsedRealtimeNanos();
                mLatestSatelliteCommunicationAllowed = true;
                updateCurrentSatelliteAllowedState(true);
            } else if ("cache_not_allowed".equalsIgnoreCase(state)) {
                mLatestSatelliteCommunicationAllowedSetTime = getElapsedRealtimeNanos();
                mLatestSatelliteCommunicationAllowed = false;
                updateCurrentSatelliteAllowedState(false);
            } else if ("cache_clear_and_not_allowed".equalsIgnoreCase(state)) {
                mLatestSatelliteCommunicationAllowedSetTime = 0;
                mLatestSatelliteCommunicationAllowed = false;
                updateCurrentSatelliteAllowedState(false);
                persistLatestSatelliteCommunicationAllowedState();
            } else if ("clear_cache_only".equalsIgnoreCase(state)) {
                mLatestSatelliteCommunicationAllowedSetTime = 0;
                mLatestSatelliteCommunicationAllowed = false;
                persistLatestSatelliteCommunicationAllowedState();
            } else {
                loge("setIsSatelliteCommunicationAllowedForCurrentLocationCache: invalid state="
                        + state);
                return false;
            }
        }
        return true;
    }

    private void notifySatelliteCommunicationAllowedStateChanged(boolean allowState) {
        plogd("notifySatelliteCommunicationAllowedStateChanged: allowState=" + allowState);

        List<ISatelliteCommunicationAccessStateCallback> deadCallersList = new ArrayList<>();
        mSatelliteCommunicationAccessStateChangedListeners.values().forEach(listener -> {
            try {
                listener.onAccessAllowedStateChanged(allowState);
            } catch (RemoteException e) {
                plogd("handleEventNtnSignalStrengthChanged RemoteException: " + e);
                deadCallersList.add(listener);
            }
        });
        deadCallersList.forEach(listener -> {
            mSatelliteCommunicationAccessStateChangedListeners.remove(listener.asBinder());
        });
    }

    private void notifySatelliteDisallowedReasonsChanged() {
        plogd("notifySatelliteDisallowedReasonsChanged");

        List<Integer> satelliteDisallowedReasons = getSatelliteDisallowedReasonsCopy();
        List<ISatelliteDisallowedReasonsCallback> deadCallersList = new ArrayList<>();
        mSatelliteDisallowedReasonsChangedListeners.values().forEach(listener -> {
            try {
                listener.onSatelliteDisallowedReasonsChanged(
                        satelliteDisallowedReasons.stream()
                                .mapToInt(Integer::intValue)
                                .toArray());
            } catch (RemoteException e) {
                plogd("notifySatelliteDisallowedReasonsChanged RemoteException: " + e);
                deadCallersList.add(listener);
            }
        });
        deadCallersList.forEach(listener -> {
            mSatelliteDisallowedReasonsChangedListeners.remove(listener.asBinder());
        });
    }

    protected void notifyRegionalSatelliteConfigurationChanged(
            @Nullable SatelliteAccessConfiguration satelliteAccessConfig) {
        plogd("notifyRegionalSatelliteConfigurationChanged : satelliteAccessConfig is "
                + satelliteAccessConfig);

        List<ISatelliteCommunicationAccessStateCallback> deadCallersList = new ArrayList<>();
        mSatelliteCommunicationAccessStateChangedListeners.values().forEach(listener -> {
            try {
                listener.onAccessConfigurationChanged(satelliteAccessConfig);
            } catch (RemoteException e) {
                plogd("handleEventNtnSignalStrengthChanged RemoteException: " + e);
                deadCallersList.add(listener);
            }
        });
        deadCallersList.forEach(listener -> {
            mSatelliteCommunicationAccessStateChangedListeners.remove(listener.asBinder());
        });
    }

    private void reportMetrics(int resultCode, boolean allowed) {
        if (resultCode == SATELLITE_RESULT_SUCCESS) {
            mControllerMetricsStats.reportAllowedSatelliteAccessCount(allowed);
        } else {
            mControllerMetricsStats.reportFailedSatelliteAccessCheckCount();
        }

        mControllerMetricsStats.reportCurrentVersionOfSatelliteAccessConfig(
                mSatelliteAccessConfigVersion);

        mAccessControllerMetricsStats
                .setLocationQueryTime(mLocationQueryStartTimeMillis)
                .setTotalCheckingTime(mTotalCheckingStartTimeMillis)
                .setIsAllowed(allowed)
                .setIsEmergency(isInEmergency())
                .setResult(resultCode)
                .setCarrierId(mSatelliteController.getSatelliteCarrierId())
                .setIsNtnOnlyCarrier(mSatelliteController.isNtnOnlyCarrier())
                .reportAccessControllerMetrics();
        mLocationQueryStartTimeMillis = 0;
        mOnDeviceLookupStartTimeMillis = 0;
        mTotalCheckingStartTimeMillis = 0;
    }

    protected boolean isSatelliteAllowedRegionPossiblyChanged() {
        synchronized (mPossibleChangeInSatelliteAllowedRegionLock) {
            return mIsSatelliteAllowedRegionPossiblyChanged;
        }
    }

    protected void setIsSatelliteAllowedRegionPossiblyChanged(boolean changed) {
        synchronized (mPossibleChangeInSatelliteAllowedRegionLock) {
            plogd("setIsSatelliteAllowedRegionPossiblyChanged : " + changed);
            mIsSatelliteAllowedRegionPossiblyChanged = changed;
        }
    }

    private static void logd(@NonNull String log) {
        Log.d(TAG, log);
    }

    private static void logw(@NonNull String log) {
        Log.w(TAG, log);
    }

    protected static void loge(@NonNull String log) {
        Log.e(TAG, log);
    }

    private static void logv(@NonNull String log) {
        Log.v(TAG, log);
    }

    /**
     * This API can be used only for test purpose to override the carrier roaming Ntn eligibility
     *
     * @param state         to update Ntn Eligibility.
     * @param resetRequired to reset the overridden flag in satellite controller.
     * @return {@code true} if the shell command is successful, {@code false} otherwise.
     */
    public boolean overrideCarrierRoamingNtnEligibilityChanged(boolean state,
            boolean resetRequired) {
        if (!mFeatureFlags.carrierRoamingNbIotNtn()) {
            logd("overrideCarrierRoamingNtnEligibilityChanged: "
                    + "carrierRoamingNbIotNtn is disabled");
            return false;
        }

        if (!isMockModemAllowed()) {
            logd("overrideCarrierRoamingNtnEligibilityChanged: "
                    + "mock modem not allowed.");
            return false;
        }

        logd("calling overrideCarrierRoamingNtnEligibilityChanged");
        return mSatelliteController.overrideCarrierRoamingNtnEligibilityChanged(state,
                resetRequired);
    }

    private static final class SatelliteRegionalConfig {
        /** Regional satellite config IDs */
        private final int mConfigId;

        /** Set of earfcns in the corresponding regions */
        private final Set<Integer> mEarfcns;

        SatelliteRegionalConfig(int configId, Set<Integer> earfcns) {
            this.mConfigId = configId;
            this.mEarfcns = earfcns;
        }

        public Set<Integer> getEarfcns() {
            return mEarfcns;
        }
    }

    private void updateSatelliteRegionalConfig(int subId) {
        plogd("updateSatelliteRegionalConfig: subId: " + subId);
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            return;
        }

        mSatelliteController.updateRegionalSatelliteEarfcns(subId);
        //key: regional satellite config Id,
        //value: set of earfcns in the corresponding regions
        Map<String, Set<Integer>> earfcnsMap = mSatelliteController
                .getRegionalSatelliteEarfcns(subId);
        if (earfcnsMap.isEmpty()) {
            plogd("updateSatelliteRegionalConfig: Earfcns are not found for subId: "
                    + subId);
            return;
        }

        synchronized (mRegionalSatelliteEarfcnsLock) {
            SatelliteRegionalConfig satelliteRegionalConfig;
            /* Key: Regional satellite config ID, Value: SatelliteRegionalConfig
             * contains satellite config IDs and set of earfcns in the corresponding regions.
             */
            Map<Integer, SatelliteRegionalConfig> satelliteRegionalConfigMap = new HashMap<>();
            for (String configId: earfcnsMap.keySet()) {
                Set<Integer> earfcnsSet = new HashSet<>();
                for (int earfcn : earfcnsMap.get(configId)) {
                    earfcnsSet.add(earfcn);
                }
                satelliteRegionalConfig = new SatelliteRegionalConfig(Integer.valueOf(configId),
                        earfcnsSet);
                satelliteRegionalConfigMap.put(Integer.valueOf(configId), satelliteRegionalConfig);
            }

            mSatelliteRegionalConfigPerSubMap.put(subId, satelliteRegionalConfigMap);
        }
    }

    private void handleCarrierConfigChanged(@NonNull Context context, int slotIndex,
            int subId, int carrierId, int specificCarrierId) {
        if (!mFeatureFlags.carrierRoamingNbIotNtn()) {
            plogd("handleCarrierConfigChanged: carrierRoamingNbIotNtn flag is disabled");
            return;
        }
        plogd("handleCarrierConfigChanged: slotIndex=" + slotIndex + ", subId=" + subId
                + ", carrierId=" + carrierId + ", specificCarrierId=" + specificCarrierId);
        updateSatelliteRegionalConfig(subId);
        evaluatePossibleChangeInDefaultSmsApp(context);
    }

    @Nullable
    private Integer getSelectedRegionalConfigId() {
        synchronized (mLock) {
            return mRegionalConfigId;
        }
    }

    private boolean isReasonPresentInSatelliteDisallowedReasons(int disallowedReason) {
        synchronized (mSatelliteDisallowedReasonsLock) {
            return mSatelliteDisallowedReasons.contains(Integer.valueOf(disallowedReason));
        }
    }

    private void addReasonToSatelliteDisallowedReasons(int disallowedReason) {
        synchronized (mSatelliteDisallowedReasonsLock) {
            mSatelliteDisallowedReasons.add(Integer.valueOf(disallowedReason));
        }
    }

    private void removeReasonFromSatelliteDisallowedReasons(int disallowedReason) {
        synchronized (mSatelliteDisallowedReasonsLock) {
            mSatelliteDisallowedReasons.remove(Integer.valueOf(disallowedReason));
        }
    }

    private boolean isSatelliteDisallowedReasonsEmpty() {
        synchronized (mSatelliteDisallowedReasonsLock) {
            return mSatelliteDisallowedReasons.isEmpty();
        }
    }

    private void removeAllReasonsFromSatelliteDisallowedReasons(
            List<Integer> disallowedReasonsList) {
        synchronized (mSatelliteDisallowedReasonsLock) {
            mSatelliteDisallowedReasons.removeAll(disallowedReasonsList);
        }
    }

    private List<Integer> getSatelliteDisallowedReasonsCopy() {
        List<Integer> satelliteDisallowedReasons;
        synchronized (mSatelliteDisallowedReasonsLock) {
            satelliteDisallowedReasons = new ArrayList<>(mSatelliteDisallowedReasons);
        }
        return satelliteDisallowedReasons;
    }

    /**
     * Returns the satellite access configuration version.
     *
     * If the satellite config data hasn't been updated by configUpdater,
     * it returns 0. If it has been updated, it returns the updated version information.
     */
    @NonNull
    public int getSatelliteAccessConfigVersion() {
        return mSatelliteAccessConfigVersion;
    }

    private void plogv(@NonNull String log) {
        Log.v(TAG, log);
        if (mPersistentLogger != null) {
            mPersistentLogger.debug(TAG, log);
        }
    }

    private void plogd(@NonNull String log) {
        Log.d(TAG, log);
        if (mPersistentLogger != null) {
            mPersistentLogger.debug(TAG, log);
        }
    }

    private void plogw(@NonNull String log) {
        Log.w(TAG, log);
        if (mPersistentLogger != null) {
            mPersistentLogger.warn(TAG, log);
        }
    }

    private void ploge(@NonNull String log) {
        Log.e(TAG, log);
        if (mPersistentLogger != null) {
            mPersistentLogger.error(TAG, log);
        }
    }
}
