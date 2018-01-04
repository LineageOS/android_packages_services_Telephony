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

import android.support.annotation.NonNull;
import android.telecom.TransformationInfo;
import android.text.TextUtils;
import android.util.Log;

import com.android.i18n.phonenumbers.NumberParseException;
import com.android.i18n.phonenumbers.PhoneNumberUtil;
import com.android.i18n.phonenumbers.Phonenumber.PhoneNumber;
import com.android.phone.PhoneGlobals;

import java.util.Optional;

/**
 * Responsible for transforming numbers to make them dialable and valid when roaming.
 */
final class NumberTransformer {

    private final PhoneNumberUtil mPhoneNumberUtil = PhoneGlobals.getInstance()
            .getPhoneNumberUtil();
    private final Constraints mConstraints;
    private static final String LOG_TAG = "NumberTransformer";

    NumberTransformer(Constraints constraints) {
        this.mConstraints = constraints;
    }

    /**
     * Returns a boolean for callers to quickly determine whether or not the AssistedDialingMediator
     * thinks an attempt at assisted dialing is likely to succeed.
     */
    public boolean canDoAssistedDialingTransformation(
            @NonNull String numberToCheck,
            @NonNull String userHomeCountryCode,
            @NonNull String userRoamingCountryCode) {
        return mConstraints.meetsPreconditions(
                numberToCheck, userHomeCountryCode, userRoamingCountryCode);
    }

    /**
     * A method to do assisted dialing transformations.
     * <p>
     * <p>The library will do its best to attempt a transformation, but, if for any reason the
     * transformation fails, we return an empty optional. The operation can be considered a success
     * when the Optional we return has a value set.
     */
    public Optional<TransformationInfo> doAssistedDialingTransformation(
            String numbertoTransform, String userHomeCountryCode, String userRoamingCountryCode) {

        if (!mConstraints.meetsPreconditions(
                numbertoTransform, userHomeCountryCode, userRoamingCountryCode)) {
            Log.i(
                    LOG_TAG,
                    "assisted dialing failed preconditions");
            return Optional.empty();
        }

        PhoneNumber phoneNumber;
        try {
            phoneNumber = mPhoneNumberUtil.parse(numbertoTransform, userHomeCountryCode);
        } catch (NumberParseException e) {
            Log.i(LOG_TAG, "number failed to parse");
            return Optional.empty();
        }

        String transformedNumber =
                mPhoneNumberUtil.formatNumberForMobileDialing(phoneNumber, userRoamingCountryCode,
                        true);

        // formatNumberForMobileDialing may return an empty String.
        if (TextUtils.isEmpty(transformedNumber)) {
            Log.i(
                    LOG_TAG,
                    "formatNumberForMobileDialing returned an empty string");
            return Optional.empty();
        }

        return Optional.of(
                new TransformationInfo(
                        numbertoTransform,
                        transformedNumber,
                        userHomeCountryCode,
                        userRoamingCountryCode,
                        phoneNumber.getCountryCode()));
    }
}
