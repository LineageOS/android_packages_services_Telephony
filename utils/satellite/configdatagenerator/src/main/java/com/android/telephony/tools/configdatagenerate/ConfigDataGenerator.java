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

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/** Creates a protubuf file **/
public class ConfigDataGenerator {
    public static final String TAG_SATELLITE_CONFIG = "satelliteconfig";
    public static final String TAG_VERSION = "version";

    public static final String TAG_SUPPORTED_SERVICES = "carriersupportedservices";
    public static final String TAG_CARRIER_ID = "carrier_id";
    public static final String TAG_PROVIDER_CAPABILITY = "providercapability";
    public static final String TAG_CARRIER_PLMN = "carrier_plmn";
    public static final String TAG_SERVICE = "service";

    public static final String TAG_CARRIER_ROAMING_CONFIG = "carrier_roaming_config";
    public static final String TAG_MAX_ALLOWED_DATA_MODE = "max_allowed_data_mode";

    public static final String TAG_SATELLITE_REGION =  "satelliteregion";
    public static final String TAG_S2_CELL_FILE = "s2_cell_file";
    public static final String TAG_COUNTRY_CODE = "country_code";
    public static final String TAG_IS_ALLOWED = "is_allowed";
    public static final String TAG_SATELLITE_ACCESS_CONFIG_FILE = "satellite_access_config_file";

    /**
     * Creates a protubuf file with user inputs
     */
    public static void main(String[] args) {
        Arguments arguments = new Arguments();
        JCommander.newBuilder()
                .addObject(arguments)
                .build()
                .parse(args);
        // Refer to the README file for an example of the input XML file
        String inputFile = arguments.inputFile;
        String outputFile = arguments.outputFile;
        SatelliteConfigProtoGenerator.sProtoResultFile = outputFile;

        Document doc = getDocumentFromInput(inputFile);

        setSatelliteConfigVersion(doc);
        createStarlinkConfigProto(doc);
        createSkyloConfigProto(doc);

        SatelliteConfigProtoGenerator.generateProto();

        System.out.print("\n" + SatelliteConfigProtoGenerator.sProtoResultFile + " is generated\n");
    }

    private static class Arguments {
        @Parameter(names = "--input-file",
                description = "input xml file",
                required = true)
        public String inputFile;

        @Parameter(names = "--output-file",
                description = "out protobuf file",
                required = false)
        public String outputFile = SatelliteConfigProtoGenerator.sProtoResultFile;
    }

    private static Document getDocumentFromInput(String inputFile) {
        File xmlFile = new File(inputFile);
        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder dBuilder = null;
        Document doc = null;
        try {
            dBuilder = dbFactory.newDocumentBuilder();
            doc = dBuilder.parse(xmlFile);
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new RuntimeException("getDocumentFromInput: e=" + e);
        }
        doc.getDocumentElement().normalize();
        return doc;
    }

    /**
     * Set version after getting version from the input document
     *
     * @param doc the input document. Format of document should be
     * <pre>
     * &lt;version&gt;value1&lt;/version&gt;
     * </pre>
     */
    public static void setSatelliteConfigVersion(Document doc) {
        NodeList versionList = doc.getElementsByTagName(TAG_VERSION);
        Node versionNode = versionList.item(0);
        System.out.println("Version: " + versionNode.getTextContent());
        SatelliteConfigProtoGenerator.sVersion = Integer.parseInt(versionNode.getTextContent());
    }


    /**
     * Creates a list of ServiceProto from the input document
     *
     * @param doc the input document. Format of document should be
     * <pre>
     * &lt;carriersupportedservices&gt;
     *   &lt;carrier_id&gt;value1&lt;/carrier_id&gt;
     *   &lt;providercapability&gt;
     *     &lt;carrier_plmn&gt;value2&lt;/carrier_plmn&gt;
     *     &lt;service&gt;value3&lt;/service&gt;
     *   &lt;/providercapability&gt;
     * &lt;/carriersupportedservices&gt;
     * </pre>
     */
    public static void createStarlinkConfigProto(Document doc) {

        Node carrierRoamingConfig = doc.getElementsByTagName(TAG_CARRIER_ROAMING_CONFIG).item(0);
        Element carrierRoamingConfigElement = (Element) carrierRoamingConfig;
        String stringMaxAllowedDataMode = carrierRoamingConfigElement.getElementsByTagName(
                TAG_MAX_ALLOWED_DATA_MODE).item(0).getTextContent();
        int maxAllowedDataMode = Integer.parseInt(stringMaxAllowedDataMode);
        if (!Util.isValidMaxAllowedDataMode(maxAllowedDataMode)) {
            throw new ParameterException("Invalid maxAllowedDataModel: "
                    + maxAllowedDataMode);
        }
        SatelliteConfigProtoGenerator.sCarrierRoamingConfig =
                new CarrierRoamingConfigProto(maxAllowedDataMode);
        System.out.println("\nCarrier Roaming Config ");
        System.out.println("└ MaxAllowedDataMode: " + maxAllowedDataMode);

        NodeList carrierServicesList = doc.getElementsByTagName(TAG_SUPPORTED_SERVICES);
        SatelliteConfigProtoGenerator.sServiceProtoList = new ArrayList<>();

        System.out.println("\nCarrier Supported Satellite Services ");

        for (int i = 0; i < carrierServicesList.getLength(); i++) {
            Node carrierServiceNode = carrierServicesList.item(i);
            if (carrierServiceNode.getNodeType() == Node.ELEMENT_NODE) {
                Element carrierServiceElement = (Element) carrierServiceNode;
                String carrierId = carrierServiceElement.getElementsByTagName(TAG_CARRIER_ID)
                        .item(0).getTextContent();
                System.out.println("└ Carrier ID: " + carrierId);

                NodeList providerCapabilityList = carrierServiceElement.getElementsByTagName(
                        TAG_PROVIDER_CAPABILITY);
                ProviderCapabilityProto[] capabilityProtoList =
                        new ProviderCapabilityProto[providerCapabilityList.getLength()];
                for (int j = 0; j < providerCapabilityList.getLength(); j++) {
                    Node providerCapabilityNode = providerCapabilityList.item(j);
                    if (providerCapabilityNode.getNodeType() == Node.ELEMENT_NODE) {
                        Element providerCapabilityElement = (Element) providerCapabilityNode;
                        String carrierPlmn = providerCapabilityElement.getElementsByTagName(
                                TAG_CARRIER_PLMN).item(0).getTextContent();
                        System.out.println("   └ Carrier PLMN: " + carrierPlmn);
                        if (!Util.isValidPlmn(carrierPlmn)) {
                            throw new ParameterException("Invalid plmn:" + carrierPlmn);
                        }

                        NodeList allowedServicesList = providerCapabilityElement
                                .getElementsByTagName(TAG_SERVICE);
                        System.out.print("   └ Allowed services: ");
                        int[] allowedServiceArray = new int[allowedServicesList.getLength()];
                        for (int k = 0; k < allowedServicesList.getLength(); k++) {
                            int service = Integer.parseInt(allowedServicesList.item(k)
                                    .getTextContent());
                            System.out.print(service + " ");
                            if (!Util.isValidService(service)) {
                                throw new ParameterException("Invalid service:" + service);
                            }
                            allowedServiceArray[k] = service;
                        }
                        System.out.println();
                        ProviderCapabilityProto capabilityProto =
                                new ProviderCapabilityProto(carrierPlmn, allowedServiceArray);
                        capabilityProtoList[j] = capabilityProto;
                    }
                }

                ServiceProto serviceProto = new ServiceProto(Integer.parseInt(carrierId),
                        capabilityProtoList);
                SatelliteConfigProtoGenerator.sServiceProtoList.add(serviceProto);
            }
        }
    }

    /**
     * Creates a RegionProto from the input document
     *
     * @param doc the input document. Format of document should be
     * <pre>
     * &lt;satelliteregion&gt;
     *   &lt;s2_cell_file&gt;value1&lt;/s2_cell_file&gt;
     *   &lt;country_code&gt;value2&lt;/country_code&gt;
     *   &lt;country_code&gt;value3&lt;/country_code&gt;
     *   &lt;is_allowed&gt;value4&lt;/is_allowed&gt;
     *   &lt;satellite_access_config_file&gt;value5lt;/satellite_access_config_file&gt;
     * &lt;/satelliteregion&gt;
     * </pre>
     */
    public static void createSkyloConfigProto(Document doc) {
        NodeList satelliteRegionList = doc.getElementsByTagName(TAG_SATELLITE_REGION);
        Node satelliteRegionNode = satelliteRegionList.item(0);
        if (satelliteRegionNode != null && satelliteRegionNode.getNodeType() == Node.ELEMENT_NODE) {
            Element satelliteRegionElement = (Element) satelliteRegionNode;
            String s2CellFileName = satelliteRegionElement.getElementsByTagName(TAG_S2_CELL_FILE)
                    .item(0).getTextContent();
            String isAllowedString = satelliteRegionElement.getElementsByTagName(TAG_IS_ALLOWED)
                    .item(0).getTextContent();
            boolean isAllowed = false;
            if (isAllowedString.equals("TRUE")) {
                isAllowed = true;
            }
            String satelliteAccessConfigFileName = "";
            if (satelliteRegionElement
                            .getElementsByTagName(TAG_SATELLITE_ACCESS_CONFIG_FILE)
                            .getLength()
                    > 0) {
                satelliteAccessConfigFileName =
                        satelliteRegionElement
                                .getElementsByTagName(TAG_SATELLITE_ACCESS_CONFIG_FILE)
                                .item(0)
                                .getTextContent();
            }
            System.out.println("\nSatellite Region:");
            System.out.println("└ S2 Cell File: " + s2CellFileName);
            System.out.println("└ Is Allowed: " + isAllowed);
            System.out.println(
                    "└ Satellite Access Config File Name: " + satelliteAccessConfigFileName);

            NodeList countryCodesList = satelliteRegionElement.getElementsByTagName(
                    TAG_COUNTRY_CODE);
            String[] listCountryCode = new String[countryCodesList.getLength()];
            System.out.print("└ Country Codes: ");
            for (int k = 0; k < countryCodesList.getLength(); k++) {
                String countryCode = countryCodesList.item(k).getTextContent();
                System.out.print(countryCode + " ");
                if (!Util.isValidCountryCode(countryCode)) {
                    throw new ParameterException("Invalid countryCode:" + countryCode);
                }
                listCountryCode[k] = countryCode;
            }
            System.out.println();
            SatelliteConfigProtoGenerator.sRegionProto =
                    new RegionProto(
                            s2CellFileName,
                            listCountryCode,
                            isAllowed,
                            satelliteAccessConfigFileName);
        }
    }
}
