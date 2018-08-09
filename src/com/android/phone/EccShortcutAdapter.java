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

package com.android.phone;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.phone.ecc.CountryEccInfo;
import com.android.phone.ecc.EccInfo;

import com.google.common.collect.LinkedListMultimap;

import java.util.ArrayList;
import java.util.List;

/**
 * An abstract adapter between ECC data and the view contains ECC shortcuts.
 * This adapter will convert given {@link CountryEccInfo} to number string, description string and
 * icon resource id for each {@link EccInfo}.
 * The subclass should implements {@link #inflateView} to provide the view for an ECC data, when the
 * view container calls {@link #getView}.
 */
public abstract class EccShortcutAdapter extends BaseAdapter {
    // GSM default emergency number, used when country's fallback ECC(112 or 911) not available.
    private static final String FALLBACK_EMERGENCY_NUMBER = "112";

    private List<EccDisplayMaterial> mEccDisplayMaterialList;

    private CharSequence mPoliceDescription;
    private CharSequence mAmbulanceDescription;
    private CharSequence mFireDescription;

    private static class EccDisplayMaterial {
        public CharSequence number = null;
        public int iconRes = 0;
        public CharSequence description = null;
    }

    public EccShortcutAdapter(@NonNull Context context) {
        mPoliceDescription = context.getText(R.string.police_type_description);
        mAmbulanceDescription = context.getText(R.string.ambulance_type_description);
        mFireDescription = context.getText(R.string.fire_type_description);

        mEccDisplayMaterialList = new ArrayList<>();
    }

    @Override
    public int getCount() {
        return mEccDisplayMaterialList.size();
    }

    @Override
    public EccDisplayMaterial getItem(int position) {
        return mEccDisplayMaterialList.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        EccDisplayMaterial material = getItem(position);
        return inflateView(convertView, parent, material.number, material.description,
                material.iconRes);
    }

    /**
     * Get a View that display the given ECC data: number, description and iconRes.
     *
     * @param convertView The old view to reuse, if possible. Note: You should check that this view
     *                   is non-null and of an appropriate type before using. If it is not possible
     *                   to convert this view to display the correct data, this method can create a
     *                   new view. Heterogeneous lists can specify their number of view types, so
     *                   that this View is always of the right type (see {@link
     *                   BaseAdapter#getViewTypeCount()} and {@link
     *                   BaseAdapter#getItemViewType(int)}).
     * @param parent The parent that this view will eventually be attached to.
     * @param number The number of the ECC shortcut to display in the view.
     * @param description The description of the ECC shortcut to display in the view.
     * @param iconRes The icon resource ID represent for the ECC shortcut.
     * @return A View corresponding to the data at the specified position.
     */
    public abstract View inflateView(View convertView, ViewGroup parent, CharSequence number,
            CharSequence description, int iconRes);

    /**
     * Update country ECC info. This method converts given country ECC info to ECC data that could
     * be display by the short container View.
     *
     * @param context The context used to access resources.
     * @param countryEccInfo Updated country ECC info.
     */
    public void updateCountryEccInfo(@NonNull Context context, CountryEccInfo countryEccInfo) {
        List<EccDisplayMaterial> displayMaterials = new ArrayList<>();

        final EccInfo.Type[] orderedMustHaveTypes =
                { EccInfo.Type.POLICE, EccInfo.Type.AMBULANCE, EccInfo.Type.FIRE };

        String fallback = null;
        EccInfo[] eccInfoList = null;
        if (countryEccInfo != null) {
            fallback = countryEccInfo.getFallbackEcc();
            eccInfoList = countryEccInfo.getEccInfoList();
        }
        if (TextUtils.isEmpty(fallback)) {
            fallback = FALLBACK_EMERGENCY_NUMBER;
        }

        // Finding matched ECC for each must have types.
        // Using LinkedListMultimap to prevent duplicated keys.
        // LinkedListMultimap also preserve the insertion order of keys (ECC number) and values
        // (matched types of the ECC number), which follows the order in orderedMustHaveTypes.
        LinkedListMultimap<String, EccInfo.Type> eccList = LinkedListMultimap.create();
        for (EccInfo.Type type : orderedMustHaveTypes) {
            String number = null;
            if (eccInfoList != null) {
                number = pickEccNumberForType(type, eccInfoList);
            }
            if (number == null) {
                number = fallback;
            }
            // append type for exist number, otherwise insert a new entry.
            eccList.put(number, type);
        }

        // prepare display material for picked ECC
        for (String number : eccList.keySet()) {
            EccDisplayMaterial material = prepareDisplayMaterialForEccInfo(context,
                    new EccInfo(number, eccList.asMap().get(number)));
            if (material != null) {
                displayMaterials.add(material);
            }
        }

        mEccDisplayMaterialList = displayMaterials;
        notifyDataSetChanged();
    }

    private @Nullable String pickEccNumberForType(@NonNull EccInfo.Type targetType,
            @NonNull EccInfo[] eccInfoList) {
        EccInfo pickedEccInfo = null;
        for (EccInfo eccInfo : eccInfoList) {
            if (eccInfo.containsType(targetType)) {
                // An ECC is more suitable for a type if the ECC has fewer other types.
                if (pickedEccInfo == null
                        || eccInfo.getTypesCount() < pickedEccInfo.getTypesCount()) {
                    pickedEccInfo = eccInfo;
                }
            }
        }
        if (pickedEccInfo != null) {
            return pickedEccInfo.getNumber();
        }
        return null;
    }

    private @Nullable EccDisplayMaterial prepareDisplayMaterialForEccInfo(@NonNull Context context,
            @NonNull EccInfo eccInfo) {
        EccDisplayMaterial material = new EccDisplayMaterial();
        material.number = eccInfo.getNumber();
        EccInfo.Type[] types = eccInfo.getTypes();
        for (EccInfo.Type type : types) {
            CharSequence description;
            switch (type) {
                case POLICE:
                    description = mPoliceDescription;
                    material.iconRes = R.drawable.ic_local_police_gm2_24px;
                    break;
                case AMBULANCE:
                    description = mAmbulanceDescription;
                    material.iconRes = R.drawable.ic_local_hospital_gm2_24px;
                    break;
                case FIRE:
                    description = mFireDescription;
                    material.iconRes = R.drawable.ic_local_fire_department_gm2_24px;
                    break;
                default:
                    // ignore unknown types
                    continue;
            }
            if (TextUtils.isEmpty(material.description)) {
                material.description = description;
            } else {
                // concatenate multiple types
                material.iconRes = R.drawable.ic_local_hospital_gm2_24px;
                material.description = context.getString(R.string.description_concat_format,
                        material.description, description);
            }
        }
        if (TextUtils.isEmpty(material.description) || material.iconRes == 0) {
            return null;
        }
        return material;
    }

}
