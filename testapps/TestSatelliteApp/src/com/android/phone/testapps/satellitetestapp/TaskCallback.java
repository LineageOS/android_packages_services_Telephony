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

package com.android.phone.testapps.satellitetestapp;

/**
 * Defines callbacks for background tasks (like {@link FileDownloadTask} or {@link FileUploadTask})
 * to communicate their progress and final state back to the main thread (typically an Activity).
 * Implementations of these methods should ensure UI updates are performed on the main/UI thread
 * (e.g., by using {@link android.app.Activity#runOnUiThread(Runnable)} or a {@link
 * android.os.Handler}).
 */
public interface TaskCallback {

    /**
     * Called on the main thread just before the background task's main work begins. Useful for
     * setting up initial UI state (e.g., showing progress bar, disabling buttons). Note: This is
     * called *after* the task object is created and submitted to the executor, but *before* the
     * task's core logic (network operations) starts.
     */
    void onPreExecute();

    /**
     * Called periodically from the background task thread to report progress.
     *
     * @param progress The current progress percentage (0-100), or -1 if the total size is unknown.
     * @param speed A formatted string representing the current transfer speed (e.g., "1.2 Mbps").
     * @param time A formatted string representing the total accumulated elapsed time (e.g.,
     *     "00:01:30"). This time includes time spent before any pauses.
     * @param currentBytes The total number of bytes transferred so far across all attempts for this
     *     task.
     */
    void onProgressUpdate(int progress, String speed, String time, long currentBytes);

    /**
     * Called from the background task thread when the task is about to sleep before retrying
     * due to a detected network error.
     *
     * @param attemptNumber The upcoming retry attempt number (starts from 1).
     * @param delayMillis   The delay in milliseconds before the next attempt will start.
     */
    void onRetryAttempt(int attemptNumber, long delayMillis);

    /**
     * Called from the background task thread when the task has been paused, typically due to
     * network loss identified by the task itself (e.g., via an IOException). The task's execution
     * has stopped at this point.
     *
     * @param message A message indicating the reason for pausing (e.g., "Download paused due to
     *     network error...").
     * @param currentBytes The total number of bytes transferred successfully before the pause
     *     occurred. This value should be saved by the Activity to allow resuming later.
     * @param timeElapsedThisRunMillis The time in milliseconds elapsed during the *most recent*
     *     execution segment before this pause occurred. This value should be added to any
     *     previously accumulated time by the Activity.
     */
    void onPaused(String message, long currentBytes, long timeElapsedThisRunMillis);

    /**
     * Called from the background task thread when the task has been explicitly cancelled (e.g., by
     * user interaction via {@link FileDownloadTask#cancelDownload()} or {@link
     * FileUploadTask#cancelUpload()}) and has stopped its execution cleanly.
     *
     * @param message A message indicating cancellation (e.g., "Download cancelled.").
     * @param averageSpeed A formatted string representing the average speed during the *last* run
     *     segment before cancellation.
     * @param time A formatted string representing the elapsed time during the *last* run segment
     *     before cancellation.
     */
    void onCancelled(String message, String averageSpeed, String time);

    /**
     * Called from the background task thread when the task has completed its execution, either
     * successfully or with a failure that is not considered a pause or cancellation.
     *
     * @param result A string describing the outcome (e.g., "Download successful.", "Error: File not
     *     found.").
     * @param averageSpeed A formatted string representing the average speed calculated over the
     *     *last* execution segment. For successful tasks spanning multiple segments (resume), this
     *     is *not* the overall average speed.
     * @param totalAccumulatedTime A formatted string representing the total accumulated elapsed
     *     time across all execution segments (including time before pauses).
     * @param finalBytes The total number of bytes transferred across all attempts for this task
     *     upon completion. For successful transfers, this should equal the file size.
     */
    void onPostExecute(
            String result, String averageSpeed, String totalAccumulatedTime, long finalBytes);
}
