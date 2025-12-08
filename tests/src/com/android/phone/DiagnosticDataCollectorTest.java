/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.os.DropBoxManager;
import android.os.SystemClock;
import android.telephony.TelephonyManager;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.telephony.flags.Flags;
import com.android.phone.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Unit Tests for DiagnosticDataCollector.
 */
@RunWith(AndroidJUnit4.class)
public class DiagnosticDataCollectorTest {
    private static final String[] TELECOM_DUMPSYS_COMMAND =
            {"/system/bin/dumpsys", "telecom", "EmergencyDiagnostics"};
    private static final String[] TELEPHONY_DUMPSYS_COMMAND =
            {"/system/bin/dumpsys", "telephony.registry", "EmergencyDiagnostics"};
    private static final String[] LOGCAT_BINARY = {"/system/bin/logcat"};
    private static final long LOG_TIME_OFFSET_MILLIS = 75L;
    private final SimpleDateFormat mDateFormat = new SimpleDateFormat("MM-dd HH:mm:ss.SSS",
            Locale.US);
    private static final String[] LOGCAT_PID_NAMES = {"com.android.phone",
            "com.android.phone.extra"};
    private static final String[] LOGCAT_TAGS = {"Telecom1", "Telecom2", "Telecom3"};


    @Mock
    DataCollectorConfig.Adapter mConfig;
    private Runtime mRuntime;

    @Mock
    private DropBoxManager mDropBoxManager;
    @Mock
    private ActivityManager mMockActivityManager;

    private DiagnosticDataCollector mDiagnosticDataCollector;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mRuntime = spy(Runtime.getRuntime());
        mDiagnosticDataCollector = new DiagnosticDataCollector(mRuntime, Runnable::run,
                mDropBoxManager, false, mMockActivityManager, LOGCAT_PID_NAMES, LOGCAT_TAGS);
    }

    @After
    public void tearDown() throws Exception {
    }

    private void verifyCmdAndDropboxTag(String[] cmd, String tag, boolean startsWithMatch)
            throws InterruptedException, IOException {
        ArgumentCaptor<String[]> textArrayCaptor = ArgumentCaptor.forClass(String[].class);

        //verify cmd passed to runtime
        verify(mRuntime).exec(textArrayCaptor.capture());
        String[] argList = textArrayCaptor.getValue();
        if (startsWithMatch) {
            assertEquals(cmd[0], argList[0]);
        } else {
            assertEquals(Arrays.toString(cmd), Arrays.toString(argList));
        }
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);

        //make sure logcat output does not have errors
        verify(mDropBoxManager, times(1)).addText(eq(tag), textCaptor.capture());
        assertFalse(textCaptor.getValue().contains(DiagnosticDataCollector.ERROR_MSG));
    }

    @Test
    public void testPersistForTelecomDumpsys() throws IOException, InterruptedException {
        TelephonyManager.EmergencyCallDiagnosticData.Builder callDiagnosticBuilder =
                new TelephonyManager.EmergencyCallDiagnosticData.Builder();
        TelephonyManager.EmergencyCallDiagnosticData ecdData =
                callDiagnosticBuilder.setTelecomDumpsysCollectionEnabled(true).build();
        mDiagnosticDataCollector.persistEmergencyDianosticData(
                mConfig, ecdData, /* tag= */ "");

        verifyCmdAndDropboxTag(TELECOM_DUMPSYS_COMMAND,
                DiagnosticDataCollector.DROPBOX_TAG, false);
    }

    @Test
    public void testPersistForTelephonyDumpsys() throws IOException, InterruptedException {
        TelephonyManager.EmergencyCallDiagnosticData.Builder callDiagnosticBuilder =
                new TelephonyManager.EmergencyCallDiagnosticData.Builder();
        TelephonyManager.EmergencyCallDiagnosticData ecdData =
                callDiagnosticBuilder.setTelephonyDumpsysCollectionEnabled(true).build();
        mDiagnosticDataCollector.persistEmergencyDianosticData(
                mConfig, ecdData, /* tag= */ "");

        verifyCmdAndDropboxTag(TELEPHONY_DUMPSYS_COMMAND,
                DiagnosticDataCollector.DROPBOX_TAG, false);
    }

    @Test
    public void testPersistForLogcat() throws IOException, InterruptedException {
        TelephonyManager.EmergencyCallDiagnosticData.Builder callDiagnosticBuilder =
                new TelephonyManager.EmergencyCallDiagnosticData.Builder();
        TelephonyManager.EmergencyCallDiagnosticData ecdData =
                callDiagnosticBuilder.setLogcatCollectionStartTimeMillis(
                        SystemClock.elapsedRealtime()).build();
        mDiagnosticDataCollector.persistEmergencyDianosticData(
                mConfig, ecdData, /* tag= */ "");

        ArgumentCaptor<String[]> textArrayCaptor = ArgumentCaptor.forClass(String[].class);
        if (Flags.enableOemLogSourcesCollection()) {
            verify(mRuntime, times(2)).exec(textArrayCaptor.capture());
        } else {
            verify(mRuntime, times(1)).exec(textArrayCaptor.capture());
        }
        String[] argList = textArrayCaptor.getAllValues().get(0);
        assertEquals(LOGCAT_BINARY[0], argList[0]);

        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(mDropBoxManager, times(1)).addText(eq(
                DiagnosticDataCollector.DROPBOX_TAG), textCaptor.capture());
        assertFalse(textCaptor.getValue().contains(DiagnosticDataCollector.ERROR_MSG));
    }

    @Test
    public void testPersistForLogcatByPidForOemLogSources() throws
        IOException, InterruptedException {
        // RequiresFlagsDisabled won't work on an unexported flag, so we need
        // to manually check the flag here.
        if (!Flags.enableOemLogSourcesCollection()) {
            return;
        }

        TelephonyManager.EmergencyCallDiagnosticData.Builder
                callDiagnosticBuilder = new
                    TelephonyManager.EmergencyCallDiagnosticData.Builder();
        long logcatStartTime = SystemClock.elapsedRealtime();
        TelephonyManager.EmergencyCallDiagnosticData ecdData =
                callDiagnosticBuilder.setLogcatCollectionStartTimeMillis(
                        logcatStartTime).build();
        int[] logcatPids = new int[] {100, 200};
        when(mMockActivityManager.getRunningAppProcesses())
                .thenReturn(List.of(
                    createRunningAppProcessInfo("com.android.phone",
                    logcatPids[0]),
                    createRunningAppProcessInfo("com.android.phone.extra",
                    logcatPids[1]),
                    createRunningAppProcessInfo("com.android.ignore1", 300),
                    createRunningAppProcessInfo("com.android.ignore2", 400)
                ));
        String startTime = mDateFormat.format(new Date(
                logcatStartTime - LOG_TIME_OFFSET_MILLIS));
        ArrayList<String[]> expectedLogcatCommands = new ArrayList<>();
        for (int pid : logcatPids) {
            String[] expectedLogcatCommandPids = {"/system/bin/logcat","-t", startTime, "-b",
                    "all", "--pid", String.valueOf(pid)};
            expectedLogcatCommands.add(expectedLogcatCommandPids);
        }
        String[] expectedLogcatCommandTags = {"/system/bin/logcat","-t", startTime, "-b",
                "all", "Telecom1:D Telecom2:D Telecom3:D"};
        expectedLogcatCommands.add(expectedLogcatCommandTags);

        // should not use tag in param for logcat oem sources
        mDiagnosticDataCollector.persistEmergencyDianosticData(
                mConfig, ecdData, /* tag= */ "");

        ArgumentCaptor<String[]> textArrayCaptor = ArgumentCaptor.forClass(String[].class);
        // 3 oem sources + 1 for old logcat
        verify(mRuntime, times(4)).exec(textArrayCaptor.capture());
        // verify all oem logcat commands are present, regardless of the order
        for (int i = 0; i < expectedLogcatCommands.size(); i++) {
            boolean containsLogcatCommand = false;
            for (String[] cmd : textArrayCaptor.getAllValues()) {
                if (Arrays.equals(expectedLogcatCommands.get(i), cmd)) {
                    containsLogcatCommand = true;
                    break;
                }
            }
            assertTrue(containsLogcatCommand);
        }
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(mDropBoxManager, times(3)).addText(
            eq(DiagnosticDataCollector.DROPBOX_TAG_FOR_OEM), textCaptor.capture());
        textCaptor.getAllValues().stream().forEach(
            text -> assertFalse(text.contains(DiagnosticDataCollector.ERROR_MSG)));
    }

  private RunningAppProcessInfo createRunningAppProcessInfo(String processName,
            int pid) {
        RunningAppProcessInfo runningAppProcessInfo =
                new RunningAppProcessInfo();
        runningAppProcessInfo.processName = processName;
        runningAppProcessInfo.pid = pid;
        return runningAppProcessInfo;
    }
}