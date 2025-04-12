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

import android.os.AsyncTask;
import android.util.Log;
import android.view.View;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

public class FileDownloadTask extends AsyncTask<String, Integer, String> {

    private static final String TAG = "FileDownloadTask";
    private static final int BUFFER_SIZE = 1024; // 1KB buffer
    private volatile boolean isCancelled = false;
    private static final long SPEED_UPDATE_INTERVAL = 500;
    private long totalBytesRead = 0;
    private long lastBytesRead = 0;
    private long startTime = 0;
    private long lastUpdateTime = 0;
    private SatelliteSpeedTest activity;
    private double minSpeed = Double.MAX_VALUE;
    private double maxSpeed = 0.0;

    public void setActivity(SatelliteSpeedTest activity) {
        this.activity = activity;
    }

    @Override
    protected void onPreExecute() {
        super.onPreExecute();
        startTime = System.currentTimeMillis();
        lastUpdateTime = startTime;
        totalBytesRead = 0;
        lastBytesRead = 0;
        if (activity != null) {
            activity.progressBar.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onCancelled(String result) {
        super.onCancelled(result);
        long endTime = System.currentTimeMillis();
        long totalTime = endTime - startTime;
        double averageSpeed = (double) totalBytesRead / totalTime * 1000;
        activity.resetUI("download_cancelled.", formatSpeed(averageSpeed), formatTime(totalTime));
    }

    public void cancelDownload() {
        isCancelled = true;
        if (activity != null) {
            activity.setDefaultView();
        }
        Log.e(TAG, "Download Cancel.");
        if (getStatus() == AsyncTask.Status.RUNNING) {
            cancel(true);
        }
    }

    @Override
    protected String doInBackground(String... params) {
        String downloadUrl = params[0];
        HttpURLConnection connection = null;
        InputStream inputStream = null;

        try {
            URL url = new URL(downloadUrl);
            Log.d(TAG, url.toString());
            connection = (HttpURLConnection) activity.mNetwork.openConnection(url);
            connection.setRequestMethod("GET");
            connection.connect();

            int responseCode = connection.getResponseCode();
            Log.d(TAG, "Server Response Code: " + responseCode);

            if (responseCode == HttpURLConnection.HTTP_OK) {
                long fileLength = connection.getContentLength();
                inputStream = new BufferedInputStream(connection.getInputStream());
                byte[] dataBuffer = new byte[BUFFER_SIZE];
                int bytesRead;
                publishProgress(0);
                while ((bytesRead = inputStream.read(dataBuffer)) != -1 && !isCancelled()) {
                    totalBytesRead += bytesRead;
                    if (fileLength > 0) {
                        publishProgress(
                                (int)
                                        ((totalBytesRead * 100)
                                                / fileLength)); // Report download progress
                    }

                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastUpdateTime >= SPEED_UPDATE_INTERVAL) {
                        long bytesSinceLastUpdate = totalBytesRead - lastBytesRead;
                        long timeElapsed = currentTime - lastUpdateTime;
                        double speed =
                                (double) bytesSinceLastUpdate
                                        / timeElapsed
                                        * 1000; // bytes per second
                        publishProgress(
                                (int) ((totalBytesRead * 100) / fileLength),
                                (int) speed,
                                (int) (currentTime - startTime)); // Publish progress with speed
                        lastBytesRead = totalBytesRead;
                        lastUpdateTime = currentTime;

                        // Update min and max speed
                        if (speed < minSpeed) {
                            minSpeed = speed;
                        }
                        if (speed > maxSpeed) {
                            maxSpeed = speed;
                        }
                    }
                }
                if (isCancelled()) {
                    if (inputStream != null) {
                        try {
                            inputStream.close();
                            return "Download cancelled.";
                        } catch (IOException e) {
                            Log.e(TAG, "Error closing in cancellation: " + e.getMessage());
                            return "Download cancelled with error.";
                        }
                    }
                    return "Download cancelled.";
                } else {
                    return "Download successful. Downloaded "
                            + (totalBytesRead / (1024))
                            + " KB of data.";
                }
            } else {
                return "Download failed. Server responded with code: " + responseCode;
            }

        } catch (IOException e) {
            Log.e(TAG, "Error during download: " + e.getMessage());
            return "Error during download: " + e.getMessage();
        } finally {
            try {
                if (inputStream != null) inputStream.close();
                if (connection != null) connection.disconnect();
            } catch (IOException e) {
                Log.e(TAG, "Error closing streams or connection: " + e.getMessage());
            }
        }
    }

    @Override
    protected void onProgressUpdate(Integer... values) {
        super.onProgressUpdate(values);
        if (values.length > 2 && activity != null) {
            int progress = values[0];
            int speedBytesPerSecond = values[1];
            int totalTimeMillis = values[2];
            String speedFormatted = formatSpeed(speedBytesPerSecond);
            String timeFormatted = formatTime(totalTimeMillis);
            activity.updateUI(progress, speedFormatted, timeFormatted);
        } else if (values.length > 1 && activity != null) {
            int progress = values[0];
            int speedBytesPerSecond = values[1];
            String speedFormatted = formatSpeed(speedBytesPerSecond);
            activity.updateUI(progress, speedFormatted, ""); // Pass empty time
        } else if (values.length == 1 && activity != null) {
            int progress = values[0];
            activity.progressBar.setProgress(progress);
        }
    }

    @Override
    protected void onPostExecute(String result) {
        super.onPostExecute(result);
        long endTime = System.currentTimeMillis();
        long totalTime = endTime - startTime;
        double averageSpeed = (double) totalBytesRead / totalTime * 1000;
        if (activity != null) {
            activity.setDefaultView();
            activity.runOnUiThread(
                    () ->
                            activity.finishUI(
                                    result,
                                    (averageSpeed == 0.0) ? "--" : formatSpeed(averageSpeed),
                                    formatTime(totalTime)));
        }
    }

    private String formatSpeed(double bytesPerSecond) {
        if (bytesPerSecond < 1024) {
            return String.format(Locale.getDefault(), "%.2f B/s", bytesPerSecond);
        } else if (bytesPerSecond < 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.2f KB/s", bytesPerSecond / 1024);
        } else {
            return String.format(Locale.getDefault(), "%.2f MB/s", bytesPerSecond / (1024 * 1024));
        }
    }

    private String formatTime(long millis) {
        long seconds = (millis / 1000) % 60;
        long minutes = (millis / (1000 * 60)) % 60;
        long hours = (millis / (1000 * 60 * 60));
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds);
    }
}
