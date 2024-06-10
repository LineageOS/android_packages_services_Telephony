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

package com.android.services.telephony.domainselection;

import static android.telephony.AccessNetworkConstants.AccessNetworkType.CDMA2000;
import static android.telephony.AccessNetworkConstants.AccessNetworkType.EUTRAN;
import static android.telephony.AccessNetworkConstants.AccessNetworkType.GERAN;
import static android.telephony.AccessNetworkConstants.AccessNetworkType.NGRAN;
import static android.telephony.AccessNetworkConstants.AccessNetworkType.UNKNOWN;
import static android.telephony.AccessNetworkConstants.AccessNetworkType.UTRAN;
import static android.telephony.AccessNetworkConstants.TRANSPORT_TYPE_INVALID;
import static android.telephony.AccessNetworkConstants.TRANSPORT_TYPE_WLAN;
import static android.telephony.AccessNetworkConstants.TRANSPORT_TYPE_WWAN;
import static android.telephony.BarringInfo.BARRING_SERVICE_TYPE_EMERGENCY;
import static android.telephony.CarrierConfigManager.KEY_CARRIER_VOLTE_TTY_SUPPORTED_BOOL;
import static android.telephony.CarrierConfigManager.ImsEmergency.DOMAIN_CS;
import static android.telephony.CarrierConfigManager.ImsEmergency.DOMAIN_PS_3GPP;
import static android.telephony.CarrierConfigManager.ImsEmergency.DOMAIN_PS_NON_3GPP;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_EMERGENCY_CALL_SETUP_TIMER_ON_CURRENT_NETWORK_SEC_INT;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_EMERGENCY_CDMA_PREFERRED_NUMBERS_STRING_ARRAY;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_EMERGENCY_DOMAIN_PREFERENCE_INT_ARRAY;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_EMERGENCY_DOMAIN_PREFERENCE_ROAMING_INT_ARRAY;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_EMERGENCY_LTE_PREFERRED_AFTER_NR_FAILED_BOOL;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_EMERGENCY_NETWORK_SCAN_TYPE_INT;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_EMERGENCY_OVER_CS_ROAMING_SUPPORTED_ACCESS_NETWORK_TYPES_INT_ARRAY;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_EMERGENCY_OVER_CS_SUPPORTED_ACCESS_NETWORK_TYPES_INT_ARRAY;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_EMERGENCY_OVER_IMS_ROAMING_SUPPORTED_3GPP_NETWORK_TYPES_INT_ARRAY;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_EMERGENCY_OVER_IMS_SUPPORTED_3GPP_NETWORK_TYPES_INT_ARRAY;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_EMERGENCY_REQUIRES_IMS_REGISTRATION_BOOL;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_EMERGENCY_REQUIRES_VOLTE_ENABLED_BOOL;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_EMERGENCY_SCAN_TIMER_SEC_INT;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_EMERGENCY_VOWIFI_REQUIRES_CONDITION_INT;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_IMS_REASONINFO_CODE_TO_RETRY_EMERGENCY_INT_ARRAY;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_MAXIMUM_CELLULAR_SEARCH_TIMER_SEC_INT;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_MAXIMUM_NUMBER_OF_EMERGENCY_TRIES_OVER_VOWIFI_INT;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_PREFER_IMS_EMERGENCY_WHEN_VOICE_CALLS_ON_CS_BOOL;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_SCAN_LIMITED_SERVICE_AFTER_VOLTE_FAILURE_BOOL;
import static android.telephony.CarrierConfigManager.ImsEmergency.SCAN_TYPE_FULL_SERVICE_FOLLOWED_BY_LIMITED_SERVICE;
import static android.telephony.CarrierConfigManager.ImsEmergency.VOWIFI_REQUIRES_SETTING_ENABLED;
import static android.telephony.CarrierConfigManager.ImsEmergency.VOWIFI_REQUIRES_VALID_EID;
import static android.telephony.CarrierConfigManager.ImsWfc.KEY_EMERGENCY_CALL_OVER_EMERGENCY_PDN_BOOL;
import static android.telephony.NetworkRegistrationInfo.REGISTRATION_STATE_HOME;
import static android.telephony.NetworkRegistrationInfo.REGISTRATION_STATE_ROAMING;
import static android.telephony.PreciseDisconnectCause.EMERGENCY_PERM_FAILURE;
import static android.telephony.PreciseDisconnectCause.EMERGENCY_TEMP_FAILURE;
import static android.telephony.PreciseDisconnectCause.NO_VALID_SIM;
import static android.telephony.PreciseDisconnectCause.SERVICE_OPTION_NOT_AVAILABLE;
import static android.telephony.SubscriptionManager.PROFILE_CLASS_PROVISIONING;
import static android.telephony.TelephonyManager.DATA_CONNECTED;
import static android.telephony.TelephonyManager.DATA_DISCONNECTED;
import static android.telephony.TelephonyManager.DATA_DISCONNECTING;
import static android.telephony.TelephonyManager.DATA_UNKNOWN;

import android.annotation.NonNull;
import android.content.Context;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.CancellationSignal;
import android.os.Looper;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.telecom.TelecomManager;
import android.telephony.AccessNetworkConstants.AccessNetworkType;
import android.telephony.AccessNetworkConstants.RadioAccessNetworkType;
import android.telephony.AccessNetworkConstants.TransportType;
import android.telephony.BarringInfo;
import android.telephony.CarrierConfigManager;
import android.telephony.DisconnectCause;
import android.telephony.DomainSelectionService;
import android.telephony.DomainSelectionService.SelectionAttributes;
import android.telephony.EmergencyRegistrationResult;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.TransportSelectorCallback;
import android.telephony.ims.ImsManager;
import android.telephony.ims.ImsMmTelManager;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.ProvisioningManager;
import android.text.TextUtils;
import android.util.LocalLog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.phone.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

/**
 * Selects the domain for emergency calling.
 */
public class EmergencyCallDomainSelector extends DomainSelectorBase
        implements ImsStateTracker.BarringInfoListener, ImsStateTracker.ImsStateListener {
    private static final String TAG = "DomainSelector-EmergencyCall";
    private static final boolean DBG = (SystemProperties.getInt("ro.debuggable", 0) == 1);
    private static final int LOG_SIZE = 50;

    /**
     * Timeout before we requests network scan without waiting for the disconnection
     * of ePDN.
     */
    private static final int DEFAULT_DATA_DISCONNECTION_TIMEOUT_MS = 2 * 1000; // 2 seconds

    /**
     * Timeout of waiting for the IMS state change before selecting domain from initial state.
     */
    private static final int DEFAULT_WAIT_FOR_IMS_STATE_TIMEOUT_MS = 3 * 1000; // 3 seconds

    private static final int MSG_START_DOMAIN_SELECTION = 11;
    @VisibleForTesting
    public static final int MSG_NETWORK_SCAN_TIMEOUT = 12;
    private static final int MSG_NETWORK_SCAN_RESULT = 13;
    @VisibleForTesting
    public static final int MSG_MAX_CELLULAR_TIMEOUT = 14;
    @VisibleForTesting
    public static final int MSG_WAIT_DISCONNECTION_TIMEOUT = 15;
    @VisibleForTesting
    public static final int MSG_WAIT_FOR_IMS_STATE_TIMEOUT = 16;
    private static final int MSG_WIFI_AVAILABLE = 17;

    private static final int NOT_SUPPORTED = -1;

    private static List<Integer> sDefaultRetryReasonCodes = List.of(
            ImsReasonInfo.CODE_LOCAL_CALL_CS_RETRY_REQUIRED,
            ImsReasonInfo.CODE_LOCAL_INTERNAL_ERROR,
            ImsReasonInfo.CODE_LOCAL_NOT_REGISTERED,
            ImsReasonInfo.CODE_SIP_ALTERNATE_EMERGENCY_CALL);

    private static List<Integer> sDisconnectCauseForTerminatation = List.of(
            SERVICE_OPTION_NOT_AVAILABLE);

    private static final LocalLog sLocalLog = new LocalLog(LOG_SIZE);

    private static List<String> sSimReadyAllowList;
    private static List<String> sPreferSlotWithNormalServiceList;
    private static List<String> sPreferCsAfterCsfbFailure;
    private static List<String> sPreferGeranWhenSimAbsent;

    /**
     * Network callback used to determine whether Wi-Fi is connected or not.
     */
    private ConnectivityManager.NetworkCallback mNetworkCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    logi("onAvailable: " + network);
                    if (network != null && !mWiFiNetworksAvailable.contains(network)) {
                        mWiFiNetworksAvailable.add(network);
                    }
                    mWiFiAvailable = true;
                    sendEmptyMessage(MSG_WIFI_AVAILABLE);
                }

                @Override
                public void onLost(Network network) {
                    logi("onLost: " + network);
                    if (network != null) {
                        mWiFiNetworksAvailable.remove(network);
                    }
                    if (!mWiFiNetworksAvailable.isEmpty()) {
                        logi("onLost: available networks=" + mWiFiNetworksAvailable);
                        return;
                    }
                    mWiFiAvailable = false;
                }

                @Override
                public void onUnavailable() {
                    logi("onUnavailable");
                    mWiFiNetworksAvailable.clear();
                    mWiFiAvailable = false;
                }
            };

    private boolean mIsEmergencyBarred;
    private boolean mImsRegistered;
    private boolean mIsVoiceCapable;
    private boolean mBarringInfoReceived;
    private boolean mImsRegStateReceived;
    private boolean mMmTelCapabilitiesReceived;
    private int mVoWifiTrialCount = 0;

    private @RadioAccessNetworkType int mCsNetworkType = UNKNOWN;
    private @RadioAccessNetworkType int mPsNetworkType = UNKNOWN;
    private @RadioAccessNetworkType int mLastNetworkType = UNKNOWN;
    private @TransportType int mLastTransportType = TRANSPORT_TYPE_INVALID;
    private @DomainSelectionService.EmergencyScanType int mScanType;
    private @RadioAccessNetworkType List<Integer> mLastPreferredNetworks;

    private CancellationSignal mCancelSignal;
    private EmergencyRegistrationResult mLastRegResult;

    // Members for carrier configuration
    private @RadioAccessNetworkType int[] mImsRatsConfig;
    private @RadioAccessNetworkType int[] mCsRatsConfig;
    private @RadioAccessNetworkType int[] mImsRoamRatsConfig;
    private @RadioAccessNetworkType int[] mCsRoamRatsConfig;
    private @CarrierConfigManager.ImsEmergency.EmergencyDomain int[] mDomainPreference;
    private @CarrierConfigManager.ImsEmergency.EmergencyDomain int[] mDomainPreferenceRoam;
    private List<String> mCdmaPreferredNumbers;
    private boolean mPreferImsWhenCallsOnCs;
    private int mVoWifiRequiresCondition;
    private int mScanTimeout;
    private int mMaxCellularTimeout;
    private int mMaxNumOfVoWifiTries;
    private boolean mVoWifiOverEmergencyPdn;
    private @CarrierConfigManager.ImsEmergency.EmergencyScanType int mPreferredNetworkScanType;
    private int mCallSetupTimerOnCurrentRat;
    private boolean mRequiresImsRegistration;
    private boolean mRequiresVoLteEnabled;
    private boolean mLtePreferredAfterNrFailure;
    private boolean mScanLimitedOnlyAfterVolteFailure;
    private List<Integer> mRetryReasonCodes;
    private boolean mNonTtyOrTtySupported;

    // Members for states
    private boolean mIsMonitoringConnectivity;
    private boolean mWiFiAvailable;
    private boolean mWasCsfbAfterPsFailure;
    private boolean mTryCsWhenPsFails;
    private boolean mTryEsFallback;
    private boolean mIsWaitingForDataDisconnection;
    private boolean mSwitchRatPreferenceWithLocalNotRegistered;
    private boolean mTerminateAfterCsFailure;
    private int mModemCount;

    /** Indicates whether this instance is deactivated. */
    private boolean mDestroyed = false;
    /** Indicates whether emergency network scan is requested. */
    private boolean mIsScanRequested = false;
    /** Indicates whether selected domain has been notified. */
    private boolean mDomainSelected = false;
    /** Indicates whether the cross sim redialing timer has expired. */
    private boolean mCrossStackTimerExpired = false;
    /** Indicates whether max cellular timer expired. */
    private boolean mMaxCellularTimerExpired = false;
    /** Indicates whether network scan timer expired. */
    private boolean mNetworkScanTimerExpired = false;

    /**
     * Indicates whether {@link #selectDomain(SelectionAttributes, TransportSelectionCallback)}
     * is called or not.
     */
    private boolean mDomainSelectionRequested = false;

    private final PowerManager.WakeLock mPartialWakeLock;
    private final CrossSimRedialingController mCrossSimRedialingController;
    private final DataConnectionStateHelper mEpdnHelper;
    private final List<Network> mWiFiNetworksAvailable = new ArrayList<>();

    /** Constructor. */
    public EmergencyCallDomainSelector(Context context, int slotId, int subId,
            @NonNull Looper looper, @NonNull ImsStateTracker imsStateTracker,
            @NonNull DestroyListener destroyListener,
            @NonNull CrossSimRedialingController csrController,
            @NonNull DataConnectionStateHelper epdnHelper) {
        super(context, slotId, subId, looper, imsStateTracker, destroyListener, TAG);

        mImsStateTracker.addBarringInfoListener(this);
        mImsStateTracker.addImsStateListener(this);

        PowerManager pm = context.getSystemService(PowerManager.class);
        mPartialWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);

        mCrossSimRedialingController = csrController;
        mEpdnHelper = epdnHelper;
        epdnHelper.setEmergencyCallDomainSelector(this);
        acquireWakeLock();
    }

    @Override
    public void handleMessage(Message msg) {
        if (mDestroyed) return;

        switch(msg.what) {
            case MSG_START_DOMAIN_SELECTION:
                startDomainSelection();
                break;

            case MSG_NETWORK_SCAN_TIMEOUT:
                handleNetworkScanTimeout();
                break;

            case MSG_NETWORK_SCAN_RESULT:
                handleScanResult((EmergencyRegistrationResult) msg.obj);
                break;

            case MSG_MAX_CELLULAR_TIMEOUT:
                handleMaxCellularTimeout();
                break;

            case MSG_WAIT_DISCONNECTION_TIMEOUT:
                requestScanDelayed();
                break;

            case MSG_WAIT_FOR_IMS_STATE_TIMEOUT:
                handleWaitForImsStateTimeout();
                break;

            case MSG_WIFI_AVAILABLE:
                handleWifiAvailable();
                break;

            default:
                super.handleMessage(msg);
                break;
        }
    }

    /**
     * Handles the scan result.
     *
     * @param result The scan result.
     */
    private void handleScanResult(EmergencyRegistrationResult result) {
        logi("handleScanResult result=" + result);

        if (mLastTransportType == TRANSPORT_TYPE_WLAN) {
            logi("handleScanResult timer expired, WLAN has been selected, ignore stale result");
            return;
        }

        // Detected the country and found that emergency calls are not allowed with this slot.
        if (!allowEmergencyCalls(result)) {
            terminateSelectionPermanentlyForSlot();
            return;
        }

        if (result.getAccessNetwork() == UNKNOWN) {
            if (maybeRedialOnTheOtherSlotInNormalService(mLastRegResult)) {
                return;
            }
            if ((mPreferredNetworkScanType == SCAN_TYPE_FULL_SERVICE_FOLLOWED_BY_LIMITED_SERVICE)
                      && (mScanType == DomainSelectionService.SCAN_TYPE_FULL_SERVICE)) {
                mScanType = DomainSelectionService.SCAN_TYPE_LIMITED_SERVICE;
                mWwanSelectorCallback.onRequestEmergencyNetworkScan(
                        mLastPreferredNetworks, mScanType, false, mCancelSignal,
                        (regResult) -> {
                            logi("requestScan-onComplete");
                            sendMessage(obtainMessage(MSG_NETWORK_SCAN_RESULT, regResult));
                        });
            } else if ((mPreferredNetworkScanType
                    == CarrierConfigManager.ImsEmergency.SCAN_TYPE_FULL_SERVICE)
                    && (mScanType == DomainSelectionService.SCAN_TYPE_FULL_SERVICE)) {
                mWwanSelectorCallback.onRequestEmergencyNetworkScan(
                        mLastPreferredNetworks, mScanType, true, mCancelSignal,
                        (regResult) -> {
                            logi("requestScan-onComplete");
                            sendMessage(obtainMessage(MSG_NETWORK_SCAN_RESULT, regResult));
                        });
            } else {
                // Continuous scan, do not start a new timer.
                requestScan(false);
            }
            return;
        }

        checkAndSetTerminateAfterCsFailure(result);

        if (result.getRegState() != REGISTRATION_STATE_HOME
                && result.getRegState() != REGISTRATION_STATE_ROAMING) {
            if (maybeRedialOnTheOtherSlotInNormalService(result)) {
                return;
            }
        }

        mLastRegResult = result;
        removeMessages(MSG_NETWORK_SCAN_TIMEOUT);
        onWwanNetworkTypeSelected(getAccessNetworkType(result));
        mCancelSignal = null;
        maybeModifyScanType(mLastNetworkType);
    }

    /**
     * Determines the scanned network type.
     *
     * @param result The result of network scan.
     * @return The selected network type.
     */
    private @RadioAccessNetworkType int getAccessNetworkType(EmergencyRegistrationResult result) {
        int accessNetworkType = result.getAccessNetwork();
        if (accessNetworkType != EUTRAN) return accessNetworkType;

        int regState = result.getRegState();

        // Emergency is not supported with LTE, but CSFB is possible.
        if ((regState == REGISTRATION_STATE_HOME || regState == REGISTRATION_STATE_ROAMING)
                && isCsDomainOnlyAvailable(result)) {
            logi("getAccessNetworkType emergency not supported but CSFB is possible");
            accessNetworkType = UTRAN;
        }

        return accessNetworkType;
    }

    private boolean isCsDomainOnlyAvailable(EmergencyRegistrationResult result) {
        int domain = result.getDomain();
        if (domain == NetworkRegistrationInfo.DOMAIN_CS) return true;
        if ((domain & NetworkRegistrationInfo.DOMAIN_CS) > 0) {
            // b/341865236, check emcBearer only
            return (!result.isEmcBearerSupported());
        }
        return false;
    }

    @Override
    public void reselectDomain(SelectionAttributes attr) {
        logi("reselectDomain attr=" + attr);
        mSelectionAttributes = attr;
        post(() -> { reselectDomain(); });
    }

    private void reselectDomain() {
        logi("reselectDomain tryCsWhenPsFails=" + mTryCsWhenPsFails);

        int cause = getDisconnectCause();
        mCrossSimRedialingController.notifyCallFailure(cause);

        if ((cause == EMERGENCY_TEMP_FAILURE && mCrossSimRedialingController.isThereOtherSlot())
                || cause == EMERGENCY_PERM_FAILURE) {
            logi("reselectDomain should redial on the other subscription");
            terminateSelectionForCrossSimRedialing(cause == EMERGENCY_PERM_FAILURE);
            return;
        }

        if (mCrossStackTimerExpired) {
            logi("reselectDomain cross stack timer expired");
            terminateSelectionForCrossSimRedialing(false);
            return;
        }

        if (maybeTerminateSelection(cause)) {
            logi("reselectDomain terminate selection");
            return;
        }

        mTerminateAfterCsFailure = false;

        if (mTryCsWhenPsFails) {
            mTryCsWhenPsFails = false;
            // Initial state was CSFB available and dial PS failed.
            // Dial CS for CSFB instead of scanning with CS preferred network list.
            logi("reselectDomain tryCs=" + accessNetworkTypeToString(mCsNetworkType));
            if (mCsNetworkType != UNKNOWN) {
                mWasCsfbAfterPsFailure = true;
                onWwanNetworkTypeSelected(mCsNetworkType);
                return;
            }
        }

        if (mWasCsfbAfterPsFailure) {
            mWasCsfbAfterPsFailure = false;
            if (preferCsAfterCsfbFailure(cause)) {
                // b/299875872, combined attach but EXTENDED_SERVICE_REQUEST failed.
                // Try CS preferred scan instead of PS preferred scan.
                mLastNetworkType = EUTRAN;
            }
        }

        if (mMaxCellularTimerExpired) {
            if (mLastTransportType == TRANSPORT_TYPE_WWAN
                    && maybeDialOverWlan()) {
                // Cellular call failed and max cellular search timer expired, so redial on Wi-Fi.
                // If this VoWi-Fi fails, the timer shall be restarted on next reselectDomain().
                return;
            } else if (mLastTransportType == TRANSPORT_TYPE_WLAN) {
                // Since VoWi-Fi failed, allow for requestScan to restart max cellular timer.
                mMaxCellularTimerExpired = false;
            }
        }

        if (mLastTransportType == TRANSPORT_TYPE_WWAN) {
            if (mLastNetworkType == NGRAN && (!mTryEsFallback) && mLtePreferredAfterNrFailure) {
                int state = mEpdnHelper.getDataConnectionState(getSlotId());
                if (state != DATA_DISCONNECTED && state != DATA_UNKNOWN) {
                    mIsWaitingForDataDisconnection = true;
                    // If deactivation of ePDN has been started, then wait for the disconnection
                    // with the timeout of 2 seconds and then request network scan.
                    // If deactivation of ePDN hasn't been started yet, then wait for the start
                    // of the deactivation with the timeout of 2 seconds.
                    // The timer shall be restarted in notifyDataConnectionStateChange()
                    // when starting the deactivation.
                    sendEmptyMessageDelayed(MSG_WAIT_DISCONNECTION_TIMEOUT,
                            DEFAULT_DATA_DISCONNECTION_TIMEOUT_MS);
                    mDomainSelected = false;
                    return;
                }
            }
        }

        if (mLastTransportType == TRANSPORT_TYPE_WLAN) {
            // Dialing over Wi-Fi failed. Try scanning cellular networks.
            onWwanSelected(this::reselectDomainInternal);
            return;
        }

        if (mLastNetworkType == EUTRAN && mLastRegResult != null
                && mSelectionAttributes.getPsDisconnectCause() != null
                && !mScanLimitedOnlyAfterVolteFailure
                && !mSwitchRatPreferenceWithLocalNotRegistered) {
            int regState = mLastRegResult.getRegState();
            int reasonCode = mSelectionAttributes.getPsDisconnectCause().getCode();
            if (reasonCode == ImsReasonInfo.CODE_LOCAL_NOT_REGISTERED
                    && regState != REGISTRATION_STATE_HOME
                    && regState != REGISTRATION_STATE_ROAMING
                    && isSimReady()) {
                // b/326292100, ePDN setup failed in limited state, request PS preferred scan.
                mLastNetworkType = UNKNOWN;
                mSwitchRatPreferenceWithLocalNotRegistered = true;
            }
        }

        requestScan(true);
        mDomainSelected = false;
    }

    private boolean preferCsAfterCsfbFailure(int cause) {
        if (cause != SERVICE_OPTION_NOT_AVAILABLE) return false;
        if (sPreferCsAfterCsfbFailure == null || mLastRegResult == null
                || TextUtils.isEmpty(mLastRegResult.getCountryIso())) {
            // Enabled by default if country is not identified.
            return true;
        }

        return sPreferCsAfterCsfbFailure.contains(mLastRegResult.getCountryIso());
    }

    private int getDisconnectCause() {
        int cause = mSelectionAttributes.getCsDisconnectCause();

        ImsReasonInfo reasonInfo = mSelectionAttributes.getPsDisconnectCause();
        if (reasonInfo != null) {
            switch (reasonInfo.getCode()) {
                case ImsReasonInfo.CODE_EMERGENCY_TEMP_FAILURE:
                    cause = EMERGENCY_TEMP_FAILURE;
                    break;
                case ImsReasonInfo.CODE_EMERGENCY_PERM_FAILURE:
                    cause = EMERGENCY_PERM_FAILURE;
                    break;
                default:
                    break;
            }
        }
        return cause;
    }

    private void reselectDomainInternal() {
        post(() -> {
            if (mDestroyed) return;
            requestScan(true, false, true);
            mDomainSelected = false;
        });
    }

    private void requestScanDelayed() {
        logi("requestScanDelayed waiting=" + mIsWaitingForDataDisconnection);
        if (!mDestroyed && mIsWaitingForDataDisconnection) {
            requestScan(true);
            removeMessages(MSG_WAIT_DISCONNECTION_TIMEOUT);
        }
        mIsWaitingForDataDisconnection = false;
    }

    @Override
    public void finishSelection() {
        logi("finishSelection");
        destroy();
    }

    @Override
    public void onBarringInfoUpdated(BarringInfo barringInfo) {
        if (mDestroyed) return;

        mBarringInfoReceived = true;
        BarringInfo.BarringServiceInfo serviceInfo =
                barringInfo.getBarringServiceInfo(BARRING_SERVICE_TYPE_EMERGENCY);
        mIsEmergencyBarred = serviceInfo.isBarred();
        logi("onBarringInfoUpdated emergencyBarred=" + mIsEmergencyBarred
                + ", serviceInfo=" + serviceInfo);
        selectDomain();
    }

    @Override
    public void selectDomain(SelectionAttributes attr, TransportSelectorCallback cb) {
        logi("selectDomain attr=" + attr);
        mTransportSelectorCallback = cb;
        mSelectionAttributes = attr;
        mLastRegResult = mSelectionAttributes.getEmergencyRegistrationResult();

        TelephonyManager tm = mContext.getSystemService(TelephonyManager.class);
        mModemCount = tm.getActiveModemCount();

        sendEmptyMessage(MSG_START_DOMAIN_SELECTION);
    }

    private void startDomainSelection() {
        logi("startDomainSelection modemCount=" + mModemCount);
        readResourceConfiguration();
        updateCarrierConfiguration();
        mDomainSelectionRequested = true;
        startCrossStackTimer();
        if (SubscriptionManager.isValidSubscriptionId(getSubId())) {
            sendEmptyMessageDelayed(MSG_WAIT_FOR_IMS_STATE_TIMEOUT,
                    DEFAULT_WAIT_FOR_IMS_STATE_TIMEOUT_MS);
            selectDomain();
        } else {
            logi("startDomainSelection invalid subId");
            onImsRegistrationStateChanged();
            onImsMmTelCapabilitiesChanged();
        }
    }

    private void handleWaitForImsStateTimeout() {
        logi("handleWaitForImsStateTimeout");
        onImsRegistrationStateChanged();
        onImsMmTelCapabilitiesChanged();
    }

    @Override
    public void onImsMmTelFeatureAvailableChanged() {
        // DOMAIN_CS shall be selected when ImsService is not available.
        // TODO(b/258289015) Recover the temporary failure in ImsService connection.
    }

    @Override
    public void onImsRegistrationStateChanged() {
        mImsRegStateReceived = true;
        mImsRegistered = mImsStateTracker.isImsRegistered();
        logi("onImsRegistrationStateChanged " + mImsRegistered);
        selectDomain();
        handleImsStateChange();
    }

    @Override
    public void onImsMmTelCapabilitiesChanged() {
        mMmTelCapabilitiesReceived = true;
        mIsVoiceCapable = mImsStateTracker.isImsVoiceCapable();
        logi("onImsMmTelCapabilitiesChanged " + mIsVoiceCapable);
        selectDomain();
        handleImsStateChange();
    }

    private void handleImsStateChange() {
        if (!mVoWifiOverEmergencyPdn && !mDomainSelected
                && (mMaxCellularTimerExpired || mNetworkScanTimerExpired)) {
            maybeDialOverWlan();
        }
    }

    private boolean isSimReady() {
        if (!SubscriptionManager.isValidSubscriptionId(getSubId())) return false;
        TelephonyManager tm = mContext.getSystemService(TelephonyManager.class);
        return tm.getSimState(getSlotId()) == TelephonyManager.SIM_STATE_READY;
    }

    /**
     * Caches the configuration.
     */
    private void updateCarrierConfiguration() {
        CarrierConfigManager configMgr = mContext.getSystemService(CarrierConfigManager.class);
        PersistableBundle b = configMgr.getConfigForSubId(getSubId(),
                KEY_EMERGENCY_OVER_IMS_SUPPORTED_3GPP_NETWORK_TYPES_INT_ARRAY,
                KEY_EMERGENCY_OVER_IMS_ROAMING_SUPPORTED_3GPP_NETWORK_TYPES_INT_ARRAY,
                KEY_EMERGENCY_OVER_CS_SUPPORTED_ACCESS_NETWORK_TYPES_INT_ARRAY,
                KEY_EMERGENCY_OVER_CS_ROAMING_SUPPORTED_ACCESS_NETWORK_TYPES_INT_ARRAY,
                KEY_EMERGENCY_DOMAIN_PREFERENCE_INT_ARRAY,
                KEY_EMERGENCY_DOMAIN_PREFERENCE_ROAMING_INT_ARRAY,
                KEY_PREFER_IMS_EMERGENCY_WHEN_VOICE_CALLS_ON_CS_BOOL,
                KEY_EMERGENCY_VOWIFI_REQUIRES_CONDITION_INT,
                KEY_EMERGENCY_SCAN_TIMER_SEC_INT,
                KEY_MAXIMUM_CELLULAR_SEARCH_TIMER_SEC_INT,
                KEY_MAXIMUM_NUMBER_OF_EMERGENCY_TRIES_OVER_VOWIFI_INT,
                KEY_EMERGENCY_CALL_OVER_EMERGENCY_PDN_BOOL,
                KEY_EMERGENCY_NETWORK_SCAN_TYPE_INT,
                KEY_EMERGENCY_CALL_SETUP_TIMER_ON_CURRENT_NETWORK_SEC_INT,
                KEY_EMERGENCY_REQUIRES_IMS_REGISTRATION_BOOL,
                KEY_EMERGENCY_REQUIRES_VOLTE_ENABLED_BOOL,
                KEY_EMERGENCY_LTE_PREFERRED_AFTER_NR_FAILED_BOOL,
                KEY_SCAN_LIMITED_SERVICE_AFTER_VOLTE_FAILURE_BOOL,
                KEY_IMS_REASONINFO_CODE_TO_RETRY_EMERGENCY_INT_ARRAY,
                KEY_CARRIER_VOLTE_TTY_SUPPORTED_BOOL,
                KEY_EMERGENCY_CDMA_PREFERRED_NUMBERS_STRING_ARRAY);
        if (b == null) {
            b = CarrierConfigManager.getDefaultConfig();
        }

        mImsRatsConfig =
                b.getIntArray(KEY_EMERGENCY_OVER_IMS_SUPPORTED_3GPP_NETWORK_TYPES_INT_ARRAY);
        mImsRoamRatsConfig = b.getIntArray(
                KEY_EMERGENCY_OVER_IMS_ROAMING_SUPPORTED_3GPP_NETWORK_TYPES_INT_ARRAY);

        mCsRatsConfig =
                b.getIntArray(KEY_EMERGENCY_OVER_CS_SUPPORTED_ACCESS_NETWORK_TYPES_INT_ARRAY);
        mCsRoamRatsConfig = b.getIntArray(
                KEY_EMERGENCY_OVER_CS_ROAMING_SUPPORTED_ACCESS_NETWORK_TYPES_INT_ARRAY);
        mDomainPreference = b.getIntArray(KEY_EMERGENCY_DOMAIN_PREFERENCE_INT_ARRAY);
        mDomainPreferenceRoam = b.getIntArray(KEY_EMERGENCY_DOMAIN_PREFERENCE_ROAMING_INT_ARRAY);
        mPreferImsWhenCallsOnCs = b.getBoolean(
                KEY_PREFER_IMS_EMERGENCY_WHEN_VOICE_CALLS_ON_CS_BOOL);
        mVoWifiRequiresCondition = b.getInt(KEY_EMERGENCY_VOWIFI_REQUIRES_CONDITION_INT);
        mScanTimeout = b.getInt(KEY_EMERGENCY_SCAN_TIMER_SEC_INT) * 1000;
        mMaxCellularTimeout = b.getInt(KEY_MAXIMUM_CELLULAR_SEARCH_TIMER_SEC_INT) * 1000;
        mMaxNumOfVoWifiTries = b.getInt(KEY_MAXIMUM_NUMBER_OF_EMERGENCY_TRIES_OVER_VOWIFI_INT);
        mVoWifiOverEmergencyPdn = b.getBoolean(KEY_EMERGENCY_CALL_OVER_EMERGENCY_PDN_BOOL);
        mPreferredNetworkScanType = b.getInt(KEY_EMERGENCY_NETWORK_SCAN_TYPE_INT);
        mCallSetupTimerOnCurrentRat = b.getInt(
                KEY_EMERGENCY_CALL_SETUP_TIMER_ON_CURRENT_NETWORK_SEC_INT) * 1000;
        mRequiresImsRegistration = b.getBoolean(KEY_EMERGENCY_REQUIRES_IMS_REGISTRATION_BOOL);
        mRequiresVoLteEnabled = b.getBoolean(KEY_EMERGENCY_REQUIRES_VOLTE_ENABLED_BOOL);
        mLtePreferredAfterNrFailure = b.getBoolean(
                KEY_EMERGENCY_LTE_PREFERRED_AFTER_NR_FAILED_BOOL);
        mScanLimitedOnlyAfterVolteFailure = b.getBoolean(
                KEY_SCAN_LIMITED_SERVICE_AFTER_VOLTE_FAILURE_BOOL);
        String[] numbers = b.getStringArray(KEY_EMERGENCY_CDMA_PREFERRED_NUMBERS_STRING_ARRAY);
        int[] imsReasonCodes =
                b.getIntArray(KEY_IMS_REASONINFO_CODE_TO_RETRY_EMERGENCY_INT_ARRAY);
        boolean ttySupported = b.getBoolean(KEY_CARRIER_VOLTE_TTY_SUPPORTED_BOOL);
        mNonTtyOrTtySupported = isNonTtyOrTtySupported(ttySupported);

        if (mImsRatsConfig == null) mImsRatsConfig = new int[0];
        if (mCsRatsConfig == null) mCsRatsConfig = new int[0];
        if (mImsRoamRatsConfig == null) mImsRoamRatsConfig = new int[0];
        if (mCsRoamRatsConfig == null) mCsRoamRatsConfig = new int[0];
        if (mDomainPreference == null) mDomainPreference = new int[0];
        if (mDomainPreferenceRoam == null) mDomainPreferenceRoam = new int[0];
        if (numbers == null) numbers = new String[0];
        if (imsReasonCodes == null) imsReasonCodes = new int[0];

        mRetryReasonCodes = Arrays.stream(imsReasonCodes).boxed().collect(Collectors.toList());
        mRetryReasonCodes.addAll(sDefaultRetryReasonCodes);

        logi("updateCarrierConfiguration "
                + "imsRats=" + arrayToString(mImsRatsConfig,
                        EmergencyCallDomainSelector::accessNetworkTypeToString)
                + ", csRats=" + arrayToString(mCsRatsConfig,
                        EmergencyCallDomainSelector::accessNetworkTypeToString)
                + ", imsRoamRats=" + arrayToString(mImsRoamRatsConfig,
                        EmergencyCallDomainSelector::accessNetworkTypeToString)
                + ", csRoamRats=" + arrayToString(mCsRoamRatsConfig,
                        EmergencyCallDomainSelector::accessNetworkTypeToString)
                + ", domainPref=" + arrayToString(mDomainPreference,
                        EmergencyCallDomainSelector::domainPreferenceToString)
                + ", domainPrefRoam=" + arrayToString(mDomainPreferenceRoam,
                        EmergencyCallDomainSelector::domainPreferenceToString)
                + ", preferImsOnCs=" + mPreferImsWhenCallsOnCs
                + ", voWifiRequiresCondition=" + mVoWifiRequiresCondition
                + ", scanTimeout=" + mScanTimeout
                + ", maxCellularTimeout=" + mMaxCellularTimeout
                + ", maxNumOfVoWifiTries=" + mMaxNumOfVoWifiTries
                + ", voWifiOverEmergencyPdn=" + mVoWifiOverEmergencyPdn
                + ", preferredScanType=" + carrierConfigNetworkScanTypeToString(
                        mPreferredNetworkScanType)
                + ", callSetupTimer=" + mCallSetupTimerOnCurrentRat
                + ", requiresImsReg=" + mRequiresImsRegistration
                + ", requiresVoLteEnabled=" + mRequiresVoLteEnabled
                + ", ltePreferredAfterNr=" + mLtePreferredAfterNrFailure
                + ", scanLimitedOnly=" + mScanLimitedOnlyAfterVolteFailure
                + ", retryReasonCodes=" + mRetryReasonCodes
                + ", ttySupported=" + ttySupported
                + ", cdmaPreferredNumbers=" + arrayToString(numbers));

        mCdmaPreferredNumbers = Arrays.asList(numbers);

        if ((mPreferredNetworkScanType == CarrierConfigManager.ImsEmergency.SCAN_TYPE_FULL_SERVICE)
                || (mPreferredNetworkScanType
                        == SCAN_TYPE_FULL_SERVICE_FOLLOWED_BY_LIMITED_SERVICE)) {
            mScanType = DomainSelectionService.SCAN_TYPE_FULL_SERVICE;
        } else {
            mScanType = DomainSelectionService.SCAN_TYPE_NO_PREFERENCE;
        }
    }

    /**
     * Caches the resource configuration.
     */
    private void readResourceConfiguration() {
        if (sSimReadyAllowList == null) {
            sSimReadyAllowList = readResourceConfiguration(
                    R.array.config_countries_require_sim_for_emergency);
        }
        logi("readResourceConfiguration simReadyCountries=" + sSimReadyAllowList);

        if (sPreferSlotWithNormalServiceList == null) {
            sPreferSlotWithNormalServiceList = readResourceConfiguration(
                    R.array.config_countries_prefer_normal_service_capable_subscription);
        }
        logi("readResourceConfiguration preferNormalServiceCountries="
                + sPreferSlotWithNormalServiceList);

        if (sPreferCsAfterCsfbFailure == null) {
            sPreferCsAfterCsfbFailure = readResourceConfiguration(
                    R.array.config_countries_prefer_cs_preferred_scan_after_csfb_failure);
        }
        logi("readResourceConfiguration preferCsAfterCsfbFailure="
                + sPreferCsAfterCsfbFailure);

        if (sPreferGeranWhenSimAbsent == null) {
            sPreferGeranWhenSimAbsent = readResourceConfiguration(
                    R.array.config_countries_prefer_geran_when_sim_absent);
        }
        logi("readResourceConfiguration preferGeranWhenSimAbsent="
                + sPreferGeranWhenSimAbsent);
    }

    private List<String> readResourceConfiguration(int id) {
        logi("readResourceConfiguration id=" + id);

        List<String> resource = null;
        try {
            resource = Arrays.asList(mContext.getResources().getStringArray(id));
        } catch (Resources.NotFoundException nfe) {
            loge("readResourceConfiguration exception=" + nfe);
        } catch (NullPointerException npe) {
            loge("readResourceConfiguration exception=" + npe);
        } finally {
            if (resource == null) {
                resource = new ArrayList<String>();
            }
        }
        return resource;
    }

    /** For test purpose only */
    @VisibleForTesting
    public void clearResourceConfiguration() {
        sSimReadyAllowList = null;
        sPreferSlotWithNormalServiceList = null;
        sPreferCsAfterCsfbFailure = null;
        sPreferGeranWhenSimAbsent = null;
    }

    private void selectDomain() {
        // State updated right after creation.
        if (!mDomainSelectionRequested) return;

        if (!mBarringInfoReceived || !mImsRegStateReceived || !mMmTelCapabilitiesReceived) {
            logi("selectDomain not received"
                    + " BarringInfo, IMS registration state, or MMTEL capabilities");
            return;
        }
        removeMessages(MSG_WAIT_FOR_IMS_STATE_TIMEOUT);

        // The statements below should be executed only once to select domain from initial state.
        // Next domain selection shall be triggered by reselectDomain().
        // However, selectDomain() can be called by change of IMS service state and Barring status
        // at any time. mIsScanRequested and mDomainSelected are not enough since there are cases
        // when neither mIsScanRequested nor mDomainSelected is set though selectDomain() has been
        // executed already.
        // Reset mDomainSelectionRequested to avoid redundant execution of selectDomain().
        mDomainSelectionRequested = false;

        if (!allowEmergencyCalls(mSelectionAttributes.getEmergencyRegistrationResult())) {
            // Detected the country and found that emergency calls are not allowed with this slot.
            terminateSelectionPermanentlyForSlot();
            return;
        }

        if (isWifiPreferred()
                || isInEmergencyCallbackModeOnWlan()) {
            onWlanSelected();
            return;
        }

        onWwanSelected(this::selectDomainInternal);
    }

    private void selectDomainInternal() {
        post(this::selectDomainFromInitialState);
    }

    private void selectDomainFromInitialState() {
        if (mDestroyed) return;

        if (isInEmergencyCallbackModeOnPsWwan()) {
            logi("selectDomain PS cellular connected in ECBM");
            mPsNetworkType = EUTRAN;
            onWwanNetworkTypeSelected(mPsNetworkType);
            return;
        }

        boolean csInService = isCsInService();
        boolean psInService = isPsInService();

        if (!csInService && !psInService) {
            if (maybeRedialOnTheOtherSlotInNormalService(mLastRegResult)) {
                return;
            }
            mCsNetworkType = getSelectableCsNetworkType();
            mPsNetworkType = getSelectablePsNetworkType(false);
            logi("selectDomain limited service ps=" + accessNetworkTypeToString(mPsNetworkType)
                    + ", cs=" + accessNetworkTypeToString(mCsNetworkType));
            if (!isInRoaming()
                    && (mPreferredNetworkScanType
                            == CarrierConfigManager.ImsEmergency.SCAN_TYPE_FULL_SERVICE)) {
                requestScan(true);
                return;
            }
            // If NGRAN, request scan to trigger emergency registration.
            if (mPsNetworkType == EUTRAN) {
                onWwanNetworkTypeSelected(mPsNetworkType);
            } else if (mCsNetworkType != UNKNOWN) {
                checkAndSetTerminateAfterCsFailure(mLastRegResult);
                onWwanNetworkTypeSelected(mCsNetworkType);
            } else {
                requestScan(true);
            }
            maybeModifyScanType(mLastNetworkType);
            return;
        }

        // Domain selection per 3GPP TS 23.167 Table H.1.
        // PS is preferred in case selection between CS and PS is implementation option.
        mCsNetworkType = UNKNOWN;
        mPsNetworkType = UNKNOWN;
        if (csInService) mCsNetworkType = getSelectableCsNetworkType();
        if (psInService) mPsNetworkType = getSelectablePsNetworkType(true);

        boolean csAvailable = mCsNetworkType != UNKNOWN;
        boolean psAvailable = mPsNetworkType != UNKNOWN;

        logi("selectDomain CS={" + csInService + ", " + accessNetworkTypeToString(mCsNetworkType)
                + "}, PS={" + psInService + ", " + accessNetworkTypeToString(mPsNetworkType) + "}");
        if (csAvailable && psAvailable) {
            if (mSelectionAttributes.isExitedFromAirplaneMode()
                    || mPreferImsWhenCallsOnCs || isImsRegisteredWithVoiceCapability()) {
                mTryCsWhenPsFails = true;
                onWwanNetworkTypeSelected(mPsNetworkType);
            } else if (isDeactivatedSim()) {
                // Deactivated SIM but PS is in service and supports emergency calls.
                onWwanNetworkTypeSelected(mPsNetworkType);
            } else {
                onWwanNetworkTypeSelected(mCsNetworkType);
            }
        } else if (psAvailable) {
            mTryEsFallback = (mPsNetworkType == NGRAN) && isEsFallbackAvailable();
            if (mSelectionAttributes.isExitedFromAirplaneMode()
                    || !mRequiresImsRegistration || isImsRegisteredWithVoiceCapability()) {
                onWwanNetworkTypeSelected(mPsNetworkType);
            } else if (isDeactivatedSim()) {
                // Deactivated SIM but PS is in service and supports emergency calls.
                onWwanNetworkTypeSelected(mPsNetworkType);
            } else {
                // Carrier configuration requires IMS registration for emergency services over PS,
                // but not registered. Try CS emergency call.
                mTryEsFallback = false;
                requestScan(true, true);
            }
        } else if (csAvailable) {
            onWwanNetworkTypeSelected(mCsNetworkType);
        } else {
            // PS is in service but not supports emergency calls.
            if (!mSelectionAttributes.isExitedFromAirplaneMode()
                    && mRequiresImsRegistration && !isImsRegisteredWithVoiceCapability()) {
                // Carrier configuration requires IMS registration for emergency services over PS,
                // but not registered. Try CS emergency call.
                requestScan(true, true);
            } else {
                mTryEsFallback = isEsFallbackAvailable();
                requestScan(true);
            }
        }
        maybeModifyScanType(mLastNetworkType);
    }

    /**
     * Requests network scan.
     *
     * @param startVoWifiTimer Indicates whether a VoWifi timer will be started.
     */
    private void requestScan(boolean startVoWifiTimer) {
        requestScan(startVoWifiTimer, false);
    }

    /**
     * Requests network scan.
     *
     * @param startVoWifiTimer Indicates whether a VoWifi timer will be started.
     * @param csPreferred Indicates whether CS preferred scan is requested.
     */
    private void requestScan(boolean startVoWifiTimer, boolean csPreferred) {
        requestScan(startVoWifiTimer, csPreferred, false);
    }

    /**
     * Requests network scan.
     *
     * @param startVoWifiTimer Indicates whether a VoWifi timer will be started.
     * @param csPreferred Indicates whether CS preferred scan is requested.
     * @param wifiFailed Indicates dialing over Wi-Fi has failed.
     */
    private void requestScan(boolean startVoWifiTimer, boolean csPreferred, boolean wifiFailed) {
        logi("requestScan timer=" + startVoWifiTimer + ", csPreferred=" + csPreferred
                + ", wifiFailed=" + wifiFailed);

        mCancelSignal = new CancellationSignal();
        // In case dialing over Wi-Fi has failed, do not the change the domain preference.
        if (!wifiFailed || mLastPreferredNetworks == null) {
            mLastPreferredNetworks = getNextPreferredNetworks(csPreferred, mTryEsFallback);
        }
        mTryEsFallback = false;

        if (isInRoaming()
                && (mPreferredNetworkScanType
                        == CarrierConfigManager.ImsEmergency.SCAN_TYPE_FULL_SERVICE)) {
            // FULL_SERVICE only preference is available only when not in roaming.
            mScanType = DomainSelectionService.SCAN_TYPE_NO_PREFERENCE;
        }

        mIsScanRequested = true;
        mWwanSelectorCallback.onRequestEmergencyNetworkScan(
                mLastPreferredNetworks, mScanType, false, mCancelSignal,
                (result) -> {
                    logi("requestScan-onComplete");
                    sendMessage(obtainMessage(MSG_NETWORK_SCAN_RESULT, result));
                });

        if (startVoWifiTimer && isSimReady()) {
            if (isEmcOverWifiSupported()
                    && mScanTimeout > 0 && mVoWifiTrialCount < mMaxNumOfVoWifiTries) {
                logi("requestScan start scan timer");
                // remove any pending timers.
                removeMessages(MSG_NETWORK_SCAN_TIMEOUT);
                sendEmptyMessageDelayed(MSG_NETWORK_SCAN_TIMEOUT, mScanTimeout);
                registerForConnectivityChanges();
            }
        }
        if (!mMaxCellularTimerExpired && !hasMessages(MSG_MAX_CELLULAR_TIMEOUT)) {
            startMaxCellularTimer();
        }
    }

    /**
     * Gets the list of preferred network type for the new scan request.
     *
     * @param csPreferred Indicates whether CS preferred scan is requested.
     * @param tryEsFallback Indicates whether scan requested for ES fallback.
     * @return The list of preferred network types.
     */
    @VisibleForTesting
    public @RadioAccessNetworkType List<Integer> getNextPreferredNetworks(boolean csPreferred,
            boolean tryEsFallback) {
        if (mRequiresVoLteEnabled && !isAdvancedCallingSettingEnabled()) {
            // Emergency call over IMS is not supported.
            logi("getNextPreferredNetworks VoLte setting is not enabled.");
            return generatePreferredNetworks(getCsNetworkTypeConfiguration());
        }

        List<Integer> preferredNetworks = new ArrayList<>();

        List<Integer> domains = getDomainPreference();
        int psPriority = domains.indexOf(DOMAIN_PS_3GPP);
        int csPriority = domains.indexOf(DOMAIN_CS);
        logi("getNextPreferredNetworks psPriority=" + psPriority + ", csPriority=" + csPriority
                + ", csPreferred=" + csPreferred + ", esFallback=" + tryEsFallback
                + ", lastNetworkType=" + accessNetworkTypeToString(mLastNetworkType));

        if (mLastRegResult != null
                && sPreferGeranWhenSimAbsent.contains(mLastRegResult.getCountryIso())
                && !isSimReady()) {
            logi("getNextPreferredNetworks preferGeran");
            preferredNetworks.add(GERAN);
            preferredNetworks.add(UTRAN);
            preferredNetworks.add(EUTRAN);
            preferredNetworks.add(NGRAN);
            return preferredNetworks;
        }

        if (!csPreferred && (mLastNetworkType == UNKNOWN || tryEsFallback)) {
            // Generate the list per the domain preference.

            if (psPriority == NOT_SUPPORTED && csPriority == NOT_SUPPORTED) {
                // should not reach here. However, to avoid unexpected problems.
                preferredNetworks = generatePreferredNetworks(getCsNetworkTypeConfiguration(),
                        getImsNetworkTypeConfiguration());
            } else if (psPriority == NOT_SUPPORTED && csPriority > NOT_SUPPORTED) {
                // CS networks only.
                preferredNetworks = generatePreferredNetworks(getCsNetworkTypeConfiguration());
            } else if (psPriority > NOT_SUPPORTED && csPriority == NOT_SUPPORTED) {
                // PS networks only.
                preferredNetworks = generatePreferredNetworks(getImsNetworkTypeConfiguration());
            } else if (psPriority < csPriority) {
                // PS preferred.
                preferredNetworks = generatePreferredNetworks(getImsNetworkTypeConfiguration(),
                        getCsNetworkTypeConfiguration());
            } else {
                // CS preferred.
                preferredNetworks = generatePreferredNetworks(getCsNetworkTypeConfiguration(),
                        getImsNetworkTypeConfiguration());
            }

            // Make NGRAN have the lowest priority
            if (tryEsFallback && preferredNetworks.contains(NGRAN)) {
                preferredNetworks.remove(Integer.valueOf(NGRAN));
                preferredNetworks.add(NGRAN);
            }
        } else if (csPreferred || mLastNetworkType == EUTRAN || mLastNetworkType == NGRAN) {
            if (!csPreferred && mLastNetworkType == NGRAN && mLtePreferredAfterNrFailure) {
                // LTE is preferred after dialing over NR failed.
                preferredNetworks = generatePreferredNetworks(getImsNetworkTypeConfiguration(),
                        getCsNetworkTypeConfiguration());
                // Make NGRAN have the lowest priority
                if (preferredNetworks.contains(NGRAN)) {
                    preferredNetworks.remove(Integer.valueOf(NGRAN));
                    preferredNetworks.add(NGRAN);
                }
            } else  if (csPriority > NOT_SUPPORTED) {
                // PS tried, generate the list with CS preferred.
                preferredNetworks = generatePreferredNetworks(getCsNetworkTypeConfiguration(),
                        getImsNetworkTypeConfiguration());
            } else {
                // CS not suppored.
                preferredNetworks = generatePreferredNetworks(getImsNetworkTypeConfiguration());
            }
        } else {
            // CS tried, generate the list with PS preferred.
            if (psPriority > NOT_SUPPORTED) {
                preferredNetworks = generatePreferredNetworks(getImsNetworkTypeConfiguration(),
                        getCsNetworkTypeConfiguration());
            } else {
                // PS not suppored.
                preferredNetworks = generatePreferredNetworks(getCsNetworkTypeConfiguration());
            }
        }

        // Adds NGRAN at the end of the list if SIM is absent or locked and NGRAN is not included.
        if (!isSimReady() && !preferredNetworks.contains(NGRAN)) {
            preferredNetworks.add(NGRAN);
        }

        if (!mNonTtyOrTtySupported) {
            logi("getNextPreferredNetworks adjust for TTY");
            preferredNetworks.remove(Integer.valueOf(NGRAN));
            preferredNetworks.remove(Integer.valueOf(EUTRAN));
            if (preferredNetworks.isEmpty()) {
                preferredNetworks.add(Integer.valueOf(UTRAN));
                preferredNetworks.add(Integer.valueOf(GERAN));
            }
        }
        return preferredNetworks;
    }

    private @RadioAccessNetworkType List<Integer> generatePreferredNetworks(List<Integer>...lists) {
        List<Integer> preferredNetworks = new ArrayList<>();
        for (List<Integer> list : lists) {
            preferredNetworks.addAll(list);
        }

        return preferredNetworks;
    }

    private void handleWifiAvailable() {
        if (!mDomainSelected && (mMaxCellularTimerExpired || mNetworkScanTimerExpired)) {
            maybeDialOverWlan();
        }
    }

    private void handleMaxCellularTimeout() {
        logi("handleMaxCellularTimeout");
        if (mVoWifiTrialCount >= mMaxNumOfVoWifiTries) {
            logi("handleMaxCellularTimeout already tried maximum");
            return;
        }

        mMaxCellularTimerExpired = true;

        if (mDomainSelected) {
            // Dialing is already requested.
            logi("handleMaxCellularTimeout wait for reselectDomain");
            return;
        }

        if (!maybeDialOverWlan()) {
            logd("handleMaxCellularTimeout VoWi-Fi is not available");
        }
    }

    private void handleNetworkScanTimeout() {
        logi("handleNetworkScanTimeout");
        mNetworkScanTimerExpired = true;
        maybeDialOverWlan();
    }

    private boolean maybeDialOverWlan() {
        boolean available = mWiFiAvailable;
        logi("maybeDialOverWlan overEmergencyPdn=" + mVoWifiOverEmergencyPdn
                + ", wifiAvailable=" + available);
        if (mVoWifiOverEmergencyPdn) {
            // SOS APN
            if (!available && isImsRegisteredOverCrossSim()) {
                available = true;
            }
            if (available) {
                switch (mVoWifiRequiresCondition) {
                    case VOWIFI_REQUIRES_SETTING_ENABLED:
                        available = isWifiCallingSettingEnabled();
                        break;
                    case VOWIFI_REQUIRES_VALID_EID:
                        available = isWifiCallingActivated();
                        break;
                    default:
                        break;
                }
            }
        } else {
            // IMS APN. When IMS is already registered over Wi-Fi.
            available = isImsRegisteredWithVoiceCapability() && isImsRegisteredOverWifi();
        }

        logi("maybeDialOverWlan VoWi-Fi available=" + available);
        if (available) {
            if (mCancelSignal != null) {
                mCancelSignal.cancel();
                mCancelSignal = null;
            }
            onWlanSelected();
        }

        return available;
    }

    /**
     * Determines whether CS is in service.
     *
     * @return {@code true} if CS is in service.
     */
    private boolean isCsInService() {
        EmergencyRegistrationResult regResult =
                mSelectionAttributes.getEmergencyRegistrationResult();
        if (regResult == null) return false;

        int regState = regResult.getRegState();
        int domain = regResult.getDomain();

        if ((regState == REGISTRATION_STATE_HOME || regState == REGISTRATION_STATE_ROAMING)
                && ((domain & NetworkRegistrationInfo.DOMAIN_CS) > 0)) {
            return true;
        }

        return false;
    }

    /**
     * Determines the network type of the circuit-switched(CS) network.
     *
     * @return The network type of the CS network.
     */
    private @RadioAccessNetworkType int getSelectableCsNetworkType() {
        List<Integer> domains = getDomainPreference();
        if (domains.indexOf(DOMAIN_CS) == NOT_SUPPORTED) {
            return UNKNOWN;
        }
        EmergencyRegistrationResult regResult =
                mSelectionAttributes.getEmergencyRegistrationResult();
        logi("getSelectableCsNetworkType regResult=" + regResult);
        if (regResult == null) return UNKNOWN;

        int accessNetwork = regResult.getAccessNetwork();

        List<Integer> ratList = getCsNetworkTypeConfiguration();
        if (ratList.contains(accessNetwork)) {
            return accessNetwork;
        }

        if ((regResult.getAccessNetwork() == EUTRAN)
                && ((regResult.getDomain() & NetworkRegistrationInfo.DOMAIN_CS) > 0)) {
            if (ratList.contains(UTRAN)) return UTRAN;
        }

        return UNKNOWN;
    }

    /**
     * Determines whether PS is in service.
     *
     * @return {@code true} if PS is in service.
     */
    private boolean isPsInService() {
        EmergencyRegistrationResult regResult =
                mSelectionAttributes.getEmergencyRegistrationResult();
        if (regResult == null) return false;

        int regState = regResult.getRegState();
        int domain = regResult.getDomain();

        if ((regState == REGISTRATION_STATE_HOME || regState == REGISTRATION_STATE_ROAMING)
                && ((domain & NetworkRegistrationInfo.DOMAIN_PS) > 0)) {
            return true;
        }

        return false;
    }

    /**
     * Determines the network type supporting emergency services over packet-switched(PS) network.
     *
     * @param inService Indicates whether PS is IN_SERVICE state.
     * @return The network type if the network supports emergency services over PS network.
     */
    private @RadioAccessNetworkType int getSelectablePsNetworkType(boolean inService) {
        List<Integer> domains = getDomainPreference();
        if ((domains.indexOf(DOMAIN_PS_3GPP) == NOT_SUPPORTED)
                || !mNonTtyOrTtySupported) {
            return UNKNOWN;
        }
        EmergencyRegistrationResult regResult =
                mSelectionAttributes.getEmergencyRegistrationResult();
        logi("getSelectablePsNetworkType regResult=" + regResult);
        if (regResult == null) return UNKNOWN;
        if (mRequiresVoLteEnabled && !isAdvancedCallingSettingEnabled()) {
            // Emergency call over IMS is not supported.
            logi("getSelectablePsNetworkType VoLte setting is not enabled.");
            return UNKNOWN;
        }

        int accessNetwork = regResult.getAccessNetwork();
        List<Integer> ratList = getImsNetworkTypeConfiguration();
        if (ratList.contains(accessNetwork)) {
            if (mIsEmergencyBarred) {
                logi("getSelectablePsNetworkType barred");
                return UNKNOWN;
            }
            if (accessNetwork == NGRAN) {
                return (regResult.getNwProvidedEmc() > 0 && regResult.isVopsSupported())
                        ? NGRAN : UNKNOWN;
            } else if (accessNetwork == EUTRAN) {
                return (regResult.isEmcBearerSupported()
                                && (regResult.isVopsSupported() || !inService))
                        ? EUTRAN : UNKNOWN;
            }
        }

        return UNKNOWN;
    }

    private boolean isEsFallbackAvailable() {
        EmergencyRegistrationResult regResult =
                mSelectionAttributes.getEmergencyRegistrationResult();
        if (regResult == null) return false;

        List<Integer> ratList = getImsNetworkTypeConfiguration();
        if (ratList.contains(EUTRAN)) {
            return (regResult.getNwProvidedEmf() > 0);
        }
        return false;
    }

    /**
     * Determines whether the SIM is a deactivated one.
     *
     * @return {@code true} if the SIM is a deactivated one.
     */
    private boolean isDeactivatedSim() {
        if (SubscriptionManager.isValidSubscriptionId(getSubId())) {
            TelephonyManager tm = mContext.getSystemService(TelephonyManager.class);
            tm = tm.createForSubscriptionId(getSubId());
            int state = tm.getDataActivationState();
            logi("isDeactivatedSim state=" + state);
            return (state == TelephonyManager.SIM_ACTIVATION_STATE_DEACTIVATED);
        }
        return false;
    }

    /**
     * Determines whether emergency call over Wi-Fi is allowed.
     *
     * @return {@code true} if emergency call over Wi-Fi allowed.
     */
    private boolean isEmcOverWifiSupported() {
        if (isSimReady() && mNonTtyOrTtySupported) {
            List<Integer> domains = getDomainPreference();
            boolean ret = domains.contains(DOMAIN_PS_NON_3GPP);
            logi("isEmcOverWifiSupported " + ret);
            return ret;
        } else {
            logi("isEmcOverWifiSupported invalid subId or lock state");
        }
        return false;
    }

    /**
     * Determines whether Wi-Fi is preferred when IMS registered over Wi-Fi.
     *
     * @return {@code true} if Wi-Fi is preferred when IMS registered over Wi-Fi.
     */
    private boolean isWifiPreferred() {
        if (SubscriptionManager.isValidSubscriptionId(getSubId())) {
            List<Integer> domains = getDomainPreference();
            int priority = domains.indexOf(DOMAIN_PS_NON_3GPP);
            logi("isWifiPreferred priority=" + priority);

            if ((priority == 0)
                    && isImsRegisteredWithVoiceCapability()
                    && isImsRegisteredOverWifi()) {
                logi("isWifiPreferred try emergency call over Wi-Fi");
                return true;
            }
        }

        return false;
    }

    private boolean isAdvancedCallingSettingEnabled() {
        try {
            if (SubscriptionManager.isValidSubscriptionId(getSubId())) {
                ImsManager imsMngr = mContext.getSystemService(ImsManager.class);
                ImsMmTelManager mmTelManager = imsMngr.getImsMmTelManager(getSubId());
                boolean result = mmTelManager.isAdvancedCallingSettingEnabled();
                logi("isAdvancedCallingSettingEnabled " + result);
                return result;
            }
        } catch (Exception e) {
            logi("isAdvancedCallingSettingEnabled e=" + e);
        }
        return true;
    }

    private boolean isWifiCallingActivated() {
        try {
            ImsManager imsMngr = mContext.getSystemService(ImsManager.class);
            ProvisioningManager pm = imsMngr.getProvisioningManager(getSubId());
            String eid = pm.getProvisioningStringValue(
                    ProvisioningManager.KEY_VOICE_OVER_WIFI_ENTITLEMENT_ID);
            boolean activated = (!TextUtils.isEmpty(eid)) && (!TextUtils.equals("0", eid));
            logi("isWifiCallingActivated " + activated);
            return activated;
        } catch (Exception e) {
            logi("isWifiCallingActivated e=" + e);
        }
        return false;
    }

    private boolean isWifiCallingSettingEnabled() {
        boolean result = false;
        try {
            if (SubscriptionManager.isValidSubscriptionId(getSubId())) {
                ImsManager imsMngr = mContext.getSystemService(ImsManager.class);
                ImsMmTelManager mmTelManager = imsMngr.getImsMmTelManager(getSubId());
                if (isInRoaming()) {
                    result = mmTelManager.isVoWiFiRoamingSettingEnabled();
                } else {
                    result = mmTelManager.isVoWiFiSettingEnabled();
                }
                logi("isWifiCallingSettingEnabled " + result);
                return result;
            }
        } catch (Exception e) {
            logi("isWifiCallingSettingEnabled e=" + e);
        }
        return result;
    }

    private @NonNull List<Integer> getImsNetworkTypeConfiguration() {
        int[] rats = mImsRatsConfig;
        if (isInRoaming()) rats = mImsRoamRatsConfig;

        List<Integer> ratList = new ArrayList<Integer>();
        for (int i = 0; i < rats.length; i++) {
            ratList.add(rats[i]);
        }
        return ratList;
    }

    private @NonNull List<Integer> getCsNetworkTypeConfiguration() {
        int[] rats = mCsRatsConfig;
        if (isInRoaming()) rats = mCsRoamRatsConfig;

        List<Integer> ratList = new ArrayList<Integer>();
        for (int i = 0; i < rats.length; i++) {
            ratList.add(rats[i]);
        }

        if (!mCdmaPreferredNumbers.isEmpty()) {
            String number = mSelectionAttributes.getAddress().getSchemeSpecificPart();
            if (mCdmaPreferredNumbers.contains(number)) {
                // The number will be dialed over CDMA.
                ratList.clear();
                ratList.add(new Integer(CDMA2000));
            } else {
                // The number will be dialed over UTRAN or GERAN.
                ratList.remove(new Integer(CDMA2000));
            }
        }

        return ratList;
    }

    private @NonNull List<Integer> getDomainPreference() {
        int[] domains = mDomainPreference;
        if (isInRoaming()) domains = mDomainPreferenceRoam;

        List<Integer> domainList = new ArrayList<Integer>();
        for (int i = 0; i < domains.length; i++) {
            domainList.add(domains[i]);
        }
        return domainList;
    }

    private boolean isInRoaming() {
        if (!SubscriptionManager.isValidSubscriptionId(getSubId())) return false;

        TelephonyManager tm = mContext.getSystemService(TelephonyManager.class);
        tm = tm.createForSubscriptionId(getSubId());
        String netIso = tm.getNetworkCountryIso();

        EmergencyRegistrationResult regResult = mLastRegResult;
        if (regResult != null) {
            if (regResult.getRegState() == REGISTRATION_STATE_HOME) return false;

            String iso = regResult.getCountryIso();
            if (!TextUtils.isEmpty(iso)) netIso = iso;
        }

        String simIso = tm.getSimCountryIso();
        logi("isInRoaming simIso=" + simIso + ", netIso=" + netIso);

        if (TextUtils.isEmpty(simIso)) return false;
        if (TextUtils.isEmpty(netIso)) return false;

        return !(TextUtils.equals(simIso, netIso));
    }

    /**
     * Determines whether IMS is registered over Wi-Fi.
     *
     * @return {@code true} if IMS is registered over Wi-Fi.
     */
    private boolean isImsRegisteredOverWifi() {
        boolean ret = false;
        if (SubscriptionManager.isValidSubscriptionId(getSubId())) {
            ret = mImsStateTracker.isImsRegisteredOverWlan();
        }

        logi("isImsRegisteredOverWifi " + ret);
        return ret;
    }

    /**
     * Determines whether IMS is registered over the mobile data of another subscription.
     *
     * @return {@code true} if IMS is registered over the mobile data of another subscription.
     */
    private boolean isImsRegisteredOverCrossSim() {
        boolean ret = false;
        if (SubscriptionManager.isValidSubscriptionId(getSubId())) {
            ret = mImsStateTracker.isImsRegisteredOverCrossSim();
        }

        logi("isImsRegisteredOverCrossSim " + ret);
        return ret;
    }

    /**
     * Determines whether IMS is registered with voice capability.
     *
     * @return {@code true} if IMS is registered with voice capability.
     */
    private boolean isImsRegisteredWithVoiceCapability() {
        boolean ret = mImsRegistered && mIsVoiceCapable;

        logi("isImsRegisteredWithVoiceCapability " + ret);
        return ret;
    }

    private void onWlanSelected() {
        logi("onWlanSelected");
        if (mLastTransportType == TRANSPORT_TYPE_WLAN) {
            logi("onWlanSelected ignore duplicated callback");
            return;
        }

        mDomainSelected = true;
        mNetworkScanTimerExpired = false;
        mIsWaitingForDataDisconnection = false;
        removeMessages(MSG_WAIT_DISCONNECTION_TIMEOUT);
        mLastTransportType = TRANSPORT_TYPE_WLAN;
        mVoWifiTrialCount++;
        mTransportSelectorCallback.onWlanSelected(mVoWifiOverEmergencyPdn);
        mWwanSelectorCallback = null;
        removeMessages(MSG_NETWORK_SCAN_TIMEOUT);
        removeMessages(MSG_MAX_CELLULAR_TIMEOUT);
    }

    private void onWwanSelected(Runnable runnable) {
        logi("onWwanSelected");
        if (mLastTransportType == TRANSPORT_TYPE_WWAN) {
            logi("onWwanSelected ignore duplicated callback");
            return;
        }

        mLastTransportType = TRANSPORT_TYPE_WWAN;
        mTransportSelectorCallback.onWwanSelected((callback) -> {
            mWwanSelectorCallback = callback;
            runnable.run();
        });
    }

    private void onWwanNetworkTypeSelected(@RadioAccessNetworkType int accessNetworkType) {
        logi("onWwanNetworkTypeSelected " + accessNetworkTypeToString(accessNetworkType));
        if (mWwanSelectorCallback == null) {
            logi("onWwanNetworkTypeSelected callback is null");
            return;
        }

        mDomainSelected = true;
        mNetworkScanTimerExpired = false;
        mLastNetworkType = accessNetworkType;
        int domain = NetworkRegistrationInfo.DOMAIN_CS;
        if (accessNetworkType == EUTRAN || accessNetworkType == NGRAN) {
            domain = NetworkRegistrationInfo.DOMAIN_PS;
        }
        mWwanSelectorCallback.onDomainSelected(domain,
                (domain == NetworkRegistrationInfo.DOMAIN_PS));
    }

    /**
     * Registers for changes to network connectivity.
     */
    private void registerForConnectivityChanges() {
        if (mIsMonitoringConnectivity) {
            return;
        }

        mWiFiNetworksAvailable.clear();
        ConnectivityManager cm = mContext.getSystemService(ConnectivityManager.class);
        if (cm != null) {
            logi("registerForConnectivityChanges");
            NetworkRequest.Builder builder = new NetworkRequest.Builder();
            builder.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
            cm.registerNetworkCallback(builder.build(), mNetworkCallback);
            mIsMonitoringConnectivity = true;
        }
    }

    /**
     * Unregisters for connectivity changes.
     */
    private void unregisterForConnectivityChanges() {
        if (!mIsMonitoringConnectivity) {
            return;
        }

        mWiFiNetworksAvailable.clear();
        ConnectivityManager cm = mContext.getSystemService(ConnectivityManager.class);
        if (cm != null) {
            logi("unregisterForConnectivityChanges");
            cm.unregisterNetworkCallback(mNetworkCallback);
            mIsMonitoringConnectivity = false;
        }
    }

    /** Starts the max cellular timer. */
    private void startMaxCellularTimer() {
        logd("startMaxCellularTimer tried=" + mVoWifiTrialCount
                + ", max=" + mMaxNumOfVoWifiTries);
        if (isEmcOverWifiSupported()
                && (mMaxCellularTimeout > 0)
                && (mVoWifiTrialCount < mMaxNumOfVoWifiTries)) {
            logi("startMaxCellularTimer start timer");
            sendEmptyMessageDelayed(MSG_MAX_CELLULAR_TIMEOUT, mMaxCellularTimeout);
            registerForConnectivityChanges();
        }
    }

    private boolean allowEmergencyCalls(EmergencyRegistrationResult regResult) {
        if (regResult == null) {
            loge("allowEmergencyCalls null regResult");
            return true;
        }

        String iso = regResult.getCountryIso();
        if (sSimReadyAllowList.contains(iso)) {
            if (isSimReady()) {
                SubscriptionManager sm = mContext.getSystemService(SubscriptionManager.class);
                SubscriptionInfo subInfo = sm.getActiveSubscriptionInfo(getSubId());
                if (subInfo != null
                        && subInfo.getProfileClass() == PROFILE_CLASS_PROVISIONING) {
                    // b/334773484, bootstrap profile
                    logi("allowEmergencyCalls bootstrap profile, iso=" + iso);
                    return false;
                }
            } else {
                logi("allowEmergencyCalls SIM state not ready, iso=" + iso);
                return false;
            }
        }

        return true;
    }

    private String getCountryIso(String iso) {
        if (TextUtils.isEmpty(iso)) {
            TelephonyManager tm = mContext.getSystemService(TelephonyManager.class);
            iso = tm.getNetworkCountryIso(getSlotId());
            if (TextUtils.isEmpty(iso)) {
                for (int i = 0; i < mModemCount; i++) {
                    iso = tm.getNetworkCountryIso(i);
                    if (!TextUtils.isEmpty(iso)) break;
                }
            }
        }
        return iso;
    }

    private boolean maybeRedialOnTheOtherSlotInNormalService(
            EmergencyRegistrationResult regResult) {
        if (regResult == null) return false;

        String iso = getCountryIso(regResult.getCountryIso());
        if (sPreferSlotWithNormalServiceList.contains(iso)
                && mCrossSimRedialingController.isThereOtherSlotInService()) {
            logi("maybeRedialOnTheOtherSlotInNormalService");
            terminateSelectionForCrossSimRedialing(false);
            return true;
        }
        return false;
    }

    private void terminateSelectionPermanentlyForSlot() {
        logi("terminateSelectionPermanentlyForSlot");
        mCrossSimRedialingController.notifyCallFailure(EMERGENCY_PERM_FAILURE);
        if (mCrossSimRedialingController.isThereOtherSlot()) {
            terminateSelection(DisconnectCause.EMERGENCY_PERM_FAILURE);
        } else {
            terminateSelection(DisconnectCause.ICC_ERROR);
        }
    }

    private void terminateSelectionForCrossSimRedialing(boolean permanent) {
        logi("terminateSelectionForCrossSimRedialing perm=" + permanent);
        terminateSelection(permanent ? DisconnectCause.EMERGENCY_PERM_FAILURE
                : DisconnectCause.EMERGENCY_TEMP_FAILURE);
    }

    private void terminateSelection(int cause) {
        removeMessages(MSG_NETWORK_SCAN_TIMEOUT);
        removeMessages(MSG_MAX_CELLULAR_TIMEOUT);
        mTransportSelectorCallback.onSelectionTerminated(cause);
    }

    private boolean maybeTerminateSelection(int cause) {
        switch (cause) {
            case NO_VALID_SIM:
                // The disconnect cause saved in DomainSelectionConnection shall be used.
                terminateSelection(DisconnectCause.NOT_VALID);
                return true;
            default:
                break;
        }

        ImsReasonInfo reasonInfo = mSelectionAttributes.getPsDisconnectCause();
        if (mRetryReasonCodes != null && reasonInfo != null) {
            if (!mRetryReasonCodes.contains(reasonInfo.getCode())) {
                // The disconnect cause saved in DomainSelectionConnection shall be used.
                terminateSelection(DisconnectCause.NOT_VALID);
                return true;
            }
        } else if (reasonInfo == null
                && sDisconnectCauseForTerminatation.contains(cause)
                && mTerminateAfterCsFailure) {
            // b/341055741
            logi("maybeTerminateSelection terminate after CS failure");
            terminateSelection(DisconnectCause.NOT_VALID);
            return true;
        }
        return false;
    }

    /** Starts the cross stack timer. */
    public void startCrossStackTimer() {
        boolean inService = false;
        boolean inRoaming = false;

        if (mModemCount == 1) return;

        EmergencyRegistrationResult regResult =
                mSelectionAttributes.getEmergencyRegistrationResult();
        if (regResult != null) {
            int regState = regResult.getRegState();

            if ((regResult.getDomain() > 0)
                    && (regState == REGISTRATION_STATE_HOME
                            || regState == REGISTRATION_STATE_ROAMING)) {
                inService = true;
            }
            inRoaming = (regState == REGISTRATION_STATE_ROAMING) || isInRoaming();
        }

        String number = mSelectionAttributes.getAddress().getSchemeSpecificPart();
        mCrossSimRedialingController.startTimer(mContext, this, mSelectionAttributes.getCallId(),
                number, inService, inRoaming, mModemCount);
    }

    /** Notifies that the cross stack redilaing timer has been expired. */
    public void notifyCrossStackTimerExpired() {
        logi("notifyCrossStackTimerExpired");

        mCrossStackTimerExpired = true;
        if (mDomainSelected) {
            // When reselecting domain, terminateSelection will be called.
            return;
        }
        mIsWaitingForDataDisconnection = false;
        removeMessages(MSG_WAIT_DISCONNECTION_TIMEOUT);
        terminateSelectionForCrossSimRedialing(false);
    }

    /** Notifies the ePDN connection state changes. */
    public void notifyDataConnectionStateChange(int slotId, int state) {
        if (slotId == getSlotId() && mIsWaitingForDataDisconnection) {
            if (state == DATA_DISCONNECTED || state == DATA_UNKNOWN) {
                requestScanDelayed();
            } else if (state == DATA_DISCONNECTING) {
                logi("notifyDataConnectionStateChange deactivation starting, restart timer");
                removeMessages(MSG_WAIT_DISCONNECTION_TIMEOUT);
                sendEmptyMessageDelayed(MSG_WAIT_DISCONNECTION_TIMEOUT,
                        DEFAULT_DATA_DISCONNECTION_TIMEOUT_MS);
            }
        }
    }

    private void maybeModifyScanType(int selectedNetworkType) {
        if ((mPreferredNetworkScanType
                != CarrierConfigManager.ImsEmergency.SCAN_TYPE_FULL_SERVICE)
                && mScanLimitedOnlyAfterVolteFailure
                && (selectedNetworkType == EUTRAN)) {
            mScanType = DomainSelectionService.SCAN_TYPE_LIMITED_SERVICE;
        }
    }

    private static String arrayToString(int[] intArray, IntFunction<String> func) {
        int length = intArray.length;
        StringBuilder sb = new StringBuilder("{");
        if (length > 0) {
            int i = 0;
            sb.append(func.apply(intArray[i++]));
            while (i < length) {
                sb.append(", ").append(func.apply(intArray[i++]));
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private static String arrayToString(String[] stringArray) {
        StringBuilder sb;
        int length = stringArray.length;
        sb = new StringBuilder("{");
        if (length > 0) {
            int i = 0;
            sb.append(stringArray[i++]);
            while (i < length) {
                sb.append(", ").append(stringArray[i++]);
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private static String domainPreferenceToString(
            @CarrierConfigManager.ImsEmergency.EmergencyDomain int domain) {
        switch (domain) {
            case DOMAIN_CS: return "CS";
            case DOMAIN_PS_3GPP: return "PS_3GPP";
            case DOMAIN_PS_NON_3GPP: return "PS_NON_3GPP";
            default: return "UNKNOWN";
        }
    }

    private static String carrierConfigNetworkScanTypeToString(
            @CarrierConfigManager.ImsEmergency.EmergencyScanType int scanType) {
        switch (scanType) {
            case CarrierConfigManager.ImsEmergency.SCAN_TYPE_NO_PREFERENCE: return "NO_PREF";
            case CarrierConfigManager.ImsEmergency.SCAN_TYPE_FULL_SERVICE: return "FULL";
            case SCAN_TYPE_FULL_SERVICE_FOLLOWED_BY_LIMITED_SERVICE: return "FULL_N_LIMITED";
            default: return "UNKNOWN";
        }
    }

    private static String accessNetworkTypeToString(
            @RadioAccessNetworkType int accessNetworkType) {
        switch (accessNetworkType) {
            case AccessNetworkType.UNKNOWN: return "UNKNOWN";
            case AccessNetworkType.GERAN: return "GERAN";
            case AccessNetworkType.UTRAN: return "UTRAN";
            case AccessNetworkType.EUTRAN: return "EUTRAN";
            case AccessNetworkType.CDMA2000: return "CDMA2000";
            case AccessNetworkType.IWLAN: return "IWLAN";
            case AccessNetworkType.NGRAN: return "NGRAN";
            default: return Integer.toString(accessNetworkType);
        }
    }

    /**
     * Destroys the instance.
     */
    @VisibleForTesting
    public void destroy() {
        if (DBG) logd("destroy");

        mEpdnHelper.setEmergencyCallDomainSelector(null);
        mCrossSimRedialingController.stopTimer();
        releaseWakeLock();

        mDestroyed = true;
        mImsStateTracker.removeBarringInfoListener(this);
        mImsStateTracker.removeImsStateListener(this);
        unregisterForConnectivityChanges();

        super.destroy();
    }

    private void acquireWakeLock() {
        if (mPartialWakeLock != null) {
            synchronized (mPartialWakeLock) {
                logi("acquireWakeLock");
                mPartialWakeLock.acquire();
            }
        }
    }

    private void releaseWakeLock() {
        if (mPartialWakeLock != null) {
            synchronized (mPartialWakeLock) {
                if (mPartialWakeLock.isHeld()) {
                    logi("releaseWakeLock");
                    mPartialWakeLock.release();
                }
            }
        }
    }

    private boolean isInEmergencyCallbackModeOnWlan() {
        return mEpdnHelper.isInEmergencyCallbackMode(getSlotId())
                && mEpdnHelper.getTransportType(getSlotId()) == TRANSPORT_TYPE_WLAN
                && mEpdnHelper.getDataConnectionState(getSlotId()) == DATA_CONNECTED;
    }

    private boolean isInEmergencyCallbackModeOnPsWwan() {
        return mEpdnHelper.isInEmergencyCallbackMode(getSlotId())
                && mEpdnHelper.getTransportType(getSlotId()) == TRANSPORT_TYPE_WWAN
                && mEpdnHelper.getDataConnectionState(getSlotId()) == DATA_CONNECTED;
    }

    /**
     * Indicates whether the call is non-TTY or if TTY is supported.
     */
    private boolean isNonTtyOrTtySupported(boolean ttySupported) {
        if (ttySupported) {
            return true;
        }

        TelecomManager tm = mContext.getSystemService(TelecomManager.class);
        if (tm == null) {
            logi("isNonTtyOrTtySupported telecom not available");
            return true;
        }

        boolean ret = (tm.getCurrentTtyMode() == TelecomManager.TTY_MODE_OFF);
        logi("isNonTtyOrTtySupported ret=" + ret);

        return ret;
    }

    private void checkAndSetTerminateAfterCsFailure(EmergencyRegistrationResult result) {
        if (result == null) return;
        String mcc = result.getMcc();
        int accessNetwork = result.getAccessNetwork();
        if (!TextUtils.isEmpty(mcc) && mcc.startsWith("00") // test network
                && (accessNetwork == UTRAN || accessNetwork == GERAN)) {
            // b/341055741
            mTerminateAfterCsFailure = true;
        }
    }

    @VisibleForTesting
    public boolean isWiFiAvailable() {
        return mWiFiAvailable;
    }

    @VisibleForTesting
    public List<Network> getWiFiNetworksAvailable() {
        return mWiFiNetworksAvailable;
    }

    @Override
    protected void logi(String msg) {
        super.logi(msg);
        sLocalLog.log(msg);
    }

    @Override
    protected void loge(String msg) {
        super.loge(msg);
        sLocalLog.log(msg);
    }
}
