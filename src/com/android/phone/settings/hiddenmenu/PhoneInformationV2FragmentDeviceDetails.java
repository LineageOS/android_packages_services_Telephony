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
import android.content.res.Resources;
import android.os.Bundle;
import android.os.UserManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class PhoneInformationV2FragmentDeviceDetails extends Fragment {

  private final static String TAG = "PhoneInformationV2 DeviceDetails";
  private static final int DEFAULT_PHONE_ID = 0;

  private Context context;
  private Resources r;
  private TelephonyManager mTelephonyManager;

  private Phone mPhone = null;
  private List<LinearLayout> phoneButtons = new ArrayList<>();
  private boolean mSystemUser = true;
  private int mPhoneId = SubscriptionManager.INVALID_PHONE_INDEX;
  private int mSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;

  // Layout elements
  private LinearLayout mPhoneButton0;
  private LinearLayout mPhoneButton1;
  private TextView mDeviceId; //DeviceId is the IMEI in GSM and the MEID in CDMA
  private TextView mLine1Number; // Phone number
  private TextView mSubscriptionId; // Subscription id
  private TextView mDds; // Default data subscription
  private TextView mSubscriberId; // IMSI
  // End layout elements

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
      @Nullable Bundle savedInstanceState) {
    View view = inflater.inflate(R.layout.phone_information_v2_tab_device_details, container,
        false);
    return view;
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    context = requireContext();
    r = context.getResources();

    mSystemUser = android.os.Process.myUserHandle().isSystem();
    log("onCreate: mSystemUser=" + mSystemUser);
    UserManager userManager = context.getSystemService(UserManager.class);
    if (userManager != null
        && userManager.hasUserRestriction(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS)) {
      logw("User is restricted from configuring mobile networks.");
      return;
    }

    if (mSystemUser) {
      mPhone = getPhone(SubscriptionManager.getDefaultSubscriptionId());
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

    mTelephonyManager = context.getSystemService(TelephonyManager.class)
        .createForSubscriptionId(mSubId);

    mPhoneButton0 = (LinearLayout) view.findViewById(R.id.phone_button_0);
    mPhoneButton1 = (LinearLayout) view.findViewById(R.id.phone_button_1);
    mDeviceId = (TextView) view.findViewById(R.id.imei);
    mLine1Number = (TextView) view.findViewById(R.id.number);
    mSubscriptionId = (TextView) view.findViewById(R.id.subid);
    mDds = (TextView) view.findViewById(R.id.dds);
    mSubscriberId = (TextView) view.findViewById(R.id.imsi);

    phoneButtons.add(mPhoneButton0);
    phoneButtons.add(mPhoneButton1);

    setupOnClickListeners();
    highlightInitialPhoneButton();
  }

  @Override
  public void onPause() {
    super.onPause();
    log("onPause");
  }

  @Override
  public void onResume() {
    super.onResume();
    log("onResume");
    updateAllFields();
  }

  private void highlightInitialPhoneButton() {
    int initialSelectedButtonId = (mPhoneId == 0) ? R.id.phone_button_0 : R.id.phone_button_1;
    int phoneCount = context.getSystemService(TelephonyManager.class).getActiveModemCount();
    if ((mPhoneId == 0 && phoneCount >= 1) || (mPhoneId == 1 && phoneCount >= 2)) {
      log("Calling updateSelectionVisuals for initial setup. PhoneId: " + mPhoneId);
      updateSelectionVisuals(initialSelectedButtonId);
    } else if (phoneCount >= 1) {
      log("Initial phoneId " + mPhoneId + " invalid/unavailable for count " + phoneCount
          + ", defaulting visual selection and data to Phone 0");
      mPhoneId = 0;
      updateSelectionVisuals(R.id.phone_button_0);
    } else {
      log("No active modems found. Not setting initial selection or updating fields.");
    }
  }

  private void updateSelectionVisuals(int selectedItemId) {
    int selectedColor = ContextCompat.getColor(context, android.R.color.holo_green_dark);
    int defaultColor = ContextCompat.getColor(context, android.R.color.darker_gray);

    for (LinearLayout phoneButton : phoneButtons) {
      TextView title = null;

      if (phoneButton.getId() == R.id.phone_button_0) {
        title = phoneButton.findViewById(R.id.phone_button_0_title);
      } else if (phoneButton.getId() == R.id.phone_button_1) {
        title = phoneButton.findViewById(R.id.phone_button_1_title);
      }

      if (phoneButton.getId() == selectedItemId) {
        if (title != null) {
          title.setTextColor(selectedColor);
        }
        if (phoneButton.getId() == R.id.phone_button_0) {
          mPhoneId = 0;
        } else if (phoneButton.getId() == R.id.phone_button_1) {
          mPhoneId = 1;
        }
        log("Updated phone id to " + mPhoneId + ", sub id to " + mSubId);
        mSubId = SubscriptionManager.getSubscriptionId(mPhoneId);
        updatePhoneIndex();
      } else {
        if (title != null) {
          title.setTextColor(defaultColor);
        }
      }
    }
  }

  private void setupOnClickListeners() {
    View.OnClickListener phoneButtonClickListener = new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        updateSelectionVisuals(v.getId());
      }
    };

    mPhoneButton0.setOnClickListener(phoneButtonClickListener);
    mPhoneButton1.setOnClickListener(phoneButtonClickListener);
  }

  private void updatePhoneIndex() {
    // update the subId
    mTelephonyManager = mTelephonyManager.createForSubscriptionId(mSubId);

    // update the phoneId
    if (mSystemUser) {
      mPhone = PhoneFactory.getPhone(mPhoneId);
    }

    updateAllFields();
  }

  private void updateAllFields() {
    log("Updating all fields");
    updateProperties();
    updateSubscriptionIds();
  }

  private Phone getPhone(int subId) {
    log("getPhone subId = " + subId);
    Phone phone = PhoneFactory.getPhone(SubscriptionManager.getPhoneId(subId));
    if (phone == null) {
      log("return the default phone");
      return PhoneFactory.getDefaultPhone();
    }

    return phone;
  }

  private void updateSubscriptionIds() {
    mSubscriptionId.setText(String.format(Locale.ROOT, "%d", mSubId));
    mDds.setText(
        String.format(Locale.ROOT, "%d", SubscriptionManager.getDefaultDataSubscriptionId()));
  }

  private void updateProperties() {
    String s;
    s = mTelephonyManager.getImei(mPhoneId);
    mDeviceId.setText(s);

    s = mTelephonyManager.getSubscriberId();
    if (s == null || !SubscriptionManager.isValidSubscriptionId(mSubId)) {
      s = r.getString(R.string.radioInfo_unknown);
    }

    mSubscriberId.setText(s);

    SubscriptionManager subMgr = context.getSystemService(SubscriptionManager.class);
    int subId = mSubId;
    s = subMgr.getPhoneNumber(subId)
        + " { CARRIER:"
        + subMgr.getPhoneNumber(subId, SubscriptionManager.PHONE_NUMBER_SOURCE_CARRIER)
        + ", UICC:"
        + subMgr.getPhoneNumber(subId, SubscriptionManager.PHONE_NUMBER_SOURCE_UICC)
        + ", IMS:"
        + subMgr.getPhoneNumber(subId, SubscriptionManager.PHONE_NUMBER_SOURCE_IMS)
        + " }";
    mLine1Number.setText(s);
  }

  private static void log(String s) {
    Log.d(TAG, s);
  }

  private static void logw(String s) {
    Log.w(TAG, s);
  }
}