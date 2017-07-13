/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.phone;

import android.content.Intent;
import android.os.Handler;
import android.os.Message;
import android.support.test.runner.AndroidJUnit4;

import com.android.TelephonyTestBase;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.TelephonyIntents;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;

import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@RunWith(AndroidJUnit4.class)
public class PhoneGlobalsTest extends TelephonyTestBase {

    private Phone mPhone = PhoneFactory.getDefaultPhone();

    private PhoneGlobals mPhoneGlobals = PhoneGlobals.getInstance();

    private Handler mHandler = mock(Handler.class);

    private static final int EVENT_DATA_ROAMING_DISCONNECTED = 10;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        Field handler = PhoneGlobals.class.getDeclaredField("mHandler");
        handler.setAccessible(true);
        handler.set(mPhoneGlobals, mHandler);
    }

    @Test
    public void testDataDisconnectedNotification() {
        mPhone.setDataRoamingEnabled(false);
        // Test no notification sent out when data is disabled, data raoming enabled and data
        // disconnected because of roaming.
        mPhone.setDataEnabled(false);
        Intent intent = new Intent(TelephonyIntents.ACTION_ANY_DATA_CONNECTION_STATE_CHANGED);
        intent.putExtra(PhoneConstants.DATA_APN_TYPE_KEY,
                PhoneConstants.APN_TYPE_DEFAULT);
        intent.putExtra(PhoneConstants.STATE_KEY, PhoneConstants.DataState.DISCONNECTED.name());
        intent.putExtra(PhoneConstants.STATE_CHANGE_REASON_KEY, Phone.REASON_ROAMING_ON);
        mContext.sendBroadcast(intent);

        waitForMs(300);

        ArgumentCaptor<Message> messageArgumentCaptor = ArgumentCaptor.forClass(Message.class);
        verify(mHandler, atLeast(0)).sendMessageAtTime(any(Message.class), anyLong());
        boolean flag = true;
        for (Message msg : messageArgumentCaptor.getAllValues()) {
            if (msg.what == EVENT_DATA_ROAMING_DISCONNECTED) {
                flag = false;
            }
        }
        assertTrue(flag);


        // Test notification sent out when data is enabled, data raoming enabled and data
        // disconnected because of roaming.
        mPhone.setDataEnabled(true);
        mContext.sendBroadcast(intent);

        waitForMs(300);


        verify(mHandler, atLeast(1)).sendMessageAtTime(messageArgumentCaptor.capture(), anyLong());
        flag = false;
        for (Message msg : messageArgumentCaptor.getAllValues()) {
            if (msg.what == EVENT_DATA_ROAMING_DISCONNECTED) {
                flag = true;
            }
        }
        assertTrue(flag);
    }
}
