/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.phone.testapps.telephonymanagertestapp;

import android.app.Activity;
import android.os.Bundle;
import android.telephony.NumberVerificationCallback;
import android.telephony.PhoneNumberRange;
import android.telephony.TelephonyManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class NumberVerificationActivity extends Activity {
    private EditText mCountryCode;
    private EditText mPrefix;
    private EditText mLowerBound;
    private EditText mUpperBound;
    private Button mRequestVerificationButton;
    private TextView mResultField;
    private TelephonyManager mTelephonyManager;

    private NumberVerificationCallback mCallback = new NumberVerificationCallback() {
        @Override
        public void onCallReceived(@NonNull String phoneNumber) {
            mResultField.setText("Received call from " + phoneNumber);
        }

        @Override
        public void onVerificationFailed(int reason) {
            mResultField.setText("Verification failed " + reason);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.number_verification);
        setupEdgeToEdge(this);
        mTelephonyManager = getSystemService(TelephonyManager.class);
        mCountryCode = findViewById(R.id.countryCode);
        mPrefix = findViewById(R.id.prefix);
        mLowerBound = findViewById(R.id.lowerBound);
        mUpperBound = findViewById(R.id.upperBound);
        mRequestVerificationButton = findViewById(R.id.request_verification_button);
        mRequestVerificationButton.setOnClickListener(v -> {
            mTelephonyManager.requestNumberVerification(
                    new PhoneNumberRange(mCountryCode.getText().toString(),
                            mPrefix.getText().toString(), mLowerBound.getText().toString(),
                            mUpperBound.getText().toString()),
                    60000,
                    getMainExecutor(),
                    mCallback
            );
        });
        mResultField = findViewById(R.id.verificationResult);
    }

    /**
     * Given an activity, configure the activity to adjust for edge to edge restrictions.
     *
     * @param activity the activity.
     */
    public static void setupEdgeToEdge(Activity activity) {
        ViewCompat.setOnApplyWindowInsetsListener(activity.findViewById(android.R.id.content),
                (v, windowInsets) -> {
                    Insets insets = windowInsets.getInsets(
                            WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());

                    // Apply the insets paddings to the view.
                    v.setPadding(insets.left, insets.top, insets.right, insets.bottom);

                    // Return CONSUMED if you don't want the window insets to keep being
                    // passed down to descendant views.
                    return WindowInsetsCompat.CONSUMED;
                });
    }
}
