/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.services.telephony;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.CarrierConfigManager;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.SubscriptionManager.OnSubscriptionsChangedListener;
import android.telephony.TelephonyManager;
import android.telephony.ims.ImsException;
import android.telephony.ims.ImsMmTelManager;
import android.telephony.ims.feature.MmTelFeature;
import android.telephony.ims.stub.ImsRegistrationImplBase;
import android.text.TextUtils;

import com.android.ims.ImsManager;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.telephony.SubscriptionController;
import com.android.phone.PhoneGlobals;
import com.android.phone.PhoneUtils;
import com.android.phone.R;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

import org.codeaurora.internal.IExtTelephony;

/**
 * Owns all data we have registered with Telecom including handling dynamic addition and
 * removal of SIMs and SIP accounts.
 */
public class TelecomAccountRegistry {
    private static final boolean DBG = false; /* STOP SHIP if true */

    // This icon is the one that is used when the Slot ID that we have for a particular SIM
    // is not supported, i.e. SubscriptionManager.INVALID_SLOT_ID or the 5th SIM in a phone.
    private final static int DEFAULT_SIM_ICON =  R.drawable.ic_multi_sim;
    private final static String GROUP_PREFIX = "group_";

    // Flag which decides whether SIM should power down due to APM,
    private static final String APM_SIM_NOT_PWDN_PROPERTY = "persist.vendor.radio.apm_sim_not_pwdn";

    private enum Count {
        ZERO,
        ONE,
        TWO
    }

    final class AccountEntry implements PstnPhoneCapabilitiesNotifier.Listener {
        private final Phone mPhone;
        private PhoneAccount mAccount;
        private final PstnIncomingCallNotifier mIncomingCallNotifier;
        private final PstnPhoneCapabilitiesNotifier mPhoneCapabilitiesNotifier;
        private boolean mIsEmergency;
        private boolean mIsRttCapable;
        private boolean mIsEmergencyPreferred;
        private MmTelFeature.MmTelCapabilities mMmTelCapabilities;
        private ImsMmTelManager.CapabilityCallback mMmtelCapabilityCallback;
        private ImsMmTelManager mMmTelManager;
        private final boolean mIsDummy;
        private boolean mIsVideoCapable;
        private boolean mIsVideoPresenceSupported;
        private boolean mIsVideoPauseSupported;
        private boolean mIsMergeCallSupported;
        private boolean mIsMergeImsCallSupported;
        private boolean mIsVideoConferencingSupported;
        private boolean mIsMergeOfWifiCallsAllowedWhenVoWifiOff;
        private boolean mIsManageImsConferenceCallSupported;
        private boolean mIsUsingSimCallManager;
        private boolean mIsShowPreciseFailedCause;

        AccountEntry(Phone phone, boolean isEmergency, boolean isDummy) {
            mPhone = phone;
            mIsEmergency = isEmergency;
            mIsDummy = isDummy;
            mAccount = registerPstnPhoneAccount(isEmergency, isDummy);
            Log.i(this, "Registered phoneAccount: %s with handle: %s",
                    mAccount, mAccount.getAccountHandle());
            mIncomingCallNotifier = new PstnIncomingCallNotifier((Phone) mPhone);
            mPhoneCapabilitiesNotifier = new PstnPhoneCapabilitiesNotifier((Phone) mPhone,
                    this);

            if (mIsDummy || isEmergency) {
                // For dummy and emergency entries, there is no sub ID that can be assigned, so do
                // not register for capabilities callbacks.
                return;
            }

            try {
                if (mPhone.getContext().getPackageManager().hasSystemFeature(
                        PackageManager.FEATURE_TELEPHONY_IMS)) {
                    mMmTelManager = ImsMmTelManager.createForSubscriptionId(getSubId());
                }
            } catch (IllegalArgumentException e) {
                Log.i(this, "Not registering MmTel capabilities listener because the subid '"
                        + getSubId() + "' is invalid: " + e.getMessage());
                return;
            }

            mMmtelCapabilityCallback = new ImsMmTelManager.CapabilityCallback() {
                @Override
                public void onCapabilitiesStatusChanged(
                        MmTelFeature.MmTelCapabilities capabilities) {
                    mMmTelCapabilities = capabilities;
                    updateRttCapability();
                }
            };

            registerMmTelCapabilityCallback();
        }

        void teardown() {
            mIncomingCallNotifier.teardown();
            mPhoneCapabilitiesNotifier.teardown();
            if (mMmTelManager != null && mMmtelCapabilityCallback != null) {
                mMmTelManager.unregisterMmTelCapabilityCallback(mMmtelCapabilityCallback);
            }
        }

        private void registerMmTelCapabilityCallback() {
            if (mMmTelManager == null || mMmtelCapabilityCallback == null) {
                // The subscription id associated with this account is invalid or not associated
                // with a subscription. Do not register in this case.
                return;
            }

            try {
                mMmTelManager.registerMmTelCapabilityCallback(mContext.getMainExecutor(),
                        mMmtelCapabilityCallback);
            } catch (ImsException e) {
                Log.w(this, "registerMmTelCapabilityCallback: registration failed, no ImsService"
                        + " available. Exception: " + e.getMessage());
                return;
            } catch (IllegalArgumentException e) {
                Log.w(this, "registerMmTelCapabilityCallback: registration failed, invalid"
                        + " subscription, Exception" + e.getMessage());
                return;
            }
        }

        /**
         * Trigger re-registration of this account.
         */
        public void reRegisterPstnPhoneAccount() {
            PhoneAccount newAccount = buildPstnPhoneAccount(mIsEmergency, mIsDummy);
            if (!newAccount.equals(mAccount)) {
                Log.i(this, "reRegisterPstnPhoneAccount: subId: " + getSubId()
                        + " - re-register due to account change.");
                mTelecomManager.registerPhoneAccount(newAccount);
                mAccount = newAccount;
            } else {
                Log.i(this, "reRegisterPstnPhoneAccount: subId: " + getSubId() + " - no change");
            }
        }

        private PhoneAccount registerPstnPhoneAccount(boolean isEmergency, boolean isDummyAccount) {
            PhoneAccount account = buildPstnPhoneAccount(mIsEmergency, mIsDummy);
            // Register with Telecom and put into the account entry.
            mTelecomManager.registerPhoneAccount(account);
            return account;
        }

        /**
         * Registers the specified account with Telecom as a PhoneAccountHandle.
         */
        private PhoneAccount buildPstnPhoneAccount(boolean isEmergency, boolean isDummyAccount) {
            String dummyPrefix = isDummyAccount ? "Dummy " : "";

            // Build the Phone account handle.
            PhoneAccountHandle phoneAccountHandle =
                    PhoneUtils.makePstnPhoneAccountHandleWithPrefix(
                            mPhone, dummyPrefix, isEmergency);

            // Populate the phone account data.
            int subId = mPhone.getSubId();
            String subscriberId = mPhone.getSubscriberId();
            int color = PhoneAccount.NO_HIGHLIGHT_COLOR;
            int slotId = SubscriptionManager.INVALID_SIM_SLOT_INDEX;
            String line1Number = mTelephonyManager.getLine1Number(subId);
            if (line1Number == null) {
                line1Number = "";
            }
            String subNumber = mPhone.getLine1Number();
            if (subNumber == null) {
                subNumber = "";
            }

            String label;
            String description;
            Icon icon = null;

            // We can only get the real slotId from the SubInfoRecord, we can't calculate the
            // slotId from the subId or the phoneId in all instances.
            SubscriptionInfo record =
                    mSubscriptionManager.getActiveSubscriptionInfo(subId);

            if (isEmergency) {
                label = mContext.getResources().getString(R.string.sim_label_emergency_calls);
                description =
                        mContext.getResources().getString(R.string.sim_description_emergency_calls);
            } else if (mTelephonyManager.getPhoneCount() == 1) {
                // For single-SIM devices, we show the label and description as whatever the name of
                // the network is.
                description = label = mTelephonyManager.getNetworkOperatorName();
            } else {
                CharSequence subDisplayName = null;

                if (record != null) {
                    subDisplayName = record.getDisplayName();
                    slotId = record.getSimSlotIndex();
                    color = record.getIconTint();
                    icon = Icon.createWithBitmap(record.createIconBitmap(mContext));
                }

                String slotIdString;
                if (SubscriptionManager.isValidSlotIndex(slotId)) {
                    slotIdString = Integer.toString(slotId);
                } else {
                    slotIdString = mContext.getResources().getString(R.string.unknown);
                }

                if (TextUtils.isEmpty(subDisplayName)) {
                    // Either the sub record is not there or it has an empty display name.
                    Log.w(this, "Could not get a display name for subid: %d", subId);
                    subDisplayName = mContext.getResources().getString(
                            R.string.sim_description_default, slotIdString);
                }

                // The label is user-visible so let's use the display name that the user may
                // have set in Settings->Sim cards.
                label = dummyPrefix + subDisplayName;
                description = dummyPrefix + mContext.getResources().getString(
                                R.string.sim_description_default, slotIdString);
            }

            // By default all SIM phone accounts can place emergency calls.
            int capabilities = PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION |
                    PhoneAccount.CAPABILITY_CALL_PROVIDER |
                    PhoneAccount.CAPABILITY_MULTI_USER;

            if (mContext.getResources().getBoolean(R.bool.config_pstnCanPlaceEmergencyCalls)) {
                capabilities |= PhoneAccount.CAPABILITY_PLACE_EMERGENCY_CALLS;
            }

            mIsEmergencyPreferred = isEmergencyPreferredAccount(subId, mActiveDataSubscriptionId);
            if (mIsEmergencyPreferred) {
                capabilities |= PhoneAccount.CAPABILITY_EMERGENCY_PREFERRED;
            }

            mIsVideoCapable = mPhone.isVideoEnabled();
            boolean isVideoEnabledByPlatform = ImsManager.getInstance(mPhone.getContext(),
                    mPhone.getPhoneId()).isVtEnabledByPlatform();

            if (!mIsPrimaryUser) {
                Log.i(this, "Disabling video calling for secondary user.");
                mIsVideoCapable = false;
                isVideoEnabledByPlatform = false;
            }

            if (mIsVideoCapable) {
                capabilities |= PhoneAccount.CAPABILITY_VIDEO_CALLING;
            }

            if (isVideoEnabledByPlatform) {
                capabilities |= PhoneAccount.CAPABILITY_SUPPORTS_VIDEO_CALLING;
            }

            mIsVideoPresenceSupported = isCarrierVideoPresenceSupported();
            if (mIsVideoCapable && mIsVideoPresenceSupported) {
                capabilities |= PhoneAccount.CAPABILITY_VIDEO_CALLING_RELIES_ON_PRESENCE;
            }

            if (mIsVideoCapable && isCarrierEmergencyVideoCallsAllowed()) {
                capabilities |= PhoneAccount.CAPABILITY_EMERGENCY_VIDEO_CALLING;
            }

            mIsVideoPauseSupported = isCarrierVideoPauseSupported();
            Bundle extras = new Bundle();
            if (isCarrierInstantLetteringSupported()) {
                capabilities |= PhoneAccount.CAPABILITY_CALL_SUBJECT;
                extras.putAll(getPhoneAccountExtras());
            }

            final boolean isHandoverFromSupported = mContext.getResources().getBoolean(
                    R.bool.config_support_handover_from);
            if (isHandoverFromSupported && !isEmergency) {
                // Only set the extra is handover is supported and this isn't the emergency-only
                // acct.
                extras.putBoolean(PhoneAccount.EXTRA_SUPPORTS_HANDOVER_FROM,
                        isHandoverFromSupported);
            }

            final boolean isTelephonyAudioDeviceSupported = mContext.getResources().getBoolean(
                    R.bool.config_support_telephony_audio_device);
            if (isTelephonyAudioDeviceSupported && !isEmergency
                    && isCarrierUseCallRecordingTone()) {
                extras.putBoolean(PhoneAccount.EXTRA_PLAY_CALL_RECORDING_TONE, true);
            }

            if (isRttCurrentlySupported()) {
                capabilities |= PhoneAccount.CAPABILITY_RTT;
                mIsRttCapable = true;
            } else {
                mIsRttCapable = false;
            }

            extras.putBoolean(PhoneAccount.EXTRA_SUPPORTS_VIDEO_CALLING_FALLBACK,
                    mContext.getResources()
                            .getBoolean(R.bool.config_support_video_calling_fallback));

            if (slotId != SubscriptionManager.INVALID_SIM_SLOT_INDEX) {
                extras.putString(PhoneAccount.EXTRA_SORT_ORDER,
                    String.valueOf(slotId));
            }

            mIsMergeCallSupported = isCarrierMergeCallSupported();
            mIsMergeImsCallSupported = isCarrierMergeImsCallSupported();
            mIsVideoConferencingSupported = isCarrierVideoConferencingSupported();
            mIsMergeOfWifiCallsAllowedWhenVoWifiOff =
                    isCarrierMergeOfWifiCallsAllowedWhenVoWifiOff();
            mIsManageImsConferenceCallSupported = isCarrierManageImsConferenceCallSupported();
            mIsUsingSimCallManager = isCarrierUsingSimCallManager();
            mIsShowPreciseFailedCause = isCarrierShowPreciseFailedCause();

            if (isEmergency && mContext.getResources().getBoolean(
                    R.bool.config_emergency_account_emergency_calls_only)) {
                capabilities |= PhoneAccount.CAPABILITY_EMERGENCY_CALLS_ONLY;
            }

            if (icon == null) {
                // TODO: Switch to using Icon.createWithResource() once that supports tinting.
                Resources res = mContext.getResources();
                Drawable drawable = res.getDrawable(DEFAULT_SIM_ICON, null);
                drawable.setTint(res.getColor(R.color.default_sim_icon_tint_color, null));
                drawable.setTintMode(PorterDuff.Mode.SRC_ATOP);

                int width = drawable.getIntrinsicWidth();
                int height = drawable.getIntrinsicHeight();
                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
                drawable.draw(canvas);

                icon = Icon.createWithBitmap(bitmap);
            }

            // Check to see if the newly registered account should replace the old account.
            String groupId = "";
            String[] mergedImsis = mTelephonyManager.getMergedSubscriberIds();
            boolean isMergedSim = false;
            if (mergedImsis != null && subscriberId != null && !isEmergency) {
                for (String imsi : mergedImsis) {
                    if (imsi.equals(subscriberId)) {
                        isMergedSim = true;
                        break;
                    }
                }
            }
            if(isMergedSim) {
                groupId = GROUP_PREFIX + line1Number;
                Log.i(this, "Adding Merged Account with group: " + Log.pii(groupId));
            }

            PhoneAccount account = PhoneAccount.builder(phoneAccountHandle, label)
                    .setAddress(Uri.fromParts(PhoneAccount.SCHEME_TEL, line1Number, null))
                    .setSubscriptionAddress(
                            Uri.fromParts(PhoneAccount.SCHEME_TEL, subNumber, null))
                    .setCapabilities(capabilities)
                    .setIcon(icon)
                    .setHighlightColor(color)
                    .setShortDescription(description)
                    .setSupportedUriSchemes(Arrays.asList(
                            PhoneAccount.SCHEME_TEL, PhoneAccount.SCHEME_VOICEMAIL))
                    .setExtras(extras)
                    .setGroupId(groupId)
                    .build();

            return account;
        }

        public PhoneAccountHandle getPhoneAccountHandle() {
            return mAccount != null ? mAccount.getAccountHandle() : null;
        }

        public int getSubId() {
            return mPhone.getSubId();
        }

        /**
         * In some cases, we need to try sending the emergency call over this PhoneAccount due to
         * restrictions and limitations in MSIM configured devices. This includes the following:
         * 1) The device does not support GNSS SUPL requests on the non-DDS subscription due to
         *   modem limitations. If the device does not support SUPL on non-DDS, we need to try the
         *   emergency call on the DDS subscription first to allow for SUPL to be completed.
         *
         * @return true if Telecom should prefer this PhoneAccount, false if there is no preference
         * needed.
         */
        private boolean isEmergencyPreferredAccount(int subId, int activeDataSubId) {
            Log.d(this, "isEmergencyPreferredAccount: subId=" + subId + ", activeData="
                    + activeDataSubId);
            final boolean gnssSuplRequiresDefaultData = mContext.getResources().getBoolean(
                    R.bool.config_gnss_supl_requires_default_data_for_emergency);
            if (!gnssSuplRequiresDefaultData) {
                Log.d(this, "isEmergencyPreferredAccount: Device does not require preference.");
                // No preference is necessary.
                return false;
            }

            SubscriptionController controller = SubscriptionController.getInstance();
            if (controller == null) {
                Log.d(this, "isEmergencyPreferredAccount: SubscriptionController not available.");
                return false;
            }
            // Only set an emergency preference on devices with multiple active subscriptions
            // (include opportunistic subscriptions) in this check.
            // API says never null, but this can return null in testing.
            int[] activeSubIds = controller.getActiveSubIdList(false);
            if (activeSubIds == null || activeSubIds.length <= 1) {
                Log.d(this, "isEmergencyPreferredAccount: one or less active subscriptions.");
                return false;
            }
            // Check to see if this PhoneAccount is associated with the default Data subscription.
            if (!SubscriptionManager.isValidSubscriptionId(subId)) {
                Log.d(this, "isEmergencyPreferredAccount: provided subId " + subId + "is not "
                        + "valid.");
                return false;
            }
            int userDefaultData = controller.getDefaultDataSubId();
            boolean isActiveDataValid = SubscriptionManager.isValidSubscriptionId(activeDataSubId);
            boolean isActiveDataOpportunistic = isActiveDataValid
                    && controller.isOpportunistic(activeDataSubId);
            // compare the activeDataSubId to the subId specified only if it is valid and not an
            // opportunistic subscription (only supports data). If not, use the current default
            // defined by the user.
            Log.d(this, "isEmergencyPreferredAccount: userDefaultData=" + userDefaultData
                    + ", isActiveDataOppurtunistic=" + isActiveDataOpportunistic);
            return subId == ((isActiveDataValid && !isActiveDataOpportunistic) ? activeDataSubId :
                    userDefaultData);
        }

        /**
         * Determines from carrier configuration whether pausing of IMS video calls is supported.
         *
         * @return {@code true} if pausing IMS video calls is supported.
         */
        private boolean isCarrierVideoPauseSupported() {
            // Check if IMS video pause is supported.
            PersistableBundle b =
                    PhoneGlobals.getInstance().getCarrierConfigForSubId(mPhone.getSubId());
            return b != null &&
                    b.getBoolean(CarrierConfigManager.KEY_SUPPORT_PAUSE_IMS_VIDEO_CALLS_BOOL);
        }

        /**
         * Determines from carrier configuration whether RCS presence indication for video calls is
         * supported.
         *
         * @return {@code true} if RCS presence indication for video calls is supported.
         */
        private boolean isCarrierVideoPresenceSupported() {
            PersistableBundle b =
                    PhoneGlobals.getInstance().getCarrierConfigForSubId(mPhone.getSubId());
            return b != null &&
                    b.getBoolean(CarrierConfigManager.KEY_USE_RCS_PRESENCE_BOOL);
        }

        /**
         * Determines from carrier config whether instant lettering is supported.
         *
         * @return {@code true} if instant lettering is supported, {@code false} otherwise.
         */
        private boolean isCarrierInstantLetteringSupported() {
            PersistableBundle b =
                    PhoneGlobals.getInstance().getCarrierConfigForSubId(mPhone.getSubId());
            return b != null &&
                    b.getBoolean(CarrierConfigManager.KEY_CARRIER_INSTANT_LETTERING_AVAILABLE_BOOL);
        }

        /**
         * Determines from carrier config whether merging calls is supported.
         *
         * @return {@code true} if merging calls is supported, {@code false} otherwise.
         */
        private boolean isCarrierMergeCallSupported() {
            PersistableBundle b =
                    PhoneGlobals.getInstance().getCarrierConfigForSubId(mPhone.getSubId());
            return b != null &&
                    b.getBoolean(CarrierConfigManager.KEY_SUPPORT_CONFERENCE_CALL_BOOL);
        }

        /**
         * Determines from carrier config whether merging IMS calls is supported.
         *
         * @return {@code true} if merging IMS calls is supported, {@code false} otherwise.
         */
        private boolean isCarrierMergeImsCallSupported() {
            PersistableBundle b =
                    PhoneGlobals.getInstance().getCarrierConfigForSubId(mPhone.getSubId());
            return b.getBoolean(CarrierConfigManager.KEY_SUPPORT_IMS_CONFERENCE_CALL_BOOL);
        }

        /**
         * Determines from carrier config whether emergency video calls are supported.
         *
         * @return {@code true} if emergency video calls are allowed, {@code false} otherwise.
         */
        private boolean isCarrierEmergencyVideoCallsAllowed() {
            PersistableBundle b =
                    PhoneGlobals.getInstance().getCarrierConfigForSubId(mPhone.getSubId());
            return b != null &&
                    b.getBoolean(CarrierConfigManager.KEY_ALLOW_EMERGENCY_VIDEO_CALLS_BOOL);
        }

        /**
         * Determines from carrier config whether video conferencing is supported.
         *
         * @return {@code true} if video conferencing is supported, {@code false} otherwise.
         */
        private boolean isCarrierVideoConferencingSupported() {
            PersistableBundle b =
                    PhoneGlobals.getInstance().getCarrierConfigForSubId(mPhone.getSubId());
            return b != null &&
                    b.getBoolean(CarrierConfigManager.KEY_SUPPORT_VIDEO_CONFERENCE_CALL_BOOL);
        }

        /**
         * Determines from carrier config whether merging of wifi calls is allowed when VoWIFI is
         * turned off.
         *
         * @return {@code true} merging of wifi calls when VoWIFI is disabled should be prevented,
         *      {@code false} otherwise.
         */
        private boolean isCarrierMergeOfWifiCallsAllowedWhenVoWifiOff() {
            PersistableBundle b =
                    PhoneGlobals.getInstance().getCarrierConfigForSubId(mPhone.getSubId());
            return b != null && b.getBoolean(
                    CarrierConfigManager.KEY_ALLOW_MERGE_WIFI_CALLS_WHEN_VOWIFI_OFF_BOOL);
        }

        /**
         * Determines from carrier config whether managing IMS conference calls is supported.
         *
         * @return {@code true} if managing IMS conference calls is supported,
         *         {@code false} otherwise.
         */
        private boolean isCarrierManageImsConferenceCallSupported() {
            PersistableBundle b =
                    PhoneGlobals.getInstance().getCarrierConfigForSubId(mPhone.getSubId());
            return b.getBoolean(CarrierConfigManager.KEY_SUPPORT_MANAGE_IMS_CONFERENCE_CALL_BOOL);
        }

        /**
         * Determines from carrier config whether the carrier uses a sim call manager.
         *
         * @return {@code true} if the carrier uses a sim call manager,
         *         {@code false} otherwise.
         */
        private boolean isCarrierUsingSimCallManager() {
            PersistableBundle b =
                    PhoneGlobals.getInstance().getCarrierConfigForSubId(mPhone.getSubId());
            return !TextUtils.isEmpty(
                    b.getString(CarrierConfigManager.KEY_DEFAULT_SIM_CALL_MANAGER_STRING));
        }

        /**
         * Determines from carrier config whether showing percise call diconnect cause to user
         * is supported.
         *
         * @return {@code true} if showing percise call diconnect cause to user is supported,
         *         {@code false} otherwise.
         */
        private boolean isCarrierShowPreciseFailedCause() {
            PersistableBundle b =
                    PhoneGlobals.getInstance().getCarrierConfigForSubId(mPhone.getSubId());
            return b.getBoolean(CarrierConfigManager.KEY_SHOW_PRECISE_FAILED_CAUSE_BOOL);
        }

        /**
         * Determines from carrier config whether the carrier requires the use of a call recording
         * tone.
         *
         * @return {@code true} if a call recording tone should be used, {@code false} otherwise.
         */
        private boolean isCarrierUseCallRecordingTone() {
            PersistableBundle b =
                    PhoneGlobals.getInstance().getCarrierConfigForSubId(mPhone.getSubId());
            return b.getBoolean(CarrierConfigManager.KEY_PLAY_CALL_RECORDING_TONE_BOOL);
        }

        /**
         * Where a device supports instant lettering and call subjects, retrieves the necessary
         * PhoneAccount extras for those features.
         *
         * @return The {@link PhoneAccount} extras associated with the current subscription.
         */
        private Bundle getPhoneAccountExtras() {
            PersistableBundle b =
                    PhoneGlobals.getInstance().getCarrierConfigForSubId(mPhone.getSubId());

            int instantLetteringMaxLength = b.getInt(
                    CarrierConfigManager.KEY_CARRIER_INSTANT_LETTERING_LENGTH_LIMIT_INT);
            String instantLetteringEncoding = b.getString(
                    CarrierConfigManager.KEY_CARRIER_INSTANT_LETTERING_ENCODING_STRING);
            Bundle phoneAccountExtras = new Bundle();
            phoneAccountExtras.putInt(PhoneAccount.EXTRA_CALL_SUBJECT_MAX_LENGTH,
                    instantLetteringMaxLength);
            phoneAccountExtras.putString(PhoneAccount.EXTRA_CALL_SUBJECT_CHARACTER_ENCODING,
                    instantLetteringEncoding);
            return phoneAccountExtras;
        }

        /**
         * Receives callback from {@link PstnPhoneCapabilitiesNotifier} when the video capabilities
         * have changed.
         *
         * @param isVideoCapable {@code true} if video is capable.
         */
        @Override
        public void onVideoCapabilitiesChanged(boolean isVideoCapable) {
            mIsVideoCapable = isVideoCapable;
            synchronized (mAccountsLock) {
                if (!mAccounts.contains(this)) {
                    // Account has already been torn down, don't try to register it again.
                    // This handles the case where teardown has already happened, and we got a video
                    // update that lost the race for the mAccountsLock.  In such a scenario by the
                    // time we get here, the original phone account could have been torn down.
                    return;
                }
                mAccount = registerPstnPhoneAccount(mIsEmergency, mIsDummy);
            }
        }

        public void updateRttCapability() {
            boolean isRttEnabled = isRttCurrentlySupported();
            if (isRttEnabled != mIsRttCapable) {
                Log.i(this, "updateRttCapability - changed, new value: " + isRttEnabled);
                mAccount = registerPstnPhoneAccount(mIsEmergency, mIsDummy);
            }
        }

        public void updateDefaultDataSubId(int activeDataSubId) {
            boolean isEmergencyPreferred = isEmergencyPreferredAccount(mPhone.getSubId(),
                    activeDataSubId);
            if (isEmergencyPreferred != mIsEmergencyPreferred) {
                Log.i(this, "updateDefaultDataSubId - changed, new value: " + isEmergencyPreferred);
                mAccount = registerPstnPhoneAccount(mIsEmergency, mIsDummy);
            }
        }

        /**
         * Determines whether RTT is supported given the current state of the
         * device.
         */
        private boolean isRttCurrentlySupported() {
            boolean hasVoiceAvailability = isImsVoiceAvailable();

            boolean isRttSupported = PhoneGlobals.getInstance().phoneMgr
                    .isRttEnabled(mPhone.getSubId());

            boolean isRoaming = mTelephonyManager.isNetworkRoaming(mPhone.getSubId());

            return hasVoiceAvailability && isRttSupported && !isRoaming;
        }

        /**
         * Indicates whether this account supports pausing video calls.
         * @return {@code true} if the account supports pausing video calls, {@code false}
         * otherwise.
         */
        public boolean isVideoPauseSupported() {
            return mIsVideoCapable && mIsVideoPauseSupported;
        }

        /**
         * Indicates whether this account supports merging calls (i.e. conferencing).
         * @return {@code true} if the account supports merging calls, {@code false} otherwise.
         */
        public boolean isMergeCallSupported() {
            return mIsMergeCallSupported;
        }

        /**
         * Indicates whether this account supports merging IMS calls (i.e. conferencing).
         * @return {@code true} if the account supports merging IMS calls, {@code false} otherwise.
         */
        public boolean isMergeImsCallSupported() {
            return mIsMergeImsCallSupported;
        }

        /**
         * Indicates whether this account supports video conferencing.
         * @return {@code true} if the account supports video conferencing, {@code false} otherwise.
         */
        public boolean isVideoConferencingSupported() {
            return mIsVideoConferencingSupported;
        }

        /**
         * Indicate whether this account allow merging of wifi calls when VoWIFI is off.
         * @return {@code true} if allowed, {@code false} otherwise.
         */
        public boolean isMergeOfWifiCallsAllowedWhenVoWifiOff() {
            return mIsMergeOfWifiCallsAllowedWhenVoWifiOff;
        }

        /**
         * Indicates whether this account supports managing IMS conference calls
         * @return {@code true} if the account supports managing IMS conference calls,
         *         {@code false} otherwise.
         */
        public boolean isManageImsConferenceCallSupported() {
            return mIsManageImsConferenceCallSupported;
        }

        /**
         * Indicates whether this account uses a sim call manger.
         * @return {@code true} if the account uses a sim call manager,
         *         {@code false} otherwise.
         */
        public boolean isUsingSimCallManager() {
            return mIsUsingSimCallManager;
        }

        /**
         * Indicates whether this account supports showing the precise call disconnect cause
         * to user (i.e. conferencing).
         * @return {@code true} if the account supports showing the precise call disconnect cause,
         *         {@code false} otherwise.
         */
        public boolean isShowPreciseFailedCause() {
            return mIsShowPreciseFailedCause;
        }

        private boolean isImsVoiceAvailable() {
            if (mMmTelCapabilities != null) {
                return mMmTelCapabilities.isCapable(
                        MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VOICE);
            }

            if (mMmTelManager == null) {
                // The Subscription is invalid, so IMS is unavailable.
                return false;
            }

            // In the rare case that mMmTelCapabilities hasn't been set, try fetching it
            // directly and register callback.
            registerMmTelCapabilityCallback();
            return mMmTelManager.isAvailable(ImsRegistrationImplBase.REGISTRATION_TECH_LTE,
                    MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VOICE)
                    || mMmTelManager.isAvailable(ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN,
                    MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VOICE);
        }
    }

    private OnSubscriptionsChangedListener mOnSubscriptionsChangedListener =
            new OnSubscriptionsChangedListener() {
        @Override
        public void onSubscriptionsChanged() {
            // Any time the SubscriptionInfo changes...rerun the setup
            Log.i(this, "onSubscriptionsChanged - update accounts");
            tearDownAccounts();
            setupAccounts();
        }
    };

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_USER_SWITCHED.equals(intent.getAction())) {
                Log.i(this, "User changed, re-registering phone accounts.");

                int userHandleId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                UserHandle currentUserHandle = new UserHandle(userHandleId);
                mIsPrimaryUser = UserManager.get(mContext).getPrimaryUser().getUserHandle()
                        .equals(currentUserHandle);

                // Any time the user changes, re-register the accounts.
                tearDownAccounts();
                setupAccounts();
            } else if (CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED.equals(
                    intent.getAction())) {
                Log.i(this, "Carrier-config changed, checking for phone account updates.");
                int subId = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
                        SubscriptionManager.INVALID_SUBSCRIPTION_ID);
                handleCarrierConfigChange(subId);
            }
        }
    };

    private final PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
        @Override
        public void onServiceStateChanged(ServiceState serviceState) {
            int newState = serviceState.getState();
            if (newState == ServiceState.STATE_IN_SERVICE && mServiceState != newState) {
                tearDownAccounts();
                setupAccounts();
            } else {
                synchronized (mAccountsLock) {
                    for (AccountEntry account : mAccounts) {
                        account.updateRttCapability();
                    }
                }
            }
            mServiceState = newState;
        }

        @Override
        public void onActiveDataSubscriptionIdChanged(int subId) {
            mActiveDataSubscriptionId = subId;
            synchronized (mAccountsLock) {
                for (AccountEntry account : mAccounts) {
                    account.updateDefaultDataSubId(mActiveDataSubscriptionId);
                }
            }
        }
    };

    private static TelecomAccountRegistry sInstance;
    private final Context mContext;
    private final TelecomManager mTelecomManager;
    private final TelephonyManager mTelephonyManager;
    private final SubscriptionManager mSubscriptionManager;
    private List<AccountEntry> mAccounts = new LinkedList<AccountEntry>();
    private final Object mAccountsLock = new Object();
    private int mServiceState = ServiceState.STATE_POWER_OFF;
    private int mActiveDataSubscriptionId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    private boolean mIsPrimaryUser = true;

    // TODO: Remove back-pointer from app singleton to Service, since this is not a preferred
    // pattern; redesign. This was added to fix a late release bug.
    private TelephonyConnectionService mTelephonyConnectionService;

    TelecomAccountRegistry(Context context) {
        mContext = context;
        mTelecomManager = TelecomManager.from(context);
        mTelephonyManager = TelephonyManager.from(context);
        mSubscriptionManager = SubscriptionManager.from(context);
    }

    /**
     * Get the singleton instance.
     */
    public static synchronized TelecomAccountRegistry getInstance(Context context) {
        if (sInstance == null && context != null) {
            sInstance = new TelecomAccountRegistry(context);
        }
        return sInstance;
    }

    void setTelephonyConnectionService(TelephonyConnectionService telephonyConnectionService) {
        this.mTelephonyConnectionService = telephonyConnectionService;
    }

    TelephonyConnectionService getTelephonyConnectionService() {
        return mTelephonyConnectionService;
    }

    /**
     * Determines if the {@link AccountEntry} associated with a {@link PhoneAccountHandle} supports
     * pausing video calls.
     *
     * @param handle The {@link PhoneAccountHandle}.
     * @return {@code True} if video pausing is supported.
     */
    boolean isVideoPauseSupported(PhoneAccountHandle handle) {
        synchronized (mAccountsLock) {
            for (AccountEntry entry : mAccounts) {
                if (entry.getPhoneAccountHandle().equals(handle)) {
                    return entry.isVideoPauseSupported();
                }
            }
        }
        return false;
    }

    /**
     * Determines if the {@link AccountEntry} associated with a {@link PhoneAccountHandle} supports
     * merging calls.
     *
     * @param handle The {@link PhoneAccountHandle}.
     * @return {@code True} if merging calls is supported.
     */
    boolean isMergeCallSupported(PhoneAccountHandle handle) {
        synchronized (mAccountsLock) {
            for (AccountEntry entry : mAccounts) {
                if (entry.getPhoneAccountHandle().equals(handle)) {
                    return entry.isMergeCallSupported();
                }
            }
        }
        return false;
    }

    /**
     * Determines if the {@link AccountEntry} associated with a {@link PhoneAccountHandle} supports
     * video conferencing.
     *
     * @param handle The {@link PhoneAccountHandle}.
     * @return {@code True} if video conferencing is supported.
     */
    boolean isVideoConferencingSupported(PhoneAccountHandle handle) {
        synchronized (mAccountsLock) {
            for (AccountEntry entry : mAccounts) {
                if (entry.getPhoneAccountHandle().equals(handle)) {
                    return entry.isVideoConferencingSupported();
                }
            }
        }
        return false;
    }

    /**
     * Determines if the {@link AccountEntry} associated with a {@link PhoneAccountHandle} allows
     * merging of wifi calls when VoWIFI is disabled.
     *
     * @param handle The {@link PhoneAccountHandle}.
     * @return {@code True} if merging of wifi calls is allowed when VoWIFI is disabled.
     */
    boolean isMergeOfWifiCallsAllowedWhenVoWifiOff(final PhoneAccountHandle handle) {
        synchronized (mAccountsLock) {
            Optional<AccountEntry> result = mAccounts.stream().filter(
                    entry -> entry.getPhoneAccountHandle().equals(handle)).findFirst();

            if (result.isPresent()) {
                return result.get().isMergeOfWifiCallsAllowedWhenVoWifiOff();
            } else {
                return false;
            }
        }
    }

    /**
     * Determines if the {@link AccountEntry} associated with a {@link PhoneAccountHandle} supports
     * merging IMS calls.
     *
     * @param handle The {@link PhoneAccountHandle}.
     * @return {@code True} if merging IMS calls is supported.
     */
    boolean isMergeImsCallSupported(PhoneAccountHandle handle) {
        synchronized (mAccountsLock) {
            for (AccountEntry entry : mAccounts) {
                if (entry.getPhoneAccountHandle().equals(handle)) {
                    return entry.isMergeImsCallSupported();
                }
            }
        }
        return false;
    }

    /**
     * Determines if the {@link AccountEntry} associated with a {@link PhoneAccountHandle} supports
     * managing IMS conference calls.
     *
     * @param handle The {@link PhoneAccountHandle}.
     * @return {@code True} if managing IMS conference calls is supported.
     */
    boolean isManageImsConferenceCallSupported(PhoneAccountHandle handle) {
        synchronized (mAccountsLock) {
            for (AccountEntry entry : mAccounts) {
                if (entry.getPhoneAccountHandle().equals(handle)) {
                    return entry.isManageImsConferenceCallSupported();
                }
            }
        }
        return false;
    }

    /**
     * showing precise call disconnect cause to the user.
     *
     * @param handle The {@link PhoneAccountHandle}.
     * @return {@code True} if showing precise call disconnect cause to the user is supported.
     */
    boolean isShowPreciseFailedCause(PhoneAccountHandle handle) {
        synchronized (mAccountsLock) {
            for (AccountEntry entry : mAccounts) {
                if (entry.getPhoneAccountHandle().equals(handle)) {
                    return entry.isShowPreciseFailedCause();
                }
            }
        }
        return false;
    }

    /**
     * @return Reference to the {@code TelecomAccountRegistry}'s subscription manager.
     */
    SubscriptionManager getSubscriptionManager() {
        return mSubscriptionManager;
    }

    /**
     * Returns the address (e.g. the phone number) associated with a subscription.
     *
     * @param handle The phone account handle to find the subscription address for.
     * @return The address.
     */
    public Uri getAddress(PhoneAccountHandle handle) {
        synchronized (mAccountsLock) {
            for (AccountEntry entry : mAccounts) {
                if (entry.getPhoneAccountHandle().equals(handle)) {
                    return entry.mAccount.getAddress();
                }
            }
        }
        return null;
    }

    /**
     * Returns whethere a the subscription associated with a {@link PhoneAccountHandle} is using a
     * sim call manager.
     *
     * @param handle The phone account handle to find the subscription address for.
     * @return {@code true} if a sim call manager is in use, {@code false} otherwise.
     */
    public boolean isUsingSimCallManager(PhoneAccountHandle handle) {
        synchronized (mAccountsLock) {
            for (AccountEntry entry : mAccounts) {
                if (entry.getPhoneAccountHandle().equals(handle)) {
                    return entry.isUsingSimCallManager();
                }
            }
        }
        return false;
    }

    /**
     * Sets up all the phone accounts for SIMs on first boot.
     */
    public void setupOnBoot() {
        // TODO: When this object "finishes" we should unregister by invoking
        // SubscriptionManager.getInstance(mContext).unregister(mOnSubscriptionsChangedListener);
        // This is not strictly necessary because it will be unregistered if the
        // notification fails but it is good form.

        // Register for SubscriptionInfo list changes which is guaranteed
        // to invoke onSubscriptionsChanged the first time.
        SubscriptionManager.from(mContext).addOnSubscriptionsChangedListener(
                mOnSubscriptionsChangedListener);

        // We also need to listen for changes to the service state (e.g. emergency -> in service)
        // because this could signal a removal or addition of a SIM in a single SIM phone.
        mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_SERVICE_STATE
                | PhoneStateListener.LISTEN_ACTIVE_DATA_SUBSCRIPTION_ID_CHANGE);

        // Listen for user switches.  When the user switches, we need to ensure that if the current
        // use is not the primary user we disable video calling.
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        filter.addAction(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED);
        mContext.registerReceiver(mReceiver, filter);

        // Listen to the RTT system setting so that we update it when the user flips it.
        ContentObserver rttUiSettingObserver = new ContentObserver(
                new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                synchronized (mAccountsLock) {
                    for (AccountEntry account : mAccounts) {
                        account.updateRttCapability();
                    }
                }
            }
        };

        // register for all settings
        for (int i = 0; i < mTelephonyManager.getPhoneCount(); i++) {
            Uri rttSettingUri = Settings.Secure.getUriFor(
                    Settings.Secure.RTT_CALLING_MODE + convertRttPhoneId(i));
            mContext.getContentResolver().registerContentObserver(
                    rttSettingUri, false, rttUiSettingObserver);
        }
    }

    private static String convertRttPhoneId(int phoneId) {
        return phoneId != 0 ? Integer.toString(phoneId) : "";
    }

    /**
     * Determines if the list of {@link AccountEntry}(s) contains an {@link AccountEntry} with a
     * specified {@link PhoneAccountHandle}.
     *
     * @param handle The {@link PhoneAccountHandle}.
     * @return {@code True} if an entry exists.
     */
    boolean hasAccountEntryForPhoneAccount(PhoneAccountHandle handle) {
        synchronized (mAccountsLock) {
            for (AccountEntry entry : mAccounts) {
                if (entry.getPhoneAccountHandle().equals(handle)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Un-registers any {@link PhoneAccount}s which are no longer present in the list
     * {@code AccountEntry}(s).
     */
    private void cleanupPhoneAccounts() {
        ComponentName telephonyComponentName =
                new ComponentName(mContext, TelephonyConnectionService.class);
        // This config indicates whether the emergency account was flagged as emergency calls only
        // in which case we need to consider all phone accounts, not just the call capable ones.
        final boolean emergencyCallsOnlyEmergencyAccount = mContext.getResources().getBoolean(
                R.bool.config_emergency_account_emergency_calls_only);
        List<PhoneAccountHandle> accountHandles = emergencyCallsOnlyEmergencyAccount
                ? mTelecomManager.getAllPhoneAccountHandles()
                : mTelecomManager.getCallCapablePhoneAccounts(true /* includeDisabled */);

        for (PhoneAccountHandle handle : accountHandles) {
            if (telephonyComponentName.equals(handle.getComponentName()) &&
                    !hasAccountEntryForPhoneAccount(handle)) {
                Log.i(this, "Unregistering phone account %s.", handle);
                mTelecomManager.unregisterPhoneAccount(handle);
            }
        }
    }

    private void setupAccounts() {
        // Go through SIM-based phones and register ourselves -- registering an existing account
        // will cause the existing entry to be replaced.
        Phone[] phones = PhoneFactory.getPhones();
        Log.i(this, "setupAccounts: Found %d phones.  Attempting to register.", phones.length);

        final boolean phoneAccountsEnabled = mContext.getResources().getBoolean(
                R.bool.config_pstn_phone_accounts_enabled);
        int activeCount = 0;
        int activeSubscriptionId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        boolean isAnyProvisionInfoPending = false;

        synchronized (mAccountsLock) {
            try {
                if (phoneAccountsEnabled) {
                    // states we are interested in from what
                    // IExtTelephony.getCurrentUiccCardProvisioningStatus()can return
                    final int PROVISIONED = 1;
                    final int INVALID_STATE = -1;
                    final int CARD_NOT_PRESENT = -2;
                    boolean isInEcm = false;

                    for (Phone phone : phones) {
                        int provisionStatus = PROVISIONED;
                        int subscriptionId = phone.getSubId();
                        int slotId = phone.getPhoneId();
                        isInEcm = Boolean.parseBoolean(mTelephonyManager.getTelephonyProperty(slotId,
                                TelephonyProperties.PROPERTY_INECM_MODE, "false"));
                        boolean isAccountAdded = false;

                        if (mTelephonyManager.getPhoneCount() > 1) {
                            IExtTelephony mExtTelephony = IExtTelephony.Stub
                                    .asInterface(ServiceManager.getService("extphone"));
                            try {
                                //get current provision state of the SIM.
                                provisionStatus =
                                        mExtTelephony.getCurrentUiccCardProvisioningStatus(slotId);
                            } catch (RemoteException ex) {
                                provisionStatus = INVALID_STATE;
                                Log.w(this, "Failed to get status , slotId: "+ slotId +" Exception: "
                                        + ex);
                            } catch (NullPointerException ex) {
                                provisionStatus = INVALID_STATE;
                                Log.w(this, "Failed to get status , slotId: "+ slotId +" Exception: "
                                        + ex);
                            }
                        }

                        // In SSR case, UiccCard's would be disposed hence the provision state received as
                        // CARD_NOT_PRESENT but valid subId present in SubscriptionInfo record.
                        if (provisionStatus == INVALID_STATE || ((provisionStatus == CARD_NOT_PRESENT)
                                && mSubscriptionManager.isActiveSubId(subscriptionId))) {
                            isAnyProvisionInfoPending = true;
                        }

                        Log.i(this, "setupAccounts: Phone with subscription id: " + subscriptionId +
                                " slotId: " + slotId + " provisionStatus: " + provisionStatus);
                        // setupAccounts can be called multiple times during service changes.
                        // Don't add an account if the Icc has not been set yet.
                        if (!SubscriptionManager.isValidSubscriptionId(subscriptionId)
                                || phone.getFullIccSerialNumber() == null) {
                            Log.d(this, "setupAccounts: skipping invalid subid %d", subscriptionId);
                            continue;
                        }
                        // Don't add account if it's opportunistic subscription, which is considered
                        // data only for now.
                        SubscriptionInfo info = SubscriptionManager.from(mContext)
                                .getActiveSubscriptionInfo(subscriptionId);
                        if (info == null || info.isOpportunistic()) {
                            Log.d(this, "setupAccounts: skipping unknown or opportunistic subid %d",
                                    subscriptionId);
                            continue;
                        }

                        if (subscriptionId >= 0  && (provisionStatus == PROVISIONED)
                                && (mSubscriptionManager.isActiveSubId(subscriptionId))) {
                            activeCount++;
                            activeSubscriptionId = subscriptionId;
                            mAccounts.add(new AccountEntry(phone, false /* emergency */,
                                    false /* isDummy */));
                            isAccountAdded = true;
                        } else {
                            // If device configured in dsds mode, a SIM removed and if corresponding
                            // phone is in ECM then add emergency account to that sub so that
                            // incoming emergency call can be processed.
                            Phone phoneInEcm = PhoneGlobals.getInstance().getPhoneInEcm();
                            if ((mTelephonyManager.getPhoneCount() > 1)
                                    && isInEcm && (phoneInEcm != null)
                                    && phoneInEcm.getPhoneId() == phone.getPhoneId()) {
                                mAccounts.add(new AccountEntry(phoneInEcm, true /* emergency */,
                                        false /* isDummy */));
                                isAccountAdded = true;
                            }
                        }
                        // Speacial case where one sub sim locked other sub reporting emergency service
                        // emergency call placed will initiate on primary sub i.e sub which is reporting
                        // limited/emergency service, if phone switches when emergency call is
                        // in progress, there will missing phone account which can notify phantom call
                        // to upper layer add emergency if for a phone no account added and if phone
                        // reporting emergency service and other subs oos or if phone has running call
                        if (!isAccountAdded && ((phone.getServiceState().isEmergencyOnly()
                                    && !isOtherPhoneInService(phone))
                                    || (phone.getState() == PhoneConstants.State.OFFHOOK))) {
                            Log.i(this, "Adding emergency account to phone id: "+phone.getPhoneId());
                            mAccounts.add(new AccountEntry(phone, true /* emergency */,
                                    false /* isDummy */));
                        }
                    }
                }
            } finally {
                // If we did not list ANY accounts, we need to provide a "default" SIM account
                // for emergency numbers since no actual SIM is needed for dialing emergency
                // numbers but a phone account is.
                if (mAccounts.isEmpty()) {
                    Log.i(this, "setupAccounts: adding default");
                    mAccounts.add(new AccountEntry(PhoneFactory.getPhone(
                            PhoneUtils.getPrimaryStackPhoneId()), true /* emergency */,
                            false /* isDummy */));
                }
            }

            // Add a fake account entry.
            if (DBG && phones.length > 0 && "TRUE".equals(System.getProperty("dummy_sim"))) {
                mAccounts.add(new AccountEntry(phones[0], false /* emergency */,
                        true /* isDummy */));
            }
        }

        // Clean up any PhoneAccounts that are no longer relevant
        cleanupPhoneAccounts();

        // At some point, the phone account ID was switched from the subId to the iccId.
        // If there is a default account, check if this is the case, and upgrade the default account
        // from using the subId to iccId if so.
        PhoneAccountHandle defaultPhoneAccount =
                mTelecomManager.getUserSelectedOutgoingPhoneAccount();
        ComponentName telephonyComponentName =
                new ComponentName(mContext, TelephonyConnectionService.class);

        if (defaultPhoneAccount != null &&
                telephonyComponentName.equals(defaultPhoneAccount.getComponentName()) &&
                !hasAccountEntryForPhoneAccount(defaultPhoneAccount)) {

            String phoneAccountId = defaultPhoneAccount.getId();
            if (!TextUtils.isEmpty(phoneAccountId) && TextUtils.isDigitsOnly(phoneAccountId)) {
                PhoneAccountHandle upgradedPhoneAccount =
                        PhoneUtils.makePstnPhoneAccountHandle(
                                PhoneGlobals.getPhone(Integer.parseInt(phoneAccountId)));

                if (hasAccountEntryForPhoneAccount(upgradedPhoneAccount)) {
                    mTelecomManager.setUserSelectedOutgoingPhoneAccount(upgradedPhoneAccount);
                }
            }
        } else if ((defaultPhoneAccount == null)
                    && (mTelephonyManager.getPhoneCount() > Count.ONE.ordinal())
                    && (activeCount == Count.ONE.ordinal()) && !isAnyProvisionInfoPending
                    && (!isNonSimAccountFound()) && (isRadioInValidState(phones))) {
            PhoneAccountHandle phoneAccountHandle =
                    subscriptionIdToPhoneAccountHandle(activeSubscriptionId);
            if (phoneAccountHandle != null) {
                mTelecomManager.setUserSelectedOutgoingPhoneAccount(phoneAccountHandle);
            }
        }
    }

    private boolean isOtherPhoneInService(Phone currentPhone) {
        TelephonyManager tm = TelephonyManager.getDefault();
        int phoneCount = tm.getPhoneCount();
        for (int phId = 0; phId < phoneCount; phId++) {
            Phone phone = PhoneFactory.getPhone(phId);
            if (currentPhone.getPhoneId() == phone.getPhoneId()) continue;
            int ss = phone.getServiceState().getState();
            Log.i(this, "Phone Id: %d Service State: %d",phone.getPhoneId(), ss);
            if (ss == ServiceState.STATE_IN_SERVICE) {
                return true;
            }
        }
        return false;
    }

    private boolean isNonSimAccountFound() {
        final Iterator<PhoneAccountHandle> phoneAccounts =
                mTelecomManager.getCallCapablePhoneAccounts().listIterator();
        while (phoneAccounts.hasNext()) {
            final PhoneAccountHandle phoneAccountHandle = phoneAccounts.next();
            final PhoneAccount phoneAccount = mTelecomManager.getPhoneAccount(phoneAccountHandle);
            if (mTelephonyManager.getSubIdForPhoneAccount(phoneAccount) ==
                    SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                return true;
            }
        }
        return false;
    }

    private boolean isRadioInValidState(Phone[] phones) {
        boolean isApmSimNotPwrDown = false;
        try {
            IExtTelephony extTelephony = IExtTelephony.Stub
                 .asInterface(ServiceManager.getService("extphone"));
            int propVal = extTelephony.getPropertyValueInt(APM_SIM_NOT_PWDN_PROPERTY, 0);
            isApmSimNotPwrDown = (propVal == 1);
            Log.d(this, "isRadioInValidState, propVal = " + propVal +
                    " isApmSimNotPwrDown = " + isApmSimNotPwrDown);
        } catch (RemoteException|NullPointerException ex) {
            Log.w(this, "Failed to get property: + " + APM_SIM_NOT_PWDN_PROPERTY +
                    " , Exception: " + ex);
        }

        int isAPMOn = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0);

        // Do not update default Voice subId when SIM is pwdn due to APM
        if ((isAPMOn == 1) && (!isApmSimNotPwrDown)) {
            Log.d(this, "isRadioInValidState, isApmSimNotPwrDown = " + isApmSimNotPwrDown
                    + ", isAPMOn:" + isAPMOn);
            return false;
        }

        //Do not update default Voice subId when when device Shutdown is in progress
        int  numPhones = mTelephonyManager.getPhoneCount();
        for (int i = 0; i < numPhones; i++) {
            if (phones[i] != null && phones[i].isShuttingDown()) {
                Log.d(this, " isRadioInValidState: device shutdown in progress ");
                return false;
            }
        }
        return true;
    }

    private PhoneAccountHandle subscriptionIdToPhoneAccountHandle(final int subId) {
        final Iterator<PhoneAccountHandle> phoneAccounts =
                mTelecomManager.getCallCapablePhoneAccounts().listIterator();
        List<PhoneAccountHandle> phoneAccountsList =
                mTelecomManager.getCallCapablePhoneAccounts();
        while (phoneAccounts.hasNext()) {
            final PhoneAccountHandle phoneAccountHandle = phoneAccounts.next();
            final PhoneAccount phoneAccount = mTelecomManager.getPhoneAccount(phoneAccountHandle);
            if (subId == mTelephonyManager.getSubIdForPhoneAccount(phoneAccount)) {
                return phoneAccountHandle;
            }
        }
        return null;
    }

    private void tearDownAccounts() {
        synchronized (mAccountsLock) {
            for (AccountEntry entry : mAccounts) {
                entry.teardown();
            }
            mAccounts.clear();
        }
    }

    /**
     * Handles changes to the carrier configuration which may impact a phone account.  There are
     * some extras defined in the {@link PhoneAccount} which are based on carrier config options.
     * Only checking for carrier config changes when the subscription is configured runs the risk of
     * missing carrier config changes which happen later.
     * @param subId The subid the carrier config changed for, if applicable.  Will be
     *              {@link SubscriptionManager#INVALID_SUBSCRIPTION_ID} if not specified.
     */
    private void handleCarrierConfigChange(int subId) {
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            return;
        }
        synchronized (mAccountsLock) {
            for (AccountEntry entry : mAccounts) {
                if (entry.getSubId() == subId) {
                    Log.d(this, "handleCarrierConfigChange: subId=%d, accountSubId=%d", subId,
                            entry.getSubId());
                    entry.reRegisterPstnPhoneAccount();
                }
            }
        }
    }
}
