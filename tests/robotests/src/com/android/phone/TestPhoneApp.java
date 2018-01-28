/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.app.Application;

/**
 * TestPhoneApp replaces {@link PhoneApp} when TelephonyService robolectric tests are executed.
 */
@SuppressWarnings("unused")
public class TestPhoneApp extends Application {

  @Override
  public void onCreate() {
    // Obscure the call to PhoneApp.onCreate(). It triggers a lot of static initialization that:
    // a) does not like to execute more than once in a process
    // b) relies on many values found in Android that would have to be mocked
  }
}
