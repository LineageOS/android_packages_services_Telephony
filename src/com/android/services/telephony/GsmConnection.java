/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.services.telephony;

import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;

import com.android.internal.telephony.Connection;
import com.android.internal.telephony.GsmCdmaConnection;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.gsm.SuppServiceNotification;

/**
 * Manages a single phone call handled by GSM.
 */
final class GsmConnection extends TelephonyConnection {
    private static final int MSG_SUPP_SERVICE_NOTIFY = 1;

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_SUPP_SERVICE_NOTIFY:
                    Log.v(GsmConnection.this, "MSG_SUPP_SERVICE_NOTIFY");
                    AsyncResult ar = (AsyncResult) msg.obj;
                    SuppServiceNotification ssn = (SuppServiceNotification) ar.result;

                    if (ssn != null) {
                        onSuppServiceNotification(ssn);
                    }
                    break;
            }
        }
    };

    private boolean mIsForwarded;
    private boolean mRemoteIncomingCallsBarred;

    GsmConnection(Connection connection, String telecomCallId, boolean isOutgoing) {
        super(connection, telecomCallId, isOutgoing);
        updateConnectionProperties();
    }

    @Override
    void setOriginalConnection(Connection originalConnection) {
        super.setOriginalConnection(originalConnection);
        getPhone().registerForSuppServiceNotification(mHandler, MSG_SUPP_SERVICE_NOTIFY, null);
    }

    @Override
    void clearOriginalConnection() {
        Phone phone = getPhone();
        if (phone != null) {
            phone.unregisterForSuppServiceNotification(mHandler);
        }
        super.clearOriginalConnection();
    }

    /**
     * Clones the current {@link GsmConnection}.
     * <p>
     * Listeners are not copied to the new instance.
     *
     * @return The cloned connection.
     */
    @Override
    public TelephonyConnection cloneConnection() {
        GsmConnection gsmConnection = new GsmConnection(getOriginalConnection(),
                getTelecomCallId(), mIsOutgoing);
        return gsmConnection;
    }

    /** {@inheritDoc} */
    @Override
    public void onPlayDtmfTone(char digit) {
        if (getPhone() != null) {
            getPhone().startDtmf(digit);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void onStopDtmfTone() {
        if (getPhone() != null) {
            getPhone().stopDtmf();
        }
    }

    @Override
    protected int buildConnectionProperties() {
        int properties = super.buildConnectionProperties();
        // PROPERTY_IS_DOWNGRADED_CONFERENCE is permanent on GSM connections -- once it is set, it
        // should be retained.
        if ((getConnectionProperties() & PROPERTY_IS_DOWNGRADED_CONFERENCE) != 0) {
            properties |= PROPERTY_IS_DOWNGRADED_CONFERENCE;
        }

        if (getOriginalConnection() instanceof GsmCdmaConnection) {
            GsmCdmaConnection gc = (GsmCdmaConnection) getOriginalConnection();
            if (gc.isForwarded()) {
                properties |= PROPERTY_WAS_FORWARDED;
            }
        }
        if (mRemoteIncomingCallsBarred) {
            properties |= PROPERTY_REMOTE_INCOMING_CALLS_BARRED;
        }

        return properties;
    }

    @Override
    protected int buildConnectionCapabilities() {
        int capabilities = super.buildConnectionCapabilities();
        capabilities |= CAPABILITY_MUTE;
        // Overwrites TelephonyConnection.buildConnectionCapabilities() and resets the hold options
        // because all GSM calls should hold, even if the carrier config option is set to not show
        // hold for IMS calls.
        if (!shouldTreatAsEmergencyCall()) {
            capabilities |= CAPABILITY_SUPPORT_HOLD;
            if (getState() == STATE_ACTIVE || getState() == STATE_HOLDING) {
                capabilities |= CAPABILITY_HOLD;
            }
        }

        // For GSM connections, CAPABILITY_CONFERENCE_HAS_NO_CHILDREN should be applied whenever
        // PROPERTY_IS_DOWNGRADED_CONFERENCE is true.
        if ((getConnectionProperties() & PROPERTY_IS_DOWNGRADED_CONFERENCE) != 0) {
            capabilities |= CAPABILITY_CONFERENCE_HAS_NO_CHILDREN;
        }

        return capabilities;
    }

    @Override
    void onRemovedFromCallService() {
        super.onRemovedFromCallService();
    }

    private void onSuppServiceNotification(SuppServiceNotification notification) {
        Phone phone = getPhone();
        int state = getState();

        Log.d(this, "SS Notification: " + notification);

        if (notification.history != null) {
            Bundle extras = new Bundle();
            Log.v(GsmConnection.this, "Updating call history info in extras.");
            extras.putStringArrayList(Connection.EXTRA_LAST_FORWARDED_NUMBER,
                    new ArrayList(Arrays.asList(notification.history)));
            putExtras(lastForwardedNumber);
        }

        if (notification.notificationType == SuppServiceNotification.NOTIFICATION_TYPE_MT) {
            if (notification.code == SuppServiceNotification.MT_CODE_CALL_ON_HOLD) {
                sendConnectionEvent(EVENT_CALL_REMOTELY_HELD, null);
            } else if (notification.code == SuppServiceNotification.MT_CODE_CALL_RETRIEVED) {
                sendConnectionEvent(EVENT_CALL_REMOTELY_UNHELD, null);
            } else if (notification.code ==
                    SuppServiceNotification.MT_CODE_ADDITIONAL_CALL_FORWARDED) {
                sendConnectionEvent(EVENT_ADDITIONAL_CALL_FORWARDED, null);
            }
        } else if (notification.notificationType == SuppServiceNotification.NOTIFICATION_TYPE_MO) {
            if (notification.code == SuppServiceNotification.MO_CODE_CALL_IS_WAITING) {
                if (state == STATE_DIALING) {
                    sendConnectionEvent(EVENT_DIALING_IS_WAITING, null);
                }
            } else if (notification.code ==
                    SuppServiceNotification.MO_CODE_INCOMING_CALLS_BARRED) {
                mRemoteIncomingCallsBarred = true;
            }
        }

        updateConnectionProperties();
    }
}
