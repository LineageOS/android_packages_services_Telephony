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

import android.os.RemoteException;
import android.telephony.mbms.IStreamingServiceCallback;
import android.telephony.mbms.StreamingService;

import java.util.HashMap;
import java.util.Map;

// Tracks the states of the streams for a single (uid, appName, subscriptionId) tuple
public class AppActiveStreams {
    // Wrapper for a pair (StreamingServiceCallback, streaming state)
    private static class StreamCallbackWithState {
        private final IStreamingServiceCallback mCallback;
        private int mState;

        public StreamCallbackWithState(IStreamingServiceCallback callback, int state) {
            mCallback = callback;
            mState = state;
        }

        public IStreamingServiceCallback getCallback() {
            return mCallback;
        }

        public int getState() {
            return mState;
        }

        public void setState(int state) {
            mState = state;
        }
    }

    // Stores the state and callback per service ID.
    private final Map<String, StreamCallbackWithState> mStreamStates = new HashMap<>();
    private final StreamingAppIdentifier mAppIdentifier;

    public AppActiveStreams(StreamingAppIdentifier appIdentifier) {
        mAppIdentifier = appIdentifier;
    }

    public int getStateForService(String serviceId) {
        StreamCallbackWithState callbackWithState = mStreamStates.get(serviceId);
        return callbackWithState == null ?
                StreamingService.STATE_STOPPED : callbackWithState.getState();
    }

    public void startStreaming(String serviceId, IStreamingServiceCallback callback) {
        mStreamStates.put(serviceId,
                new StreamCallbackWithState(callback, StreamingService.STATE_STARTED));
        try {
            callback.streamStateChanged(StreamingService.STATE_STARTED);
        } catch (RemoteException e) {
            dispose(serviceId);
        }
    }

    public void stopStreaming(String serviceId) {
        StreamCallbackWithState entry = mStreamStates.get(serviceId);

        if (entry != null) {
            try {
                if (entry.getState() != StreamingService.STATE_STOPPED) {
                    entry.setState(StreamingService.STATE_STOPPED);
                    entry.getCallback().streamStateChanged(StreamingService.STATE_STOPPED);
                }
            } catch (RemoteException e) {
                dispose(serviceId);
            }
        }
    }

    public void dispose(String serviceId) {
        mStreamStates.remove(serviceId);
    }
}
