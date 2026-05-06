/*
 * Copyright (C) 2026 The Android Open Source Project
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
import static org.junit.Assert.fail;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ComponentCaller;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
public class ContactPermissionUtilsTest {

    @Mock
    private ComponentCaller mMockCaller;

    private Uri mTestUri = Uri.parse("content://contacts/people/1");

    private AutoCloseable mMockitoSession;

    @Before
    public void setUp() throws Exception {
        mMockitoSession = MockitoAnnotations.openMocks(this);
        when(mMockCaller.getUid()).thenReturn(1000);
        when(mMockCaller.getPackage()).thenReturn("com.example.app");
    }

    @After
    public void tearDown() throws Exception {
        if (mMockitoSession != null) {
            mMockitoSession.close();
        }
    }

    @Test
    public void testCheckContactUriPermission_nullCaller_throwsSecurityException() {
        try {
            ContactPermissionUtils.checkContactUriPermission(null, mTestUri);
            fail("SecurityException expected when caller is null");
        } catch (SecurityException e) {
            assertEquals("Permission denial: Current caller is null.", e.getMessage());
        }
    }

    @Test
    public void testCheckContactUriPermission_nullUri_returnsNormally() {
        ContactPermissionUtils.checkContactUriPermission(mMockCaller, null);
        // Should not throw exception
    }

    @Test
    public void testCheckContactUriPermission_permissionGranted_returnsNormally() {
        when(mMockCaller.checkContentUriPermission(mTestUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION))
                .thenReturn(PackageManager.PERMISSION_GRANTED);

        ContactPermissionUtils.checkContactUriPermission(mMockCaller, mTestUri);
    }

    @Test
    public void testCheckContactUriPermission_permissionDenied_throwsSecurityException() {
        when(mMockCaller.checkContentUriPermission(mTestUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION))
                .thenReturn(PackageManager.PERMISSION_DENIED);

        try {
            ContactPermissionUtils.checkContactUriPermission(mMockCaller, mTestUri);
            fail("SecurityException expected when permission denied");
        } catch (SecurityException e) {
            String expectedMessage = String.format(
                    "Permission denial: Caller (uid=%d, pkg=%s) lacks specific permission"
                            + " grant %s to access contact URI %s.",
                    1000,
                    "com.example.app",
                    "FLAG_GRANT_READ_URI_PERMISSION",
                    mTestUri
            );
            assertEquals(expectedMessage, e.getMessage());
            verify(mMockCaller).checkContentUriPermission(mTestUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
    }
}
