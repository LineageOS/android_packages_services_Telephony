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

import static android.telephony.satellite.SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE;
import static android.telephony.satellite.SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_RECEIVE_FAILED;
import static android.telephony.satellite.SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_RECEIVE_NONE;
import static android.telephony.satellite.SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_RECEIVE_SUCCESS;
import static android.telephony.satellite.SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_RECEIVING;
import static android.telephony.satellite.SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SENDING;
import static android.telephony.satellite.SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SEND_FAILED;
import static android.telephony.satellite.SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SEND_SUCCESS;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.satellite.PointingInfo;
import android.telephony.satellite.SatelliteDatagram;
import android.telephony.satellite.SatelliteDatagramCallback;
import android.telephony.satellite.SatelliteManager;
import android.telephony.satellite.SatelliteStateCallback;
import android.telephony.satellite.SatelliteTransmissionUpdateCallback;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.TextView;

import java.util.LinkedList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Activity related to Datagram APIs.
 */
public class Datagram extends Activity {

    private static final String TAG = "DatagramSaloni";
    private static final int MAX_NUMBER_OF_STORED_STATES = 3;
    private int mTransferState;
    private int mModemState;
    LinkedList<Integer> mModemStateQueue = new LinkedList<>();
    LinkedList<Integer> mSendQueue = new LinkedList<>();
    LinkedList<Integer> mReceiveQueue = new LinkedList<>();

    private SatelliteManager mSatelliteManager;
    private SatelliteDatagramCallbackTestApp mDatagramCallback;
    private SatelliteStateCallbackTestApp mStateCallback;
    private SatelliteTransmissionUpdateCallbackTestApp mCallback;
    private android.telephony.satellite.stub.SatelliteDatagram mReceivedDatagram;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mSatelliteManager = getSystemService(SatelliteManager.class);
        mDatagramCallback = new SatelliteDatagramCallbackTestApp();
        mStateCallback = new SatelliteStateCallbackTestApp();
        mCallback = new SatelliteTransmissionUpdateCallbackTestApp();

        mReceivedDatagram = new android.telephony.satellite.stub.SatelliteDatagram();

        setContentView(R.layout.activity_Datagram);
        findViewById(R.id.startSatelliteTransmissionUpdates)
                .setOnClickListener(this::startSatelliteTransmissionUpdatesApp);
        findViewById(R.id.stopSatelliteTransmissionUpdates)
                .setOnClickListener(this::stopSatelliteTransmissionUpdatesApp);
        findViewById(R.id.pollPendingSatelliteDatagrams)
                .setOnClickListener(this::pollPendingSatelliteDatagramsApp);
        findViewById(R.id.sendSatelliteDatagram)
                .setOnClickListener(this::sendSatelliteDatagramApp);
        findViewById(R.id.registerForSatelliteDatagram)
                .setOnClickListener(this::registerForSatelliteDatagramApp);
        findViewById(R.id.unregisterForSatelliteDatagram)
                .setOnClickListener(this::unregisterForSatelliteDatagramApp);
        findViewById(R.id.showDatagramSendStateTransition)
                .setOnClickListener(this::showDatagramSendStateTransitionApp);
        findViewById(R.id.showDatagramReceiveStateTransition)
                .setOnClickListener(this::showDatagramReceiveStateTransitionApp);
        findViewById(R.id.registerForSatelliteModemStateChanged)
                .setOnClickListener(this::registerForSatelliteModemStateChangedApp);
        findViewById(R.id.unregisterForSatelliteModemStateChanged)
                .setOnClickListener(this::unregisterForSatelliteModemStateChangedApp);
        findViewById(R.id.showSatelliteModemStateTransition)
                .setOnClickListener(this::showSatelliteModemStateTransitionApp);

        findViewById(R.id.Back).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(Datagram.this, SatelliteTestApp.class));
            }
        });
    }

    protected class SatelliteDatagramCallbackTestApp implements SatelliteDatagramCallback {
        @Override
        public void onSatelliteDatagramReceived(long datagramId, SatelliteDatagram datagram,
                int pendingCount, Consumer<Void> callback) {
            Log.d(TAG, "onSatelliteDatagramReceived in TestApp: datagramId =" + datagramId
                    + ", datagram =" + datagram + ", pendingCount=" + pendingCount);
        }
    }

    protected class SatelliteStateCallbackTestApp implements SatelliteStateCallback {
        @Override
        public void onSatelliteModemStateChanged(int state) {
            mModemState = state;
            mModemStateQueue.addLast(state);
            if (mModemStateQueue.size() > MAX_NUMBER_OF_STORED_STATES) {
                mModemStateQueue.removeFirst();
            }
            Log.d(TAG, "onSatelliteModemStateChanged in TestApp: state=" + mModemState);
        }
    }
    protected class SatelliteTransmissionUpdateCallbackTestApp implements
            SatelliteTransmissionUpdateCallback {
        @Override
        public void onSatellitePositionChanged(PointingInfo pointingInfo) {
            Log.d(TAG, "onSatellitePositionChanged in TestApp: pointingInfo =" + pointingInfo);
        }

        @Override
        public void onSendDatagramStateChanged(int state, int sendPendingCount, int errorCode) {
            mTransferState = state;
            mSendQueue.addLast(state);
            if (mSendQueue.size() > MAX_NUMBER_OF_STORED_STATES) {
                mSendQueue.removeFirst();
            }
            Log.d(TAG, "onSendDatagramStateChanged in TestApp: state =" + mTransferState
                    + ", sendPendingCount =" + sendPendingCount + ", errorCode=" + errorCode);
        }

        @Override
        public void onReceiveDatagramStateChanged(
                int state, int receivePendingCount, int errorCode) {
            mTransferState = state;
            mReceiveQueue.addLast(state);
            if (mReceiveQueue.size() > MAX_NUMBER_OF_STORED_STATES) {
                mReceiveQueue.removeFirst();
            }
            Log.d(TAG, "onReceiveDatagramStateChanged in TestApp: state=" + mTransferState
                    + ", receivePendingCount=" + receivePendingCount + ", errorCode=" + errorCode);
        }
    }

    private void startSatelliteTransmissionUpdatesApp(View view) {
        LinkedBlockingQueue<Integer> error = new LinkedBlockingQueue<>(1);
        mSatelliteManager.requestSatelliteEnabled(true, true, Runnable::run, error::offer);
        mSatelliteManager.startSatelliteTransmissionUpdates(Runnable::run, error::offer, mCallback);
        try {
            Integer value = error.poll(1000, TimeUnit.MILLISECONDS);
            TextView textView = findViewById(R.id.text_id);
            if (value == 0) {
                textView.setText("startSatelliteTransmissionUpdates is Successful");
            } else {
                textView.setText("Status for startSatelliteTransmissionUpdates : "
                        + SatelliteErrorUtils.mapError(value));
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "exception caught =" + e);
        }
    }

    private void stopSatelliteTransmissionUpdatesApp(View view) {
        LinkedBlockingQueue<Integer> error = new LinkedBlockingQueue<>(1);
        mSatelliteManager.stopSatelliteTransmissionUpdates(mCallback, Runnable::run, error::offer);
        try {
            Integer value = error.poll(1000, TimeUnit.MILLISECONDS);
            TextView textView = findViewById(R.id.text_id);
            if (value == 0) {
                textView.setText("stopSatelliteTransmissionUpdates is Successful");
            } else {
                textView.setText("Status for stopSatelliteTransmissionUpdates : "
                        + SatelliteErrorUtils.mapError(value));
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "exception caught =" + e);
        }
    }
    private void pollPendingSatelliteDatagramsApp(View view) {
        mSatelliteManager.onDeviceAlignedWithSatellite(true);
        SatelliteTestApp.getTestSatelliteService().sendOnPendingDatagrams();
        /*SatelliteTestApp.getTestSatelliteService().sendOnSatelliteDatagramReceived(
                    mReceivedDatagram, 0);*/
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

    private void registerForSatelliteDatagramApp(View view) {
        int result = mSatelliteManager.registerForSatelliteDatagram(Runnable::run,
                mDatagramCallback);
        TextView textView = findViewById(R.id.text_id);
        if (result == 0) {
            textView.setText("registerForSatelliteDatagram is successful");
        } else {
            textView.setText("Status for registerForSatelliteDatagram : "
                    + SatelliteErrorUtils.mapError(result));
        }
    }

    private void unregisterForSatelliteDatagramApp(View view) {
        mSatelliteManager.unregisterForSatelliteDatagram(mDatagramCallback);
        TextView textView = findViewById(R.id.text_id);
        textView.setText("unregisterForSatelliteDatagram is successful");
    }

    private void showDatagramSendStateTransitionApp(View view) {
        TextView textView = findViewById(R.id.text_id);
        textView.setText("Last datagram send state transition is : "
                + getTransferStateTransition(mSendQueue));
    }

    private void showDatagramReceiveStateTransitionApp(View view) {
        TextView textView = findViewById(R.id.text_id);
        textView.setText("Last datagram receive state transition is : "
                + getTransferStateTransition(mReceiveQueue));
    }

    private void registerForSatelliteModemStateChangedApp(View view) {
        int result = mSatelliteManager.registerForSatelliteModemStateChanged(Runnable::run,
                mStateCallback);
        TextView textView = findViewById(R.id.text_id);
        if (result == 0) {
            textView.setText("registerForSatelliteModemStateChanged is successful");
        } else {
            textView.setText("Status for registerForSatelliteModemStateChanged : "
                    + SatelliteErrorUtils.mapError(result));
        }
    }

    private void unregisterForSatelliteModemStateChangedApp(View view) {
        mSatelliteManager.unregisterForSatelliteModemStateChanged(mStateCallback);
        TextView textView = findViewById(R.id.text_id);
        textView.setText("unregisterForSatelliteModemStateChanged is successful");
    }

    private void showSatelliteModemStateTransitionApp(View view) {
        TextView textView = findViewById(R.id.text_id);
        textView.setText("Last modem transition state is: "
                + getSatelliteModemStateTransition(mModemStateQueue));
    }

    private String getSatelliteModemStateName(@SatelliteManager.SatelliteModemState int state) {
        switch (state) {
            case SatelliteManager.SATELLITE_MODEM_STATE_IDLE:
                return "IDLE";
            case SatelliteManager.SATELLITE_MODEM_STATE_LISTENING:
                return "LISTENING";
            case SatelliteManager.SATELLITE_MODEM_STATE_DATAGRAM_TRANSFERRING:
                return "DATAGRAM_TRANSFERRING";
            case SatelliteManager.SATELLITE_MODEM_STATE_DATAGRAM_RETRYING:
                return "DATAGRAM_RETRYING";
            case SatelliteManager.SATELLITE_MODEM_STATE_OFF:
                return "OFF";
            case SatelliteManager.SATELLITE_MODEM_STATE_UNAVAILABLE:
                return "UNAVAILABLE";
            default: return "UNKNOWN";
        }
    }

    private String getSatelliteModemStateTransition(LinkedList<Integer> states) {
        StringBuilder sb = new StringBuilder();
        for (int state : states) {
            sb.append(getSatelliteModemStateName(state));
            sb.append("=>");
        }
        if (!sb.isEmpty()) {
            sb.delete(sb.length() - 2, sb.length());
        }
        return sb.toString();
    }

    private String getDatagramTransferStateName(
            @SatelliteManager.SatelliteDatagramTransferState int state) {
        switch (state) {
            case SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE: return "IDLE";
            case SATELLITE_DATAGRAM_TRANSFER_STATE_SENDING: return "SENDING";
            case SATELLITE_DATAGRAM_TRANSFER_STATE_SEND_SUCCESS: return "SEND_SUCCESS";
            case SATELLITE_DATAGRAM_TRANSFER_STATE_SEND_FAILED: return "SEND_FAILED";
            case SATELLITE_DATAGRAM_TRANSFER_STATE_RECEIVING: return "RECEIVING";
            case SATELLITE_DATAGRAM_TRANSFER_STATE_RECEIVE_SUCCESS: return "RECEIVE_SUCCESS";
            case SATELLITE_DATAGRAM_TRANSFER_STATE_RECEIVE_NONE: return "RECEIVE_NONE";
            case SATELLITE_DATAGRAM_TRANSFER_STATE_RECEIVE_FAILED: return "RECEIVE_FAILED";
            default: return "UNKNOWN";
        }
    }

    private String getTransferStateTransition(LinkedList<Integer> states) {
        StringBuilder sb = new StringBuilder();
        for (int state : states) {
            sb.append(getDatagramTransferStateName(state));
            sb.append("=>");
        }
        if (!sb.isEmpty()) {
            sb.delete(sb.length() - 2, sb.length());
        }
        return sb.toString();
    }
}
