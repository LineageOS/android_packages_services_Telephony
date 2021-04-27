/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.telephony.ims.DelegateRegistrationState;
import android.telephony.ims.FeatureTagState;
import android.telephony.ims.SipDelegateConfiguration;
import android.telephony.ims.SipDelegateImsConfiguration;
import android.telephony.ims.SipDelegateManager;
import android.telephony.ims.SipMessage;
import android.util.LocalLog;
import android.util.Log;

import com.android.services.telephony.rcs.validator.IncomingTransportStateValidator;
import com.android.services.telephony.rcs.validator.OutgoingTransportStateValidator;
import com.android.services.telephony.rcs.validator.SipMessageValidator;
import com.android.services.telephony.rcs.validator.ValidationResult;

import java.io.PrintWriter;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

/**
 * Track incoming and outgoing SIP messages passing through this delegate and verify these messages
 * by doing the following:
 *  <ul>
 *    <li>Track the SipDelegate's registration state to ensure that a registration event has
 *    occurred before allowing outgoing messages.</li>
 *    <li>Track the SipDelegate's IMS configuration version and deny any outgoing SipMessages
 *    associated with a stale IMS configuration version.</li>
 *    <li>Track the SipDelegate open/close state to allow/deny outgoing messages based on the
 *    session's state.</li>
 * </ul>
 */
public class TransportSipSessionTracker {

    private static final String LOG_TAG = "SipSessionT";

    private final int mSubId;
    private final ScheduledExecutorService mExecutor;
    private final LocalLog mLocalLog = new LocalLog(SipTransportController.LOG_SIZE);
    // Validators
    private final IncomingTransportStateValidator mIncomingTransportStateValidator =
            new IncomingTransportStateValidator();
    private final OutgoingTransportStateValidator mOutgoingTransportStateValidator =
            new OutgoingTransportStateValidator();
    private final SipMessageValidator mOutgoingMessageValidator;
    private final SipMessageValidator mIncomingMessageValidator;

    private Set<String> mSupportedFeatureTags;
    private Set<FeatureTagState> mDeniedFeatureTags;
    private long mConfigVersion = -1;
    private DelegateRegistrationState mLatestRegistrationState;
    private Consumer<List<String>> mClosingCompleteConsumer;
    private Consumer<List<String>> mRegistrationAppliedConsumer;


    public TransportSipSessionTracker(int subId, ScheduledExecutorService executor) {
        mSubId = subId;
        mExecutor = executor;
        mOutgoingMessageValidator = mOutgoingTransportStateValidator;
        mIncomingMessageValidator = mIncomingTransportStateValidator;
    }

    /**
     * Notify this tracker that a registration state change has occurred.
     * <p>
     * In some scenarios, this will require that existing SIP dialogs are closed (for example, when
     * moving a feature tag from REGISTERED->DEREGISTERING). This method allows the caller to
     * provide a Consumer that will be called when either there are no SIP dialogs active on
     * DEREGISTERING feature tags, or a timeout has occurred. In the case that a timeout has
     * occurred, this Consumer will accept a list of callIds that will be manually closed by the
     * framework to unblock the IMS stack.
     * <p>
     * @param stateChangeComplete A one time Consumer that when completed, will contain a List of
     *         callIds corresponding to SIP Dialogs that have not been closed yet. It is the callers
     *         responsibility to close the dialogs associated with the provided callIds. If another
     *         state update occurs before the previous was completed, the previous consumer will be
     *         completed with an empty list and the new Consumer will be executed when the new state
     *         changes.
     * @param regState The new registration state.
     */
    public void onRegistrationStateChanged(Consumer<List<String>> stateChangeComplete,
            DelegateRegistrationState regState) {
        if (mRegistrationAppliedConsumer != null) {
            logw("onRegistrationStateChanged: pending registration change, completing now.");
            // complete the pending consumer with no dialogs pending, this will be re-evaluated
            // and new configuration will be applied.
            mRegistrationAppliedConsumer.accept(Collections.emptyList());
        }
        mLatestRegistrationState = regState;
        // evaluate if this needs to be set based on reg state.
        mRegistrationAppliedConsumer = stateChangeComplete;
        // notify stateChangeComplete when reg state applied
        mExecutor.execute(() -> {
            // TODO: Track open regState & signal dialogs to close if required.
            // Collect open dialogs associated with features that regState is signalling as
            // DEREGISTERING. When PENDING_DIALOG_CLOSING_TIMEOUT_MS occurs, these dialogs need to
            // close so that the features can move to DEREGISTERED.

            // For now, just pass back an empty list and complete the Consumer.
            if (mRegistrationAppliedConsumer != null) {
                mRegistrationAppliedConsumer.accept(Collections.emptyList());
                mRegistrationAppliedConsumer = null;
            }
        });
    }

    /**
     * Notify this tracker that the IMS configuration has changed.
     *
     * Parameters contained in the IMS configuration will be used to validate outgoing messages,
     * such as the configuration version.
     * @param c The newest IMS configuration.
     */
    public void onImsConfigurationChanged(SipDelegateImsConfiguration c) {
        if (c.getVersion() == mConfigVersion) {
            return;
        }
        logi("onImsConfigurationChanged: " + mConfigVersion + "->" + c.getVersion());
        mConfigVersion = c.getVersion();
    }

    /**
     * Notify this tracker that the IMS configuration has changed.
     *
     * Parameters contained in the IMS configuration will be used to validate outgoing messages,
     * such as the configuration version.
     * @param c The newest IMS configuration.
     */
    public void onConfigurationChanged(SipDelegateConfiguration c) {
        if (c.getVersion() == mConfigVersion) {
            return;
        }
        logi("onConfigurationChanged: " + mConfigVersion + "->" + c.getVersion());
        mConfigVersion = c.getVersion();
    }

    /**
     * A new message transport has been opened to a SipDelegate.
     * <p>
     * Initializes this tracker and resets any state required to process messages.
     * @param supportedFeatureTags feature tags that are supported and should pass message
     *                             verification.
     * @param deniedFeatureTags feature tags that were denied and should fail message verification.
     */
    public void onTransportOpened(Set<String> supportedFeatureTags,
            Set<FeatureTagState> deniedFeatureTags) {
        logi("onTransportOpened: moving to open state");
        mSupportedFeatureTags = supportedFeatureTags;
        mDeniedFeatureTags = deniedFeatureTags;
        mOutgoingTransportStateValidator.open();
        mIncomingTransportStateValidator.open();
    }

    /**
     * A SIP session has been cleaned up and should no longer be tracked.
     * @param callId The call ID associated with the SIP session.
     */
    public void onSipSessionCleanup(String callId) {
        //TODO track SIP sessions.
    }

    /**
     * Move this tracker into a restricted state, where only outgoing SIP messages associated with
     * an ongoing SIP Session may be sent. Any out-of-dialog outgoing SIP messages will be denied.
     * This does not affect incoming SIP messages (for example, an incoming SIP INVITE).
     * <p>
     * This tracker will stay in this state until either all open SIP Sessions are closed by the
     * remote application, or a timeout occurs. Once this happens, the provided Consumer will accept
     * a List of call IDs associated with the open SIP Sessions that did not close before the
     * timeout. The caller must then close all open SIP Sessions before closing the transport.
     * @param closingCompleteConsumer A Consumer that will be called when the transport can be
     *         closed and may contain a list of callIds associated with SIP sessions that have not
     *         been closed.
     * @param closingReason The reason that will be provided if an outgoing out-of-dialog SIP
     *         message is sent while the transport is closing.
     * @param closedReason The reason that will be provided if any outgoing SIP message is sent
     *         once the transport is closed.
     */
    public void closeSessionsGracefully(Consumer<List<String>> closingCompleteConsumer,
            int closingReason, int closedReason) {
        if (mClosingCompleteConsumer != null) {
            logw("closeSessionsGracefully: already pending close, completing consumer to unblock");
            closingCompleteConsumer.accept(Collections.emptyList());
            return;
        }
        logi("closeSessionsGracefully: moving to restricted state, reason=" + closingReason);
        mClosingCompleteConsumer = closingCompleteConsumer;
        mOutgoingTransportStateValidator.restrict(closingReason);
        mExecutor.execute(() -> {
            logi("closeSessionsGracefully: moving to closed state, reason=" + closedReason);
            mOutgoingTransportStateValidator.close(closedReason);
            mIncomingTransportStateValidator.close(closedReason);
            if (mClosingCompleteConsumer != null) {
                // TODO: Track SIP sessions and complete when there are no SIP dialogs open anymore
                //  or the timeout occurs.
                mClosingCompleteConsumer.accept(Collections.emptyList());
                mClosingCompleteConsumer = null;
            }
        });
    }

    /**
     * The message transport must close now due to a configuration change (SIM subscription change,
     * user disabled RCS, the service is dead, etc...).
     * @param closedReason The error reason for why the message transport was closed that will be
     *         sent back to the caller if a new SIP message is sent.
     * @return A List of call IDs associated with sessions that were still open at the time that the
     * tracker closed the transport.
     */
    public List<String> closeSessionsForcefully(int closedReason) {
        logi("closeSessionsForcefully: moving to closed state, reason=" + closedReason);
        mOutgoingTransportStateValidator.close(closedReason);
        mIncomingTransportStateValidator.close(closedReason);
        // TODO: add logic to collect open SIP dialogs to be forcefully closed once they are being
        //  tracked.
        List<String> openCallIds = Collections.emptyList();
        if (mClosingCompleteConsumer != null) {
            logi("closeSessionsForcefully: sending pending call ids through close consumer");
            // send the call ID through the pending complete mechanism to unblock any previous
            // graceful close command.
            mClosingCompleteConsumer.accept(openCallIds);
            mClosingCompleteConsumer = null;
            return Collections.emptyList();
        } else {
            return openCallIds;
        }
    }

    /**
     * Verify a new outgoing SIP message before sending to the SipDelegate (ImsService).
     * @param message The SIP message being verified
     * @return The result of verifying the outgoing message.
     */

    public ValidationResult verifyOutgoingMessage(SipMessage message, long configVersion) {
        ValidationResult result = mOutgoingMessageValidator.validate(message);
        if (!result.isValidated) return result;

        if (mConfigVersion != configVersion) {
            logi("VerifyOutgoingMessage failed: for message: " + message + ", due to stale IMS "
                    + "configuration: " + configVersion + ", expected: " + mConfigVersion);
            return new ValidationResult(
                    SipDelegateManager.MESSAGE_FAILURE_REASON_STALE_IMS_CONFIGURATION);
        }
        if (mLatestRegistrationState == null) {
            result = new ValidationResult(
                    SipDelegateManager.MESSAGE_FAILURE_REASON_NOT_REGISTERED);
        }
        logi("VerifyOutgoingMessage: " + result + ", message=" + message);
        return result;
    }

    /**
     * Verify a new incoming SIP message before sending it to the
     * DelegateConnectionMessageCallback (remote application).
     * @param message The SipMessage to verify.
     * @return The result of verifying the incoming message.
     */
    public ValidationResult verifyIncomingMessage(SipMessage message) {
        return mIncomingMessageValidator.validate(message);
    }

    /**
     * Acknowledge that a pending incoming or outgoing SIP message has been delivered successfully
     * to the remote.
     * @param transactionId The transaction ID associated with the message.
     */
    public void acknowledgePendingMessage(String transactionId) {
        logi("acknowledgePendingMessage: id=" + transactionId);
        //TODO: keep track of pending messages to add to SIP session candidates.
    }

    /**
     * A pending incoming or outgoing SIP message has failed and should not be tracked.
     * @param transactionId
     */
    public void notifyPendingMessageFailed(String transactionId) {
        logi("notifyPendingMessageFailed: id=" + transactionId);
        //TODO: keep track of pending messages to remove from SIP session candidates.
    }

    /** Dump state about this tracker that should be included in the dumpsys */
    public void dump(PrintWriter printWriter) {
        printWriter.println("Supported Tags:" + mSupportedFeatureTags);
        printWriter.println("Denied Tags:" + mDeniedFeatureTags);
        printWriter.println(mOutgoingTransportStateValidator);
        printWriter.println(mIncomingTransportStateValidator);
        printWriter.println("Reg consumer pending: " + (mRegistrationAppliedConsumer != null));
        printWriter.println("Close consumer pending: " + (mClosingCompleteConsumer != null));
        printWriter.println();
        printWriter.println("Most recent logs:");
        mLocalLog.dump(printWriter);
    }

    private void logi(String log) {
        Log.w(SipTransportController.LOG_TAG, LOG_TAG + "[" + mSubId + "] " + log);
        mLocalLog.log("[I] " + log);
    }

    private void logw(String log) {
        Log.w(SipTransportController.LOG_TAG, LOG_TAG + "[" + mSubId + "] " + log);
        mLocalLog.log("[W] " + log);
    }
}
