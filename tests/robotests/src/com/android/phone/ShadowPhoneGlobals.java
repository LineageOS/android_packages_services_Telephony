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

import android.os.Build;

import com.android.i18n.phonenumbers.PhoneNumberUtil;

import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

@Implements(PhoneGlobals.class)
public class ShadowPhoneGlobals {

  private final PhoneNumberUtil mPhoneNumberUtil = PhoneNumberUtil.getInstance();

  @Implementation
  public static PhoneGlobals getInstance() {
    return new PhoneGlobals(RuntimeEnvironment.application);
  }

  @Implementation(minSdk = Build.VERSION_CODES.P)
  public PhoneNumberUtil getPhoneNumberUtil() {
    return mPhoneNumberUtil;
  }
}