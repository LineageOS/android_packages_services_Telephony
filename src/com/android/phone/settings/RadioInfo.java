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
import static android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_3G;
import static android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_CROSS_SIM;
import static android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN;
import static android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_LTE;
import static android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_NR;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.annotation.NonNull;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
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
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.telephony.AccessNetworkConstants;
import android.telephony.CarrierConfigManager;
import android.telephony.CellIdentityCdma;
import android.telephony.CellIdentityGsm;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityNr;
import android.telephony.CellIdentityWcdma;
import android.telephony.CellInfo;
import android.telephony.CellInfoCdma;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.CellInfoWcdma;
import android.telephony.CellSignalStrength;
import android.telephony.CellSignalStrengthCdma;
import android.telephony.CellSignalStrengthGsm;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthNr;
import android.telephony.CellSignalStrengthWcdma;
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
import android.telephony.satellite.SatelliteManager;
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
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AlertDialog.Builder;
import androidx.appcompat.app.AppCompatActivity;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.euicc.EuiccConnector;
import com.android.internal.telephony.satellite.SatelliteServiceUtils;
import com.android.phone.R;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 * Allows user to read and alter some of the radio related information.
 *
 */
public class RadioInfo extends AppCompatActivity {
    private static final String TAG = "RadioInfo";

    private static final boolean IS_USER_BUILD = "user".equals(Build.TYPE);

    private static final String[] PREFERRED_NETWORK_LABELS = {
            "GSM/WCDMA preferred",
            "GSM only",
            "WCDMA only",
            "GSM/WCDMA auto (PRL)",
            "CDMA/EvDo auto (PRL)",
            "CDMA only",
            "EvDo only",
            "CDMA/EvDo/GSM/WCDMA (PRL)",
            "CDMA + LTE/EvDo (PRL)",
            "GSM/WCDMA/LTE (PRL)",
            "LTE/CDMA/EvDo/GSM/WCDMA (PRL)",
            "LTE only",
            "LTE/WCDMA",
            "TDSCDMA only",
            "TDSCDMA/WCDMA",
            "LTE/TDSCDMA",
            "TDSCDMA/GSM",
            "LTE/TDSCDMA/GSM",
            "TDSCDMA/GSM/WCDMA",
            "LTE/TDSCDMA/WCDMA",
            "LTE/TDSCDMA/GSM/WCDMA",
            "TDSCDMA/CDMA/EvDo/GSM/WCDMA ",
            "LTE/TDSCDMA/CDMA/EvDo/GSM/WCDMA",
            "NR only",
            "NR/LTE",
            "NR/LTE/CDMA/EvDo",
            "NR/LTE/GSM/WCDMA",
            "NR/LTE/CDMA/EvDo/GSM/WCDMA",
            "NR/LTE/WCDMA",
            "NR/LTE/TDSCDMA",
            "NR/LTE/TDSCDMA/GSM",
            "NR/LTE/TDSCDMA/WCDMA",
            "NR/LTE/TDSCDMA/GSM/WCDMA",
            "NR/LTE/TDSCDMA/CDMA/EvDo/GSM/WCDMA",
            "Unknown"
    };

    private static final Integer[] SIGNAL_STRENGTH_LEVEL = new Integer[] {
            -1 /*clear mock*/,
            CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN,
            CellSignalStrength.SIGNAL_STRENGTH_POOR,
            CellSignalStrength.SIGNAL_STRENGTH_MODERATE,
            CellSignalStrength.SIGNAL_STRENGTH_GOOD,
            CellSignalStrength.SIGNAL_STRENGTH_GREAT
    };
    private static final Integer[] MOCK_DATA_NETWORK_TYPE = new Integer[] {
            -1 /*clear mock*/,
            ServiceState.RIL_RADIO_TECHNOLOGY_GPRS,
            ServiceState.RIL_RADIO_TECHNOLOGY_EDGE,
            ServiceState.RIL_RADIO_TECHNOLOGY_UMTS,
            ServiceState.RIL_RADIO_TECHNOLOGY_IS95A,
            ServiceState.RIL_RADIO_TECHNOLOGY_IS95B,
            ServiceState.RIL_RADIO_TECHNOLOGY_1xRTT,
            ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_0,
            ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_A,
            ServiceState.RIL_RADIO_TECHNOLOGY_HSDPA,
            ServiceState.RIL_RADIO_TECHNOLOGY_HSUPA,
            ServiceState.RIL_RADIO_TECHNOLOGY_HSPA,
            ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_B,
            ServiceState.RIL_RADIO_TECHNOLOGY_EHRPD,
            ServiceState.RIL_RADIO_TECHNOLOGY_LTE,
            ServiceState.RIL_RADIO_TECHNOLOGY_HSPAP,
            ServiceState.RIL_RADIO_TECHNOLOGY_GSM,
            ServiceState.RIL_RADIO_TECHNOLOGY_TD_SCDMA,
            ServiceState.RIL_RADIO_TECHNOLOGY_LTE_CA,
            ServiceState.RIL_RADIO_TECHNOLOGY_NR
    };
    private static String[] sPhoneIndexLabels = new String[0];

    private static final int sCellInfoListRateDisabled = Integer.MAX_VALUE;
    private static final int sCellInfoListRateMax = 0;

    private static final String OEM_RADIO_INFO_INTENT =
            "com.android.phone.settings.OEM_RADIO_INFO";

    private static final String DSDS_MODE_PROPERTY = "ro.boot.hardware.dsds";

    /**
     * A value indicates the device is always on dsds mode.
     * @see {@link #DSDS_MODE_PROPERTY}
     */
    private static final int ALWAYS_ON_DSDS_MODE = 1;

    //Values in must match CELL_INFO_REFRESH_RATES
    private static final String[] CELL_INFO_REFRESH_RATE_LABELS = {
            "Disabled",
            "Immediate",
            "Min 5s",
            "Min 10s",
            "Min 60s"
    };

    //Values in seconds, must match CELL_INFO_REFRESH_RATE_LABELS
    private static final int [] CELL_INFO_REFRESH_RATES = {
        sCellInfoListRateDisabled,
        sCellInfoListRateMax,
        5000,
        10000,
        60000
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

    private static final int MENU_ITEM_VIEW_ADN            = 1;
    private static final int MENU_ITEM_VIEW_FDN            = 2;
    private static final int MENU_ITEM_VIEW_SDN            = 3;
    private static final int MENU_ITEM_GET_IMS_STATUS      = 4;
    private static final int MENU_ITEM_TOGGLE_DATA         = 5;

    private static final String CARRIER_PROVISIONING_ACTION =
            "com.android.phone.settings.CARRIER_PROVISIONING";
    private static final String TRIGGER_CARRIER_PROVISIONING_ACTION =
            "com.android.phone.settings.TRIGGER_CARRIER_PROVISIONING";

    private static final String ACTION_REMOVABLE_ESIM_AS_DEFAULT =
            "android.telephony.euicc.action.REMOVABLE_ESIM_AS_DEFAULT";

    private TextView mDeviceId; //DeviceId is the IMEI in GSM and the MEID in CDMA
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
    private Switch mRadioPowerOnSwitch;
    private Switch mSimulateOutOfServiceSwitch;
    private Switch mEnforceSatelliteChannel;
    private Switch mMockSatellite;
    private Switch mMockSatelliteDataSwitch;
    private RadioGroup mMockSatelliteData;
    private Button mPingTestButton;
    private Button mUpdateSmscButton;
    private Button mRefreshSmscButton;
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
    private List<CellInfo> mCellInfoResult = null;
    private final boolean[] mSimulateOos = new boolean[2];
    private int[] mSelectedSignalStrengthIndex = new int[2];
    private int[] mSelectedMockDataNetworkTypeIndex = new int[2];
    private String mEuiccInfoResult = "";

    private int mPreferredNetworkTypeResult;
    private int mCellInfoRefreshRateIndex;
    private int mPhoneId = SubscriptionManager.INVALID_PHONE_INDEX;
    private static final int DEFAULT_PHONE_ID = 0;

    private int mSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;

    private String mActionEsos;
    private String mActionEsosDemo;
    private Intent mNonEsosIntent;
    private TelephonyDisplayInfo mDisplayInfo;
    private CarrierConfigManager mCarrierConfigManager;

    private List<PhysicalChannelConfig> mPhysicalChannelConfigs = new ArrayList<>();

    private final NetworkRequest mDefaultNetworkRequest = new NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build();

    private final NetworkCallback mNetworkCallback = new NetworkCallback() {
        public void onCapabilitiesChanged(Network n, NetworkCapabilities nc) {
            int dlbw = nc.getLinkDownstreamBandwidthKbps();
            int ulbw = nc.getLinkUpstreamBandwidthKbps();
            updateBandwidths(dlbw, ulbw);
        }
    };

    private static final int DEFAULT_TIMEOUT_MS = 1000;

    // not final because we need to recreate this object to register on a new subId (b/117555407)
    private TelephonyCallback mTelephonyCallback = new RadioInfoTelephonyCallback();
    private class RadioInfoTelephonyCallback extends TelephonyCallback implements
            TelephonyCallback.DataConnectionStateListener,
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
        if (type >= PREFERRED_NETWORK_LABELS.length || type < 0) {
            log("Network type: unknown type value=" + type);
            type = PREFERRED_NETWORK_LABELS.length - 1; //set to Unknown
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

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            AsyncResult ar;
            switch (msg.what) {
                case EVENT_QUERY_SMSC_DONE:
                    ar = (AsyncResult) msg.obj;
                    if (ar.exception != null) {
                        mSmsc.setText("refresh error");
                    } else {
                        mSmsc.setText((String) ar.result);
                    }
                    break;
                case EVENT_UPDATE_SMSC_DONE:
                    mUpdateSmscButton.setEnabled(true);
                    ar = (AsyncResult) msg.obj;
                    if (ar.exception != null) {
                        mSmsc.setText("update error");
                    }
                    break;
                case EVENT_UPDATE_NR_STATS:
                    log("got EVENT_UPDATE_NR_STATS");
                    updateNrStats();
                    break;
                default:
                    super.handleMessage(msg);
                    break;

            }
        }
    };

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        SettingsConstants.setupEdgeToEdge(this);
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

        mQueuedWork = new ThreadPoolExecutor(1, 1, RUNNABLE_TIMEOUT_MS, TimeUnit.MICROSECONDS,
                new LinkedBlockingDeque<>());
        mConnectivityManager = getSystemService(ConnectivityManager.class);
        if (mSystemUser) {
            mPhone = getPhone(SubscriptionManager.getDefaultSubscriptionId());
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

        sPhoneIndexLabels = getPhoneIndexLabels(mTelephonyManager);

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
        ArrayAdapter<String> mPreferredNetworkTypeAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, PREFERRED_NETWORK_LABELS);
        mPreferredNetworkTypeAdapter
                .setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mPreferredNetworkType.setAdapter(mPreferredNetworkTypeAdapter);

        mMockSignalStrength = (Spinner) findViewById(R.id.signalStrength);
        if (!Build.isDebuggable() || !mSystemUser) {
            mMockSignalStrength.setVisibility(View.GONE);
            findViewById(R.id.signalStrength).setVisibility(View.GONE);
            findViewById(R.id.signal_strength_label).setVisibility(View.GONE);
        } else {
            ArrayAdapter<Integer> mSignalStrengthAdapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item, SIGNAL_STRENGTH_LEVEL);
            mSignalStrengthAdapter
                    .setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            mMockSignalStrength.setAdapter(mSignalStrengthAdapter);
        }

        mMockDataNetworkType = (Spinner) findViewById(R.id.dataNetworkType);
        if (!Build.isDebuggable() || !mSystemUser) {
            mMockDataNetworkType.setVisibility(View.GONE);
            findViewById(R.id.dataNetworkType).setVisibility(View.GONE);
            findViewById(R.id.data_network_type_label).setVisibility(View.GONE);
        } else {
            ArrayAdapter<String> mNetworkTypeAdapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item, Arrays.stream(MOCK_DATA_NETWORK_TYPE)
                    .map(ServiceState::rilRadioTechnologyToString).toArray(String[]::new));
            mNetworkTypeAdapter
                    .setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
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
        if (isDsdsSupported() && !dsdsModeOnly()) {
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
            mDsdsSwitch.setChecked(isDsdsEnabled());
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
        if (shouldHideNonEmergencyMode()) {
            mSatelliteEnableNonEmergencyModeButton.setVisibility(View.GONE);
        } else {
            mSatelliteEnableNonEmergencyModeButton.setOnClickListener(v -> {
                if (mNonEsosIntent != null) {
                    sendBroadcast(mNonEsosIntent);
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

        mCellInfoRefreshRateIndex = 0; //disabled
        mPreferredNetworkTypeResult = PREFERRED_NETWORK_LABELS.length - 1; //Unknown

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
            parentActivity = (new Intent()).setClassName("com.android.settings",
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

        mPreferredNetworkTypeResult = b.getInt("mPreferredNetworkTypeResult",
                PREFERRED_NETWORK_LABELS.length - 1);

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
        menu.add(1, MENU_ITEM_VIEW_ADN, 0,
                R.string.radioInfo_menu_viewADN).setOnMenuItemClickListener(mViewADNCallback);
        menu.add(1, MENU_ITEM_VIEW_FDN, 0,
                R.string.radioInfo_menu_viewFDN).setOnMenuItemClickListener(mViewFDNCallback);
        menu.add(1, MENU_ITEM_VIEW_SDN, 0,
                R.string.radioInfo_menu_viewSDN).setOnMenuItemClickListener(mViewSDNCallback);
        if (isImsSupportedOnDevice()) {
            menu.add(1, MENU_ITEM_GET_IMS_STATUS,
                    0, R.string.radioInfo_menu_getIMS).setOnMenuItemClickListener(mGetImsStatus);
        }
        menu.add(1, MENU_ITEM_TOGGLE_DATA,
                0, R.string.radio_info_data_connection_disable)
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
            if (mSimulateOos[mPhoneId])  {
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
                mOnMockSignalStrengthSelectedListener.onItemSelected(null, null, 0/*pos*/, 0);
            }
            if (mSelectedMockDataNetworkTypeIndex[mPhoneId] > 0) {
                mOnMockDataNetworkTypeSelectedListener.onItemSelected(null, null, 0/*pos*/, 0);
            }
        }
    }

    // returns array of string labels for each phone index. The array index is equal to the phone
    // index.
    private static String[] getPhoneIndexLabels(TelephonyManager tm) {
        int phones = tm.getPhoneCount();
        String[] labels = new String[phones];
        for (int i = 0; i < phones; i++) {
            labels[i] = "Phone " + i;
        }
        return labels;
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
        mTelephonyManager.registerTelephonyCallback(new HandlerExecutor(mHandler),
                mTelephonyCallback);
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

        mDBm.setText(String.valueOf(signalDbm) + " "
                + r.getString(R.string.radioInfo_display_dbm) + "   "
                + String.valueOf(signalAsu) + " "
                + r.getString(R.string.radioInfo_display_asu));
    }

    private String getCellInfoDisplayString(int i) {
        return (i != Integer.MAX_VALUE) ? Integer.toString(i) : "";
    }

    private String getCellInfoDisplayString(long i) {
        return (i != Long.MAX_VALUE) ? Long.toString(i) : "";
    }

    private String getConnectionStatusString(CellInfo ci) {
        String regStr = "";
        String connStatStr = "";
        String connector = "";

        if (ci.isRegistered()) {
            regStr = "R";
        }
        switch (ci.getCellConnectionStatus()) {
            case CellInfo.CONNECTION_PRIMARY_SERVING: connStatStr = "P"; break;
            case CellInfo.CONNECTION_SECONDARY_SERVING: connStatStr = "S"; break;
            case CellInfo.CONNECTION_NONE: connStatStr = "N"; break;
            case CellInfo.CONNECTION_UNKNOWN: /* Field is unsupported */ break;
            default: break;
        }
        if (!TextUtils.isEmpty(regStr) && !TextUtils.isEmpty(connStatStr)) {
            connector = "+";
        }

        return regStr + connector + connStatStr;
    }

    private String buildCdmaInfoString(CellInfoCdma ci) {
        CellIdentityCdma cidCdma = ci.getCellIdentity();
        CellSignalStrengthCdma ssCdma = ci.getCellSignalStrength();

        return String.format("%-3.3s %-5.5s %-5.5s %-5.5s %-6.6s %-6.6s %-6.6s %-6.6s %-5.5s",
                getConnectionStatusString(ci),
                getCellInfoDisplayString(cidCdma.getSystemId()),
                getCellInfoDisplayString(cidCdma.getNetworkId()),
                getCellInfoDisplayString(cidCdma.getBasestationId()),
                getCellInfoDisplayString(ssCdma.getCdmaDbm()),
                getCellInfoDisplayString(ssCdma.getCdmaEcio()),
                getCellInfoDisplayString(ssCdma.getEvdoDbm()),
                getCellInfoDisplayString(ssCdma.getEvdoEcio()),
                getCellInfoDisplayString(ssCdma.getEvdoSnr()));
    }

    private String buildGsmInfoString(CellInfoGsm ci) {
        CellIdentityGsm cidGsm = ci.getCellIdentity();
        CellSignalStrengthGsm ssGsm = ci.getCellSignalStrength();

        return String.format("%-3.3s %-3.3s %-3.3s %-5.5s %-5.5s %-6.6s %-4.4s %-4.4s\n",
                getConnectionStatusString(ci),
                getCellInfoDisplayString(cidGsm.getMcc()),
                getCellInfoDisplayString(cidGsm.getMnc()),
                getCellInfoDisplayString(cidGsm.getLac()),
                getCellInfoDisplayString(cidGsm.getCid()),
                getCellInfoDisplayString(cidGsm.getArfcn()),
                getCellInfoDisplayString(cidGsm.getBsic()),
                getCellInfoDisplayString(ssGsm.getDbm()));
    }

    private String buildLteInfoString(CellInfoLte ci) {
        CellIdentityLte cidLte = ci.getCellIdentity();
        CellSignalStrengthLte ssLte = ci.getCellSignalStrength();

        return String.format(
                "%-3.3s %-3.3s %-3.3s %-5.5s %-5.5s %-3.3s %-6.6s %-2.2s %-4.4s %-4.4s %-2.2s\n",
                getConnectionStatusString(ci),
                getCellInfoDisplayString(cidLte.getMcc()),
                getCellInfoDisplayString(cidLte.getMnc()),
                getCellInfoDisplayString(cidLte.getTac()),
                getCellInfoDisplayString(cidLte.getCi()),
                getCellInfoDisplayString(cidLte.getPci()),
                getCellInfoDisplayString(cidLte.getEarfcn()),
                getCellInfoDisplayString(cidLte.getBandwidth()),
                getCellInfoDisplayString(ssLte.getDbm()),
                getCellInfoDisplayString(ssLte.getRsrq()),
                getCellInfoDisplayString(ssLte.getTimingAdvance()));
    }

    private String buildNrInfoString(CellInfoNr ci) {
        CellIdentityNr cidNr = (CellIdentityNr) ci.getCellIdentity();
        CellSignalStrengthNr ssNr = (CellSignalStrengthNr) ci.getCellSignalStrength();

        return String.format(
                "%-3.3s %-3.3s %-3.3s %-5.5s %-5.5s %-3.3s %-6.6s %-4.4s %-4.4s\n",
                getConnectionStatusString(ci),
                cidNr.getMccString(),
                cidNr.getMncString(),
                getCellInfoDisplayString(cidNr.getTac()),
                getCellInfoDisplayString(cidNr.getNci()),
                getCellInfoDisplayString(cidNr.getPci()),
                getCellInfoDisplayString(cidNr.getNrarfcn()),
                getCellInfoDisplayString(ssNr.getSsRsrp()),
                getCellInfoDisplayString(ssNr.getSsRsrq()));
    }

    private String buildWcdmaInfoString(CellInfoWcdma ci) {
        CellIdentityWcdma cidWcdma = ci.getCellIdentity();
        CellSignalStrengthWcdma ssWcdma = ci.getCellSignalStrength();

        return String.format("%-3.3s %-3.3s %-3.3s %-5.5s %-5.5s %-6.6s %-3.3s %-4.4s\n",
                getConnectionStatusString(ci),
                getCellInfoDisplayString(cidWcdma.getMcc()),
                getCellInfoDisplayString(cidWcdma.getMnc()),
                getCellInfoDisplayString(cidWcdma.getLac()),
                getCellInfoDisplayString(cidWcdma.getCid()),
                getCellInfoDisplayString(cidWcdma.getUarfcn()),
                getCellInfoDisplayString(cidWcdma.getPsc()),
                getCellInfoDisplayString(ssWcdma.getDbm()));
    }

    private String buildCellInfoString(List<CellInfo> arrayCi) {
        String value = new String();
        StringBuilder cdmaCells = new StringBuilder(),
                gsmCells = new StringBuilder(),
                lteCells = new StringBuilder(),
                wcdmaCells = new StringBuilder(),
                nrCells = new StringBuilder();

        if (arrayCi != null) {
            for (CellInfo ci : arrayCi) {

                if (ci instanceof CellInfoLte) {
                    lteCells.append(buildLteInfoString((CellInfoLte) ci));
                } else if (ci instanceof CellInfoWcdma) {
                    wcdmaCells.append(buildWcdmaInfoString((CellInfoWcdma) ci));
                } else if (ci instanceof CellInfoGsm) {
                    gsmCells.append(buildGsmInfoString((CellInfoGsm) ci));
                } else if (ci instanceof CellInfoCdma) {
                    cdmaCells.append(buildCdmaInfoString((CellInfoCdma) ci));
                } else if (ci instanceof CellInfoNr) {
                    nrCells.append(buildNrInfoString((CellInfoNr) ci));
                }
            }
            if (nrCells.length() != 0) {
                value += String.format(
                        "NR\n%-3.3s %-3.3s %-3.3s %-5.5s %-5.5s %-3.3s"
                                + " %-6.6s %-4.4s %-4.4s\n",
                        "SRV", "MCC", "MNC", "TAC", "NCI", "PCI",
                        "NRARFCN", "SS-RSRP", "SS-RSRQ");
                value += nrCells.toString();
            }

            if (lteCells.length() != 0) {
                value += String.format(
                        "LTE\n%-3.3s %-3.3s %-3.3s %-5.5s %-5.5s %-3.3s"
                                + " %-6.6s %-2.2s %-4.4s %-4.4s %-2.2s\n",
                        "SRV", "MCC", "MNC", "TAC", "CID", "PCI",
                        "EARFCN", "BW", "RSRP", "RSRQ", "TA");
                value += lteCells.toString();
            }
            if (wcdmaCells.length() != 0) {
                value += String.format(
                        "WCDMA\n%-3.3s %-3.3s %-3.3s %-5.5s %-5.5s %-6.6s %-3.3s %-4.4s\n",
                        "SRV", "MCC", "MNC", "LAC", "CID", "UARFCN", "PSC", "RSCP");
                value += wcdmaCells.toString();
            }
            if (gsmCells.length() != 0) {
                value += String.format(
                        "GSM\n%-3.3s %-3.3s %-3.3s %-5.5s %-5.5s %-6.6s %-4.4s %-4.4s\n",
                        "SRV", "MCC", "MNC", "LAC", "CID", "ARFCN", "BSIC", "RSSI");
                value += gsmCells.toString();
            }
            if (cdmaCells.length() != 0) {
                value += String.format(
                        "CDMA/EVDO\n%-3.3s %-5.5s %-5.5s %-5.5s"
                                + " %-6.6s %-6.6s %-6.6s %-6.6s %-5.5s\n",
                        "SRV", "SID", "NID", "BSID",
                        "C-RSSI", "C-ECIO", "E-RSSI", "E-ECIO", "E-SNR");
                value += cdmaCells.toString();
            }
        } else {
            value = "unknown";
        }

        return value.toString();
    }

    private void updateCellInfo(List<CellInfo> arrayCi) {
        mCellInfo.setText(buildCellInfoString(arrayCi));
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
        if (SubscriptionManager.isValidPhoneId(mPhoneId)
                && mSm.isActiveSubscriptionId(mSubId)) {
            mDataNetwork.setText(ServiceState.rilRadioTechnologyToString(
                    mTelephonyManager.getServiceStateForSlot(mPhoneId)
                            .getRilDataRadioTechnology()));
            mVoiceNetwork.setText(ServiceState.rilRadioTechnologyToString(
                    mTelephonyManager.getServiceStateForSlot(mPhoneId)
                            .getRilVoiceRadioTechnology()));
            int overrideNetwork = (mDisplayInfo != null
                    && SubscriptionManager.isValidSubscriptionId(mSubId))
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

        mVoiceRawReg.setText(getRawRegistrationStateText(ss, NetworkRegistrationInfo.DOMAIN_CS,
                    AccessNetworkConstants.TRANSPORT_TYPE_WWAN));
        mDataRawReg.setText(getRawRegistrationStateText(ss, NetworkRegistrationInfo.DOMAIN_PS,
                    AccessNetworkConstants.TRANSPORT_TYPE_WWAN));
        mWlanDataRawReg.setText(getRawRegistrationStateText(ss, NetworkRegistrationInfo.DOMAIN_PS,
                    AccessNetworkConstants.TRANSPORT_TYPE_WLAN));
    }

    private void updateNrStats() {
        if ((mTelephonyManager.getSupportedRadioAccessFamily()
                & TelephonyManager.NETWORK_TYPE_BITMASK_NR) == 0) {
            return;
        }
        ServiceState ss = mTelephonyManager.getServiceStateForSlot(mPhoneId);
        if (ss != null) {
            NetworkRegistrationInfo nri = ss.getNetworkRegistrationInfo(
                    NetworkRegistrationInfo.DOMAIN_PS, AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
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
        s = subMgr.getPhoneNumber(subId)
                + " { CARRIER:"
                + subMgr.getPhoneNumber(subId, SubscriptionManager.PHONE_NUMBER_SOURCE_CARRIER)
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
        long txBytes   = TrafficStats.getMobileTxBytes();
        long rxBytes   = TrafficStats.getMobileRxBytes();

        String packets = r.getString(R.string.radioInfo_display_packets);
        String bytes   = r.getString(R.string.radioInfo_display_bytes);

        mSent.setText(txPackets + " " + packets + ", " + txBytes + " " + bytes);
        mReceived.setText(rxPackets + " " + packets + ", " + rxBytes + " " + bytes);
    }

    private void updateEuiccInfo() {
        final Runnable setEuiccInfo = new Runnable() {
            public void run() {
                mEuiccInfo.setText(mEuiccInfoResult);
            }
        };

        mQueuedWork.execute(new Runnable() {
            @Override
            public void run() {
                if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY_EUICC)) {
                    mEuiccInfoResult = "Euicc Feature is disabled";
                } else if (mEuiccManager == null || !mEuiccManager.isEnabled()) {
                    mEuiccInfoResult = "EuiccManager is not enabled";
                } else {
                    try {
                        mEuiccInfoResult = " { Available memory in bytes:"
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

    /**
     *  Ping a host name
     */
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

    /**
     * This function checks for basic functionality of HTTP Client.
     */
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
        mQueuedWork.execute(() -> {
            if (mSystemUser) {
                mPhone.getSmscAddress(mHandler.obtainMessage(EVENT_QUERY_SMSC_DONE));
            }
        });
    }

    private void updateAllCellInfo() {

        mCellInfo.setText("");

        final Runnable updateAllCellInfoResults = new Runnable() {
            public void run() {
                updateCellInfo(mCellInfoResult);
            }
        };

        mQueuedWork.execute(new Runnable() {
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

        final Runnable updatePingResults = new Runnable() {
            public void run() {
                mPingHostnameV4.setText(mPingHostnameResultV4);
                mPingHostnameV6.setText(mPingHostnameResultV6);
                mHttpClientTest.setText(mHttpClientTestResult);
            }
        };

        Thread hostname = new Thread() {
            @Override
            public void run() {
                pingHostname();
                mHandler.post(updatePingResults);
            }
        };
        hostname.start();

        Thread httpClient = new Thread() {
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
                    intent.setClassName("com.android.phone",
                            "com.android.phone.settings.fdn.FdnList");
                    startActivityAsUser(intent, UserHandle.CURRENT);
                    return true;
                }
            };

    private MenuItem.OnMenuItemClickListener mViewSDNCallback =
            new MenuItem.OnMenuItemClickListener() {
                public boolean onMenuItemClick(MenuItem item) {
                    Intent intent = new Intent(
                            Intent.ACTION_VIEW, Uri.parse("content://icc/sdn"));
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
                        availableVolte = isVoiceServiceAvailable(imsMmTelManager);
                        availableVt = isVideoServiceAvailable(imsMmTelManager);
                        availableWfc = isWfcServiceAvailable(imsMmTelManager);
                        CountDownLatch latch = new CountDownLatch(1);
                        try {
                            HandlerThread handlerThread = new HandlerThread("RadioInfo");
                            handlerThread.start();
                            imsMmTelManager.isSupported(
                                    MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_UT,
                                    AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                                    handlerThread.getThreadExecutor(), (result) -> {
                                        latch.countDown();
                                        availableUt.set(result);
                                    });
                            latch.await(2, TimeUnit.SECONDS);
                            handlerThread.quit();
                        } catch (Exception e) {
                            loge("Failed to get UT state.");
                        }
                    }

                    final String imsRegString = isImsRegistered
                            ? getString(R.string.radio_info_ims_reg_status_registered)
                            : getString(R.string.radio_info_ims_reg_status_not_registered);

                    final String available = getString(
                            R.string.radio_info_ims_feature_status_available);
                    final String unavailable = getString(
                            R.string.radio_info_ims_feature_status_unavailable);

                    String imsStatus = getString(R.string.radio_info_ims_reg_status,
                            imsRegString,
                            availableVolte ? available : unavailable,
                            availableWfc ? available : unavailable,
                            availableVt ? available : unavailable,
                            availableUt.get() ? available : unavailable);

                    AlertDialog imsDialog = new  AlertDialog.Builder(RadioInfo.this)
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
                case TelephonyManager.DATA_CONNECTED:
                    mTelephonyManager.setDataEnabled(false);
                    break;
                case TelephonyManager.DATA_DISCONNECTED:
                    mTelephonyManager.setDataEnabled(true);
                    break;
                default:
                    // do nothing
                    break;
            }
            return true;
        }
    };

    private boolean isRadioOn() {
        return mTelephonyManager.getRadioPowerState() == TelephonyManager.RADIO_POWER_ON;
    }

    private void updateRadioPowerState() {
        //delightful hack to prevent on-checked-changed calls from
        //actually forcing the radio preference to its transient/current value.
        mRadioPowerOnSwitch.setOnCheckedChangeListener(null);
        mRadioPowerOnSwitch.setChecked(isRadioOn());
        mRadioPowerOnSwitch.setOnCheckedChangeListener(mRadioPowerOnChangeListener);
    }

    private void setImsVolteProvisionedState(boolean state) {
        Log.d(TAG, "setImsVolteProvisioned state: " + ((state) ? "on" : "off"));
        setImsConfigProvisionedState(CAPABILITY_TYPE_VOICE,
                REGISTRATION_TECH_LTE, state);
    }

    private void setImsVtProvisionedState(boolean state) {
        Log.d(TAG, "setImsVtProvisioned() state: " + ((state) ? "on" : "off"));
        setImsConfigProvisionedState(CAPABILITY_TYPE_VIDEO,
                REGISTRATION_TECH_LTE, state);
    }

    private void setImsWfcProvisionedState(boolean state) {
        Log.d(TAG, "setImsWfcProvisioned() state: " + ((state) ? "on" : "off"));
        setImsConfigProvisionedState(CAPABILITY_TYPE_VOICE,
                REGISTRATION_TECH_IWLAN, state);
    }

    private void setEabProvisionedState(boolean state) {
        Log.d(TAG, "setEabProvisioned() state: " + ((state) ? "on" : "off"));
        setRcsConfigProvisionedState(ImsRcsManager.CAPABILITY_TYPE_PRESENCE_UCE,
                REGISTRATION_TECH_LTE, state);
    }

    private void setImsConfigProvisionedState(int capability, int tech, boolean state) {
        if (mProvisioningManager != null) {
            mQueuedWork.execute(new Runnable() {
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
            mQueuedWork.execute(new Runnable() {
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
        return isImsConfigProvisioningRequired(
                CAPABILITY_TYPE_VOICE,
                REGISTRATION_TECH_LTE);
    }

    private boolean isImsVtProvisioningRequired() {
        return isImsConfigProvisioningRequired(
                CAPABILITY_TYPE_VIDEO,
                REGISTRATION_TECH_LTE);
    }

    private boolean isImsWfcProvisioningRequired() {
        return isImsConfigProvisioningRequired(
                CAPABILITY_TYPE_VOICE,
                REGISTRATION_TECH_IWLAN);
    }

    private boolean isEabProvisioningRequired() {
        return isRcsConfigProvisioningRequired(
                ImsRcsManager.CAPABILITY_TYPE_PRESENCE_UCE,
                REGISTRATION_TECH_LTE);
    }

    private boolean isImsConfigProvisioningRequired(int capability, int tech) {
        if (mProvisioningManager != null) {
            try {
                return mProvisioningManager.isProvisioningRequiredForCapability(
                        capability, tech);
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

    OnCheckedChangeListener mRadioPowerOnChangeListener = new OnCheckedChangeListener() {
        @Override
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            // TODO: b/145681511. Within current design, radio power on all of the phones need
            // to be controlled at the same time.
            Phone[] phones = PhoneFactory.getPhones();
            if (phones == null) {
                return;
            }
            log("toggle radio power: phone*" + phones.length + " " + (isRadioOn() ? "on" : "off"));
            for (int phoneIndex = 0; phoneIndex < phones.length; phoneIndex++) {
                if (phones[phoneIndex] != null) {
                    phones[phoneIndex].setRadioPower(isChecked);
                }
            }
        }
    };

    private final OnCheckedChangeListener mSimulateOosOnChangeListener = (bv, isChecked) -> {
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

    private static final int SATELLITE_CHANNEL = 8665;
    private final OnCheckedChangeListener mForceSatelliteChannelOnChangeListener =
            (buttonView, isChecked) -> {

                if (!isValidSubscription(mSubId)) {
                    loge("Force satellite channel invalid subId " + mSubId);
                    return;
                }
                if (getCarrierConfig() == null) {
                    loge("Force satellite channel cm == null");
                    return;
                }
                TelephonyManager tm = mTelephonyManager.createForSubscriptionId(mSubId);
                // To be used in thread in case mPhone changes.
                int subId = mSubId;
                int phoneId = mPhoneId;
                if (isChecked) {
                    (new Thread(() -> {
                        // Override carrier config
                        PersistableBundle originalBundle = getCarrierConfig().getConfigForSubId(
                                subId,
                                KEY_SATELLITE_ATTACH_SUPPORTED_BOOL,
                                KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL,
                                CarrierConfigManager.KEY_EMERGENCY_MESSAGING_SUPPORTED_BOOL
                        );
                        PersistableBundle overrideBundle = new PersistableBundle();
                        overrideBundle.putBoolean(
                                KEY_SATELLITE_ATTACH_SUPPORTED_BOOL, true);
                        overrideBundle.putBoolean(KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL, true);
                        overrideBundle.putBoolean(CarrierConfigManager
                                .KEY_EMERGENCY_MESSAGING_SUPPORTED_BOOL, true);

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

                        // Set force channel selection
                        List<RadioAccessSpecifier> mock = List.of(
                                new RadioAccessSpecifier(
                                        AccessNetworkConstants.AccessNetworkType.EUTRAN,
                                        new int[]{AccessNetworkConstants.EutranBand.BAND_25},
                                        new int[]{SATELLITE_CHANNEL}));
                        try {
                            log("Force satellite channel new channels " + mock);
                            tm.setSystemSelectionChannels(mock);
                        } catch (Exception e) {
                            loge("Force satellite channel failed to set channels " + e);
                            return;
                        }
                        log("Force satellite channel new config " + overrideBundle);
                        getCarrierConfig().overrideConfig(subId, overrideBundle, false);

                        mOriginalSystemChannels[phoneId] = originalBundle;
                        log("Force satellite channel old " + mock  + originalBundle);
                    })).start();
                } else {
                    (new Thread(() -> {
                        try {
                            tm.setSystemSelectionChannels(
                                    Collections.emptyList() /* isSpecifyChannels false */);
                            log("Force satellite channel successfully cleared channels ");
                            tm.setAllowedNetworkTypesForReason(
                                    TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_TEST,
                                    TelephonyManager.getAllNetworkTypesBitmask());
                            log("Force satellite channel successfully reset network type to "
                                    + TelephonyManager.getAllNetworkTypesBitmask());
                            PersistableBundle original = mOriginalSystemChannels[phoneId];
                            if (original != null) {
                                getCarrierConfig().overrideConfig(subId, original, false);
                                log("Force satellite channel successfully restored config to "
                                        + original);
                                mOriginalSystemChannels[phoneId] = null;
                            }
                        } catch (Exception e) {
                            loge("Force satellite channel: Can't clear mock " + e);
                        }
                    })).start();
                }
    };

    private void updateSatelliteChannelDisplay(int phoneId) {
        if (mEnforceSatelliteChannel.isChecked()) return;
        // Assume in testing mode
        (new Thread(() -> {
            TelephonyManager tm = mTelephonyManager.createForSubscriptionId(
                    SubscriptionManager.getSubscriptionId(phoneId));
            try {
                List<RadioAccessSpecifier> channels = tm.getSystemSelectionChannels();
                long networkTypeBitMask = tm.getAllowedNetworkTypesForReason(
                        TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_TEST);
                long lteNetworkBitMask = RadioAccessFamily.getRafFromNetworkType(
                        RILConstants.NETWORK_MODE_LTE_ONLY);
                mHandler.post(() -> {
                    log("Force satellite get channel " + channels
                            + " get networkTypeBitMask " + networkTypeBitMask
                            + " lte " + lteNetworkBitMask);
                    // if SATELLITE_CHANNEL is the current channel
                    mEnforceSatelliteChannel.setChecked(channels.stream().filter(specifier ->
                                    specifier.getRadioAccessNetwork()
                                            == AccessNetworkConstants.AccessNetworkType.EUTRAN)
                            .flatMapToInt(specifier -> Arrays.stream(specifier.getChannels()))
                            .anyMatch(channel -> channel == SATELLITE_CHANNEL)
                            // OR ALLOWED_NETWORK_TYPES_REASON_TEST is LTE only.
                            || (networkTypeBitMask & lteNetworkBitMask) == networkTypeBitMask);
                });
            } catch (Exception e) {
                loge("updateSatelliteChannelDisplay " + e);
            }
        })).start();
    }

    /**
     * This method will check the required carrier config keys which plays role in enabling /
     * supporting satellite data and update the keys accordingly.
     * @param bundleToModify : PersistableBundle
     */
    private void updateCarrierConfigToSupportData(PersistableBundle bundleToModify) {
        // KEY_CARRIER_SUPPORTED_SATELLITE_SERVICES_PER_PROVIDER_BUNDLE key info update
        PersistableBundle providerBundle = bundleToModify.getPersistableBundle(
                KEY_CARRIER_SUPPORTED_SATELLITE_SERVICES_PER_PROVIDER_BUNDLE);
        Map<String, Set<Integer>> satServicesBundle =
                SatelliteServiceUtils.parseSupportedSatelliteServices(providerBundle);
        String plmn = mTelephonyManager.getSimOperatorNumeric(mSubId);
        log("satData: currentplmn = " + plmn);
        if (!satServicesBundle.isEmpty() && satServicesBundle.containsKey(plmn)) {
            Set<Integer> supportedServices = satServicesBundle.get(plmn);
            log("satData: BEFORE supportedServices = " + supportedServices);
            if (!supportedServices.contains(NetworkRegistrationInfo.SERVICE_TYPE_DATA)) {
                supportedServices.add(NetworkRegistrationInfo.SERVICE_TYPE_DATA);
                providerBundle.putIntArray(plmn, supportedServices.stream()
                        .mapToInt(Integer::intValue)
                        .toArray());
                log("satData: AFTER supportedServices = " + supportedServices);
            }
            bundleToModify.putPersistableBundle(
                    KEY_CARRIER_SUPPORTED_SATELLITE_SERVICES_PER_PROVIDER_BUNDLE, providerBundle);
        }

        // KEY_CARRIER_ROAMING_SATELLITE_DEFAULT_SERVICES_INT_ARRAY key info update
        int[] availableServices = bundleToModify.getIntArray(
                KEY_CARRIER_ROAMING_SATELLITE_DEFAULT_SERVICES_INT_ARRAY);
        int[] newServices;
        if (availableServices != null && availableServices.length > 0) {
            if (Arrays.stream(availableServices).anyMatch(
                    element -> element == NetworkRegistrationInfo.SERVICE_TYPE_DATA)) {
                newServices = new int[availableServices.length];
                System.arraycopy(availableServices, 0, newServices, 0, availableServices.length);
            } else {
                newServices = new int[availableServices.length + 1];
                System.arraycopy(availableServices, 0, newServices, 0, availableServices.length);
                newServices[newServices.length - 1] = NetworkRegistrationInfo.SERVICE_TYPE_DATA;
            }
        } else {
            newServices = new int[1];
            newServices[0] = NetworkRegistrationInfo.SERVICE_TYPE_DATA;
        }
        bundleToModify.putIntArray(
                KEY_CARRIER_ROAMING_SATELLITE_DEFAULT_SERVICES_INT_ARRAY,
                newServices);

        // KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL setting to false.
        bundleToModify.putBoolean(KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL,
                false);
        bundleToModify.remove(KEY_SATELLITE_DATA_SUPPORT_MODE_INT);
        log("satData: changing carrierConfig to : " + bundleToModify);
        getCarrierConfig().overrideConfig(mSubId, bundleToModify, false);
    }

    /**
     * Method that restore the previous satellite data mode selection.
     */
    private void updateSatelliteDataButton() {
        if (mSatelliteDataOriginalBundle[mPhoneId] == null) {
            // It executes only at first time
            PersistableBundle originalBundle = getCarrierConfig().getConfigForSubId(mSubId,
                    KEY_SATELLITE_DATA_SUPPORT_MODE_INT,
                    KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL,
                    KEY_CARRIER_ROAMING_SATELLITE_DEFAULT_SERVICES_INT_ARRAY,
                    KEY_CARRIER_SUPPORTED_SATELLITE_SERVICES_PER_PROVIDER_BUNDLE);
            mSatelliteDataOriginalBundle[mPhoneId] = originalBundle;
            log("satData: OriginalConfig = " + originalBundle);
        }
        PersistableBundle currentBundle = getCarrierConfig().getConfigForSubId(mSubId,
                KEY_SATELLITE_DATA_SUPPORT_MODE_INT,
                KEY_CARRIER_ROAMING_SATELLITE_DEFAULT_SERVICES_INT_ARRAY,
                KEY_CARRIER_SUPPORTED_SATELLITE_SERVICES_PER_PROVIDER_BUNDLE);
        int dataMode = currentBundle.getInt(
                KEY_SATELLITE_DATA_SUPPORT_MODE_INT, -1);
        log("satData: present dataMode = " + dataMode);
        if (dataMode != -1) {
            int checkedId = 0;
            switch (dataMode) {
                case CarrierConfigManager.SATELLITE_DATA_SUPPORT_ONLY_RESTRICTED:
                    checkedId = R.id.satellite_data_restricted;
                    break;
                case CarrierConfigManager.SATELLITE_DATA_SUPPORT_BANDWIDTH_CONSTRAINED:
                    checkedId = R.id.satellite_data_constrained;
                    break;
                case CarrierConfigManager.SATELLITE_DATA_SUPPORT_ALL:
                    checkedId = R.id.satellite_data_unConstrained;
                    break;
            }
            mMockSatelliteData.check(checkedId);
        }
        updateCarrierConfigToSupportData(currentBundle);
    }

    private final RadioGroup.OnCheckedChangeListener mMockSatelliteDataListener =
            (group, checkedId) -> {
                int dataMode = CarrierConfigManager.SATELLITE_DATA_SUPPORT_ONLY_RESTRICTED;
                switch (checkedId) {
                    case R.id.satellite_data_restricted:
                        dataMode = CarrierConfigManager.SATELLITE_DATA_SUPPORT_ONLY_RESTRICTED;
                        break;
                    case R.id.satellite_data_constrained:
                        dataMode =
                                CarrierConfigManager.SATELLITE_DATA_SUPPORT_BANDWIDTH_CONSTRAINED;
                        break;
                    case R.id.satellite_data_unConstrained:
                        dataMode = CarrierConfigManager.SATELLITE_DATA_SUPPORT_ALL;
                        break;
                }
                log("satData: OnCheckedChangeListener setting dataMode = " + dataMode);
                if (getCarrierConfig() == null) return;
                PersistableBundle overrideBundle = new PersistableBundle();
                overrideBundle.putInt(KEY_SATELLITE_DATA_SUPPORT_MODE_INT,
                        dataMode);
                overrideBundle.putBoolean(
                        KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL, false);
                if (isValidSubscription(mSubId)) {
                    getCarrierConfig().overrideConfig(mSubId, overrideBundle, false);
                    log("satData: mMockSatelliteDataListener: Updated new config" + overrideBundle);
                }
            };

    private final OnCheckedChangeListener mMockSatelliteDataSwitchListener =
            (buttonView, isChecked) -> {
        log("satData: ServiceData enabling = " + isChecked);
        if (isChecked) {
            if (isValidOperator(mSubId)) {
                updateSatelliteDataButton();
            } else {
                log("satData: Not a valid Operator");
                mMockSatelliteDataSwitch.setChecked(false);
                return;
            }
        } else {
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
        if (mSatelliteDataOriginalBundle[mPhoneId] != null && isValidSubscription(mSubId)) {
            log("satData: Setting originalCarrierConfig = "
                    + mSatelliteDataOriginalBundle[mPhoneId]);
            getCarrierConfig().overrideConfig(mSubId, mSatelliteDataOriginalBundle[mPhoneId],
                    false);
        }
    }

    private boolean isValidOperator(int subId) {
        String operatorNumeric = null;
        if (isValidSubscription(subId)) {
            operatorNumeric = mTelephonyManager.getNetworkOperatorForPhone(mPhoneId);
            TelephonyManager tm;
            if (TextUtils.isEmpty(operatorNumeric) && (tm = getSystemService(
                    TelephonyManager.class)) != null) {
                operatorNumeric = tm.getSimOperatorNumericForPhone(mPhoneId);
            }
        }
        return !TextUtils.isEmpty(operatorNumeric);
    }

    /**
     * This method will do extra check to validate the subId.
     * <p>
     * In case user opens the radioInfo when sim is active and enable some checks and go to the
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
                if (SubscriptionManager.isValidPhoneId(phoneId)
                        && isValidSubscription(subId)) {
                    if (getCarrierConfig() == null) return;
                    if (isChecked) {
                        if (!isValidOperator(subId)) {
                            mMockSatellite.setChecked(false);
                            loge("mMockSatelliteListener: Can't mock because no operator for phone "
                                    + phoneId);
                            return;
                        }
                        PersistableBundle originalBundle = getCarrierConfig().getConfigForSubId(
                                subId,
                                KEY_SATELLITE_ATTACH_SUPPORTED_BOOL,
                                KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL,
                                KEY_CARRIER_SUPPORTED_SATELLITE_SERVICES_PER_PROVIDER_BUNDLE
                        );
                        PersistableBundle overrideBundle = new PersistableBundle();
                        overrideBundle.putBoolean(
                                KEY_SATELLITE_ATTACH_SUPPORTED_BOOL, true);
                        overrideBundle.putBoolean(KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL, false);
                        PersistableBundle capableProviderBundle = new PersistableBundle();
                        capableProviderBundle.putIntArray(mTelephonyManager
                                        .getNetworkOperatorForPhone(phoneId),
                                new int[]{
                                        // Currently satellite only supports below
                                        NetworkRegistrationInfo.SERVICE_TYPE_SMS,
                                        NetworkRegistrationInfo.SERVICE_TYPE_EMERGENCY
                        });
                        overrideBundle.putPersistableBundle(
                                KEY_CARRIER_SUPPORTED_SATELLITE_SERVICES_PER_PROVIDER_BUNDLE,
                                capableProviderBundle);
                        log("mMockSatelliteListener: new " + overrideBundle);
                        log("mMockSatelliteListener: old " + originalBundle);
                        getCarrierConfig().overrideConfig(subId, overrideBundle, false);
                        mCarrierSatelliteOriginalBundle[phoneId] = originalBundle;
                    } else {
                        try {
                            getCarrierConfig().overrideConfig(subId,
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

    private boolean shouldHideNonEmergencyMode() {
        if (!Build.isDebuggable()) {
            return true;
        }
        String action  = SatelliteManager.ACTION_SATELLITE_START_NON_EMERGENCY_SESSION;
        if (TextUtils.isEmpty(action)) {
            return true;
        }
        if (mNonEsosIntent != null) {
            mNonEsosIntent = null;
        }
        if (getCarrierConfig() == null) {
            loge("shouldHideNonEmergencyMode: cm is null");
            return true;
        }
        PersistableBundle bundle = getCarrierConfig().getConfigForSubId(mSubId,
                KEY_SATELLITE_ATTACH_SUPPORTED_BOOL,
                CarrierConfigManager.KEY_SATELLITE_ESOS_SUPPORTED_BOOL);
        if (!bundle.getBoolean(
                CarrierConfigManager.KEY_SATELLITE_ESOS_SUPPORTED_BOOL, false)) {
            log("shouldHideNonEmergencyMode: esos_supported false");
            return true;
        }
        if (!bundle.getBoolean(
                KEY_SATELLITE_ATTACH_SUPPORTED_BOOL, false)) {
            log("shouldHideNonEmergencyMode: attach_supported false");
            return true;
        }

        String packageName = getStringFromOverlayConfig(
                com.android.internal.R.string.config_satellite_gateway_service_package);

        String className = getStringFromOverlayConfig(com.android.internal.R.string
                .config_satellite_carrier_roaming_non_emergency_session_class);
        if (packageName == null || className == null
                || packageName.isEmpty() || className.isEmpty()) {
            Log.d(TAG, "shouldHideNonEmergencyMode:"
                    + " packageName or className is null or empty.");
            return true;
        }
        PackageManager pm = getPackageManager();
        Intent intent = new Intent(action);
        intent.setComponent(new ComponentName(packageName, className));
        if (pm.queryBroadcastReceivers(intent, 0).isEmpty()) {
            Log.d(TAG, "shouldHideNonEmergencyMode: Broadcast receiver not found for intent: "
                    + intent);
            return true;
        }
        mNonEsosIntent = intent;
        return false;
    }

    private String getStringFromOverlayConfig(int resourceId) {
        String name;
        try {
            name = getResources().getString(resourceId);
        } catch (Resources.NotFoundException ex) {
            loge("getStringFromOverlayConfig: ex=" + ex);
            name = null;
        }
        return name;
    }

    private boolean isImsVolteProvisioned() {
        return getImsConfigProvisionedState(CAPABILITY_TYPE_VOICE,
                REGISTRATION_TECH_LTE);
    }

    OnCheckedChangeListener mImsVolteCheckedChangeListener = new OnCheckedChangeListener() {
        @Override
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            setImsVolteProvisionedState(isChecked);
        }
    };

    private boolean isImsVtProvisioned() {
        return getImsConfigProvisionedState(CAPABILITY_TYPE_VIDEO,
                REGISTRATION_TECH_LTE);
    }

    OnCheckedChangeListener mImsVtCheckedChangeListener = new OnCheckedChangeListener() {
        @Override
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            setImsVtProvisionedState(isChecked);
        }
    };

    private boolean isImsWfcProvisioned() {
        return getImsConfigProvisionedState(CAPABILITY_TYPE_VOICE,
                REGISTRATION_TECH_IWLAN);
    }

    OnCheckedChangeListener mImsWfcCheckedChangeListener = new OnCheckedChangeListener() {
        @Override
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            setImsWfcProvisionedState(isChecked);
        }
    };

    private boolean isEabProvisioned() {
        return getRcsConfigProvisionedState(ImsRcsManager.CAPABILITY_TYPE_PRESENCE_UCE,
                REGISTRATION_TECH_LTE);
    }

    OnCheckedChangeListener mEabCheckedChangeListener = new OnCheckedChangeListener() {
        @Override
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            setEabProvisionedState(isChecked);
        }
    };

    private boolean getImsConfigProvisionedState(int capability, int tech) {
        if (mProvisioningManager != null) {
            try {
                return mProvisioningManager.getProvisioningStatusForCapability(
                        capability, tech);
            } catch (RuntimeException e) {
                Log.e(TAG, "getImsConfigProvisionedState() exception:", e);
            }
        }

        return false;
    }

    private boolean getRcsConfigProvisionedState(int capability, int tech) {
        if (mProvisioningManager != null) {
            try {
                return mProvisioningManager.getRcsProvisioningStatusForCapability(
                        capability, tech);
            } catch (RuntimeException e) {
                Log.e(TAG, "getRcsConfigProvisionedState() exception:", e);
            }
        }

        return false;
    }

    private boolean isEabEnabledByPlatform() {
        if (SubscriptionManager.isValidPhoneId(mPhoneId)) {
            PersistableBundle b = getCarrierConfig().getConfigForSubId(mSubId);
            if (b != null) {
                return b.getBoolean(
                        CarrierConfigManager.KEY_USE_RCS_PRESENCE_BOOL, false) || b.getBoolean(
                        CarrierConfigManager.Ims.KEY_ENABLE_PRESENCE_CAPABILITY_EXCHANGE_BOOL,
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

    OnClickListener mOemInfoButtonHandler = new OnClickListener() {
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

    OnClickListener mPingButtonHandler = new OnClickListener() {
        public void onClick(View v) {
            updatePingState();
        }
    };

    OnClickListener mUpdateSmscButtonHandler = new OnClickListener() {
        public void onClick(View v) {
            mUpdateSmscButton.setEnabled(false);
            mQueuedWork.execute(() -> {
                if (mSystemUser) {
                    mPhone.setSmscAddress(mSmsc.getText().toString(),
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
            final ComponentName serviceComponent =
                    ComponentName.unflattenFromString(carrierProvisioningApp);
            intent.setComponent(serviceComponent);
            sendBroadcast(intent);
        }
    };

    OnClickListener mTriggerCarrierProvisioningButtonHandler = v -> {
        String carrierProvisioningApp = getCarrierProvisioningAppString();
        if (!TextUtils.isEmpty(carrierProvisioningApp)) {
            final Intent intent = new Intent(TRIGGER_CARRIER_PROVISIONING_ACTION);
            final ComponentName serviceComponent =
                    ComponentName.unflattenFromString(carrierProvisioningApp);
            intent.setComponent(serviceComponent);
            sendBroadcast(intent);
        }
    };

    AdapterView.OnItemSelectedListener mPreferredNetworkHandler =
            new AdapterView.OnItemSelectedListener() {

        public void onItemSelected(AdapterView parent, View v, int pos, long id) {
            if (mPreferredNetworkTypeResult != pos && pos >= 0
                    && pos <= PREFERRED_NETWORK_LABELS.length - 2) {
                mPreferredNetworkTypeResult = pos;
                new Thread(() -> {
                    mTelephonyManager.setAllowedNetworkTypesForReason(
                            TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER,
                            RadioAccessFamily.getRafFromNetworkType(mPreferredNetworkTypeResult));
                }).start();
            }
        }

        public void onNothingSelected(AdapterView parent) {
        }
    };

    AdapterView.OnItemSelectedListener mOnMockSignalStrengthSelectedListener =
            new AdapterView.OnItemSelectedListener() {

                public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                    log("mOnSignalStrengthSelectedListener: " + pos);
                    mSelectedSignalStrengthIndex[mPhoneId] = pos;
                    if (mSystemUser) {
                        mPhone.getTelephonyTester().setSignalStrength(SIGNAL_STRENGTH_LEVEL[pos]);
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
                                        MOCK_DATA_NETWORK_TYPE[pos]));
                        intent.putExtra("data_reg_state", ServiceState.STATE_IN_SERVICE);
                        intent.putExtra("data_rat", MOCK_DATA_NETWORK_TYPE[pos]);
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

                public void onNothingSelected(AdapterView parent) {
                }
            };

    AdapterView.OnItemSelectedListener mCellInfoRefreshRateHandler =
            new AdapterView.OnItemSelectedListener() {

                public void onItemSelected(AdapterView parent, View v, int pos, long id) {
                    mCellInfoRefreshRateIndex = pos;
                    mTelephonyManager.setCellInfoListRate(CELL_INFO_REFRESH_RATES[pos], mPhoneId);
                    updateAllCellInfo();
                }

                public void onNothingSelected(AdapterView parent) {
                }
            };

    private String getCarrierProvisioningAppString() {
        if (SubscriptionManager.isValidPhoneId(mPhoneId)) {
            PersistableBundle b = getCarrierConfig().getConfigForSubId(mSubId);
            if (b != null) {
                return b.getString(
                        CarrierConfigManager.KEY_CARRIER_PROVISIONING_APP_STRING, "");
            }
        }
        return "";
    }

    boolean isCbrsSupported() {
        return getResources().getBoolean(
              com.android.internal.R.bool.config_cbrs_supported);
    }

    void updateCbrsDataState(boolean state) {
        Log.d(TAG, "setCbrsDataSwitchState() state:" + ((state) ? "on" : "off"));
        if (mTelephonyManager != null) {
            mQueuedWork.execute(new Runnable() {
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

    OnCheckedChangeListener mCbrsDataSwitchChangeListener = new OnCheckedChangeListener() {
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

    private static boolean isDsdsSupported() {
        return (TelephonyManager.getDefault().isMultiSimSupported()
            == TelephonyManager.MULTISIM_ALLOWED);
    }

    private static boolean isDsdsEnabled() {
        return TelephonyManager.getDefault().getPhoneCount() > 1;
    }

    private void performDsdsSwitch() {
        mTelephonyManager.switchMultiSimConfig(mDsdsSwitch.isChecked() ? 2 : 1);
    }

    /**
     * @return {@code True} if the device is only supported dsds mode.
     */
    private boolean dsdsModeOnly() {
        String dsdsMode = SystemProperties.get(DSDS_MODE_PROPERTY);
        return !TextUtils.isEmpty(dsdsMode) && Integer.parseInt(dsdsMode) == ALWAYS_ON_DSDS_MODE;
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

    OnCheckedChangeListener mRemovableEsimChangeListener = new OnCheckedChangeListener() {
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
            imsMmTelManager.isSupported(CAPABILITY_TYPE_VOICE,
                    AccessNetworkConstants.TRANSPORT_TYPE_WWAN, getMainExecutor(), (result) -> {
                        updateVolteProvisionedSwitch(result);
                    });
            imsMmTelManager.isSupported(CAPABILITY_TYPE_VIDEO,
                    AccessNetworkConstants.TRANSPORT_TYPE_WWAN, getMainExecutor(), (result) -> {
                        updateVtProvisionedSwitch(result);
                    });
            imsMmTelManager.isSupported(CAPABILITY_TYPE_VOICE,
                    AccessNetworkConstants.TRANSPORT_TYPE_WLAN, getMainExecutor(), (result) -> {
                        updateWfcProvisionedSwitch(result);
                    });
        } catch (ImsException e) {
            e.printStackTrace();
        }
    }

    private Phone getPhone(int subId) {
        log("getPhone subId = " + subId);
        Phone phone = PhoneFactory.getPhone(SubscriptionManager.getPhoneId(subId));
        if (phone == null) {
            log("return the default phone");
            return PhoneFactory.getDefaultPhone();
        }

        return phone;
    }

    private boolean isVoiceServiceAvailable(ImsMmTelManager imsMmTelManager) {
        if (imsMmTelManager == null) {
            log("isVoiceServiceAvailable: ImsMmTelManager is null");
            return false;
        }

        final int[] radioTechs = {
            REGISTRATION_TECH_LTE,
            REGISTRATION_TECH_CROSS_SIM,
            REGISTRATION_TECH_NR,
            REGISTRATION_TECH_3G
        };

        boolean isAvailable = false;
        for (int tech : radioTechs) {
            try {
                isAvailable |= imsMmTelManager.isAvailable(CAPABILITY_TYPE_VOICE, tech);
                if (isAvailable) {
                    break;
                }
            } catch (Exception e) {
                log("isVoiceServiceAvailable: exception " + e.getMessage());
            }
        }

        log("isVoiceServiceAvailable: " + isAvailable);
        return isAvailable;
    }

    private boolean isVideoServiceAvailable(ImsMmTelManager imsMmTelManager) {
        if (imsMmTelManager == null) {
            log("isVideoServiceAvailable: ImsMmTelManager is null");
            return false;
        }

        final int[] radioTechs = {
            REGISTRATION_TECH_LTE,
            REGISTRATION_TECH_IWLAN,
            REGISTRATION_TECH_CROSS_SIM,
            REGISTRATION_TECH_NR,
            REGISTRATION_TECH_3G
        };

        boolean isAvailable = false;
        for (int tech : radioTechs) {
            try {
                isAvailable |= imsMmTelManager.isAvailable(CAPABILITY_TYPE_VIDEO, tech);
                if (isAvailable) {
                    break;
                }
            } catch (Exception e) {
                log("isVideoServiceAvailable: exception " + e.getMessage());
            }
        }

        log("isVideoServiceAvailable: " + isAvailable);
        return isAvailable;
    }

    private boolean isWfcServiceAvailable(ImsMmTelManager imsMmTelManager) {
        if (imsMmTelManager == null) {
            log("isWfcServiceAvailable: ImsMmTelManager is null");
            return false;
        }

        boolean isAvailable = false;
        try {
            isAvailable = imsMmTelManager.isAvailable(CAPABILITY_TYPE_VOICE,
                    REGISTRATION_TECH_IWLAN);
        } catch (Exception e) {
            log("isWfcServiceAvailable: exception " + e.getMessage());
        }

        log("isWfcServiceAvailable: " + isAvailable);
        return isAvailable;
    }

    private CarrierConfigManager getCarrierConfig() {
        if (mCarrierConfigManager == null) {
            mCarrierConfigManager = getSystemService(CarrierConfigManager.class);
        }
        return mCarrierConfigManager;
    }
}
