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

import com.android.internal.telephony.satellite.SatelliteConfigData;

import com.google.protobuf.ByteString;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;

public class SatelliteConfigProtoGenerator {

    private static final String TAG = "ProtoGenerator";
    public static String sProtoResultFile = "telephony_config.pb";
    public static int sVersion;
    public static ArrayList<ServiceProto> sServiceProtoList;
    public static RoamingConfigProto sCarrierRoamingConfig;
    public static RegionProto sRegionProto;

    /**
     * Generate Protobuf.
     *
     * The output file is a binary file of TelephonyConfigProto.
     *
     * The format of TelephonyConfigProto is defined in
     * https://source.corp.google.com/android/frameworks/opt/telephony/proto/src/
     * telephony_config_update.proto
     */
    public static void generateProto() {
        SatelliteConfigData.TelephonyConfigProto.Builder telephonyConfigBuilder =
                SatelliteConfigData.TelephonyConfigProto.newBuilder();
        SatelliteConfigData.SatelliteConfigProto.Builder satelliteConfigBuilder =
                SatelliteConfigData.SatelliteConfigProto.newBuilder();

        satelliteConfigBuilder.setVersion(sVersion);    // Input version

        if (sServiceProtoList != null) {
            // carrierSupportedSatelliteServiceBuilder
            SatelliteConfigData.CarrierSupportedSatelliteServicesProto.Builder
                    carrierSupportedSatelliteServiceBuilder =
                    SatelliteConfigData.CarrierSupportedSatelliteServicesProto.newBuilder();
            for (int i = 0; i < sServiceProtoList.size(); i++) {
                ServiceProto proto = sServiceProtoList.get(i);
                carrierSupportedSatelliteServiceBuilder.setCarrierId(proto.mCarrierId);
                SatelliteConfigData.SatelliteProviderCapabilityProto.Builder
                        satelliteProviderCapabilityBuilder =
                        SatelliteConfigData.SatelliteProviderCapabilityProto.newBuilder();
                ProviderCapabilityProto[] capabilityProtoList = proto.mCapabilityProtoList;
                for (int j = 0; j < capabilityProtoList.length; j++) {
                    ProviderCapabilityProto capabilityProto = capabilityProtoList[j];
                    satelliteProviderCapabilityBuilder.setCarrierPlmn(capabilityProto.mPlmn);
                    int[] allowedServiceList = capabilityProto.mAllowedServices;
                    for (int k = 0; k < allowedServiceList.length; k++) {
                        satelliteProviderCapabilityBuilder
                                .addAllowedServices(allowedServiceList[k]);
                    }
                    carrierSupportedSatelliteServiceBuilder
                            .addSupportedSatelliteProviderCapabilities(
                                    satelliteProviderCapabilityBuilder);
                    satelliteProviderCapabilityBuilder.clear();
                }
                satelliteConfigBuilder.addCarrierSupportedSatelliteServices(
                        carrierSupportedSatelliteServiceBuilder);
                carrierSupportedSatelliteServiceBuilder.clear();
            }
        } else {
            System.out.print("ServiceProtoList does not exist");
        }

        if (sCarrierRoamingConfig != null) {
            // carrierRoamingConfigBuilder
            SatelliteConfigData.CarrierRoamingConfigProto.Builder carrierRoamingConfigBuilder =
                    SatelliteConfigData.CarrierRoamingConfigProto.newBuilder();
            if (sCarrierRoamingConfig.mMaxAllowedDataMode != null) {
                carrierRoamingConfigBuilder.setMaxAllowedDataMode(
                        sCarrierRoamingConfig.mMaxAllowedDataMode);
            }
            satelliteConfigBuilder.setCarrierRoamingConfig(carrierRoamingConfigBuilder);
        }

        if (sRegionProto != null) {
            System.out.println("sRegionProto");
            // satelliteRegionBuilder
            SatelliteConfigData.SatelliteRegionProto.Builder satelliteRegionBuilder =
                    SatelliteConfigData.SatelliteRegionProto.newBuilder();

            // mS2CellFileName
            if (sRegionProto.mS2CellFileName != null
                    && !sRegionProto.mS2CellFileName.isEmpty()) {
                byte[] s2SatBinaryData;
                try {
                    s2SatBinaryData = readFileToByteArray(sRegionProto.mS2CellFileName);
                } catch (IOException e) {
                    throw new RuntimeException("Got exception in reading the file "
                            + sRegionProto.mS2CellFileName + ", e=" + e);
                }
                if (s2SatBinaryData != null) {
                    satelliteRegionBuilder.setS2CellFile(ByteString.copyFrom(s2SatBinaryData));
                }
            }

            // mCountryCodeList
            String[] countryCodeList = sRegionProto.mCountryCodeList;
            for (int i = 0; i < countryCodeList.length; i++) {
                satelliteRegionBuilder.addCountryCodes(countryCodeList[i]);
            }

            // mIsAllowed
            satelliteRegionBuilder.setIsAllowed(sRegionProto.mIsAllowed);

            // mSatelliteAccessConfigFileName
            if (sRegionProto.mSatelliteAccessConfigFileName != null
                    && !sRegionProto.mSatelliteAccessConfigFileName.isEmpty()) {
                byte[] satelliteAccessBinaryData;
                try {
                    satelliteAccessBinaryData =
                            readFileToByteArray(sRegionProto.mSatelliteAccessConfigFileName);
                } catch (IOException e) {
                    throw new RuntimeException(
                            "Got exception in reading the mSatelliteAccessConfigFileName "
                                    + sRegionProto.mSatelliteAccessConfigFileName
                                    + ", e="
                                    + e);
                }
                if (satelliteAccessBinaryData != null) {
                    satelliteRegionBuilder.setSatelliteAccessConfigFile(
                            ByteString.copyFrom(satelliteAccessBinaryData));
                }
            }

            satelliteConfigBuilder.setDeviceSatelliteRegion(satelliteRegionBuilder);
        } else {
            System.out.print("\nRegionProto does not exist");
        }

        telephonyConfigBuilder.setSatellite(satelliteConfigBuilder);
        writeToResultFile(telephonyConfigBuilder);
    }

    private static void writeToResultFile(SatelliteConfigData
            .TelephonyConfigProto.Builder telephonyConfigBuilder) {
        try {
            File file = new File(sProtoResultFile);
            if (file.exists()) {
                file.delete();
            }
            FileOutputStream fos = new FileOutputStream(file);
            SatelliteConfigData.TelephonyConfigProto telephonyConfigData =
                    telephonyConfigBuilder.build();
            telephonyConfigData.writeTo(fos);

            fos.close();
        } catch (Exception e) {
            throw new RuntimeException("Got exception in writing the file "
                    + sProtoResultFile + ", e=" + e);
        }
    }

    private static byte[] readFileToByteArray(String fileName) throws IOException {
        File file = new File(fileName);
        if (!file.exists()) {
            throw new IOException("File: " + fileName + " does not exist");
        }

        if (file.exists() && file.canRead()) {
            FileInputStream fileInputStream = new FileInputStream(file);
            long fileSize = fileInputStream.available();
            byte[] bytes = new byte[(int) fileSize];
            int bytesRead = fileInputStream.read(bytes);
            fileInputStream.close();
            if (bytesRead != fileSize) {
                throw new IOException("file read fail: " + file.getCanonicalPath());
            }
            return bytes;
        }
        return null;
    }
}
