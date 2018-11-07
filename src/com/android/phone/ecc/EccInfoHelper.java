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

package com.android.phone.ecc;

import android.content.Context;
import android.os.AsyncTask;
import android.provider.Settings;
import android.telephony.CellIdentityGsm;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityWcdma;
import android.telephony.CellInfo;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoWcdma;
import android.telephony.PhoneNumberUtils;
import android.telephony.Rlog;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.telephony.MccTable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Helper for retrieve ECC info for current country.
 */
public class EccInfoHelper {
    // Debug constants.
    private static final boolean DBG = false;
    private static final String LOG_TAG = "EccInfoHelper";

    /**
     * Check if current CountryEccInfo is available for current environment.
     */
    public static boolean isCountryEccInfoAvailable(Context context, String countryIso) {
        CountryEccInfo countryEccInfo;
        try {
            countryEccInfo = IsoToEccProtobufRepository.getInstance()
                    .getCountryEccInfo(context, countryIso);
        } catch (IOException e) {
            Log.e(LOG_TAG, "Failed to retrieve ECC: ", e);
            return false;
        }

        if (countryEccInfo == null) {
            return false;
        }
        for (EccInfo entry : countryEccInfo.getEccInfoList()) {
            if (!PhoneNumberUtils.isEmergencyNumber(entry.getNumber())) {
                // The CountryEccInfo is unavailable if any ecc number in the local table was
                // declined.
                return false;
            }
        }
        return true;
    }

    // country ISO to ECC list data source
    private IsoToEccRepository mEccRepo;

    /**
     * Callback for {@link #getCountryEccInfoAsync}.
     */
    public interface CountryEccInfoResultCallback {
        /**
         * Called if successfully get country ECC info.
         *
         * @param iso Detected current country ISO.
         * @param countryEccInfo The EccInfo of current country.
         */
        void onSuccess(@NonNull String iso, @NonNull CountryEccInfo countryEccInfo);

        /**
         * Called if failed to get country ISO.
         */
        void onDetectCountryFailed();

        /**
         * Called if failed to get ECC info for given country ISO.
         *
         * @param iso Detected current country ISO.
         */
        void onRetrieveCountryEccInfoFailed(@NonNull String iso);
    }

    /**
     * Constructor of EccInfoHelper
     *
     * @param eccRepository A repository for ECC info, indexed by country ISO.
     */
    public EccInfoHelper(@NonNull IsoToEccRepository eccRepository) {
        mEccRepo = eccRepository;
    }

    /**
     * Get ECC info for current location, base on detected country ISO.
     * It's possible we cannot detect current country, ex. device is in airplane mode,
     * or there's no available base station near by.
     *
     * @param context The context used to access resources.
     * @param callback Callback for result.
     */
    public void getCountryEccInfoAsync(final @NonNull Context context,
            final CountryEccInfoResultCallback callback) {
        new AsyncTask<Void, Void, Pair<String, CountryEccInfo>>() {
            @Override
            protected Pair<String, CountryEccInfo> doInBackground(Void... voids) {
                String iso = getCurrentCountryIso(context);
                if (TextUtils.isEmpty(iso)) {
                    return null;
                }

                CountryEccInfo dialableCountryEccInfo;
                try {
                    // access data source in background thread to avoid possible file IO caused ANR.
                    CountryEccInfo rawEccInfo = mEccRepo.getCountryEccInfo(context, iso);
                    dialableCountryEccInfo = getDialableCountryEccInfo(rawEccInfo);
                } catch (IOException e) {
                    Log.e(LOG_TAG, "Failed to retrieve ECC: " + e.getMessage());
                    dialableCountryEccInfo = null;
                }
                return new Pair<>(iso, dialableCountryEccInfo);
            }

            @Override
            protected void onPostExecute(Pair<String, CountryEccInfo> result) {
                if (callback != null) {
                    if (result == null) {
                        callback.onDetectCountryFailed();
                    } else {
                        String iso = result.first;
                        CountryEccInfo countryEccInfo = result.second;
                        if (countryEccInfo == null) {
                            callback.onRetrieveCountryEccInfoFailed(iso);
                        } else {
                            callback.onSuccess(iso, countryEccInfo);
                        }
                    }
                }
            }
        }.execute();
    }

    @NonNull
    private CountryEccInfo getDialableCountryEccInfo(CountryEccInfo countryEccInfo) {
        ArrayList<EccInfo> dialableECCList = new ArrayList<>();
        String dialableFallback = null;

        // filter out non-dialable ECC
        if (countryEccInfo != null) {
            for (EccInfo entry : countryEccInfo.getEccInfoList()) {
                if (PhoneNumberUtils.isEmergencyNumber(entry.getNumber())) {
                    dialableECCList.add(entry);
                }
            }
            String defaultFallback = countryEccInfo.getFallbackEcc();
            if (PhoneNumberUtils.isEmergencyNumber(defaultFallback)) {
                dialableFallback = defaultFallback;
            }
        }
        return new CountryEccInfo(dialableFallback, dialableECCList);
    }

    @Nullable
    private String getCurrentCountryIso(@NonNull Context context) {
        // Do not detect country ISO if airplane mode is on
        int airplaneMode = Settings.System.getInt(context.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0);
        if (airplaneMode != 0) {
            Log.d(LOG_TAG, "Airplane mode is on, do not get country ISO.");
            return null;
        }

        TelephonyManager tm = (TelephonyManager) context.getSystemService(
                Context.TELEPHONY_SERVICE);
        String iso = tm.getNetworkCountryIso();
        if (DBG) Log.d(LOG_TAG, "Current country ISO is " + Rlog.pii(LOG_TAG, iso));

        if (TextUtils.isEmpty(iso)) {
            // XXX: according to ServiceStateTracker's implementation, retrieve cell info in a
            // thread other than TelephonyManager's main thread.
            String mcc = getCurrentMccFromCellInfo(context);
            iso = MccTable.countryCodeForMcc(mcc);
            if (DBG) {
                Log.d(LOG_TAG, "Current mcc is " + Rlog.pii(LOG_TAG, mcc) + ", mapping to ISO: "
                        + Rlog.pii(LOG_TAG, iso));
            }
        }
        return iso;
    }

    // XXX: According to ServiceStateTracker implementation, to actually get current cell info,
    // this method must be called in a separate thread from ServiceStateTracker, which is the
    // main thread of Telephony service.
    @Nullable
    private String getCurrentMccFromCellInfo(@NonNull Context context) {
        // retrieve mcc info from base station even no SIM present.
        TelephonyManager tm = (TelephonyManager) context.getSystemService(
                Context.TELEPHONY_SERVICE);
        List<CellInfo> cellInfos = tm.getAllCellInfo();
        String mcc = null;
        if (cellInfos != null) {
            for (CellInfo ci : cellInfos) {
                if (ci instanceof CellInfoGsm) {
                    CellInfoGsm cellInfoGsm = (CellInfoGsm) ci;
                    CellIdentityGsm cellIdentityGsm = cellInfoGsm.getCellIdentity();
                    mcc = cellIdentityGsm.getMccString();
                    break;
                } else if (ci instanceof CellInfoWcdma) {
                    CellInfoWcdma cellInfoWcdma = (CellInfoWcdma) ci;
                    CellIdentityWcdma cellIdentityWcdma = cellInfoWcdma.getCellIdentity();
                    mcc = cellIdentityWcdma.getMccString();
                    break;
                } else if (ci instanceof CellInfoLte) {
                    CellInfoLte cellInfoLte = (CellInfoLte) ci;
                    CellIdentityLte cellIdentityLte = cellInfoLte.getCellIdentity();
                    mcc = cellIdentityLte.getMccString();
                    break;
                }
            }
            if (DBG) Log.d(LOG_TAG, "Retrieve MCC from cell info list: " + Rlog.pii(LOG_TAG, mcc));
        } else {
            Log.w(LOG_TAG, "Cannot get cell info list.");
        }
        return mcc;
    }
}
