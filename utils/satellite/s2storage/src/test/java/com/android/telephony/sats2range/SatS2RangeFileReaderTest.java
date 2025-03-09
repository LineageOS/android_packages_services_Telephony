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
import static org.junit.Assert.assertNotNull;

import com.android.telephony.sats2range.read.SatS2RangeFileFormat;
import com.android.telephony.sats2range.read.SatS2RangeFileReader;
import com.android.telephony.sats2range.read.SuffixTableRange;
import com.android.telephony.sats2range.utils.TestUtils;
import com.android.telephony.sats2range.write.SatS2RangeFileWriter;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class SatS2RangeFileReaderTest {
    @Test
    public void findEntryByCellId() throws IOException {
        File file = File.createTempFile("test", ".dat");

        SatS2RangeFileFormat fileFormat;
        boolean isAllowedList = true;
        SuffixTableRange expectedRange1, expectedRange2, expectedRange3;
        try (SatS2RangeFileWriter satS2RangeFileWriter = SatS2RangeFileWriter.open(
                file, TestUtils.createS2RangeFileFormat(isAllowedList))) {
            fileFormat = satS2RangeFileWriter.getFileFormat();

            // Two ranges that share a prefix.
            expectedRange1 = new SuffixTableRange(
                    TestUtils.createCellId(fileFormat, 1, 1000, 1000),
                    TestUtils.createCellId(fileFormat, 1, 1000, 2000));
            expectedRange2 = new SuffixTableRange(
                    TestUtils.createCellId(fileFormat, 1, 1000, 2000),
                    TestUtils.createCellId(fileFormat, 1, 1000, 3000));
            // This range has a different prefix, so will be in a different suffix table.
            expectedRange3 = new SuffixTableRange(
                    TestUtils.createCellId(fileFormat, 1, 1001, 1000),
                    TestUtils.createCellId(fileFormat, 1, 1001, 2000));

            List<SuffixTableRange> ranges = new ArrayList<>();
            ranges.add(expectedRange1);
            ranges.add(expectedRange2);
            ranges.add(expectedRange3);
            satS2RangeFileWriter.createSortedSuffixBlocks(ranges.iterator());
        }

        try (SatS2RangeFileReader satS2RangeFileReader = SatS2RangeFileReader.open(file)) {
            assertEquals(isAllowedList, satS2RangeFileReader.isAllowedList());

            SuffixTableRange range1 = satS2RangeFileReader.findEntryByCellId(
                    TestUtils.createCellId(fileFormat, 1, 1000, 1500));
            assertEquals(expectedRange1, range1);

            SuffixTableRange range2 = satS2RangeFileReader.findEntryByCellId(
                    TestUtils.createCellId(fileFormat, 1, 1000, 2500));
            assertEquals(expectedRange2, range2);

            SuffixTableRange range3 = satS2RangeFileReader.findEntryByCellId(
                    TestUtils.createCellId(fileFormat, 1, 1001, 1500));
            assertEquals(expectedRange3, range3);
        }
    }

    @Test
    public void findEntryByCellIdWithEntryValue() throws IOException {
        final boolean isAllowedList = true;
        final int entryValueSizeInBytes = 4;
        final int versionNumber = 0;
        final int entryValue1 = 1;
        final int entryValue2 = 2;
        final int entryValue3 = 3;

        File file = File.createTempFile("test", ".dat");
        SatS2RangeFileFormat fileFormat;

        SuffixTableRange expectedRange1, expectedRange2, expectedRange3;
        try (SatS2RangeFileWriter satS2RangeFileWriter = SatS2RangeFileWriter.open(file,
                TestUtils.createS2RangeFileFormat(isAllowedList, entryValueSizeInBytes,
                        versionNumber))) {
            fileFormat = satS2RangeFileWriter.getFileFormat();

            // Two ranges that share a prefix.
            expectedRange1 = new SuffixTableRange(
                    TestUtils.createCellId(fileFormat, 1, 1000, 1000),
                    TestUtils.createCellId(fileFormat, 1, 1000, 2000),
                    entryValue1);
            expectedRange2 = new SuffixTableRange(
                    TestUtils.createCellId(fileFormat, 1, 1000, 2000),
                    TestUtils.createCellId(fileFormat, 1, 1000, 3000),
                    entryValue2);
            // This range has a different prefix, so will be in a different suffix table.
            expectedRange3 = new SuffixTableRange(
                    TestUtils.createCellId(fileFormat, 1, 1001, 1000),
                    TestUtils.createCellId(fileFormat, 1, 1001, 2000),
                    entryValue3);

            List<SuffixTableRange> ranges = new ArrayList<>();
            ranges.add(expectedRange1);
            ranges.add(expectedRange2);
            ranges.add(expectedRange3);
            satS2RangeFileWriter.createSortedSuffixBlocks(ranges.iterator());
        }

        try (SatS2RangeFileReader satS2RangeFileReader = SatS2RangeFileReader.open(file)) {
            assertEquals(isAllowedList, satS2RangeFileReader.isAllowedList());

            SuffixTableRange range1 = satS2RangeFileReader.findEntryByCellId(
                    TestUtils.createCellId(fileFormat, 1, 1000, 1500));
            assertNotNull(range1);
            assertEquals(expectedRange1, range1);
            assertEquals(entryValue1, range1.getEntryValue());

            SuffixTableRange range2 = satS2RangeFileReader.findEntryByCellId(
                    TestUtils.createCellId(fileFormat, 1, 1000, 2500));
            assertNotNull(range2);
            assertEquals(expectedRange2, range2);
            assertEquals(entryValue2, range2.getEntryValue());

            SuffixTableRange range3 = satS2RangeFileReader.findEntryByCellId(
                    TestUtils.createCellId(fileFormat, 1, 1001, 1500));
            assertNotNull(range3);
            assertEquals(expectedRange3, range3);
            assertEquals(entryValue3, range3.getEntryValue());
        }
    }
}
