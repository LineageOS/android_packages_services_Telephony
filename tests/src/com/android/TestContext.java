/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.AttributionSource;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PermissionEnforcer;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.test.FakePermissionEnforcer;
import android.telecom.TelecomManager;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.TelephonyRegistryManager;
import android.telephony.ims.ImsManager;
import android.test.mock.MockContentResolver;
import android.test.mock.MockContext;
import android.util.Log;
import android.util.SparseArray;

import androidx.test.InstrumentationRegistry;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;

import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.Executor;

public class TestContext extends MockContext {

    private static final String TAG = "TestContext";
    // Stub used to grant all permissions
    public static final String STUB_PERMISSION_ENABLE_ALL = "stub_permission_enable_all";

    @Mock Resources mResources;

    @Mock CarrierConfigManager mMockCarrierConfigManager;
    @Mock TelecomManager mMockTelecomManager;
    @Mock TelephonyManager mMockTelephonyManager;
    @Mock SubscriptionManager mMockSubscriptionManager;
    @Mock ImsManager mMockImsManager;
    @Mock UserManager mMockUserManager;
    @Mock PackageManager mPackageManager;
    @Mock ConnectivityManager mMockConnectivityManager;

    @Mock TelephonyRegistryManager mTelephonyRegistryManager;

    @Mock private SharedPreferences mSharedPreferences;

    private final SparseArray<PersistableBundle> mCarrierConfigs = new SparseArray<>();

    private final Multimap<Intent, BroadcastReceiver> mOrderedBroadcastReceivers =
            ArrayListMultimap.create();

    private final Multimap<String, BroadcastReceiver> mBroadcastReceiversByAction =
            ArrayListMultimap.create();
    private final HashMap<String, Intent> mStickyBroadcastByAction =
            new HashMap<String, Intent>();
    private final HashSet<String> mPermissionTable = new HashSet<>();
    private final FakePermissionEnforcer mFakePermissionEnforcer = new FakePermissionEnforcer();

    private final MockContentResolver mContentResolver = new MockContentResolver();

    public TestContext() {
        MockitoAnnotations.openMocks(this);
        doAnswer((Answer<PersistableBundle>) invocation -> {
            int subId = (int) invocation.getArguments()[0];
            return getTestConfigs(subId);
        }).when(mMockCarrierConfigManager).getConfigForSubId(anyInt());

        doAnswer((Answer<PersistableBundle>) invocation -> {
            int subId = (int) invocation.getArguments()[0];
            return getTestConfigs(subId);
        }).when(mMockCarrierConfigManager).getConfigForSubId(anyInt(), any());

        doAnswer((Answer<PersistableBundle>) invocation -> {
            int subId = (int) invocation.getArguments()[0];
            return getTestConfigs(subId);
        }).when(mMockCarrierConfigManager).getConfigForSubId(anyInt(), anyString());
        when(mPackageManager.hasSystemFeature(anyString())).thenReturn(true);
    }

    @Override
    public AssetManager getAssets() {
        return Mockito.mock(AssetManager.class);
    }

    @Override
    public Executor getMainExecutor() {
        // Just run on current thread
        return Runnable::run;
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }

    @Override
    @NonNull
    public Context createAttributionContext(@Nullable String attributionTag) {
        return this;
    }

    @Override
    public Context createContextAsUser(UserHandle user, @CreatePackageOptions int flags) {
        return this;
    }

    @Override
    public String getPackageName() {
        return "com.android.phone.tests";
    }

    @Override
    public String getOpPackageName() {
        return getPackageName();
    }

    @Override
    public String getAttributionTag() {
        return "";
    }

    @Override
    public AttributionSource getAttributionSource() {
        return new AttributionSource(Process.myUid(), getPackageName(), "");
    }

    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) {
        return mSharedPreferences;
    }

    @Override
    public File getFilesDir() {
        return InstrumentationRegistry.getTargetContext().getFilesDir();
    }

    @Override
    public void startActivityAsUser(Intent intent, UserHandle user) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void unregisterReceiver(BroadcastReceiver receiver) {
        mBroadcastReceiversByAction.entries().removeIf(entry
                -> receiver == entry.getValue());
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        return registerReceiverFakeImpl(receiver, filter);
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
                                   String broadcastPermission, Handler scheduler) {
        return registerReceiverFakeImpl(receiver, filter);
    }

    @Override
    public Intent registerReceiverForAllUsers(BroadcastReceiver receiver,
            IntentFilter filter, String broadcastPermission, Handler scheduler) {
        return registerReceiverFakeImpl(receiver, filter);
    }

    @Override
    public Intent registerReceiverAsUser(BroadcastReceiver receiver, UserHandle user,
            IntentFilter filter, String broadcastPermission, Handler scheduler) {
        return registerReceiverFakeImpl(receiver, filter);
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter, int flags) {
        return registerReceiverFakeImpl(receiver, filter);
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
                                   String broadcastPermission, Handler scheduler, int flags) {
        return registerReceiverFakeImpl(receiver, filter);
    }

    private Intent registerReceiverFakeImpl(BroadcastReceiver receiver, IntentFilter filter) {
        Intent result = null;
        synchronized (mBroadcastReceiversByAction) {
            for (int i = 0; i < filter.countActions(); i++) {
                mBroadcastReceiversByAction.put(filter.getAction(i), receiver);
                if (result == null) {
                    result = mStickyBroadcastByAction.get(filter.getAction(i));
                }
            }
        }

        return result;
    }

    @Override
    public void sendBroadcast(Intent intent) {
        logd("sendBroadcast called for " + intent.getAction());
        synchronized (mBroadcastReceiversByAction) {
            for (BroadcastReceiver broadcastReceiver :
                    mBroadcastReceiversByAction.get(intent.getAction())) {
                broadcastReceiver.onReceive(this, intent);
            }
        }
    }

    @Override
    public void sendBroadcast(Intent intent, String receiverPermission) {
        logd("sendBroadcast called for " + intent.getAction());
        sendBroadcast(intent);
    }

    @Override
    public void sendBroadcast(Intent intent, String receiverPermission, Bundle initialExtras) {
        logd("sendBroadcast called for " + intent.getAction());
        sendBroadcast(intent);
    }

    @Override
    public void sendOrderedBroadcast(Intent intent, String receiverPermission) {
        logd("sendOrderedBroadcast called for " + intent.getAction());
        sendBroadcast(intent);
    }

    @Override
    public void sendOrderedBroadcast(Intent intent, String receiverPermission,
            BroadcastReceiver resultReceiver, Handler scheduler, int initialCode,
            String initialData, Bundle initialExtras) {
        sendOrderedBroadcast(intent, receiverPermission);
        if (resultReceiver != null) {
            synchronized (mOrderedBroadcastReceivers) {
                mOrderedBroadcastReceivers.put(intent, resultReceiver);
            }
        }
    }

    @Override
    public void sendOrderedBroadcast(Intent intent, String receiverPermission, Bundle options,
            BroadcastReceiver resultReceiver, Handler scheduler, int initialCode,
            String initialData, Bundle initialExtras) {
        sendOrderedBroadcast(intent, receiverPermission, resultReceiver, scheduler,
                initialCode, initialData, initialExtras);
    }

    @Override
    public void sendOrderedBroadcast(Intent intent, String receiverPermission, int appOp,
            BroadcastReceiver resultReceiver, Handler scheduler, int initialCode,
            String initialData, Bundle initialExtras) {
        sendOrderedBroadcast(intent, receiverPermission, resultReceiver, scheduler,
                initialCode, initialData, initialExtras);
    }

    @Override
    public void sendBroadcastAsUser(Intent intent, UserHandle user) {
        sendBroadcast(intent);
    }

    @Override
    public void sendBroadcastAsUser(Intent intent, UserHandle user,
            String receiverPermission) {
        sendBroadcast(intent);
    }

    @Override
    public void sendBroadcastAsUser(Intent intent, UserHandle user,
            String receiverPermission, int appOp) {
        sendBroadcast(intent);
    }

    @Override
    public void sendBroadcastAsUser(Intent intent, UserHandle user,
            String receiverPermission, Bundle options) {
        sendBroadcast(intent);
    }

    @Override
    public void sendBroadcastMultiplePermissions(Intent intent, String[] includePermissions,
            String[] excludePermissions) {
        sendBroadcast(intent);
    }

    @Override
    public PackageManager getPackageManager() {
        return mPackageManager;
    }

    @Override
    public ContentResolver getContentResolver() {
        return mContentResolver;
    }

    @Override
    public Object getSystemService(String name) {
        switch (name) {
            case Context.CARRIER_CONFIG_SERVICE: {
                return mMockCarrierConfigManager;
            }
            case Context.CONNECTIVITY_SERVICE: {
                return mMockConnectivityManager;
            }
            case Context.TELECOM_SERVICE: {
                return mMockTelecomManager;
            }
            case Context.TELEPHONY_SERVICE: {
                return mMockTelephonyManager;
            }
            case Context.TELEPHONY_SUBSCRIPTION_SERVICE: {
                return mMockSubscriptionManager;
            }
            case Context.TELEPHONY_IMS_SERVICE: {
                return mMockImsManager;
            }
            case Context.USER_SERVICE: {
                return mMockUserManager;
            }
            case Context.PERMISSION_ENFORCER_SERVICE: {
                return mFakePermissionEnforcer;
            }
            case Context.TELEPHONY_REGISTRY_SERVICE: {
                return mTelephonyRegistryManager;
            }
        }
        return null;
    }

    @Override
    public String getSystemServiceName(Class<?> serviceClass) {
        if (serviceClass == CarrierConfigManager.class) {
            return Context.CARRIER_CONFIG_SERVICE;
        }
        if (serviceClass == ConnectivityManager.class) {
            return Context.CONNECTIVITY_SERVICE;
        }
        if (serviceClass == TelecomManager.class) {
            return Context.TELECOM_SERVICE;
        }
        if (serviceClass == TelephonyManager.class) {
            return Context.TELEPHONY_SERVICE;
        }
        if (serviceClass == SubscriptionManager.class) {
            return Context.TELEPHONY_SUBSCRIPTION_SERVICE;
        }
        if (serviceClass == ImsManager.class) {
            return Context.TELEPHONY_IMS_SERVICE;
        }
        if (serviceClass == UserManager.class) {
            return Context.USER_SERVICE;
        }
        if (serviceClass == PermissionEnforcer.class) {
            return Context.PERMISSION_ENFORCER_SERVICE;
        }
        if (serviceClass == TelephonyRegistryManager.class) {
            return Context.TELEPHONY_REGISTRY_SERVICE;
        }
        return null;
    }

    @Override
    public Looper getMainLooper() {
        return Looper.getMainLooper();
    }

    @Override
    public Handler getMainThreadHandler() {
        return new Handler(Looper.getMainLooper());
    }

    @Override
    public Resources.Theme getTheme() {
        return InstrumentationRegistry.getTargetContext().getTheme();
    }

    /**
     * @return CarrierConfig PersistableBundle for the subscription specified.
     */
    public PersistableBundle getCarrierConfig(int subId) {
        PersistableBundle b = mCarrierConfigs.get(subId);
        if (b == null) {
            b = new PersistableBundle();
            mCarrierConfigs.put(subId, b);
        }
        return b;
    }

    @Override
    public void enforceCallingOrSelfPermission(String permission, String message) {
        if (checkCallingOrSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(permission + " denied: " + message);
        }
    }

    @Override
    public void enforcePermission(String permission, int pid, int uid, String message) {
        enforceCallingOrSelfPermission(permission, message);
    }

    @Override
    public void enforceCallingPermission(String permission, String message) {
        enforceCallingOrSelfPermission(permission, message);
    }

    @Override
    public int checkCallingOrSelfPermission(String permission) {
        return checkPermission(permission, Binder.getCallingPid(), Binder.getCallingUid());
    }

    @Override
    public int checkPermission(String permission, int pid, int uid) {
        synchronized (mPermissionTable) {
            if (mPermissionTable.contains(permission)
                    || mPermissionTable.contains(STUB_PERMISSION_ENABLE_ALL)) {
                logd("checkCallingOrSelfPermission: " + permission + " return GRANTED");
                return PackageManager.PERMISSION_GRANTED;
            } else {
                logd("checkCallingOrSelfPermission: " + permission + " return DENIED");
                return PackageManager.PERMISSION_DENIED;
            }
        }
    }

    @Override
    public Resources getResources() {
        return mResources;
    }


    @Override
    public boolean bindService(Intent service, ServiceConnection conn, int flags) {
        return true;
    }

    @Override
    public void unbindService(ServiceConnection conn) {
        // Override the base implementation to ensure we don't crash.
    }

    public void grantPermission(String permission) {
        synchronized (mPermissionTable) {
            if (permission == null) return;
            mPermissionTable.remove(STUB_PERMISSION_ENABLE_ALL);
            mPermissionTable.add(permission);
        }
    }

    public void revokePermission(String permission) {
        synchronized (mPermissionTable) {
            if (permission == null) return;
            mPermissionTable.remove(permission);
        }
    }

    public void revokeAllPermissions() {
        synchronized (mPermissionTable) {
            mPermissionTable.clear();
        }
    }
    private PersistableBundle getTestConfigs(int subId) {
        if (subId < 0) {
            return new PersistableBundle();
        }
        PersistableBundle b = getCarrierConfig(subId);
        return (b != null ? b : new PersistableBundle());
    }

    private static void logd(String s) {
        Log.d(TAG, s);
    }
}
