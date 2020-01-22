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
import android.net.Uri;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.telephony.ims.ImsException;
import android.telephony.ims.RcsContactUceCapability;
import android.telephony.ims.RcsUceAdapter;
import android.telephony.ims.aidl.IRcsUceControllerCallback;
import android.util.Log;

import com.android.ims.ResultCode;
import com.android.service.ims.presence.ContactCapabilityResponse;
import com.android.service.ims.presence.PresenceBase;
import com.android.service.ims.presence.PresencePublication;
import com.android.service.ims.presence.PresenceSubscriber;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Telephony RCS Service integrates PresencePublication and PresenceSubscriber into the service.
 */
public class TelephonyRcsService {

    private static final String LOG_TAG = "TelephonyRcsService";

    private final Context mContext;

    // A helper class to manage the RCS Presences instances.
    private final PresenceHelper mPresenceHelper;

    private ConcurrentHashMap<Integer, IRcsUceControllerCallback> mPendingRequests;

    public TelephonyRcsService(Context context) {
        Log.i(LOG_TAG, "initialize");
        mContext = context;
        mPresenceHelper = new PresenceHelper(mContext);
        mPendingRequests = new ConcurrentHashMap<>();
    }

    /**
     * @return the UCE Publish state for the phone ID specified.
     */
    public int getUcePublishState(int phoneId) {
        PresencePublication publisher = getPresencePublication(phoneId);
        if (publisher == null) {
            throw new ServiceSpecificException(ImsException.CODE_ERROR_SERVICE_UNAVAILABLE,
                    "UCE service is not currently running.");
        }
        int publishState = publisher.getPublishState();
        return toUcePublishState(publishState);
    }

    /**
     * Perform a capabilities request and call {@link IRcsUceControllerCallback} with the result.
     */
    public void requestCapabilities(int phoneId, List<Uri> contactNumbers,
            IRcsUceControllerCallback c) {
        PresenceSubscriber subscriber = getPresenceSubscriber(phoneId);
        if (subscriber == null) {
            throw new ServiceSpecificException(ImsException.CODE_ERROR_SERVICE_UNAVAILABLE,
                    "UCE service is not currently running.");
        }
        List<String> numbers = contactNumbers.stream().map(TelephonyRcsService::getNumberFromUri)
                .collect(Collectors.toList());
        int taskId = subscriber.requestCapability(numbers, new ContactCapabilityResponse() {
            @Override
            public void onSuccess(int reqId) {
                Log.i(LOG_TAG, "onSuccess called for reqId:" + reqId);
            }

            @Override
            public void onError(int reqId, int resultCode) {
                IRcsUceControllerCallback c = mPendingRequests.remove(reqId);
                try {
                    if (c != null) {
                        c.onError(toUceError(resultCode));
                    } else {
                        Log.w(LOG_TAG, "onError called for unknown reqId:" + reqId);
                    }
                } catch (RemoteException e) {
                    Log.i(LOG_TAG, "Calling back to dead service");
                }
            }

            @Override
            public void onFinish(int reqId) {
                Log.i(LOG_TAG, "onFinish called for reqId:" + reqId);
            }

            @Override
            public void onTimeout(int reqId) {
                IRcsUceControllerCallback c = mPendingRequests.remove(reqId);
                try {
                    if (c != null) {
                        c.onError(RcsUceAdapter.ERROR_REQUEST_TIMEOUT);
                    } else {
                        Log.w(LOG_TAG, "onTimeout called for unknown reqId:" + reqId);
                    }
                } catch (RemoteException e) {
                    Log.i(LOG_TAG, "Calling back to dead service");
                }
            }

            @Override
            public void onCapabilitiesUpdated(int reqId,
                    List<RcsContactUceCapability> contactCapabilities,
                    boolean updateLastTimestamp) {
                IRcsUceControllerCallback c = mPendingRequests.remove(reqId);
                try {
                    if (c != null) {
                        c.onCapabilitiesReceived(contactCapabilities);
                    } else {
                        Log.w(LOG_TAG, "onCapabilitiesUpdated, unknown reqId:" + reqId);
                    }
                } catch (RemoteException e) {
                    Log.w(LOG_TAG, "onCapabilitiesUpdated on dead service");
                }
            }
        });
        if (taskId < 0) {
            try {
                c.onError(toUceError(taskId));
                return;
            } catch (RemoteException e) {
                Log.i(LOG_TAG, "Calling back to dead service");
            }
        }
        mPendingRequests.put(taskId, c);
    }

    private PresencePublication getPresencePublication(int phoneId) {
        return mPresenceHelper.getPresencePublication(phoneId);
    }

    private PresenceSubscriber getPresenceSubscriber(int phoneId) {
        return mPresenceHelper.getPresenceSubscriber(phoneId);
    }

    private static String getNumberFromUri(Uri uri) {
        String number = uri.getSchemeSpecificPart();
        String[] numberParts = number.split("[@;:]");

        if (numberParts.length == 0) {
            return null;
        }
        return numberParts[0];
    }

    private static int toUcePublishState(int publishState) {
        switch (publishState) {
            case PresenceBase.PUBLISH_STATE_200_OK:
                return RcsUceAdapter.PUBLISH_STATE_200_OK;
            case PresenceBase.PUBLISH_STATE_NOT_PUBLISHED:
                return RcsUceAdapter.PUBLISH_STATE_NOT_PUBLISHED;
            case PresenceBase.PUBLISH_STATE_VOLTE_PROVISION_ERROR:
                return RcsUceAdapter.PUBLISH_STATE_VOLTE_PROVISION_ERROR;
            case PresenceBase.PUBLISH_STATE_RCS_PROVISION_ERROR:
                return  RcsUceAdapter.PUBLISH_STATE_RCS_PROVISION_ERROR;
            case PresenceBase.PUBLISH_STATE_REQUEST_TIMEOUT:
                return RcsUceAdapter.PUBLISH_STATE_REQUEST_TIMEOUT;
            case PresenceBase.PUBLISH_STATE_OTHER_ERROR:
                return RcsUceAdapter.PUBLISH_STATE_OTHER_ERROR;
            default:
                return RcsUceAdapter.PUBLISH_STATE_OTHER_ERROR;
        }
    }

    private static int toUceError(int resultCode) {
        switch(resultCode) {
            case ResultCode.SUBSCRIBE_NOT_REGISTERED:
                return RcsUceAdapter.ERROR_NOT_REGISTERED;
            case ResultCode.SUBSCRIBE_REQUEST_TIMEOUT:
                return RcsUceAdapter.ERROR_REQUEST_TIMEOUT;
            case ResultCode.SUBSCRIBE_FORBIDDEN:
                return RcsUceAdapter.ERROR_FORBIDDEN;
            case ResultCode.SUBSCRIBE_NOT_FOUND:
                return RcsUceAdapter.ERROR_NOT_FOUND;
            case ResultCode.SUBSCRIBE_TOO_LARGE:
                return RcsUceAdapter.ERROR_REQUEST_TOO_LARGE;
            case ResultCode.SUBSCRIBE_INSUFFICIENT_MEMORY:
                return RcsUceAdapter.ERROR_INSUFFICIENT_MEMORY;
            case ResultCode.SUBSCRIBE_LOST_NETWORK:
                return RcsUceAdapter.ERROR_LOST_NETWORK;
            case ResultCode.SUBSCRIBE_ALREADY_IN_QUEUE:
                return  RcsUceAdapter.ERROR_ALREADY_IN_QUEUE;
            default:
                return RcsUceAdapter.ERROR_GENERIC_FAILURE;
        }
    }
}
