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

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * Unit Tests for Constraints.
 */
@RunWith(RobolectricTestRunner.class)
public class ConstraintsTest {

  public final Context mContext = RuntimeEnvironment.application.getBaseContext();
  private final CountryCodeProvider mCountryCodeProvider = new CountryCodeProvider();
  private Constraints mConstraints = new Constraints(mContext, mCountryCodeProvider);

  @Test
  public void testnumberMeetsPreconditionsForAssistedDialing_countryCodesEquivalent() {
    assertThat(
        mConstraints.meetsPreconditions(
            AssistedDialingTestHelper.TEST_NUMBER_UNITED_STATES,
            AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_STATES,
            AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_STATES))
        .isFalse();
  }

  @Test
  public void testnumberMeetsPreconditionsForAssistedDialing_countryCodesUnsupported() {
    assertThat(
        mConstraints.meetsPreconditions(
            AssistedDialingTestHelper.TEST_NUMBER_UNITED_STATES,
            AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_STATES,
            AssistedDialingTestHelper.UNSUPPORTED_COUNTRY_CODE_NORTH_KOREA))
        .isFalse();
  }

  @Test
  public void testnumberMeetsPreconditionsForAssistedDialing_numberIsAlreadyInternational() {
    assertThat(
        mConstraints.meetsPreconditions(
            AssistedDialingTestHelper.INTERNATIONAL_TEST_NUMBER_UNITED_STATES,
            AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_STATES,
            AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_KINGDOM))
        .isFalse();
  }

  @Test
  public void testnumberMeetsPreconditionsForAssistedDialing_conditionsAreCorrect() {
    assertThat(
        mConstraints.meetsPreconditions(
            AssistedDialingTestHelper.TEST_NUMBER_UNITED_STATES,
            AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_STATES,
            AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_KINGDOM))
        .isTrue();
  }

  @Test
  public void testLocalesAreAlwaysUpperCase() {
    assertThat(
        mConstraints.meetsPreconditions(
            AssistedDialingTestHelper.TEST_NUMBER_UNITED_STATES,
            AssistedDialingTestHelper
                .SUPPORTED_COUNTRY_CODE_UNITED_STATES.toLowerCase(),
            AssistedDialingTestHelper
                .SUPPORTED_COUNTRY_CODE_UNITED_KINGDOM.toLowerCase()))
        .isTrue();
  }

  @Test
  public void testnumberMeetsPreconditionsForAssistedDialing_nullNumber() {
    assertThat(
        mConstraints.meetsPreconditions(
            null,
            AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_STATES,
            AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_KINGDOM))
        .isFalse();
  }

  @Test
  public void testnumberMeetsPreconditionsForAssistedDialing_hasExtension() {
    assertThat(
        mConstraints.meetsPreconditions(AssistedDialingTestHelper
                .TEST_NUMBER_UNITED_STATES_WITH_POST_DIAL_EXTENSION,
            AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_STATES,
            AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_KINGDOM))
        .isFalse();
  }

  @Test
  public void testnumberMeetsPreconditionsForAssistedDialing_nullCountryCodes() {
    assertThat(
        mConstraints.meetsPreconditions(
            AssistedDialingTestHelper.TEST_NUMBER_UNITED_STATES, null, null))
        .isFalse();
  }

  @Test
  public void testnumberPreconditionsForAssistedDialing_isLocalEmergencyNumber_unitedStates() {
    assertThat(
        mConstraints.meetsPreconditions(
            AssistedDialingTestHelper.TEST_NUMBER_EMERGENCY_UNITED_STATES,
            AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_KINGDOM,
            AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_STATES))
        .isFalse();
  }

  @Test
  public void testnumberMeetsPreconditionsForAssistedDialing_numberIsInvalid() {
    assertThat(
        mConstraints.meetsPreconditions(
            AssistedDialingTestHelper.INVALID_TEST_NUMBER_UNITED_STATES,
            AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_STATES,
            AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_KINGDOM))
        .isFalse();
  }

  @Test
  public void testnumberMeetsPreconditionsForAssistedDialing_numberShouldNotParse() {
    assertThat(
        mConstraints.meetsPreconditions(
            AssistedDialingTestHelper.INVALID_NUMBER_ENCODING,
            AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_STATES,
            AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_KINGDOM))
        .isFalse();
  }
}
