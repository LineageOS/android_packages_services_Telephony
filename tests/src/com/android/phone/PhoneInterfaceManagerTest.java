/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static junit.framework.TestCase.fail;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import android.content.pm.PackageManager;
import android.os.Binder;
import android.util.Log;

import androidx.test.runner.AndroidJUnit4;

import com.android.TelephonyTestBase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;


@RunWith(AndroidJUnit4.class)
public class PhoneInterfaceManagerTest extends TelephonyTestBase {

    private static final String PRIVILEGED_PACKAGE_NAME = "test.package.name";

    private static final String TAG = "PhoneInterfaceManagerTest";

    private PhoneInterfaceManager mPhoneInterfaceManager;
    private PhoneGlobals mMockPhoneGlobals;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        mMockPhoneGlobals = mock(PhoneGlobals.class);
        //PhoneGlobals phoneGlobals = new PhoneGlobals(mContext);
        mPhoneInterfaceManager = new PhoneInterfaceManager(mMockPhoneGlobals);
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @Test
    public void testGetUiccCardsInfoSecurity() {
        // Set up mocks so that the supplied package UID does not equal the calling UID
        PackageManager mockPackageManager = mock(PackageManager.class);
        try {
            doReturn(Binder.getCallingUid() + 1).when(mockPackageManager)
                    .getPackageUid(eq(PRIVILEGED_PACKAGE_NAME), anyInt());
        } catch (Exception e) {
            Log.d(TAG, "testGetUiccCardsInfoSecurity unable to setup mocks");
            fail();
        }
        doReturn(mockPackageManager).when(mContext).getPackageManager();
        doReturn(mockPackageManager).when(mMockPhoneGlobals).getPackageManager();
        try {
            mPhoneInterfaceManager.getUiccCardsInfo(PRIVILEGED_PACKAGE_NAME);
            fail();
        } catch (SecurityException e) {
            Log.d(TAG, "testGetUiccCardsInfoSecurity e = " + e);
        }
    }
}
