/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.phone.testapps.satellitetestapp;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.satellite.SatelliteManager;
import android.telephony.satellite.SatelliteStateCallback;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.TextView;

/**
 * Activity related to SatelliteModemState APIs.
 */
public class SatelliteModemState extends Activity {

    private static final String TAG = "SatelliteModemState";

    private SatelliteManager mSatelliteManager;
    private SatelliteStateCallbackTestApp mCallback;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mSatelliteManager = getSystemService(SatelliteManager.class);
        mCallback = new SatelliteStateCallbackTestApp();

        setContentView(R.layout.activity_SatelliteModemState);
        findViewById(R.id.registerForSatelliteModemStateChanged)
                .setOnClickListener(this::registerForSatelliteModemStateChangedApp);
        findViewById(R.id.unregisterForSatelliteModemStateChanged)
                .setOnClickListener(this::unregisterForSatelliteModemStateChangedApp);
        findViewById(R.id.Back).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(SatelliteModemState.this, SatelliteTestApp.class));
            }
        });
    }

    protected static class SatelliteStateCallbackTestApp implements SatelliteStateCallback {
        @Override
        public void onSatelliteModemStateChanged(int state) {
            Log.d(TAG, "onSatelliteModemStateChanged in SatelliteTestApp: state=" + state);
        }
    }

    private void registerForSatelliteModemStateChangedApp(View view) {
        int result = mSatelliteManager.registerForSatelliteModemStateChanged(Runnable::run,
                mCallback);
        TextView textView = findViewById(R.id.text_id);
        if (result == 0) {
            textView.setText("registerForSatelliteModemStateChanged is successful");
        } else {
            textView.setText("Status for registerForSatelliteModemStateChanged : "
                    + SatelliteErrorUtils.mapError(result));
        }
    }

    private void unregisterForSatelliteModemStateChangedApp(View view) {
        mSatelliteManager.unregisterForSatelliteModemStateChanged(mCallback);
        TextView textView = findViewById(R.id.text_id);
        textView.setText("unregisterForSatelliteModemStateChanged is successful");
    }
}

