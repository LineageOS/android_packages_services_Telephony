/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.phone.assisteddialing;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.preference.ListPreference;
import android.preference.SwitchPreference;
import android.telephony.TelephonyManager;

import com.android.phone.TelephonyRobolectricTestRunner;
import com.android.phone.settings.assisteddialing.AssistedDialingSettingFragment;
import com.android.phone.settings.assisteddialing.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.FragmentController;
import org.robolectric.shadows.ShadowTelephonyManager;

/**
 * Unit Tests for AssistedDialingSettingFragmentTest.
 */
@RunWith(TelephonyRobolectricTestRunner.class)
public class AssistedDialingSettingFragmentTest {

  private final Context mContext = RuntimeEnvironment.application;
  private final TelephonyManager mTelephonyManager =
      (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
  private final ShadowTelephonyManager mShadowTelephonyManager =
      Shadows.shadowOf(mTelephonyManager);
  private FragmentController<AssistedDialingSettingFragment> mFragment;

  @Before
  public void setupTest() {
    mFragment =
        Robolectric.buildFragment(AssistedDialingSettingFragment.class)
            .create()
            .start()
            .resume()
            .visible();
  }

  @Test
  public void testPrefOnByDefault() {
    final String key = mContext.getString(R.string.assisted_dialing_setting_toggle_key);
    final SwitchPreference pref = (SwitchPreference) mFragment.get().findPreference(key);
    assertThat(pref.isChecked()).isTrue();
  }

  @Test
  public void testPrefOff_ConcreteCreatorYieldsStub() {
    final String key = mContext.getString(R.string.assisted_dialing_setting_toggle_key);
    final SwitchPreference pref = (SwitchPreference) mFragment.get().findPreference(key);
    pref.setChecked(false);
    assertThat(pref.isChecked()).isFalse();
    assertThat(ConcreteCreator.createNewAssistedDialingMediator(mTelephonyManager, mContext))
        .isInstanceOf(AssistedDialingMediatorStub.class);
  }

  @Test
  public void testPrefOn_ConcreteCreatorYieldsImpl() {
    final String key = mContext.getString(R.string.assisted_dialing_setting_toggle_key);
    final SwitchPreference pref = (SwitchPreference) mFragment.get().findPreference(key);
    pref.setChecked(true);
    assertThat(pref.isChecked()).isTrue();
    assertThat(ConcreteCreator.createNewAssistedDialingMediator(mTelephonyManager, mContext))
        .isInstanceOf(AssistedDialingMediatorImpl.class);
  }

  @Test
  public void testCountryCodePrefKeyAndValueAlwaysOneToOne() {
    final String key = mContext.getString(R.string.assisted_dialing_setting_cc_key);
    final ListPreference pref = (ListPreference) mFragment.get().findPreference(key);
    assertThat(pref.getEntries().length).isEqualTo(pref.getEntryValues().length);
  }

  @Test
  public void testCountryCodePrefDefaultValueIsFirstValue() {
    final String key = mContext.getString(R.string.assisted_dialing_setting_cc_key);
    final ListPreference pref = (ListPreference) mFragment.get().findPreference(key);
    final String fallback =
        mContext.getString(R.string.assisted_dialing_setting_cc_default_summary_fallback);
    assertThat(pref.getEntries()[0]).isEqualTo(fallback);
    assertThat(pref.getEntryValues()[0]).isEqualTo("");
  }

  @Test
  public void testAutomaticallyDetectedHomeCountryIsDisplayed() {
    // User home country
    mShadowTelephonyManager.setSimCountryIso(
        AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_STATES);
    mFragment =
        Robolectric.buildFragment(AssistedDialingSettingFragment.class)
            .create()
            .start()
            .resume()
            .visible();
    final String key = mContext.getString(R.string.assisted_dialing_setting_cc_key);
    final ListPreference pref = (ListPreference) mFragment.get().findPreference(key);
    final String usDefault = mContext.getString(
        R.string.assisted_dialing_setting_cc_default_summary,
        "United States (+1)");
    assertThat(pref.getSummary()).isEqualTo(usDefault);
  }
}
