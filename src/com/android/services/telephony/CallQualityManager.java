/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.services.telephony;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.telecom.BluetoothCallQualityReport;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.SlidingWindowEventCounter;
import com.android.phone.R;

/**
 * class to handle call quality events that are received by telecom and telephony
 */
public class CallQualityManager {
    private static final String TAG = CallQualityManager.class.getCanonicalName();

    /** notification ids */
    public static final int BLUETOOTH_CHOPPY_VOICE_NOTIFICATION_ID = 700;
    public static final String CALL_QUALITY_CHANNEL_ID = "CallQualityNotificationChannel";
    public static final long NOTIFICATION_BACKOFF_TIME_MILLIS = 5L * 60 * 1000;
    public static final int NUM_OCCURRENCES_THRESHOLD = 5;
    public static final long TIME_WINDOW_MILLIS = 5 * 1000;

    private final Context mContext;
    private final NotificationChannel mNotificationChannel;
    private final NotificationManager mNotificationManager;
    private final SlidingWindowEventCounter mSlidingWindowEventCounter;

    private long mNotificationLastTime;

    public CallQualityManager(Context context) {
        mContext = context;
        mNotificationChannel = new NotificationChannel(CALL_QUALITY_CHANNEL_ID,
                mContext.getString(R.string.call_quality_notification_name),
                NotificationManager.IMPORTANCE_HIGH);
        mNotificationManager = (NotificationManager)
                mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        mNotificationManager.createNotificationChannel(mNotificationChannel);
        //making sure at the start we qualify to show notifications
        mNotificationLastTime =
                SystemClock.elapsedRealtime() - NOTIFICATION_BACKOFF_TIME_MILLIS - 1;
        mSlidingWindowEventCounter =
                new SlidingWindowEventCounter(TIME_WINDOW_MILLIS, NUM_OCCURRENCES_THRESHOLD);
    }

    /**
     * method that is called whenever a
     * {@code BluetoothCallQualityReport.EVENT_SEND_BLUETOOTH_CALL_QUALITY_REPORT} is received
     * @param extras Bundle that includes serialized {@code BluetoothCallQualityReport} parcelable
     */
    @VisibleForTesting
    public void onBluetoothCallQualityReported(Bundle extras) {
        if (extras == null) {
            Log.d(TAG, "onBluetoothCallQualityReported: no extras provided");
        }

        BluetoothCallQualityReport callQualityReport = extras.getParcelable(
                BluetoothCallQualityReport.EXTRA_BLUETOOTH_CALL_QUALITY_REPORT);

        if (callQualityReport.isChoppyVoice()) {
            onChoppyVoice();
        }
        // TODO: once other signals are also sent, we will add more actions here
    }

    /**
     * method to post a notification to user suggesting ways to improve call quality in case of
     * bluetooth choppy voice
     */
    @VisibleForTesting
    public void onChoppyVoice() {
        String title = "Call Quality Improvement";
        Log.d(TAG, "Bluetooth choppy voice signal received.");
        if (mSlidingWindowEventCounter.addOccurrence(SystemClock.elapsedRealtime())) {
            timedNotify(title,
                    mContext.getText(R.string.call_quality_notification_bluetooth_details));
        }
    }

    // notify user only if you haven't in the last NOTIFICATION_BACKOFF_TIME_MILLIS milliseconds
    private void timedNotify(String title, CharSequence details) {
        if (!mContext.getResources().getBoolean(
                R.bool.enable_bluetooth_call_quality_notification)) {
            Log.d(TAG, "Bluetooth call quality notifications not enabled.");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - mNotificationLastTime > NOTIFICATION_BACKOFF_TIME_MILLIS) {
            int iconId = android.R.drawable.stat_notify_error;

            Notification notification = new Notification.Builder(mContext)
                    .setSmallIcon(iconId)
                    .setWhen(System.currentTimeMillis())
                    .setAutoCancel(true)
                    .setContentTitle(title)
                    .setContentText(details)
                    .setStyle(new Notification.BigTextStyle().bigText(details))
                    .setChannelId(CALL_QUALITY_CHANNEL_ID)
                    .setOnlyAlertOnce(true)
                    .build();

            mNotificationManager.notify(TAG, BLUETOOTH_CHOPPY_VOICE_NOTIFICATION_ID, notification);
            mNotificationLastTime = now;
            Log.d(TAG, "Call quality signal received, showing notification");
        } else {
            Log.d(TAG, "Call quality signal received, but not showing notification, "
                    + "as recently notified in the last "
                    + NOTIFICATION_BACKOFF_TIME_MILLIS / 1000 + " seconds");
        }
    }

    /**
     * close the notifications that have been emitted during the call
     */
    public void clearNotifications() {
        mNotificationManager.cancel(TAG, BLUETOOTH_CHOPPY_VOICE_NOTIFICATION_ID);
    }
}
