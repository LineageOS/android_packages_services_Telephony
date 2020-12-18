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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.role.RoleManager;
import android.os.IBinder;
import android.os.UserHandle;
import android.telephony.ims.DelegateRequest;
import android.telephony.ims.FeatureTagState;
import android.telephony.ims.ImsException;
import android.telephony.ims.SipDelegateManager;
import android.telephony.ims.aidl.IImsRegistration;
import android.telephony.ims.aidl.ISipDelegate;
import android.telephony.ims.aidl.ISipDelegateConnectionStateCallback;
import android.telephony.ims.aidl.ISipDelegateMessageCallback;
import android.telephony.ims.aidl.ISipTransport;
import android.util.ArraySet;
import android.util.Pair;

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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@RunWith(AndroidJUnit4.class)
public class SipTransportControllerTest extends TelephonyTestBase {
    private static final int TEST_SUB_ID = 1;
    private static final String TEST_PACKAGE_NAME = "com.test_pkg";
    private static final String TEST_PACKAGE_NAME_2 = "com.test_pkg2";
    private static final int TIMEOUT_MS = 200;
    private static final int THROTTLE_MS = 50;

    private class SipDelegateControllerContainer {
        public final int subId;
        public final String packageName;
        public final DelegateRequest delegateRequest;
        public final SipDelegateController delegateController;
        public final ISipDelegate mMockDelegate;
        public final IBinder mMockDelegateBinder;

        SipDelegateControllerContainer(int id, String name, DelegateRequest request) {
            delegateController = mock(SipDelegateController.class);
            mMockDelegate = mock(ISipDelegate.class);
            mMockDelegateBinder = mock(IBinder.class);
            doReturn(mMockDelegateBinder).when(mMockDelegate).asBinder();
            doReturn(name).when(delegateController).getPackageName();
            doReturn(request).when(delegateController).getInitialRequest();
            doReturn(mMockDelegate).when(delegateController).getSipDelegateInterface();
            subId = id;
            packageName = name;
            delegateRequest = request;
        }
    }

    @Mock private RcsFeatureManager mRcsManager;
    @Mock private ISipTransport mSipTransport;
    @Mock private IImsRegistration mImsRegistration;
    @Mock private ISipDelegateConnectionStateCallback mMockStateCallback;
    @Mock private ISipDelegateMessageCallback mMockMessageCallback;
    @Mock private SipTransportController.SipDelegateControllerFactory
            mMockDelegateControllerFactory;
    @Mock private SipTransportController.RoleManagerAdapter mMockRoleManager;

    private ScheduledExecutorService mExecutorService = null;
    private final ArrayList<SipDelegateControllerContainer> mMockControllers = new ArrayList<>();
    private final ArrayList<String> mSmsPackageName = new ArrayList<>(1);

    @Before
    public void setUp() throws Exception {
        super.setUp();
        doReturn(mSmsPackageName).when(mMockRoleManager).getRoleHolders(RoleManager.ROLE_SMS);
        doReturn(mImsRegistration).when(mRcsManager).getImsRegistration();
        mSmsPackageName.add(TEST_PACKAGE_NAME);
        doAnswer(invocation -> {
            Integer subId = invocation.getArgument(0);
            String packageName = invocation.getArgument(2);
            DelegateRequest request = invocation.getArgument(1);
            SipDelegateController c = getMockDelegateController(subId, packageName, request);
            assertNotNull("create called with no corresponding controller set up", c);
            return c;
        }).when(mMockDelegateControllerFactory).create(anyInt(), any(), anyString(), any(), any(),
                any(), any(), any());
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();
        boolean isShutdown = mExecutorService == null || mExecutorService.isShutdown();
        if (!isShutdown) {
            mExecutorService.shutdownNow();
        }
    }

    @SmallTest
    @Test
    public void isSupportedRcsNotConnected() {
        SipTransportController controller = createController(new TestExecutorService());
        try {
            controller.isSupported(TEST_SUB_ID);
            fail();
        } catch (ImsException e) {
            assertEquals(ImsException.CODE_ERROR_SERVICE_UNAVAILABLE, e.getCode());
        }
    }

    @SmallTest
    @Test
    public void isSupportedInvalidSubId() {
        SipTransportController controller = createController(new TestExecutorService());
        try {
            controller.isSupported(TEST_SUB_ID + 1);
            fail();
        } catch (ImsException e) {
            assertEquals(ImsException.CODE_ERROR_INVALID_SUBSCRIPTION, e.getCode());
        }
    }

    @SmallTest
    @Test
    public void isSupportedSubIdChanged() {
        SipTransportController controller = createController(new TestExecutorService());
        controller.onAssociatedSubscriptionUpdated(TEST_SUB_ID + 1);
        try {
            controller.isSupported(TEST_SUB_ID);
            fail();
        } catch (ImsException e) {
            assertEquals(ImsException.CODE_ERROR_INVALID_SUBSCRIPTION, e.getCode());
        }
    }

    @SmallTest
    @Test
    public void isSupportedSipTransportAvailableRcsConnected() throws Exception {
        SipTransportController controller = createController(new TestExecutorService());
        doReturn(mSipTransport).when(mRcsManager).getSipTransport();
        controller.onRcsConnected(mRcsManager);
        try {
            assertTrue(controller.isSupported(TEST_SUB_ID));
        } catch (ImsException e) {
            fail();
        }
    }

    @SmallTest
    @Test
    public void isSupportedSipTransportNotAvailableRcsDisconnected() throws Exception {
        SipTransportController controller = createController(new TestExecutorService());
        doReturn(mSipTransport).when(mRcsManager).getSipTransport();
        controller.onRcsConnected(mRcsManager);
        controller.onRcsDisconnected();
        try {
            controller.isSupported(TEST_SUB_ID);
            fail();
        } catch (ImsException e) {
            assertEquals(ImsException.CODE_ERROR_SERVICE_UNAVAILABLE, e.getCode());
        }
    }

    @SmallTest
    @Test
    public void isSupportedSipTransportNotAvailableRcsConnected() throws Exception {
        SipTransportController controller = createController(new TestExecutorService());
        doReturn(null).when(mRcsManager).getSipTransport();
        controller.onRcsConnected(mRcsManager);
        try {
            assertFalse(controller.isSupported(TEST_SUB_ID));
        } catch (ImsException e) {
            fail();
        }
    }

    @SmallTest
    @Test
    public void isSupportedImsServiceNotAvailableRcsConnected() throws Exception {
        SipTransportController controller = createController(new TestExecutorService());
        doThrow(new ImsException("", ImsException.CODE_ERROR_SERVICE_UNAVAILABLE))
                .when(mRcsManager).getSipTransport();
        controller.onRcsConnected(mRcsManager);
        try {
            controller.isSupported(TEST_SUB_ID);
            fail();
        } catch (ImsException e) {
            assertEquals(ImsException.CODE_ERROR_SERVICE_UNAVAILABLE, e.getCode());
        }
    }

    @SmallTest
    @Test
    public void createImsServiceAvailableSubIdIncorrect() throws Exception {
        SipTransportController controller = createController(new TestExecutorService());
        doReturn(mSipTransport).when(mRcsManager).getSipTransport();
        controller.onRcsConnected(mRcsManager);
        try {
            controller.createSipDelegate(TEST_SUB_ID + 1,
                    new DelegateRequest(Collections.emptySet()), TEST_PACKAGE_NAME,
                    mMockStateCallback, mMockMessageCallback);
            fail();
        } catch (ImsException e) {
            assertEquals(ImsException.CODE_ERROR_INVALID_SUBSCRIPTION, e.getCode());
        }
    }

    @SmallTest
    @Test
    public void createImsServiceDoesntSupportTransport() throws Exception {
        SipTransportController controller = createController(new TestExecutorService());
        doReturn(null).when(mRcsManager).getSipTransport();
        controller.onRcsConnected(mRcsManager);
        try {
            controller.createSipDelegate(TEST_SUB_ID,
                    new DelegateRequest(Collections.emptySet()), TEST_PACKAGE_NAME,
                    mMockStateCallback, mMockMessageCallback);
            fail();
        } catch (ImsException e) {
            assertEquals(ImsException.CODE_ERROR_UNSUPPORTED_OPERATION, e.getCode());
        }
    }

    @SmallTest
    @Test
    public void createImsServiceNotAvailable() throws Exception {
        SipTransportController controller = createController(new TestExecutorService());
        doThrow(new ImsException("", ImsException.CODE_ERROR_SERVICE_UNAVAILABLE))
                .when(mRcsManager).getSipTransport();
        // No RCS connected message
        try {
            controller.createSipDelegate(TEST_SUB_ID,
                    new DelegateRequest(Collections.emptySet()), TEST_PACKAGE_NAME,
                    mMockStateCallback, mMockMessageCallback);
            fail();
        } catch (ImsException e) {
            assertEquals(ImsException.CODE_ERROR_SERVICE_UNAVAILABLE, e.getCode());
        }
    }

    @SmallTest
    @Test
    public void basicCreate() throws Exception {
        SipTransportController controller = setupLiveTransportController();

        DelegateRequest r = getBaseDelegateRequest();

        SipDelegateController c = injectMockDelegateController(TEST_PACKAGE_NAME, r);
        createDelegateAndVerify(controller, c, r, r.getFeatureTags(), Collections.emptySet(),
                TEST_PACKAGE_NAME);
        verifyDelegateRegistrationChangedEvent(1 /*times*/, 0 /*waitMs*/);
        triggerFullNetworkRegistrationAndVerify(controller, c);
    }

    @SmallTest
    @Test
    public void basicCreateDestroy() throws Exception {
        SipTransportController controller = setupLiveTransportController();

        DelegateRequest r = getBaseDelegateRequest();
        SipDelegateController c = injectMockDelegateController(TEST_PACKAGE_NAME, r);
        createDelegateAndVerify(controller, c, r, r.getFeatureTags(), Collections.emptySet(),
                TEST_PACKAGE_NAME);
        verifyDelegateRegistrationChangedEvent(1, 0 /*throttle*/);

        destroyDelegateAndVerify(controller, c, false,
                SipDelegateManager.SIP_DELEGATE_DESTROY_REASON_REQUESTED_BY_APP);
        verifyDelegateRegistrationChangedEvent(2 /*times*/, 0 /*waitMs*/);
        triggerFullNetworkRegistrationAndVerifyNever(controller, c);
    }

    @SmallTest
    @Test
    public void testCreateButNotInRole() throws Exception {
        SipTransportController controller = setupLiveTransportController();

        DelegateRequest r = getBaseDelegateRequest();
        Set<FeatureTagState> getDeniedTags = getDeniedTagsForReason(r.getFeatureTags(),
                SipDelegateManager.DENIED_REASON_NOT_ALLOWED);

        // Try to create a SipDelegate for a package that is not the default sms role.
        SipDelegateController c = injectMockDelegateController(TEST_PACKAGE_NAME_2, r);
        createDelegateAndVerify(controller, c, r, Collections.emptySet(), getDeniedTags,
                TEST_PACKAGE_NAME_2);
    }

    @SmallTest
    @Test
    public void createTwoAndDenyOverlappingTags() throws Exception {
        SipTransportController controller = setupLiveTransportController(0 /*reeval*/,
                THROTTLE_MS);

        // First delegate requests RCS message + File transfer
        ArraySet<String> firstDelegate = new ArraySet<>(getBaseDelegateRequest().getFeatureTags());
        firstDelegate.remove(ImsSignallingUtils.GROUP_CHAT_TAG);
        DelegateRequest firstDelegateRequest = new DelegateRequest(firstDelegate);
        SipDelegateController c1 = injectMockDelegateController(TEST_PACKAGE_NAME,
                firstDelegateRequest);
        createDelegateAndVerify(controller, c1, firstDelegateRequest, firstDelegate,
                Collections.emptySet(), TEST_PACKAGE_NAME);
        // there is a delay in the indication to update reg, so it should not happen yet.
        verifyNoDelegateRegistrationChangedEvent();

        // First delegate requests RCS message + Group RCS message. For this delegate, single RCS
        // message should be denied.
        ArraySet<String> secondDelegate = new ArraySet<>(getBaseDelegateRequest().getFeatureTags());
        secondDelegate.remove(ImsSignallingUtils.FILE_TRANSFER_HTTP_TAG);
        DelegateRequest secondDelegateRequest = new DelegateRequest(secondDelegate);
        Pair<Set<String>, Set<FeatureTagState>> grantedAndDenied = getAllowedAndDeniedTagsForConfig(
                secondDelegateRequest, SipDelegateManager.DENIED_REASON_IN_USE_BY_ANOTHER_DELEGATE,
                firstDelegate);
        SipDelegateController c2 = injectMockDelegateController(TEST_PACKAGE_NAME,
                secondDelegateRequest);
        createDelegateAndVerify(controller, c2, secondDelegateRequest, grantedAndDenied.first,
                grantedAndDenied.second, TEST_PACKAGE_NAME, 1);
        // a reg changed event should happen after wait.
        verifyDelegateRegistrationChangedEvent(1, 2 * THROTTLE_MS);
    }

    @SmallTest
    @Test
    public void createTwoAndTriggerRoleChange() throws Exception {
        SipTransportController controller = setupLiveTransportController(0 /*reeval*/, THROTTLE_MS);

        DelegateRequest firstDelegateRequest = getBaseDelegateRequest();
        Set<FeatureTagState> firstDeniedTags = getDeniedTagsForReason(
                firstDelegateRequest.getFeatureTags(),
                SipDelegateManager.DENIED_REASON_NOT_ALLOWED);
        SipDelegateController c1 = injectMockDelegateController(TEST_PACKAGE_NAME,
                firstDelegateRequest);
        createDelegateAndVerify(controller, c1, firstDelegateRequest,
                firstDelegateRequest.getFeatureTags(), Collections.emptySet(), TEST_PACKAGE_NAME);
        verifyDelegateRegistrationChangedEvent(1 /*times*/, THROTTLE_MS);

        DelegateRequest secondDelegateRequest = getBaseDelegateRequest();
        Set<FeatureTagState> secondDeniedTags = getDeniedTagsForReason(
                secondDelegateRequest.getFeatureTags(),
                SipDelegateManager.DENIED_REASON_NOT_ALLOWED);
        // Try to create a SipDelegate for a package that is not the default sms role.
        SipDelegateController c2 = injectMockDelegateController(TEST_PACKAGE_NAME_2,
                secondDelegateRequest);
        createDelegateAndVerify(controller, c2, secondDelegateRequest, Collections.emptySet(),
                secondDeniedTags, TEST_PACKAGE_NAME_2, 1);

        // now swap the SMS role.
        CompletableFuture<Boolean> pendingC1Change = setChangeSupportedFeatureTagsFuture(c1,
                Collections.emptySet(), firstDeniedTags);
        CompletableFuture<Boolean> pendingC2Change = setChangeSupportedFeatureTagsFuture(c2,
                secondDelegateRequest.getFeatureTags(), Collections.emptySet());
        setSmsRoleAndEvaluate(controller, TEST_PACKAGE_NAME_2);
        // swapping roles should trigger a deregistration event on the ImsService side.
        verifyDelegateDeregistrationEvent();
        // there should also not be any new registration changed events
        verifyDelegateRegistrationChangedEvent(1 /*times*/, THROTTLE_MS);
        // trigger completion stage to run
        waitForExecutorAction(mExecutorService, TIMEOUT_MS);
        verify(c1).changeSupportedFeatureTags(Collections.emptySet(), firstDeniedTags);
        // we should not get a change for c2 until pendingC1Change completes.
        verify(c2, never()).changeSupportedFeatureTags(secondDelegateRequest.getFeatureTags(),
                Collections.emptySet());
        // ensure we are not blocking executor here
        waitForExecutorAction(mExecutorService, TIMEOUT_MS);
        completePendingChange(pendingC1Change, true);
        // trigger completion stage to run
        waitForExecutorAction(mExecutorService, TIMEOUT_MS);
        verify(c2).changeSupportedFeatureTags(secondDelegateRequest.getFeatureTags(),
                Collections.emptySet());
        // ensure we are not blocking executor here
        waitForExecutorAction(mExecutorService, TIMEOUT_MS);
        completePendingChange(pendingC2Change, true);
        // verify we now get a second registration changed event
        verifyDelegateRegistrationChangedEvent(2 /*times*/, THROTTLE_MS);
    }

    @SmallTest
    @Test
    public void createTwoAndDestroyOlder() throws Exception {
        SipTransportController controller = setupLiveTransportController(0 /*reeval*/, THROTTLE_MS);

        // First delegate requests RCS message + File transfer
        ArraySet<String> firstDelegate = new ArraySet<>(getBaseDelegateRequest().getFeatureTags());
        firstDelegate.remove(ImsSignallingUtils.GROUP_CHAT_TAG);
        DelegateRequest firstDelegateRequest = new DelegateRequest(firstDelegate);
        SipDelegateController c1 = injectMockDelegateController(TEST_PACKAGE_NAME,
                firstDelegateRequest);
        createDelegateAndVerify(controller, c1, firstDelegateRequest, firstDelegate,
                Collections.emptySet(), TEST_PACKAGE_NAME);
        verifyNoDelegateRegistrationChangedEvent();

        // First delegate requests RCS message + Group RCS message. For this delegate, single RCS
        // message should be denied.
        ArraySet<String> secondDelegate = new ArraySet<>(getBaseDelegateRequest().getFeatureTags());
        secondDelegate.remove(ImsSignallingUtils.FILE_TRANSFER_HTTP_TAG);
        DelegateRequest secondDelegateRequest = new DelegateRequest(secondDelegate);
        Pair<Set<String>, Set<FeatureTagState>> grantedAndDenied = getAllowedAndDeniedTagsForConfig(
                secondDelegateRequest, SipDelegateManager.DENIED_REASON_IN_USE_BY_ANOTHER_DELEGATE,
                firstDelegate);
        SipDelegateController c2 = injectMockDelegateController(TEST_PACKAGE_NAME,
                secondDelegateRequest);
        createDelegateAndVerify(controller, c2, secondDelegateRequest, grantedAndDenied.first,
                grantedAndDenied.second, TEST_PACKAGE_NAME, 1);
        verifyNoDelegateRegistrationChangedEvent();

        // Destroy the firstDelegate, which should now cause all previously denied tags to be
        // granted to the new delegate.
        CompletableFuture<Boolean> pendingC2Change = setChangeSupportedFeatureTagsFuture(c2,
                secondDelegate, Collections.emptySet());
        destroyDelegateAndVerify(controller, c1, false /*force*/,
                SipDelegateManager.SIP_DELEGATE_DESTROY_REASON_REQUESTED_BY_APP);
        // wait for create to be processed.
        assertTrue(waitForExecutorAction(mExecutorService, TIMEOUT_MS));
        verify(c2).changeSupportedFeatureTags(secondDelegate, Collections.emptySet());
        completePendingChange(pendingC2Change, true);

        verifyDelegateRegistrationChangedEvent(1 /*times*/, THROTTLE_MS);
    }

    @SmallTest
    @Test
    public void testThrottling() throws Exception {
        SipTransportController controller = setupLiveTransportController(THROTTLE_MS, THROTTLE_MS);

        // First delegate requests RCS message + File transfer
        ArraySet<String> firstDelegate = new ArraySet<>(getBaseDelegateRequest().getFeatureTags());
        firstDelegate.remove(ImsSignallingUtils.GROUP_CHAT_TAG);
        DelegateRequest firstDelegateRequest = new DelegateRequest(firstDelegate);
        SipDelegateController c1 = injectMockDelegateController(TEST_PACKAGE_NAME,
                firstDelegateRequest);
        CompletableFuture<Boolean> pendingC1Change = createDelegate(controller, c1,
                firstDelegateRequest, firstDelegate, Collections.emptySet(), TEST_PACKAGE_NAME);

        // Request RCS message + group RCS Message. For this delegate, single RCS message should be
        // denied.
        ArraySet<String> secondDelegate = new ArraySet<>(getBaseDelegateRequest().getFeatureTags());
        secondDelegate.remove(ImsSignallingUtils.FILE_TRANSFER_HTTP_TAG);
        DelegateRequest secondDelegateRequest = new DelegateRequest(secondDelegate);
        Pair<Set<String>, Set<FeatureTagState>> grantedAndDeniedC2 =
                getAllowedAndDeniedTagsForConfig(secondDelegateRequest,
                        SipDelegateManager.DENIED_REASON_IN_USE_BY_ANOTHER_DELEGATE, firstDelegate);
        SipDelegateController c2 = injectMockDelegateController(TEST_PACKAGE_NAME,
                secondDelegateRequest);
        CompletableFuture<Boolean> pendingC2Change = createDelegate(controller, c2,
                secondDelegateRequest, grantedAndDeniedC2.first, grantedAndDeniedC2.second,
                TEST_PACKAGE_NAME);

        // Request group RCS message + file transfer. All should be denied at first
        ArraySet<String> thirdDelegate = new ArraySet<>(getBaseDelegateRequest().getFeatureTags());
        thirdDelegate.remove(ImsSignallingUtils.ONE_TO_ONE_CHAT_TAG);
        DelegateRequest thirdDelegateRequest = new DelegateRequest(thirdDelegate);
        Pair<Set<String>, Set<FeatureTagState>> grantedAndDeniedC3 =
                getAllowedAndDeniedTagsForConfig(thirdDelegateRequest,
                        SipDelegateManager.DENIED_REASON_IN_USE_BY_ANOTHER_DELEGATE, firstDelegate,
                        grantedAndDeniedC2.first);
        SipDelegateController c3 = injectMockDelegateController(TEST_PACKAGE_NAME,
                thirdDelegateRequest);
        CompletableFuture<Boolean> pendingC3Change = createDelegate(controller, c3,
                thirdDelegateRequest, grantedAndDeniedC3.first, grantedAndDeniedC3.second,
                TEST_PACKAGE_NAME);

        verifyNoDelegateRegistrationChangedEvent();
        assertTrue(scheduleDelayedWait(2 * THROTTLE_MS));
        verifyDelegateChanged(c1, pendingC1Change, firstDelegate, Collections.emptySet(), 0);
        verifyDelegateChanged(c2, pendingC2Change, grantedAndDeniedC2.first,
                grantedAndDeniedC2.second, 0);
        verifyDelegateChanged(c3, pendingC3Change, grantedAndDeniedC3.first,
                grantedAndDeniedC3.second, 0);
        verifyDelegateRegistrationChangedEvent(1, 2 * THROTTLE_MS);

        // Destroy the first and second controller in quick succession, this should only generate
        // one reevaluate for the third controller.
        CompletableFuture<Boolean> pendingChangeC3 = setChangeSupportedFeatureTagsFuture(
                c3, thirdDelegate, Collections.emptySet());
        CompletableFuture<Integer> pendingDestroyC1 = destroyDelegate(controller, c1,
                false /*force*/,
                SipDelegateManager.SIP_DELEGATE_DESTROY_REASON_REQUESTED_BY_APP);
        CompletableFuture<Integer> pendingDestroyC2 = destroyDelegate(controller, c2,
                false /*force*/,
                SipDelegateManager.SIP_DELEGATE_DESTROY_REASON_REQUESTED_BY_APP);
        assertTrue(scheduleDelayedWait(2 * THROTTLE_MS));
        verifyDestroyDelegate(controller, c1, pendingDestroyC1, false /*force*/,
                SipDelegateManager.SIP_DELEGATE_DESTROY_REASON_REQUESTED_BY_APP);
        verifyDestroyDelegate(controller, c2, pendingDestroyC2, false /*force*/,
                SipDelegateManager.SIP_DELEGATE_DESTROY_REASON_REQUESTED_BY_APP);

        // All requested features should now be granted
        completePendingChange(pendingChangeC3, true);
        verify(c3).changeSupportedFeatureTags(thirdDelegate, Collections.emptySet());
        // In total reeval should have only been called twice.
        verify(c3, times(2)).changeSupportedFeatureTags(any(), any());
        verifyDelegateRegistrationChangedEvent(2 /*times*/, 2 * THROTTLE_MS);
    }

    @SmallTest
    @Test
    public void testSubIdChangeDestroyTriggered() throws Exception {
        SipTransportController controller = setupLiveTransportController();

        ArraySet<String> firstDelegate = new ArraySet<>(getBaseDelegateRequest().getFeatureTags());
        DelegateRequest firstDelegateRequest = new DelegateRequest(firstDelegate);
        SipDelegateController c1 = injectMockDelegateController(TEST_PACKAGE_NAME,
                firstDelegateRequest);
        createDelegateAndVerify(controller, c1, firstDelegateRequest, firstDelegate,
                Collections.emptySet(), TEST_PACKAGE_NAME);
        verifyDelegateRegistrationChangedEvent(1 /*times*/, 0 /*waitMs*/);

        CompletableFuture<Integer> pendingDestroy =  setDestroyFuture(c1, true,
                SipDelegateManager.SIP_DELEGATE_DESTROY_REASON_SUBSCRIPTION_TORN_DOWN);
        controller.onAssociatedSubscriptionUpdated(TEST_SUB_ID + 1);
        waitForExecutorAction(mExecutorService, TIMEOUT_MS);
        verifyDestroyDelegate(controller, c1, pendingDestroy, true /*force*/,
                SipDelegateManager.SIP_DELEGATE_DESTROY_REASON_SUBSCRIPTION_TORN_DOWN);
        verifyDelegateRegistrationChangedEvent(2 /*times*/, 0 /*waitMs*/);
    }

    @SmallTest
    @Test
    public void testRcsManagerGoneDestroyTriggered() throws Exception {
        SipTransportController controller = setupLiveTransportController();

        ArraySet<String> firstDelegate = new ArraySet<>(getBaseDelegateRequest().getFeatureTags());
        DelegateRequest firstDelegateRequest = new DelegateRequest(firstDelegate);
        SipDelegateController c1 = injectMockDelegateController(TEST_PACKAGE_NAME,
                firstDelegateRequest);
        createDelegateAndVerify(controller, c1, firstDelegateRequest, firstDelegate,
                Collections.emptySet(), TEST_PACKAGE_NAME);

        CompletableFuture<Integer> pendingDestroy =  setDestroyFuture(c1, true,
                SipDelegateManager.SIP_DELEGATE_DESTROY_REASON_SERVICE_DEAD);
        controller.onRcsDisconnected();
        waitForExecutorAction(mExecutorService, TIMEOUT_MS);
        verifyDestroyDelegate(controller, c1, pendingDestroy, true /*force*/,
                SipDelegateManager.SIP_DELEGATE_DESTROY_REASON_SERVICE_DEAD);
        verifyDelegateRegistrationChangedEvent(1, 0 /*waitMs*/);
    }

    @SmallTest
    @Test
    public void testDestroyTriggered() throws Exception {
        SipTransportController controller = setupLiveTransportController();

        ArraySet<String> firstDelegate = new ArraySet<>(getBaseDelegateRequest().getFeatureTags());
        DelegateRequest firstDelegateRequest = new DelegateRequest(firstDelegate);
        SipDelegateController c1 = injectMockDelegateController(TEST_PACKAGE_NAME,
                firstDelegateRequest);
        createDelegateAndVerify(controller, c1, firstDelegateRequest, firstDelegate,
                Collections.emptySet(), TEST_PACKAGE_NAME);

        CompletableFuture<Integer> pendingDestroy =  setDestroyFuture(c1, true,
                SipDelegateManager.SIP_DELEGATE_DESTROY_REASON_SUBSCRIPTION_TORN_DOWN);
        controller.onDestroy();
        waitForExecutorAction(mExecutorService, TIMEOUT_MS);
        verifyDelegateDeregistrationEvent();
        // verify change was called.
        verify(c1).destroy(true /*force*/,
                SipDelegateManager.SIP_DELEGATE_DESTROY_REASON_SUBSCRIPTION_TORN_DOWN);
        // ensure thread is not blocked while waiting for pending complete.
        waitForExecutorAction(mExecutorService, TIMEOUT_MS);
        completePendingDestroy(pendingDestroy,
                SipDelegateManager.SIP_DELEGATE_DESTROY_REASON_SUBSCRIPTION_TORN_DOWN);
    }

    @SmallTest
    @Test
    public void testTimingSubIdChangedAndCreateNewSubId() throws Exception {
        SipTransportController controller = setupLiveTransportController(THROTTLE_MS, 0);

        ArraySet<String> firstDelegate = new ArraySet<>(getBaseDelegateRequest().getFeatureTags());
        DelegateRequest firstDelegateRequest = new DelegateRequest(firstDelegate);
        SipDelegateController c1 = injectMockDelegateController(TEST_PACKAGE_NAME,
                firstDelegateRequest);
        CompletableFuture<Boolean> pendingC1Change = createDelegate(controller, c1,
                firstDelegateRequest, firstDelegate, Collections.emptySet(), TEST_PACKAGE_NAME);
        assertTrue(scheduleDelayedWait(2 * THROTTLE_MS));
        verifyDelegateChanged(c1, pendingC1Change, firstDelegate, Collections.emptySet(), 0);


        CompletableFuture<Integer> pendingDestroy =  setDestroyFuture(c1, true,
                SipDelegateManager.SIP_DELEGATE_DESTROY_REASON_SUBSCRIPTION_TORN_DOWN);
        // triggers reeval now.
        controller.onAssociatedSubscriptionUpdated(TEST_SUB_ID + 1);
        waitForExecutorAction(mExecutorService, TIMEOUT_MS);

        // mock a second delegate with the new subId associated with the slot.
        ArraySet<String> secondDelegate = new ArraySet<>();
        secondDelegate.add(ImsSignallingUtils.ONE_TO_ONE_CHAT_TAG);
        secondDelegate.add(ImsSignallingUtils.FILE_TRANSFER_HTTP_TAG);
        DelegateRequest secondDelegateRequest = new DelegateRequest(secondDelegate);
        SipDelegateController c2 = injectMockDelegateController(TEST_SUB_ID + 1,
                TEST_PACKAGE_NAME, secondDelegateRequest);
        CompletableFuture<Boolean> pendingC2Change = createDelegate(controller, c2,
                TEST_SUB_ID + 1, secondDelegateRequest, secondDelegate,
                Collections.emptySet(), TEST_PACKAGE_NAME);
        assertTrue(scheduleDelayedWait(THROTTLE_MS));

        //trigger destroyed event
        verifyDestroyDelegate(controller, c1, pendingDestroy, true /*force*/,
                SipDelegateManager.SIP_DELEGATE_DESTROY_REASON_SUBSCRIPTION_TORN_DOWN);
        assertTrue(scheduleDelayedWait(2 * THROTTLE_MS));
        verifyDelegateChanged(c2, pendingC2Change, secondDelegate, Collections.emptySet(), 0);
    }

    @SafeVarargs
    private final Pair<Set<String>, Set<FeatureTagState>> getAllowedAndDeniedTagsForConfig(
            DelegateRequest r, int denyReason, Set<String>... previousRequestedTagSets) {
        ArraySet<String> rejectedTags = new ArraySet<>(r.getFeatureTags());
        ArraySet<String> grantedTags = new ArraySet<>(r.getFeatureTags());
        Set<String> previousRequestedTags = new ArraySet<>();
        for (Set<String> s : previousRequestedTagSets) {
            previousRequestedTags.addAll(s);
        }
        rejectedTags.retainAll(previousRequestedTags);
        grantedTags.removeAll(previousRequestedTags);
        Set<FeatureTagState> deniedTags = getDeniedTagsForReason(rejectedTags, denyReason);
        return new Pair<>(grantedTags, deniedTags);
    }

    private void completePendingChange(CompletableFuture<Boolean> change, boolean result) {
        mExecutorService.execute(() -> change.complete(result));
        waitForExecutorAction(mExecutorService, TIMEOUT_MS);
    }

    private void completePendingDestroy(CompletableFuture<Integer> destroy, int result) {
        mExecutorService.execute(() -> destroy.complete(result));
        waitForExecutorAction(mExecutorService, TIMEOUT_MS);
    }

    private SipTransportController setupLiveTransportController() throws Exception {
        return setupLiveTransportController(0 /*throttleMs*/, 0 /*regDelayMs*/);
    }

    private SipTransportController setupLiveTransportController(int throttleMs, int regDelayMs)
            throws Exception {
        mExecutorService = Executors.newSingleThreadScheduledExecutor();
        SipTransportController controller = createControllerAndThrottle(mExecutorService,
                throttleMs, regDelayMs);
        doReturn(mSipTransport).when(mRcsManager).getSipTransport();
        controller.onAssociatedSubscriptionUpdated(TEST_SUB_ID);
        controller.onRcsConnected(mRcsManager);
        waitForExecutorAction(mExecutorService, TIMEOUT_MS);
        return controller;
    }

    private void createDelegateAndVerify(SipTransportController controller,
            SipDelegateController delegateController, DelegateRequest r, Set<String> allowedTags,
            Set<FeatureTagState> deniedTags, String packageName,
            int numPreviousChanges) throws ImsException {

        CompletableFuture<Boolean> pendingChange = createDelegate(controller, delegateController, r,
                allowedTags, deniedTags, packageName);
        verifyDelegateChanged(delegateController, pendingChange, allowedTags, deniedTags,
                numPreviousChanges);
    }

    private void createDelegateAndVerify(SipTransportController controller,
            SipDelegateController delegateController, DelegateRequest r, Set<String> allowedTags,
            Set<FeatureTagState> deniedTags, String packageName) throws ImsException {
        createDelegateAndVerify(controller, delegateController, r, allowedTags, deniedTags,
                packageName, 0);
    }

    private CompletableFuture<Boolean> createDelegate(SipTransportController controller,
            SipDelegateController delegateController, int subId, DelegateRequest r,
            Set<String> allowedTags, Set<FeatureTagState> deniedTags, String packageName) {
        CompletableFuture<Boolean> pendingChange = setChangeSupportedFeatureTagsFuture(
                delegateController, allowedTags, deniedTags);
        try {
            controller.createSipDelegate(subId, r, packageName, mMockStateCallback,
                    mMockMessageCallback);
        } catch (ImsException e) {
            fail("ImsException thrown:" + e);
        }
        // move to internal & schedule eval
        waitForExecutorAction(mExecutorService, TIMEOUT_MS);
        // reeval
        waitForExecutorAction(mExecutorService, TIMEOUT_MS);
        return pendingChange;
    }

    private CompletableFuture<Boolean> createDelegate(SipTransportController controller,
            SipDelegateController delegateController, DelegateRequest r, Set<String> allowedTags,
            Set<FeatureTagState> deniedTags, String packageName) throws ImsException {
        return createDelegate(controller, delegateController, TEST_SUB_ID, r, allowedTags,
                deniedTags, packageName);
    }

    private void verifyDelegateChanged(SipDelegateController delegateController,
            CompletableFuture<Boolean> pendingChange, Set<String> allowedTags,
            Set<FeatureTagState> deniedTags, int numPreviousChangeStages) {
        // empty the queue of pending changeSupportedFeatureTags before running the one we are
        // interested in, since the reevaluate waits for one stage to complete before moving to the
        // next.
        for (int i = 0; i < numPreviousChangeStages + 1; i++) {
            assertTrue(waitForExecutorAction(mExecutorService, TIMEOUT_MS));
        }
        // verify change was called.
        verify(delegateController).changeSupportedFeatureTags(allowedTags, deniedTags);
        // ensure thread is not blocked while waiting for pending complete.
        assertTrue(waitForExecutorAction(mExecutorService, TIMEOUT_MS));
        completePendingChange(pendingChange, true);
        // process pending change.
        assertTrue(waitForExecutorAction(mExecutorService, TIMEOUT_MS));
    }

    private void destroyDelegateAndVerify(SipTransportController controller,
            SipDelegateController delegateController, boolean force, int reason) {
        CompletableFuture<Integer> pendingDestroy =  destroyDelegate(controller, delegateController,
                force, reason);
        verifyDestroyDelegate(controller, delegateController, pendingDestroy, force, reason);
    }

    private CompletableFuture<Integer> destroyDelegate(SipTransportController controller,
            SipDelegateController delegateController, boolean force, int reason) {
        CompletableFuture<Integer> pendingDestroy =  setDestroyFuture(delegateController, force,
                reason);
        controller.destroySipDelegate(TEST_SUB_ID, delegateController.getSipDelegateInterface(),
                reason);
        // move to internal & schedule eval
        waitForExecutorAction(mExecutorService, TIMEOUT_MS);
        // reeval
        waitForExecutorAction(mExecutorService, TIMEOUT_MS);
        return pendingDestroy;
    }

    private void verifyDestroyDelegate(SipTransportController controller,
            SipDelegateController delegateController, CompletableFuture<Integer> pendingDestroy,
            boolean force, int reason) {
        // verify destroy was called.
        verify(delegateController).destroy(force, reason);
        // ensure thread is not blocked while waiting for pending complete.
        waitForExecutorAction(mExecutorService, TIMEOUT_MS);
        completePendingDestroy(pendingDestroy, reason);
    }

    private void triggerFullNetworkRegistrationAndVerify(SipTransportController controller,
            SipDelegateController delegateController) {
        controller.triggerFullNetworkRegistration(TEST_SUB_ID,
                delegateController.getSipDelegateInterface(), 403, "forbidden");
        // move to internal & trigger event
        waitForExecutorAction(mExecutorService, TIMEOUT_MS);
        verify(delegateController).triggerFullNetworkRegistration(403, "forbidden");
    }

    private void triggerFullNetworkRegistrationAndVerifyNever(SipTransportController controller,
            SipDelegateController delegateController) {
        controller.triggerFullNetworkRegistration(TEST_SUB_ID,
                delegateController.getSipDelegateInterface(), 403, "forbidden");
        // move to internal & potentially trigger event
        waitForExecutorAction(mExecutorService, TIMEOUT_MS);
        verify(delegateController, never()).triggerFullNetworkRegistration(anyInt(), anyString());
    }

    private DelegateRequest getBaseDelegateRequest() {
        Set<String> featureTags = new ArraySet<>();
        featureTags.add(ImsSignallingUtils.ONE_TO_ONE_CHAT_TAG);
        featureTags.add(ImsSignallingUtils.GROUP_CHAT_TAG);
        featureTags.add(ImsSignallingUtils.FILE_TRANSFER_HTTP_TAG);
        return new DelegateRequest(featureTags);
    }

    private Set<FeatureTagState> getBaseDeniedSet() {
        Set<FeatureTagState> deniedTags = new ArraySet<>();
        deniedTags.add(new FeatureTagState(ImsSignallingUtils.MMTEL_TAG,
                SipDelegateManager.DENIED_REASON_IN_USE_BY_ANOTHER_DELEGATE));
        return deniedTags;
    }

    private Set<FeatureTagState> getDeniedTagsForReason(Set<String> deniedTags, int reason) {
        return deniedTags.stream().map(t -> new FeatureTagState(t, reason))
                .collect(Collectors.toSet());
    }

    private SipDelegateController injectMockDelegateController(String packageName,
            DelegateRequest r) {
        return injectMockDelegateController(TEST_SUB_ID, packageName, r);
    }

    private SipDelegateController injectMockDelegateController(int subId, String packageName,
            DelegateRequest r) {
        SipDelegateControllerContainer c = new SipDelegateControllerContainer(subId,
                packageName, r);
        mMockControllers.add(c);
        return c.delegateController;
    }

    private SipDelegateController getMockDelegateController(int subId, String packageName,
            DelegateRequest r) {
        return mMockControllers.stream()
                .filter(c -> c.subId == subId && c.packageName.equals(packageName)
                        && c.delegateRequest.equals(r))
                .map(c -> c.delegateController).findFirst().orElse(null);
    }

    private CompletableFuture<Boolean> setChangeSupportedFeatureTagsFuture(SipDelegateController c,
            Set<String> supportedSet, Set<FeatureTagState> deniedSet) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        doReturn(result).when(c).changeSupportedFeatureTags(eq(supportedSet), eq(deniedSet));
        return result;
    }

    private CompletableFuture<Integer> setDestroyFuture(SipDelegateController c, boolean force,
            int destroyReason) {
        CompletableFuture<Integer> result = new CompletableFuture<>();
        doReturn(result).when(c).destroy(force, destroyReason);
        return result;
    }

    private void setSmsRoleAndEvaluate(SipTransportController c, String packageName) {
        verify(mMockRoleManager).addOnRoleHoldersChangedListenerAsUser(any(), any(), any());
        mSmsPackageName.clear();
        mSmsPackageName.add(packageName);
        c.onRoleHoldersChanged(RoleManager.ROLE_SMS, UserHandle.SYSTEM);
        // finish internal throttled re-evaluate
        waitForExecutorAction(mExecutorService, TIMEOUT_MS);
    }

    private void verifyNoDelegateRegistrationChangedEvent() throws Exception {
        // event is scheduled and then executed.
        waitForExecutorAction(mExecutorService, TIMEOUT_MS);
        verify(mImsRegistration, never()).triggerUpdateSipDelegateRegistration();
    }

    private void verifyDelegateRegistrationChangedEvent(int times, int waitMs)
            throws Exception {
        // event is scheduled and then executed.
        assertTrue(scheduleDelayedWait(waitMs));
        waitForExecutorAction(mExecutorService, TIMEOUT_MS);
        verify(mImsRegistration, times(times)).triggerUpdateSipDelegateRegistration();
    }


    private void verifyDelegateDeregistrationEvent() throws Exception {
        verify(mImsRegistration).triggerSipDelegateDeregistration();
    }

    private SipTransportController createController(ScheduledExecutorService e) {
        return createControllerAndThrottle(e, 0 /*throttleMs*/, 0 /*regDelayMs*/);
    }

    private SipTransportController createControllerAndThrottle(ScheduledExecutorService e,
            int throttleMs, int regDelayMs) {
        return new SipTransportController(mContext, 0 /*slotId*/, TEST_SUB_ID,
                mMockDelegateControllerFactory, mMockRoleManager,
                // Remove delays for testing.
                new SipTransportController.TimerAdapter() {
                    @Override
                    public int getReevaluateThrottleTimerMilliseconds() {
                        return throttleMs;
                    }

                    @Override
                    public int getUpdateRegistrationDelayMilliseconds() {
                        return regDelayMs;
                    }
                }, e);
    }

    private boolean scheduleDelayedWait(long timeMs) {
        CountDownLatch l = new CountDownLatch(1);
        mExecutorService.schedule(l::countDown, timeMs, TimeUnit.MILLISECONDS);
        while (l.getCount() > 0) {
            try {
                return l.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                // try again
            }
        }
        return true;
    }
}
