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

package com.android.telephony.sats2range;

import static org.junit.Assert.assertEquals;

import com.android.storage.block.read.BlockData;
import com.android.telephony.sats2range.read.SatS2RangeFileFormat;
import com.android.telephony.sats2range.read.SuffixTableSharedData;
import com.android.telephony.sats2range.write.SuffixTableSharedDataWriter;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Tests for {@link SuffixTableSharedData} and {@link SuffixTableSharedDataWriter}. */
public class SuffixTableSharedDataTest {
    @Test
    public void testSuffixTableSharedData() {
        int prefix = 321;
        SuffixTableSharedData sharedData = new SuffixTableSharedData(prefix);
        byte[] bytes = SuffixTableSharedDataWriter.toBytes(sharedData);

        assertEquals(sharedData, SuffixTableSharedData.fromBytes(bytes));
    }

    @Test
    public void testSuffixTableSharedDataWithEntryValues() {
        int prefix = 321;
        int entryValueSizeInBytes = 1;
        List<Integer> entryValues = new ArrayList<>(Arrays.asList(0x01, 0x7F, 0xFF));
        int versionNumber = 1;

        // Verify whether fromTypedData returns correct SuffixTableSharedData when entryByte is 1
        verifySharedData(prefix, entryValueSizeInBytes, entryValues.size(), entryValues,
                versionNumber);

        // Verify when entryValueSizeInBytes is 2
        entryValueSizeInBytes = 2;
        entryValues = new ArrayList<>(Arrays.asList(0x001, 0x5FFF, 0xAFFF, 0xFFFF));
        verifySharedData(prefix, entryValueSizeInBytes, entryValues.size(), entryValues,
                versionNumber);

        // Verify when entryValueSizeInBytes is 3
        entryValueSizeInBytes = 3;
        entryValues = new ArrayList<>(
                Arrays.asList(0x000001, 0x4FFFFF, 0x8FFFFF, 0xBFFFFF, 0xFFFFFF));
        verifySharedData(prefix, entryValueSizeInBytes, entryValues.size(), entryValues,
                versionNumber);

        // Verify when entryValueSizeInBytes is 4, max int value is 0x7FFFFFFF.
        // ConfigID is supported up to 0x7FFFFFFF for now.
        entryValueSizeInBytes = 4;
        entryValues = new ArrayList<>(
                Arrays.asList(0x00000001, 0x2FFFFFFF, 0x3FFFFFFF, 0x4FFFFFFF, 0x5FFFFFFF,
                        0x6FFFFFFF, 0x7FFFFFFF));
        verifySharedData(prefix, entryValueSizeInBytes, entryValues.size(), entryValues,
                versionNumber);

        // Verify when every entry has same value.
        entryValues = new ArrayList<>(
                Arrays.asList(0x3FFFFFFF, 0x3FFFFFFF, 0x3FFFFFFF, 0x3FFFFFFF, 0x3FFFFFFF,
                        0x3FFFFFFF, 0x3FFFFFFF));
        verifySharedData(prefix, entryValueSizeInBytes, entryValues.size(), entryValues,
                versionNumber);

        // Verify when entry is empty
        // entryValueSizeInBytes is set as 4, but there is no entry list
        entryValues = new ArrayList<>(List.of());
        verifySharedData(prefix, entryValueSizeInBytes, entryValues.size(), entryValues,
                versionNumber);
        // entryValueSizeInBytes is 0, no entry list
        entryValueSizeInBytes = 0;
        verifySharedData(prefix, entryValueSizeInBytes, entryValues.size(), entryValues,
                versionNumber);
    }

    private BlockData createBlockedDataFromByteBuffer(int prefix,
            List<Integer> entryValues, SatS2RangeFileFormat fileFormat) {
        SuffixTableSharedData sharedDataToWrite = new SuffixTableSharedData(prefix, entryValues,
                fileFormat);
        ByteBuffer byteBuffer = ByteBuffer.wrap(
                SuffixTableSharedDataWriter.toBytes(sharedDataToWrite));
        return new BlockData(byteBuffer.asReadOnlyBuffer());
    }

    private void verifySharedData(int expectedTablePrefix, int expectedEntryValueSizeInBytes,
            int expectedNumberOfEntryValues, List<Integer> expectedEntryValues, int versionNumber) {
        SatS2RangeFileFormat fileFormat = createSatS2RangeFileFormat(expectedEntryValueSizeInBytes,
                versionNumber);
        BlockData blockData = createBlockedDataFromByteBuffer(
                expectedTablePrefix, expectedEntryValues, fileFormat);
        SuffixTableSharedData sharedData = SuffixTableSharedData.fromTypedData(blockData,
                fileFormat);

        assertEquals(expectedTablePrefix, sharedData.getTablePrefix());
        if (!expectedEntryValues.isEmpty()) {
            assertEquals(expectedEntryValueSizeInBytes, sharedData.getEntryValueSizeInBytes());
        } else {
            assertEquals(0, sharedData.getEntryValueSizeInBytes());
        }

        // If every entry has same value, block data contains only 1 entry info
        if (expectedEntryValues.stream().distinct().count() == 1) {
            assertEquals(3 * Integer.BYTES, blockData.getSize());
            // Verify whether the entry value count has been set to 1.
            assertEquals(1, sharedData.getNumberOfEntryValues());
        } else {
            assertEquals(expectedNumberOfEntryValues, sharedData.getNumberOfEntryValues());
        }
        for (int i = 0; i < expectedNumberOfEntryValues; i++) {
            assertEquals((int) expectedEntryValues.get(i), sharedData.getEntryValue(i));
        }
    }

    private SatS2RangeFileFormat createSatS2RangeFileFormat(int entryByteCount, int versionNumber) {
        int s2Level = 12;
        int prefixBitCount = 11;
        int suffixBitCount = 16;
        int suffixTableBlockIdOffset = 5;
        int suffixTableEntryBitCount = 24;
        boolean isAllowedList = true;

        return new SatS2RangeFileFormat(s2Level,
                prefixBitCount, suffixBitCount, suffixTableBlockIdOffset, suffixTableEntryBitCount,
                isAllowedList, entryByteCount, versionNumber);
    }
}
