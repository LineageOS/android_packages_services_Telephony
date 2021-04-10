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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.os.RemoteException;
import android.telephony.ims.SipDelegateManager;
import android.telephony.ims.SipMessage;
import android.telephony.ims.aidl.ISipDelegate;
import android.telephony.ims.aidl.ISipDelegateMessageCallback;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.TelephonyTestBase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

@RunWith(AndroidJUnit4.class)
public class MessageTransportStateTrackerTest extends TelephonyTestBase {
    private static final int TEST_SUB_ID = 1;

    private static final SipMessage TEST_MESSAGE = new SipMessage(
            "INVITE sip:callee@ex.domain.com SIP/2.0",
            "Via: SIP/2.0/UDP ex.place.com;branch=z9hG4bK776asdhds",
            new byte[0]);

    // Use for finer-grained control of when the Executor executes.
    private static class PendingExecutor implements Executor {
        private final ArrayList<Runnable> mPendingRunnables = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            mPendingRunnables.add(command);
        }

        public void executePending() {
            for (Runnable r : mPendingRunnables) {
                r.run();
            }
            mPendingRunnables.clear();
        }
    }

    @Mock private ISipDelegateMessageCallback mDelegateMessageCallback;
    @Mock private ISipDelegate mISipDelegate;
    @Mock private Consumer<Boolean> mMockCloseConsumer;

    @Before
    public void setUp() throws Exception {
        super.setUp();
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @SmallTest
    @Test
    public void testDelegateConnectionSendOutgoingMessage() throws Exception {
        MessageTransportStateTracker tracker = new MessageTransportStateTracker(TEST_SUB_ID,
                Runnable::run, mDelegateMessageCallback);

        tracker.openTransport(mISipDelegate, Collections.emptySet());
        tracker.getDelegateConnection().sendMessage(TEST_MESSAGE, 1 /*version*/);
        verify(mISipDelegate).sendMessage(TEST_MESSAGE, 1 /*version*/);

        doThrow(new RemoteException()).when(mISipDelegate).sendMessage(any(), anyLong());
        tracker.getDelegateConnection().sendMessage(TEST_MESSAGE, 1 /*version*/);
        verify(mDelegateMessageCallback).onMessageSendFailure(any(),
                eq(SipDelegateManager.MESSAGE_FAILURE_REASON_DELEGATE_DEAD));

        tracker.close(SipDelegateManager.MESSAGE_FAILURE_REASON_DELEGATE_CLOSED);
        tracker.getDelegateConnection().sendMessage(TEST_MESSAGE, 1 /*version*/);
        verify(mDelegateMessageCallback).onMessageSendFailure(any(),
                eq(SipDelegateManager.MESSAGE_FAILURE_REASON_DELEGATE_CLOSED));
    }

    @SmallTest
    @Test
    public void testDelegateConnectionCloseGracefully() throws Exception {
        PendingExecutor executor = new PendingExecutor();
        MessageTransportStateTracker tracker = new MessageTransportStateTracker(TEST_SUB_ID,
                executor, mDelegateMessageCallback);

        tracker.openTransport(mISipDelegate, Collections.emptySet());
        tracker.getDelegateConnection().sendMessage(TEST_MESSAGE, 1 /*version*/);
        executor.executePending();
        verify(mISipDelegate).sendMessage(TEST_MESSAGE, 1 /*version*/);
        verify(mDelegateMessageCallback, never()).onMessageSendFailure(any(), anyInt());

        // Use PendingExecutor a little weird here, we need to queue sendMessage first, even though
        // closeGracefully will complete partly synchronously to test that the pending message will
        // return MESSAGE_FAILURE_REASON_INTERNAL_DELEGATE_STATE_TRANSITION before the scheduled
        // graceful close operation completes.
        tracker.getDelegateConnection().sendMessage(TEST_MESSAGE, 1 /*version*/);
        tracker.closeGracefully(
                SipDelegateManager.MESSAGE_FAILURE_REASON_INTERNAL_DELEGATE_STATE_TRANSITION,
                SipDelegateManager.MESSAGE_FAILURE_REASON_DELEGATE_CLOSED,
                mMockCloseConsumer);
        verify(mMockCloseConsumer, never()).accept(any());
        // resolve pending close operation
        executor.executePending();
        verify(mDelegateMessageCallback).onMessageSendFailure(any(),
                eq(SipDelegateManager.MESSAGE_FAILURE_REASON_INTERNAL_DELEGATE_STATE_TRANSITION));
        // Still should only report one call of sendMessage from before
        verify(mISipDelegate).sendMessage(TEST_MESSAGE, 1 /*version*/);
        verify(mMockCloseConsumer).accept(true);

        // ensure that after close operation completes, we get the correct
        // MESSAGE_FAILURE_REASON_DELEGATE_CLOSED message.
        tracker.getDelegateConnection().sendMessage(TEST_MESSAGE, 1 /*version*/);
        executor.executePending();
        verify(mDelegateMessageCallback).onMessageSendFailure(any(),
                eq(SipDelegateManager.MESSAGE_FAILURE_REASON_DELEGATE_CLOSED));
        // Still should only report one call of sendMessage from before
        verify(mISipDelegate).sendMessage(TEST_MESSAGE, 1 /*version*/);
    }

    @SmallTest
    @Test
    public void testDelegateConnectionNotifyMessageReceived() throws Exception {
        MessageTransportStateTracker tracker = new MessageTransportStateTracker(TEST_SUB_ID,
                Runnable::run, mDelegateMessageCallback);
        tracker.openTransport(mISipDelegate, Collections.emptySet());
        tracker.getDelegateConnection().notifyMessageReceived("z9hG4bK776asdhds");
        verify(mISipDelegate).notifyMessageReceived("z9hG4bK776asdhds");
    }

    @SmallTest
    @Test
    public void testDelegateConnectionNotifyMessageReceiveError() throws Exception {
        MessageTransportStateTracker tracker = new MessageTransportStateTracker(TEST_SUB_ID,
                Runnable::run, mDelegateMessageCallback);
        tracker.openTransport(mISipDelegate, Collections.emptySet());
        tracker.getDelegateConnection().notifyMessageReceiveError("z9hG4bK776asdhds",
                SipDelegateManager.MESSAGE_FAILURE_REASON_NETWORK_NOT_AVAILABLE);
        verify(mISipDelegate).notifyMessageReceiveError("z9hG4bK776asdhds",
                SipDelegateManager.MESSAGE_FAILURE_REASON_NETWORK_NOT_AVAILABLE);
    }

    @SmallTest
    @Test
    public void testDelegateConnectionCloseSession() throws Exception {
        MessageTransportStateTracker tracker = new MessageTransportStateTracker(TEST_SUB_ID,
                Runnable::run, mDelegateMessageCallback);
        tracker.openTransport(mISipDelegate, Collections.emptySet());
        tracker.getDelegateConnection().cleanupSession("testCallId");
        verify(mISipDelegate).cleanupSession("testCallId");
    }

    @SmallTest
    @Test
    public void testDelegateOnMessageReceived() throws Exception {
        MessageTransportStateTracker tracker = new MessageTransportStateTracker(TEST_SUB_ID,
                Runnable::run, mDelegateMessageCallback);
        tracker.openTransport(mISipDelegate, Collections.emptySet());

        tracker.getMessageCallback().onMessageReceived(TEST_MESSAGE);
        verify(mDelegateMessageCallback).onMessageReceived(TEST_MESSAGE);

        doThrow(new RemoteException()).when(mDelegateMessageCallback).onMessageReceived(any());
        tracker.getMessageCallback().onMessageReceived(TEST_MESSAGE);
        verify(mISipDelegate).notifyMessageReceiveError(any(),
                eq(SipDelegateManager.MESSAGE_FAILURE_REASON_DELEGATE_DEAD));
    }

    @SmallTest
    @Test
    public void testDelegateOnMessageReceivedClosedGracefully() throws Exception {
        PendingExecutor executor = new PendingExecutor();
        MessageTransportStateTracker tracker = new MessageTransportStateTracker(TEST_SUB_ID,
                executor, mDelegateMessageCallback);
        tracker.openTransport(mISipDelegate, Collections.emptySet());

        tracker.getMessageCallback().onMessageReceived(TEST_MESSAGE);
        executor.executePending();
        verify(mDelegateMessageCallback).onMessageReceived(TEST_MESSAGE);

        tracker.getMessageCallback().onMessageReceived(TEST_MESSAGE);
        tracker.closeGracefully(
                SipDelegateManager.MESSAGE_FAILURE_REASON_INTERNAL_DELEGATE_STATE_TRANSITION,
                SipDelegateManager.MESSAGE_FAILURE_REASON_DELEGATE_CLOSED,
                mMockCloseConsumer);
        executor.executePending();
        // Incoming SIP message should not be blocked by closeGracefully
        verify(mDelegateMessageCallback, times(2)).onMessageReceived(TEST_MESSAGE);
    }

    @SmallTest
    @Test
    public void testDelegateOnMessageSent() throws Exception {
        MessageTransportStateTracker tracker = new MessageTransportStateTracker(TEST_SUB_ID,
                Runnable::run, mDelegateMessageCallback);
        tracker.openTransport(mISipDelegate, Collections.emptySet());
        tracker.getMessageCallback().onMessageSent("z9hG4bK776asdhds");
        verify(mDelegateMessageCallback).onMessageSent("z9hG4bK776asdhds");
    }

    @SmallTest
    @Test
    public void testDelegateonMessageSendFailure() throws Exception {
        MessageTransportStateTracker tracker = new MessageTransportStateTracker(TEST_SUB_ID,
                Runnable::run, mDelegateMessageCallback);
        tracker.openTransport(mISipDelegate, Collections.emptySet());
        tracker.getMessageCallback().onMessageSendFailure("z9hG4bK776asdhds",
                SipDelegateManager.MESSAGE_FAILURE_REASON_NETWORK_NOT_AVAILABLE);
        verify(mDelegateMessageCallback).onMessageSendFailure("z9hG4bK776asdhds",
                SipDelegateManager.MESSAGE_FAILURE_REASON_NETWORK_NOT_AVAILABLE);
    }
}
