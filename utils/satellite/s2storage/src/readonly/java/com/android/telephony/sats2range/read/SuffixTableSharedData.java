/*
 * Copyright (C) 2023 The Android Open Source Project
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

import com.android.storage.block.read.TypedData;
import com.android.storage.io.read.TypedInputStream;
import com.android.storage.table.reader.Table;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Shared data for a suffix table held in a suffix table block: the information applies to all
 * entries in the table and is required when interpreting the table's block data.
 */
public final class SuffixTableSharedData {
    public static final int INVALID_ENTRY_VALUE = -1;
    private final int mTablePrefix;
    private final int mEntryValueSizeInBytes;
    private final int mNumberOfEntryValues;
    private final int mHeaderByteOffsetToRead;
    private List<Integer> mEntryValuesToWrite = List.of(); // This is used for write path
    private final TypedData mSharedDataToRead; // This is used for read path

    /**
     * Creates a {@link SuffixTableSharedData}. See also {@link #fromBytes(byte[])}.
     */
    public SuffixTableSharedData(int tablePrefix) {
        mTablePrefix = tablePrefix;
        mEntryValueSizeInBytes = 0;
        mNumberOfEntryValues = 0;
        mHeaderByteOffsetToRead = 0;
        mSharedDataToRead = null;
    }

    /**
     * This constructor is used for write path
     */
    public SuffixTableSharedData(int tablePrefix, List<Integer> entryValues,
            SatS2RangeFileFormat fileFormat) {
        mSharedDataToRead = null;
        mTablePrefix = tablePrefix;
        mNumberOfEntryValues = entryValues.size();
        mEntryValuesToWrite = entryValues;
        mEntryValueSizeInBytes = fileFormat.getEntryValueSizeInBytes();
        mHeaderByteOffsetToRead = 0;
    }

    /**
     * This constructor is used for read path
     */
    public SuffixTableSharedData(TypedData sharedDataToRead, SatS2RangeFileFormat fileFormat) {
        mSharedDataToRead = Objects.requireNonNull(sharedDataToRead);
        int offset = 0;
        // extract prefix value
        mTablePrefix = mSharedDataToRead.getInt(offset);
        offset += Integer.BYTES;

        // If the size of shared data is greater than the offset, extract the number of entry
        // values.
        if ((offset + Integer.BYTES) < mSharedDataToRead.getSize()) {
            mNumberOfEntryValues = mSharedDataToRead.getInt(offset);
            mHeaderByteOffsetToRead = offset + Integer.BYTES;
            mEntryValueSizeInBytes = fileFormat.getEntryValueSizeInBytes();
        } else {
            mNumberOfEntryValues = 0;
            mHeaderByteOffsetToRead = offset;
            mEntryValueSizeInBytes = 0;
        }
    }

    /**
     * This is used for read path
     */
    public static SuffixTableSharedData fromTypedData(TypedData sharedData,
            SatS2RangeFileFormat fileFormat) {
        return new SuffixTableSharedData(sharedData, fileFormat);
    }

    /**
     * Reads the entry value at a specific position in the byte buffer and returns it.
     *
     * @param entryIndex The index of entry to be read.
     * @return entry value (integer) read from the byte buffer.
     */
    public int getEntryValue(int entryIndex) {
        if (mSharedDataToRead == null || entryIndex < 0 || mNumberOfEntryValues == 0) {
            return INVALID_ENTRY_VALUE;
        }

        if (mNumberOfEntryValues == 1) {
            entryIndex = 0;
        }

        int offset;
        if (entryIndex < mNumberOfEntryValues) {
            // offset = table prefix(4) + entry value count(4) + size of entry * entry index
            offset = mHeaderByteOffsetToRead + (mEntryValueSizeInBytes * entryIndex);
        } else {
            return INVALID_ENTRY_VALUE;
        }

        return getValueInternal(mSharedDataToRead, mEntryValueSizeInBytes, offset);
    }

    // Entry lists to be written to a byte buffer.
    public List<Integer> getEntryValuesToWrite() {
        return mEntryValuesToWrite;
    }

    /**
     * Returns the S2 cell ID prefix associated with the table. i.e. all S2 ranges in the table will
     * have this prefix.
     */
    public int getTablePrefix() {
        return mTablePrefix;
    }

    /**
     * Returns the number of entry values.
     */
    public int getNumberOfEntryValues() {
        return mNumberOfEntryValues;
    }

    /**
     * Returns the size of entry value in Bytes.
     */
    public int getEntryValueSizeInBytes() {
        return mEntryValueSizeInBytes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SuffixTableSharedData that = (SuffixTableSharedData) o;
        return mTablePrefix == that.mTablePrefix
                && mNumberOfEntryValues == that.mNumberOfEntryValues
                && mEntryValuesToWrite.equals(that.mEntryValuesToWrite);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mTablePrefix, mNumberOfEntryValues, mEntryValuesToWrite);
    }

    @Override
    public String toString() {
        return "SuffixTableSharedData{"
                + "mTablePrefix=" + mTablePrefix
                + "mNumberOfEntries=" + mNumberOfEntryValues
                + "mEntryValuesToWrite=" + mEntryValuesToWrite
                + '}';
    }

    /**
     * Creates a {@link SuffixTableSharedData} using shared data from a {@link Table}.
     */
    public static SuffixTableSharedData fromBytes(byte[] bytes) {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
                TypedInputStream tis = new TypedInputStream(bis)) {
            int tablePrefixValue = tis.readInt();
            return new SuffixTableSharedData(tablePrefixValue);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private int getValueInternal(TypedData buffer, int valueSizeBytes, int byteOffset) {
        if (byteOffset < 0) {
            throw new IllegalArgumentException(
                    "byteOffset=" + byteOffset + " must not be negative");
        }

        // High bytes read first.
        int value = 0;
        int bytesRead = 0;
        while (bytesRead++ < valueSizeBytes) {
            value <<= Byte.SIZE;
            value |= buffer.getUnsignedByte(byteOffset++);
        }

        return value;
    }
}
