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

import android.app.ComponentCaller;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;

/**
 * Utility class for contact permission checks.
 */
public class ContactPermissionUtils {
    private static final String LOG_TAG = "ContactPermissionUtils";

    private ContactPermissionUtils() {
        // Prevents instantiation
    }

    /**
     * Validates that the caller has permission to access the returned contact URI.
     * Throws SecurityException if permission is denied.
     */
    public static void checkContactUriPermission(ComponentCaller caller, Uri uri) {
        if (caller == null) {
            throw new SecurityException("Permission denial: Current caller is null.");
        }
        if (uri == null) {
            Log.w(LOG_TAG, "checkContactUriPermission: Contact URI is null.");
            return;
        }

        if (caller.checkContentUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_DENIED) {
            throw new SecurityException(String.format(
                    "Permission denial: Caller (uid=%d, pkg=%s) lacks specific permission"
                            + " grant %s to access contact URI %s.",
                    caller.getUid(),
                    caller.getPackage(),
                    "FLAG_GRANT_READ_URI_PERMISSION",
                    uri
            ));
        }
    }
}
