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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.FeatureFlagUtils;

import java.util.List;

/**
 * A helper to query activities of emergency assistance.
 */
public class EmergencyAssistanceHelper {

    /**
     * Get intent action of target emergency app.
     *
     * @param context The context of the application.
     * @return A string of intent action to launch target emergency app by feature flag, it will be
     * used for team food.
     */
    public static String getIntentAction(Context context) {
        if (FeatureFlagUtils.isEnabled(context, FeatureFlagUtils.SAFETY_HUB)) {
            String action = context.getResources().getString(R.string.config_emergency_app_intent);
            if (!action.isEmpty()) {
                return action;
            }
        }

        return TelephonyManager.ACTION_EMERGENCY_ASSISTANCE;
    }

    /**
     * Query activities of emergency assistance.
     *
     * @param context The context of the application.
     * @return A list of {@link ResolveInfo} which is queried from default assistance package,
     * or null if there is no installed system application of emergency assistance.
     */
    public static List<ResolveInfo> resolveAssistPackageAndQueryActivities(Context context) {
        List<ResolveInfo> infos = queryAssistActivities(context);

        if (infos == null || infos.isEmpty()) {
            PackageManager packageManager = context.getPackageManager();
            Intent queryIntent = new Intent(getIntentAction(context));
            infos = packageManager.queryIntentActivities(queryIntent, 0);

            PackageInfo bestMatch = null;
            for (int i = 0; i < infos.size(); i++) {
                if (infos.get(i).activityInfo == null) continue;
                String packageName = infos.get(i).activityInfo.packageName;
                PackageInfo packageInfo;
                try {
                    packageInfo = packageManager.getPackageInfo(packageName, 0);
                } catch (PackageManager.NameNotFoundException e) {
                    continue;
                }
                // Get earliest installed system app.
                if (isSystemApp(packageInfo) && (bestMatch == null
                        || bestMatch.firstInstallTime > packageInfo.firstInstallTime)) {
                    bestMatch = packageInfo;
                }
            }

            if (bestMatch != null) {
                Settings.Secure.putString(context.getContentResolver(),
                        Settings.Secure.EMERGENCY_ASSISTANCE_APPLICATION, bestMatch.packageName);
                return queryAssistActivities(context);
            } else {
                return null;
            }
        } else {
            return infos;
        }
    }

    /**
     * Compose {@link ComponentName} from {@link ResolveInfo}.
     */
    public static ComponentName getComponentName(ResolveInfo resolveInfo) {
        if (resolveInfo == null || resolveInfo.activityInfo == null) return null;
        return new ComponentName(resolveInfo.activityInfo.packageName,
                resolveInfo.activityInfo.name);
    }

    private static List<ResolveInfo> queryAssistActivities(Context context) {
        final String assistPackage = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.EMERGENCY_ASSISTANCE_APPLICATION);
        List<ResolveInfo> infos = null;

        if (!TextUtils.isEmpty(assistPackage)) {
            Intent queryIntent = new Intent(getIntentAction(context))
                    .setPackage(assistPackage);
            infos = context.getPackageManager().queryIntentActivities(queryIntent, 0);
        }
        return infos;
    }

    private static boolean isSystemApp(PackageInfo info) {
        return info.applicationInfo != null
                && (info.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
    }
}
