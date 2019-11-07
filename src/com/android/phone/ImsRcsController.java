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

package com.android.phone;

import android.content.Context;
import android.net.Uri;
import android.os.ServiceManager;
import android.telephony.ims.aidl.IImsCapabilityCallback;
import android.telephony.ims.aidl.IImsRcsController;
import android.telephony.ims.aidl.IRcsUceControllerCallback;
import android.telephony.ims.feature.RcsFeature;
import android.util.Log;

import java.util.List;

/**
 * Implementation of the IImsRcsController interface.
 */
public class ImsRcsController extends IImsRcsController.Stub {
    private static final String TAG = "ImsRcsController";

    /** The singleton instance. */
    private static ImsRcsController sInstance;

    private PhoneGlobals mApp;

    /**
     * Initialize the singleton ImsRcsController instance.
     * This is only done once, at startup, from PhoneApp.onCreate().
     */
    static ImsRcsController init(PhoneGlobals app) {
        synchronized (ImsRcsController.class) {
            if (sInstance == null) {
                sInstance = new ImsRcsController(app);
            } else {
                Log.wtf(TAG, "init() called multiple times!  sInstance = " + sInstance);
            }
            return sInstance;
        }
    }

    /** Private constructor; @see init() */
    private ImsRcsController(PhoneGlobals app) {
        Log.i(TAG, "ImsRcsController");
        mApp = app;
        ServiceManager.addService(Context.TELEPHONY_IMS_SERVICE, this);
    }

    @Override
    public void registerRcsAvailabilityCallback(IImsCapabilityCallback c) {
        enforceReadPrivilegedPermission("registerRcsAvailabilityCallback");
    }

    @Override
    public void unregisterRcsAvailabilityCallback(IImsCapabilityCallback c) {
        enforceReadPrivilegedPermission("unregisterRcsAvailabilityCallback");
    }

    @Override
    public boolean isCapable(int subId,
            @RcsFeature.RcsImsCapabilities.RcsImsCapabilityFlag int capability) {
        enforceReadPrivilegedPermission("isCapable");
        return false;
    }

    @Override
    public boolean isAvailable(int subId,
            @RcsFeature.RcsImsCapabilities.RcsImsCapabilityFlag int capability) {
        enforceReadPrivilegedPermission("isAvailable");
        return false;
    }

    @Override
    public void requestCapabilities(int subId, List<Uri> contactNumbers,
            IRcsUceControllerCallback c) {
        enforceReadPrivilegedPermission("requestCapabilities");
    }

    @Override
    public int getUcePublishState(int subId) {
        enforceReadPrivilegedPermission("getUcePublishState");
        return -1;
    }

    @Override
    public boolean isUceSettingEnabled(int subId) {
        enforceReadPrivilegedPermission("isUceSettingEnabled");
        return false;
    }

    @Override
    public void setUceSettingEnabled(int subId, boolean isEnabled) {
        enforceModifyPermission();
    }

    /**
     * Make sure either called from same process as self (phone) or IPC caller has read privilege.
     *
     * @throws SecurityException if the caller does not have the required permission
     */
    private void enforceReadPrivilegedPermission(String message) {
        mApp.enforceCallingOrSelfPermission(
                android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE, message);
    }

    /**
     * Make sure the caller has the MODIFY_PHONE_STATE permission.
     *
     * @throws SecurityException if the caller does not have the required permission
     */
    private void enforceModifyPermission() {
        mApp.enforceCallingOrSelfPermission(android.Manifest.permission.MODIFY_PHONE_STATE, null);
    }
}
