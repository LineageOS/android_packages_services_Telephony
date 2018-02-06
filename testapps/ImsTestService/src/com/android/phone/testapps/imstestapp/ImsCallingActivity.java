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
 * limitations under the License.
 */

package com.android.phone.testapps.imstestapp;

import android.app.Activity;
import android.os.Bundle;
import android.telephony.ims.internal.feature.MmTelFeature;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.Toast;

public class ImsCallingActivity extends Activity {

    //Capabilities available by service
    private CheckBox mCapVoiceAvailBox;
    private CheckBox mCapVideoAvailBox;
    private CheckBox mCapUtAvailBox;
    private CheckBox mCapSmsAvailBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_calling);

        TestMmTelFeatureImpl.getInstance().setContext(this);

        mCapVoiceAvailBox = findViewById(R.id.call_cap_voice);
        mCapVideoAvailBox = findViewById(R.id.call_cap_video);
        mCapUtAvailBox = findViewById(R.id.call_cap_ut);
        mCapSmsAvailBox = findViewById(R.id.call_cap_sms);
        Button capChangedButton = findViewById(R.id.call_cap_change);
        capChangedButton.setOnClickListener((v) -> onCapabilitiesChangedClicked());
    }

    private void onCapabilitiesChangedClicked() {
        if (!isFrameworkConnected()) {
            return;
        }
        boolean isVoiceAvail = mCapVoiceAvailBox.isChecked();
        boolean isVideoAvail = mCapVideoAvailBox.isChecked();
        boolean isUtAvail = mCapUtAvailBox.isChecked();
        // Not used yet
        boolean isSmsAvail = mCapSmsAvailBox.isChecked();

        MmTelFeature.MmTelCapabilities capabilities = new MmTelFeature.MmTelCapabilities();
        if (isVoiceAvail) {
            capabilities.addCapabilities(MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VOICE);
        }
        if (isVideoAvail) {
            capabilities.addCapabilities(MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VIDEO);
        }
        if (isUtAvail) {
            capabilities.addCapabilities(MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_UT);
        }
        TestMmTelFeatureImpl.getInstance().sendCapabilitiesUpdate(capabilities);
    }

    private boolean isFrameworkConnected() {
        if (!TestMmTelFeatureImpl.getInstance().isReady()) {
            Toast.makeText(this, "Connection to Framework Unavailable", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }
}
