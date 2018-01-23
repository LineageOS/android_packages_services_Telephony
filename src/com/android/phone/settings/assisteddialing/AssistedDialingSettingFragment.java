/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.phone.settings.assisteddialing;

import android.icu.util.ULocale;
import android.icu.util.ULocale.Builder;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.SwitchPreference;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.phone.assisteddialing.AssistedDialingMediator;
import com.android.phone.assisteddialing.ConcreteCreator;
import com.android.phone.assisteddialing.CountryCodeProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The setting for Assisted Dialing
 */
public class AssistedDialingSettingFragment extends PreferenceFragment {

    private CountryCodeProvider mCountryCodeProvider;
    private AssistedDialingMediator mAssistedDialingMediator;
    private static final String LOG_TAG = "AssistedDialingSettingFragment";

    final class DisplayNameAndCountryCodeTuple {

        private final CharSequence mCountryDisplayname;
        private final CharSequence mCountryCode;

        DisplayNameAndCountryCodeTuple(
                CharSequence mCountryDisplayname,
                CharSequence mCountryCode) {
            if (mCountryDisplayname == null) {
                throw new NullPointerException("Null mCountryDisplayname");
            }
            this.mCountryDisplayname = mCountryDisplayname;
            if (mCountryCode == null) {
                throw new NullPointerException("Null mCountryCode");
            }
            this.mCountryCode = mCountryCode;
        }

        CharSequence countryDisplayname() {
            return mCountryDisplayname;
        }

        CharSequence countryCode() {
            return mCountryCode;
        }

        public String toString() {
            return "DisplayNameAndCountryCodeTuple{"
                    + "mCountryDisplayname=" + mCountryDisplayname + ", "
                    + "mCountryCode=" + mCountryCode
                    + "}";
        }

        @Override
        public boolean equals(Object o) {
            if (o == this) {
                return true;
            }
            if (o instanceof AssistedDialingSettingFragment.DisplayNameAndCountryCodeTuple) {
                AssistedDialingSettingFragment.DisplayNameAndCountryCodeTuple that = (
                        AssistedDialingSettingFragment.DisplayNameAndCountryCodeTuple) o;
                return (this.mCountryDisplayname.equals(that.countryDisplayname()))
                        && (this.mCountryCode.equals(that.countryCode()));
            }
            return false;
        }

        @Override
        public int hashCode() {
            int h = 1;
            h *= 1000003;
            h ^= this.mCountryDisplayname.hashCode();
            h *= 1000003;
            h ^= this.mCountryCode.hashCode();
            return h;
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mAssistedDialingMediator =
                ConcreteCreator.createNewAssistedDialingMediator(
                        getContext().getSystemService(TelephonyManager.class), getContext());

        mCountryCodeProvider =
                ConcreteCreator.getCountryCodeProvider();

        // Load the preferences from an XML resource
        addPreferencesFromResource(R.xml.assisted_dialing_setting);
        SwitchPreference switchPref =
                (SwitchPreference)
                        findPreference(getContext().getString(
                                R.string.assisted_dialing_setting_toggle_key));

        ListPreference countryChooserPref =
                (ListPreference)
                        findPreference(getContext().getString(
                                R.string.assisted_dialing_setting_cc_key));

        updateCountryChoices(countryChooserPref);
        updateCountryChooserSummary(countryChooserPref);

        countryChooserPref.setOnPreferenceChangeListener(this::updateListSummary);
    }

    private void updateCountryChooserSummary(ListPreference countryChooserPref) {
        String defaultSummaryText = countryChooserPref.getEntries()[0].toString();

        if (countryChooserPref.getEntry().equals(defaultSummaryText)) {
            Optional<String> userHomeCountryCode = mAssistedDialingMediator.userHomeCountryCode();
            if (userHomeCountryCode.isPresent()) {
                CharSequence[] entries = countryChooserPref.getEntries();
                try {
                    CharSequence regionalDisplayName =
                            entries[countryChooserPref.findIndexOfValue(userHomeCountryCode.get())];
                    countryChooserPref.setSummary(
                            getContext()
                                    .getString(
                                            R.string.assisted_dialing_setting_cc_default_summary,
                                            regionalDisplayName));
                } catch (ArrayIndexOutOfBoundsException e) {
                    // This might happen if there is a mismatch between the automatically
                    // detected home country, and the countries currently eligible to select in
                    // the settings.
                    Log.i(
                            LOG_TAG,
                            "Failed to find human readable mapping for country, using default.");
                }
            }
        } else {
            countryChooserPref.setSummary(countryChooserPref.getEntry());
        }
    }

    /**
     * Filters the default entries in the country chooser by only showing those countries in which
     * the feature in enabled.
     */
    private void updateCountryChoices(ListPreference countryChooserPref) {

        List<DisplayNameAndCountryCodeTuple> defaultCountryChoices =
                buildDefaultCountryChooserKeysAndValues(countryChooserPref);

        // Always include the default preference.
        List<CharSequence> newKeys = new ArrayList<>();
        List<CharSequence> newValues = new ArrayList<>();
        newKeys.add(countryChooserPref.getEntries()[0]);
        newValues.add(countryChooserPref.getEntryValues()[0]);

        for (DisplayNameAndCountryCodeTuple tuple : defaultCountryChoices) {
            if (mCountryCodeProvider.isSupportedCountryCode(tuple.countryCode().toString())) {
                newKeys.add(tuple.countryDisplayname());
                newValues.add(tuple.countryCode());
            }
        }

        countryChooserPref.setEntries(newKeys.toArray(new CharSequence[newKeys.size()]));
        countryChooserPref.setEntryValues(newValues.toArray(new CharSequence[newValues.size()]));
    }

    private List<DisplayNameAndCountryCodeTuple> buildDefaultCountryChooserKeysAndValues(
            ListPreference countryChooserPref) {
        CharSequence[] keys = countryChooserPref.getEntries();
        CharSequence[] values = countryChooserPref.getEntryValues();

        if (keys.length != values.length) {
            throw new IllegalStateException(
                    "Unexpected mismatch in country chooser key/value size");
        }

        List<DisplayNameAndCountryCodeTuple> displayNamesandCountryCodes = new ArrayList<>();
        // getCountry() is actually getRegion() and conforms to the iso standards of input for the
        // builder.
        ULocale userLocale =
                new ULocale.Builder()
                        .setRegion(getResources().getConfiguration().getLocales().get(0)
                                .getCountry())
                        .setLanguage(getResources().getConfiguration().getLocales().get(0)
                                .getLanguage())
                        .build();
        for (int i = 0; i < keys.length; i++) {
            ULocale settingRowDisplayCountry = new Builder().setRegion(values[i].toString())
                    .build();
            String localizedDisplayCountry = settingRowDisplayCountry.getDisplayCountry(userLocale);
            String settingDisplayName = localizedDisplayCountry + " " + keys[i];
            displayNamesandCountryCodes.add(
                    new DisplayNameAndCountryCodeTuple(settingDisplayName, values[i]));
        }

        return displayNamesandCountryCodes;
    }

    boolean updateListSummary(Preference pref, Object newValue) {
        ListPreference listPref = (ListPreference) pref;
        CharSequence[] entries = listPref.getEntries();
        listPref.setSummary(entries[listPref.findIndexOfValue(newValue.toString())]);
        return true;
    }

}
