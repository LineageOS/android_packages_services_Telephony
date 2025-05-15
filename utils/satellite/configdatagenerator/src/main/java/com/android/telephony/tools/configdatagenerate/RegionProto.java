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

package com.android.telephony.tools.configdatagenerate;

public class RegionProto {

    String mS2CellFileName;
    String[] mCountryCodeList;
    Boolean mIsAllowed;
    String mSatelliteAccessConfigFileName;

    public RegionProto(
            String s2CellFileName,
            String[] countryCodeList,
            Boolean isAllowed,
            String satelliteAccessConfigFileName) {
        mS2CellFileName = s2CellFileName;
        mCountryCodeList = countryCodeList;
        mIsAllowed = isAllowed;
        mSatelliteAccessConfigFileName = satelliteAccessConfigFileName;
    }
}
