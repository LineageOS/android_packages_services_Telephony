/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.os.Looper;
import android.os.IBinder;
import android.telephony.satellite.stub.SatelliteDatagram;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SatelliteTestApp main activity to navigate to other APIs related to satellite.
 */
public class SatelliteTestApp extends Activity {

    private static final String TAG = "SatelliteTestApp";
    public static TestSatelliteService sSatelliteService;
    private final Object mSendDatagramLock = new Object();
    Network mNetwork = null;
    Context mContext;
    ConnectivityManager mConnectivityManager;
    NetworkCallback mSatelliteConstrainNetworkCallback;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TestSatelliteServiceConnection mSatelliteServiceConn;
    private List<SatelliteDatagram> mSentSatelliteDatagrams = new ArrayList<>();
    private static final int REQUEST_CODE_SEND_SMS = 1;
    private final int NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED = 37;
    private boolean isNetworkRequested = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mContext = getApplicationContext();

        mConnectivityManager = getSystemService(ConnectivityManager.class);

        if (mSatelliteServiceConn == null) {
            mSatelliteServiceConn = new TestSatelliteServiceConnection();
            getBaseContext().bindService(new Intent(getBaseContext(),
                    TestSatelliteService.class), mSatelliteServiceConn, Context.BIND_AUTO_CREATE);
        }

        setContentView(R.layout.activity_SatelliteTestApp);
        findViewById(R.id.SatelliteControl).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(SatelliteTestApp.this, SatelliteControl.class);
                startActivity(intent);
            }
        });
        findViewById(R.id.Datagram).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(SatelliteTestApp.this, Datagram.class);
                startActivity(intent);
            }
        });
        findViewById(R.id.Provisioning).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(SatelliteTestApp.this, Provisioning.class);
                startActivity(intent);
            }
        });
        findViewById(R.id.MultipleSendReceive).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(SatelliteTestApp.this, MultipleSendReceive.class);
                startActivity(intent);
            }
        });
        findViewById(R.id.SendReceive).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(SatelliteTestApp.this, SendReceive.class);
                startActivity(intent);
            }
        });
        findViewById(R.id.NbIotSatellite).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(SatelliteTestApp.this, NbIotSatellite.class);
                startActivity(intent);
            }
        });
        findViewById(R.id.TestSatelliteWrapper).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(SatelliteTestApp.this, TestSatelliteWrapper.class);
                startActivity(intent);
            }
        });

      findViewById(R.id.TestSatelliteConstrainConnection).setOnClickListener(view -> {
        executor.execute(() -> {
          Log.e(TAG, "onClick");
          mSatelliteConstrainNetworkCallback = new NetworkCallback() {
            @Override
            public void onAvailable(final Network network) {
              makeSatelliteDataConstrainedPing(network);
            }
          };
          if(isNetworkRequested == false) {
            requestingNetwork();
          }
        });
      });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checkSelfPermission(Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.SEND_SMS}, REQUEST_CODE_SEND_SMS);
        }
    }

    @Override
    protected void onDestroy() {
      super.onDestroy();
      if(isNetworkRequested == true) {
        releasingNetwork();
      }
    }

    private void requestingNetwork() {
      Log.e(TAG, "Requesting Network");
      isNetworkRequested = true;
      NetworkRequest request = new NetworkRequest.Builder()
          .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
          .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED)
          .removeCapability(NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED)
          .addTransportType(NetworkCapabilities.TRANSPORT_SATELLITE)
          .build();

      // Requesting for Network
      mConnectivityManager.requestNetwork(request, mSatelliteConstrainNetworkCallback);
      Log.e(TAG, "onClick + " + request);
    }


    private void makeSatelliteDataConstrainedPing(final Network network) {
      Log.e(TAG, "onAvailable + " + network);
      mNetwork = network;

      try {
        PingTask pingTask = new PingTask();
        Log.d(TAG, "Connecting Satellite for ping");
        String pingResult = pingTask.ping(mNetwork);
        if(pingResult != null) {
          Toast.makeText(mContext, "Ping Passed!", Toast.LENGTH_SHORT).show();
        } else {
          Toast.makeText(mContext, "Ping Failed!", Toast.LENGTH_SHORT).show();
        }
      } catch (Exception e) {
        Log.d(TAG, "Exception at ping: " + e);
      } finally {
        // Releasing the callback in the background thread
        releasingNetwork();
      }
    }

    private void releasingNetwork() {
      Log.e(TAG, "Realsing Network");
      try {
        mConnectivityManager
            .unregisterNetworkCallback(mSatelliteConstrainNetworkCallback);
      } catch (Exception e) {
        Log.d("SatelliteDataConstrined", "Exception: " + e);
      }
      isNetworkRequested = false;
    }

    private final ILocalSatelliteListener mSatelliteListener =
            new ILocalSatelliteListener.Stub() {
                @Override
                public void onRemoteServiceConnected() {
                    Log.d(TAG, "onRemoteServiceConnected");
                }

                @Override
                public void onStartSendingSatellitePointingInfo() {
                    Log.d(TAG, "onStartSendingSatellitePointingInfo");
                }

                @Override
                public void onStopSendingSatellitePointingInfo() {
                    Log.d(TAG, "onStopSendingSatellitePointingInfo");
                }

                @Override
                public void onPollPendingSatelliteDatagrams() {
                    Log.d(TAG, "onPollPendingSatelliteDatagrams");
                }

                @Override
                public void onSendSatelliteDatagram(
                        SatelliteDatagram datagram, boolean isEmergency) {
                    Log.d(TAG, "onSendSatelliteDatagram");
                    synchronized (mSendDatagramLock) {
                        mSentSatelliteDatagrams.add(datagram);
                    }
                }

                @Override
                public void onSatelliteListeningEnabled(boolean enable) {
                    Log.d(TAG, "onSatelliteListeningEnabled");
                }

                @Override
                public void onEnableCellularModemWhileSatelliteModeIsOn(boolean enable) {
                    Log.d(TAG, "onEnableCellularModemWhileSatelliteModeIsOn");
                }

                @Override
                public void onSetSatellitePlmn() {
                    Log.d(TAG, "onSetSatellitePlmn");
                }
            };

    private class TestSatelliteServiceConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "onServiceConnected in SatelliteTestApp");
            sSatelliteService = ((TestSatelliteService.LocalBinder) service).getService();
            sSatelliteService.setLocalSatelliteListener(mSatelliteListener);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "onServiceDisconnected in SatelliteTestApp");
            sSatelliteService = null;
        }
    }

    public static TestSatelliteService getTestSatelliteService() {
        return sSatelliteService;
    }
}
