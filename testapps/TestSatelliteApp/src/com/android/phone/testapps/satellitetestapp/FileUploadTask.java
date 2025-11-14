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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Locale;

public class FileUploadTask implements Runnable {

    private static final String TAG = "FileUploadTask";
    private static final int BUFFER_SIZE = 4096;
    private volatile boolean mIsCancelled = false;
    private volatile boolean mIsPaused = false;
    private static final long SPEED_UPDATE_INTERVAL = 500;

    private static final long RETRY_DELAY_MS = 1000;

    private final String mFilePath;
    private final String mUploadUrl;
    private final long mInitialBytesSent;
    private final long mTimeElapsedBeforePauseMillis;
    private final TaskCallback mCallback;
    private final NetworkProvider mNetworkProvider;

    private long mTotalBytesSentThisRun = 0;
    private long mTotalBytesOverall = 0;
    private long mLastBytesOverall = 0;
    private long mStartTime = 0;
    private long mLastUpdateTime = 0;
    private double mMinSpeed = Double.MAX_VALUE;
    private double mMaxSpeed = 0.0;
    private long mFileSize = 0;

    public interface NetworkProvider {
        android.net.Network getNetwork();
    }

    public FileUploadTask(
            String filePath,
            String uploadUrl,
            long bytesToResumeFrom,
            long timeElapsedBeforePause,
            TaskCallback callback,
            NetworkProvider networkProvider) {
        mFilePath = filePath;
        mUploadUrl = uploadUrl;
        mInitialBytesSent = bytesToResumeFrom;
        mTimeElapsedBeforePauseMillis = timeElapsedBeforePause;
        mCallback = callback;
        mNetworkProvider = networkProvider;
        mTotalBytesOverall = bytesToResumeFrom;
        mLastBytesOverall = bytesToResumeFrom;
    }

    public void cancelUpload() {
        Log.w(TAG, "cancelUpload() called - Setting flags.");
        mIsCancelled = true;
        mIsPaused = false;
    }

    public void pauseUpload() {
        Log.w(TAG, "pauseUpload() called - Setting flags.");
        mIsPaused = true;
        mIsCancelled = true;
    }

    @Override
    public void run() {
        if (mIsCancelled && !mIsPaused) {
            Log.i(TAG, "Upload Task cancelled before execution started.");
            mCallback.onCancelled("Upload cancelled.", "--", "00:00:00");
            return;
        }
        if (mIsPaused) {
            mCallback.onPaused("Paused before run", mInitialBytesSent, 0);
        }
        mCallback.onPreExecute();
        mStartTime = System.currentTimeMillis();
        if (mInitialBytesSent == 0) {
            mMinSpeed = Double.MAX_VALUE;
            mMaxSpeed = 0.0;
            mLastBytesOverall = 0;
        } else {
            mLastBytesOverall = mInitialBytesSent;
            Log.i(TAG, "Resuming upload from byte: " + mInitialBytesSent);
        }
        mLastUpdateTime = mStartTime;
        mTotalBytesOverall = mInitialBytesSent;

        String resultMessage = performUploadWithRetries();

        long endTime = System.currentTimeMillis();
        long timeElapsedThisRunMillis = endTime - mStartTime;
        long totalAccumulatedTimeMillis = mTimeElapsedBeforePauseMillis + timeElapsedThisRunMillis;

        mTotalBytesSentThisRun = mTotalBytesOverall - mInitialBytesSent;
        double averageSpeed =
                (timeElapsedThisRunMillis > 0)
                        ? (double) mTotalBytesSentThisRun / timeElapsedThisRunMillis * 1000
                        : 0;
        String formattedAvgSpeed =
                (averageSpeed <= 0 && mTotalBytesOverall == 0) ? "--" : formatSpeed(averageSpeed);
        String formattedTotalTime = formatTime(totalAccumulatedTimeMillis);

        Log.d(
                TAG,
                "Upload Task run ended. Result: '"
                        + resultMessage
                        + "', mIsPaused="
                        + mIsPaused
                        + ", mIsCancelled="
                        + mIsCancelled);

        if (resultMessage.startsWith("Upload paused")) {
            if (!mIsPaused) {
                mIsPaused = true;
            }
            mCallback.onPaused(resultMessage, mTotalBytesOverall, timeElapsedThisRunMillis);
        } else if (resultMessage.startsWith("Upload cancelled") || (mIsCancelled && !mIsPaused)) {
            mCallback.onCancelled(
                    resultMessage.startsWith("Upload cancelled")
                            ? resultMessage
                            : "Upload cancelled.",
                    formattedAvgSpeed,
                    formatTime(timeElapsedThisRunMillis));
        } else {
            mCallback.onPostExecute(
                    resultMessage, formattedAvgSpeed, formattedTotalTime, mTotalBytesOverall);
        }
    }

    private String performUploadWithRetries() {
        int retryCount = 0;
        long currentResumeOffset = mInitialBytesSent;
        String attemptResult = "Starting upload";

        File fileToUpload = new File(mFilePath);
        if (!fileToUpload.exists()) {
            return "Error: File not found at " + mFilePath;
        }
        mFileSize = fileToUpload.length();

        if (currentResumeOffset >= mFileSize && mFileSize > 0) {
            Log.i(
                    TAG,
                    "Initial bytes ("
                            + currentResumeOffset
                            + ") already >= file size ("
                            + mFileSize
                            + "). Assuming complete.");
            return "Upload already complete.";
        }

        while (true) {
            if (mIsCancelled) {
                Log.i(TAG, "Retry loop interrupted by external cancel/pause request.");
                return mIsPaused ? "Upload paused." : "Upload cancelled.";
            }

            HttpURLConnection connection = null;
            OutputStream outputStream = null;
            InputStream inputStream = null;
            android.net.Network network = mNetworkProvider.getNetwork();

            if (network == null) {
                Log.w(TAG, "Network unavailable before attempt " + (retryCount + 1));
                attemptResult = "Network unavailable";
            } else {
                try {
                    long bytesSentThisAttempt = 0;
                    if (currentResumeOffset >= mFileSize && mFileSize > 0) {
                        Log.i(
                                TAG,
                                "Current offset ("
                                        + currentResumeOffset
                                        + ") reached file size ("
                                        + mFileSize
                                        + ") before attempt. Assuming complete.");
                        return "Upload successful. Total Sent: " + formatBytes(mTotalBytesOverall);
                    }

                    URL url = new URL(mUploadUrl);
                    Log.d(
                            TAG,
                            "Attempt "
                                    + (retryCount + 1)
                                    + ": Uploading to "
                                    + url
                                    + " | Resume Byte: "
                                    + currentResumeOffset);
                    if (currentResumeOffset > 0) {
                        Log.w(
                                TAG,
                                "!!! WARNING: Resuming upload without server confirmation is"
                                    + " unreliable !!!");
                    }

                    connection = (HttpURLConnection) network.openConnection(url);
                    connection.setRequestMethod("PUT");
                    connection.setDoOutput(true);
                    connection.setUseCaches(false);
                    connection.setConnectTimeout(15000);
                    connection.setReadTimeout(30000);

                    long contentLength;
                    if (currentResumeOffset > 0) {
                        contentLength = mFileSize - currentResumeOffset;
                        String rangeHeader =
                                "bytes "
                                        + currentResumeOffset
                                        + "-"
                                        + (mFileSize - 1)
                                        + "/"
                                        + mFileSize;
                        connection.setRequestProperty("Content-Range", rangeHeader);
                    } else {
                        contentLength = mFileSize;
                    }

                    if (contentLength >= 0) {
                        connection.setFixedLengthStreamingMode(contentLength);
                    } else {
                        connection.setChunkedStreamingMode(BUFFER_SIZE);
                    }
                    connection.setRequestProperty("Content-Type", "application/octet-stream");

                    inputStream = new FileInputStream(fileToUpload);
                    if (currentResumeOffset > 0) {
                        long skipped = inputStream.skip(currentResumeOffset);
                        if (skipped != currentResumeOffset) {
                            try {
                                inputStream.close();
                            } catch (IOException ignored) {
                                Log.e(TAG, "IOException occurs");
                            }
                            return "Error: Failed to seek in file for resume."; // Fatal error
                        }
                    }

                    outputStream = connection.getOutputStream();
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int bytesRead;

                    if (retryCount == 0 && mTotalBytesOverall == mInitialBytesSent) {
                        publishProgressUpdate();
                    }

                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        if (mIsCancelled) {
                            attemptResult = mIsPaused ? "Upload paused." : "Upload cancelled.";
                            Log.d(TAG, "Cancellation/Pause detected inside write loop.");
                            try {
                                if (outputStream != null) outputStream.close();
                            } catch (IOException e) {
                                Log.e(TAG, "IOException occurs");
                            }
                            try {
                                if (inputStream != null) inputStream.close();
                            } catch (IOException e) {
                                Log.e(TAG, "IOException occurs");
                            }
                            return attemptResult;
                        }

                        outputStream.write(buffer, 0, bytesRead);
                        bytesSentThisAttempt += bytesRead;
                        mTotalBytesOverall += bytesRead;
                        publishProgressUpdate();
                    }

                    outputStream.flush();
                    outputStream.close();

                    mTotalBytesSentThisRun += bytesSentThisAttempt;
                    int responseCode = -1;
                    String responseMessage = "No response";
                    try {
                        responseCode = connection.getResponseCode();
                        responseMessage = connection.getResponseMessage();
                        Log.i(
                                TAG,
                                "Attempt "
                                        + (retryCount + 1)
                                        + ": Server Response Code: "
                                        + responseCode
                                        + ", Message: "
                                        + responseMessage);

                        InputStream responseStream = connection.getInputStream();
                        byte[] responseBuffer = new byte[BUFFER_SIZE];
                        while (responseStream.read(responseBuffer) != -1) {
                            /* NOP */
                        }
                        responseStream.close();
                        attemptResult = "Server Response Received";

                    } catch (IOException e) {
                        InputStream errorStream = null;
                        try {
                            if (connection != null) errorStream = connection.getErrorStream();
                            if (errorStream != null) {
                                while (errorStream.read(new byte[1024]) != -1) {
                                    Log.d(TAG, "performUploadWithRetries: Reading errorStream");
                                }
                            }
                        } catch (IOException ignored) {
                            Log.e(TAG, "IOException occurs");
                        } finally {
                            try {
                                if (errorStream != null) errorStream.close();
                            } catch (IOException ignored) {
                                Log.e(TAG, "IOException occurs");
                            }
                        }

                        Log.w(
                                TAG,
                                "Attempt "
                                        + (retryCount + 1)
                                        + ": IOException reading response: "
                                        + e.getClass().getSimpleName()
                                        + " - "
                                        + e.getMessage());
                        if (mIsCancelled) {
                            return mIsPaused ? "Upload paused." : "Upload cancelled.";
                        }

                        if (mTotalBytesOverall == mFileSize
                                && errorMessageIndicatesDisconnect(e.getMessage())) {
                            Log.w(
                                    TAG,
                                    "IOException reading response,"
                                            + " but upload seems complete. Treating as success.",
                                    e);
                            return "Upload likely successful (response read error). Total Sent: "
                                    + formatBytes(mTotalBytesOverall); // SUCCESS! Exit loop.
                        } else {
                            if (shouldPauseForIOException(e)) {
                                attemptResult = "Network Error Reading Response: " + e.getMessage();
                            } else {
                                return "Upload failed (Error reading response: "
                                        + e.getMessage()
                                        + ")";
                            }
                        }
                    }

                    if (attemptResult.equals("Server Response Received")) {
                        if (responseCode >= 200 && responseCode < 300) {
                            if (mTotalBytesOverall >= mFileSize) {
                                return "Upload successful. Server responded: "
                                        + responseCode
                                        + " "
                                        + responseMessage;
                            } else {
                                Log.w(
                                        TAG,
                                        "Server success code "
                                                + responseCode
                                                + " but client only sent "
                                                + mTotalBytesOverall
                                                + "/"
                                                + mFileSize);
                                attemptResult = "Incomplete Upload Despite Success Code";
                            }
                        } else if (responseCode == 400) {
                            return "Upload failed: Bad Request (400)."
                                    + " Server likely doesn't support resume.";
                        } else if (responseCode == 416) {
                            return "Upload failed: Range Not Satisfiable (416). Invalid range used";
                        } else {
                            Log.w(
                                    TAG,
                                    "Attempt "
                                            + (retryCount + 1)
                                            + ": Failed with HTTP code: "
                                            + responseCode);
                            attemptResult = "HTTP Error " + responseCode;
                        }
                    }

                } catch (IOException e) {
                    Log.w(
                            TAG,
                            "Attempt "
                                    + (retryCount + 1)
                                    + ": IOException during connect/write: "
                                    + e.getClass().getSimpleName()
                                    + " - "
                                    + e.getMessage());
                    if (mIsCancelled) {
                        return mIsPaused ? "Upload paused." : "Upload cancelled.";
                    }
                    if (shouldPauseForIOException(e)) {
                        attemptResult = "Network Error Connect/Write: " + e.getMessage();
                    } else {
                        Log.e(TAG, "Non-retryable IOException occurred.", e);
                        return "Error during upload: " + e.getMessage(); // FATAL error
                    }
                } catch (Exception e) { // Catch unexpected errors
                    Log.e(TAG, "Attempt " + (retryCount + 1) + ": Unexpected error", e);
                    if (mIsCancelled) {
                        return mIsPaused ? "Upload paused." : "Upload cancelled.";
                    }
                    return "Unexpected error: " + e.getMessage();
                } finally {
                    try {
                        if (outputStream != null) outputStream.close();
                    } catch (IOException e) {
                        Log.e(TAG, "IOException occurs");
                    }
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
            }

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
                currentResumeOffset = mTotalBytesOverall;

                if (mIsCancelled) {
                    Log.i(
                            TAG,
                            "Retry delay interrupted or completed, finding external cancel/pause"
                                + " request.");
                    return mIsPaused ? "Upload paused." : "Upload cancelled.";
                }
                Log.d(TAG, "Starting retry attempt " + retryCount);

            } catch (InterruptedException ie) {
                Log.w(TAG, "Retry delay sleep interrupted.");
                Thread.currentThread().interrupt();
                if (mIsCancelled) {
                    return mIsPaused ? "Upload paused." : "Upload cancelled.";
                }
                return "Upload retry interrupted.";
            }
        }
    }

    private boolean shouldPauseForIOException(IOException e) {
        if (e == null) return false;
        String errorMessage =
                e.getMessage() != null ? e.getMessage().toLowerCase(Locale.getDefault()) : "";
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

    private boolean errorMessageIndicatesDisconnect(String errorMessage) {
        if (errorMessage == null) return false;
        String msgLower = errorMessage.toLowerCase(Locale.getDefault());
        return msgLower.contains("socket closed")
                || msgLower.contains("connection reset")
                || msgLower.contains("broken pipe");
    }

    private void publishProgressUpdate() {
        long currentTime = System.currentTimeMillis();
        long timeElapsedSinceRunStart = currentTime - mStartTime;

        if (timeElapsedSinceRunStart >= 0
                && currentTime - mLastUpdateTime >= SPEED_UPDATE_INTERVAL) {
            long bytesSinceLastUpdate = mTotalBytesOverall - mLastBytesOverall;
            long timeElapsedSinceLast = currentTime - mLastUpdateTime;
            double speed =
                    (timeElapsedSinceLast > 0)
                            ? (double) bytesSinceLastUpdate / timeElapsedSinceLast * 1000
                            : 0;

            int progress = (mFileSize > 0) ? (int) ((mTotalBytesOverall * 100) / mFileSize) : -1;

            if (speed < mMinSpeed && speed > 0 && timeElapsedSinceRunStart > 0) mMinSpeed = speed;
            if (speed > mMaxSpeed) mMaxSpeed = speed;

            if (!mIsCancelled || mIsPaused) {
                long currentTotalAccumulatedTime =
                        mTimeElapsedBeforePauseMillis + timeElapsedSinceRunStart;
                mCallback.onProgressUpdate(
                        progress,
                        formatSpeed(speed),
                        formatTime(currentTotalAccumulatedTime),
                        mTotalBytesOverall);
            }

            mLastBytesOverall = mTotalBytesOverall;
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
