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

import java.util.Collection;

/**
 * Emergency call code info.
 */
public class EccInfo {
    /**
     * ECC Types.
     */
    public enum Type {
        POLICE,
        AMBULANCE,
        FIRE,
    }

    private final String mNumber;
    private final Type[] mTypes;

    public EccInfo(@NonNull String number, @NonNull Type type) {
        mNumber = number;
        mTypes = new Type[]{ type };
    }

    public EccInfo(@NonNull String number, @NonNull Collection<Type> types) {
        mNumber = number;
        mTypes = types.toArray(new Type[types.size()]);
    }

    /**
     * @return ECC number.
     */
    public @NonNull String getNumber() {
        return mNumber;
    }

    /**
     * Check whether the ECC number has any matches to the target type.
     *
     * @param target The target type to check.
     * @return true if the target matches.
     */
    public boolean containsType(@NonNull Type target) {
        for (Type type : mTypes) {
            if (target.equals(type)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the types of the ECC number.
     *
     * @return Copied types array.
     */
    public Type[] getTypes() {
        return mTypes.clone();
    }

    /**
     * Get how many types the ECC number is.
     *
     * @return Count of types.
     */
    public int getTypesCount() {
        return mTypes.length;
    }
}
