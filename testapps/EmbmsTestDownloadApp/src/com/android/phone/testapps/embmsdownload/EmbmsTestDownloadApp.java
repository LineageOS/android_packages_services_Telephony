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

package com.android.phone.testapps.embmsdownload;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.telephony.MbmsDownloadManager;
import android.telephony.mbms.DownloadCallback;
import android.telephony.mbms.DownloadRequest;
import android.telephony.mbms.MbmsDownloadManagerCallback;
import android.telephony.mbms.MbmsException;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;

public class EmbmsTestDownloadApp extends Activity {
    public static final String DOWNLOAD_DONE_ACTION =
            "com.android.phone.testapps.embmsdownload.DOWNLOAD_DONE";

    private static final String TRIGGER_DOWNLOAD_ACTION =
            "com.android.phone.testapps.embmsmw.TRIGGER_DOWNLOAD";
    private static final String EXTRA_DOWNLOAD_REQUEST =
            "com.android.phone.testapps.embmsmw.EXTRA_DOWNLOAD_REQUEST";
    private static final String APP_NAME = "SampleAppName";

    private static EmbmsTestDownloadApp sInstance;

    private MbmsDownloadManagerCallback mCallback = new MbmsDownloadManagerCallback() {};

    private MbmsDownloadManager mDownloadManager;
    private Handler mHandler;
    private HandlerThread mHandlerThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sInstance = this;
        mHandlerThread = new HandlerThread("EmbmsDownloadWorker");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());

        File destination = null;
        try {
            destination = new File(getFilesDir(), "image.png").getCanonicalFile();
        } catch (IOException e) {
            // ignore, this is temp code
        }

        Intent completionIntent = new Intent(DOWNLOAD_DONE_ACTION);
        completionIntent.setClass(this, DownloadCompletionReceiver.class);

        DownloadRequest request = new DownloadRequest.Builder()
                .setId(0)
                .setServiceInfo(null) // TODO: this isn't supposed to be null, but not yet used
                .setSource(null) // ditto
                .setDest(Uri.fromFile(destination))
                .setAppIntent(completionIntent)
                .build();

        Button bindButton = (Button) findViewById(R.id.bind_button);
        bindButton.setOnClickListener((view) -> mHandler.post(() -> {
            try {
                mDownloadManager = MbmsDownloadManager.createManager(this, mCallback, APP_NAME);
            } catch (MbmsException e) {
                Toast.makeText(EmbmsTestDownloadApp.this,
                        "caught MbmsException: " + e.getErrorCode(), Toast.LENGTH_SHORT).show();
            }
        }));

        Button requestDlButton = (Button) findViewById(R.id.request_dl_button);
        requestDlButton.setOnClickListener((view) ->  {
            mDownloadManager.download(request, new DownloadCallback());
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mHandlerThread.quit();
        sInstance = null;
    }

    public static EmbmsTestDownloadApp getInstance() {
        return sInstance;
    }

    // TODO: assumes that process does not get killed. Replace with more robust alternative
    public void onDownloadDone() {
        ImageView picture = (ImageView) findViewById(R.id.sample_picture);
        File imageFile = new File(getFilesDir(), "image.png");
        if (!imageFile.exists()) {
            Toast.makeText(this, "Download done but destination doesn't exist", Toast.LENGTH_SHORT)
                    .show();
            return;
        }
        runOnUiThread(() -> picture.setImageURI(Uri.fromFile(imageFile)));
    }
}
