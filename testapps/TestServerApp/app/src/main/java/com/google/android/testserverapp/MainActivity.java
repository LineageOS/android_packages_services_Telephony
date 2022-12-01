/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.google.android.testserverapp;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
  private static final String TAG = "TestServerApp";
  private static final int SERVER_PORT = 5555;

  private HttpServer mHttpServer = null;
  private boolean mIsServerUp = false;
  private int mEntitlementStatus = 1;
  private int mProvisionStatus = 1;
  private int mResponseCount = 0;

  private Button mServerButton;
  private TextView mServerStatusTextView, mClientRequestTextView;
  private AdapterView mEntitlementStatusSpinner, mProvisionStatusSpinner;

  private HttpHandler mHttpHandler = new HttpHandler() {
    @Override
    public void handle(HttpExchange httpExchange) throws IOException {
      String method = httpExchange.getRequestMethod();
      switch (method) {
        case "GET":
        case "POST":
          updateClientRequestTextView("Client Request: received a request from client");
          Log.d(TAG, "Client Request: received a request from client, requestHeaders = "
              + httpHeadersToString(httpExchange.getRequestHeaders()));

          sendResponseToClient(httpExchange, getTS43Response(), 200);
          break;
        default:
          Log.d(TAG, "Request method = " + method);
      }
    }
  };

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    mServerStatusTextView = findViewById(R.id.serverStatusTextView);
    mClientRequestTextView = findViewById(R.id.clientRequestTextView);
    mServerButton = findViewById(R.id.serverButton);
    mServerButton.setOnClickListener(new OnClickListener() {
      @Override
      public void onClick(View view) {
        if (mIsServerUp) {
          stopServer();
          mIsServerUp = false;
        } else {
          startServer(SERVER_PORT);
          mIsServerUp = true;
        }
      }
    });

    mEntitlementStatusSpinner = findViewById(R.id.entitlementStatusSpinner);
    ArrayAdapter<CharSequence> entitlementArrayAdapter = ArrayAdapter.createFromResource(this,
        R.array.entitlement_status, android.R.layout.simple_spinner_item);
    entitlementArrayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_item);
    mEntitlementStatusSpinner.setAdapter(entitlementArrayAdapter);
    mEntitlementStatusSpinner.setOnItemSelectedListener(new OnItemSelectedListener() {
      @Override
      public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        updateEntitlementStatus(parent.getItemAtPosition(position).toString());
      }

      @Override
      public void onNothingSelected(AdapterView<?> parent) {}
    });

    mProvisionStatusSpinner = findViewById(R.id.provisionStatusSpinner);
    ArrayAdapter<CharSequence> provisionArrayAdapter = ArrayAdapter.createFromResource(this,
        R.array.provision_status, android.R.layout.simple_spinner_item);
    entitlementArrayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_item);
    mProvisionStatusSpinner.setAdapter(provisionArrayAdapter);
    mProvisionStatusSpinner.setOnItemSelectedListener(new OnItemSelectedListener() {
      @Override
      public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        updateProvisionStatus(parent.getItemAtPosition(position).toString());
      }

      @Override
      public void onNothingSelected(AdapterView<?> parent) {}
    });
  }

  private void startServer(int port) {
    try {
      mHttpServer = HttpServer.create(new InetSocketAddress(port), 0);
      mHttpServer.setExecutor(Executors.newCachedThreadPool());

      mHttpServer.createContext("/", mHttpHandler);
      mHttpServer.createContext("/index", mHttpHandler);

      mHttpServer.start();

      mServerStatusTextView.setText(R.string.server_running);
      mServerButton.setText(R.string.stop_server);
    } catch (IOException e) {
      Log.d(TAG, "Exception in startServer, e = " + e);
    }
  }

  private void stopServer() {
    if (mHttpServer != null) {
      mHttpServer.stop(0);

      mServerStatusTextView.setText(R.string.server_down);
      mServerButton.setText(R.string.start_server);
    }
  }

  private void sendResponseToClient(HttpExchange httpExchange, String message, int responseCode) {
    try {
      httpExchange.sendResponseHeaders(responseCode, message.length());
      OutputStream os = httpExchange.getResponseBody();
      os.write(message.getBytes());
      os.close();

      Log.d(TAG, "Sent a response to client, message = " + message);
      updateClientRequestTextView("Client Request: Sent " + ++mResponseCount
          + " responses to the clients");
    } catch (IOException e) {
      Log.d(TAG, "Exception in sendResponseToClient, e = " + e);
      updateClientRequestTextView("Client Request: Exception in sendResponseToClient!!!");
    }
  }

  private String httpHeadersToString(Headers headers) {
    StringBuilder sb = new StringBuilder();
    for (Entry<String, List<String>> entry : headers.entrySet()) {
      sb.append("{" + entry.getKey() + ":");
      for (String str : entry.getValue()) {
        sb.append(str + ",");
      }
      sb.append("}");
    }
    return sb.toString();
  }

  private String getTS43Response() {
    return "{"
            + "  \"Vers\":{"
            + "    \"version\": \"1\","
            + "    \"validity\": \"1728000\""
            + "  },"
            + "  \"Token\":{"
            + "    \"token\": \"kZYfCEpSsMr88KZVmab5UsZVzl+nWSsX\""
            + "  },"
            + "  \"ap2012\":{"
            + "    \"EntitlementStatus\": " + mEntitlementStatus + ","
            + "    \"ServiceFlow_URL\": \"file:///android_asset/slice_purchase_test.html\","
            + "    \"ServiceFlow_UserData\": \"PostData=U6%2FbQ%2BEP&amp;amp;l=en_US\","
            + "    \"ProvStatus\": "+ mProvisionStatus + ","
            + "    \"ProvisionTimeLeft\": 0"
            + "  },"
            + "  \"eap-relay-packet\":\"EapAkaChallengeRequest\""
            + "}";
  }

  private void updateClientRequestTextView(String status) {
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        mClientRequestTextView.setText(status);
      }
    });
  }

  private void updateEntitlementStatus(String status) {
    switch (status) {
      case "Disabled":
        mEntitlementStatus = 0;
        break;
      case "Enabled":
        mEntitlementStatus = 1;
        break;
      case "Incompatible":
        mEntitlementStatus = 2;
        break;
      case "Provisioning":
        mEntitlementStatus = 3;
        break;
      case "Included":
        mEntitlementStatus = 4;
        break;
    }
    mClientRequestTextView.setText("Entitlement Status is set to  \"" + status + "\"");
  }

  private void updateProvisionStatus(String status) {
    switch (status) {
      case "Not Provisioned":
        mProvisionStatus = 0;
        break;
      case "Provisioned":
        mProvisionStatus = 1;
        break;
      case "Not Required":
        mProvisionStatus = 2;
        break;
      case "In Progress":
        mProvisionStatus = 3;
        break;
    }
    mClientRequestTextView.setText("Provision Status is set to \"" + status + "\"");
  }
}
