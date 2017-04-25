/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.phone.vvm;

import android.annotation.Nullable;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.VisualVoicemailService;
import android.telephony.VisualVoicemailSms;

import com.android.phone.Assert;
import com.android.phone.vvm.omtp.VvmLog;

import java.util.LinkedList;
import java.util.Queue;

/**
 * Service to manage tasks issued to the {@link VisualVoicemailService}. This service will bind to
 * the default dialer on a visual voicemail event if it implements the VisualVoicemailService. The
 * service will hold all resource for the VisualVoicemailService until {@link
 * VisualVoicemailService.VisualVoicemailTask#finish()} has been called on all issued tasks.
 *
 * If the service is already running it will be reused for new events. The service will stop itself
 * after all events are handled.
 */
public class RemoteVvmTaskManager extends Service {

    private static final String TAG = "RemoteVvmTaskManager";

    private static final String ACTION_START_CELL_SERVICE_CONNECTED =
            "ACTION_START_CELL_SERVICE_CONNECTED";
    private static final String ACTION_START_SMS_RECEIVED = "ACTION_START_SMS_RECEIVED";
    private static final String ACTION_START_SIM_REMOVED = "ACTION_START_SIM_REMOVED";

    // TODO(twyen): track task individually to have time outs.
    private int mTaskReferenceCount;

    private RemoteServiceConnection mConnection;

    /**
     * Handles incoming messages from the VisualVoicemailService.
     */
    private Messenger mMessenger;

    public static void startCellServiceConnected(Context context,
            PhoneAccountHandle phoneAccountHandle) {
        Intent intent = new Intent(ACTION_START_CELL_SERVICE_CONNECTED, null, context,
                RemoteVvmTaskManager.class);
        intent.putExtra(VisualVoicemailService.DATA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle);
        context.startService(intent);
    }

    public static void startSmsReceived(Context context, VisualVoicemailSms sms) {
        Intent intent = new Intent(ACTION_START_SMS_RECEIVED, null, context,
                RemoteVvmTaskManager.class);
        intent.putExtra(VisualVoicemailService.DATA_PHONE_ACCOUNT_HANDLE,
                sms.getPhoneAccountHandle());
        intent.putExtra(VisualVoicemailService.DATA_SMS, sms);
        context.startService(intent);
    }

    public static void startSimRemoved(Context context, PhoneAccountHandle phoneAccountHandle) {
        Intent intent = new Intent(ACTION_START_SIM_REMOVED, null, context,
                RemoteVvmTaskManager.class);
        intent.putExtra(VisualVoicemailService.DATA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle);
        context.startService(intent);
    }

    public static boolean hasRemoteService(Context context) {
        return getRemotePackage(context) != null;
    }

    public static ComponentName getRemotePackage(Context context) {

        ResolveInfo info = context.getPackageManager()
                .resolveService(newBindIntent(context), PackageManager.MATCH_ALL);
        if (info == null) {
            return null;
        }
        return info.getComponentInfo().getComponentName();
    }

    @Override
    public void onCreate() {
        Assert.isMainThread();
        mMessenger = new Messenger(new Handler() {
            @Override
            public void handleMessage(Message msg) {
                Assert.isMainThread();
                switch (msg.what) {
                    case VisualVoicemailService.MSG_TASK_ENDED:
                        mTaskReferenceCount--;
                        checkReference();
                        break;
                    default:
                        VvmLog.wtf(TAG, "unexpected message " + msg.what);
                }
            }
        });
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        Assert.isMainThread();
        mTaskReferenceCount++;
        switch (intent.getAction()) {
            case ACTION_START_CELL_SERVICE_CONNECTED:
                send(VisualVoicemailService.MSG_ON_CELL_SERVICE_CONNECTED, intent.getExtras());
                break;
            case ACTION_START_SMS_RECEIVED:
                send(VisualVoicemailService.MSG_ON_SMS_RECEIVED, intent.getExtras());
                break;
            case ACTION_START_SIM_REMOVED:
                send(VisualVoicemailService.MSG_ON_SIM_REMOVED, intent.getExtras());
                break;
            default:
                Assert.fail("Unexpected action +" + intent.getAction());
                break;
        }
        // Don't rerun service if processed is killed.
        return START_NOT_STICKY;
    }

    @Override
    @Nullable
    public IBinder onBind(Intent intent) {
        return null;
    }

    private int getTaskId() {
        // TODO(twyen): generate unique IDs. Reference counting is used now so it doesn't matter.
        return 1;
    }

    /**
     * Class for interacting with the main interface of the service.
     */
    private class RemoteServiceConnection implements ServiceConnection {

        private final Queue<Message> mTaskQueue = new LinkedList<>();

        private boolean mConnected;

        /**
         * A handler in the VisualVoicemailService
         */
        private Messenger mRemoteMessenger;

        public void enqueue(Message message) {
            mTaskQueue.add(message);
            if (mConnected) {
                runQueue();
            }
        }

        public boolean isConnected() {
            return mConnected;
        }

        public void onServiceConnected(ComponentName className,
                IBinder service) {
            mRemoteMessenger = new Messenger(service);
            mConnected = true;
            runQueue();
        }

        public void onServiceDisconnected(ComponentName className) {
            mConnection = null;
            mConnected = false;
            mRemoteMessenger = null;
            VvmLog.e(TAG, "Service disconnected, " + mTaskReferenceCount + " tasks dropped.");
            mTaskReferenceCount = 0;
            checkReference();
        }

        private void runQueue() {
            Assert.isMainThread();
            Message message = mTaskQueue.poll();
            while (message != null) {
                message.replyTo = mMessenger;
                message.arg1 = getTaskId();

                try {
                    mRemoteMessenger.send(message);
                } catch (RemoteException e) {
                    VvmLog.e(TAG, "Error sending message to remote service", e);
                }
                message = mTaskQueue.poll();
            }
        }
    }

    private void send(int what, Bundle extras) {
        Assert.isMainThread();
        Message message = Message.obtain();
        message.what = what;
        message.setData(new Bundle(extras));
        if (mConnection == null) {
            mConnection = new RemoteServiceConnection();
        }
        mConnection.enqueue(message);

        if (!mConnection.isConnected()) {
            Intent intent = newBindIntent(this);
            intent.setComponent(getRemotePackage(this));
            bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
        }
    }

    private void checkReference() {
        if (mTaskReferenceCount == 0) {
            unbindService(mConnection);
            mConnection = null;
        }
    }

    private static Intent newBindIntent(Context context) {
        Intent intent = new Intent();
        intent.setAction(VisualVoicemailService.SERVICE_INTERFACE);
        TelecomManager telecomManager = context.getSystemService(TelecomManager.class);
        intent.setPackage(telecomManager.getDefaultDialerPackage());
        return intent;
    }
}
