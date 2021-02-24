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

package com.android.phone.callcomposer;

import android.content.Context;
import android.location.Location;
import android.net.Uri;
import android.os.OutcomeReceiver;
import android.os.UserHandle;
import android.provider.CallLog;
import android.telephony.CarrierConfigManager;
import android.telephony.TelephonyManager;
import android.telephony.gba.UaSecurityProtocolIdentifier;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;
import com.android.phone.callcomposer.CallComposerPictureTransfer.PictureCallback;
import com.android.phone.R;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class CallComposerPictureManager {
    private static final String TAG = CallComposerPictureManager.class.getSimpleName();
    private static final SparseArray<CallComposerPictureManager> sInstances = new SparseArray<>();

    public static CallComposerPictureManager getInstance(Context context, int subscriptionId) {
        synchronized (sInstances) {
            if (sExecutorService == null) {
                sExecutorService = Executors.newSingleThreadScheduledExecutor();
            }
            if (!sInstances.contains(subscriptionId)) {
                sInstances.put(subscriptionId,
                        new CallComposerPictureManager(context, subscriptionId));
            }
            return sInstances.get(subscriptionId);
        }
    }

    @VisibleForTesting
    public static void clearInstances() {
        synchronized (sInstances) {
            sInstances.clear();
            if (sExecutorService != null) {
                sExecutorService.shutdown();
                sExecutorService = null;
            }
        }
    }

    // disabled provisionally until the auth stack is fully operational
    @VisibleForTesting
    public static boolean sTestMode = false;
    public static final String FAKE_SERVER_URL = "https://example.com/FAKE.png";
    public static final String FAKE_SUBJECT = "This is a test call subject";
    public static final Location FAKE_LOCATION = new Location("");
    static {
        // Meteor Crater, AZ
        FAKE_LOCATION.setLatitude(35.027526);
        FAKE_LOCATION.setLongitude(-111.021696);
    }

    public interface CallLogProxy {
        default void storeCallComposerPictureAsUser(Context context,
                UserHandle user,
                InputStream input,
                Executor executor,
                OutcomeReceiver<Uri, CallLog.CallComposerLoggingException> callback) {
            CallLog.storeCallComposerPictureAsUser(context, user, input, executor, callback);
        }
    }

    private static ScheduledExecutorService sExecutorService = null;

    private final HashMap<UUID, String> mCachedServerUrls = new HashMap<>();
    private final HashMap<UUID, ImageData> mCachedImages = new HashMap<>();
    private final Map<String, GbaCredentials> mCachedCredentials = new HashMap<>();
    private final int mSubscriptionId;
    private final TelephonyManager mTelephonyManager;
    private final Context mContext;
    private CallLogProxy mCallLogProxy = new CallLogProxy() {};

    private CallComposerPictureManager(Context context, int subscriptionId) {
        mContext = context;
        mSubscriptionId = subscriptionId;
        mTelephonyManager = mContext.getSystemService(TelephonyManager.class)
                .createForSubscriptionId(mSubscriptionId);
    }

    public void handleUploadToServer(CallComposerPictureTransfer.Factory transferFactory,
            ImageData imageData, Consumer<Pair<UUID, Integer>> callback) {
        if (sTestMode) {
            UUID id = UUID.randomUUID();
            mCachedImages.put(id, imageData);
            mCachedServerUrls.put(id, FAKE_SERVER_URL);
            callback.accept(Pair.create(id, TelephonyManager.CallComposerException.SUCCESS));
            return;
        }

        String uploadUrl = mTelephonyManager.getCarrierConfig().getString(
                CarrierConfigManager.KEY_CALL_COMPOSER_PICTURE_SERVER_URL_STRING);
        if (TextUtils.isEmpty(uploadUrl)) {
            Log.e(TAG, "Call composer upload URL not configured in carrier config");
            callback.accept(Pair.create(null,
                    TelephonyManager.CallComposerException.ERROR_UNKNOWN));
        }
        UUID id = UUID.randomUUID();
        imageData.setId(id.toString());

        CallComposerPictureTransfer transfer = transferFactory.create(mContext,
                mSubscriptionId, uploadUrl, sExecutorService);

        AtomicBoolean hasRetried = new AtomicBoolean(false);
        transfer.setCallback(new PictureCallback() {
            @Override
            public void onError(int error) {
                callback.accept(Pair.create(null, error));
            }

            @Override
            public void onRetryNeeded(boolean credentialRefresh, long backoffMillis) {
                if (hasRetried.getAndSet(true)) {
                    Log.e(TAG, "Giving up on image upload after one retry.");
                    callback.accept(Pair.create(null,
                            TelephonyManager.CallComposerException.ERROR_NETWORK_UNAVAILABLE));
                    return;
                }
                GbaCredentialsSupplier supplier =
                        (realm, executor) ->
                                getGbaCredentials(credentialRefresh, realm, executor);

                sExecutorService.schedule(() -> transfer.uploadPicture(imageData, supplier),
                        backoffMillis, TimeUnit.MILLISECONDS);
            }

            @Override
            public void onUploadSuccessful(String serverUrl) {
                mCachedServerUrls.put(id, serverUrl);
                mCachedImages.put(id, imageData);
                Log.i(TAG, "Successfully received url: " + serverUrl + " associated with "
                        + id.toString());
                callback.accept(Pair.create(id, TelephonyManager.CallComposerException.SUCCESS));
            }
        });

        transfer.uploadPicture(imageData,
                (realm, executor) -> getGbaCredentials(false, realm, executor));
    }

    public void handleDownloadFromServer(CallComposerPictureTransfer.Factory transferFactory,
            String remoteUrl, Consumer<Pair<Uri, Integer>> callback) {
        if (sTestMode) {
            ImageData imageData = new ImageData(getPlaceholderPictureAsBytes(), "image/png", null);
            UUID id = UUID.randomUUID();
            mCachedImages.put(id, imageData);
            storeUploadedPictureToCallLog(id, uri -> callback.accept(Pair.create(uri, -1)));
            return;
        }

        CallComposerPictureTransfer transfer = transferFactory.create(mContext,
                mSubscriptionId, remoteUrl, sExecutorService);

        AtomicBoolean hasRetried = new AtomicBoolean(false);
        transfer.setCallback(new PictureCallback() {
            @Override
            public void onError(int error) {
                callback.accept(Pair.create(null, error));
            }

            @Override
            public void onRetryNeeded(boolean credentialRefresh, long backoffMillis) {
                if (hasRetried.getAndSet(true)) {
                    Log.e(TAG, "Giving up on image download after one retry.");
                    callback.accept(Pair.create(null,
                            TelephonyManager.CallComposerException.ERROR_NETWORK_UNAVAILABLE));
                    return;
                }
                GbaCredentialsSupplier supplier =
                        (realm, executor) ->
                                getGbaCredentials(credentialRefresh, realm, executor);

                sExecutorService.schedule(() -> transfer.downloadPicture(supplier),
                        backoffMillis, TimeUnit.MILLISECONDS);
            }

            @Override
            public void onDownloadSuccessful(ImageData data) {
                ByteArrayInputStream imageDataInput =
                        new ByteArrayInputStream(data.getImageBytes());
                mCallLogProxy.storeCallComposerPictureAsUser(
                        mContext, UserHandle.CURRENT, imageDataInput,
                        sExecutorService,
                        new OutcomeReceiver<Uri, CallLog.CallComposerLoggingException>() {
                            @Override
                            public void onResult(@NonNull Uri result) {
                                callback.accept(Pair.create(
                                        result, TelephonyManager.CallComposerException.SUCCESS));
                            }

                            @Override
                            public void onError(CallLog.CallComposerLoggingException e) {
                                // Just report an error to the client for now.
                                callback.accept(Pair.create(null,
                                        TelephonyManager.CallComposerException.ERROR_UNKNOWN));
                            }
                        });
            }
        });

        transfer.downloadPicture(((realm, executor) -> getGbaCredentials(false, realm, executor)));
    }

    public void storeUploadedPictureToCallLog(UUID id, Consumer<Uri> callback) {
        ImageData data = mCachedImages.get(id);
        if (data == null) {
            Log.e(TAG, "No picture associated with uuid " + id);
            callback.accept(null);
            return;
        }
        ByteArrayInputStream imageDataInput =
                new ByteArrayInputStream(data.getImageBytes());
        mCallLogProxy.storeCallComposerPictureAsUser(mContext, UserHandle.CURRENT, imageDataInput,
                sExecutorService,
                new OutcomeReceiver<Uri, CallLog.CallComposerLoggingException>() {
                    @Override
                    public void onResult(@NonNull Uri result) {
                        callback.accept(result);
                        clearCachedData();
                    }

                    @Override
                    public void onError(CallLog.CallComposerLoggingException e) {
                        // Just report an error to the client for now.
                        Log.e(TAG, "Error logging uploaded image: " + e.getErrorCode());
                        callback.accept(null);
                        clearCachedData();
                    }
                });
    }

    public String getServerUrlForImageId(UUID id) {
        return mCachedServerUrls.get(id);
    }

    public void clearCachedData() {
        mCachedServerUrls.clear();
        mCachedImages.clear();
    }

    private byte[] getPlaceholderPictureAsBytes() {
        InputStream resourceInput = mContext.getResources().openRawResource(R.drawable.cupcake);
        try {
            return readBytes(resourceInput);
        } catch (Exception e) {
            return new byte[] {};
        }
    }

    private static byte[] readBytes(InputStream inputStream) throws Exception {
        byte[] buffer = new byte[1024];
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int numRead;
        do {
            numRead = inputStream.read(buffer);
            if (numRead > 0) output.write(buffer, 0, numRead);
        } while (numRead > 0);
        return output.toByteArray();
    }

    private CompletableFuture<GbaCredentials> getGbaCredentials(
            boolean forceRefresh, String nafId, Executor executor) {
        synchronized (mCachedCredentials) {
            if (!forceRefresh && mCachedCredentials.containsKey(nafId)) {
                return CompletableFuture.completedFuture(mCachedCredentials.get(nafId));
            }
            if (forceRefresh) {
                mCachedCredentials.remove(nafId);
            }
        }

        UaSecurityProtocolIdentifier securityProtocolIdentifier =
                new UaSecurityProtocolIdentifier.Builder()
                        .setOrg(UaSecurityProtocolIdentifier.ORG_3GPP)
                        .setProtocol(UaSecurityProtocolIdentifier
                                .UA_SECURITY_PROTOCOL_3GPP_HTTP_DIGEST_AUTHENTICATION)
                        .build();
        CompletableFuture<GbaCredentials> resultFuture = new CompletableFuture<>();

        mTelephonyManager.bootstrapAuthenticationRequest(TelephonyManager.APPTYPE_UNKNOWN,
                Uri.parse(nafId), securityProtocolIdentifier, forceRefresh, executor,
                new TelephonyManager.BootstrapAuthenticationCallback() {
                    @Override
                    public void onKeysAvailable(byte[] gbaKey, String transactionId) {
                        GbaCredentials creds = new GbaCredentials(transactionId, gbaKey);
                        synchronized (mCachedCredentials) {
                            mCachedCredentials.put(nafId, creds);
                        }
                        resultFuture.complete(creds);
                    }

                    @Override
                    public void onAuthenticationFailure(int reason) {
                        Log.e(TAG, "GBA auth failed: reason=" + reason);
                        resultFuture.complete(null);
                    }
                });

        return resultFuture;
    }

    @VisibleForTesting
    static ScheduledExecutorService getExecutor() {
        return sExecutorService;
    }

    @VisibleForTesting
    void setCallLogProxy(CallLogProxy proxy) {
        mCallLogProxy = proxy;
    }
}
