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
import android.telephony.TelephonyManager;

import com.android.phone.TelephonyRobolectricTestRunner;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowTelephonyManager;

import java.util.Optional;

/**
 * Unit Tests for AssistedDialingMediator.
 */
@RunWith(TelephonyRobolectricTestRunner.class)
public class AssistedDialingMediatorImplTest {

  private final Context mContext = RuntimeEnvironment.application;
  private final TelephonyManager mTelephonyManager =
      (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
  private final ShadowTelephonyManager mShadowTelephonyManager =
      Shadows.shadowOf(mTelephonyManager);
  private final LocationDetector mLocationDetector = new LocationDetector(
      mTelephonyManager, null);
  private final CountryCodeProvider mCountryCodeProvider = new CountryCodeProvider();
  private final Constraints mConstraints = new Constraints(mContext, mCountryCodeProvider);
  private final NumberTransformer mNumberTransformer = new NumberTransformer(mConstraints);
  private final AssistedDialingMediator mAssistedDialingMediator =
      new AssistedDialingMediatorImpl(mLocationDetector, mNumberTransformer);

  @Test(expected = NullPointerException.class)
  public void testConstructorThrows_allParametersNull() {
    new AssistedDialingMediatorImpl(null, null);
  }

  @Test(expected = NullPointerException.class)
  public void testConstructorThrows_locationDetectorNull() {
    new AssistedDialingMediatorImpl(null, mNumberTransformer);
  }

  @Test(expected = NullPointerException.class)
  public void testConstructorThrows_numberTransformerNull() {
    new AssistedDialingMediatorImpl(mLocationDetector, null);
  }

  @Test
  public void testAttemptAssistedDial() {
    // User home country
    mShadowTelephonyManager.setSimCountryIso(
        AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_STATES);
    // User roaming country
    mShadowTelephonyManager.setNetworkCountryIso(
        AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_KINGDOM);

    assertThat(
        mAssistedDialingMediator
            .attemptAssistedDial(AssistedDialingTestHelper.TEST_NUMBER_UNITED_STATES)
            .get()
            .getTransformedNumber())
        .isEqualTo(AssistedDialingTestHelper.EXPECTED_FROM_UK_TO_US_DIALABLE_NUMBER);
  }

  @Test
  public void attemptAssistedDial_nullNumber() {
    // User home country
    mShadowTelephonyManager.setSimCountryIso(
        AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_STATES);
    // User roaming country
    mShadowTelephonyManager.setNetworkCountryIso(
        AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_KINGDOM);
    assertThat(mAssistedDialingMediator.attemptAssistedDial(null)).isEqualTo(Optional.empty());
  }

  @Test
  public void attemptAssistedDial_missingOneCountryCode() {
    // User home country
    mShadowTelephonyManager.setSimCountryIso(
        AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_STATES);
    // User roaming country
    mShadowTelephonyManager.setNetworkCountryIso(null);
    assertThat(
        mAssistedDialingMediator.attemptAssistedDial(
            AssistedDialingTestHelper.TEST_NUMBER_UNITED_STATES))
        .isEqualTo(Optional.empty());
  }

  @Test
  public void testIsPlatformEligible() {
    assertThat(mAssistedDialingMediator.isPlatformEligible()).isTrue();
  }

  @Test
  public void testUserHomeCountryCode() {
    // User home country
    mShadowTelephonyManager.setSimCountryIso(
        AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_STATES);
    assertThat(mAssistedDialingMediator.userHomeCountryCode().get())
        .isEqualTo(AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_STATES);
  }
}
