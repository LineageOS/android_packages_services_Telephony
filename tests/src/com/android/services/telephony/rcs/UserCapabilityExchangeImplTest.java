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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.Uri;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.RemoteCallbackList;
import android.telephony.ims.ImsManager;
import android.telephony.ims.ImsMmTelManager;
import android.telephony.ims.RcsContactPresenceTuple;
import android.telephony.ims.RcsContactPresenceTuple.ServiceCapabilities;
import android.telephony.ims.RcsContactUceCapability;
import android.telephony.ims.RcsContactUceCapability.PresenceBuilder;
import android.telephony.ims.RegistrationManager;
import android.telephony.ims.aidl.IRcsUceControllerCallback;
import android.telephony.ims.aidl.IRcsUcePublishStateCallback;
import android.telephony.ims.stub.RcsCapabilityExchange;
import android.telephony.ims.stub.RcsPresenceExchangeImplBase;

import androidx.test.runner.AndroidJUnit4;

import com.android.TelephonyTestBase;
import com.android.ims.RcsFeatureManager;
import com.android.ims.RcsFeatureManager.RcsFeatureCallbacks;
import com.android.ims.ResultCode;
import com.android.service.ims.presence.PresenceBase;
import com.android.service.ims.presence.PresencePublication;
import com.android.service.ims.presence.PresencePublisher;
import com.android.service.ims.presence.PresenceSubscriber;
import com.android.service.ims.presence.SubscribePublisher;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

@RunWith(AndroidJUnit4.class)
public class UserCapabilityExchangeImplTest extends TelephonyTestBase {

    private int  mSlotId = 0;
    private int mSubId = 1;
    private int mUpdatedSubId = 2;

    @Captor ArgumentCaptor<IRcsUcePublishStateCallback> mPublishStateCallbacksCaptor;

    @Mock PresencePublication mPresencePublication;
    @Mock PresenceSubscriber mPresenceSubscriber;
    @Mock RcsFeatureManager mRcsFeatureManager;
    @Mock ImsMmTelManager mImsMmTelManager;
    @Mock RemoteCallbackList<IRcsUcePublishStateCallback> mPublishStateCallbacks;

    private Looper mLooper;

    @Before
    public void setUp() throws Exception {
        super.setUp();

        ImsManager imsManager =
                (ImsManager) mContext.getSystemService(Context.TELEPHONY_IMS_SERVICE);
        when(imsManager.getImsMmTelManager(mSubId)).thenReturn(mImsMmTelManager);
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();

        if (mLooper != null) {
            mLooper.quit();
            mLooper = null;
        }
    }

    @Test
    public void testServiceConnected() throws Exception {
        UserCapabilityExchangeImpl uceImpl = createUserCapabilityExchangeImpl();
        uceImpl.onRcsConnected(mRcsFeatureManager);

        verify(mRcsFeatureManager).addFeatureListenerCallback(any(RcsFeatureCallbacks.class));
        verify(mPresencePublication).updatePresencePublisher(any(PresencePublisher.class));
        verify(mPresenceSubscriber).updatePresenceSubscriber(any(SubscribePublisher.class));
    }

    @Test
    public void testServiceDisconnected() throws Exception {
        UserCapabilityExchangeImpl uceImpl = createUserCapabilityExchangeImpl();
        uceImpl.onRcsDisconnected();

        verify(mPresencePublication).removePresencePublisher();
        verify(mPresenceSubscriber).removePresenceSubscriber();
    }

    @Test
    public void testSubscriptionUpdated() throws Exception {
        UserCapabilityExchangeImpl uceImpl = createUserCapabilityExchangeImpl();
        uceImpl.onAssociatedSubscriptionUpdated(mUpdatedSubId);

        verify(mImsMmTelManager).registerImsRegistrationCallback(any(Executor.class),
                any(RegistrationManager.RegistrationCallback.class));
        verify(mImsMmTelManager).registerMmTelCapabilityCallback(any(Executor.class),
                any(ImsMmTelManager.CapabilityCallback.class));
        verify(mPresencePublication).handleAssociatedSubscriptionChanged(mUpdatedSubId);
        verify(mPresenceSubscriber).handleAssociatedSubscriptionChanged(mUpdatedSubId);
    }

    @Test
    public void testUcePublishStateRetrieval() throws Exception {
        UserCapabilityExchangeImpl uceImpl = createUserCapabilityExchangeImpl();
        uceImpl.getUcePublishState();

        verify(mPresencePublication).getPublishState();
    }

    @Test
    public void testRegisterPublishStateCallbacks() throws Exception {
        UserCapabilityExchangeImpl uceImpl = createUserCapabilityExchangeImpl();
        uceImpl.registerPublishStateCallback(any(IRcsUcePublishStateCallback.class));
        verify(mPublishStateCallbacks).register(mPublishStateCallbacksCaptor.capture());
    }

    @Test
    public void testOnNotifyUpdateCapabilities() throws Exception {
        UserCapabilityExchangeImpl uceImpl = createUserCapabilityExchangeImpl();
        uceImpl.onRcsConnected(mRcsFeatureManager);

        int triggerType = RcsPresenceExchangeImplBase.CAPABILITY_UPDATE_TRIGGER_MOVE_TO_IWLAN;
        uceImpl.mRcsFeatureCallback.onNotifyUpdateCapabilities(triggerType);
        waitForMs(1000);

        verify(mPresencePublication).onStackPublishRequested(triggerType);
    }

    @Test
    public void testRequestPublicationWithSuccessfulResponse() throws Exception {
        int taskId = 1;
        int sipResponse = 200;
        Uri contact = Uri.fromParts("sip", "test", null);
        RcsContactUceCapability capability = getRcsContactUceCapability(contact);

        UserCapabilityExchangeImpl uceImpl = createUserCapabilityExchangeImpl();
        uceImpl.onRcsConnected(mRcsFeatureManager);

        doAnswer(invocation -> {
            uceImpl.mRcsFeatureCallback.onCommandUpdate(RcsCapabilityExchange.COMMAND_CODE_SUCCESS,
                    taskId);
            uceImpl.mRcsFeatureCallback.onNetworkResponse(sipResponse, null, taskId);
            return null;
        }).when(mRcsFeatureManager).requestPublication(capability, taskId);

        // Request publication
        int result = uceImpl.requestPublication(capability, contact.toString(), taskId);

        assertEquals(ResultCode.SUCCESS, result);
        verify(mPresencePublication).onCommandStatusUpdated(taskId, taskId, ResultCode.SUCCESS);
        verify(mPresencePublication).onSipResponse(taskId, sipResponse, null);
    }

    @Test
    public void testRequestPublicationWithFailedResponse() throws Exception {
        int taskId = 1;
        Uri contact = Uri.fromParts("sip", "test", null);
        RcsContactUceCapability capability = getRcsContactUceCapability(contact);

        UserCapabilityExchangeImpl uceImpl = createUserCapabilityExchangeImpl();
        uceImpl.onRcsConnected(mRcsFeatureManager);

        doAnswer(invocation -> {
            uceImpl.mRcsFeatureCallback.onCommandUpdate(
                    RcsCapabilityExchange.COMMAND_CODE_GENERIC_FAILURE, taskId);
            return null;
        }).when(mRcsFeatureManager).requestPublication(capability, taskId);

        // Request publication
        int result = uceImpl.requestPublication(capability, contact.toString(), taskId);

        assertEquals(ResultCode.SUCCESS, result);
        verify(mPresencePublication).onCommandStatusUpdated(taskId, taskId,
                ResultCode.PUBLISH_GENERIC_FAILURE);
    }

    private RcsContactUceCapability getRcsContactUceCapability(Uri contact) {
        ServiceCapabilities.Builder servCapsBuilder = new ServiceCapabilities.Builder(true, true);
        servCapsBuilder.addSupportedDuplexMode(ServiceCapabilities.DUPLEX_MODE_FULL);

        RcsContactPresenceTuple.Builder tupleBuilder = new RcsContactPresenceTuple.Builder(
                RcsContactPresenceTuple.TUPLE_BASIC_STATUS_OPEN,
                RcsContactPresenceTuple.SERVICE_ID_MMTEL, "1.0");
        tupleBuilder.addContactUri(contact).addServiceCapabilities(servCapsBuilder.build());

        PresenceBuilder presenceBuilder = new PresenceBuilder(contact,
                RcsContactUceCapability.SOURCE_TYPE_CACHED,
                RcsContactUceCapability.REQUEST_RESULT_FOUND);
        presenceBuilder.addCapabilityTuple(tupleBuilder.build());
        return presenceBuilder.build();
    }

    @Test
    public void testRequestCapability() throws Exception {
        int taskId = 1;
        int sipResponse = 200;
        List<RcsContactUceCapability> infos = new ArrayList<>();
        List<Uri> contacts = Arrays.asList(Uri.fromParts("sip", "00000", null));
        IRcsUceControllerCallback callback = Mockito.mock(IRcsUceControllerCallback.class);

        UserCapabilityExchangeImpl uceImpl = createUserCapabilityExchangeImpl();
        uceImpl.onRcsConnected(mRcsFeatureManager);

        when(mPresenceSubscriber.requestCapability(anyList(), any())).thenReturn(taskId);

        doAnswer(invocation -> {
            uceImpl.mRcsFeatureCallback.onCommandUpdate(RcsCapabilityExchange.COMMAND_CODE_SUCCESS,
                    taskId);
            uceImpl.mRcsFeatureCallback.onNetworkResponse(sipResponse, null, taskId);
            uceImpl.mRcsFeatureCallback.onCapabilityRequestResponsePresence(infos, taskId);
            return null;
        }).when(mRcsFeatureManager).requestCapabilities(anyList(), anyInt());

        uceImpl.requestCapabilities(contacts, callback);
        uceImpl.requestCapability(new String[] {"00000"}, taskId);

        verify(mPresenceSubscriber).onCommandStatusUpdated(taskId, taskId, ResultCode.SUCCESS);
        verify(mPresenceSubscriber).onSipResponse(taskId, sipResponse, null);
        verify(mPresenceSubscriber).updatePresences(taskId, infos, true, null);
    }

    @Test
    public void testUpdatePublisherState() throws Exception {
        IRcsUcePublishStateCallback callback = Mockito.mock(IRcsUcePublishStateCallback.class);
        doAnswer(invocation -> {
            callback.onPublishStateChanged(anyInt());
            return null;
        }).when(mPublishStateCallbacks).broadcast(any());

        UserCapabilityExchangeImpl uceImpl = createUserCapabilityExchangeImpl();
        uceImpl.onRcsConnected(mRcsFeatureManager);
        uceImpl.registerPublishStateCallback(callback);
        uceImpl.updatePublisherState(PresenceBase.PUBLISH_STATE_200_OK);

        assertEquals(PresenceBase.PUBLISH_STATE_200_OK, uceImpl.getPublisherState());
        verify(callback).onPublishStateChanged(anyInt());
    }

    @Test
    public void testUnpublish() throws Exception {
        IRcsUcePublishStateCallback callback = Mockito.mock(IRcsUcePublishStateCallback.class);
        doAnswer(invocation -> {
            callback.onPublishStateChanged(anyInt());
            return null;
        }).when(mPublishStateCallbacks).broadcast(any());

        UserCapabilityExchangeImpl uceImpl = createUserCapabilityExchangeImpl();
        uceImpl.onRcsConnected(mRcsFeatureManager);
        uceImpl.mRcsFeatureCallback.onUnpublish();
        waitForMs(1000);

        verify(mPresencePublication).setPublishState(PresenceBase.PUBLISH_STATE_NOT_PUBLISHED);
    }

    private UserCapabilityExchangeImpl createUserCapabilityExchangeImpl() throws Exception {
        HandlerThread handlerThread = new HandlerThread("UceImplHandlerThread");
        handlerThread.start();
        mLooper = handlerThread.getLooper();
        UserCapabilityExchangeImpl uceImpl = new UserCapabilityExchangeImpl(mContext, mSlotId,
                mSubId, mLooper, mPresencePublication, mPresenceSubscriber,
                mPublishStateCallbacks);
        verify(mPresencePublication).handleAssociatedSubscriptionChanged(1);
        verify(mPresenceSubscriber).handleAssociatedSubscriptionChanged(1);
        waitForHandlerAction(uceImpl.getHandler(), 1000);
        verify(mImsMmTelManager, atLeast(1)).registerImsRegistrationCallback(
                any(Executor.class), any(RegistrationManager.RegistrationCallback.class));
        verify(mContext).registerReceiver(any(), any());
        return uceImpl;
    }
}
