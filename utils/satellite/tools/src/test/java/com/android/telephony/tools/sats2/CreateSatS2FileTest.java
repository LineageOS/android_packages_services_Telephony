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

package com.android.telephony.tools.sats2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import com.android.telephony.sats2range.read.SatS2RangeFileFormat;
import com.android.telephony.sats2range.read.SatS2RangeFileReader;
import com.android.telephony.sats2range.read.SuffixTableRange;
import com.android.telephony.sats2range.utils.TestUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;


/** Tests for {@link CreateSatS2File} */
public final class CreateSatS2FileTest {
    private static final int S2_LEVEL = 12;
    private static final boolean IS_ALLOWED_LIST = true;
    private static final int ENTRY_VALUE_BYTE_SIZE = 4;
    private static final int VERSION_NUMBER = 0;
    private Path mTempDirPath;

    @Before
    public void setUp() throws IOException {
        mTempDirPath = TestUtils.createTempDir(this.getClass());
    }

    @After
    public void tearDown() throws IOException {
        if (mTempDirPath != null) {
            TestUtils.deleteDirectory(mTempDirPath);
        }
    }

    @Test
    public void testCreateSatS2FileWithInvalidInput() throws Exception {
        Path inputDirPath = mTempDirPath.resolve("input");
        Files.createDirectory(inputDirPath);
        Path inputFilePath = inputDirPath.resolve("s2cells.txt");

        Path outputDirPath = mTempDirPath.resolve("output");
        Files.createDirectory(outputDirPath);
        Path outputFilePath = outputDirPath.resolve("sats2.dat");

        // Create test input S2 cell file
        SatS2RangeFileFormat fileFormat = FileFormats.getFileFormatForLevel(S2_LEVEL,
                IS_ALLOWED_LIST, ENTRY_VALUE_BYTE_SIZE, VERSION_NUMBER);
        TestUtils.createInvalidTestS2CellFile(inputFilePath.toFile(), fileFormat);

        // Commandline input arguments
        String[] args = {
                "--input-file", inputFilePath.toAbsolutePath().toString(),
                "--s2-level", String.valueOf(S2_LEVEL),
                "--is-allowed-list", String.valueOf(IS_ALLOWED_LIST),
                "--entry-value-byte-size", String.valueOf(ENTRY_VALUE_BYTE_SIZE),
                "--version-number", String.valueOf(VERSION_NUMBER),
                "--output-file", outputFilePath.toAbsolutePath().toString()
        };

        // Execute the tool CreateSatS2File and expect exception
        try {
            CreateSatS2File.main(args);
        } catch (Exception ex) {
            // Expected exception
            return;
        }
        fail("Exception should have been caught");
    }

    @Test
    public void testCreateSatS2FileWithValidInput() throws Exception {
        Path inputDirPath = mTempDirPath.resolve("input");
        Files.createDirectory(inputDirPath);
        Path inputFilePath = inputDirPath.resolve("s2cells.txt");

        Path outputDirPath = mTempDirPath.resolve("output");
        Files.createDirectory(outputDirPath);
        Path outputFilePath = outputDirPath.resolve("sats2.dat");

        /*
         * Create test input S2 cell file with the following ranges:
         * 1) [(prefix=0b100_11111111, suffix=1000), (prefix=0b100_11111111, suffix=2000),
         * entryValue=1)
         * 2) [(prefix=0b100_11111111, suffix=2001), (prefix=0b100_11111111, suffix=3000),
         * entryValue=2)
         * 3) [(prefix=0b101_11111111, suffix=1000), (prefix=0b101_11111111, suffix=2001)),
         * entryValue=3)
         */
        SatS2RangeFileFormat fileFormat = FileFormats.getFileFormatForLevel(S2_LEVEL,
                IS_ALLOWED_LIST, ENTRY_VALUE_BYTE_SIZE, VERSION_NUMBER);
        TestUtils.createValidTestS2CellFile(inputFilePath.toFile(), fileFormat);

        // Commandline input arguments
        String[] args = {
                "--input-file", inputFilePath.toAbsolutePath().toString(),
                "--s2-level", String.valueOf(S2_LEVEL),
                "--is-allowed-list", String.valueOf(IS_ALLOWED_LIST),
                "--entry-value-byte-size", String.valueOf(ENTRY_VALUE_BYTE_SIZE),
                "--version-number", String.valueOf(VERSION_NUMBER),
                "--output-file", outputFilePath.toAbsolutePath().toString()
        };

        // Execute the tool CreateSatS2File and expect successful result
        try {
            CreateSatS2File.main(args);
        } catch (Exception ex) {
            fail("Unexpected exception when executing the tool ex=" + ex);
        }

        // Validate the output block file
        try {
            SatS2RangeFileReader satS2RangeFileReader =
                         SatS2RangeFileReader.open(outputFilePath.toFile());

            // Verify an edge cell (prefix=0b100_11111111, suffix=100)
            long s2CellId = fileFormat.createCellId(0b100_11111111, 100);
            assertNull(satS2RangeFileReader.findEntryByCellId(s2CellId));

            // Verify a middle cell (prefix=0b100_11111111, suffix=2000)
            s2CellId = fileFormat.createCellId(0b100_11111111, 2000);
            assertNull(satS2RangeFileReader.findEntryByCellId(s2CellId));

            // Verify a middle cell (prefix=0b100_11111111, suffix=2000)
            s2CellId = fileFormat.createCellId(0b100_11111111, 2000);
            assertNull(satS2RangeFileReader.findEntryByCellId(s2CellId));

            // Verify an edge cell (prefix=0b100_11111111, suffix=4000)
            s2CellId = fileFormat.createCellId(0b100_11111111, 4000);
            assertNull(satS2RangeFileReader.findEntryByCellId(s2CellId));

            // Verify an edge cell (prefix=0b101_11111111, suffix=500)
            s2CellId = fileFormat.createCellId(0b101_11111111, 500);
            assertNull(satS2RangeFileReader.findEntryByCellId(s2CellId));

            // Verify an edge cell (prefix=0b101_11111111, suffix=2001)
            s2CellId = fileFormat.createCellId(0b101_11111111, 2500);
            assertNull(satS2RangeFileReader.findEntryByCellId(s2CellId));

            // Verify an edge cell (prefix=0b101_11111111, suffix=2500)
            s2CellId = fileFormat.createCellId(0b101_11111111, 2500);
            assertNull(satS2RangeFileReader.findEntryByCellId(s2CellId));
        } catch (Exception ex) {
            fail("Unexpected exception when validating the output ex=" + ex);
        }
    }

    @Test
    public void testCreateSatS2FileWithValidCellIdAndValidEntryValue() throws Exception {
        Path inputDirPath = mTempDirPath.resolve("input");
        Files.createDirectory(inputDirPath);
        Path inputFilePath = inputDirPath.resolve("s2cells.txt");

        Path outputDirPath = mTempDirPath.resolve("output");
        Files.createDirectory(outputDirPath);
        Path outputFilePath = outputDirPath.resolve("sats2.dat");

        /*
         * Create test input S2 cell file with the following ranges:
         * 1) [(prefix=0b100_11111111, suffix=1000), (prefix=0b100_11111111, suffix=1500),
         * entryValue=1)
         * 2) [(prefix=0b100_11111111, suffix=1500), (prefix=0b100_11111111, suffix=2000),
         * entryValue=2)
         * 3) [(prefix=0b100_11111111, suffix=2001), (prefix=0b100_11111111, suffix=3000),
         * entryValue=3)
         * 4) [(prefix=0b101_11111111, suffix=1000), (prefix=0b101_11111111, suffix=2001)),
         * entryValue=4)
         */
        SatS2RangeFileFormat fileFormat = FileFormats.getFileFormatForLevel(S2_LEVEL,
                IS_ALLOWED_LIST, ENTRY_VALUE_BYTE_SIZE, VERSION_NUMBER);
        TestUtils.createValidTestS2CellFileWithValidEntryValue(inputFilePath.toFile(), fileFormat);

        // Commandline input arguments
        String[] args = {
                "--input-file", inputFilePath.toAbsolutePath().toString(),
                "--s2-level", String.valueOf(S2_LEVEL),
                "--is-allowed-list", String.valueOf(IS_ALLOWED_LIST),
                "--entry-value-byte-size", String.valueOf(ENTRY_VALUE_BYTE_SIZE),
                "--version-number", String.valueOf(VERSION_NUMBER),
                "--output-file", outputFilePath.toAbsolutePath().toString()
        };

        // Execute the tool CreateSatS2File and expect successful result
        try {
            CreateSatS2File.main(args);
        } catch (Exception ex) {
            fail("Unexpected exception when executing the tool ex=" + ex);
        }


        // Validate the output block file
        try {
            SatS2RangeFileReader satS2RangeFileReader =
                    SatS2RangeFileReader.open(outputFilePath.toFile());

            // Verify a cell outside the valid range (prefix=0b100_11111111, suffix=0)
            long s2CellId = fileFormat.createCellId(0b100_11111111, 0);
            SuffixTableRange suffixTableRange = satS2RangeFileReader.findEntryByCellId(s2CellId);
            assertNull(suffixTableRange);

            // Verify a cell outside the valid range (prefix=0b100_11111111, suffix=0)
            s2CellId = fileFormat.createCellId(0b100_11111111, 999);
            suffixTableRange = satS2RangeFileReader.findEntryByCellId(s2CellId);
            assertNull(suffixTableRange);

            // Verify the first cell (prefix=0b100_11111111, suffix=1000)
            int expectedEntryValue = 1;
            s2CellId = fileFormat.createCellId(0b100_11111111, 1000);
            suffixTableRange = satS2RangeFileReader.findEntryByCellId(s2CellId);
            assertNotNull(suffixTableRange);
            Integer entryValue = suffixTableRange.getEntryValue();
            assertNotNull(entryValue);
            assertEquals(expectedEntryValue, (int) entryValue);

            // Verify the mid cell of 1st range (prefix=0b100_11111111, suffix=1499)
            s2CellId = fileFormat.createCellId(0b100_11111111, 1250);
            suffixTableRange = satS2RangeFileReader.findEntryByCellId(s2CellId);
            assertNotNull(suffixTableRange);
            entryValue = suffixTableRange.getEntryValue();
            assertNotNull(entryValue);
            assertEquals(expectedEntryValue, (int) entryValue);

            // Verify the end of 1st range (prefix=0b100_11111111, suffix=1499)
            s2CellId = fileFormat.createCellId(0b100_11111111, 1499);
            suffixTableRange = satS2RangeFileReader.findEntryByCellId(s2CellId);
            assertNotNull(suffixTableRange);
            entryValue = suffixTableRange.getEntryValue();
            assertNotNull(entryValue);
            assertEquals(expectedEntryValue, (int) entryValue);

            // Verify the first cell of 2nd range (prefix=0b100_11111111, suffix=1500)
            expectedEntryValue = 2;
            s2CellId = fileFormat.createCellId(0b100_11111111, 1500);
            suffixTableRange = satS2RangeFileReader.findEntryByCellId(s2CellId);
            assertNotNull(suffixTableRange);
            entryValue = suffixTableRange.getEntryValue();
            assertNotNull(entryValue);
            assertEquals(expectedEntryValue, (int) entryValue);

            // Verify the mid cell of 2nd range (prefix=0b100_11111111, suffix=1750)
            s2CellId = fileFormat.createCellId(0b100_11111111, 1750);
            suffixTableRange = satS2RangeFileReader.findEntryByCellId(s2CellId);
            assertNotNull(suffixTableRange);
            entryValue = suffixTableRange.getEntryValue();
            assertNotNull(entryValue);
            assertEquals(expectedEntryValue, (int) entryValue);

            // Verify the end cell of 2nd range (prefix=0b100_11111111, suffix=1999)
            s2CellId = fileFormat.createCellId(0b100_11111111, 1999);
            suffixTableRange = satS2RangeFileReader.findEntryByCellId(s2CellId);
            assertNotNull(suffixTableRange);
            entryValue = suffixTableRange.getEntryValue();
            assertNotNull(entryValue);
            assertEquals(expectedEntryValue, (int) entryValue);

            // Verify a cell outside the valid range (prefix=0b100_11111111, suffix=2000)
            s2CellId = fileFormat.createCellId(0b100_11111111, 2000);
            suffixTableRange = satS2RangeFileReader.findEntryByCellId(s2CellId);
            assertNull(suffixTableRange);

            expectedEntryValue = 3;
            // Verify the first cell of 3rd range (prefix=0b100_11111111, suffix=2001)
            s2CellId = fileFormat.createCellId(0b100_11111111, 2001);
            suffixTableRange = satS2RangeFileReader.findEntryByCellId(s2CellId);
            assertNotNull(suffixTableRange);
            entryValue = suffixTableRange.getEntryValue();
            assertNotNull(entryValue);
            assertEquals(expectedEntryValue, (int) entryValue);

            // Verify the mid cell of 3rd range (prefix=0b100_11111111, suffix=2001)
            s2CellId = fileFormat.createCellId(0b100_11111111, 2500);
            suffixTableRange = satS2RangeFileReader.findEntryByCellId(s2CellId);
            assertNotNull(suffixTableRange);
            entryValue = suffixTableRange.getEntryValue();
            assertNotNull(entryValue);
            assertEquals(expectedEntryValue, (int) entryValue);

            // Verify the end cell of 3rd range (prefix=0b100_11111111, suffix=2999)
            s2CellId = fileFormat.createCellId(0b100_11111111, 2999);
            suffixTableRange = satS2RangeFileReader.findEntryByCellId(s2CellId);
            assertNotNull(suffixTableRange);
            entryValue = suffixTableRange.getEntryValue();
            assertNotNull(entryValue);
            assertEquals(expectedEntryValue, (int) entryValue);

            // Verify a cell outside the valid range(prefix=0b100_11111111, suffix=3000)
            s2CellId = fileFormat.createCellId(0b100_11111111, 3000);
            suffixTableRange = satS2RangeFileReader.findEntryByCellId(s2CellId);
            assertNull(suffixTableRange);

            int maxSuffixValue = (1 << fileFormat.getSuffixBitCount()) - 1;
            // Verify a cell outside the valid range (prefix=0b100_11111111, suffix=max value)
            s2CellId = fileFormat.createCellId(0b100_11111111, maxSuffixValue);
            suffixTableRange = satS2RangeFileReader.findEntryByCellId(s2CellId);
            assertNull(suffixTableRange);

            // Verify a cell outside the valid range (prefix=0b101_11111111, suffix=0)
            s2CellId = fileFormat.createCellId(0b101_11111111, 0);
            suffixTableRange = satS2RangeFileReader.findEntryByCellId(s2CellId);
            assertNull(suffixTableRange);

            // Verify a cell outside the valid range (prefix=0b101_11111111, suffix=999)
            s2CellId = fileFormat.createCellId(0b101_11111111, 999);
            suffixTableRange = satS2RangeFileReader.findEntryByCellId(s2CellId);
            assertNull(suffixTableRange);

            // Verify the first cell of 4th range (prefix=0b101_11111111, suffix=1000)
            expectedEntryValue = 4;
            s2CellId = fileFormat.createCellId(0b101_11111111, 1000);
            suffixTableRange = satS2RangeFileReader.findEntryByCellId(s2CellId);
            assertNotNull(suffixTableRange);
            entryValue = suffixTableRange.getEntryValue();
            assertNotNull(entryValue);
            assertEquals(expectedEntryValue, (int) entryValue);

            // Verify a mid cell of 4th range (prefix=0b101_11111111, suffix=1500)
            s2CellId = fileFormat.createCellId(0b101_11111111, 1500);
            suffixTableRange = satS2RangeFileReader.findEntryByCellId(s2CellId);
            assertNotNull(suffixTableRange);
            entryValue = suffixTableRange.getEntryValue();
            assertNotNull(entryValue);
            assertEquals(expectedEntryValue, (int) entryValue);

            // Verify a cell of 4th range (prefix=0b101_11111111, suffix=2000)
            s2CellId = fileFormat.createCellId(0b101_11111111, 2000);
            suffixTableRange = satS2RangeFileReader.findEntryByCellId(s2CellId);
            assertNotNull(suffixTableRange);
            entryValue = suffixTableRange.getEntryValue();
            assertNotNull(entryValue);
            assertEquals(expectedEntryValue, (int) entryValue);

            // Verify the end cell of 4th range (prefix=0b101_11111111, suffix=2001)
            s2CellId = fileFormat.createCellId(0b101_11111111, 2001);
            suffixTableRange = satS2RangeFileReader.findEntryByCellId(s2CellId);
            assertNull(suffixTableRange);

            // Verify a cell outside the valid range (prefix=0b101_11111111, suffix=max value)
            s2CellId = fileFormat.createCellId(0b101_11111111, maxSuffixValue);
            suffixTableRange = satS2RangeFileReader.findEntryByCellId(s2CellId);
            assertNull(suffixTableRange);
        } catch (Exception ex) {
            fail("Unexpected exception when validating the output ex=" + ex);
        }
    }

    @Test
    public void testCreateSatS2FileWithValidInputAndRandomEntryValue() throws Exception {
        String inputFileName = "s2cells_random_entry_value.txt";
        Path inputDirPath = mTempDirPath.resolve("input");
        Path inputFilePath = inputDirPath.resolve(inputFileName);
        TestUtils.copyTestResource(getClass(), inputFileName, inputDirPath);

        Path outputDirPath = mTempDirPath.resolve("output");
        Files.createDirectory(outputDirPath);
        Path outputFilePath = outputDirPath.resolve("sats2.dat");

        // Commandline input arguments
        String[] args = {
                "--input-file", inputFilePath.toAbsolutePath().toString(),
                "--s2-level", String.valueOf(S2_LEVEL),
                "--is-allowed-list", String.valueOf(IS_ALLOWED_LIST),
                "--entry-value-byte-size", String.valueOf(ENTRY_VALUE_BYTE_SIZE),
                "--version-number", String.valueOf(VERSION_NUMBER),
                "--output-file", outputFilePath.toAbsolutePath().toString()
        };

        // Execute the tool CreateSatS2File and expect successful result
        try {
            CreateSatS2File.main(args);
        } catch (Exception ex) {
            fail("Unexpected exception when executing the tool ex=" + ex);
        }
    }

    @Test
    public void testCreateSatS2FileWithValidCellIdAndInValidInputParameter() throws Exception {
        Path inputDirPath = mTempDirPath.resolve("input");
        Files.createDirectory(inputDirPath);
        Path inputFilePath = inputDirPath.resolve("s2cells.txt");

        Path outputDirPath = mTempDirPath.resolve("output");
        Files.createDirectory(outputDirPath);
        Path outputFilePath = outputDirPath.resolve("sats2.dat");

        /*
         * Create test input S2 cell file with the following ranges:
         * 1) [(prefix=0b100_11111111, suffix=1000), (prefix=0b100_11111111, suffix=1500),
         * entryValue=1)
         * 2) [(prefix=0b100_11111111, suffix=1500), (prefix=0b100_11111111, suffix=2000),
         * entryValue=2)
         * 3) [(prefix=0b100_11111111, suffix=2001), (prefix=0b100_11111111, suffix=3000),
         * entryValue=3)
         * 4) [(prefix=0b101_11111111, suffix=1000), (prefix=0b101_11111111, suffix=2001)),
         * entryValue=4)
         */
        SatS2RangeFileFormat fileFormat = FileFormats.getFileFormatForLevel(S2_LEVEL,
                IS_ALLOWED_LIST, ENTRY_VALUE_BYTE_SIZE, VERSION_NUMBER);
        TestUtils.createValidTestS2CellFileWithValidEntryValue(inputFilePath.toFile(), fileFormat);

        // Entry value size in byte < 0
        String[] args = {
                "--input-file", inputFilePath.toAbsolutePath().toString(),
                "--s2-level", String.valueOf(S2_LEVEL),
                "--is-allowed-list", String.valueOf(IS_ALLOWED_LIST),
                "--entry-value-byte-size", String.valueOf(-1),
                "--version-number", String.valueOf(VERSION_NUMBER),
                "--output-file", outputFilePath.toAbsolutePath().toString()
        };

        // Execute the tool CreateSatS2File and expect exception
        try {
            CreateSatS2File.main(args);
            fail("Exception should have been caught");
        } catch (IllegalArgumentException ex) {
            // Expected exception
        } catch (Exception ex) {
            // Unexpected exception
            fail("Unexpected exception, ex=" + ex);
        }

        // isAllowList is false && entryValueSizeInBytes > 0
        args = new String[]{
                "--input-file", inputFilePath.toAbsolutePath().toString(),
                "--s2-level", String.valueOf(S2_LEVEL),
                "--is-allowed-list", String.valueOf(false),
                "--entry-value-byte-size", String.valueOf(ENTRY_VALUE_BYTE_SIZE),
                "--version-number", String.valueOf(VERSION_NUMBER),
                "--output-file", outputFilePath.toAbsolutePath().toString()
        };

        // Execute the tool CreateSatS2File and expect exception
        try {
            CreateSatS2File.main(args);
            fail("Exception should have been caught");
        } catch (IllegalArgumentException ex) {
            // Expected exception
        } catch (Exception ex) {
            // Unexpected exception
            fail("Unexpected exception, ex=" + ex);
        }

        // entryValueSizeInBytes > 4
        args = new String[]{
                "--input-file", inputFilePath.toAbsolutePath().toString(),
                "--s2-level", String.valueOf(S2_LEVEL),
                "--is-allowed-list", String.valueOf(IS_ALLOWED_LIST),
                "--entry-value-byte-size", String.valueOf(ENTRY_VALUE_BYTE_SIZE + 1),
                "--version-number", String.valueOf(VERSION_NUMBER),
                "--output-file", outputFilePath.toAbsolutePath().toString()
        };

        // Execute the tool CreateSatS2File and expect exception
        try {
            CreateSatS2File.main(args);
            fail("Exception should have been caught");
        } catch (IllegalArgumentException ex) {
            // Expected exception
        } catch (Exception ex) {
            // Unexpected exception
            fail("Unexpected exception, ex=" + ex);
        }
    }
}
