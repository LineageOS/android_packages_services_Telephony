/*
 * Copyright 2020 The Android Open Source Project
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

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.role.IOnRoleHoldersChangedListener;
import android.app.role.IRoleManager;
import android.app.role.RoleManager;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Telephony.SimInfo;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyRegistryManager;
import android.telephony.ims.ProvisioningManager;
import android.telephony.ims.RcsConfig;
import android.telephony.ims.aidl.IImsConfig;
import android.telephony.ims.feature.ImsFeature;
import android.test.mock.MockContentProvider;
import android.test.mock.MockContentResolver;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.TestableLooper;
import android.util.Log;

import com.android.internal.telephony.ITelephony;
import com.android.internal.telephony.ims.ImsResolver;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Unit tests for RcsProvisioningMonitor
 */
public class RcsProvisioningMonitorTest {
    private static final String TAG = "RcsProvisioningMonitorTest";
    private static final String SAMPLE_CONFIG = "<RCSConfig>\n"
            + "\t<rcsVolteSingleRegistration>1</rcsVolteSingleRegistration>\n"
            + "\t<SERVICES>\n"
            + "\t\t<SupportedRCSProfileVersions>UP_2.0</SupportedRCSProfileVersions>\n"
            + "\t\t<ChatAuth>1</ChatAuth>\n"
            + "\t\t<GroupChatAuth>1</GroupChatAuth>\n"
            + "\t\t<ftAuth>1</ftAuth>\n"
            + "\t\t<standaloneMsgAuth>1</standaloneMsgAuth>\n"
            + "\t\t<geolocPushAuth>1<geolocPushAuth>\n"
            + "\t\t<Ext>\n"
            + "\t\t\t<DataOff>\n"
            + "\t\t\t\t<rcsMessagingDataOff>1</rcsMessagingDataOff>\n"
            + "\t\t\t\t<fileTransferDataOff>1</fileTransferDataOff>\n"
            + "\t\t\t\t<mmsDataOff>1</mmsDataOff>\n"
            + "\t\t\t\t<syncDataOff>1</syncDataOff>\n"
            + "\t\t\t</DataOff>\n"
            + "\t\t</Ext>\n"
            + "\t</SERVICES>\n"
            + "</RCSConfig>";
    private static final int FAKE_SUB_ID_BASE = 0x0FFFFFF0;
    private static final String DEFAULT_MESSAGING_APP1 = "DMA1";
    private static final String DEFAULT_MESSAGING_APP2 = "DMA2";

    private MockitoSession mSession;
    private RcsProvisioningMonitor mRcsProvisioningMonitor;
    private Handler mHandler;
    private HandlerThread mHandlerThread;
    private TestableLooper mLooper;
    private PersistableBundle mBundle;
    private MockContentResolver mContentResolver = new MockContentResolver();
    private SimInfoContentProvider mProvider;
    private BroadcastReceiver mReceiver;
    @Mock
    private Cursor mCursor;
    @Mock
    private SubscriptionManager mSubscriptionManager;
    private SubscriptionManager.OnSubscriptionsChangedListener mSubChangedListener;
    @Mock
    private TelephonyRegistryManager mTelephonyRegistryManager;
    @Mock
    private CarrierConfigManager mCarrierConfigManager;
    private IOnRoleHoldersChangedListener.Stub mRoleHolderChangedListener;
    private RoleManager mRoleManager;
    @Mock
    private IRoleManager.Stub mIRoleManager;
    @Mock
    private ITelephony.Stub mITelephony;
    @Mock
    private ImsResolver mImsResolver;
    @Mock
    private IImsConfig.Stub mIImsConfig;
    @Mock
    private Resources mResources;
    @Mock
    private PhoneGlobals mPhone;

    private Executor mExecutor = new Executor() {
        @Override
        public void execute(Runnable r) {
            r.run();
        }
    };

    private class SimInfoContentProvider extends MockContentProvider {
        private Cursor mCursor;
        private ContentValues mValues;

        SimInfoContentProvider(Context context) {
            super(context);
        }

        public void setCursor(Cursor cursor) {
            mCursor = cursor;
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection,
                String[] selectionArgs, String sortOrder) {
            return mCursor;
        }

        @Override
        public int update(Uri uri, ContentValues values,
                String selection, String[] selectionArgs) {
            mValues = values;
            return 1;
        }

        ContentValues getContentValues() {
            return mValues;
        }
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        replaceService(Context.ROLE_SERVICE, mIRoleManager);
        mRoleManager = new RoleManager(mPhone);
        when(mPhone.getResources()).thenReturn(mResources);
        when(mResources.getBoolean(
                eq(R.bool.config_rcsVolteSingleRegistrationEnabled))).thenReturn(true);
        when(mPhone.getMainExecutor()).thenReturn(mExecutor);
        when(mPhone.getSystemServiceName(eq(CarrierConfigManager.class)))
                .thenReturn(Context.CARRIER_CONFIG_SERVICE);
        when(mPhone.getSystemServiceName(eq(SubscriptionManager.class)))
                .thenReturn(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        when(mPhone.getSystemServiceName(eq(TelephonyRegistryManager.class)))
                .thenReturn(Context.TELEPHONY_REGISTRY_SERVICE);
        when(mPhone.getSystemServiceName(eq(RoleManager.class)))
                .thenReturn(Context.ROLE_SERVICE);
        when(mPhone.getSystemService(eq(Context.CARRIER_CONFIG_SERVICE)))
                .thenReturn(mCarrierConfigManager);
        when(mPhone.getSystemService(eq(Context.TELEPHONY_SUBSCRIPTION_SERVICE)))
                .thenReturn(mSubscriptionManager);
        when(mPhone.getSystemService(eq(Context.TELEPHONY_REGISTRY_SERVICE)))
                .thenReturn(mTelephonyRegistryManager);
        when(mPhone.getSystemService(eq(Context.ROLE_SERVICE)))
                .thenReturn(mRoleManager);

        mBundle = new PersistableBundle();
        when(mCarrierConfigManager.getConfigForSubId(anyInt())).thenReturn(mBundle);

        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                mReceiver = (BroadcastReceiver) invocation.getArguments()[0];
                return null;
            }
        }).when(mPhone).registerReceiver(any(BroadcastReceiver.class), any());

        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                mSubChangedListener = (SubscriptionManager.OnSubscriptionsChangedListener)
                        invocation.getArguments()[0];
                return null;
            }
        }).when(mTelephonyRegistryManager).addOnSubscriptionsChangedListener(
                any(SubscriptionManager.OnSubscriptionsChangedListener.class),
                any());

        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                mRoleHolderChangedListener = (IOnRoleHoldersChangedListener.Stub)
                        invocation.getArguments()[0];
                return null;
            }
        }).when(mIRoleManager).addOnRoleHoldersChangedListenerAsUser(
                any(IOnRoleHoldersChangedListener.Stub.class), anyInt());
        List<String> dmas = new ArrayList<>();
        dmas.add(DEFAULT_MESSAGING_APP1);
        when(mIRoleManager.getRoleHoldersAsUser(any(), anyInt())).thenReturn(dmas);

        mProvider = new SimInfoContentProvider(mPhone);
        mProvider.setCursor(mCursor);
        mContentResolver.addProvider(SimInfo.CONTENT_URI.getAuthority(), mProvider);
        when(mPhone.getContentResolver()).thenReturn(mContentResolver);
        when(mCursor.moveToFirst()).thenReturn(true);
        when(mCursor.getColumnIndexOrThrow(any())).thenReturn(1);
        when(mCursor.getBlob(anyInt())).thenReturn(
                RcsConfig.compressGzip(SAMPLE_CONFIG.getBytes()));
        when(mPhone.getImsResolver()).thenReturn(mImsResolver);
        when(mImsResolver.getImsConfig(anyInt(), anyInt())).thenReturn(mIImsConfig);
        mHandlerThread = new HandlerThread("RcsProvisioningMonitorTest");
        mHandlerThread.start();
    }

    @After
    public void tearDown() throws Exception {
        if (mRcsProvisioningMonitor != null) {
            mRcsProvisioningMonitor.destroy();
            mRcsProvisioningMonitor = null;
        }

        if (mSession != null) {
            mSession.finishMocking();
        }
        if (mLooper != null) {
            mLooper.destroy();
            mLooper = null;
        }
    }

    @Test
    @SmallTest
    public void testInitWithSavedConfig() throws Exception {
        createMonitor(3);
        ArgumentCaptor<Intent> captorIntent = ArgumentCaptor.forClass(Intent.class);
        for (int i = 0; i < 3; i++) {
            assertTrue(Arrays.equals(SAMPLE_CONFIG.getBytes(),
                    mRcsProvisioningMonitor.getConfig(FAKE_SUB_ID_BASE + i)));
        }

        verify(mPhone, times(3)).sendBroadcast(captorIntent.capture());
        Intent capturedIntent = captorIntent.getAllValues().get(1);
        assertEquals(ProvisioningManager.ACTION_RCS_SINGLE_REGISTRATION_CAPABILITY_UPDATE,
                capturedIntent.getAction());
        PhoneGlobals.getInstance().getImsResolver();
        verify(mPhone, atLeastOnce()).getImsResolver();
        verify(mIImsConfig, times(3)).notifyRcsAutoConfigurationReceived(any(), anyBoolean());
    }

    @Test
    @SmallTest
    public void testInitWithoutSavedConfig() throws Exception {
        when(mCursor.getBlob(anyInt())).thenReturn(null);
        ArgumentCaptor<Intent> captorIntent = ArgumentCaptor.forClass(Intent.class);
        createMonitor(3);

        verify(mPhone, times(3)).sendBroadcast(captorIntent.capture());
        Intent capturedIntent = captorIntent.getAllValues().get(1);
        assertEquals(ProvisioningManager.ACTION_RCS_SINGLE_REGISTRATION_CAPABILITY_UPDATE,
                capturedIntent.getAction());
        //Should not notify null config
        verify(mIImsConfig, never()).notifyRcsAutoConfigurationReceived(any(), anyBoolean());
    }

    @Test
    @SmallTest
    public void testSubInfoChanged() throws Exception {
        createMonitor(3);
        ArgumentCaptor<Intent> captorIntent = ArgumentCaptor.forClass(Intent.class);
        mExecutor.execute(() -> mSubChangedListener.onSubscriptionsChanged());
        processAllMessages();
        for (int i = 0; i < 3; i++) {
            assertTrue(Arrays.equals(SAMPLE_CONFIG.getBytes(),
                    mRcsProvisioningMonitor.getConfig(FAKE_SUB_ID_BASE + i)));
        }
        verify(mPhone, times(3)).sendBroadcast(captorIntent.capture());
        Intent capturedIntent = captorIntent.getAllValues().get(1);
        assertEquals(ProvisioningManager.ACTION_RCS_SINGLE_REGISTRATION_CAPABILITY_UPDATE,
                capturedIntent.getAction());
        verify(mIImsConfig, times(3)).notifyRcsAutoConfigurationReceived(any(), anyBoolean());

        makeFakeActiveSubIds(1);
        mExecutor.execute(() -> mSubChangedListener.onSubscriptionsChanged());
        processAllMessages();

        for (int i = 1; i < 3; i++) {
            assertNull(mRcsProvisioningMonitor.getConfig(FAKE_SUB_ID_BASE + i));
        }
        verify(mIImsConfig, times(2)).notifyRcsAutoConfigurationRemoved();
    }

    @Test
    @SmallTest
    public void testDefaultMessagingApplicationChangedWithAcs() throws Exception {
        createMonitor(1);
        updateDefaultMessageApplication(DEFAULT_MESSAGING_APP2);
        mBundle.putBoolean(CarrierConfigManager.KEY_USE_ACS_FOR_RCS_BOOL, true);
        processAllMessages();
        byte[] configCached = mRcsProvisioningMonitor.getConfig(FAKE_SUB_ID_BASE);

        assertNull(configCached);
        assertNull(mProvider.getContentValues().get(SimInfo.COLUMN_RCS_CONFIG));
        verify(mIImsConfig, atLeastOnce()).notifyRcsAutoConfigurationRemoved();
        verify(mIImsConfig, atLeastOnce()).triggerRcsReconfiguration();
        // The api should only be called when monitor is initilized.
        verify(mIImsConfig, times(1))
                .notifyRcsAutoConfigurationReceived(any(), anyBoolean());
    }

    @Test
    @SmallTest
    public void testDefaultMessagingApplicationChangedWithoutAcs() throws Exception {
        createMonitor(1);
        updateDefaultMessageApplication(DEFAULT_MESSAGING_APP2);
        mBundle.putBoolean(CarrierConfigManager.KEY_USE_ACS_FOR_RCS_BOOL, false);
        processAllMessages();
        byte[] configCached = mRcsProvisioningMonitor.getConfig(FAKE_SUB_ID_BASE);

        assertTrue(Arrays.equals(SAMPLE_CONFIG.getBytes(), configCached));
        verify(mIImsConfig, never()).notifyRcsAutoConfigurationRemoved();
        // The api should be called 2 times, one happens when monitor is initilized,
        // Another happens when DMS is changed.
        verify(mIImsConfig, times(2))
                .notifyRcsAutoConfigurationReceived(any(), anyBoolean());
    }

    @Test
    @SmallTest
    public void testCarrierConfigChanged() throws Exception {
        createMonitor(1);
        when(mResources.getBoolean(
                eq(R.bool.config_rcsVolteSingleRegistrationEnabled))).thenReturn(true);
        ArgumentCaptor<Intent> captorIntent = ArgumentCaptor.forClass(Intent.class);
        mBundle.putBoolean(
                CarrierConfigManager.Ims.KEY_IMS_SINGLE_REGISTRATION_REQUIRED_BOOL, true);
        broadcastCarrierConfigChange(FAKE_SUB_ID_BASE);
        processAllMessages();
        verify(mPhone, atLeastOnce()).sendBroadcast(captorIntent.capture());
        Intent capturedIntent = captorIntent.getValue();
        assertEquals(capturedIntent.getAction(),
                ProvisioningManager.ACTION_RCS_SINGLE_REGISTRATION_CAPABILITY_UPDATE);
        assertEquals(FAKE_SUB_ID_BASE, capturedIntent.getIntExtra(
                ProvisioningManager.EXTRA_SUBSCRIPTION_ID, -1));
        assertEquals(ProvisioningManager.STATUS_CAPABLE,
                capturedIntent.getIntExtra(ProvisioningManager.EXTRA_STATUS, -1));

        mBundle.putBoolean(
                CarrierConfigManager.Ims.KEY_IMS_SINGLE_REGISTRATION_REQUIRED_BOOL, false);
        broadcastCarrierConfigChange(FAKE_SUB_ID_BASE);
        processAllMessages();
        verify(mPhone, atLeastOnce()).sendBroadcast(captorIntent.capture());
        capturedIntent = captorIntent.getValue();
        assertEquals(capturedIntent.getAction(),
                ProvisioningManager.ACTION_RCS_SINGLE_REGISTRATION_CAPABILITY_UPDATE);
        assertEquals(FAKE_SUB_ID_BASE, capturedIntent.getIntExtra(
                ProvisioningManager.EXTRA_SUBSCRIPTION_ID, -1));
        assertEquals(ProvisioningManager.STATUS_CARRIER_NOT_CAPABLE,
                capturedIntent.getIntExtra(ProvisioningManager.EXTRA_STATUS, -1));


        when(mResources.getBoolean(
                eq(R.bool.config_rcsVolteSingleRegistrationEnabled))).thenReturn(false);
        broadcastCarrierConfigChange(FAKE_SUB_ID_BASE);
        processAllMessages();
        verify(mPhone, atLeastOnce()).sendBroadcast(captorIntent.capture());
        capturedIntent = captorIntent.getValue();
        assertEquals(capturedIntent.getAction(),
                ProvisioningManager.ACTION_RCS_SINGLE_REGISTRATION_CAPABILITY_UPDATE);
        assertEquals(FAKE_SUB_ID_BASE, capturedIntent.getIntExtra(
                ProvisioningManager.EXTRA_SUBSCRIPTION_ID, -1));
        assertEquals(ProvisioningManager.STATUS_CARRIER_NOT_CAPABLE
                | ProvisioningManager.STATUS_DEVICE_NOT_CAPABLE,
                capturedIntent.getIntExtra(ProvisioningManager.EXTRA_STATUS, -1));
    }

    @Test
    @SmallTest
    public void testUpdateConfig() throws Exception {
        createMonitor(1);
        final ArgumentCaptor<byte[]> argumentBytes = ArgumentCaptor.forClass(byte[].class);

        mRcsProvisioningMonitor.updateConfig(FAKE_SUB_ID_BASE, SAMPLE_CONFIG.getBytes(), false);
        processAllMessages();

        verify(mImsResolver, atLeastOnce()).getImsConfig(
                anyInt(), eq(ImsFeature.FEATURE_RCS));
        verify(mIImsConfig, atLeastOnce()).notifyRcsAutoConfigurationReceived(
                argumentBytes.capture(), eq(false));
        assertTrue(Arrays.equals(SAMPLE_CONFIG.getBytes(), argumentBytes.getValue()));
    }

    @Test
    @SmallTest
    public void testRequestReconfig() throws Exception {
        createMonitor(1);

        mRcsProvisioningMonitor.requestReconfig(FAKE_SUB_ID_BASE);
        processAllMessages();

        verify(mImsResolver, atLeastOnce()).getImsConfig(
                anyInt(), eq(ImsFeature.FEATURE_RCS));
        verify(mIImsConfig, times(1)).notifyRcsAutoConfigurationRemoved();
        verify(mIImsConfig, times(1)).triggerRcsReconfiguration();
    }


    @Test
    @SmallTest
    public void testIsRcsVolteSingleRegistrationEnabled() throws Exception {
        createMonitor(1);

        when(mResources.getBoolean(
                eq(R.bool.config_rcsVolteSingleRegistrationEnabled))).thenReturn(true);
        mBundle.putBoolean(
                CarrierConfigManager.Ims.KEY_IMS_SINGLE_REGISTRATION_REQUIRED_BOOL, true);
        broadcastCarrierConfigChange(FAKE_SUB_ID_BASE);
        processAllMessages();
        assertTrue(mRcsProvisioningMonitor.isRcsVolteSingleRegistrationEnabled(FAKE_SUB_ID_BASE));

        mBundle.putBoolean(
                CarrierConfigManager.Ims.KEY_IMS_SINGLE_REGISTRATION_REQUIRED_BOOL, false);
        broadcastCarrierConfigChange(FAKE_SUB_ID_BASE);
        processAllMessages();
        assertFalse(mRcsProvisioningMonitor.isRcsVolteSingleRegistrationEnabled(FAKE_SUB_ID_BASE));


        when(mResources.getBoolean(
                eq(R.bool.config_rcsVolteSingleRegistrationEnabled))).thenReturn(false);
        mBundle.putBoolean(
                CarrierConfigManager.Ims.KEY_IMS_SINGLE_REGISTRATION_REQUIRED_BOOL, true);
        broadcastCarrierConfigChange(FAKE_SUB_ID_BASE);
        processAllMessages();
        assertFalse(mRcsProvisioningMonitor.isRcsVolteSingleRegistrationEnabled(FAKE_SUB_ID_BASE));
    }

    private void createMonitor(int subCount) {
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
        makeFakeActiveSubIds(subCount);
        mRcsProvisioningMonitor = new RcsProvisioningMonitor(mPhone, mHandlerThread.getLooper());
        mHandler = mRcsProvisioningMonitor.getHandler();
        try {
            mLooper = new TestableLooper(mHandler.getLooper());
        } catch (Exception e) {
            logd("Unable to create looper from handler.");
        }
    }

    private void broadcastCarrierConfigChange(int subId) {
        Intent intent = new Intent(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED);
        intent.putExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, subId);
        mExecutor.execute(() -> {
            mReceiver.onReceive(mPhone, intent);
        });
    }

    private void makeFakeActiveSubIds(int count) {
        final int[] subIds = new int[count];
        for (int i = 0; i < count; i++) {
            subIds[i] = FAKE_SUB_ID_BASE + i;
        }
        when(mSubscriptionManager.getActiveSubscriptionIdList()).thenReturn(subIds);
    }

    private void updateDefaultMessageApplication(String packageName) throws Exception {
        List<String> dmas = new ArrayList<>();
        dmas.add(packageName);
        when(mIRoleManager.getRoleHoldersAsUser(any(), anyInt())).thenReturn(dmas);
        mExecutor.execute(() -> {
            try {
                mRoleHolderChangedListener.onRoleHoldersChanged(
                        RoleManager.ROLE_SMS, UserHandle.USER_ALL);
            } catch (RemoteException e) {
                logd("exception to call onRoleHoldersChanged " + e);
            }
        });
    }

    private void replaceService(final String serviceName,
            final IInterface serviceInstance) throws Exception {
        IBinder binder = mock(IBinder.class);
        when(binder.queryLocalInterface(anyString())).thenReturn(serviceInstance);
        Field field = ServiceManager.class.getDeclaredField("sCache");
        field.setAccessible(true);
        ((Map<String, IBinder>) field.get(null)).put(serviceName, binder);
    }

    private void processAllMessages() {
        while (!mLooper.getLooper().getQueue().isIdle()) {
            mLooper.processAllMessages();
        }
    }

    private static void logd(String str) {
        Log.d(TAG, str);
    }
}
