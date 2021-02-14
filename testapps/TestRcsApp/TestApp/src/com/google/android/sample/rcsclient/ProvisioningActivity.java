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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.telephony.SmsManager;
import android.telephony.SubscriptionManager;
import android.telephony.ims.ImsException;
import android.telephony.ims.ProvisioningManager;
import android.telephony.ims.ProvisioningManager.RcsProvisioningCallback;
import android.telephony.ims.RcsClientConfiguration;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/** An activity to verify provisioning. */
public class ProvisioningActivity extends AppCompatActivity {

    private static final String TAG = "TestRcsApp.ProvisioningActivity";
    private static final int MSG_RESULT = 1;
    private final ExecutorService mExecutorService = Executors.newSingleThreadExecutor();
    private int mDefaultSmsSubId;
    private ProvisioningManager mProvisioningManager;
    private Button mRegisterButton;
    private Button mUnRegisterButton;
    private Button mIsCapableButton;
    private TextView mSingleRegResult;
    private TextView mCallbackResult;
    private SingleRegCapabilityReceiver mSingleRegCapabilityReceiver;
    private boolean mIsRegistered = false;
    private Handler mHandler;
    private RcsProvisioningCallback mCallback =
            new RcsProvisioningCallback() {
                @Override
                public void onConfigurationChanged(@NonNull byte[] configXml) {
                    String configResult = new String(configXml);
                    Log.i(TAG, "RcsProvisioningCallback.onConfigurationChanged called with xml:");
                    Log.i(TAG, configResult);
                    mHandler.sendMessage(mHandler.obtainMessage(MSG_RESULT,
                            "onConfigurationChanged \r\n" + configResult));
                }

                @Override
                public void onConfigurationReset() {
                    Log.i(TAG, "RcsProvisioningCallback.onConfigurationReset called.");
                    mHandler.sendMessage(
                            mHandler.obtainMessage(MSG_RESULT, "onConfigurationReset"));
                }

                @Override
                public void onRemoved() {
                    Log.i(TAG, "RcsProvisioningCallback.onRemoved called.");
                    mHandler.sendMessage(mHandler.obtainMessage(MSG_RESULT, "onRemoved"));
                }
            };

    // Static configuration.
    private static RcsClientConfiguration getDefaultClientConfiguration() {
        return new RcsClientConfiguration(
                /*rcsVersion=*/ "6.0",
                /*rcsProfile=*/ "UP_1.0",
                /*clientVendor=*/ "Goog",
                /*clientVersion=*/ "RCSAndrd-1.0");
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.provision_layout);

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setDisplayShowHomeEnabled(true);
        mSingleRegCapabilityReceiver = new SingleRegCapabilityReceiver();
        this.registerReceiver(mSingleRegCapabilityReceiver, new IntentFilter(
                ProvisioningManager.ACTION_RCS_SINGLE_REGISTRATION_CAPABILITY_UPDATE));
        mHandler = new Handler() {
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MSG_RESULT:
                        mCallbackResult.setText(message.obj.toString());
                        break;
                }
            }
        };
    }

    @Override
    protected void onStart() {
        super.onStart();
        mDefaultSmsSubId = SmsManager.getDefaultSmsSubscriptionId();
        Log.i(TAG, "defaultSmsSubId:" + mDefaultSmsSubId);
        if (isValidSubscriptionId(mDefaultSmsSubId)) {
            mProvisioningManager = ProvisioningManager.createForSubscriptionId(mDefaultSmsSubId);
            init();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        this.unregisterReceiver(mSingleRegCapabilityReceiver);
        if (mIsRegistered) {
            mProvisioningManager.unregisterRcsProvisioningChangedCallback(mCallback);
        }
    }

    private void init() {
        mRegisterButton = findViewById(R.id.provisioning_register_btn);
        mUnRegisterButton = findViewById(R.id.provisioning_unregister_btn);
        mIsCapableButton = findViewById(R.id.provisioning_singlereg_btn);
        mSingleRegResult = findViewById(R.id.provisioning_singlereg_result);
        mCallbackResult = findViewById(R.id.provisioning_callback_result);
        mCallbackResult.setMovementMethod(new ScrollingMovementMethod());

        boolean isSingleRegCapable = false;
        try {
            mProvisioningManager.isRcsVolteSingleRegistrationCapable();
        } catch (ImsException e) {
            Log.i(TAG, e.getMessage());
        }
        if (isSingleRegCapable && !mIsRegistered) {
            setClickable(mRegisterButton, true);
        }

        mRegisterButton.setOnClickListener(view -> {
            if (mProvisioningManager != null) {
                Log.i(TAG, "Using configuration: " + getDefaultClientConfiguration());
                try {
                    Log.i(TAG, "setRcsClientConfiguration()");
                    Log.i(TAG, "registerRcsProvisioningChangedCallback()");
                    mProvisioningManager.setRcsClientConfiguration(getDefaultClientConfiguration());
                    mProvisioningManager.registerRcsProvisioningChangedCallback(mExecutorService,
                            mCallback);
                    mIsRegistered = true;
                } catch (ImsException e) {
                    Log.e(TAG, e.getMessage());
                }
                setClickable(mRegisterButton, false);
                setClickable(mUnRegisterButton, true);
            } else {
                Log.i(TAG, "provisioningManager null");
            }
        });
        mUnRegisterButton.setOnClickListener(view -> {
            if (mProvisioningManager != null) {
                mProvisioningManager.unregisterRcsProvisioningChangedCallback(mCallback);
                setClickable(mRegisterButton, false);
                setClickable(mRegisterButton, true);
                mIsRegistered = false;
            }
        });
        mIsCapableButton.setOnClickListener(view -> {
            if (mProvisioningManager != null) {
                try {
                    boolean capable = mProvisioningManager.isRcsVolteSingleRegistrationCapable();
                    mSingleRegResult.setText(String.valueOf(capable));
                    Log.i(TAG, "isRcsVolteSingleRegistrationCapable:" + capable);
                } catch (ImsException e) {
                    Log.e(TAG, e.getMessage());
                }
            }
        });
    }

    private void setClickable(Button button, boolean clickable) {
        if (clickable) {
            button.setAlpha(1);
            button.setClickable(true);
        } else {
            button.setAlpha(.5f);
            button.setClickable(false);
        }
    }

    private boolean isValidSubscriptionId(int subId) {
        return SubscriptionManager.isValidSubscriptionId(mDefaultSmsSubId);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
        }
        return super.onOptionsItemSelected(item);
    }

    class SingleRegCapabilityReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.i(TAG, "onReceive action:" + action);
            if (ProvisioningManager.ACTION_RCS_SINGLE_REGISTRATION_CAPABILITY_UPDATE.equals(
                    action)) {
                int status = intent.getIntExtra(ProvisioningManager.EXTRA_STATUS,
                        ProvisioningManager.STATUS_DEVICE_NOT_CAPABLE);
                Log.i(TAG, "singleRegCap status:" + status);
                if (mRegisterButton != null && !mIsRegistered) {
                    if (status == ProvisioningManager.STATUS_DEVICE_NOT_CAPABLE) {
                        setClickable(mRegisterButton, true);
                    } else {
                        setClickable(mRegisterButton, false);
                    }
                }

            }
        }
    }

}
