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
import android.util.Log;

import com.android.service.ims.presence.PresencePublication;
import com.android.service.ims.presence.PresenceSubscriber;

/**
 * Telephony RCS Service integrates PresencePublication and PresenceSubscriber into the service.
 */
public class TelephonyRcsService {

    private static final String LOG_TAG = "TelephonyRcsService";

    private final Context mContext;

    // A helper class to manage the RCS Presences instances.
    private final PresenceHelper mPresenceHelper;

    public TelephonyRcsService(Context context) {
        Log.i(LOG_TAG, "initialize");
        mContext = context;
        mPresenceHelper = new PresenceHelper(mContext);
    }

    private PresencePublication getPresencePublication(int phoneId) {
        return mPresenceHelper.getPresencePublication(phoneId);
    }

    private PresenceSubscriber getPresenceSubscriber(int phoneId) {
        return mPresenceHelper.getPresenceSubscriber(phoneId);
    }
}
