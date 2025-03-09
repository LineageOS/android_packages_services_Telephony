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

package com.android.phone.satellite.entitlement;

import static android.telephony.CarrierConfigManager.SATELLITE_DATA_SUPPORT_ALL;
import static android.telephony.CarrierConfigManager.SATELLITE_DATA_SUPPORT_BANDWIDTH_CONSTRAINED;
import static android.telephony.NetworkRegistrationInfo.SERVICE_TYPE_DATA;
import static android.telephony.NetworkRegistrationInfo.SERVICE_TYPE_VOICE;
import static android.telephony.NetworkRegistrationInfo.SERVICE_TYPE_SMS;

import static com.android.internal.telephony.satellite.SatelliteController.SATELLITE_DATA_PLAN_METERED;
import static com.android.internal.telephony.satellite.SatelliteController.SATELLITE_DATA_PLAN_UNMETERED;

import android.annotation.IntDef;

import com.android.internal.telephony.satellite.SatelliteNetworkInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * This class stores the result of the satellite entitlement query and passes them to
 * SatelliteEntitlementController.
 */
public class SatelliteEntitlementResult {
    /** SatMode allowed, but not yet provisioned and activated on the network. */
    public static final int SATELLITE_ENTITLEMENT_STATUS_DISABLED = 0;
    /** SatMode service allowed, provisioned and activated on the network. User can access the
     * satellite service. */
    public static final int SATELLITE_ENTITLEMENT_STATUS_ENABLED = 1;
    /** SatMode cannot be offered for network or device. */
    public static final int SATELLITE_ENTITLEMENT_STATUS_INCOMPATIBLE = 2;
    /** SatMode is being provisioned on the network. Not yet activated. */
    public static final int SATELLITE_ENTITLEMENT_STATUS_PROVISIONING = 3;

    @IntDef(prefix = {"SATELLITE_ENTITLEMENT_STATUS_"}, value = {
            SATELLITE_ENTITLEMENT_STATUS_DISABLED,
            SATELLITE_ENTITLEMENT_STATUS_ENABLED,
            SATELLITE_ENTITLEMENT_STATUS_INCOMPATIBLE,
            SATELLITE_ENTITLEMENT_STATUS_PROVISIONING
    })
    public @interface SatelliteEntitlementStatus {}

    private @SatelliteEntitlementStatus int mEntitlementStatus;
    /**
     * An SatelliteNetworkInfo list consisting of the PLMN and the DataPlanType in the PLMNAlowed
     * item of the satellite configuration received from the entitlement server.
     */
    private List<SatelliteNetworkInfo> mAllowedSatelliteNetworkInfoList;
    /**
     * List consisting of the PLMN in the PLMNBarred item of the satellite configuration received
     * from the entitlement server
     */
    private List<String> mBarredPlmnList;

    /**
     * Store the result of the satellite entitlement response.
     *
     * @param entitlementStatus The entitlement status.
     * @param allowedSatelliteNetworkInfoList The allowedSatelliteNetworkInfoList
     * @param barredPlmnList The barred plmn list
     */
    public SatelliteEntitlementResult(@SatelliteEntitlementStatus int entitlementStatus,
            List<SatelliteNetworkInfo> allowedSatelliteNetworkInfoList,
            List<String> barredPlmnList) {
        mEntitlementStatus = entitlementStatus;
        mAllowedSatelliteNetworkInfoList = allowedSatelliteNetworkInfoList;
        mBarredPlmnList = barredPlmnList;
    }

    /**
     * Get the entitlement status.
     *
     * @return The entitlement status.
     */
    public @SatelliteEntitlementStatus int getEntitlementStatus() {
        return mEntitlementStatus;
    }

    /**
     * Get the plmn allowed list
     *
     * @return The plmn allowed list.
     */
    public List<String> getAllowedPLMNList() {
        return mAllowedSatelliteNetworkInfoList.stream().map(info -> info.mPlmn).collect(
                Collectors.toList());
    }

    /**
     * Get the plmn barred list
     *
     * @return The plmn barred list.
     */
    public List<String> getBarredPLMNList() {
        return mBarredPlmnList.stream().map(String::new).collect(Collectors.toList());
    }

    /**
     * Get the default SatelliteEntitlementResult. EntitlementStatus set to
     * `SATELLITE_ENTITLEMENT_STATUS_DISABLED` and SatelliteNetworkInfo list set to empty.
     *
     * @return If there is no response, return default SatelliteEntitlementResult
     */
    public static SatelliteEntitlementResult getDefaultResult() {
        return new SatelliteEntitlementResult(SATELLITE_ENTITLEMENT_STATUS_DISABLED,
                new ArrayList<>(), new ArrayList<>());
    }

    /**
     * Get the data plan for the plmn List
     *
     * @return data plan for the plmn List
     */
    public Map<String, Integer> getDataPlanInfoForPlmnList() {
        Map<String, Integer> dataPlanInfo = new HashMap<>();

        for (SatelliteNetworkInfo plmnInfo :  mAllowedSatelliteNetworkInfoList) {
            int dataPlan = SATELLITE_DATA_PLAN_METERED; // default metered is available
            if (plmnInfo.mDataPlanType.equalsIgnoreCase("unmetered")) {
                dataPlan = SATELLITE_DATA_PLAN_UNMETERED; // overwrite data plan if unmetered
            }
            dataPlanInfo.put(plmnInfo.mPlmn, dataPlan);
        }
        return dataPlanInfo;
    }

    /**
     * Get ServiceType at Allowed Services for the plmn List
     *
     * @return The Allowed Services for the plmn List
     */
    public Map<String, List<Integer>> getAvailableServiceTypeInfoForPlmnList() {
        Map<String, List<Integer>> availableServicesInfo = new HashMap<>();
        for (SatelliteNetworkInfo plmnInfo : mAllowedSatelliteNetworkInfoList) {
            List<Integer> allowedServicesList = new ArrayList<>();
            if (plmnInfo.mAllowedServicesInfo != null) {
                for (String key : plmnInfo.mAllowedServicesInfo.keySet()) {
                    if (key.equalsIgnoreCase("data")) {
                        allowedServicesList.add(SERVICE_TYPE_DATA);
                    } else if (key.equalsIgnoreCase("voice")) {
                        allowedServicesList.add(SERVICE_TYPE_VOICE);
                    }
                }
                // By default sms is added to the allowed services
                allowedServicesList.add(SERVICE_TYPE_SMS);
                availableServicesInfo.put(plmnInfo.mPlmn, allowedServicesList);
            }
        }
        return availableServicesInfo;
    }

    /**
     * Get ServicePolicy for data at Allowed Services for the plmn List
     *
     * @return The Allowed Services for the plmn List
     */
    public Map<String, Integer> getDataServicePolicyInfoForPlmnList() {
        return getServicePolicyInfoForServiceType("data");
    }

    /**
     * Get ServicePolicy for voice at Allowed Services for the plmn List
     *
     * @return The Allowed Services for the plmn List
     */
    public Map<String, Integer> getVoiceServicePolicyInfoForPlmnList() {
        return getServicePolicyInfoForServiceType("voice");
    }

    public Map<String, Integer> getServicePolicyInfoForServiceType(String serviceType) {
        Map<String, Integer> servicePolicyInfo = new HashMap<>();
        for (SatelliteNetworkInfo plmnInfo : mAllowedSatelliteNetworkInfoList) {
            if (plmnInfo.mAllowedServicesInfo != null) {
                for (String key : plmnInfo.mAllowedServicesInfo.keySet()) {
                    if (key.equalsIgnoreCase(serviceType)) {
                        String servicePolicy = plmnInfo.mAllowedServicesInfo.get(key);
                        if (servicePolicy.equalsIgnoreCase("constrained")) {
                            servicePolicyInfo.put(plmnInfo.mPlmn,
                                    SATELLITE_DATA_SUPPORT_BANDWIDTH_CONSTRAINED);
                        } else if (servicePolicy.equalsIgnoreCase("unconstrained")) {
                            servicePolicyInfo.put(plmnInfo.mPlmn, SATELLITE_DATA_SUPPORT_ALL);
                        }
                    }
                }
            }
        }
        return servicePolicyInfo;
    }
}
