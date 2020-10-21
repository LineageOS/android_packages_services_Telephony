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

import android.content.Context;
import android.telephony.ims.ImsException;
import android.telephony.ims.ImsService;
import android.util.Log;

import com.android.ims.RcsFeatureManager;
import com.android.internal.annotations.VisibleForTesting;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Manages the creation and destruction of SipDelegates in response to an IMS application requesting
 * a SipDelegateConnection registered to one or more IMS feature tags.
 * <p>
 * This allows an IMS application to forward traffic related to those feature tags over the existing
 * IMS registration managed by the {@link ImsService} associated with this cellular subscription
 * instead of requiring that the IMS application manage its own IMS registration over-the-top. This
 * is required for some cellular carriers, which mandate that all IMS SIP traffic must be sent
 * through a single IMS registration managed by the system IMS service.
 */
public class SipTransportController implements RcsFeatureController.Feature {
    private static final String LOG_TAG = "SipTransportC";

    private final Context mContext;
    private final int mSlotId;
    private final ScheduledExecutorService mExecutorService;

    private int mSubId;
    private RcsFeatureManager mRcsManager;

    /**
     * Create an instance of SipTransportController.
     * @param context The Context associated with this controller.
     * @param slotId The slot index associated with this controller.
     * @param subId The subscription ID associated with this controller when it was first created.
     */
    public SipTransportController(Context context, int slotId, int subId) {
        mContext = context;
        mSlotId = slotId;
        mSubId = subId;

        mExecutorService = Executors.newSingleThreadScheduledExecutor();
    }

    /**
     * Constructor to inject dependencies for testing.
     */
    @VisibleForTesting
    public SipTransportController(Context context, int slotId, int subId,
            ScheduledExecutorService executor) {
        mContext = context;
        mSlotId = slotId;
        mSubId = subId;

        mExecutorService = executor;
        logi("created");
    }

    @Override
    public void onRcsConnected(RcsFeatureManager manager) {
        mExecutorService.submit(() -> onRcsManagerChanged(manager));
    }

    @Override
    public void onRcsDisconnected() {
        mExecutorService.submit(() -> onRcsManagerChanged(null));
    }

    @Override
    public void onAssociatedSubscriptionUpdated(int subId) {
        mExecutorService.submit(()-> onSubIdChanged(subId));
    }

    @Override
    public void onDestroy() {
        // Can be null in testing.
        mExecutorService.shutdownNow();
    }

    /**
     * @return Whether or not SipTransports are supported on the connected ImsService. This can
     * change based on the capabilities of the ImsService.
     * @throws ImsException if the ImsService connected to this controller is currently down.
     */
    public boolean isSupported(int subId) throws ImsException {
        Boolean result = waitForMethodToComplete(() -> isSupportedInternal(subId));
        if (result == null) {
            logw("isSupported, unexpected null result, returning false");
            return false;
        }
        return result;
    }

    /**
     * Returns whether or not the ImsService implementation associated with the supplied subId
     * supports the SipTransport APIs.
     * <p>
     * This should only be called on the ExecutorService.
     * @return true if SipTransport is supported on this subscription, false otherwise.
     * @throws ImsException thrown if there was an error determining the state of the ImsService.
     */
    private boolean isSupportedInternal(int subId) throws ImsException {
        checkStateOfController(subId);
        return (mRcsManager.getSipTransport() != null);
    }

    /**
     * Run a Callable on the ExecutorService Thread and wait for the result.
     * If an ImsException is thrown, catch it and rethrow it to caller.
     */
    private <T> T waitForMethodToComplete(Callable<T> callable) throws ImsException {
        Future<T> r = mExecutorService.submit(callable);
        T result;
        try {
            result = r.get();
        } catch (InterruptedException e) {
            result = null;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ImsException) {
                // Rethrow the exception
                throw (ImsException) cause;
            }
            logw("Unexpected Exception, returning null: " + cause);
            result = null;
        }
        return result;
    }

    /**
     * Throw an ImsException for common scenarios where the state of the controller is not ready
     * for communication.
     * <p>
     * This should only be called while running on the on the ExecutorService.
     */
    private void checkStateOfController(int subId) throws ImsException {
        if (mSubId != subId) {
            // sub ID has changed while this was in the queue.
            throw new ImsException("subId is no longer valid for this request.",
                    ImsException.CODE_ERROR_INVALID_SUBSCRIPTION);
        }
        if (mRcsManager == null) {
            throw new ImsException("Connection to ImsService is not available",
                    ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

    private void onRcsManagerChanged(RcsFeatureManager m) {
        logi("manager changed, " + mRcsManager + "->" + m);
        mRcsManager = m;
    }

    /**
     * Called when either the sub ID associated with the slot has changed or the carrier
     * configuration associated with the same subId has changed.
     */
    private void onSubIdChanged(int newSubId) {
        logi("subId changed, " + mSubId + "->" + newSubId);
        mSubId = newSubId;
    }

    private void logi(String message) {
        Log.i(LOG_TAG, getPrefix() + ": " + message);
    }

    private void logw(String message) {
        Log.w(LOG_TAG, getPrefix() + ": " + message);
    }

    private String getPrefix() {
        return "[" + mSlotId + "," + mSubId + "]";
    }
}
