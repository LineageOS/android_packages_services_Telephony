/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.phone.testapps.embmsmw;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.telephony.mbms.IMbmsStreamingManagerCallback;
import android.telephony.mbms.MbmsException;
import android.telephony.mbms.vendor.IMbmsStreamingService;
import android.telephony.mbms.vendor.MbmsStreamingServiceBase;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

public class EmbmsTestStreamingService extends Service {
    private final static String TAG = "EmbmsTestStreaming";
    private final Map<String, IMbmsStreamingManagerCallback> mAppCallbacks = new HashMap<>();

    private final IMbmsStreamingService.Stub mBinder = new MbmsStreamingServiceBase() {
        @Override
        public int initialize(IMbmsStreamingManagerCallback listener, String appName, int subId)
                throws MbmsException {
            String appKey = appName + subId;
            if (!mAppCallbacks.containsKey(appKey)) {
                mAppCallbacks.put(appKey, listener);
            } else {
                return MbmsException.ERROR_ALREADY_INITIALIZED;
            }
            return 0;
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        logd("EmbmsTestStreamingService onCreate");
    }

    @Override
    public void onDestroy() {
        super.onCreate();
        logd("EmbmsTestStreamingService onDestroy");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    private static void logd(String s) {
        Log.d(TAG, s);
    }
}
