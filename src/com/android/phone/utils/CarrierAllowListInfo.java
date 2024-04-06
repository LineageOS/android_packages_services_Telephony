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

package com.android.phone.utils;

import android.annotation.TestApi;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.telephony.Rlog;
import android.text.TextUtils;

import com.android.internal.telephony.uicc.IccUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


public class CarrierAllowListInfo {
    private static final String LOG_TAG = "CarrierAllowListInfo";
    private JSONObject mDataJSON;
    private static final String JSON_CHARSET = "UTF-8";
    private static final String MESSAGE_DIGEST_256_ALGORITHM = "SHA-256";
    private static final String CALLER_SHA256_ID = "callerSHA256Ids";
    private static final String CALLER_CARRIER_ID = "carrierIds";
    public static final int INVALID_CARRIER_ID = -1;

    private static final String CARRIER_RESTRICTION_OPERATOR_REGISTERED_FILE =
            "CarrierRestrictionOperatorDetails.json";

    private static CarrierAllowListInfo mInstance = null;
    private Context mContext;

    private CarrierAllowListInfo(Context context) {
        mContext = context;
        loadJsonFile(context);
    }

    public static CarrierAllowListInfo loadInstance(Context context) {
        if (mInstance == null) {
            mInstance = new CarrierAllowListInfo(context);
        }
        return mInstance;
    }

    public Set<Integer> validateCallerAndGetCarrierIds(String packageName) {
        CarrierInfo carrierInfo = parseJsonForCallerInfo(packageName);
        boolean isValid = (carrierInfo != null) && validateCallerSignature(mContext, packageName,
                carrierInfo.getSHAIdList());
        return (isValid) ? carrierInfo.getCallerCarrierIdList() : Collections.singleton(
                INVALID_CARRIER_ID);
    }

    private void loadJsonFile(Context context) {
        try {
            String jsonString = getJsonFromAssets(context,
                    CARRIER_RESTRICTION_OPERATOR_REGISTERED_FILE, JSON_CHARSET);
            if (!TextUtils.isEmpty(jsonString)) {
                mDataJSON = new JSONObject(jsonString);
            }
        } catch (Exception ex) {
            Rlog.e(LOG_TAG, "CarrierAllowListInfo: JSON file reading exception = " + ex);
        }
    }

    /**
     * Parse the JSON object to fetch the given caller's SHA-Ids and carrierId.
     */
    private CarrierInfo parseJsonForCallerInfo(String callerPackage) {
        try {
            if (mDataJSON != null && callerPackage != null) {
                JSONObject callerJSON = mDataJSON.getJSONObject(callerPackage.trim());
                JSONArray callerJSONArray = callerJSON.getJSONArray(CALLER_SHA256_ID);
                JSONArray carrierIdArray = callerJSON.getJSONArray(CALLER_CARRIER_ID);

                Set<Integer> carrierIds = new HashSet<>();
                for (int index = 0; index < carrierIdArray.length(); index++) {
                    carrierIds.add(carrierIdArray.getInt(index));
                }

                List<String> appSignatures = new ArrayList<>();
                for (int index = 0; index < callerJSONArray.length(); index++) {
                    appSignatures.add((String) callerJSONArray.get(index));
                }
                return new CarrierInfo(carrierIds, appSignatures);
            }
        } catch (JSONException ex) {
            Rlog.e(LOG_TAG, "getCallerSignatureInfo: JSONException = " + ex);
        }
        return null;
    }

    /**
     * Read the Json file from the assert folder.
     *
     * @param context  context
     * @param fileName JSON file name in assets folder
     * @param charset  JSON file data format
     * @return JSON file content in string format or null in case of IOException
     */
    private static String getJsonFromAssets(Context context, String fileName, String charset) {
        String jsonStr;
        try {
            InputStream ipStream = context.getAssets().open(fileName);
            int bufSize = ipStream.available();
            byte[] fileBuffer = new byte[bufSize];
            ipStream.read(fileBuffer);
            ipStream.close();
            jsonStr = new String(fileBuffer, charset);
        } catch (IOException ex) {
            Rlog.e(LOG_TAG, "getJsonFromAssets: Exception = " + ex);
            return null;
        }
        return jsonStr;
    }

    /**
     * API fetches all the related signatures of the given package from the packageManager
     * and validate all the signatures using SHA-256.
     *
     * @param context             context
     * @param packageName         package name of the caller to validate the signatures.
     * @param allowListSignatures list of signatures to be validated.
     * @return {@code true} if all the signatures are available with package manager.
     * {@code false} if any one of the signatures won't match with package manager.
     */
    public static boolean validateCallerSignature(Context context, String packageName,
            List<String> allowListSignatures) {
        if (TextUtils.isEmpty(packageName) || allowListSignatures.size() == 0) {
            // package name is mandatory
            return false;
        }
        final PackageManager packageManager = context.getPackageManager();
        try {
            MessageDigest sha256MDigest = MessageDigest.getInstance(MESSAGE_DIGEST_256_ALGORITHM);
            final PackageInfo packageInfo = packageManager.getPackageInfo(packageName,
                    PackageManager.GET_SIGNATURES);
            for (Signature signature : packageInfo.signatures) {
                final byte[] signatureSha256 = sha256MDigest.digest(signature.toByteArray());
                final String hexSignatureSha256 = IccUtils.bytesToHexString(signatureSha256);
                if (!allowListSignatures.contains(hexSignatureSha256)) {
                    return false;
                }
            }
            return true;
        } catch (NoSuchAlgorithmException | PackageManager.NameNotFoundException ex) {
            Rlog.e(LOG_TAG, "validateCallerSignature: Exception = " + ex);
            return false;
        }
    }

    public int updateJsonForTest(String callerInfo) {
        try {
            if (callerInfo == null) {
                // reset the Json content after testing
                loadJsonFile(mContext);
            } else {
                mDataJSON = new JSONObject(callerInfo);
            }
            return 0;
        } catch (JSONException ex) {
            Rlog.e(LOG_TAG, "updateJsonForTest: Exception = " + ex);
        }
        return -1;
    }

    private static class CarrierInfo {
        final private Set<Integer> mCallerCarrierIdList;
        final private List<String> mSHAIdList;

        public CarrierInfo(Set<Integer> carrierIds, List<String> SHAIds) {
            mCallerCarrierIdList = carrierIds;
            mSHAIdList = SHAIds;
        }

        public Set<Integer> getCallerCarrierIdList() {
            return mCallerCarrierIdList;
        }

        public List<String> getSHAIdList() {
            return mSHAIdList;
        }
    }

    @TestApi
    public List<String> getShaIdList(String srcPkg, int carrierId) {
        CarrierInfo carrierInfo = parseJsonForCallerInfo(srcPkg);
        if (carrierInfo != null && carrierInfo.getCallerCarrierIdList().contains(carrierId)) {
            return carrierInfo.getSHAIdList();
        }
        Rlog.e(LOG_TAG, "getShaIdList: carrierId or shaIdList is empty");
        return Collections.EMPTY_LIST;
    }
}
