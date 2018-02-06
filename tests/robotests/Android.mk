##############################################
# Compile TeleService robolectric tests
##############################################
LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE := TeleService_robotests

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_JAVA_RESOURCE_DIRS := res

LOCAL_JAVA_LIBRARIES := \
  robolectric_android-all-stub \
  Robolectric_all-target \
  truth-prebuilt

LOCAL_INSTRUMENTATION_FOR := TeleService

include $(BUILD_STATIC_JAVA_LIBRARY)

##############################################
# Execute TeleService robolectric tests
##############################################
include $(CLEAR_VARS)

LOCAL_MODULE := Run_TeleService_robotests

LOCAL_TEST_PACKAGE := TeleService

LOCAL_JAVA_LIBRARIES := \
  TeleService_robotests \
  robolectric_android-all-stub \
  Robolectric_all-target \
  truth-prebuilt

LOCAL_ROBOTEST_FILES := $(filter-out %/BaseRobolectricTest.java,\
  $(call find-files-in-subdirs, $(LOCAL_PATH)/src, *Test.java, .))

include external/robolectric-shadows/run_robotests.mk