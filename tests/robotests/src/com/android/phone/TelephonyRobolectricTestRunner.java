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

import android.annotation.NonNull;

import java.net.MalformedURLException;
import java.net.URL;

import org.junit.runners.model.InitializationError;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.manifest.AndroidManifest;
import org.robolectric.res.Fs;
import org.robolectric.res.ResourcePath;

import java.util.List;

public class TelephonyRobolectricTestRunner extends RobolectricTestRunner {

  public TelephonyRobolectricTestRunner(Class<?> testClass) throws InitializationError {
    super(testClass);
  }

  /**
   * We are going to create our own custom manifest so we can add multiple resource paths to it.
   */
  @Override
  protected AndroidManifest getAppManifest(Config config) {
    try {
      // Using the manifest file's relative path, we can figure out the application directory.
      final URL appRoot = new URL("file:packages/services/Telephony/");
      final URL manifestPath = new URL(appRoot, "AndroidManifest.xml");
      final URL resDir = new URL(appRoot, "res");
      final URL assetsDir = new URL(appRoot, "assets");

      // By adding any resources from libraries we need to the AndroidManifest, we can access
      // them from within the parallel universe's resource loader.
      return new AndroidManifest(Fs.fromURL(manifestPath), Fs.fromURL(resDir),
          Fs.fromURL(assetsDir), "com.android.phone") {
        @Override
        public List<ResourcePath> getIncludedResourcePaths() {
          final List<ResourcePath> paths = super.getIncludedResourcePaths();
          paths.add(resourcePath(appRoot, "src/com/android/phone/assisteddialing/res"));
          paths.add(resourcePath(appRoot, "src/com/android/phone/settings/assisteddialing/res"));
          return paths;
        }
      };
    } catch (MalformedURLException e) {
      throw new RuntimeException("TelephonyRobolectricTestRunner failure", e);
    }
  }

  private static ResourcePath resourcePath(@NonNull URL appRoot, @NonNull String spec) {
    try {
      return new ResourcePath(null, Fs.fromURL(new URL(appRoot, spec)), null);
    } catch (MalformedURLException e) {
      throw new RuntimeException("TelephonyRobolectricTestRunner failure", e);
    }
  }
}
