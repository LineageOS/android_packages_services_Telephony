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

import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.NetworkRequest;
import android.os.AsyncTask;
import android.util.Log;
import android.view.View;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

public class FileUploadTask extends AsyncTask<String, Integer, String> {

    private static final String TAG = "FileUploadTask";
    ConnectivityManager mConnectivityManager;
    NetworkRequest mRequest;
    NetworkCallback mSatelliteConstrainNetworkCallback;
    private static final int BUFFER_SIZE = 4096; // 4KB buffer
    private volatile boolean isCancelled = false;
    private static final long SPEED_UPDATE_INTERVAL = 200;
    private long totalBytesSent = 0;
    private long lastBytesSent = 0;
    private long startTime = 0;
    private long lastUpdateTime = 0;
    private double minSpeed = Double.MAX_VALUE;
    private double maxSpeed = 0.0;
    private SatelliteSpeedTest activity;

    public void setActivity(SatelliteSpeedTest activity) {
        this.activity = activity;
        mConnectivityManager = activity.getSystemService(ConnectivityManager.class);
    }

    @Override
    protected void onPreExecute() {
        super.onPreExecute();
        startTime = System.currentTimeMillis();
        lastUpdateTime = startTime;
        totalBytesSent = 0;
        lastBytesSent = 0;
        if (activity != null) {
            activity.progressBar.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onCancelled(String result) {
        super.onCancelled(result);
        if (activity != null) {
            activity.setDefaultView();
        }
        long endTime = System.currentTimeMillis();
        long totalTime = endTime - startTime;
        double averageSpeed = (double) totalBytesSent / totalTime * 1000;
        activity.resetUI("upload_cancelled.", formatSpeed(averageSpeed), formatTime(totalTime));
    }

    public void cancelUpload() {
        Log.e(TAG, "Upload Cancel.");
        isCancelled = true;
        if (getStatus() == AsyncTask.Status.RUNNING) {
            cancel(true);
        }
    }

    @Override
    protected String doInBackground(String... params) {
        String filePath = params[0]; // Path to the file to upload
        String uploadUrl = params[1]; // The server upload URL
        File fileToUpload = new File(filePath);
        if (!fileToUpload.exists()) {
            return "Error: File not found at " + filePath;
        }

        HttpURLConnection connection = null;
        OutputStream outputStream = null;
        InputStream inputStream = null;
        publishProgress(0); // Report progress set to 0

        try {
            URL url = new URL(uploadUrl);
            Log.d(TAG, url.toString());
            connection = (HttpURLConnection) activity.mNetwork.openConnection(url);
            connection.setRequestMethod("POST"); // Or "PUT" depending on the server
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setChunkedStreamingMode(BUFFER_SIZE); // Optional: for large files

            // Set Content-Type (important for the server to know what it's receiving)
            connection.setRequestProperty(
                    "Content-Type", "application/zip"); // Adjust if your file is not a zip

            outputStream = connection.getOutputStream();
            inputStream = new FileInputStream(fileToUpload);

            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            long fileSize = fileToUpload.length();

            while ((bytesRead = inputStream.read(buffer)) != -1 && !isCancelled()) {
                outputStream.write(buffer, 0, bytesRead);
                totalBytesSent += bytesRead;

                long currentTime = System.currentTimeMillis();
                if (currentTime - lastUpdateTime >= SPEED_UPDATE_INTERVAL) {
                    long bytesSinceLastUpdate = totalBytesSent - lastBytesSent;
                    long timeElapsed = currentTime - lastUpdateTime;
                    double speed = (double) bytesSinceLastUpdate / timeElapsed * 1000;
                    publishProgress(
                            (int) ((totalBytesSent * 100) / fileSize),
                            (int) speed,
                            (int) (currentTime - startTime));
                    lastBytesSent = totalBytesSent;
                    lastUpdateTime = currentTime;

                    if (speed < minSpeed) {
                        minSpeed = speed;
                    }
                    if (speed > maxSpeed) {
                        maxSpeed = speed;
                    }
                }
            }
            outputStream.flush();
            if (isCancelled()) {
                return "Upload cancelled.";
            }
            int responseCode = connection.getResponseCode();
            Log.d(TAG, "Server Response Code: " + responseCode);

            // Simulate -O /dev/null by just reading and discarding the response
            InputStream responseStream = connection.getInputStream();
            byte[] responseBuffer = new byte[BUFFER_SIZE];
            while (responseStream.read(responseBuffer) != -1) {
                // Do nothing with the response
            }
            responseStream.close();

            if (responseCode == HttpURLConnection.HTTP_OK
                    || responseCode == HttpURLConnection.HTTP_CREATED
                    || responseCode == HttpURLConnection.HTTP_ACCEPTED) {
                return "Upload successful. Server responded with code: " + responseCode;
            } else {
                return "Upload failed. Server responded with code: " + responseCode;
            }
        } catch (IOException e) {
            Log.e(TAG, "Error during upload: " + e.getMessage());
            return "Error during upload: " + e.getMessage();
        } finally {
            try {
                if (outputStream != null) outputStream.close();
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
            activity.updateUI(progress, speedFormatted, "");
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
        double averageSpeed = (double) totalBytesSent / totalTime * 1000;
        if (activity != null) {
            activity.setDefaultView();
            activity.runOnUiThread(
                    new Runnable() {
                        @Override
                        public void run() {
                            activity.finishUI(
                                    result,
                                    (averageSpeed == 0.0) ? "--" : formatSpeed(averageSpeed),
                                    formatTime(totalTime));
                        }
                    });
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
