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

package com.android.telephony.sats2range.read;

import static com.android.storage.s2.S2Support.cellIdToString;

import com.android.storage.s2.S2LevelRange;

import java.util.Objects;

public final class SuffixTableRange extends S2LevelRange {
    private static final int DEFAULT_ENTRY_VALUE = -1;
    private final int mEntryValue;

    // For backward compatibility
    public SuffixTableRange(long startCellId, long endCellId) {
        this(startCellId, endCellId, DEFAULT_ENTRY_VALUE);
    }

    public SuffixTableRange(long startCellId, long endCellId, int entryValue) {
        super(startCellId, endCellId);
        mEntryValue = entryValue;
    }

    /** Returns the entry value associated with this range. */
    public int getEntryValue() {
        return mEntryValue;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        if (super.equals(o)) {
            int entryValue = ((SuffixTableRange) o).mEntryValue;
            return mEntryValue == entryValue;
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(mStartCellId, mEndCellId, mEntryValue);
    }

    @Override
    public String toString() {
        return "SuffixTableRange{"
                + "mS2Level=" + mS2Level
                + ", mStartCellId=" + cellIdToString(mStartCellId)
                + ", mEndCellId=" + cellIdToString(mEndCellId)
                + ", mEntryValue=" + mEntryValue
                + '}';
    }
}
