/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.services.telephony.domainselection;

import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.os.Looper;
import android.telephony.TelephonyManager;
import android.telephony.emergency.EmergencyNumber;
import android.text.TextUtils;

import com.android.TestContext;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.emergency.EmergencyNumberTracker;
import com.android.phone.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;

/**
 * Unit tests for DynamicRoutingController
 */
public class DynamicRoutingControllerTest {
    private static final String TAG = "DynamicRoutingControllerTest";

    private static final int SLOT_0 = 0;
    private static final int SLOT_1 = 1;

    @Mock private Resources mResources;
    @Mock private DynamicRoutingController.PhoneFactoryProxy mPhoneFactoryProxy;
    @Mock private Phone mPhone0;
    @Mock private Phone mPhone1;
    @Mock private EmergencyNumberTracker mEmergencyNumberTracker;

    private BroadcastReceiver mReceiver;
    private IntentFilter mIntentFilter;

    private Context mContext;
    private DynamicRoutingController mDrc;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mContext = new TestContext() {
            @Override
            public String getOpPackageName() {
                return "";
            }

            @Override
            public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
                mReceiver = receiver;
                mIntentFilter = filter;
                return null;
            }

            @Override
            public Resources getResources() {
                return mResources;
            }
        };

        if (Looper.myLooper() == null) {
            Looper.prepare();
        }

        when(mResources.getStringArray(anyInt())).thenReturn(null);
        when(mPhoneFactoryProxy.getPhone(eq(SLOT_0))).thenReturn(mPhone0);
        when(mPhoneFactoryProxy.getPhone(eq(SLOT_1))).thenReturn(mPhone1);
        when(mPhone0.getPhoneId()).thenReturn(SLOT_0);
        when(mPhone1.getPhoneId()).thenReturn(SLOT_1);
        when(mPhone0.getEmergencyNumberTracker()).thenReturn(mEmergencyNumberTracker);
        when(mPhone1.getEmergencyNumberTracker()).thenReturn(mEmergencyNumberTracker);
        when(mEmergencyNumberTracker.getEmergencyNumbers(anyString()))
                .thenReturn(new ArrayList<EmergencyNumber>());
    }

    @After
    public void tearDown() throws Exception {
        mDrc = null;
    }

    @Test
    public void testNotEnabledInitialize() throws Exception {
        createController(false, null);

        assertFalse(mDrc.isDynamicRoutingEnabled());

        assertNull(mReceiver);
        assertNull(mIntentFilter);
    }

    @Test
    public void testEnabledInitialize() throws Exception {
        createController(true, null);

        assertTrue(mDrc.isDynamicRoutingEnabled());
        assertFalse(mDrc.isDynamicRoutingEnabled(mPhone0));
        assertFalse(mDrc.isDynamicRoutingEnabled(mPhone1));
    }

    @Test
    public void testEnabledCountryChanged() throws Exception {
        createController(true, "us");

        sendNetworkCountryChanged(SLOT_0, "zz");
        assertTrue(mDrc.isDynamicRoutingEnabled());
        assertFalse(mDrc.isDynamicRoutingEnabled(mPhone0));
        assertFalse(mDrc.isDynamicRoutingEnabled(mPhone1));

        sendNetworkCountryChanged(SLOT_0, "us");
        assertTrue(mDrc.isDynamicRoutingEnabled(mPhone0));
        assertTrue(mDrc.isDynamicRoutingEnabled(mPhone1));
    }

    @Test
    public void testDynamicRouting() throws Exception {
        doReturn(new String[] {"us,,110,117,118,119", "zz,,200"})
                .when(mResources).getStringArray(
                eq(R.array.config_dynamic_routing_emergency_numbers));

        createController(true, "us");

        sendNetworkCountryChanged(SLOT_0, "us");

        ArrayList<EmergencyNumber> nums = new ArrayList<EmergencyNumber>();
        nums.add(getEmergencyNumber("us", "110", "92"));
        when(mEmergencyNumberTracker.getEmergencyNumbers(eq("110"))).thenReturn(nums);

        // Not included in the resource configuration.
        nums = new ArrayList<EmergencyNumber>();
        nums.add(getEmergencyNumber("us", "111", "92"));
        when(mEmergencyNumberTracker.getEmergencyNumbers(eq("111"))).thenReturn(nums);

        // Different country.
        nums = new ArrayList<EmergencyNumber>();
        nums.add(getEmergencyNumber("zz", "117", "92"));
        when(mEmergencyNumberTracker.getEmergencyNumbers(eq("117"))).thenReturn(nums);

        // No info in the EmergencyNumberTracker
        nums = new ArrayList<EmergencyNumber>();
        when(mEmergencyNumberTracker.getEmergencyNumbers(eq("118"))).thenReturn(nums);

        nums = new ArrayList<EmergencyNumber>();
        nums.add(getEmergencyNumber("us", "119", "92"));
        when(mEmergencyNumberTracker.getEmergencyNumbers(eq("119"))).thenReturn(nums);

        // Different country.
        nums = new ArrayList<EmergencyNumber>();
        nums.add(getEmergencyNumber("us", "200", "92"));
        when(mEmergencyNumberTracker.getEmergencyNumbers(eq("200"))).thenReturn(nums);

        assertTrue(mDrc.isDynamicNumber(mPhone0, "110"));
        assertFalse(mDrc.isDynamicNumber(mPhone0, "111"));
        assertFalse(mDrc.isDynamicNumber(mPhone0, "117"));
        assertFalse(mDrc.isDynamicNumber(mPhone0, "118"));
        assertTrue(mDrc.isDynamicNumber(mPhone0, "119"));
        assertFalse(mDrc.isDynamicNumber(mPhone0, "200"));
    }

    private EmergencyNumber getEmergencyNumber(String iso, String number, String mnc) {
        return new EmergencyNumber(number, iso, mnc,
                EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_UNSPECIFIED,
                new ArrayList<String>(),
                EmergencyNumber.EMERGENCY_NUMBER_SOURCE_MODEM_CONFIG,
                EmergencyNumber.EMERGENCY_CALL_ROUTING_UNKNOWN);
    }

    private void sendNetworkCountryChanged(int phoneId, String iso) {
        Intent intent = new Intent(TelephonyManager.ACTION_NETWORK_COUNTRY_CHANGED);
        intent.putExtra(PhoneConstants.PHONE_KEY, phoneId);
        intent.putExtra(TelephonyManager.EXTRA_NETWORK_COUNTRY, iso);
        mReceiver.onReceive(mContext, intent);
    }

    private void createController(boolean enabled, String iso) throws Exception {
        doReturn(enabled).when(mResources).getBoolean(
                eq(R.bool.dynamic_routing_emergency_enabled));
        if (!TextUtils.isEmpty(iso)) {
            doReturn(new String[] {iso}).when(mResources).getStringArray(
                    eq(R.array.config_countries_dynamic_routing_emergency_enabled));
        }

        mDrc = new DynamicRoutingController(mPhoneFactoryProxy);
        mDrc.initialize(mContext);

        if (enabled) {
            assertNotNull(mReceiver);
            assertNotNull(mIntentFilter);
            assertTrue(mIntentFilter.hasAction(TelephonyManager.ACTION_NETWORK_COUNTRY_CHANGED));
        }
    }
}
