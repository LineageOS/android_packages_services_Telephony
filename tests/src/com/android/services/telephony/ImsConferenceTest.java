/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.services.telephony;

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.times;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.net.Uri;
import android.os.Looper;
import android.telecom.ConferenceParticipant;
import android.telecom.Connection;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.internal.telephony.PhoneConstants;

import org.junit.Before;
import org.junit.Test;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.Arrays;

public class ImsConferenceTest {
    @Mock
    private TelephonyConnectionServiceProxy mMockTelephonyConnectionServiceProxy;

    @Mock
    private TelecomAccountRegistry mMockTelecomAccountRegistry;

    @Mock
    private com.android.internal.telephony.Connection mOriginalConnection;

    private TestTelephonyConnection mConferenceHost;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
        mConferenceHost = new TestTelephonyConnection();
        mConferenceHost.setManageImsConferenceCallSupported(true);
    }

    @Test
    @SmallTest
    public void testSinglePartyEmulation() {
        ImsConference imsConference = new ImsConference(mMockTelecomAccountRegistry,
                mMockTelephonyConnectionServiceProxy, mConferenceHost,
                null /* phoneAccountHandle */, () -> true /* featureFlagProxy */);

        ConferenceParticipant participant1 = new ConferenceParticipant(
                Uri.parse("tel:6505551212"),
                "A",
                Uri.parse("sip:6505551212@testims.com"),
                Connection.STATE_ACTIVE);
        ConferenceParticipant participant2 = new ConferenceParticipant(
                Uri.parse("tel:6505551213"),
                "A",
                Uri.parse("sip:6505551213@testims.com"),
                Connection.STATE_ACTIVE);
        imsConference.handleConferenceParticipantsUpdate(mConferenceHost,
                Arrays.asList(participant1, participant2));
        assertEquals(2, imsConference.getNumberOfParticipants());

        // Because we're pretending its a single party, there should be no participants any more.
        imsConference.handleConferenceParticipantsUpdate(mConferenceHost,
                Arrays.asList(participant1));
        assertEquals(0, imsConference.getNumberOfParticipants());

        // Back to 2!
        imsConference.handleConferenceParticipantsUpdate(mConferenceHost,
                Arrays.asList(participant1, participant2));
        assertEquals(2, imsConference.getNumberOfParticipants());
    }

    @Test
    @SmallTest
    public void testNormalConference() {
        ImsConference imsConference = new ImsConference(mMockTelecomAccountRegistry,
                mMockTelephonyConnectionServiceProxy, mConferenceHost,
                null /* phoneAccountHandle */, () -> false /* featureFlagProxy */);

        ConferenceParticipant participant1 = new ConferenceParticipant(
                Uri.parse("tel:6505551212"),
                "A",
                Uri.parse("sip:6505551212@testims.com"),
                Connection.STATE_ACTIVE);
        ConferenceParticipant participant2 = new ConferenceParticipant(
                Uri.parse("tel:6505551213"),
                "A",
                Uri.parse("sip:6505551213@testims.com"),
                Connection.STATE_ACTIVE);
        imsConference.handleConferenceParticipantsUpdate(mConferenceHost,
                Arrays.asList(participant1, participant2));
        assertEquals(2, imsConference.getNumberOfParticipants());

        // Not emulating single party; should still be one.
        imsConference.handleConferenceParticipantsUpdate(mConferenceHost,
                Arrays.asList(participant1));
        assertEquals(1, imsConference.getNumberOfParticipants());
    }
}
