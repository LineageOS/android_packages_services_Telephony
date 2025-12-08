/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.phone.settings.hiddenmenu;

import static android.telephony.CarrierConfigManager.KEY_CARRIER_ROAMING_SATELLITE_DEFAULT_SERVICES_INT_ARRAY;
import static android.telephony.CarrierConfigManager.KEY_CARRIER_SUPPORTED_SATELLITE_SERVICES_PER_PROVIDER_BUNDLE;
import static android.telephony.CarrierConfigManager.KEY_SATELLITE_ATTACH_SUPPORTED_BOOL;
import static android.telephony.CarrierConfigManager.KEY_SATELLITE_DATA_SUPPORT_MODE_INT;
import static android.telephony.CarrierConfigManager.KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL;

import static com.android.internal.telephony.configupdate.ConfigProviderAdaptor.DOMAIN_SATELLITE;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Build;
import android.os.PersistableBundle;
import android.os.SystemProperties;
import android.telephony.CarrierConfigManager;
import android.telephony.CellIdentityCdma;
import android.telephony.CellIdentityGsm;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityNr;
import android.telephony.CellIdentityWcdma;
import android.telephony.CellInfo;
import android.telephony.CellInfoCdma;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.CellInfoWcdma;
import android.telephony.CellSignalStrength;
import android.telephony.CellSignalStrengthCdma;
import android.telephony.CellSignalStrengthGsm;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthNr;
import android.telephony.CellSignalStrengthWcdma;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.ims.ImsMmTelManager;
import android.telephony.ims.feature.MmTelFeature;
import android.telephony.ims.stub.ImsRegistrationImplBase;
import android.telephony.satellite.SatelliteManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.configupdate.TelephonyConfigUpdateInstallReceiver;
import com.android.internal.telephony.satellite.SatelliteConfig;
import com.android.internal.telephony.satellite.SatelliteConfigParser;

import java.util.Arrays;

public class PhoneInformationUtil {
    private static final String DSDS_MODE_PROPERTY = "ro.boot.hardware.dsds";
    private static final int ALWAYS_ON_DSDS_MODE = 1;
    private static final String TAG = "PhoneInformationUtil";
    private static SatelliteConfigParser sBackedUpSatelliteConfigParser;
    private static SatelliteConfig sBackedUpSatelliteConfig;
    public static Intent mNonEsosIntent;
    private static CarrierConfigManager sCarrierConfigManager;

    /** Returns whether DSDS is supported. */
    public static boolean isDsdsSupported() {
        return (TelephonyManager.getDefault().isMultiSimSupported()
                == TelephonyManager.MULTISIM_ALLOWED);
    }

    /** Returns whether DSDS is enabled. */
    public static boolean isDsdsEnabled() {
        return TelephonyManager.getDefault().getPhoneCount() > 1;
    }

    /** Returns whether the device is in a DSDS-only mode. */
    public static boolean dsdsModeOnly() {
        String dsdsMode = SystemProperties.get(DSDS_MODE_PROPERTY);
        return !TextUtils.isEmpty(dsdsMode) && Integer.parseInt(dsdsMode) == ALWAYS_ON_DSDS_MODE;
    }

    /**
     * Gets the labels for the phone indexes.
     *
     * @param tm The {@link TelephonyManager} instance.
     * @return An array of strings for each phone index. The array index is equal to the phone
     * index.
     */
    public static String[] getPhoneIndexLabels(TelephonyManager tm) {
        int phones = tm.getActiveModemCount();
        String[] labels = new String[phones];
        for (int i = 0; i < phones; i++) {
            labels[i] = "Phone " + i;
        }
        return labels;
    }

    /**
     * Gets the phone for the given subscription ID.
     */
    public static Phone getPhone(int subId) {
        log("getPhone subId = " + subId);
        Phone phone = PhoneFactory.getPhone(SubscriptionManager.getPhoneId(subId));
        if (phone == null) {
            log("return the default phone");
            return PhoneFactory.getDefaultPhone();
        }

        return phone;
    }

    private static String getCellInfoDisplayString(int i) {
        return (i != Integer.MAX_VALUE) ? Integer.toString(i) : "";
    }

    private static String getCellInfoDisplayString(long i) {
        return (i != Long.MAX_VALUE) ? Long.toString(i) : "";
    }

    private static String getConnectionStatusString(CellInfo ci) {
        String regStr = "";
        String connector = "";

        if (ci.isRegistered()) {
            regStr = "R";
        }
        String connStatStr = switch (ci.getCellConnectionStatus()) {
            case CellInfo.CONNECTION_PRIMARY_SERVING -> "P";
            case CellInfo.CONNECTION_SECONDARY_SERVING -> "S";
            case CellInfo.CONNECTION_NONE -> "N";
            case CellInfo.CONNECTION_UNKNOWN -> "";
            default -> "";
        };
        if (!TextUtils.isEmpty(regStr) && !TextUtils.isEmpty(connStatStr)) {
            connector = "+";
        }

        return regStr + connector + connStatStr;
    }

    private static String buildCdmaInfoString(CellInfoCdma ci) {
        CellIdentityCdma cidCdma = ci.getCellIdentity();
        CellSignalStrengthCdma ssCdma = ci.getCellSignalStrength();

        return String.format(
                "%-3.3s %-5.5s %-5.5s %-5.5s %-6.6s %-6.6s %-6.6s %-6.6s %-5.5s",
                getConnectionStatusString(ci),
                getCellInfoDisplayString(cidCdma.getSystemId()),
                getCellInfoDisplayString(cidCdma.getNetworkId()),
                getCellInfoDisplayString(cidCdma.getBasestationId()),
                getCellInfoDisplayString(ssCdma.getCdmaDbm()),
                getCellInfoDisplayString(ssCdma.getCdmaEcio()),
                getCellInfoDisplayString(ssCdma.getEvdoDbm()),
                getCellInfoDisplayString(ssCdma.getEvdoEcio()),
                getCellInfoDisplayString(ssCdma.getEvdoSnr()));
    }

    private static String buildGsmInfoString(CellInfoGsm ci) {
        CellIdentityGsm cidGsm = ci.getCellIdentity();
        CellSignalStrengthGsm ssGsm = ci.getCellSignalStrength();

        return String.format(
                "%-3.3s %-3.3s %-3.3s %-5.5s %-5.5s %-6.6s %-4.4s %-4.4s\n",
                getConnectionStatusString(ci),
                getCellInfoDisplayString(cidGsm.getMcc()),
                getCellInfoDisplayString(cidGsm.getMnc()),
                getCellInfoDisplayString(cidGsm.getLac()),
                getCellInfoDisplayString(cidGsm.getCid()),
                getCellInfoDisplayString(cidGsm.getArfcn()),
                getCellInfoDisplayString(cidGsm.getBsic()),
                getCellInfoDisplayString(ssGsm.getDbm()));
    }

    private static String buildLteInfoString(CellInfoLte ci) {
        CellIdentityLte cidLte = ci.getCellIdentity();
        CellSignalStrengthLte ssLte = ci.getCellSignalStrength();

        return String.format(
                "%-3.3s %-3.3s %-3.3s %-5.5s %-5.5s %-3.3s %-6.6s %-2.2s %-4.4s %-4.4s %-2.2s\n",
                getConnectionStatusString(ci),
                getCellInfoDisplayString(cidLte.getMcc()),
                getCellInfoDisplayString(cidLte.getMnc()),
                getCellInfoDisplayString(cidLte.getTac()),
                getCellInfoDisplayString(cidLte.getCi()),
                getCellInfoDisplayString(cidLte.getPci()),
                getCellInfoDisplayString(cidLte.getEarfcn()),
                getCellInfoDisplayString(cidLte.getBandwidth()),
                getCellInfoDisplayString(ssLte.getDbm()),
                getCellInfoDisplayString(ssLte.getRsrq()),
                getCellInfoDisplayString(ssLte.getTimingAdvance()));
    }

    private static String buildNrInfoString(CellInfoNr ci) {
        CellIdentityNr cidNr = (CellIdentityNr) ci.getCellIdentity();
        CellSignalStrengthNr ssNr = (CellSignalStrengthNr) ci.getCellSignalStrength();

        return String.format(
                "%-3.3s %-3.3s %-3.3s %-5.5s %-5.5s %-3.3s %-6.6s %-4.4s %-4.4s\n",
                getConnectionStatusString(ci),
                cidNr.getMccString(),
                cidNr.getMncString(),
                getCellInfoDisplayString(cidNr.getTac()),
                getCellInfoDisplayString(cidNr.getNci()),
                getCellInfoDisplayString(cidNr.getPci()),
                getCellInfoDisplayString(cidNr.getNrarfcn()),
                getCellInfoDisplayString(ssNr.getSsRsrp()),
                getCellInfoDisplayString(ssNr.getSsRsrq()));
    }

    private static String buildWcdmaInfoString(CellInfoWcdma ci) {
        CellIdentityWcdma cidWcdma = ci.getCellIdentity();
        CellSignalStrengthWcdma ssWcdma = ci.getCellSignalStrength();

        return String.format(
                "%-3.3s %-3.3s %-3.3s %-5.5s %-5.5s %-6.6s %-3.3s %-4.4s\n",
                getConnectionStatusString(ci),
                getCellInfoDisplayString(cidWcdma.getMcc()),
                getCellInfoDisplayString(cidWcdma.getMnc()),
                getCellInfoDisplayString(cidWcdma.getLac()),
                getCellInfoDisplayString(cidWcdma.getCid()),
                getCellInfoDisplayString(cidWcdma.getUarfcn()),
                getCellInfoDisplayString(cidWcdma.getPsc()),
                getCellInfoDisplayString(ssWcdma.getDbm()));
    }

    /**
     * Builds a string representation of a list of {@link CellInfo} objects.
     *
     * @param arrayCi The list of {@link CellInfo} objects.
     * @return A string representation of the cell info.
     */
    public static String buildCellInfoString(java.util.List<CellInfo> arrayCi) {
        String value = new String();
        StringBuilder cdmaCells = new StringBuilder(),
                gsmCells = new StringBuilder(),
                lteCells = new StringBuilder(),
                wcdmaCells = new StringBuilder(),
                nrCells = new StringBuilder();

        if (arrayCi != null) {
            for (CellInfo ci : arrayCi) {

                if (ci instanceof CellInfoLte) {
                    lteCells.append(buildLteInfoString((CellInfoLte) ci));
                } else if (ci instanceof CellInfoWcdma) {
                    wcdmaCells.append(buildWcdmaInfoString((CellInfoWcdma) ci));
                } else if (ci instanceof CellInfoGsm) {
                    gsmCells.append(buildGsmInfoString((CellInfoGsm) ci));
                } else if (ci instanceof CellInfoCdma) {
                    cdmaCells.append(buildCdmaInfoString((CellInfoCdma) ci));
                } else if (ci instanceof CellInfoNr) {
                    nrCells.append(buildNrInfoString((CellInfoNr) ci));
                }
            }
            if (nrCells.length() != 0) {
                value +=
                        String.format(
                                "NR\n%-3.3s %-3.3s %-3.3s %-5.5s %-5.5s %-3.3s"
                                        + " %-6.6s %-4.4s %-4.4s\n",
                                "SRV", "MCC", "MNC", "TAC", "NCI", "PCI", "NRARFCN", "SS-RSRP",
                                "SS-RSRQ");
                value += nrCells.toString();
            }

            if (lteCells.length() != 0) {
                value +=
                        String.format(
                                "LTE\n%-3.3s %-3.3s %-3.3s %-5.5s %-5.5s %-3.3s"
                                        + " %-6.6s %-2.2s %-4.4s %-4.4s %-2.2s\n",
                                "SRV", "MCC", "MNC", "TAC", "CID", "PCI", "EARFCN", "BW", "RSRP",
                                "RSRQ", "TA");
                value += lteCells.toString();
            }
            if (wcdmaCells.length() != 0) {
                value +=
                        String.format(
                                "WCDMA\n%-3.3s %-3.3s %-3.3s %-5.5s %-5.5s %-6.6s %-3.3s %-4.4s\n",
                                "SRV", "MCC", "MNC", "LAC", "CID", "UARFCN", "PSC", "RSCP");
                value += wcdmaCells.toString();
            }
            if (gsmCells.length() != 0) {
                value +=
                        String.format(
                                "GSM\n%-3.3s %-3.3s %-3.3s %-5.5s %-5.5s %-6.6s %-4.4s %-4.4s\n",
                                "SRV", "MCC", "MNC", "LAC", "CID", "ARFCN", "BSIC", "RSSI");
                value += gsmCells.toString();
            }
            if (cdmaCells.length() != 0) {
                value +=
                        String.format(
                                "CDMA/EVDO\n%-3.3s %-5.5s %-5.5s %-5.5s"
                                        + " %-6.6s %-6.6s %-6.6s %-6.6s %-5.5s\n",
                                "SRV", "SID", "NID", "BSID", "C-RSSI", "C-ECIO", "E-RSSI", "E-ECIO",
                                "E-SNR");
                value += cdmaCells.toString();
            }
        } else {
            value = "unknown";
        }

        return value.toString();
    }

    /**
     * Returns whether voice service is available over IMS.
     *
     * @param imsMmTelManager The {@link ImsMmTelManager} instance.
     * @return {@code true} if voice service is available, {@code false} otherwise.
     */
    public static boolean isVoiceServiceAvailable(ImsMmTelManager imsMmTelManager) {
        if (imsMmTelManager == null) {
            return false;
        }

        final int[] radioTechs = {
            ImsRegistrationImplBase.REGISTRATION_TECH_LTE,
            ImsRegistrationImplBase.REGISTRATION_TECH_CROSS_SIM,
            ImsRegistrationImplBase.REGISTRATION_TECH_NR,
            ImsRegistrationImplBase.REGISTRATION_TECH_3G
        };

        boolean isAvailable = false;
        for (int tech : radioTechs) {
            try {
                isAvailable |= imsMmTelManager.isAvailable(
                        MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VOICE, tech);
                if (isAvailable) {
                    break;
                }
            } catch (Exception e) {
                loge("isVoiceServiceAvailable: exception=" + e);
            }
        }
        return isAvailable;
    }

    /**
     * Returns whether video service is available over IMS.
     *
     * @param imsMmTelManager The {@link ImsMmTelManager} instance.
     * @return {@code true} if video service is available, {@code false} otherwise.
     */
    public static boolean isVideoServiceAvailable(ImsMmTelManager imsMmTelManager) {
        if (imsMmTelManager == null) {
            return false;
        }

        final int[] radioTechs = {
            ImsRegistrationImplBase.REGISTRATION_TECH_LTE,
            ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN,
            ImsRegistrationImplBase.REGISTRATION_TECH_CROSS_SIM,
            ImsRegistrationImplBase.REGISTRATION_TECH_NR,
            ImsRegistrationImplBase.REGISTRATION_TECH_3G
        };

        boolean isAvailable = false;
        for (int tech : radioTechs) {
            try {
                isAvailable |= imsMmTelManager.isAvailable(
                        MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VIDEO, tech);
                if (isAvailable) {
                    break;
                }
            } catch (Exception e) {
                loge("isVideoServiceAvailable: exception=" + e);
            }
        }
        return isAvailable;
    }

    /**
     * Returns whether Wi-Fi calling service is available.
     *
     * @param imsMmTelManager The {@link ImsMmTelManager} instance.
     * @return {@code true} if Wi-Fi calling is available, {@code false} otherwise.
     */
    public static boolean isWfcServiceAvailable(ImsMmTelManager imsMmTelManager) {
        if (imsMmTelManager == null) {
            return false;
        }

        boolean isAvailable = false;
        try {
            isAvailable = imsMmTelManager.isAvailable(
                    MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VOICE,
                    ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN);
        } catch (Exception e) {
            loge("isWfcServiceAvailable: exception=" + e);
        }
        return isAvailable;
    }

    public static final String[] PREFERRED_NETWORK_LABELS = {
            "GSM/WCDMA preferred",
            "GSM only",
            "WCDMA only",
            "GSM/WCDMA auto (PRL)",
            "CDMA/EvDo auto (PRL)",
            "CDMA only",
            "EvDo only",
            "CDMA/EvDo/GSM/WCDMA (PRL)",
            "CDMA + LTE/EvDo (PRL)",
            "GSM/WCDMA/LTE (PRL)",
            "LTE/CDMA/EvDo/GSM/WCDMA (PRL)",
            "LTE only",
            "LTE/WCDMA",
            "TDSCDMA only",
            "TDSCDMA/WCDMA",
            "LTE/TDSCDMA",
            "TDSCDMA/GSM",
            "LTE/TDSCDMA/GSM",
            "TDSCDMA/GSM/WCDMA",
            "LTE/TDSCDMA/WCDMA",
            "LTE/TDSCDMA/GSM/WCDMA",
            "TDSCDMA/CDMA/EvDo/GSM/WCDMA ",
            "LTE/TDSCDMA/CDMA/EvDo/GSM/WCDMA",
            "NR only",
            "NR/LTE",
            "NR/LTE/CDMA/EvDo",
            "NR/LTE/GSM/WCDMA",
            "NR/LTE/CDMA/EvDo/GSM/WCDMA",
            "NR/LTE/WCDMA",
            "NR/LTE/TDSCDMA",
            "NR/LTE/TDSCDMA/GSM",
            "NR/LTE/TDSCDMA/WCDMA",
            "NR/LTE/TDSCDMA/GSM/WCDMA",
            "NR/LTE/TDSCDMA/CDMA/EvDo/GSM/WCDMA",
            "Unknown"
    };

    public static final Integer[]SIGNAL_STRENGTH_LEVEL =
            new Integer[] {
                -1 /*clear mock*/,
                CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN,
                CellSignalStrength.SIGNAL_STRENGTH_POOR,
                CellSignalStrength.SIGNAL_STRENGTH_MODERATE,
                CellSignalStrength.SIGNAL_STRENGTH_GOOD,
                CellSignalStrength.SIGNAL_STRENGTH_GREAT
            };

    public static final Integer[] MOCK_DATA_NETWORK_TYPE =
            new Integer[] {
                -1 /*clear mock*/,
                ServiceState.RIL_RADIO_TECHNOLOGY_GPRS,
                ServiceState.RIL_RADIO_TECHNOLOGY_EDGE,
                ServiceState.RIL_RADIO_TECHNOLOGY_UMTS,
                ServiceState.RIL_RADIO_TECHNOLOGY_IS95A,
                ServiceState.RIL_RADIO_TECHNOLOGY_IS95B,
                ServiceState.RIL_RADIO_TECHNOLOGY_1xRTT,
                ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_0,
                ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_A,
                ServiceState.RIL_RADIO_TECHNOLOGY_HSDPA,
                ServiceState.RIL_RADIO_TECHNOLOGY_HSUPA,
                ServiceState.RIL_RADIO_TECHNOLOGY_HSPA,
                ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_B,
                ServiceState.RIL_RADIO_TECHNOLOGY_EHRPD,
                ServiceState.RIL_RADIO_TECHNOLOGY_LTE,
                ServiceState.RIL_RADIO_TECHNOLOGY_HSPAP,
                ServiceState.RIL_RADIO_TECHNOLOGY_GSM,
                ServiceState.RIL_RADIO_TECHNOLOGY_TD_SCDMA,
                ServiceState.RIL_RADIO_TECHNOLOGY_LTE_CA,
                ServiceState.RIL_RADIO_TECHNOLOGY_NR
            };

    /**
     * Uncaps the maximum allowed data mode for satellite communication.
     *
     * <p>This method overrides the satellite configuration to allow all data support modes. It
     * backs up the current satellite configuration before applying the override.
     */
    public static void uncapMaxAllowedDataMode() {
        log("uncapMaxAllowedDataMode: uncap max allowed data mode by overriding satellite config");
        SatelliteConfigParser satelliteConfigParser =
                (SatelliteConfigParser)
                        TelephonyConfigUpdateInstallReceiver.getInstance()
                                .getConfigParser(DOMAIN_SATELLITE);
        SatelliteConfig satelliteConfig =
                satelliteConfigParser != null ? satelliteConfigParser.getConfig() : null;

        log("uncapMaxAllowedDataMode: backing up satellite config parser: "
                + satelliteConfigParser);
        sBackedUpSatelliteConfigParser = satelliteConfigParser;

        log("uncapMaxAllowedDataMode: backing up satellite config: " + satelliteConfig);
        sBackedUpSatelliteConfig = satelliteConfig;

        SatelliteConfig uncappedSatelliteConfig;
        if (satelliteConfig == null) {
            log("uncapMaxAllowedDataMode: satelliteConfig is null, creating new SatelliteConfig"
                    + " just to uncap max allowed data mode");
            uncappedSatelliteConfig = new SatelliteConfig();
        } else {
            log("uncapMaxAllowedDataMode: satelliteConfig is not null, make a deepcopy just to"
                    + " uncap max allowed data mode");
            uncappedSatelliteConfig = new SatelliteConfig(satelliteConfig);
        }
        uncappedSatelliteConfig.overrideSatelliteMaxAllowedDataMode(
                CarrierConfigManager.SATELLITE_DATA_SUPPORT_ALL);

        log(
                "uncapMaxAllowedDataMode: creating uncappedSatelliteConfigParser to uncap max"
                        + " allowed data mode");
        SatelliteConfigParser uncappedSatelliteConfigParser =
                new SatelliteConfigParser(new byte[] {});

        uncappedSatelliteConfigParser.overrideConfig(uncappedSatelliteConfig);
        TelephonyConfigUpdateInstallReceiver.getInstance()
                .overrideConfigParser(uncappedSatelliteConfigParser);
    }

    /**
     * Restores the original maximum allowed data mode for satellite communication.
     *
     * <p>This method restores the satellite configuration that was backed up before {@link
     * #uncapMaxAllowedDataMode()} was called.
     */
    public static void restoreMaxAllowedDataMode() {
        log("restoreMaxAllowedDataMode: restoring max allowed data mode by restoring the backed"
                + " up satellite config parser: " + sBackedUpSatelliteConfigParser + " and config: "
                + sBackedUpSatelliteConfig);
        TelephonyConfigUpdateInstallReceiver.getInstance().overrideConfigParser(
                sBackedUpSatelliteConfigParser);
        if (sBackedUpSatelliteConfigParser == null) {
            log("restoreMaxAllowedDataMode: mBackedUpSatelliteConfigParser is null, therefore"
                    + " don't have to override mBackedUpSatelliteConfig, as it would null" + " as"
                    + " well");
            return;
        }
        TelephonyConfigUpdateInstallReceiver.getInstance().getConfigParser(
                DOMAIN_SATELLITE).overrideConfig(sBackedUpSatelliteConfig);
    }

    /**
     * Determines whether the UI for starting a non-emergency satellite session should be displayed.
     *
     * <p>This method performs several checks to validate if the feature is enabled and correctly
     * configured:
     * <ul>
     *     <li>The build must be debuggable.</li>
     *     <li>The carrier configuration for the given subscription must indicate support for both
     *         satellite attach and ESOS (Emergency SOS).</li>
     *     <li>The system overlays must define a valid package and class name for the satellite
     *         gateway service and the non-emergency session receiver.</li>
     *     <li>A {@link android.content.BroadcastReceiver} must be available to handle the
     *         {@link SatelliteManager#ACTION_SATELLITE_START_NON_EMERGENCY_SESSION} intent.</li>
     * </ul>
     *
     * <p>As a side effect, if all conditions are met, this method populates the static
     * {@link #mNonEsosIntent} field with the created {@link Intent} for later use.
     *
     * @param context The {@link Context} to access system services and resources.
     * @param mSubId The subscription ID to check carrier configurations against.
     * @return {@code true} if the non-emergency mode UI should be displayed, {@code false} if it
     *         should be hidden.
     */
    public static boolean shouldHideNonEmergencyMode(Context context, int mSubId) {
        if (!Build.isDebuggable()) {
            return false;
        }
        String action = SatelliteManager.ACTION_SATELLITE_START_NON_EMERGENCY_SESSION;
        if (TextUtils.isEmpty(action)) {
            return false;
        }
        if (mNonEsosIntent != null) {
            mNonEsosIntent = null;
        }
        CarrierConfigManager carrierConfigManager =
                context.getSystemService(CarrierConfigManager.class);
        if (carrierConfigManager == null) {
            loge("shouldHideNonEmergencyMode: cm is null");
            return false;
        }
        android.os.PersistableBundle bundle = carrierConfigManager.getConfigForSubId(mSubId,
                KEY_SATELLITE_ATTACH_SUPPORTED_BOOL,
                CarrierConfigManager.KEY_SATELLITE_ESOS_SUPPORTED_BOOL);
        if (!bundle.getBoolean(CarrierConfigManager.KEY_SATELLITE_ESOS_SUPPORTED_BOOL, false)) {
            log("shouldHideNonEmergencyMode: esos_supported false");
            return false;
        }
        if (!bundle.getBoolean(KEY_SATELLITE_ATTACH_SUPPORTED_BOOL, false)) {
            log("shouldHideNonEmergencyMode: attach_supported false");
            return false;
        }

        String packageName = getStringFromOverlayConfig(context,
                com.android.internal.R.string.config_satellite_gateway_service_package);

        String className = getStringFromOverlayConfig(context,
                com.android.internal
                        .R.string.config_satellite_carrier_roaming_non_emergency_session_class);
        if (packageName == null || className == null || packageName.isEmpty()
                || className.isEmpty()) {
            log("shouldHideNonEmergencyMode:" + " packageName or className is null or empty.");
            return false;
        }
        PackageManager pm = context.getPackageManager();
        Intent intent = new Intent(action);
        intent.setComponent(new ComponentName(packageName, className));
        if (pm.queryBroadcastReceivers(intent, 0).isEmpty()) {
            log("shouldHideNonEmergencyMode: Broadcast receiver not found for intent: " + intent);
            return false;
        }
        mNonEsosIntent = intent;
        return true;
    }

    /**
     * Method will create the PersistableBundle and pack the satellite services like
     * SMS, MMS, EMERGENCY CALL, DATA in it.
     *
     * @param telephonyManager The TelephonyManager instance.
     * @param phoneId The phone ID.
     * @param subId The subscription ID.
     * @param originalBundle The original PersistableBundle.
     * @return A new PersistableBundle with satellite services.
     */
    public static PersistableBundle getSatelliteServicesBundleForOperatorPlmn(
            TelephonyManager telephonyManager,
            int phoneId,
            int subId,
            PersistableBundle originalBundle) {
        String plmn = telephonyManager.getNetworkOperatorForPhone(phoneId);
        if (TextUtils.isEmpty(plmn)) {
            loge("satData: NetworkOperator PLMN is empty");
            plmn = telephonyManager.getSimOperatorNumeric(subId);
            loge("satData: SimOperator PLMN = " + plmn);
        }
        int[] supportedServicesArray = {
            NetworkRegistrationInfo.SERVICE_TYPE_DATA,
            NetworkRegistrationInfo.SERVICE_TYPE_SMS,
            NetworkRegistrationInfo.SERVICE_TYPE_EMERGENCY,
            NetworkRegistrationInfo.SERVICE_TYPE_MMS
        };

        PersistableBundle satServicesPerBundle = originalBundle.getPersistableBundle(
                KEY_CARRIER_SUPPORTED_SATELLITE_SERVICES_PER_PROVIDER_BUNDLE);
        // New bundle is required, as existed one will throw `ArrayMap is immutable` when we try
        // to modify.
        PersistableBundle newSatServicesPerBundle = new PersistableBundle();
        //Copy the values from the old bundle into the new bundle.
        boolean hasPlmnKey = false;
        if (satServicesPerBundle != null) {
            for (String key : satServicesPerBundle.keySet()) {
                if (!TextUtils.isEmpty(key) && key.equalsIgnoreCase(plmn)) {
                    newSatServicesPerBundle.putIntArray(plmn, supportedServicesArray);
                    hasPlmnKey = true;
                } else {
                    newSatServicesPerBundle.putIntArray(key, satServicesPerBundle.getIntArray(key));
                }
            }
        }
        if (!hasPlmnKey) {
            newSatServicesPerBundle.putIntArray(plmn, supportedServicesArray);
        }
        log("satData: New SatelliteServicesBundle = " + newSatServicesPerBundle);
        return newSatServicesPerBundle;
    }

    /**
     *This method will check the required carrier config keys which plays role in enabling /
     * supporting satellite data and update the keys accordingly.
     *
     * @param carrierConfigManager The CarrierConfigManager instance.
     * @param subId The subscription ID.
     * @param bundleToModify The PersistableBundle to modify.
     */
    public static void updateCarrierConfigToSupportData(
            CarrierConfigManager carrierConfigManager,
            int subId,
            PersistableBundle bundleToModify) {
        int[] availableServices = bundleToModify.getIntArray(
                KEY_CARRIER_ROAMING_SATELLITE_DEFAULT_SERVICES_INT_ARRAY);
        int[] newServices;
        if (availableServices != null && availableServices.length > 0) {
            if (Arrays.stream(availableServices)
                    .anyMatch(element -> element == NetworkRegistrationInfo.SERVICE_TYPE_DATA)) {
                newServices = new int[availableServices.length];
                System.arraycopy(availableServices, 0, newServices, 0, availableServices.length);
            } else {
                newServices = new int[availableServices.length + 1];
                System.arraycopy(availableServices, 0, newServices, 0, availableServices.length);
                newServices[newServices.length - 1] = NetworkRegistrationInfo.SERVICE_TYPE_DATA;
            }
        } else {
            newServices = new int[1];
            newServices[0] = NetworkRegistrationInfo.SERVICE_TYPE_DATA;
        }
        bundleToModify.putIntArray(
                KEY_CARRIER_ROAMING_SATELLITE_DEFAULT_SERVICES_INT_ARRAY, newServices);
        bundleToModify.putBoolean(KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL, false);
        bundleToModify.remove(KEY_SATELLITE_DATA_SUPPORT_MODE_INT);
        log("satData: changing carrierConfig to : " + bundleToModify);
        carrierConfigManager.overrideConfig(subId, bundleToModify, false);
    }

    private static String getStringFromOverlayConfig(Context context, int resourceId) {
        String name;
        try {
            name = context.getResources().getString(resourceId);
        } catch (Resources.NotFoundException ex) {
            loge("getStringFromOverlayConfig: ex=" + ex);
            name = null;
        }
        return name;
    }

    /**
     * Gets a cached instance of the {@link CarrierConfigManager}.
     *
     * <p>This method lazily initializes the {@link CarrierConfigManager} on the first call
     * and returns the cached instance on subsequent calls to avoid repeated lookups.
     *
     * @param context The {@link Context} used to retrieve the system service.
     * @return The singleton {@link CarrierConfigManager} instance.
     */
    public static CarrierConfigManager getCarrierConfig(Context context) {
        if (sCarrierConfigManager == null) {
            sCarrierConfigManager = context.getSystemService(CarrierConfigManager.class);
        }
        return sCarrierConfigManager;
    }

    /**
     * Configures the visibility and labels of phone selection buttons and titles.
     *
     * <p>This method adjusts the UI based on the number of active modems. If two phones are
     * active, it displays and labels both selection buttons. If only one is active, it shows
     * a single button. If no phones are active, all selection buttons are hidden.
     *
     * @param phoneButton0     The button for selecting the first phone (Phone 0).
     * @param phoneButton1     The button for selecting the second phone (Phone 1).
     * @param phoneTitle0      The TextView displaying the label for the first phone.
     * @param phoneTitle1      The TextView displaying the label for the second phone.
     * @param phoneIndexLabels An array of labels for each phone, e.g., ["Phone 0", "Phone 1"].
     */
    public static void configurePhoneSelectionUi(LinearLayout phoneButton0,
            LinearLayout phoneButton1, TextView phoneTitle0, TextView phoneTitle1,
            String[] phoneIndexLabels) {
        if (phoneIndexLabels.length > 1) {
            phoneTitle0.setText(phoneIndexLabels[0]);
            phoneTitle1.setText(phoneIndexLabels[1]);
            phoneButton0.setVisibility(View.VISIBLE);
            phoneButton1.setVisibility(View.VISIBLE);
        } else if (phoneIndexLabels.length == 1) {
            phoneTitle0.setText(phoneIndexLabels[0]);
            phoneButton0.setVisibility(View.VISIBLE);
            phoneButton1.setVisibility(View.GONE);
        } else {
            phoneButton0.setVisibility(View.GONE);
            phoneButton1.setVisibility(View.GONE);
        }
    }

    private static void log(String s) {
        Log.d(TAG, s);
    }

    private static void loge(String s) {
        Log.e(TAG, s);
    }
}
