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
import android.content.pm.PackageManager;
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
import android.telephony.ims.aidl.IRcsConfigCallback;
import android.telephony.ims.feature.ImsFeature;
import android.text.TextUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.CollectionUtils;
import com.android.telephony.Rlog;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

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
    // Cache the RCS provsioning info and related sub id
    private final ConcurrentHashMap<Integer, RcsProvisioningInfo> mRcsProvisioningInfos =
            new ConcurrentHashMap<>();
    private Boolean mDeviceSingleRegistrationEnabledOverride;
    private final HashMap<Integer, Boolean> mCarrierSingleRegistrationEnabledOverride =
            new HashMap<>();
    private String mDmaPackageName;

    private final CarrierConfigManager mCarrierConfigManager;
    private final DmaChangedListener mDmaChangedListener;
    private final SubscriptionManager mSubscriptionManager;
    private final TelephonyRegistryManager mTelephonyRegistryManager;
    private final RoleManagerAdapter mRoleManager;

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
        @Override
        public void onRoleHoldersChanged(String role, UserHandle user) {
            if (RoleManager.ROLE_SMS.equals(role)) {
                logv("default messaging application changed.");
                String packageName = getDmaPackageName();
                mHandler.sendEmptyMessage(EVENT_DMA_CHANGED);
            }
        }

        public void register() {
            try {
                mRoleManager.addOnRoleHoldersChangedListenerAsUser(
                        mPhone.getMainExecutor(), this, UserHandle.SYSTEM);
            } catch (RuntimeException e) {
                loge("Could not register dma change listener due to " + e);
            }
        }

        public void unregister() {
            try {
                mRoleManager.removeOnRoleHoldersChangedListenerAsUser(this, UserHandle.SYSTEM);
            } catch (RuntimeException e) {
                loge("Could not unregister dma change listener due to " + e);
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

    private final class RcsProvisioningInfo {
        private int mSubId;
        private volatile int mSingleRegistrationCapability;
        private volatile byte[] mConfig;
        private HashSet<IRcsConfigCallback> mRcsConfigCallbacks;

        RcsProvisioningInfo(int subId, int singleRegistrationCapability, byte[] config) {
            mSubId = subId;
            mSingleRegistrationCapability = singleRegistrationCapability;
            mConfig = config;
            mRcsConfigCallbacks = new HashSet<>();
        }

        void setSingleRegistrationCapability(int singleRegistrationCapability) {
            mSingleRegistrationCapability = singleRegistrationCapability;
        }

        int getSingleRegistrationCapability() {
            return mSingleRegistrationCapability;
        }

        void setConfig(byte[] config) {
            mConfig = config;
        }

        byte[] getConfig() {
            return mConfig;
        }

        boolean addRcsConfigCallback(IRcsConfigCallback cb) {
            IImsConfig imsConfig = getIImsConfig(mSubId, ImsFeature.FEATURE_RCS);
            if (imsConfig == null) {
                logd("fail to addRcsConfigCallback as imsConfig is null");
                return false;
            }

            synchronized (mRcsConfigCallbacks) {
                try {
                    imsConfig.addRcsConfigCallback(cb);
                } catch (RemoteException e) {
                    loge("fail to addRcsConfigCallback due to " + e);
                    return false;
                }
                mRcsConfigCallbacks.add(cb);
            }
            return true;
        }

        boolean removeRcsConfigCallback(IRcsConfigCallback cb) {
            boolean result = true;
            IImsConfig imsConfig = getIImsConfig(mSubId, ImsFeature.FEATURE_RCS);

            synchronized (mRcsConfigCallbacks) {
                if (imsConfig != null) {
                    try {
                        imsConfig.removeRcsConfigCallback(cb);
                    } catch (RemoteException e) {
                        loge("fail to removeRcsConfigCallback due to " + e);
                    }
                } else {
                    // Return false but continue to remove the callback
                    result = false;
                }

                try {
                    cb.onRemoved();
                } catch (RemoteException e) {
                    logd("Failed to notify onRemoved due to dead binder of " + cb);
                }
                mRcsConfigCallbacks.remove(cb);
            }
            return result;
        }

        void clear() {
            setConfig(null);
            synchronized (mRcsConfigCallbacks) {
                IImsConfig imsConfig = getIImsConfig(mSubId, ImsFeature.FEATURE_RCS);
                Iterator<IRcsConfigCallback> it = mRcsConfigCallbacks.iterator();
                while (it.hasNext()) {
                    IRcsConfigCallback cb = it.next();
                    if (imsConfig != null) {
                        try {
                            imsConfig.removeRcsConfigCallback(cb);
                        } catch (RemoteException e) {
                            loge("fail to removeRcsConfigCallback due to " + e);
                        }
                    }
                    try {
                        cb.onRemoved();
                    } catch (RemoteException e) {
                        logd("Failed to notify onRemoved due to dead binder of " + cb);
                    }
                    it.remove();
                }
            }
        }
    }

    @VisibleForTesting
    public RcsProvisioningMonitor(PhoneGlobals app, Looper looper, RoleManagerAdapter roleManager) {
        mPhone = app;
        mHandler = new MyHandler(looper);
        mCarrierConfigManager = mPhone.getSystemService(CarrierConfigManager.class);
        mSubscriptionManager = mPhone.getSystemService(SubscriptionManager.class);
        mTelephonyRegistryManager = mPhone.getSystemService(TelephonyRegistryManager.class);
        mRoleManager = roleManager;
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
            sInstance = new RcsProvisioningMonitor(app, handlerThread.getLooper(),
                    new RoleManagerAdapterImpl(app));
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
        if (mRcsProvisioningInfos.containsKey(subId)) {
            return mRcsProvisioningInfos.get(subId).getConfig();
        }
        return null;
    }

    /**
     * Returns whether Rcs Volte single registration is enabled for the sub.
     */
    public boolean isRcsVolteSingleRegistrationEnabled(int subId) {
        if (mRcsProvisioningInfos.containsKey(subId)) {
            return mRcsProvisioningInfos.get(subId).getSingleRegistrationCapability()
                    == ProvisioningManager.STATUS_CAPABLE;
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
     * Called when the application registers rcs provisioning changed callback
     */
    public boolean registerRcsProvisioningChangedCallback(int subId, IRcsConfigCallback cb) {
        RcsProvisioningInfo info = mRcsProvisioningInfos.get(subId);
        // should not happen in normal case
        if (info == null) {
            logd("fail to register rcs provisioning changed due to subscription unavailable");
            return false;
        }

        return info.addRcsConfigCallback(cb);
    }

    /**
     * Called when the application unregisters rcs provisioning changed callback
     */
    public boolean unregisterRcsProvisioningChangedCallback(int subId, IRcsConfigCallback cb) {
        RcsProvisioningInfo info = mRcsProvisioningInfos.get(subId);
        // should not happen in normal case
        if (info == null) {
            logd("fail to unregister rcs provisioning changed due to subscription unavailable");
            return false;
        }

        return info.removeRcsConfigCallback(cb);
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
        if (!mRcsProvisioningInfos.containsKey(subId)) {
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
        for (RcsProvisioningInfo info : mRcsProvisioningInfos.values()) {
            return (info.getSingleRegistrationCapability()
                    & ProvisioningManager.STATUS_DEVICE_NOT_CAPABLE) == 0;
        }
        return false;
    }

    /**
     * Returns the carrier config whether single registration is enabled
     */
    public boolean getCarrierSingleRegistrationEnabled(int subId) {
        if (mRcsProvisioningInfos.containsKey(subId)) {
            return (mRcsProvisioningInfos.get(subId).getSingleRegistrationCapability()
                    & ProvisioningManager.STATUS_CARRIER_NOT_CAPABLE) == 0;
        }
        return false;
    }

    private void onDefaultMessagingApplicationChanged() {
        final String packageName = getDmaPackageName();
        if (!TextUtils.equals(mDmaPackageName, packageName)) {
            mDmaPackageName = packageName;
            logv("new default messaging application " + mDmaPackageName);

            mRcsProvisioningInfos.forEach((k, v) -> {
                byte[] cachedConfig = v.getConfig();
                //clear old callbacks
                v.clear();
                if (isAcsUsed(k)) {
                    logv("acs used, trigger to re-configure.");
                    notifyRcsAutoConfigurationRemoved(k);
                    triggerRcsReconfiguration(k);
                } else {
                    v.setConfig(cachedConfig);
                    logv("acs not used, notify.");
                    notifyRcsAutoConfigurationReceived(k, v.getConfig(), false);
                }
            });
        }
    }

    private void notifyRcsAutoConfigurationReceived(int subId, byte[] config,
            boolean isCompressed) {
        if (config == null) {
            logd("Rcs config is null for sub : " + subId);
            return;
        }

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
        RcsConfig.updateConfigForSub(mPhone, subId, null, true);
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
                : mPhone.getPackageManager().hasSystemFeature(
                        PackageManager.FEATURE_TELEPHONY_IMS_SINGLE_REGISTRATION);

        int value = (isSingleRegistrationEnabledOnDevice ? 0
                : ProvisioningManager.STATUS_DEVICE_NOT_CAPABLE) | (
                isSingleRegistrationRequiredByCarrier(subId) ? 0
                : ProvisioningManager.STATUS_CARRIER_NOT_CAPABLE);
        logv("SingleRegistrationCapableValue : " + value);
        return value;
    }

    private void onCarrierConfigChange() {
        logv("onCarrierConfigChange");
        mRcsProvisioningInfos.forEach((subId, info) -> {
            int value = getSingleRegistrationCapableValue(subId);
            if (value != info.getSingleRegistrationCapability()) {
                info.setSingleRegistrationCapability(value);
                notifyDmaForSub(subId, value);
            }
        });
    }

    private void onSubChanged() {
        final int[] activeSubs = mSubscriptionManager.getActiveSubscriptionIdList();
        final HashSet<Integer> subsToBeDeactivated = new HashSet<>(mRcsProvisioningInfos.keySet());

        for (int i : activeSubs) {
            subsToBeDeactivated.remove(i);
            if (!mRcsProvisioningInfos.containsKey(i)) {
                byte[] data = RcsConfig.loadRcsConfigForSub(mPhone, i, false);
                int capability = getSingleRegistrationCapableValue(i);
                logv("new info is created for sub : " + i + ", single registration capability :"
                        + capability + ", rcs config : " + data);
                mRcsProvisioningInfos.put(i, new RcsProvisioningInfo(i, capability, data));
                notifyRcsAutoConfigurationReceived(i, data, false);
                notifyDmaForSub(i, capability);
            }
        }

        subsToBeDeactivated.forEach(i -> {
            RcsProvisioningInfo info = mRcsProvisioningInfos.remove(i);
            notifyRcsAutoConfigurationRemoved(i);
            if (info != null) {
                info.clear();
            }
        });
    }

    private void onConfigReceived(int subId, byte[] config, boolean isCompressed) {
        logv("onConfigReceived, subId:" + subId + ", config:"
                + config + ", isCompressed:" + isCompressed);
        RcsProvisioningInfo info = mRcsProvisioningInfos.get(subId);
        if (info != null) {
            info.setConfig(isCompressed ? RcsConfig.decompressGzip(config) : config);
        }
        RcsConfig.updateConfigForSub(mPhone, subId, config, isCompressed);
        notifyRcsAutoConfigurationReceived(subId, config, isCompressed);
    }

    private void onReconfigRequest(int subId) {
        logv("onReconfigRequest, subId:" + subId);
        RcsProvisioningInfo info = mRcsProvisioningInfos.get(subId);
        if (info != null) {
            info.setConfig(null);
        }
        notifyRcsAutoConfigurationRemoved(subId);
        triggerRcsReconfiguration(subId);
    }

    private void notifyDmaForSub(int subId, int capability) {
        final Intent intent = new Intent(
                ProvisioningManager.ACTION_RCS_SINGLE_REGISTRATION_CAPABILITY_UPDATE);
        intent.setPackage(mDmaPackageName);
        intent.putExtra(ProvisioningManager.EXTRA_SUBSCRIPTION_ID, subId);
        intent.putExtra(ProvisioningManager.EXTRA_STATUS, capability);
        logv("notify " + intent);
        mPhone.sendBroadcast(intent);
    }

    private IImsConfig getIImsConfig(int subId, int feature) {
        return mPhone.getImsResolver().getImsConfig(
                SubscriptionManager.getSlotIndex(subId), feature);
    }

    private String getDmaPackageName() {
        try {
            return CollectionUtils.firstOrNull(mRoleManager.getRoleHolders(RoleManager.ROLE_SMS));
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

    /**
     * {@link RoleManager} is final so we have to wrap the implementation for testing.
     */
    @VisibleForTesting
    public interface RoleManagerAdapter {
        /** See {@link RoleManager#getRoleHolders(String)} */
        List<String> getRoleHolders(String roleName);
        /** See {@link RoleManager#addOnRoleHoldersChangedListenerAsUser} */
        void addOnRoleHoldersChangedListenerAsUser(Executor executor,
                OnRoleHoldersChangedListener listener, UserHandle user);
        /** See {@link RoleManager#removeOnRoleHoldersChangedListenerAsUser} */
        void removeOnRoleHoldersChangedListenerAsUser(OnRoleHoldersChangedListener listener,
                UserHandle user);
    }

    private static class RoleManagerAdapterImpl implements RoleManagerAdapter {
        private final RoleManager mRoleManager;

        private RoleManagerAdapterImpl(Context context) {
            mRoleManager = context.getSystemService(RoleManager.class);
        }

        @Override
        public List<String> getRoleHolders(String roleName) {
            return mRoleManager.getRoleHolders(roleName);
        }

        @Override
        public void addOnRoleHoldersChangedListenerAsUser(Executor executor,
                OnRoleHoldersChangedListener listener, UserHandle user) {
            mRoleManager.addOnRoleHoldersChangedListenerAsUser(executor, listener, user);
        }

        @Override
        public void removeOnRoleHoldersChangedListenerAsUser(OnRoleHoldersChangedListener listener,
                UserHandle user) {
            mRoleManager.removeOnRoleHoldersChangedListenerAsUser(listener, user);
        }
    }
}
