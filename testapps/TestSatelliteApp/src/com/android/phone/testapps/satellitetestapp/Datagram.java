/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.satellite.SatelliteDatagram;
import android.telephony.satellite.SatelliteDatagramCallback;
import android.telephony.satellite.SatelliteManager;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.TextView;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Activity related to Datagram APIs.
 */
public class Datagram extends Activity {

    private static final String TAG = "Datagram";

    private SatelliteManager mSatelliteManager;
    private SatelliteDatagramCallbackTestApp mCallback;
    private android.telephony.satellite.stub.SatelliteDatagram mReceivedDatagram;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mSatelliteManager = getSystemService(SatelliteManager.class);
        mCallback = new SatelliteDatagramCallbackTestApp();

        mReceivedDatagram = new android.telephony.satellite.stub.SatelliteDatagram();

        setContentView(R.layout.activity_Datagram);
        findViewById(R.id.pollPendingSatelliteDatagrams)
                .setOnClickListener(this::pollPendingSatelliteDatagramsApp);
        findViewById(R.id.multiplePollPendingSatelliteDatagrams)
                .setOnClickListener(this::multiplePollPendingSatelliteDatagramsApp);
        findViewById(R.id.sendSatelliteDatagram)
                .setOnClickListener(this::sendSatelliteDatagramApp);
        findViewById(R.id.multipleSendSatelliteDatagram)
                .setOnClickListener(this::multipleSendSatelliteDatagramApp);
        findViewById(R.id.multipleSendReceiveSatelliteDatagram)
                .setOnClickListener(this::multipleSendReceiveSatelliteDatagramApp);
        findViewById(R.id.registerForSatelliteDatagram)
                .setOnClickListener(this::registerForSatelliteDatagramApp);
        findViewById(R.id.unregisterForSatelliteDatagram)
                .setOnClickListener(this::unregisterForSatelliteDatagramApp);
        findViewById(R.id.Back).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(Datagram.this, SatelliteTestApp.class));
            }
        });
    }

    protected static class SatelliteDatagramCallbackTestApp implements SatelliteDatagramCallback {
        @Override
        public void onSatelliteDatagramReceived(long datagramId, SatelliteDatagram datagram,
                int pendingCount, Consumer<Void> callback) {
            Log.d(TAG, "onSatelliteDatagramReceived in SatelliteTestApp: datagramId =" + datagramId
                    + ", datagram =" + datagram + ", pendingCount=" + pendingCount);
        }
    }

    private void pollPendingSatelliteDatagramsApp(View view) {
        mSatelliteManager.onDeviceAlignedWithSatellite(true);
        SatelliteTestApp.getTestSatelliteService().sendOnPendingDatagrams();
        SatelliteTestApp.getTestSatelliteService().sendOnSatelliteDatagramReceived(
                    mReceivedDatagram, 0);
        LinkedBlockingQueue<Integer> resultListener = new LinkedBlockingQueue<>(1);
        mSatelliteManager.requestSatelliteEnabled(true, true, Runnable::run, resultListener::offer);
        mSatelliteManager.pollPendingSatelliteDatagrams(Runnable::run, resultListener::offer);
        try {
            Integer value = resultListener.poll(1000, TimeUnit.MILLISECONDS);
            TextView textView = findViewById(R.id.text_id);
            if (value == 0) {
                textView.setText("pollPendingSatelliteDatagrams is Successful");
            } else {
                textView.setText("Status for pollPendingSatelliteDatagrams : "
                        + SatelliteErrorUtils.mapError(value));
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "exception caught =" + e);
        }
    }

    private void multiplePollPendingSatelliteDatagramsApp(View view) {
        mSatelliteManager.onDeviceAlignedWithSatellite(true);
        SatelliteTestApp.getTestSatelliteService().sendOnPendingDatagrams();
        SatelliteTestApp.getTestSatelliteService().sendOnSatelliteDatagramReceived(
                    mReceivedDatagram, 4);
        LinkedBlockingQueue<Integer> resultListener = new LinkedBlockingQueue<>(1);
        mSatelliteManager.requestSatelliteEnabled(true, true, Runnable::run, resultListener::offer);
        mSatelliteManager.pollPendingSatelliteDatagrams(Runnable::run, resultListener::offer);
        SatelliteTestApp.getTestSatelliteService().sendOnSatelliteDatagramReceived(
                    mReceivedDatagram, 3);
        mSatelliteManager.pollPendingSatelliteDatagrams(Runnable::run, resultListener::offer);
        SatelliteTestApp.getTestSatelliteService().sendOnSatelliteDatagramReceived(
                    mReceivedDatagram, 2);
        mSatelliteManager.pollPendingSatelliteDatagrams(Runnable::run, resultListener::offer);
        SatelliteTestApp.getTestSatelliteService().sendOnSatelliteDatagramReceived(
                    mReceivedDatagram, 1);
        mSatelliteManager.pollPendingSatelliteDatagrams(Runnable::run, resultListener::offer);
        SatelliteTestApp.getTestSatelliteService().sendOnSatelliteDatagramReceived(
                    mReceivedDatagram, 0);
        mSatelliteManager.pollPendingSatelliteDatagrams(Runnable::run, resultListener::offer);
        try {
            Integer value = resultListener.poll(1000, TimeUnit.MILLISECONDS);
            TextView textView = findViewById(R.id.text_id);
            if (value == 0) {
                textView.setText("multiplePollPendingSatelliteDatagrams is Successful");
            } else {
                textView.setText("Status for multiplePollPendingSatelliteDatagrams : "
                        + SatelliteErrorUtils.mapError(value));
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "exception caught =" + e);
        }
    }

    private void sendSatelliteDatagramApp(View view) {
        mSatelliteManager.onDeviceAlignedWithSatellite(true);
        LinkedBlockingQueue<Integer> resultListener = new LinkedBlockingQueue<>(1);
        String mText = "This is a test datagram message";
        SatelliteDatagram datagram = new SatelliteDatagram(mText.getBytes());
        mSatelliteManager.sendSatelliteDatagram(SatelliteManager.DATAGRAM_TYPE_SOS_MESSAGE,
                datagram, true, Runnable::run, resultListener::offer);
        try {
            Integer value = resultListener.poll(1000, TimeUnit.MILLISECONDS);
            TextView textView = findViewById(R.id.text_id);
            if (value == 0) {
                textView.setText("sendSatelliteDatagram is Successful");
            } else {
                textView.setText("Status for sendSatelliteDatagram : "
                        + SatelliteErrorUtils.mapError(value));
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "exception caught =" + e);
        }
    }

    private void multipleSendSatelliteDatagramApp(View view) {
        mSatelliteManager.onDeviceAlignedWithSatellite(true);
        LinkedBlockingQueue<Integer> resultListener = new LinkedBlockingQueue<>(1);
        String mText = "This is a test datagram message";
        SatelliteDatagram datagram = new SatelliteDatagram(mText.getBytes());
        mSatelliteManager.sendSatelliteDatagram(SatelliteManager.DATAGRAM_TYPE_SOS_MESSAGE,
                datagram, true, Runnable::run, resultListener::offer);
        mSatelliteManager.sendSatelliteDatagram(SatelliteManager.DATAGRAM_TYPE_SOS_MESSAGE,
                datagram, true, Runnable::run, resultListener::offer);
        mSatelliteManager.sendSatelliteDatagram(SatelliteManager.DATAGRAM_TYPE_SOS_MESSAGE,
                datagram, true, Runnable::run, resultListener::offer);
        mSatelliteManager.sendSatelliteDatagram(SatelliteManager.DATAGRAM_TYPE_SOS_MESSAGE,
                datagram, true, Runnable::run, resultListener::offer);
        mSatelliteManager.sendSatelliteDatagram(SatelliteManager.DATAGRAM_TYPE_SOS_MESSAGE,
                datagram, true, Runnable::run, resultListener::offer);
        try {
            Integer value = resultListener.poll(1000, TimeUnit.MILLISECONDS);
            TextView textView = findViewById(R.id.text_id);
            if (value == 0) {
                textView.setText("multipleSendSatelliteDatagram is Successful");
            } else {
                textView.setText("Status for multipleSendSatelliteDatagram : "
                        + SatelliteErrorUtils.mapError(value));
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "exception caught =" + e);
        }
    }

    private void multipleSendReceiveSatelliteDatagramApp(View view) {
        mSatelliteManager.onDeviceAlignedWithSatellite(true);
        LinkedBlockingQueue<Integer> resultListener = new LinkedBlockingQueue<>(1);
        mSatelliteManager.requestSatelliteEnabled(true, true, Runnable::run, resultListener::offer);
        String mText = "This is a test datagram message";
        SatelliteDatagram datagram = new SatelliteDatagram(mText.getBytes());
        mSatelliteManager.sendSatelliteDatagram(SatelliteManager.DATAGRAM_TYPE_SOS_MESSAGE,
                datagram, true, Runnable::run, resultListener::offer);
        SatelliteTestApp.getTestSatelliteService().sendOnSatelliteDatagramReceived(
                    mReceivedDatagram, 4);
        mSatelliteManager.pollPendingSatelliteDatagrams(Runnable::run, resultListener::offer);
        mSatelliteManager.sendSatelliteDatagram(SatelliteManager.DATAGRAM_TYPE_SOS_MESSAGE,
                datagram, true, Runnable::run, resultListener::offer);
        SatelliteTestApp.getTestSatelliteService().sendOnSatelliteDatagramReceived(
                    mReceivedDatagram, 3);
        mSatelliteManager.sendSatelliteDatagram(SatelliteManager.DATAGRAM_TYPE_SOS_MESSAGE,
                datagram, true, Runnable::run, resultListener::offer);
        SatelliteTestApp.getTestSatelliteService().sendOnSatelliteDatagramReceived(
                    mReceivedDatagram, 2);
        mSatelliteManager.sendSatelliteDatagram(SatelliteManager.DATAGRAM_TYPE_SOS_MESSAGE,
                datagram, true, Runnable::run, resultListener::offer);
        SatelliteTestApp.getTestSatelliteService().sendOnSatelliteDatagramReceived(
                    mReceivedDatagram, 1);
        mSatelliteManager.sendSatelliteDatagram(SatelliteManager.DATAGRAM_TYPE_SOS_MESSAGE,
                datagram, true, Runnable::run, resultListener::offer);
        SatelliteTestApp.getTestSatelliteService().sendOnSatelliteDatagramReceived(
                    mReceivedDatagram, 0);
        try {
            Integer value = resultListener.poll(1000, TimeUnit.MILLISECONDS);
            TextView textView = findViewById(R.id.text_id);
            if (value == 0) {
                textView.setText("multipleSendReceiveSatelliteDatagram is Successful");
            } else {
                textView.setText("Status for multipleSendReceiveSatelliteDatagram : "
                        + SatelliteErrorUtils.mapError(value));
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "exception caught =" + e);
        }
    }

    private void registerForSatelliteDatagramApp(View view) {
        int result = mSatelliteManager.registerForSatelliteDatagram(Runnable::run, mCallback);
        TextView textView = findViewById(R.id.text_id);
        if (result == 0) {
            textView.setText("registerForSatelliteDatagram is successful");
        } else {
            textView.setText("Status for registerForSatelliteDatagram : "
                    + SatelliteErrorUtils.mapError(result));
        }
    }

    private void unregisterForSatelliteDatagramApp(View view) {
        mSatelliteManager.unregisterForSatelliteDatagram(mCallback);
        TextView textView = findViewById(R.id.text_id);
        textView.setText("unregisterForSatelliteDatagram is successful");
    }
}
