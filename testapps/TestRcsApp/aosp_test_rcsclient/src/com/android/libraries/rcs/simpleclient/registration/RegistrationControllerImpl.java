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

package com.android.libraries.rcs.simpleclient.registration;

import android.os.Build.VERSION_CODES;
import android.telephony.ims.DelegateRegistrationState;
import android.telephony.ims.DelegateRequest;
import android.telephony.ims.FeatureTagState;
import android.telephony.ims.ImsException;
import android.telephony.ims.ImsManager;
import android.telephony.ims.SipDelegateConnection;
import android.telephony.ims.SipDelegateImsConfiguration;
import android.telephony.ims.SipDelegateManager;
import android.telephony.ims.SipMessage;
import android.telephony.ims.stub.DelegateConnectionMessageCallback;
import android.telephony.ims.stub.DelegateConnectionStateCallback;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.android.libraries.rcs.simpleclient.protocol.sip.SipSession;
import com.android.libraries.rcs.simpleclient.protocol.sip.SipSessionConfiguration;
import com.android.libraries.rcs.simpleclient.protocol.sip.SipSessionListener;
import com.android.libraries.rcs.simpleclient.service.ImsService;

import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import java.text.ParseException;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

import javax.sip.message.Message;

/**
 * Actual implementation built upon SipDelegateConnection as a SIP transport.
 * Feature tag registration state changes will trigger callbacks SimpleRcsClient to
 * enable/disable related ImsServices.
 */
@RequiresApi(api = VERSION_CODES.R)
public class RegistrationControllerImpl implements RegistrationController {
    private static final String TAG = RegistrationControllerImpl.class.getCanonicalName();

    private final Executor executor;
    private final int subscriptionId;
    private SipDelegateManager sipDelegateManager;
    private RegistrationContext context;

    public RegistrationControllerImpl(int subscriptionId, Executor executor,
            ImsManager imsManager) {
        this.subscriptionId = subscriptionId;
        this.executor = executor;
        this.sipDelegateManager = imsManager.getSipDelegateManager(subscriptionId);
    }

    @Override
    public ListenableFuture<SipSession> register(ImsService imsService) {
        Log.i(TAG, "register");
        context = new RegistrationContext(this, imsService);
        context.register();
        return context.getFuture();
    }

    @Override
    public void deregister() {
        Log.i(TAG, "deregister");
        if (context != null && context.sipDelegateConnection != null) {
            sipDelegateManager.destroySipDelegate(context.sipDelegateConnection,
                    SipDelegateManager.SIP_DELEGATE_DESTROY_REASON_REQUESTED_BY_APP);
        }
    }

    @Override
    public void onRegistrationStateChange(RegistrationStateChangeCallback callback) {
        throw new IllegalStateException("Not implemented!");
    }

    /**
     * Envelopes the registration data for a single ImsService instance.
     */
    private static class RegistrationContext implements SipSession, SipSessionConfiguration {

        private final RegistrationControllerImpl controller;
        private final ImsService imsService;
        private final SettableFuture<SipSession> sessionFuture = SettableFuture.create();

        protected SipDelegateConnection sipDelegateConnection;
        private SipDelegateImsConfiguration configuration;
        private final DelegateConnectionStateCallback connectionCallback =
                new DelegateConnectionStateCallback() {

                    @Override
                    public void onCreated(SipDelegateConnection c) {
                        sipDelegateConnection = c;
                    }

                    @Override
                    public void onImsConfigurationChanged(
                            SipDelegateImsConfiguration registeredSipConfig) {
                        Log.d(
                                TAG,
                                "onSipConfigurationChanged: version="
                                        + registeredSipConfig.getVersion()
                                        + " bundle="
                                        + registeredSipConfig.copyBundle());
                        dumpConfig(registeredSipConfig);
                        RegistrationContext.this.configuration = registeredSipConfig;
                    }

                    @Override
                    public void onFeatureTagStatusChanged(
                            @NonNull DelegateRegistrationState registrationState,
                            @NonNull Set<FeatureTagState> deniedFeatureTags) {
                        dumpFeatureTagState(registrationState, deniedFeatureTags);
                        if (registrationState
                                .getRegisteredFeatureTags()
                                .containsAll(imsService.getFeatureTags())) {
                            // registered;
                            sessionFuture.set(RegistrationContext.this);
                        }
                    }

                    @Override
                    public void onDestroyed(int reason) {
                    }
                };
        private SipSessionListener sipSessionListener;
        // Callback for incoming messages on the modem connection
        private final DelegateConnectionMessageCallback messageCallback =
                new DelegateConnectionMessageCallback() {
                    @Override
                    public void onMessageReceived(@NonNull SipMessage message) {
                        SipSessionListener listener = sipSessionListener;
                        if (listener != null) {
                            try {
                                listener.onMessageReceived(
                                        MessageConverter.toStackMessage(message));
                            } catch (ParseException e) {
                                // TODO: logging here
                            }
                        }
                    }

                    @Override
                    public void onMessageSendFailure(@NonNull String viaTransactionId, int reason) {
                    }

                    @Override
                    public void onMessageSent(@NonNull String viaTransactionId) {
                    }

                };

        public RegistrationContext(RegistrationControllerImpl controller,
                ImsService imsService) {
            this.controller = controller;
            this.imsService = imsService;
        }

        public ListenableFuture<SipSession> getFuture() {
            return sessionFuture;
        }

        @Override
        public SipSessionConfiguration getSessionConfiguration() {
            return this;
        }

        public void register() {
            Log.i(TAG, "createSipDelegate");
            DelegateRequest request = new DelegateRequest(imsService.getFeatureTags());
            try {
                controller.sipDelegateManager.createSipDelegate(
                        request, controller.executor, connectionCallback, messageCallback);
            } catch (ImsException e) {
                // TODO: ...
            }
        }

        private void dumpFeatureTagState(DelegateRegistrationState registrationState,
                Set<FeatureTagState> deniedFeatureTags) {
            StringBuilder stringBuilder = new StringBuilder(
                    "onFeatureTagStatusChanged ").append(
                    " deniedFeatureTags:[");
            Iterator<FeatureTagState> iterator = deniedFeatureTags.iterator();
            while (iterator.hasNext()) {
                FeatureTagState featureTagState = iterator.next();
                stringBuilder.append(featureTagState.getFeatureTag()).append(" ").append(
                        featureTagState.getState());
            }
            Set<String> registeredFt = registrationState.getRegisteredFeatureTags();
            Iterator<String> iteratorStr = registeredFt.iterator();
            stringBuilder.append("] registeredFT:[");
            while (iteratorStr.hasNext()) {
                String ft = iteratorStr.next();
                stringBuilder.append(ft).append(" ");
            }
            stringBuilder.append("]");
            String result = stringBuilder.toString();
            Log.i(TAG, result);
        }

        private void dumpConfig(SipDelegateImsConfiguration config) {
            Log.i(TAG, "KEY_SIP_CONFIG_TRANSPORT_TYPE_STRING:" + config.getString(
                    SipDelegateImsConfiguration.KEY_SIP_CONFIG_TRANSPORT_TYPE_STRING));
            Log.i(TAG, "KEY_SIP_CONFIG_UE_PUBLIC_USER_ID_STRING:" + config.getString(
                    SipDelegateImsConfiguration.KEY_SIP_CONFIG_UE_PUBLIC_USER_ID_STRING));
            Log.i(TAG, "KEY_SIP_CONFIG_UE_PRIVATE_USER_ID_STRING:" + config.getString(
                    SipDelegateImsConfiguration.KEY_SIP_CONFIG_UE_PRIVATE_USER_ID_STRING));
            Log.i(TAG, "KEY_SIP_CONFIG_HOME_DOMAIN_STRING:" + config.getString(
                    SipDelegateImsConfiguration.KEY_SIP_CONFIG_HOME_DOMAIN_STRING));
            Log.i(TAG, "KEY_SIP_CONFIG_IMEI_STRING:" + config.getString(
                    SipDelegateImsConfiguration.KEY_SIP_CONFIG_IMEI_STRING));
            Log.i(TAG, "KEY_SIP_CONFIG_IPTYPE_STRING:" + config.getString(
                    SipDelegateImsConfiguration.KEY_SIP_CONFIG_IPTYPE_STRING));
            Log.i(TAG, "KEY_SIP_CONFIG_UE_DEFAULT_IPADDRESS_STRING:" + config.getString(
                    SipDelegateImsConfiguration.KEY_SIP_CONFIG_UE_DEFAULT_IPADDRESS_STRING));
            Log.i(TAG, "KEY_SIP_CONFIG_SERVER_DEFAULT_IPADDRESS_STRING:" + config.getString(
                    SipDelegateImsConfiguration.KEY_SIP_CONFIG_SERVER_DEFAULT_IPADDRESS_STRING));
            Log.i(TAG, "KEY_SIP_CONFIG_UE_PUBLIC_IPADDRESS_WITH_NAT_STRING:" +
                    config.getString(SipDelegateImsConfiguration.
                            KEY_SIP_CONFIG_UE_PUBLIC_IPADDRESS_WITH_NAT_STRING));
            Log.i(TAG, "KEY_SIP_CONFIG_UE_PUBLIC_GRUU_STRING:" + config.getString(
                    SipDelegateImsConfiguration.KEY_SIP_CONFIG_UE_PUBLIC_GRUU_STRING));
            Log.i(TAG, "KEY_SIP_CONFIG_AUTHENTICATION_HEADER_STRING:" + config.getString(
                    SipDelegateImsConfiguration.KEY_SIP_CONFIG_AUTHENTICATION_HEADER_STRING));
            Log.i(TAG, "KEY_SIP_CONFIG_AUTHENTICATION_NONCE_STRING:" + config.getString(
                    SipDelegateImsConfiguration.KEY_SIP_CONFIG_AUTHENTICATION_NONCE_STRING));
            Log.i(TAG, "KEY_SIP_CONFIG_SERVICE_ROUTE_HEADER_STRING:" + config.getString(
                    SipDelegateImsConfiguration.KEY_SIP_CONFIG_SERVICE_ROUTE_HEADER_STRING));
            Log.i(TAG, "KEY_SIP_CONFIG_SECURITY_VERIFY_HEADER_STRING:" + config.getString(
                    SipDelegateImsConfiguration.KEY_SIP_CONFIG_SECURITY_VERIFY_HEADER_STRING));
            Log.i(TAG, "KEY_SIP_CONFIG_PATH_HEADER_STRING:" + config.getString(
                    SipDelegateImsConfiguration.KEY_SIP_CONFIG_PATH_HEADER_STRING));
            Log.i(TAG, "KEY_SIP_CONFIG_URI_USER_PART_STRING:" + config.getString(
                    SipDelegateImsConfiguration.KEY_SIP_CONFIG_URI_USER_PART_STRING));
            Log.i(TAG, "KEY_SIP_CONFIG_P_ACCESS_NETWORK_INFO_HEADER_STRING:" +
                    config.getString(SipDelegateImsConfiguration.
                            KEY_SIP_CONFIG_P_ACCESS_NETWORK_INFO_HEADER_STRING));
            Log.i(TAG, "KEY_SIP_CONFIG_P_LAST_ACCESS_NETWORK_INFO_HEADER_STRING:" +
                    config.getString(SipDelegateImsConfiguration.
                            KEY_SIP_CONFIG_P_LAST_ACCESS_NETWORK_INFO_HEADER_STRING));
            Log.i(TAG, "KEY_SIP_CONFIG_P_ASSOCIATED_URI_HEADER_STRING:" + config.getString(
                    SipDelegateImsConfiguration.KEY_SIP_CONFIG_P_ASSOCIATED_URI_HEADER_STRING));
            Log.i(TAG, "KEY_SIP_CONFIG_USER_AGENT_HEADER_STRING:" + config.getString(
                    SipDelegateImsConfiguration.KEY_SIP_CONFIG_USER_AGENT_HEADER_STRING));

            Log.i(TAG, "KEY_SIP_CONFIG_MAX_PAYLOAD_SIZE_ON_UDP_INT:" + config.getInt(
                    SipDelegateImsConfiguration.KEY_SIP_CONFIG_MAX_PAYLOAD_SIZE_ON_UDP_INT, -99));
            Log.i(TAG, "KEY_SIP_CONFIG_UE_DEFAULT_PORT_INT:" + config.getInt(
                    SipDelegateImsConfiguration.KEY_SIP_CONFIG_UE_DEFAULT_PORT_INT, -99));
            Log.i(TAG, "KEY_SIP_CONFIG_SERVER_DEFAULT_PORT_INT:" + config.getInt(
                    SipDelegateImsConfiguration.KEY_SIP_CONFIG_SERVER_DEFAULT_PORT_INT, -99));
            Log.i(TAG, "KEY_SIP_CONFIG_UE_PUBLIC_PORT_WITH_NAT_INT:" + config.getInt(
                    SipDelegateImsConfiguration.KEY_SIP_CONFIG_UE_PUBLIC_PORT_WITH_NAT_INT, -99));
            Log.i(TAG, "KEY_SIP_CONFIG_UE_IPSEC_CLIENT_PORT_INT:" + config.getInt(
                    SipDelegateImsConfiguration.KEY_SIP_CONFIG_UE_IPSEC_CLIENT_PORT_INT, -99));
            Log.i(TAG, "KEY_SIP_CONFIG_UE_IPSEC_SERVER_PORT_INT:" + config.getInt(
                    SipDelegateImsConfiguration.KEY_SIP_CONFIG_UE_IPSEC_SERVER_PORT_INT, -99));
            Log.i(TAG, "KEY_SIP_CONFIG_UE_IPSEC_OLD_CLIENT_PORT_INT:" + config.getInt(
                    SipDelegateImsConfiguration.KEY_SIP_CONFIG_UE_IPSEC_OLD_CLIENT_PORT_INT, -99));
            Log.i(TAG, "KEY_SIP_CONFIG_SERVER_IPSEC_CLIENT_PORT_INT:" + config.getInt(
                    SipDelegateImsConfiguration.KEY_SIP_CONFIG_SERVER_IPSEC_CLIENT_PORT_INT, -99));
            Log.i(TAG, "KEY_SIP_CONFIG_SERVER_IPSEC_SERVER_PORT_INT:" + config.getInt(
                    SipDelegateImsConfiguration.KEY_SIP_CONFIG_SERVER_IPSEC_SERVER_PORT_INT, -99));
            Log.i(TAG, "KEY_SIP_CONFIG_SERVER_IPSEC_OLD_CLIENT_PORT_INT:" + config.getInt(
                    SipDelegateImsConfiguration.KEY_SIP_CONFIG_SERVER_IPSEC_OLD_CLIENT_PORT_INT,
                    -99));

            Log.i(TAG, "KEY_SIP_CONFIG_IS_COMPACT_FORM_ENABLED_BOOL:" + config.getBoolean(
                    SipDelegateImsConfiguration.KEY_SIP_CONFIG_IS_COMPACT_FORM_ENABLED_BOOL,
                    false));
            Log.i(TAG, "KEY_SIP_CONFIG_IS_KEEPALIVE_ENABLED_BOOL:" + config.getBoolean(
                    SipDelegateImsConfiguration.KEY_SIP_CONFIG_IS_KEEPALIVE_ENABLED_BOOL, false));
            Log.i(TAG, "KEY_SIP_CONFIG_IS_NAT_ENABLED_BOOL:" + config.getBoolean(
                    SipDelegateImsConfiguration.KEY_SIP_CONFIG_IS_NAT_ENABLED_BOOL, false));
            Log.i(TAG, "KEY_SIP_CONFIG_IS_GRUU_ENABLED_BOOL:" + config.getBoolean(
                    SipDelegateImsConfiguration.KEY_SIP_CONFIG_IS_GRUU_ENABLED_BOOL, false));
            Log.i(TAG, "KEY_SIP_CONFIG_IS_IPSEC_ENABLED_BOOL:" + config.getBoolean(
                    SipDelegateImsConfiguration.KEY_SIP_CONFIG_IS_IPSEC_ENABLED_BOOL, false));
        }

        @Override
        public void setSessionListener(SipSessionListener listener) {
            sipSessionListener = listener;
        }

        @Override
        public ListenableFuture<Boolean> send(Message message) {
            sipDelegateConnection.sendMessage(MessageConverter.toPlatformMessage(message),
                    getVersion());
            // TODO: check on transaction
            return Futures.immediateFuture(true);
        }

        // Config values here.

        @Override
        public long getVersion() {
            return configuration.getVersion();
        }

        @Override
        public String getOutboundProxyAddr() {
            return configuration.getString(SipDelegateImsConfiguration.
                    KEY_SIP_CONFIG_SERVER_DEFAULT_IPADDRESS_STRING);
        }

        @Override
        public int getOutboundProxyPort() {
            return configuration.getInt(
                    SipDelegateImsConfiguration.KEY_SIP_CONFIG_SERVER_DEFAULT_PORT_INT, -1);
        }

        @Override
        public String getLocalIpAddress() {
            return configuration.getString(
                    SipDelegateImsConfiguration.KEY_SIP_CONFIG_UE_DEFAULT_IPADDRESS_STRING);
        }

        @Override
        public int getLocalPort() {
            return configuration.getInt(
                    SipDelegateImsConfiguration.KEY_SIP_CONFIG_UE_DEFAULT_PORT_INT, -1);
        }

        @Override
        public String getSipTransport() {
            return configuration.getString(
                    SipDelegateImsConfiguration.KEY_SIP_CONFIG_TRANSPORT_TYPE_STRING);
        }

        @Override
        public String getPublicUserIdentity() {
            return null;
        }

        @Override
        public String getDomain() {
            return configuration.getString(
                    SipDelegateImsConfiguration.KEY_SIP_CONFIG_HOME_DOMAIN_STRING);
        }

        @Override
        public List<String> getAssociatedUris() {
            String associatedUris = configuration.getString(
                    SipDelegateImsConfiguration.KEY_SIP_CONFIG_P_ASSOCIATED_URI_HEADER_STRING);
            if (!TextUtils.isEmpty(associatedUris)) {
                return Splitter.on(',').trimResults(CharMatcher.anyOf("<>")).splitToList(
                        associatedUris);
            }

            return ImmutableList.of();
        }

        @Override
        public String getSecurityVerifyHeader() {
            return configuration.getString(
                    SipDelegateImsConfiguration.KEY_SIP_CONFIG_SECURITY_VERIFY_HEADER_STRING);
        }

        @Override
        public List<String> getServiceRouteHeaders() {
            String serviceRoutes =
                    configuration.getString(
                            SipDelegateImsConfiguration.KEY_SIP_CONFIG_SERVICE_ROUTE_HEADER_STRING);
            if (TextUtils.isEmpty(serviceRoutes)) {
                return Collections.emptyList();
            }
            return Splitter.on(',').trimResults().splitToList(serviceRoutes);
        }

        @Override
        public String getContactUser() {
            return configuration.getString(
                    SipDelegateImsConfiguration.KEY_SIP_CONFIG_URI_USER_PART_STRING);
        }

        @Override
        public String getImei() {
            return configuration.getString(SipDelegateImsConfiguration.KEY_SIP_CONFIG_IMEI_STRING);
        }

        @Override
        public String getPaniHeader() {
            return configuration.getString(
                    SipDelegateImsConfiguration.KEY_SIP_CONFIG_P_ACCESS_NETWORK_INFO_HEADER_STRING);
        }

        @Override
        public String getPlaniHeader() {
            return configuration.getString(
                    SipDelegateImsConfiguration.
                            KEY_SIP_CONFIG_P_LAST_ACCESS_NETWORK_INFO_HEADER_STRING);
        }

        @Override
        public String getUserAgentHeader() {
            return configuration.getString(
                    SipDelegateImsConfiguration.KEY_SIP_CONFIG_USER_AGENT_HEADER_STRING);
        }

        @Override
        public int getMaxPayloadSizeOnUdp() {
            return configuration.getInt(
                    SipDelegateImsConfiguration.KEY_SIP_CONFIG_MAX_PAYLOAD_SIZE_ON_UDP_INT, 1500);
        }
    }
}

