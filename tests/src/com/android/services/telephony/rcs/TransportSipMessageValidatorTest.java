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

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import android.net.InetAddresses;
import android.telephony.ims.DelegateRegistrationState;
import android.telephony.ims.SipDelegateConfiguration;
import android.telephony.ims.SipDelegateManager;
import android.telephony.ims.SipMessage;

import androidx.test.runner.AndroidJUnit4;

import com.android.TelephonyTestBase;
import com.android.TestExecutorService;
import com.android.services.telephony.rcs.validator.IncomingTransportStateValidator;
import com.android.services.telephony.rcs.validator.OutgoingTransportStateValidator;
import com.android.services.telephony.rcs.validator.SipMessageValidator;
import com.android.services.telephony.rcs.validator.ValidationResult;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.concurrent.ScheduledExecutorService;

@RunWith(AndroidJUnit4.class)
public class TransportSipMessageValidatorTest extends TelephonyTestBase {
    private static final int TEST_SUB_ID = 1;
    private static final int TEST_CONFIG_VERSION = 1;
    private static final SipMessage TEST_MESSAGE = new SipMessage(
            "INVITE sip:bob@biloxi.com SIP/2.0",
            // Typical Via
            "Via: SIP/2.0/UDP pc33.atlanta.com;branch=z9hG4bK776asdhds\n"
                    + "Max-Forwards: 70\n"
                    + "To: Bob <sip:bob@biloxi.com>\n"
                    + "From: Alice <sip:alice@atlanta.com>;tag=1928301774\n"
                    + "Call-ID: a84b4c76e66710@pc33.atlanta.com\n"
                    + "CSeq: 314159 INVITE\n"
                    + "Contact: <sip:alice@pc33.atlanta.com>\n"
                    + "Content-Type: application/sdp\n"
                    + "Content-Length: 142",
            new byte[0]);

    @Mock
    private IncomingTransportStateValidator mIncomingStateValidator;
    @Mock
    private OutgoingTransportStateValidator mOutgoingStateValidator;
    @Mock
    private SipMessageValidator mStatelessValidator;

    @Before
    public void setUp() throws Exception {
        super.setUp();
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @Test
    public void testTransportOpening() {
        TestExecutorService executor = new TestExecutorService();
        TransportSipMessageValidator tracker = getTestTracker(executor);
        tracker.onTransportOpened(Collections.emptySet(), Collections.emptySet());
        verify(mOutgoingStateValidator).open();
        verify(mIncomingStateValidator).open();
        // Incoming messages are already verified
        assertTrue(isIncomingTransportOpen(tracker));
        // IMS config and registration state needs to be sent before outgoing messages can be
        // verified.
        assertFalse(isOutgoingTransportOpen(tracker));
        tracker.onConfigurationChanged(getConfigBuilder(TEST_CONFIG_VERSION).build());
        // Incoming messages are already verified
        assertTrue(isIncomingTransportOpen(tracker));
        assertFalse(isOutgoingTransportOpen(tracker));
        tracker.onRegistrationStateChanged((ignore) -> {}, getTestRegistrationState());
        // Config set + IMS reg state sent, transport is now open.
        assertTrue(isIncomingTransportOpen(tracker));
        assertTrue(isOutgoingTransportOpen(tracker));
    }

    @Test
    public void testTransportOpenConfigChange() {
        TestExecutorService executor = new TestExecutorService();
        TransportSipMessageValidator tracker = getTestTracker(executor);
        tracker.onTransportOpened(Collections.emptySet(), Collections.emptySet());
        tracker.onConfigurationChanged(getConfigBuilder(TEST_CONFIG_VERSION).build());
        tracker.onRegistrationStateChanged((ignore) -> {}, getTestRegistrationState());
        // Config set + IMS reg state sent, transport is now open.
        assertTrue(isIncomingTransportOpen(tracker));
        assertTrue(isOutgoingTransportOpen(tracker));

        // Update IMS config version and send a message with an outdated version.
        tracker.onConfigurationChanged(getConfigBuilder(TEST_CONFIG_VERSION + 1).build());
        assertEquals(SipDelegateManager.MESSAGE_FAILURE_REASON_STALE_IMS_CONFIGURATION,
                verifyOutgoingTransportClosed(tracker));
    }

    @Test
    public void testTransportClosingGracefully() {
        TestExecutorService executor = new TestExecutorService(true /*wait*/);
        TransportSipMessageValidator tracker = getTestTracker(executor);
        tracker.onTransportOpened(Collections.emptySet(), Collections.emptySet());
        tracker.onConfigurationChanged(getConfigBuilder(TEST_CONFIG_VERSION).build());
        tracker.onRegistrationStateChanged((ignore) -> {}, getTestRegistrationState());
        // Config set + IMS reg state sent, transport is now open.
        assertTrue(isIncomingTransportOpen(tracker));
        assertTrue(isOutgoingTransportOpen(tracker));

        tracker.closeSessionsGracefully((ignore) -> {},
                SipDelegateManager.MESSAGE_FAILURE_REASON_INTERNAL_DELEGATE_STATE_TRANSITION,
                SipDelegateManager.MESSAGE_FAILURE_REASON_DELEGATE_CLOSED);

        // Before executor executes, outgoing messages will be restricted.
        assertTrue(isIncomingTransportOpen(tracker));
        verify(mOutgoingStateValidator).restrict(anyInt());
        executor.executePending();
        // After Executor executes, all messages will be rejected.
        verify(mOutgoingStateValidator).close(anyInt());
        verify(mIncomingStateValidator).close(anyInt());
    }

    @Test
    public void testTransportClosingForcefully() {
        TestExecutorService executor = new TestExecutorService();
        TransportSipMessageValidator tracker = getTestTracker(executor);
        tracker.onTransportOpened(Collections.emptySet(), Collections.emptySet());
        tracker.onConfigurationChanged(getConfigBuilder(TEST_CONFIG_VERSION).build());
        tracker.onRegistrationStateChanged((ignore) -> {}, getTestRegistrationState());
        // Config set + IMS reg state sent, transport is now open.
        assertTrue(isIncomingTransportOpen(tracker));
        assertTrue(isOutgoingTransportOpen(tracker));

        tracker.closeSessionsForcefully(
                SipDelegateManager.MESSAGE_FAILURE_REASON_DELEGATE_CLOSED);

        // All messages will be rejected.
        verify(mOutgoingStateValidator).close(anyInt());
        verify(mIncomingStateValidator).close(anyInt());
    }

    private SipDelegateConfiguration.Builder getConfigBuilder(int version) {
        InetSocketAddress localAddr = new InetSocketAddress(
                InetAddresses.parseNumericAddress("1.1.1.1"), 80);
        InetSocketAddress serverAddr = new InetSocketAddress(
                InetAddresses.parseNumericAddress("2.2.2.2"), 81);
        return new SipDelegateConfiguration.Builder(version,
                SipDelegateConfiguration.SIP_TRANSPORT_TCP, localAddr, serverAddr);
    }


    private boolean isIncomingTransportOpen(TransportSipMessageValidator tracker) {
        return tracker.verifyIncomingMessage(TEST_MESSAGE).isValidated;
    }

    private boolean isOutgoingTransportOpen(TransportSipMessageValidator tracker) {
        return tracker.verifyOutgoingMessage(TEST_MESSAGE, TEST_CONFIG_VERSION).isValidated;
    }

    private int verifyOutgoingTransportClosed(TransportSipMessageValidator tracker) {
        ValidationResult result = tracker.verifyOutgoingMessage(TEST_MESSAGE, TEST_CONFIG_VERSION);
        assertFalse(result.isValidated);
        return result.restrictedReason;
    }

    private DelegateRegistrationState getTestRegistrationState() {
        return new DelegateRegistrationState.Builder().build();
    }

    private TransportSipMessageValidator getTestTracker(ScheduledExecutorService executor) {
        doReturn(ValidationResult.SUCCESS).when(mStatelessValidator).validate(any());
        doReturn(mStatelessValidator).when(mStatelessValidator).andThen(any());
        doReturn(ValidationResult.SUCCESS).when(mOutgoingStateValidator).validate(any());
        // recreate chain for mocked instances.
        doReturn(mStatelessValidator).when(mOutgoingStateValidator).andThen(any());
        doReturn(ValidationResult.SUCCESS).when(mIncomingStateValidator).validate(any());
        doReturn(mIncomingStateValidator).when(mIncomingStateValidator).andThen(any());
        return new TransportSipMessageValidator(TEST_SUB_ID, executor, mOutgoingStateValidator,
                mIncomingStateValidator, mStatelessValidator);
    }
}
