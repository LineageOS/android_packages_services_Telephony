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
package com.android.phone.settings.hiddenmenu;


import android.content.Context;
import android.os.Bundle;
import android.os.HandlerThread;
import android.telephony.AccessNetworkConstants;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.ims.ImsManager;
import android.telephony.ims.ImsMmTelManager;
import android.telephony.ims.feature.MmTelFeature;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.phone.R;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class PhoneInformationV2FragmentIms extends Fragment {
    private static final String TAG = "PhoneInformationV2 Ims";
    private PhoneInformationV2PhoneId listener;
    private TelephonyManager mTelephonyManager;
    private Context mContext;
    private boolean mSystemUser = true;
    private Phone mPhone = null;
    private ImsManager mImsManager = null;
    private LinearLayout phoneButton0, phoneButton1;
    private TextView phoneTitle0;
    private TextView phoneTitle1;
    private TextView mImsRegistration;
    private TextView mVoiceOverLte;
    private TextView mVoiceOverWiFi;
    private TextView mVideoCalling;
    private TextView mUtInterface;
    private int mPhoneId = SubscriptionManager.INVALID_PHONE_INDEX;
    private int mSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    private static final int DEFAULT_PHONE_ID = 0;
    private static String[] sPhoneIndexLabels = new String[0];
    private final String REGISTERED = "Registered";
    private final String NOT_REGISTERED = "Not Registered";
    private final String AVAILABLE = "Available";
    private final String UNAVAILABLE = "Unavailable";

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        // Ensure the host activity implements the callback interface
        if (context instanceof PhoneInformationV2PhoneId) {
            listener = (PhoneInformationV2PhoneId) context;
        } else {
            throw new RuntimeException(context.toString() + " must implement SharedValueListener");
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        log("onCreate");
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.phone_information_v2_tab_ims, container, false);
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        log("on View Created");
        mContext = requireContext();

        mSystemUser = android.os.Process.myUserHandle().isSystem();
        log("onCreate: mSystemUser=" + mSystemUser);

        if (mSystemUser) {
            mPhone = PhoneInformationUtil.getPhone(SubscriptionManager.getDefaultSubscriptionId());
        }
        mSubId = SubscriptionManager.getDefaultSubscriptionId();
        if (mPhone != null) {
            mPhoneId = mPhone.getPhoneId();
        } else {
            mPhoneId = SubscriptionManager.getPhoneId(mSubId);
        }
        if (!SubscriptionManager.isValidPhoneId(mPhoneId)) {
            mPhoneId = DEFAULT_PHONE_ID;
        }
        if (listener != null) {
            mPhoneId = listener.getPhoneId();
            mSubId = SubscriptionManager.getSubscriptionId(mPhoneId);
        }

        mTelephonyManager =
                mContext.getSystemService(TelephonyManager.class).createForSubscriptionId(mSubId);

        sPhoneIndexLabels = PhoneInformationUtil.getPhoneIndexLabels(mTelephonyManager);

        phoneButton0 = view.findViewById(R.id.phone_button_0);
        phoneTitle0 = view.findViewById(R.id.phone_button_0_title);

        phoneButton1 = view.findViewById(R.id.phone_button_1);
        phoneTitle1 = view.findViewById(R.id.phone_button_1_title);

        PhoneInformationUtil.configurePhoneSelectionUi(phoneButton0, phoneButton1, phoneTitle0,
                phoneTitle1, sPhoneIndexLabels);

        mImsManager = new ImsManager(mContext);

        mImsRegistration = view.findViewById(R.id.ims_registration);
        mVoiceOverLte = view.findViewById(R.id.voice_over_lte);
        mVoiceOverWiFi = view.findViewById(R.id.voice_over_wifi);
        mVideoCalling = view.findViewById(R.id.video_calling);
        mUtInterface = view.findViewById(R.id.ut_interface);

        View.OnClickListener selectionListener =
                clickedView -> {
                    int targetPhoneId = -1;
                    if (clickedView.getId() == R.id.phone_button_0) {
                        targetPhoneId = 0;
                    } else if (clickedView.getId() == R.id.phone_button_1) {
                        targetPhoneId = 1;
                    }

                    log("targetPhoneId: " + targetPhoneId + ", mPhoneId: " + mPhoneId);

                    if (targetPhoneId != -1) {
                        // Validation
                        if (mTelephonyManager.getActiveModemCount() > targetPhoneId) {
                            // Check if selection actually changed to avoid redundant work
                            if (mPhoneId != targetPhoneId) {
                                mPhoneId = targetPhoneId;
                                listener.setPhoneId(mPhoneId);
                                try {
                                    mSubId = SubscriptionManager.getSubscriptionId(mPhoneId);
                                    log(
                                            "Updated phone id to "
                                                    + mPhoneId
                                                    + ", sub id to "
                                                    + mSubId);
                                    updatePhoneIndex();
                                } catch (SecurityException e) {
                                    log("Permission error getting subId for phoneId " + mPhoneId);
                                }
                            }
                        } else {
                            log(
                                    "Selected phone index "
                                            + targetPhoneId
                                            + " is not active/available.");
                        }
                    }
                };
        phoneButton0.setOnClickListener(selectionListener);
        phoneButton1.setOnClickListener(selectionListener);
    }

    @Override
    public void onResume() {
        super.onResume();

        log("Started onResume");

        updateAllFields();
    }

    private void updatePhoneIndex() {
        log("updatePhoneIndex");
        // update the subId
        mTelephonyManager = mTelephonyManager.createForSubscriptionId(mSubId);

        // update the phoneId
        if (mSystemUser) {
            mPhone = PhoneFactory.getPhone(mPhoneId);
        }
        mImsManager = new ImsManager(mContext);
        updateAllFields();
    }

    private void updateSelectionVisuals() {
        LinearLayout selectedButton, unSelectedButton;
        if (mPhoneId == 0) {
            selectedButton = phoneButton0;
            unSelectedButton = phoneButton1;
        } else {
            selectedButton = phoneButton1;
            unSelectedButton = phoneButton0;
        }
        selectedButton.setBackgroundColor(
                ContextCompat.getColor(mContext, android.R.color.holo_green_dark));
        unSelectedButton.setBackgroundColor(
                ContextCompat.getColor(mContext, android.R.color.darker_gray));
    }

    private void updateAllFields() {
        boolean isSimValid = SubscriptionManager.isValidSubscriptionId(mSubId);
        boolean isImsRegistered = isSimValid && mTelephonyManager.isImsRegistered();
        boolean availableVolte = false;
        boolean availableWfc = false;
        boolean availableVt = false;
        AtomicBoolean availableUt = new AtomicBoolean(false);

        if (isSimValid) {
            ImsMmTelManager imsMmTelManager = mImsManager.getImsMmTelManager(mSubId);
            availableVolte = PhoneInformationUtil.isVoiceServiceAvailable(imsMmTelManager);
            availableVt = PhoneInformationUtil.isVideoServiceAvailable(imsMmTelManager);
            availableWfc = PhoneInformationUtil.isWfcServiceAvailable(imsMmTelManager);
            CountDownLatch latch = new CountDownLatch(1);
            try {
                HandlerThread handlerThread = new HandlerThread("PhoneInfoIms");
                handlerThread.start();
                imsMmTelManager.isSupported(
                        MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_UT,
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                        handlerThread.getThreadExecutor(),
                        (result) -> {
                            latch.countDown();
                            availableUt.set(result);
                        });
                latch.await(2, TimeUnit.SECONDS);
                handlerThread.quit();
            } catch (Exception e) {
                log("Failed to get UT state.");
            }
        }

        updateSelectionVisuals();

        mImsRegistration.setText((isImsRegistered ? REGISTERED : NOT_REGISTERED));
        mVoiceOverLte.setText((availableVolte ? AVAILABLE : UNAVAILABLE));
        mVoiceOverWiFi.setText((availableWfc ? AVAILABLE : UNAVAILABLE));
        mVideoCalling.setText((availableVt ? AVAILABLE : UNAVAILABLE));
        mUtInterface.setText((availableUt.get() ? AVAILABLE : UNAVAILABLE));
    }

    private static void log(String s) {
        Log.d(TAG, s);
    }
}
