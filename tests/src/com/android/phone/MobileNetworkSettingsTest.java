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
package com.android.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.app.Activity;
import android.app.FragmentManager;
import android.support.test.rule.ActivityTestRule;
import android.support.test.runner.AndroidJUnit4;
import android.telephony.PhoneNumberUtils;


import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;


@RunWith(AndroidJUnit4.class)
public class MobileNetworkSettingsTest {

    @Rule
    public ActivityTestRule<MobileNetworkSettings> mRule =
            new ActivityTestRule<>(MobileNetworkSettings.class);

    private Activity mActivity;
    private MobileNetworkSettings.MobileNetworkFragment mFragment;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mActivity = mRule.getActivity();
        FragmentManager fragmentManager = mActivity.getFragmentManager();
        mFragment = (MobileNetworkSettings.MobileNetworkFragment)
                fragmentManager.findFragmentById(R.id.network_setting_content);
    }

    @Test
    public void testGetEuiccSettingsSummary() {

        assertNull(mFragment.getEuiccSettingsSummary(null, "1234"));
        assertNull(mFragment.getEuiccSettingsSummary("spn", null));
        assertNull(mFragment.getEuiccSettingsSummary("spn", "123"));
        assertEquals(mFragment.getEuiccSettingsSummary("spn", "123456789"),
                mFragment.getString(R.string.carrier_settings_euicc_summary, "spn", "6789"));
        assertEquals(mFragment.getEuiccSettingsSummary("spn", "1234-56-78"),
                mFragment.getString(R.string.carrier_settings_euicc_summary, "spn", "56-78"));
        assertEquals(mFragment.getEuiccSettingsSummary("spn", "1234-56789"),
                mFragment.getString(R.string.carrier_settings_euicc_summary, "spn", "56789"));
        assertEquals(mFragment.getEuiccSettingsSummary("spn", "56-789"),
                mFragment.getString(R.string.carrier_settings_euicc_summary, "spn", "56-789"));
        assertEquals(
                mFragment.getEuiccSettingsSummary(
                    "spn", PhoneNumberUtils.formatNumber("16501234567")),
                mFragment.getString(R.string.carrier_settings_euicc_summary, "spn", "4567"));
    }
}
