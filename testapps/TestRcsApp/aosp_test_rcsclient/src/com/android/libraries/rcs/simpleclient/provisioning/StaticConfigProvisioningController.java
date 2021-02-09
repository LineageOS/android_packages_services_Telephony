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

package com.android.libraries.rcs.simpleclient.provisioning;

import android.os.Build.VERSION_CODES;
import android.telephony.SubscriptionManager;
import android.telephony.ims.ImsException;
import android.telephony.ims.ProvisioningManager;
import android.telephony.ims.ProvisioningManager.RcsProvisioningCallback;
import android.telephony.ims.RcsClientConfiguration;
import android.util.Log;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.annotation.RequiresPermission;
import androidx.annotation.VisibleForTesting;

/**
 * "Fake" provisioning implementation for supplying a static config when testing ProvisioningManager
 * is unnecessary. State changes are invoked manually.
 */
public class StaticConfigProvisioningController implements ProvisioningController {

    private static final String TAG = StaticConfigProvisioningController.class.getSimpleName();
    private final ProvisioningManager provisioningManager;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private Optional<RcsProvisioningCallback> storedCallback = Optional.empty();
    private Optional<ProvisioningStateChangeCallback> stateChangeCallback = Optional.empty();
    private Optional<byte[]> configXmlData = Optional.empty();

    private StaticConfigProvisioningController(int subId) {
        this.provisioningManager = ProvisioningManager.createForSubscriptionId(subId);
    }

    @RequiresApi(api = VERSION_CODES.R)
    public static StaticConfigProvisioningController createWithDefaultSubscriptionId() {
        return new StaticConfigProvisioningController(
                SubscriptionManager.getActiveDataSubscriptionId());
    }

    public static StaticConfigProvisioningController createForSubscriptionId(int subscriptionId) {
        return new StaticConfigProvisioningController(subscriptionId);
    }

    // Static configuration.
    private static RcsClientConfiguration getDefaultClientConfiguration() {

        return new RcsClientConfiguration(
                /*rcsVersion=*/ "6.0",
                /*rcsProfile=*/ "UP_2.3",
                /*clientVendor=*/ "Goog",
                /*clientVersion=*/ "RCSAndrd-1.0");//"RCS fake library 1.0");
    }

    @Override
    @RequiresPermission(value = "Manifest.permission.READ_PRIVILEGED_PHONE_STATE")
    public void triggerProvisioning() throws ImsException {
        boolean isRegistered = false;
        synchronized (this) {
            isRegistered = storedCallback.isPresent();
        }

        if (isRegistered) {
            triggerReconfiguration();
        } else {
            register();
        }
    }

    @Override
    public void onConfigurationChange(ProvisioningStateChangeCallback cb) {
        stateChangeCallback = Optional.of(cb);
    }

    @RequiresPermission(value = "Manifest.permission.READ_PRIVILEGED_PHONE_STATE")
    public void register() throws ImsException {
        register(getDefaultClientConfiguration());
    }

    @SuppressWarnings("LogConditional")
    // TODO(b/171976006) Use 'tools:ignore=' in manifest instead.
    @RequiresPermission(value = "Manifest.permission.READ_PRIVILEGED_PHONE_STATE")
    public void register(@NonNull RcsClientConfiguration clientConfiguration) throws ImsException {
        Log.i(TAG, "Using configuration: " + clientConfiguration.toString());
        provisioningManager.setRcsClientConfiguration(clientConfiguration);

        RcsProvisioningCallback callback =
                new RcsProvisioningCallback() {
                    @Override
                    public void onConfigurationChanged(@NonNull byte[] configXml) {
                        Log.i(TAG, "RcsProvisioningCallback.onConfigurationChanged called.");
                        synchronized (this) {
                            configXmlData = Optional.of(configXml);
                        }
                        stateChangeCallback.ifPresent(cb -> cb.notifyConfigChanged(configXml));
                    }

                    @RequiresApi(api = VERSION_CODES.R)
                    @Override
                    public void onConfigurationReset() {
                        Log.i(TAG, "RcsProvisioningCallback.onConfigurationReset called.");
                        synchronized (this) {
                            configXmlData = Optional.empty();
                        }
                        stateChangeCallback.ifPresent(cb -> cb.notifyConfigChanged(null));
                    }

                    @RequiresApi(api = VERSION_CODES.R)
                    @Override
                    public void onRemoved() {
                        Log.i(TAG, "RcsProvisioningCallback.onRemoved called.");
                        synchronized (this) {
                            configXmlData = Optional.empty();
                        }
                        stateChangeCallback.ifPresent(cb -> cb.notifyConfigChanged(null));
                    }
                };

        Log.i(TAG, "Registering the callback.");
        synchronized (this) {
            provisioningManager.registerRcsProvisioningChangedCallback(executorService, callback);
            storedCallback = Optional.of(callback);
        }
    }

    @RequiresPermission(value = "Manifest.permission.READ_PRIVILEGED_PHONE_STATE")
    public void unRegister() {
        synchronized (this) {
            RcsProvisioningCallback callback =
                    storedCallback.orElseThrow(
                            () -> new IllegalStateException("No callback present."));
            provisioningManager.unregisterRcsProvisioningChangedCallback(callback);
            storedCallback = Optional.empty();
        }
    }

    @Override
    @RequiresPermission(value = "Manifest.permission.READ_PRIVILEGED_PHONE_STATE")
    public boolean isRcsVolteSingleRegistrationCapable() throws ImsException {
        return provisioningManager.isRcsVolteSingleRegistrationCapable();
    }

    public synchronized byte[] getLatestConfiguration() {
        return configXmlData.orElseThrow(() -> new IllegalStateException("No config present"));
    }

    @VisibleForTesting
    @RequiresPermission(value = "Manifest.permission.READ_PRIVILEGED_PHONE_STATE")
    void triggerReconfiguration() {
        provisioningManager.triggerRcsReconfiguration();
    }

    @VisibleForTesting
    ProvisioningManager getProvisioningManager() {
        return provisioningManager;
    }
}
