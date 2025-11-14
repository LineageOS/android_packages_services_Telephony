package com.android.phone.testapps.satelliteentitlementtestserverapp;

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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class SimpleAndroidHttpServer {
    private static final String CONTENT_TYPE = "application/json";
    public static final int SERVER_PORT = 5555;
    private ServerSocket mServerSocket;
    private ExecutorService mConnectionExecutor; // To handle client connections
    private volatile boolean mIsRunning = false; // To control the server loop
    private final SatelliteTestServerActivity mSatelliteTestServerActivity;

    /**
     * Constructor
     *
     * @param satelliteTestServerActivity SatelliteTestServerActivity instance.
     */
    public SimpleAndroidHttpServer(SatelliteTestServerActivity satelliteTestServerActivity) {
        mSatelliteTestServerActivity = satelliteTestServerActivity;
    }

    /**
     * Starts the server in a background thread.
     *
     * @throws IOException If the server socket cannot be created.
     */
    public void start() throws IOException {
        if (mIsRunning) {
            logd("Server is already running.");
            return;
        }

        // Create a thread pool to handle client connections concurrently
        mConnectionExecutor = Executors.newCachedThreadPool();

        // ServerSocket must be created before starting the thread
        // This might throw IOException if port is busy
        mServerSocket = new ServerSocket(SERVER_PORT);

        mIsRunning = true;

        // Start the main server loop in its own thread to avoid blocking
        new Thread(this::listeningForClientRequests, "HttpServerAcceptThread").start();

        logd("Server started Successfully");
    }

    public boolean isServerRunning() {
        return mIsRunning;
    }

    /**
     * Stops the server.
     */
    public void stop() {
        try {
            mIsRunning = false; // Signal the loop to stop
            if (mServerSocket != null && !mServerSocket.isClosed()) {
                mServerSocket.close(); // Closing the socket interrupts the accept() call
                logd("Server socket closed.");
            }
            if (mConnectionExecutor != null) {
                mConnectionExecutor.shutdown(); // Disable new tasks from being submitted
                logd("Connection executor shutdown.");
            }
        } catch (IOException e) {
            loge("Error closing server socket " + e);
        } finally {
            mServerSocket = null;
            mConnectionExecutor = null;
        }
        logd("Server stopped.");
    }

    /**
     * The main loop that accepts client connections.
     */
    private void listeningForClientRequests() {
        while (mIsRunning) {
            try {
                // Waits here for a client to connect
                Socket clientSocket = mServerSocket.accept();
                // Handle the client connection in a separate thread from the pool
                Future<?> future = mConnectionExecutor.submit(
                        new ClientRequestHandler(clientSocket));
                String serverStatus =
                        "Client connected: " + clientSocket.getInetAddress() + ":" + SERVER_PORT;
                logd(serverStatus);
                mSatelliteTestServerActivity.updateServerStatusTextView(serverStatus);
            } catch (IOException e) {
                if (!mIsRunning) {
                    // This exception is expected when stop() closes the socket
                    logd("Server accept loop stopped.");
                } else {
                    // Unexpected error accepting connection
                    loge("Error accepting client connection" + e);
                }
            } catch (Exception e) { // Catch other potential runtime exceptions
                loge("Unexpected error in server loop" + e);
            }
        }
        logd("Exiting server accept loop.");
    }

    /**
     * Handles a single client connection.
     */
    private class ClientRequestHandler implements Runnable {
        private final Socket clientSocket;

        public ClientRequestHandler(Socket socket) {
            this.clientSocket = socket;
        }

        @Override
        public void run() {
            try ( // Try-with-resources to ensure streams/socket are closed
                  BufferedReader httpRequestReader = new BufferedReader(
                          new InputStreamReader(clientSocket.getInputStream()));
                  OutputStream responseOutputStream = clientSocket.getOutputStream();
                  // Using PrintWriter for easy text writing, auto-flushing enabled
                  PrintWriter httpResponseWriter = new PrintWriter(responseOutputStream, true)) {
                // --- Very Basic Request Parsing ---
                String requestLine = httpRequestReader.readLine(); // e.g., "GET / HTTP/1.1"
                logd("Request Line: " + requestLine);

                int responseCode = mSatelliteTestServerActivity.getResponseCode();
                String responseBody = "Response";
                String statusText = "Not Found";
                // --- Basic Routing & Response ---
                String httpResponse;
                if (requestLine != null && ((requestLine.startsWith("GET /")
                        || requestLine.startsWith("POST /")) || requestLine.startsWith(
                        "GET /index "))) {
                    if (responseCode == 200) {
                        responseBody = mSatelliteTestServerActivity.getTS43ResponseForSatellite();
                        statusText = "OK";
                    }
                }
                logd("responseBody : " + responseBody);
                httpResponse = buildHttpResponse(responseCode, statusText, responseBody);
                // Send the response
                httpResponseWriter.print(httpResponse);
                httpResponseWriter.flush(); // Response is sent to the client

                logd("Response sent to client is " + httpResponse);
                mSatelliteTestServerActivity.updateClientRequestTextView(null);
                mSatelliteTestServerActivity.updateUiWithCurrentSatelliteInfo(true);
            } catch (IOException e) {
                loge("Error handling client connection" + e);
                mSatelliteTestServerActivity.updateClientRequestTextView(e.getMessage());
            } catch (Exception e) { // Catch unexpected errors
                loge("Unexpected error in ClientRequestHandler" + e);
                mSatelliteTestServerActivity.updateClientRequestTextView(e.getMessage());
            } finally {
                try {
                    if (clientSocket != null && !clientSocket.isClosed()) {
                        clientSocket.close();
                        logd("Client socket closed.");
                    }
                } catch (IOException e) {
                    loge("Error closing client socket" + e);
                }
            }
        }

        /**
         * Helper to build a basic HTTP response string.
         */
        private String buildHttpResponse(int statusCode, String statusText, String body) {
            return "HTTP/1.1 " + statusCode + " " + statusText + "\r\n" + "Content-Type: "
                    + SimpleAndroidHttpServer.CONTENT_TYPE + "\r\n" + "Content-Length: "
                    + body.getBytes().length + "\r\n" + "Connection: close\r\n" +
                    // Tell client we will close connection
                    "\r\n" + // Blank line separating headers and body
                    body;
        }
    }

    public void logd(String message) {
        Utils.logd(message);
    }

    public void loge(String message) {
        Utils.loge(message);
    }
}