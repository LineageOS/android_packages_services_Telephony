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

package com.android.phone.testapps.embmsfrontend;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.telephony.MbmsStreamingManager;
import android.telephony.mbms.MbmsException;
import android.telephony.mbms.MbmsStreamingManagerCallback;
import android.widget.Button;
import android.widget.Toast;

public class EmbmsTestStreamingApp extends Activity {
    private MbmsStreamingManagerCallback mStreamingListener = new MbmsStreamingManagerCallback() {};
    private MbmsStreamingManager mStreamingManager;

    private Handler mHandler;
    private HandlerThread mHandlerThread;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mHandlerThread = new HandlerThread("EmbmsSampleFrontendWorker");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());

        Button bindButton = (Button) findViewById(R.id.bind_button);
        bindButton.setOnClickListener((view) ->
            mHandler.post(() -> {
                try {
                    mStreamingManager = MbmsStreamingManager.create(
                            EmbmsTestStreamingApp.this, mStreamingListener, getPackageName());
                } catch (MbmsException e) {
                    EmbmsTestStreamingApp.this.runOnUiThread(() ->
                            Toast.makeText(EmbmsTestStreamingApp.this,
                                    "Init error: " + e.getErrorCode(), Toast.LENGTH_SHORT).show());
                    return;
                }
                EmbmsTestStreamingApp.this.runOnUiThread(() ->
                        Toast.makeText(EmbmsTestStreamingApp.this, "Successfully bound",
                                Toast.LENGTH_SHORT).show());
            })
        );
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mHandlerThread.quit();
    }
}
