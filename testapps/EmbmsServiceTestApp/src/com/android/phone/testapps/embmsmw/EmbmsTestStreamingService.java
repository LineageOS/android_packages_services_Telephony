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

import android.app.AppOpsManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;
import android.telephony.mbms.IMbmsStreamingManagerCallback;
import android.telephony.mbms.MbmsException;
import android.telephony.mbms.StreamingServiceInfo;
import android.telephony.mbms.vendor.IMbmsStreamingService;
import android.telephony.mbms.vendor.MbmsStreamingServiceBase;
import android.util.Log;

import com.android.internal.os.SomeArgs;

import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class EmbmsTestStreamingService extends Service {
    private static final Set<String> ALLOWED_PACKAGES = new HashSet<String>() {{
        add("com.android.phone.testapps.embmsfrontend");
    }};

    private static final String TAG = "EmbmsTestStreaming";
    private static final int SEND_STREAMING_SERVICES_LIST = 1;

    private final Map<StreamingAppIdentifier, IMbmsStreamingManagerCallback> mAppCallbacks =
            new HashMap<>();

    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private Handler.Callback mWorkerCallback = (msg) -> {
        switch (msg.what) {
            case SEND_STREAMING_SERVICES_LIST:
                SomeArgs args = (SomeArgs) msg.obj;
                StreamingAppIdentifier appKey = (StreamingAppIdentifier) args.arg1;
                List<StreamingServiceInfo> services = (List) args.arg2;
                IMbmsStreamingManagerCallback appCallback = mAppCallbacks.get(appKey);
                if (appCallback != null) {
                    try {
                        appCallback.streamingServicesUpdated(services);
                    } catch (RemoteException e) {
                        // Assume app has gone away and clean up.
                    }
                }
        }
        return true;
    };

    private final IMbmsStreamingService.Stub mBinder = new MbmsStreamingServiceBase() {
        @Override
        public int initialize(IMbmsStreamingManagerCallback listener, String appName, int subId) {
            String[] packageNames = getPackageManager().getPackagesForUid(Binder.getCallingUid());
            if (packageNames == null) {
                return MbmsException.ERROR_APP_PERMISSIONS_NOT_GRANTED;
            }
            boolean isUidAllowed = Arrays.stream(packageNames).anyMatch(ALLOWED_PACKAGES::contains);
            if (!isUidAllowed) {
                return MbmsException.ERROR_APP_PERMISSIONS_NOT_GRANTED;
            }

            StreamingAppIdentifier appKey =
                    new StreamingAppIdentifier(Binder.getCallingUid(), appName, subId);
            if (!mAppCallbacks.containsKey(appKey)) {
                mAppCallbacks.put(appKey, listener);
            } else {
                return MbmsException.ERROR_ALREADY_INITIALIZED;
            }
            return 0;
        }

        @Override
        public int getStreamingServices(String appName, int subscriptionId,
                List<String> serviceClasses) {
            StreamingAppIdentifier appKey =
                    new StreamingAppIdentifier(Binder.getCallingUid(), appName, subscriptionId);
            if (!mAppCallbacks.containsKey(appKey)) {
                return MbmsException.ERROR_NOT_YET_INITIALIZED;
            }

            Map<Locale, String> nameDict1 = new HashMap<Locale, String>() {{
                put(Locale.US, "TestService1");
            }};
            Map<Locale, String> nameDict2 = new HashMap<Locale, String>() {{
                put(Locale.US, "TestService1");
            }};
            StreamingServiceInfo info1 = new StreamingServiceInfo(nameDict1, "Class 1", Locale.US,
                    "Service ID 1", new Date(System.currentTimeMillis() - 10000),
                    new Date(System.currentTimeMillis() + 10000));
            StreamingServiceInfo info2 = new StreamingServiceInfo(nameDict2, "Class 2", Locale.US,
                    "Service ID 2", new Date(System.currentTimeMillis() - 20000),
                    new Date(System.currentTimeMillis() + 20000));
            List<StreamingServiceInfo> serviceInfos = new LinkedList<StreamingServiceInfo>() {{
                add(info1);
                add(info2);
            }};

            SomeArgs args = SomeArgs.obtain();
            args.arg1 = appKey;
            args.arg2 = serviceInfos;

            mHandler.removeMessages(SEND_STREAMING_SERVICES_LIST);
            mHandler.sendMessageDelayed(
                    mHandler.obtainMessage(SEND_STREAMING_SERVICES_LIST, args), 300);
            return MbmsException.SUCCESS;
        }
    };

    @Override
    public void onDestroy() {
        super.onCreate();
        mHandlerThread.quitSafely();
        logd("EmbmsTestStreamingService onDestroy");
    }

    @Override
    public IBinder onBind(Intent intent) {
        logd("EmbmsTestStreamingService onBind");
        mHandlerThread = new HandlerThread("EmbmsTestStreamingServiceWorker");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper(), mWorkerCallback);
        return mBinder;
    }

    private static void logd(String s) {
        Log.d(TAG, s);
    }
}
