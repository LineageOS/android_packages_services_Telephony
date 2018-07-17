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
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;
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
public class EmergencyInfoGroup extends FrameLayout {
    private ImageView mEmergencyInfoImage;
    private TextView mEmergencyInfoName;
    private TextView mEmergencyInfoHint;
    private View mEmergencyInfoButton;

    public EmergencyInfoGroup(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mEmergencyInfoButton = findViewById(R.id.emergency_info_button);
        mEmergencyInfoImage = (ImageView) findViewById(R.id.emergency_info_image);
        mEmergencyInfoName = (TextView) findViewById(R.id.emergency_info_name);
        mEmergencyInfoHint = (TextView) findViewById(R.id.emergency_info_hint);
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == View.VISIBLE) {
            setupButtonInfo();
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        updateLayoutHeight();
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

            visible = true;
        }

        setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /**
     * Get user icon.
     *
     * @return user icon, or anonymous avatar if user do not set photo.
     */
    private Drawable getCircularUserIcon() {
        final int userId = UserHandle.getCallingUserId();

        final UserManager userManager = (UserManager) getContext().getSystemService(
                Context.USER_SERVICE);

        // get user icon.
        Bitmap bitmapUserIcon = userManager.getUserIcon(userId);

        if (bitmapUserIcon == null) {
            // use anonymous avatar.
            return getContext().getDrawable(R.drawable.logo_avatar_anonymous_120);
        }

        // get default user icon.
        Drawable drawableDefaultUserIcon = UserIcons.getDefaultUserIcon(
                getContext().getResources(), userId, false);
        Bitmap bitmapDefaultUserIcon = UserIcons.convertToBitmap(drawableDefaultUserIcon);

        // User icon is default icon that means user do not set photo, replacing default icon
        // with anonymous avatar on emergency info button.
        if (bitmapUserIcon.sameAs(bitmapDefaultUserIcon)) {
            return getContext().getDrawable(R.drawable.logo_avatar_anonymous_120);
        }

        // set user icon circular.
        RoundedBitmapDrawable drawableUserIcon = RoundedBitmapDrawableFactory.create(
                getContext().getResources(), bitmapUserIcon);
        drawableUserIcon.setCircular(true);

        return drawableUserIcon;
    }

    private void updateLayoutHeight() {
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) getLayoutParams();
        // Update height if mEmergencyInfoHint text line more than 1.
        // EmergencyInfoGroup max line is 2, eclipse type "end" will be adopt if string too long.
        params.height =
                mEmergencyInfoHint.getLineCount() > 1 ? getResources().getDimensionPixelSize(
                        R.dimen.emergency_info_button_multiline_height)
                        : getResources().getDimensionPixelSize(
                                R.dimen.emergency_info_button_singleline_height);
        setLayoutParams(params);
    }

    void updateEmergencyInfo(String emergencyInfoName) {
        if (TextUtils.isEmpty(emergencyInfoName)) {
            emergencyInfoName = getContext().getString(R.string.emergency_information_owner_hint);
        }
        mEmergencyInfoName.setText(emergencyInfoName);
    }
}