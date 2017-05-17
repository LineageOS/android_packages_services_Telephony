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
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;
import android.telephony.mbms.IMbmsStreamingManagerCallback;
import android.telephony.mbms.IStreamingServiceCallback;
import android.telephony.mbms.MbmsException;
import android.telephony.mbms.StreamingService;
import android.telephony.mbms.StreamingServiceInfo;
import android.telephony.mbms.vendor.IMbmsStreamingService;
import android.telephony.mbms.vendor.MbmsStreamingServiceBase;
import android.util.Log;

import com.android.internal.os.SomeArgs;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class EmbmsTestStreamingService extends Service {
    private static final Set<String> ALLOWED_PACKAGES = new HashSet<String>() {{
        add("com.android.phone.testapps.embmsfrontend");
    }};

    private static final String TAG = "EmbmsTestStreaming";

    private static final long SEND_SERVICE_LIST_DELAY = 300;
    private static final long START_STREAMING_DELAY = 500;

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
                break;
        }
        return true;
    };

    private final IMbmsStreamingService.Stub mBinder = new MbmsStreamingServiceBase() {
        @Override
        public int initialize(IMbmsStreamingManagerCallback listener, String appName, int subId) {
            String[] packageNames = getPackageManager().getPackagesForUid(Binder.getCallingUid());
            if (packageNames == null) {
                throw new SecurityException("No matching packages found for your UID");
            }
            boolean isUidAllowed = Arrays.stream(packageNames).anyMatch(ALLOWED_PACKAGES::contains);
            if (!isUidAllowed) {
                throw new SecurityException("No packages for your UID are allowed to use this " +
                        "service");
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
            checkInitialized(appKey);

            List<StreamingServiceInfo> serviceInfos =
                    StreamingServiceRepository.getStreamingServicesForClasses(serviceClasses);

            SomeArgs args = SomeArgs.obtain();
            args.arg1 = appKey;
            args.arg2 = serviceInfos;

            mHandler.removeMessages(SEND_STREAMING_SERVICES_LIST);
            mHandler.sendMessageDelayed(
                    mHandler.obtainMessage(SEND_STREAMING_SERVICES_LIST, args),
                    SEND_SERVICE_LIST_DELAY);
            return MbmsException.SUCCESS;
        }

        @Override
        public int startStreaming(String appName, int subscriptionId, String serviceId,
                IStreamingServiceCallback callback) {
            StreamingAppIdentifier appKey =
                    new StreamingAppIdentifier(Binder.getCallingUid(), appName, subscriptionId);
            checkInitialized(appKey);
            checkServiceExists(serviceId);

            if (StreamStateTracker.getStreamingState(appKey, serviceId) ==
                    StreamingService.STATE_STARTED) {
                return MbmsException.ERROR_STREAM_ALREADY_STARTED;
            }

            mHandler.postDelayed(
                    () -> StreamStateTracker.startStreaming(appKey, serviceId, callback),
                    START_STREAMING_DELAY);
            return MbmsException.SUCCESS;
        }

        @Override
        public Uri getPlaybackUri(String appName, int subscriptionId, String serviceId) {
            StreamingAppIdentifier appKey =
                    new StreamingAppIdentifier(Binder.getCallingUid(), appName, subscriptionId);
            checkInitialized(appKey);
            checkServiceExists(serviceId);

            Uri streamingUri = StreamingServiceRepository.getUriForService(serviceId);
            if (streamingUri == null) {
                throw new IllegalArgumentException("Invalid service ID");
            }
            return streamingUri;
        }

        @Override
        public void disposeStream(String appName, int subscriptionId, String serviceId) {
            StreamingAppIdentifier appKey =
                    new StreamingAppIdentifier(Binder.getCallingUid(), appName, subscriptionId);
            checkInitialized(appKey);
            checkServiceExists(serviceId);

            Log.i(TAG, "Disposing of stream " + serviceId);
            StreamStateTracker.dispose(appKey, serviceId);
        }

        @Override
        public void dispose(String appName, int subscriptionId) {
            StreamingAppIdentifier appKey =
                    new StreamingAppIdentifier(Binder.getCallingUid(), appName, subscriptionId);
            checkInitialized(appKey);

            Log.i(TAG, "Disposing app " + appName);
            StreamStateTracker.disposeAll(appKey);
            mAppCallbacks.remove(appKey);
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

    private void checkInitialized(StreamingAppIdentifier appKey) {
        if (!mAppCallbacks.containsKey(appKey)) {
            throw new IllegalStateException("Not yet initialized");
        }
    }

    private void checkServiceExists(String serviceId) {
        if (StreamingServiceRepository.getStreamingServiceInfoForId(serviceId) == null) {
            throw new IllegalArgumentException("Invalid service ID");
        }
    }
}
