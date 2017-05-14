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
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.telephony.MbmsStreamingManager;
import android.telephony.mbms.MbmsException;
import android.telephony.mbms.MbmsStreamingManagerCallback;
import android.telephony.mbms.StreamingServiceInfo;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class EmbmsTestStreamingApp extends Activity {
    private static final String APP_NAME = "StreamingApp1";

    private MbmsStreamingManagerCallback mStreamingListener = new MbmsStreamingManagerCallback() {
        @Override
        public void streamingServicesUpdated(List<StreamingServiceInfo> services) {
            EmbmsTestStreamingApp.this.runOnUiThread(() ->
                    Toast.makeText(EmbmsTestStreamingApp.this,
                            "Got services length " + services.size(),
                            Toast.LENGTH_SHORT).show());
            updateStreamingServicesList(services);
        }
    };

    private final class StreamingServiceInfoAdapter
            extends ArrayAdapter<StreamingServiceInfo> {
        public StreamingServiceInfoAdapter(Context context, int resource) {
            super(context, resource);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            StreamingServiceInfo info = getItem(position);
            TextView result = new TextView(EmbmsTestStreamingApp.this);
            result.setText(info.getClassName());
            return result;
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            StreamingServiceInfo info = getItem(position);
            TextView result = new TextView(EmbmsTestStreamingApp.this);
            String text = "classname="
                    + info.getClassName()
                    + ", "
                    + "serviceId="
                    + info.getServiceId();
            result.setText(text);
            return result;
        }

        public void update(List<StreamingServiceInfo> services) {
            clear();
            addAll(services);
        }
    }

    private MbmsStreamingManager mStreamingManager = null;

    private Handler mHandler;
    private HandlerThread mHandlerThread;

    private StreamingServiceInfoAdapter mStreamingServicesDisplayAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mHandlerThread = new HandlerThread("EmbmsSampleFrontendWorker");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mStreamingServicesDisplayAdapter =
                new StreamingServiceInfoAdapter(this, android.R.layout.simple_spinner_item);

        Button bindButton = (Button) findViewById(R.id.bind_button);
        bindButton.setOnClickListener((view) ->
            mHandler.post(() -> {
                try {
                    mStreamingManager = MbmsStreamingManager.create(
                            EmbmsTestStreamingApp.this, mStreamingListener, APP_NAME);
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

        Button getStreamingServicesButton = (Button)
                findViewById(R.id.get_streaming_services_button);
        getStreamingServicesButton.setOnClickListener((view) -> {
            if (mStreamingManager == null) {
                Toast.makeText(EmbmsTestStreamingApp.this,
                        "No streaming service bound", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                mStreamingManager.getStreamingServices(null);
            } catch (MbmsException e) {
                Toast.makeText(EmbmsTestStreamingApp.this,
                        "Error getting streaming services" + e.getErrorCode(),
                        Toast.LENGTH_SHORT).show();
            }
        });

        Spinner serviceSelector = (Spinner) findViewById(R.id.available_streaming_services);
        mStreamingServicesDisplayAdapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        serviceSelector.setAdapter(mStreamingServicesDisplayAdapter);
        serviceSelector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                StreamingServiceInfo info =
                        (StreamingServiceInfo) serviceSelector.getItemAtPosition(position);
                Toast.makeText(EmbmsTestStreamingApp.this,
                        "Service selected: " + info.getClassName(), Toast.LENGTH_SHORT).show();
                // TODO: use this value for start streaming
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mHandlerThread.quit();
    }

    private void updateStreamingServicesList(List<StreamingServiceInfo> services) {
        runOnUiThread(() -> mStreamingServicesDisplayAdapter.update(services));
    }
}
