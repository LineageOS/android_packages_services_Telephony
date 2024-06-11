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

package com.android.services.telephony.domainselection;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.os.SystemProperties;
import android.telephony.PhoneNumberUtils;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.telephony.emergency.EmergencyNumber;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.LocaleTracker;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.ServiceStateTracker;
import com.android.phone.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Manages dynamic routing of emergency numbers.
 *
 * Normal routing shall be tried if noraml service is available.
 * Otherwise, emergency routing shall be tried.
 */
public class DynamicRoutingController {
    private static final String TAG = "DynamicRoutingController";
    private static final boolean DBG = (SystemProperties.getInt("ro.debuggable", 0) == 1);

    private static final DynamicRoutingController sInstance =
            new DynamicRoutingController();

    /** PhoneFactory Dependencies for testing. */
    @VisibleForTesting
    public interface PhoneFactoryProxy {
        Phone getPhone(int phoneId);
    }

    private static class PhoneFactoryProxyImpl implements PhoneFactoryProxy {
        @Override
        public Phone getPhone(int phoneId) {
            return PhoneFactory.getPhone(phoneId);
        }
    }

    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(
                    TelephonyManager.ACTION_NETWORK_COUNTRY_CHANGED)) {
                int phoneId = intent.getIntExtra(PhoneConstants.PHONE_KEY, -1);
                String countryIso = intent.getStringExtra(
                        TelephonyManager.EXTRA_NETWORK_COUNTRY);
                Log.i(TAG, "ACTION_NETWORK_COUNTRY_CHANGED phoneId: " + phoneId
                        + " countryIso: " + countryIso);
                if (TextUtils.isEmpty(countryIso)) {
                    countryIso = getLastKnownCountryIso(phoneId);
                    if (TextUtils.isEmpty(countryIso)) {
                        return;
                    }
                }
                String prevIso = mNetworkCountries.get(Integer.valueOf(phoneId));
                if (!TextUtils.equals(prevIso, countryIso)) {
                    mNetworkCountries.put(Integer.valueOf(phoneId), countryIso);
                    updateDynamicEmergencyNumbers(phoneId);
                }
                mLastCountryIso = countryIso;
            }
        }
    };

    private String getLastKnownCountryIso(int phoneId) {
        try {
            Phone phone = mPhoneFactoryProxy.getPhone(phoneId);
            if (phone == null) return "";

            ServiceStateTracker sst = phone.getServiceStateTracker();
            if (sst == null) return "";

            LocaleTracker lt = sst.getLocaleTracker();
            if (lt != null) {
                String iso = lt.getLastKnownCountryIso();
                Log.e(TAG, "getLastKnownCountryIso iso=" + iso);
                return iso;
            }
        } catch (Exception e) {
            Log.e(TAG, "getLastKnownCountryIso e=" + e);
        }
        return "";
    }

    private final PhoneFactoryProxy mPhoneFactoryProxy;
    private final ArrayMap<Integer, String> mNetworkCountries = new ArrayMap<>();
    private final ArrayMap<Integer, List<EmergencyNumber>> mEmergencyNumbers = new ArrayMap<>();

    private String mLastCountryIso;
    private boolean mEnabled;
    private List<String> mCountriesEnabled = null;
    private List<String> mDynamicNumbers = null;


    /**
     * Returns the singleton instance of DynamicRoutingController.
     *
     * @return A {@link DynamicRoutingController} instance.
     */
    public static DynamicRoutingController getInstance() {
        return sInstance;
    }

    private DynamicRoutingController() {
          this(new PhoneFactoryProxyImpl());
    }

    @VisibleForTesting
    public DynamicRoutingController(PhoneFactoryProxy phoneFactoryProxy) {
        mPhoneFactoryProxy = phoneFactoryProxy;
    }

    /**
     * Initializes the instance.
     *
     * @param context The context of the application.
     */
    public void initialize(Context context) {
        try {
            mEnabled = context.getResources().getBoolean(R.bool.dynamic_routing_emergency_enabled);
        } catch (Resources.NotFoundException nfe) {
            Log.e(TAG, "init exception=" + nfe);
        } catch (NullPointerException npe) {
            Log.e(TAG, "init exception=" + npe);
        }

        mCountriesEnabled = readResourceConfiguration(context,
                R.array.config_countries_dynamic_routing_emergency_enabled);

        mDynamicNumbers = readResourceConfiguration(context,
                R.array.config_dynamic_routing_emergency_numbers);

        Log.i(TAG, "init enabled=" + mEnabled + ", countriesEnabled=" + mCountriesEnabled);

        if (mEnabled) {
            //register country change listener
            IntentFilter filter = new IntentFilter(TelephonyManager.ACTION_NETWORK_COUNTRY_CHANGED);
            context.registerReceiver(mIntentReceiver, filter);
        }
    }

    private List<String> readResourceConfiguration(Context context, int id) {
        Log.i(TAG, "readResourceConfiguration id=" + id);

        List<String> resource = null;
        try {
            resource = Arrays.asList(context.getResources().getStringArray(id));
        } catch (Resources.NotFoundException nfe) {
            Log.e(TAG, "readResourceConfiguration exception=" + nfe);
        } catch (NullPointerException npe) {
            Log.e(TAG, "readResourceConfiguration exception=" + npe);
        } finally {
            if (resource == null) {
                resource = new ArrayList<String>();
            }
        }
        return resource;
    }

    /**
     * Returns whether the dynamic routing feature is enabled.
     */
    public boolean isDynamicRoutingEnabled() {
        Log.i(TAG, "isDynamicRoutingEnabled " + mEnabled);
        return mEnabled;
    }

    /**
     * Returns whether the dynamic routing is enabled with the given {@link Phone}.
     * @param phone A {@link Phone} instance.
     */
    public boolean isDynamicRoutingEnabled(Phone phone) {
        Log.i(TAG, "isDynamicRoutingEnabled");
        if (phone == null) return false;
        String iso = mNetworkCountries.get(Integer.valueOf(phone.getPhoneId()));
        Log.i(TAG, "isDynamicRoutingEnabled phoneId=" + phone.getPhoneId() + ", iso=" + iso
                + ", lastIso=" + mLastCountryIso);
        if (TextUtils.isEmpty(iso)) {
            iso = mLastCountryIso;
        }
        boolean ret = mEnabled && mCountriesEnabled.contains(iso);
        Log.i(TAG, "isDynamicRoutingEnabled returns " + ret);
        return ret;
    }

    /**
     * Returns emergency call routing that to be used for the given number.
     * @param phone A {@link Phone} instance.
     * @param number The dialed number.
     * @param isNormal Indicates whether it is normal routing number.
     * @param isAllowed Indicates whether it is allowed emergency number.
     * @param needToTurnOnRadio Indicates whether it needs to turn on radio power.
     */
    public int getEmergencyCallRouting(Phone phone, String number,
            boolean isNormal, boolean isAllowed, boolean needToTurnOnRadio) {
        Log.i(TAG, "getEmergencyCallRouting isNormal=" + isNormal + ", isAllowed=" + isAllowed
                + ", needToTurnOnRadio=" + needToTurnOnRadio);
        number = PhoneNumberUtils.stripSeparators(number);
        boolean isDynamic = isDynamicNumber(phone, number);
        if ((!isNormal && !isDynamic && isAllowed) || isFromNetworkOrSim(phone, number)) {
            return EmergencyNumber.EMERGENCY_CALL_ROUTING_UNKNOWN;
        }
        if (isDynamicRoutingEnabled(phone)) {
            // If airplane mode is enabled, check the service state
            // after turning on the radio power.
            return (phone.getServiceState().getState() == ServiceState.STATE_IN_SERVICE
                    || needToTurnOnRadio)
                    ? EmergencyNumber.EMERGENCY_CALL_ROUTING_NORMAL
                    : EmergencyNumber.EMERGENCY_CALL_ROUTING_EMERGENCY;
        }
        return EmergencyNumber.EMERGENCY_CALL_ROUTING_NORMAL;
    }

    private boolean isFromNetworkOrSim(Phone phone, String number) {
        if (phone == null) return false;
        Log.i(TAG, "isFromNetworkOrSim phoneId=" + phone.getPhoneId());
        if (phone.getEmergencyNumberTracker() == null) return false;
        for (EmergencyNumber num : phone.getEmergencyNumberTracker().getEmergencyNumbers(
                number)) {
            if (num.getNumber().equals(number)) {
                if (num.isFromSources(EmergencyNumber.EMERGENCY_NUMBER_SOURCE_NETWORK_SIGNALING)
                        || num.isFromSources(EmergencyNumber.EMERGENCY_NUMBER_SOURCE_SIM)) {
                    Log.i(TAG, "isFromNetworkOrSim SIM or NETWORK emergency number");
                    return true;
                }
            }
        }
        return false;
    }

    private String getNetworkCountryIso(int phoneId) {
        String iso = mNetworkCountries.get(Integer.valueOf(phoneId));
        if (TextUtils.isEmpty(iso)) {
            iso = mLastCountryIso;
        }
        return iso;
    }

    @VisibleForTesting
    public boolean isDynamicNumber(Phone phone, String number) {
        if (phone == null || phone.getEmergencyNumberTracker() == null
                || TextUtils.isEmpty(number)
                || mDynamicNumbers == null || mDynamicNumbers.isEmpty()) {
            return false;
        }

        List<EmergencyNumber> emergencyNumbers =
                mEmergencyNumbers.get(Integer.valueOf(phone.getPhoneId()));
        if (emergencyNumbers == null) {
            updateDynamicEmergencyNumbers(phone.getPhoneId());
            emergencyNumbers =
                    mEmergencyNumbers.get(Integer.valueOf(phone.getPhoneId()));
        }
        String iso = getNetworkCountryIso(phone.getPhoneId());
        if (TextUtils.isEmpty(iso)
                || emergencyNumbers == null || emergencyNumbers.isEmpty()) {
            return false;
        }

        // Filter the list with the number.
        List<EmergencyNumber> dynamicNumbers =
                getDynamicEmergencyNumbers(emergencyNumbers, number);

        // Compare the dynamicNumbers with the list of EmergencyNumber from EmergencyNumberTracker.
        emergencyNumbers = phone.getEmergencyNumberTracker().getEmergencyNumbers(number);

        if (dynamicNumbers == null || emergencyNumbers == null
                || dynamicNumbers.isEmpty() || emergencyNumbers.isEmpty()) {
            return false;
        }

        if (DBG) {
            Log.i(TAG, "isDynamicNumber " + emergencyNumbers);
        }

        // Compare coutry ISO and MNC. MNC is optional.
        for (EmergencyNumber dynamicNumber: dynamicNumbers) {
            if (emergencyNumbers.stream().anyMatch(n ->
                    TextUtils.equals(n.getCountryIso(), dynamicNumber.getCountryIso())
                    && (TextUtils.equals(n.getMnc(), dynamicNumber.getMnc())
                    || TextUtils.isEmpty(dynamicNumber.getMnc())))) {
                Log.i(TAG, "isDynamicNumber found");
                return true;
            }
        }
        return false;
    }

    /** Filter the list of {@link EmergencyNumber} with given number. */
    private static List<EmergencyNumber> getDynamicEmergencyNumbers(
            List<EmergencyNumber> emergencyNumbers, String number) {
        List<EmergencyNumber> filteredNumbers = emergencyNumbers.stream()
                .filter(num -> num.getNumber().equals(number))
                .toList();

        if (DBG) {
            Log.i(TAG, "getDynamicEmergencyNumbers " + filteredNumbers);
        }
        return filteredNumbers;
    }

    /**
     * Generates the lis of {@link EmergencyNumber} for the given phoneId
     * based on the detected country from the resource configuration.
     */
    private void updateDynamicEmergencyNumbers(int phoneId) {
        if (mDynamicNumbers == null || mDynamicNumbers.isEmpty()) {
            // No resource configuration.
            mEmergencyNumbers.put(Integer.valueOf(phoneId),
                    new ArrayList<EmergencyNumber>());
            return;
        }

        String iso = getNetworkCountryIso(phoneId);
        if (TextUtils.isEmpty(iso)) {
            // Update again later.
            return;
        }
        List<EmergencyNumber> emergencyNumbers = new ArrayList<EmergencyNumber>();
        for (String numberInfo : mDynamicNumbers) {
            if (!TextUtils.isEmpty(numberInfo) && numberInfo.startsWith(iso)) {
                emergencyNumbers.addAll(getEmergencyNumbers(numberInfo));
            }
        }
        mEmergencyNumbers.put(Integer.valueOf(phoneId), emergencyNumbers);
    }

    /** Returns an {@link EmergencyNumber} instance from the resource configuration. */
    private List<EmergencyNumber> getEmergencyNumbers(String numberInfo) {
        ArrayList<EmergencyNumber> emergencyNumbers = new ArrayList<EmergencyNumber>();
        if (TextUtils.isEmpty(numberInfo)) {
            return emergencyNumbers;
        }

        String[] fields = numberInfo.split(",");
        // Format: "iso,mnc,number1,number2,..."
        if (fields == null || fields.length < 3
                || TextUtils.isEmpty(fields[0])
                || TextUtils.isEmpty(fields[2])) {
            return emergencyNumbers;
        }

        for (int i = 2; i < fields.length; i++) {
            if (TextUtils.isEmpty(fields[i])) {
                continue;
            }
            emergencyNumbers.add(new EmergencyNumber(fields[i] /* number */,
                fields[0] /* iso */, fields[1] /* mnc */,
                EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_UNSPECIFIED,
                new ArrayList<String>(),
                EmergencyNumber.EMERGENCY_NUMBER_SOURCE_DATABASE,
                EmergencyNumber.EMERGENCY_CALL_ROUTING_UNKNOWN));
        }

        return emergencyNumbers;
    }
}
