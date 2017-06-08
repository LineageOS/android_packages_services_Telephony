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

import android.app.Activity;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.telephony.MbmsDownloadManager;
import android.telephony.mbms.DownloadRequest;
import android.telephony.mbms.IDownloadCallback;
import android.telephony.mbms.MbmsDownloadReceiver;
import android.telephony.mbms.MbmsException;
import android.telephony.mbms.UriPathPair;
import android.telephony.mbms.vendor.IMbmsDownloadService;
import android.telephony.mbms.vendor.MbmsDownloadServiceBase;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class EmbmsSampleDownloadService extends Service {
    private static final String LOG_TAG = "EmbmsSampleDownload";
    private static final long DOWNLOAD_DELAY_MS = 1000;

    private final IMbmsDownloadService mBinder = new MbmsDownloadServiceBase() {
        @Override
        public int download(DownloadRequest downloadRequest, IDownloadCallback listener) {
            // TODO: move this package name finding logic to initialize()
            String[] packageNames = getPackageManager().getPackagesForUid(Binder.getCallingUid());
            if (packageNames == null) {
                throw new SecurityException("No matching packages found for your UID");
            }

            if (packageNames.length != 1) {
                throw new IllegalStateException("More than one package found for your UID");
            }

            String packageName = packageNames[0];

            mHandler.post(() -> sendFdRequest(downloadRequest, packageName, 1));
            return MbmsException.SUCCESS;
        }
    };

    private HandlerThread mHandlerThread;
    private Handler mHandler;

    @Override
    public IBinder onBind(Intent intent) {
        mHandlerThread = new HandlerThread("EmbmsTestDownloadServiceWorker");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        return mBinder.asBinder();
    }

    private void sendFdRequest(DownloadRequest request, String packageName, int numFds) {
        // Compose the FILE_DESCRIPTOR_REQUEST_INTENT
        Intent requestIntent = new Intent(MbmsDownloadManager.ACTION_FILE_DESCRIPTOR_REQUEST);
        requestIntent.putExtra(MbmsDownloadManager.EXTRA_REQUEST, request);
        requestIntent.putExtra(MbmsDownloadManager.EXTRA_FD_COUNT, numFds);
        ComponentName mbmsReceiverComponent = new ComponentName(packageName,
                MbmsDownloadReceiver.class.getCanonicalName());
        requestIntent.setComponent(mbmsReceiverComponent);

        // Send as an ordered broadcast, using a BroadcastReceiver to capture the result
        // containing UriPathPairs.
        sendOrderedBroadcast(requestIntent,
                null, // receiverPermission
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        Bundle resultExtras = getResultExtras(false);
                        // This delay is to emulate the time it'd usually take to fetch the file
                        // off the network.
                        mHandler.postDelayed(
                                () -> performDownload(request, packageName, resultExtras),
                                DOWNLOAD_DELAY_MS);
                    }
                },
                null, // scheduler
                Activity.RESULT_OK,
                null, // initialData
                null /* initialExtras */);
    }

    private void performDownload(DownloadRequest request, String packageName, Bundle extras) {
        int result = MbmsDownloadManager.RESULT_SUCCESSFUL;
        List<UriPathPair> tempFiles = extras.getParcelableArrayList(
                MbmsDownloadManager.EXTRA_FREE_URI_LIST);
        Uri tempFilePathUri = tempFiles.get(0).getFilePathUri();
        Uri freeTempFileUri = tempFiles.get(0).getContentUri();

        try {
            // Get the ParcelFileDescriptor for the single temp file we requested
            ParcelFileDescriptor tempFile = getContentResolver().openFileDescriptor(
                    freeTempFileUri, "rw");
            OutputStream destinationStream =
                    new ParcelFileDescriptor.AutoCloseOutputStream(tempFile);

            // This is how you get the native fd
            Log.i(LOG_TAG, "Native fd: " + tempFile.getFd());

            // Open the picture we have in our res/raw directory
            InputStream image = getResources().openRawResource(R.raw.s1);

            // Copy it into the temp file in the app's file space (crudely)
            byte[] imageBuffer = new byte[image.available()];
            image.read(imageBuffer);
            destinationStream.write(imageBuffer);
            destinationStream.flush();
        } catch (IOException e) {
            result = MbmsDownloadManager.RESULT_CANCELLED;
        }

        Intent downloadResultIntent =
                new Intent(MbmsDownloadManager.ACTION_DOWNLOAD_RESULT_INTERNAL);
        downloadResultIntent.putExtra(MbmsDownloadManager.EXTRA_REQUEST, request);
        downloadResultIntent.putExtra(MbmsDownloadManager.EXTRA_FINAL_URI, tempFilePathUri);
        ArrayList<Uri> tempFileList = new ArrayList<>(1);
        tempFileList.add(tempFilePathUri);
        downloadResultIntent.getExtras().putParcelableArrayList(
                MbmsDownloadManager.EXTRA_TEMP_LIST, tempFileList);
        downloadResultIntent.putExtra(MbmsDownloadManager.EXTRA_RESULT, result);

        ComponentName mbmsReceiverComponent = new ComponentName(packageName,
                MbmsDownloadReceiver.class.getCanonicalName());
        downloadResultIntent.setComponent(mbmsReceiverComponent);

        sendOrderedBroadcast(downloadResultIntent,
                null, // receiverPermission
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        int resultCode = getResultCode();
                        Log.i(LOG_TAG, "Download result ack: " + resultCode);
                    }
                },
                null, // scheduler
                Activity.RESULT_OK,
                null, // initialData
                null /* initialExtras */);
    }
}
