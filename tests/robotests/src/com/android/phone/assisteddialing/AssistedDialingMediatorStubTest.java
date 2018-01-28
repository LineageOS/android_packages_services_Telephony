/*
 * Copyright (C) 2017 The Android Open Source Project
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

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowTelephonyManager;

import java.util.Optional;

/**
 * Unit Tests for AssistedDialingMediator.
 */
@RunWith(RobolectricTestRunner.class)
public class AssistedDialingMediatorStubTest {

    private final TelephonyManager mTelephonyManager =
            (TelephonyManager) RuntimeEnvironment.application.getSystemService(
                    Context.TELEPHONY_SERVICE);
    private final ShadowTelephonyManager mShadowTelephonyManager =
            (ShadowTelephonyManager) Shadow.extract(mTelephonyManager);
    private final AssistedDialingMediatorStub mAssistedDialingMediatorStub =
            new AssistedDialingMediatorStub();

    @Test
    public void testAttemptAssistedDial_conditionsEligibleButStubInert() {
        // User home country
        mShadowTelephonyManager.setSimCountryIso(
                AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_STATES);
        // User roaming country
        mShadowTelephonyManager.setNetworkCountryIso(
                AssistedDialingTestHelper.SUPPORTED_COUNTRY_CODE_UNITED_KINGDOM);
        assertThat(
                mAssistedDialingMediatorStub.attemptAssistedDial(
                        AssistedDialingTestHelper.TEST_NUMBER_UNITED_STATES))
                .isEqualTo(Optional.empty());
    }

    @Test
    public void testIsPlatformEligible() {
        assertThat(mAssistedDialingMediatorStub.isPlatformEligible()).isFalse();
    }

    @Test
    public void testUserHomeCountryCode() {
        assertThat(mAssistedDialingMediatorStub.userHomeCountryCode())
                .isInstanceOf(Optional.empty().getClass());
    }
}
