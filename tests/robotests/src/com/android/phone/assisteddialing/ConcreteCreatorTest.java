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
import android.os.UserManager;
import android.preference.PreferenceManager;
import android.telecom.TransformationInfo;
import android.telephony.TelephonyManager;

import com.android.phone.TelephonyRobolectricTestRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowTelephonyManager;
import org.robolectric.shadows.ShadowUserManager;

import java.util.Optional;

/**
 * Unit Tests for ConcreteCreator.
 */
@RunWith(TelephonyRobolectricTestRunner.class)
public class ConcreteCreatorTest {

  private final Context mContext = RuntimeEnvironment.application;
  private final TelephonyManager mTelephonyManager =
      (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
  private final ShadowTelephonyManager mShadowTelephonyManager =
      Shadows.shadowOf(mTelephonyManager);
  private final ShadowUserManager mShadowUserManager =
      Shadows.shadowOf(mContext.getSystemService(UserManager.class));

  @Before
  public void setupTest() {
    mShadowUserManager.setUserUnlocked(true);
  }

  @Test
  public void testCreateNewAssistedDialingMediator() {
    assertThat(ConcreteCreator.createNewAssistedDialingMediator(mTelephonyManager, mContext))
        .isInstanceOf(AssistedDialingMediator.class);
  }

  @Test
  public void testCreateNewAssistedDialingMediator_userDisabledFeature() {
    PreferenceManager.getDefaultSharedPreferences(mContext)
        .edit()
        .putBoolean(mContext.getString(R.string.assisted_dialing_setting_toggle_key), false)
        .apply();

    assertThat(ConcreteCreator.createNewAssistedDialingMediator(mTelephonyManager, mContext))
        .isInstanceOf(AssistedDialingMediatorStub.class);
  }

  @Test
  public void testCreateNewAssistedDialingMediator_userSpecifiedCountryTakesPrecedence() {
    // System thinks user is from the UK
    mShadowTelephonyManager.setSimCountryIso(
        AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_KINGDOM);
    // Set the user to roam in the US
    mShadowTelephonyManager.setNetworkCountryIso(
        AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_STATES);

    // User sets preferred home country to Japan
    PreferenceManager.getDefaultSharedPreferences(mContext)
        .edit()
        .putString(
            mContext.getString(R.string.assisted_dialing_setting_cc_key),
            AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_JAPAN)
        .apply();

    AssistedDialingMediator mediator =
        ConcreteCreator.createNewAssistedDialingMediator(mTelephonyManager, mContext);
    Optional<TransformationInfo> result =
        mediator.attemptAssistedDial(AssistedDialingTestHelper.TEST_NUMBER_JAPAN);
    assertThat(result.get().getUserHomeCountryCode())
        .isEqualTo(AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_JAPAN);
    assertThat(result.get().getTransformedNumber())
        .isEqualTo(AssistedDialingTestHelper.EXPECTED_FROM_US_TO_JP_DIALABLE_NUMBER);
  }

  @Test
  public void testCreateNewAssistedDialingMediator_defaultCountryCodeAutoDetected() {
    // System thinks user is from the UK
    mShadowTelephonyManager.setSimCountryIso(
        AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_KINGDOM);
    // Set the user to roam in the US
    mShadowTelephonyManager.setNetworkCountryIso(
        AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_STATES);

    AssistedDialingMediator mediator =
        ConcreteCreator.createNewAssistedDialingMediator(mTelephonyManager, mContext);
    Optional<TransformationInfo> result =
        mediator.attemptAssistedDial(AssistedDialingTestHelper.TEST_NUMBER_UNITED_KINGDOM);
    assertThat(result.get().getUserHomeCountryCode())
        .isEqualTo(AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_KINGDOM);
    assertThat(result.get().getTransformedNumber())
        .isEqualTo(AssistedDialingTestHelper.EXPECTED_FROM_US_TO_UK_DIALABLE_NUMBER);
  }

  @Test
  public void testCreateNewAssistedDialingMediatorWithNullTelephonyManager_returnsStub() {
    assertThat(ConcreteCreator.createNewAssistedDialingMediator(null, mContext))
        .isInstanceOf(AssistedDialingMediatorStub.class);
  }

  @Test
  public void testCreateNewAssistedDialingMediatorWithNullContext_returnsStub() {
    assertThat(ConcreteCreator.createNewAssistedDialingMediator(null, mContext))
        .isInstanceOf(AssistedDialingMediatorStub.class);
  }

  @Test
  public void testCreateNewAssistedDialingMediator_deviceLockedDisablesFeature() {
    mShadowUserManager.setUserUnlocked(false);
    assertThat(ConcreteCreator.createNewAssistedDialingMediator(mTelephonyManager, mContext))
        .isInstanceOf(AssistedDialingMediatorStub.class);
  }

  @Test
  public void testGetCountryCodeProvider() {
    assertThat(ConcreteCreator.getCountryCodeProvider()).isNotNull();
  }
}