/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.services.telephony.rcs.validator;

import android.telephony.ims.SipDelegateManager;
import android.telephony.ims.SipMessage;
import android.util.ArrayMap;

/**
 * Tracks the state of the outgoing SIP message transport from the remote IMS application to the
 * ImsService. Used to validate outgoing SIP messages based off of this state.
 */
public class OutgoingTransportStateValidator implements SipMessageValidator {

    /**
     * The message transport is closed, meaning there can be no more outgoing messages
     */
    private static final int STATE_CLOSED = 0;

    /**
     * The message transport is restricted to only in-dialog outgoing traffic
     */
    private static final int STATE_RESTRICTED = 1;

    /**
     * The message transport is open and outgoing traffic is not restricted.
     */
    private static final int STATE_OPEN = 2;

    private static final ArrayMap<Integer, String> ENUM_TO_STRING_MAP  = new ArrayMap<>(3);
    static {
        ENUM_TO_STRING_MAP.append(STATE_CLOSED, "CLOSED");
        ENUM_TO_STRING_MAP.append(STATE_RESTRICTED, "RESTRICTED");
        ENUM_TO_STRING_MAP.append(STATE_OPEN, "OPEN");
    }

    private int mState = STATE_CLOSED;
    private int mReason = SipDelegateManager.MESSAGE_FAILURE_REASON_DELEGATE_CLOSED;

    /**
     * The SIP message transport is open and will successfully validate both in and out of dialog
     * SIP messages.
     */
    public void open() {
        mState = STATE_OPEN;
        mReason = -1;
    }

    /**
     * The SIP message transport is restricted and only allows in-dialog outgoing messages.
     * @param reason The reason that will be returned to outgoing out-of-dialog SIP messages that
     *               are denied.
     */
    public void restrict(int reason) {
        mState = STATE_RESTRICTED;
        mReason = reason;
    }

    /**
     * The SIP message transport is closed for outgoing SIP messages.
     * @param reason The error reason sent in response to any outgoing SIP messages requests.
     */
    public void close(int reason) {
        mState = STATE_CLOSED;
        mReason = reason;
    }

    @Override
    public ValidationResult validate(SipMessage message) {
        // TODO: integrate in and out-of-dialog message detection as well as supported & denied tags
        if (mState != STATE_OPEN) {
            return new ValidationResult(mReason);
        }
        return ValidationResult.SUCCESS;
    }

    @Override
    public String toString() {
        return "Outgoing Transport State: " + ENUM_TO_STRING_MAP.getOrDefault(mState,
                String.valueOf(mState)) + ", reason: "
                + SipDelegateManager.MESSAGE_FAILURE_REASON_STRING_MAP.getOrDefault(mReason,
                String.valueOf(mReason));
    }
}
