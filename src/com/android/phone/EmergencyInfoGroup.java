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

import android.annotation.Nullable;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.os.UserManager;
import android.telephony.TelephonyManager;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;

import com.android.internal.util.UserIcons;

import java.util.List;

/**
 * EmergencyInfoGroup display user icon and user name. And it is an entry point to
 * Emergency Information.
 */
public class EmergencyInfoGroup extends LinearLayout {

    private ImageView mEmergencyInfoImage;
    private TextView mEmergencyInfoName;
    private View mEmergencyInfoTitle;
    private View mEmergencyInfoButton;

    public EmergencyInfoGroup(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mEmergencyInfoTitle = findViewById(R.id.emergency_info_title);
        mEmergencyInfoButton = findViewById(R.id.emergency_info_button);
        mEmergencyInfoImage = (ImageView) findViewById(R.id.emergency_info_image);
        mEmergencyInfoName = (TextView) findViewById(R.id.emergency_info_name);
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == View.VISIBLE) {
            setupButtonInfo();
        }
    }

    private void setupButtonInfo() {
        List<ResolveInfo> infos;

        if (TelephonyManager.EMERGENCY_ASSISTANCE_ENABLED) {
            infos = EmergencyAssistanceHelper.resolveAssistPackageAndQueryActivities(getContext());
        } else {
            infos = null;
        }

        boolean visible = false;

        if (infos != null && infos.size() > 0) {
            final String packageName = infos.get(0).activityInfo.packageName;
            final Intent intent = new Intent(TelephonyManager.ACTION_EMERGENCY_ASSISTANCE)
                    .setPackage(packageName);
            mEmergencyInfoButton.setTag(R.id.tag_intent, intent);
            mEmergencyInfoImage.setImageDrawable(getCircularUserIcon());

            /* TODO: Get user name.
                if user name exist:
                    1. mEmergencyInfoTitle show title.
                    2. mEmergencyInfoName show user name.
                if user name does not exist:
                    1. mEmergencyInfoTitle hide.
                    2. mEmergencyInfoName show app label. */
            mEmergencyInfoTitle.setVisibility(View.INVISIBLE);
            mEmergencyInfoName.setText(getContext().getResources().getString(
                    R.string.emergency_information_title));

            visible = true;
        }

        setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private Drawable getCircularUserIcon() {
        final UserManager userManager = (UserManager) getContext().getSystemService(
                Context.USER_SERVICE);
        Bitmap bitmapUserIcon = userManager.getUserIcon(UserHandle.getCallingUserId());

        if (bitmapUserIcon == null) {
            // get default user icon.
            final Drawable defaultUserIcon = UserIcons.getDefaultUserIcon(
                    getContext().getResources(), UserHandle.getCallingUserId(), false);
            bitmapUserIcon = UserIcons.convertToBitmap(defaultUserIcon);
        }

        RoundedBitmapDrawable drawableUserIcon = RoundedBitmapDrawableFactory.create(
                getContext().getResources(), bitmapUserIcon);
        drawableUserIcon.setCircular(true);

        return drawableUserIcon;
    }
}
