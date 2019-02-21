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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collection;

/**
 * ECC info of a country.
 */
public class CountryEccInfo {
    private final String mFallbackEcc;
    private final EccInfo[] mEccInfoList;

    public CountryEccInfo(String eccFallback, @NonNull Collection<EccInfo> eccInfoList) {
        mFallbackEcc = eccFallback;
        mEccInfoList = eccInfoList.toArray(new EccInfo[eccInfoList.size()]);
    }

    /**
     * @return fallback ECC, null if not available.
     */
    public @Nullable String getFallbackEcc() {
        return mFallbackEcc;
    }

    public @NonNull EccInfo[] getEccInfoList() {
        return mEccInfoList.clone();
    }
}
