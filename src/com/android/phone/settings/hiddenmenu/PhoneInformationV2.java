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

import android.app.FragmentTransaction;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;

import com.android.phone.R;

import java.util.ArrayList;
import java.util.List;

public class PhoneInformationV2 extends AppCompatActivity
        implements View.OnClickListener, PhoneInformationV2PhoneId {
    private static final String TAG = "PhoneInformationV2";
    private PhoneInfoSharedViewModel mViewModel;
    private static final String SHARED_PREFERENCES = "PhoneInfoV2Prefs";
    private static final String KEY_LAST_SELECTED_TAB = "last_selected_nav_item_id";
    private static final int DEFAULT_PHONE_ID = 0;
    private int phoneId;
    private SharedPreferences prefs;
    private LinearLayout itemOneContainer, itemTwoContainer, itemThreeContainer, itemFourContainer;
    private List<LinearLayout> navItemContainers = new ArrayList<>();
    private int currentlySelectedItem = -1;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.phone_information_v2);
        mViewModel = new ViewModelProvider(this).get(PhoneInfoSharedViewModel.class);
        phoneId = DEFAULT_PHONE_ID;
        itemOneContainer = findViewById(R.id.nav_item_one_container);
        itemTwoContainer = findViewById(R.id.nav_item_two_container);
        itemThreeContainer = findViewById(R.id.nav_item_three_container);
        itemFourContainer = findViewById(R.id.nav_item_four_container);

        navItemContainers.add(itemOneContainer);
        navItemContainers.add(itemTwoContainer);
        navItemContainers.add(itemThreeContainer);
        navItemContainers.add(itemFourContainer);

        itemOneContainer.setOnClickListener(this);
        itemTwoContainer.setOnClickListener(this);
        itemThreeContainer.setOnClickListener(this);
        itemFourContainer.setOnClickListener(this);

        prefs = getSharedPreferences(SHARED_PREFERENCES, Context.MODE_PRIVATE);
        int defaultTab = R.id.nav_item_one_container;
        int lastSelectedTab = prefs.getInt(KEY_LAST_SELECTED_TAB, defaultTab);
        selectNavItem(lastSelectedTab, false);
    }

    @Override
    public void onClick(View v) {
        selectNavItem(v.getId(), true);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mViewModel != null) {
            mViewModel.resetToDefaults();
        }
    }

    private void selectNavItem(int selectedItemId, boolean savePreference) {
        if (currentlySelectedItem == selectedItemId && currentlySelectedItem != -1) {
            return;
        }

        Fragment selectedFragment = null;

        if (selectedItemId == R.id.nav_item_one_container) {
            selectedFragment = new PhoneInformationV2FragmentDeviceDetails();
        } else if (selectedItemId == R.id.nav_item_two_container) {
            selectedFragment = new PhoneInformationV2FragmentDataNetwork();
        } else if (selectedItemId == R.id.nav_item_three_container) {
            selectedFragment = new PhoneInformationV2FragmentSatellite();
        } else if (selectedItemId == R.id.nav_item_four_container) {
            selectedFragment = new PhoneInformationV2FragmentIms();
        }

        if (selectedFragment != null) {
            loadFragment(selectedFragment);
            currentlySelectedItem = selectedItemId;
            updateSelectionVisuals(selectedItemId);

            if (savePreference) {
                prefs.edit().putInt(KEY_LAST_SELECTED_TAB, selectedItemId).apply();
            }
        } else {
            currentlySelectedItem = selectedItemId;
            updateSelectionVisuals(selectedItemId);
            if (savePreference) {
                prefs.edit().putInt(KEY_LAST_SELECTED_TAB, selectedItemId).apply();
            }
        }
    }

    private void loadFragment(Fragment fragment) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        fragmentManager
                .beginTransaction()
                .replace(R.id.content_container, fragment)
                .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                .commit();
    }

    private void updateSelectionVisuals(int selectedItemId) {
        int selectedColor = ContextCompat.getColor(this, android.R.color.holo_green_dark);
        int defaultColor = ContextCompat.getColor(this, android.R.color.darker_gray);

        for (LinearLayout container : navItemContainers) {
            ImageView icon = null;
            TextView label = null;

            if (container.getId() == R.id.nav_item_one_container) {
                icon = container.findViewById(R.id.nav_item_one_icon);
                label = container.findViewById(R.id.nav_item_one_label);
            } else if (container.getId() == R.id.nav_item_two_container) {
                icon = container.findViewById(R.id.nav_item_two_icon);
                label = container.findViewById(R.id.nav_item_two_label);
            } else if (container.getId() == R.id.nav_item_three_container) {
                icon = container.findViewById(R.id.nav_item_three_icon);
                label = container.findViewById(R.id.nav_item_three_label);
            } else { // Assuming it is four container
                label = container.findViewById(R.id.nav_item_four_label);
            }

            if (container.getId() == selectedItemId) {
                // Selected State
                if (icon != null) icon.setColorFilter(selectedColor, PorterDuff.Mode.SRC_IN);
                if (label != null) label.setTextColor(selectedColor);
            } else {
                // Default State
                if (icon != null) icon.setColorFilter(defaultColor, PorterDuff.Mode.SRC_IN);
                if (label != null) label.setTextColor(defaultColor);
            }
        }
    }

    private void notifyOtherFragments(int phoneId) {
        for (Fragment frag : getSupportFragmentManager().getFragments()) {
            if (frag instanceof FragmentValueUpdater && frag.isVisible()) {
                ((FragmentValueUpdater) frag).updateValueDisplay(phoneId);
            }
        }
    }

    public interface FragmentValueUpdater {
        void updateValueDisplay(int phoneId);
    }

    @Override
    public int getPhoneId() {
        return phoneId;
    }

    @Override
    public void setPhoneId(int phoneId) {
        this.phoneId = phoneId;
        notifyOtherFragments(phoneId);
    }
}
