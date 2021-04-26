/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.services.telephony.rcs;

import android.os.Binder;
import android.os.RemoteException;
import android.telephony.ims.DelegateMessageCallback;
import android.telephony.ims.DelegateRegistrationState;
import android.telephony.ims.FeatureTagState;
import android.telephony.ims.SipDelegateConfiguration;
import android.telephony.ims.SipDelegateImsConfiguration;
import android.telephony.ims.SipDelegateManager;
import android.telephony.ims.SipMessage;
import android.telephony.ims.aidl.ISipDelegate;
import android.telephony.ims.aidl.ISipDelegateMessageCallback;
import android.telephony.ims.stub.SipDelegate;
import android.util.LocalLog;
import android.util.Log;

import java.io.PrintWriter;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Tracks the SIP message path both from the IMS application to the SipDelegate and from the
 * SipDelegate back to the IMS Application.
 * <p>
 * Responsibilities include:
 * 1) Queue incoming and outgoing SIP messages and deliver to IMS application and SipDelegate in
 *        order. If there is an error delivering the message, notify the caller.
 * 2) TODO Perform basic validation of outgoing messages.
 * 3) TODO Record the status of ongoing SIP Dialogs and trigger the completion of pending
 *         consumers when they are finished or call closeDialog to clean up the SIP
 *         dialogs that did not complete within the allotted timeout time.
 * <p>
 * Note: This handles incoming binder calls, so all calls from other processes should be handled on
 * the provided Executor.
 */
public class MessageTransportStateTracker implements DelegateBinderStateManager.StateCallback {
    private static final String TAG = "MessageST";

    /**
     * Communicates the result of verifying whether a SIP message should be sent based on the
     * contents of the SIP message as well as if the transport is in an available state for the
     * intended recipient of the message.
     */
    private static class VerificationResult {
        public static final VerificationResult SUCCESS = new VerificationResult();

        /**
         * If {@code true}, the requested SIP message has been verified to be sent to the remote. If
         * {@code false}, the SIP message has failed verification and should not be sent to the
         * result. The {@link #restrictedReason} field will contain the reason for the verification
         * failure.
         */
        public final boolean isVerified;

        /**
         * The reason associated with why the SIP message was not verified and generated a
         * {@code false} result for {@link #isVerified}.
         */
        public final int restrictedReason;

        /**
         * Communicates a verified result of success. Use {@link #SUCCESS} instead.
         */
        private VerificationResult() {
            isVerified = true;
            restrictedReason = SipDelegateManager.MESSAGE_FAILURE_REASON_UNKNOWN;
        }

        /**
         * The result of verifying that the SIP Message should be sent.
         * @param reason The reason associated with why the SIP message was not verified and
         *               generated a {@code false} result for {@link #isVerified}.
         */
        VerificationResult(@SipDelegateManager.MessageFailureReason int reason) {
            isVerified = false;
            restrictedReason = reason;
        }
    }

    // SipDelegateConnection(IMS Application) -> SipDelegate(ImsService)
    private final ISipDelegate.Stub mSipDelegateConnection = new ISipDelegate.Stub() {
        /**
         * The IMS application is acknowledging that it has successfully received and processed an
         * incoming SIP message sent by the SipDelegate in
         * {@link ISipDelegateMessageCallback#onMessageReceived(SipMessage)}.
         */
        @Override
        public void notifyMessageReceived(String viaTransactionId) {
            long token = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> {
                    if (mSipDelegate == null) {
                        logw("notifyMessageReceived called when SipDelegate is not associated for "
                                + "transaction id: " + viaTransactionId);
                        return;
                    }
                    try {
                        // TODO track the SIP Dialogs created/destroyed on the associated
                        // SipDelegate.
                        mSipDelegate.notifyMessageReceived(viaTransactionId);
                    } catch (RemoteException e) {
                        logw("SipDelegate not available when notifyMessageReceived was called "
                                + "for transaction id: " + viaTransactionId);
                    }
                });
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        /**
         * The IMS application is acknowledging that it received an incoming SIP message sent by the
         * SipDelegate in {@link ISipDelegateMessageCallback#onMessageReceived(SipMessage)} but it
         * was unable to process it.
         */
        @Override
        public void notifyMessageReceiveError(String viaTransactionId, int reason) {
            long token = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> {
                    if (mSipDelegate == null) {
                        logw("notifyMessageReceiveError called when SipDelegate is not associated "
                                + "for transaction id: " + viaTransactionId);
                        return;
                    }
                    try {
                        // TODO track the SIP Dialogs created/destroyed on the associated
                        // SipDelegate.
                        mSipDelegate.notifyMessageReceiveError(viaTransactionId, reason);
                    } catch (RemoteException e) {
                        logw("SipDelegate not available when notifyMessageReceiveError was called "
                                + "for transaction id: " + viaTransactionId);
                    }
                });
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        /**
         * The IMS application is sending an outgoing SIP message to the SipDelegate to be processed
         * and sent over the network.
         */
        @Override
        public void sendMessage(SipMessage sipMessage, long configVersion) {
            long token = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> {
                    VerificationResult result = verifyOutgoingMessage(sipMessage);
                    if (!result.isVerified) {
                        notifyDelegateSendError("Outgoing messages restricted", sipMessage,
                                result.restrictedReason);
                        return;
                    }
                    try {
                        // TODO track the SIP Dialogs created/destroyed on the associated
                        // SipDelegate.
                        mSipDelegate.sendMessage(sipMessage, configVersion);
                        logi("sendMessage: message sent - " + sipMessage + ", configVersion: "
                                + configVersion);
                    } catch (RemoteException e) {
                        notifyDelegateSendError("RemoteException: " + e, sipMessage,
                                SipDelegateManager.MESSAGE_FAILURE_REASON_DELEGATE_DEAD);
                    }
                });
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        /**
         * The SipDelegateConnection is requesting that the resources associated with an ongoing SIP
         * dialog be released as the SIP dialog is now closed.
         */
        @Override
        public void cleanupSession(String callId) {
            long token = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> {
                    if (mSipDelegate == null) {
                        logw("closeDialog called when SipDelegate is not associated, callId: "
                                + callId);
                        return;
                    }
                    try {
                        // TODO track the SIP Dialogs created/destroyed on the associated
                        // SipDelegate.
                        mSipDelegate.cleanupSession(callId);
                    } catch (RemoteException e) {
                        logw("SipDelegate not available when closeDialog was called "
                                + "for call id: " + callId);
                    }
                });
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    };

    // SipDelegate(ImsService) -> SipDelegateConnection(IMS Application)
    private final ISipDelegateMessageCallback.Stub mDelegateConnectionMessageCallback =
            new ISipDelegateMessageCallback.Stub() {
        /**
         * An Incoming SIP Message has been received by the SipDelegate and is being routed
         * to the IMS application for processing.
         * <p>
         * IMS application will call {@link ISipDelegate#notifyMessageReceived(String)} to
         * acknowledge receipt of this incoming message.
         */
        @Override
        public void onMessageReceived(SipMessage message) {
            long token = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> {
                    VerificationResult result = verifyIncomingMessage(message);
                    if (!result.isVerified) {
                        notifyAppReceiveError("Incoming messages restricted", message,
                                result.restrictedReason);
                        return;
                    }
                    try {
                        // TODO track the SIP Dialogs created/destroyed on the associated
                        //  SipDelegate.
                        mAppCallback.onMessageReceived(message);
                        logi("onMessageReceived: received " + message);
                    } catch (RemoteException e) {
                        notifyAppReceiveError("RemoteException: " + e, message,
                                SipDelegateManager.MESSAGE_FAILURE_REASON_DELEGATE_DEAD);
                    }
                });
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        /**
         * An outgoing SIP message sent previously by the SipDelegateConnection to the SipDelegate
         * using {@link ISipDelegate#sendMessage(SipMessage, int)} as been successfully sent.
         */
        @Override
        public void onMessageSent(String viaTransactionId) {
            long token = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> {
                    if (mSipDelegate == null) {
                        logw("Unexpected state, onMessageSent called when SipDelegate is not "
                                + "associated");
                    }
                    try {
                        mAppCallback.onMessageSent(viaTransactionId);
                    } catch (RemoteException e) {
                        logw("Error sending onMessageSent to SipDelegateConnection, remote not"
                                + "available for transaction ID: " + viaTransactionId);
                    }
                });
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        /**
         * An outgoing SIP message sent previously by the SipDelegateConnection to the SipDelegate
         * using {@link ISipDelegate#sendMessage(SipMessage, int)} failed to be sent.
         */
        @Override
        public void onMessageSendFailure(String viaTransactionId, int reason) {
            long token = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> {
                    if (mSipDelegate == null) {
                        logw("Unexpected state, onMessageSendFailure called when SipDelegate is not"
                                + "associated");
                    }
                    try {
                        mAppCallback.onMessageSendFailure(viaTransactionId, reason);
                    } catch (RemoteException e) {
                        logw("Error sending onMessageSendFailure to SipDelegateConnection, remote"
                                + " not available for transaction ID: " + viaTransactionId);
                    }
                });
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    };

    private final ISipDelegateMessageCallback mAppCallback;
    private final Executor mExecutor;
    private final int mSubId;
    private final LocalLog mLocalLog = new LocalLog(SipTransportController.LOG_SIZE);

    private ISipDelegate mSipDelegate;
    private Consumer<Boolean> mPendingClosedConsumer;
    private int mDelegateClosingReason = -1;
    private int mDelegateClosedReason = -1;

    public MessageTransportStateTracker(int subId, Executor executor,
            ISipDelegateMessageCallback appMessageCallback) {
        mSubId = subId;
        mAppCallback = appMessageCallback;
        mExecutor = executor;
    }

    @Override
    public void onRegistrationStateChanged(DelegateRegistrationState registrationState) {
        // TODO: integrate registration changes to SipMessage verification checks.
    }

    @Override
    public void onImsConfigurationChanged(SipDelegateImsConfiguration config) {
        // Not needed for this Tracker
    }

    @Override
    public void onConfigurationChanged(SipDelegateConfiguration config) {
        // Not needed for this Tracker
    }

    /**
     * Open the transport and allow SIP messages to be sent/received on the delegate specified.
     * @param delegate The delegate connection to send SIP messages to on the ImsService.
     * @param deniedFeatureTags Feature tags that have been denied. Outgoing SIP messages relating
     *         to these tags will be denied.
     */
    public void openTransport(ISipDelegate delegate, Set<FeatureTagState> deniedFeatureTags) {
        mSipDelegate = delegate;
        mDelegateClosingReason = -1;
        mDelegateClosedReason = -1;
        // TODO: integrate denied tags to SipMessage verification checks.
    }

    /** Dump state about this tracker that should be included in the dumpsys */
    public void dump(PrintWriter printWriter) {
        printWriter.println("Most recent logs:");
        mLocalLog.dump(printWriter);
    }

    /**
     * @return SipDelegate implementation to be sent to IMS application.
     */
    public ISipDelegate getDelegateConnection() {
        return mSipDelegateConnection;
    }

    /**
     * @return The remote application's message callback.
     */
    public ISipDelegateMessageCallback getAppMessageCallback() {
        return mAppCallback;
    }

    /**
     * @return MessageCallback implementation to be sent to the ImsService.
     */
    public ISipDelegateMessageCallback getMessageCallback() {
        return mDelegateConnectionMessageCallback;
    }

    /**
     * Gradually close all SIP Dialogs by:
     * 1) denying all new outgoing SIP Dialog requests with the reason specified and
     * 2) only allowing existing SIP Dialogs to continue.
     * <p>
     * This will allow traffic to continue on existing SIP Dialogs until a BYE is sent and the
     * SIP Dialogs are closed or a timeout is hit and {@link SipDelegate#closeDialog(String)} is
     * forcefully called on all open SIP Dialogs.
     * <p>
     * Any outgoing out-of-dialog traffic on this transport will be denied with the provided reason.
     * <p>
     * Incoming out-of-dialog traffic will continue to be set up until the SipDelegate is fully
     * closed.
     * @param delegateClosingReason The reason code to return to
     * {@link DelegateMessageCallback#onMessageSendFailure(String, int)} if a new out-of-dialog SIP
     *         message is received while waiting for existing Dialogs.
     * @param closedReason reason to return to new outgoing SIP messages via
     *         {@link SipDelegate#notifyMessageReceiveError(String, int)} once the transport
     *         transitions to the fully closed state.
     * @param resultConsumer The consumer called when the message transport has been closed. It will
     *         return {@code true} if the procedure completed successfully or {@link false} if the
     *         transport needed to be closed forcefully due to the application not responding before
     *         a timeout occurred.
     */
    public void closeGracefully(int delegateClosingReason, int closedReason,
            Consumer<Boolean> resultConsumer) {
        mDelegateClosingReason = delegateClosingReason;
        mPendingClosedConsumer = resultConsumer;
        mExecutor.execute(() -> {
            // TODO: Track SIP Dialogs and complete when there are no SIP dialogs open anymore or
            //  the timeout occurs.
            mPendingClosedConsumer.accept(true);
            mPendingClosedConsumer = null;
            closeTransport(closedReason);
        });
    }

    /**
     * Close all ongoing SIP Dialogs immediately and respond to any incoming/outgoing messages with
     * the provided reason.
     * @param closedReason The failure reason to provide to incoming/outgoing SIP messages
     *         if an attempt is made to send/receive a message after this method is called.
     */
    public void close(int closedReason) {
        closeTransport(closedReason);
    }

    // Clean up all state related to the existing SipDelegate immediately.
    private void closeTransport(int closedReason) {
        // TODO: add logic to forcefully close open SIP dialogs once they are being tracked.
        mSipDelegate = null;
        if (mPendingClosedConsumer != null) {
            mExecutor.execute(() -> {
                logw("closeTransport: transport close forced with pending consumer.");
                mPendingClosedConsumer.accept(false /*closedGracefully*/);
                mPendingClosedConsumer = null;
            });
        }
        mDelegateClosingReason = -1;
        mDelegateClosedReason = closedReason;
    }

    private VerificationResult verifyOutgoingMessage(SipMessage message) {
        if (mDelegateClosingReason > -1) {
            return new VerificationResult(mDelegateClosingReason);
        }
        if (mDelegateClosedReason > -1) {
            return new VerificationResult(mDelegateClosedReason);
        }
        if (mSipDelegate == null) {
            logw("sendMessage called when SipDelegate is not associated." + message);
            return new VerificationResult(SipDelegateManager.MESSAGE_FAILURE_REASON_DELEGATE_DEAD);
        }
        return VerificationResult.SUCCESS;
    }

    private VerificationResult verifyIncomingMessage(SipMessage message) {
        // Do not restrict incoming based on closing reason.
        if (mDelegateClosedReason > -1) {
            return new VerificationResult(mDelegateClosedReason);
        }
        return VerificationResult.SUCCESS;
    }

    private void notifyDelegateSendError(String logReason, SipMessage message, int reasonCode) {
        // TODO parse SipMessage header for viaTransactionId.
        logw("Error sending SipMessage[id: " + null + ", code: " + reasonCode + "] -> SipDelegate "
                + "for reason: " + logReason);
        try {
            mAppCallback.onMessageSendFailure(null, reasonCode);
        } catch (RemoteException e) {
            logw("notifyDelegateSendError, SipDelegate is not available: " + e);
        }
    }

    private void notifyAppReceiveError(String logReason, SipMessage message, int reasonCode) {
        // TODO parse SipMessage header for viaTransactionId.
        logw("Error sending SipMessage[id: " + null + ", code: " + reasonCode + "] -> "
                + "SipDelegateConnection for reason: " + logReason);
        try {
            mSipDelegate.notifyMessageReceiveError(null, reasonCode);
        } catch (RemoteException e) {
            logw("notifyAppReceiveError, SipDelegate is not available: " + e);
        }
    }

    private void logi(String log) {
        Log.w(SipTransportController.LOG_TAG, TAG + "[" + mSubId + "] " + log);
        mLocalLog.log("[I] " + log);
    }

    private void logw(String log) {
        Log.w(SipTransportController.LOG_TAG, TAG + "[" + mSubId + "] " + log);
        mLocalLog.log("[W] " + log);
    }
}
