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

import android.content.Intent;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ContactPermissionUtilsTest {

    private Uri mTestUri = Uri.parse("content://contacts/people/1");

    @Test
    public void testCheckContactUriPermission_nullData_throwsSecurityException() {
        try {
            ContactPermissionUtils.checkContactUriPermission(null, mTestUri);
            fail("SecurityException expected when data is null");
        } catch (SecurityException e) {
            assertEquals("Permission denial: Intent data is null.", e.getMessage());
        }
    }

    @Test
    public void testCheckContactUriPermission_nullUri_returnsNormally() {
        Intent data = new Intent();
        data.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ContactPermissionUtils.checkContactUriPermission(data, null);
        // Should not throw exception
    }

    @Test
    public void testCheckContactUriPermission_permissionGranted_returnsNormally() {
        Intent data = new Intent();
        data.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ContactPermissionUtils.checkContactUriPermission(data, mTestUri);
        // Should not throw exception
    }

    @Test
    public void testCheckContactUriPermission_permissionDenied_throwsSecurityException() {
        Intent data = new Intent(); // Missing FLAG_GRANT_READ_URI_PERMISSION
        try {
            ContactPermissionUtils.checkContactUriPermission(data, mTestUri);
            fail("SecurityException expected when permission denied");
        } catch (SecurityException e) {
            String expectedMessage = "Permission denial: Contact picker did not grant "
                    + "FLAG_GRANT_READ_URI_PERMISSION to access contact URI " + mTestUri;
            assertEquals(expectedMessage, e.getMessage());
        }
    }
}
