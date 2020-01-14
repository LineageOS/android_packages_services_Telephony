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

import android.annotation.Nullable;
import android.content.Context;
import android.telephony.SubscriptionManager;
import android.util.Log;
import android.util.SparseArray;

import com.android.ims.RcsFeatureConnection;
import com.android.ims.RcsFeatureManager;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.imsphone.ImsPhone;
import com.android.internal.telephony.imsphone.ImsRcsStatusListener;
import com.android.phone.R;
import com.android.service.ims.presence.PresencePublication;
import com.android.service.ims.presence.PresenceSubscriber;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

class PresenceHelper {

    private static final String LOG_TAG = "PresenceHelper";

    private final Context mContext;
    private final List<Phone> mPhones;

    private final SparseArray<PresencePublication> mPresencePublications = new SparseArray<>();
    private final SparseArray<PresenceSubscriber> mPresenceSubscribers = new SparseArray<>();

    PresenceHelper(Context context) {
        mContext = context;

        // Get phones
        Phone[] phoneAry = PhoneFactory.getPhones();
        mPhones = (phoneAry != null) ? Arrays.asList(phoneAry) : new ArrayList<>();

        initRcsPresencesInstance();
        registerRcsConnectionStatus();

        Log.i(LOG_TAG, "initialized: phone size=" + mPhones.size());
    }

    private void initRcsPresencesInstance() {
        String[] volteError = mContext.getResources().getStringArray(
                R.array.config_volte_provision_error_on_publish_response);
        String[] rcsError = mContext.getResources().getStringArray(
                R.array.config_rcs_provision_error_on_publish_response);

        mPhones.forEach((phone) -> {
            RcsFeatureConnection rcsConnection = getRcsFeatureConnection(phone);
            // Initialize PresencePublication
            mPresencePublications.put(
                    phone.getPhoneId(),
                    new PresencePublication(rcsConnection, mContext, volteError, rcsError));
            // Initialize PresenceSubscriber
            mPresenceSubscribers.put(
                    phone.getPhoneId(),
                    new PresenceSubscriber(rcsConnection, mContext, volteError, rcsError));
        });
    }

    private @Nullable RcsFeatureConnection getRcsFeatureConnection(Phone phone) {
        ImsPhone imsPhone = (ImsPhone) phone.getImsPhone();
        if (imsPhone != null) {
            RcsFeatureManager rcsFeatureManager = imsPhone.getRcsManager();
            if (rcsFeatureManager != null) {
                return rcsFeatureManager.getRcsFeatureConnection();
            }
        }
        return null;
    }

    /*
     * RcsFeatureManager in ImsPhone is not null only when RCS is connected. Register a callback to
     * receive the RCS connection status.
     */
    private void registerRcsConnectionStatus() {
        mPhones.forEach((phone) -> {
            ImsPhone imsPhone = (ImsPhone) phone.getImsPhone();
            if (imsPhone != null) {
                imsPhone.setRcsStatusListener(mStatusListener);
            }
        });
    }

    /**
     * The IMS RCS status listener to listen the status changed
     */
    private ImsRcsStatusListener mStatusListener = new ImsRcsStatusListener() {
        @Override
        public void onRcsConnected(int phoneId, RcsFeatureManager rcsFeatureManager) {
            int subId = getSubscriptionId(phoneId);
            if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                Log.e(LOG_TAG, "onRcsConnected: invalid subId, phoneId=" + phoneId);
                return;
            }

            Log.i(LOG_TAG, "onRcsConnected: phoneId=" + phoneId + ", subId=" + subId);
            RcsFeatureConnection connection = rcsFeatureManager.getRcsFeatureConnection();
            PresencePublication presencePublication = getPresencePublication(phoneId);
            if (presencePublication != null) {
                Log.i(LOG_TAG, "Update PresencePublisher because RCS is connected");
                presencePublication.updatePresencePublisher(subId, connection);
            }
            PresenceSubscriber presenceSubscriber = getPresenceSubscriber(phoneId);
            if (presenceSubscriber != null) {
                Log.i(LOG_TAG, "Update PresenceSubscriber because RCS is connected");
                presenceSubscriber.updatePresenceSubscriber(subId, connection);
            }
        }

        @Override
        public void onRcsDisconnected(int phoneId) {
            int subId = getSubscriptionId(phoneId);
            Log.i(LOG_TAG, "onRcsDisconnected: phoneId=" + phoneId + ", subId=" + subId);
            PresencePublication publication = getPresencePublication(phoneId);
            if (publication != null) {
                Log.i(LOG_TAG, "Remove PresencePublisher because RCS is disconnected");
                publication.removePresencePublisher(subId);
            }

            PresenceSubscriber subscriber = getPresenceSubscriber(phoneId);
            if (subscriber != null) {
                Log.i(LOG_TAG, "Remove PresencePublisher because RCS is disconnected");
                subscriber.removePresenceSubscriber(subId);
            }
        }
    };

    private int getSubscriptionId(int phoneId) {
        Optional<Phone> phone = mPhones.stream()
                .filter(p -> p.getPhoneId() == phoneId).findFirst();
        if (phone.isPresent()) {
            return phone.get().getSubId();
        }
        return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    }

    public @Nullable PresencePublication getPresencePublication(int phoneId) {
        return mPresencePublications.get(phoneId);
    }

    public @Nullable PresenceSubscriber getPresenceSubscriber(int phoneId) {
        return mPresenceSubscribers.get(phoneId);
    }
}
