/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.services.telephony.rcs;

import android.annotation.AnyThread;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.PhoneConfigurationManager;
import com.android.internal.util.IndentingPrintWriter;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Singleton service setup to manage RCS related services that the platform provides such as User
 * Capability Exchange.
 */
@AnyThread
public class TelephonyRcsService {

    private static final String LOG_TAG = "TelephonyRcsService";

    /**
     * Used to inject RcsFeatureController and UserCapabilityExchangeImpl instances for testing.
     */
    @VisibleForTesting
    public interface FeatureFactory {
        /**
         * @return an {@link RcsFeatureController} assoicated with the slot specified.
         */
        RcsFeatureController createController(Context context, int slotId);

        /**
         * @return an instance of {@link UserCapabilityExchangeImpl} associated with the slot
         * specified.
         */
        UserCapabilityExchangeImpl createUserCapabilityExchange(Context context, int slotId,
                int subId);
    }

    private FeatureFactory mFeatureFactory = new FeatureFactory() {
        @Override
        public RcsFeatureController createController(Context context, int slotId) {
            return new RcsFeatureController(context, slotId);
        }

        @Override
        public UserCapabilityExchangeImpl createUserCapabilityExchange(Context context, int slotId,
                int subId) {
            return new UserCapabilityExchangeImpl(context, slotId, subId);
        }
    };

    // Notifies this service that there has been a change in available slots.
    private static final int HANDLER_MSIM_CONFIGURATION_CHANGE = 1;

    private final Context mContext;
    private final Object mLock = new Object();
    private int mNumSlots;

    // Index corresponds to the slot ID.
    private List<RcsFeatureController> mFeatureControllers;

    private BroadcastReceiver mCarrierConfigChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                return;
            }
            if (CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED.equals(intent.getAction())) {
                Bundle bundle = intent.getExtras();
                if (bundle == null) {
                    return;
                }
                int slotId = bundle.getInt(CarrierConfigManager.EXTRA_SLOT_INDEX);
                int subId = bundle.getInt(CarrierConfigManager.EXTRA_SUBSCRIPTION_INDEX);
                updateFeatureControllerSubscription(slotId, subId);
            }
        }
    };

    private Handler mHandler = new Handler(Looper.getMainLooper(), (msg) -> {
        switch (msg.what) {
            case HANDLER_MSIM_CONFIGURATION_CHANGE: {
                AsyncResult result = (AsyncResult) msg.obj;
                Integer numSlots = (Integer) result.result;
                if (numSlots == null) {
                    Log.w(LOG_TAG, "msim config change with null num slots.");
                    break;
                }
                updateFeatureControllerSize(numSlots);
                break;
            }
            default:
                return false;
        }
        return true;
    });

    public TelephonyRcsService(Context context, int numSlots) {
        Log.i(LOG_TAG, "initialize");
        mContext = context;
        mNumSlots = numSlots;
        mFeatureControllers = new ArrayList<>(numSlots);
    }

    /**
     * @return the {@link RcsFeatureController} associated with the given slot.
     */
    public RcsFeatureController getFeatureController(int slotId) {
        synchronized (mLock) {
            return mFeatureControllers.get(slotId);
        }
    }

    /**
     * Called after instance creation to initialize internal structures as well as register for
     * system callbacks.
     */
    public void initialize() {
        synchronized (mLock) {
            for (int i = 0; i < mNumSlots; i++) {
                mFeatureControllers.add(constructFeatureController(i));
            }
        }

        PhoneConfigurationManager.registerForMultiSimConfigChange(mHandler,
                HANDLER_MSIM_CONFIGURATION_CHANGE, null);
        mContext.registerReceiver(mCarrierConfigChangedReceiver, new IntentFilter(
                CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED));
    }

    @VisibleForTesting
    public void setFeatureFactory(FeatureFactory f) {
        mFeatureFactory = f;
    }

    /**
     * Update the number of {@link RcsFeatureController}s that are created based on the number of
     * active slots on the device.
     */
    @VisibleForTesting
    public void updateFeatureControllerSize(int newNumSlots) {
        synchronized (mLock) {
            int oldNumSlots = mFeatureControllers.size();
            if (oldNumSlots == newNumSlots) {
                return;
            }
            mNumSlots = newNumSlots;
            if (oldNumSlots < newNumSlots) {
                for (int i = oldNumSlots; i < newNumSlots; i++) {
                    mFeatureControllers.add(constructFeatureController(i));
                }
            } else {
                for (int i = (oldNumSlots - 1); i > (newNumSlots - 1); i--) {
                    RcsFeatureController controller = mFeatureControllers.remove(i);
                    controller.destroy();
                }
            }
        }
    }

    private void updateFeatureControllerSubscription(int slotId, int newSubId) {
        synchronized (mLock) {
            RcsFeatureController f = mFeatureControllers.get(slotId);
            if (f == null) {
                Log.w(LOG_TAG, "unexpected null FeatureContainer for slot " + slotId);
                return;
            }
            f.updateAssociatedSubscription(newSubId);
        }
    }

    private RcsFeatureController constructFeatureController(int slotId) {
        RcsFeatureController c = mFeatureFactory.createController(mContext, slotId);
        // TODO: integrate user setting into whether or not this feature is added as well as logic
        // to listen for changes in user setting.
        c.addFeature(mFeatureFactory.createUserCapabilityExchange(mContext, slotId,
                getSubscriptionFromSlot(slotId)), UserCapabilityExchangeImpl.class);
        c.connect();
        return c;
    }

    private int getSubscriptionFromSlot(int slotId) {
        SubscriptionManager manager = mContext.getSystemService(SubscriptionManager.class);
        if (manager == null) {
            Log.w(LOG_TAG, "Couldn't find SubscriptionManager for slotId=" + slotId);
            return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        }
        int[] subIds = manager.getSubscriptionIds(slotId);
        if (subIds != null && subIds.length > 0) {
            return subIds[0];
        }
        return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    }

    /**
     * Dump this instance into a readable format for dumpsys usage.
     */
    public void dump(FileDescriptor fd, PrintWriter printWriter, String[] args) {
        IndentingPrintWriter pw = new IndentingPrintWriter(printWriter, "  ");
        pw.println("RcsFeatureControllers:");
        pw.increaseIndent();
        synchronized (mLock) {
            for (RcsFeatureController f : mFeatureControllers) {
                pw.increaseIndent();
                f.dump(fd, printWriter, args);
                pw.decreaseIndent();
            }
        }
        pw.decreaseIndent();
    }
}
