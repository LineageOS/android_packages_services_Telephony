/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.phone.settings;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.telephony.TelephonyManager;

import com.android.internal.telephony.Phone;
import com.android.phone.GsmUmtsAdditionalCallOptions;
import com.android.phone.GsmUmtsCallOptions;
import com.android.phone.R;

/**
 * Utility class to help supplementary service functions and UI.
 */
public class SuppServicesUiUtil {
    static final String LOG_TAG = "SuppServicesUiUtil";

    /**
     * show dialog for supplementary services over ut precaution.
     *
     * @param context The context.
     * @param phone   The Phone object.
     * @param preferenceKey The preference's key.
     */
    public static Dialog showBlockingSuppServicesDialog(Context context, Phone phone,
            String preferenceKey) {
        if (context == null || phone == null) {
            return null;
        }

        String message = makeMessage(context, preferenceKey, phone);

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        DialogInterface.OnClickListener networkSettingsClickListener =
                new Dialog.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        context.startActivity(new Intent(context,
                                com.android.phone.MobileNetworkSettings.class));
                    }
                };
        return builder.setMessage(message)
                .setNeutralButton(context.getResources().getString(
                        R.string.settings_label),
                        networkSettingsClickListener)
                .setPositiveButton(context.getResources().getString(
                        R.string.supp_service_over_ut_precautions_dialog_dismiss), null)
                .create();
    }

    private static String makeMessage(Context context, String preferenceKey, Phone phone) {
        String message = "";
        int simSlot = (phone.getPhoneId() == 0) ? 1 : 2;
        String suppServiceName = getSuppServiceName(context, preferenceKey);

        TelephonyManager telephonyManager =
                (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        boolean isRoaming = telephonyManager.isNetworkRoaming(phone.getSubId());
        boolean isMultiSim = (telephonyManager.getSimCount() > 1);

        if (!isMultiSim) {
            if (isRoaming) {
                message = context.getResources().getString(
                        R.string.supp_service_over_ut_precautions_roaming, suppServiceName);
            } else {
                message = context.getResources().getString(
                        R.string.supp_service_over_ut_precautions, suppServiceName);
            }
        } else {
            if (isRoaming) {
                message = context.getResources().getString(
                        R.string.supp_service_over_ut_precautions_roaming_dual_sim, suppServiceName,
                        simSlot);
            } else {
                message = context.getResources().getString(
                        R.string.supp_service_over_ut_precautions_dual_sim, suppServiceName,
                        simSlot);
            }
        }
        return message;
    }

    private static String getSuppServiceName(Context context, String preferenceKey) {
        String suppServiceName = "";
        if (preferenceKey.equals(GsmUmtsCallOptions.CALL_FORWARDING_KEY)) {
            suppServiceName = context.getResources().getString(R.string.labelCF);
        } else if (preferenceKey.equals(GsmUmtsCallOptions.CALL_BARRING_KEY)) {
            suppServiceName = context.getResources().getString(R.string.labelCallBarring);
        } else if (preferenceKey.equals(GsmUmtsAdditionalCallOptions.BUTTON_CLIR_KEY)) {
            suppServiceName = context.getResources().getString(R.string.labelCallerId);
        } else if (preferenceKey.equals(GsmUmtsAdditionalCallOptions.BUTTON_CW_KEY)) {
            suppServiceName = context.getResources().getString(R.string.labelCW);
        }
        return suppServiceName;
    }

    /**
     * Check SS over Ut precautions in condition which is
     * "mobile data button is off" or "Roaming button is off during roaming".
     *
     * @param context The context.
     * @param phone   The Phone object.
     * @return "mobile data button is off" or "Roaming button is off during roaming", return true.
     */
    public static boolean isSsOverUtPrecautions(Context context, Phone phone) {
        if (phone == null || context == null) {
            return false;
        }
        return isMobileDataOff(context, phone) || isDataRoamingOffUnderRoaming(context, phone);
    }

    private static boolean isMobileDataOff(Context context, Phone phone) {
        TelephonyManager telephonyManager =
                (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        return !telephonyManager.getDataEnabled(phone.getSubId());
    }

    private static boolean isDataRoamingOffUnderRoaming(Context context, Phone phone) {
        TelephonyManager telephonyManager =
                (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        return telephonyManager.isNetworkRoaming(phone.getSubId())
                && !phone.getDataRoamingEnabled();
    }
}
