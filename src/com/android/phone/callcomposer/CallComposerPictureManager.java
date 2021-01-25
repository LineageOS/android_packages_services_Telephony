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

package com.android.phone.callcomposer;

import android.content.Context;
import android.net.Uri;
import android.util.Pair;
import android.util.SparseArray;

import java.util.HashMap;
import java.util.UUID;
import java.util.function.Consumer;

public class CallComposerPictureManager {
    private static final SparseArray<CallComposerPictureManager> sInstances = new SparseArray<>();

    public static CallComposerPictureManager getInstance(Context context, int subscriptionId) {
        synchronized (sInstances) {
            if (!sInstances.contains(subscriptionId)) {
                sInstances.put(subscriptionId,
                        new CallComposerPictureManager(context, subscriptionId));
            }
            return sInstances.get(subscriptionId);
        }
    }

    private HashMap<UUID, ImageData> mCachedPics = new HashMap<>();
    private HashMap<UUID, String> mCachedServerUrls = new HashMap<>();
    private GbaCredentials mCachedCredentials;
    private final int mSubscriptionId;
    private final Context mContext;

    private CallComposerPictureManager(Context context, int subscriptionId) {
        mContext = context;
        mSubscriptionId = subscriptionId;
    }

    public void handleUploadToServer(ImageData imageData, Consumer<Pair<UUID, Integer>> callback) {
        // TODO: plumbing
    }

    public void handleDownloadFromServer(String remoteUrl, Consumer<Pair<Uri, Integer>> callback) {
        // TODO: plumbing, insert to call log
    }
}
