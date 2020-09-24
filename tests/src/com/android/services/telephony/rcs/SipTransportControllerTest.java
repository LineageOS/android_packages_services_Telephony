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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import android.telephony.ims.ImsException;
import android.telephony.ims.aidl.ISipTransport;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.TelephonyTestBase;
import com.android.TestExecutorService;
import com.android.ims.RcsFeatureManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

@RunWith(AndroidJUnit4.class)
public class SipTransportControllerTest extends TelephonyTestBase {

    @Mock private RcsFeatureManager mRcsManager;
    @Mock private ISipTransport mSipTransport;

    private final TestExecutorService mExecutorService = new TestExecutorService();

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
    public void isSupportedRcsNotConnected() {
        SipTransportController controller = createController(0 /*slotId*/, 0 /*subId*/);
        try {
            controller.isSupported(0 /*subId*/);
            fail();
        } catch (ImsException e) {
            assertEquals(ImsException.CODE_ERROR_SERVICE_UNAVAILABLE, e.getCode());
        }
    }

    @SmallTest
    @Test
    public void isSupportedInvalidSubId() {
        SipTransportController controller = createController(0 /*slotId*/, 0 /*subId*/);
        try {
            controller.isSupported(1 /*subId*/);
            fail();
        } catch (ImsException e) {
            assertEquals(ImsException.CODE_ERROR_INVALID_SUBSCRIPTION, e.getCode());
        }
    }

    @SmallTest
    @Test
    public void isSupportedSubIdChanged() {
        SipTransportController controller = createController(0 /*slotId*/, 0 /*subId*/);
        controller.onAssociatedSubscriptionUpdated(1 /*subId*/);
        try {
            controller.isSupported(0 /*subId*/);
            fail();
        } catch (ImsException e) {
            assertEquals(ImsException.CODE_ERROR_INVALID_SUBSCRIPTION, e.getCode());
        }
    }

    @SmallTest
    @Test
    public void isSupportedSipTransportAvailableRcsConnected() throws Exception {
        SipTransportController controller = createController(0 /*slotId*/, 0 /*subId*/);
        doReturn(mSipTransport).when(mRcsManager).getSipTransport();
        controller.onRcsConnected(mRcsManager);
        try {
            assertTrue(controller.isSupported(0 /*subId*/));
        } catch (ImsException e) {
            fail();
        }
    }

    @SmallTest
    @Test
    public void isSupportedSipTransportNotAvailableRcsDisconnected() throws Exception {
        SipTransportController controller = createController(0 /*slotId*/, 0 /*subId*/);
        doReturn(mSipTransport).when(mRcsManager).getSipTransport();
        controller.onRcsConnected(mRcsManager);
        controller.onRcsDisconnected();
        try {
            controller.isSupported(0 /*subId*/);
            fail();
        } catch (ImsException e) {
            assertEquals(ImsException.CODE_ERROR_SERVICE_UNAVAILABLE, e.getCode());
        }
    }

    @SmallTest
    @Test
    public void isSupportedSipTransportNotAvailableRcsConnected() throws Exception {
        SipTransportController controller = createController(0 /*slotId*/, 0 /*subId*/);
        doReturn(null).when(mRcsManager).getSipTransport();
        controller.onRcsConnected(mRcsManager);
        try {
            assertFalse(controller.isSupported(0 /*subId*/));
        } catch (ImsException e) {
            fail();
        }
    }

    @SmallTest
    @Test
    public void isSupportedImsServiceNotAvailableRcsConnected() throws Exception {
        SipTransportController controller = createController(0 /*slotId*/, 0 /*subId*/);
        doThrow(new ImsException("", ImsException.CODE_ERROR_SERVICE_UNAVAILABLE))
                .when(mRcsManager).getSipTransport();
        controller.onRcsConnected(mRcsManager);
        try {
            controller.isSupported(0 /*subId*/);
            fail();
        } catch (ImsException e) {
            assertEquals(ImsException.CODE_ERROR_SERVICE_UNAVAILABLE, e.getCode());
        }
    }

    private SipTransportController createController(int slotId, int subId) {
        return new SipTransportController(mContext, slotId, subId, mExecutorService);
    }
}
