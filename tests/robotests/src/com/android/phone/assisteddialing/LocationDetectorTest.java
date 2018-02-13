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

import java.util.Locale;
import java.util.Optional;

/**
 * Unit Tests for LocationDetector.
 */
@RunWith(TelephonyRobolectricTestRunner.class)
public class LocationDetectorTest {

  private final TelephonyManager mTelephonyManager =
      (TelephonyManager) RuntimeEnvironment.application.getSystemService(Context.TELEPHONY_SERVICE);
  private final ShadowTelephonyManager mShadowTelephonyManager =
      Shadows.shadowOf(mTelephonyManager);
  private final LocationDetector mLocationDetector = new LocationDetector(mTelephonyManager, null);

  @Test
  public void testGetUserHomeCountry() {
    // User home country
    mShadowTelephonyManager.setSimCountryIso(
        AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_KINGDOM);
    assertThat(mLocationDetector.getUpperCaseUserHomeCountry().get())
        .isEqualTo(AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_KINGDOM);
  }

  @Test
  public void testGetUserHomeCountry_userProvidedHomeCountry() {
    // User home country
    mShadowTelephonyManager.setSimCountryIso(
        AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_KINGDOM);

    LocationDetector localLocationDetector = new LocationDetector(mTelephonyManager, "ZZ");
    assertThat(localLocationDetector.getUpperCaseUserHomeCountry().get()).isEqualTo("ZZ");
  }

  @Test
  public void testGetUserRoamingCountry() {
    // User roaming country
    mShadowTelephonyManager.setNetworkCountryIso(
        AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_KINGDOM);
    assertThat(mLocationDetector.getUpperCaseUserRoamingCountry().get())
        .isEqualTo(AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_KINGDOM);
  }

  @Test
  public void testGetLocationValues_returnValuesAreAlwaysUpperCase() {
    mShadowTelephonyManager.setNetworkCountryIso(
        AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_KINGDOM.toLowerCase());
    mShadowTelephonyManager.setSimCountryIso(
        AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_KINGDOM.toLowerCase());

    assertThat(mLocationDetector.getUpperCaseUserHomeCountry().get())
        .isEqualTo(
            AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_KINGDOM.toUpperCase(
                Locale.US));
    assertThat(mLocationDetector.getUpperCaseUserRoamingCountry().get())
        .isEqualTo(
            AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_KINGDOM.toUpperCase(
                Locale.US));
  }

  @Test
  public void testGetLocationValues_returnValuesMayBeEmpty() {
    mShadowTelephonyManager.setNetworkCountryIso(null);
    mShadowTelephonyManager.setSimCountryIso(null);

    assertThat(mLocationDetector.getUpperCaseUserHomeCountry()).isEqualTo(Optional.empty());
    assertThat(mLocationDetector.getUpperCaseUserRoamingCountry()).isEqualTo(Optional.empty());
  }

  @Test
  public void testConstructorWithNullYieldsEmptyOptional() {
    mShadowTelephonyManager.setNetworkCountryIso(
        AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_KINGDOM.toLowerCase());
    mShadowTelephonyManager.setSimCountryIso(
        AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_KINGDOM.toLowerCase());

    final LocationDetector locationDetector = new LocationDetector(null, null);
    assertThat(locationDetector.getUpperCaseUserHomeCountry().isPresent()).isFalse();
    assertThat(locationDetector.getUpperCaseUserRoamingCountry().isPresent()).isFalse();
  }
}
