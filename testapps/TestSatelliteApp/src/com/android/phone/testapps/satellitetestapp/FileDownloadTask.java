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

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Locale;

public class FileDownloadTask implements Runnable {

    private static final String TAG = "FileDownloadTask";
    private static final int BUFFER_SIZE = 4096;
    private volatile boolean mIsCancelled = false;
    private volatile boolean mIsPaused = false;
    private static final long SPEED_UPDATE_INTERVAL = 500;

    private static final long RETRY_DELAY_MS = 1000;

    private final String mDownloadUrl;
    private final long mInitialBytesRead;
    private final long mTimeElapsedBeforePauseMillis;
    private final TaskCallback mCallback;
    private final NetworkProvider mNetworkProvider;

    private long mTotalBytesRead = 0;
    private long mLastBytesRead = 0;
    private long mStartTime = 0;
    private long mLastUpdateTime = 0;
    private double mMinSpeed = Double.MAX_VALUE;
    private double mMaxSpeed = 0.0;
    private long mTotalFileSize = -1;

    public interface NetworkProvider {
        android.net.Network getNetwork();
    }

    public FileDownloadTask(
            String downloadUrl,
            long bytesToResumeFrom,
            long timeElapsedBeforePause,
            TaskCallback callback,
            NetworkProvider networkProvider) {
        mDownloadUrl = downloadUrl;
        mInitialBytesRead = bytesToResumeFrom;
        mTimeElapsedBeforePauseMillis = timeElapsedBeforePause;
        mCallback = callback;
        mNetworkProvider = networkProvider;
        mTotalBytesRead = bytesToResumeFrom;
    }

    public void cancelDownload() {
        Log.w(TAG, "cancelDownload() called - Setting flags.");
        mIsCancelled = true;
        mIsPaused = false;
    }

    public void pauseDownload() {
        Log.w(TAG, "pauseDownload() called - Setting flags.");
        mIsPaused = true;
        mIsCancelled = true;
    }

    @Override
    public void run() {
        if (mIsCancelled && !mIsPaused) {
            Log.i(TAG, "Task cancelled before execution started.");
            mCallback.onCancelled("Download cancelled.", "--", "00:00:00");
            return;
        }
        if (mIsPaused) {
            mCallback.onPaused("Paused before run", mTotalBytesRead, 0);
        }
        mCallback.onPreExecute();
        mStartTime = System.currentTimeMillis();
        if (mInitialBytesRead == 0) {
            mMinSpeed = Double.MAX_VALUE;
            mMaxSpeed = 0.0;
            mLastBytesRead = 0;
        } else {
            mLastBytesRead = mInitialBytesRead;
            Log.i(TAG, "Resuming download from byte: " + mInitialBytesRead);
        }
        mLastUpdateTime = mStartTime;
        mTotalBytesRead = mInitialBytesRead;

        String resultMessage = performDownloadWithRetries();
        long endTime = System.currentTimeMillis();
        long timeElapsedThisRunMillis = endTime - mStartTime;
        long totalAccumulatedTimeMillis = mTimeElapsedBeforePauseMillis + timeElapsedThisRunMillis;

        long bytesTransferredThisRun = mTotalBytesRead - mInitialBytesRead;
        double averageSpeed =
                (timeElapsedThisRunMillis > 0)
                        ? (double) bytesTransferredThisRun / timeElapsedThisRunMillis * 1000
                        : 0;
        String formattedAvgSpeed =
                (averageSpeed <= 0 && mTotalBytesRead == 0) ? "--" : formatSpeed(averageSpeed);
        String formattedTotalTime = formatTime(totalAccumulatedTimeMillis);

        Log.d(
                TAG,
                "Task run ended. Result: '"
                        + resultMessage
                        + "', mIsPaused="
                        + mIsPaused
                        + ", mIsCancelled="
                        + mIsCancelled);

        if (resultMessage.startsWith("Download paused")) {
            if (!mIsPaused) {
                mIsPaused = true;
            }
            mCallback.onPaused(resultMessage, mTotalBytesRead, timeElapsedThisRunMillis);
        } else if (resultMessage.startsWith("Download cancelled") || (mIsCancelled && !mIsPaused)) {
            mCallback.onCancelled(
                    resultMessage.startsWith("Download cancelled")
                            ? resultMessage
                            : "Download cancelled.",
                    formattedAvgSpeed,
                    formatTime(timeElapsedThisRunMillis));
        } else {
            mCallback.onPostExecute(
                    resultMessage, formattedAvgSpeed, formattedTotalTime, mTotalBytesRead);
        }
    }

    /**
     * Attempts the download, retrying automatically and indefinitely with a fixed delay on specific
     * network errors until success, cancellation, pause request, or a fatal error occurs.
     *
     * @return A status message indicating success, pause, cancellation, or a fatal error.
     */
    private String performDownloadWithRetries() {
        int retryCount = 0;
        long currentResumeOffset = mInitialBytesRead;
        String attemptResult = "Starting download";

        // Loop indefinitely
        while (true) {
            if (mIsCancelled) {
                /* ... handle cancellation ... */
                Log.i(TAG, "Retry loop interrupted by external cancel/pause request.");
                return mIsPaused ? "Download paused." : "Download cancelled.";
            }

            HttpURLConnection connection = null;
            InputStream inputStream = null;
            android.net.Network network = mNetworkProvider.getNetwork();

            if (network == null) {
                Log.w(TAG, "Network unavailable before attempt " + (retryCount + 1));
                attemptResult = "Network unavailable";
                // Fall through to retry logic
            } else {
                // --- Single Download Attempt (try-catch-finally block) ---
                try {
                    URL url = new URL(mDownloadUrl);
                    Log.d(
                            TAG,
                            "Attempt "
                                    + (retryCount + 1)
                                    + ": Connecting | Resume Byte: "
                                    + currentResumeOffset);

                    connection = (HttpURLConnection) network.openConnection(url);
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(15000);
                    connection.setReadTimeout(15000);
                    connection.setUseCaches(false);
                    connection.setRequestProperty("Accept-Encoding", "identity");

                    if (currentResumeOffset > 0) {
                        connection.setRequestProperty(
                                "Range", "bytes=" + currentResumeOffset + "-");
                    }
                    connection.connect();
                    int responseCode = connection.getResponseCode();
                    Log.d(TAG, "Attempt " + (retryCount + 1) + ": Response Code: " + responseCode);

                    if (responseCode == HttpURLConnection.HTTP_OK
                            || responseCode == HttpURLConnection.HTTP_PARTIAL) {
                        handleSuccessfulConnection(connection);
                        inputStream = new BufferedInputStream(connection.getInputStream());
                        byte[] dataBuffer = new byte[BUFFER_SIZE];
                        int bytesRead;
                        if (retryCount == 0 && mTotalBytesRead == mInitialBytesRead) {
                            publishProgressUpdate();
                        }

                        while ((bytesRead = inputStream.read(dataBuffer)) != -1) {
                            if (mIsCancelled) {
                                attemptResult =
                                        mIsPaused ? "Download paused." : "Download cancelled.";
                                Log.d(TAG, "Cancellation/Pause detected inside read loop.");
                                try {
                                    if (inputStream != null) inputStream.close();
                                } catch (IOException e) {
                                    Log.e(TAG, "IOException occurs");
                                }
                                return attemptResult;
                            }
                            mTotalBytesRead += bytesRead;
                            publishProgressUpdate();
                        }
                        Log.i(TAG, "Attempt " + (retryCount + 1) + ": Download successful.");
                        return "Download successful. Total downloaded: "
                                + formatBytes(mTotalBytesRead); // SUCCESS! Exit loop.

                    } else { // Other non-successful HTTP codes
                        Log.w(
                                TAG,
                                "Attempt "
                                        + (retryCount + 1)
                                        + ": Failed with HTTP code: "
                                        + responseCode);
                        attemptResult = "HTTP Error " + responseCode;
                        // Fall through to retry logic
                    }

                } catch (IOException e) {
                    Log.w(
                            TAG,
                            "Attempt "
                                    + (retryCount + 1)
                                    + ": IOException: "
                                    + e.getClass().getSimpleName()
                                    + " - "
                                    + e.getMessage());
                    if (mIsCancelled) {
                        return mIsPaused ? "Download paused." : "Download cancelled.";
                    }
                    if (shouldPauseForIOException(e)) { // Check if retryable network error
                        attemptResult = "Network Error: " + e.getMessage();
                        // Fall through to retry logic
                    } else { // Non-retryable IO error
                        Log.e(TAG, "Non-retryable IOException occurred.", e);
                        return "Error during download: "
                                + e.getMessage(); // FATAL error, exit loop.
                    }
                } catch (Exception e) { // Catch unexpected errors
                    Log.e(TAG, "Attempt " + (retryCount + 1) + ": Unexpected error", e);
                    if (mIsCancelled) {
                        return mIsPaused ? "Download paused." : "Download cancelled.";
                    }
                    return "Unexpected error: " + e.getMessage(); // FATAL error, exit loop.
                } finally {
                    try {
                        if (inputStream != null) inputStream.close();
                    } catch (IOException e) {
                        Log.e(TAG, "IOException occurs");
                    }
                    if (connection != null) {
                        connection.disconnect();
                    }
                    Log.d(TAG, "Attempt " + (retryCount + 1) + " finished cleanup.");
                }
            } // End of single download attempt block

            // --- Retry Logic ---
            retryCount++;
            Log.i(
                    TAG,
                    "Attempt "
                            + (retryCount)
                            + " failed ("
                            + attemptResult
                            + "). Retrying in "
                            + RETRY_DELAY_MS
                            + " ms...");

            try {
                Thread.sleep(RETRY_DELAY_MS);
                mCallback.onRetryAttempt(retryCount, RETRY_DELAY_MS);
                currentResumeOffset = mTotalBytesRead;
                if (mIsCancelled) {
                    Log.i(
                            TAG,
                            "Retry delay interrupted or completed, finding external cancel/pause"
                                    + " request.");
                    return mIsPaused ? "Download paused." : "Download cancelled.";
                }
                Log.d(TAG, "Starting retry attempt " + retryCount);

            } catch (InterruptedException ie) {
                Log.w(TAG, "Retry delay sleep interrupted.");
                Thread.currentThread().interrupt(); // Re-interrupt thread
                if (mIsCancelled) {
                    return mIsPaused ? "Download paused." : "Download cancelled.";
                }
                return "Download retry interrupted."; // Exit if interrupted
            }
        }
    }

    private boolean shouldPauseForIOException(IOException e) {
        if (e == null) return false;
        String errorMessage = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        return (e instanceof SocketTimeoutException
                || e instanceof UnknownHostException
                || (e instanceof SocketException
                        && (errorMessage.contains("software caused connection abort")
                                || errorMessage.contains("connection reset")
                                || errorMessage.contains("network is down")
                                || errorMessage.contains("network is unreachable")
                                || errorMessage.contains("enetunreach")
                                || errorMessage.contains("enetdown")
                                || errorMessage.contains("broken pipe")))
                || errorMessage.contains("network"));
    }

    private void handleSuccessfulConnection(HttpURLConnection connection) {
        long contentLengthHeader = connection.getContentLengthLong();
        int responseCode = -1;
        try {
            responseCode = connection.getResponseCode();
        } catch (IOException e) {
            Log.e(TAG, "IOException occurs");
        }
        mTotalFileSize = -1;

        if (responseCode == HttpURLConnection.HTTP_OK) {
            mTotalFileSize = contentLengthHeader;
            Log.d(TAG, "Received HTTP_OK. Full file size: " + mTotalFileSize);
            String rangeRequest = connection.getRequestProperty("Range");
            if (rangeRequest != null && !rangeRequest.equals("bytes=0-")) {
                Log.w(
                        TAG,
                        "Server returned 200 OK despite Range request '"
                                + rangeRequest
                                + "'. File might need full download.");
            }
        } else if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
            String contentRange = connection.getHeaderField("Content-Range");
            if (contentRange != null) {
                int slashPos = contentRange.lastIndexOf('/');
                if (slashPos != -1 && slashPos < contentRange.length() - 1) {
                    try {
                        mTotalFileSize = Long.parseLong(contentRange.substring(slashPos + 1));
                        Log.d(
                                TAG,
                                "Received HTTP_PARTIAL. Content-Range: "
                                        + contentRange
                                        + ", Total size: "
                                        + mTotalFileSize);
                    } catch (NumberFormatException e) {
                        Log.e(
                                TAG,
                                "Could not parse total size from Content-Range: " + contentRange);
                    }
                }
            } else if (contentLengthHeader >= 0) {
                long offset = 0;
                String rangeReq = connection.getRequestProperty("Range");
                if (rangeReq != null && rangeReq.startsWith("bytes=")) {
                    try {
                        offset = Long.parseLong(rangeReq.substring(6, rangeReq.indexOf('-')));
                    } catch (Exception ignored) {
                    }
                }
                mTotalFileSize = offset + contentLengthHeader;
                Log.d(
                        TAG,
                        "Received HTTP_PARTIAL. No Content-Range. Content-Length: "
                                + contentLengthHeader
                                + ". Estimated total size: "
                                + mTotalFileSize);
            } else {
                Log.d(TAG, "Received HTTP_PARTIAL. Size unknown.");
            }
        }
    }

    private void publishProgressUpdate() {
        long currentTime = System.currentTimeMillis();
        long timeElapsedSinceRunStart = currentTime - mStartTime;

        if (timeElapsedSinceRunStart >= 0
                && (currentTime - mLastUpdateTime >= SPEED_UPDATE_INTERVAL
                        || mTotalBytesRead == mInitialBytesRead)) {
            long bytesSinceLastUpdate = mTotalBytesRead - mLastBytesRead;
            long timeElapsedSinceLast = currentTime - mLastUpdateTime;
            double speed =
                    (timeElapsedSinceLast > 0)
                            ? (double) bytesSinceLastUpdate / timeElapsedSinceLast * 1000
                            : 0;
            int progress =
                    (mTotalFileSize > 0) ? (int) ((mTotalBytesRead * 100) / mTotalFileSize) : -1;

            if (speed < mMinSpeed && speed > 0 && timeElapsedSinceRunStart > 0) mMinSpeed = speed;
            if (speed > mMaxSpeed) mMaxSpeed = speed;

            if (!mIsCancelled || mIsPaused) {
                long currentTotalAccumulatedTime =
                        mTimeElapsedBeforePauseMillis + timeElapsedSinceRunStart;
                mCallback.onProgressUpdate(
                        progress,
                        formatSpeed(speed),
                        formatTime(currentTotalAccumulatedTime),
                        mTotalBytesRead);
            }
            mLastBytesRead = mTotalBytesRead;
            mLastUpdateTime = currentTime;
        }
    }

    private String formatSpeed(double bytesPerSecond) {
        if (bytesPerSecond < 0) return "--";
        if (bytesPerSecond < 1.0 && bytesPerSecond > 0) return "< 1 bps";
        if (bytesPerSecond == 0) return "0 bps";
        double bitsPerSecond = bytesPerSecond * 8.0;
        if (bitsPerSecond < 1000.0) {
            return String.format(Locale.getDefault(), "%.0f bps", bitsPerSecond);
        }
        if (bitsPerSecond < 1_000_000.0) {
            return String.format(Locale.getDefault(), "%.1f kbps", bitsPerSecond / 1000.0);
        }
        if (bitsPerSecond < 1_000_000_000.0) {
            return String.format(Locale.getDefault(), "%.2f Mbps", bitsPerSecond / 1_000_000.0);
        }
        return String.format(Locale.getDefault(), "%.2f Gbps", bitsPerSecond / 1_000_000_000.0);
    }

    private String formatTime(long millis) {
        if (millis < 0) millis = 0;
        long seconds = (millis / 1000) % 60;
        long minutes = (millis / (1000 * 60)) % 60;
        long hours = (millis / (1000 * 60 * 60));
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds);
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log((double) bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format(Locale.getDefault(), "%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }
}
