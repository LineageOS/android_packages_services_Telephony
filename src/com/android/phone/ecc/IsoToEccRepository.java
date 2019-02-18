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

package com.android.phone.ecc;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;

import java.io.IOException;

/**
 * Data source for country ISO to ECC info list mapping.
 */
public interface IsoToEccRepository {
    /**
     * Get available emergency numbers for given country ISO. Because the possible of IO wait
     * (depends on the implementation), this method should not be called in the main thread.
     *
     * @param context The context used to access resources.
     * @param iso For which ECC info list is returned.
     * @return The ECC info of given ISO. Null if no match.
     * @throws IOException if an error occurs while initialize the repository or retrieving
     * the {@link CountryEccInfo}.
     */
    @Nullable CountryEccInfo getCountryEccInfo(@NonNull Context context, @Nullable String iso)
            throws IOException;
}
