/**
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

import android.content.Context;
import android.media.ToneGenerator;
import android.provider.Settings;
import android.telecom.DisconnectCause;
import android.telephony.SubscriptionManager;

import com.android.internal.telephony.CallFailCause;
import com.android.phone.ImsUtil;
import com.android.phone.PhoneGlobals;
import com.android.phone.common.R;

import com.android.internal.telephony.gsm.SuppServiceNotification;

public class DisconnectCauseUtil {

    public static int mNotificationCode = 0xFF;
    public static int mNotificationType = 0xFF;

   /**
    * Converts from a disconnect code in {@link android.telephony.DisconnectCause} into a more
    * generic {@link android.telecom.DisconnectCause} object, possibly populated with a localized
    * message and tone.
    *
    * @param telephonyDisconnectCause The code for the reason for the disconnect.
    */
    public static DisconnectCause toTelecomDisconnectCause(int telephonyDisconnectCause) {
        return toTelecomDisconnectCause(telephonyDisconnectCause,
                CallFailCause.NOT_VALID, null /* reason */);
    }

    public static DisconnectCause toTelecomDisconnectCause(int telephonyDisconnectCause,
            String reason, int type, int code, int phoneId) {
        mNotificationCode = code;
        mNotificationType = type;
        return toTelecomDisconnectCause(telephonyDisconnectCause, reason, phoneId);
    }

   /**
    * Converts from a disconnect code in {@link android.telephony.DisconnectCause} into a more
    * generic {@link android.telecom.DisconnectCause}.object, possibly populated with a localized
    * message and tone.
    *
    * @param telephonyDisconnectCause The code for the reason for the disconnect.
    * @param reason Description of the reason for the disconnect, not intended for the user to see..
    */
    public static DisconnectCause toTelecomDisconnectCause(
            int telephonyDisconnectCause, String reason) {
        return toTelecomDisconnectCause(telephonyDisconnectCause, CallFailCause.NOT_VALID, reason);
    }

   /**
    * Converts from a disconnect code in {@link android.telephony.DisconnectCause} into a more
    * generic {@link android.telecom.DisconnectCause}.object, possibly populated with a localized
    * message and tone.
    *
    * @param telephonyDisconnectCause The code for the reason for the disconnect.
    * @param telephonyPerciseDisconnectCause The code for the percise reason for the disconnect.
    * @param reason Description of the reason for the disconnect, not intended for the user to see..
    */
    public static DisconnectCause toTelecomDisconnectCause(
            int telephonyDisconnectCause, int telephonyPerciseDisconnectCause, String reason) {
        return toTelecomDisconnectCause(telephonyDisconnectCause, telephonyPerciseDisconnectCause,
                reason, SubscriptionManager.getDefaultVoicePhoneId());
    }

    /**
     * Converts from a disconnect code in {@link android.telephony.DisconnectCause} into a more
     * generic {@link android.telecom.DisconnectCause}.object, possibly populated with a localized
     * message and tone for Slot.
     *
     * @param telephonyDisconnectCause The code for the reason for the disconnect.
     * @param phoneId To support localized message based on phoneId
     */
    public static DisconnectCause toTelecomDisconnectCause(int telephonyDisconnectCause,
            String reason, int phoneId) {
        return toTelecomDisconnectCause(telephonyDisconnectCause, CallFailCause.NOT_VALID,
                reason, phoneId);
    }

   /**
    * Converts from a disconnect code in {@link android.telephony.DisconnectCause} into a more
    * generic {@link android.telecom.DisconnectCause}.object, possibly populated with a localized
    * message and tone for Slot.
    *
    * @param telephonyDisconnectCause The code for the reason for the disconnect.
    * @param telephonyPerciseDisconnectCause The code for the percise reason for the disconnect.
    * @param reason Description of the reason for the disconnect, not intended for the user to see..
    * @param phoneId To support localized message based on phoneId
    */
    public static DisconnectCause toTelecomDisconnectCause(
            int telephonyDisconnectCause, int telephonyPerciseDisconnectCause, String reason,
            int phoneId) {
        Context context = PhoneGlobals.getInstance();
        return new DisconnectCause(
                toTelecomDisconnectCauseCode(telephonyDisconnectCause),
                toTelecomDisconnectCauseLabel(context, telephonyDisconnectCause,
                        telephonyPerciseDisconnectCause),
                toTelecomDisconnectCauseDescription(context, telephonyDisconnectCause, phoneId),
                toTelecomDisconnectReason(context,telephonyDisconnectCause, reason, phoneId),
                toTelecomDisconnectCauseTone(telephonyDisconnectCause));
    }

    /**
     * Convert the {@link android.telephony.DisconnectCause} disconnect code into a
     * {@link android.telecom.DisconnectCause} disconnect code.
     * @return The disconnect code as defined in {@link android.telecom.DisconnectCause}.
     */
    private static int toTelecomDisconnectCauseCode(int telephonyDisconnectCause) {
        switch (telephonyDisconnectCause) {
            case android.telephony.DisconnectCause.LOCAL:
                return DisconnectCause.LOCAL;

            case android.telephony.DisconnectCause.NORMAL:
            case android.telephony.DisconnectCause.NORMAL_UNSPECIFIED:
                return DisconnectCause.REMOTE;

            case android.telephony.DisconnectCause.OUTGOING_CANCELED:
                return DisconnectCause.CANCELED;

            case android.telephony.DisconnectCause.INCOMING_MISSED:
                return DisconnectCause.MISSED;

            case android.telephony.DisconnectCause.INCOMING_REJECTED:
                return DisconnectCause.REJECTED;

            case android.telephony.DisconnectCause.BUSY:
                return DisconnectCause.BUSY;

            case android.telephony.DisconnectCause.CALL_BARRED:
            case android.telephony.DisconnectCause.CDMA_ACCESS_BLOCKED:
            case android.telephony.DisconnectCause.CDMA_NOT_EMERGENCY:
            case android.telephony.DisconnectCause.CS_RESTRICTED:
            case android.telephony.DisconnectCause.CS_RESTRICTED_EMERGENCY:
            case android.telephony.DisconnectCause.CS_RESTRICTED_NORMAL:
            case android.telephony.DisconnectCause.EMERGENCY_ONLY:
            case android.telephony.DisconnectCause.FDN_BLOCKED:
            case android.telephony.DisconnectCause.LIMIT_EXCEEDED:
            case android.telephony.DisconnectCause.VIDEO_CALL_NOT_ALLOWED_WHILE_TTY_ENABLED:
                return DisconnectCause.RESTRICTED;

            case android.telephony.DisconnectCause.CDMA_ACCESS_FAILURE:
            case android.telephony.DisconnectCause.CDMA_ALREADY_ACTIVATED:
            case android.telephony.DisconnectCause.CDMA_CALL_LOST:
            case android.telephony.DisconnectCause.CDMA_DROP:
            case android.telephony.DisconnectCause.CDMA_INTERCEPT:
            case android.telephony.DisconnectCause.CDMA_LOCKED_UNTIL_POWER_CYCLE:
            case android.telephony.DisconnectCause.CDMA_PREEMPTED:
            case android.telephony.DisconnectCause.CDMA_REORDER:
            case android.telephony.DisconnectCause.CDMA_RETRY_ORDER:
            case android.telephony.DisconnectCause.CDMA_SO_REJECT:
            case android.telephony.DisconnectCause.CONGESTION:
            case android.telephony.DisconnectCause.ICC_ERROR:
            case android.telephony.DisconnectCause.INVALID_CREDENTIALS:
            case android.telephony.DisconnectCause.INVALID_NUMBER:
            case android.telephony.DisconnectCause.LOST_SIGNAL:
            case android.telephony.DisconnectCause.NO_PHONE_NUMBER_SUPPLIED:
            case android.telephony.DisconnectCause.NUMBER_UNREACHABLE:
            case android.telephony.DisconnectCause.OUTGOING_FAILURE:
            case android.telephony.DisconnectCause.OUT_OF_NETWORK:
            case android.telephony.DisconnectCause.OUT_OF_SERVICE:
            case android.telephony.DisconnectCause.POWER_OFF:
            case android.telephony.DisconnectCause.LOW_BATTERY:
            case android.telephony.DisconnectCause.DIAL_LOW_BATTERY:
            case android.telephony.DisconnectCause.SERVER_ERROR:
            case android.telephony.DisconnectCause.SERVER_UNREACHABLE:
            case android.telephony.DisconnectCause.TIMED_OUT:
            case android.telephony.DisconnectCause.ALREADY_DIALING:
            case android.telephony.DisconnectCause.CANT_CALL_WHILE_RINGING:
            case android.telephony.DisconnectCause.CALLING_DISABLED:
            case android.telephony.DisconnectCause.TOO_MANY_ONGOING_CALLS:
            case android.telephony.DisconnectCause.OTASP_PROVISIONING_IN_PROCESS:
            case android.telephony.DisconnectCause.UNOBTAINABLE_NUMBER:
            case android.telephony.DisconnectCause.VOICEMAIL_NUMBER_MISSING:
            case android.telephony.DisconnectCause.DIAL_MODIFIED_TO_USSD:
            case android.telephony.DisconnectCause.DIAL_MODIFIED_TO_SS:
            case android.telephony.DisconnectCause.DIAL_MODIFIED_TO_DIAL:
            case android.telephony.DisconnectCause.DIAL_MODIFIED_TO_DIAL_VIDEO:
            case android.telephony.DisconnectCause.DIAL_VIDEO_MODIFIED_TO_SS:
            case android.telephony.DisconnectCause.DIAL_VIDEO_MODIFIED_TO_USSD:
            case android.telephony.DisconnectCause.DIAL_VIDEO_MODIFIED_TO_DIAL:
            case android.telephony.DisconnectCause.DIAL_VIDEO_MODIFIED_TO_DIAL_VIDEO:
            case android.telephony.DisconnectCause.ERROR_UNSPECIFIED:
            case android.telephony.DisconnectCause.MAXIMUM_NUMBER_OF_CALLS_REACHED:
            case android.telephony.DisconnectCause.DATA_DISABLED:
            case android.telephony.DisconnectCause.DATA_LIMIT_REACHED:
            case android.telephony.DisconnectCause.DIALED_CALL_FORWARDING_WHILE_ROAMING:
            case android.telephony.DisconnectCause.IMEI_NOT_ACCEPTED:
            case android.telephony.DisconnectCause.WIFI_LOST:
            case android.telephony.DisconnectCause.IMS_ACCESS_BLOCKED:
            case android.telephony.DisconnectCause.IMS_SIP_ALTERNATE_EMERGENCY_CALL:
                return DisconnectCause.ERROR;

            case android.telephony.DisconnectCause.DIALED_MMI:
            case android.telephony.DisconnectCause.EXITED_ECM:
            case android.telephony.DisconnectCause.MMI:
            case android.telephony.DisconnectCause.IMS_MERGED_SUCCESSFULLY:
            case android.telephony.DisconnectCause.NO_CIRCUIT_AVAIL:
            case android.telephony.DisconnectCause.NO_ROUTE_TO_DESTINATION:
            case android.telephony.DisconnectCause.OPERATOR_DETERMINED_BARRING:
            case android.telephony.DisconnectCause.CALL_FAIL_NO_USER_RESPONDING:
            case android.telephony.DisconnectCause.CALL_FAIL_NO_ANSWER_FROM_USER:
            case android.telephony.DisconnectCause.CALL_FAIL_DESTINATION_OUT_OF_ORDER:
            case android.telephony.DisconnectCause.BEARER_CAPABILITY_NOT_AUTHORIZED:
            case android.telephony.DisconnectCause.CHANNEL_UNACCEPTABLE:
            case android.telephony.DisconnectCause.CALL_REJECTED:
            case android.telephony.DisconnectCause.NUMBER_CHANGED:
            case android.telephony.DisconnectCause.PREEMPTION:
            case android.telephony.DisconnectCause.FACILITY_REJECTED:
            case android.telephony.DisconnectCause.RESP_TO_STATUS_ENQUIRY:
            case android.telephony.DisconnectCause.NETWORK_OUT_OF_ORDER:
            case android.telephony.DisconnectCause.TEMPORARY_FAILURE:
            case android.telephony.DisconnectCause.SWITCHING_EQUIPMENT_CONGESTION:
            case android.telephony.DisconnectCause.ACCESS_INFORMATION_DISCARDED:
            case android.telephony.DisconnectCause.REQUESTED_CIRCUIT_OR_CHANNEL_NOT_AVAILABLE:
            case android.telephony.DisconnectCause.RESOURCES_UNAVAILABLE_OR_UNSPECIFIED:
            case android.telephony.DisconnectCause.QOS_UNAVAILABLE:
            case android.telephony.DisconnectCause.REQUESTED_FACILITY_NOT_SUBSCRIBED:
            case android.telephony.DisconnectCause.INCOMING_CALLS_BARRED_WITHIN_CUG:
            case android.telephony.DisconnectCause.BEARER_CAPABILITY_UNAVAILABLE:
            case android.telephony.DisconnectCause.SERVICE_OPTION_NOT_AVAILABLE:
            case android.telephony.DisconnectCause.BEARER_SERVICE_NOT_IMPLEMENTED:
            case android.telephony.DisconnectCause.REQUESTED_FACILITY_NOT_IMPLEMENTED:
            case android.telephony.DisconnectCause.ONLY_DIGITAL_INFORMATION_BEARER_AVAILABLE:
            case android.telephony.DisconnectCause.SERVICE_OR_OPTION_NOT_IMPLEMENTED:
            case android.telephony.DisconnectCause.INVALID_TRANSACTION_IDENTIFIER:
            case android.telephony.DisconnectCause.USER_NOT_MEMBER_OF_CUG:
            case android.telephony.DisconnectCause.INCOMPATIBLE_DESTINATION:
            case android.telephony.DisconnectCause.INVALID_TRANSIT_NW_SELECTION:
            case android.telephony.DisconnectCause.SEMANTICALLY_INCORRECT_MESSAGE:
            case android.telephony.DisconnectCause.INVALID_MANDATORY_INFORMATION:
            case android.telephony.DisconnectCause.MESSAGE_TYPE_NON_IMPLEMENTED:
            case android.telephony.DisconnectCause.MESSAGE_TYPE_NOT_COMPATIBLE_WITH_PROTOCOL_STATE:
            case android.telephony.DisconnectCause.INFORMATION_ELEMENT_NON_EXISTENT:
            case android.telephony.DisconnectCause.CONDITIONAL_IE_ERROR:
            case android.telephony.DisconnectCause.MESSAGE_NOT_COMPATIBLE_WITH_PROTOCOL_STATE:
            case android.telephony.DisconnectCause.RECOVERY_ON_TIMER_EXPIRED:
            case android.telephony.DisconnectCause.PROTOCOL_ERROR_UNSPECIFIED:
            case android.telephony.DisconnectCause.INTERWORKING_UNSPECIFIED:
                return DisconnectCause.OTHER;

            case android.telephony.DisconnectCause.NOT_VALID:
            case android.telephony.DisconnectCause.NOT_DISCONNECTED:
                return DisconnectCause.UNKNOWN;

            case android.telephony.DisconnectCause.CALL_PULLED:
                return DisconnectCause.CALL_PULLED;

            case android.telephony.DisconnectCause.ANSWERED_ELSEWHERE:
                return DisconnectCause.ANSWERED_ELSEWHERE;

            default:
                Log.w("DisconnectCauseUtil.toTelecomDisconnectCauseCode",
                        "Unrecognized Telephony DisconnectCause "
                        + telephonyDisconnectCause);
                return DisconnectCause.UNKNOWN;
        }
    }

    /**
     * Returns a label for to the disconnect cause to be shown to the user.
     */
    private static CharSequence toTelecomDisconnectCauseLabel(
            Context context, int telephonyDisconnectCause, int telephonyPerciseDisconnectCause) {
        CharSequence label;
        if (telephonyPerciseDisconnectCause != CallFailCause.NOT_VALID) {
            label = getLabelFromPreciseDisconnectCause(context, telephonyPerciseDisconnectCause,
                    telephonyDisconnectCause);
        } else {
            label = getLabelFromDisconnectCause(context, telephonyDisconnectCause);
        }
        return label;
    }

    /**
     * Returns a label for to the generic disconnect cause to be shown to the user.
     */
    private static CharSequence getLabelFromDisconnectCause(
            Context context, int telephonyDisconnectCause) {
        if (context == null) {
            return "";
        }

        Integer resourceId = null;
        switch (telephonyDisconnectCause) {
            case android.telephony.DisconnectCause.BUSY:
                resourceId = R.string.callFailed_userBusy;
                break;

            case android.telephony.DisconnectCause.CDMA_REORDER:
                resourceId = R.string.callFailed_NetworkBusy;
                break;

            case android.telephony.DisconnectCause.IMS_ACCESS_BLOCKED:
                resourceId = R.string.callFailed_NetworkCongested;
                break;

            case android.telephony.DisconnectCause.CONGESTION:
                resourceId = R.string.callFailed_congestion;
                break;

            case android.telephony.DisconnectCause.TIMED_OUT:
                resourceId = R.string.callFailed_timedOut;
                break;

            case android.telephony.DisconnectCause.SERVER_UNREACHABLE:
                resourceId = R.string.callFailed_server_unreachable;
                break;

            case android.telephony.DisconnectCause.NUMBER_UNREACHABLE:
                resourceId = R.string.callFailed_number_unreachable;
                break;

            case android.telephony.DisconnectCause.INVALID_CREDENTIALS:
                resourceId = R.string.callFailed_invalid_credentials;
                break;

            case android.telephony.DisconnectCause.SERVER_ERROR:
                resourceId = R.string.callFailed_server_error;
                break;

            case android.telephony.DisconnectCause.OUT_OF_NETWORK:
                resourceId = R.string.callFailed_out_of_network;
                break;

            case android.telephony.DisconnectCause.LOST_SIGNAL:
            case android.telephony.DisconnectCause.CDMA_DROP:
                resourceId = R.string.callFailed_noSignal;
                break;

            case android.telephony.DisconnectCause.LIMIT_EXCEEDED:
                resourceId = R.string.callFailed_limitExceeded;
                break;

            case android.telephony.DisconnectCause.POWER_OFF:
                resourceId = R.string.callFailed_powerOff;
                break;

            case android.telephony.DisconnectCause.LOW_BATTERY:
                resourceId = R.string.callFailed_low_battery;
                break;

            case android.telephony.DisconnectCause.DIAL_LOW_BATTERY:
                resourceId = R.string.dialFailed_low_battery;
                break;

            case android.telephony.DisconnectCause.ICC_ERROR:
                resourceId = R.string.callFailed_simError;
                break;

            case android.telephony.DisconnectCause.OUT_OF_SERVICE:
                resourceId = R.string.callFailed_outOfService;
                break;

            case android.telephony.DisconnectCause.INVALID_NUMBER:
            case android.telephony.DisconnectCause.UNOBTAINABLE_NUMBER:
                resourceId = R.string.callFailed_unobtainable_number;
                break;

            case android.telephony.DisconnectCause.VOICEMAIL_NUMBER_MISSING:
                resourceId = R.string.incall_error_missing_voicemail_number;
                break;

            case android.telephony.DisconnectCause.CALL_PULLED:
                resourceId = R.string.callEnded_pulled;
                break;

            case android.telephony.DisconnectCause.MAXIMUM_NUMBER_OF_CALLS_REACHED:
                resourceId = R.string.callFailed_maximum_reached;
                break;

            case android.telephony.DisconnectCause.DATA_DISABLED:
                resourceId = R.string.callFailed_data_disabled;
                break;

            case android.telephony.DisconnectCause.DATA_LIMIT_REACHED:
                resourceId = R.string.callFailed_data_limit_reached;
                break;
            case android.telephony.DisconnectCause.ALREADY_DIALING:
                resourceId = R.string.callFailed_already_dialing;
                break;
            case android.telephony.DisconnectCause.CANT_CALL_WHILE_RINGING:
                resourceId = R.string.callFailed_already_ringing;
                break;
            case android.telephony.DisconnectCause.CALLING_DISABLED:
                resourceId = R.string.callFailed_calling_disabled;
                break;
            case android.telephony.DisconnectCause.TOO_MANY_ONGOING_CALLS:
                resourceId = R.string.callFailed_too_many_calls;
                break;
            case android.telephony.DisconnectCause.IMS_SIP_ALTERNATE_EMERGENCY_CALL:
                resourceId = R.string.incall_error_power_off;
                break;
            case android.telephony.DisconnectCause.OTASP_PROVISIONING_IN_PROCESS:
                resourceId = R.string.callFailed_otasp_provisioning_in_process;
                break;
            default:
                break;
        }
        return resourceId == null ? "" : context.getResources().getString(resourceId);
    }

    /**
     * Returns a label for to the precise disconnect cause to be shown to the user.
     */
    private static CharSequence getLabelFromPreciseDisconnectCause(
            Context context, int telephonyPreciseDisconnectCause, int telephonyDisconnectCause) {
        if (context == null) {
            return "";
        }

        Integer resourceId = null;
        switch (telephonyPreciseDisconnectCause) {
            case CallFailCause.UNOBTAINABLE_NUMBER:
                resourceId = R.string.clh_callFailed_unassigned_number_txt;
                break;
            case CallFailCause.NO_ROUTE_TO_DEST:
                resourceId = R.string.clh_callFailed_no_route_to_destination_txt;
                break;
            case CallFailCause.CHANNEL_UNACCEPTABLE:
                resourceId = R.string.clh_callFailed_channel_unacceptable_txt;
                break;
            case CallFailCause.OPERATOR_DETERMINED_BARRING:
                resourceId = R.string.clh_callFailed_operator_determined_barring_txt;
                break;
            case CallFailCause.NORMAL_CLEARING:
                resourceId = R.string.clh_callFailed_normal_call_clearing_txt;
                break;
            case CallFailCause.USER_BUSY:
                resourceId = R.string.clh_callFailed_user_busy_txt;
                break;
            case CallFailCause.NO_USER_RESPONDING:
                resourceId = R.string.clh_callFailed_no_user_responding_txt;
                break;
            case CallFailCause.USER_ALERTING_NO_ANSWER:
                resourceId = R.string.clh_callFailed_user_alerting_txt;
                break;
            case CallFailCause.CALL_REJECTED:
                resourceId = R.string.clh_callFailed_call_rejected_txt;
                break;
            case CallFailCause.NUMBER_CHANGED:
                resourceId = R.string.clh_callFailed_number_changed_txt;
                break;
            case CallFailCause.PRE_EMPTION:
                resourceId = R.string.clh_callFailed_pre_emption_txt;
                break;
            case CallFailCause.NON_SELECTED_USER_CLEARING:
                resourceId = R.string.clh_callFailed_non_selected_user_clearing_txt;
                break;
            case CallFailCause.DESTINATION_OUT_OF_ORDER:
                resourceId = R.string.clh_callFailed_destination_out_of_order_txt;
                break;
            case CallFailCause.INVALID_NUMBER_FORMAT:
                resourceId = R.string.clh_callFailed_invalid_number_format_txt;
                break;
            case CallFailCause.FACILITY_REJECTED:
                resourceId = R.string.clh_callFailed_facility_rejected_txt;
                break;
            case CallFailCause.STATUS_ENQUIRY:
                resourceId = R.string.clh_callFailed_response_to_STATUS_ENQUIRY_txt;
                break;
            case CallFailCause.NORMAL_UNSPECIFIED:
                resourceId = R.string.clh_callFailed_normal_unspecified_txt;
                break;
            case CallFailCause.NO_CIRCUIT_AVAIL:
                resourceId = R.string.clh_callFailed_no_circuit_available_txt;
                break;
            case CallFailCause.NETWORK_OUT_OF_ORDER:
                resourceId = R.string.clh_callFailed_network_out_of_order_txt;
                break;
            case CallFailCause.TEMPORARY_FAILURE:
                resourceId = R.string.clh_callFailed_temporary_failure_txt;
                break;
            case CallFailCause.SWITCHING_CONGESTION:
                resourceId = R.string.clh_callFailed_switching_equipment_congestion_txt;
                break;
            case CallFailCause.ACCESS_INFORMATION_DISCARDED:
                resourceId = R.string.clh_callFailed_access_information_discarded_txt;
                break;
            case CallFailCause.CHANNEL_NOT_AVAIL:
                resourceId = R.string.clh_callFailed_requested_circuit_txt;
                break;
            case CallFailCause.RESOURCES_UNAVAILABLE_UNSPECIFIED:
                resourceId = R.string.clh_callFailed_resources_unavailable_unspecified_txt;
                break;
            case CallFailCause.QOS_NOT_AVAIL:
                resourceId = R.string.clh_callFailed_quality_of_service_unavailable_txt;
                break;
            case CallFailCause.REQUESTED_FACILITY_NOT_SUBSCRIBED:
                resourceId = R.string.clh_callFailed_requested_facility_not_subscribed_txt;
                break;
            case CallFailCause.INCOMING_CALL_BARRED_WITHIN_CUG:
                resourceId = R.string.clh_callFailed_incoming_calls_barred_within_the_CUG_txt;
                break;
            case CallFailCause.BEARER_CAPABILITY_NOT_AUTHORISED:
                resourceId = R.string.clh_callFailed_bearer_capability_not_authorized_txt;
                break;
            case CallFailCause.BEARER_NOT_AVAIL:
                resourceId = R.string.clh_callFailed_bearer_capability_not_presently_available_txt;
                break;
            case CallFailCause.SERVICE_OR_OPTION_NOT_AVAILABLE:
                resourceId =
                        R.string.clh_callFailed_service_or_option_not_available_unspecified_txt;
                break;
            case CallFailCause.BEARER_SERVICE_NOT_IMPLEMENTED:
                resourceId = R.string.clh_callFailed_bearer_service_not_implemented_txt;
                break;
            case CallFailCause.ACM_LIMIT_EXCEEDED:
                resourceId = R.string.clh_callFailed_ACM_equal_to_or_greater_than_ACMmax_txt;
                break;
            case CallFailCause.REQUESTED_FACILITY_NOT_IMPLEMENTED:
                resourceId = R.string.clh_callFailed_requested_facility_not_implemented_txt;
                break;
            case CallFailCause.ONLY_RESTRICTED_DIGITAL_INFO_BC_AVAILABLE:
                resourceId = R.string
                        .clh_callFailed_only_restricted_digital_information_bearer_capability_is_available_txt;
                break;
            case CallFailCause.SERVICE_OR_OPTION_NOT_IMPLEMENTED:
                resourceId =
                        R.string.clh_callFailed_service_or_option_not_implemented_unspecified_txt;
                break;
            case CallFailCause.INVALID_TRANSACTION_ID_VALUE:
                resourceId = R.string.clh_callFailed_invalid_transaction_identifier_value_txt;
                break;
            case CallFailCause.USER_NOT_MEMBER_OF_CUG:
                resourceId = R.string.clh_callFailed_user_not_member_of_CUG_txt;
                break;
            case CallFailCause.INCOMPATIBLE_DESTINATION:
                resourceId = R.string.clh_callFailed_incompatible_destination_txt;
                break;
            case CallFailCause.INVALID_TRANSIT_NETWORK_SELECTION:
                resourceId = R.string.clh_callFailed_invalid_transit_network_selection_txt;
                break;
            case CallFailCause.SEMANTICALLY_INCORRECT_MESSAGE:
                resourceId = R.string.clh_callFailed_semantically_incorrect_message_txt;
                break;
            case CallFailCause.INVALID_MANDATORY_INFORMATION:
                resourceId = R.string.clh_callFailed_invalid_mandatory_information_txt;
                break;
            case CallFailCause.MESSAGE_TYPE_NON_EXISTENT:
                resourceId =
                        R.string.clh_callFailed_message_type_non_existent_or_not_implemented_txt;
                break;
            case CallFailCause.MESSAGE_TYPE_NOT_COMPATIBLE_WITH_PROT_STATE:
                resourceId = R.string
                        .clh_callFailed_message_type_not_compatible_with_protocol_state_txt;
                break;
            case CallFailCause.IE_NON_EXISTENT_OR_NOT_IMPLEMENTED:
                resourceId = R.string
                        .clh_callFailed_information_element_non_existent_or_not_implemented_txt;
                break;
            case CallFailCause.CONDITIONAL_IE_ERROR:
                resourceId = R.string.clh_callFailed_conditional_IE_error_txt;
                break;
            case CallFailCause.MESSAGE_NOT_COMPATIBLE_WITH_PROTOCOL_STATE:
                resourceId = R.string.clh_callFailed_message_not_compatible_with_protocol_state_txt;
                break;
            case CallFailCause.RECOVERY_ON_TIMER_EXPIRY:
                resourceId = R.string.clh_callFailed_recovery_on_timer_expiry_txt;
                break;
            case CallFailCause.PROTOCOL_ERROR_UNSPECIFIED:
                resourceId = R.string.clh_callFailed_protocol_Error_unspecified_txt;
                break;
            case CallFailCause.INTERWORKING_UNSPECIFIED:
                resourceId = R.string.clh_callFailed_interworking_unspecified_txt;
                break;
            default:
                switch (telephonyDisconnectCause) {
                    case android.telephony.DisconnectCause.POWER_OFF:
                        resourceId = R.string.clh_callFailed_powerOff_txt;
                        break;
                    case android.telephony.DisconnectCause.ICC_ERROR:
                        resourceId = R.string.clh_callFailed_simError_txt;
                        break;
                    case android.telephony.DisconnectCause.OUT_OF_SERVICE:
                        resourceId = R.string.clh_incall_error_out_of_service_txt;
                        break;
                    default:
                        resourceId = R.string.clh_card_title_call_ended_txt;
                        break;
                }
                break;
        }
        return context.getResources().getString(resourceId);
    }

    /**
     * Returns a description of the disconnect cause to be shown to the user.
     */
    private static CharSequence toTelecomDisconnectCauseDescription(
            Context context, int telephonyDisconnectCause, int phoneId) {
        if (context == null ) {
            return "";
        }

        Integer resourceId = null;
        switch (telephonyDisconnectCause) {
            case android.telephony.DisconnectCause.INCOMING_MISSED: {
                // If the network sends SVC Notification then this dialog will be displayed
                // in case of B when the incoming call at B is not answered and gets forwarded
                // to C
                if (mNotificationType == SuppServiceNotification.NOTIFICATION_TYPE_CODE_2 &&
                        mNotificationCode ==
                        SuppServiceNotification.CODE_2_ADDITIONAL_CALL_FORWARDED) {
                    resourceId = R.string.callUnanswered_forwarded;
                }
                break;
            }

            case android.telephony.DisconnectCause.CALL_BARRED:{
                // When call is disconnected with this code then it can either be barring from
                // MO side or MT side.
                // In MT case, if network sends SVC Notification then this dialog will be
                // displayed when A is calling B & incoming is barred on B.
                if (mNotificationType == SuppServiceNotification.NOTIFICATION_TYPE_CODE_1 &&
                        mNotificationCode ==
                        SuppServiceNotification.CODE_1_INCOMING_CALLS_BARRED) {
                    resourceId = R.string.callFailed_incoming_cb_enabled;
                } else {
                    resourceId = R.string.callFailed_cb_enabled;
                }
                break;
            }

            case android.telephony.DisconnectCause.CDMA_ALREADY_ACTIVATED:
                resourceId = R.string.callFailed_cdma_activation;
                break;

            case android.telephony.DisconnectCause.CDMA_REORDER:
                resourceId = R.string.callFailed_NetworkBusy;
                break;

            case android.telephony.DisconnectCause.IMS_ACCESS_BLOCKED:
                resourceId = R.string.callFailed_NetworkCongested;
                break;

            case android.telephony.DisconnectCause.FDN_BLOCKED:
                resourceId = R.string.callFailed_fdn_only;
                break;

            case android.telephony.DisconnectCause.CS_RESTRICTED:
                resourceId = R.string.callFailed_dsac_restricted;
                break;

            case android.telephony.DisconnectCause.CS_RESTRICTED_EMERGENCY:
                resourceId = R.string.callFailed_dsac_restricted_emergency;
                break;

            case android.telephony.DisconnectCause.CS_RESTRICTED_NORMAL:
                resourceId = R.string.callFailed_dsac_restricted_normal;
                break;

            case android.telephony.DisconnectCause.DIAL_MODIFIED_TO_USSD:
                resourceId = R.string.callFailed_dialToUssd;
                break;

            case android.telephony.DisconnectCause.DIAL_MODIFIED_TO_SS:
                resourceId = R.string.callFailed_dialToSs;
                break;

            case android.telephony.DisconnectCause.DIAL_MODIFIED_TO_DIAL:
                resourceId = R.string.callFailed_dialToDial;
                break;

            case android.telephony.DisconnectCause.DIAL_MODIFIED_TO_DIAL_VIDEO:
                resourceId = R.string.callFailed_dialToDialVideo;
                break;

            case android.telephony.DisconnectCause.DIAL_VIDEO_MODIFIED_TO_SS:
                resourceId = R.string.callFailed_dialVideoToSs;
                break;

            case android.telephony.DisconnectCause.DIAL_VIDEO_MODIFIED_TO_USSD:
                resourceId = R.string.callFailed_dialVideoToUssd;
                break;

            case android.telephony.DisconnectCause.DIAL_VIDEO_MODIFIED_TO_DIAL:
                resourceId = R.string.callFailed_dialVideoToDial;
                break;

            case android.telephony.DisconnectCause.DIAL_VIDEO_MODIFIED_TO_DIAL_VIDEO:
                resourceId = R.string.callFailed_dialVideoToDialVideo;
                break;

            case android.telephony.DisconnectCause.OUTGOING_FAILURE:
                // We couldn't successfully place the call; there was some
                // failure in the telephony layer.
                // TODO: Need UI spec for this failure case; for now just
                // show a generic error.
                resourceId = R.string.incall_error_outgoing_call_failed;
                break;

            case android.telephony.DisconnectCause.POWER_OFF:
                // Radio is explictly powered off because the device is in airplane mode.

                // TODO: Offer the option to turn the radio on, and automatically retry the call
                // once network registration is complete.

                if (ImsUtil.shouldPromoteWfc(context, phoneId)) {
                    resourceId = R.string.incall_error_promote_wfc;
                } else if (ImsUtil.isWfcModeWifiOnly(context, phoneId)) {
                    resourceId = R.string.incall_error_wfc_only_no_wireless_network;
                } else if (ImsUtil.isWfcEnabled(context, phoneId)) {
                    resourceId = R.string.incall_error_power_off_wfc;
                } else {
                    resourceId = R.string.incall_error_power_off;
                }
                break;

            case android.telephony.DisconnectCause.LOW_BATTERY:
                resourceId = R.string.callFailed_low_battery;
                break;

            case android.telephony.DisconnectCause.DIAL_LOW_BATTERY:
                resourceId = R.string.dialFailed_low_battery;
                break;

            case android.telephony.DisconnectCause.CDMA_NOT_EMERGENCY:
                // Only emergency calls are allowed when in emergency callback mode.
                resourceId = R.string.incall_error_ecm_emergency_only;
                break;

            case android.telephony.DisconnectCause.EMERGENCY_ONLY:
                // Only emergency numbers are allowed, but we tried to dial
                // a non-emergency number.
                resourceId = R.string.incall_error_emergency_only;
                break;

            case android.telephony.DisconnectCause.OUT_OF_SERVICE:
                // No network connection.
                if (ImsUtil.shouldPromoteWfc(context, phoneId)) {
                    resourceId = R.string.incall_error_promote_wfc;
                } else if (ImsUtil.isWfcModeWifiOnly(context, phoneId)) {
                    resourceId = R.string.incall_error_wfc_only_no_wireless_network;
                } else if (ImsUtil.isWfcEnabled(context, phoneId)) {
                    resourceId = R.string.incall_error_out_of_service_wfc;
                } else {
                    resourceId = R.string.incall_error_out_of_service;
                }
                break;

            case android.telephony.DisconnectCause.NO_PHONE_NUMBER_SUPPLIED:
                // The supplied Intent didn't contain a valid phone number.
                // (This is rare and should only ever happen with broken
                // 3rd-party apps.) For now just show a generic error.
                resourceId = R.string.incall_error_no_phone_number_supplied;
                break;

            case android.telephony.DisconnectCause.VOICEMAIL_NUMBER_MISSING:
                resourceId = R.string.incall_error_missing_voicemail_number;
                break;

            case android.telephony.DisconnectCause.VIDEO_CALL_NOT_ALLOWED_WHILE_TTY_ENABLED:
                resourceId = R.string.callFailed_video_call_tty_enabled;
                break;

            case android.telephony.DisconnectCause.CALL_PULLED:
                resourceId = R.string.callEnded_pulled;
                break;

            case android.telephony.DisconnectCause.MAXIMUM_NUMBER_OF_CALLS_REACHED:
                resourceId = R.string.callFailed_maximum_reached;
            // Supplementary for Clear Code
            case android.telephony.DisconnectCause.BUSY:
                resourceId = R.string.callFailed_userBusy;
                break;

            case android.telephony.DisconnectCause.NO_CIRCUIT_AVAIL:
                resourceId = R.string.callFailed_no_circuit_avail;
                break;

            case android.telephony.DisconnectCause.TEMPORARY_FAILURE:
                resourceId = R.string.callFailed_tempopary_failure;
                break;

            case android.telephony.DisconnectCause.SWITCHING_EQUIPMENT_CONGESTION:
                resourceId = R.string.callFailed_switching_equipment_congestion;
                break;

            case android.telephony.DisconnectCause.REQUESTED_CIRCUIT_OR_CHANNEL_NOT_AVAILABLE:
                resourceId = R.string.callFailed_requested_circuit_or_channel_not_available;
                break;

            case android.telephony.DisconnectCause.QOS_UNAVAILABLE:
                resourceId = R.string.callFailed_QOS_unavailable;
                break;

            case android.telephony.DisconnectCause.BEARER_CAPABILITY_UNAVAILABLE:
                resourceId = R.string.callFailed_bearer_capability_unavailable;
                break;

            case android.telephony.DisconnectCause.LIMIT_EXCEEDED:
                resourceId = R.string.callFailed_limitExceeded;

            case android.telephony.DisconnectCause.INVALID_NUMBER:
                resourceId = R.string.callFailed_invalid_number_format;
                break;

            case android.telephony.DisconnectCause.UNOBTAINABLE_NUMBER:
                resourceId = R.string.callFailed_unobtainable_number;
                break;

            case android.telephony.DisconnectCause.NO_ROUTE_TO_DESTINATION:
                resourceId = R.string.callFailed_no_route_to_destination;
                break;

            case android.telephony.DisconnectCause.CALL_FAIL_NO_USER_RESPONDING:
                resourceId = R.string.callFailed_no_user_responding;
                break;

            case android.telephony.DisconnectCause.CALL_FAIL_NO_ANSWER_FROM_USER:
                resourceId = R.string.callFailed_no_answer_from_user;
                break;

            case android.telephony.DisconnectCause.CALL_FAIL_DESTINATION_OUT_OF_ORDER:
                resourceId = R.string.callFailed_destination_out_of_order;
                break;

            case android.telephony.DisconnectCause.BEARER_CAPABILITY_NOT_AUTHORIZED:
                resourceId = R.string.callFailed_bearer_capability_not_authorized;
                break;

            case android.telephony.DisconnectCause.CHANNEL_UNACCEPTABLE:
                resourceId = R.string.callFailed_channel_unacceptable;
                break;

            case android.telephony.DisconnectCause.OPERATOR_DETERMINED_BARRING:
                resourceId = R.string.callFailed_operator_determined_barring;
                break;

            case android.telephony.DisconnectCause.CALL_REJECTED:
                resourceId = R.string.callFailed_call_rejected;
                break;

            case android.telephony.DisconnectCause.NUMBER_CHANGED:
                resourceId = R.string.callFailed_number_changed;
                break;

            case android.telephony.DisconnectCause.PREEMPTION:
                resourceId = R.string.callFailed_preemption;
                break;

            case android.telephony.DisconnectCause.FACILITY_REJECTED:
                resourceId = R.string.callFailed_facility_rejected;
                break;

            case android.telephony.DisconnectCause.RESP_TO_STATUS_ENQUIRY:
                resourceId = R.string.callFailed_resp_to_status_enquiry;
                break;

            case android.telephony.DisconnectCause.NORMAL_UNSPECIFIED:
                resourceId = R.string.callFailed_normal_unspecified;
                break;

            case android.telephony.DisconnectCause.NETWORK_OUT_OF_ORDER:
                resourceId = R.string.callFailed_network_out_of_order;
                break;

            case android.telephony.DisconnectCause.ACCESS_INFORMATION_DISCARDED:
                resourceId = R.string.callFailed_access_information_discarded;
                break;

            case android.telephony.DisconnectCause.RESOURCES_UNAVAILABLE_OR_UNSPECIFIED:
                resourceId = R.string.callFailed_resources_unavailable_or_unspecified;
                break;

            case android.telephony.DisconnectCause.REQUESTED_FACILITY_NOT_SUBSCRIBED:
                resourceId = R.string.callFailed_requested_facility_not_subscribed;
                break;

            case android.telephony.DisconnectCause.INCOMING_CALLS_BARRED_WITHIN_CUG:
                resourceId = R.string.callFailed_incoming_calls_barred_within_CUG;
                break;

            case android.telephony.DisconnectCause.SERVICE_OPTION_NOT_AVAILABLE:
                resourceId = R.string.callFailed_service_option_not_available;
                break;

            case android.telephony.DisconnectCause.BEARER_SERVICE_NOT_IMPLEMENTED:
                resourceId = R.string.callFailed_bearer_service_not_implemented;
                break;

            case android.telephony.DisconnectCause.REQUESTED_FACILITY_NOT_IMPLEMENTED:
                resourceId = R.string.callFailed_requested_facility_not_implemented;
                break;

            case android.telephony.DisconnectCause.ONLY_DIGITAL_INFORMATION_BEARER_AVAILABLE:
                resourceId = R.string.callFailed_only_digital_information_bearer_available;
                break;

            case android.telephony.DisconnectCause.SERVICE_OR_OPTION_NOT_IMPLEMENTED:
                resourceId = R.string.callFailed_service_or_option_not_implemented;
                break;

            case android.telephony.DisconnectCause.INVALID_TRANSACTION_IDENTIFIER:
                resourceId = R.string.callFailed_invalid_transaction_identifier;
                break;

            case android.telephony.DisconnectCause.USER_NOT_MEMBER_OF_CUG:
                resourceId = R.string.callFailed_user_not_member_of_CUG;
                break;

            case android.telephony.DisconnectCause.INCOMPATIBLE_DESTINATION:
                resourceId = R.string.callFailed_incompatible_destination;
                break;

            case android.telephony.DisconnectCause.INVALID_TRANSIT_NW_SELECTION:
                resourceId = R.string.callFailed_invalid_transit_NW_selection;
                break;

            case android.telephony.DisconnectCause.SEMANTICALLY_INCORRECT_MESSAGE:
                resourceId = R.string.callFailed_semantically_incorrect_message;
                break;

            case android.telephony.DisconnectCause.INVALID_MANDATORY_INFORMATION:
                resourceId = R.string.callFailed_invalid_mandatory_information;
                break;

            case android.telephony.DisconnectCause.MESSAGE_TYPE_NON_IMPLEMENTED:
                resourceId = R.string.callFailed_message_type_non_implemented;
                break;

            case android.telephony.DisconnectCause.MESSAGE_TYPE_NOT_COMPATIBLE_WITH_PROTOCOL_STATE:
                resourceId = R.string.callFailed_message_type_not_compatible_with_protocol_state;
                break;

            case android.telephony.DisconnectCause.INFORMATION_ELEMENT_NON_EXISTENT:
                resourceId = R.string.callFailed_information_element_non_existent;
                break;

            case android.telephony.DisconnectCause.CONDITIONAL_IE_ERROR:
                resourceId = R.string.callFailed_conditional_IE_error;
                break;

            case android.telephony.DisconnectCause.MESSAGE_NOT_COMPATIBLE_WITH_PROTOCOL_STATE:
                resourceId = R.string.callFailed_message_not_compatible_with_protocol_state;
                break;

            case android.telephony.DisconnectCause.RECOVERY_ON_TIMER_EXPIRED:
                resourceId = R.string.callFailed_recovery_on_timer_expired;
                break;

            case android.telephony.DisconnectCause.PROTOCOL_ERROR_UNSPECIFIED:
                resourceId = R.string.callFailed_protocol_error_unspecified;
                break;

            case android.telephony.DisconnectCause.INTERWORKING_UNSPECIFIED:
                resourceId = R.string.callFailed_interworking_unspecified;
                break;

            case android.telephony.DisconnectCause.NORMAL:
                resourceId = R.string.callFailed_normal;
                break;

            case android.telephony.DisconnectCause.NON_SELECTED_USER_CLEARING:
                resourceId = R.string.callFailed_non_selected_user_clearing;
                break;

            case android.telephony.DisconnectCause.OUTGOING_CANCELED:
                // We don't want to show any dialog for the canceled case since the call was
                // either canceled by the user explicitly (end-call button pushed immediately)
                // or some other app canceled the call and immediately issued a new CALL to
                // replace it.
                break;

            case android.telephony.DisconnectCause.DATA_DISABLED:
                resourceId = R.string.callFailed_data_disabled;
                break;

            case android.telephony.DisconnectCause.DATA_LIMIT_REACHED:
                resourceId = R.string.callFailed_data_limit_reached_description;
                break;
            case android.telephony.DisconnectCause.DIALED_CALL_FORWARDING_WHILE_ROAMING:
                resourceId = com.android.internal.R.string.mmiErrorWhileRoaming;
                break;

            case android.telephony.DisconnectCause.IMEI_NOT_ACCEPTED:
                resourceId = R.string.callFailed_imei_not_accepted;
                break;

            case android.telephony.DisconnectCause.WIFI_LOST:
                resourceId = R.string.callFailed_wifi_lost;
                break;
            case android.telephony.DisconnectCause.ALREADY_DIALING:
                resourceId = R.string.callFailed_already_dialing;
                break;
            case android.telephony.DisconnectCause.CANT_CALL_WHILE_RINGING:
                resourceId = R.string.callFailed_already_ringing;
                break;
            case android.telephony.DisconnectCause.CALLING_DISABLED:
                resourceId = R.string.callFailed_calling_disabled;
                break;
            case android.telephony.DisconnectCause.TOO_MANY_ONGOING_CALLS:
                resourceId = R.string.callFailed_too_many_calls;
                break;
            case android.telephony.DisconnectCause.IMS_SIP_ALTERNATE_EMERGENCY_CALL:
                boolean isAirplaneModeOn = Settings.Global.getInt(context.getContentResolver(),
                        Settings.Global.AIRPLANE_MODE_ON, 0) > 0;
                if (isAirplaneModeOn) {
                    resourceId = R.string.incall_error_power_off;
                }
                break;
            case android.telephony.DisconnectCause.OTASP_PROVISIONING_IN_PROCESS:
                resourceId = R.string.callFailed_otasp_provisioning_in_process;
                break;
            default:
                break;
        }
        return resourceId == null ? "" : context.getResources().getString(resourceId);
    }

    /**
     * Maps the telephony {@link android.telephony.DisconnectCause} into a reason string which is
     * returned in the Telecom {@link DisconnectCause#getReason()}.
     *
     * @param context The current context.
     * @param telephonyDisconnectCause The {@link android.telephony.DisconnectCause} code.
     * @param reason A reason provided by the caller; only used if a more specific reason cannot
     *               be determined here.
     * @return The disconnect reason.
     */
    private static String toTelecomDisconnectReason(Context context, int telephonyDisconnectCause,
            String reason, int phoneId) {

        if (context == null) {
            return "";
        }

        switch (telephonyDisconnectCause) {
            case android.telephony.DisconnectCause.POWER_OFF:
                // Airplane mode (radio off)
                // intentional fall-through
            case android.telephony.DisconnectCause.OUT_OF_SERVICE:
                // No network connection.
                if (ImsUtil.shouldPromoteWfc(context, phoneId)) {
                    return android.telecom.DisconnectCause.REASON_WIFI_ON_BUT_WFC_OFF;
                }
                break;
            case android.telephony.DisconnectCause.IMS_ACCESS_BLOCKED:
                return DisconnectCause.REASON_IMS_ACCESS_BLOCKED;
        }

        // If no specific code-mapping found, then fall back to using the reason.
        String causeAsString = android.telephony.DisconnectCause.toString(telephonyDisconnectCause);
        if (reason == null) {
            return causeAsString;
        } else {
            return reason + ", " + causeAsString;
        }
    }

    /**
     * Returns the tone to play for the disconnect cause, or UNKNOWN if none should be played.
     */
    private static int toTelecomDisconnectCauseTone(int telephonyDisconnectCause) {
        switch (telephonyDisconnectCause) {
            case android.telephony.DisconnectCause.BUSY:
                return ToneGenerator.TONE_SUP_BUSY;

            case android.telephony.DisconnectCause.CONGESTION:
                return ToneGenerator.TONE_SUP_CONGESTION;

            case android.telephony.DisconnectCause.CDMA_REORDER:
                return ToneGenerator.TONE_CDMA_REORDER;

            case android.telephony.DisconnectCause.CDMA_INTERCEPT:
                return ToneGenerator.TONE_CDMA_ABBR_INTERCEPT;

            case android.telephony.DisconnectCause.CDMA_DROP:
            case android.telephony.DisconnectCause.OUT_OF_SERVICE:
                return ToneGenerator.TONE_CDMA_CALLDROP_LITE;

            case android.telephony.DisconnectCause.NO_ROUTE_TO_DESTINATION:
            case android.telephony.DisconnectCause.OPERATOR_DETERMINED_BARRING:
            case android.telephony.DisconnectCause.CALL_FAIL_NO_USER_RESPONDING:
            case android.telephony.DisconnectCause.NUMBER_CHANGED:
            case android.telephony.DisconnectCause.CALL_FAIL_DESTINATION_OUT_OF_ORDER:
            case android.telephony.DisconnectCause.BEARER_CAPABILITY_NOT_AUTHORIZED:
            case android.telephony.DisconnectCause.USER_NOT_MEMBER_OF_CUG:
            case android.telephony.DisconnectCause.UNOBTAINABLE_NUMBER:
                return ToneGenerator.TONE_SUP_ERROR;

            case android.telephony.DisconnectCause.ERROR_UNSPECIFIED:
            case android.telephony.DisconnectCause.LOCAL:
            case android.telephony.DisconnectCause.NORMAL:
            case android.telephony.DisconnectCause.NORMAL_UNSPECIFIED:
            case android.telephony.DisconnectCause.VIDEO_CALL_NOT_ALLOWED_WHILE_TTY_ENABLED:
                return ToneGenerator.TONE_PROP_PROMPT;

            case android.telephony.DisconnectCause.IMS_MERGED_SUCCESSFULLY:
                // Do not play any tones if disconnected because of a successful merge.
            default:
                return ToneGenerator.TONE_UNKNOWN;
        }
    }
}
