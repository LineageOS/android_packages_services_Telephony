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
import android.telecom.TransformationInfo;

import com.android.phone.TelephonyRobolectricTestRunner;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;

import java.util.Optional;

/**
 * Unit Tests for NumberTransformer.
 */
@RunWith(TelephonyRobolectricTestRunner.class)
public class NumberTransformerTest {

  private final Context mContext = RuntimeEnvironment.application;
  private final CountryCodeProvider mCountryCodeProvider = new CountryCodeProvider();
  private final Constraints mConstraints = new Constraints(mContext, mCountryCodeProvider);
  private final NumberTransformer mNumberTransformer = new NumberTransformer(mConstraints);

  @Test
  public void testDoAssistedDialingTransformation_nothingToDo() {
    assertThat(
        mNumberTransformer.doAssistedDialingTransformation(
            AssistedDialingTestHelper.TEST_NUMBER_UNITED_STATES,
            AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_STATES,
            AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_STATES))
        .isEqualTo(Optional.empty());

    assertThat(
        mNumberTransformer.doAssistedDialingTransformation(
            AssistedDialingTestHelper.TEST_NUMBER_UNITED_STATES,
            AssistedDialingTestHelper.UNSUPPORTED_COUNTRY_CODE_NORTH_KOREA,
            AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_STATES))
        .isEqualTo(Optional.empty());

    assertThat(
        mNumberTransformer.doAssistedDialingTransformation(
            AssistedDialingTestHelper.INTERNATIONAL_TEST_NUMBER_UNITED_STATES,
            AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_STATES,
            AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_KINGDOM))
        .isEqualTo(Optional.empty());
  }

  @Test
  public void testDoAssistedDialingTransformation_conditionsAreCorrect_fromUKtoUS() {
    assertThat(
        mNumberTransformer
            .doAssistedDialingTransformation(
                AssistedDialingTestHelper.TEST_NUMBER_UNITED_STATES,
                AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_STATES,
                AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_KINGDOM)
            .get()
            .getTransformedNumber())
        .isEqualTo(AssistedDialingTestHelper.EXPECTED_FROM_UK_TO_US_DIALABLE_NUMBER);
  }

  @Test
  public void testDoAssistedDialingTransformation_conditionsAreCorrect_fromUStoUK() {
    assertThat(
        mNumberTransformer
            .doAssistedDialingTransformation(
                AssistedDialingTestHelper.TEST_NUMBER_UNITED_KINGDOM,
                AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_KINGDOM,
                AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_STATES)
            .get()
            .getTransformedNumber())
        .isEqualTo(AssistedDialingTestHelper.EXPECTED_FROM_US_TO_UK_DIALABLE_NUMBER);
  }

  @Test
  public void testDoAssistedDialingTransformation_conditionsAreCorrect_fromJPtoMX() {
    assertThat(
        mNumberTransformer
            .doAssistedDialingTransformation(
                AssistedDialingTestHelper.TEST_NUMBER_MEXICO,
                AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_MEXICO,
                AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_JAPAN)
            .get()
            .getTransformedNumber())
        .isEqualTo(AssistedDialingTestHelper.EXPECTED_FROM_JP_TO_MX_DIALABLE_NUMBER);
  }

  @Test
  public void testDoAssistedDialingTransformation_conditionsAreCorrect_fromCAtoJP() {
    assertThat(
        mNumberTransformer
            .doAssistedDialingTransformation(
                AssistedDialingTestHelper.TEST_NUMBER_JAPAN,
                AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_JAPAN,
                AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_CANADA)
            .get()
            .getTransformedNumber())
        .isEqualTo(AssistedDialingTestHelper.EXPECTED_FROM_CA_TO_JP_DIALABLE_NUMBER);
  }

  @Test
  public void testDoADTransformation_conditionsCorrect_fromUKtoUS_allMembersReturned() {
    TransformationInfo transformationInfo =
        mNumberTransformer
            .doAssistedDialingTransformation(
                AssistedDialingTestHelper.TEST_NUMBER_UNITED_STATES,
                AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_STATES,
                AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_KINGDOM)
            .get();
    assertThat(transformationInfo.getOriginalNumber())
        .isEqualTo(AssistedDialingTestHelper.TEST_NUMBER_UNITED_STATES);
    assertThat(transformationInfo.getTransformedNumber())
        .isEqualTo(AssistedDialingTestHelper.EXPECTED_FROM_UK_TO_US_DIALABLE_NUMBER);
    assertThat(transformationInfo.getUserHomeCountryCode())
        .isEqualTo(AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_STATES);
    assertThat(transformationInfo.getUserRoamingCountryCode())
        .isEqualTo(AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_KINGDOM);
    assertThat(transformationInfo.getTransformedNumberCountryCallingCode())
        .isEqualTo(AssistedDialingTestHelper.UNITED_STATES_COUNTRY_CALLING_CODE);
  }
}
