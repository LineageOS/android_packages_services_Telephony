/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.phone.settings.hiddenmenu;

import static android.telephony.ims.feature.MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VIDEO;
import static android.telephony.ims.feature.MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VOICE;
import static android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN;
import static android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_LTE;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Looper;
import android.os.PersistableBundle;
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
import android.telephony.RadioAccessFamily;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;
import android.telephony.ims.ImsException;
import android.telephony.ims.ImsManager;
import android.telephony.ims.ImsMmTelManager;
import android.telephony.ims.ImsRcsManager;
import android.telephony.ims.ProvisioningManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.phone.R;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class PhoneInformationV2FragmentDataNetwork extends Fragment {
    private static final String TAG = "PhoneInformationV2 DataNetwork";
    private PhoneInfoSharedViewModel mViewModel;
    private static final boolean IS_USER_BUILD = "user".equals(Build.TYPE);
    private PhoneInformationV2PhoneId mListener;
    private LinearLayout mPhoneButton0, mPhoneButton1;
    private TextView mPhoneTitle0;
    private TextView mPhoneTitle1;
    private TextView mOperatorName;
    private TextView mRoamingState;
    private TextView mGprsState;
    private TextView mDataNetwork;
    private TextView mDataRawReg;
    private TextView mOverrideNetwork;
    private TextView mGsmState;
    private TextView mVoiceNetwork;
    private TextView mVoiceRawReg;
    private TextView mDBm;
    private static final long RUNNABLE_TIMEOUT_MS = 5 * 60 * 1000L;
    private ThreadPoolExecutor mQueuedWork;
    private TextView mDownlinkKbps;
    private TextView mUplinkKbps;
    private TextView mNrAvailable;
    private TextView mNrState;
    private TextView mNrFrequency;
    private TextView mCellInfo;
    private Spinner mPreferredNetworkType;
    private int mPreferredNetworkTypeResult;
    private Spinner mMockSignalStrength;
    private Spinner mMockDataNetworkType;
    private Spinner mCellInfoRefreshRateSpinner;
    private Switch mRadioPowerOnSwitch;
    private Switch mSimulateOutOfServiceSwitch;
    private Switch mImsVolteProvisionedSwitch;
    private Switch mImsVtProvisionedSwitch;
    private Switch mImsWfcProvisionedSwitch;
    private Switch mEabProvisionedSwitch;
    private TelephonyManager mTelephonyManager;
    private ImsManager mImsManager = null;
    private ProvisioningManager mProvisioningManager = null;
    private Phone mPhone = null;
    private boolean mSystemUser = true;
    private int mPhoneId = SubscriptionManager.INVALID_PHONE_INDEX;
    private static final int DEFAULT_PHONE_ID = 0;

    private int mSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    private TelephonyDisplayInfo mDisplayInfo;
    private CarrierConfigManager mCarrierConfigManager;
    private Context mContext;
    private Handler mHandler;
    private View mView;
    private int mCellInfoRefreshRateIndex;
    private static String[] sPhoneIndexLabels = new String[0];
    private final boolean[] mSimulateOos = new boolean[2];
    private int[] mSelectedSignalStrengthIndex = new int[2];
    private int[] mSelectedMockDataNetworkTypeIndex = new int[2];
    private List<CellInfo> mCellInfoResult = null;
    private static final int EVENT_UPDATE_NR_STATS = 1008;

    private static final int CELL_INFO_LIST_RATE_DISABLED = Integer.MAX_VALUE;
    private static final int CELL_INFO_LIST_RATE_MAX = 0;
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
            CELL_INFO_LIST_RATE_DISABLED,
            CELL_INFO_LIST_RATE_MAX,
            5000,
            10000,
            60000
    };
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
    private static final Integer[] SIGNAL_STRENGTH_LEVEL =
            new Integer[] {
                -1 /*clear mock*/,
                CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN,
                CellSignalStrength.SIGNAL_STRENGTH_POOR,
                CellSignalStrength.SIGNAL_STRENGTH_MODERATE,
                CellSignalStrength.SIGNAL_STRENGTH_GOOD,
                CellSignalStrength.SIGNAL_STRENGTH_GREAT
            };
    private static final Integer[] MOCK_DATA_NETWORK_TYPE =
            new Integer[] {
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

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        // Ensure the host activity implements the callback interface
        if (context instanceof PhoneInformationV2PhoneId) {
            mListener = (PhoneInformationV2PhoneId) context;
        } else {
            throw new RuntimeException(context.toString() + " must implement SharedValueListener");
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mViewModel = new ViewModelProvider(requireActivity()).get(PhoneInfoSharedViewModel.class);
        log("onCreate");
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view =
                inflater.inflate(R.layout.phone_information_v2_tab_data_network, container, false);
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mView = view;
        mContext = requireContext();
        mHandler = new Handler(Looper.getMainLooper());
        mSystemUser = android.os.Process.myUserHandle().isSystem();
        log("onCreate: mSystemUser=" + mSystemUser);

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
        if (mListener != null) {
            mPhoneId = mListener.getPhoneId();
            mSubId = SubscriptionManager.getSubscriptionId(mPhoneId);
        }
        mImsManager = new ImsManager(mContext);
        try {
            mProvisioningManager = ProvisioningManager.createForSubscriptionId(mSubId);
        } catch (IllegalArgumentException e) {
            log("onCreate : IllegalArgumentException " + e.getMessage());
            mProvisioningManager = null;
        }

        mQueuedWork =
                new ThreadPoolExecutor(
                        1,
                        1,
                        RUNNABLE_TIMEOUT_MS,
                        TimeUnit.MICROSECONDS,
                        new LinkedBlockingDeque<>());
        mTelephonyManager =
                mContext.getSystemService(TelephonyManager.class).createForSubscriptionId(mSubId);

        sPhoneIndexLabels = getPhoneIndexLabels(mTelephonyManager);

        mOperatorName = (TextView) view.findViewById(R.id.operator);
        mRoamingState = (TextView) view.findViewById(R.id.roaming);
        mGprsState = (TextView) view.findViewById(R.id.gprs);
        mDataNetwork = (TextView) view.findViewById(R.id.data_network);
        mDataRawReg = (TextView) view.findViewById(R.id.data_raw_registration_state);
        mOverrideNetwork = (TextView) view.findViewById(R.id.override_network);
        mGsmState = (TextView) view.findViewById(R.id.gsm);
        mVoiceNetwork = (TextView) view.findViewById(R.id.voice_network);
        mVoiceRawReg = (TextView) view.findViewById(R.id.voice_raw_registration_state);
        mDBm = (TextView) view.findViewById(R.id.dbm);
        mNrAvailable = (TextView) view.findViewById(R.id.nr_available);
        mNrState = (TextView) view.findViewById(R.id.nr_state);
        mNrFrequency = (TextView) view.findViewById(R.id.nr_frequency);

        mDownlinkKbps = (TextView) view.findViewById(R.id.dl_kbps);
        mUplinkKbps = (TextView) view.findViewById(R.id.ul_kbps);
        updateBandwidths(0, 0);

        mCellInfo = (TextView) view.findViewById(R.id.cellinfo);
        mCellInfo.setTypeface(Typeface.MONOSPACE);
        mPreferredNetworkType = (Spinner) view.findViewById(R.id.preferredNetworkType);
        ArrayAdapter<String> mPreferredNetworkTypeAdapter =
                new ArrayAdapter<String>(
                        mContext, android.R.layout.simple_spinner_item, PREFERRED_NETWORK_LABELS);
        mPreferredNetworkTypeAdapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        mPreferredNetworkType.setAdapter(mPreferredNetworkTypeAdapter);
        mPreferredNetworkTypeResult = PREFERRED_NETWORK_LABELS.length - 1; // Unknown

        mMockSignalStrength = (Spinner) view.findViewById(R.id.signalStrength);
        if (!Build.isDebuggable() || !mSystemUser) {
            mMockSignalStrength.setVisibility(View.GONE);
            view.findViewById(R.id.signalStrength).setVisibility(View.GONE);
            view.findViewById(R.id.signal_strength_label).setVisibility(View.GONE);
        } else {
            ArrayAdapter<Integer> mSignalStrengthAdapter =
                    new ArrayAdapter<>(
                            mContext, android.R.layout.simple_spinner_item, SIGNAL_STRENGTH_LEVEL);
            mSignalStrengthAdapter.setDropDownViewResource(
                    android.R.layout.simple_spinner_dropdown_item);
            mMockSignalStrength.setAdapter(mSignalStrengthAdapter);
        }

        mMockDataNetworkType = (Spinner) view.findViewById(R.id.dataNetworkType);
        if (!Build.isDebuggable() || !mSystemUser) {
            mMockDataNetworkType.setVisibility(View.GONE);

            view.findViewById(R.id.dataNetworkType).setVisibility(View.GONE);
            view.findViewById(R.id.data_network_type_label).setVisibility(View.GONE);
        } else {
            ArrayAdapter<String> mNetworkTypeAdapter =
                    new ArrayAdapter<>(
                            mContext,
                            android.R.layout.simple_spinner_item,
                            Arrays.stream(MOCK_DATA_NETWORK_TYPE)
                                    .map(ServiceState::rilRadioTechnologyToString)
                                    .toArray(String[]::new));
            mNetworkTypeAdapter.setDropDownViewResource(
                    android.R.layout.simple_spinner_dropdown_item);
            mMockDataNetworkType.setAdapter(mNetworkTypeAdapter);
        }

        mRadioPowerOnSwitch = (Switch) view.findViewById(R.id.radio_power);
        mSimulateOutOfServiceSwitch = (Switch) view.findViewById(R.id.simulate_out_of_service);
        if (!Build.isDebuggable()) {
            mSimulateOutOfServiceSwitch.setVisibility(View.GONE);
        }

        mImsVolteProvisionedSwitch = (Switch) view.findViewById(R.id.volte_provisioned_switch);
        mImsVtProvisionedSwitch = (Switch) view.findViewById(R.id.vt_provisioned_switch);
        mImsWfcProvisionedSwitch = (Switch) view.findViewById(R.id.wfc_provisioned_switch);
        mEabProvisionedSwitch = (Switch) view.findViewById(R.id.eab_provisioned_switch);

        if (!isImsSupportedOnDevice()) {
            mImsVolteProvisionedSwitch.setVisibility(View.GONE);
            mImsVtProvisionedSwitch.setVisibility(View.GONE);
            mImsWfcProvisionedSwitch.setVisibility(View.GONE);
            mEabProvisionedSwitch.setVisibility(View.GONE);
        }

        // hide 5G stats on devices that don't support 5G
        if ((mTelephonyManager.getSupportedRadioAccessFamily()
                & TelephonyManager.NETWORK_TYPE_BITMASK_NR) == 0) {
            setNrStatsVisibility(View.GONE);
        }

        mCellInfoRefreshRateSpinner = (Spinner) view.findViewById(R.id.cell_info_rate_select);
        ArrayAdapter<String> cellInfoAdapter = new ArrayAdapter<String>(requireContext(),
                android.R.layout.simple_spinner_item, CELL_INFO_REFRESH_RATE_LABELS);
        cellInfoAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mCellInfoRefreshRateSpinner.setAdapter(cellInfoAdapter);

        mCellInfoRefreshRateIndex = 0; //disabled

        mPhoneButton0 = view.findViewById(R.id.phone_button_0);
        mPhoneTitle0 = view.findViewById(R.id.phone_button_0_title);

        mPhoneButton1 = view.findViewById(R.id.phone_button_1);
        mPhoneTitle1 = view.findViewById(R.id.phone_button_1_title);

        mPhoneTitle0.setText(sPhoneIndexLabels[0]);
        mPhoneTitle1.setText(sPhoneIndexLabels[1]);

        View.OnClickListener selectionListener =
                clickedView -> {
                    int targetPhoneId = -1;
                    if (clickedView.getId() == R.id.phone_button_0) {
                        targetPhoneId = 0;
                    } else if (clickedView.getId() == R.id.phone_button_1) {
                        targetPhoneId = 1;
                    }

                    log("targetPhoneId: " + targetPhoneId + ", mPhoneId: " + mPhoneId);

                    if (targetPhoneId != -1) {
                        // Validation
                        if (mTelephonyManager.getActiveModemCount() > targetPhoneId) {
                            // Check if selection actually changed to avoid redundant work
                            if (mPhoneId != targetPhoneId) {
                                mPhoneId = targetPhoneId;
                                mListener.setPhoneId(mPhoneId);
                                try {
                                    mSubId = SubscriptionManager.getSubscriptionId(mPhoneId);
                                    log(
                                            "Updated phone id to "
                                                    + mPhoneId
                                                    + ", sub id to "
                                                    + mSubId);
                                    updatePhoneIndex();
                                } catch (SecurityException e) {
                                    log("Permission error getting subId for phoneId " + mPhoneId);
                                }
                            }
                        } else {
                            log(
                                    "Selected phone index "
                                            + targetPhoneId
                                            + " is not active/available.");
                        }
                    }
                };
        mPhoneButton0.setOnClickListener(selectionListener);
        mPhoneButton1.setOnClickListener(selectionListener);
        restoreFromBundle(savedInstanceState);
        updateUI();
    }

    private void updateUI() {
        mSelectedSignalStrengthIndex[mPhoneId] =
                mViewModel.getDataSignalStrength(mPhoneId).getValue();
        mSelectedMockDataNetworkTypeIndex[mPhoneId] =
                mViewModel.getDataNetworkTypeDisplay(mPhoneId).getValue();
        mSimulateOos[mPhoneId] = Boolean.TRUE.equals(
                mViewModel.getSimulateOutOfService(mPhoneId).getValue());
    }

    @Override
    public void onResume() {
        super.onResume();
        log("Started onResume");

        updateAllFields();
    }

    @Override
    public void onPause() {
        log("onPause: unregister phone & data intents");
        super.onPause();
        mTelephonyManager.unregisterTelephonyCallback(mTelephonyCallback);
    }

    @Override
    public void onDestroy() {
        log("onDestroy");
        super.onDestroy();
        if (mQueuedWork != null) {
            mQueuedWork.shutdown();
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("mPreferredNetworkTypeResult", mPreferredNetworkTypeResult);
        outState.putInt("mSelectedPhoneIndex", mPhoneId);
        outState.putInt("mCellInfoRefreshRateIndex", mCellInfoRefreshRateIndex);
    }

    private void restoreFromBundle(Bundle b) {
        if (b == null) {
            return;
        }

        mPreferredNetworkTypeResult =
                b.getInt("mPreferredNetworkTypeResult", PREFERRED_NETWORK_LABELS.length - 1);

        mPhoneId = b.getInt("mSelectedPhoneIndex", 0);
        mSubId = SubscriptionManager.getSubscriptionId(mPhoneId);

        mCellInfoRefreshRateIndex = b.getInt("mCellInfoRefreshRateIndex", 0);
    }

    private boolean isImsConfigProvisioningRequired(int capability, int tech) {
        if (mProvisioningManager != null) {
            try {
                return mProvisioningManager.isProvisioningRequiredForCapability(capability, tech);
            } catch (RuntimeException e) {
                log("isImsConfigProvisioningRequired() exception: " + e.getMessage());
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
                log("isRcsConfigProvisioningRequired() exception: " + e.getMessage());
            }
        }

        return false;
    }

    private boolean getImsConfigProvisionedState(int capability, int tech) {
        if (mProvisioningManager != null) {
            try {
                return mProvisioningManager.getProvisioningStatusForCapability(capability, tech);
            } catch (RuntimeException e) {
                log("getImsConfigProvisionedState() exception: " + e.getMessage());
            }
        }

        return false;
    }

    private boolean getRcsConfigProvisionedState(int capability, int tech) {
        if (mProvisioningManager != null) {
            try {
                return mProvisioningManager.getRcsProvisioningStatusForCapability(capability, tech);
            } catch (RuntimeException e) {
                log("getRcsConfigProvisionedState() exception: " + e.getMessage());
            }
        }

        return false;
    }

    private CarrierConfigManager getCarrierConfig() {
        if (mCarrierConfigManager == null) {
            mCarrierConfigManager = mContext.getSystemService(CarrierConfigManager.class);
        }
        return mCarrierConfigManager;
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

    private boolean isImsVolteProvisioned() {
        return getImsConfigProvisionedState(CAPABILITY_TYPE_VOICE, REGISTRATION_TECH_LTE);
    }

    private boolean isImsSupportedOnDevice() {
        return mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY_IMS);
    }

    private boolean isImsVtProvisioned() {
        return getImsConfigProvisionedState(CAPABILITY_TYPE_VIDEO, REGISTRATION_TECH_LTE);
    }

    private boolean isImsWfcProvisioned() {
        return getImsConfigProvisionedState(CAPABILITY_TYPE_VOICE, REGISTRATION_TECH_IWLAN);
    }

    private boolean isEabProvisioned() {
        return getRcsConfigProvisionedState(
                ImsRcsManager.CAPABILITY_TYPE_PRESENCE_UCE, REGISTRATION_TECH_LTE);
    }

    private boolean isEabEnabledByPlatform() {
        if (SubscriptionManager.isValidPhoneId(mPhoneId)) {
            PersistableBundle b = getCarrierConfig().getConfigForSubId(mSubId);
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

    OnCheckedChangeListener mImsVolteCheckedChangeListener =
            new OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    setImsVolteProvisionedState(isChecked);
                }
            };

    OnCheckedChangeListener mImsVtCheckedChangeListener =
            new OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    setImsVtProvisionedState(isChecked);
                }
            };

    OnCheckedChangeListener mImsWfcCheckedChangeListener =
            new OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    setImsWfcProvisionedState(isChecked);
                }
            };

    OnCheckedChangeListener mEabCheckedChangeListener =
            new OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    setEabProvisionedState(isChecked);
                }
            };

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
                    mContext.getMainExecutor(),
                    (result) -> {
                        updateVolteProvisionedSwitch(result);
                    });
            imsMmTelManager.isSupported(
                    CAPABILITY_TYPE_VIDEO,
                    AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                    mContext.getMainExecutor(),
                    (result) -> {
                        updateVtProvisionedSwitch(result);
                    });
            imsMmTelManager.isSupported(
                    CAPABILITY_TYPE_VOICE,
                    AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                    mContext.getMainExecutor(),
                    (result) -> {
                        updateWfcProvisionedSwitch(result);
                    });
        } catch (ImsException e) {
            log(e.getMessage());
        }
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
        SubscriptionManager mSm = mContext.getSystemService(SubscriptionManager.class);
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

    private void updateRadioPowerState() {
        // delightful hack to prevent on-checked-changed calls from
        // actually forcing the radio preference to its transient/current value.
        mRadioPowerOnSwitch.setOnCheckedChangeListener(null);
        mRadioPowerOnSwitch.setChecked(isRadioOn());
        mRadioPowerOnSwitch.setOnCheckedChangeListener(mRadioPowerOnChangeListener);
    }

    private void updateVolteProvisionedSwitch(boolean isEnabledByPlatform) {
        boolean isProvisioned = isEnabledByPlatform && isImsVolteProvisioned();
        log("updateVolteProvisionedSwitch isProvisioned" + isProvisioned);

        mImsVolteProvisionedSwitch.setOnCheckedChangeListener(null);
        mImsVolteProvisionedSwitch.setChecked(isProvisioned);
        mImsVolteProvisionedSwitch.setOnCheckedChangeListener(mImsVolteCheckedChangeListener);
        mImsVolteProvisionedSwitch.setEnabled(
                !IS_USER_BUILD && isEnabledByPlatform && isImsVolteProvisioningRequired());
    }

    private void updateVtProvisionedSwitch(boolean isEnabledByPlatform) {
        boolean isProvisioned = isEnabledByPlatform && isImsVtProvisioned();
        log("updateVtProvisionedSwitch isProvisioned" + isProvisioned);

        mImsVtProvisionedSwitch.setOnCheckedChangeListener(null);
        mImsVtProvisionedSwitch.setChecked(isProvisioned);
        mImsVtProvisionedSwitch.setOnCheckedChangeListener(mImsVtCheckedChangeListener);
        mImsVtProvisionedSwitch.setEnabled(
                !IS_USER_BUILD && isEnabledByPlatform && isImsVtProvisioningRequired());
    }

    private void updateWfcProvisionedSwitch(boolean isEnabledByPlatform) {
        boolean isProvisioned = isEnabledByPlatform && isImsWfcProvisioned();
        log("updateWfcProvisionedSwitch isProvisioned" + isProvisioned);

        mImsWfcProvisionedSwitch.setOnCheckedChangeListener(null);
        mImsWfcProvisionedSwitch.setChecked(isProvisioned);
        mImsWfcProvisionedSwitch.setOnCheckedChangeListener(mImsWfcCheckedChangeListener);
        mImsWfcProvisionedSwitch.setEnabled(
                !IS_USER_BUILD && isEnabledByPlatform && isImsWfcProvisioningRequired());
    }

    private void updateEabProvisionedSwitch(boolean isEnabledByPlatform) {
        log("updateEabProvisionedSwitch isEabWfcProvisioned()=" + isEabProvisioned());

        mEabProvisionedSwitch.setOnCheckedChangeListener(null);
        mEabProvisionedSwitch.setChecked(isEabProvisioned());
        mEabProvisionedSwitch.setOnCheckedChangeListener(mEabCheckedChangeListener);
        mEabProvisionedSwitch.setEnabled(
                !IS_USER_BUILD && isEnabledByPlatform && isEabProvisioningRequired());
    }

    private void updateImsProvisionedState() {
        if (!isImsSupportedOnDevice()) {
            return;
        }

        updateServiceEnabledByPlatform();
        updateEabProvisionedSwitch(isEabEnabledByPlatform());
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
    }

    private void updateAllFields() {
        updateDataState();
        updateSelectionVisuals();
        updateRadioPowerState();
        updateImsProvisionedState();
        updateNetworkType();
        updateNrStats();

        updateCellInfo(mCellInfoResult);
        mCellInfoRefreshRateSpinner.setOnItemSelectedListener(mCellInfoRefreshRateHandler);
        //set selection after registering listener to force update
        mCellInfoRefreshRateSpinner.setSelection(mCellInfoRefreshRateIndex);
        // Request cell information update from RIL.
        mTelephonyManager.setCellInfoListRate(CELL_INFO_REFRESH_RATES[mCellInfoRefreshRateIndex],
                mSubId);

        // set selection before registering to prevent update
        mPreferredNetworkType.setSelection(mPreferredNetworkTypeResult, true);
        mPreferredNetworkType.setOnItemSelectedListener(mPreferredNetworkHandler);

        new Thread(() -> {
            int networkType = (int) mTelephonyManager.getAllowedNetworkTypesBitmask();
            mHandler.post(() -> updatePreferredNetworkType(
                    RadioAccessFamily.getNetworkTypeFromRaf(networkType)));
        }).start();

        // mock signal strength
        mMockSignalStrength.setSelection(mSelectedSignalStrengthIndex[mPhoneId]);
        mMockSignalStrength.setOnItemSelectedListener(mOnMockSignalStrengthSelectedListener);

        // mock data network type
        mMockDataNetworkType.setSelection(mSelectedMockDataNetworkTypeIndex[mPhoneId]);
        mMockDataNetworkType.setOnItemSelectedListener(mOnMockDataNetworkTypeSelectedListener);

        mRadioPowerOnSwitch.setOnCheckedChangeListener(mRadioPowerOnChangeListener);
        mSimulateOutOfServiceSwitch.setChecked(mSimulateOos[mPhoneId]);
        mSimulateOutOfServiceSwitch.setOnCheckedChangeListener(mSimulateOosOnChangeListener);

        mImsVolteProvisionedSwitch.setOnCheckedChangeListener(mImsVolteCheckedChangeListener);
        mImsVtProvisionedSwitch.setOnCheckedChangeListener(mImsVtCheckedChangeListener);
        mImsWfcProvisionedSwitch.setOnCheckedChangeListener(mImsWfcCheckedChangeListener);
        mEabProvisionedSwitch.setOnCheckedChangeListener(mEabCheckedChangeListener);

        unregisterPhoneStateListener();
        registerPhoneStateListener();
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

    private static String[] getPhoneIndexLabels(TelephonyManager tm) {
        int phones = tm.getActiveModemCount();
        String[] labels = new String[phones];
        for (int i = 0; i < phones; i++) {
            labels[i] = "Phone " + i;
        }
        return labels;
    }

    private void updatePreferredNetworkType(int type) {
        if (type >= PREFERRED_NETWORK_LABELS.length || type < 0) {
            log("Network type: unknown type value=" + type);
            type = PREFERRED_NETWORK_LABELS.length - 1; // set to Unknown
        }
        mPreferredNetworkTypeResult = type;

        mPreferredNetworkType.setSelection(mPreferredNetworkTypeResult, true);
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
    private void updatePhoneIndex() {
        // unregister listeners on the old subId
        unregisterPhoneStateListener();

        // update the subId
        mTelephonyManager = mTelephonyManager.createForSubscriptionId(mSubId);

        // update the phoneId
        if (mSystemUser) {
            mPhone = PhoneFactory.getPhone(mPhoneId);
        }
        mImsManager = new ImsManager(mContext);
        try {
            mProvisioningManager = ProvisioningManager.createForSubscriptionId(mSubId);
        } catch (IllegalArgumentException e) {
            log("updatePhoneIndex : IllegalArgumentException " + e.getMessage());
            mProvisioningManager = null;
        }

        updateAllFields();
    }

    private void unregisterPhoneStateListener() {
        mTelephonyManager.unregisterTelephonyCallback(mTelephonyCallback);

        // clear all fields so they are blank until the next mListener event occurs
        mOperatorName.setText("");
        mRoamingState.setText("");
        mGprsState.setText("");
        mDataNetwork.setText("");
        mDataRawReg.setText("");
        mOverrideNetwork.setText("");
        mVoiceNetwork.setText("");
        mGsmState.setText("");
        mVoiceRawReg.setText("");
        mDBm.setText("");
        mDownlinkKbps.setText("");
        mUplinkKbps.setText("");
        mCellInfo.setText("");
    }

    // register mTelephonyCallback for relevant fields using the current TelephonyManager

    private void registerPhoneStateListener() {
        mTelephonyCallback = new RadioInfoTelephonyCallback();
        mTelephonyManager.registerTelephonyCallback(
                new HandlerExecutor(mHandler), mTelephonyCallback);
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

    private void updateBandwidths(int dlbw, int ulbw) {
        dlbw = (dlbw < 0 || dlbw == Integer.MAX_VALUE) ? -1 : dlbw;
        ulbw = (ulbw < 0 || ulbw == Integer.MAX_VALUE) ? -1 : ulbw;
        mDownlinkKbps.setText(String.format("%-5d", dlbw));
        mUplinkKbps.setText(String.format("%-5d", ulbw));
    }

    private void setNrStatsVisibility(int visibility) {
        ((TextView) mView.findViewById(R.id.nr_available_label)).setVisibility(visibility);
        mNrAvailable.setVisibility(visibility);
        ((TextView) mView.findViewById(R.id.nr_state_label)).setVisibility(visibility);
        mNrState.setVisibility(visibility);
        ((TextView) mView.findViewById(R.id.nr_frequency_label)).setVisibility(visibility);
        mNrFrequency.setVisibility(visibility);
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
                    mNrAvailable.setText(String.valueOf(dsri.isNrAvailable));
                }
            }
            mNrState.setText(NetworkRegistrationInfo.nrStateToString(ss.getNrState()));
            mNrFrequency.setText(ServiceState.frequencyRangeToString(ss.getNrFrequencyRange()));
        } else {
            log("Clear Nr stats by null service state");
            mNrAvailable.setText("");
            mNrState.setText("");
            mNrFrequency.setText("");
        }
    }

    private TelephonyCallback mTelephonyCallback = new RadioInfoTelephonyCallback();

    private class RadioInfoTelephonyCallback extends TelephonyCallback
            implements TelephonyCallback.DataConnectionStateListener,
                    TelephonyCallback.CallStateListener,
                    TelephonyCallback.ServiceStateListener,
                    TelephonyCallback.DisplayInfoListener,
                    TelephonyCallback.CellInfoListener {
        @Override
        public void onDataConnectionStateChanged(int state, int networkType) {
            updateDataState();
            updateNetworkType();
        }

        @Override
        public void onCellInfoChanged(List<CellInfo> arrayCi) {
            log("onCellInfoChanged: arrayCi=" + arrayCi);
            mCellInfoResult = arrayCi;
            updateCellInfo(mCellInfoResult);
        }

        @Override
        public void onCallStateChanged(int state) {
            updateNetworkType();
        }

        @Override
        public void onServiceStateChanged(ServiceState serviceState) {
            log("onServiceStateChanged: ServiceState=" + serviceState);
            updateServiceState(serviceState);
            updateRadioPowerState();
            updateNetworkType();
            updateRawRegistrationState(serviceState);
            updateImsProvisionedState();
            if (!mHandler.hasMessages(EVENT_UPDATE_NR_STATS)) {
                mHandler.post(() -> updateNrStats());
            }
        }

        @Override
        public void onDisplayInfoChanged(TelephonyDisplayInfo displayInfo) {
            mDisplayInfo = displayInfo;
            updateNetworkType();
        }
    }

    private boolean isRadioOn() {
        return mTelephonyManager.getRadioPowerState() == TelephonyManager.RADIO_POWER_ON;
    }

    private void setImsVolteProvisionedState(boolean state) {
        log("setImsVolteProvisioned state: " + ((state) ? "on" : "off"));
        setImsConfigProvisionedState(CAPABILITY_TYPE_VOICE, REGISTRATION_TECH_LTE, state);
    }

    private void setImsVtProvisionedState(boolean state) {
        log("setImsVtProvisioned() state: " + ((state) ? "on" : "off"));
        setImsConfigProvisionedState(CAPABILITY_TYPE_VIDEO, REGISTRATION_TECH_LTE, state);
    }

    private void setImsWfcProvisionedState(boolean state) {
        log("setImsWfcProvisioned() state: " + ((state) ? "on" : "off"));
        setImsConfigProvisionedState(CAPABILITY_TYPE_VOICE, REGISTRATION_TECH_IWLAN, state);
    }

    private void setEabProvisionedState(boolean state) {
        log("setEabProvisioned() state: " + ((state) ? "on" : "off"));
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
                                log("setImsConfigProvisioned() exception: " + e.getMessage());
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
                                log("setRcsConfigProvisioned() exception:" + e.getMessage());
                            }
                        }
                    });
        }
    }

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

    OnItemSelectedListener mPreferredNetworkHandler =
            new OnItemSelectedListener() {

                public void onItemSelected(AdapterView parent, View v, int pos, long id) {
                    if (mPreferredNetworkTypeResult != pos
                            && pos >= 0
                            && pos <= PREFERRED_NETWORK_LABELS.length - 2) {
                        mPreferredNetworkTypeResult = pos;
                        new Thread(
                                        () -> {
                                            mTelephonyManager.setAllowedNetworkTypesForReason(
                                                    TelephonyManager
                                                            .ALLOWED_NETWORK_TYPES_REASON_USER,
                                                    RadioAccessFamily.getRafFromNetworkType(
                                                            mPreferredNetworkTypeResult));
                                        })
                                .start();
                    }
                }

                public void onNothingSelected(AdapterView parent) {}
            };

    OnItemSelectedListener mOnMockSignalStrengthSelectedListener =
            new OnItemSelectedListener() {

                public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                    log("mOnSignalStrengthSelectedListener: " + pos);
                    mSelectedSignalStrengthIndex[mPhoneId] = pos;
                    if (mSystemUser) {
                        mPhone.getTelephonyTester().setSignalStrength(SIGNAL_STRENGTH_LEVEL[pos]);
                        mViewModel.setDataSignalStrength(pos, mPhoneId);
                    }
                }

                public void onNothingSelected(AdapterView<?> parent) {}
            };
    OnItemSelectedListener mOnMockDataNetworkTypeSelectedListener =
            new OnItemSelectedListener() {

                public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                    log("mOnMockDataNetworkTypeSelectedListener: " + pos);
                    mSelectedMockDataNetworkTypeIndex[mPhoneId] = pos;
                    mViewModel.setDataNetworkTypeDisplay(pos, mPhoneId);
                    Intent intent = new Intent("com.android.internal.telephony.TestServiceState");
                    if (pos > 0) {
                        log(
                                "mOnMockDataNetworkTypeSelectedListener: Override RAT: "
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
                mViewModel.setSimulateOutOfService(isChecked, mPhoneId);
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

    private void updateSelectionVisuals() {
        LinearLayout selectedButton, unSelectedButton;
        if (mPhoneId == 0) {
            selectedButton = mPhoneButton0;
            unSelectedButton = mPhoneButton1;
        } else {
            selectedButton = mPhoneButton1;
            unSelectedButton = mPhoneButton0;
        }
        selectedButton.setBackgroundColor(
                ContextCompat.getColor(mContext, android.R.color.holo_green_dark));
        unSelectedButton.setBackgroundColor(
                ContextCompat.getColor(mContext, android.R.color.darker_gray));
    }

    private static void log(String s) {
        Log.d(TAG, s);
    }
}
