/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.phone.settings;

import static android.net.ConnectivityManager.NetworkCallback;
import static android.telephony.CarrierConfigManager.KEY_CARRIER_ROAMING_SATELLITE_DEFAULT_SERVICES_INT_ARRAY;
import static android.telephony.CarrierConfigManager.KEY_CARRIER_SUPPORTED_SATELLITE_SERVICES_PER_PROVIDER_BUNDLE;
import static android.telephony.CarrierConfigManager.KEY_SATELLITE_ATTACH_SUPPORTED_BOOL;
import static android.telephony.CarrierConfigManager.KEY_SATELLITE_DATA_SUPPORT_MODE_INT;
import static android.telephony.CarrierConfigManager.KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL;
import static android.telephony.ims.feature.MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VIDEO;
import static android.telephony.ims.feature.MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VOICE;
import static android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN;
import static android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_LTE;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.annotation.NonNull;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.HandlerThread;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.telephony.AccessNetworkConstants;
import android.telephony.CarrierConfigManager;
import android.telephony.CellInfo;
import android.telephony.DataSpecificRegistrationInfo;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.PhysicalChannelConfig;
import android.telephony.RadioAccessFamily;
import android.telephony.RadioAccessSpecifier;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;
import android.telephony.data.NetworkSlicingConfig;
import android.telephony.euicc.EuiccManager;
import android.telephony.ims.ImsException;
import android.telephony.ims.ImsManager;
import android.telephony.ims.ImsMmTelManager;
import android.telephony.ims.ImsRcsManager;
import android.telephony.ims.ProvisioningManager;
import android.telephony.ims.feature.MmTelFeature;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AlertDialog.Builder;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.euicc.EuiccConnector;
import com.android.internal.telephony.satellite.SatelliteConfig;
import com.android.internal.telephony.satellite.SatelliteConfigParser;
import com.android.internal.telephony.satellite.SatelliteController;
import com.android.phone.R;
import com.android.phone.settings.hiddenmenu.PhoneInformationUtil;
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Radio Information Class
 *
 * <p>Allows user to read and alter some of the radio related information.
 */
public class RadioInfo extends CollapsingToolbarBaseActivity {
    private static final String TAG = "RadioInfo";

    private static final boolean IS_USER_BUILD = "user".equals(Build.TYPE);

    private static final Integer[] BAND_VALUES =
            new Integer[] {
                -1,
                AccessNetworkConstants.EutranBand.BAND_1,
                AccessNetworkConstants.EutranBand.BAND_2,
                AccessNetworkConstants.EutranBand.BAND_3,
                AccessNetworkConstants.EutranBand.BAND_4,
                AccessNetworkConstants.EutranBand.BAND_5,
                AccessNetworkConstants.EutranBand.BAND_6,
                AccessNetworkConstants.EutranBand.BAND_7,
                AccessNetworkConstants.EutranBand.BAND_8,
                AccessNetworkConstants.EutranBand.BAND_9,
                AccessNetworkConstants.EutranBand.BAND_10,
                AccessNetworkConstants.EutranBand.BAND_11,
                AccessNetworkConstants.EutranBand.BAND_12,
                AccessNetworkConstants.EutranBand.BAND_13,
                AccessNetworkConstants.EutranBand.BAND_14,
                AccessNetworkConstants.EutranBand.BAND_17,
                AccessNetworkConstants.EutranBand.BAND_18,
                AccessNetworkConstants.EutranBand.BAND_19,
                AccessNetworkConstants.EutranBand.BAND_20,
                AccessNetworkConstants.EutranBand.BAND_21,
                AccessNetworkConstants.EutranBand.BAND_22,
                AccessNetworkConstants.EutranBand.BAND_23,
                AccessNetworkConstants.EutranBand.BAND_24,
                AccessNetworkConstants.EutranBand.BAND_25,
                AccessNetworkConstants.EutranBand.BAND_26,
                AccessNetworkConstants.EutranBand.BAND_27,
                AccessNetworkConstants.EutranBand.BAND_28,
                AccessNetworkConstants.EutranBand.BAND_30,
                AccessNetworkConstants.EutranBand.BAND_31,
                AccessNetworkConstants.EutranBand.BAND_33,
                AccessNetworkConstants.EutranBand.BAND_34,
                AccessNetworkConstants.EutranBand.BAND_35,
                AccessNetworkConstants.EutranBand.BAND_36,
                AccessNetworkConstants.EutranBand.BAND_37,
                AccessNetworkConstants.EutranBand.BAND_38,
                AccessNetworkConstants.EutranBand.BAND_39,
                AccessNetworkConstants.EutranBand.BAND_40,
                AccessNetworkConstants.EutranBand.BAND_41,
                AccessNetworkConstants.EutranBand.BAND_42,
                AccessNetworkConstants.EutranBand.BAND_43,
                AccessNetworkConstants.EutranBand.BAND_44,
                AccessNetworkConstants.EutranBand.BAND_45,
                AccessNetworkConstants.EutranBand.BAND_46,
                AccessNetworkConstants.EutranBand.BAND_47,
                AccessNetworkConstants.EutranBand.BAND_48,
                AccessNetworkConstants.EutranBand.BAND_49,
                AccessNetworkConstants.EutranBand.BAND_50,
                AccessNetworkConstants.EutranBand.BAND_51,
                AccessNetworkConstants.EutranBand.BAND_52,
                AccessNetworkConstants.EutranBand.BAND_53,
                AccessNetworkConstants.EutranBand.BAND_65,
                AccessNetworkConstants.EutranBand.BAND_66,
                AccessNetworkConstants.EutranBand.BAND_68,
                AccessNetworkConstants.EutranBand.BAND_70,
                AccessNetworkConstants.EutranBand.BAND_71,
                AccessNetworkConstants.EutranBand.BAND_72,
                AccessNetworkConstants.EutranBand.BAND_73,
                AccessNetworkConstants.EutranBand.BAND_74,
                AccessNetworkConstants.EutranBand.BAND_85,
                AccessNetworkConstants.EutranBand.BAND_87,
                AccessNetworkConstants.EutranBand.BAND_88
            };

    private static final String[] BAND_LABELS = {
        "SELECT", "BAND_1", "BAND_2", "BAND_3", "BAND_4", "BAND_5", "BAND_6", "BAND_7", "BAND_8",
        "BAND_9", "BAND_10", "BAND_11", "BAND_12", "BAND_13", "BAND_14", "BAND_17", "BAND_18",
        "BAND_19", "BAND_20", "BAND_21", "BAND_22", "BAND_23", "BAND_24", "BAND_25", "BAND_26",
        "BAND_27", "BAND_28", "BAND_30", "BAND_31", "BAND_33", "BAND_34", "BAND_35", "BAND_36",
        "BAND_37", "BAND_38", "BAND_39", "BAND_40", "BAND_41", "BAND_42", "BAND_43", "BAND_44",
        "BAND_45", "BAND_46", "BAND_47", "BAND_48", "BAND_49", "BAND_50", "BAND_51", "BAND_52",
        "BAND_53", "BAND_65", "BAND_66", "BAND_68", "BAND_70", "BAND_71", "BAND_72", "BAND_73",
        "BAND_74", "BAND_85", "BAND_87", "BAND_88"
    };

    private static String[] sPhoneIndexLabels = new String[0];

    private static final int sCellInfoListRateDisabled = Integer.MAX_VALUE;
    private static final int sCellInfoListRateMax = 0;

    private static final String OEM_RADIO_INFO_INTENT = "com.android.phone.settings.OEM_RADIO_INFO";

    private static final String DSDS_MODE_PROPERTY = "ro.boot.hardware.dsds";

    /**
     * A value indicates the device is always on dsds mode.
     *
     * @see {@link #DSDS_MODE_PROPERTY}
     */
    private static final int ALWAYS_ON_DSDS_MODE = 1;

    // Values in must match CELL_INFO_REFRESH_RATES
    private static final String[] CELL_INFO_REFRESH_RATE_LABELS = {
        "Disabled", "Immediate", "Min 5s", "Min 10s", "Min 60s"
    };

    // Values in seconds, must match CELL_INFO_REFRESH_RATE_LABELS
    private static final int[] CELL_INFO_REFRESH_RATES = {
        sCellInfoListRateDisabled, sCellInfoListRateMax, 5000, 10000, 60000
    };

    private static void log(String s) {
        Log.d(TAG, s);
    }

    private static void loge(String s) {
        Log.e(TAG, s);
    }

    private static final int EVENT_QUERY_SMSC_DONE = 1005;
    private static final int EVENT_UPDATE_SMSC_DONE = 1006;
    private static final int EVENT_UPDATE_NR_STATS = 1008;

    private static final int MENU_ITEM_VIEW_ADN = 1;
    private static final int MENU_ITEM_VIEW_FDN = 2;
    private static final int MENU_ITEM_VIEW_SDN = 3;
    private static final int MENU_ITEM_GET_IMS_STATUS = 4;
    private static final int MENU_ITEM_TOGGLE_DATA = 5;

    private static final String CARRIER_PROVISIONING_ACTION =
            "com.android.phone.settings.CARRIER_PROVISIONING";
    private static final String TRIGGER_CARRIER_PROVISIONING_ACTION =
            "com.android.phone.settings.TRIGGER_CARRIER_PROVISIONING";

    private static final String ACTION_REMOVABLE_ESIM_AS_DEFAULT =
            "android.telephony.euicc.action.REMOVABLE_ESIM_AS_DEFAULT";

    private Context mContext;
    private TextView mDeviceId; // DeviceId is the IMEI in GSM and the MEID in CDMA
    private TextView mLine1Number;
    private TextView mSubscriptionId;
    private TextView mDds;
    private TextView mSubscriberId;
    private TextView mCallState;
    private TextView mOperatorName;
    private TextView mRoamingState;
    private TextView mGsmState;
    private TextView mGprsState;
    private TextView mVoiceNetwork;
    private TextView mDataNetwork;
    private TextView mVoiceRawReg;
    private TextView mDataRawReg;
    private TextView mWlanDataRawReg;
    private TextView mOverrideNetwork;
    private TextView mDBm;
    private TextView mMwi;
    private TextView mCfi;
    private TextView mCellInfo;
    private TextView mSent;
    private TextView mReceived;
    private TextView mPingHostnameV4;
    private TextView mPingHostnameV6;
    private TextView mHttpClientTest;
    private TextView mPhyChanConfig;
    private TextView mDownlinkKbps;
    private TextView mUplinkKbps;
    private TextView mEndcAvailable;
    private TextView mDcnrRestricted;
    private TextView mNrAvailable;
    private TextView mNrState;
    private TextView mNrFrequency;
    private TextView mNetworkSlicingConfig;
    private TextView mEuiccInfo;
    private EditText mSmsc;
    private EditText mSatelliteChannels;
    private Switch mRadioPowerOnSwitch;
    private Switch mSimulateOutOfServiceSwitch;
    private Switch mEnforceSatelliteChannel;
    private RadioGroup mForceCampSatelliteSelectionRadioGroup;
    private Spinner mManualOverrideBand;
    private Switch mMockSatellite;
    private Switch mMockSatelliteDataSwitch;
    private RadioGroup mMockSatelliteData;
    private Button mPingTestButton;
    private Button mUpdateSmscButton;
    private Button mRefreshSmscButton;
    private Button mForceCampSatelliteButton;
    private Button mOemInfoButton;
    private Button mCarrierProvisioningButton;
    private Button mTriggerCarrierProvisioningButton;
    private Button mEsosButton;
    private Button mSatelliteEnableNonEmergencyModeButton;
    private Button mEsosDemoButton;
    private Button mSatelliteConfigViewerButton;
    private Switch mImsVolteProvisionedSwitch;
    private Switch mImsVtProvisionedSwitch;
    private Switch mImsWfcProvisionedSwitch;
    private Switch mEabProvisionedSwitch;
    private Switch mCbrsDataSwitch;
    private Switch mDsdsSwitch;
    private Switch mRemovableEsimSwitch;
    private Spinner mPreferredNetworkType;
    private Spinner mMockSignalStrength;
    private Spinner mMockDataNetworkType;

    private Spinner mSelectPhoneIndex;
    private Spinner mCellInfoRefreshRateSpinner;

    private static final long RUNNABLE_TIMEOUT_MS = 5 * 60 * 1000L;

    private ThreadPoolExecutor mQueuedWork;

    private ConnectivityManager mConnectivityManager;
    private TelephonyManager mTelephonyManager;
    private ImsManager mImsManager = null;
    private Phone mPhone = null;
    private ProvisioningManager mProvisioningManager = null;
    private EuiccManager mEuiccManager;

    private String mPingHostnameResultV4;
    private String mPingHostnameResultV6;
    private String mHttpClientTestResult;
    private boolean mMwiValue = false;
    private boolean mCfiValue = false;

    private boolean mSystemUser = true;

    private final PersistableBundle[] mCarrierSatelliteOriginalBundle = new PersistableBundle[2];
    private final PersistableBundle[] mSatelliteDataOriginalBundle = new PersistableBundle[2];
    private final PersistableBundle[] mOriginalSystemChannels = new PersistableBundle[2];
    private final PersistableBundle[] mPreviousSatelliteBand = new PersistableBundle[2];
    private static final String KEY_SATELLITE_BANDS = "force_camp_satellite_bands";
    private static final String KEY_FORCE_CAMP_SATELLITE_BAND_SELECTED =
            "force_camp_satellite_band_selected";
    private static final String KEY_SATELLITE_CHANNELS = "force_camp_satellite_channels";
    private List<CellInfo> mCellInfoResult = null;
    private final boolean[] mSimulateOos = new boolean[2];
    private int[] mSelectedSignalStrengthIndex = new int[2];
    private int[] mSelectedMockDataNetworkTypeIndex = new int[2];
    private int[] mSelectedManualOverrideBandIndex = new int[2];

    private String mEuiccInfoResult = "";

    private int mPreferredNetworkTypeResult;
    private int mCellInfoRefreshRateIndex;
    private int mPhoneId = SubscriptionManager.INVALID_PHONE_INDEX;
    private static final int DEFAULT_PHONE_ID = 0;

    private int mSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;

    private String mActionEsos;
    private String mActionEsosDemo;
    private TelephonyDisplayInfo mDisplayInfo;

    private SatelliteConfigParser mBackedUpSatelliteConfigParser;
    private SatelliteConfig mBackedUpSatelliteConfig;

    private List<PhysicalChannelConfig> mPhysicalChannelConfigs = new ArrayList<>();

    private final NetworkRequest mDefaultNetworkRequest =
            new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();

    private final NetworkCallback mNetworkCallback =
            new NetworkCallback() {
                public void onCapabilitiesChanged(Network n, NetworkCapabilities nc) {
                    int dlbw = nc.getLinkDownstreamBandwidthKbps();
                    int ulbw = nc.getLinkUpstreamBandwidthKbps();
                    updateBandwidths(dlbw, ulbw);
                }
            };

    private static final int DEFAULT_TIMEOUT_MS = 1000;

    // not final because we need to recreate this object to register on a new subId (b/117555407)
    private TelephonyCallback mTelephonyCallback = new RadioInfoTelephonyCallback();

    private class RadioInfoTelephonyCallback extends TelephonyCallback
            implements TelephonyCallback.DataConnectionStateListener,
                    TelephonyCallback.DataActivityListener,
                    TelephonyCallback.CallStateListener,
                    TelephonyCallback.MessageWaitingIndicatorListener,
                    TelephonyCallback.CallForwardingIndicatorListener,
                    TelephonyCallback.CellInfoListener,
                    TelephonyCallback.SignalStrengthsListener,
                    TelephonyCallback.ServiceStateListener,
                    TelephonyCallback.PhysicalChannelConfigListener,
                    TelephonyCallback.DisplayInfoListener {

        @Override
        public void onDataConnectionStateChanged(int state, int networkType) {
            updateDataState();
            updateNetworkType();
        }

        @Override
        public void onDataActivity(int direction) {
            updateDataStats2();
        }

        @Override
        public void onCallStateChanged(int state) {
            updateNetworkType();
            updatePhoneState(state);
        }

        @Override
        public void onMessageWaitingIndicatorChanged(boolean mwi) {
            mMwiValue = mwi;
            updateMessageWaiting();
        }

        @Override
        public void onCallForwardingIndicatorChanged(boolean cfi) {
            mCfiValue = cfi;
            updateCallRedirect();
        }

        @Override
        public void onCellInfoChanged(List<CellInfo> arrayCi) {
            log("onCellInfoChanged: arrayCi=" + arrayCi);
            mCellInfoResult = arrayCi;
            updateCellInfo(mCellInfoResult);
        }

        @Override
        public void onSignalStrengthsChanged(SignalStrength signalStrength) {
            log("onSignalStrengthChanged: SignalStrength=" + signalStrength);
            updateSignalStrength(signalStrength);
        }

        @Override
        public void onServiceStateChanged(ServiceState serviceState) {
            log("onServiceStateChanged: ServiceState=" + serviceState);
            updateServiceState(serviceState);
            updateRadioPowerState();
            updateNetworkType();
            updateRawRegistrationState(serviceState);
            updateImsProvisionedState();

            // Since update NR stats includes a ril message to get slicing information, it runs
            // as blocking during the timeout period of 1 second. if ServiceStateChanged event
            // fires consecutively, RadioInfo can run for more than 10 seconds. This can cause ANR.
            // Therefore, send event only when there is no same event being processed.
            if (!mHandler.hasMessages(EVENT_UPDATE_NR_STATS)) {
                mHandler.obtainMessage(EVENT_UPDATE_NR_STATS).sendToTarget();
            }
        }

        @Override
        public void onDisplayInfoChanged(TelephonyDisplayInfo displayInfo) {
            mDisplayInfo = displayInfo;
            updateNetworkType();
        }

        @Override
        public void onPhysicalChannelConfigChanged(@NonNull List<PhysicalChannelConfig> configs) {
            updatePhysicalChannelConfiguration(configs);
        }
    }

    private void updatePhysicalChannelConfiguration(List<PhysicalChannelConfig> configs) {
        mPhysicalChannelConfigs = configs;
        StringBuilder sb = new StringBuilder();
        String div = "";
        sb.append("{");
        if (mPhysicalChannelConfigs != null) {
            for (PhysicalChannelConfig c : mPhysicalChannelConfigs) {
                sb.append(div).append(c);
                div = ",";
            }
        }
        sb.append("}");
        mPhyChanConfig.setText(sb.toString());
    }

    private void updatePreferredNetworkType(int type) {
        if (type >= PhoneInformationUtil.PREFERRED_NETWORK_LABELS.length || type < 0) {
            log("Network type: unknown type value=" + type);
            type = PhoneInformationUtil.PREFERRED_NETWORK_LABELS.length - 1; // set to Unknown
        }
        mPreferredNetworkTypeResult = type;

        mPreferredNetworkType.setSelection(mPreferredNetworkTypeResult, true);
    }

    private void updatePhoneIndex() {
        // unregister listeners on the old subId
        unregisterPhoneStateListener();
        mTelephonyManager.setCellInfoListRate(sCellInfoListRateDisabled, mSubId);

        // update the subId
        mTelephonyManager = mTelephonyManager.createForSubscriptionId(mSubId);

        // update the phoneId
        if (mSystemUser) {
            mPhone = PhoneFactory.getPhone(mPhoneId);
        }
        mImsManager = new ImsManager(this);
        try {
            mProvisioningManager = ProvisioningManager.createForSubscriptionId(mSubId);
        } catch (IllegalArgumentException e) {
            log("updatePhoneIndex : IllegalArgumentException " + e.getMessage());
            mProvisioningManager = null;
        }

        updateAllFields();
    }

    private Handler mHandler =
            new Handler() {
                @Override
                public void handleMessage(Message msg) {
                    switch (msg.what) {
                        case EVENT_QUERY_SMSC_DONE -> {
                            AsyncResult ar = (AsyncResult) msg.obj;
                            if (ar.exception != null) {
                                mSmsc.setText("refresh error");
                            } else {
                                mSmsc.setText((String) ar.result);
                            }
                        }
                        case EVENT_UPDATE_SMSC_DONE -> {
                            mUpdateSmscButton.setEnabled(true);
                            AsyncResult ar = (AsyncResult) msg.obj;
                            if (ar.exception != null) {
                                mSmsc.setText("update error");
                            }
                        }
                        case EVENT_UPDATE_NR_STATS -> {
                            log("got EVENT_UPDATE_NR_STATS");
                            updateNrStats();
                        }
                        default ->
                                super.handleMessage(msg);
                    }
                }
            };

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        mContext = this;
        SettingsConstants.setupEdgeToEdge(this);
        int currentNightMode = getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        if (currentNightMode == android.content.res.Configuration.UI_MODE_NIGHT_NO) {
            // Light mode is active
            View decorView = getWindow().getDecorView();
            decorView.setSystemUiVisibility(decorView.getSystemUiVisibility()
                    | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        mSystemUser = android.os.Process.myUserHandle().isSystem();
        log("onCreate: mSystemUser=" + mSystemUser);
        UserManager userManager = getSystemService(UserManager.class);
        if (userManager != null
                && userManager.hasUserRestriction(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS)) {
            Log.w(TAG, "User is restricted from configuring mobile networks.");
            finish();
            return;
        }

        setContentView(R.layout.radio_info);
        Resources r = getResources();
        mActionEsos =
                r.getString(
                        com.android.internal.R.string
                                .config_satellite_test_with_esp_replies_intent_action);

        mActionEsosDemo =
                r.getString(
                        com.android.internal.R.string.config_satellite_demo_mode_sos_intent_action);

        mQueuedWork = new ThreadPoolExecutor(1, 1, RUNNABLE_TIMEOUT_MS,
                TimeUnit.MICROSECONDS, new LinkedBlockingDeque<>());
        mConnectivityManager = getSystemService(ConnectivityManager.class);
        if (mSystemUser) {
            mPhone = PhoneInformationUtil.getPhone(SubscriptionManager.getDefaultSubscriptionId());
        }
        mSubId = SubscriptionManager.getDefaultSubscriptionId();
        if (mPhone != null) {
            mPhoneId = mPhone.getPhoneId();
        } else {
            mPhoneId = SubscriptionManager.getPhoneId(mSubId);
        }
        if (!SubscriptionManager.isValidPhoneId(mPhoneId)) {
            mPhoneId = DEFAULT_PHONE_ID;
        }

        mTelephonyManager = getSystemService(TelephonyManager.class)
                .createForSubscriptionId(mSubId);
        mEuiccManager = getSystemService(EuiccManager.class);

        mImsManager = new ImsManager(this);
        try {
            mProvisioningManager = ProvisioningManager.createForSubscriptionId(mSubId);
        } catch (IllegalArgumentException e) {
            log("onCreate : IllegalArgumentException " + e.getMessage());
            mProvisioningManager = null;
        }

        sPhoneIndexLabels = PhoneInformationUtil.getPhoneIndexLabels(mTelephonyManager);

        mDeviceId = (TextView) findViewById(R.id.imei);
        mLine1Number = (TextView) findViewById(R.id.number);
        mSubscriptionId = (TextView) findViewById(R.id.subid);
        mDds = (TextView) findViewById(R.id.dds);
        mSubscriberId = (TextView) findViewById(R.id.imsi);
        mCallState = (TextView) findViewById(R.id.call);
        mOperatorName = (TextView) findViewById(R.id.operator);
        mRoamingState = (TextView) findViewById(R.id.roaming);
        mGsmState = (TextView) findViewById(R.id.gsm);
        mGprsState = (TextView) findViewById(R.id.gprs);
        mVoiceNetwork = (TextView) findViewById(R.id.voice_network);
        mDataNetwork = (TextView) findViewById(R.id.data_network);
        mVoiceRawReg = (TextView) findViewById(R.id.voice_raw_registration_state);
        mDataRawReg = (TextView) findViewById(R.id.data_raw_registration_state);
        mWlanDataRawReg = (TextView) findViewById(R.id.wlan_data_raw_registration_state);
        mOverrideNetwork = (TextView) findViewById(R.id.override_network);
        mDBm = (TextView) findViewById(R.id.dbm);
        mMwi = (TextView) findViewById(R.id.mwi);
        mCfi = (TextView) findViewById(R.id.cfi);
        mCellInfo = (TextView) findViewById(R.id.cellinfo);
        mCellInfo.setTypeface(Typeface.MONOSPACE);

        mSent = (TextView) findViewById(R.id.sent);
        mReceived = (TextView) findViewById(R.id.received);
        mSmsc = (EditText) findViewById(R.id.smsc);
        mSatelliteChannels = (EditText) findViewById(R.id.satellite_channel_number);
        mPingHostnameV4 = (TextView) findViewById(R.id.pingHostnameV4);
        mPingHostnameV6 = (TextView) findViewById(R.id.pingHostnameV6);
        mHttpClientTest = (TextView) findViewById(R.id.httpClientTest);
        mEndcAvailable = (TextView) findViewById(R.id.endc_available);
        mDcnrRestricted = (TextView) findViewById(R.id.dcnr_restricted);
        mNrAvailable = (TextView) findViewById(R.id.nr_available);
        mNrState = (TextView) findViewById(R.id.nr_state);
        mNrFrequency = (TextView) findViewById(R.id.nr_frequency);
        mPhyChanConfig = (TextView) findViewById(R.id.phy_chan_config);
        mNetworkSlicingConfig = (TextView) findViewById(R.id.network_slicing_config);
        mEuiccInfo = (TextView) findViewById(R.id.euicc_info);

        // hide 5G stats on devices that don't support 5G
        if ((mTelephonyManager.getSupportedRadioAccessFamily()
                & TelephonyManager.NETWORK_TYPE_BITMASK_NR) == 0) {
            setNrStatsVisibility(View.GONE);
        }

        mPreferredNetworkType = (Spinner) findViewById(R.id.preferredNetworkType);
        ArrayAdapter<String> mPreferredNetworkTypeAdapter =
                new ArrayAdapter<String>(
                        this,
                        android.R.layout.simple_spinner_item,
                        PhoneInformationUtil.PREFERRED_NETWORK_LABELS);
        mPreferredNetworkTypeAdapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        mPreferredNetworkType.setAdapter(mPreferredNetworkTypeAdapter);

        mMockSignalStrength = (Spinner) findViewById(R.id.signalStrength);
        if (!Build.isDebuggable() || !mSystemUser) {
            mMockSignalStrength.setVisibility(View.GONE);
            findViewById(R.id.signalStrength).setVisibility(View.GONE);
            findViewById(R.id.signal_strength_label).setVisibility(View.GONE);
        } else {
            ArrayAdapter<Integer> mSignalStrengthAdapter =
                    new ArrayAdapter<>(
                            this,
                            android.R.layout.simple_spinner_item,
                            PhoneInformationUtil.SIGNAL_STRENGTH_LEVEL);
            mSignalStrengthAdapter.setDropDownViewResource(
                    android.R.layout.simple_spinner_dropdown_item);
            mMockSignalStrength.setAdapter(mSignalStrengthAdapter);
        }

        mMockDataNetworkType = (Spinner) findViewById(R.id.dataNetworkType);
        if (!Build.isDebuggable() || !mSystemUser) {
            mMockDataNetworkType.setVisibility(View.GONE);
            findViewById(R.id.dataNetworkType).setVisibility(View.GONE);
            findViewById(R.id.data_network_type_label).setVisibility(View.GONE);
        } else {
            ArrayAdapter<String> mNetworkTypeAdapter =
                    new ArrayAdapter<>(
                            this,
                            android.R.layout.simple_spinner_item,
                            Arrays.stream(PhoneInformationUtil.MOCK_DATA_NETWORK_TYPE)
                                    .map(ServiceState::rilRadioTechnologyToString)
                                    .toArray(String[]::new));
            mNetworkTypeAdapter.setDropDownViewResource(
                    android.R.layout.simple_spinner_dropdown_item);
            mMockDataNetworkType.setAdapter(mNetworkTypeAdapter);
        }

        mSelectPhoneIndex = (Spinner) findViewById(R.id.phoneIndex);
        ArrayAdapter<String> phoneIndexAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, sPhoneIndexLabels);
        phoneIndexAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mSelectPhoneIndex.setAdapter(phoneIndexAdapter);

        mCellInfoRefreshRateSpinner = (Spinner) findViewById(R.id.cell_info_rate_select);
        ArrayAdapter<String> cellInfoAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, CELL_INFO_REFRESH_RATE_LABELS);
        cellInfoAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mCellInfoRefreshRateSpinner.setAdapter(cellInfoAdapter);

        mImsVolteProvisionedSwitch = (Switch) findViewById(R.id.volte_provisioned_switch);
        mImsVtProvisionedSwitch = (Switch) findViewById(R.id.vt_provisioned_switch);
        mImsWfcProvisionedSwitch = (Switch) findViewById(R.id.wfc_provisioned_switch);
        mEabProvisionedSwitch = (Switch) findViewById(R.id.eab_provisioned_switch);

        if (!isImsSupportedOnDevice()) {
            mImsVolteProvisionedSwitch.setVisibility(View.GONE);
            mImsVtProvisionedSwitch.setVisibility(View.GONE);
            mImsWfcProvisionedSwitch.setVisibility(View.GONE);
            mEabProvisionedSwitch.setVisibility(View.GONE);
        }

        mCbrsDataSwitch = (Switch) findViewById(R.id.cbrs_data_switch);
        mCbrsDataSwitch.setVisibility(isCbrsSupported() ? View.VISIBLE : View.GONE);

        mDsdsSwitch = findViewById(R.id.dsds_switch);
        if (PhoneInformationUtil.isDsdsSupported() && !PhoneInformationUtil.dsdsModeOnly()) {
            mDsdsSwitch.setVisibility(View.VISIBLE);
            mDsdsSwitch.setOnClickListener(v -> {
                if (mTelephonyManager.doesSwitchMultiSimConfigTriggerReboot()) {
                    // Undo the click action until user clicks the confirm dialog.
                    mDsdsSwitch.toggle();
                    showDsdsChangeDialog();
                } else {
                    performDsdsSwitch();
                }
            });
            mDsdsSwitch.setChecked(PhoneInformationUtil.isDsdsEnabled());
        } else {
            mDsdsSwitch.setVisibility(View.GONE);
        }

        mRemovableEsimSwitch = (Switch) findViewById(R.id.removable_esim_switch);
        if (!IS_USER_BUILD) {
            mRemovableEsimSwitch.setEnabled(true);
            mRemovableEsimSwitch.setChecked(mTelephonyManager.isRemovableEsimDefaultEuicc());
            mRemovableEsimSwitch.setOnCheckedChangeListener(mRemovableEsimChangeListener);
        }

        mRadioPowerOnSwitch = (Switch) findViewById(R.id.radio_power);

        mSimulateOutOfServiceSwitch = (Switch) findViewById(R.id.simulate_out_of_service);
        if (!Build.isDebuggable()) {
            mSimulateOutOfServiceSwitch.setVisibility(View.GONE);
        }
        mMockSatellite = (Switch) findViewById(R.id.mock_carrier_roaming_satellite);
        mMockSatelliteDataSwitch = (Switch) findViewById(R.id.satellite_data_controller_switch);
        mMockSatelliteData = findViewById(R.id.satellite_data_controller);
        mEnforceSatelliteChannel = (Switch) findViewById(R.id.enforce_satellite_channel);
        if (!Build.isDebuggable()) {
            mMockSatellite.setVisibility(View.GONE);
            mMockSatelliteDataSwitch.setVisibility(View.GONE);
            mMockSatelliteData.setVisibility(View.GONE);
            mEnforceSatelliteChannel.setVisibility(View.GONE);
        }

        // onCreate for satellite radio group and manual band override
        mForceCampSatelliteSelectionRadioGroup = findViewById(
                R.id.force_camp_satellite_selection_radio_group);
        mManualOverrideBand = (Spinner) findViewById(R.id.manualBandSelector);

        mForceCampSatelliteButton = (Button) findViewById(R.id.force_camp_satellite_button);
        mForceCampSatelliteButton.setOnClickListener(mForceCampSatelliteConnectHandler);

        mForceCampSatelliteSelectionRadioGroup.setVisibility(View.GONE);
        mManualOverrideBand.setVisibility(View.GONE);
        findViewById(R.id.manualBandSelector).setVisibility(View.GONE);
        findViewById(R.id.manual_band_selector_label).setVisibility(View.GONE);
        mForceCampSatelliteButton.setVisibility(View.GONE);
        findViewById(R.id.satellite_channel_label).setVisibility(View.GONE);
        findViewById(R.id.satellite_channel_number).setVisibility(View.GONE);

        if (!(!Build.isDebuggable() || !mSystemUser)) {
            ArrayAdapter<String> mManualOverrideBandAdapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item, BAND_LABELS);
            mManualOverrideBandAdapter
                    .setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            mManualOverrideBand.setAdapter(mManualOverrideBandAdapter);
        }

        mDownlinkKbps = (TextView) findViewById(R.id.dl_kbps);
        mUplinkKbps = (TextView) findViewById(R.id.ul_kbps);
        updateBandwidths(0, 0);

        mPingTestButton = (Button) findViewById(R.id.ping_test);
        mPingTestButton.setOnClickListener(mPingButtonHandler);
        mUpdateSmscButton = (Button) findViewById(R.id.update_smsc);
        mUpdateSmscButton.setOnClickListener(mUpdateSmscButtonHandler);
        mRefreshSmscButton = (Button) findViewById(R.id.refresh_smsc);
        mRefreshSmscButton.setOnClickListener(mRefreshSmscButtonHandler);
        if (!mSystemUser) {
            mSmsc.setVisibility(View.GONE);
            mUpdateSmscButton.setVisibility(View.GONE);
            mRefreshSmscButton.setVisibility(View.GONE);
            findViewById(R.id.smsc_label).setVisibility(View.GONE);
        }
        mCarrierProvisioningButton = (Button) findViewById(R.id.carrier_provisioning);
        if (!TextUtils.isEmpty(getCarrierProvisioningAppString())) {
            mCarrierProvisioningButton.setOnClickListener(mCarrierProvisioningButtonHandler);
        } else {
            mCarrierProvisioningButton.setEnabled(false);
        }

        mTriggerCarrierProvisioningButton = (Button) findViewById(
                R.id.trigger_carrier_provisioning);
        if (!TextUtils.isEmpty(getCarrierProvisioningAppString())) {
            mTriggerCarrierProvisioningButton.setOnClickListener(
                    mTriggerCarrierProvisioningButtonHandler);
        } else {
            mTriggerCarrierProvisioningButton.setEnabled(false);
        }

        mEsosButton = (Button) findViewById(R.id.esos_questionnaire);
        mEsosDemoButton  = (Button) findViewById(R.id.demo_esos_questionnaire);
        mSatelliteEnableNonEmergencyModeButton = (Button) findViewById(
                R.id.satellite_enable_non_emergency_mode);
        mSatelliteConfigViewerButton = (Button) findViewById(R.id.satellite_config_viewer);

        if (shouldHideButton(mActionEsos)) {
            mEsosButton.setVisibility(View.GONE);
        } else {
            mEsosButton.setOnClickListener(v -> startActivityAsUser(
                    new Intent(mActionEsos).addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK),
                    UserHandle.CURRENT)
            );
        }
        if (shouldHideButton(mActionEsosDemo)) {
            mEsosDemoButton.setVisibility(View.GONE);
        } else {
            mEsosDemoButton.setOnClickListener(v -> startActivityAsUser(
                    new Intent(mActionEsosDemo).addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK),
                    UserHandle.CURRENT)
            );
        }
        if (PhoneInformationUtil.shouldHideNonEmergencyMode(mContext, mSubId)) {
            mSatelliteEnableNonEmergencyModeButton.setVisibility(View.GONE);
        } else {
            mSatelliteEnableNonEmergencyModeButton.setOnClickListener(v -> {
                if (PhoneInformationUtil.mNonEsosIntent != null) {
                    sendBroadcast(PhoneInformationUtil.mNonEsosIntent);
                }
            });
        }

        mSatelliteConfigViewerButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.putExtra("mSubId", mSubId);
            intent.setClassName("com.android.phone",
                    "com.android.phone.settings.SatelliteConfigViewer");
            startActivityAsUser(intent, UserHandle.CURRENT);
        });

        mOemInfoButton = (Button) findViewById(R.id.oem_info);
        mOemInfoButton.setOnClickListener(mOemInfoButtonHandler);
        PackageManager pm = getPackageManager();
        Intent oemInfoIntent = new Intent(OEM_RADIO_INFO_INTENT);
        List<ResolveInfo> oemInfoIntentList = pm.queryIntentActivities(oemInfoIntent, 0);
        if (oemInfoIntentList.size() == 0) {
            mOemInfoButton.setEnabled(false);
        }

        mCellInfoRefreshRateIndex = 0; // disabled
        mPreferredNetworkTypeResult =
                PhoneInformationUtil.PREFERRED_NETWORK_LABELS.length - 1; // Unknown

        new Thread(() -> {
            int networkType = (int) mTelephonyManager.getPreferredNetworkTypeBitmask();
            runOnUiThread(() -> updatePreferredNetworkType(
                    RadioAccessFamily.getNetworkTypeFromRaf(networkType)));
        }).start();
        restoreFromBundle(icicle);
    }

    boolean shouldHideButton(String action) {
        if (!Build.isDebuggable()) {
            return true;
        }
        if (TextUtils.isEmpty(action)) {
            return true;
        }
        PackageManager pm = getPackageManager();
        Intent intent = new Intent(action);
        if (pm.resolveActivity(intent, 0) == null) {
            return true;
        }
        return false;
    }

    @Override
    public Intent getParentActivityIntent() {
        Intent parentActivity = super.getParentActivityIntent();
        if (parentActivity == null) {
            parentActivity =
                    (new Intent())
                            .setClassName(
                                    "com.android.settings",
                                    "com.android.settings.Settings$TestingSettingsActivity");
        }
        return parentActivity;
    }

    @Override
    protected void onResume() {
        super.onResume();

        log("Started onResume");

        updateAllFields();
    }

    private void updateAllFields() {
        updateMessageWaiting();
        updateCallRedirect();
        updateDataState();
        updateDataStats2();
        updateRadioPowerState();
        updateImsProvisionedState();
        updateProperties();
        updateNetworkType();
        updateNrStats();
        updateEuiccInfo();

        updateCellInfo(mCellInfoResult);
        updateSubscriptionIds();

        mPingHostnameV4.setText(mPingHostnameResultV4);
        mPingHostnameV6.setText(mPingHostnameResultV6);
        mHttpClientTest.setText(mHttpClientTestResult);

        mCellInfoRefreshRateSpinner.setOnItemSelectedListener(mCellInfoRefreshRateHandler);
        //set selection after registering listener to force update
        mCellInfoRefreshRateSpinner.setSelection(mCellInfoRefreshRateIndex);
        // Request cell information update from RIL.
        mTelephonyManager.setCellInfoListRate(CELL_INFO_REFRESH_RATES[mCellInfoRefreshRateIndex],
                mSubId);

        //set selection before registering to prevent update
        mPreferredNetworkType.setSelection(mPreferredNetworkTypeResult, true);
        mPreferredNetworkType.setOnItemSelectedListener(mPreferredNetworkHandler);

        new Thread(() -> {
            int networkType = (int) mTelephonyManager.getPreferredNetworkTypeBitmask();
            runOnUiThread(() -> updatePreferredNetworkType(
                    RadioAccessFamily.getNetworkTypeFromRaf(networkType)));
        }).start();

        // mock signal strength
        mMockSignalStrength.setSelection(mSelectedSignalStrengthIndex[mPhoneId]);
        mMockSignalStrength.setOnItemSelectedListener(mOnMockSignalStrengthSelectedListener);

        // mock data network type
        mMockDataNetworkType.setSelection(mSelectedMockDataNetworkTypeIndex[mPhoneId]);
        mMockDataNetworkType.setOnItemSelectedListener(mOnMockDataNetworkTypeSelectedListener);

        // set phone index
        mSelectPhoneIndex.setSelection(mPhoneId, true);
        mSelectPhoneIndex.setOnItemSelectedListener(mSelectPhoneIndexHandler);

        mRadioPowerOnSwitch.setOnCheckedChangeListener(mRadioPowerOnChangeListener);
        mSimulateOutOfServiceSwitch.setChecked(mSimulateOos[mPhoneId]);
        mSimulateOutOfServiceSwitch.setOnCheckedChangeListener(mSimulateOosOnChangeListener);
        mMockSatellite.setChecked(mCarrierSatelliteOriginalBundle[mPhoneId] != null);
        mMockSatellite.setOnCheckedChangeListener(mMockSatelliteListener);
        mMockSatelliteDataSwitch.setChecked(mSatelliteDataOriginalBundle[mPhoneId] != null);
        mMockSatelliteDataSwitch.setOnCheckedChangeListener(mMockSatelliteDataSwitchListener);
        mMockSatelliteData.setOnCheckedChangeListener(mMockSatelliteDataListener);

        updateSatelliteChannelDisplay(mPhoneId);
        mEnforceSatelliteChannel.setOnCheckedChangeListener(mForceSatelliteChannelOnChangeListener);

        mForceCampSatelliteSelectionRadioGroup.setOnCheckedChangeListener(
                mForceCampSatelliteSelectionRadioGroupListener);
        // update manual band override
        mManualOverrideBand.setSelection(mSelectedManualOverrideBandIndex[mPhoneId]);
        mManualOverrideBand.setOnItemSelectedListener(mManualOverrideBandSelectedListener);

        mImsVolteProvisionedSwitch.setOnCheckedChangeListener(mImsVolteCheckedChangeListener);
        mImsVtProvisionedSwitch.setOnCheckedChangeListener(mImsVtCheckedChangeListener);
        mImsWfcProvisionedSwitch.setOnCheckedChangeListener(mImsWfcCheckedChangeListener);
        mEabProvisionedSwitch.setOnCheckedChangeListener(mEabCheckedChangeListener);

        if (isCbrsSupported()) {
            mCbrsDataSwitch.setChecked(getCbrsDataState());
            mCbrsDataSwitch.setOnCheckedChangeListener(mCbrsDataSwitchChangeListener);
        }

        unregisterPhoneStateListener();
        registerPhoneStateListener();
        mConnectivityManager.registerNetworkCallback(
                mDefaultNetworkRequest, mNetworkCallback, mHandler);
        mSmsc.clearFocus();
    }

    @Override
    protected void onPause() {
        super.onPause();

        log("onPause: unregister phone & data intents");

        mTelephonyManager.unregisterTelephonyCallback(mTelephonyCallback);
        mTelephonyManager.setCellInfoListRate(sCellInfoListRateDisabled, mSubId);
        mConnectivityManager.unregisterNetworkCallback(mNetworkCallback);
    }

    private void restoreFromBundle(Bundle b) {
        if (b == null) {
            return;
        }

        mPingHostnameResultV4 = b.getString("mPingHostnameResultV4", "");
        mPingHostnameResultV6 = b.getString("mPingHostnameResultV6", "");
        mHttpClientTestResult = b.getString("mHttpClientTestResult", "");

        mPingHostnameV4.setText(mPingHostnameResultV4);
        mPingHostnameV6.setText(mPingHostnameResultV6);
        mHttpClientTest.setText(mHttpClientTestResult);

        mPreferredNetworkTypeResult =
                b.getInt(
                        "mPreferredNetworkTypeResult",
                        PhoneInformationUtil.PREFERRED_NETWORK_LABELS.length - 1);

        mPhoneId = b.getInt("mSelectedPhoneIndex", 0);
        mSubId = SubscriptionManager.getSubscriptionId(mPhoneId);

        mCellInfoRefreshRateIndex = b.getInt("mCellInfoRefreshRateIndex", 0);
    }

    @SuppressWarnings("MissingSuperCall") // TODO: Fix me
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putString("mPingHostnameResultV4", mPingHostnameResultV4);
        outState.putString("mPingHostnameResultV6", mPingHostnameResultV6);
        outState.putString("mHttpClientTestResult", mHttpClientTestResult);

        outState.putInt("mPreferredNetworkTypeResult", mPreferredNetworkTypeResult);
        outState.putInt("mSelectedPhoneIndex", mPhoneId);
        outState.putInt("mCellInfoRefreshRateIndex", mCellInfoRefreshRateIndex);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Removed "select Radio band". If need it back, use setSystemSelectionChannels()
        menu.add(1, MENU_ITEM_VIEW_ADN, 0, R.string.radioInfo_menu_viewADN)
                .setOnMenuItemClickListener(mViewADNCallback);
        menu.add(1, MENU_ITEM_VIEW_FDN, 0, R.string.radioInfo_menu_viewFDN)
                .setOnMenuItemClickListener(mViewFDNCallback);
        menu.add(1, MENU_ITEM_VIEW_SDN, 0, R.string.radioInfo_menu_viewSDN)
                .setOnMenuItemClickListener(mViewSDNCallback);
        if (isImsSupportedOnDevice()) {
            menu.add(1, MENU_ITEM_GET_IMS_STATUS, 0, R.string.radioInfo_menu_getIMS)
                    .setOnMenuItemClickListener(mGetImsStatus);
        }
        menu.add(1, MENU_ITEM_TOGGLE_DATA, 0, R.string.radio_info_data_connection_disable)
                .setOnMenuItemClickListener(mToggleData);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        // Get the TOGGLE DATA menu item in the right state.
        MenuItem item = menu.findItem(MENU_ITEM_TOGGLE_DATA);
        int state = mTelephonyManager.getDataState();
        boolean visible = true;

        switch (state) {
            case TelephonyManager.DATA_CONNECTED:
            case TelephonyManager.DATA_SUSPENDED:
                item.setTitle(R.string.radio_info_data_connection_disable);
                break;
            case TelephonyManager.DATA_DISCONNECTED:
                item.setTitle(R.string.radio_info_data_connection_enable);
                break;
            default:
                visible = false;
                break;
        }
        item.setVisible(visible);
        return true;
    }

    @Override
    protected void onDestroy() {
        log("onDestroy");
        clearOverride();
        super.onDestroy();
        if (mQueuedWork != null) {
            mQueuedWork.shutdown();
        }
    }

    private void clearOverride() {
        for (int phoneId = 0; phoneId < sPhoneIndexLabels.length; phoneId++) {
            if (mSystemUser) {
                mPhone = PhoneFactory.getPhone(phoneId);
            }
            if (mSimulateOos[mPhoneId]) {
                mSimulateOosOnChangeListener.onCheckedChanged(mSimulateOutOfServiceSwitch, false);
            }
            if (mCarrierSatelliteOriginalBundle[mPhoneId] != null) {
                mMockSatelliteListener.onCheckedChanged(mMockSatellite, false);
            }
            if (mSatelliteDataOriginalBundle[mPhoneId] != null) {
                mMockSatelliteDataSwitchListener.onCheckedChanged(mMockSatelliteDataSwitch, false);
                mSatelliteDataOriginalBundle[mPhoneId] = null;
            }
            if (mSelectedSignalStrengthIndex[mPhoneId] > 0) {
                mOnMockSignalStrengthSelectedListener.onItemSelected(null, null, 0 /*pos*/, 0);
            }
            if (mSelectedMockDataNetworkTypeIndex[mPhoneId] > 0) {
                mOnMockDataNetworkTypeSelectedListener.onItemSelected(null, null, 0 /*pos*/, 0);
            }
        }
    }

    private void unregisterPhoneStateListener() {
        mTelephonyManager.unregisterTelephonyCallback(mTelephonyCallback);

        // clear all fields so they are blank until the next listener event occurs
        mOperatorName.setText("");
        mGprsState.setText("");
        mDataNetwork.setText("");
        mDataRawReg.setText("");
        mOverrideNetwork.setText("");
        mVoiceNetwork.setText("");
        mVoiceRawReg.setText("");
        mWlanDataRawReg.setText("");
        mSent.setText("");
        mReceived.setText("");
        mCallState.setText("");
        mMwiValue = false;
        mMwi.setText("");
        mCfiValue = false;
        mCfi.setText("");
        mCellInfo.setText("");
        mDBm.setText("");
        mGsmState.setText("");
        mRoamingState.setText("");
        mPhyChanConfig.setText("");
        mDownlinkKbps.setText("");
        mUplinkKbps.setText("");
    }

    // register mTelephonyCallback for relevant fields using the current TelephonyManager
    private void registerPhoneStateListener() {
        mTelephonyCallback = new RadioInfoTelephonyCallback();
        mTelephonyManager.registerTelephonyCallback(
                new HandlerExecutor(mHandler), mTelephonyCallback);
    }

    private void setNrStatsVisibility(int visibility) {
        ((TextView) findViewById(R.id.endc_available_label)).setVisibility(visibility);
        mEndcAvailable.setVisibility(visibility);
        ((TextView) findViewById(R.id.dcnr_restricted_label)).setVisibility(visibility);
        mDcnrRestricted.setVisibility(visibility);
        ((TextView) findViewById(R.id.nr_available_label)).setVisibility(visibility);
        mNrAvailable.setVisibility(visibility);
        ((TextView) findViewById(R.id.nr_state_label)).setVisibility(visibility);
        mNrState.setVisibility(visibility);
        ((TextView) findViewById(R.id.nr_frequency_label)).setVisibility(visibility);
        mNrFrequency.setVisibility(visibility);
        ((TextView) findViewById(R.id.network_slicing_config_label)).setVisibility(visibility);
        mNetworkSlicingConfig.setVisibility(visibility);
    }

    private void updateBandwidths(int dlbw, int ulbw) {
        dlbw = (dlbw < 0 || dlbw == Integer.MAX_VALUE) ? -1 : dlbw;
        ulbw = (ulbw < 0 || ulbw == Integer.MAX_VALUE) ? -1 : ulbw;
        mDownlinkKbps.setText(String.format("%-5d", dlbw));
        mUplinkKbps.setText(String.format("%-5d", ulbw));
    }

    private void updateSignalStrength(SignalStrength signalStrength) {
        Resources r = getResources();

        int signalDbm = signalStrength.getDbm();

        int signalAsu = signalStrength.getAsuLevel();

        if (-1 == signalAsu) signalAsu = 0;

        mDBm.setText(
                String.valueOf(signalDbm)
                        + " "
                        + r.getString(R.string.radioInfo_display_dbm)
                        + "   "
                        + String.valueOf(signalAsu)
                        + " "
                        + r.getString(R.string.radioInfo_display_asu));
    }

    private void updateCellInfo(List<CellInfo> arrayCi) {
        mCellInfo.setText(PhoneInformationUtil.buildCellInfoString(arrayCi));
    }

    private void updateSubscriptionIds() {
        mSubscriptionId.setText(String.format(Locale.ROOT, "%d", mSubId));
        mDds.setText(Integer.toString(SubscriptionManager.getDefaultDataSubscriptionId()));
    }

    private void updateMessageWaiting() {
        mMwi.setText(String.valueOf(mMwiValue));
    }

    private void updateCallRedirect() {
        mCfi.setText(String.valueOf(mCfiValue));
    }

    private void updateServiceState(ServiceState serviceState) {
        if (!SubscriptionManager.isValidSubscriptionId(mSubId)) {
            // When SIM is absent, we can't listen service state change from absent slot. Need
            // explicitly get service state from the specific slot.
            serviceState = mTelephonyManager.getServiceStateForSlot(mPhoneId);
        }
        log("Update service state " + serviceState);
        int state = serviceState.getState();
        Resources r = getResources();
        String display = r.getString(R.string.radioInfo_unknown);

        switch (state) {
            case ServiceState.STATE_IN_SERVICE:
                display = r.getString(R.string.radioInfo_service_in);
                break;
            case ServiceState.STATE_OUT_OF_SERVICE:
                display = r.getString(R.string.radioInfo_service_out);
                break;
            case ServiceState.STATE_EMERGENCY_ONLY:
                display = r.getString(R.string.radioInfo_service_emergency);
                break;
            case ServiceState.STATE_POWER_OFF:
                display = r.getString(R.string.radioInfo_service_off);
                break;
        }

        mGsmState.setText(display);

        if (serviceState.getRoaming()) {
            mRoamingState.setText(R.string.radioInfo_roaming_in);
        } else {
            mRoamingState.setText(R.string.radioInfo_roaming_not);
        }

        mOperatorName.setText(serviceState.getOperatorAlphaLong());
    }

    private void updatePhoneState(int state) {
        Resources r = getResources();
        String display = r.getString(R.string.radioInfo_unknown);

        switch (state) {
            case TelephonyManager.CALL_STATE_IDLE:
                display = r.getString(R.string.radioInfo_phone_idle);
                break;
            case TelephonyManager.CALL_STATE_RINGING:
                display = r.getString(R.string.radioInfo_phone_ringing);
                break;
            case TelephonyManager.CALL_STATE_OFFHOOK:
                display = r.getString(R.string.radioInfo_phone_offhook);
                break;
        }

        mCallState.setText(display);
    }

    private void updateDataState() {
        int state = mTelephonyManager.getDataState();
        Resources r = getResources();
        String display = r.getString(R.string.radioInfo_unknown);

        if (SubscriptionManager.isValidSubscriptionId(mSubId)) {
            switch (state) {
                case TelephonyManager.DATA_CONNECTED:
                    display = r.getString(R.string.radioInfo_data_connected);
                    break;
                case TelephonyManager.DATA_CONNECTING:
                    display = r.getString(R.string.radioInfo_data_connecting);
                    break;
                case TelephonyManager.DATA_DISCONNECTED:
                    display = r.getString(R.string.radioInfo_data_disconnected);
                    break;
                case TelephonyManager.DATA_SUSPENDED:
                    display = r.getString(R.string.radioInfo_data_suspended);
                    break;
            }
        } else {
            display = r.getString(R.string.radioInfo_data_disconnected);
        }

        mGprsState.setText(display);
    }

    private void updateNetworkType() {
        SubscriptionManager mSm = getSystemService(SubscriptionManager.class);
        if (SubscriptionManager.isValidPhoneId(mPhoneId) && mSm.isActiveSubscriptionId(mSubId)) {
            mDataNetwork.setText(
                    ServiceState.rilRadioTechnologyToString(
                            mTelephonyManager
                                    .getServiceStateForSlot(mPhoneId)
                                    .getRilDataRadioTechnology()));
            mVoiceNetwork.setText(
                    ServiceState.rilRadioTechnologyToString(
                            mTelephonyManager
                                    .getServiceStateForSlot(mPhoneId)
                                    .getRilVoiceRadioTechnology()));
            int overrideNetwork =
                    (mDisplayInfo != null && SubscriptionManager.isValidSubscriptionId(mSubId))
                            ? mDisplayInfo.getOverrideNetworkType()
                            : TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE;
            mOverrideNetwork.setText(
                    TelephonyDisplayInfo.overrideNetworkTypeToString(overrideNetwork));
        }
    }

    private String getRawRegistrationStateText(ServiceState ss, int domain, int transportType) {
        if (ss != null) {
            NetworkRegistrationInfo nri = ss.getNetworkRegistrationInfo(domain, transportType);
            if (nri != null) {
                return NetworkRegistrationInfo.registrationStateToString(
                                nri.getNetworkRegistrationState())
                        + (nri.isEmergencyEnabled() ? "_EM" : "");
            }
        }
        return "";
    }

    private void updateRawRegistrationState(ServiceState serviceState) {
        ServiceState ss = serviceState;
        ss = mTelephonyManager.getServiceStateForSlot(mPhoneId);

        mVoiceRawReg.setText(
                getRawRegistrationStateText(
                        ss,
                        NetworkRegistrationInfo.DOMAIN_CS,
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN));
        mDataRawReg.setText(
                getRawRegistrationStateText(
                        ss,
                        NetworkRegistrationInfo.DOMAIN_PS,
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN));
        mWlanDataRawReg.setText(
                getRawRegistrationStateText(
                        ss,
                        NetworkRegistrationInfo.DOMAIN_PS,
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN));
    }

    private void updateNrStats() {
        if ((mTelephonyManager.getSupportedRadioAccessFamily()
                        & TelephonyManager.NETWORK_TYPE_BITMASK_NR)
                == 0) {
            return;
        }
        ServiceState ss = mTelephonyManager.getServiceStateForSlot(mPhoneId);
        if (ss != null) {
            NetworkRegistrationInfo nri =
                    ss.getNetworkRegistrationInfo(
                            NetworkRegistrationInfo.DOMAIN_PS,
                            AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
            if (nri != null) {
                DataSpecificRegistrationInfo dsri = nri.getDataSpecificInfo();
                if (dsri != null) {
                    mEndcAvailable.setText(String.valueOf(dsri.isEnDcAvailable));
                    mDcnrRestricted.setText(String.valueOf(dsri.isDcNrRestricted));
                    mNrAvailable.setText(String.valueOf(dsri.isNrAvailable));
                }
            }
            mNrState.setText(NetworkRegistrationInfo.nrStateToString(ss.getNrState()));
            mNrFrequency.setText(ServiceState.frequencyRangeToString(ss.getNrFrequencyRange()));
        } else {
            Log.e(TAG, "Clear Nr stats by null service state");
            mEndcAvailable.setText("");
            mDcnrRestricted.setText("");
            mNrAvailable.setText("");
            mNrState.setText("");
            mNrFrequency.setText("");
        }

        CompletableFuture<NetworkSlicingConfig> resultFuture = new CompletableFuture<>();
        mTelephonyManager.getNetworkSlicingConfiguration(Runnable::run, resultFuture::complete);
        try {
            NetworkSlicingConfig networkSlicingConfig =
                    resultFuture.get(DEFAULT_TIMEOUT_MS, MILLISECONDS);
            mNetworkSlicingConfig.setText(networkSlicingConfig.toString());
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            loge("Unable to get slicing config: " + e);
            mNetworkSlicingConfig.setText("Unable to get slicing config.");
        }
    }

    private void updateProperties() {
        String s;
        Resources r = getResources();

        s = mTelephonyManager.getImei(mPhoneId);
        mDeviceId.setText(s);

        s = mTelephonyManager.getSubscriberId();
        if (s == null || !SubscriptionManager.isValidSubscriptionId(mSubId)) {
            s = r.getString(R.string.radioInfo_unknown);
        }

        mSubscriberId.setText(s);

        SubscriptionManager subMgr = getSystemService(SubscriptionManager.class);
        int subId = mSubId;
        s =
                subMgr.getPhoneNumber(subId)
                        + " { CARRIER:"
                        + subMgr.getPhoneNumber(
                                subId, SubscriptionManager.PHONE_NUMBER_SOURCE_CARRIER)
                        + ", UICC:"
                        + subMgr.getPhoneNumber(subId, SubscriptionManager.PHONE_NUMBER_SOURCE_UICC)
                        + ", IMS:"
                        + subMgr.getPhoneNumber(subId, SubscriptionManager.PHONE_NUMBER_SOURCE_IMS)
                        + " }";
        mLine1Number.setText(s);
    }

    private void updateDataStats2() {
        Resources r = getResources();

        long txPackets = TrafficStats.getMobileTxPackets();
        long rxPackets = TrafficStats.getMobileRxPackets();
        long txBytes = TrafficStats.getMobileTxBytes();
        long rxBytes = TrafficStats.getMobileRxBytes();

        String packets = r.getString(R.string.radioInfo_display_packets);
        String bytes = r.getString(R.string.radioInfo_display_bytes);

        mSent.setText(txPackets + " " + packets + ", " + txBytes + " " + bytes);
        mReceived.setText(rxPackets + " " + packets + ", " + rxBytes + " " + bytes);
    }

    private void updateEuiccInfo() {
        final Runnable setEuiccInfo =
                new Runnable() {
                    public void run() {
                        mEuiccInfo.setText(mEuiccInfoResult);
                    }
                };

        mQueuedWork.execute(
                new Runnable() {
                    @Override
                    public void run() {
                        if (!getPackageManager()
                                .hasSystemFeature(PackageManager.FEATURE_TELEPHONY_EUICC)) {
                            mEuiccInfoResult = "Euicc Feature is disabled";
                        } else if (mEuiccManager == null || !mEuiccManager.isEnabled()) {
                            mEuiccInfoResult = "EuiccManager is not enabled";
                        } else {
                            try {
                                mEuiccInfoResult =
                                        " { Available memory in bytes:"
                                                + mEuiccManager.getAvailableMemoryInBytes()
                                                + " }";
                            } catch (Exception e) {
                                mEuiccInfoResult = e.getMessage();
                            }
                        }
                        mHandler.post(setEuiccInfo);
                    }
                });
    }

    /** Ping a host name */
    private void pingHostname() {
        try {
            try {
                Process p4 = Runtime.getRuntime().exec("ping -c 1 www.google.com");
                int status4 = p4.waitFor();
                if (status4 == 0) {
                    mPingHostnameResultV4 = "Pass";
                } else {
                    mPingHostnameResultV4 = String.format("Fail(%d)", status4);
                }
            } catch (IOException e) {
                mPingHostnameResultV4 = "Fail: IOException";
            }
            try {
                Process p6 = Runtime.getRuntime().exec("ping6 -c 1 www.google.com");
                int status6 = p6.waitFor();
                if (status6 == 0) {
                    mPingHostnameResultV6 = "Pass";
                } else {
                    mPingHostnameResultV6 = String.format("Fail(%d)", status6);
                }
            } catch (IOException e) {
                mPingHostnameResultV6 = "Fail: IOException";
            }
        } catch (InterruptedException e) {
            mPingHostnameResultV4 = mPingHostnameResultV6 = "Fail: InterruptedException";
        }
    }

    /** This function checks for basic functionality of HTTP Client. */
    private void httpClientTest() {
        HttpURLConnection urlConnection = null;
        try {
            // TODO: Hardcoded for now, make it UI configurable
            URL url = new URL("https://www.google.com");
            urlConnection = (HttpURLConnection) url.openConnection();
            if (urlConnection.getResponseCode() == 200) {
                mHttpClientTestResult = "Pass";
            } else {
                mHttpClientTestResult = "Fail: Code: " + urlConnection.getResponseMessage();
            }
        } catch (IOException e) {
            mHttpClientTestResult = "Fail: IOException";
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
        }
    }

    private void refreshSmsc() {
        mQueuedWork.execute(
                () -> {
                    if (mSystemUser) {
                        mPhone.getSmscAddress(mHandler.obtainMessage(EVENT_QUERY_SMSC_DONE));
                    }
                });
    }

    private void updateAllCellInfo() {

        mCellInfo.setText("");

        final Runnable updateAllCellInfoResults =
                new Runnable() {
                    public void run() {
                        updateCellInfo(mCellInfoResult);
                    }
                };

        mQueuedWork.execute(
                new Runnable() {
                    @Override
                    public void run() {
                        mCellInfoResult = mTelephonyManager.getAllCellInfo();

                        mHandler.post(updateAllCellInfoResults);
                    }
                });
    }

    private void updatePingState() {
        // Set all to unknown since the threads will take a few secs to update.
        mPingHostnameResultV4 = getResources().getString(R.string.radioInfo_unknown);
        mPingHostnameResultV6 = getResources().getString(R.string.radioInfo_unknown);
        mHttpClientTestResult = getResources().getString(R.string.radioInfo_unknown);

        mPingHostnameV4.setText(mPingHostnameResultV4);
        mPingHostnameV6.setText(mPingHostnameResultV6);
        mHttpClientTest.setText(mHttpClientTestResult);

        final Runnable updatePingResults =
                new Runnable() {
                    public void run() {
                        mPingHostnameV4.setText(mPingHostnameResultV4);
                        mPingHostnameV6.setText(mPingHostnameResultV6);
                        mHttpClientTest.setText(mHttpClientTestResult);
                    }
                };

        Thread hostname =
                new Thread() {
                    @Override
                    public void run() {
                        pingHostname();
                        mHandler.post(updatePingResults);
                    }
                };
        hostname.start();

        Thread httpClient =
                new Thread() {
                    @Override
                    public void run() {
                        httpClientTest();
                        mHandler.post(updatePingResults);
                    }
                };
        httpClient.start();
    }

    private MenuItem.OnMenuItemClickListener mViewADNCallback =
            new MenuItem.OnMenuItemClickListener() {
                public boolean onMenuItemClick(MenuItem item) {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    // XXX We need to specify the component here because if we don't
                    // the activity manager will try to resolve the type by calling
                    // the content provider, which causes it to be loaded in a process
                    // other than the Dialer process, which causes a lot of stuff to
                    // break.
                    intent.setClassName("com.android.phone", "com.android.phone.SimContacts");
                    startActivityAsUser(intent, UserHandle.CURRENT);
                    return true;
                }
            };

    private MenuItem.OnMenuItemClickListener mViewFDNCallback =
            new MenuItem.OnMenuItemClickListener() {
                public boolean onMenuItemClick(MenuItem item) {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    // XXX We need to specify the component here because if we don't
                    // the activity manager will try to resolve the type by calling
                    // the content provider, which causes it to be loaded in a process
                    // other than the Dialer process, which causes a lot of stuff to
                    // break.
                    intent.setClassName(
                            "com.android.phone", "com.android.phone.settings.fdn.FdnList");
                    startActivityAsUser(intent, UserHandle.CURRENT);
                    return true;
                }
            };

    private MenuItem.OnMenuItemClickListener mViewSDNCallback =
            new MenuItem.OnMenuItemClickListener() {
                public boolean onMenuItemClick(MenuItem item) {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("content://icc/sdn"));
                    // XXX We need to specify the component here because if we don't
                    // the activity manager will try to resolve the type by calling
                    // the content provider, which causes it to be loaded in a process
                    // other than the Dialer process, which causes a lot of stuff to
                    // break.
                    intent.setClassName("com.android.phone", "com.android.phone.ADNList");
                    startActivityAsUser(intent, UserHandle.CURRENT);
                    return true;
                }
            };

    private MenuItem.OnMenuItemClickListener mGetImsStatus =
            new MenuItem.OnMenuItemClickListener() {
                public boolean onMenuItemClick(MenuItem item) {
                    boolean isSimValid = SubscriptionManager.isValidSubscriptionId(mSubId);
                    boolean isImsRegistered = isSimValid && mTelephonyManager.isImsRegistered();
                    boolean availableVolte = false;
                    boolean availableWfc = false;
                    boolean availableVt = false;
                    AtomicBoolean availableUt = new AtomicBoolean(false);

                    if (isSimValid) {
                        ImsMmTelManager imsMmTelManager = mImsManager.getImsMmTelManager(mSubId);
                        availableVolte =
                                PhoneInformationUtil.isVoiceServiceAvailable(imsMmTelManager);
                        availableVt = PhoneInformationUtil.isVideoServiceAvailable(imsMmTelManager);
                        availableWfc = PhoneInformationUtil.isWfcServiceAvailable(imsMmTelManager);
                        CountDownLatch latch = new CountDownLatch(1);
                        try {
                            HandlerThread handlerThread = new HandlerThread("RadioInfo");
                            handlerThread.start();
                            imsMmTelManager.isSupported(
                                    MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_UT,
                                    AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                                    handlerThread.getThreadExecutor(),
                                    (result) -> {
                                        latch.countDown();
                                        availableUt.set(result);
                                    });
                            latch.await(2, TimeUnit.SECONDS);
                            handlerThread.quit();
                        } catch (Exception e) {
                            loge("Failed to get UT state.");
                        }
                    }

                    final String imsRegString =
                            isImsRegistered
                                    ? getString(R.string.radio_info_ims_reg_status_registered)
                                    : getString(R.string.radio_info_ims_reg_status_not_registered);

                    final String available =
                            getString(R.string.radio_info_ims_feature_status_available);
                    final String unavailable =
                            getString(R.string.radio_info_ims_feature_status_unavailable);

                    String imsStatus =
                            getString(
                                    R.string.radio_info_ims_reg_status,
                                    imsRegString,
                                    availableVolte ? available : unavailable,
                                    availableWfc ? available : unavailable,
                                    availableVt ? available : unavailable,
                                    availableUt.get() ? available : unavailable);

                    AlertDialog imsDialog =
                            new AlertDialog.Builder(RadioInfo.this)
                                    .setMessage(imsStatus)
                                    .setTitle(getString(R.string.radio_info_ims_reg_status_title))
                                    .create();

                    imsDialog.show();

                    return true;
                }
            };

    private MenuItem.OnMenuItemClickListener mToggleData =
            new MenuItem.OnMenuItemClickListener() {
                public boolean onMenuItemClick(MenuItem item) {
                    int state = mTelephonyManager.getDataState();
                    switch (state) {
                        case TelephonyManager.DATA_CONNECTED ->
                                mTelephonyManager.setDataEnabled(false);
                        case TelephonyManager.DATA_DISCONNECTED ->
                                mTelephonyManager.setDataEnabled(true);
                        default -> {
                            // do nothing
                        }
                    }
                    return true;
                }
            };

    private boolean isRadioOn() {
        return mTelephonyManager.getRadioPowerState() == TelephonyManager.RADIO_POWER_ON;
    }

    private void updateRadioPowerState() {
        // delightful hack to prevent on-checked-changed calls from
        // actually forcing the radio preference to its transient/current value.
        mRadioPowerOnSwitch.setOnCheckedChangeListener(null);
        mRadioPowerOnSwitch.setChecked(isRadioOn());
        mRadioPowerOnSwitch.setOnCheckedChangeListener(mRadioPowerOnChangeListener);
    }

    private void setImsVolteProvisionedState(boolean state) {
        Log.d(TAG, "setImsVolteProvisioned state: " + ((state) ? "on" : "off"));
        setImsConfigProvisionedState(CAPABILITY_TYPE_VOICE, REGISTRATION_TECH_LTE, state);
    }

    private void setImsVtProvisionedState(boolean state) {
        Log.d(TAG, "setImsVtProvisioned() state: " + ((state) ? "on" : "off"));
        setImsConfigProvisionedState(CAPABILITY_TYPE_VIDEO, REGISTRATION_TECH_LTE, state);
    }

    private void setImsWfcProvisionedState(boolean state) {
        Log.d(TAG, "setImsWfcProvisioned() state: " + ((state) ? "on" : "off"));
        setImsConfigProvisionedState(CAPABILITY_TYPE_VOICE, REGISTRATION_TECH_IWLAN, state);
    }

    private void setEabProvisionedState(boolean state) {
        Log.d(TAG, "setEabProvisioned() state: " + ((state) ? "on" : "off"));
        setRcsConfigProvisionedState(
                ImsRcsManager.CAPABILITY_TYPE_PRESENCE_UCE, REGISTRATION_TECH_LTE, state);
    }

    private void setImsConfigProvisionedState(int capability, int tech, boolean state) {
        if (mProvisioningManager != null) {
            mQueuedWork.execute(
                    new Runnable() {
                        public void run() {
                            try {
                                mProvisioningManager.setProvisioningStatusForCapability(
                                        capability, tech, state);
                            } catch (RuntimeException e) {
                                Log.e(TAG, "setImsConfigProvisioned() exception:", e);
                            }
                        }
                    });
        }
    }

    private void setRcsConfigProvisionedState(int capability, int tech, boolean state) {
        if (mProvisioningManager != null) {
            mQueuedWork.execute(
                    new Runnable() {
                        public void run() {
                            try {
                                mProvisioningManager.setRcsProvisioningStatusForCapability(
                                        capability, tech, state);
                            } catch (RuntimeException e) {
                                Log.e(TAG, "setRcsConfigProvisioned() exception:", e);
                            }
                        }
                    });
        }
    }

    private boolean isImsVolteProvisioningRequired() {
        return isImsConfigProvisioningRequired(CAPABILITY_TYPE_VOICE, REGISTRATION_TECH_LTE);
    }

    private boolean isImsVtProvisioningRequired() {
        return isImsConfigProvisioningRequired(CAPABILITY_TYPE_VIDEO, REGISTRATION_TECH_LTE);
    }

    private boolean isImsWfcProvisioningRequired() {
        return isImsConfigProvisioningRequired(CAPABILITY_TYPE_VOICE, REGISTRATION_TECH_IWLAN);
    }

    private boolean isEabProvisioningRequired() {
        return isRcsConfigProvisioningRequired(
                ImsRcsManager.CAPABILITY_TYPE_PRESENCE_UCE, REGISTRATION_TECH_LTE);
    }

    private boolean isImsConfigProvisioningRequired(int capability, int tech) {
        if (mProvisioningManager != null) {
            try {
                return mProvisioningManager.isProvisioningRequiredForCapability(capability, tech);
            } catch (RuntimeException e) {
                Log.e(TAG, "isImsConfigProvisioningRequired() exception:", e);
            }
        }

        return false;
    }

    private boolean isRcsConfigProvisioningRequired(int capability, int tech) {
        if (mProvisioningManager != null) {
            try {
                return mProvisioningManager.isRcsProvisioningRequiredForCapability(
                        capability, tech);
            } catch (RuntimeException e) {
                Log.e(TAG, "isRcsConfigProvisioningRequired() exception:", e);
            }
        }

        return false;
    }

    OnCheckedChangeListener mRadioPowerOnChangeListener =
            new OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    // TODO: b/145681511. Within current design, radio power on all of the phones
                    // need
                    // to be controlled at the same time.
                    Phone[] phones = PhoneFactory.getPhones();
                    if (phones == null) {
                        return;
                    }
                    log(
                            "toggle radio power: phone*"
                                    + phones.length
                                    + " "
                                    + (isRadioOn() ? "on" : "off"));
                    for (int phoneIndex = 0; phoneIndex < phones.length; phoneIndex++) {
                        if (phones[phoneIndex] != null) {
                            phones[phoneIndex].setRadioPower(isChecked);
                        }
                    }
                }
            };

    private final OnCheckedChangeListener mSimulateOosOnChangeListener =
            (bv, isChecked) -> {
                Intent intent = new Intent("com.android.internal.telephony.TestServiceState");
                if (isChecked) {
                    log("Send OOS override broadcast intent.");
                    intent.putExtra("data_reg_state", 1);
                    mSimulateOos[mPhoneId] = true;
                } else {
                    log("Remove OOS override.");
                    intent.putExtra("action", "reset");
                    mSimulateOos[mPhoneId] = false;
                }
                mPhone.getTelephonyTester().setServiceStateTestIntent(intent);
            };

    // satellite radio group function
    private final RadioGroup.OnCheckedChangeListener
            mForceCampSatelliteSelectionRadioGroupListener =
                    (group, checkedId) -> {
                        switch (checkedId) {
                            case R.id.starlink_band -> {
                                log("Force satellite channel set to STARLINK_BAND");
                                setManualOverrideBandVisibility(false);
                            }
                            case R.id.ast_band -> {
                                log("Force satellite channel set to AST_BAND");
                                setManualOverrideBandVisibility(false);
                            }
                            case R.id.manual_override_band -> {
                                setManualOverrideBandVisibility(true);
                                mManualOverrideBand.setSelection(
                                        mSelectedManualOverrideBandIndex[mPhoneId]);
                            }
                        }
                    };

    private void updateForceCampSatelliteSelectionRadioGroupButtons() {
        int checkId = R.id.starlink_band;
        if (mPreviousSatelliteBand[mPhoneId] != null) {
            checkId =
                    mPreviousSatelliteBand[mPhoneId].getInt(KEY_FORCE_CAMP_SATELLITE_BAND_SELECTED);
            int[] satelliteChannels =
                    mPreviousSatelliteBand[mPhoneId].getIntArray(KEY_SATELLITE_CHANNELS);
            if (checkId == R.id.manual_override_band) {
                setManualOverrideBandVisibility(true);
                mManualOverrideBand.setSelection(mSelectedManualOverrideBandIndex[mPhoneId]);
                if (satelliteChannels.length > 0 && satelliteChannels[0] != -1) {
                    mSatelliteChannels.setText(String.valueOf(satelliteChannels[0]));
                }
            }
        }
        setForceCampSatelliteSelectionRadioGroupVisibility(true);
        mForceCampSatelliteSelectionRadioGroup.check(checkId);
    }

    private static final int SATELLITE_CHANNEL_STARLINK_US = 8665;
    private static final int[] STARLINK_CHANNELS = {SATELLITE_CHANNEL_STARLINK_US};
    private static final int[] AST_CHANNELS = {};
    private static final int[] STARLINK_BAND = {AccessNetworkConstants.EutranBand.BAND_25};
    private static final int[] AST_BAND = {
        AccessNetworkConstants.EutranBand.BAND_5, AccessNetworkConstants.EutranBand.BAND_14
    };

    private void forceSatelliteChannel(
            int[] satelliteBands, int satelliteBandRadioButton, int[] satelliteChannels) {

        int phoneId = mPhoneId;
        int subId = mSubId;
        TelephonyManager tm = mTelephonyManager.createForSubscriptionId(mSubId);
        mQueuedWork.execute(
                new Runnable() {
                    @Override
                    public void run() {
                        // Do not store current plmn as satellite plmn in allPlmnList during testing
                        SatelliteController.getInstance()
                                .setSatelliteIgnorePlmnListFromStorage(true);
                        // Override carrier config
                        PersistableBundle originalBundle =
                                PhoneInformationUtil.getCarrierConfig(mContext)
                                        .getConfigForSubId(
                                                subId,
                                                KEY_SATELLITE_ATTACH_SUPPORTED_BOOL,
                                                KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL,
                                                CarrierConfigManager
                                                        .KEY_EMERGENCY_MESSAGING_SUPPORTED_BOOL);
                        PersistableBundle overrideBundle = new PersistableBundle();
                        overrideBundle.putBoolean(KEY_SATELLITE_ATTACH_SUPPORTED_BOOL, true);
                        overrideBundle.putBoolean(KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL, true);
                        overrideBundle.putBoolean(
                                CarrierConfigManager.KEY_EMERGENCY_MESSAGING_SUPPORTED_BOOL, true);

                        // Set only allow LTE network type
                        try {
                            tm.setAllowedNetworkTypesForReason(
                                    TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_TEST,
                                    RadioAccessFamily.getRafFromNetworkType(
                                            RILConstants.NETWORK_MODE_LTE_ONLY));
                            log("Force satellite channel set to LTE only");
                        } catch (Exception e) {
                            loge("Force satellite channel failed to set network type to LTE " + e);
                            return;
                        }
                        List<RadioAccessSpecifier> mock =
                                List.of(
                                        new RadioAccessSpecifier(
                                                AccessNetworkConstants.AccessNetworkType.EUTRAN,
                                                satelliteBands,
                                                satelliteChannels));
                        try {
                            log("Force satellite channel new channels " + mock);
                            tm.setSystemSelectionChannels(mock);
                        } catch (Exception e) {
                            loge("Force satellite channel failed to set channels " + e);
                            return;
                        }
                        log("Force satellite channel new config " + overrideBundle);
                        PhoneInformationUtil.getCarrierConfig(mContext)
                                .overrideConfig(subId, overrideBundle, false);

                        mOriginalSystemChannels[phoneId] = originalBundle;
                        log("Force satellite channel old " + mock + originalBundle);

                        PersistableBundle satelliteBandsBundle = new PersistableBundle();
                        satelliteBandsBundle.putIntArray(KEY_SATELLITE_BANDS, satelliteBands);
                        satelliteBandsBundle.putInt(
                                KEY_FORCE_CAMP_SATELLITE_BAND_SELECTED, satelliteBandRadioButton);
                        satelliteBandsBundle.putIntArray(KEY_SATELLITE_CHANNELS, satelliteChannels);
                        mPreviousSatelliteBand[phoneId] = satelliteBandsBundle;

                        log("Satellite bands save " + Arrays.toString(satelliteBands));

                        mHandler.post(() -> enableSatelliteBandControls(false));
                    }
                });
    }

    private void enableSatelliteBandControls(boolean enable) {
        mForceCampSatelliteButton.setEnabled(enable);
        mSatelliteChannels.setEnabled(enable);
        mManualOverrideBand.setEnabled(enable);
        mForceCampSatelliteSelectionRadioGroup.setEnabled(enable);
        for (int i = 0; i < mForceCampSatelliteSelectionRadioGroup.getChildCount(); i++) {
            View child = mForceCampSatelliteSelectionRadioGroup.getChildAt(i);
            if (child instanceof RadioButton) {
                child.setEnabled(enable);
            }
        }
    }

    private final OnCheckedChangeListener mForceSatelliteChannelOnChangeListener =
            (buttonView, isChecked) -> {
                if (!isValidSubscription(mSubId)) {
                    loge("Force satellite channel invalid subId " + mSubId);
                    return;
                }
                if (PhoneInformationUtil.getCarrierConfig(mContext) == null) {
                    loge("Force satellite channel cm == null");
                    return;
                }
                TelephonyManager tm = mTelephonyManager.createForSubscriptionId(mSubId);
                // To be used in thread in case mPhone changes.
                int subId = mSubId;
                int phoneId = mPhoneId;

                if (isChecked) {
                    mForceCampSatelliteSelectionRadioGroup.clearCheck();
                    updateForceCampSatelliteSelectionRadioGroupButtons();
                } else {
                    log("Force camp off");
                    mQueuedWork.execute(
                            () -> {
                                try {
                                    // Reset to original configuration
                                    SatelliteController.getInstance()
                                            .setSatelliteIgnorePlmnListFromStorage(false);
                                    tm.setSystemSelectionChannels(
                                            Collections.emptyList() /* isSpecifyChannels false */);
                                    log("Force satellite channel successfully cleared channels ");
                                    tm.setAllowedNetworkTypesForReason(
                                            TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_TEST,
                                            TelephonyManager.getAllNetworkTypesBitmask());
                                    log(
                                            "Force satellite channel successfully reset network"
                                                    + " type to "
                                                    + TelephonyManager.getAllNetworkTypesBitmask());
                                    PersistableBundle original = mOriginalSystemChannels[phoneId];
                                    if (original != null) {
                                        PhoneInformationUtil.getCarrierConfig(mContext)
                                                .overrideConfig(subId, original, false);
                                        log(
                                                "Force satellite channel successfully restored"
                                                        + " config to "
                                                        + original);
                                        mOriginalSystemChannels[phoneId] = null;
                                    }
                                    mHandler.post(
                                            () -> {
                                                setForceCampSatelliteSelectionRadioGroupVisibility(
                                                        false);
                                                mSelectedManualOverrideBandIndex[phoneId] = 0;
                                                mPreviousSatelliteBand[phoneId] = null;
                                                mSatelliteChannels.setText("");
                                                enableSatelliteBandControls(true);
                                            });
                                } catch (Exception e) {
                                    loge("Force satellite channel: Can't clear mock " + e);
                                }
                            });
                }
            };

    private void setManualOverrideBandVisibility(boolean isChecked) {
        if (isChecked) {
            findViewById(R.id.manualBandSelector).setVisibility(View.VISIBLE);
            findViewById(R.id.manual_band_selector_label).setVisibility(View.VISIBLE);
            findViewById(R.id.satellite_channel_label).setVisibility(View.VISIBLE);
            findViewById(R.id.satellite_channel_number).setVisibility(View.VISIBLE);
        } else {
            findViewById(R.id.manualBandSelector).setVisibility(View.GONE);
            findViewById(R.id.manual_band_selector_label).setVisibility(View.GONE);
            findViewById(R.id.satellite_channel_label).setVisibility(View.GONE);
            findViewById(R.id.satellite_channel_number).setVisibility(View.GONE);
        }
    }

    private void setForceCampSatelliteSelectionRadioGroupVisibility(boolean isChecked) {
        if (isChecked) {
            mForceCampSatelliteSelectionRadioGroup.setVisibility(View.VISIBLE);
            mForceCampSatelliteButton.setVisibility(View.VISIBLE);
        } else {
            mForceCampSatelliteSelectionRadioGroup.setVisibility(View.GONE);
            mForceCampSatelliteButton.setVisibility(View.GONE);
            setManualOverrideBandVisibility(false);
        }
    }

    private void updateSatelliteChannelDisplay(int phoneId) {
        if (mEnforceSatelliteChannel.isChecked()) return;
        // Assume in testing mode
        mQueuedWork.execute(() -> {
            TelephonyManager tm = mTelephonyManager.createForSubscriptionId(
                    SubscriptionManager.getSubscriptionId(phoneId));
            try {
                List<RadioAccessSpecifier> channels = tm.getSystemSelectionChannels();
                long networkTypeBitMask = tm.getAllowedNetworkTypesForReason(
                        TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_TEST);
                long lteNetworkBitMask = RadioAccessFamily.getRafFromNetworkType(
                        RILConstants.NETWORK_MODE_LTE_ONLY);

                if (channels.size() != 1) {
                    return;
                }
                RadioAccessSpecifier channel = channels.getFirst();
                boolean radioAccessNetworkCheck = (channel.getRadioAccessNetwork()
                        == AccessNetworkConstants.AccessNetworkType.EUTRAN);
                boolean forceCampChecked = (radioAccessNetworkCheck
                        || (networkTypeBitMask & lteNetworkBitMask) == networkTypeBitMask);
                if (!forceCampChecked) {
                    return;
                }
                int[] satelliteBands = channel.getBands();
                int[] satelliteChannels = channel.getChannels();

                boolean starlinkCheck = Arrays.stream(satelliteChannels).anyMatch(c -> {
                    for (int starlinkChannel : STARLINK_CHANNELS) {
                        if (c == starlinkChannel) {
                            return true;
                        }
                    }
                    return false;
                });

                boolean astCheck = Arrays.stream(satelliteBands).anyMatch(c -> {
                    for (int astBand : AST_BAND) {
                        if (c == astBand) {
                            return true;
                        }
                    }
                    return false;
                });

                int satelliteBandRadioButton;
                if (starlinkCheck) {
                    satelliteBandRadioButton = R.id.starlink_band;
                } else if (astCheck) {
                    satelliteBandRadioButton = R.id.ast_band;
                } else {
                    satelliteBandRadioButton = R.id.manual_override_band;
                    mSelectedManualOverrideBandIndex[phoneId] = 0;
                    if (satelliteBands.length > 0) {
                        int band = satelliteBands[0];
                        for (int i = 0; i < BAND_VALUES.length; i++) {
                            if (band == BAND_VALUES[i]) {
                                mSelectedManualOverrideBandIndex[phoneId] = i;
                            }
                        }
                    }
                }
                PersistableBundle satelliteBandsBundle = new PersistableBundle();
                satelliteBandsBundle.putIntArray(KEY_SATELLITE_BANDS, satelliteBands);
                satelliteBandsBundle.putInt(KEY_FORCE_CAMP_SATELLITE_BAND_SELECTED,
                        satelliteBandRadioButton);
                satelliteBandsBundle.putIntArray(KEY_SATELLITE_CHANNELS, satelliteChannels);
                mPreviousSatelliteBand[phoneId] = satelliteBandsBundle;

                mHandler.post(() -> {
                    log("Force satellite get channel " + channels
                            + " get networkTypeBitMask " + networkTypeBitMask + " lte "
                            + lteNetworkBitMask);
                    // if SATELLITE_CHANNEL is the current channel
                    mEnforceSatelliteChannel.setChecked(true);
                    enableSatelliteBandControls(false);
                });
            } catch (Exception e) {
                loge("updateSatelliteChannelDisplay " + e);
            }
        });
    }

    /** Method that restore the previous satellite data mode selection. */
    private void updateSatelliteDataButton() {
        if (mSatelliteDataOriginalBundle[mPhoneId] == null) {
            // It executes only at first time
            PersistableBundle originalBundle =
                    PhoneInformationUtil.getCarrierConfig(mContext)
                            .getConfigForSubId(
                                    mSubId,
                                    KEY_SATELLITE_DATA_SUPPORT_MODE_INT,
                                    KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL,
                                    KEY_CARRIER_ROAMING_SATELLITE_DEFAULT_SERVICES_INT_ARRAY,
                                    KEY_CARRIER_SUPPORTED_SATELLITE_SERVICES_PER_PROVIDER_BUNDLE);
            mSatelliteDataOriginalBundle[mPhoneId] = originalBundle;
            log("satData: OriginalConfig = " + originalBundle);
        }
        PersistableBundle currentBundle =
                PhoneInformationUtil.getCarrierConfig(mContext)
                        .getConfigForSubId(
                                mSubId,
                                KEY_SATELLITE_DATA_SUPPORT_MODE_INT,
                                KEY_CARRIER_ROAMING_SATELLITE_DEFAULT_SERVICES_INT_ARRAY,
                                KEY_CARRIER_SUPPORTED_SATELLITE_SERVICES_PER_PROVIDER_BUNDLE);
        int dataMode = currentBundle.getInt(KEY_SATELLITE_DATA_SUPPORT_MODE_INT, -1);
        log("satData: present dataMode = " + dataMode);
        if (dataMode != -1) {
            int checkedId = switch (dataMode) {
                case CarrierConfigManager.SATELLITE_DATA_SUPPORT_ONLY_RESTRICTED ->
                        R.id.satellite_data_restricted;
                case CarrierConfigManager.SATELLITE_DATA_SUPPORT_BANDWIDTH_CONSTRAINED ->
                        R.id.satellite_data_constrained;
                case CarrierConfigManager.SATELLITE_DATA_SUPPORT_ALL ->
                        R.id.satellite_data_unConstrained;
                default -> 0;
            };
            mMockSatelliteData.check(checkedId);
        }
        PhoneInformationUtil.updateCarrierConfigToSupportData(
                PhoneInformationUtil.getCarrierConfig(mContext), mSubId, currentBundle);
    }

    private final RadioGroup.OnCheckedChangeListener mMockSatelliteDataListener =
            (group, checkedId) -> {
                int dataMode = CarrierConfigManager.SATELLITE_DATA_SUPPORT_ONLY_RESTRICTED;
                dataMode = switch (checkedId) {
                    case R.id.satellite_data_restricted ->
                            CarrierConfigManager.SATELLITE_DATA_SUPPORT_ONLY_RESTRICTED;
                    case R.id.satellite_data_constrained ->
                            CarrierConfigManager.SATELLITE_DATA_SUPPORT_BANDWIDTH_CONSTRAINED;
                    case R.id.satellite_data_unConstrained ->
                            CarrierConfigManager.SATELLITE_DATA_SUPPORT_ALL;
                    default -> dataMode;
                };
                log("satData: OnCheckedChangeListener setting dataMode = " + dataMode);
                if (PhoneInformationUtil.getCarrierConfig(mContext) == null) return;
                PersistableBundle overrideBundle = new PersistableBundle();
                overrideBundle.putInt(KEY_SATELLITE_DATA_SUPPORT_MODE_INT, dataMode);
                overrideBundle.putBoolean(KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL, false);
                if (isValidSubscription(mSubId)) {
                    PhoneInformationUtil.getCarrierConfig(mContext)
                            .overrideConfig(mSubId, overrideBundle, false);
                    log("satData: mMockSatelliteDataListener: Updated new config" + overrideBundle);
                }
            };

    private final OnCheckedChangeListener mMockSatelliteDataSwitchListener =
            (buttonView, isChecked) -> {
                log("satData: ServiceData enabling = " + isChecked);
                if (isChecked) {
                    if (isValidOperator(mSubId)) {
                        log("satData: Uncapping maxAllowedDataMode");
                        PhoneInformationUtil.uncapMaxAllowedDataMode();
                        updateSatelliteDataButton();
                    } else {
                        log("satData: Not a valid Operator");
                        mMockSatelliteDataSwitch.setChecked(false);
                        return;
                    }
                } else {
                    log("satData: restoring maxAllowedDataMode");
                    PhoneInformationUtil.restoreMaxAllowedDataMode();
                    reloadCarrierConfigDefaults();
                }
                setDataModeChangeVisibility(isChecked);
            };

    private void setDataModeChangeVisibility(boolean isChecked) {
        if (isChecked) {
            mMockSatelliteData.setVisibility(View.VISIBLE);
        } else {
            mMockSatelliteData.setVisibility(View.GONE);
        }
    }

    private void reloadCarrierConfigDefaults() {
        if (mSatelliteDataOriginalBundle[mPhoneId] != null) {
            log(
                    "satData: Setting originalCarrierConfig = "
                            + mSatelliteDataOriginalBundle[mPhoneId]);
            PhoneInformationUtil.getCarrierConfig(mContext).overrideConfig(mSubId,
                    mSatelliteDataOriginalBundle[mPhoneId], false);
            mSatelliteDataOriginalBundle[mPhoneId] = null;
        }
    }

    private boolean isValidOperator(int subId) {
        String operatorNumeric = null;
        if (isValidSubscription(subId)) {
            operatorNumeric = mTelephonyManager.getNetworkOperatorForPhone(mPhoneId);
            TelephonyManager tm;
            if (TextUtils.isEmpty(operatorNumeric)
                    && (tm = getSystemService(TelephonyManager.class)) != null) {
                operatorNumeric = tm.getSimOperatorNumericForPhone(mPhoneId);
            }
        }
        return !TextUtils.isEmpty(operatorNumeric);
    }

    /**
     * This method will do extra check to validate the subId.
     *
     * <p>In case user opens the radioInfo when sim is active and enable some checks and go to the
     * SIM settings screen and disabled the screen. Upon return to radioInfo screen subId is still
     * valid but not in active state any more.
     */
    private boolean isValidSubscription(int subId) {
        boolean isValidSubId = false;
        if (SubscriptionManager.isValidSubscriptionId(subId)) {
            SubscriptionManager mSm = getSystemService(SubscriptionManager.class);
            isValidSubId = mSm.isActiveSubscriptionId(subId);
        }
        log("isValidSubscription, subId [ " + subId + " ] = " + isValidSubId);
        return isValidSubId;
    }

    private final OnCheckedChangeListener mMockSatelliteListener =
            (buttonView, isChecked) -> {
                int subId = mSubId;
                int phoneId = mPhoneId;
                if (SubscriptionManager.isValidPhoneId(phoneId) && isValidSubscription(subId)) {
                    if (PhoneInformationUtil.getCarrierConfig(mContext) == null) return;
                    if (isChecked) {
                        if (!isValidOperator(subId)) {
                            mMockSatellite.setChecked(false);
                            loge(
                                    "mMockSatelliteListener: Can't mock because no operator for"
                                            + " phone "
                                            + phoneId);
                            return;
                        }
                        PersistableBundle originalBundle =
                                PhoneInformationUtil.getCarrierConfig(mContext).getConfigForSubId(
                                subId, KEY_SATELLITE_ATTACH_SUPPORTED_BOOL,
                                KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL,
                                KEY_CARRIER_SUPPORTED_SATELLITE_SERVICES_PER_PROVIDER_BUNDLE);
                        mCarrierSatelliteOriginalBundle[phoneId] = originalBundle;

                        PersistableBundle overrideBundle = new PersistableBundle();
                        overrideBundle.putBoolean(KEY_SATELLITE_ATTACH_SUPPORTED_BOOL, true);
                        // NOTE: In case of TMO setting KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL
                        // to false will result in SIM Settings not to show few items, which is
                        // expected.
                        overrideBundle.putBoolean(KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL, false);
                        overrideBundle.putPersistableBundle(
                                KEY_CARRIER_SUPPORTED_SATELLITE_SERVICES_PER_PROVIDER_BUNDLE,
                                PhoneInformationUtil.getSatelliteServicesBundleForOperatorPlmn(
                                        mTelephonyManager, mPhoneId, mSubId, originalBundle));
                        log("mMockSatelliteListener: old " + originalBundle);
                        log("mMockSatelliteListener: new " + overrideBundle);
                        PhoneInformationUtil.getCarrierConfig(mContext)
                                .overrideConfig(subId, overrideBundle, false);
                    } else {
                        try {
                            PhoneInformationUtil.getCarrierConfig(mContext).overrideConfig(subId,
                                    mCarrierSatelliteOriginalBundle[phoneId], false);
                            mCarrierSatelliteOriginalBundle[phoneId] = null;
                            log("mMockSatelliteListener: Successfully cleared mock for phone "
                                    + phoneId);
                        } catch (Exception e) {
                            loge("mMockSatelliteListener: Can't clear mock because invalid sub Id "
                                    + subId
                                    + ", insert SIM and use adb shell cmd phone cc clear-values");
                            // Keep show toggle ON if the view is not destroyed. If destroyed, must
                            // use cmd to reset, because upon creation the view doesn't remember the
                            // last toggle state while override mock is still in place.
                            mMockSatellite.setChecked(true);
                        }
                    }
                }
            };

    private boolean isImsVolteProvisioned() {
        return getImsConfigProvisionedState(CAPABILITY_TYPE_VOICE, REGISTRATION_TECH_LTE);
    }

    OnCheckedChangeListener mImsVolteCheckedChangeListener =
            new OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    setImsVolteProvisionedState(isChecked);
                }
            };

    private boolean isImsVtProvisioned() {
        return getImsConfigProvisionedState(CAPABILITY_TYPE_VIDEO, REGISTRATION_TECH_LTE);
    }

    OnCheckedChangeListener mImsVtCheckedChangeListener =
            new OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    setImsVtProvisionedState(isChecked);
                }
            };

    private boolean isImsWfcProvisioned() {
        return getImsConfigProvisionedState(CAPABILITY_TYPE_VOICE, REGISTRATION_TECH_IWLAN);
    }

    OnCheckedChangeListener mImsWfcCheckedChangeListener =
            new OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    setImsWfcProvisionedState(isChecked);
                }
            };

    private boolean isEabProvisioned() {
        return getRcsConfigProvisionedState(
                ImsRcsManager.CAPABILITY_TYPE_PRESENCE_UCE, REGISTRATION_TECH_LTE);
    }

    OnCheckedChangeListener mEabCheckedChangeListener =
            new OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    setEabProvisionedState(isChecked);
                }
            };

    private boolean getImsConfigProvisionedState(int capability, int tech) {
        if (mProvisioningManager != null) {
            try {
                return mProvisioningManager.getProvisioningStatusForCapability(capability, tech);
            } catch (RuntimeException e) {
                Log.e(TAG, "getImsConfigProvisionedState() exception:", e);
            }
        }

        return false;
    }

    private boolean getRcsConfigProvisionedState(int capability, int tech) {
        if (mProvisioningManager != null) {
            try {
                return mProvisioningManager.getRcsProvisioningStatusForCapability(capability, tech);
            } catch (RuntimeException e) {
                Log.e(TAG, "getRcsConfigProvisionedState() exception:", e);
            }
        }

        return false;
    }

    private boolean isEabEnabledByPlatform() {
        if (SubscriptionManager.isValidPhoneId(mPhoneId)) {
            PersistableBundle b = PhoneInformationUtil.getCarrierConfig(mContext)
                    .getConfigForSubId(mSubId);
            if (b != null) {
                return b.getBoolean(CarrierConfigManager.KEY_USE_RCS_PRESENCE_BOOL, false)
                        || b.getBoolean(
                                CarrierConfigManager.Ims
                                        .KEY_ENABLE_PRESENCE_CAPABILITY_EXCHANGE_BOOL,
                                false);
            }
        }
        return false;
    }

    private void updateImsProvisionedState() {
        if (!isImsSupportedOnDevice()) {
            return;
        }
        updateServiceEnabledByPlatform();
        updateEabProvisionedSwitch(isEabEnabledByPlatform());
    }

    private void updateVolteProvisionedSwitch(boolean isEnabledByPlatform) {
        boolean isProvisioned = isEnabledByPlatform && isImsVolteProvisioned();
        log("updateVolteProvisionedSwitch isProvisioned" + isProvisioned);

        mImsVolteProvisionedSwitch.setOnCheckedChangeListener(null);
        mImsVolteProvisionedSwitch.setChecked(isProvisioned);
        mImsVolteProvisionedSwitch.setOnCheckedChangeListener(mImsVolteCheckedChangeListener);
        mImsVolteProvisionedSwitch.setEnabled(!IS_USER_BUILD
                && isEnabledByPlatform && isImsVolteProvisioningRequired());
    }

    private void updateVtProvisionedSwitch(boolean isEnabledByPlatform) {
        boolean isProvisioned = isEnabledByPlatform && isImsVtProvisioned();
        log("updateVtProvisionedSwitch isProvisioned" + isProvisioned);

        mImsVtProvisionedSwitch.setOnCheckedChangeListener(null);
        mImsVtProvisionedSwitch.setChecked(isProvisioned);
        mImsVtProvisionedSwitch.setOnCheckedChangeListener(mImsVtCheckedChangeListener);
        mImsVtProvisionedSwitch.setEnabled(!IS_USER_BUILD
                && isEnabledByPlatform && isImsVtProvisioningRequired());
    }

    private void updateWfcProvisionedSwitch(boolean isEnabledByPlatform) {
        boolean isProvisioned = isEnabledByPlatform && isImsWfcProvisioned();
        log("updateWfcProvisionedSwitch isProvisioned" + isProvisioned);

        mImsWfcProvisionedSwitch.setOnCheckedChangeListener(null);
        mImsWfcProvisionedSwitch.setChecked(isProvisioned);
        mImsWfcProvisionedSwitch.setOnCheckedChangeListener(mImsWfcCheckedChangeListener);
        mImsWfcProvisionedSwitch.setEnabled(!IS_USER_BUILD
                && isEnabledByPlatform && isImsWfcProvisioningRequired());
    }

    private void updateEabProvisionedSwitch(boolean isEnabledByPlatform) {
        log("updateEabProvisionedSwitch isEabWfcProvisioned()=" + isEabProvisioned());

        mEabProvisionedSwitch.setOnCheckedChangeListener(null);
        mEabProvisionedSwitch.setChecked(isEabProvisioned());
        mEabProvisionedSwitch.setOnCheckedChangeListener(mEabCheckedChangeListener);
        mEabProvisionedSwitch.setEnabled(!IS_USER_BUILD
                && isEnabledByPlatform && isEabProvisioningRequired());
    }

    OnClickListener mOemInfoButtonHandler =
            new OnClickListener() {
                public void onClick(View v) {
                    Intent intent = new Intent(OEM_RADIO_INFO_INTENT);
                    try {
                        startActivityAsUser(intent, UserHandle.CURRENT);
                    } catch (ActivityNotFoundException ex) {
                        log("OEM-specific Info/Settings Activity Not Found : " + ex);
                        // If the activity does not exist, there are no OEM
                        // settings, and so we can just do nothing...
                    }
                }
            };

    OnClickListener mPingButtonHandler =
            new OnClickListener() {
                public void onClick(View v) {
                    updatePingState();
                }
            };

    OnClickListener mForceCampSatelliteConnectHandler =
            new OnClickListener() {
                @Override
                public void onClick(View v) {
                    int satelliteBandRadioButton =
                            mForceCampSatelliteSelectionRadioGroup.getCheckedRadioButtonId();
                    int[] satelliteBands = STARLINK_BAND;
                    int[] satelliteChannels = STARLINK_CHANNELS;
                    switch (satelliteBandRadioButton) {
                        case (R.id.starlink_band) -> {
                            satelliteBands = STARLINK_BAND;
                            satelliteChannels = STARLINK_CHANNELS;
                            log("Connect start with starlink");
                        }
                        case (R.id.ast_band) -> {
                            satelliteBands = AST_BAND;
                            satelliteChannels = AST_CHANNELS;
                            log("Connect start with ast");
                        }
                        case (R.id.manual_override_band) -> {
                            int index = mManualOverrideBand.getSelectedItemPosition();
                            if (index == 0) {
                                return;
                            }
                            satelliteBands = new int[] {BAND_VALUES[index]};
                            String channelText = mSatelliteChannels.getText().toString();
                            try {
                                int channel = Integer.parseInt(channelText);
                                satelliteChannels = new int[] {channel};
                            } catch (NumberFormatException e) {
                                log(
                                        "Could not convert to satellite channel, connecting without"
                                                + " channel.");
                                satelliteChannels = new int[0];
                            }
                            log(
                                    "Connect start with manual override band"
                                            + Arrays.toString(satelliteBands)
                                            + "and channel "
                                            + Arrays.toString(satelliteChannels));
                        }
                    }
                    forceSatelliteChannel(satelliteBands, satelliteBandRadioButton,
                            satelliteChannels);
                }
            };

    OnClickListener mUpdateSmscButtonHandler =
            new OnClickListener() {
                public void onClick(View v) {
                    mUpdateSmscButton.setEnabled(false);
                    mQueuedWork.execute(
                            () -> {
                                if (mSystemUser) {
                                    mPhone.setSmscAddress(
                                            mSmsc.getText().toString(),
                                            mHandler.obtainMessage(EVENT_UPDATE_SMSC_DONE));
                                }
                            });
                }
            };

    OnClickListener mRefreshSmscButtonHandler = v -> refreshSmsc();

    OnClickListener mCarrierProvisioningButtonHandler = v -> {
        String carrierProvisioningApp = getCarrierProvisioningAppString();
        if (!TextUtils.isEmpty(carrierProvisioningApp)) {
            final Intent intent = new Intent(CARRIER_PROVISIONING_ACTION);
            final ComponentName serviceComponent = ComponentName.unflattenFromString(
                    carrierProvisioningApp);
            intent.setComponent(serviceComponent);
            sendBroadcast(intent);
        }
    };

    OnClickListener mTriggerCarrierProvisioningButtonHandler = v -> {
        String carrierProvisioningApp = getCarrierProvisioningAppString();
        if (!TextUtils.isEmpty(carrierProvisioningApp)) {
            final Intent intent = new Intent(TRIGGER_CARRIER_PROVISIONING_ACTION);
            final ComponentName serviceComponent = ComponentName.unflattenFromString(
                    carrierProvisioningApp);
            intent.setComponent(serviceComponent);
            sendBroadcast(intent);
        }
    };

    AdapterView.OnItemSelectedListener mPreferredNetworkHandler =
            new AdapterView.OnItemSelectedListener() {
                public void onItemSelected(AdapterView parent, View v, int pos, long id) {
                    if (mPreferredNetworkTypeResult != pos && pos >= 0
                            && pos <= PhoneInformationUtil.PREFERRED_NETWORK_LABELS.length - 2) {
                        mPreferredNetworkTypeResult = pos;
                        new Thread(() -> {
                            mTelephonyManager.setAllowedNetworkTypesForReason(
                                    TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER,
                                    RadioAccessFamily.getRafFromNetworkType(
                                            mPreferredNetworkTypeResult));
                        }).start();
                    }
                }

                public void onNothingSelected(AdapterView parent) {}
            };

    AdapterView.OnItemSelectedListener mOnMockSignalStrengthSelectedListener =
            new AdapterView.OnItemSelectedListener() {

                public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                    log("mOnSignalStrengthSelectedListener: " + pos);
                    mSelectedSignalStrengthIndex[mPhoneId] = pos;
                    if (mSystemUser) {
                        mPhone.getTelephonyTester()
                                .setSignalStrength(PhoneInformationUtil.SIGNAL_STRENGTH_LEVEL[pos]);
                    }
                }

                public void onNothingSelected(AdapterView<?> parent) {}
            };

    AdapterView.OnItemSelectedListener mOnMockDataNetworkTypeSelectedListener =
            new AdapterView.OnItemSelectedListener() {

                public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                    log("mOnMockDataNetworkTypeSelectedListener: " + pos);
                    mSelectedMockDataNetworkTypeIndex[mPhoneId] = pos;
                    Intent intent = new Intent("com.android.internal.telephony.TestServiceState");
                    if (pos > 0) {
                        log("mOnMockDataNetworkTypeSelectedListener: Override RAT: "
                                + ServiceState.rilRadioTechnologyToString(
                                PhoneInformationUtil.MOCK_DATA_NETWORK_TYPE[pos]));
                        intent.putExtra("data_reg_state", ServiceState.STATE_IN_SERVICE);
                        intent.putExtra("data_rat",
                                PhoneInformationUtil.MOCK_DATA_NETWORK_TYPE[pos]);
                    } else {
                        log("mOnMockDataNetworkTypeSelectedListener: Remove RAT override.");
                        intent.putExtra("action", "reset");
                    }

                    if (mSystemUser) {
                        mPhone.getTelephonyTester().setServiceStateTestIntent(intent);
                    }
                }

                public void onNothingSelected(AdapterView<?> parent) {}
            };

    AdapterView.OnItemSelectedListener mSelectPhoneIndexHandler =
            new AdapterView.OnItemSelectedListener() {

                public void onItemSelected(AdapterView parent, View v, int pos, long id) {
                    if (pos >= 0 && pos <= sPhoneIndexLabels.length - 1) {
                        if (mTelephonyManager.getActiveModemCount() <= pos) {
                            return;
                        }

                        mPhoneId = pos;
                        mSubId = SubscriptionManager.getSubscriptionId(mPhoneId);
                        log("Updated phone id to " + mPhoneId + ", sub id to " + mSubId);
                        updatePhoneIndex();
                    }
                }

                public void onNothingSelected(AdapterView parent) {}
            };

    AdapterView.OnItemSelectedListener mManualOverrideBandSelectedListener =
            new AdapterView.OnItemSelectedListener() {

                public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                    log("mManualOverrideBandSelectedListener: " + pos);
                    mSelectedManualOverrideBandIndex[mPhoneId] = pos;
                    if (pos == 0) {
                        log("mManualOverrideBandSelectedListener: No band selected");
                    }
                }

                public void onNothingSelected(AdapterView<?> parent) {}
            };

    AdapterView.OnItemSelectedListener mCellInfoRefreshRateHandler =
            new AdapterView.OnItemSelectedListener() {

                public void onItemSelected(AdapterView parent, View v, int pos, long id) {
                    mCellInfoRefreshRateIndex = pos;
                    mTelephonyManager.setCellInfoListRate(CELL_INFO_REFRESH_RATES[pos], mPhoneId);
                    updateAllCellInfo();
                }

                public void onNothingSelected(AdapterView parent) {}
            };

    private String getCarrierProvisioningAppString() {
        if (SubscriptionManager.isValidPhoneId(mPhoneId)) {
            PersistableBundle b = PhoneInformationUtil.getCarrierConfig(mContext)
                    .getConfigForSubId(mSubId);
            if (b != null) {
                return b.getString(CarrierConfigManager.KEY_CARRIER_PROVISIONING_APP_STRING, "");
            }
        }
        return "";
    }

    boolean isCbrsSupported() {
        return getResources().getBoolean(com.android.internal.R.bool.config_cbrs_supported);
    }

    void updateCbrsDataState(boolean state) {
        Log.d(TAG, "setCbrsDataSwitchState() state:" + ((state) ? "on" : "off"));
        if (mTelephonyManager != null) {
            mQueuedWork.execute(
                    new Runnable() {
                        public void run() {
                            mTelephonyManager.setOpportunisticNetworkState(state);
                            mHandler.post(() -> mCbrsDataSwitch.setChecked(getCbrsDataState()));
                        }
                    });
        }
    }

    boolean getCbrsDataState() {
        boolean state = false;
        if (mTelephonyManager != null) {
            state = mTelephonyManager.isOpportunisticNetworkEnabled();
        }
        Log.d(TAG, "getCbrsDataState() state:" + ((state) ? "on" : "off"));
        return state;
    }

    OnCheckedChangeListener mCbrsDataSwitchChangeListener =
            new OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    updateCbrsDataState(isChecked);
                }
            };

    private void showDsdsChangeDialog() {
        final AlertDialog confirmDialog = new Builder(RadioInfo.this)
                .setTitle(R.string.dsds_dialog_title)
                .setMessage(R.string.dsds_dialog_message)
                .setPositiveButton(R.string.dsds_dialog_confirm, mOnDsdsDialogConfirmedListener)
                .setNegativeButton(R.string.dsds_dialog_cancel, mOnDsdsDialogConfirmedListener)
                .create();
        confirmDialog.show();
    }

    private void performDsdsSwitch() {
        mTelephonyManager.switchMultiSimConfig(mDsdsSwitch.isChecked() ? 2 : 1);
    }

    DialogInterface.OnClickListener mOnDsdsDialogConfirmedListener =
            new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (which == DialogInterface.BUTTON_POSITIVE) {
                        mDsdsSwitch.toggle();
                        performDsdsSwitch();
                    }
                }
            };

    OnCheckedChangeListener mRemovableEsimChangeListener =
            new OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    setRemovableEsimAsDefaultEuicc(isChecked);
                }
            };

    private void setRemovableEsimAsDefaultEuicc(boolean isChecked) {
        Log.d(TAG, "setRemovableEsimAsDefaultEuicc isChecked: " + isChecked);
        mTelephonyManager.setRemovableEsimAsDefaultEuicc(isChecked);
        // TODO(b/232528117): Instead of sending intent, add new APIs in platform,
        //  LPA can directly use the API.
        ComponentInfo componentInfo = EuiccConnector.findBestComponent(getPackageManager());
        if (componentInfo == null) {
            Log.d(TAG, "setRemovableEsimAsDefaultEuicc: unable to find suitable component info");
            return;
        }
        final Intent intent = new Intent(ACTION_REMOVABLE_ESIM_AS_DEFAULT);
        intent.setPackage(componentInfo.packageName);
        intent.putExtra("isDefault", isChecked);
        sendBroadcast(intent);
    }

    private boolean isImsSupportedOnDevice() {
        return getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY_IMS);
    }

    private void updateServiceEnabledByPlatform() {
        if (!SubscriptionManager.isValidSubscriptionId(mSubId)) {
            log("updateServiceEnabledByPlatform subscription ID is invalid");
            return;
        }

        ImsMmTelManager imsMmTelManager = mImsManager.getImsMmTelManager(mSubId);
        try {
            imsMmTelManager.isSupported(
                    CAPABILITY_TYPE_VOICE,
                    AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                    getMainExecutor(),
                    (result) -> {
                        updateVolteProvisionedSwitch(result);
                    });
            imsMmTelManager.isSupported(
                    CAPABILITY_TYPE_VIDEO,
                    AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                    getMainExecutor(),
                    (result) -> {
                        updateVtProvisionedSwitch(result);
                    });
            imsMmTelManager.isSupported(
                    CAPABILITY_TYPE_VOICE,
                    AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                    getMainExecutor(),
                    (result) -> {
                        updateWfcProvisionedSwitch(result);
                    });
        } catch (ImsException e) {
            e.printStackTrace();
        }
    }
}
