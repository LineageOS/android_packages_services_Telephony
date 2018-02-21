#############################################
# Telephony Robolectric test target. #
#############################################
LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-java-files-under, src)
LOCAL_PRIVILEGED_MODULE := true

# Include the testing libraries (JUnit4 + Robolectric libs).
LOCAL_STATIC_JAVA_LIBRARIES := \
    platform-robolectric-android-all-stubs \
    android-support-test \
    mockito-robolectric-prebuilt \
    platform-test-annotations \
    truth-prebuilt \
    testng

LOCAL_JAVA_LIBRARIES := \
    junit \
    platform-robolectric-3.6.1-prebuilt \
    telephony-common \
    sdk_vcurrent

LOCAL_INSTRUMENTATION_FOR := TeleService
LOCAL_MODULE := TeleRobo

LOCAL_MODULE_TAGS := optional

include $(BUILD_STATIC_JAVA_LIBRARY)

#############################################################
# Telephony runner target to run the previous target. #
#############################################################
include $(CLEAR_VARS)

LOCAL_MODULE := TelephonyRoboTests

LOCAL_SDK_VERSION := current

LOCAL_STATIC_JAVA_LIBRARIES := \
    TeleRobo \

LOCAL_TEST_PACKAGE := TeleService

include prebuilts/misc/common/robolectric/3.6.1/run_robotests.mk