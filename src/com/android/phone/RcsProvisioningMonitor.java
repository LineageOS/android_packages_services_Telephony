/*
 * Copyright 2020 The Android Open Source Project
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

import android.app.role.OnRoleHoldersChangedListener;
import android.app.role.RoleManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyRegistryManager;
import android.telephony.ims.ProvisioningManager;
import android.telephony.ims.RcsConfig;
import android.telephony.ims.aidl.IImsConfig;
import android.telephony.ims.feature.ImsFeature;
import android.text.TextUtils;

import com.android.ims.ImsManager;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.CollectionUtils;
import com.android.telephony.Rlog;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Class to monitor RCS Provisioning Status
 */
public class RcsProvisioningMonitor {
    private static final String TAG = "RcsProvisioningMonitor";
    private static final boolean DBG = Build.IS_ENG;

    private static final int EVENT_SUB_CHANGED = 1;
    private static final int EVENT_DMA_CHANGED = 2;
    private static final int EVENT_CC_CHANGED  = 3;
    private static final int EVENT_CONFIG_RECEIVED = 4;
    private static final int EVENT_RECONFIG_REQUEST = 5;
    private static final int EVENT_DEVICE_CONFIG_OVERRIDE = 6;
    private static final int EVENT_CARRIER_CONFIG_OVERRIDE = 7;

    private final PhoneGlobals mPhone;
    private final Handler mHandler;
    //cache the rcs config per sub id
    private final Map<Integer, byte[]> mConfigs = Collections.synchronizedMap(new HashMap<>());
    //cache the single registration config per sub id
    private final ConcurrentHashMap<Integer, Integer> mSingleRegistrations =
            new ConcurrentHashMap<>();
    private Boolean mDeviceSingleRegistrationEnabledOverride;
    private final HashMap<Integer, Boolean> mCarrierSingleRegistrationEnabledOverride =
            new HashMap<>();
    private String mDmaPackageName;

    private final CarrierConfigManager mCarrierConfigManager;
    private final DmaChangedListener mDmaChangedListener;
    private final SubscriptionManager mSubscriptionManager;
    private final TelephonyRegistryManager mTelephonyRegistryManager;

    private static RcsProvisioningMonitor sInstance;

    private final SubscriptionManager.OnSubscriptionsChangedListener mSubChangedListener =
            new SubscriptionManager.OnSubscriptionsChangedListener() {
        @Override
        public void onSubscriptionsChanged() {
            if (!mHandler.hasMessages(EVENT_SUB_CHANGED)) {
                mHandler.sendEmptyMessage(EVENT_SUB_CHANGED);
            }
        }
    };

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED.equals(
                    intent.getAction())) {
                int subId = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
                        SubscriptionManager.INVALID_SUBSCRIPTION_ID);
                logv("Carrier-config changed for sub : " + subId);
                if (SubscriptionManager.isValidSubscriptionId(subId)
                        && !mHandler.hasMessages(EVENT_CC_CHANGED)) {
                    mHandler.sendEmptyMessage(EVENT_CC_CHANGED);
                }
            }
        }
    };

    private final class DmaChangedListener implements OnRoleHoldersChangedListener {
        private RoleManager mRoleManager;

        @Override
        public void onRoleHoldersChanged(String role, UserHandle user) {
            if (RoleManager.ROLE_SMS.equals(role)) {
                logv("default messaging application changed.");
                String packageName = getDmaPackageName();
                mHandler.sendEmptyMessage(EVENT_DMA_CHANGED);
            }
        }

        public void register() {
            mRoleManager = mPhone.getSystemService(RoleManager.class);
            if (mRoleManager != null) {
                try {
                    mRoleManager.addOnRoleHoldersChangedListenerAsUser(
                            mPhone.getMainExecutor(), this, UserHandle.SYSTEM);
                } catch (RuntimeException e) {
                    loge("Could not register dma change listener due to " + e);
                }
            }
        }

        public void unregister() {
            if (mRoleManager != null) {
                try {
                    mRoleManager.removeOnRoleHoldersChangedListenerAsUser(this, UserHandle.SYSTEM);
                } catch (RuntimeException e) {
                    loge("Could not unregister dma change listener due to " + e);
                }
            }
        }
    }

    private final class MyHandler extends Handler {
        MyHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case EVENT_SUB_CHANGED:
                    onSubChanged();
                    break;
                case EVENT_DMA_CHANGED:
                    onDefaultMessagingApplicationChanged();
                    break;
                case EVENT_CC_CHANGED:
                    onCarrierConfigChange();
                    break;
                case EVENT_CONFIG_RECEIVED:
                    onConfigReceived(msg.arg1, (byte[]) msg.obj, msg.arg2 == 1);
                    break;
                case EVENT_RECONFIG_REQUEST:
                    onReconfigRequest(msg.arg1);
                    break;
                case EVENT_DEVICE_CONFIG_OVERRIDE:
                    Boolean deviceEnabled = (Boolean) msg.obj;
                    if (!booleanEquals(deviceEnabled, mDeviceSingleRegistrationEnabledOverride)) {
                        mDeviceSingleRegistrationEnabledOverride = deviceEnabled;
                        onCarrierConfigChange();
                    }
                    break;
                case EVENT_CARRIER_CONFIG_OVERRIDE:
                    Boolean carrierEnabledOverride = (Boolean) msg.obj;
                    Boolean carrierEnabled = mCarrierSingleRegistrationEnabledOverride.put(
                            msg.arg1, carrierEnabledOverride);
                    if (!booleanEquals(carrierEnabledOverride, carrierEnabled)) {
                        onCarrierConfigChange();
                    }
                    break;
                default:
                    loge("Unhandled event " + msg.what);
            }
        }
    }

    @VisibleForTesting
    public RcsProvisioningMonitor(PhoneGlobals app, Looper looper) {
        mPhone = app;
        mHandler = new MyHandler(looper);
        mCarrierConfigManager = mPhone.getSystemService(CarrierConfigManager.class);
        mSubscriptionManager = mPhone.getSystemService(SubscriptionManager.class);
        mTelephonyRegistryManager = mPhone.getSystemService(TelephonyRegistryManager.class);
        mDmaPackageName = getDmaPackageName();
        logv("DMA is " + mDmaPackageName);
        IntentFilter filter = new IntentFilter();
        filter.addAction(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED);
        mPhone.registerReceiver(mReceiver, filter);
        mTelephonyRegistryManager.addOnSubscriptionsChangedListener(
                mSubChangedListener, mSubChangedListener.getHandlerExecutor());
        mDmaChangedListener = new DmaChangedListener();
        mDmaChangedListener.register();
        //initialize configs for all active sub
        onSubChanged();
    }

    /**
     * create an instance
     */
    public static RcsProvisioningMonitor make(PhoneGlobals app) {
        if (sInstance == null) {
            logd("RcsProvisioningMonitor created.");
            HandlerThread handlerThread = new HandlerThread(TAG);
            handlerThread.start();
            sInstance = new RcsProvisioningMonitor(app, handlerThread.getLooper());
        }
        return sInstance;
    }

    /**
     * get the instance
     */
    public static RcsProvisioningMonitor getInstance() {
        return sInstance;
    }

    /**
     * destroy the instance
     */
    @VisibleForTesting
    public void destroy() {
        logd("destroy it.");
        mDmaChangedListener.unregister();
        mTelephonyRegistryManager.removeOnSubscriptionsChangedListener(mSubChangedListener);
        mPhone.unregisterReceiver(mReceiver);
        mHandler.getLooper().quit();
    }

    /**
     * get the handler
     */
    @VisibleForTesting
    public Handler getHandler() {
        return mHandler;
    }

    /**
     * Gets the config for a subscription
     */
    @VisibleForTesting
    public byte[] getConfig(int subId) {
        return mConfigs.get(subId);
    }

    /**
     * Returns whether Rcs Volte single registration is enabled for the sub.
     */
    public boolean isRcsVolteSingleRegistrationEnabled(int subId) {
        if (mSingleRegistrations.containsKey(subId)) {
            return mSingleRegistrations.get(subId) == ProvisioningManager.STATUS_CAPABLE;
        }
        return false;
    }

    /**
     * Called when the new rcs config is received
     */
    public void updateConfig(int subId, byte[] config, boolean isCompressed) {
        mHandler.sendMessage(mHandler.obtainMessage(
                EVENT_CONFIG_RECEIVED, subId, isCompressed ? 1 : 0, config));
    }

    /**
     * Called when the application needs rcs re-config
     */
    public void requestReconfig(int subId) {
        mHandler.sendMessage(mHandler.obtainMessage(EVENT_RECONFIG_REQUEST, subId, 0));
    }

    /**
     * override the device config whether single registration is enabled
     */
    public void overrideDeviceSingleRegistrationEnabled(Boolean enabled) {
        mHandler.sendMessage(mHandler.obtainMessage(EVENT_DEVICE_CONFIG_OVERRIDE, enabled));
    }

    /**
     * Overrides the carrier config whether single registration is enabled
     */
    public boolean overrideCarrierSingleRegistrationEnabled(int subId, Boolean enabled) {
        if (!mSingleRegistrations.containsKey(subId)) {
            return false;
        }
        mHandler.sendMessage(mHandler.obtainMessage(
                EVENT_CARRIER_CONFIG_OVERRIDE, subId, 0, enabled));
        return true;
    }

    /**
     * Returns the device config whether single registration is enabled
     */
    public boolean getDeviceSingleRegistrationEnabled() {
        for (int val : mSingleRegistrations.values()) {
            return (val & ProvisioningManager.STATUS_DEVICE_NOT_CAPABLE) == 0;
        }
        return false;
    }

    /**
     * Returns the carrier config whether single registration is enabled
     */
    public boolean getCarrierSingleRegistrationEnabled(int subId) {
        if (mSingleRegistrations.containsKey(subId)) {
            return (mSingleRegistrations.get(subId)
                    & ProvisioningManager.STATUS_CARRIER_NOT_CAPABLE) == 0;
        }
        return false;
    }

    private void onDefaultMessagingApplicationChanged() {
        final String packageName = getDmaPackageName();
        if (!TextUtils.equals(mDmaPackageName, packageName)) {
            //clear old callbacks
            ImsManager.getInstance(mPhone, mPhone.getPhone().getPhoneId())
                    .clearRcsProvisioningCallbacks();
            mDmaPackageName = packageName;
            logv("new default messaging application " + mDmaPackageName);
            mConfigs.forEach((k, v) -> {
                if (isAcsUsed(k)) {
                    logv("acs used, trigger to re-configure.");
                    notifyRcsAutoConfigurationRemoved(k);
                    triggerRcsReconfiguration(k);
                } else {
                    logv("acs not used, notify.");
                    notifyRcsAutoConfigurationReceived(k, v, false);
                }
            });
        }
    }

    private void notifyRcsAutoConfigurationReceived(int subId, byte[] config,
            boolean isCompressed) {
        IImsConfig imsConfig = getIImsConfig(subId, ImsFeature.FEATURE_RCS);
        if (imsConfig != null) {
            try {
                imsConfig.notifyRcsAutoConfigurationReceived(config, isCompressed);
            } catch (RemoteException e) {
                loge("fail to notify rcs configuration received!");
            }
        } else {
            logd("getIImsConfig returns null.");
        }
    }

    private void notifyRcsAutoConfigurationRemoved(int subId) {
        IImsConfig imsConfig = getIImsConfig(subId, ImsFeature.FEATURE_RCS);
        if (imsConfig != null) {
            try {
                imsConfig.notifyRcsAutoConfigurationRemoved();
            } catch (RemoteException e) {
                loge("fail to notify rcs configuration removed!");
            }
        } else {
            logd("getIImsConfig returns null.");
        }
    }

    private void triggerRcsReconfiguration(int subId) {
        IImsConfig imsConfig = getIImsConfig(subId, ImsFeature.FEATURE_RCS);
        if (imsConfig != null) {
            try {
                imsConfig.triggerRcsReconfiguration();
            } catch (RemoteException e) {
                loge("fail to trigger rcs reconfiguration!");
            }
        } else {
            logd("getIImsConfig returns null.");
        }
    }

    private boolean isAcsUsed(int subId) {
        PersistableBundle b = mCarrierConfigManager.getConfigForSubId(subId);
        if (b == null) {
            return false;
        }
        return b.getBoolean(CarrierConfigManager.KEY_USE_ACS_FOR_RCS_BOOL);
    }

    private boolean isSingleRegistrationRequiredByCarrier(int subId) {
        Boolean enabledByOverride = mCarrierSingleRegistrationEnabledOverride.get(subId);
        if (enabledByOverride != null) {
            return enabledByOverride;
        }

        PersistableBundle b = mCarrierConfigManager.getConfigForSubId(subId);
        if (b == null) {
            return false;
        }
        return b.getBoolean(CarrierConfigManager.Ims.KEY_IMS_SINGLE_REGISTRATION_REQUIRED_BOOL);
    }

    private int getSingleRegistrationCapableValue(int subId) {
        boolean isSingleRegistrationEnabledOnDevice =
                mDeviceSingleRegistrationEnabledOverride != null
                ? mDeviceSingleRegistrationEnabledOverride
                : mPhone.getResources().getBoolean(R.bool.config_rcsVolteSingleRegistrationEnabled);

        int value = (isSingleRegistrationEnabledOnDevice ? 0
                : ProvisioningManager.STATUS_DEVICE_NOT_CAPABLE) | (
                isSingleRegistrationRequiredByCarrier(subId) ? 0
                : ProvisioningManager.STATUS_CARRIER_NOT_CAPABLE);
        logv("SingleRegistrationCapableValue : " + value);
        return value;
    }

    private void onCarrierConfigChange() {
        logv("onCarrierConfigChange");
        mConfigs.forEach((subId, config) -> {
            int value = getSingleRegistrationCapableValue(subId);
            if (value != mSingleRegistrations.get(subId)) {
                mSingleRegistrations.put(subId, value);
                notifyDmaForSub(subId);
            }
        });
    }

    private void onSubChanged() {
        final int[] activeSubs = mSubscriptionManager.getActiveSubscriptionIdList();
        final HashSet<Integer> subsToBeDeactivated = new HashSet<>(mConfigs.keySet());

        for (int i : activeSubs) {
            subsToBeDeactivated.remove(i);
            if (!mConfigs.containsKey(i)) {
                byte[] data = RcsConfig.loadRcsConfigForSub(mPhone, i, false);
                logv("new config is created for sub : " + i);
                mConfigs.put(i, data);
                notifyRcsAutoConfigurationReceived(i, data, false);
                mSingleRegistrations.put(i, getSingleRegistrationCapableValue(i));
                notifyDmaForSub(i);
            }
        }

        subsToBeDeactivated.forEach(i -> {
            mConfigs.remove(i);
            notifyRcsAutoConfigurationRemoved(i);
        });
    }

    private void onConfigReceived(int subId, byte[] config, boolean isCompressed) {
        logv("onConfigReceived, subId:" + subId + ", config:"
                + config + ", isCompressed:" + isCompressed);
        mConfigs.put(subId, isCompressed ? RcsConfig.decompressGzip(config) : config);
        RcsConfig.updateConfigForSub(mPhone, subId, config, isCompressed);
        notifyRcsAutoConfigurationReceived(subId, config, isCompressed);
    }

    private void onReconfigRequest(int subId) {
        logv("onReconfigRequest, subId:" + subId);
        mConfigs.remove(subId);
        RcsConfig.updateConfigForSub(mPhone, subId, null, true);
        notifyRcsAutoConfigurationRemoved(subId);
        triggerRcsReconfiguration(subId);
    }

    private void notifyDmaForSub(int subId) {
        final Intent intent = new Intent(
                ProvisioningManager.ACTION_RCS_SINGLE_REGISTRATION_CAPABILITY_UPDATE);
        intent.setPackage(mDmaPackageName);
        intent.putExtra(ProvisioningManager.EXTRA_SUBSCRIPTION_ID, subId);
        intent.putExtra(ProvisioningManager.EXTRA_STATUS, mSingleRegistrations.get(subId));
        logv("notify " + intent);
        mPhone.sendBroadcast(intent);
    }

    private IImsConfig getIImsConfig(int subId, int feature) {
        return mPhone.getImsResolver().getImsConfig(
                SubscriptionManager.getSlotIndex(subId), feature);
    }

    private String getDmaPackageName() {
        try {
            return CollectionUtils.firstOrNull(mPhone.getSystemService(RoleManager.class)
                    .getRoleHolders(RoleManager.ROLE_SMS));
        } catch (RuntimeException e) {
            loge("Could not get dma name due to " + e);
            return null;
        }
    }

    private static boolean booleanEquals(Boolean val1, Boolean val2) {
        return (val1 == null && val2 == null)
                || (Boolean.TRUE.equals(val1) && Boolean.TRUE.equals(val2))
                || (Boolean.FALSE.equals(val1) && Boolean.FALSE.equals(val2));
    }

    private static void logv(String msg) {
        if (DBG) {
            Rlog.d(TAG, msg);
        }
    }

    private static void logd(String msg) {
        Rlog.d(TAG, msg);
    }

    private static void loge(String msg) {
        Rlog.e(TAG, msg);
    }
}
