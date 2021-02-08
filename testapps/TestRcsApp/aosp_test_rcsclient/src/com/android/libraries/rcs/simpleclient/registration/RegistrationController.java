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

import com.android.libraries.rcs.simpleclient.protocol.sip.SipSession;
import com.android.libraries.rcs.simpleclient.service.ImsService;

import com.google.common.util.concurrent.ListenableFuture;

/**
 * Access to registration functionality.
 */
public interface RegistrationController {

    /**
     * Registers the given ImsService with the backend and returns a SipSession for sending and
     * receiving SIP messages.
     */
    ListenableFuture<SipSession> register(ImsService imsService);

    void deregister();

    void onRegistrationStateChange(RegistrationStateChangeCallback callback);
}
