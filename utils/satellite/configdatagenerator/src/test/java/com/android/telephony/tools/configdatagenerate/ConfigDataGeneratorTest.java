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

import static com.android.telephony.tools.configdatagenerate.Util.SATELLITE_DATA_SUPPORT_ALL;
import static com.android.telephony.tools.configdatagenerate.Util.SATELLITE_DATA_SUPPORT_BANDWIDTH_CONSTRAINED;
import static com.android.telephony.tools.configdatagenerate.Util.SATELLITE_DATA_SUPPORT_ONLY_RESTRICTED;
import static com.android.telephony.tools.configdatagenerate.Util.SERVICE_TYPE_INVALID;
import static com.android.telephony.tools.configdatagenerate.Util.SERVICE_TYPE_MMS;
import static com.android.telephony.tools.configdatagenerate.Util.SERVICE_TYPE_SMS;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.android.internal.telephony.satellite.SatelliteConfigData.CarrierRoamingConfigProto;
import com.android.internal.telephony.satellite.SatelliteConfigData.CarrierSupportedSatelliteServicesProto;
import com.android.internal.telephony.satellite.SatelliteConfigData.SatelliteConfigProto;
import com.android.internal.telephony.satellite.SatelliteConfigData.SatelliteProviderCapabilityProto;
import com.android.internal.telephony.satellite.SatelliteConfigData.SatelliteRegionProto;
import com.android.internal.telephony.satellite.SatelliteConfigData.TelephonyConfigProto;

import com.google.protobuf.ByteString;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

public class ConfigDataGeneratorTest {

    private static final String PLMN_VALID_310062 = "310062";
    private static final String PLMN_VALID_45005 = "45005";
    private static final String PLMN_INVALID_310062222 = "310062222";
    private static final String COUNTRY_CODE_US = "US";
    private static final String COUNTRY_CODE_INVALID = "USSSS";
    private static final int VERSION_VALID = 14;
    private static final int CARRIER_ID_VALID = 1;

    private Path mTempDirPath;
    private Path mInputDirPath;
    private Path mOutputDirPath;

    private File mInputXmlFile;
    private Path mInputXmlFilePath;
    private Path mOutputPbFilePath;

    @Before
    public void setUp() throws IOException {
        mTempDirPath = createTempDir(this.getClass());
        mInputDirPath = mTempDirPath.resolve("input");
        mOutputDirPath = mTempDirPath.resolve("output");
    }

    @After
    public void tearDown() throws IOException {
        if (mTempDirPath != null) {
            deleteDirectory(mTempDirPath);
        }
    }

    /**
     * Prepares the input and output file paths and directories for a test run.
     * This method ensures that the necessary input and output directories exist.
     * If they don't, it creates them. It then constructs the full paths for
     * the input XML file and the output Protocol Buffer (.pb) file, incorporating
     * a unique count for each file.
     *
     * @param count A unique integer used to identify the input and output files.
     *              This is typically used to differentiate between multiple test runs or data sets.
     * @throws IOException If an I/O error occurs during directory creation or
     *                     file path resolution.
     */
    private void prepareInAndOutData(int count) throws IOException {
        if (!Files.exists(mInputDirPath)) {
            System.out.println("Create mInputDirPath: " + mInputDirPath.toString());
            Files.createDirectory(mInputDirPath);
        }

        mInputXmlFilePath = mInputDirPath.resolve(String.format("test_input_%s.xml", count));
        String inputXmlFileName = mInputXmlFilePath.toAbsolutePath().toString();
        mInputXmlFile = new File(inputXmlFileName);

        if (!Files.exists(mOutputDirPath)) {
            System.out.println("Create mOutputDirPath: " + mOutputDirPath.toString());
            Files.createDirectory(mOutputDirPath);
        }
        mOutputPbFilePath = mOutputDirPath.resolve(String.format("test_out_%s.pb", count));
    }

    /**
     * Retrieves the path to an S2 Cell data file, creating it if it doesn't exist
     * and writing dummy data to it for testing purposes.
     *
     * @param empty A boolean flag indicating whether a empty S2 Cell file path should be returned.
     *              If {@code false}, the method immediately returns {@code null}.
     * @return The absolute path to the S2 Cell data file ("sats2.dat") as a String,
     * or {@code null} if {@code empty} is {@code false}.
     * @throws IOException If an I/O error occurs during directory creation or
     *                     file writing.
     */
    private String getS2CellFile(boolean empty) throws IOException {
        if (!empty) {
            System.out.println("Set s2CellFile node as empty");
            return null;
        }
        if (!Files.exists(mInputDirPath)) {
            System.out.println("Create mInputDirPath: " + mInputDirPath.toString());
            Files.createDirectory(mInputDirPath);
        }
        Path inputS2CellFilePath = mInputDirPath.resolve("sats2.dat");
        String inputS2CellFileName = inputS2CellFilePath.toAbsolutePath().toString();
        ByteString inputByteStringForS2Cell = ByteString.copyFromUtf8("Test ByteString!");
        writeByteStringToFile(inputS2CellFileName, inputByteStringForS2Cell);
        return inputS2CellFileName;
    }

    /**
     * Retrieves the path to an S2 Cell data file, creating it if it doesn't exist
     * and writing dummy data to it for testing purposes.
     *
     * @param empty A boolean flag indicating whether a empty S2 Cell file path should be returned.
     *              If {@code false}, the method immediately returns {@code null}.
     * @return The absolute path to the S2 Cell data file ("sats2.dat") as a String,
     * or {@code null} if {@code empty} is {@code false}.
     * @throws IOException If an I/O error occurs during directory creation or
     *                     file writing.
     */
    private String getSACJsonFile(boolean empty) throws IOException {
        if (!empty) {
            System.out.println("Set satellite_access_config.json node as empty");
            return null;
        }
        if (!Files.exists(mInputDirPath)) {
            System.out.println("Create mInputDirPath: " + mInputDirPath.toString());
            Files.createDirectory(mInputDirPath);
        }
        Path inputSatelliteAccessConfigFilePath =
                mInputDirPath.resolve("satellite_access_config.json");
        String inputSatelliteAccessConfigFileAbsolutePath =
                inputSatelliteAccessConfigFilePath.toAbsolutePath().toString();
        ByteString inputSatelliteAccessConfigContent =
                ByteString.copyFromUtf8("Test ByteString for satellite access config!");
        writeByteStringToFile(
                inputSatelliteAccessConfigFileAbsolutePath, inputSatelliteAccessConfigContent);
        return inputSatelliteAccessConfigFileAbsolutePath;
    }

    private String getPlmn(boolean empty) {
        if (!empty) {
            System.out.println("Set plmn node as empty");
            return PLMN_INVALID_310062222;
        }
        return PLMN_VALID_310062;
    }

    private Integer getVersion(boolean empty) {
        if (!empty) {
            System.out.println("Set version node as empty");
            return null;
        }
        return VERSION_VALID;
    }

    private Integer getCarrierId(boolean empty) {
        if (!empty) {
            System.out.println("Set carrierId node as empty");
            return null;
        }
        return CARRIER_ID_VALID;
    }

    private Integer getServiceType(boolean empty) {
        if (!empty) {
            System.out.println("Set service type node as empty");
            return null;
        }
        return SERVICE_TYPE_SMS;
    }

    private Boolean getIsAllowed(boolean empty) {
        if (!empty) {
            System.out.println("Set isallowed node as empty");
            return null;
        }
        return true;
    }

    private Integer getMaxAllowedDataMode(boolean empty) {
        if (!empty) {
            System.out.println("Set max allowed data mode node as empty");
            return null;
        }
        return SATELLITE_DATA_SUPPORT_BANDWIDTH_CONSTRAINED;
    }

    private String getCountryCode(boolean empty) {
        if (!empty) {
            System.out.println("Set country code node as empty");
            return null;
        }
        return COUNTRY_CODE_US;
    }


    @Test
    public void testConfigDataGeneratorWithInvalidPlmn() throws IOException {
        prepareInAndOutData(1);
        createInputXml(
                mInputXmlFile,
                VERSION_VALID,
                CARRIER_ID_VALID,
                PLMN_INVALID_310062222,
                SERVICE_TYPE_SMS,
                SATELLITE_DATA_SUPPORT_BANDWIDTH_CONSTRAINED,
                COUNTRY_CODE_US,
                true,
                getS2CellFile(true),
                getSACJsonFile(true));

        String[] args = {
                "--input-file", mInputXmlFilePath.toAbsolutePath().toString(),
                "--output-file", mOutputPbFilePath.toAbsolutePath().toString()
        };
        try {
            ConfigDataGenerator.main(args);
        } catch (Exception ex) {
            // Expected exception because input plmn is invalid
            return;
        }
        fail("Exception should have been caught");
    }

    @Test
    public void testConfigDataGeneratorWithInvalidService() throws Exception {
        prepareInAndOutData(1);
        createInputXml(
                mInputXmlFile,
                VERSION_VALID,
                CARRIER_ID_VALID,
                PLMN_VALID_310062,
                SERVICE_TYPE_INVALID,
                SATELLITE_DATA_SUPPORT_BANDWIDTH_CONSTRAINED,
                COUNTRY_CODE_US,
                true,
                getS2CellFile(true),
                getSACJsonFile(true));
        String[] args = {
                "--input-file", mInputXmlFilePath.toAbsolutePath().toString(),
                "--output-file", mOutputPbFilePath.toAbsolutePath().toString()
        };
        try {
            ConfigDataGenerator.main(args);
        } catch (Exception ex) {
            // Expected exception because input allowed service is invalid
            return;
        }
        fail("Exception should have been caught");
    }

    @Test
    public void testConfigDataGeneratorWithInvalidCountryCode() throws Exception {
        prepareInAndOutData(1);
        createInputXml(
                mInputXmlFile,
                VERSION_VALID,
                CARRIER_ID_VALID,
                PLMN_VALID_310062,
                SERVICE_TYPE_INVALID,
                SATELLITE_DATA_SUPPORT_BANDWIDTH_CONSTRAINED,
                COUNTRY_CODE_INVALID,
                true,
                getS2CellFile(true),
                getSACJsonFile(true));
        String[] args = {
                "--input-file", mInputXmlFilePath.toAbsolutePath().toString(),
                "--output-file", mOutputPbFilePath.toAbsolutePath().toString()
        };
        try {
            ConfigDataGenerator.main(args);
        } catch (Exception ex) {
            // Expected exception because input country code is invalid
            return;
        }
        fail("Exception should have been caught");
    }

    @Test
    public void testConfigDataGeneratorWithInvalidMaxAllowedDataMode() throws Exception {
        prepareInAndOutData(1);

        int countCaughtException = 0;
        createInputXml(
                mInputXmlFile,
                VERSION_VALID,
                CARRIER_ID_VALID,
                PLMN_VALID_310062,
                SERVICE_TYPE_INVALID,
                SATELLITE_DATA_SUPPORT_ALL + 1,
                COUNTRY_CODE_INVALID,
                true,
                getS2CellFile(true),
                getSACJsonFile(true));
        String[] args1 = {
                "--input-file", mInputXmlFilePath.toAbsolutePath().toString(),
                "--output-file", mOutputPbFilePath.toAbsolutePath().toString()
        };
        try {
            ConfigDataGenerator.main(args1);
        } catch (Exception ex) {
            // Expected exception because input country code is invalid
            countCaughtException++;
        }
        createInputXml(
                mInputXmlFile,
                VERSION_VALID,
                CARRIER_ID_VALID,
                PLMN_VALID_310062,
                SERVICE_TYPE_INVALID,
                SATELLITE_DATA_SUPPORT_ONLY_RESTRICTED - 1,
                COUNTRY_CODE_US,
                true,
                getS2CellFile(true),
                getSACJsonFile(true));
        String[] args2 = {
                "--input-file", mInputXmlFilePath.toAbsolutePath().toString(),
                "--output-file", mOutputPbFilePath.toAbsolutePath().toString()
        };
        try {
            ConfigDataGenerator.main(args2);
        } catch (Exception ex) {
            // Expected exception because input country code is invalid
            countCaughtException++;
        }
        if (countCaughtException != 2) {
            fail("Exception should have been caught");
        }
    }

    @Test
    public void testConfigDataGeneratorWithValidInput() throws Exception {
        prepareInAndOutData(1);
        createInputXml(
                mInputXmlFile,
                getVersion(true),
                getCarrierId(true),
                getPlmn(true),
                getServiceType(true),
                getMaxAllowedDataMode(true),
                getCountryCode(true),
                getIsAllowed(true),
                getS2CellFile(true),
                getSACJsonFile(true));

        String[] args = {
                "--input-file", mInputXmlFilePath.toAbsolutePath().toString(),
                "--output-file", mOutputPbFilePath.toAbsolutePath().toString()
        };
        try {
            ConfigDataGenerator.main(args);
        } catch (Exception ex) {
            fail("Unexpected exception when executing the tool ex=" + ex);
        }

        Path filePath = Paths.get(mOutputPbFilePath.toAbsolutePath().toString());
        byte[] fileBytes = Files.readAllBytes(filePath);
        TelephonyConfigProto telephonyConfigProto = TelephonyConfigProto.parseFrom(fileBytes);
        SatelliteConfigProto satelliteConfigProto = telephonyConfigProto.getSatellite();

        int version = satelliteConfigProto.getVersion();
        assertEquals(Objects.requireNonNull(getVersion(true)).intValue(), version);
        CarrierSupportedSatelliteServicesProto serviceProto =
                satelliteConfigProto.getCarrierSupportedSatelliteServices(0);
        int carrierId = serviceProto.getCarrierId();
        assertEquals(Objects.requireNonNull(getCarrierId(true)).intValue(), carrierId);
        SatelliteProviderCapabilityProto providerCapabilityProto =
                serviceProto.getSupportedSatelliteProviderCapabilities(0);
        String plmn = providerCapabilityProto.getCarrierPlmn();
        assertEquals(getPlmn(true), plmn);
        int allowedService = providerCapabilityProto.getAllowedServices(0);
        assertEquals(
                Objects.requireNonNull(getServiceType(true)).intValue(), allowedService);

        CarrierRoamingConfigProto carrierRoamingConfigProto =
                satelliteConfigProto.getCarrierRoamingConfig();
        int maxAllowedDataMode = carrierRoamingConfigProto.getMaxAllowedDataMode();
        assertEquals(SATELLITE_DATA_SUPPORT_BANDWIDTH_CONSTRAINED, maxAllowedDataMode);

        SatelliteRegionProto regionProto = satelliteConfigProto.getDeviceSatelliteRegion();
        String countryCode = regionProto.getCountryCodes(0);
        assertEquals(getCountryCode(true), countryCode);
        ByteString s2CellFile = regionProto.getS2CellFile();
        byte[] fileBytesForInputS2CellFile = Files.readAllBytes(Paths.get(getS2CellFile(true)));
        ByteString inputS2CellFile = ByteString.copyFrom(fileBytesForInputS2CellFile);
        assertEquals(inputS2CellFile, s2CellFile);
        boolean isAllowed = regionProto.getIsAllowed();
        assertEquals(getIsAllowed(true), isAllowed);

        ByteString outputSatelliteAccessConfigFileContent =
                regionProto.getSatelliteAccessConfigFile();
        byte[] inputSatelliteAccessConfigFileContentAsBytes =
                Files.readAllBytes(Paths.get(getSACJsonFile(true)));
        ByteString inputSatelliteAccessConfigFileContent =
                ByteString.copyFrom(inputSatelliteAccessConfigFileContentAsBytes);
        assertEquals(inputSatelliteAccessConfigFileContent, outputSatelliteAccessConfigFileContent);
    }

    @Test
    public void testSatelliteAccessConfigFileNotPresent() throws Exception {
        prepareInAndOutData(1);
        createInputXml(
                mInputXmlFile,
                Objects.requireNonNull(getVersion(true)),
                Objects.requireNonNull(getCarrierId(true)),
                getPlmn(true),
                Objects.requireNonNull(getServiceType(true)),
                Objects.requireNonNull(getMaxAllowedDataMode(true)),
                getCountryCode(true),
                getIsAllowed(true),
                getS2CellFile(true),
                getSACJsonFile(false));
        String[] args = {
                "--input-file", mInputXmlFilePath.toAbsolutePath().toString(),
                "--output-file", mOutputPbFilePath.toAbsolutePath().toString()
        };
        try {
            ConfigDataGenerator.main(args);
        } catch (Exception ex) {
            fail("Unexpected exception when executing the tool ex=" + ex);
        }

        Path filePath = Paths.get(mOutputPbFilePath.toAbsolutePath().toString());
        byte[] fileBytes = Files.readAllBytes(filePath);
        TelephonyConfigProto telephonyConfigProto = TelephonyConfigProto.parseFrom(fileBytes);
        SatelliteConfigProto satelliteConfigProto = telephonyConfigProto.getSatellite();
        SatelliteRegionProto regionProto = satelliteConfigProto.getDeviceSatelliteRegion();
        ByteString outputSatelliteAccessConfigFile = regionProto.getSatelliteAccessConfigFile();
        assertEquals(ByteString.EMPTY, outputSatelliteAccessConfigFile);
    }


    private boolean verifyInputXmlGenerated(
            Integer version,
            ServiceProto serviceProto,
            RoamingConfigProto roamingConfigProto,
            RegionProto regionProto) {

        try {
            createInputXmlWithProto(
                    mInputXmlFile,
                    version,
                    serviceProto,
                    roamingConfigProto,
                    regionProto);
        } catch (Exception e) {
            return false;
        }
        printXmlFileContentForDebug(mInputXmlFilePath.toAbsolutePath().toString());
        return true;
    }

    /**
     * Verifies the generation and validation of configuration data, specifically
     * ensuring that the Protocol Buffer (.pb) file is correctly produced and
     * its contents accurately reflect the input, even when certain fields are
     * optional or omitted.
     */
    private void verifyConfigDataGenerationAndValidationWithOptionalFields(
            Boolean successProtoBuffer,
            Integer version,
            ServiceProto serviceProto,
            RoamingConfigProto roamingConfigProto,
            RegionProto regionProto) throws IOException {

        assertTrue(verifyInputXmlGenerated(version, serviceProto, roamingConfigProto, regionProto));

        String[] args = {
                "--input-file", mInputXmlFilePath.toAbsolutePath().toString(),
                "--output-file", mOutputPbFilePath.toAbsolutePath().toString()
        };
        try {
            System.out.println("args1: mInputXmlFilePath: "
                    + mInputXmlFilePath.toAbsolutePath().toString());
            System.out.println("args2: mOutputPbFilePath: "
                    + mOutputPbFilePath.toAbsolutePath().toString());
            ConfigDataGenerator.main(args);
        } catch (Exception ex) {
            if (successProtoBuffer) {
                fail("ProtoBuffer should be created, exception shouldn't be happened");
            } else {
                return;
            }
        }

        if (successProtoBuffer) {
            Path filePath = Paths.get(mOutputPbFilePath.toAbsolutePath().toString());
            System.out.println(
                    "mOutputPbFilePath :" + mOutputPbFilePath.toAbsolutePath().toString());

            byte[] fileBytes = Files.readAllBytes(filePath);
            TelephonyConfigProto telephonyConfigProto = TelephonyConfigProto.parseFrom(fileBytes);
            SatelliteConfigProto satelliteConfigProto = telephonyConfigProto.getSatellite();

            assertEquals(version.intValue(), satelliteConfigProto.getVersion());

            if (serviceProto == null
                    || serviceProto.mCarrierId == null
                    || serviceProto.mCapabilityProtoList == null
                    || serviceProto.mCapabilityProtoList[0].mPlmn == null
                    || serviceProto.mCapabilityProtoList[0].mAllowedServices.length == 0) {
                assertTrue(satelliteConfigProto.getCarrierSupportedSatelliteServicesList()
                        .isEmpty());
            } else {
                assertEquals(serviceProto.mCarrierId.intValue(),
                        satelliteConfigProto.getCarrierSupportedSatelliteServices(
                                0).getCarrierId());
                assertEquals(serviceProto.mCapabilityProtoList[0].mPlmn,
                        satelliteConfigProto.getCarrierSupportedSatelliteServices(0)
                                .getSupportedSatelliteProviderCapabilities(0).getCarrierPlmn());
                assertArrayEquals(serviceProto.mCapabilityProtoList[0].mAllowedServices,
                        satelliteConfigProto.getCarrierSupportedSatelliteServices(0)
                                .getSupportedSatelliteProviderCapabilities(0)
                                .getAllowedServicesList()
                                .stream().mapToInt(Integer::intValue).toArray());
            }

            if (roamingConfigProto == null) {
                assertFalse(satelliteConfigProto.hasCarrierRoamingConfig());
            } else {
                assertTrue(satelliteConfigProto.hasCarrierRoamingConfig());
                if (roamingConfigProto.mMaxAllowedDataMode == null) {
                    System.out.println("mMaxAllowedDataMode is null");
                    assertFalse(satelliteConfigProto.getCarrierRoamingConfig()
                            .hasMaxAllowedDataMode());
                } else {
                    System.out.println("mMaxAllowedDataMode is "
                            + roamingConfigProto.mMaxAllowedDataMode.intValue());
                    assertEquals(roamingConfigProto.mMaxAllowedDataMode.intValue(),
                            satelliteConfigProto.getCarrierRoamingConfig()
                                    .getMaxAllowedDataMode());
                }
            }

            if (regionProto == null) {
                assertFalse(satelliteConfigProto.hasDeviceSatelliteRegion());
            } else {
                if (regionProto.mIsAllowed != null) {
                    assertEquals(regionProto.mIsAllowed,
                            satelliteConfigProto.getDeviceSatelliteRegion().getIsAllowed());
                } else {
                    fail("regionProto.mIsAllowed should not be null, check xml");
                }

                if (regionProto.mCountryCodeList != null) {
                    assertArrayEquals(regionProto.mCountryCodeList,
                            satelliteConfigProto.getDeviceSatelliteRegion().getCountryCodesList()
                                    .toArray());
                } else {
                    assertTrue(satelliteConfigProto.getDeviceSatelliteRegion()
                            .getCountryCodesList().isEmpty());
                }

                if (regionProto.mS2CellFileName != null) {
                    assertTrue(satelliteConfigProto.getDeviceSatelliteRegion().hasS2CellFile());
                } else {
                    assertFalse(satelliteConfigProto.getDeviceSatelliteRegion().hasS2CellFile());
                }

                if (regionProto.mSatelliteAccessConfigFileName != null) {
                    assertTrue(satelliteConfigProto.getDeviceSatelliteRegion()
                            .hasSatelliteAccessConfigFile());
                } else {
                    assertFalse(satelliteConfigProto.getDeviceSatelliteRegion()
                            .hasSatelliteAccessConfigFile());
                }
            }
        } else {
            fail("Exception should have been happened");
        }
    }

    @Test
    public void testConfigDataGeneratorForEmptyFieldOfRoamingConfigProto() throws IOException {
        System.out.println("testConfigDataGeneratorForEmptyFieldOfRoamingConfigProto");

        HashMap<RoamingConfigProto, Boolean> roamingConfigProtoMap = new HashMap<>();
        roamingConfigProtoMap.put(null, true);
        roamingConfigProtoMap.put(new RoamingConfigProto(null), false);
        roamingConfigProtoMap.put(new RoamingConfigProto(
                SATELLITE_DATA_SUPPORT_BANDWIDTH_CONSTRAINED), true);

        ServiceProto serviceProtoValid = new ServiceProto(CARRIER_ID_VALID,
                new ProviderCapabilityProto[]{new ProviderCapabilityProto(PLMN_VALID_45005,
                        new int[]{SERVICE_TYPE_MMS})});
        RegionProto regionProtoValid = new RegionProto(
                getS2CellFile(true),
                new String[]{COUNTRY_CODE_US},
                true,
                getSACJsonFile(true));

        int iterationCount = 1;
        Set<RoamingConfigProto> keys = roamingConfigProtoMap.keySet();
        for (RoamingConfigProto roamingConfigProto : keys) {
            System.out.println("~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~");
            System.out.println("roamingConfigProto postfix is " + iterationCount);
            System.out.println("~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~");

            prepareInAndOutData(iterationCount++);
            verifyConfigDataGenerationAndValidationWithOptionalFields(
                    roamingConfigProtoMap.get(roamingConfigProto),
                    1,
                    serviceProtoValid,
                    roamingConfigProto,
                    regionProtoValid);
        }
    }

    @Test
    public void testConfigDataGeneratorWithEmptyFieldOfServiceProto() throws IOException {
        System.out.println("testConfigDataGeneratorWithEmptyFieldOfServiceProto");
        Map<ServiceProto, Boolean> serviceProtoMap = new LinkedHashMap<>();

        serviceProtoMap.put(null, true);
        serviceProtoMap.put(new ServiceProto(null, null), false);
        serviceProtoMap.put(new ServiceProto(null, new ProviderCapabilityProto[]{
                new ProviderCapabilityProto(PLMN_VALID_45005, new int[]{SERVICE_TYPE_MMS})}),
                false);
        serviceProtoMap.put(new ServiceProto(1, null), false);
        serviceProtoMap.put(new ServiceProto(1, new ProviderCapabilityProto[]{
                new ProviderCapabilityProto(null, null)}), false);
        serviceProtoMap.put(new ServiceProto(1, new ProviderCapabilityProto[]{
                new ProviderCapabilityProto(null, new int[]{SERVICE_TYPE_MMS})}), false);
        serviceProtoMap.put(new ServiceProto(1, new ProviderCapabilityProto[]{
                new ProviderCapabilityProto(PLMN_VALID_45005, null)}), false);
        serviceProtoMap.put(new ServiceProto(1, new ProviderCapabilityProto[]{
                new ProviderCapabilityProto(PLMN_VALID_45005, new int[]{SERVICE_TYPE_MMS})}), true);

        RoamingConfigProto roamingConfigProtoValid =
                new RoamingConfigProto(SATELLITE_DATA_SUPPORT_BANDWIDTH_CONSTRAINED);
        RegionProto regionProtoValid = new RegionProto(getS2CellFile(true),
                new String[]{COUNTRY_CODE_US},
                true, getSACJsonFile(true));

        int iterationCount = 1;
        Set<ServiceProto> keys = serviceProtoMap.keySet();
        for (ServiceProto serviceProto : keys) {
            System.out.println("~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~");
            System.out.println("serviceProto postfix is " + iterationCount);
            System.out.println("~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~");
            prepareInAndOutData(iterationCount++);
            verifyConfigDataGenerationAndValidationWithOptionalFields(
                    serviceProtoMap.get(serviceProto),
                    1,
                    serviceProto,
                    roamingConfigProtoValid,
                    regionProtoValid);
        }
    }

    @Test
    public void testConfigDataGeneratorWithEmptyFieldOfRegionProto() throws IOException {
        System.out.println("testConfigDataGeneratorWithEmptyFieldOfRegionProto");
        Map<RegionProto, Boolean> regionProtoMap = new LinkedHashMap<>();

        regionProtoMap.put(null, true);
        regionProtoMap.put(new RegionProto(
                getS2CellFile(false),
                new String[]{getCountryCode(true)},
                getIsAllowed(true),
                getSACJsonFile(true)), true);
        regionProtoMap.put(new RegionProto(
                getS2CellFile(true),
                null,
                getIsAllowed(true),
                getSACJsonFile(true)), true);
        regionProtoMap.put(new RegionProto(
                getS2CellFile(true),
                new String[]{getCountryCode(true)},
                getIsAllowed(false),
                getSACJsonFile(true)), false);
        regionProtoMap.put(new RegionProto(
                getS2CellFile(true),
                new String[]{getCountryCode(true)},
                getIsAllowed(true),
                getSACJsonFile(false)), true);
        regionProtoMap.put(new RegionProto(
                getS2CellFile(true),
                new String[]{getCountryCode(true)},
                getIsAllowed(true),
                getSACJsonFile(true)), true);

        RoamingConfigProto roamingConfigProtoValid = new RoamingConfigProto(1);
        ServiceProto serviceProtoValid = new ServiceProto(
                CARRIER_ID_VALID,
                new ProviderCapabilityProto[]{new ProviderCapabilityProto(PLMN_VALID_45005,
                        new int[]{SERVICE_TYPE_MMS})});

        int iterationCount = 1;
        Set<RegionProto> keys = regionProtoMap.keySet();
        for (RegionProto regionProto : keys) {
            System.out.println("~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~");
            System.out.println("serviceProto postfix is " + iterationCount);
            System.out.println("~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~");
            prepareInAndOutData(iterationCount++);
            verifyConfigDataGenerationAndValidationWithOptionalFields(
                    regionProtoMap.get(regionProto),
                    1,
                    serviceProtoValid,
                    roamingConfigProtoValid,
                    regionProto);
        }
    }

    private void createInputXmlWithProto(
            File outputFile,
            Integer version,
            ServiceProto serviceProto,
            RoamingConfigProto roamingConfigProto,
            RegionProto regionProto) {
        try {
            DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder docBuilder = docFactory.newDocumentBuilder();

            // Create Document and Root Element
            Document doc = docBuilder.newDocument();
            Element rootElement = doc.createElement(ConfigDataGenerator.TAG_SATELLITE_CONFIG);
            doc.appendChild(rootElement);

            if (version != null) {
                // Add <version>
                Element versionElement = doc.createElement(ConfigDataGenerator.TAG_VERSION);
                versionElement.appendChild(doc.createTextNode(version.toString()));
                rootElement.appendChild(versionElement);
            }

            if (serviceProto != null) {
                if (serviceProto.mCapabilityProtoList != null) {
                    rootElement.appendChild(
                            createCarrierSupportedServices(doc, serviceProto.mCarrierId,
                                    true,
                                    serviceProto.mCapabilityProtoList[0].mPlmn,
                                    serviceProto.mCapabilityProtoList[0].mAllowedServices));
                } else {
                    rootElement.appendChild(
                            createCarrierSupportedServices(doc, serviceProto.mCarrierId,
                                    false,
                                    "dummy",
                                    7, 8, 9));
                }

            }

            if (roamingConfigProto != null) {
                // Add <carrierRoamingConfig>
                rootElement.appendChild(
                        createCarrierRoamingConfig(doc, roamingConfigProto.mMaxAllowedDataMode));
            }

            if (regionProto != null) {
                Element satelliteRegion =
                        doc.createElement(ConfigDataGenerator.TAG_SATELLITE_REGION);
                if (regionProto.mS2CellFileName != null) {
                    satelliteRegion.appendChild(
                            createElementWithText(doc, ConfigDataGenerator.TAG_S2_CELL_FILE,
                                    regionProto.mS2CellFileName));
                }
                if (regionProto.mCountryCodeList != null) {
                    satelliteRegion.appendChild(
                            createElementWithText(doc, ConfigDataGenerator.TAG_COUNTRY_CODE,
                                    regionProto.mCountryCodeList[0]));
                }
                if (regionProto.mIsAllowed != null) {
                    satelliteRegion.appendChild(
                            createElementWithText(doc, ConfigDataGenerator.TAG_IS_ALLOWED,
                                    String.valueOf(regionProto.mIsAllowed)));
                }
                if (regionProto.mSatelliteAccessConfigFileName != null) {
                    satelliteRegion.appendChild(createElementWithText(
                            doc, ConfigDataGenerator.TAG_SATELLITE_ACCESS_CONFIG_FILE,
                            regionProto.mSatelliteAccessConfigFileName));
                }
                rootElement.appendChild(satelliteRegion);
            }
            // Write XML to File
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();

            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");

            DOMSource source = new DOMSource(doc);
            StreamResult result = new StreamResult(outputFile);
            transformer.transform(source, result);
        } catch (Exception e) {
            throw new RuntimeException("Got exception in creating input file , e=" + e);
        }
    }

    private void createInputXml(
            File outputFile,
            int version,
            int carrierId,
            String plmn,
            int allowedService,
            int maxAllowedDataMode,
            String countryCode,
            boolean isAllowed,
            String inputS2CellFileName,
            String inputSatelliteAccessConfigFileName) {
        try {
            DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder docBuilder = docFactory.newDocumentBuilder();

            // Create Document and Root Element
            Document doc = docBuilder.newDocument();
            Element rootElement = doc.createElement(ConfigDataGenerator.TAG_SATELLITE_CONFIG);
            doc.appendChild(rootElement);

            // Add <version>
            Element versionElement = doc.createElement(ConfigDataGenerator.TAG_VERSION);
            versionElement.appendChild(doc.createTextNode(String.valueOf(version)));
            rootElement.appendChild(versionElement);

            // Add <carriersupportedservices>
            rootElement.appendChild(
                    createCarrierSupportedServices(doc, carrierId, true, plmn, allowedService));

            // Add <carrierroamingconfig>
            rootElement.appendChild(
                    createCarrierRoamingConfig(doc, maxAllowedDataMode));

            // Add <satelliteregion>
            Element satelliteRegion = doc.createElement(ConfigDataGenerator.TAG_SATELLITE_REGION);
            satelliteRegion.appendChild(
                    createElementWithText(doc, ConfigDataGenerator.TAG_S2_CELL_FILE,
                            inputS2CellFileName));
            satelliteRegion.appendChild(
                    createElementWithText(doc, ConfigDataGenerator.TAG_COUNTRY_CODE, countryCode));
            satelliteRegion.appendChild(
                    createElementWithText(doc, ConfigDataGenerator.TAG_IS_ALLOWED,
                            isAllowed ? "TRUE" : "FALSE"));
            satelliteRegion.appendChild(
                    createElementWithText(
                            doc,
                            ConfigDataGenerator.TAG_SATELLITE_ACCESS_CONFIG_FILE,
                            inputSatelliteAccessConfigFileName));
            rootElement.appendChild(satelliteRegion);

            // Write XML to File
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();
            DOMSource source = new DOMSource(doc);
            StreamResult result = new StreamResult(outputFile);
            transformer.transform(source, result);

        } catch (Exception e) {
            throw new RuntimeException("Got exception in creating input file , e=" + e);
        }
    }

    private static Element createCarrierSupportedServices(Document doc, Integer carrierId,
            boolean isCapability, String carrierPlmn, int... services) {
        Element carrierSupportedServices = doc.createElement(
                ConfigDataGenerator.TAG_SUPPORTED_SERVICES);

        if (carrierId != null) {
            // Add CarrierId
            carrierSupportedServices.appendChild(createElementWithText(doc,
                    ConfigDataGenerator.TAG_CARRIER_ID, String.valueOf(carrierId)));
        }

        if (isCapability) {
            // Add Plmn and Services
            Element providerCapability = doc.createElement(
                    ConfigDataGenerator.TAG_PROVIDER_CAPABILITY);

            if (carrierPlmn != null) {
                providerCapability.appendChild(createElementWithText(doc,
                        ConfigDataGenerator.TAG_CARRIER_PLMN, carrierPlmn));
            }

            if (services != null) {
                for (int service : services) {
                    providerCapability.appendChild(createElementWithText(doc,
                            ConfigDataGenerator.TAG_SERVICE, String.valueOf(service)));
                }
            }

            carrierSupportedServices.appendChild(providerCapability);
        }
        return carrierSupportedServices;
    }


    private static Element createCarrierRoamingConfig(Document doc, Integer maxAllowedDataMode) {
        Element carrierRoamingConfig = doc.createElement(
                ConfigDataGenerator.TAG_CARRIER_ROAMING_CONFIG);
        // Add CarrierId
        if (maxAllowedDataMode != null) {
            carrierRoamingConfig.appendChild(createElementWithText(doc,
                    ConfigDataGenerator.TAG_MAX_ALLOWED_DATA_MODE,
                    String.valueOf(maxAllowedDataMode)));
        }
        return carrierRoamingConfig;
    }

    private static Element createElementWithText(Document doc, String tagName, String textContent) {
        Element element = doc.createElement(tagName);
        element.appendChild(doc.createTextNode(textContent));
        return element;
    }

    private static Path createTempDir(Class<?> testClass) throws IOException {
        return Files.createTempDirectory(testClass.getSimpleName());
    }

    private static void deleteDirectory(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes basicFileAttributes)
                    throws IOException {
                Files.deleteIfExists(path);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path path, IOException e) throws IOException {
                Files.delete(path);
                return FileVisitResult.CONTINUE;
            }
        });
        assertFalse(Files.exists(dir));
    }

    private void writeByteStringToFile(String fileName, ByteString byteString) {
        try (FileOutputStream fos = new FileOutputStream(fileName)) {
            fos.write(byteString.toByteArray());
        } catch (IOException e) {
            System.err.println("Error writing to file: " + e.getMessage());
        }
    }

    private static void printXmlFileContentForDebug(String xmlFilePath) {
        System.out.println("\n--- XML Debug Output Start ---");
        System.out.println("Attempting to read file: " + xmlFilePath);

        Path path = Paths.get(xmlFilePath);

        if (!Files.exists(path)) {
            System.err.println("Error: File not found at path: " + xmlFilePath);
            System.out.println("--- XML Debug Output End (Error) ---");
            return;
        }
        if (!Files.isRegularFile(path)) {
            System.err.println("Error: Path is not a regular file: " + xmlFilePath);
            System.out.println("--- XML Debug Output End (Error) ---");
            return;
        }
        if (!Files.isReadable(path)) {
            System.err.println("Error: Cannot read file due to permissions: " + xmlFilePath);
            System.out.println("--- XML Debug Output End (Error) ---");
            return;
        }

        try {
            String content = Files.readString(path);
            System.out.println("\nFile Content:");
            System.out.println(content);
        } catch (IOException e) {
            System.err.println("Error reading file '" + xmlFilePath + "': " + e.getMessage());
            e.printStackTrace();
        }
        System.out.println("--- XML Debug Output End ---");
    }
}
