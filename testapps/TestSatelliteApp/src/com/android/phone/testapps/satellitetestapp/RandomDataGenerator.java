/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.phone.testapps.satellitetestapp;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Random;

public class RandomDataGenerator {

    public void generateRandomData(String fileName, int targetSize) {
        String filePath = getFilesDir() + fileName;
        int targetSizeKB = targetSize;
        long targetSizeBytes = targetSizeKB * 1024;
        byte[] buffer = new byte[1024]; // Write in 1KB chunks for efficiency
        Random random = new Random();
        long bytesWritten = 0;

        try (FileOutputStream fos = new FileOutputStream(filePath)) {
            while (bytesWritten < targetSizeBytes) {
                random.nextBytes(buffer);
                long remainingBytes = targetSizeBytes - bytesWritten;
                int bytesToWrite = (int) Math.min(buffer.length, remainingBytes);
                fos.write(buffer, 0, bytesToWrite);
                bytesWritten += bytesToWrite;
            }
            System.out.println(
                    "Successfully generated "
                            + bytesWritten / 1024
                            + "KB of random data at: "
                            + filePath);
        } catch (IOException e) {
            e.printStackTrace();
            System.err.println("Error writing random data to file: " + e.getMessage());
        }
    }

    public static File getFilesDir() {
        return new java.io.File(System.getProperty("java.io.tmpdir"));
    }
}
