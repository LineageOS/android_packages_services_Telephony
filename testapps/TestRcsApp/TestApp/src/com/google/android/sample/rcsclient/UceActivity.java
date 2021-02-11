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

package com.google.android.sample.rcsclient;

import android.net.Uri;
import android.os.Bundle;
import android.telephony.SmsManager;
import android.telephony.ims.ImsException;
import android.telephony.ims.ImsManager;
import android.telephony.ims.ImsRcsManager;
import android.telephony.ims.RcsContactPresenceTuple;
import android.telephony.ims.RcsContactUceCapability;
import android.telephony.ims.RcsUceAdapter;
import android.text.TextUtils;
import android.util.Log;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** An activity to verify UCE. */
public class UceActivity extends AppCompatActivity {

    private static final String TAG = "TestRcsApp.UceActivity";
    private final ExecutorService mExecutorService = Executors.newSingleThreadExecutor();
    private Button mCapabilityButton;
    private Button mAvailabilityButton;
    private TextView mCapabilityResult;
    private TextView mAvailabilityResult;
    private EditText mNumbers;
    private int mDefaultSmsSubId;
    private ImsRcsManager mImsRcsManager;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.uce_layout);

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setDisplayShowHomeEnabled(true);

        initLayout();
    }

    private void initLayout() {
        mDefaultSmsSubId = SmsManager.getDefaultSmsSubscriptionId();

        mCapabilityButton = findViewById(R.id.capability_btn);
        mAvailabilityButton = findViewById(R.id.availability_btn);
        mCapabilityResult = findViewById(R.id.capability_result);
        mAvailabilityResult = findViewById(R.id.capability_result);

        List<Uri> contactList = getContectList();
        mImsRcsManager = getImsRcsManager(mDefaultSmsSubId);
        mCapabilityButton.setOnClickListener(view -> {
            if (contactList.size() == 0) {
                Log.i(TAG, "empty contact list");
                return;
            }
            mCapabilityResult.setText("pending...\n");
            try {
                mImsRcsManager.getUceAdapter().requestCapabilities(contactList, mExecutorService,
                        new RcsUceAdapter.CapabilitiesCallback() {
                            public void onCapabilitiesReceived(
                                    List<RcsContactUceCapability> contactCapabilities) {
                                Log.i(TAG, "onCapabilitiesReceived()");
                                StringBuilder b = new StringBuilder("onCapabilitiesReceived:\n");
                                for (RcsContactUceCapability c : contactCapabilities) {
                                    b.append(getReadableCapability(c));
                                    b.append("\n");
                                }
                                mCapabilityResult.append(b.toString() + "\n");
                            }

                            public void onComplete() {
                                Log.i(TAG, "onComplete()");
                                mCapabilityResult.append("complete");

                            }

                            public void onError(int errorCode, long retryAfterMilliseconds) {
                                Log.i(TAG, "onError() errorCode:" + errorCode + " retryAfterMs:"
                                        + retryAfterMilliseconds);
                                mCapabilityResult.append("error - errorCode:" + errorCode
                                        + " retryAfterMs:" + retryAfterMilliseconds);
                            }
                        });
            } catch (ImsException e) {
                mCapabilityResult.setText("ImsException:" + e);
            }
        });

        mAvailabilityButton.setOnClickListener(view -> {
            if (contactList.size() == 0) {
                Log.i(TAG, "empty contact list");
                return;
            }
            mAvailabilityResult.setText("pending...\n");
            try {
                mImsRcsManager.getUceAdapter().requestAvailability(contactList.get(0),
                        mExecutorService, new RcsUceAdapter.CapabilitiesCallback() {
                            public void onCapabilitiesReceived(
                                    List<RcsContactUceCapability> contactCapabilities) {
                                Log.i(TAG, "onCapabilitiesReceived()");
                                StringBuilder b = new StringBuilder("onCapabilitiesReceived:\n");
                                for (RcsContactUceCapability c : contactCapabilities) {
                                    b.append(getReadableCapability(c));
                                    b.append("\n");
                                }
                                mAvailabilityResult.append(b.toString() + "\n");
                            }

                            public void onComplete() {
                                Log.i(TAG, "onComplete()");
                                mAvailabilityResult.append("complete");

                            }

                            public void onError(int errorCode, long retryAfterMilliseconds) {
                                Log.i(TAG, "onError() errorCode:" + errorCode + " retryAfterMs:"
                                        + retryAfterMilliseconds);
                                mAvailabilityResult.append("error - errorCode:" + errorCode
                                        + " retryAfterMs:" + retryAfterMilliseconds);
                            }
                        });
            } catch (ImsException e) {
                mAvailabilityResult.setText("ImsException:" + e);
            }
        });
    }

    private List<Uri> getContectList() {
        mNumbers = findViewById(R.id.number_list);
        String []numbers;
        ArrayList<Uri> contactList = new ArrayList<>();
        if (!TextUtils.isEmpty(mNumbers.getText().toString())) {
            String numberList = mNumbers.getText().toString().trim();
            numbers = numberList.split(",");
            for (String number : numbers) {
                contactList.add(Uri.parse(ChatActivity.TELURI_PREFIX + number));
            }
        }

        return contactList;
    }

    private ImsRcsManager getImsRcsManager(int subId) {
        ImsManager imsManager = getSystemService(ImsManager.class);
        if (imsManager != null) {
            try {
                return imsManager.getImsRcsManager(subId);
            } catch (Exception e) {
                Log.e(TAG, "fail to getImsRcsManager " + e.getMessage());
                return null;
            }
        }
        return null;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
        }
        return super.onOptionsItemSelected(item);
    }

    private String getReadableCapability(RcsContactUceCapability c) {
        StringBuilder b = new StringBuilder("RcsContactUceCapability: uri=");
        b.append(c.getContactUri());
        b.append(", requestResult=");
        b.append(c.getRequestResult());
        b.append(", sourceType=");
        b.append(c.getSourceType());
        if (c.getCapabilityMechanism() == RcsContactUceCapability.CAPABILITY_MECHANISM_PRESENCE) {
            b.append(", tuples={");
            for (RcsContactPresenceTuple t : c.getCapabilityTuples()) {
                b.append("[uri=");
                b.append(t.getContactUri());
                b.append(", serviceId=");
                b.append(t.getServiceId());
                b.append(", serviceVersion=");
                b.append(t.getServiceVersion());
                if (t.getServiceCapabilities() != null) {
                    RcsContactPresenceTuple.ServiceCapabilities servCaps =
                            t.getServiceCapabilities();
                    b.append(", servCaps=(supported=");
                    b.append(servCaps.getSupportedDuplexModes());
                    b.append("), servCaps=(unsupported=");
                    b.append(servCaps.getUnsupportedDuplexModes());
                    b.append("))");
                }
                b.append("]");
            }
            b.append("}");
        }
        return b.toString();
    }
}
