/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.services.telephony.domainselection;

import static android.telephony.AccessNetworkConstants.AccessNetworkType.EUTRAN;
import static android.telephony.AccessNetworkConstants.AccessNetworkType.GERAN;
import static android.telephony.AccessNetworkConstants.AccessNetworkType.UNKNOWN;
import static android.telephony.AccessNetworkConstants.AccessNetworkType.UTRAN;
import static android.telephony.BarringInfo.BARRING_SERVICE_TYPE_EMERGENCY;
import static android.telephony.BarringInfo.BarringServiceInfo.BARRING_TYPE_UNCONDITIONAL;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_EMERGENCY_CALL_SETUP_TIMER_ON_CURRENT_NETWORK_SEC_INT;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_EMERGENCY_CDMA_PREFERRED_NUMBERS_STRING_ARRAY;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_EMERGENCY_DOMAIN_PREFERENCE_INT_ARRAY;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_EMERGENCY_DOMAIN_PREFERENCE_ROAMING_INT_ARRAY;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_EMERGENCY_LTE_PREFERRED_AFTER_NR_FAILED_BOOL;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_EMERGENCY_NETWORK_SCAN_TYPE_INT;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_EMERGENCY_OVER_CS_ROAMING_SUPPORTED_ACCESS_NETWORK_TYPES_INT_ARRAY;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_EMERGENCY_OVER_CS_SUPPORTED_ACCESS_NETWORK_TYPES_INT_ARRAY;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_EMERGENCY_OVER_IMS_ROAMING_SUPPORTED_3GPP_NETWORK_TYPES_INT_ARRAY;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_EMERGENCY_OVER_IMS_SUPPORTED_3GPP_NETWORK_TYPES_INT_ARRAY;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_EMERGENCY_REQUIRES_IMS_REGISTRATION_BOOL;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_EMERGENCY_REQUIRES_VOLTE_ENABLED_BOOL;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_EMERGENCY_SCAN_TIMER_SEC_INT;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_MAXIMUM_NUMBER_OF_EMERGENCY_TRIES_OVER_VOWIFI_INT;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_PREFER_IMS_EMERGENCY_WHEN_VOICE_CALLS_ON_CS_BOOL;
import static android.telephony.CarrierConfigManager.ImsEmergency.SCAN_TYPE_NO_PREFERENCE;
import static android.telephony.DomainSelectionService.SELECTOR_TYPE_CALLING;
import static android.telephony.NetworkRegistrationInfo.DOMAIN_CS;
import static android.telephony.NetworkRegistrationInfo.DOMAIN_PS;
import static android.telephony.NetworkRegistrationInfo.REGISTRATION_STATE_HOME;
import static android.telephony.NetworkRegistrationInfo.REGISTRATION_STATE_UNKNOWN;

import static com.android.services.telephony.domainselection.EmergencyCallDomainSelector.MSG_NETWORK_SCAN_TIMEOUT;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IPowerManager;
import android.os.IThermalService;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.PowerManager;
import android.telephony.AccessNetworkConstants;
import android.telephony.BarringInfo;
import android.telephony.CarrierConfigManager;
import android.telephony.CellIdentityLte;
import android.telephony.DomainSelectionService;
import android.telephony.DomainSelectionService.SelectionAttributes;
import android.telephony.EmergencyRegResult;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.TelephonyManager;
import android.telephony.TransportSelectorCallback;
import android.telephony.WwanSelectorCallback;
import android.telephony.ims.ImsManager;
import android.telephony.ims.ImsMmTelManager;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.TestableLooper;
import android.util.Log;
import android.util.SparseArray;

import com.android.TestContext;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.lang.reflect.Field;
import java.util.List;
import java.util.function.Consumer;

/**
 * Unit tests for EmergencyCallDomainSelector
 */
public class EmergencyCallDomainSelectorTest {
    private static final String TAG = "EmergencyCallDomainSelectorTest";

    private static final int SLOT_0 = 0;
    private static final int SLOT_0_SUB_ID = 1;

    @Mock private CarrierConfigManager mCarrierConfigManager;
    @Mock private TelephonyManager mTelephonyManager;
    @Mock private WwanSelectorCallback mWwanSelectorCallback;
    @Mock private TransportSelectorCallback mTransportSelectorCallback;
    @Mock private ImsMmTelManager mMmTelManager;
    @Mock private ImsStateTracker mImsStateTracker;
    @Mock private DomainSelectorBase.DestroyListener mDestroyListener;

    private Context mContext;

    private HandlerThread mHandlerThread;
    private TestableLooper mLooper;
    private EmergencyCallDomainSelector mDomainSelector;
    private SelectionAttributes mSelectionAttributes;
    private @AccessNetworkConstants.RadioAccessNetworkType List<Integer> mAccessNetwork;
    private PowerManager mPowerManager;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mContext = new TestContext() {
            @Override
            public String getSystemServiceName(Class<?> serviceClass) {
                if (serviceClass == ImsManager.class) {
                    return Context.TELEPHONY_IMS_SERVICE;
                } else if (serviceClass == TelephonyManager.class) {
                    return Context.TELEPHONY_SERVICE;
                } else if (serviceClass == CarrierConfigManager.class) {
                    return Context.CARRIER_CONFIG_SERVICE;
                } else if (serviceClass == PowerManager.class) {
                    return Context.POWER_SERVICE;
                }
                return super.getSystemServiceName(serviceClass);
            }

            @Override
            public Object getSystemService(String name) {
                switch (name) {
                    case (Context.POWER_SERVICE) : {
                        return mPowerManager;
                    }
                }
                return super.getSystemService(name);
            }

            @Override
            public String getOpPackageName() {
                return "";
            }
        };

        if (Looper.myLooper() == null) {
            Looper.prepare();
        }

        mHandlerThread = new HandlerThread("EmergencyCallDomainSelectorTest");
        mHandlerThread.start();

        try {
            mLooper = new TestableLooper(mHandlerThread.getLooper());
        } catch (Exception e) {
            logd("Unable to create looper from handler.");
        }

        mTelephonyManager = mContext.getSystemService(TelephonyManager.class);
        when(mTelephonyManager.createForSubscriptionId(anyInt()))
                .thenReturn(mTelephonyManager);
        when(mTelephonyManager.getNetworkCountryIso()).thenReturn("");

        mCarrierConfigManager = mContext.getSystemService(CarrierConfigManager.class);
        when(mCarrierConfigManager.getConfigForSubId(anyInt()))
            .thenReturn(getDefaultPersistableBundle());

        IPowerManager powerManager = mock(IPowerManager.class);
        mPowerManager = new PowerManager(mContext, powerManager, mock(IThermalService.class),
                new Handler(mHandlerThread.getLooper()));

        ImsManager imsManager = mContext.getSystemService(ImsManager.class);
        when(imsManager.getImsMmTelManager(anyInt())).thenReturn(mMmTelManager);
        when(mMmTelManager.isAdvancedCallingSettingEnabled()).thenReturn(true);

        when(mTransportSelectorCallback.onWwanSelected()).thenReturn(mWwanSelectorCallback);
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                Consumer<WwanSelectorCallback> consumer =
                        (Consumer<WwanSelectorCallback>) invocation.getArguments()[0];
                consumer.accept(mWwanSelectorCallback);
                return null;
            }
        }).when(mTransportSelectorCallback).onWwanSelected(any());

        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                mAccessNetwork = (List<Integer>) invocation.getArguments()[0];
                return null;
            }
        }).when(mWwanSelectorCallback).onRequestEmergencyNetworkScan(
                any(), anyInt(), any(), any());
    }

    @After
    public void tearDown() throws Exception {
        if (mDomainSelector != null) {
            mDomainSelector.destroy();
            mDomainSelector = null;
        }

        if (mLooper != null) {
            mLooper.destroy();
            mLooper = null;
        }
    }

    @SmallTest
    @Test
    public void testInit() throws Exception {
        createSelector(SLOT_0_SUB_ID);

        verify(mWwanSelectorCallback, times(0)).onRequestEmergencyNetworkScan(
                any(), anyInt(), any(), any());
        verify(mWwanSelectorCallback, times(0)).onDomainSelected(anyInt());
    }

    @Test
    public void testDefaultCombinedImsRegisteredBarredSelectCs() throws Exception {
        createSelector(SLOT_0_SUB_ID);
        unsolBarringInfoChanged(true);

        EmergencyRegResult regResult = getEmergencyRegResult(EUTRAN, REGISTRATION_STATE_HOME,
                NetworkRegistrationInfo.DOMAIN_CS | NetworkRegistrationInfo.DOMAIN_PS,
                true, true, 0, 0, "", "");
        SelectionAttributes attr = getSelectionAttributes(SLOT_0, SLOT_0_SUB_ID, regResult);
        mDomainSelector.selectDomain(attr, mTransportSelectorCallback);
        processAllMessages();

        bindImsService();

        verifyCsDialed();
    }

    @Test
    public void testDefaultCombinedImsRegisteredSelectPs() throws Exception {
        createSelector(SLOT_0_SUB_ID);
        unsolBarringInfoChanged(false);

        EmergencyRegResult regResult = getEmergencyRegResult(EUTRAN, REGISTRATION_STATE_HOME,
                NetworkRegistrationInfo.DOMAIN_CS | NetworkRegistrationInfo.DOMAIN_PS,
                true, true, 0, 0, "", "");
        SelectionAttributes attr = getSelectionAttributes(SLOT_0, SLOT_0_SUB_ID, regResult);
        mDomainSelector.selectDomain(attr, mTransportSelectorCallback);
        processAllMessages();

        bindImsService();

        verifyPsDialed();
    }

    @Test
    public void testDefaultCombinedImsNotRegisteredSelectCs() throws Exception {
        createSelector(SLOT_0_SUB_ID);
        unsolBarringInfoChanged(false);

        EmergencyRegResult regResult = getEmergencyRegResult(EUTRAN, REGISTRATION_STATE_HOME,
                NetworkRegistrationInfo.DOMAIN_CS | NetworkRegistrationInfo.DOMAIN_PS,
                true, true, 0, 0, "", "");
        SelectionAttributes attr = getSelectionAttributes(SLOT_0, SLOT_0_SUB_ID, regResult);
        mDomainSelector.selectDomain(attr, mTransportSelectorCallback);
        processAllMessages();

        bindImsServiceUnregistered();

        verifyCsDialed();
    }

    @Test
    public void testDefaultCombinedImsNotRegisteredBarredSelectCs() throws Exception {
        createSelector(SLOT_0_SUB_ID);
        unsolBarringInfoChanged(true);

        EmergencyRegResult regResult = getEmergencyRegResult(EUTRAN, REGISTRATION_STATE_HOME,
                NetworkRegistrationInfo.DOMAIN_CS | NetworkRegistrationInfo.DOMAIN_PS,
                true, true, 0, 0, "", "");
        SelectionAttributes attr = getSelectionAttributes(SLOT_0, SLOT_0_SUB_ID, regResult);
        mDomainSelector.selectDomain(attr, mTransportSelectorCallback);
        processAllMessages();

        bindImsServiceUnregistered();

        verifyCsDialed();
    }

    @Test
    public void testDefaultCombinedImsRegisteredEmsOffBarredSelectCs() throws Exception {
        createSelector(SLOT_0_SUB_ID);
        unsolBarringInfoChanged(true);

        EmergencyRegResult regResult = getEmergencyRegResult(EUTRAN, REGISTRATION_STATE_HOME,
                NetworkRegistrationInfo.DOMAIN_CS | NetworkRegistrationInfo.DOMAIN_PS,
                true, false, 0, 0, "", "");
        SelectionAttributes attr = getSelectionAttributes(SLOT_0, SLOT_0_SUB_ID, regResult);
        mDomainSelector.selectDomain(attr, mTransportSelectorCallback);
        processAllMessages();

        bindImsService();

        verifyCsDialed();
    }

    @Test
    public void testDefaultCombinedImsRegisteredEmsOffSelectCs() throws Exception {
        createSelector(SLOT_0_SUB_ID);
        unsolBarringInfoChanged(false);

        EmergencyRegResult regResult = getEmergencyRegResult(EUTRAN, REGISTRATION_STATE_HOME,
                NetworkRegistrationInfo.DOMAIN_CS | NetworkRegistrationInfo.DOMAIN_PS,
                true, false, 0, 0, "", "");
        SelectionAttributes attr = getSelectionAttributes(SLOT_0, SLOT_0_SUB_ID, regResult);
        mDomainSelector.selectDomain(attr, mTransportSelectorCallback);
        processAllMessages();

        bindImsService();

        verifyCsDialed();
    }

    @Test
    public void testDefaultCombinedImsNotRegisteredEmsOffSelectCs() throws Exception {
        createSelector(SLOT_0_SUB_ID);
        unsolBarringInfoChanged(false);

        EmergencyRegResult regResult = getEmergencyRegResult(EUTRAN, REGISTRATION_STATE_HOME,
                NetworkRegistrationInfo.DOMAIN_CS | NetworkRegistrationInfo.DOMAIN_PS,
                true, false, 0, 0, "", "");
        SelectionAttributes attr = getSelectionAttributes(SLOT_0, SLOT_0_SUB_ID, regResult);
        mDomainSelector.selectDomain(attr, mTransportSelectorCallback);
        processAllMessages();

        bindImsServiceUnregistered();

        verifyCsDialed();
    }

    @Test
    public void testDefaultCombinedImsNotRegisteredEmsOffBarredSelectCs() throws Exception {
        createSelector(SLOT_0_SUB_ID);
        unsolBarringInfoChanged(true);

        EmergencyRegResult regResult = getEmergencyRegResult(EUTRAN, REGISTRATION_STATE_HOME,
                NetworkRegistrationInfo.DOMAIN_CS | NetworkRegistrationInfo.DOMAIN_PS,
                true, false, 0, 0, "", "");
        SelectionAttributes attr = getSelectionAttributes(SLOT_0, SLOT_0_SUB_ID, regResult);
        mDomainSelector.selectDomain(attr, mTransportSelectorCallback);
        processAllMessages();

        bindImsServiceUnregistered();

        verifyCsDialed();
    }

    @Test
    public void testDefaultCombinedImsRegisteredVopsOffBarredSelectCs() throws Exception {
        createSelector(SLOT_0_SUB_ID);
        unsolBarringInfoChanged(true);

        EmergencyRegResult regResult = getEmergencyRegResult(EUTRAN, REGISTRATION_STATE_HOME,
                NetworkRegistrationInfo.DOMAIN_CS | NetworkRegistrationInfo.DOMAIN_PS,
                false, true, 0, 0, "", "");
        SelectionAttributes attr = getSelectionAttributes(SLOT_0, SLOT_0_SUB_ID, regResult);
        mDomainSelector.selectDomain(attr, mTransportSelectorCallback);
        processAllMessages();

        bindImsService();

        verifyCsDialed();
    }

    @Test
    public void testDefaultCombinedImsRegisteredVopsOffSelectCs() throws Exception {
        createSelector(SLOT_0_SUB_ID);
        unsolBarringInfoChanged(false);

        EmergencyRegResult regResult = getEmergencyRegResult(EUTRAN, REGISTRATION_STATE_HOME,
                NetworkRegistrationInfo.DOMAIN_CS | NetworkRegistrationInfo.DOMAIN_PS,
                false, true, 0, 0, "", "");
        SelectionAttributes attr = getSelectionAttributes(SLOT_0, SLOT_0_SUB_ID, regResult);
        mDomainSelector.selectDomain(attr, mTransportSelectorCallback);
        processAllMessages();

        bindImsService();

        verifyCsDialed();
    }

    @Test
    public void testDefaultCombinedImsNotRegisteredVopsOffSelectCs() throws Exception {
        createSelector(SLOT_0_SUB_ID);
        unsolBarringInfoChanged(false);

        EmergencyRegResult regResult = getEmergencyRegResult(EUTRAN, REGISTRATION_STATE_HOME,
                NetworkRegistrationInfo.DOMAIN_CS | NetworkRegistrationInfo.DOMAIN_PS,
                false, true, 0, 0, "", "");
        SelectionAttributes attr = getSelectionAttributes(SLOT_0, SLOT_0_SUB_ID, regResult);
        mDomainSelector.selectDomain(attr, mTransportSelectorCallback);
        processAllMessages();

        bindImsServiceUnregistered();

        verifyCsDialed();
    }

    @Test
    public void testDefaultCombinedImsNotRegisteredVopsOffBarredSelectCs() throws Exception {
        createSelector(SLOT_0_SUB_ID);
        unsolBarringInfoChanged(true);

        EmergencyRegResult regResult = getEmergencyRegResult(EUTRAN, REGISTRATION_STATE_HOME,
                NetworkRegistrationInfo.DOMAIN_CS | NetworkRegistrationInfo.DOMAIN_PS,
                false, true, 0, 0, "", "");
        SelectionAttributes attr = getSelectionAttributes(SLOT_0, SLOT_0_SUB_ID, regResult);
        mDomainSelector.selectDomain(attr, mTransportSelectorCallback);
        processAllMessages();

        bindImsServiceUnregistered();

        verifyCsDialed();
    }

    @Test
    public void testDefaultCombinedImsRegisteredVopsOffEmsOffBarredSelectCs() throws Exception {
        createSelector(SLOT_0_SUB_ID);
        unsolBarringInfoChanged(true);

        EmergencyRegResult regResult = getEmergencyRegResult(EUTRAN, REGISTRATION_STATE_HOME,
                NetworkRegistrationInfo.DOMAIN_CS | NetworkRegistrationInfo.DOMAIN_PS,
                false, false, 0, 0, "", "");
        SelectionAttributes attr = getSelectionAttributes(SLOT_0, SLOT_0_SUB_ID, regResult);
        mDomainSelector.selectDomain(attr, mTransportSelectorCallback);
        processAllMessages();

        bindImsService();

        verifyCsDialed();
    }

    @Test
    public void testDefaultCombinedImsRegisteredVopsOffEmsOffSelectCs() throws Exception {
        createSelector(SLOT_0_SUB_ID);
        unsolBarringInfoChanged(false);

        EmergencyRegResult regResult = getEmergencyRegResult(EUTRAN, REGISTRATION_STATE_HOME,
                NetworkRegistrationInfo.DOMAIN_CS | NetworkRegistrationInfo.DOMAIN_PS,
                false, false, 0, 0, "", "");
        SelectionAttributes attr = getSelectionAttributes(SLOT_0, SLOT_0_SUB_ID, regResult);
        mDomainSelector.selectDomain(attr, mTransportSelectorCallback);
        processAllMessages();

        bindImsService();

        verifyCsDialed();
    }

    @Test
    public void testDefaultCombinedImsNotRegisteredVopsOffEmsOffSelectCs() throws Exception {
        createSelector(SLOT_0_SUB_ID);
        unsolBarringInfoChanged(false);

        EmergencyRegResult regResult = getEmergencyRegResult(EUTRAN, REGISTRATION_STATE_HOME,
                NetworkRegistrationInfo.DOMAIN_CS | NetworkRegistrationInfo.DOMAIN_PS,
                false, false, 0, 0, "", "");
        SelectionAttributes attr = getSelectionAttributes(SLOT_0, SLOT_0_SUB_ID, regResult);
        mDomainSelector.selectDomain(attr, mTransportSelectorCallback);
        processAllMessages();

        bindImsServiceUnregistered();

        verifyCsDialed();
    }

    @Test
    public void testDefaultCombinedImsNotRegisteredVopsOffEmsOffBarredSelectCs() throws Exception {
        createSelector(SLOT_0_SUB_ID);
        unsolBarringInfoChanged(true);

        EmergencyRegResult regResult = getEmergencyRegResult(EUTRAN, REGISTRATION_STATE_HOME,
                NetworkRegistrationInfo.DOMAIN_CS | NetworkRegistrationInfo.DOMAIN_PS,
                false, false, 0, 0, "", "");
        SelectionAttributes attr = getSelectionAttributes(SLOT_0, SLOT_0_SUB_ID, regResult);
        mDomainSelector.selectDomain(attr, mTransportSelectorCallback);
        processAllMessages();

        bindImsServiceUnregistered();

        verifyCsDialed();
    }

    @Test
    public void testDefaultCsSelectCs() throws Exception {
        createSelector(SLOT_0_SUB_ID);
        unsolBarringInfoChanged(false);

        EmergencyRegResult regResult = getEmergencyRegResult(UTRAN, REGISTRATION_STATE_HOME,
                NetworkRegistrationInfo.DOMAIN_CS,
                true, true, 0, 0, "", "");
        SelectionAttributes attr = getSelectionAttributes(SLOT_0, SLOT_0_SUB_ID, regResult);
        mDomainSelector.selectDomain(attr, mTransportSelectorCallback);
        processAllMessages();

        bindImsServiceUnregistered();

        verifyCsDialed();
    }

    @Test
    public void testDefaultEpsImsRegisteredBarredScanPsPreferred() throws Exception {
        createSelector(SLOT_0_SUB_ID);
        unsolBarringInfoChanged(true);

        EmergencyRegResult regResult = getEmergencyRegResult(EUTRAN, REGISTRATION_STATE_HOME,
                NetworkRegistrationInfo.DOMAIN_PS,
                true, true, 0, 0, "", "");
        SelectionAttributes attr = getSelectionAttributes(SLOT_0, SLOT_0_SUB_ID, regResult);
        mDomainSelector.selectDomain(attr, mTransportSelectorCallback);
        processAllMessages();

        bindImsService();

        verifyScanPsPreferred();
    }

    @Test
    public void testDefaultEpsImsRegisteredSelectPs() throws Exception {
        createSelector(SLOT_0_SUB_ID);
        unsolBarringInfoChanged(false);

        EmergencyRegResult regResult = getEmergencyRegResult(EUTRAN, REGISTRATION_STATE_HOME,
                NetworkRegistrationInfo.DOMAIN_PS,
                true, true, 0, 0, "", "");
        SelectionAttributes attr = getSelectionAttributes(SLOT_0, SLOT_0_SUB_ID, regResult);
        mDomainSelector.selectDomain(attr, mTransportSelectorCallback);
        processAllMessages();

        bindImsService();

        verifyPsDialed();
    }

    @Test
    public void testDefaultEpsImsNotRegisteredSelectPs() throws Exception {
        createSelector(SLOT_0_SUB_ID);
        unsolBarringInfoChanged(false);

        EmergencyRegResult regResult = getEmergencyRegResult(EUTRAN, REGISTRATION_STATE_HOME,
                NetworkRegistrationInfo.DOMAIN_PS,
                true, true, 0, 0, "", "");
        SelectionAttributes attr = getSelectionAttributes(SLOT_0, SLOT_0_SUB_ID, regResult);
        mDomainSelector.selectDomain(attr, mTransportSelectorCallback);
        processAllMessages();

        bindImsServiceUnregistered();

        verifyPsDialed();
    }

    @Test
    public void testDefaultEpsImsNotRegisteredBarredSelectScanPsPreferred() throws Exception {
        createSelector(SLOT_0_SUB_ID);
        unsolBarringInfoChanged(true);

        EmergencyRegResult regResult = getEmergencyRegResult(EUTRAN, REGISTRATION_STATE_HOME,
                NetworkRegistrationInfo.DOMAIN_PS,
                true, true, 0, 0, "", "");
        SelectionAttributes attr = getSelectionAttributes(SLOT_0, SLOT_0_SUB_ID, regResult);
        mDomainSelector.selectDomain(attr, mTransportSelectorCallback);
        processAllMessages();

        bindImsServiceUnregistered();

        verifyScanPsPreferred();
    }

    @Test
    public void testDefaultEpsImsRegisteredEmsOffBarredScanPsPreferred() throws Exception {
        createSelector(SLOT_0_SUB_ID);
        unsolBarringInfoChanged(true);

        EmergencyRegResult regResult = getEmergencyRegResult(EUTRAN, REGISTRATION_STATE_HOME,
                NetworkRegistrationInfo.DOMAIN_PS,
                true, false, 0, 0, "", "");
        SelectionAttributes attr = getSelectionAttributes(SLOT_0, SLOT_0_SUB_ID, regResult);
        mDomainSelector.selectDomain(attr, mTransportSelectorCallback);
        processAllMessages();

        bindImsService();

        verifyScanPsPreferred();
    }

    @Test
    public void testDefaultEpsImsRegisteredEmsOffScanPsPreferred() throws Exception {
        createSelector(SLOT_0_SUB_ID);
        unsolBarringInfoChanged(false);

        EmergencyRegResult regResult = getEmergencyRegResult(EUTRAN, REGISTRATION_STATE_HOME,
                NetworkRegistrationInfo.DOMAIN_PS,
                true, false, 0, 0, "", "");
        SelectionAttributes attr = getSelectionAttributes(SLOT_0, SLOT_0_SUB_ID, regResult);
        mDomainSelector.selectDomain(attr, mTransportSelectorCallback);
        processAllMessages();

        bindImsService();

        verifyScanPsPreferred();
    }

    @Test
    public void testDefaultEpsImsNotRegisteredEmsOffScanPsPreferred() throws Exception {
        createSelector(SLOT_0_SUB_ID);
        unsolBarringInfoChanged(false);

        EmergencyRegResult regResult = getEmergencyRegResult(EUTRAN, REGISTRATION_STATE_HOME,
                NetworkRegistrationInfo.DOMAIN_PS,
                true, false, 0, 0, "", "");
        SelectionAttributes attr = getSelectionAttributes(SLOT_0, SLOT_0_SUB_ID, regResult);
        mDomainSelector.selectDomain(attr, mTransportSelectorCallback);
        processAllMessages();

        bindImsServiceUnregistered();

        verifyScanPsPreferred();
    }

    @Test
    public void testDefaultEpsImsNotRegisteredEmsOffBarredScanPsPreferred() throws Exception {
        createSelector(SLOT_0_SUB_ID);
        unsolBarringInfoChanged(true);

        EmergencyRegResult regResult = getEmergencyRegResult(EUTRAN, REGISTRATION_STATE_HOME,
                NetworkRegistrationInfo.DOMAIN_PS,
                true, false, 0, 0, "", "");
        SelectionAttributes attr = getSelectionAttributes(SLOT_0, SLOT_0_SUB_ID, regResult);
        mDomainSelector.selectDomain(attr, mTransportSelectorCallback);
        processAllMessages();

        bindImsServiceUnregistered();

        verifyScanPsPreferred();
    }

    @Test
    public void testDefaultEpsImsRegisteredVopsOffBarredScanPsPreferred() throws Exception {
        createSelector(SLOT_0_SUB_ID);
        unsolBarringInfoChanged(true);

        EmergencyRegResult regResult = getEmergencyRegResult(EUTRAN, REGISTRATION_STATE_HOME,
                NetworkRegistrationInfo.DOMAIN_PS,
                false, true, 0, 0, "", "");
        SelectionAttributes attr = getSelectionAttributes(SLOT_0, SLOT_0_SUB_ID, regResult);
        mDomainSelector.selectDomain(attr, mTransportSelectorCallback);
        processAllMessages();

        bindImsService();

        verifyScanPsPreferred();
    }

    @Test
    public void testDefaultEpsImsRegisteredVopsOffScanPsPreferred() throws Exception {
        createSelector(SLOT_0_SUB_ID);
        unsolBarringInfoChanged(false);

        EmergencyRegResult regResult = getEmergencyRegResult(EUTRAN, REGISTRATION_STATE_HOME,
                NetworkRegistrationInfo.DOMAIN_PS,
                false, true, 0, 0, "", "");
        SelectionAttributes attr = getSelectionAttributes(SLOT_0, SLOT_0_SUB_ID, regResult);
        mDomainSelector.selectDomain(attr, mTransportSelectorCallback);
        processAllMessages();

        bindImsService();

        verifyScanPsPreferred();
    }

    @Test
    public void testDefaultEpsImsNotRegisteredVopsOffScanPsPreferred() throws Exception {
        createSelector(SLOT_0_SUB_ID);
        unsolBarringInfoChanged(false);

        EmergencyRegResult regResult = getEmergencyRegResult(EUTRAN, REGISTRATION_STATE_HOME,
                NetworkRegistrationInfo.DOMAIN_PS,
                false, true, 0, 0, "", "");
        SelectionAttributes attr = getSelectionAttributes(SLOT_0, SLOT_0_SUB_ID, regResult);
        mDomainSelector.selectDomain(attr, mTransportSelectorCallback);
        processAllMessages();

        bindImsServiceUnregistered();

        verifyScanPsPreferred();
    }

    @Test
    public void testDefaultEpsImsNotRegisteredVopsOffBarredScanPsPreferred() throws Exception {
        createSelector(SLOT_0_SUB_ID);
        unsolBarringInfoChanged(true);

        EmergencyRegResult regResult = getEmergencyRegResult(EUTRAN, REGISTRATION_STATE_HOME,
                NetworkRegistrationInfo.DOMAIN_PS,
                false, true, 0, 0, "", "");
        SelectionAttributes attr = getSelectionAttributes(SLOT_0, SLOT_0_SUB_ID, regResult);
        mDomainSelector.selectDomain(attr, mTransportSelectorCallback);
        processAllMessages();

        bindImsServiceUnregistered();

        verifyScanPsPreferred();
    }

    @Test
    public void testDefaultEpsImsRegisteredVopsOffEmsOffBarredScanPsPreferred() throws Exception {
        createSelector(SLOT_0_SUB_ID);
        unsolBarringInfoChanged(true);

        EmergencyRegResult regResult = getEmergencyRegResult(EUTRAN, REGISTRATION_STATE_HOME,
                NetworkRegistrationInfo.DOMAIN_PS,
                false, false, 0, 0, "", "");
        SelectionAttributes attr = getSelectionAttributes(SLOT_0, SLOT_0_SUB_ID, regResult);
        mDomainSelector.selectDomain(attr, mTransportSelectorCallback);
        processAllMessages();

        bindImsService();

        verifyScanPsPreferred();
    }

    @Test
    public void testDefaultEpsImsRegisteredVopsOffEmsOffScanPsPreferred() throws Exception {
        createSelector(SLOT_0_SUB_ID);
        unsolBarringInfoChanged(false);

        EmergencyRegResult regResult = getEmergencyRegResult(EUTRAN, REGISTRATION_STATE_HOME,
                NetworkRegistrationInfo.DOMAIN_PS,
                false, false, 0, 0, "", "");
        SelectionAttributes attr = getSelectionAttributes(SLOT_0, SLOT_0_SUB_ID, regResult);
        mDomainSelector.selectDomain(attr, mTransportSelectorCallback);
        processAllMessages();

        bindImsService();

        verifyScanPsPreferred();
    }

    @Test
    public void testDefaultEpsImsNotRegisteredVopsOffEmsOffScanPsPreferred() throws Exception {
        createSelector(SLOT_0_SUB_ID);
        unsolBarringInfoChanged(false);

        EmergencyRegResult regResult = getEmergencyRegResult(EUTRAN, REGISTRATION_STATE_HOME,
                NetworkRegistrationInfo.DOMAIN_PS,
                false, false, 0, 0, "", "");
        SelectionAttributes attr = getSelectionAttributes(SLOT_0, SLOT_0_SUB_ID, regResult);
        mDomainSelector.selectDomain(attr, mTransportSelectorCallback);
        processAllMessages();

        bindImsServiceUnregistered();

        verifyScanPsPreferred();
    }

    @Test
    public void testDefaultEpsNotRegisteredVopsOffEmsOffBarredScanPsPreferred() throws Exception {
        createSelector(SLOT_0_SUB_ID);
        unsolBarringInfoChanged(true);

        EmergencyRegResult regResult = getEmergencyRegResult(EUTRAN, REGISTRATION_STATE_HOME,
                NetworkRegistrationInfo.DOMAIN_PS,
                false, false, 0, 0, "", "");
        SelectionAttributes attr = getSelectionAttributes(SLOT_0, SLOT_0_SUB_ID, regResult);
        mDomainSelector.selectDomain(attr, mTransportSelectorCallback);
        processAllMessages();

        bindImsServiceUnregistered();

        verifyScanPsPreferred();
    }

    @Test
    public void testDefaultOutOfServiceScanPsPreferred() throws Exception {
        createSelector(SLOT_0_SUB_ID);
        unsolBarringInfoChanged(false);

        EmergencyRegResult regResult = getEmergencyRegResult(
                UNKNOWN, REGISTRATION_STATE_UNKNOWN, 0, false, false, 0, 0, "", "");
        SelectionAttributes attr = getSelectionAttributes(SLOT_0, SLOT_0_SUB_ID, regResult);
        mDomainSelector.selectDomain(attr, mTransportSelectorCallback);
        processAllMessages();

        bindImsServiceUnregistered();

        verifyScanPsPreferred();
    }

    @Test
    public void testVoLteOnEpsImsNotRegisteredSelectPs() throws Exception {
        PersistableBundle bundle = getDefaultPersistableBundle();
        bundle.putBoolean(KEY_EMERGENCY_REQUIRES_VOLTE_ENABLED_BOOL, true);
        when(mCarrierConfigManager.getConfigForSubId(anyInt())).thenReturn(bundle);

        createSelector(SLOT_0_SUB_ID);
        unsolBarringInfoChanged(false);

        EmergencyRegResult regResult = getEmergencyRegResult(EUTRAN, REGISTRATION_STATE_HOME,
                NetworkRegistrationInfo.DOMAIN_PS,
                true, true, 0, 0, "", "");
        SelectionAttributes attr = getSelectionAttributes(SLOT_0, SLOT_0_SUB_ID, regResult);
        mDomainSelector.selectDomain(attr, mTransportSelectorCallback);
        processAllMessages();

        bindImsServiceUnregistered();

        // Requires VoLTE enabled and VoLTE is enabled.
        verifyPsDialed();
    }

    @Test
    public void testVoLteOffEpsImsNotRegisteredSelectCs() throws Exception {
        PersistableBundle bundle = getDefaultPersistableBundle();
        bundle.putBoolean(KEY_EMERGENCY_REQUIRES_VOLTE_ENABLED_BOOL, true);
        when(mCarrierConfigManager.getConfigForSubId(anyInt())).thenReturn(bundle);

        // Disable VoLTE.
        when(mMmTelManager.isAdvancedCallingSettingEnabled()).thenReturn(false);

        createSelector(SLOT_0_SUB_ID);
        unsolBarringInfoChanged(false);

        EmergencyRegResult regResult = getEmergencyRegResult(EUTRAN, REGISTRATION_STATE_HOME,
                NetworkRegistrationInfo.DOMAIN_PS,
                true, true, 0, 0, "", "");
        SelectionAttributes attr = getSelectionAttributes(SLOT_0, SLOT_0_SUB_ID, regResult);
        mDomainSelector.selectDomain(attr, mTransportSelectorCallback);
        processAllMessages();

        bindImsServiceUnregistered();

        // Requires VoLTE enabled but VoLTE is'nt enabled.
        verifyCsDialed();
    }

    @Test
    public void testRequiresRegEpsImsNotRegisteredScanCsPreferred() throws Exception {
        PersistableBundle bundle = getDefaultPersistableBundle();
        bundle.putBoolean(KEY_EMERGENCY_REQUIRES_IMS_REGISTRATION_BOOL, true);
        when(mCarrierConfigManager.getConfigForSubId(anyInt())).thenReturn(bundle);

        createSelector(SLOT_0_SUB_ID);
        unsolBarringInfoChanged(false);

        EmergencyRegResult regResult = getEmergencyRegResult(EUTRAN, REGISTRATION_STATE_HOME,
                NetworkRegistrationInfo.DOMAIN_PS,
                true, true, 0, 0, "", "");
        SelectionAttributes attr = getSelectionAttributes(SLOT_0, SLOT_0_SUB_ID, regResult);
        mDomainSelector.selectDomain(attr, mTransportSelectorCallback);
        processAllMessages();

        bindImsServiceUnregistered();

        verifyScanCsPreferred();
    }

    @Test
    public void testDefaultEpsImsRegisteredBarredScanTimeoutWifi() throws Exception {
        createSelector(SLOT_0_SUB_ID);
        unsolBarringInfoChanged(true);

        EmergencyRegResult regResult = getEmergencyRegResult(EUTRAN, REGISTRATION_STATE_HOME,
                NetworkRegistrationInfo.DOMAIN_PS,
                true, true, 0, 0, "", "");
        SelectionAttributes attr = getSelectionAttributes(SLOT_0, SLOT_0_SUB_ID, regResult);
        mDomainSelector.selectDomain(attr, mTransportSelectorCallback);
        processAllMessages();

        bindImsService(true);

        verifyScanPsPreferred();

        assertTrue(mDomainSelector.hasMessages(MSG_NETWORK_SCAN_TIMEOUT));

        mDomainSelector.handleMessage(mDomainSelector.obtainMessage(MSG_NETWORK_SCAN_TIMEOUT));

        verify(mTransportSelectorCallback, times(1)).onWlanSelected();
    }

    private void createSelector(int subId) throws Exception {
        mDomainSelector = new EmergencyCallDomainSelector(
                mContext, SLOT_0, subId, mHandlerThread.getLooper(),
                mImsStateTracker, mDestroyListener);

        replaceInstance(DomainSelectorBase.class,
                "mWwanSelectorCallback", mDomainSelector, mWwanSelectorCallback);
    }

    private void verifyCsDialed() {
        verify(mWwanSelectorCallback, times(1)).onDomainSelected(eq(DOMAIN_CS));
    }

    private void verifyPsDialed() {
        verify(mWwanSelectorCallback, times(1)).onDomainSelected(eq(DOMAIN_PS));
    }

    private void verifyScanPsPreferred() {
        verifyScanPreferred(DomainSelectionService.SCAN_TYPE_NO_PREFERENCE, EUTRAN);
    }

    private void verifyScanCsPreferred() {
        verifyScanPreferred(DomainSelectionService.SCAN_TYPE_NO_PREFERENCE, UTRAN);
    }

    private void verifyScanPreferred(int scanType, int expectedPreferredAccessNetwork) {
        verify(mWwanSelectorCallback, times(1)).onRequestEmergencyNetworkScan(
                any(), eq(scanType), any(), any());
        assertEquals(expectedPreferredAccessNetwork, (int) mAccessNetwork.get(0));
    }

    private void unsolBarringInfoChanged(boolean barred) {
        SparseArray<BarringInfo.BarringServiceInfo> serviceInfos = new SparseArray<>();
        if (barred) {
            serviceInfos.put(BARRING_SERVICE_TYPE_EMERGENCY,
                    new BarringInfo.BarringServiceInfo(BARRING_TYPE_UNCONDITIONAL, false, 0, 0));
        }
        mDomainSelector.onBarringInfoUpdated(new BarringInfo(new CellIdentityLte(), serviceInfos));
    }

    private void bindImsService() {
        bindImsService(false);
    }

    private void bindImsService(boolean isWifi) {
        doReturn(isWifi).when(mImsStateTracker).isImsRegisteredOverWlan();
        doReturn(true).when(mImsStateTracker).isImsRegistered();
        mDomainSelector.onImsRegistrationStateChanged();
        doReturn(true).when(mImsStateTracker).isImsVoiceCapable();
        mDomainSelector.onImsMmTelCapabilitiesChanged();
    }

    private void bindImsServiceUnregistered() {
        doReturn(false).when(mImsStateTracker).isImsRegistered();
        mDomainSelector.onImsRegistrationStateChanged();
        doReturn(false).when(mImsStateTracker).isImsVoiceCapable();
        mDomainSelector.onImsMmTelCapabilitiesChanged();
    }

    private static EmergencyRegResult getEmergencyRegResult(
            @AccessNetworkConstants.RadioAccessNetworkType int accessNetwork,
            @NetworkRegistrationInfo.RegistrationState int regState,
            @NetworkRegistrationInfo.Domain int domain,
            boolean isVopsSupported, boolean isEmcBearerSupported, int emc, int emf,
            @NonNull String mcc, @NonNull String mnc) {
        return new EmergencyRegResult(accessNetwork, regState,
                domain, isVopsSupported, isEmcBearerSupported,
                emc, emf, mcc, mnc, "");
    }

    private static PersistableBundle getDefaultPersistableBundle() {
        int[] imsRats = new int[] { EUTRAN };
        int[] csRats = new int[] { UTRAN, GERAN };
        int[] imsRoamRats = new int[] { EUTRAN };
        int[] csRoamRats = new int[] { UTRAN, GERAN };
        int[] domainPreference = new int[] {
                CarrierConfigManager.ImsEmergency.DOMAIN_PS_3GPP,
                CarrierConfigManager.ImsEmergency.DOMAIN_CS,
                CarrierConfigManager.ImsEmergency.DOMAIN_PS_NON_3GPP
                };
        int[] roamDomainPreference = new int[] {
                CarrierConfigManager.ImsEmergency.DOMAIN_PS_3GPP,
                CarrierConfigManager.ImsEmergency.DOMAIN_CS,
                CarrierConfigManager.ImsEmergency.DOMAIN_PS_NON_3GPP
                };
        boolean imsWhenVoiceOnCs = false;
        int maxRetriesOverWiFi = 1;
        int cellularScanTimerSec = 10;
        int scanType = SCAN_TYPE_NO_PREFERENCE;
        boolean requiresImsRegistration = false;
        boolean requiresVoLteEnabled = false;
        boolean ltePreferredAfterNrFailed = false;
        String[] cdmaPreferredNumbers = new String[] {};

        return getPersistableBundle(imsRats, csRats, imsRoamRats, csRoamRats,
                domainPreference, roamDomainPreference, imsWhenVoiceOnCs, maxRetriesOverWiFi,
                cellularScanTimerSec, scanType, requiresImsRegistration, requiresVoLteEnabled,
                ltePreferredAfterNrFailed, cdmaPreferredNumbers);
    }

    private static PersistableBundle getPersistableBundle(
            @Nullable int[] imsRats, @Nullable int[] csRats,
            @Nullable int[] imsRoamRats, @Nullable int[] csRoamRats,
            @Nullable int[] domainPreference, @Nullable int[] roamDomainPreference,
            boolean imsWhenVoiceOnCs, int maxRetriesOverWiFi,
            int cellularScanTimerSec, int scanType, boolean requiresImsRegistration,
            boolean requiresVoLteEnabled, boolean ltePreferredAfterNrFailed,
            @Nullable String[] cdmaPreferredNumbers) {

        PersistableBundle bundle  = new PersistableBundle();
        if (imsRats != null) {
            bundle.putIntArray(KEY_EMERGENCY_OVER_IMS_SUPPORTED_3GPP_NETWORK_TYPES_INT_ARRAY,
                    imsRats);
        }
        if (imsRoamRats != null) {
            bundle.putIntArray(
                    KEY_EMERGENCY_OVER_IMS_ROAMING_SUPPORTED_3GPP_NETWORK_TYPES_INT_ARRAY,
                    imsRoamRats);
        }
        if (csRats != null) {
            bundle.putIntArray(KEY_EMERGENCY_OVER_CS_SUPPORTED_ACCESS_NETWORK_TYPES_INT_ARRAY,
                    csRats);
        }
        if (csRoamRats != null) {
            bundle.putIntArray(
                    KEY_EMERGENCY_OVER_CS_ROAMING_SUPPORTED_ACCESS_NETWORK_TYPES_INT_ARRAY,
                    csRoamRats);
        }
        if (domainPreference != null) {
            bundle.putIntArray(KEY_EMERGENCY_DOMAIN_PREFERENCE_INT_ARRAY, domainPreference);
        }
        if (roamDomainPreference != null) {
            bundle.putIntArray(KEY_EMERGENCY_DOMAIN_PREFERENCE_ROAMING_INT_ARRAY,
                    roamDomainPreference);
        }
        bundle.putBoolean(KEY_PREFER_IMS_EMERGENCY_WHEN_VOICE_CALLS_ON_CS_BOOL, imsWhenVoiceOnCs);
        bundle.putInt(KEY_MAXIMUM_NUMBER_OF_EMERGENCY_TRIES_OVER_VOWIFI_INT, maxRetriesOverWiFi);
        bundle.putInt(KEY_EMERGENCY_SCAN_TIMER_SEC_INT, cellularScanTimerSec);
        bundle.putInt(KEY_EMERGENCY_NETWORK_SCAN_TYPE_INT, scanType);
        bundle.putBoolean(KEY_EMERGENCY_REQUIRES_IMS_REGISTRATION_BOOL, requiresImsRegistration);
        bundle.putBoolean(KEY_EMERGENCY_REQUIRES_VOLTE_ENABLED_BOOL, requiresVoLteEnabled);
        bundle.putInt(KEY_EMERGENCY_CALL_SETUP_TIMER_ON_CURRENT_NETWORK_SEC_INT, 0);
        bundle.putBoolean(KEY_EMERGENCY_LTE_PREFERRED_AFTER_NR_FAILED_BOOL,
                ltePreferredAfterNrFailed);
        bundle.putStringArray(KEY_EMERGENCY_CDMA_PREFERRED_NUMBERS_STRING_ARRAY,
                cdmaPreferredNumbers);

        return bundle;
    }

    public static SelectionAttributes getSelectionAttributes(int slotId, int subId,
            EmergencyRegResult regResult) {
        SelectionAttributes.Builder builder =
                new SelectionAttributes.Builder(slotId, subId, SELECTOR_TYPE_CALLING)
                .setEmergency(true)
                .setEmergencyRegResult(regResult);
        return builder.build();
    }

    private static void replaceInstance(final Class c,
            final String instanceName, final Object obj, final Object newValue) throws Exception {
        Field field = c.getDeclaredField(instanceName);
        field.setAccessible(true);
        field.set(obj, newValue);
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
